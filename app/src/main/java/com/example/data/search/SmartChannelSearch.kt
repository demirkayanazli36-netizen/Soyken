package com.example.data.search

import com.example.data.model.Channel
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class IndexedChannel(
    val channel: Channel,
    val normalizedName: String,
    val collapsedName: String,
    val coreCollapsedName: String,
    val tokens: List<String>,
    val coreTokens: List<String>,
    val consonantSkeleton: String,
    val normalizedCategory: String,
    val categoryTokens: List<String>,
    val channelNumberStr: String,
    val trigrams: Set<String>
)

data class SmartSearchSplit(
    val directMatches: List<Channel> = emptyList(),
    val similarOptions: List<Channel> = emptyList(),
    val suggestedTerms: List<String> = emptyList(),
    val crossCategoryFallbackUsed: Boolean = false
) {
    val allResults: List<Channel>
        get() = if (similarOptions.isEmpty()) {
            directMatches
        } else if (directMatches.isEmpty()) {
            similarOptions
        } else {
            val directIds = directMatches.asSequence().map { it.id }.toHashSet()
            directMatches + similarOptions.filterNot { it.id in directIds }
        }
}

object SmartChannelSearch {

    private val NOISE_STREAM_TAGS = setOf(
        "hd", "fhd", "uhd", "sd", "4k", "8k", "tr", "turkce", "dublaj", "altyazili",
        "hevc", "h265", "h264", "raw", "vip", "plus", "yedek", "backup", "live", "tv"
    )

    private val ROMAN_TO_ARABIC = mapOf(
        "ii" to "2",
        "iii" to "3",
        "iv" to "4",
        "v" to "5",
        "vi" to "6",
        "vii" to "7",
        "viii" to "8",
        "ix" to "9",
        "x" to "10"
    )

    // Deterministic local synonym & bilingual title aliases (no cloud/AI dependency)
    private val LOCAL_ALIAS_GROUPS: List<Set<String>> = listOf(
        setOf("film", "filmler", "sinema", "movie", "movies", "cinema", "vod"),
        setOf("dizi", "diziler", "series", "sezon", "bolum"),
        setOf("spor", "sport", "sports", "futbol", "mac", "basketbol"),
        setOf("haber", "haberler", "news", "gundem"),
        setOf("cocuk", "kids", "cizgi film", "animasyon", "cartoon", "anime"),
        setOf("belgesel", "documentary", "docu", "doga", "tarih", "bilim"),
        setOf("ulusal", "genel", "turkiye", "yerel"),
        setOf("muzik", "music", "radyo", "klip"),
        setOf("komedi", "eglence", "comedy", "standup"),
        setOf("aksiyon", "macera", "action", "adventure"),
        setOf("korku", "gerilim", "horror", "thriller"),
        setOf("bilim kurgu", "scifi", "sci fi", "fantastik"),
        setOf("orumcek adam", "spider man", "spiderman"),
        setOf("yuzuklerin efendisi", "lord of the rings", "lotr"),
        setOf("hizli ve ofkeli", "fast and furious", "fast furious"),
        setOf("karayip korsanlari", "pirates of the caribbean"),
        setOf("yildiz savaslari", "star wars"),
        setOf("kara sovalye", "batman", "dark knight"),
        setOf("yenilmezler", "avengers"),
        setOf("buz devri", "ice age"),
        setOf("gorevimiz tehlike", "mission impossible"),
        setOf("harry potter", "felsefe tasi", "sirlar odasi", "azkaban tutsagi")
    )

    private val NON_ALPHANUMERIC_REGEX = Regex("[^a-z0-9]+")
    private val COMBINING_MARKS_REGEX = Regex("\\p{Mn}+")

