package com.swordfish.lemuroid.app.shared.telemetry

/**
 * Minimal decoder for the debuggerd `Tombstone` protobuf.
 *
 * On Android 12+ [android.app.ApplicationExitInfo.getTraceInputStream] returns **binary protobuf**
 * for `REASON_CRASH_NATIVE`, not text. Reading it as UTF-8 produces 100–300 KB of garbage per
 * event — which is exactly what happened to the sibling ARMSX2 app, where 52 native crashes arrived
 * unreadable. So the blob is decoded here into the same text debuggerd would have written, and if
 * decoding fails a short summary is sent instead — the raw bytes are never reported.
 *
 * Only the handful of fields worth reading are decoded; every other field is skipped generically by
 * wire type. Field numbers follow `system/core/debuggerd/proto/tombstone.proto` (AOSP).
 *
 * Any malformed input makes the parse return null rather than throw.
 */
object TombstoneParser {
    // Tombstone (root)
    private const val F_BUILD_FINGERPRINT = 2
    private const val F_TIMESTAMP = 4
    private const val F_PID = 5
    private const val F_TID = 6
    private const val F_SIGNAL_INFO = 10
    private const val F_ABORT_MESSAGE = 14
    private const val F_CAUSES = 15
    private const val F_THREADS = 16

    // Signal
    private const val F_SIG_NUMBER = 1
    private const val F_SIG_NAME = 2
    private const val F_SIG_CODE_NAME = 4
    private const val F_SIG_HAS_FAULT_ADDR = 8
    private const val F_SIG_FAULT_ADDR = 9

    // Cause
    private const val F_CAUSE_HUMAN_READABLE = 1

    // Thread
    private const val F_THREAD_ID = 1
    private const val F_THREAD_NAME = 2
    private const val F_THREAD_BACKTRACE = 4

    // BacktraceFrame
    private const val F_FRAME_REL_PC = 1
    private const val F_FRAME_FUNCTION_NAME = 4
    private const val F_FRAME_FUNCTION_OFFSET = 5
    private const val F_FRAME_FILE_NAME = 6

    private const val MAX_FRAMES = 64

    private class Frame(
        val relPc: Long,
        val fileName: String,
        val functionName: String,
        val functionOffset: Long,
    ) {
        override fun toString(): String {
            val where = fileName.ifBlank { "<unknown>" }
            val sym = if (functionName.isNotBlank()) " ($functionName+$functionOffset)" else ""
            return "  pc %016x  %s%s".format(relPc, where, sym)
        }
    }

    private class ThreadInfo(val id: Int, val name: String, val frames: List<Frame>)

    /** Decoded tombstone: [text] for the report log, [culpritLibrary]/[culpritSymbol] for triage. */
    class Result(
        val text: String,
        val signalSummary: String,
        val culpritLibrary: String?,
        val culpritSymbol: String?,
    )

