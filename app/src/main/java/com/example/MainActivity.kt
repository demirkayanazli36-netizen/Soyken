package com.example

import android.Manifest
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon as AndroidIcon
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AlarmOn
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.ManageSearch
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import com.example.billing.PremiumFeature
import com.example.data.parser.M3uParser
import com.example.service.FirebaseAppCheckConfig
import com.example.service.NotificationHelper
import com.example.ui.components.CategoryChips
import com.example.ui.components.ChannelCard
import com.example.ui.components.EmailDispatchOutcome
import com.example.ui.components.ImportDialog
import com.example.ui.components.IssueReportDialog
import com.example.ui.components.PlaylistManagerSheet
import com.example.ui.components.PremiumPaywallDialog
import com.example.ui.components.PrivacyPolicyDialog
import com.example.ui.components.SUPPORT_REPORT_EMAIL
import com.example.ui.components.ScheduleBottomSheet
import com.example.ui.components.SoykenHeader
import com.example.ui.components.VideoPlayer
import com.example.ui.components.dispatchIssueReportEmail
import com.example.ui.components.openPlayStoreUpdatePage
import com.example.ui.theme.SoykenGold
import com.example.ui.theme.SoykenPlayTheme
import com.example.ui.theme.ThemeMode
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val pipActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_PIP_CONTROL) return
            when (intent.getIntExtra(EXTRA_PIP_COMMAND, 0)) {
                PIP_CMD_PREV -> viewModel.previousChannel()
                PIP_CMD_PLAY_PAUSE -> viewModel.triggerPipPlayPause()
                PIP_CMD_NEXT -> viewModel.nextChannel()
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FirebaseAppCheckConfig.initialize(this, intent)
        enableEdgeToEdge()
        handleReminderNotificationIntent(intent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.registerReceiver(
                this,
                pipActionReceiver,
                IntentFilter(ACTION_PIP_CONTROL),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )

            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    combine(
                        viewModel.playingChannel,
                        viewModel.isCurrentlyPlaying,
                        viewModel.effectiveBackgroundPlayEnabled
                    ) { channel, playing, bgEnabled ->
                        Triple(channel != null, playing, bgEnabled)
                    }
                        .distinctUntilChanged()
                        .collect { (hasChannel, playing, bgEnabled) ->
                            syncPipParams(hasChannel, playing, bgEnabled)
                        }
                }
            }
        }

        setContent {
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()

            SoykenPlayTheme(themeMode = themeMode) {
                // Request notification permission for Android 13+
                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { /* Handle permission response */ }

                val ensureNotificationPermission: () -> Unit = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        if (ContextCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }

                // Defer silent sign-in & runtime permission dialog until after the initial
                // ViewRootImpl frame settles so SurfaceSyncGroup never stalls during startup.
                LaunchedEffect(Unit) {
                    delay(450L)
                    viewModel.attemptSilentSignIn(this@MainActivity)
                    delay(250L)
                    ensureNotificationPermission()
                }

                SoykenPlayApp(
                    viewModel = viewModel,
                    onRequestPipMode = { enterAppPipMode() },
                    onEnsureNotificationPermission = ensureNotificationPermission,
                    onSignInWithGoogle = { viewModel.signInWithGoogle(this@MainActivity) },
                    onSignOutGoogle = { viewModel.signOutGoogle(this@MainActivity) },
                    onPurchaseOffer = { offer -> viewModel.launchSubscriptionPurchase(this@MainActivity, offer) }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleReminderNotificationIntent(intent)
    }

    private fun handleReminderNotificationIntent(intent: Intent?) {
        val channelId = intent?.getLongExtra(NotificationHelper.EXTRA_REMINDER_CHANNEL_ID, -1L) ?: -1L
        if (channelId > 0L) {
            intent?.removeExtra(NotificationHelper.EXTRA_REMINDER_CHANNEL_ID)
            viewModel.openChannelFromReminderNotification(channelId)
        }
    }

    private var wasInPipSession = false

    override fun onResume() {
        super.onResume()
        wasInPipSession = false
        viewModel.onAppResumed()
    }

    override fun onStop() {
        super.onStop()
        if (wasInPipSession && !isInPictureInPictureMode) {
            wasInPipSession = false
            viewModel.notifyPipDismissed()
        }
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { unregisterReceiver(pipActionReceiver) }
        }
        super.onDestroy()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Auto-enter Picture-in-Picture on Android 8.0+ strictly for Premium users with background play enabled
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !isInPictureInPictureMode) {
            if (viewModel.playingChannel.value != null &&
                viewModel.isCurrentlyPlaying.value &&
                viewModel.effectiveBackgroundPlayEnabled.value
            ) {
                enterAppPipMode()
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        viewModel.setInPipMode(isInPictureInPictureMode)
        if (isInPictureInPictureMode) {
            wasInPipSession = true
        } else if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            wasInPipSession = false
            viewModel.notifyPipDismissed()
        }
    }

    private fun isPipSupportedOnDevice(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun buildPipParams(
        hasChannel: Boolean,
        isPlaying: Boolean,
        autoEnterAllowed: Boolean
    ): PictureInPictureParams? {
        if (!isPipSupportedOnDevice() || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null

        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))

        if (hasChannel) {
            val actions = listOf(
                buildRemoteAction(
                    requestCode = 101,
                    command = PIP_CMD_PREV,
                    iconRes = android.R.drawable.ic_media_previous,
                    title = "Önceki"
                ),
                buildRemoteAction(
                    requestCode = 102,
                    command = PIP_CMD_PLAY_PAUSE,
                    iconRes = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    title = if (isPlaying) "Duraklat" else "Oynat"
                ),
                buildRemoteAction(
                    requestCode = 103,
                    command = PIP_CMD_NEXT,
                    iconRes = android.R.drawable.ic_media_next,
                    title = "Sonraki"
                )
            )
            builder.setActions(actions)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(hasChannel && isPlaying && autoEnterAllowed)
            builder.setSeamlessResizeEnabled(true)
        }

        return builder.build()
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun buildRemoteAction(
        requestCode: Int,
        command: Int,
        iconRes: Int,
        title: String
    ): RemoteAction {
        val intent = Intent(ACTION_PIP_CONTROL).apply {
            setPackage(packageName)
            putExtra(EXTRA_PIP_COMMAND, command)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return RemoteAction(
            AndroidIcon.createWithResource(this, iconRes),
            title,
            title,
            pendingIntent
        )
    }

    private fun syncPipParams(hasChannel: Boolean, isPlaying: Boolean, bgEnabled: Boolean) {
        if (!isPipSupportedOnDevice() || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (isFinishing || isDestroyed || window?.decorView?.isAttachedToWindow != true) return
        runCatching {
            buildPipParams(hasChannel, isPlaying, bgEnabled)?.let { params ->
                setPictureInPictureParams(params)
            }
        }
    }

    private fun enterAppPipMode() {
        if (!viewModel.isPremium.value) {
            viewModel.openPremiumDialog(PremiumFeature.BACKGROUND_MINI_PIP)
            return
        }
        if (!isPipSupportedOnDevice() || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (viewModel.playingChannel.value == null) return
        runCatching {
            val params = buildPipParams(
                hasChannel = true,
                isPlaying = viewModel.isCurrentlyPlaying.value,
                autoEnterAllowed = viewModel.effectiveBackgroundPlayEnabled.value
            )
            if (params != null) {
                enterPictureInPictureMode(params)
            }
        }
    }

    companion object {
        private const val ACTION_PIP_CONTROL = "com.example.soykenplay.ACTION_PIP_CONTROL"
        private const val EXTRA_PIP_COMMAND = "EXTRA_PIP_COMMAND"
        private const val PIP_CMD_PREV = 1
        private const val PIP_CMD_PLAY_PAUSE = 2
        private const val PIP_CMD_NEXT = 3
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoykenPlayApp(
    viewModel: MainViewModel,
    onRequestPipMode: () -> Unit = {},
    onEnsureNotificationPermission: () -> Unit = {},
    onSignInWithGoogle: () -> Unit = {},
    onSignOutGoogle: () -> Unit = {},
    onPurchaseOffer: (com.example.billing.SubscriptionPlanOffer) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val activePlaylist by viewModel.activePlaylist.collectAsStateWithLifecycle()
    val allPlaylists by viewModel.allPlaylists.collectAsStateWithLifecycle()
    val channels by viewModel.filteredChannels.collectAsStateWithLifecycle()
    val searchSplit by viewModel.searchResultSplit.collectAsStateWithLifecycle()
    val playlistChannels by viewModel.currentPlaylistChannels.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val selectedCategory by viewModel.selectedCategory.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val playingChannel by viewModel.playingChannel.collectAsStateWithLifecycle()
    val isFullscreen by viewModel.isFullscreen.collectAsStateWithLifecycle()
    val isMiniPlayer by viewModel.isMiniPlayer.collectAsStateWithLifecycle()
    val isInPipMode by viewModel.isInPipMode.collectAsStateWithLifecycle()
    val videoQualityPreset by viewModel.effectiveVideoQualityPreset.collectAsStateWithLifecycle()
    val isBackgroundPlayEnabled by viewModel.effectiveBackgroundPlayEnabled.collectAsStateWithLifecycle()
    val isTurboStreamEnabled by viewModel.effectiveTurboStreamEnabled.collectAsStateWithLifecycle()
    val isNotificationsEnabled by viewModel.isNotificationsEnabled.collectAsStateWithLifecycle()
    val isPremium by viewModel.isPremium.collectAsStateWithLifecycle()
    val premiumStatus by viewModel.premiumStatus.collectAsStateWithLifecycle()
    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()
    val subscriptionOffers by viewModel.subscriptionOffers.collectAsStateWithLifecycle()
    val isLoadingOffers by viewModel.isLoadingOffers.collectAsStateWithLifecycle()
    val billingNotice by viewModel.billingNotice.collectAsStateWithLifecycle()
    val isVerifyingSubscription by viewModel.isVerifyingSubscription.collectAsStateWithLifecycle()
    val isPremiumDialogOpen by viewModel.isPremiumDialogOpen.collectAsStateWithLifecycle()
    val premiumHighlightFeature by viewModel.premiumHighlightFeature.collectAsStateWithLifecycle()
    val pipPlayPauseSignal by viewModel.pipPlayPauseSignal.collectAsStateWithLifecycle()
    val pipDismissSignal by viewModel.pipDismissSignal.collectAsStateWithLifecycle()
    val playbackProgressMap by viewModel.playbackProgressMap.collectAsStateWithLifecycle()

    val scheduleChannel by viewModel.scheduleChannel.collectAsStateWithLifecycle()
    val reminders by viewModel.reminders.collectAsStateWithLifecycle()
    val activeReminderByChannelId = remember(reminders) {
        reminders
            .sortedBy { it.startTime }
            .groupBy { it.channelId }
            .mapValues { (_, list) -> list.firstOrNull()?.programTitle }
    }

    val isImportDialogOpen by viewModel.isImportDialogOpen.collectAsStateWithLifecycle()
    val isPlaylistManagerOpen by viewModel.isPlaylistManagerOpen.collectAsStateWithLifecycle()
    val isScheduleSheetOpen by viewModel.isScheduleSheetOpen.collectAsStateWithLifecycle()
    val isReportDialogOpen by viewModel.isReportDialogOpen.collectAsStateWithLifecycle()
    val reportTargetChannel by viewModel.reportTargetChannel.collectAsStateWithLifecycle()
    val reportPlaybackError by viewModel.reportPlaybackError.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val userMessage by viewModel.userMessage.collectAsStateWithLifecycle()
    val activeReminderAlert by viewModel.activeReminderAlert.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val playlistSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scheduleSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var isPrivacyPolicyOpen by remember { mutableStateOf(false) }

    LaunchedEffect(activeReminderAlert?.programId) {
        if (activeReminderAlert != null) {
            delay(12_000L)
            viewModel.dismissReminderAlert()
        }
    }

    val context = LocalContext.current
    val connectivityManager = remember {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }
    val isOnline = remember {
        connectivityManager?.activeNetwork?.let { network ->
            connectivityManager.getNetworkCapabilities(network)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } ?: true
    }

    LaunchedEffect(userMessage) {
        userMessage?.let { msg ->
            if (!isInPipMode) {
                snackbarHostState.showSnackbar(msg)
            }
            viewModel.clearUserMessage()
        }
    }

    // Back press hierarchy: Fullscreen -> Embedded -> Mini-Player (Premium) / Close Player
    BackHandler(enabled = (isFullscreen || playingChannel != null) && !isInPipMode) {
        when {
            isFullscreen -> viewModel.setFullscreen(false)
            playingChannel != null && !isMiniPlayer && isPremium -> viewModel.enterMiniPlayer()
            playingChannel != null -> viewModel.closePlayer()
        }
    }

    val isFullscreenActive = (isFullscreen || isInPipMode) && playingChannel != null
    var topHeaderHeightPx by remember { mutableIntStateOf(0) }
    var rootWidthPx by remember { mutableIntStateOf(0) }
    var rootHeightPx by remember { mutableIntStateOf(0) }
    var miniDragOffsetX by remember { mutableFloatStateOf(0f) }
    var miniDragOffsetY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(isMiniPlayer) {
        if (!isMiniPlayer) {
            miniDragOffsetX = 0f
            miniDragOffsetY = 0f
        }
    }

    Scaffold(
        modifier = if (isFullscreenActive) {
            modifier.fillMaxSize()
        } else {
            modifier
                .fillMaxSize()
                .statusBarsPadding()
        },
        snackbarHost = {
            if (!isInPipMode) {
                SnackbarHost(snackbarHostState)
            }
        },
        containerColor = if (isFullscreenActive) Color.Black else MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (isFullscreenActive) Modifier else Modifier.padding(innerPadding))
                .onSizeChanged { size ->
                    rootWidthPx = size.width
                    rootHeightPx = size.height
                }
        ) {
            if (!isFullscreenActive) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Header + Optional Offline Banner (dynamically measured for embedded player offset)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onSizeChanged { topHeaderHeightPx = it.height }
                    ) {
                        SoykenHeader(
                            activePlaylist = activePlaylist,
                            searchQuery = searchQuery,
                            themeMode = themeMode,
                            onSearchChange = { viewModel.setSearchQuery(it) },
                            onThemeToggle = {
                                val nextMode = when (themeMode) {
                                    ThemeMode.DARK -> ThemeMode.LIGHT
                                    ThemeMode.LIGHT -> ThemeMode.SYSTEM
                                    ThemeMode.SYSTEM -> ThemeMode.DARK
                                }
                                viewModel.setThemeMode(nextMode)
                            },
                            onOpenImport = { viewModel.openImportDialog() },
                            onOpenPlaylists = { viewModel.openPlaylistManager() },
                            onSendTestNotification = { viewModel.sendTestNotification() },
                            isNotificationsEnabled = isNotificationsEnabled,
                            onNotificationsEnabledChange = { viewModel.setNotificationsEnabled(it) },
                            isBackgroundPlayEnabled = isBackgroundPlayEnabled,
                            onBackgroundPlayChange = { viewModel.setBackgroundPlayEnabled(it) },
                            isTurboStreamEnabled = isTurboStreamEnabled,
                            onTurboStreamChange = { viewModel.setTurboStreamEnabled(it) },
                            isPremium = isPremium,
                            onOpenPremium = { viewModel.openPremiumDialog() },
                            onOpenPrivacyPolicy = { isPrivacyPolicyOpen = true },
                            onOpenReportIssue = {
                                viewModel.openReportDialog(
                                    channel = playingChannel ?: channels.firstOrNull()
                                )
                            },
                            onOpenUpdateApp = {
                                val opened = openPlayStoreUpdatePage(context)
                                if (!opened) {
                                    viewModel.showUserFeedback(
                                        "Bağlantıyı açacak Google Play veya tarayıcı uygulaması bulunamadı."
                                    )
                                }
                            }
                        )

                        if (!isOnline) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF382A00))
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.WifiOff,
                                    contentDescription = null,
                                    tint = SoykenGold,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Çevrimdışı Mod: İnternet bağlantısı tespit edilemedi. Yerel dosyalarınızı ve test kanalını izleyebilirsiniz.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = SoykenGold
                                )
                            }
                        }
                    }

                    // Reserve exact 16:9 slot when player is docked in normal embedded mode
                    if (playingChannel != null && !isMiniPlayer) {
                        Spacer(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                        )
                    }

                    // Category Chips Row
                    CategoryChips(
                        categories = categories,
                        selectedCategory = selectedCategory,
                        onCategorySelect = { viewModel.setCategory(it) }
                    )

                    // Quick Similar Suggestions & Flexible Search Info when searching
                    if (searchQuery.isNotBlank() &&
                        (searchSplit.suggestedTerms.isNotEmpty() || searchSplit.crossCategoryFallbackUsed)
                    ) {
                        SmartSearchInfoBar(
                            suggestedTerms = searchSplit.suggestedTerms,
                            crossCategoryFallbackUsed = searchSplit.crossCategoryFallbackUsed,
                            onSelectSuggestion = { viewModel.setSearchQuery(it) }
                        )
                    }

                    // Channel Count & Quick Info
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (searchQuery.isNotBlank() && searchSplit.similarOptions.isNotEmpty()) {
                                "${searchSplit.directMatches.size} Eşleşme • ${searchSplit.similarOptions.size} Benzer Seçenek"
                            } else {
                                "${channels.size} Kanal Listeleniyor"
                            },
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (selectedCategory != "Tümü" && !searchSplit.crossCategoryFallbackUsed) {
                            Text(
                                text = "Filtre: $selectedCategory",
                                style = MaterialTheme.typography.labelSmall,
                                color = SoykenGold
                            )
                        }
                    }

                    // Channel List or Empty State
                    if (isLoading) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = SoykenGold)
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Kanallar yükleniyor...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    } else if (channels.isEmpty()) {
                        EmptyChannelsView(
                            isSearch = searchQuery.isNotBlank(),
                            isFavorites = selectedCategory == "⭐ Favoriler",
                            onClearSearch = { viewModel.setSearchQuery("") },
                            onOpenImport = { viewModel.openImportDialog() },
                            modifier = Modifier
                                .fillMaxSize()
                                .weight(1f)
                        )
                    } else {
                        val bottomPadding = if (playingChannel != null && isMiniPlayer) 160.dp else 8.dp
                        val isSearchActive = searchQuery.isNotBlank()
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .testTag("channels_list"),
                            contentPadding = PaddingValues(
                                start = 16.dp,
                                end = 16.dp,
                                top = 8.dp,
                                bottom = bottomPadding
                            ),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (!isSearchActive) {
                                items(channels, key = { it.id }) { channel ->
                                    val normalizedUrl = M3uParser.toHlsStreamUrl(channel.streamUrl)
                                    ChannelCard(
                                        channel = channel,
                                        isPlaying = playingChannel?.id == channel.id,
                                        onPlay = { viewModel.playChannel(channel) },
                                        onToggleFavorite = { viewModel.toggleFavorite(channel) },
                                        onOpenSchedule = { viewModel.openSchedule(channel) },
                                        onReportIssue = { viewModel.openReportDialog(channel) },
                                        playbackProgress = playbackProgressMap[normalizedUrl],
                                        activeReminderTime = activeReminderByChannelId[channel.id]
                                    )
                                }
                            } else {
                                items(searchSplit.directMatches, key = { "direct_${it.id}" }) { channel ->
                                    val normalizedUrl = M3uParser.toHlsStreamUrl(channel.streamUrl)
                                    ChannelCard(
                                        channel = channel,
                                        isPlaying = playingChannel?.id == channel.id,
                                        onPlay = { viewModel.playChannel(channel) },
                                        onToggleFavorite = { viewModel.toggleFavorite(channel) },
                                        onOpenSchedule = { viewModel.openSchedule(channel) },
                                        onReportIssue = { viewModel.openReportDialog(channel) },
                                        playbackProgress = playbackProgressMap[normalizedUrl],
                                        activeReminderTime = activeReminderByChannelId[channel.id]
                                    )
                                }

                                if (searchSplit.similarOptions.isNotEmpty()) {
                                    item(key = "similar_options_header") {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = if (searchSplit.directMatches.isNotEmpty()) 8.dp else 0.dp, bottom = 2.dp)
                                                .testTag("similar_channels_section_header"),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.ManageSearch,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = if (searchSplit.directMatches.isEmpty()) {
                                                    "Yazdığınıza En Yakın & Benzer Seçenekler"
                                                } else {
                                                    "Yazdığınıza Benzer Seçenekler"
                                                },
                                                style = MaterialTheme.typography.titleSmall.copy(
                                                    fontWeight = FontWeight.Bold
                                                ),
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }

                                    items(searchSplit.similarOptions, key = { "sim_${it.id}" }) { channel ->
                                        val normalizedUrl = M3uParser.toHlsStreamUrl(channel.streamUrl)
                                        ChannelCard(
                                            channel = channel,
                                            isPlaying = playingChannel?.id == channel.id,
                                            onPlay = { viewModel.playChannel(channel) },
                                            onToggleFavorite = { viewModel.toggleFavorite(channel) },
                                            onOpenSchedule = { viewModel.openSchedule(channel) },
                                            onReportIssue = { viewModel.openReportDialog(channel) },
                                            playbackProgress = playbackProgressMap[normalizedUrl],
                                            activeReminderTime = activeReminderByChannelId[channel.id]
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Single Persistent Video Player across Embedded, Fullscreen, Draggable Mini-Player, and System PiP!
            playingChannel?.let { channel ->
                val playerPlacementModifier = when {
                    isFullscreenActive -> Modifier.fillMaxSize()
                    isMiniPlayer -> {
                        Modifier
                            .align(Alignment.BottomEnd)
                            .navigationBarsPadding()
                            .padding(end = 12.dp, bottom = 12.dp)
                            .offset {
                                IntOffset(
                                    miniDragOffsetX.roundToInt(),
                                    miniDragOffsetY.roundToInt()
                                )
                            }
                            .width(236.dp)
                            .shadow(14.dp, RoundedCornerShape(14.dp))
                            .clip(RoundedCornerShape(14.dp))
                            .border(1.5.dp, SoykenGold, RoundedCornerShape(14.dp))
                            .pointerInput(rootWidthPx, rootHeightPx) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    val maxLeft = -(rootWidthPx - size.width - 24).coerceAtLeast(0).toFloat()
                                    val maxUp = -(rootHeightPx - size.height - 24).coerceAtLeast(0).toFloat()
                                    miniDragOffsetX = (miniDragOffsetX + dragAmount.x).coerceIn(maxLeft, 0f)
                                    miniDragOffsetY = (miniDragOffsetY + dragAmount.y).coerceIn(maxUp, 0f)
                                }
                            }
                    }
                    else -> {
                        Modifier
                            .align(Alignment.TopCenter)
                            .offset { IntOffset(0, topHeaderHeightPx) }
                            .fillMaxWidth()
                    }
                }

                VideoPlayer(
                    channel = channel,
                    isFullscreen = isFullscreen,
                    onToggleFullscreen = { viewModel.toggleFullscreen() },
                    onClose = { viewModel.closePlayer() },
                    onNextChannel = { viewModel.nextChannel() },
                    onPreviousChannel = { viewModel.previousChannel() },
                    initialPositionMs = viewModel.getInitialPositionMs(channel),
                    onSaveProgress = { pos, dur, isLive ->
                        viewModel.savePlaybackProgress(channel, pos, dur, isLive)
                    },
                    onResetProgress = { viewModel.resetChannelProgress(channel) },
                    isMiniPlayer = isMiniPlayer,
                    isInPipMode = isInPipMode,
                    qualityPreset = videoQualityPreset,
                    onQualityPresetChange = { viewModel.setVideoQualityPreset(it) },
                    isBackgroundPlayEnabled = isBackgroundPlayEnabled,
                    onBackgroundPlayChange = { viewModel.setBackgroundPlayEnabled(it) },
                    isTurboStreamEnabled = isTurboStreamEnabled,
                    onTurboStreamChange = { viewModel.setTurboStreamEnabled(it) },
                    onEnterMiniPlayer = { viewModel.enterMiniPlayer() },
                    onExitMiniPlayer = { viewModel.exitMiniPlayer() },
                    onRequestPipMode = onRequestPipMode,
                    pipPlayPauseSignal = pipPlayPauseSignal,
                    pipDismissSignal = pipDismissSignal,
                    onPlayingStateChanged = { viewModel.updateCurrentlyPlaying(it) },
                    onReportIssue = { errorMsg ->
                        viewModel.openReportDialog(channel = channel, playbackError = errorMsg)
                    },
                    isPremium = isPremium,
                    onOpenPremium = { feature -> viewModel.openPremiumDialog(feature) },
                    modifier = playerPlacementModifier
                )
            }

            // Heads-Up Reminder Notification Banner (always visible at scheduled HH:mm)
            AnimatedVisibility(
                visible = !isInPipMode && activeReminderAlert != null,
                enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .then(if (isFullscreenActive) Modifier.statusBarsPadding() else Modifier)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .testTag("reminder_heads_up_banner")
            ) {
                activeReminderAlert?.let { alert ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .shadow(16.dp, RoundedCornerShape(16.dp))
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .border(1.5.dp, SoykenGold, RoundedCornerShape(16.dp))
                            .clickable {
                                if (alert.channelId > 0L) {
                                    viewModel.openReminderAlertChannel(alert)
                                } else {
                                    viewModel.dismissReminderAlert()
                                }
                            }
                            .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(SoykenGold.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AlarmOn,
                                contentDescription = null,
                                tint = SoykenGold,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${alert.channelName} • ${alert.programTitle}",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.ExtraBold
                                ),
                                color = SoykenGold,
                                maxLines = 1
                            )
                            Text(
                                text = "Saat ${alert.programTitle} hatırlatma zamanı geldi",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        if (alert.channelId > 0L) {
                            Button(
                                onClick = { viewModel.openReminderAlertChannel(alert) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                modifier = Modifier.height(36.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = SoykenGold,
                                    contentColor = Color.Black
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "İzle",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }
                        }

                        IconButton(
                            onClick = { viewModel.dismissReminderAlert() },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Bildirimi kapat",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        // Saat & Dakika Hatırlatıcı Bottom Sheet
        if (!isInPipMode && isScheduleSheetOpen && scheduleChannel != null) {
            ScheduleBottomSheet(
                channel = scheduleChannel,
                reminders = reminders,
                sheetState = scheduleSheetState,
                onDismiss = { viewModel.closeSchedule() },
                onSaveTimeReminder = { hour, minute ->
                    onEnsureNotificationPermission()
                    scheduleChannel?.let { ch ->
                        viewModel.setTimeReminder(ch, hour, minute)
                    }
                },
                onRemoveReminder = { reminder ->
                    viewModel.removeReminder(reminder)
                },
                isPremium = isPremium
            )
        }

        // Stream Issue Report Dialog (Sorun Bildir -> polazmaison@gmail.com)
        if (!isInPipMode) {
            IssueReportDialog(
                isOpen = isReportDialogOpen,
                channel = reportTargetChannel,
                availableChannels = playlistChannels.ifEmpty { channels },
                playlistName = activePlaylist?.name,
                playbackError = reportPlaybackError,
                onSelectChannel = { viewModel.selectReportChannel(it) },
                onDismiss = { viewModel.closeReportDialog() },
                onSubmitReport = { issueType, userNote, duplicateChannels ->
                    val outcome = dispatchIssueReportEmail(
                        context = context,
                        channel = reportTargetChannel,
                        playlistName = activePlaylist?.name,
                        issueType = issueType,
                        userNote = userNote,
                        playbackError = reportPlaybackError,
                        duplicateChannels = duplicateChannels
                    )
                    viewModel.closeReportDialog()
                    when (outcome) {
                        EmailDispatchOutcome.EMAIL_CLIENT_OPENED -> {
                            viewModel.showUserFeedback(
                                "Bildirim hazırlandı ($SUPPORT_REPORT_EMAIL). E-posta uygulamanızda Gönder butonuna dokunun."
                            )
                        }
                        EmailDispatchOutcome.COPIED_TO_CLIPBOARD -> {
                            viewModel.showUserFeedback(
                                "E-posta uygulaması bulunamadı; bildirim metni ($SUPPORT_REPORT_EMAIL) panoya kopyalandı."
                            )
                        }
                    }
                }
            )
        }

        // Import Dialog (URL, File, Text)
        if (!isInPipMode) {
            ImportDialog(
                isOpen = isImportDialogOpen,
                onDismiss = { viewModel.closeImportDialog() },
                onImportUrl = { name, url -> viewModel.importFromUrl(name, url) },
                onImportStream = { name, stream -> viewModel.importFromStream(name, stream) },
                onImportText = { name, text -> viewModel.importFromString(name, text) }
            )
        }

        // Playlist Manager Sheet
        if (!isInPipMode && isPlaylistManagerOpen) {
            PlaylistManagerSheet(
                playlists = allPlaylists,
                activePlaylistId = activePlaylist?.id,
                sheetState = playlistSheetState,
                onDismiss = { viewModel.closePlaylistManager() },
                onSelectPlaylist = { id -> viewModel.switchActivePlaylist(id) },
                onDeletePlaylist = { playlist -> viewModel.deletePlaylist(playlist) },
                onAddNewPlaylist = {
                    viewModel.closePlaylistManager()
                    viewModel.openImportDialog()
                },
                isPremium = isPremium
            )
        }

        // Soyken Premium Subscription Dialog (Phone & Android TV Adaptive)
        if (!isInPipMode) {
            PremiumPaywallDialog(
                isOpen = isPremiumDialogOpen,
                highlightedFeature = premiumHighlightFeature,
                premiumStatus = premiumStatus,
                currentUser = currentUser,
                offers = subscriptionOffers,
                isLoadingOffers = isLoadingOffers,
                isVerifying = isVerifyingSubscription,
                billingNotice = billingNotice,
                onDismiss = { viewModel.closePremiumDialog() },
                onSignInWithGoogle = onSignInWithGoogle,
                onSignOut = onSignOutGoogle,
                onPurchaseOffer = onPurchaseOffer,
                onRestorePurchases = { viewModel.restoreAndVerifyPurchases() },
                onRetryLoadOffers = { viewModel.reloadSubscriptionOffers() }
            )
        }

        // Privacy Policy Dialog
        if (!isInPipMode) {
            PrivacyPolicyDialog(
                isOpen = isPrivacyPolicyOpen,
                onDismiss = { isPrivacyPolicyOpen = false }
            )
        }
    }
}

@Composable
fun EmptyChannelsView(
    isSearch: Boolean,
    isFavorites: Boolean,
    onClearSearch: () -> Unit,
    onOpenImport: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isSearch) Icons.Default.SearchOff else Icons.Default.LiveTv,
                    contentDescription = null,
                    tint = SoykenGold,
                    modifier = Modifier.size(40.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = when {
                    isSearch -> "Eşleşen Kanal Bulunamadı"
                    isFavorites -> "Henüz Favori Kanal Eklenmedi"
                    else -> "Oynatma Listesinde Kanal Yok"
                },
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = when {
                    isSearch -> "Arama kriterlerinizi değiştirip tekrar deneyin."
                    isFavorites -> "Kanalların yanındaki yıldız simgesine dokunarak favorilerinize ekleyebilirsiniz."
                    else -> "HLS (M3U8) yayın bağlantısı girerek veya cihazınızdan .m3u8 / .m3u dosyası yükleyerek başlayın."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(20.dp))

            if (isSearch) {
                Button(
                    onClick = onClearSearch,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SoykenGold,
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Aramayı Temizle", fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = onOpenImport,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SoykenGold,
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("HLS (M3U8) Listesi Ekle", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun SmartSearchInfoBar(
    suggestedTerms: List<String>,
    crossCategoryFallbackUsed: Boolean,
    onSelectSuggestion: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.75f),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("smart_search_info_bar")
    ) {
        if (crossCategoryFallbackUsed) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.ManageSearch,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Seçili kategoride bulunamadı; tüm listedeki yakın sonuçlar gösteriliyor",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (suggestedTerms.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
            }
        }

        if (suggestedTerms.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Benzer:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                suggestedTerms.forEach { term ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable { onSelectSuggestion(term) }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = term,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}
