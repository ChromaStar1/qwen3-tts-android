package com.qwen.tts.android.audio

import android.content.Context
import android.net.Uri

/**
 * High-level "import voice from file" pipeline for voice cloning.
 *
 * Produces mono float32 PCM at [TARGET_SAMPLE_RATE] (24 kHz), matching what
 * qwen3-tts.cpp's speaker encoder (ECAPA-TDNN x-vector extractor) expects.
 * Feed the resulting [VoiceImportResult.pcm] into whichever JNI call your
 * mic-recording path already uses to extract the speaker embedding — the
 * encoder doesn't care whether the PCM came from a microphone or a file,
 * only that the sample rate/format match.
 */
object VoiceCloneImporter {

    const val TARGET_SAMPLE_RATE = 24_000
    private const val MIN_DURATION_SECONDS = 3.0
    private const val MAX_DURATION_SECONDS = 30.0
    private const val SILENCE_PAD_SECONDS = 0.5

    data class VoiceImportResult(
        val pcm: FloatArray,
        val sampleRate: Int,
        val durationSeconds: Float,
        val warnings: List<String>
    )

    /**
     * Decodes [uri] (wav/mp3/m4a/ogg), downmixes to mono, resamples to 24kHz,
     * auto-trims to at most 30s (long reference audio can make qwen3-tts.cpp's
     * generation hang), and by default appends 0.5s of silence at the end —
     * this reduces a known artifact where the first generated phoneme "bleeds"
     * from whatever sound the reference clip ends on.
     */
    fun importFromFile(
        context: Context,
        uri: Uri,
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

        return VoiceImportResult(
            pcm = pcm,
            sampleRate = TARGET_SAMPLE_RATE,
            durationSeconds = pcm.size.toFloat() / TARGET_SAMPLE_RATE,
            warnings = warnings
        )
    }
}
