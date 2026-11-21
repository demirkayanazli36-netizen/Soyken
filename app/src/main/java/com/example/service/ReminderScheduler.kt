package com.example.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.example.data.local.AppDatabase
import com.example.data.model.ProgramReminder
import com.example.receiver.ReminderBroadcastReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Multi-engine scheduler for channel HH:mm reminders (100% Google Play Policy compliant):
 * 1. Main-Looper 1-second wall-clock Handler ticker while app process is alive.
 * 2. Dynamic OS BroadcastReceiver for Intent.ACTION_TIME_TICK / ACTION_TIME_CHANGED.
 * 3. Millisecond coroutine sleeper dispatching onto Main Thread.
 * 4. Play-Store-compliant AlarmManager.setWindow / setAndAllowWhileIdle wakeup backup
 *    (requiring zero restricted exact-alarm permissions).
 * All paths converge on deliverReminderOnceOnMain() on the Main Thread using the exact same
 * NotificationHelper.showProgramReminderNotification() call as Test Notifications.
 */
object ReminderScheduler {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val isInitialized = AtomicBoolean(false)

    private val pendingReminders = ConcurrentHashMap<String, ProgramReminder>()
    private val activeTimerJobs = ConcurrentHashMap<String, Job>()
    private val firedTokens = ConcurrentHashMap.newKeySet<String>()

    private val _firedEvents = MutableSharedFlow<ProgramReminder>(extraBufferCapacity = 16)
    val firedEvents: SharedFlow<ProgramReminder> = _firedEvents.asSharedFlow()

    @Volatile
    private var appContextRef: Context? = null

    private val mainTickerRunnable = object : Runnable {
        override fun run() {
            val ctx = appContextRef
            if (ctx != null && pendingReminders.isNotEmpty()) {
                checkDueRemindersOnMain(ctx)
            }
            if (pendingReminders.isNotEmpty()) {
                mainHandler.postDelayed(this, 1_000L)
            }
        }
    }

