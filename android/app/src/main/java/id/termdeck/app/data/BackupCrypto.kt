package id.termdeck.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

private val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
data class BackupKdf(
    val algorithm: String = "PBKDF2-HMAC-SHA256",
    val iterations: Int = 100000,
    @SerialName("salt_hex") val saltHex: String
)

@Serializable
data class BackupEnvelope(
    val magic: String = "TERMDECK_BACKUP",
    val version: Int = 1,
    val kdf: BackupKdf,
    val cipher: String = "AES-256-GCM",
    @SerialName("iv_hex") val ivHex: String,
    @SerialName("ciphertext_hex") val ciphertextHex: String
)

@Serializable
data class BackupBookmarkItem(
    val name: String,
    val path: String,
    @SerialName("startup_cmd") val startupCmd: String = ""
)

@Serializable
data class BackupServerItem(
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    @SerialName("auth_type") val authType: String = "password",
    val password: String? = null,
    @SerialName("private_key") val privateKey: String? = null,
    val passphrase: String? = null,
    @SerialName("group_name") val groupName: String = "",
    val color: String = "",
    val bookmarks: List<BackupBookmarkItem> = emptyList()
)

@Serializable
data class BackupSnippetItem(
    val name: String,
    val command: String
)

@Serializable
data class BackupPayload(
    val version: Int = 1,
    val servers: List<BackupServerItem> = emptyList(),
    val snippets: List<BackupSnippetItem> = emptyList()
)

@Serializable
data class BackupExportIn(
    val pin: String
)

@Serializable
data class BackupImportIn(
    val pin: String,
    val backup: BackupEnvelope
)

@Serializable
data class BackupStatsOut(
    @SerialName("servers_added") val serversAdded: Int,
    @SerialName("servers_updated") val serversUpdated: Int,
    @SerialName("bookmarks_added") val bookmarksAdded: Int,
    @SerialName("snippets_added") val snippetsAdded: Int
)

class BackupCryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)
class BackupPinException(message: String) : Exception(message)

