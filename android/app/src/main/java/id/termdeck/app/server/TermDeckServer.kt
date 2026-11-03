package id.termdeck.app.server

import id.termdeck.app.buildContentDispositionHeader
import id.termdeck.app.contentTypeForFilename
import id.termdeck.app.data.AuthStatus
import id.termdeck.app.data.BackupCrypto
import id.termdeck.app.data.BackupCryptoException
import id.termdeck.app.data.BackupExportIn
import id.termdeck.app.data.BackupImportIn
import id.termdeck.app.data.BackupPinException
import id.termdeck.app.data.BookmarkIn
import id.termdeck.app.data.ChangePinIn
import id.termdeck.app.data.ConnectionCheck
import id.termdeck.app.data.ErrorDetail
import id.termdeck.app.data.FileChmodIn
import id.termdeck.app.data.FilePathIn
import id.termdeck.app.data.FileRenameIn
import id.termdeck.app.data.FileWriteIn
import id.termdeck.app.data.InvalidPinException
import id.termdeck.app.data.LoginIn
import id.termdeck.app.data.LoginOut
import id.termdeck.app.data.PastedImage
import id.termdeck.app.data.ServerIn
import id.termdeck.app.data.ServerOrderIn
import id.termdeck.app.data.ServerSecrets
import id.termdeck.app.data.ServerTestIn
import id.termdeck.app.data.SetupPinIn
import id.termdeck.app.data.SnippetIn
import id.termdeck.app.data.StoreBackend
import id.termdeck.app.data.TooManyAttemptsException
import id.termdeck.app.data.Vault
import id.termdeck.app.data.AndroidUpdater
import id.termdeck.app.data.UpdateApplyResult
import id.termdeck.app.data.UpdateCheckResult
import id.termdeck.app.data.UpdateProgressResult
import id.termdeck.app.data.generateRandomToken
import id.termdeck.app.ssh.MAX_PASTE_BYTES
import id.termdeck.app.ssh.MAX_UPLOAD_FILE_BYTES
import id.termdeck.app.ssh.RemotePathError
import id.termdeck.app.ssh.ServerNotFound
import id.termdeck.app.ssh.SshPoolBackend
import id.termdeck.app.ssh.TerminalSession
import id.termdeck.app.ssh.buildCdCommand
import id.termdeck.app.ssh.sniffImageType
import id.termdeck.app.ssh.sshErrorMessage
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.utils.io.readAvailable
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.net.URI
import java.security.MessageDigest

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
val AppJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    explicitNulls = true
}

val LOCAL_HOSTS = setOf("localhost", "127.0.0.1")

class HttpError(
    val status: HttpStatusCode,
    val detail: String,
    val retryAfterSeconds: Int? = null
) : Exception(detail)

fun originAllowed(origin: String?, allowedHosts: Set<String>?): Boolean {
    if (allowedHosts == null || origin.isNullOrBlank()) return true
    return try {
        val host = URI(origin).host?.lowercase()
        host != null && host in allowedHosts
    } catch (_: Exception) {
        false
    }
}

fun escapeHtml(text: String): String {
    return text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#x27;")
}

