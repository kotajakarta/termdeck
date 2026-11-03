package id.termdeck.app.data

import android.content.ContentValues
import net.zetetic.database.DatabaseErrorHandler
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File

class WrongKeyException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface StoreBackend {
    fun listServers(): List<ServerOut>
    fun getServer(serverId: Int): ServerOut?
    fun getServerSecrets(serverId: Int): ServerSecrets?
    fun createServer(data: ServerIn): ServerOut
    fun updateServer(serverId: Int, data: ServerIn): ServerOut?
    fun reorderServers(ids: List<Int>)
    fun deleteServer(serverId: Int): Boolean
    fun setHostKey(serverId: Int, key: String?, fingerprint: String? = null)

    fun listBookmarks(serverId: Int?): List<BookmarkOut>
    fun getBookmark(bookmarkId: Int): BookmarkOut?
    fun createBookmark(data: BookmarkIn): BookmarkOut
    fun updateBookmark(bookmarkId: Int, data: BookmarkIn): BookmarkOut?
    fun deleteBookmark(bookmarkId: Int): Boolean

    fun listSnippets(): List<SnippetOut>
    fun getSnippet(snippetId: Int): SnippetOut?
    fun createSnippet(data: SnippetIn): SnippetOut
    fun updateSnippet(snippetId: Int, data: SnippetIn): SnippetOut?
    fun deleteSnippet(snippetId: Int): Boolean

    fun listCfAccounts(): List<CFAccountOut>
    fun getCfAccount(id: Int): CFAccountWithSecret?
    fun createCfAccount(name: String, token: String, accountId: String, accountName: String): CFAccountOut
    fun updateCfAccount(id: Int, name: String, token: String? = null, accountId: String? = null, accountName: String? = null): CFAccountOut?
    fun deleteCfAccount(id: Int): Boolean

    fun rekey(newKey: ByteArray)
    fun close()
}

private val SCHEMA_STATEMENTS = listOf(
    """
    CREATE TABLE IF NOT EXISTS servers (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        name TEXT NOT NULL,
        host TEXT NOT NULL,
        port INTEGER NOT NULL DEFAULT 22,
        username TEXT NOT NULL,
        auth_type TEXT NOT NULL,
        password TEXT,
        private_key TEXT,
        passphrase TEXT,
        group_name TEXT NOT NULL DEFAULT '',
        color TEXT NOT NULL DEFAULT '',
        host_key TEXT,
        host_key_fp TEXT,
        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
        sort_order INTEGER NOT NULL DEFAULT 0
    )
    """.trimIndent(),
    """
    CREATE TABLE IF NOT EXISTS bookmarks (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        server_id INTEGER NOT NULL REFERENCES servers(id) ON DELETE CASCADE,
        name TEXT NOT NULL,
        path TEXT NOT NULL,
        startup_cmd TEXT NOT NULL DEFAULT '',
        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
    )
    """.trimIndent(),
    """
    CREATE TABLE IF NOT EXISTS snippets (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        name TEXT NOT NULL,
        command TEXT NOT NULL,
        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
    )
    """.trimIndent(),
    """
    CREATE TABLE IF NOT EXISTS cf_accounts (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        name TEXT NOT NULL,
        token TEXT NOT NULL,
        account_id TEXT NOT NULL,
        account_name TEXT NOT NULL DEFAULT '',
        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
    )
    """.trimIndent()
)

fun rawKeyPassphrase(key: ByteArray): String {
    require(key.size == 32) { "Database key must be 32 bytes" }
    val hex = key.joinToString("") { "%02x".format(it) }
    return "x'$hex'"
}

private val NOOP_ERROR_HANDLER = DatabaseErrorHandler { _ ->
    // Never delete the encrypted database when an incorrect PIN/key triggers SQLiteDatabaseCorruptException.
}

class Store(dbFile: File, key: ByteArray) : StoreBackend {
    private val lock = Any()
    private val db: SQLiteDatabase