object BackupCrypto {
    private const val BACKUP_MAGIC = "TERMDECK_BACKUP"
    private const val BACKUP_VERSION = 1
    private const val PBKDF2_ITERATIONS = 100000

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        val len = clean.length
        if (len % 2 != 0) throw BackupCryptoException("Invalid hex string length")
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(clean[i], 16) shl 4) + Character.digit(clean[i + 1], 16)).toByte()
        }
        return data
    }

    private fun deriveKey(pin: String, salt: ByteArray): ByteArray {
        if (pin.isEmpty()) throw BackupPinException("PIN tidak boleh kosong")
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    fun encryptPayload(payload: BackupPayload, pin: String): BackupEnvelope {
        val secureRandom = SecureRandom()
        val salt = ByteArray(16).also { secureRandom.nextBytes(it) }
        val iv = ByteArray(12).also { secureRandom.nextBytes(it) }

        val keyBytes = deriveKey(pin, salt)
        val keySpec = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(128, iv))

        val plaintextBytes = AppJson.encodeToString(payload).toByteArray(Charsets.UTF_8)
        val ciphertext = cipher.doFinal(plaintextBytes)

        return BackupEnvelope(
            magic = BACKUP_MAGIC,
            version = BACKUP_VERSION,
            kdf = BackupKdf(
                algorithm = "PBKDF2-HMAC-SHA256",
                iterations = PBKDF2_ITERATIONS,
                saltHex = bytesToHex(salt)
            ),
            cipher = "AES-256-GCM",
            ivHex = bytesToHex(iv),
            ciphertextHex = bytesToHex(ciphertext)
        )
    }

    fun decryptEnvelope(envelope: BackupEnvelope, pin: String): BackupPayload {
        if (envelope.magic != BACKUP_MAGIC) {
            throw BackupCryptoException("Format file backup tidak valid (magic header salah)")
        }
        if (envelope.version != BACKUP_VERSION) {
            throw BackupCryptoException("Versi backup ${envelope.version} tidak didukung")
        }

        val salt = runCatching { hexToBytes(envelope.kdf.saltHex) }.getOrElse {
            throw BackupCryptoException("Salt data backup rusak")
        }
        val iv = runCatching { hexToBytes(envelope.ivHex) }.getOrElse {
            throw BackupCryptoException("IV data backup rusak")
        }
        val ciphertext = runCatching { hexToBytes(envelope.ciphertextHex) }.getOrElse {
            throw BackupCryptoException("Ciphertext data backup rusak")
        }

        val keyBytes = deriveKey(pin, salt)
        val keySpec = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(128, iv))

        val plaintextBytes = try {
            cipher.doFinal(ciphertext)
        } catch (t: Throwable) {
            throw BackupPinException("PIN backup salah atau file telah rusak/dimodifikasi")
        }

        val jsonStr = String(plaintextBytes, Charsets.UTF_8)
        return try {
            AppJson.decodeFromString<BackupPayload>(jsonStr)
        } catch (e: Exception) {
            throw BackupCryptoException("Gagal membaca struktur data backup: ${e.message}")
        }
    }

    fun collectStoreBackup(store: StoreBackend): BackupPayload {
        val serversOut = store.listServers()
        val exportedServers = serversOut.map { srv ->
            val secrets = store.getServerSecrets(srv.id)
            val bookmarks = store.listBookmarks(srv.id).map { bm ->
                BackupBookmarkItem(
                    name = bm.name,
                    path = bm.path,
                    startupCmd = bm.startupCmd
                )
            }
            BackupServerItem(
                name = srv.name,
                host = srv.host,
                port = srv.port,
                username = srv.username,
                authType = srv.authType,
                password = secrets?.password,
                privateKey = secrets?.privateKey,
                passphrase = secrets?.passphrase,
                groupName = srv.groupName,
                color = srv.color,
                bookmarks = bookmarks
            )
        }

        val exportedSnippets = store.listSnippets().map { sn ->
            BackupSnippetItem(
                name = sn.name,
                command = sn.command
            )
        }

        return BackupPayload(
            version = 1,
            servers = exportedServers,
            snippets = exportedSnippets
        )
    }

    fun mergeBackupIntoStore(store: StoreBackend, payload: BackupPayload): BackupStatsOut {
        var serversAdded = 0
        var serversUpdated = 0
        var bookmarksAdded = 0
        var snippetsAdded = 0

        val existingServers = store.listServers()
        val serverMap = existingServers.associateBy {
            Triple(it.host.lowercase(), it.port, it.username.lowercase())
        }.toMutableMap()

        for (s in payload.servers) {
            val host = s.host.trim()
            val port = s.port
            val username = s.username.trim()
            if (host.isEmpty() || username.isEmpty()) continue

            val key = Triple(host.lowercase(), port, username.lowercase())
            val existing = serverMap[key]

            val srvId: Int
            if (existing != null) {
                srvId = existing.id
                val curSecrets = store.getServerSecrets(srvId)
                val newPw = s.password
                val newKey = s.privateKey
                val newPass = s.passphrase

                val hasCurPw = !curSecrets?.password.isNullOrBlank()
                val hasCurKey = !curSecrets?.privateKey.isNullOrBlank()

                if ((!hasCurPw && !newPw.isNullOrBlank()) || (!hasCurKey && !newKey.isNullOrBlank())) {
                    store.updateServer(
                        srvId,
                        ServerIn(
                            name = existing.name,
                            host = existing.host,
                            port = existing.port,
                            username = existing.username,
                            authType = s.authType,
                            password = newPw ?: curSecrets?.password,
                            privateKey = newKey ?: curSecrets?.privateKey,
                            passphrase = newPass ?: curSecrets?.passphrase,
                            groupName = existing.groupName.ifEmpty { s.groupName },
                            color = existing.color.ifEmpty { s.color }
                        )
                    )
                    serversUpdated++
                }
            } else {
                val created = store.createServer(
                    ServerIn(
                        name = s.name.ifEmpty { host },
                        host = host,
                        port = port,
                        username = username,
                        authType = s.authType,
                        password = s.password,
                        privateKey = s.privateKey,
                        passphrase = s.passphrase,
                        groupName = s.groupName,
                        color = s.color
                    )
                )
                srvId = created.id
                serverMap[key] = created
                serversAdded++
            }

            val existingBms = store.listBookmarks(srvId).associateBy { it.path }
            for (bm in s.bookmarks) {
                val bPath = bm.path.trim()
                if (bPath.isNotEmpty() && !existingBms.containsKey(bPath)) {
                    store.createBookmark(
                        BookmarkIn(
                            serverId = srvId,
                            name = bm.name.ifEmpty { bPath },
                            path = bPath,
                            startupCmd = bm.startupCmd
                        )
                    )
                    bookmarksAdded++
                }
            }
        }

        val existingSnippets = store.listSnippets().associateBy { it.name }
        for (sn in payload.snippets) {
            val snName = sn.name.trim()
            if (snName.isNotEmpty() && !existingSnippets.containsKey(snName)) {
                store.createSnippet(
                    SnippetIn(
                        name = snName,
                        command = sn.command
                    )
                )
                snippetsAdded++
            }
        }

        return BackupStatsOut(
            serversAdded = serversAdded,
            serversUpdated = serversUpdated,
            bookmarksAdded = bookmarksAdded,
            snippetsAdded = snippetsAdded
        )
    }
}
