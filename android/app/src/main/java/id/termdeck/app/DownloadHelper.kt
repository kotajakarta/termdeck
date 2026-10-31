package id.termdeck.app

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import io.ktor.http.ContentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

private val EXTENSION_TO_MIME: Map<String, String> = mapOf(
    // Text & Configuration
    "txt" to "text/plain",
    "log" to "text/plain",
    "conf" to "text/plain",
    "cfg" to "text/plain",
    "ini" to "text/plain",
    "env" to "text/plain",
    "service" to "text/plain",
    "timer" to "text/plain",
    "socket" to "text/plain",
    "csv" to "text/csv",
    "tsv" to "text/tab-separated-values",
    "md" to "text/markdown",
    "markdown" to "text/markdown",
    "html" to "text/html",
    "htm" to "text/html",
    "css" to "text/css",
    "xml" to "application/xml",
    "json" to "application/json",
    "yaml" to "text/yaml",
    "yml" to "text/yaml",
    "toml" to "application/toml",
    "sql" to "application/sql",

    // Code & Scripts
    "sh" to "application/x-sh",
    "bash" to "application/x-sh",
    "zsh" to "application/x-sh",
    "py" to "text/x-python",
    "js" to "application/javascript",
    "mjs" to "application/javascript",
    "ts" to "application/typescript",
    "tsx" to "text/plain",
    "jsx" to "text/plain",
    "php" to "application/x-httpd-php",
    "c" to "text/plain",
    "h" to "text/plain",
    "cpp" to "text/plain",
    "hpp" to "text/plain",
    "rs" to "text/plain",
    "go" to "text/plain",
    "java" to "text/plain",
    "kt" to "text/plain",
    "kts" to "text/plain",

    // Documents
    "pdf" to "application/pdf",
    "doc" to "application/msword",
    "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "xls" to "application/vnd.ms-excel",
    "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "ppt" to "application/vnd.ms-powerpoint",
    "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",

    // Archives & Packages
    "zip" to "application/zip",
    "tar" to "application/x-tar",
    "gz" to "application/gzip",
    "tgz" to "application/gzip",
    "bz2" to "application/x-bzip2",
    "xz" to "application/x-xz",
    "zst" to "application/zstd",
    "7z" to "application/x-7z-compressed",
    "rar" to "application/x-rar-compressed",
    "apk" to "application/vnd.android.package-archive",
    "deb" to "application/vnd.debian.binary-package",
    "rpm" to "application/x-rpm",
    "iso" to "application/x-iso9660-image",

    // Images
    "png" to "image/png",
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "gif" to "image/gif",
    "webp" to "image/webp",
    "svg" to "image/svg+xml",
    "ico" to "image/x-icon",
    "bmp" to "image/bmp",
    "avif" to "image/avif",
    "tif" to "image/tiff",
    "tiff" to "image/tiff",

    // Audio & Video
    "mp4" to "video/mp4",
    "m4v" to "video/mp4",
    "webm" to "video/webm",
    "mkv" to "video/webm",
    "ogv" to "video/ogg",
    "mov" to "video/quicktime",
    "avi" to "video/x-msvideo",
    "mp3" to "audio/mpeg",
    "m4a" to "audio/mp4",
    "ogg" to "audio/ogg",
    "oga" to "audio/ogg",
    "opus" to "audio/ogg",
    "wav" to "audio/wav",
    "flac" to "audio/flac"
)

private val MIME_TO_EXTENSION: Map<String, String> = mapOf(
    "application/pdf" to "pdf",
    "application/zip" to "zip",
    "application/gzip" to "gz",
    "application/x-tar" to "tar",
    "application/vnd.android.package-archive" to "apk",
    "application/json" to "json",
    "image/png" to "png",
    "image/jpeg" to "jpg",
    "image/gif" to "gif",
    "image/webp" to "webp",
    "image/svg+xml" to "svg",
    "video/mp4" to "mp4",
    "audio/mpeg" to "mp3",
    "text/plain" to "txt",
    "text/html" to "html",
    "text/css" to "css"
)

fun sanitizeFileName(name: String): String {
    val clean = name.trim()
        .replace("\r", "")
        .replace("\n", "")
        .replace(Regex("""[/\\:*?"<>|]+"""), "_")
        .trimEnd('.', ' ')
    return clean.ifEmpty { "download" }
}

