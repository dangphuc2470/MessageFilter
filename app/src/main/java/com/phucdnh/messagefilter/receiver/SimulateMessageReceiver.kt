package com.phucdnh.messagefilter.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.phucdnh.messagefilter.data.CapturedMessage
import com.phucdnh.messagefilter.data.MessageRepository
import com.phucdnh.messagefilter.util.DebounceDecision
import com.phucdnh.messagefilter.util.GroupNotificationDebouncer
import com.phucdnh.messagefilter.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SimulateMessageReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "SimulateReceiver"
        const val ACTION_SIMULATE_BURST = "com.phucdnh.messagefilter.SIMULATE_GROUP_BURST"
        const val ACTION_SIMULATE_MENTION = "com.phucdnh.messagefilter.SIMULATE_GROUP_MENTION"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d(TAG, "Received action: $action")

        val groupTitle = intent.getStringExtra("groupTitle") ?: "Nhóm Dự Án Alpha"
        val packageName = intent.getStringExtra("packageName") ?: "com.whatsapp"

        when (action) {
            ACTION_SIMULATE_BURST -> {
                val burstMessages = listOf(
                    "Hoàng" to "Mọi người đã nộp báo cáo tuần chưa?",
                    "Lan" to "T vừa nộp qua email lúc nãy rồi",
                    "Nam" to "Chiều nay 2h họp chốt tiến độ sprint nhé cả nhóm",
                    "Tuấn" to "Ok t có mặt đúng giờ nha",
                    "Mai" to "Nhớ chuẩn bị sẵn slide demo sản phẩm"
                )

                CoroutineScope(Dispatchers.Default).launch {
                    burstMessages.forEachIndexed { index, (sender, text) ->
                        val decision = GroupNotificationDebouncer.handleIncomingGroupMessage(
                            context = context.applicationContext,
                            packageName = packageName,
                            groupTitle = groupTitle,
                            sender = sender,
                            messageText = text,
                            timestamp = System.currentTimeMillis()
                        )
                        Log.d(TAG, "[$index] Sent '$sender: $text' -> decision: $decision")
                        delay(250)
                    }
                }
            }
            ACTION_SIMULATE_MENTION -> {
                val sender = intent.getStringExtra("sender") ?: "Hoàng"
                val text = intent.getStringExtra("text") ?: "@Phúc ơi server đang bị lỗi 500 vào check gấp giúp t!"
                val now = System.currentTimeMillis()

                val decision = GroupNotificationDebouncer.handleIncomingGroupMessage(
                    context = context.applicationContext,
                    packageName = packageName,
                    groupTitle = groupTitle,
                    sender = sender,
                    messageText = text,
                    timestamp = now
                )

                Log.d(TAG, "Mention message '$text' -> decision: $decision")
                if (decision == DebounceDecision.URGENT_DIRECT_MENTION) {
                    val conversationId = (packageName.hashCode() xor groupTitle.hashCode()) and 0x7FFFFFFF
                    NotificationHelper.showForwardedMessageNotification(
                        context = context.applicationContext,
                        notificationId = conversationId,
                        packageName = packageName,
                        sender = "[$groupTitle] $sender",
                        latestText = text,
                        timestamp = now
                    )
                    val captured = CapturedMessage(
                        packageName = packageName,
                        senderOrTitle = "[$groupTitle] $sender",
                        messageContent = text,
                        timestamp = now,
                        isForwarded = true,
                        status = "FORWARD"
                    )
                    MessageRepository.addMessage(context.applicationContext, captured)
                }
            }
        }
    }
}
