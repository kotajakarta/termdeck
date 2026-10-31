package id.termdeck.app.ssh

import id.termdeck.app.data.DirEntry
import id.termdeck.app.data.DirListing
import id.termdeck.app.data.ServerStats
import java.security.SecureRandom
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

const val IDLE_CLOSE_SECONDS = 90L
const val CONNECT_TIMEOUT_MS = 15_000
const val PASTE_DIR = ".cache/termdeck/paste"
const val MAX_PASTE_BYTES = 20 * 1024 * 1024
const val MAX_TEXT_FILE_BYTES = 2 * 1024 * 1024
const val MAX_UPLOAD_FILE_BYTES = 50 * 1024 * 1024
const val PASTE_KEEP_SECONDS = 10 * 60L

val STATS_SCRIPT = """
echo '@@hostname'; hostname 2>/dev/null || cat /etc/hostname 2>/dev/null
echo '@@os'; ( . /etc/os-release 2>/dev/null && echo "${'$'}PRETTY_NAME" ) || uname -s
echo '@@kernel'; uname -sr 2>/dev/null
echo '@@uptime'; cat /proc/uptime 2>/dev/null
echo '@@load'; cat /proc/loadavg 2>/dev/null
echo '@@cpu'; nproc 2>/dev/null || getconf _NPROCESSORS_ONLN 2>/dev/null
echo '@@mem'; head -5 /proc/meminfo 2>/dev/null
echo '@@disk'; df -Pk / 2>/dev/null | tail -1
""".trimIndent() + "\n"

private val SAFE_SHELL_CHARS = Regex("""^[A-Za-z0-9_@%+=:,./-]+$""")
private val STAMP_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
private val RANDOM = SecureRandom()

class HostKeyMismatch(val expectedFp: String, val actualFp: String) : Exception(
    "Host key server BERUBAH! Tersimpan: $expectedFp, diterima: $actualFp. " +
        "Kemungkinan server diinstal ulang atau ada serangan man-in-the-middle. " +
        "Reset host key di pengaturan server jika Anda yakin aman."
)

class ServerNotFound(message: String) : Exception(message)

class RemotePathError(message: String) : Exception(message)

fun shlexQuote(s: String): String {
    if (s.isEmpty()) return "''"
    if (SAFE_SHELL_CHARS.matches(s)) return s
    return "'" + s.replace("'", "'\"'\"'") + "'"
}

fun quoteRemotePath(path: String): String {
    if (path.isEmpty() || path == "~") return "~"
    if (path.startsWith("~/")) return "~/" + shlexQuote(path.substring(2))
    return shlexQuote(path)
}

fun buildCdCommand(path: String, startup: String?): String {
    var command = "cd -- ${quoteRemotePath(path)} && clear"
    if (!startup.isNullOrBlank()) {
        command += " && ${startup.trim()}"
    }
    return command
}

fun sniffImageType(data: ByteArray): String? {
    if (data.size >= 8 &&
        data[0] == 0x89.toByte() &&
        data[1] == 'P'.code.toByte() &&
        data[2] == 'N'.code.toByte() &&
        data[3] == 'G'.code.toByte() &&
        data[4] == 0x0D.toByte() &&
        data[5] == 0x0A.toByte() &&
        data[6] == 0x1A.toByte() &&
        data[7] == 0x0A.toByte()
    ) {
        return "png"
    }
    if (data.size >= 3 &&
        data[0] == 0xFF.toByte() &&
        data[1] == 0xD8.toByte() &&
        data[2] == 0xFF.toByte()
    ) {
        return "jpg"
    }
    if (data.size >= 6) {
        val head = String(data, 0, 6, Charsets.US_ASCII)
        if (head == "GIF87a" || head == "GIF89a") {
            return "gif"
        }
    }
    if (data.size >= 12) {
        val riff = String(data, 0, 4, Charsets.US_ASCII)
        val webp = String(data, 8, 4, Charsets.US_ASCII)
        if (riff == "RIFF" && webp == "WEBP") {
            return "webp"
        }
    }
    return null
}

