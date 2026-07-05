package com.qwen.tts.android

import android.app.Application
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qwen.tts.android.ui.theme.QwenTtsTheme
import com.qwen.tts.studio.engine.QwenEngine
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class ModelFile(
    val name: String,
    val url: String,
    val sizeBytes: Long,
)

private object QwenModel {
    const val talkerName = "qwen-talker-0.6b-base-Q8_0.gguf"
    private const val tokenizerName = "qwen-tokenizer-12hz-Q8_0.gguf"
    private const val repo = "Serveurperso/Qwen3-TTS-GGUF"
    private const val baseUrl = "https://huggingface.co/$repo/resolve/main"

    val files = listOf(
        ModelFile(tokenizerName, "$baseUrl/$tokenizerName?download=true", 291_150_624L),
        ModelFile(talkerName, "$baseUrl/$talkerName?download=true", 992_615_488L),
    )

    val totalBytes: Long = files.sumOf { it.sizeBytes }
}

data class QwenTtsUiState(
    val text: String = "Hello.",
    val modelReady: Boolean = false,
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val downloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val downloadBytes: Long = 0L,
    val downloadTotalBytes: Long = QwenModel.totalBytes,
    val status: String = "Model not downloaded",
    val error: String? = null,
    val sampleRate: Int = 0,
    val sampleCount: Int = 0,
    val synthesisMillis: Long = 0,
    val playing: Boolean = false,
    val operationElapsedMillis: Long = 0,
    val synthesisFrames: Int = 0,
    val maxAudioTokens: Int = 512,
    val cpuThreads: Int = 0,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val modelDir = File(application.filesDir, "qwen3-tts-models")
    private val _uiState = MutableStateFlow(QwenTtsUiState(modelReady = isModelReady()))
    val uiState = _uiState.asStateFlow()

    private var engine: QwenEngine? = null
    private var generatedAudio: FloatArray? = null
    private var playJob: Job? = null
    private var operationTickerJob: Job? = null
    private var activeTrack: AudioTrack? = null

    init {
        refreshModelState()
    }

    fun updateText(value: String) {
        _uiState.update { it.copy(text = value) }
    }

    private fun preferredCpuThreadCount(): Int {
        val available = Runtime.getRuntime().availableProcessors()
        return when {
            available >= 10 -> 8
            available >= 8 -> 6
            available >= 4 -> 4
            else -> available.coerceAtLeast(1)
        }
    }

    private fun startBusy(status: String, downloading: Boolean = false, resetSynthesis: Boolean = false) {
        operationTickerJob?.cancel()
        val start = SystemClock.elapsedRealtime()
        _uiState.update {
            it.copy(
                busy = true,
                downloading = downloading,
                error = null,
                status = status,
                operationElapsedMillis = 0L,
                synthesisFrames = if (resetSynthesis) 0 else it.synthesisFrames,
                sampleCount = if (resetSynthesis) 0 else it.sampleCount,
                synthesisMillis = if (resetSynthesis) 0 else it.synthesisMillis,
            )
        }
        operationTickerJob = viewModelScope.launch {
            while (true) {
                delay(250L)
                val elapsed = SystemClock.elapsedRealtime() - start
                _uiState.update { current ->
                    if (current.busy) current.copy(operationElapsedMillis = elapsed) else current
                }
            }
        }
    }

    private fun stopBusyTicker() {
        operationTickerJob?.cancel()
        operationTickerJob = null
    }

    fun downloadModel() {
        if (_uiState.value.downloading) return
        viewModelScope.launch {
            startBusy(status = "Preparing model download", downloading = true)
            _uiState.update {
                it.copy(
                    downloadProgress = 0f,
                    downloadBytes = 0L,
                )
            }

            val result = runCatching {
                withContext(Dispatchers.IO) {
                    modelDir.mkdirs()
                    downloadFiles()
                }
            }

            result.fold(
                onSuccess = {
                    stopBusyTicker()
                    refreshModelState("Model ready")
                },
                onFailure = { throwable ->
                    stopBusyTicker()
                    _uiState.update {
                        it.copy(
                            busy = false,
                            downloading = false,
                            status = "Download failed",
                            error = throwable.message ?: "Download failed",
                        )
                    }
                },
            )
        }
    }

    fun loadModel() {
        if (!_uiState.value.modelReady || _uiState.value.loaded || _uiState.value.busy) return
        viewModelScope.launch {
            startBusy(status = "Loading native model")

            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val native = engine ?: QwenEngine().also { engine = it }
                    val threads = preferredCpuThreadCount()
                    native.setCpuThreads(threads)
                    if (!native.loadModels(modelDir.absolutePath, QwenModel.talkerName)) {
                        error(native.getLastError() ?: "Native model load failed")
                    }
                    native.getActiveBackendName().orEmpty() to native.getCpuThreads()
                }
            }

            result.fold(
                onSuccess = { (backend, threads) ->
                    stopBusyTicker()
                    _uiState.update {
                        it.copy(
                            busy = false,
                            loaded = true,
                            status = if (backend.isBlank()) "Model loaded" else "Model loaded on $backend",
                            cpuThreads = threads,
                        )
                    }
                },
                onFailure = { throwable ->
                    stopBusyTicker()
                    _uiState.update {
                        it.copy(
                            busy = false,
                            loaded = false,
                            status = "Load failed",
                            error = throwable.message ?: "Load failed",
                        )
                    }
                },
            )
        }
    }

    fun synthesize() {
        val text = _uiState.value.text.trim()
        if (text.isEmpty() || _uiState.value.busy) return
        viewModelScope.launch {
            startBusy(status = "Synthesizing speech", resetSynthesis = true)

            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val native = engine ?: QwenEngine().also { engine = it }
                    val threads = preferredCpuThreadCount()
                    native.setCpuThreads(threads)
                    _uiState.update { it.copy(cpuThreads = native.getCpuThreads()) }
                    if (!_uiState.value.loaded) {
                        _uiState.update { it.copy(status = "Loading native model") }
                        if (!native.loadModels(modelDir.absolutePath, QwenModel.talkerName)) {
                            error(native.getLastError() ?: "Native model load failed")
                        }
                    }
                    native.setProgressCallback { frames, maxTokens ->
                        _uiState.update {
                            it.copy(
                                loaded = true,
                                status = "Generating speech codes",
                                synthesisFrames = frames,
                                maxAudioTokens = maxTokens,
                            )
                        }
                    }
                    try {
                        native.synthesize(text, params = QwenEngine.NativeParams(languageId = 2050))
                    } finally {
                        native.setProgressCallback(null)
                    }
                }
            }

            result.fold(
                onSuccess = { nativeResult ->
                    stopBusyTicker()
                    if (!nativeResult.success || nativeResult.audio == null) {
                        _uiState.update {
                            it.copy(
                                busy = false,
                                loaded = true,
                                status = "Synthesis failed",
                                error = nativeResult.errorMsg ?: "Native synthesis failed",
                            )
                        }
                        return@fold
                    }

                    generatedAudio = nativeResult.audio
                    _uiState.update {
                        it.copy(
                            busy = false,
                            loaded = true,
                            status = "Speech ready",
                            sampleRate = nativeResult.sampleRate,
                            sampleCount = nativeResult.audio.size,
                            synthesisMillis = nativeResult.timeMs,
                        )
                    }
                    playAudio()
                },
                onFailure = { throwable ->
                    stopBusyTicker()
                    _uiState.update {
                        it.copy(
                            busy = false,
                            status = "Synthesis failed",
                            error = throwable.message ?: "Synthesis failed",
                        )
                    }
                },
            )
        }
    }

    fun playAudio() {
        val audio = generatedAudio ?: return
        val sampleRate = _uiState.value.sampleRate.takeIf { it > 0 } ?: 24_000
        stopAudio()
        playJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val minBuffer = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_FLOAT,
                ).coerceAtLeast(sampleRate)

                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(minBuffer * Float.SIZE_BYTES)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                activeTrack = track
                _uiState.update { it.copy(playing = true, status = "Playing speech") }
                track.play()
                val writtenFrames = track.write(audio, 0, audio.size, AudioTrack.WRITE_BLOCKING)
                    .coerceAtLeast(0)
                while (track.playState == AudioTrack.PLAYSTATE_PLAYING &&
                    track.playbackHeadPosition < writtenFrames
                ) {
                    SystemClock.sleep(20L)
                }
                track.stop()
                track.release()
            }.onFailure { throwable ->
                _uiState.update { it.copy(error = throwable.message ?: "Playback failed") }
            }

            activeTrack = null
            _uiState.update { it.copy(playing = false, status = "Speech ready") }
        }
    }

    fun stopAudio() {
        playJob?.cancel()
        playJob = null
        activeTrack?.let { track ->
            runCatching {
                track.pause()
                track.flush()
                track.release()
            }
        }
        activeTrack = null
        _uiState.update { it.copy(playing = false) }
    }

    private fun refreshModelState(status: String? = null) {
        val ready = isModelReady()
        stopBusyTicker()
        _uiState.update {
            it.copy(
                modelReady = ready,
                busy = false,
                downloading = false,
                status = status ?: if (ready) "Model ready" else "Model not downloaded",
            )
        }
    }

    private fun isModelReady(): Boolean =
        QwenModel.files.all { file ->
            val local = File(modelDir, file.name)
            local.isFile && local.length() >= file.sizeBytes
        }

    private fun downloadFiles() {
        var completedBytes = 0L
        QwenModel.files.forEachIndexed { index, file ->
            val target = File(modelDir, file.name)
            if (target.isFile && target.length() >= file.sizeBytes) {
                completedBytes += file.sizeBytes
                return@forEachIndexed
            }

            val temp = File(modelDir, "${file.name}.download")
            val connection = (URL(file.url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 30_000
                readTimeout = 30_000
            }

            connection.inputStream.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var fileBytes = 0L
                    var lastUpdate = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        fileBytes += read
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 250L) {
                            lastUpdate = now
                            val total = completedBytes + fileBytes
                            _uiState.update {
                                it.copy(
                                    downloadBytes = total,
                                    downloadProgress = total.toFloat() / QwenModel.totalBytes.toFloat(),
                                    status = "Downloading ${index + 1}/${QwenModel.files.size}: ${file.name}",
                                )
                            }
                        }
                    }
                }
            }
            connection.disconnect()

            if (temp.length() < file.sizeBytes) {
                temp.delete()
                error("Downloaded ${file.name} is incomplete")
            }
            if (target.exists() && !target.delete()) {
                temp.delete()
                error("Could not replace ${file.name}")
            }
            if (!temp.renameTo(target)) {
                temp.delete()
                error("Could not save ${file.name}")
            }
            completedBytes += file.sizeBytes
        }
    }

    override fun onCleared() {
        stopAudio()
        stopBusyTicker()
        engine?.close()
        engine = null
        super.onCleared()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        setContent {
            QwenTtsTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    QwenTtsApp()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QwenTtsApp(viewModel: MainViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Qwen3 TTS", fontWeight = FontWeight.SemiBold)
                        Text("On-device speech synthesis", style = MaterialTheme.typography.bodySmall)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ModelPanel(state = state, onDownload = viewModel::downloadModel, onLoad = viewModel::loadModel)
            SynthesisPanel(
                state = state,
                onTextChange = viewModel::updateText,
                onSynthesize = viewModel::synthesize,
            )
            ResultPanel(state = state, onPlay = viewModel::playAudio, onStop = viewModel::stopAudio)
        }
    }
}

