package com.elthon.broexe.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the ASR `model_type` value that crashed the Detect/Speech self-tests.
 *
 * `sherpa-onnx-streaming-zipformer-en-2023-06-26` is a zipformer2 checkpoint. Passing
 * the legacy `"zipformer"` string makes sherpa skip model auto-detection and build the
 * wrong class, which reads missing ONNX metadata and terminates the process via
 * `_Exit(-1)` — no exception, no tombstone, just a dead app. This test is the tripwire
 * for anyone who edits that string.
 */
class StreamingModelTypeTest {

    @Test
    fun asrModelTypeMatchesCheckpoint() {
        assertEquals(NATIVE_MODEL_TYPE, ASR_MODEL_TYPE)
    }

    @Test
    fun kwsAlsoUsesZipformer2() {
        assertEquals(NATIVE_MODEL_TYPE, KWS_MODEL_TYPE)
    }

    private companion object {
        /** The family advertised in the ASR/KWS encoder ONNX metadata. */
        const val NATIVE_MODEL_TYPE = "zipformer2"
    }
}