fun pasteImageName(ext: String, now: LocalDateTime = LocalDateTime.now()): String {
    val stamp = now.format(STAMP_FMT)
    val rand = ByteArray(3)
    RANDOM.nextBytes(rand)
    val hex = rand.joinToString("") { "%02x".format(it) }
    return "paste-$stamp-$hex.$ext"
}

fun parseStats(text: String): ServerStats {
    val sections = mutableMapOf<String, MutableList<String>>()
    var current: MutableList<String>? = null
    for (rawLine in text.lines()) {
        if (rawLine.startsWith("@@")) {
            val key = rawLine.substring(2).trim()
            current = sections.getOrPut(key) { mutableListOf() }
        } else if (current != null && rawLine.isNotBlank()) {
            current.add(rawLine.trim())
        }
    }

    fun first(name: String): String = sections[name]?.firstOrNull() ?: ""

    val uptimeParts = first("uptime").split(Regex("""\s+""")).filter { it.isNotEmpty() }
    val uptimeSeconds = uptimeParts.firstOrNull()?.toDoubleOrNull()

    val loadParts = first("load").split(Regex("""\s+""")).filter { it.isNotEmpty() }.take(3)
    val load = if (loadParts.isNotEmpty() && loadParts.all { it.toDoubleOrNull() != null }) {
        loadParts.map { it.toDouble() }
    } else {
        emptyList()
    }

    val cpuCount = first("cpu").toIntOrNull()

    val memInfo = mutableMapOf<String, Long>()
    for (line in sections["mem"].orEmpty()) {
        val idx = line.indexOf(':')
        if (idx > 0) {
            val key = line.substring(0, idx).trim()
            val rest = line.substring(idx + 1).trim().split(Regex("""\s+"""))
            val num = rest.firstOrNull()?.toLongOrNull()
            if (num != null) {
                memInfo[key] = num * 1024L
            }
        }
    }

    val memTotal = memInfo["MemTotal"]
    val memAvailable = memInfo["MemAvailable"] ?: memInfo["MemFree"]
    val memUsed = if (memTotal != null && memAvailable != null) memTotal - memAvailable else null

    val diskParts = first("disk").split(Regex("""\s+""")).filter { it.isNotEmpty() }
    var diskTotal: Long? = null
    var diskUsed: Long? = null
    if (diskParts.size >= 3) {
        val total = diskParts[1].toLongOrNull()
        val used = diskParts[2].toLongOrNull()
        if (total != null && used != null) {
            diskTotal = total * 1024L
            diskUsed = used * 1024L
        }
    }

    return ServerStats(
        hostname = first("hostname"),
        os = first("os"),
        kernel = first("kernel"),
        uptimeSeconds = uptimeSeconds,
        load = load,
        cpuCount = cpuCount,
        memTotal = memTotal,
        memUsed = memUsed,
        diskTotal = diskTotal,
        diskUsed = diskUsed
    )
}

fun posixJoin(base: String, name: String): String {
    if (base.isEmpty() || base == "/") return "/${name.trimStart('/')}"
    return "${base.trimEnd('/')}/${name.trimStart('/')}"
}

fun parseShellLs(stdout: String): DirListing {
    val lines = stdout.lines().filter { it.isNotEmpty() }
    if (lines.isEmpty()) {
        throw RemotePathError("folder tidak bisa dibaca")
    }
    val target = lines.first()
    val entries = lines.drop(1)
        .filter { it.isNotEmpty() && it != "./" && it != "../" }
        .map { line ->
            val isDir = line.endsWith("/")
            val name = line.trimEnd('/')
            DirEntry(
                name = name,
                path = posixJoin(target, name),
                isDir = isDir
            )
        }
        .sortedWith(compareBy<DirEntry> { !it.isDir }.thenBy { it.name.lowercase() })

    return DirListing(path = target, entries = entries)
}

fun formatOctalPerm(permissionsMask: Int): String {
    val perm = permissionsMask and 0b111_111_111
    return "0" + perm.toString(8).padStart(3, '0')
}

fun parseOctalMode(mode: String): Int {
    val trimmed = mode.trim()
    require(Regex("""^[0-7]{3,4}$""").matches(trimmed)) { "Mode izin harus angka oktal 3-4 digit (contoh: 0755 atau 644)." }
    return trimmed.toInt(8)
}
