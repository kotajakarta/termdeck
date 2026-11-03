package id.termdeck.app

import id.termdeck.app.data.AuthStatus
import id.termdeck.app.data.BookmarkIn
import id.termdeck.app.data.BookmarkOut
import id.termdeck.app.data.ChangePinIn
import id.termdeck.app.data.ConnectionCheck
import id.termdeck.app.data.DirEntry
import id.termdeck.app.data.DirListing
import id.termdeck.app.data.FileActionOut
import id.termdeck.app.data.FileChmodIn
import id.termdeck.app.data.FileContentOut
import id.termdeck.app.data.FilePathIn
import id.termdeck.app.data.FileRenameIn
import id.termdeck.app.data.FileWriteIn
import id.termdeck.app.data.LoginIn
import id.termdeck.app.data.LoginOut
import id.termdeck.app.data.PastedImage
import id.termdeck.app.data.ServerIn
import id.termdeck.app.data.ServerOrderIn
import id.termdeck.app.data.ServerOut
import id.termdeck.app.data.ServerSecrets
import id.termdeck.app.data.ServerStats
import id.termdeck.app.data.ServerTestIn
import id.termdeck.app.data.SetupPinIn
import id.termdeck.app.data.SnippetIn
import id.termdeck.app.data.DB_FILE
import id.termdeck.app.data.SALT_FILE
import id.termdeck.app.data.deriveVaultKey
import id.termdeck.app.data.SnippetOut
import id.termdeck.app.data.StoreBackend
import id.termdeck.app.data.Vault
import id.termdeck.app.data.WrongKeyException
import id.termdeck.app.data.CFAccountIn
import id.termdeck.app.data.CFAccountOut
import id.termdeck.app.data.CFAccountUpdateIn
import id.termdeck.app.data.CFAccountVerifyIn
import id.termdeck.app.data.CFAccountWithSecret
import id.termdeck.app.data.CFRouteIn
import id.termdeck.app.data.maskCfToken
import id.termdeck.app.data.validateBookmarkIn
import id.termdeck.app.data.validateCfAccountIn
import id.termdeck.app.data.validateServerIn
import id.termdeck.app.data.validateSnippetIn
import id.termdeck.app.server.AppJson
import id.termdeck.app.server.configureTermDeckModule
import id.termdeck.app.ssh.ExecProcess
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import id.termdeck.app.ssh.ExecResult
import id.termdeck.app.ssh.HostKeyMismatch
import id.termdeck.app.ssh.SshPoolBackend
import id.termdeck.app.ssh.TerminalSession
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class InMemoryStore(
    private val dbFile: File,
    initialKey: ByteArray
) : StoreBackend {
    private var currentKey: ByteArray = initialKey.copyOf()

    init {
        if (dbFile.exists()) {
            val savedHex = dbFile.readText().trim()
            val currentHex = currentKey.joinToString("") { "%02x".format(it) }
            if (savedHex != currentHex) {
                throw WrongKeyException("Wrong key")
            }
        } else {
            dbFile.writeText(currentKey.joinToString("") { "%02x".format(it) })
        }
    }

    private data class ServerRow(
        val id: Int,
        var name: String,
        var host: String,
        var port: Int,
        var username: String,
        var authType: String,
        var password: String?,
        var privateKey: String?,
        var passphrase: String?,
        var groupName: String,
        var color: String,
        var hostKey: String? = null,
        var hostKeyFp: String? = null,
        var sortOrder: Int = 0
    )

    private val servers = mutableListOf<ServerRow>()
    private val bookmarks = mutableListOf<BookmarkOut>()
    private val snippets = mutableListOf<SnippetOut>()
    private var nextServerId = 1
    private var nextBookmarkId = 1
    private var nextSnippetId = 1

    override fun listServers(): List<ServerOut> {
        return servers.sortedWith(compareBy<ServerRow> { it.sortOrder }.thenBy { it.id }).map { it.toOut() }
    }

    override fun getServer(serverId: Int): ServerOut? = servers.find { it.id == serverId }?.toOut()

    override fun getServerSecrets(serverId: Int): ServerSecrets? {
        val r = servers.find { it.id == serverId } ?: return null
        return ServerSecrets(
            id = r.id,
            host = r.host,
            port = r.port,
            username = r.username,
            authType = r.authType,
            password = r.password,
            privateKey = r.privateKey,
            passphrase = r.passphrase,
            hostKey = r.hostKey
        )
    }

    override fun createServer(data: ServerIn): ServerOut {
        validateServerIn(data)
        val sort = (servers.maxOfOrNull { it.sortOrder } ?: -1) + 1
        val row = ServerRow(
            id = nextServerId++,
            name = data.name,
            host = data.host,
            port = data.port,
            username = data.username,
            authType = data.authType,
            password = data.password?.ifEmpty { null },
            privateKey = data.privateKey?.ifEmpty { null },
            passphrase = data.passphrase?.ifEmpty { null },
            groupName = data.groupName,
            color = data.color,
            sortOrder = sort
        )
        servers.add(row)
        return row.toOut()
    }

    override fun updateServer(serverId: Int, data: ServerIn): ServerOut? {
        validateServerIn(data)
        val row = servers.find { it.id == serverId } ?: return null
        val endpointChanged = row.host != data.host || row.port != data.port
        row.name = data.name
        row.host = data.host
        row.port = data.port
        row.username = data.username
        row.authType = data.authType
        data.password?.let { row.password = it.ifEmpty { null } }
        data.privateKey?.let { row.privateKey = it.ifEmpty { null } }
        data.passphrase?.let { row.passphrase = it.ifEmpty { null } }
        row.groupName = data.groupName
        row.color = data.color
        if (endpointChanged) {
            row.hostKey = null
            row.hostKeyFp = null
        }
        return row.toOut()
    }

    override fun reorderServers(ids: List<Int>) {
        val existing = servers.map { it.id }.toSet()
        if (ids.size != ids.toSet().size || ids.toSet() != existing) {
            throw IllegalArgumentException("Urutan harus berisi setiap server tepat satu kali.")
        }
        ids.forEachIndexed { idx, sid ->
            servers.find { it.id == sid }?.sortOrder = idx
        }
    }

    override fun deleteServer(serverId: Int): Boolean {
        val removed = servers.removeIf { it.id == serverId }
        if (removed) {
            bookmarks.removeIf { it.serverId == serverId }
        }
        return removed
    }

    override fun setHostKey(serverId: Int, key: String?, fingerprint: String?) {
        val row = servers.find { it.id == serverId } ?: return
        row.hostKey = key
        row.hostKeyFp = fingerprint
    }

    override fun listBookmarks(serverId: Int?): List<BookmarkOut> {
        val filtered = if (serverId == null) bookmarks else bookmarks.filter { it.serverId == serverId }
        return filtered.sortedBy { it.name.lowercase() }
    }

    override fun getBookmark(bookmarkId: Int): BookmarkOut? = bookmarks.find { it.id == bookmarkId }

    override fun createBookmark(data: BookmarkIn): BookmarkOut {
        validateBookmarkIn(data)
        val b = BookmarkOut(nextBookmarkId++, data.serverId, data.name, data.path, data.startupCmd)
        bookmarks.add(b)
        return b
    }

    override fun updateBookmark(bookmarkId: Int, data: BookmarkIn): BookmarkOut? {
        validateBookmarkIn(data)
        val idx = bookmarks.indexOfFirst { it.id == bookmarkId }
        if (idx < 0) return null
        val b = BookmarkOut(bookmarkId, data.serverId, data.name, data.path, data.startupCmd)
        bookmarks[idx] = b
        return b
    }

    override fun deleteBookmark(bookmarkId: Int): Boolean = bookmarks.removeIf { it.id == bookmarkId }

    override fun listSnippets(): List<SnippetOut> = snippets.sortedBy { it.name.lowercase() }

    override fun getSnippet(snippetId: Int): SnippetOut? = snippets.find { it.id == snippetId }

    override fun createSnippet(data: SnippetIn): SnippetOut {
        validateSnippetIn(data)
        val s = SnippetOut(nextSnippetId++, data.name, data.command)
        snippets.add(s)
        return s
    }

    override fun updateSnippet(snippetId: Int, data: SnippetIn): SnippetOut? {
        validateSnippetIn(data)
        val idx = snippets.indexOfFirst { it.id == snippetId }
        if (idx < 0) return null
        val s = SnippetOut(snippetId, data.name, data.command)
        snippets[idx] = s
        return s
    }

    override fun deleteSnippet(snippetId: Int): Boolean = snippets.removeIf { it.id == snippetId }

    private data class CfAccountRow(
        val id: Int,
        var name: String,
        var token: String,
        var accountId: String,
        var accountName: String,
        val createdAt: String = "2026-11-02 12:00:00"
    ) {
        fun toOut() = CFAccountOut(
            id = id,
            name = name,
            tokenMasked = maskCfToken(token),
            accountId = accountId,
            accountName = accountName,
            createdAt = createdAt
        )
        fun toSecret() = CFAccountWithSecret(
            id = id,
            name = name,
            token = token,
            tokenMasked = maskCfToken(token),
            accountId = accountId,
            accountName = accountName,
            createdAt = createdAt
        )
    }

    private val cfAccounts = mutableListOf<CfAccountRow>()
    private var nextCfAccountId = 1

    override fun listCfAccounts(): List<CFAccountOut> = cfAccounts.map { it.toOut() }

    override fun getCfAccount(id: Int): CFAccountWithSecret? = cfAccounts.find { it.id == id }?.toSecret()

    override fun createCfAccount(name: String, token: String, accountId: String, accountName: String): CFAccountOut {
        validateCfAccountIn(name, token)
        val row = CfAccountRow(nextCfAccountId++, name, token, accountId, accountName)
        cfAccounts.add(row)
        return row.toOut()
    }

    override fun updateCfAccount(id: Int, name: String, token: String?, accountId: String?, accountName: String?): CFAccountOut? {
        val row = cfAccounts.find { it.id == id } ?: return null
        val cleanToken = token?.trim()?.ifEmpty { null }
        if (cleanToken != null) {
            validateCfAccountIn(name, cleanToken)
            row.token = cleanToken
            if (accountId != null) row.accountId = accountId
            if (accountName != null) row.accountName = accountName
        } else {
            require(name.isNotBlank() && name.length <= 100) { "Nama akun Cloudflare wajib diisi." }
        }
        row.name = name.trim()
        return row.toOut()
    }

    override fun deleteCfAccount(id: Int): Boolean = cfAccounts.removeIf { it.id == id }

    override fun rekey(newKey: ByteArray) {
        currentKey = newKey.copyOf()
        dbFile.writeText(currentKey.joinToString("") { "%02x".format(it) })
    }

    override fun close() {}

    private fun ServerRow.toOut() = ServerOut(
        id = id,
        name = name,
        host = host,
        port = port,
        username = username,
        authType = authType,
        groupName = groupName,
        color = color,
        hasPassword = password != null,
        hasPrivateKey = privateKey != null,
        hostKeyFingerprint = hostKeyFp
    )
}

