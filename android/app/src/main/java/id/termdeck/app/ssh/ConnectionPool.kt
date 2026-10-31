package id.termdeck.app.ssh

import id.termdeck.app.data.DirEntry
import id.termdeck.app.data.DirListing
import id.termdeck.app.data.FileActionOut
import id.termdeck.app.data.FileContentOut
import id.termdeck.app.data.ServerSecrets
import id.termdeck.app.data.ServerStats
import id.termdeck.app.data.StoreBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.common.SSHException
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.password.PasswordUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Security
import java.util.Base64
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

interface TerminalSession : AutoCloseable {
    val inputStream: InputStream
    val outputStream: OutputStream
    val exitStatus: Int?
    fun resize(cols: Int, rows: Int)
}

interface ExecProcess : AutoCloseable {
    val inputStream: InputStream
    val errorStream: InputStream
    val outputStream: OutputStream
    fun join(timeoutMs: Long = 120_000L): Int
}

data class ExecResult(val stdout: String, val stderr: String, val exitStatus: Int)

interface SshPoolBackend {
    suspend fun testConnection(secrets: ServerSecrets): String
    suspend fun stats(serverId: Int): ServerStats
    suspend fun listdir(serverId: Int, path: String): DirListing
    suspend fun uploadPaste(serverId: Int, data: ByteArray, ext: String): String
    suspend fun readFile(serverId: Int, path: String): FileContentOut
    suspend fun writeFile(serverId: Int, path: String, content: String): FileActionOut
    suspend fun mkdir(serverId: Int, path: String): FileActionOut
    suspend fun renameFile(serverId: Int, oldPath: String, newPath: String): FileActionOut
    suspend fun chmodFile(serverId: Int, path: String, mode: String): FileActionOut
    suspend fun deleteFile(serverId: Int, path: String)
    suspend fun uploadFile(serverId: Int, path: String, data: ByteArray): FileActionOut
    suspend fun downloadFile(serverId: Int, path: String): ByteArray
    suspend fun openTerminal(serverId: Int, cols: Int, rows: Int): TerminalSession
    suspend fun execCommand(serverId: Int, command: String, stdinText: String = "", timeoutMs: Long = 120_000L): ExecResult {
        throw UnsupportedOperationException("execCommand not supported in this backend")
    }
    suspend fun openProcess(serverId: Int, command: String): ExecProcess {
        throw UnsupportedOperationException("openProcess not supported in this backend")
    }
    suspend fun release(serverId: Int)
    suspend fun drop(serverId: Int)
    suspend fun closeAll()
}

