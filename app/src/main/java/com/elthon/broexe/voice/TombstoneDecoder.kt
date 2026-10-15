package com.elthon.broexe.voice

/**
 * Minimal, dependency-free decoder for the Android native-crash `Tombstone` protobuf.
 *
 * From API 31, `ApplicationExitInfo.getTraceInputStream()` for a `REASON_CRASH_NATIVE`
 * exit yields a serialized `android.os.Tombstone` protobuf (schema:
 * `system/core/debuggerd/proto/tombstone.proto`), **not** human-readable text and not
 * a `ProcessCrashedReport`. Reading those bytes as a string produces garbage — which is
 * exactly the bug this fixes.
 *
 * This is intentionally a hand-rolled protobuf reader (protobuf wire format, proto3)
 * so the app needs no `protobuf-java`/`protobuf-lite` dependency. Only the fields we
 * actually display are decoded; everything else is skipped safely.
 *
 * The decoder never throws on malformed input: [decode] returns null, and callers fall
 * back to a printable-strings scan so a bad trace still yields *something*. When a
 * trace fails to decode, [analyze] records exactly *why* — the parser exception, the
 * byte offset, a hex head and a schema-agnostic walk of the top-level fields — so a
 * forensic report can still describe the trace.
 */
object TombstoneDecoder {

    data class Frame(
        val relPc: Long,
        val pc: Long,
        val functionName: String?,
        val functionOffset: Long,
        val fileName: String?,
        val buildId: String?,
    )

    data class ThreadInfo(
        val id: Int,
        val name: String?,
        val backtrace: List<Frame>,
        val notes: List<String>,
    )

    data class Mapping(
        val begin: Long,
        val end: Long,
        val offset: Long,
        val read: Boolean,
        val write: Boolean,
        val execute: Boolean,
        val name: String?,
        val buildId: String?,
        val loadBias: Long,
    )

    data class Signal(
        val number: Int,
        val name: String?,
        val code: Int,
        val codeName: String?,
        val hasFaultAddress: Boolean,
        val faultAddress: Long,
    )

    data class Tombstone(
        val arch: Int,
        val buildFingerprint: String?,
        val revision: String?,
        val timestamp: String?,
        val pid: Int,
        val tid: Int,
        val uid: Int,
        val selinuxLabel: String?,
        val commandLine: List<String>,
        val processUptime: Int,
        val signal: Signal?,
        val abortMessage: String?,
        val causes: List<String>,
        val threads: List<ThreadInfo>,
        val mappings: List<Mapping>,
    ) {
        fun archName(): String = when (arch) {
            0 -> "arm32"
            1 -> "arm64"
            2 -> "x86"
            3 -> "x86_64"
            4 -> "riscv64"
            else -> "unknown($arch)"
        }

        /** Library responsible for an absolute program counter, via mapping ranges. */
        fun libraryForPc(pc: Long): Mapping? =
            mappings.firstOrNull { pc >= it.begin && pc < it.end }

        fun threadById(id: Int): ThreadInfo? = threads.firstOrNull { it.id == id }
    }

    /**
     * Structured outcome of inspecting a raw trace. [tombstone] may be a *partial*
     * parse when the trace appears to be truncated; [error] then explains the stop.
     */
    data class Analysis(
        val tombstone: Tombstone?,
        val error: String?,
        val errorOffset: Int,
        val headHex: String,
        val fields: List<String>,
        val length: Int,
        val truncated: Boolean,
    ) {
        fun isPartial(): Boolean = tombstone != null && error != null
    }

    // --- wire format ----------------------------------------------------------

    private const val WT_VARINT = 0
    private const val WT_64BIT = 1
    private const val WT_LEN = 2
    private const val WT_32BIT = 5

    private class Reader(private val buf: ByteArray) {
        var pos = 0
        fun eof() = pos >= buf.size

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                require(pos < buf.size) { "varint overrun at offset $pos (size ${buf.size})" }
                val b = buf[pos++].toInt() and 0xFF
                result = result or ((b.toLong() and 0x7F) shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                require(shift <= 63) { "varint too long at offset $pos" }
            }
        }

