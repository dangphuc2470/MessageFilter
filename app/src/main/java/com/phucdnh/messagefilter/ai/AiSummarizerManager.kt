package com.phucdnh.messagefilter.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.codeshipping.llamakotlin.LlamaModel
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

sealed class ModelDownloadState {
    data object NotDownloaded : ModelDownloadState()
    data class Downloading(val progress: Float, val downloadedBytes: Long, val totalBytes: Long) : ModelDownloadState()
    data class Ready(val fileSizeMb: Float) : ModelDownloadState()
    data class Error(val error: String) : ModelDownloadState()
}

data class GroupSummaryResult(
    val isUrgent: Boolean,
    val summary: String,
    val rawResponse: String
)

object AiSummarizerManager {
    private const val TAG = "AiSummarizer"
    const val MODEL_FILENAME = "SmolLM2-135M-Instruct-Q4_K_M.gguf"
    const val MODEL_DOWNLOAD_URL = "https://huggingface.co/bartowski/SmolLM2-135M-Instruct-GGUF/resolve/main/SmolLM2-135M-Instruct-Q4_K_M.gguf"
    const val ESTIMATED_SIZE_BYTES = 105454432L // ~100.5 MB

    private val scope = CoroutineScope(Dispatchers.IO)
    private val mutex = Mutex()

    private var activeModel: LlamaModel? = null
    private var idleUnloadJob: Job? = null
    private var downloadJob: Job? = null

    private val _downloadState = MutableStateFlow<ModelDownloadState>(ModelDownloadState.NotDownloaded)
    val downloadState: StateFlow<ModelDownloadState> = _downloadState.asStateFlow()

    private val _isInferring = MutableStateFlow(false)
    val isInferring: StateFlow<Boolean> = _isInferring.asStateFlow()

    fun getModelFile(context: Context): File {
        val modelsDir = File(context.filesDir, "models")
        if (!modelsDir.exists()) {
            modelsDir.mkdirs()
        }
        return File(modelsDir, MODEL_FILENAME)
    }

    fun isModelReady(context: Context): Boolean {
        val file = getModelFile(context)
        return file.exists() && file.length() > 50_000_000L // At least 50MB
    }

    fun initialize(context: Context) {
        val file = getModelFile(context)
        if (file.exists() && file.length() > 50_000_000L) {
            val mb = file.length().toFloat() / (1024 * 1024)
            _downloadState.value = ModelDownloadState.Ready(mb)
        } else {
            _downloadState.value = ModelDownloadState.NotDownloaded
        }
    }

