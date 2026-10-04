package com.phucdnh.messagefilter.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.toBitmap
import android.util.Log
import com.phucdnh.messagefilter.R
import com.phucdnh.messagefilter.receiver.MarkAsReadReceiver
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class ChatMessage(
    val sender: String,
    val text: String,
    val timestamp: Long
)

data class PinnedMention(
    val sender: String,
    val text: String,
    val timestamp: Long
)

object NotificationHelper {
    const val CHANNEL_ID = "forwarded_messages_channel"
    private const val CHANNEL_NAME = "Forwarded Messages"
    private const val CHANNEL_DESC = "Notifications formatted with newest message on top for smartwatches"

    const val SILENT_CHANNEL_ID = "filtered_summary_channel"
    private const val SILENT_CHANNEL_NAME = "Filtered Notifications Summary"
    private const val SILENT_CHANNEL_DESC = "Silent notification summarizing blocked/filtered messages"

    const val AI_DIGEST_CHANNEL_ID = "ai_group_digest_channel"
    private const val AI_DIGEST_CHANNEL_NAME = "AI Group Digest"
    private const val AI_DIGEST_CHANNEL_DESC = "Smart summaries of busy group chats powered by on-device AI"

    const val FILTERED_SUMMARY_NOTIFICATION_ID = 99999
    private val filteredCount = AtomicInteger(0)

    private val MENTION_REGEX = Regex("""@(?i)(PhucDNH|all|everyone)""")

    // Cache of loaded app icons
    private val appIconCache = ConcurrentHashMap<String, Bitmap>()

    // Stores recent unread messages per conversation (newest at index 0)
    private val conversationHistory = ConcurrentHashMap<String, MutableList<ChatMessage>>()

    // Stores pinned mention per conversation until marked read
    private val pinnedMentions = ConcurrentHashMap<String, PinnedMention>()

    // Stores cached original PendingIntent per conversation to allow opening the exact chat from History
    private val conversationIntents = ConcurrentHashMap<String, PendingIntent>()

    // Track active child notifications per package to manage group summary notifications
    private val activeNotificationsByPackage = ConcurrentHashMap<String, MutableSet<Int>>()

    fun getGroupSummaryNotificationId(packageName: String): Int {
        return (packageName.hashCode() and 0x7FFFFFFF) xor 0x1111
    }

    fun getAppLabel(context: Context, packageName: String): String {
        return try {
            val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            when {
                packageName.contains("whatsapp") -> "WhatsApp"
                packageName.contains("messaging") || packageName.contains("mms") -> "Tin nhắn (SMS)"
                packageName.contains("telegram") -> "Telegram"
                packageName.contains("zalo") -> "Zalo"
                packageName.contains("orca") || packageName.contains("facebook.mlite") -> "Messenger"
                packageName.contains("viber") -> "Viber"
                packageName.contains("instagram") -> "Instagram"
                else -> "Tin nhắn"
            }
        }
    }

    fun getAppBrandColor(packageName: String): Int {
        return when {
            packageName.contains("whatsapp") -> 0xFF25D366.toInt()
            packageName.contains("facebook.orca") || packageName.contains("mlite") -> 0xFF0084FF.toInt()
            packageName.contains("telegram") -> 0xFF2AABEE.toInt()
            packageName.contains("zalo") -> 0xFF0068FF.toInt()
            packageName.contains("messaging") || packageName.contains("mms") -> 0xFF1A73E8.toInt()
            packageName.contains("viber") -> 0xFF7360F2.toInt()
            packageName.contains("instagram") -> 0xFFE1306C.toInt()
            else -> 0xFF2D5D86.toInt()
        }
    }

    fun saveConversationIntent(packageName: String, sender: String, intent: PendingIntent) {
        val key = "$packageName:$sender"
        conversationIntents[key] = intent
        conversationIntents[packageName] = intent
    }

