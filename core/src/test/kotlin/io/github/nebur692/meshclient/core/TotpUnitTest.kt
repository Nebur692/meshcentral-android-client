package io.github.nebur692.meshclient.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The RFC 6238 test vectors (SHA-1), last 6 digits, as MeshCentral's otplib. / Los vectores de prueba del RFC 6238. */
class TotpUnitTest {
    private val secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ" // "12345678901234567890"

    @Test fun `the RFC 6238 vectors give their codes`() {
        assertEquals("287082", Totp.code(secret, 59_000))
        assertEquals("081804", Totp.code(secret, 1_111_111_109_000))
        assertEquals("050471", Totp.code(secret, 1_111_111_111_000))
        assertEquals("005924", Totp.code(secret, 1_234_567_890_000))
        assertEquals("279037", Totp.code(secret, 2_000_000_000_000))
        // Spaces, lower case and padding, as the secret is often shown. / Espacios, minúsculas y relleno, como se suele enseñar.
        assertEquals("287082", Totp.code("gezd gnbv gy3t qojq gezd gnbv gy3t qojq==", 59_000))
    }

    @Test fun `the seconds left and what is a secret`() {
        assertEquals(30, Totp.secondsLeft(60_000))
        assertEquals(1, Totp.secondsLeft(89_000))
        assertTrue(Totp.validSecret(secret))
        assertFalse(Totp.validSecret("short"))
        assertFalse(Totp.validSecret("GEZDGNBVGY3TQOJ1")) // 1 is not base32 / el 1 no es base32
    }
}
