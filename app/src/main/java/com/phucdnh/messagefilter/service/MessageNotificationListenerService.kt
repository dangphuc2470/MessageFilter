package com.phucdnh.messagefilter.service

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.toBitmap
import com.phucdnh.messagefilter.data.CapturedMessage
import com.phucdnh.messagefilter.data.MessageRepository
import com.phucdnh.messagefilter.data.local.AppAction
import com.phucdnh.messagefilter.data.local.AppFilterPreferences
import com.phucdnh.messagefilter.util.DebounceDecision
import com.phucdnh.messagefilter.util.GroupNotificationDebouncer
import com.phucdnh.messagefilter.util.NotificationHelper
import com.phucdnh.messagefilter.util.OtpExtractor

class MessageNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "MessageListener"

        // Junk / system status keywords to dismiss and block (English & Vietnamese)
        private val BLOCKED_PATTERNS = listOf(
            "checking for new messages",
            "checking new message",
            "messages is doing work in the background",
            "đang kiểm tra tin nhắn mới",
            "đang tìm kiếm tin nhắn",
            "đang tải tin nhắn",
            "whatsapp web is currently active",
            "whatsapp web đang hoạt động",
            "backup in progress",
            "backing up",
            "finished backup",
            "backup complete",
            "couldn't complete backup",
            "could not complete backup",
            "backup paused",
            "looking for backups",
            "chat history backup",
            "google drive backup",
            "sao lưu trên google drive",
            "đang sao lưu",
            "sao lưu hoàn tất",
            "không thể hoàn tất sao lưu",
            "sao lưu tạm dừng",
            "tìm kiếm bản sao lưu",
            "sao lưu tin nhắn",
            "khôi phục tin nhắn",
            "restoring messages",
            "restoring chat",
            "chat heads active",
            "bong bóng chat đang hoạt động",
            "waiting for network",
            "đang kết nối"
        )
        private val SUMMARY_REGEX = Regex(
            """.*(\d+\s+)?(new\s+messages?|unread\s+messages?|tin\s+nhắn\s+mới|tin\s+nhắn\s+chưa\s+đọc|messages?\s+from|tin\s+nhắn\s+từ).*""",
            RegexOption.IGNORE_CASE
        )

        private val recentMessagesCache = java.util.concurrent.ConcurrentHashMap<String, Long>()

        private fun isDuplicateMessage(pkg: String, title: String, content: String): Boolean {
            val key = "$pkg|$title|$content"
            val now = System.currentTimeMillis()
            val lastSeen = recentMessagesCache[key]
            recentMessagesCache[key] = now

            if (recentMessagesCache.size > 100) {
                val expireTime = now - 10000
                recentMessagesCache.entries.removeIf { it.value < expireTime }
            }

            return lastSeen != null && (now - lastSeen) < 2000
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        AppFilterPreferences.initialize(applicationContext)
        Log.d(TAG, "NotificationListener connected successfully.")

        try {
            val nm = androidx.core.app.NotificationManagerCompat.from(applicationContext)
            nm.cancel(("com.whatsapp:You").hashCode())
            nm.cancel(("com.whatsapp:Bạn").hashCode())
            NotificationHelper.clearConversation("com.whatsapp:You")
            NotificationHelper.clearConversation("com.whatsapp:Bạn")
        } catch (e: Exception) {
            // Ignore
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.d(TAG, "NotificationListener disconnected.")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)

        if (sbn == null) return

        val pkgName = sbn.packageName

        // Ignore our own notifications to avoid infinite loops
        if (pkgName == packageName) return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        // Ignore ongoing / foreground service notifications (e.g. active call or foreground music/timer)
        val isOngoing = (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
        if (isOngoing) {
            val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.lowercase() ?: ""
            val rawText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.lowercase() ?: ""
            val isOngoingJunk = BLOCKED_PATTERNS.any { rawTitle.contains(it) || rawText.contains(it) }
            if (isOngoingJunk && AppFilterPreferences.isAutoDismissOriginalEnabled(applicationContext)) {
                cancelNotificationSafely(sbn.key)
            }
            return
        }

        // Ignore group summary notifications (e.g. "X new messages") to prevent duplicate group alerts
        val isGroupSummary = (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0
        if (isGroupSummary) {
            val isFwd = AppFilterPreferences.isForwardEnabled(applicationContext, pkgName)
            val isBlk = AppFilterPreferences.isBlocked(applicationContext, pkgName)
            // Cancel group summary if app is in FORWARD or BLOCK mode (even if FILTER is also on)
            if (AppFilterPreferences.isAutoDismissOriginalEnabled(applicationContext)
                && (isFwd || isBlk)
            ) {
                cancelNotificationSafely(sbn)
            }
            return
        }

        var isGroupConversation = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)
            || extras.getBoolean("android.isGroupConversation", false)

        var groupConversationTitle: String? = sanitizeConversationTitle(
            extras.getCharSequence("android.conversationTitle")?.toString()
                ?: extras.getString("extra_im_notification_participant_normalized_destination")
        )

        var individualSender: String? = null
        var messageText: String? = null

        // 1. Try reading android.messages parcelable array in detail (Google Messages, WhatsApp, Telegram)
        try {
            @Suppress("DEPRECATION")
            val messagesArray = extras.getParcelableArray("android.messages")
            if (messagesArray != null && messagesArray.isNotEmpty()) {
                val lastItem = messagesArray.lastOrNull()
                if (lastItem is Bundle) {
                    val senderPerson = lastItem.getBundle("sender_person")
                    val senderName = senderPerson?.getCharSequence("name")?.toString()
                        ?: lastItem.getCharSequence("sender")?.toString()
                    val isLastOutgoing = !senderName.isNullOrBlank()
                            && (senderName.equals("You", ignoreCase = true) || senderName.equals("Bạn", ignoreCase = true))
                    if (isLastOutgoing) {
                        Log.d(TAG, "Latest message in notification was sent by the user (outgoing). Dismissing.")
                        cancelNotificationSafely(sbn)
                        return
                    }
                }

                for (item in messagesArray) {
                    if (item is Bundle) {
                        val senderPerson = item.getBundle("sender_person")
                        val senderName = senderPerson?.getCharSequence("name")?.toString()
                            ?: item.getCharSequence("sender")?.toString()
                        val isFromUser = !senderName.isNullOrBlank()
                                && (senderName.equals("You", ignoreCase = true) || senderName.equals("Bạn", ignoreCase = true))
                        if (isFromUser) continue

                        val t = item.getCharSequence("text")?.toString()
                        if (!t.isNullOrBlank() && !t.contains("Sensitive notification content hidden", ignoreCase = true)) {
                            messageText = t
                        }
                        if (!senderName.isNullOrBlank()) {
                            individualSender = senderName
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading android.messages: ${e.message}")
        }

        // 2. Check MessagingStyle
        if (messageText.isNullOrBlank() || messageText?.contains("Sensitive notification content hidden", ignoreCase = true) == true) {
            try {
                val messagingStyle = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
                if (messagingStyle != null) {
                    if (messagingStyle.isGroupConversation) {
                        isGroupConversation = true
                    }
                    if (groupConversationTitle.isNullOrBlank()) {
                        groupConversationTitle = sanitizeConversationTitle(messagingStyle.conversationTitle?.toString())
                    }
                    if (messagingStyle.messages.isNotEmpty()) {
                        val lastMsg = messagingStyle.messages.last()
                        val lastSender = lastMsg.person?.name?.toString()
                        val isLastOutgoing = !lastSender.isNullOrBlank()
                                && (lastSender.equals("You", ignoreCase = true) || lastSender.equals("Bạn", ignoreCase = true))
                        if (isLastOutgoing) {
                            Log.d(TAG, "Latest message in MessagingStyle was sent by the user (outgoing). Dismissing.")
                            cancelNotificationSafely(sbn)
                            return
                        }

                        for (m in messagingStyle.messages.reversed()) {
                            val sender = m.person?.name?.toString()
                            val isFromUser = !sender.isNullOrBlank()
                                    && (sender.equals("You", ignoreCase = true) || sender.equals("Bạn", ignoreCase = true))
                            if (isFromUser) continue

                            val t = m.text?.toString()
                            if (!t.isNullOrBlank() && !t.contains("Sensitive notification content hidden", ignoreCase = true)) {
                                messageText = t
                                if (individualSender.isNullOrBlank()) {
                                    individualSender = sender
                                }
                                break
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed extracting MessagingStyle: ${e.message}")
            }
        }

        // 3. Check textLines (InboxStyle)
        if (messageText.isNullOrBlank() || messageText?.contains("Sensitive notification content hidden", ignoreCase = true) == true) {
            val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            if (textLines != null && textLines.isNotEmpty()) {
                messageText = textLines.lastOrNull()?.toString()
            }
        }

        // 4. Fallbacks for messageText (tickerText, bigText, text)
        if (messageText.isNullOrBlank() || messageText?.contains("Sensitive notification content hidden", ignoreCase = true) == true) {
            val ticker = notification.tickerText?.toString()
            if (!ticker.isNullOrBlank() && !ticker.contains("Sensitive notification content hidden", ignoreCase = true)) {
                messageText = ticker
            }
        }

        if (messageText.isNullOrBlank() || messageText?.contains("Sensitive notification content hidden", ignoreCase = true) == true) {
            val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                ?: extras.getCharSequence("android.bigText")?.toString()
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                ?: extras.getString(Notification.EXTRA_TEXT)
                ?: extras.getCharSequence("android.text")?.toString()
            if (!bigText.isNullOrBlank() && !bigText.contains("Sensitive notification content hidden", ignoreCase = true)) {
                messageText = bigText
            } else if (!text.isNullOrBlank() && !text.contains("Sensitive notification content hidden", ignoreCase = true)) {
                messageText = text
            }
        }

        // 5. Determine canonical conversation Title and Message formatting
        var title: String? = null
        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: extras.getString(Notification.EXTRA_TITLE)
            ?: extras.getCharSequence("android.title")?.toString()

        // Also check if rawTitle has pattern: "GroupName (X messages): SenderName" or "GroupName: SenderName"
        if (groupConversationTitle.isNullOrBlank() && !rawTitle.isNullOrBlank() && rawTitle.contains(":")) {
            val colonIndex = rawTitle.indexOf(":")
            val potentialGroupName = sanitizeConversationTitle(rawTitle.substring(0, colonIndex).trim())
            val potentialSender = rawTitle.substring(colonIndex + 1).trim()
            if (!potentialGroupName.isNullOrBlank() && potentialSender.isNotBlank()) {
                groupConversationTitle = potentialGroupName
                isGroupConversation = true
                if (individualSender.isNullOrBlank()) {
                    individualSender = potentialSender
                }
            }
        }

        if (!groupConversationTitle.isNullOrBlank()) {
            isGroupConversation = true
            title = sanitizeConversationTitle(groupConversationTitle)
            val sender = individualSender?.trim()
            if (!sender.isNullOrBlank() && !messageText.isNullOrBlank() && sender != title) {
                if (!messageText!!.startsWith("$sender:", ignoreCase = true) && !messageText!!.startsWith("$sender :", ignoreCase = true)) {
                    messageText = "$sender: $messageText"
                }
            }
        } else {
            title = individualSender ?: sanitizeConversationTitle(rawTitle)
        }

        // Fallback title if completely empty
        if (title.isNullOrBlank()) {
            title = try {
                val appInfo = packageManager.getApplicationInfo(pkgName, 0)
                packageManager.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) {
                if (pkgName.contains("messaging") || pkgName.contains("mms")) "SMS" else "Notification"
            }
        }

        val cleanTitle = (sanitizeConversationTitle(title) ?: "").trim()
        val cleanMessage = (messageText ?: "").trim()

        // Drop outgoing message notifications where title is "You" or "Bạn"
        if (cleanTitle.equals("You", ignoreCase = true) || cleanTitle.equals("Bạn", ignoreCase = true)) {
            Log.d(TAG, "Dismissing self/outgoing message notification with title '$cleanTitle'")
            cancelNotificationSafely(sbn)
            return
        }

        // Ignore if message body is empty or hidden
        if (cleanMessage.isBlank() || cleanMessage.contains("Sensitive notification content hidden", ignoreCase = true)) {
            return
        }

        val appAction = AppFilterPreferences.getPackageAction(applicationContext, pkgName)
        Log.d(TAG, "Incoming notification: pkg=$pkgName, action=$appAction, title='$cleanTitle', msg='$cleanMessage'")

        // Cache contentIntent to allow tapping history items to open the exact conversation
        notification.contentIntent?.let {
            NotificationHelper.saveConversationIntent(pkgName, cleanTitle, it)
        }

        // Check against junk/system status patterns
        val lowerTitle = cleanTitle.lowercase()
        val lowerMessage = cleanMessage.lowercase()

        val isBlocked = AppFilterPreferences.isBlocked(applicationContext, pkgName)
        val isFilter = AppFilterPreferences.isFilterEnabled(applicationContext, pkgName)
        val isForward = AppFilterPreferences.isForwardEnabled(applicationContext, pkgName)

        val isOngoingOrSyncJunk = BLOCKED_PATTERNS.any { lowerMessage.contains(it) || lowerTitle.contains(it) }
        if (isOngoingOrSyncJunk) {
            Log.d(TAG, "Dismissing system junk sync: $cleanTitle -> $cleanMessage")
            if (AppFilterPreferences.isAutoDismissOriginalEnabled(applicationContext)) {
                cancelNotificationSafely(sbn.key)
            }
            return
        }

        // Summary notifications (e.g. "X new messages") should only be dismissed if app is in FORWARD or BLOCK mode
        val isSummaryMessage = SUMMARY_REGEX.matches(cleanMessage) || SUMMARY_REGEX.matches(cleanTitle)
        if (isSummaryMessage && (isForward || isBlocked)) {
            Log.d(TAG, "Dismissing forwarded/blocked summary: $cleanTitle -> $cleanMessage")
            if (AppFilterPreferences.isAutoDismissOriginalEnabled(applicationContext)) {
                cancelNotificationSafely(sbn.key)
            }
            return
        }

        val isAnyAction = isBlocked || isFilter || isForward
        if (!isAnyAction) {
            val isMessagingApp = sbn.notification?.category == Notification.CATEGORY_MESSAGE
                    || pkgName.contains("messaging") || pkgName.contains("mms")
                    || AppFilterPreferences.POPULAR_MESSAGING_APPS.any { it.first == pkgName }
            if (isMessagingApp && !isDuplicateMessage(pkgName, cleanTitle, cleanMessage)) {
                val captured = CapturedMessage(
                    packageName = pkgName,
                    senderOrTitle = cleanTitle,
                    messageContent = cleanMessage,
                    timestamp = sbn.postTime,
                    isForwarded = false,
                    status = "NORMAL"
                )
                MessageRepository.addMessage(applicationContext, captured)
            }
            return
        }

        if (isDuplicateMessage(pkgName, cleanTitle, cleanMessage)) {
            Log.d(TAG, "Dropping duplicate notification event for $pkgName: $cleanTitle -> $cleanMessage")
            return
        }

        if (isBlocked) {
            // Completely block: dismiss original and log as BLOCKED
            Log.d(TAG, "Blocked notification from $pkgName")
            if (AppFilterPreferences.isAutoDismissOriginalEnabled(applicationContext)) {
                cancelNotificationSafely(sbn)
            }
            NotificationHelper.incrementFilteredCount(applicationContext, cleanTitle, "Blocked")
            val captured = CapturedMessage(
                packageName = pkgName,
                senderOrTitle = cleanTitle,
                messageContent = cleanMessage,
                timestamp = sbn.postTime,
                isForwarded = false,
                status = "BLOCK"
            )
            MessageRepository.addMessage(applicationContext, captured)
            return
        }

        if (isFilter) {
            // Check if notification contains any blacklisted filter keyword
            val filterKeywords = AppFilterPreferences.getFilterKeywords(applicationContext)
            val matchedKeyword = filterKeywords.firstOrNull { kw ->
                kw.isNotBlank() && (lowerMessage.contains(kw) || lowerTitle.contains(kw))
            }

            if (matchedKeyword != null) {
                // Blacklisted keyword found: dismiss notification, do NOT forward, log as FILTER
                Log.d(TAG, "Filtered notification from $pkgName containing keyword: '$matchedKeyword'")
                cancelNotificationSafely(sbn)

                NotificationHelper.incrementFilteredCount(applicationContext, cleanTitle, matchedKeyword)

                val captured = CapturedMessage(
                    packageName = pkgName,
                    senderOrTitle = cleanTitle,
                    messageContent = "[Đã lọc: '$matchedKeyword'] $cleanMessage",
                    timestamp = sbn.postTime,
                    isForwarded = false,
                    status = "FILTER"
                )
                MessageRepository.addMessage(applicationContext, captured)
                return
            }
        }

        if (isForward) {
            val forwardType = AppFilterPreferences.getForwardType(applicationContext, pkgName)
            val detectedOtp = OtpExtractor.extractOtp(cleanMessage)

            // If mode is OTP_ONLY and no OTP was detected, log as NORMAL and leave the
            // original notification completely untouched so the user still sees it.
            if (forwardType == com.phucdnh.messagefilter.data.local.ForwardType.OTP_ONLY && detectedOtp == null) {
                Log.d(TAG, "Skipping forward for $pkgName because message has no OTP (mode: OTP_ONLY)")
                val captured = CapturedMessage(
                    packageName = pkgName,
                    senderOrTitle = cleanTitle,
                    messageContent = cleanMessage,
                    timestamp = sbn.postTime,
                    isForwarded = false,
                    status = "NORMAL"
                )
                MessageRepository.addMessage(applicationContext, captured)
                // Do NOT cancel or touch the original notification - let it show normally.
                return
            }

            try {
                // 1. Format message with OTP if present
                val effectiveMessage = if (detectedOtp != null) {
                    "[OTP: $detectedOtp]\n───\n$cleanMessage"
                } else {
                    cleanMessage
                }

                // Extract avatar
                var avatarBitmap: android.graphics.Bitmap? = null
                try {
                    val largeIcon = notification.getLargeIcon()
                    val drawable = largeIcon?.loadDrawable(applicationContext)
                    avatarBitmap = drawable?.toBitmap(192, 192)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed loading avatar: ${e.message}")
                }

                // Check On-Device AI Group Summarization & Debouncing
                if (isGroupConversation && AppFilterPreferences.isAiSummarizeGroupsEnabled(applicationContext)) {
                    val senderName = individualSender ?: cleanTitle
                    val decision = GroupNotificationDebouncer.handleIncomingGroupMessage(
                        context = applicationContext,
                        packageName = pkgName,
                        groupTitle = cleanTitle,
                        sender = senderName,
                        messageText = cleanMessage,
                        timestamp = sbn.postTime,
                        originalContentIntent = notification.contentIntent,
                        avatarBitmap = avatarBitmap
                    )

                    if (decision == DebounceDecision.BUFFERED_WAITING) {
                        Log.d(TAG, "Group message buffered for AI digest ($cleanTitle): $cleanMessage")
                        if (AppFilterPreferences.isAutoDismissOriginalEnabled(applicationContext)) {
                            cancelNotificationSafely(sbn)
                        }
                        return
                    }
                    Log.d(TAG, "Direct user mention detected in group ($cleanTitle). Forwarding urgently!")
                }

                // 2. Record in DB as FORWARD
                val capturedMessage = CapturedMessage(
                    packageName = pkgName,
                    senderOrTitle = cleanTitle,
                    messageContent = effectiveMessage,
                    timestamp = sbn.postTime,
                    isForwarded = true,
                    status = "FORWARD"
                )
                MessageRepository.addMessage(applicationContext, capturedMessage)

                // 3. Distinct notification ID per conversation
                val conversationId = (pkgName.hashCode() xor cleanTitle.hashCode()) and 0x7FFFFFFF

                // Extract "Mark as Read" and "Reply" actions from standard, wearable, and invisible actions
                var markAsReadPendingIntent: android.app.PendingIntent? = null
                var replyPendingIntent: android.app.PendingIntent? = null
                var replyRemoteInput: androidx.core.app.RemoteInput? = null
                val allActions = mutableListOf<NotificationCompat.Action>()

                // Standard actions
                val actionCount = NotificationCompat.getActionCount(notification)
                for (i in 0 until actionCount) {
                    NotificationCompat.getAction(notification, i)?.let { allActions.add(it) }
                }
                // Wearable actions
                allActions.addAll(NotificationCompat.WearableExtender(notification).actions)
                // Invisible actions
                allActions.addAll(NotificationCompat.getInvisibleActions(notification))

                for (act in allActions) {
                    val titleLower = act.title?.toString()?.lowercase() ?: ""
                    val inputs = act.remoteInputs

                    // Check for Mark as Read
                    val isReadAction = act.semanticAction == NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ
                            || titleLower.contains("read")
                            || titleLower.contains("đọc")
                            || titleLower.contains("mark")
                            || titleLower.contains("seen")
                            || titleLower.contains("xem")
                    if (isReadAction && act.actionIntent != null && markAsReadPendingIntent == null) {
                        markAsReadPendingIntent = act.actionIntent
                    }

                    // Check for Direct Reply
                    val isReplyAction = act.semanticAction == NotificationCompat.Action.SEMANTIC_ACTION_REPLY
                            || (inputs != null && inputs.isNotEmpty())
                            || (titleLower.contains("reply") || titleLower.contains("trả lời"))
                    if (isReplyAction && act.actionIntent != null && inputs != null && inputs.isNotEmpty() && replyPendingIntent == null) {
                        replyPendingIntent = act.actionIntent
                        replyRemoteInput = inputs[0]
                    }
                }

                // 4. Post clean forwarded notification FIRST so screen transition is seamless
                NotificationHelper.showForwardedMessageNotification(
                    context = applicationContext,
                    notificationId = conversationId,
                    packageName = pkgName,
                    sender = cleanTitle,
                    latestText = effectiveMessage,
                    timestamp = sbn.postTime,
                    avatarBitmap = avatarBitmap,
                    originalContentIntent = notification.contentIntent,
                    originalMarkAsReadIntent = markAsReadPendingIntent,
                    originalReplyIntent = replyPendingIntent,
                    originalReplyRemoteInput = replyRemoteInput,
                    isGroup = isGroupConversation
                )

                // 5. Dismiss original app notification AFTER posting our notification
                if (AppFilterPreferences.isAutoDismissOriginalEnabled(applicationContext)) {
                    cancelNotificationSafely(sbn)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling forward notification", e)
            }
            return
        }

        // Log all other apps into History as NORMAL so full log is available
        val captured = CapturedMessage(
            packageName = pkgName,
            senderOrTitle = cleanTitle,
            messageContent = cleanMessage,
            timestamp = sbn.postTime,
            isForwarded = false,
            status = "NORMAL"
        )
        MessageRepository.addMessage(applicationContext, captured)
    }

    private fun sanitizeConversationTitle(rawTitle: String?): String? {
        if (rawTitle.isNullOrBlank()) return rawTitle
        // Matches suffixes like " (2 messages)", " (5 tin nhắn)", " (10 new messages)", " (3 msgs)", " (4)"
        val pattern = Regex("""\s*\((?:\d+\s*(?:messages?|tin nhắn|new messages?|msgs?|tin)|\d{1,3})\)\s*$""", RegexOption.IGNORE_CASE)
        val cleaned = rawTitle.replace(pattern, "").trim()
        return if (cleaned.isNotBlank()) cleaned else rawTitle.trim()
    }

    private fun cancelNotificationSafely(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val key = sbn.key
        try {
            if (!key.isNullOrBlank()) {
                cancelNotification(key)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not cancel notification with key: $key", e)
        }
        try {
            cancelNotification(sbn.packageName, sbn.tag, sbn.id)
        } catch (e: Exception) {
            // Ignore fallback cancel exception
        }
    }

    private fun cancelNotificationSafely(key: String?) {
        if (key.isNullOrBlank()) return
        try {
            cancelNotification(key)
        } catch (e: Exception) {
            Log.w(TAG, "Could not cancel notification with key: $key", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn == null) return
        if (sbn.packageName == packageName) {
            val convKey = sbn.notification?.extras?.getString("extra_conversation_key")
            if (!convKey.isNullOrBlank()) {
                NotificationHelper.clearConversation(convKey)
                val pkg = convKey.substringBefore(":")
                NotificationHelper.clearNotification(applicationContext, pkg, sbn.id)
            }
        }
    }
}