fun extractFileNameFromContentDisposition(contentDisposition: String?): String? {
    if (contentDisposition.isNullOrBlank()) return null

    // 1. RFC 5987 / RFC 6266 filename*=UTF-8''<encoded>
    val rfcPattern = Regex("""filename\*\s*=\s*(?:UTF-8|utf-8)''([^;]+)""", RegexOption.IGNORE_CASE)
    val rfcMatch = rfcPattern.find(contentDisposition)
    if (rfcMatch != null) {
        val raw = rfcMatch.groupValues[1].trim('"', '\'', ' ')
        val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        val name = decoded.substringAfterLast('/').substringAfterLast('\\').trim()
        if (name.isNotEmpty()) return sanitizeFileName(name)
    }

    // 2. Standard filename="..." or filename=...
    val stdPattern = Regex("""filename\s*=\s*(?:"([^"]+)"|([^;\s]+))""", RegexOption.IGNORE_CASE)
    val stdMatch = stdPattern.find(contentDisposition)
    if (stdMatch != null) {
        val raw = (stdMatch.groupValues[1].ifEmpty { stdMatch.groupValues[2] }).trim('"', '\'', ' ')
        val name = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        if (name.isNotEmpty()) return sanitizeFileName(name)
    }

    return null
}

fun extractFileNameFromUrl(url: String?): String? {
    if (url.isNullOrBlank()) return null
    return runCatching {
        // Query param "path" (e.g. /api/files/1/download?path=/etc/nginx/nginx.conf)
        val queryStart = url.indexOf('?')
        if (queryStart != -1) {
            val query = url.substring(queryStart + 1)
            for (param in query.split('&')) {
                val eq = param.indexOf('=')
                if (eq != -1) {
                    val key = param.substring(0, eq)
                    if (key.equals("path", ignoreCase = true)) {
                        val rawValue = param.substring(eq + 1)
                        val decoded = runCatching { URLDecoder.decode(rawValue, "UTF-8") }.getOrDefault(rawValue)
                        val candidate = decoded.trimEnd('/').substringAfterLast('/').trim()
                        if (candidate.isNotEmpty()) return sanitizeFileName(candidate)
                    }
                }
            }
        }

        // Path segment before '?'
        val pathPart = if (queryStart != -1) url.substring(0, queryStart) else url
        val lastSegment = pathPart.trimEnd('/').substringAfterLast('/').trim()
        if (lastSegment.isNotEmpty() &&
            lastSegment != "download" &&
            lastSegment != "files" &&
            lastSegment != "view" &&
            !lastSegment.contains("://")
        ) {
            val decoded = runCatching { URLDecoder.decode(lastSegment, "UTF-8") }.getOrDefault(lastSegment)
            return sanitizeFileName(decoded)
        }
        null
    }.getOrNull()
}

fun resolveDownloadFileName(
    url: String?,
    contentDisposition: String?,
    mimeType: String?
): String {
    // Priority 1: Content-Disposition header
    var candidate = extractFileNameFromContentDisposition(contentDisposition)

    // Priority 2: URL query param "path" or descriptive URL segment
    if (candidate == null) {
        candidate = extractFileNameFromUrl(url)
    }

    if (candidate.isNullOrBlank()) {
        candidate = "download"
    }

    // If filename has no dot/extension and we have a specific known MIME type (not octet-stream/bin):
    val hasDot = candidate.contains('.')
    if (!hasDot) {
        val normalizedMime = mimeType?.trim()?.lowercase() ?: ""
        if (normalizedMime.isNotEmpty() &&
            normalizedMime != "application/octet-stream" &&
            normalizedMime != "binary/octet-stream"
        ) {
            val ext = MIME_TO_EXTENSION[normalizedMime]
            if (ext != null) {
                candidate = "$candidate.$ext"
            }
        }
    }

    return sanitizeFileName(candidate)
}

fun resolveDownloadMimeType(fileName: String, headerMimeType: String?): String {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    if (ext.isNotEmpty()) {
        val mapped = EXTENSION_TO_MIME[ext]
        if (mapped != null) return mapped

        // On Android runtime, fall back to android.webkit.MimeTypeMap safely
        val systemMime = runCatching {
            android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        }.getOrNull()
        if (!systemMime.isNullOrBlank()) return systemMime
    }

    val normalizedHeader = headerMimeType?.trim()?.lowercase() ?: ""
    if (normalizedHeader.isNotEmpty() &&
        normalizedHeader != "application/octet-stream" &&
        normalizedHeader != "binary/octet-stream"
    ) {
        return headerMimeType!!.trim()
    }

    return "application/octet-stream"
}

