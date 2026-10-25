package dev.matchscope.app.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** Reject error documents before reading them, and close every response, including failures. */
internal object ImageResponseBody {
    const val MAX_BYTES = 8 * 1024 * 1024

    fun read(status: Int, contentType: String?, contentLength: Long?, body: InputStream?,
             beforeRead: () -> Unit = {}): ByteArray {
        return body.use { input ->
            if (status !in 200..299) return@use byteArrayOf()
            val type = contentType?.substringBefore(';')?.trim()?.lowercase()
            if (type != null && !type.startsWith("image/") && type != "application/octet-stream")
                throw IOException("Image source returned a document")
            if (contentLength != null && contentLength > MAX_BYTES) throw IOException("Image exceeds size limit")
            if (input == null) throw IOException("Empty image response")
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                beforeRead()
                val size = input.read(buffer)
                if (size < 0) break
                if (output.size() + size > MAX_BYTES) throw IOException("Image exceeds size limit")
                output.write(buffer, 0, size)
            }
            val bytes = output.toByteArray()
            if (bytes.isEmpty()) throw IOException("Empty image response")
            // A challenge may be mislabeled as an image or have no Content-Type.
            // SVG/XML remains supported; actual format decoding is left to Coil.
            val prefix = bytes.take(1024).toByteArray().toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\r', '\n', '\t').lowercase()
            if (prefix.startsWith("<!doctype html") || prefix.startsWith("<html") || prefix.startsWith("<head"))
                throw IOException("Image source returned a document")
            bytes
        }
    }
}