    fun parse(bytes: ByteArray): Result? {
        return try {
            val reader = Reader(bytes, 0, bytes.size)
            var fingerprint = ""
            var timestamp = ""
            var pid = 0L
            var tid = 0L
            var abortMessage = ""
            var signalText = ""
            val causes = mutableListOf<String>()
            val threads = mutableListOf<ThreadInfo>()

            while (!reader.isAtEnd()) {
                val tag = reader.readTag() ?: return null
                when {
                    tag.field == F_BUILD_FINGERPRINT && tag.wire == 2 -> fingerprint = reader.readString() ?: return null
                    tag.field == F_TIMESTAMP && tag.wire == 2 -> timestamp = reader.readString() ?: return null
                    tag.field == F_PID && tag.wire == 0 -> pid = reader.readVarint() ?: return null
                    tag.field == F_TID && tag.wire == 0 -> tid = reader.readVarint() ?: return null
                    tag.field == F_ABORT_MESSAGE && tag.wire == 2 -> abortMessage = reader.readString() ?: return null
                    tag.field == F_SIGNAL_INFO && tag.wire == 2 ->
                        signalText = reader.readMessage { parseSignal(it) } ?: return null
                    tag.field == F_CAUSES && tag.wire == 2 ->
                        reader.readMessage { parseCause(it) }?.let { if (it.isNotBlank()) causes.add(it) } ?: return null
                    tag.field == F_THREADS && tag.wire == 2 ->
                        reader.readMessage { parseThreadMapEntry(it) }?.let { threads.add(it) } ?: return null
                    else -> if (!reader.skip(tag.wire)) return null
                }
            }

            // The thread that crashed is the one whose id matches the tombstone's tid.
            val crashed = threads.firstOrNull { it.id.toLong() == tid } ?: threads.firstOrNull()

            val text =
                buildString {
                    appendLine("*** Native crash (decoded from Tombstone protobuf) ***")
                    if (timestamp.isNotBlank()) appendLine("Timestamp: $timestamp")
                    if (fingerprint.isNotBlank()) appendLine("Build fingerprint: $fingerprint")
                    appendLine("pid: $pid, tid: $tid, name: ${crashed?.name ?: "?"}")
                    if (signalText.isNotBlank()) appendLine(signalText)
                    if (abortMessage.isNotBlank()) appendLine("Abort message: $abortMessage")
                    causes.forEach { appendLine("Cause: $it") }
                    if (crashed != null && crashed.frames.isNotEmpty()) {
                        appendLine("backtrace:")
                        crashed.frames.forEachIndexed { i, f -> appendLine("  #%02d%s".format(i, f.toString())) }
                    }
                }

            // Blame the first frame that belongs to the app rather than to the platform — that is
            // the libretro core, which is what triage actually needs to see.
            val appFrame =
                crashed?.frames?.firstOrNull { isAppLibrary(it.fileName) }
                    ?: crashed?.frames?.firstOrNull()

            Result(
                text = text,
                signalSummary = signalText.ifBlank { "Native crash" },
                culpritLibrary = appFrame?.fileName?.substringAfterLast('/')?.takeIf { it.isNotBlank() },
                culpritSymbol = appFrame?.functionName?.takeIf { it.isNotBlank() },
            )
        } catch (e: Throwable) {
            null
        }
    }

    private fun isAppLibrary(path: String): Boolean =
        path.contains("_libretro_") ||
            path.contains("liblibretrodroid") ||
            (path.contains("/data/app/") && path.endsWith(".so"))

    private fun parseSignal(r: Reader): String {
        var number = 0L
        var name = ""
        var codeName = ""
        var hasFaultAddress = false
        var faultAddress = 0L
        while (!r.isAtEnd()) {
            val tag = r.readTag() ?: break
            when {
                tag.field == F_SIG_NUMBER && tag.wire == 0 -> number = r.readVarint() ?: break
                tag.field == F_SIG_NAME && tag.wire == 2 -> name = r.readString() ?: break
                tag.field == F_SIG_CODE_NAME && tag.wire == 2 -> codeName = r.readString() ?: break
                tag.field == F_SIG_HAS_FAULT_ADDR && tag.wire == 0 -> hasFaultAddress = (r.readVarint() ?: 0L) != 0L
                tag.field == F_SIG_FAULT_ADDR && tag.wire == 0 -> faultAddress = r.readVarint() ?: break
                else -> if (!r.skip(tag.wire)) break
            }
        }
        return buildString {
            append("signal ").append(number)
            if (name.isNotBlank()) append(" (").append(name).append(")")
            if (codeName.isNotBlank()) append(", code ").append(codeName)
            if (hasFaultAddress) append(", fault addr 0x%x".format(faultAddress))
        }
    }

    private fun parseCause(r: Reader): String {
        var human = ""
        while (!r.isAtEnd()) {
            val tag = r.readTag() ?: break
            when {
                tag.field == F_CAUSE_HUMAN_READABLE && tag.wire == 2 -> human = r.readString() ?: break
                else -> if (!r.skip(tag.wire)) break
            }
        }
        return human
    }

