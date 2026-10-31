package com.thalzeronlabs.llama

/**
 * The raw JNI surface. Every handle is an opaque pointer owned by the Kotlin object that created it
 * ([LlamaModel] or [LlamaSession]); nothing outside this module touches these functions.
 */
internal object NativeBridge {

    init {
        System.loadLibrary("llama_kotlin")
        backendInit()
    }

    @JvmStatic external fun backendInit()

    @JvmStatic external fun loadModel(path: String, useMmap: Boolean): Long
    @JvmStatic external fun freeModel(model: Long)
    @JvmStatic external fun applyChatTemplate(
        model: Long,
        roles: Array<String>,
        contents: Array<String>,
        addAssistant: Boolean,
    ): String

    @JvmStatic external fun newSession(
        model: Long,
        nCtx: Int,
        nBatch: Int,
        nThreads: Int,
        temperature: Float,
        topK: Int,
        topP: Float,
        minP: Float,
        seed: Int,
    ): Long

    @JvmStatic external fun freeSession(session: Long)
    @JvmStatic external fun ingest(session: Long, text: String, addSpecial: Boolean): Int
    @JvmStatic external fun nextToken(session: Long): ByteArray?
    @JvmStatic external fun reset(session: Long)
    @JvmStatic external fun usedTokens(session: Long): Int
    @JvmStatic external fun contextSize(session: Long): Int
}
