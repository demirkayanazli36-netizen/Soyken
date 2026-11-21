package com.example.ui.viewmodel

import android.app.Activity
import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.billing.BillingUser
import com.example.billing.PlayBillingManager
import com.example.billing.PremiumFeature
import com.example.billing.PremiumGatePolicy
import com.example.billing.PremiumStatus
import com.example.billing.PurchaseEvent
import com.example.billing.SubscriptionConfig
import com.example.billing.SubscriptionPlanOffer
import com.example.billing.SubscriptionRepository
import com.example.data.model.Channel
import com.example.data.model.PlaybackProgress
import com.example.data.model.Playlist
import com.example.data.model.ProgramReminder
import com.example.data.network.StreamAccelerator
import com.example.data.parser.M3uParser
import com.example.data.repository.IptvRepository
import com.example.data.search.IndexedChannel
import com.example.data.search.SmartChannelSearch
import com.example.data.search.SmartSearchSplit
import com.example.service.NotificationHelper
import com.example.service.ReminderScheduler
import com.example.ui.components.VideoQualityPreset
import com.example.ui.theme.ThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.InputStream
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = IptvRepository(application)
    private val subscriptionRepository = SubscriptionRepository(application)
    private val billingManager = PlayBillingManager(application)
    private val prefs = application.getSharedPreferences("soyken_play_prefs", Context.MODE_PRIVATE)

    // Theme Mode (persist in SharedPreferences)
    private val _themeMode = MutableStateFlow(
        try {
            ThemeMode.valueOf(prefs.getString("theme_mode", ThemeMode.DARK.name) ?: ThemeMode.DARK.name)
        } catch (_: Exception) {
            ThemeMode.DARK
        }
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    // Active & All Playlists
    val allPlaylists: StateFlow<List<Playlist>> = repository.getAllPlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activePlaylist: StateFlow<Playlist?> = repository.getActivePlaylist()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Category & Search
    private val _selectedCategory = MutableStateFlow("Tümü")
    val selectedCategory: StateFlow<String> = _selectedCategory.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Channels in current active playlist (eagerly kept warm so search never misses channels)
    val currentPlaylistChannels: StateFlow<List<Channel>> = activePlaylist.flatMapLatest { playlist ->
        if (playlist != null) {
            repository.getChannelsForPlaylist(playlist.id)
        } else {
            flowOf(emptyList())
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Pre-indexed normalized & phonetic channel catalog built off the main thread
    private val indexedPlaylistChannels: StateFlow<List<IndexedChannel>> = currentPlaylistChannels
        .map { SmartChannelSearch.buildIndex(it) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Categories in current active playlist
    val categories: StateFlow<List<String>> = activePlaylist.flatMapLatest { playlist ->
        if (playlist != null) {
            repository.getCategoriesForPlaylist(playlist.id)
        } else {
            flowOf(emptyList())
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Punctuation-insensitive, typo-tolerant & similarity split computed on Dispatchers.Default
    val searchResultSplit: StateFlow<SmartSearchSplit> = combine(
        indexedPlaylistChannels,
        _selectedCategory,
        _searchQuery
    ) { indexedChannels, category, query ->
        SmartChannelSearch.searchIndexedChannels(
            indexedPlaylistChannels = indexedChannels,
            selectedCategory = category,
            rawQuery = query
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, SmartSearchSplit())

    // Unified list of channels (direct matches followed by similar options)
    val filteredChannels: StateFlow<List<Channel>> = searchResultSplit
        .map { it.allResults }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Currently playing channel & display modes
    private val _playingChannel = MutableStateFlow<Channel?>(null)
    val playingChannel: StateFlow<Channel?> = _playingChannel.asStateFlow()

    private val _isFullscreen = MutableStateFlow(false)
    val isFullscreen: StateFlow<Boolean> = _isFullscreen.asStateFlow()

    private val _isMiniPlayer = MutableStateFlow(false)
    val isMiniPlayer: StateFlow<Boolean> = _isMiniPlayer.asStateFlow()

    private val _isInPipMode = MutableStateFlow(false)
    val isInPipMode: StateFlow<Boolean> = _isInPipMode.asStateFlow()

    private val _isCurrentlyPlaying = MutableStateFlow(false)
    val isCurrentlyPlaying: StateFlow<Boolean> = _isCurrentlyPlaying.asStateFlow()

    private val _videoQualityPreset = MutableStateFlow(
        VideoQualityPreset.fromNameOrDefault(prefs.getString(KEY_VIDEO_QUALITY, VideoQualityPreset.AUTO.name))
    )
    val videoQualityPreset: StateFlow<VideoQualityPreset> = _videoQualityPreset.asStateFlow()

    private val _isBackgroundPlayEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_BACKGROUND_MINI_PLAY, false)
    )
    val isBackgroundPlayEnabled: StateFlow<Boolean> = _isBackgroundPlayEnabled.asStateFlow()

    private val _isTurboStreamEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_TURBO_STREAM_ENABLED, false)
    )
    val isTurboStreamEnabled: StateFlow<Boolean> = _isTurboStreamEnabled.asStateFlow()

    // Zero-trust Firebase Auth + Server-Verified Firestore Premium Entitlement
    val currentUser: StateFlow<BillingUser?> = subscriptionRepository.observeAuthUser()
        .stateIn(viewModelScope, SharingStarted.Eagerly, subscriptionRepository.currentUser())

    private val _verifiedOverrideStatus = MutableStateFlow<PremiumStatus?>(null)

    private val firestorePremiumStatus: StateFlow<PremiumStatus> = currentUser
        .flatMapLatest { user ->
            if (user == null || user.uid.isBlank()) {
                flowOf(PremiumStatus())
            } else {
                subscriptionRepository.observeServerEntitlement(user.uid)
                    .catch { err ->
                        val fsErr = SubscriptionRepository.unwrapFirestoreException(err)
                        if (fsErr != null) {
                            Log.w("MainViewModel", "Firestore entitlement listener error: ${fsErr.code}")
                        } else if (err !is CancellationException) {
                            Log.w("MainViewModel", "Entitlement stream error: ${err.message}")
                        }
                        emit(PremiumStatus())
                    }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PremiumStatus())

    val premiumStatus: StateFlow<PremiumStatus> = combine(
        firestorePremiumStatus,
        _verifiedOverrideStatus,
        currentUser
    ) { fsStatus, overrideStatus, user ->
        if (user == null) {
            PremiumStatus()
        } else if (fsStatus.isEffectivelyPremium()) {
            fsStatus
        } else if (overrideStatus != null && overrideStatus.isEffectivelyPremium()) {
            overrideStatus
        } else {
            fsStatus
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PremiumStatus())

    val isPremium: StateFlow<Boolean> = premiumStatus
        .map { it.isEffectivelyPremium() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val effectiveVideoQualityPreset: StateFlow<VideoQualityPreset> = combine(
        _videoQualityPreset,
        isPremium
    ) { preset, premium ->
        if (!premium && preset == VideoQualityPreset.UHD_4K) {
            VideoQualityPreset.FHD_1080P
        } else {
            preset
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, VideoQualityPreset.AUTO)

    val effectiveBackgroundPlayEnabled: StateFlow<Boolean> = combine(
        _isBackgroundPlayEnabled,
        isPremium
    ) { enabled, premium ->
        premium && enabled
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val effectiveTurboStreamEnabled: StateFlow<Boolean> = combine(
        _isTurboStreamEnabled,
        isPremium
    ) { enabled, premium ->
        premium && enabled
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val subscriptionOffers: StateFlow<List<SubscriptionPlanOffer>> = billingManager.offers
    val isLoadingOffers: StateFlow<Boolean> = billingManager.isLoadingOffers
    val billingNotice: StateFlow<String?> = billingManager.billingNotice

    private val _isVerifyingSubscription = MutableStateFlow(false)
    val isVerifyingSubscription: StateFlow<Boolean> = _isVerifyingSubscription.asStateFlow()

    private val _isPremiumDialogOpen = MutableStateFlow(false)
    val isPremiumDialogOpen: StateFlow<Boolean> = _isPremiumDialogOpen.asStateFlow()

    private val _premiumHighlightFeature = MutableStateFlow<PremiumFeature?>(null)
    val premiumHighlightFeature: StateFlow<PremiumFeature?> = _premiumHighlightFeature.asStateFlow()

    private val _isNotificationsEnabled = MutableStateFlow(
        prefs.getBoolean(NotificationHelper.KEY_NOTIFICATIONS_ENABLED, true)
    )
    val isNotificationsEnabled: StateFlow<Boolean> = _isNotificationsEnabled.asStateFlow()

    private val _pipPlayPauseSignal = MutableStateFlow(0)
    val pipPlayPauseSignal: StateFlow<Int> = _pipPlayPauseSignal.asStateFlow()

    private val _pipDismissSignal = MutableStateFlow(0)
    val pipDismissSignal: StateFlow<Int> = _pipDismissSignal.asStateFlow()

    // Per-stream saved playback positions (Room + immediate in-memory cache)
    private val inMemoryPositions = ConcurrentHashMap<String, Long>()

    val playbackProgressMap: StateFlow<Map<String, PlaybackProgress>> =
        repository.getAllPlaybackProgress()
            .map { list -> list.associateBy { it.streamUrl } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    // Channel Time Reminder (Saat & Dakika)
    private val _scheduleChannel = MutableStateFlow<Channel?>(null)
    val scheduleChannel: StateFlow<Channel?> = _scheduleChannel.asStateFlow()

    val reminders: StateFlow<List<ProgramReminder>> = repository.getAllReminders()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // UI Dialog States
    private val _isImportDialogOpen = MutableStateFlow(false)
    val isImportDialogOpen: StateFlow<Boolean> = _isImportDialogOpen.asStateFlow()

    private val _isPlaylistManagerOpen = MutableStateFlow(false)
    val isPlaylistManagerOpen: StateFlow<Boolean> = _isPlaylistManagerOpen.asStateFlow()

    private val _isScheduleSheetOpen = MutableStateFlow(false)
    val isScheduleSheetOpen: StateFlow<Boolean> = _isScheduleSheetOpen.asStateFlow()

    private val _isReportDialogOpen = MutableStateFlow(false)
    val isReportDialogOpen: StateFlow<Boolean> = _isReportDialogOpen.asStateFlow()

    private val _reportTargetChannel = MutableStateFlow<Channel?>(null)
    val reportTargetChannel: StateFlow<Channel?> = _reportTargetChannel.asStateFlow()

    private val _reportPlaybackError = MutableStateFlow<String?>(null)
    val reportPlaybackError: StateFlow<String?> = _reportPlaybackError.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage.asStateFlow()

    private val _activeReminderAlert = MutableStateFlow<ProgramReminder?>(null)
    val activeReminderAlert: StateFlow<ProgramReminder?> = _activeReminderAlert.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteExpiredReminders(System.currentTimeMillis() - 300_000L)
            ReminderScheduler.bootstrapAsync(application)
            // Try immediate fast session restore first for existing playlists
            restoreLastPlaybackSession()
            repository.initializeDemoIfEmpty()
            if (_playingChannel.value == null) {
                restoreLastPlaybackSession()
            }
        }
        viewModelScope.launch {
            ReminderScheduler.firedEvents.collect { fired ->
                if (_isNotificationsEnabled.value) {
                    _activeReminderAlert.value = fired
                }
            }
        }
        viewModelScope.launch {
            currentPlaylistChannels.collect { channels ->
                if (effectiveTurboStreamEnabled.value && channels.isNotEmpty()) {
                    StreamAccelerator.preWarmPlaylistAsync(channels)
                }
            }
        }
        viewModelScope.launch {
            billingManager.purchaseEvents.collect { event ->
                when (event) {
                    is PurchaseEvent.VerifiedPurchaseReady -> {
                        verifyPurchaseOnServer(event.purchaseToken, event.productId)
                    }
                    PurchaseEvent.Pending -> {
                        _userMessage.value =
                            "Ödeme işleminiz beklemede. Google Play onayladığında Premium otomatik açılacaktır."
                    }
                    PurchaseEvent.Canceled -> {
                        _userMessage.value = "Satın alma işlemi iptal edildi."
                    }
                    is PurchaseEvent.Error -> {
                        _userMessage.value = event.message
                    }
                }
            }
        }
        viewModelScope.launch {
            currentUser.collect { user ->
                if (user == null) {
                    _verifiedOverrideStatus.value = null
                } else {
                    retryPendingVerificationIfAny()
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            billingManager.loadSubscriptionOffers()
        }
    }

    override fun onCleared() {
        billingManager.endConnection()
        super.onCleared()
    }

    // ---- Google Play Billing, Google Sign-In & Premium Entitlement Actions ----

    fun attemptSilentSignIn(activity: Activity) {
        viewModelScope.launch {
            val user = subscriptionRepository.attemptSilentSignIn(activity)
            if (user != null) {
                retryPendingVerificationIfAny()
            }
        }
    }

    fun signInWithGoogle(activity: Activity) {
        viewModelScope.launch {
            val result = subscriptionRepository.signInWithGoogle(activity)
            result.onSuccess { user ->
                _userMessage.value = "Hoş geldiniz, ${user.displayName ?: user.email ?: "Soyken Play"}!"
                syncActivePurchasesWithServer(showNoPurchaseMessage = false)
            }.onFailure { err ->
                if (err !is androidx.credentials.exceptions.GetCredentialCancellationException) {
                    _userMessage.value = err.localizedMessage ?: "Google ile giriş yapılamadı."
                }
            }
        }
    }

    fun signOutGoogle(activity: Activity) {
        viewModelScope.launch {
            subscriptionRepository.signOut(activity)
            _verifiedOverrideStatus.value = null
            _userMessage.value = "Google hesabından çıkış yapıldı."
        }
    }

    fun openPremiumDialog(highlightFeature: PremiumFeature? = null) {
        _premiumHighlightFeature.value = highlightFeature
        _isPremiumDialogOpen.value = true
        if (subscriptionOffers.value.isEmpty() && !isLoadingOffers.value) {
            viewModelScope.launch {
                billingManager.loadSubscriptionOffers()
            }
        }
    }

    fun closePremiumDialog() {
        _isPremiumDialogOpen.value = false
        _premiumHighlightFeature.value = null
    }

    fun reloadSubscriptionOffers() {
        viewModelScope.launch {
            billingManager.loadSubscriptionOffers()
        }
    }

    fun launchSubscriptionPurchase(activity: Activity, offer: SubscriptionPlanOffer) {
        val user = currentUser.value
        if (user == null) {
            _userMessage.value = "Satın alma işlemi için önce Google hesabınızla giriş yapın."
            return
        }
        billingManager.launchSubscriptionPurchase(
            activity = activity,
            offer = offer,
            firebaseUid = user.uid
        )
    }

    fun restoreAndVerifyPurchases() {
        if (currentUser.value == null) {
            _userMessage.value = "Aboneliğinizi geri yüklemek için önce Google hesabınızla giriş yapın."
            return
        }
        syncActivePurchasesWithServer(showNoPurchaseMessage = true)
    }

    fun onAppResumed() {
        if (currentUser.value != null) {
            viewModelScope.launch {
                retryPendingVerificationIfAny()
                syncActivePurchasesWithServer(showNoPurchaseMessage = false)
            }
        }
    }

    private fun syncActivePurchasesWithServer(showNoPurchaseMessage: Boolean) {
        viewModelScope.launch {
            val activePurchases = billingManager.queryActiveSubscriptionPurchases()
            if (activePurchases.isEmpty()) {
                if (showNoPurchaseMessage) {
                    _userMessage.value = if (isPremium.value) {
                        "Soyken Premium aboneliğiniz zaten aktif."
                    } else {
                        "Bu Google Play hesabında aktif Soyken Premium aboneliği bulunamadı."
                    }
                }
                return@launch
            }
            activePurchases.forEach { purchase ->
                verifyPurchaseOnServer(
                    purchaseToken = purchase.purchaseToken,
                    productId = SubscriptionConfig.SUBSCRIPTION_PRODUCT_ID
                )
            }
        }
    }

    private suspend fun retryPendingVerificationIfAny() {
        val pendingToken = subscriptionRepository.getPendingVerificationToken() ?: return
        verifyPurchaseOnServer(pendingToken, SubscriptionConfig.SUBSCRIPTION_PRODUCT_ID)
    }

    private suspend fun verifyPurchaseOnServer(purchaseToken: String, productId: String) {
        if (_isVerifyingSubscription.value) return
        _isVerifyingSubscription.value = true
        try {
            val result = subscriptionRepository.verifyPurchaseWithServer(purchaseToken, productId)
            result.onSuccess { status ->
                _verifiedOverrideStatus.value = status
                if (status.isEffectivelyPremium()) {
                    // Automatically enable Turbo & Background PiP when user unlocks Premium
                    if (!prefs.contains(KEY_TURBO_STREAM_ENABLED)) {
                        _isTurboStreamEnabled.value = true
                        prefs.edit().putBoolean(KEY_TURBO_STREAM_ENABLED, true).apply()
                    }
                    if (!prefs.contains(KEY_BACKGROUND_MINI_PLAY)) {
                        _isBackgroundPlayEnabled.value = true
                        prefs.edit().putBoolean(KEY_BACKGROUND_MINI_PLAY, true).apply()
                    }
                    _userMessage.value = "Soyken Premium doğrulandı ve aktif edildi!"
                } else {
                    _userMessage.value = "Abonelik durumu güncellendi: aktif abonelik bulunmuyor."
                }
            }.onFailure { err ->
                _userMessage.value = err.localizedMessage
                    ?: "Sunucu doğrulaması bekleniyor. İnternet bağlantınızla otomatik tamamlanacak."
            }
        } finally {
            _isVerifyingSubscription.value = false
        }
    }

    private suspend fun restoreLastPlaybackSession() {
        val wasPlayerOpen = prefs.getBoolean(KEY_IS_PLAYER_OPEN, false)
        if (!wasPlayerOpen) return

        val lastChannelId = prefs.getLong(KEY_LAST_CHANNEL_ID, -1L)
        val lastStreamUrl = prefs.getString(KEY_LAST_STREAM_URL, null)
        val wasFullscreen = prefs.getBoolean(KEY_LAST_IS_FULLSCREEN, false)
        val lastSavedDur = prefs.getLong(KEY_LAST_DURATION_MS, 0L)
        val lastSavedPos = if (lastSavedDur >= IptvRepository.MIN_VOD_DURATION_MS) {
            prefs.getLong(KEY_LAST_POSITION_MS, 0L)
        } else {
            0L
        }

        val channel = repository.findChannelForResume(lastChannelId, lastStreamUrl) ?: return
        val normalizedUrl = M3uParser.toHlsStreamUrl(channel.streamUrl)
        val dbProgress = repository.getPlaybackProgressSync(normalizedUrl)
        val resumePos = maxOf(lastSavedPos, dbProgress?.positionMs ?: 0L)
        if (resumePos >= IptvRepository.MIN_RESUME_POSITION_MS) {
            inMemoryPositions[normalizedUrl] = resumePos
        } else {
            inMemoryPositions.remove(normalizedUrl)
        }

        val currentActive = repository.getActivePlaylistSync()
        if (currentActive == null || currentActive.id != channel.playlistId) {
            repository.setActivePlaylist(channel.playlistId)
        }

        _isFullscreen.value = wasFullscreen
        _playingChannel.value = channel
    }

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        prefs.edit().putString("theme_mode", mode.name).apply()
    }

    fun setCategory(category: String) {
        _selectedCategory.value = category
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun getInitialPositionMs(channel: Channel): Long {
        val normalizedUrl = M3uParser.toHlsStreamUrl(channel.streamUrl)
        inMemoryPositions[normalizedUrl]?.let { return it }
        playbackProgressMap.value[normalizedUrl]
            ?.takeIf { !it.isLive && it.durationMs >= IptvRepository.MIN_VOD_DURATION_MS }
            ?.positionMs
            ?.let { return it }
        val lastUrl = prefs.getString(KEY_LAST_STREAM_URL, null)
        val lastDur = prefs.getLong(KEY_LAST_DURATION_MS, 0L)
        if (lastUrl == normalizedUrl && lastDur >= IptvRepository.MIN_VOD_DURATION_MS) {
            return prefs.getLong(KEY_LAST_POSITION_MS, 0L)
        }
        return 0L
    }

    fun savePlaybackProgress(
        channel: Channel,
        positionMs: Long,
        durationMs: Long,
        isLive: Boolean
    ) {
        val normalizedUrl = M3uParser.toHlsStreamUrl(channel.streamUrl)
        if (isLive) {
            inMemoryPositions.remove(normalizedUrl)
            if (_playingChannel.value?.id == channel.id) {
                prefs.edit()
                    .putLong(KEY_LAST_POSITION_MS, 0L)
                    .putLong(KEY_LAST_DURATION_MS, 0L)
                    .apply()
            }
            return
        }
        if (durationMs < IptvRepository.MIN_VOD_DURATION_MS) {
            // Stream metadata/duration not loaded yet; do not overwrite existing saved VOD progress
            return
        }

        if (IptvRepository.isPlaybackCompleted(positionMs, durationMs)) {
            inMemoryPositions[normalizedUrl] = 0L
            if (prefs.getString(KEY_LAST_STREAM_URL, null) == normalizedUrl) {
                prefs.edit()
                    .putLong(KEY_LAST_POSITION_MS, 0L)
                    .putLong(KEY_LAST_DURATION_MS, durationMs)
                    .apply()
            }
            viewModelScope.launch {
                repository.clearPlaybackProgress(normalizedUrl)
            }
            return
        }

        if (positionMs >= IptvRepository.MIN_RESUME_POSITION_MS) {
            inMemoryPositions[normalizedUrl] = positionMs
            if (prefs.getString(KEY_LAST_STREAM_URL, null) == normalizedUrl) {
                prefs.edit()
                    .putLong(KEY_LAST_POSITION_MS, positionMs)
                    .putLong(KEY_LAST_DURATION_MS, durationMs)
                    .apply()
            }
            viewModelScope.launch {
                repository.savePlaybackProgress(channel, positionMs, durationMs, false)
            }
        }
    }

    fun resetChannelProgress(channel: Channel) {
        val normalizedUrl = M3uParser.toHlsStreamUrl(channel.streamUrl)
        inMemoryPositions[normalizedUrl] = 0L
        if (prefs.getString(KEY_LAST_STREAM_URL, null) == normalizedUrl) {
            prefs.edit()
                .putLong(KEY_LAST_POSITION_MS, 0L)
                .putLong(KEY_LAST_DURATION_MS, 0L)
                .apply()
        }
        viewModelScope.launch {
            repository.clearPlaybackProgress(normalizedUrl)
        }
    }

    fun playChannel(channel: Channel) {
        val normalizedUrl = M3uParser.toHlsStreamUrl(channel.streamUrl)
        val dbEntry = playbackProgressMap.value[normalizedUrl]
            ?.takeIf { !it.isLive && it.durationMs >= IptvRepository.MIN_VOD_DURATION_MS }
        val savedPos = inMemoryPositions[normalizedUrl]
            ?: dbEntry?.positionMs
            ?: 0L
        val savedDur = dbEntry?.durationMs ?: if (savedPos > 0L) IptvRepository.MIN_VOD_DURATION_MS else 0L
        _playingChannel.value = channel
        if (effectiveTurboStreamEnabled.value) {
            val list = filteredChannels.value
            val idx = list.indexOfFirst { it.id == channel.id }
            val preWarmTargets = buildList {
                add(channel.streamUrl)
                if (idx != -1 && list.size > 1) {
                    add(list[(idx + 1) % list.size].streamUrl)
                    add(list[if (idx > 0) idx - 1 else list.size - 1].streamUrl)
                }
            }
            StreamAccelerator.preWarmUrlsAsync(preWarmTargets)
        }
        prefs.edit()
            .putBoolean(KEY_IS_PLAYER_OPEN, true)
            .putLong(KEY_LAST_CHANNEL_ID, channel.id)
            .putString(KEY_LAST_STREAM_URL, normalizedUrl)
            .putLong(KEY_LAST_POSITION_MS, savedPos)
            .putLong(KEY_LAST_DURATION_MS, savedDur)
            .putBoolean(KEY_LAST_IS_FULLSCREEN, _isFullscreen.value)
            .apply()
    }

    fun closePlayer() {
        _playingChannel.value = null
        _isFullscreen.value = false
        _isMiniPlayer.value = false
        _isCurrentlyPlaying.value = false
        prefs.edit()
            .putBoolean(KEY_IS_PLAYER_OPEN, false)
            .putBoolean(KEY_LAST_IS_FULLSCREEN, false)
            .apply()
    }

    fun toggleFullscreen() {
        val next = !_isFullscreen.value
        if (next) {
            _isMiniPlayer.value = false
        }
        _isFullscreen.value = next
        prefs.edit().putBoolean(KEY_LAST_IS_FULLSCREEN, next).apply()
    }

    fun setFullscreen(fullscreen: Boolean) {
        if (fullscreen) {
            _isMiniPlayer.value = false
        }
        _isFullscreen.value = fullscreen
        prefs.edit().putBoolean(KEY_LAST_IS_FULLSCREEN, fullscreen).apply()
    }

    fun enterMiniPlayer() {
        if (_playingChannel.value == null) return
        if (!isPremium.value) {
            openPremiumDialog(PremiumFeature.BACKGROUND_MINI_PIP)
            return
        }
        _isFullscreen.value = false
        _isMiniPlayer.value = true
        prefs.edit().putBoolean(KEY_LAST_IS_FULLSCREEN, false).apply()
    }

    fun exitMiniPlayer() {
        _isMiniPlayer.value = false
    }

    fun setInPipMode(inPip: Boolean) {
        _isInPipMode.value = inPip
    }

    fun updateCurrentlyPlaying(playing: Boolean) {
        _isCurrentlyPlaying.value = playing
    }

    fun setVideoQualityPreset(preset: VideoQualityPreset) {
        if (preset == VideoQualityPreset.UHD_4K && !isPremium.value) {
            openPremiumDialog(PremiumFeature.ULTRA_HD_4K)
            return
        }
        _videoQualityPreset.value = preset
        prefs.edit().putString(KEY_VIDEO_QUALITY, preset.name).apply()
        _userMessage.value = "Görüntü kalitesi: ${preset.title}"
    }

    fun setBackgroundPlayEnabled(enabled: Boolean) {
        if (enabled && !isPremium.value) {
            openPremiumDialog(PremiumFeature.BACKGROUND_MINI_PIP)
            return
        }
        _isBackgroundPlayEnabled.value = enabled
        prefs.edit().putBoolean(KEY_BACKGROUND_MINI_PLAY, enabled).apply()
        _userMessage.value = if (enabled) {
            "Arka planda mini oynatma aktif edildi."
        } else {
            "Arka planda mini oynatma kapatıldı."
        }
    }

    fun setTurboStreamEnabled(enabled: Boolean) {
        if (enabled && !isPremium.value) {
            openPremiumDialog(PremiumFeature.TURBO_CACHE)
            return
        }
        _isTurboStreamEnabled.value = enabled
        prefs.edit().putBoolean(KEY_TURBO_STREAM_ENABLED, enabled).apply()
        if (enabled) {
            val channels = currentPlaylistChannels.value
            if (channels.isNotEmpty()) {
                StreamAccelerator.preWarmPlaylistAsync(channels)
            }
        }
        _userMessage.value = if (enabled) {
            "Hızlı bağlantı ve donma önleyici Turbo Akış aktif edildi."
        } else {
            "Standart akış tamponu seçildi."
        }
    }

    fun triggerPipPlayPause() {
        _pipPlayPauseSignal.value += 1
    }

    fun notifyPipDismissed() {
        _pipDismissSignal.value += 1
    }

    fun nextChannel() {
        val list = filteredChannels.value
        if (list.isEmpty()) return
        val current = _playingChannel.value
        val currentIndex = list.indexOfFirst { it.id == current?.id }
        val nextIndex = if (currentIndex != -1) (currentIndex + 1) % list.size else 0
        playChannel(list[nextIndex])
    }

    fun previousChannel() {
        val list = filteredChannels.value
        if (list.isEmpty()) return
        val current = _playingChannel.value
        val currentIndex = list.indexOfFirst { it.id == current?.id }
        val prevIndex = if (currentIndex > 0) currentIndex - 1 else list.size - 1
        playChannel(list[prevIndex])
    }

    fun toggleFavorite(channel: Channel) {
        viewModelScope.launch {
            repository.toggleFavorite(channel)
        }
    }

    // Saat & Dakika Hatırlatıcı
    fun openSchedule(channel: Channel) {
        viewModelScope.launch {
            repository.deleteExpiredReminders(System.currentTimeMillis() - 120_000L)
        }
        _scheduleChannel.value = channel
        _isScheduleSheetOpen.value = true
    }

    fun closeSchedule() {
        _isScheduleSheetOpen.value = false
        _scheduleChannel.value = null
    }

    fun setTimeReminder(channel: Channel, hourOfDay: Int, minute: Int) {
        val context = getApplication<Application>()
        val safeHour = hourOfDay.coerceIn(0, 23)
        val safeMinute = minute.coerceIn(0, 59)
        val formattedTime = formatReminderHourMinute(safeHour, safeMinute)
        val reminderId = buildChannelTimeReminderId(channel.id, safeHour, safeMinute)

        val currentReminders = reminders.value
        val isUpdatingExisting = currentReminders.any { it.programId == reminderId }
        if (!isUpdatingExisting && !PremiumGatePolicy.canAddAnotherReminder(isPremium.value, currentReminders.size)) {
            _isScheduleSheetOpen.value = false
            _scheduleChannel.value = null
            openPremiumDialog(PremiumFeature.UNLIMITED_REMINDERS)
            return
        }

        if (!_isNotificationsEnabled.value) {
            _isNotificationsEnabled.value = true
            prefs.edit().putBoolean(NotificationHelper.KEY_NOTIFICATIONS_ENABLED, true).apply()
        }

        viewModelScope.launch {
            val triggerAt = computeNextReminderTriggerMillis(
                nowMillis = System.currentTimeMillis(),
                hourOfDay = safeHour,
                minute = safeMinute
            )
            val reminder = ProgramReminder(
                programId = reminderId,
                channelId = channel.id,
                channelName = channel.name,
                programTitle = formattedTime,
                startTime = triggerAt
            )
            repository.addReminder(reminder)
            ReminderScheduler.schedule(context, reminder)
            _isScheduleSheetOpen.value = false
            _scheduleChannel.value = null
            _userMessage.value = "⏰ ${channel.name} • $formattedTime hatırlatıcısı kuruldu!"
        }
    }

    fun removeReminder(reminder: ProgramReminder) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            repository.removeReminder(reminder.programId)
            ReminderScheduler.cancel(context, reminder.programId)
            _userMessage.value = "${reminder.programTitle} hatırlatıcısı iptal edildi."
        }
    }

    fun dismissReminderAlert() {
        _activeReminderAlert.value = null
    }

    fun openReminderAlertChannel(reminder: ProgramReminder) {
        _activeReminderAlert.value = null
        openChannelFromReminderNotification(reminder.channelId)
    }

    fun openChannelFromReminderNotification(channelId: Long) {
        if (channelId <= 0L) return
        viewModelScope.launch {
            val channel = repository.findChannelForResume(channelId, null) ?: return@launch
            playChannel(channel)
        }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        _isNotificationsEnabled.value = enabled
        prefs.edit().putBoolean(NotificationHelper.KEY_NOTIFICATIONS_ENABLED, enabled).apply()
        if (!enabled) {
            _activeReminderAlert.value = null
        }
        _userMessage.value = if (enabled) {
            "Yayın bildirimleri açıldı."
        } else {
            "Yayın bildirimleri kapatıldı."
        }
    }

    fun sendTestNotification() {
        val context = getApplication<Application>()
        if (!_isNotificationsEnabled.value) {
            _isNotificationsEnabled.value = true
            prefs.edit().putBoolean(NotificationHelper.KEY_NOTIFICATIONS_ENABLED, true).apply()
        }
        val posted = NotificationHelper.showTestNotification(context)
        val cal = Calendar.getInstance()
        val nowFormatted = formatReminderHourMinute(
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE)
        )
        if (!posted) {
            NotificationHelper.playReminderSoundFallback(context)
        }
        _activeReminderAlert.value = ProgramReminder(
            programId = "test_${System.currentTimeMillis()}",
            channelId = _playingChannel.value?.id ?: -1L,
            channelName = _playingChannel.value?.name ?: "Soyken Play",
            programTitle = nowFormatted,
            startTime = System.currentTimeMillis()
        )
        _userMessage.value = "Test bildirimi gönderildi!"
    }

    // Import Dialog controls
    fun openImportDialog() {
        if (!PremiumGatePolicy.canAddAnotherUserPlaylist(isPremium.value, allPlaylists.value)) {
            openPremiumDialog(PremiumFeature.UNLIMITED_PLAYLISTS)
            return
        }
        _isImportDialogOpen.value = true
    }

    fun closeImportDialog() {
        _isImportDialogOpen.value = false
    }

    fun openPlaylistManager() {
        _isPlaylistManagerOpen.value = true
    }

    fun closePlaylistManager() {
        _isPlaylistManagerOpen.value = false
    }

    fun importFromUrl(name: String, url: String) {
        if (!PremiumGatePolicy.canAddAnotherUserPlaylist(isPremium.value, allPlaylists.value)) {
            _isImportDialogOpen.value = false
            openPremiumDialog(PremiumFeature.UNLIMITED_PLAYLISTS)
            return
        }
        viewModelScope.launch {
            _isLoading.value = true
            _userMessage.value = "1dot1dot1dot1.cloudflare-dns.com üzerinden M3U bağlantısı açılıyor..."
            val result = repository.importFromUrl(name, url)
            _isLoading.value = false
            if (result.isSuccess) {
                _userMessage.value = "Başarıyla içe aktarıldı: ${result.getOrNull()?.channelCount} kanal bulundu!"
                _isImportDialogOpen.value = false
                _selectedCategory.value = "Tümü"
            } else {
                _userMessage.value = "Hata: ${result.exceptionOrNull()?.localizedMessage ?: "Bilinmeyen hata"}"
            }
        }
    }

    fun importFromStream(name: String, inputStream: InputStream) {
        if (!PremiumGatePolicy.canAddAnotherUserPlaylist(isPremium.value, allPlaylists.value)) {
            _isImportDialogOpen.value = false
            openPremiumDialog(PremiumFeature.UNLIMITED_PLAYLISTS)
            return
        }
        viewModelScope.launch {
            _isLoading.value = true
            _userMessage.value = "M3U dosyası ayrıştırılıyor..."
            val result = repository.importFromInputStream(name, inputStream)
            _isLoading.value = false
            if (result.isSuccess) {
                _userMessage.value = "Dosya yüklendi: ${result.getOrNull()?.channelCount} kanal eklendi!"
                _isImportDialogOpen.value = false
                _selectedCategory.value = "Tümü"
            } else {
                _userMessage.value = "Hata: ${result.exceptionOrNull()?.localizedMessage ?: "Dosya okunamadı"}"
            }
        }
    }

    fun importFromString(name: String, text: String) {
        if (!PremiumGatePolicy.canAddAnotherUserPlaylist(isPremium.value, allPlaylists.value)) {
            _isImportDialogOpen.value = false
            openPremiumDialog(PremiumFeature.UNLIMITED_PLAYLISTS)
            return
        }
        viewModelScope.launch {
            _isLoading.value = true
            val result = repository.importFromString(name, text)
            _isLoading.value = false
            if (result.isSuccess) {
                _userMessage.value = "Liste eklendi: ${result.getOrNull()?.channelCount} kanal!"
                _isImportDialogOpen.value = false
                _selectedCategory.value = "Tümü"
            } else {
                _userMessage.value = "Hata: ${result.exceptionOrNull()?.localizedMessage ?: "Hata oluştu"}"
            }
        }
    }

    fun switchActivePlaylist(playlistId: Long) {
        viewModelScope.launch {
            repository.setActivePlaylist(playlistId)
            _selectedCategory.value = "Tümü"
            _isPlaylistManagerOpen.value = false
        }
    }

    fun deletePlaylist(playlist: Playlist) {
        viewModelScope.launch {
            if (_playingChannel.value?.playlistId == playlist.id) {
                closePlayer()
            }
            repository.deletePlaylist(playlist)
            _userMessage.value = "\"${playlist.name}\" silindi."
        }
    }

    // Stream Issue Reporting (Sorun Bildir)
    fun openReportDialog(
        channel: Channel? = _playingChannel.value,
        playbackError: String? = null
    ) {
        _reportTargetChannel.value = channel ?: _playingChannel.value
        _reportPlaybackError.value = playbackError?.takeIf { it.isNotBlank() }
        _isReportDialogOpen.value = true
    }

    fun selectReportChannel(channel: Channel) {
        _reportTargetChannel.value = channel
    }

    fun closeReportDialog() {
        _isReportDialogOpen.value = false
        _reportPlaybackError.value = null
    }

    fun showUserFeedback(message: String) {
        _userMessage.value = message
    }

    fun clearUserMessage() {
        _userMessage.value = null
    }

    companion object {
        private const val KEY_IS_PLAYER_OPEN = "last_is_player_open"
        private const val KEY_LAST_CHANNEL_ID = "last_playing_channel_id"
        private const val KEY_LAST_STREAM_URL = "last_playing_stream_url"
        private const val KEY_LAST_POSITION_MS = "last_playing_position_ms"
        private const val KEY_LAST_DURATION_MS = "last_playing_duration_ms"
        private const val KEY_LAST_IS_FULLSCREEN = "last_is_fullscreen"
        private const val KEY_VIDEO_QUALITY = "preferred_video_quality"
        private const val KEY_BACKGROUND_MINI_PLAY = "background_mini_play_enabled"
        private const val KEY_TURBO_STREAM_ENABLED = "turbo_stream_enabled"

        fun formatReminderHourMinute(hourOfDay: Int, minute: Int): String {
            return String.format(
                Locale.US,
                "%02d:%02d",
                hourOfDay.coerceIn(0, 23),
                minute.coerceIn(0, 59)
            )
        }

        fun buildChannelTimeReminderId(channelId: Long, hourOfDay: Int, minute: Int): String {
            return String.format(
                Locale.US,
                "ch_%d_%02d_%02d",
                channelId,
                hourOfDay.coerceIn(0, 23),
                minute.coerceIn(0, 59)
            )
        }

        fun computeNextReminderTriggerMillis(
            nowMillis: Long,
            hourOfDay: Int,
            minute: Int,
            timeZone: TimeZone = TimeZone.getDefault(),
            allowEmulatorLocalFallback: Boolean = true
        ): Long {
            val primaryTrigger = computeTriggerInTimeZone(nowMillis, hourOfDay, minute, timeZone)

            if (allowEmulatorLocalFallback && isRunningOnCloudEmulator()) {
                val primaryWaitMs = primaryTrigger - nowMillis
                if (primaryWaitMs > 3_600_000L) {
                    // 1. Check Turkey local time (Europe/Istanbul) if user entered their wall-clock time
                    val istanbulTz = TimeZone.getTimeZone("Europe/Istanbul")
                    if (timeZone.rawOffset != istanbulTz.rawOffset) {
                        val trTrigger = computeTriggerInTimeZone(nowMillis, hourOfDay, minute, istanbulTz)
                        if (trTrigger - nowMillis in 1_000L..3_600_000L) {
                            return trTrigger
                        }
                    }
                    // 2. Check 12-hour status-bar clock flip (e.g. "12:05" shown at 00:05 or "11:55" at 23:55)
                    val flippedHour = (hourOfDay.coerceIn(0, 23) + 12) % 24
                    val flippedTrigger = computeTriggerInTimeZone(nowMillis, flippedHour, minute, timeZone)
                    if (flippedTrigger - nowMillis in 1_000L..900_000L) {
                        return flippedTrigger
                    }
                }
            }

            return primaryTrigger
        }

        private fun computeTriggerInTimeZone(
            nowMillis: Long,
            hourOfDay: Int,
            minute: Int,
            timeZone: TimeZone
        ): Long {
            val safeHour = hourOfDay.coerceIn(0, 23)
            val safeMinute = minute.coerceIn(0, 59)

            val nowCal = Calendar.getInstance(timeZone).apply {
                timeInMillis = nowMillis
            }
            val nowHour = nowCal.get(Calendar.HOUR_OF_DAY)
            val nowMinute = nowCal.get(Calendar.MINUTE)

            // If user picked the exact current HH:mm shown on the clock, fire within 2 seconds
            // instead of silently pushing 24 hours into tomorrow.
            if (safeHour == nowHour && safeMinute == nowMinute) {
                return nowMillis + 2_000L
            }

            val calendar = Calendar.getInstance(timeZone).apply {
                timeInMillis = nowMillis
                set(Calendar.HOUR_OF_DAY, safeHour)
                set(Calendar.MINUTE, safeMinute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (calendar.timeInMillis <= nowMillis) {
                calendar.add(Calendar.DAY_OF_YEAR, 1)
            }
            return calendar.timeInMillis
        }

        private fun isRunningOnCloudEmulator(): Boolean {
            val fingerprint = android.os.Build.FINGERPRINT.orEmpty().lowercase(Locale.US)
            val model = android.os.Build.MODEL.orEmpty().lowercase(Locale.US)
            val hardware = android.os.Build.HARDWARE.orEmpty().lowercase(Locale.US)
            val product = android.os.Build.PRODUCT.orEmpty().lowercase(Locale.US)
            return fingerprint.contains("generic") ||
                fingerprint.contains("emulator") ||
                model.contains("sdk") ||
                model.contains("emulator") ||
                hardware.contains("ranchu") ||
                hardware.contains("goldfish") ||
                product.contains("sdk")
        }
    }
}
