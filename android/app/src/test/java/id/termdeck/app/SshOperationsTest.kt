package id.termdeck.app

import id.termdeck.app.ssh.HostKeyMismatch
import id.termdeck.app.ssh.buildCdCommand
import id.termdeck.app.ssh.formatOctalPerm
import id.termdeck.app.ssh.parseOctalMode
import id.termdeck.app.ssh.parseShellLs
import id.termdeck.app.ssh.parseStats
import id.termdeck.app.ssh.pasteImageName
import id.termdeck.app.ssh.quoteRemotePath
import id.termdeck.app.ssh.sniffImageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class SshOperationsTest {

    @Test
    fun `buildCdCommand and quoteRemotePath match backend quoting`() {
        assertEquals("cd -- '/var/www/my app' && clear", buildCdCommand("/var/www/my app", null))
        assertEquals("cd -- '/srv/it'\"'\"'s' && clear", buildCdCommand("/srv/it's", ""))
        assertEquals("cd -- ~ && clear", buildCdCommand("~", null))
        assertEquals("cd -- ~/'proj a' && clear && git status", buildCdCommand("~/proj a", "git status"))
        assertEquals("/var/www/app-1.2_x", quoteRemotePath("/var/www/app-1.2_x"))
        assertEquals("~", quoteRemotePath(""))
        assertEquals("'\$HOME/x'", quoteRemotePath("\$HOME/x"))
    }

    @Test
    fun `parseStats extracts hostname os kernel uptime load cpu mem and disk`() {
        val text = listOf(
            "@@hostname",
            "web-1",
            "@@os",
            "Ubuntu 24.04.1 LTS",
            "@@kernel",
            "Linux 6.8.0-45-generic",
            "@@uptime",
            "12345.67 23456.78",
            "@@load",
            "0.15 0.20 0.30 1/234 5678",
            "@@cpu",
            "4",
            "@@mem",
            "MemTotal:        8000000 kB",
            "MemFree:         1000000 kB",
            "MemAvailable:    6000000 kB",
            "@@disk",
            "/dev/sda1 100000 40000 60000 40% /"
        ).joinToString("\n")

        val stats = parseStats(text)
        assertEquals("web-1", stats.hostname)
        assertEquals("Ubuntu 24.04.1 LTS", stats.os)
        assertEquals("Linux 6.8.0-45-generic", stats.kernel)
        assertEquals(12345.67, stats.uptimeSeconds!!, 0.001)
        assertEquals(listOf(0.15, 0.20, 0.30), stats.load)
        assertEquals(4, stats.cpuCount)
        assertEquals(8000000L * 1024L, stats.memTotal)
        assertEquals(2000000L * 1024L, stats.memUsed)
        assertEquals(100000L * 1024L, stats.diskTotal)
        assertEquals(40000L * 1024L, stats.diskUsed)
    }

    @Test
    fun `parseStats tolerates missing sections`() {
        val stats = parseStats("@@hostname\nbox\n@@load\n\n@@cpu\nnot-a-number\n")
        assertEquals("box", stats.hostname)
        assertEquals(emptyList<Double>(), stats.load)
        assertNull(stats.cpuCount)
        assertNull(stats.memTotal)
    }

    @Test
    fun `sniffImageType detects PNG JPG GIF WebP and rejects others`() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(16)
        val jpg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        val gif = "GIF89a....".toByteArray()
        val webp = "RIFF\u0010\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1)

        assertEquals("png", sniffImageType(png))
        assertEquals("jpg", sniffImageType(jpg))
        assertEquals("gif", sniffImageType(gif))
        assertEquals("webp", sniffImageType(webp))
        assertNull(sniffImageType("<svg xmlns=...>".toByteArray()))
        assertNull(sniffImageType(ByteArray(0)))
    }

    @Test
    fun `pasteImageName formats timestamp and random suffix`() {
        val name = pasteImageName("png", LocalDateTime.of(2026, 10, 19, 8, 5, 3))
        assertTrue(name.startsWith("paste-20261019-080503-") && name.endsWith(".png"))
        assertNotEquals(pasteImageName("png"), pasteImageName("png"))
    }

    @Test
    fun `parseShellLs parses pwd and ls entries sorted directories first`() {
        val stdout = "/home/alice/my proj\nsrc/\n.env\nlink/\n"
        val listing = parseShellLs(stdout)
        assertEquals("/home/alice/my proj", listing.path)
        assertEquals(
            listOf("link" to true, "src" to true, ".env" to false),
            listing.entries.map { it.name to it.isDir }
        )
    }

    @Test
    fun `HostKeyMismatch formats Indonesian message with fingerprints`() {
        val err = HostKeyMismatch("SHA256:aaa", "SHA256:bbb")
        assertTrue(err.message!!.contains("Host key server BERUBAH!"))
        assertTrue(err.message!!.contains("SHA256:aaa"))
        assertTrue(err.message!!.contains("SHA256:bbb"))
    }

    @Test
    fun `formatOctalPerm and parseOctalMode convert permissions accurately`() {
        assertEquals("0755", formatOctalPerm(0b111_101_101))
        assertEquals("0644", formatOctalPerm(0b110_100_100))
        assertEquals(0b111_101_101, parseOctalMode("755"))
        assertEquals(0b110_000_000, parseOctalMode("0600"))
    }
}