    fun getConversationIntent(packageName: String, sender: String): PendingIntent? {
        val key = "$packageName:$sender"
        return conversationIntents[key] ?: conversationIntents[packageName]
    }

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, importance).apply {
                description = CHANNEL_DESC
                enableVibration(true)
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(channel)

            val silentChannel = NotificationChannel(SILENT_CHANNEL_ID, SILENT_CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                description = SILENT_CHANNEL_DESC
                enableVibration(false)
                enableLights(false)
                setSound(null, null)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(silentChannel)

            val digestChannel = NotificationChannel(AI_DIGEST_CHANNEL_ID, AI_DIGEST_CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                description = AI_DIGEST_CHANNEL_DESC
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 300, 200, 300)
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(digestChannel)
        }
    }

    fun incrementFilteredCount(context: Context, latestSender: String? = null, latestKeyword: String? = null) {
        val count = filteredCount.incrementAndGet()
        showFilteredSummaryNotification(context, count, latestSender, latestKeyword)
    }

    fun resetFilteredCount(context: Context) {
        filteredCount.set(0)
        try {
            NotificationManagerCompat.from(context).cancel(FILTERED_SUMMARY_NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.w("NotificationHelper", "Failed cancelling silent summary: ${e.message}")
        }
    }

    fun showFilteredSummaryNotification(
        context: Context,
        count: Int,
        latestSender: String? = null,
        latestKeyword: String? = null
    ) {
        if (count <= 0) return
        createNotificationChannel(context)

        val title = "Đã lọc $count thông báo"
        val contentText = if (!latestSender.isNullOrBlank() && !latestKeyword.isNullOrBlank()) {
            "Gần nhất: $latestSender ('$latestKeyword')"
        } else if (!latestSender.isNullOrBlank()) {
            "Gần nhất từ: $latestSender"
        } else {
            "Đã chặn $count thông báo không mong muốn"
        }

        // Action: "Đã đọc" (Mark as Read / Reset)
        val readIntent = Intent(context, MarkAsReadReceiver::class.java).apply {
            action = MarkAsReadReceiver.ACTION_MARK_AS_READ
            putExtra(MarkAsReadReceiver.EXTRA_NOTIFICATION_ID, FILTERED_SUMMARY_NOTIFICATION_ID)
        }
        val readPending = PendingIntent.getBroadcast(
            context,
            FILTERED_SUMMARY_NOTIFICATION_ID xor 0x7777,
            readIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, SILENT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_chat_bubble)
            .setColor(0xFF757575.toInt())
            .setContentTitle(title)
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setLocalOnly(true) // Stays strictly on phone under the shade; does NOT buzz watch
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOngoing(false)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setDeleteIntent(readPending) // Swiping away also resets counter
            .addAction(0, "Đã đọc", readPending)

        try {
            NotificationManagerCompat.from(context).notify(FILTERED_SUMMARY_NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) {
            Log.w("NotificationHelper", "Permission missing for notification: ${e.message}")
        } catch (e: Exception) {
            Log.w("NotificationHelper", "Failed posting silent notification: ${e.message}")
        }
    }

    fun getAppIconBitmap(context: Context, packageName: String): Bitmap? {
        return appIconCache.getOrPut(packageName) {
            try {
                val drawable = context.packageManager.getApplicationIcon(packageName)
                drawable.toBitmap(width = 192, height = 192)
            } catch (e: Exception) {
                return null
            }
        }
    }

    fun showForwardedMessageNotification(
        context: Context,
        notificationId: Int,
        packageName: String,
        sender: String,
        latestText: String,
        timestamp: Long = System.currentTimeMillis(),
        avatarBitmap: Bitmap? = null,
        originalContentIntent: PendingIntent? = null,
        originalMarkAsReadIntent: PendingIntent? = null,
        originalReplyIntent: PendingIntent? = null,
        originalReplyRemoteInput: androidx.core.app.RemoteInput? = null,
        isGroup: Boolean = false
    ) {
        createNotificationChannel(context)

        val conversationKey = "$packageName:$sender"
        if (originalContentIntent != null) {
            saveConversationIntent(packageName, sender, originalContentIntent)
        }
        val messageList = conversationHistory.getOrPut(conversationKey) { mutableListOf() }

        // Check if latest message mentions @PhucDNH or @all / @everyone
        if (MENTION_REGEX.containsMatchIn(latestText)) {
            pinnedMentions[conversationKey] = PinnedMention(sender, latestText, timestamp)
        }

        synchronized(messageList) {
            val lastSaved = messageList.firstOrNull()
            // Prevent duplicate insertion if the same notification fires multiple times in quick succession
            if (lastSaved == null || lastSaved.text != latestText) {
                messageList.add(0, ChatMessage(sender, latestText, timestamp))
                if (messageList.size > 15) {
                    messageList.removeAt(messageList.lastIndex)
                }
            }
        }

        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val fullTextBuilder = StringBuilder()

        synchronized(messageList) {
            val newestMsg = messageList.firstOrNull()
            if (newestMsg != null) {
                fullTextBuilder.append(newestMsg.text)
            }

            // Check if there is a pinned mention to show underneath
            val pinned = pinnedMentions[conversationKey]
            if (pinned != null && pinned.text != newestMsg?.text) {
                val pinnedTime = timeFormat.format(Date(pinned.timestamp))
                fullTextBuilder.append("\n───\n[@Tag $pinnedTime] ").append(pinned.text)
            }

            // Older messages
            if (messageList.size > 1) {
                fullTextBuilder.append("\n───")
                for (i in 1 until messageList.size) {
                    val oldMsg = messageList[i]
                    val timeStr = timeFormat.format(Date(oldMsg.timestamp))
                    val cleanOldText = if (oldMsg.text.startsWith("[OTP:") && oldMsg.text.contains("\n───\n")) {
                        oldMsg.text.substringAfter("\n───\n")
                    } else {
                        oldMsg.text
                    }
                    fullTextBuilder.append("\n[$timeStr] ").append(cleanOldText)
                }
            }
        }
        val fullFormattedText = fullTextBuilder.toString()

        // Determine icon to display: sender avatar or app icon
        val appIconBitmap = getAppIconBitmap(context, packageName)
        val displayBitmap = avatarBitmap ?: appIconBitmap

        val appName = getAppLabel(context, packageName)
        val brandColor = getAppBrandColor(packageName)
        val groupKey = "group_$packageName"

        activeNotificationsByPackage.getOrPut(packageName) { ConcurrentHashMap.newKeySet() }.add(notificationId)

        val bigTextStyle = NotificationCompat.BigTextStyle()
            .setBigContentTitle(sender)
            .bigText(fullFormattedText)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_chat_bubble)
            .setColor(brandColor)
            .setContentTitle(sender)
            .setContentText(latestText) // ONLY latest message for preview
            .setStyle(bigTextStyle)     // Newest on line 1, pinned mention, older messages underneath when expanded
            .setSubText(appName)        // Displays original app name in header
            .setGroup(groupKey)         // Group by original app package
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setOnlyAlertOnce(false)
            .setAutoCancel(true)
            .setAllowSystemGeneratedContextualActions(false)

        // Add explicit "Sao chép [OTP]" action if OTP is present
        val otpRegex = Regex("""\[OTP:\s*([0-9A-Za-z\-]+)\]""")
        val otpMatch = otpRegex.find(latestText)?.groupValues?.getOrNull(1) ?: OtpExtractor.extractOtp(latestText)
        if (!otpMatch.isNullOrBlank()) {
            val copyIntent = Intent(context, com.phucdnh.messagefilter.receiver.CopyOtpReceiver::class.java).apply {
                action = com.phucdnh.messagefilter.receiver.CopyOtpReceiver.ACTION_COPY_OTP
                putExtra(com.phucdnh.messagefilter.receiver.CopyOtpReceiver.EXTRA_OTP_CODE, otpMatch)
            }
            val copyPending = PendingIntent.getBroadcast(
                context,
                notificationId xor 0x55AA,
                copyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.ic_menu_save,
                "Sao chép $otpMatch",
                copyPending
            )
        }

        // Attach conversation key in extras so onNotificationRemoved can clear history when opened
        builder.addExtras(android.os.Bundle().apply {
            putString("extra_conversation_key", conversationKey)
        })

        // Direct Reply action (send message without opening the app / seen)
        if (originalReplyIntent != null && originalReplyRemoteInput != null) {
            val replyIntent = Intent(context, com.phucdnh.messagefilter.receiver.ReplyReceiver::class.java).apply {
                action = com.phucdnh.messagefilter.receiver.ReplyReceiver.ACTION_REPLY
                putExtra(com.phucdnh.messagefilter.receiver.ReplyReceiver.EXTRA_NOTIFICATION_ID, notificationId)
                putExtra(com.phucdnh.messagefilter.receiver.ReplyReceiver.EXTRA_CONVERSATION_KEY, conversationKey)
                putExtra(com.phucdnh.messagefilter.receiver.ReplyReceiver.EXTRA_ORIGINAL_REPLY_INTENT, originalReplyIntent)
                putExtra(com.phucdnh.messagefilter.receiver.ReplyReceiver.EXTRA_RESULT_KEY, originalReplyRemoteInput.resultKey)
            }
            val replyPending = PendingIntent.getBroadcast(
                context,
                notificationId xor 0x3333,
                replyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )

            val remoteInput = androidx.core.app.RemoteInput.Builder(originalReplyRemoteInput.resultKey)
                .setLabel("Trả lời...")
                .build()

            val replyAction = NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_send,
                "Trả lời",
                replyPending
            )
                .addRemoteInput(remoteInput)
                .setAllowGeneratedReplies(true)
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                .build()

            builder.addAction(replyAction)
        }

        // Tapping notification opens the original app directly (works reliably on all Android versions)
        if (originalContentIntent != null) {
            builder.setContentIntent(originalContentIntent)
        }

        // Swiping away notification clears conversation history in memory
        val deleteIntent = Intent(context, MarkAsReadReceiver::class.java).apply {
            action = MarkAsReadReceiver.ACTION_MARK_AS_READ
            putExtra(MarkAsReadReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(MarkAsReadReceiver.EXTRA_CONVERSATION_KEY, conversationKey)
        }
        val deletePending = PendingIntent.getBroadcast(
            context,
            notificationId xor 0x5555,
            deleteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.setDeleteIntent(deletePending)

        // Always add "Đã đọc" (Mark as Read) action button for both group and individual chats
        val markAsReadBroadcastIntent = Intent(context, MarkAsReadReceiver::class.java).apply {
            action = MarkAsReadReceiver.ACTION_MARK_AS_READ
            putExtra(MarkAsReadReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(MarkAsReadReceiver.EXTRA_CONVERSATION_KEY, conversationKey)
            if (originalMarkAsReadIntent != null) {
                putExtra(MarkAsReadReceiver.EXTRA_ORIGINAL_INTENT, originalMarkAsReadIntent)
            }
        }
        val pendingBroadcast = PendingIntent.getBroadcast(
            context,
            notificationId,
            markAsReadBroadcastIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(
            android.R.drawable.ic_menu_send,
            "Đã đọc",
            pendingBroadcast
        )

        if (displayBitmap != null) {
            builder.setLargeIcon(displayBitmap)
        }

        try {
            val notificationManager = NotificationManagerCompat.from(context)
            notificationManager.notify(notificationId, builder.build())

            // Post/update group summary notification for this app package
            val summaryId = getGroupSummaryNotificationId(packageName)
            val summaryBuilder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_chat_bubble)
                .setColor(brandColor)
                .setContentTitle(appName)
                .setContentText(latestText)
                .setSubText(appName)
                .setGroup(groupKey)
                .setGroupSummary(true)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)

            if (appIconBitmap != null) {
                summaryBuilder.setLargeIcon(appIconBitmap)
            }
            if (originalContentIntent != null) {
                summaryBuilder.setContentIntent(originalContentIntent)
            }

            notificationManager.notify(summaryId, summaryBuilder.build())
        } catch (e: SecurityException) {
            e.printStackTrace()
        } catch (e: Exception) {
            Log.w("NotificationHelper", "Failed posting notification: ${e.message}")
        }
    }

    fun clearNotification(context: Context, packageName: String, notificationId: Int) {
        val activeSet = activeNotificationsByPackage[packageName]
        activeSet?.remove(notificationId)
        if (activeSet.isNullOrEmpty()) {
            try {
                val summaryId = getGroupSummaryNotificationId(packageName)
                NotificationManagerCompat.from(context).cancel(summaryId)
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    fun clearConversation(conversationKey: String) {
        conversationHistory.remove(conversationKey)
        pinnedMentions.remove(conversationKey)
    }

    fun showGroupDigestNotification(
        context: Context,
        notificationId: Int,
        packageName: String,
        groupTitle: String,
        summaryText: String,
        messageCount: Int,
        originalContentIntent: PendingIntent? = null,
        avatarBitmap: Bitmap? = null
    ) {
        createNotificationChannel(context)

        val isSilent = com.phucdnh.messagefilter.data.local.AppFilterPreferences.isAiSilentDigest(context)
        val channelToUse = if (isSilent) SILENT_CHANNEL_ID else AI_DIGEST_CHANNEL_ID
        val brandColor = getAppBrandColor(packageName)
        val appName = getAppLabel(context, packageName)
        val title = "[Tóm tắt AI] $groupTitle ($messageCount tin)"

        val bigTextStyle = NotificationCompat.BigTextStyle()
            .setBigContentTitle(title)
            .bigText(summaryText)

        // Action: "Đã đọc" (Mark as Read / Dismiss)
        val readIntent = Intent(context, MarkAsReadReceiver::class.java).apply {
            action = MarkAsReadReceiver.ACTION_MARK_AS_READ
            putExtra(MarkAsReadReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(MarkAsReadReceiver.EXTRA_CONVERSATION_KEY, "$packageName:$groupTitle")
        }
        val readPending = PendingIntent.getBroadcast(
            context,
            notificationId xor 0x9999,
            readIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, channelToUse)
            .setSmallIcon(R.drawable.ic_chat_bubble)
            .setColor(brandColor)
            .setContentTitle(title)
            .setContentText(summaryText)
            .setStyle(bigTextStyle)
            .setSubText(appName)
            .setPriority(if (isSilent) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .setVibrate(if (isSilent) null else longArrayOf(0, 300, 200, 300))
            .setLocalOnly(isSilent) // When silent mode is off (default), bridges and vibrates on smartwatch!
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setDeleteIntent(readPending)
            .addAction(android.R.drawable.ic_menu_send, "Đã đọc", readPending)

        val appIconBitmap = getAppIconBitmap(context, packageName)
        val displayBitmap = avatarBitmap ?: appIconBitmap
        if (displayBitmap != null) {
            builder.setLargeIcon(displayBitmap)
        }

        if (originalContentIntent != null) {
            builder.setContentIntent(originalContentIntent)
        }

        try {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        } catch (e: Exception) {
            Log.w("NotificationHelper", "Failed posting AI digest notification: ${e.message}")
        }
    }

    fun clearAllConversations() {
        conversationHistory.clear()
        pinnedMentions.clear()
        activeNotificationsByPackage.clear()
    }
}
