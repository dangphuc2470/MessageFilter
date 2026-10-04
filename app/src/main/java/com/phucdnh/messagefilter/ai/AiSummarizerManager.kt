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
    const val MODEL_FILENAME = "qwen2.5-0.5b-instruct-q4_k_m.gguf"
    const val MODEL_DOWNLOAD_URL = "https://github.com/dangphuc2470/MessageFilter/releases/download/v1.0.0/qwen2.5-0.5b-instruct-q4_k_m.gguf"
    const val MODEL_DOWNLOAD_URL_FALLBACK = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf"

    const val ESTIMATED_SIZE_BYTES = 491400032L // ~468.6 MB

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
                var connection: HttpURLConnection? = null
                var lastException: Exception? = null

                for (sourceUrl in listOf(MODEL_DOWNLOAD_URL, MODEL_DOWNLOAD_URL_FALLBACK)) {
                    var currentUrl = sourceUrl
                    var redirectCount = 0
                    val maxRedirects = 6
                    try {
                        while (redirectCount < maxRedirects) {
                            val url = URL(currentUrl)
                            val conn = (url.openConnection() as HttpURLConnection).apply {
                                connectTimeout = 30000
                                readTimeout = 30000
                                instanceFollowRedirects = false
                                setRequestProperty("User-Agent", "MessageFilter-Android")
                            }
                            val code = conn.responseCode
                            if (code == HttpURLConnection.HTTP_MOVED_TEMP ||
                                code == HttpURLConnection.HTTP_MOVED_PERM ||
                                code == 307 || code == 308
                            ) {
                                val newUrl = conn.getHeaderField("Location")
                                conn.disconnect()
                                if (newUrl.isNullOrBlank()) {
                                    throw IllegalStateException("Received redirect without Location header")
                                }
                                currentUrl = newUrl
                                redirectCount++
                            } else if (code == HttpURLConnection.HTTP_OK) {
                                connection = conn
                                break
                            } else {
                                conn.disconnect()
                                throw IllegalStateException("HTTP error $code: ${conn.responseMessage}")
                            }
                        }
                        if (connection != null) break
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed connecting to $sourceUrl: ${e.message}, trying fallback if available")
                        lastException = e
                    }
                }

                val conn = connection ?: throw (lastException ?: IllegalStateException("Could not establish connection"))
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
                temperature = 0.2f
                topP = 0.85f
                maxTokens = 64
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
        // Take last 8 messages, skip extremely long individual messages to save context
        val formattedMessages = messages.takeLast(8).joinToString("\n") { (sender, text) ->
            val truncatedText = if (text.length > 120) text.take(120) + "..." else text
            "$sender: $truncatedText"
        }

        return buildString {
            append("<|im_start|>system\n")
            append(
                "You are a Vietnamese group chat summarizer for smartwatch notifications. " +
                "Your ONLY task: read the conversation and output EXACTLY ONE concise Vietnamese sentence summarizing the key topic/action. " +
                "STRICT RULES:\n" +
                "- Output ONLY the summary sentence, nothing else.\n" +
                "- Do NOT copy or quote any original message lines.\n" +
                "- Do NOT list sender names.\n" +
                "- Do NOT continue the conversation.\n" +
                "- End with a period.\n"
            )
            append("<|im_end|>\n")

            // Few-shot example 1: casual lunch planning
            append("<|im_start|>user\n")
            append("Hội thoại nhóm \"Bạn Bè\":\n")
            append("An: Trưa nay ăn gì cả nhà?\n")
            append("Bình: Ra quán cơm tấm sườn nướng đầu phố đi\n")
            append("Chi: 12h có mặt nha\n")
            append("<|im_end|>\n")
            append("<|im_start|>assistant\n")
            append("Cả nhóm hẹn nhau 12h ăn cơm tấm sườn nướng.\n")
            append("<|im_end|>\n")

            // Few-shot example 2: work deadline scenario
            append("<|im_start|>user\n")
            append("Hội thoại nhóm \"Dự Án\":\n")
            append("Minh: Deadline nộp báo cáo là tối nay 8h nha mọi người\n")
            append("Hà: Ok t đang làm rồi\n")
            append("Tuấn: Nhớ gửi kèm file Excel\n")
            append("<|im_end|>\n")
            append("<|im_start|>assistant\n")
            append("Nhóm nhắc deadline nộp báo cáo tối nay 8h, kèm file Excel.\n")
            append("<|im_end|>\n")

            // Actual conversation to summarize
            append("<|im_start|>user\n")
            append("Hội thoại nhóm \"$groupTitle\":\n")
            append(formattedMessages)
            append("\n<|im_end|>\n")
            append("<|im_start|>assistant\n")
        }
    }

    private fun parseSummaryOutput(raw: String, messages: List<Pair<String, String>>): GroupSummaryResult {
        // Only filter out lines that literally look like "Sender: message" chat echoes.
        // Do NOT filter by original keywords — a good summary naturally contains input words.
        val senderColonRegex = Regex("""^[\w\s\u00C0-\u1EF4\u00E0-\u1EF5]{1,20}:\s""")

        val candidateLines = raw
            .replace("<|im_end|>", "")
            .replace("<|im_start|>", "")
            .lines()
            .map { it.trim() }
            .filter { line ->
                if (line.isBlank()) return@filter false
                // Reject lines that look like echoed "Sender: message" chat lines
                if (senderColonRegex.containsMatchIn(line)) return@filter false
                true
            }

        var summary = candidateLines.firstOrNull()?.trim() ?: ""

        // Strip surrounding quotes
        summary = summary.trim('"', '“', '”', '\'', ' ')

        // Truncate at first sentence boundary
        val sentenceEnd = summary.indexOfFirst { it == '.' || it == '!' || it == '?' }
        if (sentenceEnd in 10..200) {
            summary = summary.substring(0, sentenceEnd + 1).trim()
        } else if (summary.length > 130) {
            summary = summary.take(130).trimEnd() + "..."
        }

        if (summary.isBlank() || summary.length < 8) {
            Log.w(TAG, "Summary rejected or too short, using fallback. Raw: $raw")
            summary = fallbackSummary(messages).summary
        }

        return GroupSummaryResult(
            isUrgent = false,
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
