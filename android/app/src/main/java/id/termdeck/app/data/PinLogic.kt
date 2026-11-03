package id.termdeck.app.data

import org.bouncycastle.crypto.generators.SCrypt
import java.security.SecureRandom

private val PIN_REGEX = Regex("""^\d{4,12}$""")
const val SCRYPT_N = 65536
const val SCRYPT_R = 8
const val SCRYPT_P = 1
const val SCRYPT_DKLEN = 32

const val FREE_ATTEMPTS = 5
const val BASE_BLOCK_SECONDS = 30.0
const val MAX_BLOCK_SECONDS = 900.0

class InvalidPinException(message: String) : Exception(message)

class TooManyAttemptsException(val retryAfter: Double) :
    Exception("Terlalu banyak PIN salah. Coba lagi dalam ${retryAfter.toInt() + 1} detik.")

fun validatePin(pin: String) {
    if (!PIN_REGEX.matches(pin)) {
        throw IllegalArgumentException("PIN harus 4-12 digit angka.")
    }
}

fun validateNewPin(pin: String) {
    validatePin(pin)
    if (pin == DEFAULT_INITIAL_PIN) {
        throw IllegalArgumentException("PIN tidak boleh menggunakan PIN default lama.")
    }
}

fun deriveVaultKey(pin: String, salt: ByteArray): ByteArray {
    return SCrypt.generate(
        pin.toByteArray(Charsets.UTF_8),
        salt,
        SCRYPT_N,
        SCRYPT_R,
        SCRYPT_P,
        SCRYPT_DKLEN
    )
}

fun generateRandomToken(byteLength: Int = 32): String {
    val bytes = ByteArray(byteLength)
    SecureRandom().nextBytes(bytes)
    return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

class PinThrottle(
    private val clock: () -> Double = { System.nanoTime() / 1_000_000_000.0 }
) {
    private var failures: Int = 0
    private var blockedUntil: Double = 0.0

    fun check() {
        val remaining = blockedUntil - clock()
        if (remaining > 0) {
            throw TooManyAttemptsException(remaining)
        }
    }

    fun recordFailure() {
        failures += 1
        if (failures >= FREE_ATTEMPTS) {
            val exp = (failures - FREE_ATTEMPTS).coerceAtMost(10)
            val delay = (BASE_BLOCK_SECONDS * Math.pow(2.0, exp.toDouble())).coerceAtMost(MAX_BLOCK_SECONDS)
            blockedUntil = clock() + delay
        }
    }

    fun reset() {
        failures = 0
        blockedUntil = 0.0
    }
}
