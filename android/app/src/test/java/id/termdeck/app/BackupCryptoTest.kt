package id.termdeck.app

import id.termdeck.app.data.*
import org.junit.Assert.*
import org.junit.Test

class BackupCryptoTest {

    @Test
    fun testEncryptionRoundtripSuccess() {
        val payload = BackupPayload(
            version = 1,
            servers = listOf(
                BackupServerItem(
                    name = "Ubuntu Server",
                    host = "192.168.1.50",
                    port = 22,
                    username = "ubuntu",
                    authType = "password",
                    password = "my-secret-pw",
                    bookmarks = listOf(
                        BackupBookmarkItem(name = "App Dir", path = "/var/www/app")
                    )
                )
            ),
            snippets = listOf(
                BackupSnippetItem(name = "Restart Nginx", command = "systemctl restart nginx")
            )
        )

        val pin = "123456"
        val envelope = BackupCrypto.encryptPayload(payload, pin)

        assertEquals("TERMDECK_BACKUP", envelope.magic)
        assertEquals(1, envelope.version)
        assertEquals("AES-256-GCM", envelope.cipher)
        assertTrue(envelope.ciphertextHex.isNotEmpty())

        val decrypted = BackupCrypto.decryptEnvelope(envelope, pin)
        assertEquals(1, decrypted.servers.size)
        assertEquals("Ubuntu Server", decrypted.servers[0].name)
        assertEquals("my-secret-pw", decrypted.servers[0].password)
        assertEquals(1, decrypted.servers[0].bookmarks.size)
        assertEquals("/var/www/app", decrypted.servers[0].bookmarks[0].path)
        assertEquals(1, decrypted.snippets.size)
        assertEquals("Restart Nginx", decrypted.snippets[0].name)
    }

    @Test
    fun testDecryptionFailsOnWrongPin() {
        val payload = BackupPayload(
            version = 1,
            servers = listOf(BackupServerItem(name = "S1", host = "10.0.0.1", username = "root"))
        )
        val envelope = BackupCrypto.encryptPayload(payload, "correct-pin")

        assertThrows(BackupPinException::class.java) {
            BackupCrypto.decryptEnvelope(envelope, "wrong-pin")
        }
    }

    @Test
    fun testDecryptionFailsOnInvalidMagic() {
        val payload = BackupPayload(version = 1)
        val envelope = BackupCrypto.encryptPayload(payload, "pin").copy(magic = "BAD_MAGIC")

        assertThrows(BackupCryptoException::class.java) {
            BackupCrypto.decryptEnvelope(envelope, "pin")
        }
    }

    @Test
    fun testMergeIntoInMemoryStore() {
        val store = InMemoryStore(java.io.File("/tmp/dummy.db"), ByteArray(32))

        // Existing server
        val s1 = store.createServer(
            ServerIn(
                name = "Existing S1",
                host = "10.0.0.1",
                port = 22,
                username = "root",
                authType = "password",
                password = ""
            )
        )
        store.createBookmark(
            BookmarkIn(
                serverId = s1.id,
                name = "Old Path",
                path = "/old"
            )
        )

        // Backup has same host/port/username with a new password and extra bookmark, plus a brand new server S2
        val backup = BackupPayload(
            version = 1,
            servers = listOf(
                BackupServerItem(
                    name = "Existing S1",
                    host = "10.0.0.1",
                    port = 22,
                    username = "root",
                    authType = "password",
                    password = "updated-pw",
                    bookmarks = listOf(
                        BackupBookmarkItem(name = "Old Path", path = "/old"),
                        BackupBookmarkItem(name = "New Path", path = "/new")
                    )
                ),
                BackupServerItem(
                    name = "New S2",
                    host = "10.0.0.2",
                    port = 22,
                    username = "admin",
                    bookmarks = listOf(
                        BackupBookmarkItem(name = "Home", path = "/home/admin")
                    )
                )
            ),
            snippets = listOf(
                BackupSnippetItem(name = "Docker PS", command = "docker ps")
            )
        )

        val stats = BackupCrypto.mergeBackupIntoStore(store, backup)
        assertEquals(1, stats.serversAdded) // S2 added
        assertEquals(1, stats.serversUpdated) // S1 password updated
        assertEquals(2, stats.bookmarksAdded) // S1 /new + S2 /home/admin
        assertEquals(1, stats.snippetsAdded)

        val allServers = store.listServers()
        assertEquals(2, allServers.size)
        val secrets1 = store.getServerSecrets(s1.id)
        assertEquals("updated-pw", secrets1?.password)

        val bms1 = store.listBookmarks(s1.id)
        assertEquals(2, bms1.size)
    }
}