fun Application.configureTermDeckModule(
    vault: Vault,
    token: String,
    assetLoader: (String) -> ByteArray? = { null },
    allowedHosts: Set<String>? = LOCAL_HOSTS,
    onTerminalCountChanged: (Int) -> Unit = {},
    cfHttpClient: (suspend (method: String, path: String, token: String, body: String?) -> kotlinx.serialization.json.JsonElement)? = null,
    updater: AndroidUpdater? = null
) {
    install(ContentNegotiation) {
        json(AppJson)
    }
    install(WebSockets)

    val tokenBytes = token.toByteArray(Charsets.UTF_8)
    var activeTerminals = 0
    val termCountLock = Any()

    fun adjustTerminalCount(delta: Int) {
        val current = synchronized(termCountLock) {
            activeTerminals = (activeTerminals + delta).coerceAtLeast(0)
            activeTerminals
        }
        runCatching { onTerminalCountChanged(current) }
    }

    fun checkHost(call: ApplicationCall) {
        if (allowedHosts == null) return
        val rawHost = call.request.header(HttpHeaders.Host) ?: return
        val hostOnly = rawHost.substringBefore(':').lowercase()
        if (hostOnly !in allowedHosts) {
            throw HttpError(HttpStatusCode.BadRequest, "Invalid host header")
        }
    }

    fun requireToken(call: ApplicationCall) {
        checkHost(call)
        val xToken = call.request.header("X-Token")
            ?: call.request.queryParameters["token"]
            ?: ""
        if (!MessageDigest.isEqual(xToken.toByteArray(Charsets.UTF_8), tokenBytes)) {
            throw HttpError(HttpStatusCode.Unauthorized, "Token tidak valid")
        }
    }

    fun requireUnlockedBase(call: ApplicationCall): Triple<StoreBackend, SshPoolBackend, String> {
        requireToken(call)
        val xSession = call.request.header("X-Session")
            ?: call.request.queryParameters["session"]
            ?: ""
        val s = vault.store
        val p = vault.pool
        if (!vault.checkSession(xSession) || s == null || p == null) {
            throw HttpError(HttpStatusCode.Unauthorized, "Sesi berakhir. Masukkan PIN lagi.")
        }
        return Triple(s, p, xSession)
    }

    fun requireUnlocked(call: ApplicationCall): Pair<StoreBackend, SshPoolBackend> {
        val (s, p, xSession) = requireUnlockedBase(call)
        if (vault.needsPinChange(xSession)) {
            throw HttpError(HttpStatusCode.Forbidden, "Wajib mengganti PIN sebelum mengakses data.")
        }
        return s to p
    }

    suspend fun handleApi(call: ApplicationCall, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: HttpError) {
            if (e.retryAfterSeconds != null) {
                call.response.header(HttpHeaders.RetryAfter, e.retryAfterSeconds.toString())
            }
            call.respond(e.status, ErrorDetail(e.detail))
        } catch (e: TooManyAttemptsException) {
            val retryAfter = e.retryAfter.toInt() + 1
            call.response.header(HttpHeaders.RetryAfter, retryAfter.toString())
            call.respond(HttpStatusCode.TooManyRequests, ErrorDetail(e.message ?: "Terlalu banyak percobaan"))
        } catch (e: InvalidPinException) {
            call.respond(HttpStatusCode.Forbidden, ErrorDetail(e.message ?: "PIN salah."))
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.UnprocessableEntity, ErrorDetail(e.message ?: "Data tidak valid"))
        } catch (e: Throwable) {
            call.respond(HttpStatusCode.InternalServerError, ErrorDetail(e.message ?: e.javaClass.simpleName))
        }
    }

    routing {
        route("/api") {
            get("/auth/status") {
                handleApi(call) {
                    requireToken(call)
                    val xSession = call.request.header("X-Session")
                        ?: call.request.queryParameters["session"]
                        ?: ""
                    call.respond(
                        AuthStatus(
                            unlocked = vault.unlocked,
                            sessionValid = vault.checkSession(xSession),
                            initialized = vault.isInitialized,
                            mustChangePin = vault.needsPinChange(xSession)
                        )
                    )
                }
            }

            post("/auth/setup-pin") {
                handleApi(call) {
                    requireToken(call)
                    if (vault.isInitialized) {
                        throw HttpError(HttpStatusCode.BadRequest, "Vault sudah diinisialisasi. Silakan login.")
                    }
                    val body = call.receive<SetupPinIn>()
                    val session = withContext(Dispatchers.IO) {
                        vault.setupPin(body.pin)
                    }
                    call.respond(LoginOut(session = session, mustChangePin = false))
                }
            }

            post("/auth/login") {
                handleApi(call) {
                    requireToken(call)
                    if (!vault.isInitialized) {
                        throw HttpError(
                            HttpStatusCode.BadRequest,
                            "Vault belum diinisialisasi. Silakan buat PIN terlebih dahulu."
                        )
                    }
                    val body = call.receive<LoginIn>()
                    val session = withContext(Dispatchers.IO) {
                        vault.unlock(body.pin)
                    }
                    call.respond(LoginOut(session = session, mustChangePin = vault.needsPinChange(session)))
                }
            }

            post("/auth/lock") {
                handleApi(call) {
                    requireUnlocked(call)
                    vault.lock()
                    call.respond(HttpStatusCode.NoContent)
                }
            }

            post("/auth/change-pin") {
                handleApi(call) {
                    val (_, _, session) = requireUnlockedBase(call)
                    val body = call.receive<ChangePinIn>()
                    withContext(Dispatchers.IO) {
                        vault.changePin(body.oldPin, body.newPin, session)
                    }
                    call.respond(HttpStatusCode.NoContent)
                }
            }

            get("/servers") {
                handleApi(call) {
                    val (store, _) = requireUnlocked(call)
                    call.respond(store.listServers())
                }
            }

            post("/servers") {
                handleApi(call) {
                    val (store, _) = requireUnlocked(call)
                    val body = call.receive<ServerIn>()
                    val created = store.createServer(body)
                    call.respond(HttpStatusCode.Created, created)
                }
            }

            put("/servers/order") {
                handleApi(call) {
                    val (store, _) = requireUnlocked(call)
                    val body = call.receive<ServerOrderIn>()
                    store.reorderServers(body.ids)
                    call.respond(store.listServers())
                }
            }

            post("/servers/test") {
                handleApi(call) {
                    val (store, pool) = requireUnlocked(call)
                    val data = call.receive<ServerTestIn>()
                    val stored = data.id?.let { store.getServerSecrets(it) }
                    val sameEndpoint = stored != null && stored.host == data.host && stored.port == data.port
                    val candidate = ServerSecrets(
                        id = data.id ?: 0,
                        host = data.host,
                        port = data.port,
                        username = data.username,
                        authType = data.authType,
                        password = data.password ?: stored?.password,
                        privateKey = data.privateKey ?: stored?.privateKey,
                        passphrase = data.passphrase ?: stored?.passphrase,
                        hostKey = if (stored != null && sameEndpoint) stored.hostKey else null
                    )
                    val result = try {
                        val fp = pool.testConnection(candidate)
                        ConnectionCheck(ok = true, message = "Berhasil terhubung. Host key: $fp")
                    } catch (e: Throwable) {
                        ConnectionCheck(ok = false, message = sshErrorMessage(e))
                    }
                    call.respond(result)
                }
            }

            put("/servers/{serverId}") {
                handleApi(call) {
                    val (store, pool) = requireUnlocked(call)
                    val serverId = call.parameters["serverId"]?.toIntOrNull()
                        ?: throw HttpError(HttpStatusCode.NotFound, "Server tidak ditemukan")
                    val body = call.receive<ServerIn>()
                    val before = store.getServerSecrets(serverId)
                    val updated = store.updateServer(serverId, body)
                        ?: throw HttpError(HttpStatusCode.NotFound, "Server tidak ditemukan")
                    if (before != store.getServerSecrets(serverId)) {
                        pool.drop(serverId)
                    }
                    call.respond(updated)
                }
            }

            delete("/servers/{serverId}") {
                handleApi(call) {
                    val (store, pool) = requireUnlocked(call)
                    val serverId = call.parameters["serverId"]?.toIntOrNull()
                        ?: throw HttpError(HttpStatusCode.NotFound, "Server tidak ditemukan")
                    if (!store.deleteServer(serverId)) {
                        throw HttpError(HttpStatusCode.NotFound, "Server tidak ditemukan")
                    }
                    pool.drop(serverId)
                    call.respond(HttpStatusCode.NoContent)
                }
            }

            post("/servers/{serverId}/reset-hostkey") {
                handleApi(call) {
                    val (store, pool) = requireUnlocked(call)
                    val serverId = call.parameters["serverId"]?.toIntOrNull()
                        ?: throw HttpError(HttpStatusCode.NotFound, "Server tidak ditemukan")
                    store.getServer(serverId)
                        ?: throw HttpError(HttpStatusCode.NotFound, "Server tidak ditemukan")
                    store.setHostKey(serverId, null, null)
                    pool.drop(serverId)
                    call.respond(store.getServer(serverId)!!)
                }
            }

            get("/servers/{serverId}/stats") {
                handleApi(call) {
                    val (store, pool) = requireUnlocked(call)
                    val serverId = call.parameters["serverId"]?.toIntOrNull()
                        ?: throw HttpError(HttpStatusCode.NotFound, "Server tidak ditemukan")
                    store.getServer(serverId)
                        ?: throw HttpError(HttpStatusCode.NotFound, "Server tidak ditemukan")
                    try {
                        call.respond(pool.stats(serverId))
                    } catch (e: Throwable) {
                        throw HttpError(HttpStatusCode.BadGateway, sshErrorMessage(e))
                    }
                }
            }

            get("/servers/{serverId}/ls") {
                handleApi(call) {
                    val (store, pool) = requireUnlocked(call)
                    val serverId = call.parameters["serverId"]?.toIntOrNull()
                        ?: throw HttpError(HttpStatusCode.NotFound, "Server tidak ditemukan")
                    store.getServer(serverId)
                        ?: throw HttpError(HttpStatusCode.NotFound, "Server tidak ditemukan")
                    val path = call.request.queryParameters["path"] ?: "~"
                    try {
                        call.respond(pool.listdir(serverId, path))
                    } catch (e: RemotePathError) {
                        throw HttpError(HttpStatusCode.BadRequest, e.message ?: "Folder tidak bisa dibaca")
                    } catch (e: Throwable) {
                        throw HttpError(HttpStatusCode.BadGateway, sshErrorMessage(e))
                    }
                }
            }

            post("/servers/{serverId}/paste-image") {
                handleApi(call) {
                    val (store, pool) = requireUnlocked(call)
                    val serverId = call.parameters["serverId"]?.toIntOrNull()
                        ?: throw HttpError(HttpStatusCode.NotFound, "Server tidak ditemukan")
                    store.getServer(serverId)
                        ?: throw HttpError(HttpStatusCode.NotFound, "Server tidak ditemukan")

                    val channel = call.receiveChannel()
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(65536)
                    while (!channel.isClosedForRead) {
                        val n = channel.readAvailable(buf, 0, buf.size)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        if (out.size() > MAX_PASTE_BYTES) {
                            throw HttpError(
                                HttpStatusCode.PayloadTooLarge,
                                "Gambar maksimal ${MAX_PASTE_BYTES / (1024 * 1024)} MB."
                            )
                        }
                    }
                    val data = out.toByteArray()
                    val ext = sniffImageType(data)
                        ?: throw HttpError(
                            HttpStatusCode.UnsupportedMediaType,
                            "Format gambar tidak didukung (PNG, JPEG, GIF, WebP)."
                        )
                    try {
                        val remotePath = pool.uploadPaste(serverId, data, ext)
                        call.respond(PastedImage(path = remotePath))
                    } catch (e: RemotePathError) {
                        throw HttpError(HttpStatusCode.BadRequest, e.message ?: "Gagal menyimpan gambar")
                    } catch (e: Throwable) {
                        throw HttpError(HttpStatusCode.BadGateway, sshErrorMessage(e))
                    }
                }
            }

            get("/servers/{serverId}/files/read") {
                handleApi(call) {
                    val (store, pool) = requireUnlocked(call)
                    val serverId = call.parameters["serverId"]?.toIntOrNull()
                        ?: throw HttpError(HttpStatusCode.NotFound, "Server tidak ditemukan")
                    store.getServer(serverId)
                        ?: throw HttpError(HttpStatusCode.NotFound, "Server tidak ditemukan")
                    val path = call.request.queryParameters["path"]
                        ?: throw HttpError(HttpStatusCode.BadRequest, "Path wajib diisi")
                    try {
                        call.respond(pool.readFile(serverId, path))
                    } catch (e: RemotePathError) {
                        throw HttpError(HttpStatusCode.BadRequest, e.message ?: "File tidak bisa dibaca")
                    } catch (e: Throwable) {
                        throw HttpError(HttpStatusCode.BadGateway, sshErrorMessage(e))
                    }
                }
            }

            put("/servers/{serverId}/files/write") {
                handleApi(call) {
                    val (store, pool) = requireUnlocked(call)
                    val serverId = call.parameters["serverId"]?.toIntOrNull()
                        ?: throw HttpError(HttpStatusCode.NotFound, "Server tidak ditemukan")
                    store.getServer(serverId)
                        ?: throw HttpError(HttpStatusCode.NotFound, "Server tidak ditemukan")
                    val body = call.receive<FileWriteIn>()
                    try {
                        call.respond(pool.writeFile(serverId, body.path, body.content))
                    } catch (e: RemotePathError) {
                        throw HttpError(HttpStatusCode.BadRequest, e.message ?: "Gagal menyimpan file")
                    } catch (e: Throwable) {
                        throw HttpError(HttpStatusCode.BadGateway, sshErrorMessage(e))
                    }
                }
            }

            post("/servers/{serverId}/files/mkdir") {
                handleApi(call) {
                    val (store, pool) = requireUnlocked(call)
                    val serverId = call.parameters["serverId"]?.toIntOrNull()
                        ?: throw HttpError(HttpStatusCode.NotFound, "Server tidak ditemukan")
                    store.getServer(serverId)
                        ?: throw HttpError(HttpStatusCode.NotFound, "Server tidak ditemukan")
                    val body = call.receive<FilePathIn>()
                    try {
                        call.respond(pool.mkdir(serverId, body.path))
                    } catch (e: RemotePathError) {
                        throw HttpError(HttpStatusCode.BadRequest, e.message ?: "Gagal membuat folder")
                    } catch (e: Throwable) {
                        throw HttpError(HttpStatusCode.BadGateway, sshErrorMessage(e))
                    }
                }
            }

            post("/servers/{serverId}/files/rename") {
                handleApi(call) {
                    val (store, pool) = requireUnlocked(call)
                    val serverId = call.parameters["serverId"]?.toIntOrNull()
                        ?: throw HttpError(HttpStatusCode.NotFound, "Server tidak ditemukan")
                    store.getServer(serverId)
                        ?: throw HttpError(HttpStatusCode.NotFound, "Server tidak ditemukan")
                    val body = call.receive<FileRenameIn>()
                    try {
                        call.respond(pool.renameFile(serverId, body.oldPath, body.newPath))
                    } catch (e: RemotePathError) {
                        throw HttpError(HttpStatusCode.BadRequest, e.message ?: "Gagal mengubah nama")
                    } catch (e: Throwable) {
                        throw HttpError(HttpStatusCode.BadGateway, sshErrorMessage(e))
                    }
                }
            }

            post("/servers/{serverId}/files/chmod") {
                handleApi(call) {
                    val (store, pool) = requireUnlocked(call)
                    val serverId = call.parameters["serverId"]?.toIntOrNull()
                        ?: throw HttpError(HttpStatusCode.NotFound, "Server tidak ditemukan")
                    store.getServer(serverId)
                        ?: throw HttpError(HttpStatusCode.NotFound, "Server tidak ditemukan")
                    val body = call.receive<FileChmodIn>()
                    try {
                        call.respond(pool.chmodFile(serverId, body.path, body.mode))
                    } catch (e: RemotePathError) {
                        throw HttpError(HttpStatusCode.BadRequest, e.message ?: "Gagal mengubah izin")
                    } catch (e: IllegalArgumentException) {
                        throw e
                    } catch (e: Throwable) {
                        throw HttpError(HttpStatusCode.BadGateway, sshErrorMessage(e))
                    }
                }
            }

            delete("/servers/{serverId}/files") {
                handleApi(call) {
                    val (store, pool) = requireUnlocked(call)
                    val serverId = call.parameters["serverId"]?.toIntOrNull()
                        ?: throw HttpError(HttpStatusCode.NotFound, "Server tidak ditemukan")
                    store.getServer(serverId)
                        ?: throw HttpError(HttpStatusCode.NotFound, "Server tidak ditemukan")
                    val path = call.request.queryParameters["path"]
                        ?: throw HttpError(HttpStatusCode.BadRequest, "Path wajib diisi")
                    try {
                        pool.deleteFile(serverId, path)
                        call.respond(HttpStatusCode.NoContent)
                    } catch (e: RemotePathError) {
                        throw HttpError(HttpStatusCode.BadRequest, e.message ?: "Gagal menghapus file/folder")
                    } catch (e: IllegalArgumentException) {
                        throw e
                    } catch (e: Throwable) {
                        throw HttpError(HttpStatusCode.BadGateway, sshErrorMessage(e))
                    }
                }
            }

            post("/servers/{serverId}/files/upload") {
                handleApi(call) {
                    val (store, pool) = requireUnlocked(call)
                    val serverId = call.parameters["serverId"]?.toIntOrNull()
                        ?: throw HttpError(HttpStatusCode.NotFound, "Server tidak ditemukan")
                    store.getServer(serverId)
                        ?: throw HttpError(HttpStatusCode.NotFound, "Server tidak ditemukan")
                    val path = call.request.queryParameters["path"]
                        ?: throw HttpError(HttpStatusCode.BadRequest, "Path wajib diisi")

                    val channel = call.receiveChannel()
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(65536)
                    while (!channel.isClosedForRead) {
                        val n = channel.readAvailable(buf, 0, buf.size)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        if (out.size() > MAX_UPLOAD_FILE_BYTES) {
                            throw HttpError(
                                HttpStatusCode.PayloadTooLarge,
                                "Ukuran file maksimal ${MAX_UPLOAD_FILE_BYTES / (1024 * 1024)} MB."
                            )
                        }
                    }
                    try {
                        call.respond(pool.uploadFile(serverId, path, out.toByteArray()))
                    } catch (e: RemotePathError) {
                        throw HttpError(HttpStatusCode.BadRequest, e.message ?: "Gagal mengunggah file")
                    } catch (e: Throwable) {
                        throw HttpError(HttpStatusCode.BadGateway, sshErrorMessage(e))
                    }
                }
            }

            get("/servers/{serverId}/files/download") {
                handleApi(call) {
                    val (store, pool) = requireUnlocked(call)
                    val serverId = call.parameters["serverId"]?.toIntOrNull()
                        ?: throw HttpError(HttpStatusCode.NotFound, "Server tidak ditemukan")
                    store.getServer(serverId)
                        ?: throw HttpError(HttpStatusCode.NotFound, "Server tidak ditemukan")
                    val path = call.request.queryParameters["path"]
                        ?: throw HttpError(HttpStatusCode.BadRequest, "Path wajib diisi")
                    val filename = path.trimEnd('/').substringAfterLast('/').ifEmpty { "download" }
                    try {
                        val bytes = pool.downloadFile(serverId, path)
                        call.response.header(
                            HttpHeaders.ContentDisposition,
                            buildContentDispositionHeader(filename)
                        )
                        call.respondBytes(bytes, contentTypeForFilename(filename))
                    } catch (e: RemotePathError) {
                        throw HttpError(HttpStatusCode.BadRequest, e.message ?: "Gagal mengunduh file")
                    } catch (e: Throwable) {
                        throw HttpError(HttpStatusCode.BadGateway, sshErrorMessage(e))
                    }
                }
            }

            get("/bookmarks") {
                handleApi(call) {
                    val (store, _) = requireUnlocked(call)
                    val serverId = call.request.queryParameters["server_id"]?.toIntOrNull()
                    call.respond(store.listBookmarks(serverId))
                }
            }

            post("/bookmarks") {
                handleApi(call) {
                    val (store, _) = requireUnlocked(call)
                    val body = call.receive<BookmarkIn>()
                    store.getServer(body.serverId)
                        ?: throw HttpError(HttpStatusCode.NotFound, "Server tidak ditemukan")
                    call.respond(HttpStatusCode.Created, store.createBookmark(body))
                }
            }

            put("/bookmarks/{bookmarkId}") {
                handleApi(call) {
                    val (store, _) = requireUnlocked(call)
                    val bookmarkId = call.parameters["bookmarkId"]?.toIntOrNull()
                        ?: throw HttpError(HttpStatusCode.NotFound, "Path tidak ditemukan")
                    val body = call.receive<BookmarkIn>()
                    store.getServer(body.serverId)
                        ?: throw HttpError(HttpStatusCode.NotFound, "Server tidak ditemukan")
                    val updated = store.updateBookmark(bookmarkId, body)
                        ?: throw HttpError(HttpStatusCode.NotFound, "Path tidak ditemukan")
                    call.respond(updated)
                }
            }

            delete("/bookmarks/{bookmarkId}") {
                handleApi(call) {
                    val (store, _) = requireUnlocked(call)
                    val bookmarkId = call.parameters["bookmarkId"]?.toIntOrNull()
                        ?: throw HttpError(HttpStatusCode.NotFound, "Path tidak ditemukan")
                    if (!store.deleteBookmark(bookmarkId)) {
                        throw HttpError(HttpStatusCode.NotFound, "Path tidak ditemukan")
                    }
                    call.respond(HttpStatusCode.NoContent)
                }
            }

            get("/snippets") {
                handleApi(call) {
                    val (store, _) = requireUnlocked(call)
                    call.respond(store.listSnippets())
                }
            }

            post("/snippets") {
                handleApi(call) {
                    val (store, _) = requireUnlocked(call)
                    val body = call.receive<SnippetIn>()
                    call.respond(HttpStatusCode.Created, store.createSnippet(body))
                }
            }

            put("/snippets/{snippetId}") {
                handleApi(call) {
                    val (store, _) = requireUnlocked(call)
                    val snippetId = call.parameters["snippetId"]?.toIntOrNull()
                        ?: throw HttpError(HttpStatusCode.NotFound, "Snippet tidak ditemukan")
                    val body = call.receive<SnippetIn>()
                    val updated = store.updateSnippet(snippetId, body)
                        ?: throw HttpError(HttpStatusCode.NotFound, "Snippet tidak ditemukan")
                    call.respond(updated)
                }
            }

            delete("/snippets/{snippetId}") {
                handleApi(call) {
                    val (store, _) = requireUnlocked(call)
                    val snippetId = call.parameters["snippetId"]?.toIntOrNull()
                        ?: throw HttpError(HttpStatusCode.NotFound, "Snippet tidak ditemukan")
                    if (!store.deleteSnippet(snippetId)) {
                        throw HttpError(HttpStatusCode.NotFound, "Snippet tidak ditemukan")
                    }
                    call.respond(HttpStatusCode.NoContent)
                }
            }

            post("/config/export") {
                handleApi(call) {
                    val (store, _) = requireUnlocked(call)
                    val body = call.receive<BackupExportIn>()
                    if (body.pin.isBlank()) {
                        throw HttpError(HttpStatusCode.UnprocessableEntity, "PIN tidak boleh kosong")
                    }
                    val payload = BackupCrypto.collectStoreBackup(store)
                    val envelope = BackupCrypto.encryptPayload(payload, body.pin)
                    call.respond(envelope)
                }
            }

            post("/config/import") {
                handleApi(call) {
                    val (store, _) = requireUnlocked(call)
                    val body = call.receive<BackupImportIn>()
                    if (body.pin.isBlank()) {
                        throw HttpError(HttpStatusCode.BadRequest, "PIN tidak boleh kosong")
                    }
                    try {
                        val payload = BackupCrypto.decryptEnvelope(body.backup, body.pin)
                        val stats = BackupCrypto.mergeBackupIntoStore(store, payload)
                        call.respond(stats)
                    } catch (e: BackupPinException) {
                        throw HttpError(HttpStatusCode.BadRequest, e.message ?: "PIN salah")
                    } catch (e: BackupCryptoException) {
                        throw HttpError(HttpStatusCode.UnprocessableEntity, e.message ?: "File backup rusak")
                    }
                }
            }

            registerCockpitFilesRoutes(
                assetLoader = assetLoader,
                handleApiFn = { call, block -> handleApi(call, block) },
                requireUnlockedFn = { call -> requireUnlocked(call) },
                ensureServerFn = { store, serverId ->
                    store.getServer(serverId)
                        ?: throw HttpError(HttpStatusCode.NotFound, "Server tidak ditemukan")
                }
            )

            registerContainersRoutes(
                handleApiFn = { call, block -> handleApi(call, block) },
                requireUnlockedFn = { call -> requireUnlocked(call) },
                ensureServerFn = { store, serverId ->
                    store.getServer(serverId)
                        ?: throw HttpError(HttpStatusCode.NotFound, "Server tidak ditemukan")
                }
            )

            registerCloudflareRoutes(
                handleApiFn = { call, block -> handleApi(call, block) },
                requireUnlockedFn = { call -> requireUnlocked(call) },
                cfHttpClient = cfHttpClient
            )

            get("/system/update-check") {
                handleApi(call) {
                    requireUnlocked(call)
                    val result = updater?.checkUpdate() ?: UpdateCheckResult(
                        platform = "android",
                        current_version = "1.0.0",
                        latest_version = "1.0.0",
                        has_update = false,
                        release_title = "TermDeck Android",
                        release_notes = "Aplikasi versi terbaru",
                        download_url = null
                    )
                    call.respond(HttpStatusCode.OK, result)
                }
            }

            post("/system/update-apply") {
                handleApi(call) {
                    requireUnlocked(call)
                    val result = if (updater != null) {
                        val check = updater.checkUpdate()
                        if (check.download_url != null) {
                            updater.startDownload(check.download_url)
                            UpdateApplyResult(started = true, message = "Pengunduhan pembaruan dimulai.")
                        } else {
                            UpdateApplyResult(started = false, message = "URL unduhan APK tidak ditemukan.")
                        }
                    } else {
                        UpdateApplyResult(started = true, message = "Pembaruan dimulai.")
                    }
                    call.respond(HttpStatusCode.OK, result)
                }
            }

            get("/system/update-progress") {
                handleApi(call) {
                    requireUnlocked(call)
                    val result = updater?.getProgress() ?: UpdateProgressResult(
                        state = "idle",
                        progress_percent = 0,
                        downloaded_bytes = 0L,
                        total_bytes = 0L,
                        message = "Tidak ada pembaruan aktif."
                    )
                    call.respond(HttpStatusCode.OK, result)
                }
            }
        }

        webSocket("/ws/terminal") {
            val sessionToken = call.request.queryParameters["session"] ?: ""
            val serverId = call.request.queryParameters["server_id"]?.toIntOrNull()
            val bookmarkId = call.request.queryParameters["bookmark_id"]?.toIntOrNull()
            val cols = call.request.queryParameters["cols"]?.toIntOrNull() ?: 80
            val rows = call.request.queryParameters["rows"]?.toIntOrNull() ?: 24
            val origin = call.request.header(HttpHeaders.Origin)

            val store = vault.store
            val pool = vault.pool
            if (serverId == null ||
                !vault.checkSession(sessionToken) ||
                vault.needsPinChange(sessionToken) ||
                store == null ||
                pool == null ||
                !originAllowed(origin, allowedHosts)
            ) {
                close(CloseReason(4401, "Unauthorized"))
                return@webSocket
            }

            suspend fun sendJson(obj: JsonObject) {
                send(Frame.Text(AppJson.encodeToString(obj)))
            }

            val bookmark = bookmarkId?.let { store.getBookmark(it) }
            sendJson(buildJsonObject {
                put("type", "status")
                put("status", "connecting")
            })

            val term: TerminalSession = try {
                pool.openTerminal(serverId, cols, rows)
            } catch (e: ServerNotFound) {
                sendJson(buildJsonObject {
                    put("type", "error")
                    put("message", e.message ?: "Server tidak ditemukan")
                })
                close()
                return@webSocket
            } catch (e: Throwable) {
                sendJson(buildJsonObject {
                    put("type", "error")
                    put("message", sshErrorMessage(e))
                })
                close()
                return@webSocket
            }

            adjustTerminalCount(1)
            try {
                sendJson(buildJsonObject {
                    put("type", "status")
                    put("status", "connected")
                })

                if (bookmark != null && bookmark.serverId == serverId) {
                    val cmd = buildCdCommand(bookmark.path, bookmark.startupCmd) + "\n"
                    withContext(Dispatchers.IO) {
                        term.outputStream.write(cmd.toByteArray(Charsets.UTF_8))
                        term.outputStream.flush()
                    }
                }

                val outJob = launch(Dispatchers.IO) {
                    val buf = ByteArray(65536)
                    while (isActive) {
                        val n = try {
                            term.inputStream.read(buf)
                        } catch (_: Exception) {
                            -1
                        }
                        if (n <= 0) break
                        send(Frame.Binary(true, buf.copyOf(n)))
                    }
                }

                val inJob = launch {
                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                val raw = frame.readText()
                                val msg = runCatching {
                                    AppJson.parseToJsonElement(raw) as? JsonObject
                                }.getOrNull() ?: continue
                                val type = msg["type"]?.jsonPrimitive?.content
                                if (type == "input") {
                                    val data = msg["data"]?.jsonPrimitive?.content ?: continue
                                    withContext(Dispatchers.IO) {
                                        term.outputStream.write(data.toByteArray(Charsets.UTF_8))
                                        term.outputStream.flush()
                                    }
                                } else if (type == "resize") {
                                    val c = msg["cols"]?.jsonPrimitive?.intOrNull ?: continue
                                    val r = msg["rows"]?.jsonPrimitive?.intOrNull ?: continue
                                    if (c > 0 && r > 0) {
                                        withContext(Dispatchers.IO) {
                                            term.resize(c, r)
                                        }
                                    }
                                }
                            }
                        }
                    } catch (_: ClosedReceiveChannelException) {
                    }
                }

                val outFinishedFirst = select<Boolean> {
                    outJob.onJoin { true }
                    inJob.onJoin { false }
                }

                if (outFinishedFirst) {
                    inJob.cancelAndJoin()
                    sendJson(buildJsonObject {
                        put("type", "exit")
                        put("code", term.exitStatus)
                    })
                    close()
                } else {
                    outJob.cancelAndJoin()
                }
            } catch (e: Throwable) {
                runCatching {
                    sendJson(buildJsonObject {
                        put("type", "error")
                        put("message", sshErrorMessage(e))
                    })
                    close()
                }
            } finally {
                adjustTerminalCount(-1)
                withContext(Dispatchers.IO) {
                    runCatching { term.close() }
                }
                pool.release(serverId)
            }
        }

        // Static UI routes
        suspend fun serveIndex(call: ApplicationCall) {
            checkHost(call)
            val bytes = assetLoader("index.html")
            if (bytes == null) {
                call.respond(HttpStatusCode.NotFound, "UI asset index.html not found")
                return
            }
            val meta = """<meta name="termdeck-token" content="${escapeHtml(token)}">"""
            val html = bytes.toString(Charsets.UTF_8).replaceFirst("<head>", "<head>\n    $meta")
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respondText(html, ContentType.Text.Html)
        }

        get("/") { serveIndex(call) }
        get("/index.html") { serveIndex(call) }

        get("/{path...}") {
            checkHost(call)
            val segments = call.parameters.getAll("path").orEmpty()
            val relPath = segments.joinToString("/")
            if (relPath.isEmpty() || relPath == "index.html") {
                serveIndex(call)
                return@get
            }
            if ( segments.any { it == ".." || it.contains('\\') }) {
                call.respond(HttpStatusCode.BadRequest)
                return@get
            }
            val bytes = assetLoader(relPath)
            if (bytes == null) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            val contentType = contentTypeForPath(relPath)
            call.respondBytes(bytes, contentType)
        }
    }
}

