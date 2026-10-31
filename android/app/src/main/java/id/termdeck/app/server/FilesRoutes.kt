package id.termdeck.app.server

import id.termdeck.app.buildContentDispositionHeader
import id.termdeck.app.contentTypeForFilename
import id.termdeck.app.data.BookmarkIn
import id.termdeck.app.data.StoreBackend
import id.termdeck.app.ssh.MAX_UPLOAD_FILE_BYTES
import id.termdeck.app.ssh.SshPoolBackend
import id.termdeck.app.ssh.posixJoin
import id.termdeck.app.ssh.shlexQuote
import id.termdeck.app.ssh.sshErrorMessage
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private val PROTECTED_PATHS = setOf(
    "/", "/bin", "/boot", "/dev", "/etc", "/home", "/lib", "/lib64", "/opt", "/proc", "/root",
    "/run", "/sbin", "/srv", "/sys", "/tmp", "/usr", "/var"
)

private val VIEW_MEDIA_TYPES = mapOf(
    "png" to ContentType.Image.PNG,
    "jpg" to ContentType.Image.JPEG,
    "jpeg" to ContentType.Image.JPEG,
    "gif" to ContentType.Image.GIF,
    "webp" to ContentType("image", "webp"),
    "bmp" to ContentType("image", "bmp"),
    "ico" to ContentType("image", "x-icon"),
    "avif" to ContentType("image", "avif"),
    "svg" to ContentType.Image.SVG,
    "mp4" to ContentType("video", "mp4"),
    "m4v" to ContentType("video", "mp4"),
    "webm" to ContentType("video", "webm"),
    "ogv" to ContentType("video", "ogg"),
    "mov" to ContentType("video", "quicktime"),
    "mkv" to ContentType("video", "webm"),
    "mp3" to ContentType("audio", "mpeg"),
    "m4a" to ContentType("audio", "mp4"),
    "ogg" to ContentType("audio", "ogg"),
    "oga" to ContentType("audio", "ogg"),
    "opus" to ContentType("audio", "ogg"),
    "wav" to ContentType("audio", "wav"),
    "flac" to ContentType("audio", "flac"),
    "pdf" to ContentType.Application.Pdf
)

@Serializable
data class BookmarkToggleBody(val server_id: Int, val path: String)

@Serializable
data class BookmarkSyncBody(val server_id: Int, val paths: List<String>)

@Serializable
data class FileRemoteRecord(
    val id: Int,
    val name: String,
    val local_server_id: Int,
    val local_path: String,
    val remote_server_id: Int,
    val remote_path: String,
    val ignore: List<String> = emptyList()
)

@Serializable
data class FileRemoteInputBody(
    val name: String,
    val local_server_id: Int,
    val local_path: String,
    val remote_server_id: Int,
    val remote_path: String,
    val ignore: List<String> = emptyList()
)

@Serializable
data class RemoteTransferBody(
    val direction: String,
    val paths: List<String>
)

@Serializable
data class DirectTransferScanBody(
    val source_server_id: Int,
    val dest_server_id: Int,
    val source_dir: String,
    val dest_dir: String,
    val paths: List<String>,
    val ignore: List<String>? = null,
    val use_gitignore: Boolean = true
)

@Serializable
data class DirectTransferStartBody(
    val source_server_id: Int,
    val dest_server_id: Int,
    val source_dir: String,
    val dest_dir: String,
    val paths: List<String>,
    val mode: String = "copy",
    val ignore: List<String>? = null,
    val use_gitignore: Boolean = true
)

class ActiveTransferJob(
    val id: String = UUID.randomUUID().toString().replace("-", "").take(16),
    val remoteId: Int? = null,
    val remoteName: String,
    val direction: String,
    val sourceServerId: Int,
    val sourceRoot: String,
    val destServerId: Int,
    val destRoot: String,
    val rels: List<String>,
    val ignore: List<String>,
    val readGitignore: Boolean = true,
    val totalBytes: Long,
    val totalFiles: Int
) {
    @Volatile
    var state: String = "running"

    @Volatile
    var sentBytes: Long = 0L

    @Volatile
    var result: JsonObject? = null

    @Volatile
    var error: String? = null

    val startedAt: Double = System.currentTimeMillis() / 1000.0

    @Volatile
    var finishedAt: Double? = null

    @Volatile
    var job: Job? = null

    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        if (remoteId != null) put("remote_id", remoteId) else put("remote_id", JsonNull)
        put("remote_name", remoteName)
        put("direction", direction)
        put("state", state)
        put("source_server_id", sourceServerId)
        put("source_root", sourceRoot)
        put("dest_server_id", destServerId)
        put("dest_root", destRoot)
        put("rels", buildJsonArray { rels.forEach { add(JsonPrimitive(it)) } })
        put("read_gitignore", readGitignore)
        put("sent_bytes", sentBytes)
        put("total_bytes", totalBytes)
        put("total_files", totalFiles)
        put("result", result ?: JsonNull)
        if (error != null) put("error", error) else put("error", JsonNull)
        put("started_at", startedAt)
        if (finishedAt != null) put("finished_at", finishedAt) else put("finished_at", JsonNull)
    }
}