    /**
     * Normalizes Turkish & Latin characters, removes all punctuation/symbols,
     * collapses whitespace, and converts Roman numeral suffixes to digits.
     */
    fun normalizeForSearch(input: String): String {
        if (input.isBlank()) return ""

        val folded = buildString(input.length) {
            for (ch in input) {
                when (ch) {
                    'İ', 'I', 'ı', 'i', 'î', 'Î', 'ï', 'Ï' -> append('i')
                    'Ş', 'ş' -> append('s')
                    'Ğ', 'ğ' -> append('g')
                    'Ü', 'ü', 'û', 'Û' -> append('u')
                    'Ö', 'ö' -> append('o')
                    'Ç', 'ç' -> append('c')
                    'Â', 'â', 'ä', 'Ä' -> append('a')
                    'Ê', 'ê', 'é', 'É', 'è', 'È' -> append('e')
                    else -> append(ch.lowercaseChar())
                }
            }
        }

        val deaccented = Normalizer.normalize(folded, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS_REGEX, "")
            .lowercase(Locale.ROOT)

        val rawTokens = deaccented
            .replace(NON_ALPHANUMERIC_REGEX, " ")
            .trim()
            .split(' ')
            .filter { it.isNotEmpty() }

        if (rawTokens.isEmpty()) return ""

        val normalizedTokens = rawTokens.mapIndexed { index, token ->
            // Convert trailing/standalone Roman numerals (e.g., "Recep İvedik VI" -> "recep ivedik 6")
            if (index > 0 && token in ROMAN_TO_ARABIC) {
                ROMAN_TO_ARABIC.getValue(token)
            } else {
                token
            }
        }

        return normalizedTokens.joinToString(" ")
    }

    fun buildConsonantSkeleton(collapsed: String): String {
        if (collapsed.isEmpty()) return ""
        val sb = StringBuilder(collapsed.length)
        var prev = '\u0000'
        for (ch in collapsed) {
            val mapped = when (ch) {
                'a', 'e', 'i', 'o', 'u' -> continue
                'w' -> 'v'
                'q' -> 'k'
                else -> ch
            }
            if (mapped != prev) {
                sb.append(mapped)
                prev = mapped
            }
        }
        return sb.toString()
    }

    private fun buildTrigrams(text: String): Set<String> {
        if (text.isEmpty()) return emptySet()
        val padded = " $text "
        if (padded.length < 3) return setOf(padded)
        val result = HashSet<String>(padded.length - 2)
        for (i in 0..padded.length - 3) {
            result.add(padded.substring(i, i + 3))
        }
        return result
    }

    fun buildIndex(channels: List<Channel>): List<IndexedChannel> {
        return channels.map { indexChannel(it) }
    }

    fun indexChannel(channel: Channel): IndexedChannel {
        val normName = normalizeForSearch(channel.name)
        val tokens = if (normName.isEmpty()) emptyList() else normName.split(' ')
        val collapsed = tokens.joinToString("")
        val coreTokens = tokens.filterNot { it in NOISE_STREAM_TAGS && tokens.size > 1 }
            .ifEmpty { tokens }
        // Keep "tv" when followed by a number like "TV 8,5 HD" -> core "tv85"
        val smartCoreTokens = if (tokens.firstOrNull() == "tv" && tokens.getOrNull(1)?.firstOrNull()?.isDigit() == true) {
            tokens.filterNot { it in NOISE_STREAM_TAGS && it != "tv" }
        } else {
            coreTokens
        }
        val coreCollapsed = smartCoreTokens.joinToString("")
        val normCat = normalizeForSearch(channel.category)
        val catTokens = if (normCat.isEmpty()) emptyList() else normCat.split(' ')
        val skeleton = buildConsonantSkeleton(coreCollapsed.ifEmpty { collapsed })
        val trigramSource = if (normCat.isNotEmpty()) "$normName $normCat" else normName

        return IndexedChannel(
            channel = channel,
            normalizedName = normName,
            collapsedName = collapsed,
            coreCollapsedName = coreCollapsed.ifEmpty { collapsed },
            tokens = tokens,
            coreTokens = smartCoreTokens.ifEmpty { tokens },
            consonantSkeleton = skeleton,
            normalizedCategory = normCat,
            categoryTokens = catTokens,
            channelNumberStr = channel.channelNumber.toString(),
            trigrams = buildTrigrams(trigramSource)
        )
    }

    fun localSemanticAliasesForQuery(rawQuery: String): List<String> {
        val norm = normalizeForSearch(rawQuery)
        if (norm.isEmpty()) return emptyList()
        val queryTokens = norm.split(' ').toSet()
        val results = LinkedHashSet<String>()

        for (group in LOCAL_ALIAS_GROUPS) {
            val matchesGroup = group.any { alias ->
                alias == norm ||
                    (alias !in setOf("tv", "hd") && alias in queryTokens) ||
                    (norm.length >= 5 && alias.length >= 5 && normalizedEditSimilarity(norm, alias) >= 0.82)
            }
            if (matchesGroup) {
                for (alias in group) {
                    if (alias != norm) {
                        results.add(alias)
                    }
                }
            }
        }
        return results.toList()
    }