private fun contentTypeForPath(path: String): ContentType {
    return when {
        path.endsWith(".html", ignoreCase = true) -> ContentType.Text.Html
        path.endsWith(".css", ignoreCase = true) -> ContentType.Text.CSS
        path.endsWith(".js", ignoreCase = true) -> ContentType.Application.JavaScript
        path.endsWith(".json", ignoreCase = true) -> ContentType.Application.Json
        path.endsWith(".svg", ignoreCase = true) -> ContentType.Image.SVG
        path.endsWith(".png", ignoreCase = true) -> ContentType.Image.PNG
        path.endsWith(".jpg", ignoreCase = true) || path.endsWith(".jpeg", ignoreCase = true) -> ContentType.Image.JPEG
        path.endsWith(".woff2", ignoreCase = true) -> ContentType("font", "woff2")
        path.endsWith(".woff", ignoreCase = true) -> ContentType("font", "woff")
        path.endsWith(".ttf", ignoreCase = true) -> ContentType("font", "ttf")
        else -> ContentType.Application.OctetStream
    }
}

class TermDeckServer(
    private val vault: Vault,
    private val assetLoader: (String) -> ByteArray?,
    private val onTerminalCountChanged: (Int) -> Unit = {},
    private val updater: AndroidUpdater? = null
) {
    val token: String = generateRandomToken(32)
    private var engine: CIOApplicationEngine? = null

    @Volatile
    var port: Int = 0
        private set

    val baseUrl: String
        get() = "http://127.0.0.1:$port"

    @Synchronized
    fun start(): Int {
        if (engine != null && port > 0) return port
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            configureTermDeckModule(
                vault = vault,
                token = token,
                assetLoader = assetLoader,
                allowedHosts = LOCAL_HOSTS,
                onTerminalCountChanged = onTerminalCountChanged,
                updater = updater
            )
        }
        server.start(wait = false)
        val resolvedPort = runBlocking {
            server.resolvedConnectors().first().port
        }
        engine = server
        port = resolvedPort
        return resolvedPort
    }

    @Synchronized
    fun stop() {
        val current = engine ?: return
        engine = null
        port = 0
        runCatching { current.stop(500, 1000) }
    }

    fun lockVault() {
        runBlocking(Dispatchers.IO) {
            vault.lock()
        }
    }
}
