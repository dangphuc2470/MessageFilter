package com.phucdnh.messagefilter.data

import android.content.Context
import com.phucdnh.messagefilter.data.local.AppDatabaseHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CapturedMessage(
    val id: Long = 0,
    val packageName: String,
    val senderOrTitle: String,
    val messageContent: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isForwarded: Boolean = true,
    val status: String = "FORWARD" // "FORWARD", "FILTER", "BLOCK", "NORMAL"
) {
    val formattedTime: String
        get() {
            val sdf = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }
}

object MessageRepository {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val _messages = MutableStateFlow<List<CapturedMessage>>(emptyList())
    val messages: StateFlow<List<CapturedMessage>> = _messages.asStateFlow()

    private var dbHelper: AppDatabaseHelper? = null

    fun initialize(context: Context) {
        if (dbHelper == null) {
            dbHelper = AppDatabaseHelper.getInstance(context)
            loadHistory()
        }
    }

    private fun loadHistory() {
        scope.launch {
            val history = dbHelper?.getAllMessages() ?: emptyList()
            _messages.value = history
        }
    }

    fun addMessage(context: Context, message: CapturedMessage) {
        initialize(context)
        scope.launch {
            val insertedId = dbHelper?.insertMessage(message) ?: 0L
            val savedMsg = message.copy(id = insertedId)
            withContext(Dispatchers.Main) {
                val currentList = _messages.value.toMutableList()
                currentList.add(0, savedMsg)
                _messages.value = currentList
            }
        }
    }

    fun deleteMessage(id: Long) {
        scope.launch {
            dbHelper?.deleteMessage(id)
            withContext(Dispatchers.Main) {
                _messages.value = _messages.value.filter { it.id != id }
            }
        }
    }

    fun clearAllHistory() {
        scope.launch {
            dbHelper?.clearAll()
            withContext(Dispatchers.Main) {
                _messages.value = emptyList()
            }
        }
    }
}