    fun searchChannels(
        allPlaylistChannels: List<Channel>,
        selectedCategory: String,
        rawQuery: String
    ): SmartSearchSplit {
        return searchIndexedChannels(
            indexedPlaylistChannels = buildIndex(allPlaylistChannels),
            selectedCategory = selectedCategory,
            rawQuery = rawQuery
        )
    }

    fun searchIndexedChannels(
        indexedPlaylistChannels: List<IndexedChannel>,
        selectedCategory: String,
        rawQuery: String
    ): SmartSearchSplit {
        if (indexedPlaylistChannels.isEmpty()) {
            return SmartSearchSplit()
        }

        val categoryFiltered = filterByCategory(indexedPlaylistChannels, selectedCategory)

        if (rawQuery.isBlank()) {
            return SmartSearchSplit(
                directMatches = categoryFiltered.map { it.channel }
            )
        }

        val normQuery = normalizeForSearch(rawQuery)
        if (normQuery.isEmpty()) {
            return SmartSearchSplit(
                directMatches = categoryFiltered.map { it.channel }
            )
        }

        val aliases = localSemanticAliasesForQuery(rawQuery)

        // Score within the selected category first
        val primaryEvaluation = evaluateCandidates(
            candidates = categoryFiltered,
            rawQuery = rawQuery,
            normQuery = normQuery,
            aliases = aliases
        )

        // If user is inside a specific category (not "Tümü") and no strong match was found,
        // automatically search across all playlist categories so the user still finds their movie/channel!
        if (selectedCategory != "Tümü" && primaryEvaluation.directMatches.isEmpty()) {
            val globalEvaluation = evaluateCandidates(
                candidates = indexedPlaylistChannels,
                rawQuery = rawQuery,
                normQuery = normQuery,
                aliases = aliases
            )
            if (globalEvaluation.directMatches.isNotEmpty() ||
                globalEvaluation.topScore > primaryEvaluation.topScore + 0.08
            ) {
                return SmartSearchSplit(
                    directMatches = globalEvaluation.directMatches,
                    similarOptions = globalEvaluation.similarOptions,
                    suggestedTerms = buildSuggestedTerms(indexedPlaylistChannels, normQuery, aliases, globalEvaluation),
                    crossCategoryFallbackUsed = true
                )
            }
        }

        return SmartSearchSplit(
            directMatches = primaryEvaluation.directMatches,
            similarOptions = primaryEvaluation.similarOptions,
            suggestedTerms = buildSuggestedTerms(
                candidates = categoryFiltered.ifEmpty { indexedPlaylistChannels },
                normQuery = normQuery,
                aliases = aliases,
                evaluation = primaryEvaluation
            ),
            crossCategoryFallbackUsed = false
        )
    }

    private data class ScoredItem(
        val indexed: IndexedChannel,
        val score: Double
    )

    private data class CandidateEvaluation(
        val directMatches: List<Channel>,
        val similarOptions: List<Channel>,
        val scoredItems: List<ScoredItem>,
        val topScore: Double
    )

    private fun filterByCategory(
        channels: List<IndexedChannel>,
        selectedCategory: String
    ): List<IndexedChannel> {
        return when (selectedCategory) {
            "Tümü" -> channels
            "⭐ Favoriler" -> channels.filter { it.channel.isFavorite }
            else -> channels.filter { it.channel.category.equals(selectedCategory, ignoreCase = true) }
        }
    }