    init {
        dbFile.parentFile?.mkdirs()
        val existedBefore = dbFile.exists()
        val opened = try {
            SQLiteDatabase.openOrCreateDatabase(
                dbFile,
                rawKeyPassphrase(key),
                null,
                NOOP_ERROR_HANDLER,
                null
            )
        } catch (e: Exception) {
            if (!existedBefore) {
                runCatching { SQLiteDatabase.deleteDatabase(dbFile) }
            }
            throw WrongKeyException("Gagal membuka database: ${e.message}", e)
        }
        try {
            opened.rawQuery("SELECT count(*) FROM sqlite_master", null).use { cursor ->
                cursor.moveToFirst()
            }
            opened.enableWriteAheadLogging()
            opened.execSQL("PRAGMA synchronous = NORMAL")
            opened.execSQL("PRAGMA foreign_keys = ON")
            for (stmt in SCHEMA_STATEMENTS) {
                opened.execSQL(stmt)
            }
            migrate(opened)
        } catch (e: Exception) {
            try {
                opened.close()
            } catch (_: Exception) {
            }
            if (!existedBefore) {
                runCatching { SQLiteDatabase.deleteDatabase(dbFile) }
            }
            throw WrongKeyException("Kunci database salah: ${e.message}", e)
        }
        db = opened
    }