fun contentTypeForFilename(filename: String): ContentType {
    val ext = filename.substringAfterLast('.', "").lowercase()
    val mimeStr = EXTENSION_TO_MIME[ext] ?: return ContentType.Application.OctetStream
    val parts = mimeStr.split('/')
    return if (parts.size == 2) {
        ContentType(parts[0], parts[1])
    } else {
        ContentType.Application.OctetStream
    }
}

fun buildContentDispositionHeader(filename: String): String {
    val safeAscii = filename
        .replace("\\", "_")
        .replace("\"", "_")
        .replace("\r", "")
        .replace("\n", "")
        .ifEmpty { "download" }
    val encoded = URLEncoder.encode(filename, "UTF-8").replace("+", "%20")
    return "attachment; filename=\"$safeAscii\"; filename*=UTF-8''$encoded"
}

data class DownloadResult(
    val fileName: String,
    val mimeType: String,
    val uri: Uri,
    val sizeBytes: Long
)

fun formatFileSize(bytes: Long): String {
    if (bytes < 0) return "0 B"
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.1f GB", gb)
}

fun cleanBase64Data(raw: String): String {
    val comma = raw.indexOf(',')
    return if (comma != -1 && raw.startsWith("data:", ignoreCase = true)) {
        raw.substring(comma + 1).trim()
    } else {
        raw.trim()
    }
}

fun decodeBase64Bytes(base64Data: String): ByteArray {
    val clean = cleanBase64Data(base64Data)
    return try {
        java.util.Base64.getMimeDecoder().decode(clean)
    } catch (_: Exception) {
        java.util.Base64.getDecoder().decode(clean)
    }
}

fun parseDataUri(dataUri: String): Pair<ByteArray, String>? {
    if (!dataUri.startsWith("data:", ignoreCase = true)) return null
    val commaIndex = dataUri.indexOf(',')
    if (commaIndex == -1) return null
    val metadata = dataUri.substring(5, commaIndex)
    val dataPart = dataUri.substring(commaIndex + 1)
    val isBase64 = metadata.endsWith(";base64", ignoreCase = true)
    val mimeType = if (isBase64) {
        metadata.substring(0, metadata.length - 7)
    } else {
        metadata
    }.ifBlank { "application/octet-stream" }

    val bytes = if (isBase64) {
        runCatching {
            decodeBase64Bytes(dataPart)
        }.getOrNull() ?: return null
    } else {
        runCatching {
            URLDecoder.decode(dataPart, "UTF-8").toByteArray(Charsets.UTF_8)
        }.getOrNull() ?: return null
    }

    return Pair(bytes, mimeType)
}

fun saveBytesToDownloads(
    context: Context,
    bytes: ByteArray,
    fileName: String,
    mimeType: String,
    onProgress: ((downloaded: Long, total: Long) -> Unit)? = null
): DownloadResult {
    return java.io.ByteArrayInputStream(bytes).use { inputStream ->
        saveStreamToDownloads(
            context = context,
            inputStream = inputStream,
            fileName = fileName,
            mimeType = mimeType,
            expectedBytes = bytes.size.toLong(),
            onProgress = onProgress
        )
    }
}


fun copyStreamWithVerification(
    inputStream: InputStream,
    outputStream: java.io.OutputStream,
    expectedBytes: Long = -1L,
    onProgress: ((downloaded: Long, total: Long) -> Unit)? = null
): Long {
    val buf = ByteArray(64 * 1024)
    var totalBytes = 0L
    var n: Int
    while (inputStream.read(buf).also { n = it } != -1) {
        if (n > 0) {
            outputStream.write(buf, 0, n)
            totalBytes += n
            onProgress?.invoke(totalBytes, expectedBytes)
        }
    }
    outputStream.flush()
    if (expectedBytes > 0L && totalBytes < expectedBytes) {
        throw IOException("Unduhan tidak lengkap: hanya menerima $totalBytes dari $expectedBytes byte")
    }
    return totalBytes
}

