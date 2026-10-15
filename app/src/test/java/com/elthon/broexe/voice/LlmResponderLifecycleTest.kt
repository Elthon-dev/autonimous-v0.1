package com.elthon.broexe.voice

import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LlmResponderLifecycleTest {

    private class FakeEngine : InferenceEngine {
        private val _state = MutableStateFlow<InferenceEngine.State>(InferenceEngine.State.Initialized)
        override val state: StateFlow<InferenceEngine.State> get() = _state

        var loadCount = 0
        var setPromptCount = 0
        var cleanCount = 0
        var lastPrompt: String? = null

        override suspend fun loadModel(pathToModel: String) {
            loadCount++
            _state.value = InferenceEngine.State.ModelReady
        }

        override suspend fun setSystemPrompt(systemPrompt: String) {
            setPromptCount++
            lastPrompt = systemPrompt
            _state.value = InferenceEngine.State.ModelReady
        }

        override fun sendUserPrompt(message: String, predictLength: Int): Flow<String> = flowOf("ok")

        override suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int): String = ""

        override fun cleanUp() {
            cleanCount++
            _state.value = InferenceEngine.State.Initialized
        }

        override fun systemInfo(): String = ""

        override fun backendReport(): String = ""

        override fun destroy() {}
    }

    private val a = File("/tmp/a.gguf")
    private val b = File("/tmp/b.gguf")

    @Test
    fun firstLoadReadsTheModelOnce() = runTest {
        val engine = FakeEngine()
        val responder = LlmResponder(engine)

        assertEquals(LoadOutcome.LOADED, responder.ensureLoaded(a, "sys"))
        assertEquals(1, engine.loadCount)
        assertEquals(1, engine.setPromptCount)
    }

    @Test
    fun sameModelAndPromptIsReusedNeverReloaded() = runTest {
        val engine = FakeEngine()
        val responder = LlmResponder(engine)

        responder.ensureLoaded(a, "sys")
        assertEquals(LoadOutcome.REUSED, responder.ensureLoaded(a, "sys"))
        assertEquals(LoadOutcome.REUSED, responder.ensureLoaded(a, "sys"))

        assertEquals("the GGUF must be read exactly once", 1, engine.loadCount)
        assertEquals(1, engine.setPromptCount)
    }

    @Test
    fun changingOnlyThePromptDoesNotReloadTheModel() = runTest {
        val engine = FakeEngine()
        val responder = LlmResponder(engine)

        responder.ensureLoaded(a, "sys-1")
        assertEquals(LoadOutcome.PROMPT_UPDATED, responder.ensureLoaded(a, "sys-2"))

        assertEquals(1, engine.loadCount)
        assertEquals(2, engine.setPromptCount)
        assertEquals("sys-2", engine.lastPrompt)
    }

    @Test
    fun switchingModelReloads() = runTest {
        val engine = FakeEngine()
        val responder = LlmResponder(engine)

        responder.ensureLoaded(a, "sys")
        assertEquals(LoadOutcome.LOADED, responder.ensureLoaded(b, "sys"))

        assertEquals(2, engine.loadCount)
        assertTrue("old model must be unloaded before the new one", engine.cleanCount >= 1)
    }

    @Test
    fun releaseUnloadsAndForcesReloadNextTime() = runTest {
        val engine = FakeEngine()
        val responder = LlmResponder(engine)

        responder.ensureLoaded(a, "sys")
        responder.release()
        assertEquals(1, engine.cleanCount)

        assertEquals(LoadOutcome.LOADED, responder.ensureLoaded(a, "sys"))
        assertEquals(2, engine.loadCount)
    }

    @Test
    fun answerStreamsEngineOutput() = runTest {
        val responder = LlmResponder(FakeEngine())
        responder.ensureLoaded(a, "sys")
        assertEquals("ok", responder.answer("hi"))
    }
}