    private fun migrate(database: SQLiteDatabase) {
        val columns = mutableSetOf<String>()
        database.rawQuery("PRAGMA table_info(servers)", null).use { c ->
            val nameIdx = c.getColumnIndex("name")
            while (c.moveToNext()) {
                if (nameIdx >= 0) columns.add(c.getString(nameIdx))
            }
        }
        if ("sort_order" !in columns) {
            database.execSQL("ALTER TABLE servers ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0")
            val ids = mutableListOf<Int>()
            database.rawQuery(
                "SELECT id FROM servers ORDER BY group_name COLLATE NOCASE, name COLLATE NOCASE",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    ids.add(c.getInt(0))
                }
            }
            ids.forEachIndexed { index, id ->
                database.execSQL("UPDATE servers SET sort_order = ? WHERE id = ?", arrayOf<Any>(index, id))
            }
        }
    }

    override fun rekey(newKey: ByteArray) {
        synchronized(lock) {
            db.changePassword(rawKeyPassphrase(newKey))
        }
    }

    override fun close() {
        synchronized(lock) {
            db.close()
        }
    }

    override fun listServers(): List<ServerOut> = synchronized(lock) {
        val result = mutableListOf<ServerOut>()
        db.rawQuery("SELECT * FROM servers ORDER BY sort_order, id", null).use { c ->
            while (c.moveToNext()) {
                result.add(readServerOut(c))
            }
        }
        result
    }

    override fun getServer(serverId: Int): ServerOut? = synchronized(lock) {
        db.rawQuery("SELECT * FROM servers WHERE id = ?", arrayOf(serverId.toString())).use { c ->
            if (c.moveToFirst()) readServerOut(c) else null
        }
    }

    override fun getServerSecrets(serverId: Int): ServerSecrets? = synchronized(lock) {
        db.rawQuery("SELECT * FROM servers WHERE id = ?", arrayOf(serverId.toString())).use { c ->
            if (!c.moveToFirst()) return@synchronized null
            ServerSecrets(
                id = c.getInt(c.getColumnIndexOrThrow("id")),
                host = c.getString(c.getColumnIndexOrThrow("host")),
                port = c.getInt(c.getColumnIndexOrThrow("port")),
                username = c.getString(c.getColumnIndexOrThrow("username")),
                authType = c.getString(c.getColumnIndexOrThrow("auth_type")),
                password = c.getStringOrNull("password"),
                privateKey = c.getStringOrNull("private_key"),
                passphrase = c.getStringOrNull("passphrase"),
                hostKey = c.getStringOrNull("host_key")
            )
        }
    }

    override fun createServer(data: ServerIn): ServerOut = synchronized(lock) {
        validateServerIn(data)
        var maxSort = -1
        db.rawQuery("SELECT COALESCE(MAX(sort_order), -1) FROM servers", null).use { c ->
            if (c.moveToFirst()) maxSort = c.getInt(0)
        }
        val values = ContentValues().apply {
            put("name", data.name)
            put("host", data.host)
            put("port", data.port)
            put("username", data.username)
            put("auth_type", data.authType)
            val pwd = data.password?.ifEmpty { null }
            if (pwd != null) put("password", pwd) else putNull("password")
            val pk = data.privateKey?.ifEmpty { null }
            if (pk != null) put("private_key", pk) else putNull("private_key")
            val pp = data.passphrase?.ifEmpty { null }
            if (pp != null) put("passphrase", pp) else putNull("passphrase")
            put("group_name", data.groupName)
            put("color", data.color)
            put("sort_order", maxSort + 1)
        }
        val rowId = db.insertOrThrow("servers", null, values)
        getServer(rowId.toInt())!!
    }

    override fun updateServer(serverId: Int, data: ServerIn): ServerOut? = synchronized(lock) {
        validateServerIn(data)
        val old = getServerSecrets(serverId) ?: return@synchronized null
        val oldFp = getServer(serverId)?.hostKeyFingerprint

        fun merge(newValue: String?, oldVal: String?): String? {
            if (newValue == null) return oldVal
            return newValue.ifEmpty { null }
        }

        val endpointChanged = old.host != data.host || old.port != data.port
        val values = ContentValues().apply {
            put("name", data.name)
            put("host", data.host)
            put("port", data.port)
            put("username", data.username)
            put("auth_type", data.authType)
            val pwd = merge(data.password, old.password)
            if (pwd != null) put("password", pwd) else putNull("password")
            val pk = merge(data.privateKey, old.privateKey)
            if (pk != null) put("private_key", pk) else putNull("private_key")
            val pp = merge(data.passphrase, old.passphrase)
            if (pp != null) put("passphrase", pp) else putNull("passphrase")
            put("group_name", data.groupName)
            put("color", data.color)
            val hk = if (endpointChanged) null else old.hostKey
            if (hk != null) put("host_key", hk) else putNull("host_key")
            val hkFp = if (endpointChanged) null else oldFp
            if (hkFp != null) put("host_key_fp", hkFp) else putNull("host_key_fp")
        }
        db.update("servers", values, "id = ?", arrayOf(serverId.toString()))
        getServer(serverId)
    }

    override fun reorderServers(ids: List<Int>) = synchronized(lock) {
        val existing = mutableSetOf<Int>()
        db.rawQuery("SELECT id FROM servers", null).use { c ->
            while (c.moveToNext()) {
                existing.add(c.getInt(0))
            }
        }
        if (ids.size != ids.toSet().size || ids.toSet() != existing) {
            throw IllegalArgumentException("Urutan harus berisi setiap server tepat satu kali.")
        }
        db.beginTransaction()
        try {
            ids.forEachIndexed { index, sid ->
                val values = ContentValues().apply { put("sort_order", index) }
                db.update("servers", values, "id = ?", arrayOf(sid.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun deleteServer(serverId: Int): Boolean = synchronized(lock) {
        db.delete("servers", "id = ?", arrayOf(serverId.toString())) > 0
    }

    override fun setHostKey(serverId: Int, key: String?, fingerprint: String?) {
        synchronized(lock) {
            val values = ContentValues().apply {
                if (key != null) put("host_key", key) else putNull("host_key")
                if (fingerprint != null) put("host_key_fp", fingerprint) else putNull("host_key_fp")
            }
            db.update("servers", values, "id = ?", arrayOf(serverId.toString()))
        }
    }

    override fun listBookmarks(serverId: Int?): List<BookmarkOut> = synchronized(lock) {
        val result = mutableListOf<BookmarkOut>()
        val cursor = if (serverId == null) {
            db.rawQuery("SELECT * FROM bookmarks ORDER BY name COLLATE NOCASE", null)
        } else {
            db.rawQuery(
                "SELECT * FROM bookmarks WHERE server_id = ? ORDER BY name COLLATE NOCASE",
                arrayOf(serverId.toString())
            )
        }
        cursor.use { c ->
            while (c.moveToNext()) {
                result.add(readBookmarkOut(c))
            }
        }
        result
    }

    override fun getBookmark(bookmarkId: Int): BookmarkOut? = synchronized(lock) {
        db.rawQuery("SELECT * FROM bookmarks WHERE id = ?", arrayOf(bookmarkId.toString())).use { c ->
            if (c.moveToFirst()) readBookmarkOut(c) else null
        }
    }

    override fun createBookmark(data: BookmarkIn): BookmarkOut = synchronized(lock) {
        validateBookmarkIn(data)
        require(getServer(data.serverId) != null) { "Server tidak ditemukan." }
        val values = ContentValues().apply {
            put("server_id", data.serverId)
            put("name", data.name)
            put("path", data.path)
            put("startup_cmd", data.startupCmd)
        }
        val rowId = db.insertOrThrow("bookmarks", null, values)
        getBookmark(rowId.toInt())!!
    }

    override fun updateBookmark(bookmarkId: Int, data: BookmarkIn): BookmarkOut? = synchronized(lock) {
        validateBookmarkIn(data)
        require(getServer(data.serverId) != null) { "Server tidak ditemukan." }
        val values = ContentValues().apply {
            put("server_id", data.serverId)
            put("name", data.name)
            put("path", data.path)
            put("startup_cmd", data.startupCmd)
        }
        val rows = db.update("bookmarks", values, "id = ?", arrayOf(bookmarkId.toString()))
        if (rows <= 0) null else getBookmark(bookmarkId)
    }

    override fun deleteBookmark(bookmarkId: Int): Boolean = synchronized(lock) {
        db.delete("bookmarks", "id = ?", arrayOf(bookmarkId.toString())) > 0
    }

    override fun listSnippets(): List<SnippetOut> = synchronized(lock) {
        val result = mutableListOf<SnippetOut>()
        db.rawQuery("SELECT * FROM snippets ORDER BY name COLLATE NOCASE", null).use { c ->
            while (c.moveToNext()) {
                result.add(readSnippetOut(c))
            }
        }
        result
    }

    override fun getSnippet(snippetId: Int): SnippetOut? = synchronized(lock) {
        db.rawQuery("SELECT * FROM snippets WHERE id = ?", arrayOf(snippetId.toString())).use { c ->
            if (c.moveToFirst()) readSnippetOut(c) else null
        }
    }

    override fun createSnippet(data: SnippetIn): SnippetOut = synchronized(lock) {
        validateSnippetIn(data)
        val values = ContentValues().apply {
            put("name", data.name)
            put("command", data.command)
        }
        val rowId = db.insertOrThrow("snippets", null, values)
        getSnippet(rowId.toInt())!!
    }

    override fun updateSnippet(snippetId: Int, data: SnippetIn): SnippetOut? = synchronized(lock) {
        validateSnippetIn(data)
        val values = ContentValues().apply {
            put("name", data.name)
            put("command", data.command)
        }
        val rows = db.update("snippets", values, "id = ?", arrayOf(snippetId.toString()))
        if (rows <= 0) null else getSnippet(snippetId)
    }

    override fun deleteSnippet(snippetId: Int): Boolean = synchronized(lock) {
        db.delete("snippets", "id = ?", arrayOf(snippetId.toString())) > 0
    }

    override fun listCfAccounts(): List<CFAccountOut> = synchronized(lock) {
        val result = mutableListOf<CFAccountOut>()
        db.rawQuery("SELECT id, name, token, account_id, account_name, created_at FROM cf_accounts ORDER BY id ASC", null).use { c ->
            while (c.moveToNext()) {
                result.add(readCfAccountOut(c))
            }
        }
        result
    }

    override fun getCfAccount(id: Int): CFAccountWithSecret? = synchronized(lock) {
        db.rawQuery("SELECT id, name, token, account_id, account_name, created_at FROM cf_accounts WHERE id = ?", arrayOf(id.toString())).use { c ->
            if (c.moveToNext()) readCfAccountWithSecret(c) else null
        }
    }

    override fun createCfAccount(name: String, token: String, accountId: String, accountName: String): CFAccountOut = synchronized(lock) {
        validateCfAccountIn(name, token)
        val cv = ContentValues().apply {
            put("name", name.trim())
            put("token", token.trim())
            put("account_id", accountId.trim())
            put("account_name", accountName.trim())
        }
        val id = db.insertOrThrow("cf_accounts", null, cv).toInt()
        getCfAccount(id)?.toOut() ?: error("Failed to insert cf_account")
    }

    override fun updateCfAccount(id: Int, name: String, token: String?, accountId: String?, accountName: String?): CFAccountOut? = synchronized(lock) {
        if (getCfAccount(id) == null) return null
        val cleanToken = token?.trim()?.ifEmpty { null }
        if (cleanToken != null) {
            validateCfAccountIn(name, cleanToken)
        } else {
            require(name.isNotBlank() && name.length <= 100) { "Nama akun Cloudflare wajib diisi." }
        }
        val cv = ContentValues().apply {
            put("name", name.trim())
            if (cleanToken != null) {
                put("token", cleanToken)
                if (accountId != null) put("account_id", accountId.trim())
                if (accountName != null) put("account_name", accountName.trim())
            }
        }
        db.update("cf_accounts", cv, "id = ?", arrayOf(id.toString()))
        getCfAccount(id)?.toOut()
    }

    override fun deleteCfAccount(id: Int): Boolean = synchronized(lock) {
        db.delete("cf_accounts", "id = ?", arrayOf(id.toString())) > 0
    }

    private fun readServerOut(c: android.database.Cursor): ServerOut {
        return ServerOut(
            id = c.getInt(c.getColumnIndexOrThrow("id")),
            name = c.getString(c.getColumnIndexOrThrow("name")),
            host = c.getString(c.getColumnIndexOrThrow("host")),
            port = c.getInt(c.getColumnIndexOrThrow("port")),
            username = c.getString(c.getColumnIndexOrThrow("username")),
            authType = c.getString(c.getColumnIndexOrThrow("auth_type")),
            groupName = c.getString(c.getColumnIndexOrThrow("group_name")),
            color = c.getString(c.getColumnIndexOrThrow("color")),
            hasPassword = c.getStringOrNull("password") != null,
            hasPrivateKey = c.getStringOrNull("private_key") != null,
            hostKeyFingerprint = c.getStringOrNull("host_key_fp")
        )
    }

    private fun readBookmarkOut(c: android.database.Cursor): BookmarkOut {
        return BookmarkOut(
            id = c.getInt(c.getColumnIndexOrThrow("id")),
            serverId = c.getInt(c.getColumnIndexOrThrow("server_id")),
            name = c.getString(c.getColumnIndexOrThrow("name")),
            path = c.getString(c.getColumnIndexOrThrow("path")),
            startupCmd = c.getString(c.getColumnIndexOrThrow("startup_cmd"))
        )
    }

    private fun readSnippetOut(c: android.database.Cursor): SnippetOut {
        return SnippetOut(
            id = c.getInt(c.getColumnIndexOrThrow("id")),
            name = c.getString(c.getColumnIndexOrThrow("name")),
            command = c.getString(c.getColumnIndexOrThrow("command"))
        )
    }

    private fun readCfAccountOut(c: android.database.Cursor): CFAccountOut {
        val token = c.getString(c.getColumnIndexOrThrow("token"))
        return CFAccountOut(
            id = c.getInt(c.getColumnIndexOrThrow("id")),
            name = c.getString(c.getColumnIndexOrThrow("name")),
            tokenMasked = maskCfToken(token),
            accountId = c.getString(c.getColumnIndexOrThrow("account_id")),
            accountName = c.getString(c.getColumnIndexOrThrow("account_name")),
            createdAt = c.getString(c.getColumnIndexOrThrow("created_at"))
        )
    }

    private fun readCfAccountWithSecret(c: android.database.Cursor): CFAccountWithSecret {
        val token = c.getString(c.getColumnIndexOrThrow("token"))
        return CFAccountWithSecret(
            id = c.getInt(c.getColumnIndexOrThrow("id")),
            name = c.getString(c.getColumnIndexOrThrow("name")),
            token = token,
            tokenMasked = maskCfToken(token),
            accountId = c.getString(c.getColumnIndexOrThrow("account_id")),
            accountName = c.getString(c.getColumnIndexOrThrow("account_name")),
            createdAt = c.getString(c.getColumnIndexOrThrow("created_at"))
        )
    }

    private fun android.database.Cursor.getStringOrNull(col: String): String? {
        val idx = getColumnIndexOrThrow(col)
        return if (isNull(idx)) null else getString(idx)
    }
}

fun CFAccountWithSecret.toOut() = CFAccountOut(
    id = id,
    name = name,
    tokenMasked = tokenMasked,
    accountId = accountId,
    accountName = accountName,
    createdAt = createdAt
)

fun maskCfToken(token: String): String {
    if (token.length <= 10) return "******"
    return "${token.take(5)}...${token.takeLast(4)}"
}

fun validateCfAccountIn(name: String, token: String) {
    require(name.isNotBlank() && name.length <= 100) { "Nama akun Cloudflare wajib diisi (maks 100 karakter)." }
    require(token.isNotBlank() && token.length in 10..255) { "Token Cloudflare harus antara 10 dan 255 karakter." }
}

fun validateServerIn(data: ServerIn) {
    require(data.name.isNotBlank() && data.name.length <= 100) { "Nama server wajib diisi (maks 100 karakter)." }
    require(data.host.isNotBlank() && data.host.length <= 255) { "Host wajib diisi (maks 255 karakter)." }
    require(data.port in 1..65535) { "Port harus antara 1 dan 65535." }
    require(data.username.isNotBlank() && data.username.length <= 100) { "Username wajib diisi (maks 100 karakter)." }
    require(data.authType == "password" || data.authType == "key") { "auth_type harus 'password' atau 'key'." }
}

fun validateBookmarkIn(data: BookmarkIn) {
    require(data.name.isNotBlank() && data.name.length <= 100) { "Nama path wajib diisi." }
    require(data.path.isNotBlank() && data.path.length <= 1024) { "Path wajib diisi." }
    require(data.startupCmd.length <= 1024) { "Startup command terlalu panjang." }
}

fun validateSnippetIn(data: SnippetIn) {
    require(data.name.isNotBlank() && data.name.length <= 100) { "Nama snippet wajib diisi." }
    require(data.command.isNotBlank() && data.command.length <= 4096) { "Perintah snippet wajib diisi." }
}