    private fun evaluateCandidates(
        candidates: List<IndexedChannel>,
        rawQuery: String,
        normQuery: String,
        aliases: List<String>
    ): CandidateEvaluation {
        if (candidates.isEmpty()) {
            return CandidateEvaluation(emptyList(), emptyList(), emptyList(), 0.0)
        }

        val queryPrep = prepareQuery(rawQuery, normQuery)
        val aliasPreps = aliases.take(8).map { prepareQuery(it, it) }

        val scored = candidates.map { item ->
            var bestScore = scorePreparedQuery(item, queryPrep)
            for (aliasPrep in aliasPreps) {
                val aliasScore = scorePreparedQuery(item, aliasPrep) * 0.92
                if (aliasScore > bestScore) {
                    bestScore = aliasScore
                }
            }
            ScoredItem(item, bestScore)
        }.sortedWith(
            compareByDescending<ScoredItem> { it.score }
                .thenByDescending { it.indexed.channel.isFavorite }
                .thenBy { it.indexed.channel.channelNumber }
                .thenBy { it.indexed.channel.name }
        )

        val topScore = scored.firstOrNull()?.score ?: 0.0
        val directThreshold = if (queryPrep.collapsed.length <= 2) 0.78 else 0.68
        val similarFloor = when {
            queryPrep.collapsed.length <= 2 -> 0.48
            queryPrep.collapsed.length <= 4 -> 0.36
            else -> 0.32
        }

        val directItems = scored.filter { it.score >= directThreshold }
        val directIds = directItems.asSequence().map { it.indexed.channel.id }.toHashSet()

        val similarList = ArrayList<Channel>(12)
        val addedSimilarIds = HashSet<Long>()

        // 1. Fuzzy & phonetically close candidates right below the direct threshold
        for (entry in scored) {
            if (entry.indexed.channel.id in directIds) continue
            if (entry.score >= similarFloor) {
                if (addedSimilarIds.add(entry.indexed.channel.id)) {
                    similarList.add(entry.indexed.channel)
                }
            }
            if (similarList.size >= 10) break
        }

        // 2. Same-category or shared-keyword sibling channels/movies for top direct matches
        //    so searching e.g. a channel/movie also surfaces closely related options!
        if (directItems.isNotEmpty() && similarList.size < 8) {
            val topDirectCategories = directItems.take(3)
                .map { it.indexed.normalizedCategory }
                .filter { it.isNotEmpty() }
                .toSet()
            val topDirectTokens = directItems.take(2)
                .flatMap { it.indexed.coreTokens }
                .filter { it.length >= 3 }
                .toSet()

            val siblings = candidates
                .asSequence()
                .filter { it.channel.id !in directIds && it.channel.id !in addedSimilarIds }
                .map { candidate ->
                    var siblingScore = 0.0
                    if (candidate.normalizedCategory in topDirectCategories) {
                        siblingScore += 0.45
                    }
                    val sharedTokenCount = candidate.coreTokens.count { it in topDirectTokens }
                    if (sharedTokenCount > 0) {
                        siblingScore += 0.35 * min(2, sharedTokenCount)
                    }
                    if (candidate.channel.isFavorite) {
                        siblingScore += 0.08
                    }
                    ScoredItem(candidate, siblingScore)
                }
                .filter { it.score >= 0.40 }
                .sortedWith(
                    compareByDescending<ScoredItem> { it.score }
                        .thenBy { it.indexed.channel.channelNumber }
                )
                .take(8 - similarList.size)

            for (sib in siblings) {
                if (addedSimilarIds.add(sib.indexed.channel.id)) {
                    similarList.add(sib.indexed.channel)
                }
            }
        }

        // 3. If neither direct nor medium-fuzzy matched (e.g. heavy misspelling or channel missing from a small playlist),
        //    offer the closest ranked candidates so the user always gets helpful nearby alternatives!
        if (directItems.isEmpty() && similarList.isEmpty() && scored.isNotEmpty()) {
            val fallbackCandidates = scored
                .filter { it.score >= 0.14 || candidates.size <= 15 }
                .take(6)
            for (entry in fallbackCandidates) {
                if (addedSimilarIds.add(entry.indexed.channel.id)) {
                    similarList.add(entry.indexed.channel)
                }
            }
        }

        return CandidateEvaluation(
            directMatches = directItems.map { it.indexed.channel },
            similarOptions = similarList,
            scoredItems = scored,
            topScore = topScore
        )
    }