    /** `threads` is a proto map: each entry is a message with key=1 and value=2 (the Thread). */
    private fun parseThreadMapEntry(r: Reader): ThreadInfo? {
        var result: ThreadInfo? = null
        while (!r.isAtEnd()) {
            val tag = r.readTag() ?: break
            when {
                tag.field == 2 && tag.wire == 2 -> result = r.readMessage { parseThread(it) }
                else -> if (!r.skip(tag.wire)) break
            }
        }
        return result
    }

    private fun parseThread(r: Reader): ThreadInfo {
        var id = 0L
        var name = ""
        val frames = mutableListOf<Frame>()
        while (!r.isAtEnd()) {
            val tag = r.readTag() ?: break
            when {
                tag.field == F_THREAD_ID && tag.wire == 0 -> id = r.readVarint() ?: break
                tag.field == F_THREAD_NAME && tag.wire == 2 -> name = r.readString() ?: break
                tag.field == F_THREAD_BACKTRACE && tag.wire == 2 -> {
                    val frame = r.readMessage { parseFrame(it) } ?: break
                    if (frames.size < MAX_FRAMES) frames.add(frame)
                }
                else -> if (!r.skip(tag.wire)) break
            }
        }
        return ThreadInfo(id.toInt(), name, frames)
    }

    private fun parseFrame(r: Reader): Frame {
        var relPc = 0L
        var fileName = ""
        var functionName = ""
        var functionOffset = 0L
        while (!r.isAtEnd()) {
            val tag = r.readTag() ?: break
            when {
                tag.field == F_FRAME_REL_PC && tag.wire == 0 -> relPc = r.readVarint() ?: break
                tag.field == F_FRAME_FUNCTION_NAME && tag.wire == 2 -> functionName = r.readString() ?: break
                tag.field == F_FRAME_FUNCTION_OFFSET && tag.wire == 0 -> functionOffset = r.readVarint() ?: break
                tag.field == F_FRAME_FILE_NAME && tag.wire == 2 -> fileName = r.readString() ?: break
                else -> if (!r.skip(tag.wire)) break
            }
        }
        return Frame(relPc, fileName, functionName, functionOffset)
    }

    private class Tag(val field: Int, val wire: Int)

    /** Bounds-checked protobuf wire-format reader. Returns null instead of throwing on bad input. */
    private class Reader(private val buf: ByteArray, private var pos: Int, private val end: Int) {
        fun isAtEnd(): Boolean = pos >= end

        fun readVarint(): Long? {
            var result = 0L
            var shift = 0
            while (shift < 64) {
                if (pos >= end) return null
                val b = buf[pos++].toInt()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
            return null
        }

        fun readTag(): Tag? {
            val v = readVarint() ?: return null
            val field = (v ushr 3).toInt()
            val wire = (v and 7L).toInt()
            if (field <= 0) return null
            return Tag(field, wire)
        }

        fun readString(): String? {
            val len = readVarint()?.toInt() ?: return null
            if (len < 0 || pos + len > end) return null
            val s = String(buf, pos, len, Charsets.UTF_8)
            pos += len
            return s
        }

        fun <T> readMessage(block: (Reader) -> T): T? {
            val len = readVarint()?.toInt() ?: return null
            if (len < 0 || pos + len > end) return null
            val sub = Reader(buf, pos, pos + len)
            pos += len
            return block(sub)
        }

        /** Skips an unknown field's payload. Returns false when the input is malformed. */
        fun skip(wire: Int): Boolean {
            return when (wire) {
                0 -> readVarint() != null
                1 -> advance(8)
                2 -> {
                    val len = readVarint()?.toInt() ?: return false
                    if (len < 0) false else advance(len)
                }
                5 -> advance(4)
                else -> false
            }
        }

        private fun advance(n: Int): Boolean {
            if (pos + n > end) return false
            pos += n
            return true
        }
    }
}