    fun startDownload(context: Context) {
        if (downloadJob?.isActive == true) return

        downloadJob = scope.launch {
            val targetFile = getModelFile(context)
            val tempFile = File(targetFile.parentFile, "$MODEL_FILENAME.tmp")

            try {
                _downloadState.value = ModelDownloadState.Downloading(0f, 0L, ESTIMATED_SIZE_BYTES)
                var currentUrl = MODEL_DOWNLOAD_URL
                var connection: HttpURLConnection? = null
                var redirectCount = 0
                val maxRedirects = 6

                while (redirectCount < maxRedirects) {
                    val url = URL(currentUrl)
                    connection = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 20000
                        readTimeout = 30000
                        instanceFollowRedirects = false
                        setRequestProperty("User-Agent", "MessageFilter-Android")
                    }
                    val code = connection.responseCode
                    if (code == HttpURLConnection.HTTP_MOVED_TEMP ||
                        code == HttpURLConnection.HTTP_MOVED_PERM ||
                        code == 307 || code == 308
                    ) {
                        val newUrl = connection.getHeaderField("Location")
                        connection.disconnect()
                        if (newUrl.isNullOrBlank()) {
                            throw IllegalStateException("Received redirect without Location header")
                        }
                        currentUrl = newUrl
                        redirectCount++
                    } else if (code == HttpURLConnection.HTTP_OK) {
                        break
                    } else {
                        throw IllegalStateException("HTTP error $code: ${connection.responseMessage}")
                    }
                }

                val conn = connection ?: throw IllegalStateException("Could not establish connection")
                val totalBytes = if (conn.contentLengthLong > 0) conn.contentLengthLong else ESTIMATED_SIZE_BYTES

                if (tempFile.exists()) {
                    tempFile.delete()
                }

                conn.inputStream.use { input: InputStream ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(32768)
                        var bytesRead: Int
                        var downloaded = 0L
                        var lastEmitTime = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloaded += bytesRead

                            val now = System.currentTimeMillis()
                            if (now - lastEmitTime > 300 || downloaded == totalBytes) {
                                lastEmitTime = now
                                val progress = (downloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                                _downloadState.value = ModelDownloadState.Downloading(progress, downloaded, totalBytes)
                            }
                        }
                        output.flush()
                    }
                }

                // Verify file size and rename
                if (tempFile.exists() && tempFile.length() > 50_000_000L) {
                    if (targetFile.exists()) {
                        targetFile.delete()
                    }
                    if (tempFile.renameTo(targetFile)) {
                        val mb = targetFile.length().toFloat() / (1024 * 1024)
                        _downloadState.value = ModelDownloadState.Ready(mb)
                        Log.i(TAG, "Model downloaded successfully: ${targetFile.absolutePath} ($mb MB)")
                    } else {
                        throw IllegalStateException("Failed renaming temp model file")
                    }
                } else {
                    throw IllegalStateException("Downloaded file is incomplete or corrupted")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                if (tempFile.exists()) {
                    tempFile.delete()
                }
                _downloadState.value = ModelDownloadState.Error(e.message ?: "Download failed")
            }
        }
    }

    fun cancelDownload(context: Context) {
        downloadJob?.cancel()
        downloadJob = null
        val tempFile = File(getModelFile(context).parentFile, "$MODEL_FILENAME.tmp")
        if (tempFile.exists()) {
            tempFile.delete()
        }
        val file = getModelFile(context)
        if (file.exists() && file.length() > 50_000_000L) {
            val mb = file.length().toFloat() / (1024 * 1024)
            _downloadState.value = ModelDownloadState.Ready(mb)
        } else {
            _downloadState.value = ModelDownloadState.NotDownloaded
        }
    }

    fun deleteModel(context: Context): Boolean {
        unloadModel()
        val file = getModelFile(context)
        val deleted = if (file.exists()) file.delete() else true
        val tempFile = File(file.parentFile, "$MODEL_FILENAME.tmp")
        if (tempFile.exists()) {
            tempFile.delete()
        }
        _downloadState.value = ModelDownloadState.NotDownloaded
        return deleted
    }

    private suspend fun getOrLoadModel(context: Context): LlamaModel {
        mutex.withLock {
            idleUnloadJob?.cancel()
            idleUnloadJob = null

            val existing = activeModel
            if (existing != null && existing.isLoaded) {
                return existing
            }

            val file = getModelFile(context)
            if (!file.exists()) {
                throw IllegalStateException("Model file not found at ${file.absolutePath}")
            }

            Log.d(TAG, "Loading LlamaModel on-demand: ${file.absolutePath}")
            val model = LlamaModel.load(file.absolutePath) {
                contextSize = 1024
                batchSize = 256
                threads = 4
                threadsBatch = 4
                temperature = 0.25f
                topP = 0.85f
                maxTokens = 128
                useMmap = true
                useMlock = false
                gpuLayers = 0
            }
            activeModel = model
            return model
        }
    }

    private fun scheduleIdleUnload() {
        idleUnloadJob?.cancel()
        idleUnloadJob = scope.launch {
            // Keep in memory for 2 minutes to serve follow-up burst messages quickly
            delay(120_000)
            mutex.withLock {
                unloadModelLocked()
            }
        }
    }

    fun unloadModel() {
        scope.launch {
            mutex.withLock {
                unloadModelLocked()
            }
        }
    }

    private fun unloadModelLocked() {
        try {
            if (activeModel != null) {
                Log.d(TAG, "Unloading LlamaModel to free system RAM")
                activeModel?.close()
                activeModel = null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error closing LlamaModel: ${e.message}")
        }
    }

    suspend fun summarizeGroupMessages(
        context: Context,
        groupTitle: String,
        messages: List<Pair<String, String>>,
        userNicknames: List<String>
    ): GroupSummaryResult = withContext(Dispatchers.IO) {
        if (!isModelReady(context)) {
            return@withContext fallbackSummary(messages)
        }

        _isInferring.value = true
        try {
            val model = getOrLoadModel(context)
            val prompt = buildChatMLPrompt(groupTitle, messages, userNicknames)

            Log.d(TAG, "Running on-device AI inference with prompt length: ${prompt.length}")
            val rawOutput = model.generate(prompt).trim()
            Log.d(TAG, "AI inference output:\n$rawOutput")

            val parsed = parseSummaryOutput(rawOutput, messages)
            scheduleIdleUnload()
            parsed
        } catch (e: Exception) {
            Log.e(TAG, "Error during AI summarization", e)
            fallbackSummary(messages)
        } finally {
            _isInferring.value = false
        }
    }

    private fun buildChatMLPrompt(
        groupTitle: String,
        messages: List<Pair<String, String>>,
        userNicknames: List<String>
    ): String {
        val nameList = if (userNicknames.isNotEmpty()) userNicknames.joinToString(", ") else "User"
        val formattedMessages = messages.takeLast(10).joinToString("\n") { (sender, text) ->
            "$sender: $text"
        }

        return buildString {
            append("<|im_start|>system\n")
            append("You are an on-device notification summarizer for an individual named $nameList.\n")
            append("Analyze the following conversation from group \"$groupTitle\".\n")
            append("1. Check if any message is urgently directed to $nameList.\n")
            append("2. Summarize what the discussion is about in ONE concise sentence in Vietnamese.\n")
            append("Respond strictly in this format:\n")
            append("URGENT: YES or NO\n")
            append("SUMMARY: <1 sentence summary in Vietnamese>\n")
            append("<|im_end|>\n")
            append("<|im_start|>user\n")
            append("Conversation:\n")
            append(formattedMessages)
            append("\n<|im_end|>\n")
            append("<|im_start|>assistant\n")
        }
    }

    private fun parseSummaryOutput(raw: String, messages: List<Pair<String, String>>): GroupSummaryResult {
        var isUrgent = false
        var summary = ""

        val lines = raw.lines()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("URGENT:", ignoreCase = true)) {
                val value = trimmed.substringAfter(":").trim().uppercase()
                if (value.startsWith("YES")) {
                    isUrgent = true
                }
            } else if (trimmed.startsWith("SUMMARY:", ignoreCase = true)) {
                summary = trimmed.substringAfter(":").trim()
            }
        }

        if (summary.isBlank()) {
            summary = lines.firstOrNull { it.isNotBlank() && !it.contains("URGENT:", ignoreCase = true) }?.trim() ?: ""
        }

        if (summary.isBlank()) {
            summary = fallbackSummary(messages).summary
        }

        return GroupSummaryResult(
            isUrgent = isUrgent,
            summary = summary,
            rawResponse = raw
        )
    }

    private fun fallbackSummary(messages: List<Pair<String, String>>): GroupSummaryResult {
        val senders = messages.map { it.first }.distinct()
        val count = messages.size
        val senderText = if (senders.size <= 2) senders.joinToString(" và ") else "${senders.first()} và ${senders.size - 1} người khác"
        val lastMsg = messages.lastOrNull()?.second ?: ""
        return GroupSummaryResult(
            isUrgent = false,
            summary = "$count tin nhắn từ $senderText: \"$lastMsg\"",
            rawResponse = "Fallback summary"
        )
    }
}
