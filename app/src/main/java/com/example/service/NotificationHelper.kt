package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.MainActivity
import com.example.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object NotificationHelper {

    const val CHANNEL_ID = "soyken_play_alarm_channel_v3"
    private const val CHANNEL_NAME = "Soyken Play Saat Hatırlatıcıları"
    private const val CHANNEL_DESC = "Belirlediğiniz saat ve dakikada kanal hatırlatma bildirimi gönderir"
    private const val PREFS_NAME = "soyken_play_prefs"
    const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"
    const val EXTRA_REMINDER_CHANNEL_ID = "EXTRA_REMINDER_CHANNEL_ID"

    fun areNotificationsEnabledInPrefs(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_NOTIFICATIONS_ENABLED, true)
    }

    fun canPostSystemNotifications(context: Context): Boolean {
        return runCatching {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }.getOrDefault(false)
    }

    fun playReminderSoundFallback(context: Context) {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            if (uri != null) {
                RingtoneManager.getRingtone(context.applicationContext, uri)?.play()
            }
        }
    }

    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = CHANNEL_DESC
                enableVibration(true)
                vibrationPattern = longArrayOf(0L, 300L, 150L, 300L)
                enableLights(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                if (soundUri != null) {
                    setSound(soundUri, audioAttributes)
                }
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    fun showProgramReminderNotification(
        context: Context,
        channelName: String,
        programTitle: String,
        notificationId: Int = (System.currentTimeMillis() % 100000).toInt(),
        channelId: Long = -1L
    ): Boolean {
        createNotificationChannels(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (channelId > 0L) {
                putExtra(EXTRA_REMINDER_CHANNEL_ID, channelId)
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val timeLabel = programTitle.trim().ifBlank {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_alarm)
            .setContentTitle("⏰ $channelName • $timeLabel")
            .setContentText("Saat $timeLabel hatırlatma zamanı geldi.")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$channelName için belirlediğiniz $timeLabel hatırlatma saati geldi. Yayını açmak için dokunun.")
            )
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setColor(0xFFFFB800.toInt())
            .build()

        if (!canPostSystemNotifications(context)) {
            return false
        }

        return try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
            true
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    fun showTestNotification(context: Context): Boolean {
        val nowTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        return showProgramReminderNotification(
            context = context,
            channelName = "Soyken Play",
            programTitle = nowTime,
            notificationId = 9999
        )
    }
}