fun ensureBouncyCastleRegistered() {
    synchronized(BouncyCastleProvider::class.java) {
        val existing = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
        if (existing == null || existing.javaClass != BouncyCastleProvider::class.java) {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
    }
}

fun publicKeyWireBytes(key: PublicKey): ByteArray {
    return Buffer.PlainBuffer().putPublicKey(key).compactData
}

fun publicKeyFingerprint(wireBytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(wireBytes)
    val b64 = Base64.getEncoder().withoutPadding().encodeToString(digest)
    return "SHA256:$b64"
}

fun encodeStoredHostKey(wireBytes: ByteArray): String {
    return Base64.getEncoder().encodeToString(wireBytes)
}

fun decodeStoredHostKey(stored: String): ByteArray {
    val trimmed = stored.trim()
    val parts = trimmed.split(Regex("""\s+"""))
    val b64 = if (parts.size >= 2 && parts[0].startsWith("ssh-") || parts[0].startsWith("ecdsa-")) {
        parts[1]
    } else {
        parts[0]
    }
    return Base64.getDecoder().decode(b64)
}

fun sshErrorMessage(exc: Throwable): String {
    return when (exc) {
        is HostKeyMismatch -> exc.message ?: "Host key mismatch"
        is UserAuthException -> "Autentikasi gagal: username / password / private key salah."
        is KeyImportException -> "Private key tidak valid atau passphrase salah: ${exc.message}"
        is SSHException -> {
            if (exc.disconnectReason == DisconnectReason.HOST_KEY_NOT_VERIFIABLE && exc.cause is HostKeyMismatch) {
                exc.cause!!.message!!
            } else if (exc.disconnectReason != DisconnectReason.UNKNOWN) {
                "Koneksi SSH terputus: ${exc.message ?: exc.disconnectReason}"
            } else if (exc.cause is SocketTimeoutException) {
                "Koneksi timeout. Periksa host/port dan firewall."
            } else if (exc.cause is java.io.IOException) {
                "Tidak bisa terhubung: ${exc.cause?.message ?: exc.message}"
            } else {
                "${exc.javaClass.simpleName}: ${exc.message}"
            }
        }
        is SocketTimeoutException -> "Koneksi timeout. Periksa host/port dan firewall."
        is java.io.IOException -> "Tidak bisa terhubung: ${exc.message ?: exc}"
        is IllegalArgumentException -> exc.message ?: "Input tidak valid"
        else -> "${exc.javaClass.simpleName}: ${exc.message}"
    }
}

class KeyImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

private class TofuVerifier(private val expectedStored: String?) : HostKeyVerifier {
    var seenWireBytes: ByteArray? = null
    var mismatch: HostKeyMismatch? = null

    override fun verify(hostname: String?, port: Int, key: PublicKey?): Boolean {
        if (key == null) return false
        val wire = publicKeyWireBytes(key)
        seenWireBytes = wire
        if (expectedStored.isNullOrBlank()) {
            return true
        }
        return try {
            val expectedWire = decodeStoredHostKey(expectedStored)
            if (MessageDigest.isEqual(wire, expectedWire)) {
                true
            } else {
                mismatch = HostKeyMismatch(
                    expectedFp = publicKeyFingerprint(expectedWire),
                    actualFp = publicKeyFingerprint(wire)
                )
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    override fun findExistingAlgorithms(hostname: String?, port: Int): MutableList<String> {
        return mutableListOf()
    }
}

data class OpenedSsh(
    val client: SSHClient,
    val hostKeyStored: String,
    val hostKeyFingerprint: String
)

fun openSshConnection(secrets: ServerSecrets): OpenedSsh {
    ensureBouncyCastleRegistered()
    if (secrets.authType == "key" && secrets.privateKey.isNullOrBlank()) {
        throw IllegalArgumentException("Private key belum diisi untuk server ini.")
    }

    val config = DefaultConfig()
    val client = SSHClient(config)
    client.connectTimeout = CONNECT_TIMEOUT_MS
    client.timeout = CONNECT_TIMEOUT_MS
    val verifier = TofuVerifier(secrets.hostKey)
    client.addHostKeyVerifier(verifier)

    try {
        client.connect(secrets.host, secrets.port)
        client.connection.keepAlive.keepAliveInterval = 30
        if (secrets.authType == "key") {
            val keyProvider = try {
                val finder = if (!secrets.passphrase.isNullOrEmpty()) {
                    PasswordUtils.createOneOff(secrets.passphrase.toCharArray())
                } else {
                    null
                }
                client.loadKeys(secrets.privateKey!!.trim(), null, finder)
            } catch (e: Exception) {
                throw KeyImportException(e.message ?: "Format kunci tidak didukung", e)
            }
            // Trigger private key parsing before auth so invalid keys report KeyImportException
            try {
                keyProvider.private
            } catch (e: Exception) {
                throw KeyImportException(e.message ?: "Passphrase salah atau kunci rusak", e)
            }
            client.authPublickey(secrets.username, keyProvider)
        } else {
            client.authPassword(secrets.username, secrets.password ?: "")
        }
    } catch (t: Throwable) {
        try {
            client.disconnect()
        } catch (_: Exception) {
        }
        verifier.mismatch?.let { throw it }
        throw t
    }

    val wire = verifier.seenWireBytes ?: error("Server tidak mengirimkan host key")
    return OpenedSsh(
        client = client,
        hostKeyStored = encodeStoredHostKey(wire),
        hostKeyFingerprint = publicKeyFingerprint(wire)
    )
}

class ConnectionPool(
    private val store: StoreBackend,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : SshPoolBackend {

    private class Entry(
        val client: SSHClient,
        var refs: Int = 0,
        var idleJob: Job? = null
    )

    private val entries = ConcurrentHashMap<Int, Entry>()
    private val locks = ConcurrentHashMap<Int, Mutex>()

    private fun lockFor(serverId: Int): Mutex = locks.computeIfAbsent(serverId) { Mutex() }

    private suspend fun acquire(serverId: Int): SSHClient = lockFor(serverId).withLock {
        var entry = entries[serverId]
        if (entry != null && (!entry.client.isConnected || !entry.client.isAuthenticated)) {
            entries.remove(serverId)
            withContext(Dispatchers.IO) {
                runCatching { entry!!.client.disconnect() }
            }
            entry = null
        }
        if (entry == null) {
            val secrets = store.getServerSecrets(serverId)
                ?: throw ServerNotFound("Server $serverId tidak ditemukan")
            val opened = withContext(Dispatchers.IO) {
                openSshConnection(secrets)
            }
            if (secrets.hostKey == null) {
                store.setHostKey(serverId, opened.hostKeyStored, opened.hostKeyFingerprint)
            }
            entry = Entry(client = opened.client)
            entries[serverId] = entry
        }
        entry.idleJob?.cancel()
        entry.idleJob = null
        entry.refs += 1
        entry.client
    }

    override suspend fun release(serverId: Int) = lockFor(serverId).withLock {
        val entry = entries[serverId] ?: return@withLock
        entry.refs = (entry.refs - 1).coerceAtLeast(0)
        if (entry.refs == 0 && entry.idleJob == null) {
            entry.idleJob = scope.launch {
                delay(IDLE_CLOSE_SECONDS * 1000L)
                lockFor(serverId).withLock {
                    val current = entries[serverId]
                    if (current === entry && current.refs == 0) {
                        entries.remove(serverId)
                        runCatching { current.client.disconnect() }
                    }
                }
            }
        }
    }

    override suspend fun drop(serverId: Int) {
        val removed = lockFor(serverId).withLock {
            entries.remove(serverId)?.also { it.idleJob?.cancel() }
        }
        if (removed != null) {
            withContext(Dispatchers.IO) {
                runCatching { removed.client.disconnect() }
            }
        }
    }

    override suspend fun closeAll() {
        for (serverId in entries.keys.toList()) {
            drop(serverId)
        }
    }

    override suspend fun testConnection(secrets: ServerSecrets): String = withContext(Dispatchers.IO) {
        val opened = openSshConnection(secrets)
        try {
            opened.hostKeyFingerprint
        } finally {
            runCatching { opened.client.disconnect() }
        }
    }

    override suspend fun stats(serverId: Int): ServerStats {
        val client = acquire(serverId)
        try {
            val output = withContext(Dispatchers.IO) {
                runCommandWithInput(client, "sh -s", STATS_SCRIPT).stdout
            }
            return parseStats(output)
        } finally {
            release(serverId)
        }
    }

    override suspend fun listdir(serverId: Int, path: String): DirListing {
        val client = acquire(serverId)
        try {
            return withContext(Dispatchers.IO) {
                val listing = try {
                    listdirSftp(client, path)
                } catch (_: SSHException) {
                    listdirShell(client, path)
                }
                listing.copy(
                    entries = listing.entries.sortedWith(
                        compareBy<DirEntry> { !it.isDir }.thenBy { it.name.lowercase() }
                    )
                )
            }
        } finally {
            release(serverId)
        }
    }

    override suspend fun uploadPaste(serverId: Int, data: ByteArray, ext: String): String {
        val client = acquire(serverId)
        try {
            return withContext(Dispatchers.IO) {
                val sftp = try {
                    client.newSFTPClient()
                } catch (e: SSHException) {
                    throw RemotePathError("SFTP tidak aktif di server ini, gambar tidak bisa dikirim.")
                }
                sftp.use { sftpClient ->
                    try {
                        val home = sftpClient.canonicalize(".")
                        val folder = posixJoin(home, PASTE_DIR)
                        sftpClient.mkdirs(folder)
                        runCatching { sftpClient.chmod(folder, 0b111_000_000) } // 0700
                        val remotePath = posixJoin(folder, pasteImageName(ext))
                        val handle = sftpClient.open(
                            remotePath,
                            EnumSet.of(OpenMode.CREAT, OpenMode.EXCL, OpenMode.WRITE)
                        )
                        handle.use { h ->
                            h.write(0L, data, 0, data.size)
                        }
                        runCatching { sftpClient.chmod(remotePath, 0b110_000_000) } // 0600
                        removeOldPastes(sftpClient, folder, remotePath)
                        remotePath
                    } catch (e: SFTPException) {
                        throw RemotePathError("Gagal menyimpan gambar di server: ${e.message}")
                    }
                }
            }
        } finally {
            release(serverId)
        }
    }

    private fun resolveRemotePath(sftp: SFTPClient, rawPath: String): String {
        val trimmed = rawPath.trim()
        require(trimmed.isNotEmpty()) { "Path wajib diisi." }
        if (trimmed == "~" || trimmed.startsWith("~/")) {
            val home = sftp.canonicalize(".")
            return if (trimmed == "~") home else posixJoin(home, trimmed.substring(2))
        }
        return trimmed
    }

    override suspend fun readFile(serverId: Int, path: String): FileContentOut {
        val client = acquire(serverId)
        try {
            return withContext(Dispatchers.IO) {
                val sftp = try {
                    client.newSFTPClient()
                } catch (e: SSHException) {
                    throw RemotePathError("SFTP tidak aktif di server ini.")
                }
                sftp.use { s ->
                    try {
                        val target = resolveRemotePath(s, path)
                        val attrs = s.stat(target)
                        if (attrs.type == FileMode.Type.DIRECTORY) {
                            throw RemotePathError("Tidak bisa membaca folder sebagai file teks.")
                        }
                        if (attrs.size > MAX_TEXT_FILE_BYTES) {
                            throw RemotePathError("Ukuran file terlalu besar untuk editor teks (maks 2 MB).")
                        }
                        val size = attrs.size.toInt().coerceAtLeast(0)
                        val buf = ByteArray(size)
                        if (size > 0) {
                            s.open(target, EnumSet.of(OpenMode.READ)).use { handle ->
                                var offset = 0
                                while (offset < size) {
                                    val n = handle.read(offset.toLong(), buf, offset, size - offset)
                                    if (n <= 0) break
                                    offset += n
                                }
                            }
                        }
                        FileContentOut(
                            path = target,
                            content = buf.toString(Charsets.UTF_8),
                            size = attrs.size,
                            mtime = attrs.mtime,
                            mode = formatOctalPerm(attrs.mode.permissionsMask)
                        )
                    } catch (e: SFTPException) {
                        throw RemotePathError("Gagal membaca file $path: ${e.message}")
                    }
                }
            }
        } finally {
            release(serverId)
        }
    }

    override suspend fun writeFile(serverId: Int, path: String, content: String): FileActionOut {
        val bytes = content.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_TEXT_FILE_BYTES) { "Konten terlalu besar (maks 2 MB)." }
        return uploadFile(serverId, path, bytes).copy(message = "File berhasil disimpan")
    }

    override suspend fun mkdir(serverId: Int, path: String): FileActionOut {
        val client = acquire(serverId)
        try {
            return withContext(Dispatchers.IO) {
                val sftp = try {
                    client.newSFTPClient()
                } catch (e: SSHException) {
                    throw RemotePathError("SFTP tidak aktif di server ini.")
                }
                sftp.use { s ->
                    try {
                        val target = resolveRemotePath(s, path)
                        s.mkdirs(target)
                        FileActionOut(path = target, message = "Folder berhasil dibuat")
                    } catch (e: SFTPException) {
                        throw RemotePathError("Gagal membuat folder $path: ${e.message}")
                    }
                }
            }
        } finally {
            release(serverId)
        }
    }

    override suspend fun renameFile(serverId: Int, oldPath: String, newPath: String): FileActionOut {
        val client = acquire(serverId)
        try {
            return withContext(Dispatchers.IO) {
                val sftp = try {
                    client.newSFTPClient()
                } catch (e: SSHException) {
                    throw RemotePathError("SFTP tidak aktif di server ini.")
                }
                sftp.use { s ->
                    try {
                        val src = resolveRemotePath(s, oldPath)
                        val dst = resolveRemotePath(s, newPath)
                        s.rename(src, dst)
                        FileActionOut(path = dst, message = "Berhasil mengubah nama / memindahkan")
                    } catch (e: SFTPException) {
                        throw RemotePathError("Gagal memindahkan $oldPath ke $newPath: ${e.message}")
                    }
                }
            }
        } finally {
            release(serverId)
        }
    }

    override suspend fun chmodFile(serverId: Int, path: String, mode: String): FileActionOut {
        val perms = parseOctalMode(mode)
        val client = acquire(serverId)
        try {
            return withContext(Dispatchers.IO) {
                val sftp = try {
                    client.newSFTPClient()
                } catch (e: SSHException) {
                    throw RemotePathError("SFTP tidak aktif di server ini.")
                }
                sftp.use { s ->
                    try {
                        val target = resolveRemotePath(s, path)
                        s.chmod(target, perms)
                        FileActionOut(path = target, message = "Izin file diubah ke ${formatOctalPerm(perms)}")
                    } catch (e: SFTPException) {
                        throw RemotePathError("Gagal mengubah izin $path: ${e.message}")
                    }
                }
            }
        } finally {
            release(serverId)
        }
    }

    override suspend fun deleteFile(serverId: Int, path: String) {
        val trimmed = path.trim()
        require(trimmed.isNotEmpty() && trimmed != "/") { "Tidak diizinkan menghapus root directory." }
        val client = acquire(serverId)
        try {
            withContext(Dispatchers.IO) {
                val sftp = try {
                    client.newSFTPClient()
                } catch (e: SSHException) {
                    throw RemotePathError("SFTP tidak aktif di server ini.")
                }
                sftp.use { s ->
                    try {
                        val target = resolveRemotePath(s, trimmed)
                        require(target != "/") { "Tidak diizinkan menghapus root directory." }
                        deleteRecursiveSftp(s, target)
                    } catch (e: SFTPException) {
                        throw RemotePathError("Gagal menghapus $path: ${e.message}")
                    }
                }
            }
        } finally {
            release(serverId)
        }
    }

    private fun deleteRecursiveSftp(sftp: SFTPClient, target: String) {
        val attrs = sftp.lstat(target)
        if (attrs.type == FileMode.Type.DIRECTORY) {
            for (item in sftp.ls(target)) {
                if (item.name == "." || item.name == "..") continue
                deleteRecursiveSftp(sftp, posixJoin(target, item.name))
            }
            sftp.rmdir(target)
        } else {
            sftp.rm(target)
        }
    }

    override suspend fun uploadFile(serverId: Int, path: String, data: ByteArray): FileActionOut {
        require(data.size <= MAX_UPLOAD_FILE_BYTES) { "Ukuran file melebihi batas 50 MB." }
        val client = acquire(serverId)
        try {
            return withContext(Dispatchers.IO) {
                val sftp = try {
                    client.newSFTPClient()
                } catch (e: SSHException) {
                    throw RemotePathError("SFTP tidak aktif di server ini.")
                }
                sftp.use { s ->
                    try {
                        val target = resolveRemotePath(s, path)
                        val handle = s.open(
                            target,
                            EnumSet.of(OpenMode.CREAT, OpenMode.TRUNC, OpenMode.WRITE)
                        )
                        handle.use { h ->
                            if (data.isNotEmpty()) {
                                h.write(0L, data, 0, data.size)
                            }
                        }
                        FileActionOut(path = target, message = "File berhasil diunggah")
                    } catch (e: SFTPException) {
                        throw RemotePathError("Gagal menulis file $path: ${e.message}")
                    }
                }
            }
        } finally {
            release(serverId)
        }
    }

    override suspend fun downloadFile(serverId: Int, path: String): ByteArray {
        val client = acquire(serverId)
        try {
            return withContext(Dispatchers.IO) {
                val sftp = try {
                    client.newSFTPClient()
                } catch (e: SSHException) {
                    throw RemotePathError("SFTP tidak aktif di server ini.")
                }
                sftp.use { s ->
                    try {
                        val target = resolveRemotePath(s, path)
                        val attrs = s.stat(target)
                        if (attrs.type == FileMode.Type.DIRECTORY) {
                            throw RemotePathError("Tidak bisa mengunduh folder secara langsung.")
                        }
                        if (attrs.size > MAX_UPLOAD_FILE_BYTES) {
                            throw RemotePathError("Ukuran file terlalu besar untuk diunduh langsung (maks 50 MB).")
                        }
                        val size = attrs.size.toInt().coerceAtLeast(0)
                        val buf = ByteArray(size)
                        if (size > 0) {
                            s.open(target, EnumSet.of(OpenMode.READ)).use { handle ->
                                var offset = 0
                                while (offset < size) {
                                    val n = handle.read(offset.toLong(), buf, offset, size - offset)
                                    if (n <= 0) break
                                    offset += n
                                }
                            }
                        }
                        buf
                    } catch (e: SFTPException) {
                        throw RemotePathError("Gagal mengunduh $path: ${e.message}")
                    }
                }
            }
        } finally {
            release(serverId)
        }
    }

    override suspend fun openTerminal(serverId: Int, cols: Int, rows: Int): TerminalSession {
        val client = acquire(serverId)
        return try {
            withContext(Dispatchers.IO) {
                val session = client.startSession()
                session.allocatePTY(
                    "xterm-256color",
                    cols.coerceAtLeast(10),
                    rows.coerceAtLeast(5),
                    0,
                    0,
                    emptyMap()
                )
                val shell = session.startShell()
                object : TerminalSession {
                    override val inputStream: InputStream = shell.inputStream
                    override val outputStream: OutputStream = shell.outputStream
                    override val exitStatus: Int?
                        get() = null

                    override fun resize(cols: Int, rows: Int) {
                        runCatching {
                            shell.changeWindowDimensions(cols.coerceAtLeast(1), rows.coerceAtLeast(1), 0, 0)
                        }
                    }

                    override fun close() {
                        runCatching { shell.close() }
                        runCatching { session.close() }
                    }
                }
            }
        } catch (t: Throwable) {
            release(serverId)
            throw t
        }
    }

    private fun removeOldPastes(sftp: SFTPClient, folder: String, newest: String) {
        try {
            val now = sftp.stat(newest).mtime
            for (item in sftp.ls(folder)) {
                val name = item.name
                val mtime = item.attributes.mtime
                if (name.startsWith("paste-") && now - mtime > PASTE_KEEP_SECONDS) {
                    runCatching { sftp.rm(posixJoin(folder, name)) }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun listdirSftp(client: SSHClient, path: String): DirListing {
        val sftp = client.newSFTPClient()
        sftp.use { s ->
            val target: String
            val items = try {
                val home = s.canonicalize(".")
                val rawTarget = when {
                    path.isEmpty() || path == "~" -> home
                    path.startsWith("~/") -> posixJoin(home, path.substring(2))
                    else -> path
                }
                target = s.canonicalize(rawTarget)
                s.ls(target)
            } catch (e: SFTPException) {
                throw RemotePathError("Tidak bisa membuka $path: ${e.message}")
            }
            val entries = mutableListOf<DirEntry>()
            for (item in items) {
                val name = item.name
                if (name == "." || name == "..") continue
                val full = posixJoin(target, name)
                val isSymlink = item.attributes.type == FileMode.Type.SYMLINK
                var isDir = item.isDirectory
                if (isSymlink) {
                    isDir = try {
                        s.stat(full).type == FileMode.Type.DIRECTORY
                    } catch (_: Exception) {
                        false
                    }
                }
                entries.add(
                    DirEntry(
                        name = name,
                        path = full,
                        isDir = isDir,
                        size = item.attributes.size,
                        mtime = item.attributes.mtime,
                        mode = formatOctalPerm(item.attributes.mode.permissionsMask),
                        isSymlink = isSymlink
                    )
                )
            }
            return DirListing(path = target, entries = entries)
        }
    }

    private fun listdirShell(client: SSHClient, path: String): DirListing {
        val script = "cd -- ${quoteRemotePath(path)} && pwd && LC_ALL=C ls -1ApL\n"
        val res = runCommandWithInput(client, "sh -s", script)
        if (res.exitStatus != 0 || res.stdout.isBlank()) {
            val reason = res.stderr.trim().lines().lastOrNull { it.isNotBlank() } ?: "folder tidak bisa dibaca"
            throw RemotePathError("Tidak bisa membuka $path: $reason")
        }
        return parseShellLs(res.stdout)
    }

    override suspend fun execCommand(serverId: Int, command: String, stdinText: String, timeoutMs: Long): ExecResult {
        val client = acquire(serverId)
        try {
            return withContext(Dispatchers.IO) {
                val res = runCommandWithInput(client, command, stdinText, timeoutMs)
                ExecResult(res.stdout, res.stderr, res.exitStatus)
            }
        } finally {
            release(serverId)
        }
    }

    override suspend fun openProcess(serverId: Int, command: String): ExecProcess {
        val client = acquire(serverId)
        return try {
            withContext(Dispatchers.IO) {
                val session = client.startSession()
                val cmd = session.exec(command)
                object : ExecProcess {
                    override val inputStream: InputStream = cmd.inputStream
                    override val errorStream: InputStream = cmd.errorStream
                    override val outputStream: OutputStream = cmd.outputStream

                    override fun join(timeoutMs: Long): Int {
                        runCatching { cmd.join(timeoutMs, TimeUnit.MILLISECONDS) }
                        return cmd.exitStatus ?: 0
                    }

                    override fun close() {
                        runCatching { cmd.outputStream.close() }
                        runCatching { cmd.close() }
                        runCatching { session.close() }
                    }
                }
            }
        } catch (t: Throwable) {
            release(serverId)
            throw t
        }
    }

    private data class CmdResult(val stdout: String, val stderr: String, val exitStatus: Int)

    private fun runCommandWithInput(
        client: SSHClient,
        command: String,
        stdinText: String,
        timeoutMs: Long = CONNECT_TIMEOUT_MS.toLong()
    ): CmdResult {
        val session: Session = client.startSession()
        session.use { s ->
            val cmd = s.exec(command)
            cmd.use { c ->
                c.outputStream.use { out ->
                    if (stdinText.isNotEmpty()) {
                        out.write(stdinText.toByteArray(Charsets.UTF_8))
                    }
                    out.flush()
                }
                val stdout = c.inputStream.readBytes().toString(Charsets.UTF_8)
                val stderr = c.errorStream.readBytes().toString(Charsets.UTF_8)
                c.join(timeoutMs, TimeUnit.MILLISECONDS)
                return CmdResult(stdout, stderr, c.exitStatus ?: 0)
            }
        }
    }
}