private fun normalizeAbsPath(raw: String): String {
    val trimmed = raw.trim()
    val candidate = when {
        trimmed.isEmpty() -> "/"
        !trimmed.startsWith("/") -> "/$trimmed"
        else -> trimmed
    }
    if (candidate.contains('\u0000')) {
        throw HttpError(HttpStatusCode.BadRequest, "Path must be absolute")
    }
    val parts = candidate.split("/")
    val out = mutableListOf<String>()
    for (p in parts) {
        if (p.isEmpty() || p == ".") continue
        if (p == "..") {
            if (out.isNotEmpty()) out.removeAt(out.lastIndex)
        } else {
            out.add(p)
        }
    }
    return if (out.isEmpty()) "/" else "/" + out.joinToString("/")
}

private fun validateFileName(name: String): String {
    if (name.isEmpty() || name == "." || name == ".." || name.contains("/") || name.contains('\u0000') || name.toByteArray(Charsets.UTF_8).size > 255) {
        throw HttpError(HttpStatusCode.BadRequest, "Invalid file name")
    }
    return name
}

private fun parseByteRange(header: String?, size: Long, maxChunk: Long = 16L * 1024L * 1024L): Pair<Long, Long>? {
    if (header.isNullOrBlank() || !header.startsWith("bytes=") || header.contains(",")) return null
    val spec = header.substring(6).trim()
    val dash = spec.indexOf('-')
    if (dash < 0) return null
    val first = spec.substring(0, dash)
    val last = spec.substring(dash + 1)
    val start: Long
    var end: Long
    try {
        if (first.isNotEmpty()) {
            start = first.toLong()
            end = if (last.isNotEmpty()) {
                val parsedEnd = last.toLong()
                if (parsedEnd - start + 1 > maxChunk) start + maxChunk - 1 else parsedEnd
            } else {
                minOf(start + maxChunk - 1, size - 1)
            }
        } else {
            val requested = minOf(last.toLong(), maxChunk)
            start = maxOf(size - requested, 0L)
            end = size - 1
        }
    } catch (_: NumberFormatException) {
        return null
    }
    if (start >= size || start > end) {
        throw HttpError(HttpStatusCode.RequestedRangeNotSatisfiable, "Range not satisfiable")
    }
    return start to minOf(end, size - 1)
}