class FakeSshPool : SshPoolBackend {
    var droppedServers = mutableListOf<Int>()
    var failWithMismatch = false

    override suspend fun testConnection(secrets: ServerSecrets): String {
        if (failWithMismatch) {
            throw HostKeyMismatch("SHA256:old", "SHA256:new")
        }
        return "SHA256:testfp123"
    }

    override suspend fun stats(serverId: Int): ServerStats {
        return ServerStats(hostname = "node-$serverId", os = "Ubuntu", kernel = "Linux 6.8", cpuCount = 4)
    }

    override suspend fun listdir(serverId: Int, path: String): DirListing {
        return DirListing(
            path = "/home/deploy",
            entries = listOf(DirEntry(name = "app", path = "/home/deploy/app", isDir = true))
        )
    }

    override suspend fun uploadPaste(serverId: Int, data: ByteArray, ext: String): String {
        return "/home/deploy/.cache/termdeck/paste/paste-123.$ext"
    }

    val files = mutableMapOf("/var/www/.env" to "PORT=8080\n".toByteArray())
    val modes = mutableMapOf("/var/www/.env" to "0600")
    val dirs = mutableSetOf("/var/www")

    override suspend fun readFile(serverId: Int, path: String): FileContentOut {
        val bytes = files[path] ?: error("File not found: $path")
        return FileContentOut(
            path = path,
            content = bytes.toString(Charsets.UTF_8),
            size = bytes.size.toLong(),
            mtime = 1700000000L,
            mode = modes[path] ?: "0644"
        )
    }

