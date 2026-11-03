package id.termdeck.app

import id.termdeck.app.data.PinThrottle
import id.termdeck.app.data.TooManyAttemptsException
import id.termdeck.app.data.deriveVaultKey
import id.termdeck.app.data.rawKeyPassphrase
import id.termdeck.app.data.validateNewPin
import id.termdeck.app.data.validatePin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PinLogicTest {

    @Test
    fun `validatePin accepts 4 to 12 digits and rejects non-digits or wrong length`() {
        validatePin("7821724")
        validatePin("1234")
        validatePin("123456789012")

        assertThrows(IllegalArgumentException::class.java) { validatePin("123") }
        assertThrows(IllegalArgumentException::class.java) { validatePin("1234567890123") }
        assertThrows(IllegalArgumentException::class.java) { validatePin("12a4") }
    }

    @Test
    fun `validateNewPin rejects legacy default PIN 7821724`() {
        validateNewPin("123456")
        assertThrows(IllegalArgumentException::class.java) { validateNewPin("7821724") }
    }

    @Test
    fun `deriveVaultKey produces deterministic 32-byte key via scrypt`() {
        val salt = ByteArray(32) { it.toByte() }
        val k1 = deriveVaultKey("7821724", salt)
        val k2 = deriveVaultKey("7821724", salt)
        val k3 = deriveVaultKey("1234567", salt)

        assertEquals(32, k1.size)
        assertTrue(k1.contentEquals(k2))
        assertNotEquals(k1.toList(), k3.toList())
    }

    @Test
    fun `PinThrottle allows 5 attempts then blocks exponentially up to 900s`() {
        var now = 100.0
        val throttle = PinThrottle { now }

        repeat(5) {
            throttle.check()
            throttle.recordFailure()
        }

        // After 5th failure, blocked for 30s
        val ex1 = assertThrows(TooManyAttemptsException::class.java) { throttle.check() }
        assertEquals(30.0, ex1.retryAfter, 0.01)

        // Advance 31s -> allowed, 6th failure -> blocked for 60s
        now += 31.0
        throttle.check()
        throttle.recordFailure()
        val ex2 = assertThrows(TooManyAttemptsException::class.java) { throttle.check() }
        assertEquals(60.0, ex2.retryAfter, 0.01)

        // Reset clears block
        throttle.reset()
        throttle.check()
    }

    @Test
    fun `rawKeyPassphrase formats 32-byte key as SQLCipher raw hex specifier`() {
        val key = ByteArray(32) { it.toByte() }
        val spec = rawKeyPassphrase(key)
        assertTrue(spec.startsWith("x'") && spec.endsWith("'"))
        assertEquals(2 + 64 + 1, spec.length)
    }
}