    private fun buildSuggestedTerms(
        candidates: List<IndexedChannel>,
        normQuery: String,
        aliases: List<String>,
        evaluation: CandidateEvaluation
    ): List<String> {
        if (normQuery.length < 2) return emptyList()
        val suggestions = LinkedHashSet<String>()
        val seenNorm = HashSet<String>()
        seenNorm.add(normQuery)

        // 1. Add top close-but-not-identical channel names or categories when user had a typo or partial query
        val sourceItems = (evaluation.directMatches.take(2) + evaluation.similarOptions.take(4))
        for (ch in sourceItems) {
            val cleanTitle = cleanDisplayTitle(ch.name)
            val normTitle = normalizeForSearch(cleanTitle)
            if (normTitle.isNotEmpty() && seenNorm.add(normTitle)) {
                suggestions.add(cleanTitle)
            }
            if (suggestions.size >= 3) break
        }

        // 2. Add matching category / alias chips present in the playlist
        if (suggestions.size < 4) {
            val availableCategories = candidates.map { it.channel.category }.distinct()
            for (cat in availableCategories) {
                val normCat = normalizeForSearch(cat)
                if (normCat.isNotEmpty() && seenNorm.add(normCat)) {
                    val catMatchesAlias = aliases.any { normCat.contains(it) || it.contains(normCat) }
                    val catSim = trigramSimilarity(buildTrigrams(normQuery), buildTrigrams(normCat))
                    if (catMatchesAlias || catSim >= 0.35) {
                        suggestions.add(cat)
                    }
                }
                if (suggestions.size >= 4) break
            }
        }

        return suggestions.take(4)
    }