    override suspend fun writeFile(serverId: Int, path: String, content: String): FileActionOut {
        files[path] = content.toByteArray(Charsets.UTF_8)
        return FileActionOut(path = path, message = "File disimpan")
    }

    override suspend fun mkdir(serverId: Int, path: String): FileActionOut {
        dirs.add(path)
        return FileActionOut(path = path, message = "Folder dibuat")
    }

    override suspend fun renameFile(serverId: Int, oldPath: String, newPath: String): FileActionOut {
        val data = files.remove(oldPath)
        if (data != null) files[newPath] = data
        return FileActionOut(path = newPath, message = "Nama diubah")
    }

    override suspend fun chmodFile(serverId: Int, path: String, mode: String): FileActionOut {
        modes[path] = mode
        return FileActionOut(path = path, message = "Izin diubah")
    }

    override suspend fun deleteFile(serverId: Int, path: String) {
        files.remove(path)
        dirs.remove(path)
    }

    override suspend fun uploadFile(serverId: Int, path: String, data: ByteArray): FileActionOut {
        files[path] = data
        return FileActionOut(path = path, message = "File diunggah")
    }

    override suspend fun downloadFile(serverId: Int, path: String): ByteArray {
        return files[path] ?: ByteArray(0)
    }

    override suspend fun execCommand(
        serverId: Int,
        command: String,
        stdinText: String,
        timeoutMs: Long
    ): ExecResult {
        if (command.contains("podman --version")) {
            return ExecResult(stdout = "podman version 5.8.2", stderr = "", exitStatus = 0)
        }
        if (command.contains("podman info")) {
            return ExecResult(stdout = "true", stderr = "", exitStatus = 0)
        }
        if (command.contains("podman ps")) {
            val sample = """
            [
              {
                "Id": "1076caca2d7536d6cf4972ec69ca915f821289d9a3c14be0045f1ff1e5e94389",
                "Names": ["test_nginx"],
                "Image": "docker.io/library/nginx:1.27-alpine",
                "State": "running",
                "Status": "Up 2 minutes",
                "Created": 1793193641,
                "Command": ["sleep", "60"],
                "Ports": [
                  {
                    "host_ip": "127.0.0.1",
                    "container_port": 80,
                    "host_port": 19999,
                    "protocol": "tcp"
                  }
                ]
              }
            ]
            """.trimIndent()
            return ExecResult(stdout = sample, stderr = "", exitStatus = 0)
        }
        if (command.contains("podman images")) {
            val sample = """
            [
              {
                "Id": "6769dc3a703c719c1d2756bda113659be28ae16cf0da58dd5fd823d6b9a050ea",
                "Names": ["docker.io/library/nginx:1.27-alpine"],
                "Size": 49659388,
                "Created": 1744815031
              }
            ]
            """.trimIndent()
            return ExecResult(stdout = sample, stderr = "", exitStatus = 0)
        }
        val mockOut = """{"ok":true,"result":{"path":"/var/www","real_path":"/var/www","home":"/root","entries":[{"name":".env","type":"file","size":10,"mode":416,"mode_octal":"0640","perm_str":"-rw-r-----","uid":0,"gid":0,"owner":"root","group":"root","mtime":1700000000.0,"atime":1700000000.0,"ctime":1700000000.0,"target":null,"target_type":null,"category":"code"}]}}"""
        return ExecResult(
            stdout = mockOut,
            stderr = "",
            exitStatus = 0
        )
    }