    private val systemTimeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val ctx = context?.applicationContext ?: appContextRef ?: return
            mainHandler.post {
                checkDueRemindersOnMain(ctx)
            }
        }
    }

    private fun ensureInitialized(context: Context) {
        val appContext = context.applicationContext
        appContextRef = appContext
        if (isInitialized.compareAndSet(false, true)) {
            NotificationHelper.createNotificationChannels(appContext)
            runCatching {
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_TIME_TICK)
                    addAction(Intent.ACTION_TIME_CHANGED)
                    addAction(Intent.ACTION_TIMEZONE_CHANGED)
                }
                ContextCompat.registerReceiver(
                    appContext,
                    systemTimeReceiver,
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
            }
        }
    }

    private fun startMainTicker() {
        mainHandler.post {
            mainHandler.removeCallbacks(mainTickerRunnable)
            if (pendingReminders.isNotEmpty()) {
                mainHandler.postDelayed(mainTickerRunnable, 1_000L)
            }
        }
    }

    private fun checkDueRemindersOnMain(appContext: Context) {
        val now = System.currentTimeMillis()
        val dueList = pendingReminders.values.filter { it.startTime <= now }
        dueList.forEach { reminder ->
            deliverReminderOnceOnMain(appContext, reminder)
        }
    }

    fun bootstrapAsync(context: Context) {
        val appContext = context.applicationContext
        ensureInitialized(appContext)
        scope.launch(Dispatchers.IO) {
            runCatching {
                val dao = AppDatabase.getInstance(appContext).reminderDao()
                dao.deleteLegacyProgramReminders()
                val now = System.currentTimeMillis()
                dao.deleteExpiredReminders(now - 300_000L)
                val stored = dao.getAllRemindersSync()
                stored.forEach { reminder ->
                    if (reminder.startTime <= now) {
                        withContext(Dispatchers.Main) {
                            deliverReminderOnceOnMain(appContext, reminder)
                        }
                    } else {
                        schedule(appContext, reminder)
                    }
                }
            }
        }
    }

    fun schedule(context: Context, reminder: ProgramReminder) {
        val appContext = context.applicationContext
        ensureInitialized(appContext)
        // Clear any old fired token for this exact startTime if re-scheduled
        pendingReminders[reminder.programId] = reminder
        scheduleSystemAlarmBackup(appContext, reminder)
        schedulePrecisionTimer(appContext, reminder)
        startMainTicker()
    }

    fun cancel(context: Context, reminderId: String) {
        val appContext = context.applicationContext
        ensureInitialized(appContext)
        pendingReminders.remove(reminderId)
        activeTimerJobs.remove(reminderId)?.cancel()
        cancelSystemAlarmBackup(appContext, reminderId)
    }

    private fun schedulePrecisionTimer(appContext: Context, reminder: ProgramReminder) {
        activeTimerJobs.remove(reminder.programId)?.cancel()
        val job = scope.launch {
            while (true) {
                val remainingMs = reminder.startTime - System.currentTimeMillis()
                if (remainingMs <= 0L) break
                val sleepChunkMs = when {
                    remainingMs > 15_000L -> 4_000L
                    remainingMs > 1_500L -> remainingMs - 800L
                    else -> remainingMs
                }
                delay(sleepChunkMs.coerceAtLeast(15L))
            }
            withContext(Dispatchers.Main) {
                deliverReminderOnceOnMain(appContext, reminder)
            }
        }
        activeTimerJobs[reminder.programId] = job
    }

    suspend fun fireReminderFromReceiver(
        context: Context,
        reminderId: String?,
        channelId: Long,
        channelName: String,
        reminderTime: String,
        notificationId: Int,
        scheduledStartTime: Long = 0L
    ) {
        val appContext = context.applicationContext
        ensureInitialized(appContext)
        val wakeLock = acquireBriefWakeLock(appContext)
        try {
            val resolvedId = reminderId?.takeIf { it.isNotBlank() } ?: "rx_$notificationId"
            val existing = pendingReminders[resolvedId]
            val effectiveStartTime = when {
                scheduledStartTime > 0L -> scheduledStartTime
                existing != null -> existing.startTime
                else -> 0L
            }

            if (existing == null && !reminderId.isNullOrBlank()) {
                // If process was cold-started by AlarmManager, verify row still exists in Room
                // unless already present in memory; if user cancelled it earlier, skip firing.
                val existsInDb = runCatching {
                    AppDatabase.getInstance(appContext).reminderDao().hasReminder(reminderId)
                }.getOrDefault(true)
                val token = "${resolvedId}_$effectiveStartTime"
                if (!existsInDb && firedTokens.contains(token)) {
                    return
                }
                if (!existsInDb && effectiveStartTime > 0L) {
                    // Cancelled by user while app was closed
                    return
                }
            }

            val reminder = existing ?: ProgramReminder(
                programId = resolvedId,
                channelId = channelId,
                channelName = channelName,
                programTitle = reminderTime,
                startTime = effectiveStartTime
            )

            withContext(Dispatchers.Main) {
                deliverReminderOnceOnMain(appContext, reminder)
            }
        } finally {
            runCatching {
                if (wakeLock?.isHeld == true) wakeLock.release()
            }
        }
    }

    private fun deliverReminderOnceOnMain(appContext: Context, reminder: ProgramReminder) {
        val token = "${reminder.programId}_${reminder.startTime}"
        if (!firedTokens.add(token)) {
            return
        }

        pendingReminders.remove(reminder.programId)
        activeTimerJobs.remove(reminder.programId)?.cancel()
        cancelSystemAlarmBackup(appContext, reminder.programId)

        scope.launch(Dispatchers.IO) {
            runCatching {
                AppDatabase.getInstance(appContext).reminderDao().deleteReminderById(reminder.programId)
            }
        }

        val notifId = stableNotificationId(reminder.programId)
        if (NotificationHelper.areNotificationsEnabledInPrefs(appContext)) {
            val posted = NotificationHelper.showProgramReminderNotification(
                context = appContext,
                channelName = reminder.channelName,
                programTitle = reminder.programTitle,
                notificationId = notifId,
                channelId = reminder.channelId
            )
            if (!posted) {
                NotificationHelper.playReminderSoundFallback(appContext)
            }
        }
        _firedEvents.tryEmit(reminder)
    }

    fun stableNotificationId(reminderId: String): Int {
        return ((reminderId.hashCode() and 0x7FFFFFFF) % 80_000) + 10_000
    }

    private fun acquireBriefWakeLock(appContext: Context): PowerManager.WakeLock? {
        return runCatching {
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SoykenPlay:ReminderWakeLock")?.apply {
                acquire(5_000L)
            }
        }.getOrNull()
    }

    private fun scheduleSystemAlarmBackup(appContext: Context, reminder: ProgramReminder) {
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val notifId = stableNotificationId(reminder.programId)
        val intent = Intent(appContext, ReminderBroadcastReceiver::class.java).apply {
            action = "com.example.soykenplay.ACTION_TRIGGER_REMINDER_${reminder.programId}"
            putExtra(ReminderBroadcastReceiver.EXTRA_REMINDER_ID, reminder.programId)
            putExtra(ReminderBroadcastReceiver.EXTRA_CHANNEL_ID, reminder.channelId)
            putExtra(ReminderBroadcastReceiver.EXTRA_CHANNEL_NAME, reminder.channelName)
            putExtra(ReminderBroadcastReceiver.EXTRA_REMINDER_TIME, reminder.programTitle)
            putExtra(ReminderBroadcastReceiver.EXTRA_NOTIFICATION_ID, notifId)
            putExtra(ReminderBroadcastReceiver.EXTRA_START_TIME, reminder.startTime)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            appContext,
            notifId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerAt = reminder.startTime.coerceAtLeast(System.currentTimeMillis() + 500L)

        runCatching {
            alarmManager.setWindow(AlarmManager.RTC_WAKEUP, triggerAt, 10_000L, pendingIntent)
        }.onFailure {
            runCatching {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        }
    }

    private fun cancelSystemAlarmBackup(appContext: Context, reminderId: String) {
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val notifId = stableNotificationId(reminderId)
        val reminderLookupIntent = Intent(appContext, ReminderBroadcastReceiver::class.java).apply {
            action = "com.example.soykenplay.ACTION_TRIGGER_REMINDER_$reminderId"
        }
        val pendingIntent = PendingIntent.getBroadcast(
            appContext,
            notifId,
            reminderLookupIntent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            runCatching { alarmManager.cancel(pendingIntent) }
            pendingIntent.cancel()
        }
    }
}
