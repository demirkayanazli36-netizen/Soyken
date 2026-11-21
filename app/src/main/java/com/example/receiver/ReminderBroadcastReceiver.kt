package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.service.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ReminderBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getStringExtra(EXTRA_REMINDER_ID)
        val channelId = intent.getLongExtra(EXTRA_CHANNEL_ID, -1L)
        val channelName = intent.getStringExtra(EXTRA_CHANNEL_NAME) ?: "Soyken Play"
        val reminderTime = intent.getStringExtra(EXTRA_REMINDER_TIME)
            ?: intent.getStringExtra(EXTRA_PROGRAM_TITLE)
            ?: ""
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 10001)
        val scheduledStartTime = intent.getLongExtra(EXTRA_START_TIME, 0L)

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ReminderScheduler.fireReminderFromReceiver(
                    context = appContext,
                    reminderId = reminderId,
                    channelId = channelId,
                    channelName = channelName,
                    reminderTime = reminderTime,
                    notificationId = notificationId,
                    scheduledStartTime = scheduledStartTime
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val EXTRA_REMINDER_ID = "EXTRA_REMINDER_ID"
        const val EXTRA_CHANNEL_ID = "EXTRA_CHANNEL_ID"
        const val EXTRA_CHANNEL_NAME = "EXTRA_CHANNEL_NAME"
        const val EXTRA_REMINDER_TIME = "EXTRA_REMINDER_TIME"
        const val EXTRA_PROGRAM_TITLE = "EXTRA_PROGRAM_TITLE"
        const val EXTRA_NOTIFICATION_ID = "EXTRA_NOTIFICATION_ID"
        const val EXTRA_START_TIME = "EXTRA_START_TIME"
    }
}