@Composable
private fun ModelPanel(state: QwenTtsUiState, onDownload: () -> Unit, onLoad: () -> Unit) {
    ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.GraphicEq, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Qwen3-TTS 0.6B Q8_0", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${formatBytes(QwenModel.totalBytes)} model package",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AssistChip(
                    onClick = {},
                    label = { Text(if (state.modelReady) "Ready" else "Missing") },
                )
            }

            if (state.downloading) {
                LinearProgressIndicator(
                    progress = { state.downloadProgress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${formatBytes(state.downloadBytes)} / ${formatBytes(state.downloadTotalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onDownload,
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.modelReady) "Verify" else "Download")
                }
                OutlinedButton(
                    onClick = onLoad,
                    enabled = state.modelReady && !state.loaded && !state.busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (state.loaded) "Loaded" else "Load")
                }
            }
        }
    }
}

@Composable
private fun SynthesisPanel(
    state: QwenTtsUiState,
    onTextChange: (String) -> Unit,
    onSynthesize: () -> Unit,
) {
    ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Text to speech", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = state.text,
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 5,
                maxLines = 8,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                label = { Text("Text") },
            )
            Button(
                onClick = onSynthesize,
                enabled = state.modelReady && state.text.isNotBlank() && !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (state.busy && !state.downloading) "Working" else "Synthesize")
            }
        }
    }
}

