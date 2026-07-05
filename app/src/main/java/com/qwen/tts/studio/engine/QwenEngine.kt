package com.qwen.tts.studio.engine

class QwenEngine : AutoCloseable {
    private var nativePtr: Long = 0

    class NativeParams(
        val languageId: Int = 2050,
        val instruction: String? = null,
        val speaker: String? = null,
        val maxAudioTokens: Int = 512,
    )

    class NativeResult(
        val audio: FloatArray?,
        val sampleRate: Int,
        val success: Boolean,
        val errorMsg: String?,
        val timeMs: Long,
    )

    class NativeCapabilities(
        val loaded: Boolean,
        val supportsCloning: Boolean,
        val supportsNamedSpeakers: Boolean,
        val supportsInstruction: Boolean,
        val speakerEmbeddingDim: Int,
        val modelKind: Int,
        val speakerCount: Int,
    )

    fun interface ProgressCallback {
        fun onProgress(tokensGenerated: Int, maxTokens: Int)
    }

    init {
        System.loadLibrary("qwen3_tts_jni")
        nativePtr = nativeInit()
    }

    fun loadModels(modelDir: String, modelName: String? = null): Boolean =
        nativeLoadModels(nativePtr, modelDir, modelName)

    fun synthesize(
        text: String,
        referenceWav: String? = null,
        speakerEmbeddingPath: String? = null,
        params: NativeParams = NativeParams(),
    ): NativeResult =
        nativeSynthesize(nativePtr, text, referenceWav, speakerEmbeddingPath, params)

    fun getLastError(): String? = nativeGetLastError(nativePtr)

    fun getActiveBackendName(): String? = nativeGetActiveBackendName()

    fun setCpuThreads(nThreads: Int): Boolean = nativeSetCpuThreads(nThreads)

    fun getCpuThreads(): Int = nativeGetCpuThreads()

    fun setProgressCallback(callback: ProgressCallback?): Boolean =
        nativeSetProgressCallback(nativePtr, callback)

    fun getModelCapabilities(): NativeCapabilities? =
        nativeGetModelCapabilities(nativePtr)

    override fun close() {
        if (nativePtr != 0L) {
            nativeFree(nativePtr)
            nativePtr = 0
        }
    }

    private external fun nativeInit(): Long
    private external fun nativeFree(ptr: Long)
    private external fun nativeSetCpuThreads(nThreads: Int): Boolean
    private external fun nativeGetCpuThreads(): Int
    private external fun nativeSetProgressCallback(ptr: Long, callback: ProgressCallback?): Boolean
    private external fun nativeGetActiveBackendName(): String?
    private external fun nativeLoadModels(ptr: Long, modelDir: String, modelName: String?): Boolean
    private external fun nativeSynthesize(
        ptr: Long,
        text: String,
        referenceWav: String?,
        speakerEmbeddingPath: String?,
        params: NativeParams?,
    ): NativeResult
    private external fun nativeGetLastError(ptr: Long): String?
    private external fun nativeGetModelCapabilities(ptr: Long): NativeCapabilities?
}
