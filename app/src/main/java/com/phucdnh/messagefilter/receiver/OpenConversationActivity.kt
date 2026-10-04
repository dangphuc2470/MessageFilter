package com.phucdnh.messagefilter.receiver

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.phucdnh.messagefilter.util.NotificationHelper

/**
 * Transparent trampoline Activity that intercepts notification taps.
 * Because this is an Activity (not a BroadcastReceiver), Android allows it to
 * immediately launch the target app (WhatsApp, Messenger, SMS) without being blocked
 * by Android 10-15 Background Activity Launch (BAL) restrictions.
 */
class OpenConversationActivity : Activity() {

    companion object {
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
        const val EXTRA_CONVERSATION_KEY = "extra_conversation_key"
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
        const val EXTRA_ORIGINAL_INTENT = "extra_original_intent"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        val conversationKey = intent.getStringExtra(EXTRA_CONVERSATION_KEY)
        val pkgName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
        @Suppress("DEPRECATION")
        val originalIntent = intent.getParcelableExtra<PendingIntent>(EXTRA_ORIGINAL_INTENT)

        Log.d("OpenConversationAct", "Opening conversation: key=$conversationKey, pkg=$pkgName, id=$notificationId")

        // 1. Clear conversation history so future messages start fresh
        if (!conversationKey.isNullOrBlank()) {
            NotificationHelper.clearConversation(conversationKey)
        }

        // 2. Dismiss forwarded notification
        if (notificationId != -1) {
            try {
                NotificationManagerCompat.from(this).cancel(notificationId)
            } catch (e: Exception) {
                Log.w("OpenConversationAct", "Failed cancelling notification: ${e.message}")
            }
        }

        // 3. Launch the original app conversation
        try {
            if (originalIntent != null) {
                originalIntent.send()
            } else if (!pkgName.isNullOrBlank()) {
                val launchIntent = packageManager.getLaunchIntentForPackage(pkgName)
                if (launchIntent != null) {
                    startActivity(launchIntent)
                }
            }
        } catch (e: Exception) {
            Log.w("OpenConversationAct", "Failed launching original intent: ${e.message}")
            if (!pkgName.isNullOrBlank()) {
                try {
                    val launchIntent = packageManager.getLaunchIntentForPackage(pkgName)
                    if (launchIntent != null) {
                        startActivity(launchIntent)
                    }
                } catch (ex: Exception) {
                    Log.e("OpenConversationAct", "Fallback launch failed: ${ex.message}")
                }
            }
        }

        finish()
    }
}
