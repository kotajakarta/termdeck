package id.termdeck.app

import io.ktor.http.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadHelperTest {

    @Test
    fun testContentDispositionRfc5987() {
        val cd = "attachment; filename*=UTF-8''my%20document%202026.pdf"
        val name = resolveDownloadFileName("http://127.0.0.1:8765/api/files/1/download", cd, "application/octet-stream")
        assertEquals("my document 2026.pdf", name)
    }

    @Test
    fun testContentDispositionStandardQuoted() {
        val cd = "attachment; filename=\"report.xlsx\""
        val name = resolveDownloadFileName("http://127.0.0.1:8765/api/files/1/download", cd, "application/octet-stream")
        assertEquals("report.xlsx", name)
    }

    @Test
    fun testContentDispositionStandardUnquoted() {
        val cd = "attachment; filename=script.sh"
        val name = resolveDownloadFileName("http://127.0.0.1:8765/api/files/1/download", cd, "application/octet-stream")
        assertEquals("script.sh", name)
    }

    @Test
    fun testContentDispositionBothPrefersRfc5987() {
        val cd = "attachment; filename=\"fallback.pdf\"; filename*=UTF-8''actual%20name.pdf"
        val name = resolveDownloadFileName("http://127.0.0.1:8765/api/files/1/download", cd, "application/octet-stream")
        assertEquals("actual name.pdf", name)
    }

    @Test
    fun testUrlQueryParamPath() {
        val url = "http://127.0.0.1:8765/api/files/1/download?path=%2Fvar%2Flog%2Fnginx%2Faccess.log&token=abc"
        val name = resolveDownloadFileName(url, null, "application/octet-stream")
        assertEquals("access.log", name)
    }

    @Test
    fun testPreservesMultiDotExtensions() {
        val url = "http://127.0.0.1:8765/api/files/1/download?path=/home/ubuntu/backup.tar.gz"
        val name = resolveDownloadFileName(url, null, "application/octet-stream")
        assertEquals("backup.tar.gz", name)
    }

    @Test
    fun testFileWithoutExtensionPreservedWhenOctetStream() {
        val url = "http://127.0.0.1:8765/api/files/1/download?path=/etc/hosts"
        val name = resolveDownloadFileName(url, null, "application/octet-stream")
        assertEquals("hosts", name)
    }

    @Test
    fun testFileWithoutExtensionInfersFromKnownMimeType() {
        val url = "http://127.0.0.1:8765/api/files/1/download?path=/tmp/imagefile"
        val name = resolveDownloadFileName(url, null, "image/png")
        assertEquals("imagefile.png", name)
    }

    @Test
    fun testSanitizeRemovesIllegalCharacters() {
        assertEquals("safe_file_name.txt", sanitizeFileName("safe/file\\name.txt"))
        assertEquals("weird_name.conf", sanitizeFileName("weird:?*\"<>|name.conf"))
    }

    @Test
    fun testResolveDownloadMimeType() {
        assertEquals("application/pdf", resolveDownloadMimeType("doc.pdf", "application/octet-stream"))
        assertEquals("application/vnd.android.package-archive", resolveDownloadMimeType("termdeck.apk", null))
        assertEquals("image/png", resolveDownloadMimeType("photo.png", "application/octet-stream"))
        assertEquals("application/json", resolveDownloadMimeType("package.json", "application/octet-stream"))
        assertEquals("text/plain", resolveDownloadMimeType("syslog.log", "application/octet-stream"))
        assertEquals("application/x-sh", resolveDownloadMimeType("deploy.sh", "application/octet-stream"))
        assertEquals("application/gzip", resolveDownloadMimeType("archive.tar.gz", "application/octet-stream"))
        assertEquals("application/zip", resolveDownloadMimeType("data.zip", "application/octet-stream"))
    }

    @Test
    fun testContentTypeForFilename() {
        assertEquals(ContentType.Application.Pdf, contentTypeForFilename("test.pdf"))
        assertEquals(ContentType.Image.PNG, contentTypeForFilename("icon.png"))
        assertEquals(ContentType("text", "x-python"), contentTypeForFilename("app.py"))
        assertEquals(ContentType.Text.Plain, contentTypeForFilename("notes.txt"))
        assertEquals(ContentType("application", "zip"), contentTypeForFilename("archive.zip"))
        assertEquals(ContentType("application", "gzip"), contentTypeForFilename("archive.tar.gz"))
        assertEquals(ContentType("application", "vnd.android.package-archive"), contentTypeForFilename("app.apk"))
    }

    @Test
    fun testBuildContentDispositionHeader() {
        val header = buildContentDispositionHeader("my file (1).tar.gz")
        assertTrue(header.contains("filename=\"my file (1).tar.gz\""))
        assertTrue(header.contains("filename*=UTF-8''my%20file%20%281%29.tar.gz"))
    }

    @Test
    fun testFormatFileSize() {
        assertEquals("0 B", formatFileSize(-1))
        assertEquals("0 B", formatFileSize(0))
        assertEquals("512 B", formatFileSize(512))
        assertEquals("1.0 KB", formatFileSize(1024))
        assertEquals("2.5 KB", formatFileSize(2560))
        assertEquals("1.0 MB", formatFileSize(1024 * 1024))
        assertEquals("15.5 MB", formatFileSize((15.5 * 1024 * 1024).toLong()))
        assertEquals("1.0 GB", formatFileSize(1024L * 1024L * 1024L))
    }

    @Test
    fun testCopyStreamWithVerificationSuccess() {
        val sample = "Hello World! This is a test file for TermDeck download stream.".toByteArray(Charsets.UTF_8)
        val input = java.io.ByteArrayInputStream(sample)
        val output = java.io.ByteArrayOutputStream()
        val progressUpdates = mutableListOf<Long>()

        val total = copyStreamWithVerification(
            inputStream = input,
            outputStream = output,
            expectedBytes = sample.size.toLong(),
            onProgress = { read, _ -> progressUpdates.add(read) }
        )

        assertEquals(sample.size.toLong(), total)
        assertEquals(sample.size, output.toByteArray().size)
        assertTrue(progressUpdates.isNotEmpty())
        assertEquals(sample.size.toLong(), progressUpdates.last())
    }

    @Test(expected = java.io.IOException::class)
    fun testCopyStreamWithVerificationThrowsOnTruncatedStream() {
        val sample = "Partial content".toByteArray(Charsets.UTF_8)
        val input = java.io.ByteArrayInputStream(sample)
        val output = java.io.ByteArrayOutputStream()

        copyStreamWithVerification(
            inputStream = input,
            outputStream = output,
            expectedBytes = 1000L // Expected 1000 bytes, but only 15 provided
        )
    }

    @Test
    fun testCopyStreamWithVerificationHandlesZeroByteChunks() {
        // Stream that returns 0 bytes twice before delivering actual data
        val data = "Complete stream content".toByteArray(Charsets.UTF_8)
        var zeroCount = 2
        var dataRead = false

        val stream = object : java.io.InputStream() {
            override fun read(): Int = throw UnsupportedOperationException()

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (zeroCount > 0) {
                    zeroCount--
                    return 0 // Simulating temporary 0-byte packet from SSH stream
                }
                if (!dataRead) {
                    dataRead = true
                    System.arraycopy(data, 0, b, off, data.size)
                    return data.size
                }
                return -1 // EOF
            }
        }

        val output = java.io.ByteArrayOutputStream()
        val total = copyStreamWithVerification(
            inputStream = stream,
            outputStream = output,
            expectedBytes = data.size.toLong()
        )

        assertEquals(data.size.toLong(), total)
        assertEquals(data.size, output.toByteArray().size)
    }

    @Test
    fun testCleanBase64Data() {
        val pure = "SGVsbG8gV29ybGQ="
        assertEquals(pure, cleanBase64Data(pure))
        assertEquals(pure, cleanBase64Data("  $pure \n"))
        assertEquals(pure, cleanBase64Data("data:application/json;base64,$pure"))
        assertEquals(pure, cleanBase64Data("data:text/plain;charset=utf-8;base64,$pure"))
    }

    @Test
    fun testDecodeBase64Bytes() {
        val original = "Hello TermDeck Export!"
        val base64 = java.util.Base64.getEncoder().encodeToString(original.toByteArray(Charsets.UTF_8))
        val decodedBytes = decodeBase64Bytes(base64)
        assertEquals(original, String(decodedBytes, Charsets.UTF_8))

        // Also handles data URL prefix
        val withPrefix = "data:application/octet-stream;base64,$base64"
        assertEquals(original, String(decodeBase64Bytes(withPrefix), Charsets.UTF_8))
    }

    @Test
    fun testParseDataUriBase64() {
        val original = "Sample configuration content"
        val b64 = java.util.Base64.getEncoder().encodeToString(original.toByteArray(Charsets.UTF_8))
        val uri = "data:application/json;base64,$b64"

        val parsed = parseDataUri(uri)
        org.junit.Assert.assertNotNull(parsed)
        assertEquals("application/json", parsed!!.second)
        assertEquals(original, String(parsed.first, Charsets.UTF_8))
    }

    @Test
    fun testParseDataUriUrlEncoded() {
        val uri = "data:text/plain;charset=utf-8,Hello%20World%21"
        val parsed = parseDataUri(uri)
        org.junit.Assert.assertNotNull(parsed)
        org.junit.Assert.assertTrue(parsed!!.second.startsWith("text/plain"))
        assertEquals("Hello World!", String(parsed.first, Charsets.UTF_8))
    }

    @Test(expected = IllegalArgumentException::class)
    fun testDownloadFileDirectlyRejectsBlobScheme() {
        downloadFileDirectly(
            context = object : android.content.ContextWrapper(null) {},
            url = "blob:http://127.0.0.1:8765/1234-5678"
        )
    }
}