        fun bytes(): ByteArray {
            val len = varint().toInt()
            require(len >= 0 && pos + len <= buf.size) {
                "length overrun: need $len bytes at offset $pos (size ${buf.size})"
            }
            val out = buf.copyOfRange(pos, pos + len)
            pos += len
            return out
        }

        fun string(): String = String(bytes(), Charsets.UTF_8)

        /** Advance [n] bytes, bounds-checked (used by the schema-agnostic walk). */
        fun advance(n: Int) {
            require(n >= 0 && pos + n <= buf.size) {
                "advance overrun: need $n bytes at offset $pos (size ${buf.size})"
            }
            pos += n
        }

        fun skip(wireType: Int) {
            when (wireType) {
                WT_VARINT -> varint()
                WT_64BIT -> advance(8)
                WT_LEN -> advance(varint().toInt())
                WT_32BIT -> advance(4)
                else -> throw IllegalStateException("bad wire type $wireType at offset $pos")
            }
        }
    }

    private inline fun forEachField(buf: ByteArray, block: (field: Int, wt: Int, r: Reader) -> Unit) {
        val r = Reader(buf)
        while (!r.eof()) {
            val tag = r.varint()
            val field = (tag ushr 3).toInt()
            val wt = (tag and 0x7).toInt()
            block(field, wt, r)
        }
    }

    // --- decoding -------------------------------------------------------------

    /** Accumulates parsed fields so a partial parse survives an exception. */
    private class Accumulator {
        var arch = 0
        var buildFingerprint: String? = null
        var revision: String? = null
        var timestamp: String? = null
        var pid = 0
        var tid = 0
        var uid = 0
        var selinuxLabel: String? = null
        val commandLine = ArrayList<String>()
        var processUptime = 0
        var signal: Signal? = null
        var abortMessage: String? = null
        val causes = ArrayList<String>()
        val threads = ArrayList<ThreadInfo>()
        val mappings = ArrayList<Mapping>()

        fun toTombstone() = Tombstone(
            arch = arch,
            buildFingerprint = buildFingerprint,
            revision = revision,
            timestamp = timestamp,
            pid = pid,
            tid = tid,
            uid = uid,
            selinuxLabel = selinuxLabel,
            commandLine = commandLine,
            processUptime = processUptime,
            signal = signal,
            abortMessage = abortMessage,
            causes = causes,
            threads = threads,
            mappings = mappings,
        )
    }

    private fun applyField(acc: Accumulator, field: Int, wt: Int, r: Reader) {
        when (field) {
            1 -> if (wt == WT_VARINT) acc.arch = r.varint().toInt() else r.skip(wt)
            2 -> if (wt == WT_LEN) acc.buildFingerprint = r.string() else r.skip(wt)
            3 -> if (wt == WT_LEN) acc.revision = r.string() else r.skip(wt)
            4 -> if (wt == WT_LEN) acc.timestamp = r.string() else r.skip(wt)
            5 -> if (wt == WT_VARINT) acc.pid = r.varint().toInt() else r.skip(wt)
            6 -> if (wt == WT_VARINT) acc.tid = r.varint().toInt() else r.skip(wt)
            7 -> if (wt == WT_VARINT) acc.uid = r.varint().toInt() else r.skip(wt)
            8 -> if (wt == WT_LEN) acc.selinuxLabel = r.string() else r.skip(wt)
            9 -> if (wt == WT_LEN) acc.commandLine.add(r.string()) else r.skip(wt)
            10 -> if (wt == WT_LEN) acc.signal = parseSignal(r.bytes()) else r.skip(wt)
            14 -> if (wt == WT_LEN) acc.abortMessage = r.string() else r.skip(wt)
            15 -> if (wt == WT_LEN) parseCause(r.bytes())?.let { acc.causes.add(it) } else r.skip(wt)
            16 -> if (wt == WT_LEN) parseThreadEntry(r.bytes())?.let { acc.threads.add(it) } else r.skip(wt)
            17 -> if (wt == WT_LEN) acc.mappings.add(parseMapping(r.bytes())) else r.skip(wt)
            20 -> if (wt == WT_VARINT) acc.processUptime = r.varint().toInt() else r.skip(wt)
            else -> r.skip(wt)
        }
    }

    /** Strict parse: throws on the first malformed byte. */
    private fun parseFull(bytes: ByteArray): Tombstone {
        val acc = Accumulator()
        forEachField(bytes) { field, wt, r -> applyField(acc, field, wt, r) }
        return acc.toTombstone()
    }

    /** Tolerant parse: keeps every complete top-level field up to the first bad one. */
    private fun parsePartial(bytes: ByteArray): Tombstone {
        val acc = Accumulator()
        val r = Reader(bytes)
        while (!r.eof()) {
            val ok = try {
                val tag = r.varint()
                applyField(acc, (tag ushr 3).toInt(), (tag and 0x7).toInt(), r)
                true
            } catch (_: Throwable) {
                false
            }
            if (!ok) break
        }
        return acc.toTombstone()
    }

    private fun meaningful(t: Tombstone): Boolean =
        t.threads.isNotEmpty() || t.mappings.isNotEmpty() || t.commandLine.isNotEmpty() ||
            t.pid != 0 || t.tid != 0 || t.signal != null || t.timestamp != null ||
            t.buildFingerprint != null || t.causes.isNotEmpty() || t.abortMessage != null

    /** Returns the best tombstone we can build plus the parser failure, if any. */
    private fun parseWithError(bytes: ByteArray): Pair<Tombstone?, String?> = try {
        parseFull(bytes) to null
    } catch (e: Throwable) {
        val failure = describe(e)
        val partial = runCatching { parsePartial(bytes) }.getOrNull()
        if (partial != null && meaningful(partial)) partial to failure else null to failure
    }

    fun decode(bytes: ByteArray?): Tombstone? {
        if (bytes == null || bytes.isEmpty()) return null
        return parseWithError(bytes).first
    }

    /**
     * Inspect a raw trace: decode it (fully or partially) and, when it cannot be
     * decoded, capture the exact parser exception, byte offset, hex head and a
     * schema-agnostic walk of the top-level protobuf fields.
     */
    fun analyze(bytes: ByteArray?): Analysis {
        if (bytes == null) {
            return Analysis(null, "no trace bytes supplied", -1, "", emptyList(), 0, false)
        }
        if (bytes.isEmpty()) {
            return Analysis(null, "trace is empty (0 bytes)", 0, "", emptyList(), 0, false)
        }
        val walk = walkTopLevel(bytes)
        val (tombstone, error) = parseWithError(bytes)
        return Analysis(
            tombstone = tombstone,
            error = error ?: if (tombstone == null) "could not parse as Tombstone protobuf" else null,
            errorOffset = walk.errorOffset,
            headHex = hex(bytes, 32),
            fields = walk.fields,
            length = bytes.size,
            truncated = walk.truncated,
        )
    }

    private fun parseSignal(bytes: ByteArray): Signal {
        var number = 0
        var name: String? = null
        var code = 0
        var codeName: String? = null
        var hasFault = false
        var fault = 0L
        forEachField(bytes) { field, wt, r ->
            when (field) {
                1 -> if (wt == WT_VARINT) number = r.varint().toInt() else r.skip(wt)
                2 -> if (wt == WT_LEN) name = r.string() else r.skip(wt)
                3 -> if (wt == WT_VARINT) code = r.varint().toInt() else r.skip(wt)
                4 -> if (wt == WT_LEN) codeName = r.string() else r.skip(wt)
                8 -> if (wt == WT_VARINT) hasFault = r.varint() != 0L else r.skip(wt)
                9 -> if (wt == WT_VARINT) fault = r.varint() else r.skip(wt)
                else -> r.skip(wt)
            }
        }
        return Signal(number, name, code, codeName, hasFault, fault)
    }

    private fun parseCause(bytes: ByteArray): String? {
        var human: String? = null
        forEachField(bytes) { field, wt, r ->
            when (field) {
                1 -> if (wt == WT_LEN) human = r.string() else r.skip(wt)
                else -> r.skip(wt)
            }
        }
        return human
    }

    private fun parseThreadEntry(bytes: ByteArray): ThreadInfo? {
        var value: ThreadInfo? = null
        forEachField(bytes) { field, wt, r ->
            when (field) {
                2 -> if (wt == WT_LEN) value = parseThread(r.bytes()) else r.skip(wt)
                else -> r.skip(wt)
            }
        }
        return value
    }

    private fun parseThread(bytes: ByteArray): ThreadInfo {
        var id = 0
        var name: String? = null
        val backtrace = ArrayList<Frame>()
        val notes = ArrayList<String>()
        forEachField(bytes) { field, wt, r ->
            when (field) {
                1 -> if (wt == WT_VARINT) id = r.varint().toInt() else r.skip(wt)
                2 -> if (wt == WT_LEN) name = r.string() else r.skip(wt)
                4 -> if (wt == WT_LEN) backtrace.add(parseFrame(r.bytes())) else r.skip(wt)
                7 -> if (wt == WT_LEN) notes.add(r.string()) else r.skip(wt)
                else -> r.skip(wt)
            }
        }
        return ThreadInfo(id, name, backtrace, notes)
    }

    private fun parseFrame(bytes: ByteArray): Frame {
        var relPc = 0L
        var pc = 0L
        var functionName: String? = null
        var functionOffset = 0L
        var fileName: String? = null
        var buildId: String? = null
        forEachField(bytes) { field, wt, r ->
            when (field) {
                1 -> if (wt == WT_VARINT) relPc = r.varint() else r.skip(wt)
                2 -> if (wt == WT_VARINT) pc = r.varint() else r.skip(wt)
                4 -> if (wt == WT_LEN) functionName = r.string() else r.skip(wt)
                5 -> if (wt == WT_VARINT) functionOffset = r.varint() else r.skip(wt)
                6 -> if (wt == WT_LEN) fileName = r.string() else r.skip(wt)
                8 -> if (wt == WT_LEN) buildId = r.string() else r.skip(wt)
                else -> r.skip(wt)
            }
        }
        return Frame(relPc, pc, functionName, functionOffset, fileName, buildId)
    }

    private fun parseMapping(bytes: ByteArray): Mapping {
        var begin = 0L
        var end = 0L
        var offset = 0L
        var read = false
        var write = false
        var execute = false
        var name: String? = null
        var buildId: String? = null
        var loadBias = 0L
        forEachField(bytes) { field, wt, r ->
            when (field) {
                1 -> if (wt == WT_VARINT) begin = r.varint() else r.skip(wt)
                2 -> if (wt == WT_VARINT) end = r.varint() else r.skip(wt)
                3 -> if (wt == WT_VARINT) offset = r.varint() else r.skip(wt)
                4 -> if (wt == WT_VARINT) read = r.varint() != 0L else r.skip(wt)
                5 -> if (wt == WT_VARINT) write = r.varint() != 0L else r.skip(wt)
                6 -> if (wt == WT_VARINT) execute = r.varint() != 0L else r.skip(wt)
                7 -> if (wt == WT_LEN) name = r.string() else r.skip(wt)
                8 -> if (wt == WT_LEN) buildId = r.string() else r.skip(wt)
                9 -> if (wt == WT_VARINT) loadBias = r.varint() else r.skip(wt)
                else -> r.skip(wt)
            }
        }
        return Mapping(begin, end, offset, read, write, execute, name, buildId, loadBias)
    }

    // --- forensic helpers -----------------------------------------------------

    private class Walk(
        val fields: List<String>,
        val error: String?,
        val errorOffset: Int,
        val truncated: Boolean,
    )

    /** Schema-agnostic top-level walk: field number, wire type and shape, no guessing. */
    private fun walkTopLevel(bytes: ByteArray): Walk {
        val r = Reader(bytes)
        val out = ArrayList<String>()
        var index = 0
        while (!r.eof()) {
            val start = r.pos
            try {
                val tag = r.varint()
                val field = (tag ushr 3).toInt()
                val wt = (tag and 0x7).toInt()
                val detail = when (wt) {
                    WT_VARINT -> "varint=" + r.varint()
                    WT_LEN -> {
                        val len = r.varint().toInt()
                        r.advance(len)
                        "len=$len"
                    }
                    WT_64BIT -> {
                        r.advance(8)
                        "fixed64"
                    }
                    WT_32BIT -> {
                        r.advance(4)
                        "fixed32"
                    }
                    else -> return Walk(out, "bad wire type $wt for field $field", start, true)
                }
                out.add("#$index off=$start field=$field wt=$wt $detail")
                index++
            } catch (e: Throwable) {
                return Walk(out, describe(e), start, true)
            }
        }
        return Walk(out, null, -1, false)
    }

    private fun hex(bytes: ByteArray, max: Int): String {
        val digits = "0123456789abcdef"
        val sb = StringBuilder()
        val n = minOf(max, bytes.size)
        for (i in 0 until n) {
            if (i > 0) sb.append(' ')
            val v = bytes[i].toInt() and 0xFF
            sb.append(digits[v ushr 4]).append(digits[v and 0xF])
        }
        if (bytes.size > max) sb.append(" ...")
        return sb.toString()
    }

    private fun describe(e: Throwable): String =
        (e.javaClass.simpleName ?: "Error") + ": " + (e.message ?: "(no message)")

    // --- reporting ------------------------------------------------------------

    /** Best-effort printable strings from a trace we could not decode as a tombstone. */
    fun printableStrings(bytes: ByteArray?, max: Int = 120): List<String> {
        if (bytes == null || bytes.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 32..126) {
                sb.append(c.toChar())
            } else {
                if (sb.length >= 4) out.add(sb.toString())
                sb.setLength(0)
                if (out.size >= max) break
            }
        }
        if (sb.length >= 4 && out.size < max) out.add(sb.toString())
        return out
    }

    /** Render a decoded tombstone as the plain-text body of a crash report. */
    fun format(t: Tombstone): String = buildString {
        append("-- tombstone (protobuf) --\n")
        append("arch=").append(t.archName()).append('\n')
        t.signal?.let { s ->
            append("signal=").append(s.number)
            s.name?.let { append(" (").append(it).append(')') }
            append(" code=").append(s.code)
            s.codeName?.let { append(" (").append(it).append(')') }
            append('\n')
            if (s.hasFaultAddress) {
                append("fault_address=0x").append(java.lang.Long.toHexString(s.faultAddress)).append('\n')
            }
        }
        append("pid=").append(t.pid).append(" tid=").append(t.tid).append(" uid=").append(t.uid).append('\n')
        if (t.processUptime > 0) append("process_uptime=").append(t.processUptime).append("s\n")
        t.timestamp?.let { append("timestamp=").append(it).append('\n') }
        t.buildFingerprint?.let { append("build_fingerprint=").append(it).append('\n') }
        t.selinuxLabel?.let { append("selinux_label=").append(it).append('\n') }
        if (t.commandLine.isNotEmpty()) {
            append("command_line=").append(t.commandLine.joinToString(" ")).append('\n')
        }
        t.abortMessage?.let {
            append("abort_message=").append(it).append('\n')
        }
        if (t.causes.isNotEmpty()) {
            append("causes=").append(t.causes.joinToString(" | ")).append('\n')
        }

        val crash = t.threadById(t.tid) ?: t.threads.firstOrNull()
        if (crash != null) {
            append("-- crashing thread ").append(crash.id)
            crash.name?.let { append(" \"").append(it).append('"') }
            append(" --\n")
            for ((i, f) in crash.backtrace.withIndex()) {
                append("  #").append(i.toString().padStart(2, '0')).append(' ')
                val lib = t.libraryForPc(f.pc)?.name
                if (lib != null) append(lib).append(' ')
                append("pc=0x").append(java.lang.Long.toHexString(f.pc))
                f.functionName?.let { append(" ").append(it) }
                f.fileName?.let { append(" (").append(it).append(')') }
                append('\n')
            }
            for (n in crash.notes) append("  note: ").append(n).append('\n')
        } else {
            append("-- no thread map in tombstone --\n")
        }

        val exec = t.mappings.filter { it.execute }
        if (exec.isNotEmpty()) {
            append("-- executable mappings --\n")
            for (m in exec) {
                append("  0x").append(java.lang.Long.toHexString(m.begin))
                    .append("-0x").append(java.lang.Long.toHexString(m.end))
                    .append(" ").append(m.name ?: "?")
                m.buildId?.let { append(" [build_id=").append(it).append(']') }
                append('\n')
            }
        }
    }
}
