package com.phucdnh.messagefilter.util

import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.phucdnh.messagefilter.ai.AiSummarizerManager
import com.phucdnh.messagefilter.data.CapturedMessage
import com.phucdnh.messagefilter.data.MessageRepository
import com.phucdnh.messagefilter.data.local.AppFilterPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

enum class DebounceDecision {
    URGENT_DIRECT_MENTION,
    BUFFERED_WAITING
}

private data class GroupChatSession(
    val packageName: String,
    val groupTitle: String,
    val messages: MutableList<Pair<String, String>> = mutableListOf(),
    var firstMessageTime: Long = System.currentTimeMillis(),
    var lastMessageTime: Long = System.currentTimeMillis(),
    var originalContentIntent: PendingIntent? = null,
    var avatarBitmap: Bitmap? = null,
    var debounceJob: Job? = null
)

object GroupNotificationDebouncer {
    private const val TAG = "GroupDebouncer"
    private val scope = CoroutineScope(Dispatchers.Default)

    // Key: "$packageName:$groupTitle"
    private val activeSessions = ConcurrentHashMap<String, GroupChatSession>()

    fun isUserMentioned(text: String, userNicknames: List<String>): Boolean {
        if (text.isBlank() || userNicknames.isEmpty()) return false
        val lowerText = text.lowercase()

        for (name in userNicknames) {
            val cleanName = name.trim().lowercase()
            if (cleanName.isBlank()) continue

            // 1. Direct tag @name
            if (lowerText.contains("@$cleanName")) return true

            // 2. Mention calling patterns: "name ơi", "name dạ", "gọi name", "hỏi name"
            if (lowerText.contains("$cleanName ơi") ||
                lowerText.contains("$cleanName oi") ||
                lowerText.contains("anh $cleanName") ||
                lowerText.contains("em $cleanName") ||
                lowerText.contains("bác $cleanName")
            ) {
                return true
            }

            // 3. Word boundary check
            val pattern = Regex("""(?i)\b${Regex.escape(cleanName)}\b""")
            if (pattern.containsMatchIn(text)) {
                return true
            }
        }
        return false
    }

    fun handleIncomingGroupMessage(
        context: Context,
        packageName: String,
        groupTitle: String,
        sender: String,
        messageText: String,
        timestamp: Long,
        originalContentIntent: PendingIntent? = null,
        avatarBitmap: Bitmap? = null
    ): DebounceDecision {
        val userNicknames = AppFilterPreferences.getUserNicknames(context)
        val sessionKey = "$packageName:$groupTitle"

        // 1. Check if user is mentioned in this message
        if (isUserMentioned(messageText, userNicknames)) {
            Log.d(TAG, "Direct mention detected in group '$groupTitle' for message: '$messageText'")
            // Cancel any pending debounced digest for this group
            activeSessions[sessionKey]?.debounceJob?.cancel()
            activeSessions.remove(sessionKey)
            return DebounceDecision.URGENT_DIRECT_MENTION
        }

        // 2. Buffer message into session
        val session = activeSessions.computeIfAbsent(sessionKey) {
            GroupChatSession(
                packageName = packageName,
                groupTitle = groupTitle,
                firstMessageTime = timestamp,
                lastMessageTime = timestamp,
                originalContentIntent = originalContentIntent,
                avatarBitmap = avatarBitmap
            )
        }

        synchronized(session) {
            session.messages.add(sender to messageText)
            session.lastMessageTime = timestamp
            if (originalContentIntent != null) {
                session.originalContentIntent = originalContentIntent
            }
            if (avatarBitmap != null) {
                session.avatarBitmap = avatarBitmap
            }

            // Cancel previous timer
            session.debounceJob?.cancel()

            val debounceSec = AppFilterPreferences.getDebounceSeconds(context).coerceIn(10, 120)
            val maxMessagesThreshold = 8
            val sessionDurationMs = timestamp - session.firstMessageTime
            val maxSessionDurationMs = 180_000L // Hard ceiling of 3 minutes per burst

            // If session has already run for 3 minutes, force dispatch immediately
            if (sessionDurationMs >= maxSessionDurationMs) {
                Log.d(TAG, "Hard ceiling of 3 minutes reached for '$groupTitle'. Dispatching digest now.")
                session.debounceJob = scope.launch {
                    dispatchGroupDigest(context, sessionKey)
                }
                return DebounceDecision.BUFFERED_WAITING
            }

            val remainingMaxTimeMs = (maxSessionDurationMs - sessionDurationMs).coerceAtLeast(1000L)
            val standardDelayMs = if (session.messages.size >= maxMessagesThreshold) {
                1500L
            } else {
                debounceSec * 1000L
            }
            val delayMs = minOf(standardDelayMs, remainingMaxTimeMs)

            session.debounceJob = scope.launch {
                delay(delayMs)
                dispatchGroupDigest(context, sessionKey)
            }
        }

        return DebounceDecision.BUFFERED_WAITING
    }

    fun flushSessionImmediately(context: Context, packageName: String, groupTitle: String) {
        val sessionKey = "$packageName:$groupTitle"
        dispatchGroupDigest(context, sessionKey)
    }

    private fun dispatchGroupDigest(context: Context, sessionKey: String) {
        val session = activeSessions.remove(sessionKey) ?: return
        val messagesSnapshot: List<Pair<String, String>>
        val originalIntent: PendingIntent?
        val avatar: Bitmap?

        synchronized(session) {
            messagesSnapshot = session.messages.toList()
            originalIntent = session.originalContentIntent
            avatar = session.avatarBitmap
        }

        if (messagesSnapshot.isEmpty()) return

        scope.launch {
            val userNicknames = AppFilterPreferences.getUserNicknames(context)
            val summaryResult = AiSummarizerManager.summarizeGroupMessages(
                context = context,
                groupTitle = session.groupTitle,
                messages = messagesSnapshot,
                userNicknames = userNicknames
            )

            val digestNotificationId = (sessionKey.hashCode() xor 0x4A49) and 0x7FFFFFFF

            // Post clean digested notification
            NotificationHelper.showGroupDigestNotification(
                context = context,
                notificationId = digestNotificationId,
                packageName = session.packageName,
                groupTitle = session.groupTitle,
                summaryText = summaryResult.summary,
                messageCount = messagesSnapshot.size,
                originalContentIntent = originalIntent,
                avatarBitmap = avatar
            )

            // Save to DB history
            val formattedSnippet = messagesSnapshot.joinToString("\n") { "${it.first}: ${it.second}" }
            val captured = CapturedMessage(
                packageName = session.packageName,
                senderOrTitle = "[Tóm tắt AI] ${session.groupTitle}",
                messageContent = "${summaryResult.summary}\n───\n$formattedSnippet",
                timestamp = System.currentTimeMillis(),
                isForwarded = true,
                status = "AI_DIGEST"
            )
            MessageRepository.addMessage(context, captured)
        }
    }

    fun clearAll() {
        for ((_, session) in activeSessions) {
            session.debounceJob?.cancel()
        }
        activeSessions.clear()
    }
}