    override suspend fun openProcess(serverId: Int, command: String): ExecProcess {
        return object : ExecProcess {
            override val inputStream: InputStream = ByteArrayInputStream(ByteArray(0))
            override val errorStream: InputStream = ByteArrayInputStream(ByteArray(0))
            override val outputStream: OutputStream = ByteArrayOutputStream()
            override fun join(timeoutMs: Long): Int = 0
            override fun close() {}
        }
    }

    override suspend fun openTerminal(serverId: Int, cols: Int, rows: Int): TerminalSession {
        return object : TerminalSession {
            override val inputStream: InputStream = ByteArrayInputStream("hello\r\n".toByteArray())
            override val outputStream: OutputStream = ByteArrayOutputStream()
            override val exitStatus: Int = 0
            override fun resize(cols: Int, rows: Int) {}
            override fun close() {}
        }
    }

    override suspend fun release(serverId: Int) {}

    override suspend fun drop(serverId: Int) {
        droppedServers.add(serverId)
    }

    override suspend fun closeAll() {}
}

class ServerProtocolTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `auth flow enforces token PIN login lock and change-pin`() = testApplication {
        val dir = tempFolder.newFolder("vault1")
        val fakePool = FakeSshPool()
        val vault = Vault(
            folder = dir,
            initialPin = "7821724",
            storeFactory = { f, k -> InMemoryStore(f, k) },
            poolFactory = { fakePool }
        )
        val token = "test-secret-token"

        application {
            configureTermDeckModule(
                vault = vault,
                token = token,
                assetLoader = { path ->
                    if (path == "index.html") "<html><head></head><body>TermDeck</body></html>".toByteArray() else null
                },
                allowedHosts = null
            )
        }

        // Index injects meta token
        val indexRes = client.get("/")
        assertEquals(HttpStatusCode.OK, indexRes.status)
        assertTrue(indexRes.bodyAsText().contains("""<meta name="termdeck-token" content="test-secret-token">"""))

        // Missing X-Token -> 401
        val noTokenRes = client.get("/api/auth/status")
        assertEquals(HttpStatusCode.Unauthorized, noTokenRes.status)

        // Status before setup -> initialized = false
        val preStatusRes = client.get("/api/auth/status") {
            header("X-Token", token)
        }
        val preStatus = AppJson.decodeFromString<AuthStatus>(preStatusRes.bodyAsText())
        assertFalse(preStatus.initialized)
        assertFalse(preStatus.unlocked)

        // Login before setup -> 400
        val prematureLogin = client.post("/api/auth/login") {
            header("X-Token", token)
            contentType(ContentType.Application.Json)
            setBody(AppJson.encodeToString(LoginIn("123456")))
        }
        assertEquals(HttpStatusCode.BadRequest, prematureLogin.status)

        // Setup with default PIN 7821724 -> 422
        val badSetup = client.post("/api/auth/setup-pin") {
            header("X-Token", token)
            contentType(ContentType.Application.Json)
            setBody(AppJson.encodeToString(SetupPinIn("7821724")))
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, badSetup.status)

        // Setup with valid custom PIN -> 200 + session
        val okSetup = client.post("/api/auth/setup-pin") {
            header("X-Token", token)
            contentType(ContentType.Application.Json)
            setBody(AppJson.encodeToString(SetupPinIn("123456")))
        }
        assertEquals(HttpStatusCode.OK, okSetup.status)
        val setupOut = AppJson.decodeFromString<LoginOut>(okSetup.bodyAsText())
        val session = setupOut.session
        assertTrue(session.isNotBlank())
        assertFalse(setupOut.mustChangePin)

        // Status shows unlocked + session_valid + initialized
        val statusRes = client.get("/api/auth/status") {
            header("X-Token", token)
            header("X-Session", session)
        }
        val status = AppJson.decodeFromString<AuthStatus>(statusRes.bodyAsText())
        assertTrue(status.unlocked)
        assertTrue(status.sessionValid)
        assertTrue(status.initialized)
        assertFalse(status.mustChangePin)

        // Duplicate setup rejected -> 400
        val dupSetup = client.post("/api/auth/setup-pin") {
            header("X-Token", token)
            contentType(ContentType.Application.Json)
            setBody(AppJson.encodeToString(SetupPinIn("654321")))
        }
        assertEquals(HttpStatusCode.BadRequest, dupSetup.status)

        // Change PIN to 654321
        val changeRes = client.post("/api/auth/change-pin") {
            header("X-Token", token)
            header("X-Session", session)
            contentType(ContentType.Application.Json)
            setBody(AppJson.encodeToString(ChangePinIn("123456", "654321")))
        }
        assertEquals(HttpStatusCode.NoContent, changeRes.status)

        // Lock vault
        val lockRes = client.post("/api/auth/lock") {
            header("X-Token", token)
            header("X-Session", session)
        }
        assertEquals(HttpStatusCode.NoContent, lockRes.status)

