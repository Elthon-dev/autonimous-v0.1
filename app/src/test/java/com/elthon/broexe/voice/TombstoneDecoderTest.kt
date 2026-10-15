package com.elthon.broexe.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Builds a synthetic `Tombstone` protobuf (matching debuggerd's tombstone.proto) and
 * checks that [TombstoneDecoder] pulls the useful fields back out. This is the
 * regression test for "the native crash trace is a protobuf, not text".
 */
class TombstoneDecoderTest {

    // --- a tiny proto3 writer -------------------------------------------------

    private class Writer {
        private val out = ByteArrayOutputStream()

        fun raw(b: ByteArray) = out.write(b)

        fun varint(v: Long) {
            var x = v
            while (true) {
                if (x and 0x7FL.inv() == 0L) {
                    out.write(x.toInt())
                    return
                }
                out.write(((x and 0x7F) or 0x80).toInt())
                x = x ushr 7
            }
        }

        fun tag(field: Int, wireType: Int) = varint(((field shl 3) or wireType).toLong())

        fun varintField(field: Int, v: Long) {
            tag(field, 0); varint(v)
        }

        fun bytesField(field: Int, b: ByteArray) {
            tag(field, 2); varint(b.size.toLong()); raw(b)
        }

        fun stringField(field: Int, s: String) = bytesField(field, s.toByteArray())

        fun messageField(field: Int, body: Writer.() -> Unit) {
            val w = Writer(); w.body(); bytesField(field, w.done())
        }

        fun done(): ByteArray = out.toByteArray()
    }

    private fun signal(): ByteArray {
        val w = Writer()
        w.varintField(1, 11)             // number
        w.stringField(2, "SIGSEGV")      // name
        w.varintField(3, 1)              // code
        w.stringField(4, "SEGV_MAPERR")  // code_name
        w.varintField(8, 1)              // has_fault_address
        w.varintField(9, 0)              // fault_address
        return w.done()
    }

    private fun frame(pc: Long, fn: String?, file: String?): ByteArray {
        val w = Writer()
        w.varintField(1, pc)             // rel_pc
        w.varintField(2, pc)             // pc
        fn?.let { w.stringField(4, it) } // function_name
        file?.let { w.stringField(6, it) }
        return w.done()
    }

    private fun thread(id: Int, name: String, frames: List<ByteArray>): ByteArray {
        val w = Writer()
        w.varintField(1, id.toLong())
        w.stringField(2, name)
        for (f in frames) w.messageField(4) { raw(f) }
        return w.done()
    }

    private fun threadMapEntry(key: Int, value: ByteArray): ByteArray {
        val w = Writer()
        w.varintField(1, key.toLong())
        w.messageField(2) { raw(value) }
        return w.done()
    }

    private fun mapping(begin: Long, end: Long, name: String, buildId: String): ByteArray {
        val w = Writer()
        w.varintField(1, begin)
        w.varintField(2, end)
        w.varintField(4, 1)  // read
        w.varintField(6, 1)  // execute
        w.stringField(7, name)
        w.stringField(8, buildId)
        return w.done()
    }

    private fun tombstone(): ByteArray {
        val crashing = thread(
            id = 200,
            name = "llama-native",
            frames = listOf(
                frame(0x1000L, "ggml_backend_cpu_reg", "ggml-cpu.cpp"),
                frame(0x2000L, null, null),
            ),
        )
        val other = thread(id = 1, name = "main", frames = listOf(frame(0x3000L, "main", null)))
        val w = Writer()
        w.varintField(1, 1)                          // arch = ARM64
        w.stringField(2, "oppo/a18:15/...")          // build_fingerprint
        w.stringField(4, "1739000000")               // timestamp (string)
        w.varintField(5, 100)                        // pid
        w.varintField(6, 200)                        // tid
        w.varintField(7, 300)                        // uid
        w.stringField(9, "/system/bin/app_process")  // command_line
        w.messageField(10) { raw(signal()) }         // signal_info
        w.stringField(14, "abort")                   // abort_message
        w.messageField(15) { stringField(1, "cause") } // causes
        w.messageField(16) { raw(threadMapEntry(200, crashing)) }
        w.messageField(16) { raw(threadMapEntry(1, other)) }
        w.messageField(17) { raw(mapping(0x1000L, 0x3000L, "/apex/libggml.so", "abc")) }
        w.varintField(20, 7)                         // process_uptime
        return w.done()
    }

    // --- tests ----------------------------------------------------------------

    @Test
    fun decodesSignalPidTidAndArch() {
        val t = TombstoneDecoder.decode(tombstone())
        assertNotNull(t)
        t!!
        assertEquals(1, t.arch)
        assertEquals("arm64", t.archName())
        assertEquals(100, t.pid)
        assertEquals(200, t.tid)
        assertEquals(300, t.uid)
        assertEquals(7, t.processUptime)
        assertEquals("oppo/a18:15/...", t.buildFingerprint)
        assertEquals("1739000000", t.timestamp)
        assertEquals("abort", t.abortMessage)
    }

    @Test
    fun decodesSignal() {
        val s = TombstoneDecoder.decode(tombstone())!!.signal!!
        assertEquals(11, s.number)
        assertEquals("SIGSEGV", s.name)
        assertEquals(1, s.code)
        assertEquals("SEGV_MAPERR", s.codeName)
        assertTrue(s.hasFaultAddress)
        assertEquals(0L, s.faultAddress)
    }

    @Test
    fun resolvesCrashingThreadByTidAndBacktrace() {
        val t = TombstoneDecoder.decode(tombstone())!!
        val crash = t.threadById(t.tid)
        assertNotNull(crash)
        assertEquals("llama-native", crash!!.name)
        assertEquals(2, crash.backtrace.size)
        assertEquals("ggml_backend_cpu_reg", crash.backtrace[0].functionName)
        assertEquals("ggml-cpu.cpp", crash.backtrace[0].fileName)
        assertNull(crash.backtrace[1].functionName)
    }

    @Test
    fun mapsPcToLibraryUsingMappings() {
        val t = TombstoneDecoder.decode(tombstone())!!
        val crash = t.threadById(t.tid)!!
        val lib = t.libraryForPc(crash.backtrace[0].pc)
        assertNotNull(lib)
        assertEquals("/apex/libggml.so", lib!!.name)
        assertEquals("abc", lib.buildId)
        // The second frame's pc (0x2000) is still inside the mapping, the main frame's
        // pc (0x3000) is not.
        assertNotNull(t.libraryForPc(crash.backtrace[1].pc))
        assertNull(t.libraryForPc(0x3000L))
    }

    @Test
    fun formatIncludesKeyFacts() {
        val text = TombstoneDecoder.format(TombstoneDecoder.decode(tombstone())!!)
        assertTrue(text.contains("arch=arm64"))
        assertTrue(text.contains("SIGSEGV"))
        assertTrue(text.contains("SEGV_MAPERR"))
        assertTrue(text.contains("ggml_backend_cpu_reg"))
        assertTrue(text.contains("llama-native"))
        assertTrue(text.contains("/apex/libggml.so"))
    }

    @Test
    fun decodeReturnsNullForEmptyOrGarbage() {
        assertNull(TombstoneDecoder.decode(null))
        assertNull(TombstoneDecoder.decode(ByteArray(0)))
        // A truncated/invalid varint must not escape as an exception.
        assertNull(TombstoneDecoder.decode(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())))
    }

    @Test
    fun printableStringsExtractsAsciiRuns() {
        val bytes = "hello\u0000\u0001worldwide".toByteArray()
        val s = TombstoneDecoder.printableStrings(bytes)
        assertEquals(listOf("hello", "worldwide"), s)
    }

    @Test
    fun analyzeReportsPartialTombstoneWhenTruncated() {
        val full = tombstone()
        // Cut in the middle of the message: the trailing bytes now end mid-field.
        val truncated = full.copyOfRange(0, full.size * 2 / 3)

        val a = TombstoneDecoder.analyze(truncated)

        // We must not lose the fields that parsed before the cut.
        val t = a.tombstone ?: error("expected a partial tombstone")
        assertEquals(1, t.arch)
        assertEquals(100, t.pid)
        assertEquals(200, t.tid)

        // And we must explain exactly why the rest did not decode.
        assertNotNull(a.error)
        assertTrue(a.isPartial())
        assertTrue(a.truncated)
        assertTrue(a.errorOffset >= 0)
        assertTrue(a.fields.isNotEmpty())
        assertTrue(a.headHex.isNotEmpty())
        assertEquals(truncated.size, a.length)
    }

    @Test
    fun analyzeExplainsGarbageTrace() {
        val a = TombstoneDecoder.analyze(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
        assertNull(a.tombstone)
        assertNotNull(a.error)
        assertTrue(a.truncated)
    }

    @Test
    fun analyzeDescribesTopLevelFieldsForValidTombstone() {
        val a = TombstoneDecoder.analyze(tombstone())
        assertNotNull(a.tombstone)
        assertNull(a.error)
        // arch(field 1), pid(5), tid(6): the walk must have seen them.
        assertTrue(a.fields.any { it.contains("field=1 ") })
        assertTrue(a.fields.any { it.contains("field=5 ") })
        assertTrue(a.fields.any { it.contains("field=16 ") })
    }
}