@Composable
private fun ResultPanel(state: QwenTtsUiState, onPlay: () -> Unit, onStop: () -> Unit) {
    ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Output", style = MaterialTheme.typography.titleMedium)
            Text(state.status, color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (state.busy && !state.downloading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (state.busy || state.operationElapsedMillis > 0L) {
                Text(
                    "Elapsed ${formatDuration(state.operationElapsedMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.synthesisFrames > 0) {
                Text(
                    "${state.synthesisFrames} frames generated (~${formatSeconds(state.synthesisFrames * 0.08)} audio, cap ${state.maxAudioTokens})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.cpuThreads > 0) {
                Text(
                    "${state.cpuThreads} CPU threads",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.error?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            if (state.sampleCount > 0) {
                val audioSeconds = if (state.sampleRate > 0) {
                    state.sampleCount.toDouble() / state.sampleRate.toDouble()
                } else {
                    0.0
                }
                val realtimeFactor = if (audioSeconds > 0.0) {
                    state.synthesisMillis.toDouble() / 1000.0 / audioSeconds
                } else {
                    0.0
                }
                Text(
                    "${state.sampleRate} Hz · ${formatSeconds(audioSeconds)} audio · ${formatDuration(state.synthesisMillis)} synth · RTF ${"%.1f".format(Locale.US, realtimeFactor)}x",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onPlay,
                    enabled = state.sampleCount > 0 && !state.playing,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Play")
                }
                OutlinedButton(
                    onClick = onStop,
                    enabled = state.playing,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Stop")
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex += 1
    }
    return "%.1f %s".format(value, units[unitIndex])
}

private fun formatDuration(milliseconds: Long): String {
    val totalSeconds = (milliseconds / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return if (minutes > 0L) {
        "%d:%02d".format(Locale.US, minutes, seconds)
    } else {
        "%ds".format(Locale.US, seconds)
    }
}

private fun formatSeconds(seconds: Double): String =
    "%.1fs".format(Locale.US, seconds.coerceAtLeast(0.0))