fun Route.registerCockpitFilesRoutes(
    assetLoader: (String) -> ByteArray?,
    handleApiFn: suspend (ApplicationCall, suspend () -> Unit) -> Unit,
    requireUnlockedFn: (ApplicationCall) -> Pair<StoreBackend, SshPoolBackend>,
    ensureServerFn: (StoreBackend, Int) -> Any
) {
    val agentSource: String by lazy {
        val bytes = assetLoader("files_agent.py")
            ?: runCatching { File("src/main/assets/files_agent.py").readBytes() }.getOrNull()
            ?: runCatching { File("android/app/src/main/assets/files_agent.py").readBytes() }.getOrNull()
            ?: error("files_agent.py asset not found")
        bytes.toString(Charsets.UTF_8)
    }
    val agentCmd: String by lazy { "python3 -c ${shlexQuote(agentSource)}" }

    val remotes = ConcurrentHashMap<Int, FileRemoteRecord>()
    val nextRemoteId = AtomicInteger(1)
    val transferJobs = ConcurrentHashMap<String, ActiveTransferJob>()
    val transferScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun runAgent(pool: SshPoolBackend, serverId: Int, reqObj: JsonObject): JsonElement {
        val payload = AppJson.encodeToString(reqObj)
        val execRes = try {
            pool.execCommand(serverId, agentCmd, payload + "\n", 120_000L)
        } catch (t: Throwable) {
            throw HttpError(HttpStatusCode.BadGateway, sshErrorMessage(t))
        }
        val parsed = try {
            AppJson.parseToJsonElement(execRes.stdout).jsonObject
        } catch (_: Exception) {
            val detail = (execRes.stderr.ifBlank { execRes.stdout }.ifBlank { "no output" }).trim().take(500)
            throw HttpError(HttpStatusCode.BadGateway, "File agent failed: $detail")
        }
        val ok = parsed["ok"]?.jsonPrimitive?.booleanOrNull ?: false
        if (!ok) {
            val errText = parsed["error"]?.jsonPrimitive?.content ?: "Operation failed"
            val code = parsed["code"]?.jsonPrimitive?.content
            val status = if (code == "exists") HttpStatusCode.Conflict else HttpStatusCode.BadRequest
            throw HttpError(status, errText)
        }
        return parsed["result"] ?: JsonNull
    }

    fun launchRelayTransfer(pool: SshPoolBackend, job: ActiveTransferJob) {
        job.job = transferScope.launch {
            try {
                val srcProc = pool.openProcess(job.sourceServerId, agentCmd)
                try {
                    val dstProc = pool.openProcess(job.destServerId, agentCmd)
                    try {
                        val packReq = buildJsonObject {
                            put("op", "pack")
                            put("root", job.sourceRoot)
                            put("rels", buildJsonArray { job.rels.forEach { add(JsonPrimitive(it)) } })
                            put("ignore", buildJsonArray { job.ignore.forEach { add(JsonPrimitive(it)) } })
                            put("read_gitignore", job.readGitignore)
                        }
                        srcProc.outputStream.write((AppJson.encodeToString(packReq) + "\n").toByteArray(Charsets.UTF_8))
                        srcProc.outputStream.flush()
                        runCatching { srcProc.outputStream.close() }

                        val unpackReq = buildJsonObject {
                            put("op", "unpack")
                            put("root", job.destRoot)
                        }
                        dstProc.outputStream.write((AppJson.encodeToString(unpackReq) + "\n").toByteArray(Charsets.UTF_8))
                        dstProc.outputStream.flush()

                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = srcProc.inputStream.read(buf)
                            if (n == -1) break
                            if (n > 0) {
                                dstProc.outputStream.write(buf, 0, n)
                                dstProc.outputStream.flush()
                                job.sentBytes += n
                            }
                        }
                        runCatching { dstProc.outputStream.close() }

                        val dstOut = dstProc.inputStream.readBytes().toString(Charsets.UTF_8)
                        val dstErr = dstProc.errorStream.readBytes().toString(Charsets.UTF_8)
                        srcProc.join(120_000L)
                        dstProc.join(120_000L)

                        val reply = try {
                            AppJson.parseToJsonElement(dstOut).jsonObject
                        } catch (_: Exception) {
                            throw IllegalStateException((dstErr.ifBlank { dstOut }.ifBlank { "Unpack failed" }).trim().take(400))
                        }
                        if (reply["ok"]?.jsonPrimitive?.booleanOrNull != true) {
                            throw IllegalStateException(reply["error"]?.jsonPrimitive?.content ?: "Unpack failed")
                        }
                        job.result = reply["result"] as? JsonObject
                        job.state = "done"
                    } finally {
                        runCatching { dstProc.close() }
                        pool.release(job.destServerId)
                    }
                } finally {
                    runCatching { srcProc.close() }
                    pool.release(job.sourceServerId)
                }

                if (job.direction == "direct-move" && job.state == "done") {
                    val deletePaths = job.rels.filter { it != "." }.map { posixJoin(job.sourceRoot, it) }
                    if (deletePaths.isNotEmpty()) {
                        runCatching {
                            runAgent(
                                pool,
                                job.sourceServerId,
                                buildJsonObject {
                                    put("op", "delete")
                                    put("paths", buildJsonArray { deletePaths.forEach { add(JsonPrimitive(it)) } })
                                }
                            )
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                job.state = "cancelled"
                job.error = "Cancelled"
            } catch (t: Throwable) {
                job.state = "error"
                job.error = t.message ?: t.javaClass.simpleName
            } finally {
                job.finishedAt = System.currentTimeMillis() / 1000.0
            }
        }
    }

    route("/files") {
        get("/bookmarks") {
            handleApiFn(call) {
                val (store, _) = requireUnlockedFn(call)
                val serverId = call.request.queryParameters["server_id"]?.toIntOrNull()
                if (serverId != null) {
                    val list = store.listBookmarks(serverId).map { it.path }
                    call.respond(list)
                } else {
                    val map = mutableMapOf<String, MutableList<String>>()
                    for (b in store.listBookmarks(null)) {
                        map.getOrPut(b.serverId.toString()) { mutableListOf() }.add(b.path)
                    }
                    call.respond(map)
                }
            }
        }

        post("/bookmarks/toggle") {
            handleApiFn(call) {
                val (store, _) = requireUnlockedFn(call)
                val body = call.receive<BookmarkToggleBody>()
                val clean = normalizeAbsPath(body.path)
                val existing = store.listBookmarks(body.server_id)
                val match = existing.find { it.path == clean }
                val bookmarked = if (match != null) {
                    store.deleteBookmark(match.id)
                    false
                } else {
                    val name = clean.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }
                    store.createBookmark(BookmarkIn(serverId = body.server_id, name = name, path = clean, startupCmd = ""))
                    true
                }
                val updated = store.listBookmarks(body.server_id).map { it.path }
                call.respond(
                    buildJsonObject {
                        put("bookmarked", bookmarked)
                        put("bookmarks", buildJsonArray { updated.forEach { add(JsonPrimitive(it)) } })
                    }
                )
            }
        }

        post("/bookmarks/sync") {
            handleApiFn(call) {
                val (store, _) = requireUnlockedFn(call)
                val body = call.receive<BookmarkSyncBody>()
                val existing = store.listBookmarks(body.server_id).map { it.path }.toMutableSet()
                for (raw in body.paths) {
                    val clean = runCatching { normalizeAbsPath(raw) }.getOrNull() ?: continue
                    if (clean !in existing) {
                        val name = clean.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }
                        store.createBookmark(BookmarkIn(serverId = body.server_id, name = name, path = clean, startupCmd = ""))
                        existing.add(clean)
                    }
                }
                val updated = store.listBookmarks(body.server_id).map { it.path }
                call.respond(updated)
            }
        }

        get("/remotes") {
            handleApiFn(call) {
                requireUnlockedFn(call)
                call.respond(remotes.values.sortedBy { it.name })
            }
        }

        post("/remotes") {
            handleApiFn(call) {
                val (store, _) = requireUnlockedFn(call)
                val body = call.receive<FileRemoteInputBody>()
                ensureServerFn(store, body.local_server_id)
                ensureServerFn(store, body.remote_server_id)
                val localPath = normalizeAbsPath(body.local_path)
                val remotePath = normalizeAbsPath(body.remote_path)
                if (localPath in PROTECTED_PATHS || remotePath in PROTECTED_PATHS) {
                    throw HttpError(HttpStatusCode.BadRequest, "Choose a project folder, not a system directory")
                }
                val id = nextRemoteId.getAndIncrement()
                val rec = FileRemoteRecord(
                    id = id,
                    name = body.name.trim(),
                    local_server_id = body.local_server_id,
                    local_path = localPath,
                    remote_server_id = body.remote_server_id,
                    remote_path = remotePath,
                    ignore = body.ignore
                )
                remotes[id] = rec
                call.respond(HttpStatusCode.Created, rec)
            }
        }

        put("/remotes/{id}") {
            handleApiFn(call) {
                val (store, _) = requireUnlockedFn(call)
                val id = call.parameters["id"]?.toIntOrNull()
                    ?: throw HttpError(HttpStatusCode.BadRequest, "Invalid remote id")
                if (!remotes.containsKey(id)) {
                    throw HttpError(HttpStatusCode.NotFound, "Remote not found")
                }
                val body = call.receive<FileRemoteInputBody>()
                ensureServerFn(store, body.local_server_id)
                ensureServerFn(store, body.remote_server_id)
                val rec = FileRemoteRecord(
                    id = id,
                    name = body.name.trim(),
                    local_server_id = body.local_server_id,
                    local_path = normalizeAbsPath(body.local_path),
                    remote_server_id = body.remote_server_id,
                    remote_path = normalizeAbsPath(body.remote_path),
                    ignore = body.ignore
                )
                remotes[id] = rec
                call.respond(rec)
            }
        }

        delete("/remotes/{id}") {
            handleApiFn(call) {
                requireUnlockedFn(call)
                val id = call.parameters["id"]?.toIntOrNull()
                    ?: throw HttpError(HttpStatusCode.BadRequest, "Invalid remote id")
                remotes.remove(id)
                call.respond(HttpStatusCode.NoContent)
            }
        }

        post("/remotes/{id}/scan") {
            handleApiFn(call) {
                val (_, pool) = requireUnlockedFn(call)
                val id = call.parameters["id"]?.toIntOrNull()
                    ?: throw HttpError(HttpStatusCode.BadRequest, "Invalid remote id")
                val remote = remotes[id] ?: throw HttpError(HttpStatusCode.NotFound, "Remote not found")
                val body = call.receive<RemoteTransferBody>()
                val root = remote.local_path.trimEnd('/')
                val rels = body.paths.map { raw ->
                    val p = normalizeAbsPath(raw)
                    if (p == remote.local_path) "." else p.substring(root.length + 1)
                }
                val srcId = if (body.direction == "upload") remote.local_server_id else remote.remote_server_id
                val srcRoot = if (body.direction == "upload") remote.local_path else remote.remote_path
                val dstId = if (body.direction == "upload") remote.remote_server_id else remote.local_server_id
                val dstRoot = if (body.direction == "upload") remote.remote_path else remote.local_path
                val counts = runAgent(
                    pool,
                    srcId,
                    buildJsonObject {
                        put("op", "scan")
                        put("root", srcRoot)
                        put("rels", buildJsonArray { rels.forEach { add(JsonPrimitive(it)) } })
                        put("ignore", buildJsonArray { remote.ignore.forEach { add(JsonPrimitive(it)) } })
                    }
                ).jsonObject

                val resp = buildJsonObject {
                    counts.forEach { (k, v) -> put(k, v) }
                    put("source_server_id", srcId)
                    put("source_root", srcRoot)
                    put("dest_server_id", dstId)
                    put("dest_root", dstRoot)
                    put("rels", buildJsonArray { rels.forEach { add(JsonPrimitive(it)) } })
                }
                call.respondText(AppJson.encodeToString(resp), ContentType.Application.Json)
            }
        }

        post("/remotes/{id}/transfer") {
            handleApiFn(call) {
                val (_, pool) = requireUnlockedFn(call)
                val id = call.parameters["id"]?.toIntOrNull()
                    ?: throw HttpError(HttpStatusCode.BadRequest, "Invalid remote id")
                val remote = remotes[id] ?: throw HttpError(HttpStatusCode.NotFound, "Remote not found")
                val body = call.receive<RemoteTransferBody>()
                val root = remote.local_path.trimEnd('/')
                val rels = body.paths.map { raw ->
                    val p = normalizeAbsPath(raw)
                    if (p == remote.local_path) "." else p.substring(root.length + 1)
                }
                val srcId = if (body.direction == "upload") remote.local_server_id else remote.remote_server_id
                val srcRoot = if (body.direction == "upload") remote.local_path else remote.remote_path
                val dstId = if (body.direction == "upload") remote.remote_server_id else remote.local_server_id
                val dstRoot = if (body.direction == "upload") remote.remote_path else remote.local_path

                val counts = runAgent(
                    pool,
                    srcId,
                    buildJsonObject {
                        put("op", "scan")
                        put("root", srcRoot)
                        put("rels", buildJsonArray { rels.forEach { add(JsonPrimitive(it)) } })
                        put("ignore", buildJsonArray { remote.ignore.forEach { add(JsonPrimitive(it)) } })
                    }
                ).jsonObject

                val skipped = counts["skipped"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content }?.toSet().orEmpty()
                val activeJob = ActiveTransferJob(
                    remoteId = remote.id,
                    remoteName = remote.name,
                    direction = body.direction,
                    sourceServerId = srcId,
                    sourceRoot = srcRoot,
                    destServerId = dstId,
                    destRoot = dstRoot,
                    rels = rels.filter { it !in skipped },
                    ignore = remote.ignore,
                    totalBytes = counts["tar_bytes"]?.jsonPrimitive?.longOrNull ?: 1024L,
                    totalFiles = counts["files"]?.jsonPrimitive?.intOrNull ?: 0
                )
                transferJobs[activeJob.id] = activeJob
                launchRelayTransfer(pool, activeJob)
                call.respondText(AppJson.encodeToString(activeJob.toJson()), ContentType.Application.Json)
            }
        }

        fun planDirect(sourceDir: String, destDir: String, rawPaths: List<String>): Triple<String, String, List<String>> {
            val srcRoot = normalizeAbsPath(sourceDir).trimEnd('/').ifEmpty { "/" }
            val dstRoot = normalizeAbsPath(destDir)
            if (dstRoot in PROTECTED_PATHS) {
                throw HttpError(HttpStatusCode.BadRequest, "Destination cannot be system directory $dstRoot")
            }
            val prefix = if (srcRoot == "/") "" else srcRoot
            val rels = mutableListOf<String>()
            for (raw in rawPaths) {
                val p = normalizeAbsPath(raw)
                when {
                    p == srcRoot -> rels.add(".")
                    p.startsWith("$prefix/") -> rels.add(p.substring(prefix.length + 1))
                    else -> throw HttpError(HttpStatusCode.BadRequest, "Path $p is outside source directory $srcRoot")
                }
            }
            return Triple(srcRoot, dstRoot, rels)
        }

        post("/direct-transfer/scan") {
            handleApiFn(call) {
                val (store, pool) = requireUnlockedFn(call)
                val body = call.receive<DirectTransferScanBody>()
                ensureServerFn(store, body.source_server_id)
                ensureServerFn(store, body.dest_server_id)
                val (srcRoot, dstRoot, rels) = planDirect(body.source_dir, body.dest_dir, body.paths)
                val counts = runAgent(
                    pool,
                    body.source_server_id,
                    buildJsonObject {
                        put("op", "scan")
                        put("root", srcRoot)
                        put("rels", buildJsonArray { rels.forEach { add(JsonPrimitive(it)) } })
                        put("ignore", buildJsonArray { body.ignore.orEmpty().forEach { add(JsonPrimitive(it)) } })
                        put("read_gitignore", body.use_gitignore)
                    }
                ).jsonObject

                val resp = buildJsonObject {
                    counts.forEach { (k, v) -> put(k, v) }
                    put("source_server_id", body.source_server_id)
                    put("source_root", srcRoot)
                    put("dest_server_id", body.dest_server_id)
                    put("dest_root", dstRoot)
                    put("rels", buildJsonArray { rels.forEach { add(JsonPrimitive(it)) } })
                    put("use_gitignore", body.use_gitignore)
                }
                call.respondText(AppJson.encodeToString(resp), ContentType.Application.Json)
            }
        }

        post("/direct-transfer/start") {
            handleApiFn(call) {
                val (store, pool) = requireUnlockedFn(call)
                val body = call.receive<DirectTransferStartBody>()
                ensureServerFn(store, body.source_server_id)
                ensureServerFn(store, body.dest_server_id)
                val (srcRoot, dstRoot, rels) = planDirect(body.source_dir, body.dest_dir, body.paths)
                val counts = runAgent(
                    pool,
                    body.source_server_id,
                    buildJsonObject {
                        put("op", "scan")
                        put("root", srcRoot)
                        put("rels", buildJsonArray { rels.forEach { add(JsonPrimitive(it)) } })
                        put("ignore", buildJsonArray { body.ignore.orEmpty().forEach { add(JsonPrimitive(it)) } })
                        put("read_gitignore", body.use_gitignore)
                    }
                ).jsonObject

                val skipped = counts["skipped"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content }?.toSet().orEmpty()
                val activeJob = ActiveTransferJob(
                    remoteName = "${body.source_server_id} -> ${body.dest_server_id}",
                    direction = "direct-${body.mode}",
                    sourceServerId = body.source_server_id,
                    sourceRoot = srcRoot,
                    destServerId = body.dest_server_id,
                    destRoot = dstRoot,
                    rels = rels.filter { it !in skipped },
                    ignore = body.ignore.orEmpty(),
                    readGitignore = body.use_gitignore,
                    totalBytes = counts["tar_bytes"]?.jsonPrimitive?.longOrNull ?: 1024L,
                    totalFiles = counts["files"]?.jsonPrimitive?.intOrNull ?: 0
                )
                transferJobs[activeJob.id] = activeJob
                launchRelayTransfer(pool, activeJob)
                call.respondText(AppJson.encodeToString(activeJob.toJson()), ContentType.Application.Json)
            }
        }

        get("/transfers/{jobId}") {
            handleApiFn(call) {
                requireUnlockedFn(call)
                val jobId = call.parameters["jobId"] ?: throw HttpError(HttpStatusCode.BadRequest, "Missing jobId")
                val job = transferJobs[jobId] ?: throw HttpError(HttpStatusCode.NotFound, "Transfer not found")
                call.respondText(AppJson.encodeToString(job.toJson()), ContentType.Application.Json)
            }
        }

        post("/transfers/{jobId}/cancel") {
            handleApiFn(call) {
                requireUnlockedFn(call)
                val jobId = call.parameters["jobId"] ?: throw HttpError(HttpStatusCode.BadRequest, "Missing jobId")
                val job = transferJobs[jobId] ?: throw HttpError(HttpStatusCode.NotFound, "Transfer not found")
                job.job?.cancel()
                job.state = "cancelled"
                job.error = "Cancelled"
                call.respondText(AppJson.encodeToString(job.toJson()), ContentType.Application.Json)
            }
        }

        route("/{serverId}") {
            get("/list") {
                handleApiFn(call) {
                    val (store, pool) = requireUnlockedFn(call)
                    val serverId = call.parameters["serverId"]?.toIntOrNull()
                        ?: throw HttpError(HttpStatusCode.BadRequest, "Server ID tidak valid")
                    ensureServerFn(store, serverId)
                    val path = normalizeAbsPath(call.request.queryParameters["path"]?.trim()?.ifEmpty { "/" } ?: "/")
                    val showHidden = call.request.queryParameters["show_hidden"]?.toBooleanStrictOrNull() ?: false
                    val res = runAgent(
                        pool,
                        serverId,
                        buildJsonObject {
                            put("op", "list")
                            put("path", path)
                            put("show_hidden", showHidden)
                        }
                    )
                    call.respondText(AppJson.encodeToString(res), ContentType.Application.Json)
                }
            }

            get("/principals") {
                handleApiFn(call) {
                    val (store, pool) = requireUnlockedFn(call)
                    val serverId = call.parameters["serverId"]?.toIntOrNull()
                        ?: throw HttpError(HttpStatusCode.BadRequest, "Server ID tidak valid")
                    ensureServerFn(store, serverId)
                    val res = runAgent(pool, serverId, buildJsonObject { put("op", "principals") })
                    call.respondText(AppJson.encodeToString(res), ContentType.Application.Json)
                }
            }

            get("/read") {
                handleApiFn(call) {
                    val (store, pool) = requireUnlockedFn(call)
                    val serverId = call.parameters["serverId"]?.toIntOrNull()
                        ?: throw HttpError(HttpStatusCode.BadRequest, "Server ID tidak valid")
                    ensureServerFn(store, serverId)
                    val path = normalizeAbsPath(call.request.queryParameters["path"] ?: "")
                    val res = runAgent(
                        pool,
                        serverId,
                        buildJsonObject {
                            put("op", "read")
                            put("path", path)
                        }
                    )
                    call.respondText(AppJson.encodeToString(res), ContentType.Application.Json)
                }
            }

            post("/action") {
                handleApiFn(call) {
                    val (store, pool) = requireUnlockedFn(call)
                    val serverId = call.parameters["serverId"]?.toIntOrNull()
                        ?: throw HttpError(HttpStatusCode.BadRequest, "Server ID tidak valid")
                    ensureServerFn(store, serverId)
                    val rawBody = call.receive<JsonObject>()
                    val op = rawBody["op"]?.jsonPrimitive?.content
                        ?: throw HttpError(HttpStatusCode.BadRequest, "Missing op")

                    val cleaned = buildJsonObject {
                        for ((k, v) in rawBody) {
                            when (k) {
                                "path", "new_path", "dest_dir" -> {
                                    if (v !is JsonNull) put(k, normalizeAbsPath(v.jsonPrimitive.content))
                                }
                                "paths" -> {
                                    if (v is JsonArray) {
                                        val arr = v.map { JsonPrimitive(normalizeAbsPath(it.jsonPrimitive.content)) }
                                        if (arr.isEmpty()) throw HttpError(HttpStatusCode.BadRequest, "No paths given")
                                        put(k, JsonArray(arr))
                                    }
                                }
                                else -> put(k, v)
                            }
                        }
                        put("op", op)
                    }
                    val res = runAgent(pool, serverId, cleaned)
                    call.respondText(AppJson.encodeToString(res), ContentType.Application.Json)
                }
            }

            post("/upload") {
                handleApiFn(call) {
                    val (store, pool) = requireUnlockedFn(call)
                    val serverId = call.parameters["serverId"]?.toIntOrNull()
                        ?: throw HttpError(HttpStatusCode.BadRequest, "Server ID tidak valid")
                    ensureServerFn(store, serverId)
                    val directory = normalizeAbsPath(call.request.queryParameters["dir"] ?: "")
                    val name = validateFileName(call.request.queryParameters["name"] ?: "")
                    val overwrite = call.request.queryParameters["overwrite"]?.toBooleanStrictOrNull() ?: false

                    val proc = try {
                        pool.openProcess(serverId, agentCmd)
                    } catch (t: Throwable) {
                        throw HttpError(HttpStatusCode.BadGateway, sshErrorMessage(t))
                    }
                    try {
                        val headerJson = buildJsonObject {
                            put("op", "upload")
                            put("dir", directory)
                            put("name", name)
                            put("overwrite", overwrite)
                        }
                        withContext(Dispatchers.IO) {
                            proc.outputStream.write((AppJson.encodeToString(headerJson) + "\n").toByteArray(Charsets.UTF_8))
                            proc.outputStream.flush()
                        }
                        val channel = call.request.receiveChannel()
                        val buf = ByteArray(65536)
                        var received = 0L
                        var agentGone = false
                        while (!channel.isClosedForRead) {
                            val read = channel.readAvailable(buf, 0, buf.size)
                            if (read <= 0) break
                            received += read
                            if (received > MAX_UPLOAD_FILE_BYTES) {
                                throw HttpError(HttpStatusCode.PayloadTooLarge, "Upload exceeds 50 MB")
                            }
                            if (!agentGone) {
                                try {
                                    withContext(Dispatchers.IO) {
                                        val lenBytes = ByteBuffer.allocate(4).putInt(read).array()
                                        proc.outputStream.write(lenBytes)
                                        proc.outputStream.write(buf, 0, read)
                                        proc.outputStream.flush()
                                    }
                                } catch (_: Exception) {
                                    agentGone = true
                                }
                            }
                        }
                        if (!agentGone) {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    proc.outputStream.write(ByteArray(4))
                                    proc.outputStream.flush()
                                    proc.outputStream.close()
                                }
                            }
                        }
                        val (stdout, stderr) = withContext(Dispatchers.IO) {
                            val out = proc.inputStream.readBytes().toString(Charsets.UTF_8)
                            val err = proc.errorStream.readBytes().toString(Charsets.UTF_8)
                            proc.join(120_000L)
                            out to err
                        }
                        val parsed = try {
                            AppJson.parseToJsonElement(stdout).jsonObject
                        } catch (_: Exception) {
                            val detail = (stderr.ifBlank { stdout }.ifBlank { "no output" }).trim().take(500)
                            throw HttpError(HttpStatusCode.BadGateway, "File agent failed: $detail")
                        }
                        if (parsed["ok"]?.jsonPrimitive?.booleanOrNull != true) {
                            val code = parsed["code"]?.jsonPrimitive?.content
                            val status = if (code == "exists") HttpStatusCode.Conflict else HttpStatusCode.BadRequest
                            throw HttpError(status, parsed["error"]?.jsonPrimitive?.content ?: "Upload failed")
                        }
                        call.respondText(AppJson.encodeToString(parsed["result"] ?: JsonNull), ContentType.Application.Json)
                    } finally {
                        withContext(Dispatchers.IO) { runCatching { proc.close() } }
                        pool.release(serverId)
                    }
                }
            }

            suspend fun regularFileSize(pool: SshPoolBackend, serverId: Int, path: String, verb: String): Long {
                var info = runAgent(
                    pool,
                    serverId,
                    buildJsonObject {
                        put("op", "stat")
                        put("path", path)
                    }
                ).jsonObject
                if (info["type"]?.jsonPrimitive?.content == "link") {
                    val target = info["target"]?.jsonPrimitive?.content ?: ""
                    val resolved = if (target.startsWith("/")) target else normalizeAbsPath(posixJoin(path.substringBeforeLast('/').ifEmpty { "/" }, target))
                    info = runAgent(
                        pool,
                        serverId,
                        buildJsonObject {
                            put("op", "stat")
                            put("path", resolved)
                        }
                    ).jsonObject
                }
                if (info["type"]?.jsonPrimitive?.content != "file") {
                    throw HttpError(HttpStatusCode.BadRequest, "Only regular files can be $verb")
                }
                return info["size"]?.jsonPrimitive?.longOrNull ?: 0L
            }

            suspend fun streamCommandOutput(call: ApplicationCall, pool: SshPoolBackend, serverId: Int, cmd: String, status: HttpStatusCode, contentType: ContentType) {
                val proc = try {
                    pool.openProcess(serverId, cmd)
                } catch (t: Throwable) {
                    throw HttpError(HttpStatusCode.BadGateway, sshErrorMessage(t))
                }
                try {
                    call.respondOutputStream(contentType = contentType, status = status) {
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = proc.inputStream.read(buf)
                            if (n == -1) break
                            if (n > 0) {
                                write(buf, 0, n)
                                flush()
                            }
                        }
                    }
                } finally {
                    withContext(Dispatchers.IO) { runCatching { proc.close() } }
                    pool.release(serverId)
                }
            }

            get("/download") {
                handleApiFn(call) {
                    val (store, pool) = requireUnlockedFn(call)
                    val serverId = call.parameters["serverId"]?.toIntOrNull()
                        ?: throw HttpError(HttpStatusCode.BadRequest, "Server ID tidak valid")
                    ensureServerFn(store, serverId)
                    val path = normalizeAbsPath(call.request.queryParameters["path"] ?: "")
                    val size = regularFileSize(pool, serverId, path, "downloaded")
                    val filename = path.substringAfterLast('/')
                    val cdHeader = buildContentDispositionHeader(filename)
                    call.response.header(HttpHeaders.ContentDisposition, cdHeader)
                    call.response.header(HttpHeaders.ContentLength, size.toString())
                    val contentType = contentTypeForFilename(filename)
                    streamCommandOutput(
                        call,
                        pool,
                        serverId,
                        "cat -- ${shlexQuote(path)}",
                        HttpStatusCode.OK,
                        contentType
                    )
                }
            }

            suspend fun serveInlineMedia(call: ApplicationCall, pool: SshPoolBackend, serverId: Int, path: String, mediaType: ContentType) {
                val size = regularFileSize(pool, serverId, path, "previewed")
                val range = if (size > 0) parseByteRange(call.request.header(HttpHeaders.Range), size) else null
                val start = range?.first ?: 0L
                val end = range?.second ?: maxOf(size - 1L, 0L)
                val qPath = shlexQuote(path)
                val cmd = when {
                    size == 0L || (start == 0L && end == size - 1L) -> "cat -- $qPath"
                    start == 0L -> "head -c ${end + 1L} -- $qPath"
                    else -> "tail -c +${start + 1L} -- $qPath | head -c ${end - start + 1L}"
                }
                val filename = path.substringAfterLast('/')
                val encoded = URLEncoder.encode(filename, "UTF-8").replace("+", "%20")
                call.response.header(HttpHeaders.ContentDisposition, "inline; filename*=UTF-8''$encoded")
                call.response.header(HttpHeaders.ContentLength, (if (size > 0) maxOf(end - start + 1L, 0L) else 0L).toString())
                call.response.header(HttpHeaders.AcceptRanges, "bytes")
                call.response.header(HttpHeaders.CacheControl, "private, max-age=3600")
                if (range != null) {
                    call.response.header(HttpHeaders.ContentRange, "bytes $start-$end/$size")
                }
                streamCommandOutput(
                    call,
                    pool,
                    serverId,
                    cmd,
                    if (range != null) HttpStatusCode.PartialContent else HttpStatusCode.OK,
                    mediaType
                )
            }

            get("/view") {
                handleApiFn(call) {
                    val (store, pool) = requireUnlockedFn(call)
                    val serverId = call.parameters["serverId"]?.toIntOrNull()
                        ?: throw HttpError(HttpStatusCode.BadRequest, "Server ID tidak valid")
                    ensureServerFn(store, serverId)
                    val path = normalizeAbsPath(call.request.queryParameters["path"] ?: "")
                    val ext = path.substringAfterLast('.', "").lowercase()
                    val mediaType = VIEW_MEDIA_TYPES[ext]
                        ?: throw HttpError(HttpStatusCode.UnsupportedMediaType, "This file type cannot be previewed")
                    serveInlineMedia(call, pool, serverId, path, mediaType)
                }
            }

            get("/thumb") {
                handleApiFn(call) {
                    val (store, pool) = requireUnlockedFn(call)
                    val serverId = call.parameters["serverId"]?.toIntOrNull()
                        ?: throw HttpError(HttpStatusCode.BadRequest, "Server ID tidak valid")
                    ensureServerFn(store, serverId)
                    val path = normalizeAbsPath(call.request.queryParameters["path"] ?: "")
                    val ext = path.substringAfterLast('.', "").lowercase()
                    val mediaType = VIEW_MEDIA_TYPES[ext]
                    if (mediaType == null || !(mediaType.contentType == "image" || mediaType.contentType == "video")) {
                        throw HttpError(HttpStatusCode.UnsupportedMediaType, "No thumbnail for this file type")
                    }
                    serveInlineMedia(call, pool, serverId, path, mediaType)
                }
            }
        }
    }
}
