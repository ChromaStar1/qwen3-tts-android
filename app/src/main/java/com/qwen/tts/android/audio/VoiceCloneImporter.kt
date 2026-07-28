package com.qwen.tts.android.audio

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.OutputStream

/**
 * High-level "import voice from file" pipeline for voice cloning.
 *
 * Your app's native `extractSpeakerEmbedding(wavPath, embeddingOutPath)` call
 * takes a WAV file path (same as the mic-recording flow writes to
 * `reference.wav`), not a raw PCM array. So this writes a ready-to-use WAV
 * file instead of returning samples directly — call [importToWav] and pass
 * the resulting file path straight into `extractSpeakerEmbedding`, exactly
 * like `stopVoiceRecordingAndCreate` already does for microphone recordings.
 */
object VoiceCloneImporter {

    const val TARGET_SAMPLE_RATE = 24_000
    private const val MIN_DURATION_SECONDS = 3.0
    private const val MAX_DURATION_SECONDS = 30.0
    private const val SILENCE_PAD_SECONDS = 0.5

    data class VoiceImportResult(
        val durationSeconds: Float,
        val warnings: List<String>
    )

    /**
     * Decodes [uri] (wav/mp3/m4a/ogg), downmixes to mono, resamples to 24kHz,
     * auto-trims to at most 30s (long reference audio can make qwen3-tts.cpp's
     * generation hang), pads 0.5s of silence at the end (reduces a known
     * artifact where the first generated phoneme "bleeds" from whatever sound
     * the reference clip ends on), and writes the result as a 16-bit PCM WAV
     * to [outputFile].
     */
    fun importToWav(
        context: Context,
        uri: Uri,
        outputFile: File,
        appendSilencePad: Boolean = true
    ): VoiceImportResult {
        val decoded = AudioFileDecoder.decodeToMono(context, uri)
        val resampled = AudioFileDecoder.resample(
            decoded.samples, decoded.sampleRate, TARGET_SAMPLE_RATE
        )

        val warnings = mutableListOf<String>()
        var pcm = resampled
        val rawDurationSeconds = pcm.size.toFloat() / TARGET_SAMPLE_RATE

        if (rawDurationSeconds < MIN_DURATION_SECONDS) {
            warnings += "Референс короче ${MIN_DURATION_SECONDS.toInt()} секунд " +
                "(${"%.1f".format(rawDurationSeconds)}с) — качество клонирования может " +
                "пострадать. Рекомендуется 3-30 секунд чистой речи без фонового шума."
        }

        if (rawDurationSeconds > MAX_DURATION_SECONDS) {
            val maxSamples = (MAX_DURATION_SECONDS * TARGET_SAMPLE_RATE).toInt()
            pcm = pcm.copyOfRange(0, maxSamples)
            warnings += "Файл длиннее ${MAX_DURATION_SECONDS.toInt()} секунд — обрезано " +
                "до ${MAX_DURATION_SECONDS.toInt()}с (длинный референс может вызвать " +
                "зависание генерации)."
        }

        if (appendSilencePad) {
            val padSamples = (SILENCE_PAD_SECONDS * TARGET_SAMPLE_RATE).toInt()
            pcm = pcm + FloatArray(padSamples) // zeros = silence
        }

        outputFile.parentFile?.mkdirs()
        outputFile.outputStream().use { writeWav(it, pcm, TARGET_SAMPLE_RATE) }

        return VoiceImportResult(
            durationSeconds = pcm.size.toFloat() / TARGET_SAMPLE_RATE,
            warnings = warnings
        )
    }

    /** Standard 16-bit PCM mono WAV writer (same format your app already uses). */
    private fun writeWav(output: OutputStream, samples: FloatArray, sampleRate: Int) {
        val dataBytes = samples.size * 2
        output.writeAscii("RIFF")
        output.writeIntLe(36 + dataBytes)
        output.writeAscii("WAVE")
        output.writeAscii("fmt ")
        output.writeIntLe(16)
        output.writeShortLe(1)
        output.writeShortLe(1)
        output.writeIntLe(sampleRate)
        output.writeIntLe(sampleRate * 2)
        output.writeShortLe(2)
        output.writeShortLe(16)
        output.writeAscii("data")
        output.writeIntLe(dataBytes)
        samples.forEach { sample ->
            val clamped = sample.coerceIn(-1f, 1f)
            output.writeShortLe((clamped * 32767f).toInt())
        }
    }

    private fun OutputStream.writeAscii(text: String) = write(text.toByteArray(Charsets.US_ASCII))

    private fun OutputStream.writeIntLe(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }

    private fun OutputStream.writeShortLe(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }
}