        // Old PIN now fails, new PIN succeeds
        val oldPinRes = client.post("/api/auth/login") {
            header("X-Token", token)
            contentType(ContentType.Application.Json)
            setBody(AppJson.encodeToString(LoginIn("123456")))
        }
        assertEquals(HttpStatusCode.Forbidden, oldPinRes.status)

        val newPinRes = client.post("/api/auth/login") {
            header("X-Token", token)
            contentType(ContentType.Application.Json)
            setBody(AppJson.encodeToString(LoginIn("654321")))
        }
        assertEquals(HttpStatusCode.OK, newPinRes.status)
    }

    @Test
    fun `legacy default PIN enforces must_change_pin and 403 on data routes`() = testApplication {
        val dir = tempFolder.newFolder("vault_legacy")
        val fakePool = FakeSshPool()
        // Pre-create database with legacy PIN 7821724
        val dbFile = File(dir, DB_FILE)
        val salt = ByteArray(32) { it.toByte() }
        File(dir, SALT_FILE).writeBytes(salt)
        val key = deriveVaultKey("7821724", salt)
        val memStore = InMemoryStore(dbFile, key)
        memStore.createServer(ServerIn(name = "srv1", host = "10.0.0.1", username = "root"))

        val vault = Vault(
            folder = dir,
            storeFactory = { f, k -> InMemoryStore(f, k) },
            poolFactory = { fakePool }
        )
        val token = "test-secret-token"

        application {
            configureTermDeckModule(
                vault = vault,
                token = token,
                assetLoader = { null },
                allowedHosts = null
            )
        }

        // Login with legacy default PIN
        val loginRes = client.post("/api/auth/login") {
            header("X-Token", token)
            contentType(ContentType.Application.Json)
            setBody(AppJson.encodeToString(LoginIn("7821724")))
        }
        assertEquals(HttpStatusCode.OK, loginRes.status)
        val loginOut = AppJson.decodeFromString<LoginOut>(loginRes.bodyAsText())
        assertTrue(loginOut.mustChangePin)
        val session = loginOut.session

        // Status shows mustChangePin = true
        val statusRes = client.get("/api/auth/status") {
            header("X-Token", token)
            header("X-Session", session)
        }
        val status = AppJson.decodeFromString<AuthStatus>(statusRes.bodyAsText())
        assertTrue(status.mustChangePin)

        // Accessing data route -> 403 Forbidden
        val serversRes = client.get("/api/servers") {
            header("X-Token", token)
            header("X-Session", session)
        }
        assertEquals(HttpStatusCode.Forbidden, serversRes.status)
        assertTrue(serversRes.bodyAsText().contains("Wajib mengganti PIN"))

        // Change PIN to 987654
        val changeRes = client.post("/api/auth/change-pin") {
            header("X-Token", token)
            header("X-Session", session)
            contentType(ContentType.Application.Json)
            setBody(AppJson.encodeToString(ChangePinIn("7821724", "987654")))
        }
        assertEquals(HttpStatusCode.NoContent, changeRes.status)

        // Status shows mustChangePin = false
        val statusAfterRes = client.get("/api/auth/status") {
            header("X-Token", token)
            header("X-Session", session)
        }
        assertFalse(AppJson.decodeFromString<AuthStatus>(statusAfterRes.bodyAsText()).mustChangePin)

        // Data routes now accessible -> 200 OK
        val serversAfterRes = client.get("/api/servers") {
            header("X-Token", token)
            header("X-Session", session)
        }
        assertEquals(HttpStatusCode.OK, serversAfterRes.status)
        assertEquals("[]", serversAfterRes.bodyAsText().trim())
    }

    @Test
    fun `CRUD servers bookmarks snippets paste-image and secret merging`() = testApplication {
        val dir = tempFolder.newFolder("vault2")
        val fakePool = FakeSshPool()
        val vault = Vault(
            folder = dir,
            storeFactory = { f, k -> InMemoryStore(f, k) },
            poolFactory = { fakePool }
        )
        val token = "tok-2"

        application {
            configureTermDeckModule(vault = vault, token = token, allowedHosts = null)
        }

        val session = AppJson.decodeFromString<LoginOut>(
            client.post("/api/auth/setup-pin") {
                header("X-Token", token)
                contentType(ContentType.Application.Json)
                setBody(AppJson.encodeToString(SetupPinIn("123456")))
            }.bodyAsText()
        ).session

        // Create server
        val createSrv = client.post("/api/servers") {
            header("X-Token", token)
            header("X-Session", session)
            contentType(ContentType.Application.Json)
            setBody(
                AppJson.encodeToString(
                    ServerIn(
                        name = "Prod",
                        host = "10.0.0.1",
                        port = 22,
                        username = "root",
                        authType = "password",
                        password = "secret-password",
                        groupName = "Cloud"
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Created, createSrv.status)
        val srv1 = AppJson.decodeFromString<ServerOut>(createSrv.bodyAsText())
        assertTrue(srv1.hasPassword)
        assertFalse(srv1.hasPrivateKey)

        // Set a host key and verify updating name only keeps password & host key without dropping connection
        vault.store!!.setHostKey(srv1.id, "AAAAB3Nza...", "SHA256:xyz")
        val renameRes = client.put("/api/servers/${srv1.id}") {
            header("X-Token", token)
            header("X-Session", session)
            contentType(ContentType.Application.Json)
            setBody(
                AppJson.encodeToString(
                    ServerIn(
                        name = "Prod Renamed",
                        host = "10.0.0.1",
                        port = 22,
                        username = "root",
                        authType = "password",
                        password = null
                    )
                )
            )
        }
        val renamed = AppJson.decodeFromString<ServerOut>(renameRes.bodyAsText())
        assertEquals("Prod Renamed", renamed.name)
        assertTrue(renamed.hasPassword)
        assertEquals("SHA256:xyz", renamed.hostKeyFingerprint)
        assertTrue(fakePool.droppedServers.isEmpty())

        // Changing host clears host key and drops connection
        val hostChangeRes = client.put("/api/servers/${srv1.id}") {
            header("X-Token", token)
            header("X-Session", session)
            contentType(ContentType.Application.Json)
            setBody(
                AppJson.encodeToString(
                    ServerIn(
                        name = "Prod Renamed",
                        host = "10.0.0.2",
                        port = 22,
                        username = "root",
                        authType = "password",
                        password = ""
                    )
                )
            )
        }
        val hostChanged = AppJson.decodeFromString<ServerOut>(hostChangeRes.bodyAsText())
        assertFalse(hostChanged.hasPassword)
        assertNull(hostChanged.hostKeyFingerprint)
        assertTrue(fakePool.droppedServers.contains(srv1.id))

        // Test connection endpoint with HostKeyMismatch
        fakePool.failWithMismatch = true
        val checkMismatch = client.post("/api/servers/test") {
            header("X-Token", token)
            header("X-Session", session)
            contentType(ContentType.Application.Json)
            setBody(
                AppJson.encodeToString(
                    ServerTestIn(id = srv1.id, name = "Prod", host = "10.0.0.2", username = "root")
                )
            )
        }
        val checkOut = AppJson.decodeFromString<ConnectionCheck>(checkMismatch.bodyAsText())
        assertFalse(checkOut.ok)
        assertTrue(checkOut.message.contains("Host key server BERUBAH!"))

        // Bookmarks & Snippets
        val bmRes = client.post("/api/bookmarks") {
            header("X-Token", token)
            header("X-Session", session)
            contentType(ContentType.Application.Json)
            setBody(AppJson.encodeToString(BookmarkIn(srv1.id, "Web", "/var/www", "git status")))
        }
        assertEquals(HttpStatusCode.Created, bmRes.status)

        val snipRes = client.post("/api/snippets") {
            header("X-Token", token)
            header("X-Session", session)
            contentType(ContentType.Application.Json)
            setBody(AppJson.encodeToString(SnippetIn("Docker PS", "docker ps --format table")))
        }
        assertEquals(HttpStatusCode.Created, snipRes.status)

        // Paste PNG image
        val pngBytes = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(8)
        val pasteRes = client.post("/api/servers/${srv1.id}/paste-image") {
            header("X-Token", token)
            header("X-Session", session)
            setBody(pngBytes)
        }
        assertEquals(HttpStatusCode.OK, pasteRes.status)
        val pasted = AppJson.decodeFromString<PastedImage>(pasteRes.bodyAsText())
        assertTrue(pasted.path.endsWith(".png"))

        // Cockpit File Manager endpoints: read, write, chmod, mkdir, rename, download, delete
        val readRes = client.get("/api/servers/${srv1.id}/files/read?path=/var/www/.env") {
            header("X-Token", token)
            header("X-Session", session)
        }
        assertEquals(HttpStatusCode.OK, readRes.status)
        val fileContent = AppJson.decodeFromString<FileContentOut>(readRes.bodyAsText())
        assertEquals("PORT=8080\n", fileContent.content)
        assertEquals("0600", fileContent.mode)

        val writeRes = client.put("/api/servers/${srv1.id}/files/write") {
            header("X-Token", token)
            header("X-Session", session)
            contentType(ContentType.Application.Json)
            setBody(AppJson.encodeToString(FileWriteIn("/var/www/.env", "PORT=9000\n")))
        }
        assertEquals(HttpStatusCode.OK, writeRes.status)

        val chmodRes = client.post("/api/servers/${srv1.id}/files/chmod") {
            header("X-Token", token)
            header("X-Session", session)
            contentType(ContentType.Application.Json)
            setBody(AppJson.encodeToString(FileChmodIn("/var/www/.env", "0640")))
        }
        assertEquals(HttpStatusCode.OK, chmodRes.status)
        assertEquals("0640", fakePool.modes["/var/www/.env"])

        val mkdirRes = client.post("/api/servers/${srv1.id}/files/mkdir") {
            header("X-Token", token)
            header("X-Session", session)
            contentType(ContentType.Application.Json)
            setBody(AppJson.encodeToString(FilePathIn("/var/www/releases")))
        }
        assertEquals(HttpStatusCode.OK, mkdirRes.status)
        assertTrue(fakePool.dirs.contains("/var/www/releases"))

        val renameFileRes = client.post("/api/servers/${srv1.id}/files/rename") {
            header("X-Token", token)
            header("X-Session", session)
            contentType(ContentType.Application.Json)
            setBody(AppJson.encodeToString(FileRenameIn("/var/www/.env", "/var/www/.env.bak")))
        }
        assertEquals(HttpStatusCode.OK, renameFileRes.status)

        val dlRes = client.get("/api/servers/${srv1.id}/files/download?path=/var/www/.env.bak&token=$token&session=$session")
        assertEquals(HttpStatusCode.OK, dlRes.status)
        assertEquals("PORT=9000\n", dlRes.bodyAsText())

        val delRes = client.delete("/api/servers/${srv1.id}/files?path=/var/www/.env.bak") {
            header("X-Token", token)
            header("X-Session", session)
        }
        assertEquals(HttpStatusCode.NoContent, delRes.status)

        // Cockpit-py style /api/files/{serverId}/list & /api/files/bookmarks
        val filesListRes = client.get("/api/files/${srv1.id}/list?path=/var/www") {
            header("X-Token", token)
            header("X-Session", session)
        }
        assertEquals(HttpStatusCode.OK, filesListRes.status)
        assertTrue(filesListRes.bodyAsText().contains(""""real_path":"/var/www""""))

        val toggleBmRes = client.post("/api/files/bookmarks/toggle") {
            header("X-Token", token)
            header("X-Session", session)
            contentType(ContentType.Application.Json)
            setBody("""{"server_id":${srv1.id},"path":"/var/www/app"}""")
        }
        assertEquals(HttpStatusCode.OK, toggleBmRes.status)
        assertTrue(toggleBmRes.bodyAsText().contains("/var/www/app"))

        // Containers routes
        val engineRes = client.get("/api/containers/engine?server_id=${srv1.id}") {
            header("X-Token", token)
            header("X-Session", session)
        }
        assertEquals(HttpStatusCode.OK, engineRes.status)
        assertTrue(engineRes.bodyAsText().contains(""""engine":"podman""""))

        val containersListRes = client.get("/api/containers/list?server_id=${srv1.id}&all=true") {
            header("X-Token", token)
            header("X-Session", session)
        }
        assertEquals(HttpStatusCode.OK, containersListRes.status)
        assertTrue(containersListRes.bodyAsText().contains(""""id":"1076caca2d75""""))
        assertTrue(containersListRes.bodyAsText().contains(""""command":"sleep 60""""))

        val imagesRes = client.get("/api/containers/images?server_id=${srv1.id}") {
            header("X-Token", token)
            header("X-Session", session)
        }
        assertEquals(HttpStatusCode.OK, imagesRes.status)
        assertTrue(imagesRes.bodyAsText().contains(""""repository":"docker.io/library/nginx""""))
    }

    @Test
    fun `cloudflare routes verify create list update tunnels zones and routes`() = testApplication {
        val dir = tempFolder.newFolder("vault_cf")
        val fakePool = FakeSshPool()
        val vault = Vault(
            folder = dir,
            initialPin = "7821724",
            storeFactory = { f, k -> InMemoryStore(f, k) },
            poolFactory = { fakePool }
        )
        val token = "test-token-cf"

        val mockCfAccounts = """{"success":true,"result":[{"id":"acc-123","name":"My Cloudflare Account"}]}"""
        val mockCfZones = """{"success":true,"result":[{"id":"zone-xyz","name":"example.com","status":"active"}]}"""
        val mockCfDns = """{"success":true,"result":[]}"""
        val mockCfTunnels = """{"success":true,"result":[{"id":"tun-abc","name":"termdeck-tun","status":"healthy","connections":[{"colo_name":"cgk01"}],"created_at":"2026-01-01T00:00:00Z","remote_config":true}]}"""
        val mockCfConfig = """{"success":true,"result":{"config":{"ingress":[{"hostname":"old.example.com","service":"http://localhost:3000"},{"service":"http_status:404"}]}}}"""
        val mockCfToken = """{"success":true,"result":"cf-tunnel-token-secret-123"}"""

        val mockHttpClient: suspend (String, String, String, String?) -> kotlinx.serialization.json.JsonElement = { _, path, _, _ ->
            when {
                path == "/accounts" -> AppJson.parseToJsonElement(mockCfAccounts)
                path.startsWith("/zones") && path.contains("dns_records") -> AppJson.parseToJsonElement(mockCfDns)
                path.startsWith("/zones") -> AppJson.parseToJsonElement(mockCfZones)
                path.contains("/cfd_tunnel") && path.endsWith("/token") -> AppJson.parseToJsonElement(mockCfToken)
                path.contains("/cfd_tunnel") && path.endsWith("/configurations") -> AppJson.parseToJsonElement(mockCfConfig)
                path.contains("/cfd_tunnel") -> AppJson.parseToJsonElement(mockCfTunnels)
                else -> AppJson.parseToJsonElement("""{"success":true,"result":[]}""")
            }
        }

        application {
            configureTermDeckModule(
                vault = vault,
                token = token,
                allowedHosts = null,
                cfHttpClient = mockHttpClient
            )
        }

        // Setup PIN first
        val setupRes = client.post("/api/auth/setup-pin") {
            header("X-Token", token)
            contentType(ContentType.Application.Json)
            setBody(AppJson.encodeToString(SetupPinIn("123456")))
        }
        val session = AppJson.decodeFromString<LoginOut>(setupRes.bodyAsText()).session

        // 1. Verify token
        val verifyRes = client.post("/api/cf/accounts/verify") {
            header("X-Token", token)
            header("X-Session", session)
            contentType(ContentType.Application.Json)
            setBody("""{"token":"mock_cf_token_testing_only_4860"}""")
        }
        assertEquals(HttpStatusCode.OK, verifyRes.status)
        val verifyJson = AppJson.parseToJsonElement(verifyRes.bodyAsText()).jsonObject
        assertEquals("acc-123", verifyJson["account_id"]?.jsonPrimitive?.content)
        assertEquals("My Cloudflare Account", verifyJson["account_name"]?.jsonPrimitive?.content)
        assertTrue(verifyJson["has_dns_access"]?.jsonPrimitive?.booleanOrNull == true)

        // 2. Create CF Account
        val createRes = client.post("/api/cf/accounts") {
            header("X-Token", token)
            header("X-Session", session)
            contentType(ContentType.Application.Json)
            setBody(AppJson.encodeToString(CFAccountIn(name = "Main Org", token = "mock_cf_token_testing_only_4860")))
        }
        assertEquals(HttpStatusCode.OK, createRes.status)
        val createdAcc = AppJson.decodeFromString<CFAccountOut>(createRes.bodyAsText())
        assertEquals("Main Org", createdAcc.name)
        assertEquals("acc-123", createdAcc.accountId)
        assertEquals("mock_...4860", createdAcc.tokenMasked)

        // 3. List CF Accounts
        val listRes = client.get("/api/cf/accounts") {
            header("X-Token", token)
            header("X-Session", session)
        }
        assertEquals(HttpStatusCode.OK, listRes.status)
        val accs = AppJson.decodeFromString<List<CFAccountOut>>(listRes.bodyAsText())
        assertEquals(1, accs.size)
        assertEquals(createdAcc.id, accs[0].id)

        // 4. Update CF Account
        val updateRes = client.put("/api/cf/accounts/${createdAcc.id}") {
            header("X-Token", token)
            header("X-Session", session)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Renamed Org"}""")
        }
        assertEquals(HttpStatusCode.OK, updateRes.status)
        val updatedAcc = AppJson.decodeFromString<CFAccountOut>(updateRes.bodyAsText())
        assertEquals("Renamed Org", updatedAcc.name)

        // 5. List Tunnels
        val tunRes = client.get("/api/cf/accounts/${createdAcc.id}/tunnels") {
            header("X-Token", token)
            header("X-Session", session)
        }
        assertEquals(HttpStatusCode.OK, tunRes.status)
        assertTrue(tunRes.bodyAsText().contains("termdeck-tun"))

        // 6. List Zones
        val zoneRes = client.get("/api/cf/accounts/${createdAcc.id}/zones") {
            header("X-Token", token)
            header("X-Session", session)
        }
        assertEquals(HttpStatusCode.OK, zoneRes.status)
        assertTrue(zoneRes.bodyAsText().contains("example.com"))

        // 7. List Tunnel Routes
        val routesRes = client.get("/api/cf/accounts/${createdAcc.id}/tunnels/tun-abc/routes") {
            header("X-Token", token)
            header("X-Session", session)
        }
        assertEquals(HttpStatusCode.OK, routesRes.status)
        assertTrue(routesRes.bodyAsText().contains("old.example.com"))

        // 8. Add Tunnel Route
        val addRouteRes = client.post("/api/cf/accounts/${createdAcc.id}/tunnels/tun-abc/routes") {
            header("X-Token", token)
            header("X-Session", session)
            contentType(ContentType.Application.Json)
            setBody(AppJson.encodeToString(CFRouteIn(hostname = "term.example.com", service = "http://localhost:8080", zoneId = "zone-xyz")))
        }
        assertEquals(HttpStatusCode.OK, addRouteRes.status)
        assertTrue(addRouteRes.bodyAsText().contains("term.example.com"))

        // 9. Delete Tunnel Route
        val delRouteRes = client.delete("/api/cf/accounts/${createdAcc.id}/tunnels/tun-abc/routes?hostname=term.example.com&zone_id=zone-xyz") {
            header("X-Token", token)
            header("X-Session", session)
        }
        assertEquals(HttpStatusCode.OK, delRouteRes.status)
        assertTrue(delRouteRes.bodyAsText().contains(""""deleted":"term.example.com""""))

        // 10. Get Tunnel Token
        val tokenRes = client.get("/api/cf/accounts/${createdAcc.id}/tunnels/tun-abc/token") {
            header("X-Token", token)
            header("X-Session", session)
        }
        assertEquals(HttpStatusCode.OK, tokenRes.status)
        assertTrue(tokenRes.bodyAsText().contains("cf-tunnel-token-secret-123"))

        // 11. Delete Account
        val delAccRes = client.delete("/api/cf/accounts/${createdAcc.id}") {
            header("X-Token", token)
            header("X-Session", session)
        }
        assertEquals(HttpStatusCode.OK, delAccRes.status)
        val emptyListRes = client.get("/api/cf/accounts") {
            header("X-Token", token)
            header("X-Session", session)
        }
        val emptyAccs = AppJson.decodeFromString<List<CFAccountOut>>(emptyListRes.bodyAsText())
        assertEquals(0, emptyAccs.size)
    }
}
