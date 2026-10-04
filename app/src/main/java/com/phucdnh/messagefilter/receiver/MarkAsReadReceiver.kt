package com.phucdnh.messagefilter.receiver

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.phucdnh.messagefilter.util.NotificationHelper

class MarkAsReadReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_MARK_AS_READ = "com.phucdnh.messagefilter.ACTION_MARK_AS_READ"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
        const val EXTRA_CONVERSATION_KEY = "extra_conversation_key"
        const val EXTRA_ORIGINAL_INTENT = "extra_original_intent"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_MARK_AS_READ) return

        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        val conversationKey = intent.getStringExtra(EXTRA_CONVERSATION_KEY)
        @Suppress("DEPRECATION")
        val originalIntent = intent.getParcelableExtra<PendingIntent>(EXTRA_ORIGINAL_INTENT)

        Log.d("MarkAsReadReceiver", "Mark as read triggered for key: $conversationKey, id: $notificationId")

        // If the silent filtered summary notification was marked as read or swiped away
        if (notificationId == NotificationHelper.FILTERED_SUMMARY_NOTIFICATION_ID) {
            NotificationHelper.resetFilteredCount(context)
            return
        }

        // 1. Trigger original app's Mark as Read action
        try {
            originalIntent?.send()
        } catch (e: Exception) {
            Log.w("MarkAsReadReceiver", "Failed triggering original intent: ${e.message}")
        }

        // 2. Clear conversation history and pinned mentions
        if (!conversationKey.isNullOrBlank()) {
            NotificationHelper.clearConversation(conversationKey)
        }

        // 3. Dismiss forwarded notification
        if (notificationId != -1) {
            try {
                NotificationManagerCompat.from(context).cancel(notificationId)
                val pkgName = conversationKey?.substringBefore(":")
                if (!pkgName.isNullOrBlank()) {
                    NotificationHelper.clearNotification(context, pkgName, notificationId)
                }
            } catch (e: Exception) {
                Log.w("MarkAsReadReceiver", "Failed cancelling notification: ${e.message}")
            }
        }
    }
}