fun saveStreamToDownloads(
    context: Context,
    inputStream: InputStream,
    fileName: String,
    mimeType: String,
    expectedBytes: Long = -1L,
    onProgress: ((downloaded: Long, total: Long) -> Unit)? = null
): DownloadResult {
    val cleanName = sanitizeFileName(fileName)
    val effectiveMime = resolveDownloadMimeType(cleanName, mimeType)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, cleanName)
            put(MediaStore.Downloads.MIME_TYPE, effectiveMime)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val itemUri = resolver.insert(collection, values)
            ?: throw IOException("Gagal membuat file baru di folder Downloads")

        try {
            val totalBytes = resolver.openOutputStream(itemUri)?.use { out ->
                copyStreamWithVerification(inputStream, out, expectedBytes, onProgress)
            } ?: throw IOException("Gagal membuka output stream ke MediaStore")

            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(itemUri, values, null, null)
            return DownloadResult(cleanName, effectiveMime, itemUri, totalBytes)
        } catch (t: Throwable) {
            runCatching { resolver.delete(itemUri, null, null) }
            throw t
        }
    } else {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadsDir.exists()) {
            downloadsDir.mkdirs()
        }
        var targetFile = File(downloadsDir, cleanName)
        if (targetFile.exists()) {
            val dot = cleanName.lastIndexOf('.')
            val base = if (dot != -1) cleanName.substring(0, dot) else cleanName
            val ext = if (dot != -1) cleanName.substring(dot) else ""
            var count = 1
            while (targetFile.exists()) {
                targetFile = File(downloadsDir, "$base ($count)$ext")
                count++
            }
        }
        try {
            val totalBytes = targetFile.outputStream().use { out ->
                copyStreamWithVerification(inputStream, out, expectedBytes, onProgress)
            }
            MediaScannerConnection.scanFile(
                context,
                arrayOf(targetFile.absolutePath),
                arrayOf(effectiveMime),
                null
            )
            val uri = runCatching {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", targetFile)
            }.getOrElse {
                Uri.fromFile(targetFile)
            }
            return DownloadResult(targetFile.name, effectiveMime, uri, totalBytes)
        } catch (t: Throwable) {
            runCatching { targetFile.delete() }
            throw t
        }
    }
}

fun downloadFileDirectly(
    context: Context,
    url: String,
    userAgent: String? = null,
    contentDisposition: String? = null,
    mimeType: String? = null,
    onProgress: ((downloaded: Long, total: Long) -> Unit)? = null
): DownloadResult {
    if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
        throw IllegalArgumentException("Protokol URL tidak didukung untuk unduhan HTTP: $url")
    }

    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 15000
        readTimeout = 60000
        instanceFollowRedirects = true
        if (!userAgent.isNullOrBlank()) {
            setRequestProperty("User-Agent", userAgent)
        }
    }

    try {
        connection.connect()
        val code = connection.responseCode
        if (code !in 200..299) {
            val errorText = runCatching {
                connection.errorStream?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            val parsedMsg = if (!errorText.isNullOrBlank() && errorText.contains("\"detail\"")) {
                runCatching {
                    val elem = Json.parseToJsonElement(errorText)
                    elem.jsonObject["detail"]?.jsonPrimitive?.content
                }.getOrNull() ?: errorText
            } else {
                errorText?.ifBlank { null } ?: "HTTP $code ${connection.responseMessage}"
            }
            throw IOException(parsedMsg)
        }

        val headerCd = connection.getHeaderField("Content-Disposition") ?: contentDisposition
        val headerMime = connection.contentType ?: mimeType
        val headerLen = connection.getHeaderField("Content-Length")?.toLongOrNull()
            ?: (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) connection.contentLengthLong else connection.contentLength.toLong())
        val fileName = resolveDownloadFileName(url, headerCd, headerMime)
        val effectiveMime = resolveDownloadMimeType(fileName, headerMime)

        return connection.inputStream.use { stream ->
            saveStreamToDownloads(
                context = context,
                inputStream = stream,
                fileName = fileName,
                mimeType = effectiveMime,
                expectedBytes = if (headerLen > 0L) headerLen else -1L,
                onProgress = onProgress
            )
        }
    } finally {
        connection.disconnect()
    }
}
