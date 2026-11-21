package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.Channel
import com.example.data.network.CloudflareDns
import com.example.data.network.CloudflareOkHttpDataSource
import com.example.ui.components.StreamProbe
import com.example.ui.components.VideoQualityPreset
import com.example.ui.viewmodel.MainViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StartupRobolectricTest {

    @Test
    fun applicationAndCloudflareDnsInitializeCleanlyOnAndroidRuntime() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("Soyken Play", context.getString(R.string.app_name))

        val app = context as SoykenPlayApplication
        val imageLoader = app.newImageLoader()
        assertNotNull(imageLoader)

        val client = CloudflareDns.createHttpClient()
        assertNotNull(client)

        val dataSource = CloudflareOkHttpDataSource.Factory().createDataSource()
        assertNotNull(dataSource)

        assertEquals(
            "https://iptv.example.com",
            StreamProbe.extractOrigin("https://iptv.example.com/live/ch1.m3u8")
        )
    }

    @Test
    fun viewModelPersistsVideoQualityAndManagesMiniPlayerAndPipStates() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("soyken_play_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        val vm = MainViewModel(app)
        assertEquals(VideoQualityPreset.AUTO, vm.videoQualityPreset.value)
        assertTrue(vm.isBackgroundPlayEnabled.value)
        assertFalse(vm.isMiniPlayer.value)
        assertFalse(vm.isInPipMode.value)

        // Persist 720p preset and background play setting
        vm.setVideoQualityPreset(VideoQualityPreset.HD_720P)
        assertEquals(VideoQualityPreset.HD_720P, vm.videoQualityPreset.value)

        vm.setBackgroundPlayEnabled(false)
        assertFalse(vm.isBackgroundPlayEnabled.value)

        // Verify persistence across new MainViewModel instance
        val vmRestored = MainViewModel(app)
        assertEquals(VideoQualityPreset.HD_720P, vmRestored.videoQualityPreset.value)
        assertFalse(vmRestored.isBackgroundPlayEnabled.value)

        vmRestored.setBackgroundPlayEnabled(true)
        assertTrue(vmRestored.isBackgroundPlayEnabled.value)

        // Mini-player requires an active playing channel
        vmRestored.enterMiniPlayer()
        assertFalse(vmRestored.isMiniPlayer.value)

        val testChannel = Channel(
            id = 101L,
            playlistId = 1L,
            name = "TRT 1 HD",
            streamUrl = "https://tv-trt1.medya.trt.com.tr/master.m3u8",
            category = "Ulusal",
            channelNumber = 1
        )
        vmRestored.playChannel(testChannel)
        assertEquals(testChannel.id, vmRestored.playingChannel.value?.id)

        // Fullscreen -> MiniPlayer mutual exclusivity
        vmRestored.setFullscreen(true)
        assertTrue(vmRestored.isFullscreen.value)
        assertFalse(vmRestored.isMiniPlayer.value)

        vmRestored.enterMiniPlayer()
        assertTrue(vmRestored.isMiniPlayer.value)
        assertFalse(vmRestored.isFullscreen.value)

        vmRestored.toggleFullscreen()
        assertTrue(vmRestored.isFullscreen.value)
        assertFalse(vmRestored.isMiniPlayer.value)

        // PiP mode & remote signals
        vmRestored.setInPipMode(true)
        assertTrue(vmRestored.isInPipMode.value)
        val initialPauseSig = vmRestored.pipPlayPauseSignal.value
        vmRestored.triggerPipPlayPause()
        assertEquals(initialPauseSig + 1, vmRestored.pipPlayPauseSignal.value)

        val initialDismissSig = vmRestored.pipDismissSignal.value
        vmRestored.notifyPipDismissed()
        assertEquals(initialDismissSig + 1, vmRestored.pipDismissSignal.value)

        // Closing player resets mini-player and fullscreen
        vmRestored.enterMiniPlayer()
        vmRestored.closePlayer()
        assertNull(vmRestored.playingChannel.value)
        assertFalse(vmRestored.isMiniPlayer.value)
        assertFalse(vmRestored.isFullscreen.value)
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test
    fun codecResourceReclaimedErrorsAreClassifiedAccurately() {
        val reclaimedException = androidx.media3.common.PlaybackException(
            "MediaCodecVideoRenderer error",
            IllegalStateException("Released by resource manager"),
            androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED
        )
        assertTrue(com.example.ui.components.isCodecResourceOrDecoderError(reclaimedException))

        val genericCauseWithResourceMsg = androidx.media3.common.PlaybackException(
            "Unexpected runtime error",
            RuntimeException("android.media.MediaCodec: Released by resource manager"),
            androidx.media3.common.PlaybackException.ERROR_CODE_UNSPECIFIED
        )
        assertTrue(com.example.ui.components.isCodecResourceOrDecoderError(genericCauseWithResourceMsg))

        val httpException = androidx.media3.common.PlaybackException(
            "Bad HTTP status",
            java.io.IOException("HTTP 404"),
            androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        )
        assertFalse(com.example.ui.components.isCodecResourceOrDecoderError(httpException))

        // Verify contention-free C2 software decoder is prioritized ahead of goldfish/hardware decoder
        val hwGoldfish = androidx.media3.exoplayer.mediacodec.MediaCodecInfo.newInstance(
            /* name = */ "c2.goldfish.h264.decoder",
            /* mimeType = */ "video/avc",
            /* codecMimeType = */ "video/avc",
            /* capabilities = */ null,
            /* hardwareAccelerated = */ true,
            /* softwareOnly = */ false,
            /* vendor = */ true,
            /* forceDisableAdaptive = */ false,
            /* forceSecure = */ false
        )
        val swAndroidAvc = androidx.media3.exoplayer.mediacodec.MediaCodecInfo.newInstance(
            /* name = */ "c2.android.avc.decoder",
            /* mimeType = */ "video/avc",
            /* codecMimeType = */ "video/avc",
            /* capabilities = */ null,
            /* hardwareAccelerated = */ false,
            /* softwareOnly = */ true,
            /* vendor = */ false,
            /* forceDisableAdaptive = */ false,
            /* forceSecure = */ false
        )
        val reordered = com.example.ui.components.prioritizeContentionFreeVideoDecoders(
            listOf(hwGoldfish, swAndroidAvc)
        )
        assertEquals("c2.android.avc.decoder", reordered[0].name)
        assertEquals("c2.goldfish.h264.decoder", reordered[1].name)

        // Verify physical GPU/VPU vendor hardware decoder stays ahead of software decoder on real phones/TVs
        val hwQualcomm = androidx.media3.exoplayer.mediacodec.MediaCodecInfo.newInstance(
            /* name = */ "c2.qti.avc.decoder",
            /* mimeType = */ "video/avc",
            /* codecMimeType = */ "video/avc",
            /* capabilities = */ null,
            /* hardwareAccelerated = */ true,
            /* softwareOnly = */ false,
            /* vendor = */ true,
            /* forceDisableAdaptive = */ false,
            /* forceSecure = */ false
        )
        val physicalOrdered = com.example.ui.components.prioritizeContentionFreeVideoDecoders(
            listOf(swAndroidAvc, hwQualcomm, hwGoldfish),
            forcePhysicalDeviceMode = true
        )
        assertEquals("c2.qti.avc.decoder", physicalOrdered[0].name)
        assertEquals("c2.android.avc.decoder", physicalOrdered[1].name)
        assertEquals("c2.goldfish.h264.decoder", physicalOrdered[2].name)

        // Verify Cuttlefish / Cloud Streaming Emulator virtualized v4l2 & virtio codecs are demoted behind c2.android.*
        val hwCuttlefishV4l2 = androidx.media3.exoplayer.mediacodec.MediaCodecInfo.newInstance(
            /* name = */ "c2.v4l2.avc.decoder",
            /* mimeType = */ "video/avc",
            /* codecMimeType = */ "video/avc",
            /* capabilities = */ null,
            /* hardwareAccelerated = */ true,
            /* softwareOnly = */ false,
            /* vendor = */ true,
            /* forceDisableAdaptive = */ false,
            /* forceSecure = */ false
        )
        val cloudOrdered = com.example.ui.components.prioritizeContentionFreeVideoDecoders(
            listOf(hwCuttlefishV4l2, swAndroidAvc),
            forcePhysicalDeviceMode = true
        )
        assertEquals("c2.android.avc.decoder", cloudOrdered[0].name)
        assertEquals("c2.v4l2.avc.decoder", cloudOrdered[1].name)

        // Verify sticky forceSoftwareFirst recovery promotes c2.android.* ahead of physical HW after reclamation
        val reclaimedFallback = com.example.ui.components.prioritizeContentionFreeVideoDecoders(
            listOf(hwQualcomm, swAndroidAvc),
            forcePhysicalDeviceMode = true,
            forceSoftwareFirst = true
        )
        assertEquals("c2.android.avc.decoder", reclaimedFallback[0].name)
        assertEquals("c2.qti.avc.decoder", reclaimedFallback[1].name)
    }

    @Test
    fun issueReportEmailIntentAndViewModelStateTargetPolazMaisonEmail() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = MainViewModel(app)

        val channel = Channel(
            id = 77L,
            playlistId = 1L,
            name = "Show TV HD",
            streamUrl = "https://cdn.example.com/live/showtv.m3u8",
            category = "Ulusal",
            channelNumber = 18
        )

        assertFalse(vm.isReportDialogOpen.value)
        assertNull(vm.reportTargetChannel.value)

        vm.openReportDialog(channel = channel, playbackError = "HTTP 404 Not Found")
        assertTrue(vm.isReportDialogOpen.value)
        assertEquals(18, vm.reportTargetChannel.value?.channelNumber)
        assertEquals("Show TV HD", vm.reportTargetChannel.value?.name)
        assertEquals("HTTP 404 Not Found", vm.reportPlaybackError.value)

        val intent = com.example.ui.components.buildIssueReportEmailIntent(
            channel = channel,
            playlistName = "Ulusal Paket",
            issueType = com.example.ui.components.StreamIssueType.STREAM_NOT_WORKING,
            userNote = "Kanal açılmıyor",
            playbackError = "HTTP 404 Not Found",
            appVersion = "15.0"
        )
        assertEquals(android.content.Intent.ACTION_SENDTO, intent.action)
        assertEquals("mailto", intent.data?.scheme)
        assertTrue(intent.data.toString().startsWith("mailto:polazmaison@gmail.com?"))

        val recipients = intent.getStringArrayExtra(android.content.Intent.EXTRA_EMAIL)
        assertNotNull(recipients)
        assertEquals("polazmaison@gmail.com", recipients!![0])

        val subject = intent.getStringExtra(android.content.Intent.EXTRA_SUBJECT).orEmpty()
        assertTrue(subject.contains("Kanal #18"))
        assertTrue(subject.contains("Show TV HD"))

        val body = intent.getStringExtra(android.content.Intent.EXTRA_TEXT).orEmpty()
        assertTrue(body.contains("Kanal Numarası: #18"))
        assertTrue(body.contains("Kanal Adı: Show TV HD"))
        assertTrue(body.contains("HTTP 404 Not Found"))

        vm.closeReportDialog()
        assertFalse(vm.isReportDialogOpen.value)
        assertNull(vm.reportPlaybackError.value)
    }

    @Test
    fun notificationSettingPersistsForTopRightDropdownMenu() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("soyken_play_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        val vm = MainViewModel(app)
        assertTrue(vm.isNotificationsEnabled.value)
        assertTrue(com.example.service.NotificationHelper.areNotificationsEnabledInPrefs(app))

        vm.setNotificationsEnabled(false)
        assertFalse(vm.isNotificationsEnabled.value)
        assertFalse(com.example.service.NotificationHelper.areNotificationsEnabledInPrefs(app))

        val restoredVm = MainViewModel(app)
        assertFalse(restoredVm.isNotificationsEnabled.value)

        // Sending a test notification re-enables notifications automatically
        restoredVm.sendTestNotification()
        assertTrue(restoredVm.isNotificationsEnabled.value)
        assertTrue(com.example.service.NotificationHelper.areNotificationsEnabledInPrefs(app))
    }

    @Test
    fun viewModelSmartSearchFindsPunctuationInsensitiveAndSimilarChannels() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = MainViewModel(app)
        vm.setSearchQuery("spiderman")
        assertEquals("spiderman", vm.searchQuery.value)
        vm.setSearchQuery("")
        assertEquals("", vm.searchQuery.value)
    }

    @Test
    fun turboStreamSettingPersistsAndSmartCacheFactoryInitializes() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("soyken_play_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        val vm = MainViewModel(app)
        assertTrue(vm.isTurboStreamEnabled.value)

        vm.setTurboStreamEnabled(false)
        assertFalse(vm.isTurboStreamEnabled.value)

        val restoredVm = MainViewModel(app)
        assertFalse(restoredVm.isTurboStreamEnabled.value)

        restoredVm.setTurboStreamEnabled(true)
        assertTrue(restoredVm.isTurboStreamEnabled.value)

        val smartFactory = com.example.data.network.StreamAccelerator.createSmartCacheDataSourceFactory(
            context = app,
            upstreamFactory = CloudflareOkHttpDataSource.Factory(),
            isTurboEnabled = { restoredVm.isTurboStreamEnabled.value }
        )
        assertNotNull(smartFactory.createDataSource())
    }

    @Test
    fun customHourMinuteReminderSchedulesAlarmAndCancelsCleanly() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val alarmManager = app.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val shadowAlarmManager = org.robolectric.Shadows.shadowOf(alarmManager)

        val vm = MainViewModel(app)
        val channel = Channel(
            id = 505L,
            playlistId = 1L,
            name = "TRT Spor HD",
            streamUrl = "https://cdn.example.com/live/trtspor.m3u8",
            category = "Spor",
            channelNumber = 12
        )

        vm.openSchedule(channel)
        assertTrue(vm.isScheduleSheetOpen.value)
        assertEquals(505L, vm.scheduleChannel.value?.id)

        vm.setTimeReminder(channel = channel, hourOfDay = 21, minute = 45)

        val deadline = System.currentTimeMillis() + 3_000L
        while (vm.reminders.value.none { it.channelId == 505L } && System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(25)
        }

        assertFalse(vm.isScheduleSheetOpen.value)
        val savedReminder = vm.reminders.value.firstOrNull { it.channelId == 505L }
        assertNotNull(savedReminder)
        assertEquals("21:45", savedReminder?.programTitle)
        assertTrue(shadowAlarmManager.scheduledAlarms.isNotEmpty())

        vm.removeReminder(savedReminder!!)
        val removeDeadline = System.currentTimeMillis() + 3_000L
        while (vm.reminders.value.any { it.channelId == 505L } && System.currentTimeMillis() < removeDeadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(25)
        }
        assertTrue(vm.reminders.value.none { it.channelId == 505L })

        // Verify precision ReminderScheduler posts notification when scheduled time arrives
        val notificationManager = app.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val shadowNotificationManager = org.robolectric.Shadows.shadowOf(notificationManager)
        notificationManager.cancelAll()

        val dueReminder = com.example.data.model.ProgramReminder(
            programId = "ch_505_23_15",
            channelId = 505L,
            channelName = "TRT Spor HD",
            programTitle = "23:15",
            startTime = System.currentTimeMillis() + 120L
        )
        kotlinx.coroutines.runBlocking {
            com.example.data.local.AppDatabase.getInstance(app).reminderDao().insertReminder(dueReminder)
        }
        com.example.service.ReminderScheduler.schedule(app, dueReminder)

        val fireDeadline = System.currentTimeMillis() + 3_000L
        while (shadowNotificationManager.allNotifications.isEmpty() && System.currentTimeMillis() < fireDeadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(30)
        }
        assertEquals(1, shadowNotificationManager.allNotifications.size)

        // Verify duplicate receiver invocation is ignored once precision scheduler already fired
        kotlinx.coroutines.runBlocking {
            com.example.service.ReminderScheduler.fireReminderFromReceiver(
                context = app,
                reminderId = dueReminder.programId,
                channelId = dueReminder.channelId,
                channelName = dueReminder.channelName,
                reminderTime = dueReminder.programTitle,
                notificationId = dueReminder.programId.hashCode()
            )
        }
        assertEquals(1, shadowNotificationManager.allNotifications.size)
    }
}