    private fun cleanDisplayTitle(rawName: String): String {
        val trimmed = rawName
            .replace(Regex("\\[[^]]*]"), " ")
            .replace(Regex("\\([^)]*\\)"), " ")
            .replace(Regex("(?i)\\b(FHD|UHD|HD|SD|4K|HEVC|TR|RAW|VIP)\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return trimmed.ifEmpty { rawName.trim() }
    }

    private data class PreparedQuery(
        val rawTrimmedDigits: String?,
        val normQuery: String,
        val collapsed: String,
        val tokens: List<String>,
        val consonantSkeleton: String,
        val trigrams: Set<String>
    )

    private fun prepareQuery(rawQuery: String, normQuery: String): PreparedQuery {
        val rawTrimmed = rawQuery.trim().trimStart('#')
        val digitsOnly = rawTrimmed.takeIf { it.isNotEmpty() && it.all { ch -> ch.isDigit() } }
        val tokens = if (normQuery.isEmpty()) emptyList() else normQuery.split(' ')
        val collapsed = tokens.joinToString("")
        return PreparedQuery(
            rawTrimmedDigits = digitsOnly,
            normQuery = normQuery,
            collapsed = collapsed,
            tokens = tokens,
            consonantSkeleton = buildConsonantSkeleton(collapsed),
            trigrams = buildTrigrams(normQuery)
        )
    }

    fun scoreIndexedChannelAgainstQuery(item: IndexedChannel, rawQuery: String): Double {
        val norm = normalizeForSearch(rawQuery)
        if (norm.isEmpty()) return 0.0
        return scorePreparedQuery(item, prepareQuery(rawQuery, norm))
    }

    private fun scorePreparedQuery(item: IndexedChannel, q: PreparedQuery): Double {
        if (q.collapsed.isEmpty()) return 0.0

        // 1. Exact channel number match
        if (q.rawTrimmedDigits != null) {
            if (item.channelNumberStr == q.rawTrimmedDigits) {
                return 1.0
            }
            if (item.channelNumberStr.startsWith(q.rawTrimmedDigits) && q.rawTrimmedDigits.length >= 2) {
                return 0.78
            }
        }

        var best = 0.0

        // 2. Exact & substring matches on punctuation-stripped & collapsed strings
        if (item.normalizedName == q.normQuery ||
            item.coreCollapsedName == q.collapsed ||
            item.collapsedName == q.collapsed
        ) {
            return 1.0
        }

        if (item.coreCollapsedName.startsWith(q.collapsed) || item.collapsedName.startsWith(q.collapsed)) {
            val ratio = q.collapsed.length.toDouble() / max(1, item.coreCollapsedName.length).toDouble()
            best = max(best, 0.90 + 0.08 * ratio.coerceIn(0.0, 1.0))
        } else if (q.collapsed.length >= 2 &&
            (item.coreCollapsedName.contains(q.collapsed) || item.collapsedName.contains(q.collapsed))
        ) {
            val ratio = q.collapsed.length.toDouble() / max(1, item.coreCollapsedName.length).toDouble()
            best = max(best, 0.80 + 0.12 * ratio.coerceIn(0.0, 1.0))
        } else if (item.coreCollapsedName.length >= 4 && q.collapsed.contains(item.coreCollapsedName)) {
            best = max(best, 0.82)
        }

        // 3. Category match (e.g. searching "belgesel", "spor", "sinema")
        if (item.normalizedCategory.isNotEmpty()) {
            if (item.normalizedCategory == q.normQuery) {
                best = max(best, 0.88)
            } else if (q.normQuery.length >= 3 && item.normalizedCategory.contains(q.normQuery)) {
                best = max(best, 0.78)
            }
        }

        // 4. Consonant skeleton match (e.g. "knl d" -> "knld" == "Kanal D" -> "knld", "hbrtrk" -> "Habertürk")
        if (q.consonantSkeleton.length >= 3 && item.consonantSkeleton.isNotEmpty()) {
            when {
                item.consonantSkeleton == q.consonantSkeleton -> {
                    best = max(best, 0.86)
                }
                item.consonantSkeleton.startsWith(q.consonantSkeleton) ||
                    (q.consonantSkeleton.length >= 4 && item.consonantSkeleton.contains(q.consonantSkeleton)) -> {
                    best = max(best, 0.76)
                }
                else -> {
                    val skelSim = normalizedEditSimilarity(q.consonantSkeleton, item.consonantSkeleton)
                    if (skelSim >= 0.78) {
                        best = max(best, skelSim * 0.84)
                    }
                }
            }
        }

        // 5. Order-independent token fuzzy matching (handles typos & swapped word order like "pusu kurlar" -> "Kurtlar Vadisi: Pusu")
        val allTargetTokens = if (item.categoryTokens.isEmpty()) {
            item.tokens
        } else {
            item.tokens + item.categoryTokens
        }
        if (q.tokens.isNotEmpty() && allTargetTokens.isNotEmpty()) {
            var tokenScoreSum = 0.0
            var minTokenScore = 1.0
            for (qt in q.tokens) {
                var bestForQt = 0.0
                for (tt in item.tokens) {
                    val s = scoreSingleToken(qt, tt)
                    if (s > bestForQt) bestForQt = s
                }
                // Category tokens match when user typed the category (or prefix/typo of it),
                // while compound proper names starting with a category word (e.g. "habertuk" vs "haber")
                // score as similar options (~0.52) rather than direct matches.
                for (ct in item.categoryTokens) {
                    val s = scoreCategoryToken(qt, ct)
                    if (s > bestForQt) bestForQt = s
                }
                tokenScoreSum += bestForQt
                if (bestForQt < minTokenScore) minTokenScore = bestForQt
            }
            val avgTokenScore = tokenScoreSum / q.tokens.size.toDouble()
            // Blend average & weakest token so multi-word queries strongly reward matching all typed words
            val combinedTokenScore = if (q.tokens.size == 1) {
                avgTokenScore * 0.94
            } else {
                (avgTokenScore * 0.72 + minTokenScore * 0.28)
            }
            best = max(best, combinedTokenScore)
        }

        // 6. Whole-title edit similarity (catches space-split differences & transpositions)
        if (item.coreCollapsedName.isNotEmpty() && abs(item.coreCollapsedName.length - q.collapsed.length) <= 8) {
            val wholeEdit = normalizedEditSimilarity(q.collapsed, item.coreCollapsedName)
            best = max(best, wholeEdit * 0.95)
        }

        // 7. Character trigram Dice similarity & ordered subsequence ratio for broad "similar options" discovery
        val trigramSim = trigramSimilarity(q.trigrams, item.trigrams)
        best = max(best, trigramSim * 0.85)

        if (q.collapsed.length >= 3 && item.coreCollapsedName.isNotEmpty()) {
            val subseqRatio = orderedSubsequenceScore(q.collapsed, item.coreCollapsedName)
            best = max(best, subseqRatio * 0.62)
        }

        return best.coerceIn(0.0, 1.0)
    }

    private fun scoreSingleToken(queryToken: String, targetToken: String): Double {
        if (queryToken == targetToken) return 1.0
        if (queryToken.isEmpty() || targetToken.isEmpty()) return 0.0

        if (targetToken.startsWith(queryToken)) {
            val coverage = queryToken.length.toDouble() / targetToken.length.toDouble()
            return 0.86 + 0.14 * coverage
        }
        if (queryToken.length >= 3 && targetToken.contains(queryToken)) {
            val coverage = queryToken.length.toDouble() / targetToken.length.toDouble()
            return 0.76 + 0.16 * coverage
        }
        if (targetToken.length >= 3 && queryToken.startsWith(targetToken)) {
            val coverage = targetToken.length.toDouble() / queryToken.length.toDouble()
            return 0.74 + 0.16 * coverage
        }

        val maxLen = max(queryToken.length, targetToken.length)
        if (maxLen <= 2) {
            return if (queryToken[0] == targetToken[0]) 0.45 else 0.0
        }

        val editSim = normalizedEditSimilarity(queryToken, targetToken)
        val qSkel = buildConsonantSkeleton(queryToken)
        val tSkel = buildConsonantSkeleton(targetToken)
        val skelSim = if (qSkel.length >= 2 && tSkel.length >= 2) {
            if (qSkel == tSkel) 0.88 else normalizedEditSimilarity(qSkel, tSkel) * 0.82
        } else {
            0.0
        }
        return max(editSim, skelSim)
    }

    private fun scoreCategoryToken(queryToken: String, categoryToken: String): Double {
        if (queryToken == categoryToken) return 0.90
        if (queryToken.isEmpty() || categoryToken.isEmpty()) return 0.0

        if (categoryToken.startsWith(queryToken) && queryToken.length >= 3) {
            val coverage = queryToken.length.toDouble() / categoryToken.length.toDouble()
            return 0.76 + 0.12 * coverage
        }
        if (categoryToken.length >= 3 && queryToken.startsWith(categoryToken)) {
            return 0.52
        }

        val editSim = normalizedEditSimilarity(queryToken, categoryToken)
        return if (editSim >= 0.80) editSim * 0.88 else editSim * 0.65
    }

    /**
     * Optimal String Alignment (Damerau-Levenshtein) similarity in [0.0, 1.0],
     * tolerating insertions, deletions, substitutions, and adjacent transpositions.
     */
    fun normalizedEditSimilarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val maxLen = max(a.length, b.length)
        if (abs(a.length - b.length) > maxLen / 2 + 3) {
            return 0.0
        }
        val dist = damerauLevenshtein(a, b)
        return (1.0 - dist.toDouble() / maxLen.toDouble()).coerceIn(0.0, 1.0)
    }

    private fun damerauLevenshtein(a: String, b: String): Int {
        val n = a.length
        val m = b.length
        if (n == 0) return m
        if (m == 0) return n

        var prevPrev = IntArray(m + 1)
        var prev = IntArray(m + 1) { it }
        var curr = IntArray(m + 1)

        for (i in 1..n) {
            curr[0] = i
            val ca = a[i - 1]
            for (j in 1..m) {
                val cb = b[j - 1]
                val cost = if (ca == cb) 0 else 1
                var best = min(
                    min(curr[j - 1] + 1, prev[j] + 1),
                    prev[j - 1] + cost
                )
                if (i > 1 && j > 1 && ca == b[j - 2] && a[i - 2] == cb) {
                    best = min(best, prevPrev[j - 2] + 1)
                }
                curr[j] = best
            }
            val tmp = prevPrev
            prevPrev = prev
            prev = curr
            curr = tmp
        }
        return prev[m]
    }

    private fun trigramSimilarity(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val smaller = if (a.size <= b.size) a else b
        val larger = if (a.size <= b.size) b else a
        var intersection = 0
        for (gram in smaller) {
            if (gram in larger) intersection++
        }
        return (2.0 * intersection.toDouble()) / (a.size + b.size).toDouble()
    }

    private fun orderedSubsequenceScore(query: String, target: String): Double {
        if (query.isEmpty() || target.isEmpty()) return 0.0
        var qi = 0
        var matched = 0
        for (ch in target) {
            if (qi < query.length && ch == query[qi]) {
                qi++
                matched++
            }
        }
        return matched.toDouble() / query.length.toDouble()
    }
}
