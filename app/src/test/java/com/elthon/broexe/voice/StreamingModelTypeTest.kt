package com.elthon.broexe.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the `model_type` values that, when wrong, kill the process.
 *
 * When `model_type` is non-empty sherpa skips model auto-detection and builds the
 * class for that exact string; a wrong value reads missing ONNX metadata and the
 * native loader calls `_Exit(-1)` — no exception, no tombstone, just a dead app.
 * The ASR is now an offline Parakeet TDT NeMo transducer (`"nemo_transducer"`), and
 * the keyword spotter remains a zipformer2 export. This test is the tripwire for
 * anyone who edits either string.
 */
class StreamingModelTypeTest {

    @Test
    fun asrModelTypeMatchesCheckpoint() {
        assertEquals("nemo_transducer", ASR_MODEL_TYPE)
    }

    @Test
    fun kwsUsesZipformer2() {
        assertEquals("zipformer2", KWS_MODEL_TYPE)
    }
}