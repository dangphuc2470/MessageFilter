package com.phucdnh.messagefilter.receiver

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.phucdnh.messagefilter.util.NotificationHelper

/**
 * Intercepts the "tap to open" action on a forwarded notification.
 * Clears the in-memory conversation history so the next incoming message
 * starts a fresh notification instead of appending to stale history,
 * then launches the original app's content intent.
 */
class OpenConversationReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_OPEN_CONVERSATION = "com.phucdnh.messagefilter.ACTION_OPEN_CONVERSATION"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
        const val EXTRA_CONVERSATION_KEY = "extra_conversation_key"
        const val EXTRA_ORIGINAL_INTENT = "extra_original_intent"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_OPEN_CONVERSATION) return

        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        val conversationKey = intent.getStringExtra(EXTRA_CONVERSATION_KEY)
        @Suppress("DEPRECATION")
        val originalIntent = intent.getParcelableExtra<PendingIntent>(EXTRA_ORIGINAL_INTENT)

        Log.d("OpenConversationReceiver", "Conversation opened: key=$conversationKey, id=$notificationId")

        // 1. Clear conversation history so future messages don't carry stale history
        if (!conversationKey.isNullOrBlank()) {
            NotificationHelper.clearConversation(conversationKey)
        }

        // 2. Dismiss our forwarded notification
        if (notificationId != -1) {
            try {
                NotificationManagerCompat.from(context).cancel(notificationId)
            } catch (e: Exception) {
                Log.w("OpenConversationReceiver", "Failed cancelling notification: ${e.message}")
            }
        }

        // 3. Launch the original app's conversation screen
        try {
            originalIntent?.send()
        } catch (e: Exception) {
            Log.w("OpenConversationReceiver", "Failed launching original intent: ${e.message}")
        }
    }
}
