package dev.matchscope.app.data

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class ImageResponseBodyTest {
    private class GuardedStream : InputStream() {
        var closed = false
        override fun read(): Int = error("Response body must not be read")
        override fun close() { closed = true }
    }

    @Test fun `rejections and redirects close immediately without downloading error pages`() {
        for (status in listOf(301, 403, 404, 429, 503)) {
            val body = GuardedStream()
            assertTrue(ImageResponseBody.read(status, "text/html", null, body).isEmpty())
            assertTrue(body.closed)
        }
    }

    @Test fun `successful documents and oversized responses are rejected before reading`() {
        for ((type, length) in listOf("text/html" to 200L, "application/json" to 20L, "image/jpeg" to ImageResponseBody.MAX_BYTES + 1L)) {
            val body = GuardedStream()
            assertThrows(IOException::class.java) { ImageResponseBody.read(200, type, length, body) }
            assertTrue(body.closed)
        }
    }

    @Test fun `mislabeled challenge and empty responses cannot become cached images`() {
        for (content in listOf("<!DOCTYPE html><html>Just a moment</html>", "\uFEFF <html>blocked</html>", ""))
            assertThrows(IOException::class.java) { ImageResponseBody.read(200, "image/jpeg", null, content.byteInputStream()) }
    }

    @Test fun `svg and image bytes retain their original content`() {
        val svg = "<?xml version='1.0'?><svg xmlns='http://www.w3.org/2000/svg'><path/></svg>".toByteArray()
        assertArrayEquals(svg, ImageResponseBody.read(200, "image/svg+xml; charset=utf-8", svg.size.toLong(), svg.inputStream()))
        val image = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01)
        assertArrayEquals(image, ImageResponseBody.read(200, null, null, image.inputStream()))
    }

    @Test fun `streaming size limit works without a content length`() {
        val body = ByteArrayInputStream(ByteArray(ImageResponseBody.MAX_BYTES + 1))
        assertThrows(IOException::class.java) { ImageResponseBody.read(200, "image/png", null, body) }
    }

    @Test fun `cancelled or expired request closes the body before another read`() {
        val body = GuardedStream()
        assertThrows(IOException::class.java) { ImageResponseBody.read(200, "image/webp", null, body) { throw IOException("cancelled") } }
        assertTrue(body.closed)
    }
}
