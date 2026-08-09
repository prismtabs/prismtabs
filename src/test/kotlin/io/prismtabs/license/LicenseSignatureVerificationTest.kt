package io.prismtabs.license

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * Plain JUnit 5, no platform harness — [LicenseSignatureVerification] deliberately has no
 * `LicensingFacade` dependency (see its doc comment), so it's testable the same way
 * `TabGroupServiceTest` is. There's no way to construct a genuinely JetBrains-signed licence in a
 * test, so this covers the reject paths: malformed input must fail closed, never throw.
 */
class LicenseSignatureVerificationTest {

    @Test
    fun `key with the wrong number of dash-separated parts is rejected`() {
        assertFalse(LicenseSignatureVerification.isKeyValid("only-three-parts"))
    }

    @Test
    fun `key with non-base64 parts is rejected, not thrown`() {
        assertFalse(LicenseSignatureVerification.isKeyValid("id-not base64!-not base64!-not base64!"))
    }

    @Test
    fun `key with well-formed but unsigned parts is rejected`() {
        val fakeBase64 = java.util.Base64.getEncoder().encodeToString("not a real payload".toByteArray())
        assertFalse(LicenseSignatureVerification.isKeyValid("id-$fakeBase64-$fakeBase64-$fakeBase64"))
    }

    @Test
    fun `server stamp with too few colon-separated parts is rejected`() {
        assertFalse(LicenseSignatureVerification.isLicenseServerStampValid("machine:123"))
    }

    @Test
    fun `server stamp with a non-numeric timestamp is rejected, not thrown`() {
        assertFalse(
            LicenseSignatureVerification.isLicenseServerStampValid("machine:not-a-number:machine:SHA1withRSA:AA==:AA=="),
        )
    }

    @Test
    fun `server stamp with well-formed but unsigned parts is rejected`() {
        val fakeBase64 = java.util.Base64.getEncoder().encodeToString("not a real cert".toByteArray())
        assertFalse(
            LicenseSignatureVerification.isLicenseServerStampValid(
                "machine:${System.currentTimeMillis()}:machine:SHA1withRSA:$fakeBase64:$fakeBase64",
            ),
        )
    }
}
