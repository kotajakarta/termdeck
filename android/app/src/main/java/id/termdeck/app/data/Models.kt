package id.termdeck.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ServerIn(
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    @SerialName("auth_type") val authType: String = "password",
    val password: String? = null,
    @SerialName("private_key") val privateKey: String? = null,
    val passphrase: String? = null,
    @SerialName("group_name") val groupName: String = "",
    val color: String = ""
)

@Serializable
data class ServerTestIn(
    val id: Int? = null,
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    @SerialName("auth_type") val authType: String = "password",
    val password: String? = null,
    @SerialName("private_key") val privateKey: String? = null,
    val passphrase: String? = null,
    @SerialName("group_name") val groupName: String = "",
    val color: String = ""
)

@Serializable
data class ServerOut(
    val id: Int,
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    @SerialName("auth_type") val authType: String,
    @SerialName("group_name") val groupName: String,
    val color: String,
    @SerialName("has_password") val hasPassword: Boolean,
    @SerialName("has_private_key") val hasPrivateKey: Boolean,
    @SerialName("host_key_fingerprint") val hostKeyFingerprint: String? = null
)

data class ServerSecrets(
    val id: Int,
    val host: String,
    val port: Int,
    val username: String,
    val authType: String,
    val password: String?,
    val privateKey: String?,
    val passphrase: String?,
    val hostKey: String?
)

@Serializable
data class BookmarkIn(
    @SerialName("server_id") val serverId: Int,
    val name: String,
    val path: String,
    @SerialName("startup_cmd") val startupCmd: String = ""
)

@Serializable
data class BookmarkOut(
    val id: Int,
    @SerialName("server_id") val serverId: Int,
    val name: String,
    val path: String,
    @SerialName("startup_cmd") val startupCmd: String = ""
)

@Serializable
data class SnippetIn(
    val name: String,
    val command: String
)

@Serializable
data class SnippetOut(
    val id: Int,
    val name: String,
    val command: String
)

@Serializable
data class DirEntry(
    val name: String,
    val path: String,
    @SerialName("is_dir") val isDir: Boolean,
    val size: Long = 0L,
    val mtime: Long? = null,
    val mode: String = "",
    @SerialName("is_symlink") val isSymlink: Boolean = false
)

@Serializable
data class DirListing(
    val path: String,
    val entries: List<DirEntry>
)

@Serializable
data class FileContentOut(
    val path: String,
    val content: String,
    val size: Long,
    val mtime: Long? = null,
    val mode: String = "0644"
)

@Serializable
data class FileWriteIn(
    val path: String,
    val content: String
)

@Serializable
data class FilePathIn(
    val path: String
)

@Serializable
data class FileRenameIn(
    @SerialName("old_path") val oldPath: String,
    @SerialName("new_path") val newPath: String
)

@Serializable
data class FileChmodIn(
    val path: String,
    val mode: String
)

@Serializable
data class FileActionOut(
    val path: String,
    val message: String
)

@Serializable
data class ServerStats(
    val hostname: String = "",
    val os: String = "",
    val kernel: String = "",
    @SerialName("uptime_seconds") val uptimeSeconds: Double? = null,
    val load: List<Double> = emptyList(),
    @SerialName("cpu_count") val cpuCount: Int? = null,
    @SerialName("mem_total") val memTotal: Long? = null,
    @SerialName("mem_used") val memUsed: Long? = null,
    @SerialName("disk_total") val diskTotal: Long? = null,
    @SerialName("disk_used") val diskUsed: Long? = null
)

@Serializable
data class ConnectionCheck(
    val ok: Boolean,
    val message: String
)

@Serializable
data class PastedImage(
    val path: String
)

@Serializable
data class SetupPinIn(
    val pin: String
)

@Serializable
data class LoginIn(
    val pin: String
)

@Serializable
data class LoginOut(
    val session: String,
    @SerialName("must_change_pin") val mustChangePin: Boolean = false
)

@Serializable
data class ChangePinIn(
    @SerialName("old_pin") val oldPin: String,
    @SerialName("new_pin") val newPin: String
)

@Serializable
data class AuthStatus(
    val unlocked: Boolean,
    @SerialName("session_valid") val sessionValid: Boolean,
    val initialized: Boolean = true,
    @SerialName("must_change_pin") val mustChangePin: Boolean = false
)

@Serializable
data class ServerOrderIn(
    val ids: List<Int>
)

@Serializable
data class ErrorDetail(
    val detail: String
)

@Serializable
data class CFAccountIn(
    val name: String,
    val token: String
)

@Serializable
data class CFAccountUpdateIn(
    val name: String,
    val token: String? = null
)

@Serializable
data class CFAccountVerifyIn(
    val token: String
)

@Serializable
data class CFAccountOut(
    val id: Int,
    val name: String,
    @SerialName("token_masked") val tokenMasked: String,
    @SerialName("account_id") val accountId: String,
    @SerialName("account_name") val accountName: String,
    @SerialName("created_at") val createdAt: String
)

data class CFAccountWithSecret(
    val id: Int,
    val name: String,
    val token: String,
    val tokenMasked: String,
    val accountId: String,
    val accountName: String,
    val createdAt: String
)

@Serializable
data class CFRouteIn(
    val hostname: String,
    val service: String,
    @SerialName("zone_id") val zoneId: String? = null
)

@Serializable
data class UpdateCheckResult(
    val platform: String,
    val current_version: String,
    val latest_version: String,
    val has_update: Boolean,
    val release_title: String? = null,
    val release_notes: String? = null,
    val download_url: String? = null
)

@Serializable
data class UpdateApplyResult(
    val started: Boolean,
    val message: String
)

@Serializable
data class UpdateProgressResult(
    val state: String,
    val progress_percent: Int,
    val downloaded_bytes: Long,
    val total_bytes: Long,
    val message: String
)

