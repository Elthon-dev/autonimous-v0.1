package com.elthon.broexe.util

import java.io.File
import java.security.MessageDigest

/**
 * Streamed SHA-256 of a file, returned as lowercase hex.
 *
 * Streams a 64 KiB buffer at a time so a ~470 MB GGUF never lands on the Java heap.
 */
fun sha256Of(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(1 shl 16)
        var n: Int
        while (input.read(buf).also { n = it } != -1) md.update(buf, 0, n)
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}
