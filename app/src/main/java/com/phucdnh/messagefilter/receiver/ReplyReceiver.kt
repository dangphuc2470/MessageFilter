package com.phucdnh.messagefilter.receiver

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.phucdnh.messagefilter.util.NotificationHelper

class ReplyReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_REPLY = "com.phucdnh.messagefilter.ACTION_REPLY"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
        const val EXTRA_CONVERSATION_KEY = "extra_conversation_key"
        const val EXTRA_ORIGINAL_REPLY_INTENT = "extra_original_reply_intent"
        const val EXTRA_RESULT_KEY = "extra_result_key"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_REPLY) return

        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        val conversationKey = intent.getStringExtra(EXTRA_CONVERSATION_KEY)
        val resultKey = intent.getStringExtra(EXTRA_RESULT_KEY) ?: "key_text_reply"
        @Suppress("DEPRECATION")
        val originalReplyIntent = intent.getParcelableExtra<PendingIntent>(EXTRA_ORIGINAL_REPLY_INTENT)

        val remoteInputResults = RemoteInput.getResultsFromIntent(intent)
        val replyText = remoteInputResults?.getCharSequence(resultKey)?.toString()

        Log.d("ReplyReceiver", "Reply received for $conversationKey: '$replyText'")

        if (!replyText.isNullOrBlank() && originalReplyIntent != null) {
            try {
                val fillInIntent = Intent()
                val bundle = Bundle().apply {
                    putCharSequence(resultKey, replyText)
                }
                RemoteInput.addResultsToIntent(
                    arrayOf(RemoteInput.Builder(resultKey).build()),
                    fillInIntent,
                    bundle
                )
                originalReplyIntent.send(context, 0, fillInIntent)
                Log.d("ReplyReceiver", "Successfully forwarded reply to original app intent")
            } catch (e: Exception) {
                Log.e("ReplyReceiver", "Failed sending direct reply: ${e.message}", e)
            }
        }

        // 1. Clear unread conversation memory since user replied
        if (!conversationKey.isNullOrBlank()) {
            NotificationHelper.clearConversation(conversationKey)
        }

        // 2. Dismiss the forwarded notification so Android stops the spinning indicator
        if (notificationId != -1) {
            try {
                NotificationManagerCompat.from(context).cancel(notificationId)
            } catch (e: Exception) {
                Log.w("ReplyReceiver", "Failed cancelling notification: ${e.message}")
            }
        }
    }
}
