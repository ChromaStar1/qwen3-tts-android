package com.qwen.tts.android.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes an arbitrary audio file (content:// or file:// Uri) into mono
 * float32 PCM samples, using Android's built-in MediaExtractor/MediaCodec.
 * Supports whatever formats the device's codecs support — in practice
 * WAV (PCM), MP3, M4A/AAC, and OGG/Opus on any modern Android device.
 *
 * No extra dependencies needed: MediaExtractor/MediaCodec are part of the
 * Android SDK.
 */
object AudioFileDecoder {

    data class DecodedAudio(val samples: FloatArray, val sampleRate: Int)

    fun decodeToMono(context: Context, uri: Uri): DecodedAudio {
        val extractor = MediaExtractor()
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("Cannot open file descriptor for $uri")
        pfd.use { extractor.setDataSource(it.fileDescriptor) }

        try {
            val trackIndex = selectAudioTrack(extractor)
            require(trackIndex >= 0) { "No audio track found in file" }
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: error("Missing MIME type")
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val interleaved = if (mime == MediaFormat.MIMETYPE_AUDIO_RAW) {
                readRawPcm(extractor)
            } else {
                decodeWithCodec(extractor, format, mime)
            }

            val mono = if (channelCount > 1) {
                downmixToMono(interleaved, channelCount)
            } else {
                FloatArray(interleaved.size) { interleaved[it] / 32768f }
            }

            return DecodedAudio(mono, sampleRate)
        } finally {
            extractor.release()
        }
    }

    /** Resample [input] from [fromRate] to [toRate] using linear interpolation. */
    fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate || input.isEmpty()) return input
        val ratio = toRate.toDouble() / fromRate.toDouble()
        val outLength = (input.size * ratio).toInt().coerceAtLeast(1)
        val output = FloatArray(outLength)
        for (i in output.indices) {
            val srcPos = i / ratio
            val idx = srcPos.toInt()
            val frac = (srcPos - idx).toFloat()
            val s0 = input.getOrElse(idx) { input.last() }
            val s1 = input.getOrElse(idx + 1) { input.last() }
            output[i] = s0 + (s1 - s0) * frac
        }
        return output
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return -1
    }

    /** WAV/PCM containers: no codec involved, read samples straight from the extractor. */
    private fun readRawPcm(extractor: MediaExtractor): ShortArray {
        val chunks = mutableListOf<ShortArray>()
        val buffer = ByteBuffer.allocate(1 shl 20).order(ByteOrder.LITTLE_ENDIAN)
        while (true) {
            buffer.clear()
            val read = extractor.readSampleData(buffer, 0)
            if (read < 0) break
            buffer.limit(read)
            val shorts = ShortArray(read / 2)
            buffer.asShortBuffer().get(shorts)
            chunks.add(shorts)
            extractor.advance()
        }
        return mergeChunks(chunks)
    }

    private fun decodeWithCodec(
        extractor: MediaExtractor,
        format: MediaFormat,
        mime: String
    ): ShortArray {
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val chunks = mutableListOf<ShortArray>()
        val bufferInfo = MediaCodec.BufferInfo()
        var sawInputEOS = false
        var sawOutputEOS = false

        try {
            while (!sawOutputEOS) {
                if (!sawInputEOS) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            sawInputEOS = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outIndex)!!
                    if (bufferInfo.size > 0) {
                        outputBuffer.order(ByteOrder.LITTLE_ENDIAN)
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        val shorts = ShortArray(bufferInfo.size / 2)
                        outputBuffer.asShortBuffer().get(shorts)
                        chunks.add(shorts)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawOutputEOS = true
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
        }
        return mergeChunks(chunks)
    }

    private fun mergeChunks(chunks: List<ShortArray>): ShortArray {
        val total = chunks.sumOf { it.size }
        val out = ShortArray(total)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(out, offset)
            offset += chunk.size
        }
        return out
    }

    private fun downmixToMono(interleaved: ShortArray, channels: Int): FloatArray {
        val frames = interleaved.size / channels
        val out = FloatArray(frames)
        for (i in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) sum += interleaved[i * channels + c] / 32768f
            out[i] = sum / channels
        }
        return out
    }
}

