package id.termdeck.app.data

import id.termdeck.app.ssh.ConnectionPool
import id.termdeck.app.ssh.SshPoolBackend
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

const val DB_FILE = "termdeck.vault"
const val SALT_FILE = "vault.key"
const val DEFAULT_INITIAL_PIN = "7821724"

class Vault(
    private val folder: File,
    private val initialPin: String = DEFAULT_INITIAL_PIN,
    clock: () -> Double = { System.nanoTime() / 1_000_000_000.0 },
    private val storeFactory: (File, ByteArray) -> StoreBackend = { file, key -> Store(file, key) },
    private val poolFactory: (StoreBackend) -> SshPoolBackend = { store -> ConnectionPool(store) }
) {
    private val dbFile = File(folder, DB_FILE)
    private val saltFile = File(folder, SALT_FILE)
    private val lockObj = Any()
    private val throttle = PinThrottle(clock)
    private var key: ByteArray? = null
    private val sessions = mutableSetOf<String>()

    @Volatile
    var store: StoreBackend? = null
        private set

    @Volatile
    var pool: SshPoolBackend? = null
        private set

    val unlocked: Boolean
        get() = store != null

    private fun getOrCreateSalt(): ByteArray {
        folder.mkdirs()
        if (saltFile.exists()) {
            return saltFile.readBytes()
        }
        val salt = ByteArray(32)
        SecureRandom().nextBytes(salt)
        saltFile.writeBytes(salt)
        return salt
    }

    private fun derive(pin: String): ByteArray {
        return deriveVaultKey(pin, getOrCreateSalt())
    }

    fun unlock(pin: String): String {
        synchronized(lockObj) {
            throttle.check()
            val derived = derive(pin)
            val currentKey = key
            val ok = if (currentKey != null) {
                MessageDigest.isEqual(derived, currentKey)
            } else if (!dbFile.exists() || dbFile.length() == 0L) {
                if (dbFile.exists() && dbFile.length() == 0L) {
                    dbFile.delete()
                }
                val match = MessageDigest.isEqual(
                    pin.toByteArray(Charsets.UTF_8),
                    initialPin.toByteArray(Charsets.UTF_8)
                )
                if (match) {
                    openInternal(derived)
                }
                match
            } else {
                try {
                    openInternal(derived)
                    true
                } catch (_: WrongKeyException) {
                    false
                }
            }

            if (!ok) {
                throttle.recordFailure()
                throw InvalidPinException("PIN salah.")
            }

            throttle.reset()
            val session = generateRandomToken(32)
            sessions.add(session)
            return session
        }
    }

    private fun openInternal(derivedKey: ByteArray) {
        val openedStore = storeFactory(dbFile, derivedKey)
        store = openedStore
        pool = poolFactory(openedStore)
        key = derivedKey
    }

    fun checkSession(session: String?): Boolean {
        if (session.isNullOrEmpty() || store == null) return false
        synchronized(lockObj) {
            val target = session.toByteArray(Charsets.UTF_8)
            return sessions.any { known ->
                MessageDigest.isEqual(target, known.toByteArray(Charsets.UTF_8))
            }
        }
    }

    suspend fun lock() {
        val oldPool: SshPoolBackend?
        val oldStore: StoreBackend?
        synchronized(lockObj) {
            oldPool = pool
            oldStore = store
            sessions.clear()
            key = null
            store = null
            pool = null
        }
        oldPool?.closeAll()
        oldStore?.close()
    }

    fun changePin(oldPin: String, newPin: String) {
        synchronized(lockObj) {
            throttle.check()
            val currentStore = store
            val currentKey = key
            if (currentStore == null || currentKey == null) {
                throw InvalidPinException("Aplikasi terkunci.")
            }
            if (!MessageDigest.isEqual(derive(oldPin), currentKey)) {
                throttle.recordFailure()
                throw InvalidPinException("PIN lama salah.")
            }
            validatePin(newPin)
            val newKey = derive(newPin)
            currentStore.rekey(newKey)
            key = newKey
        }
    }
}
