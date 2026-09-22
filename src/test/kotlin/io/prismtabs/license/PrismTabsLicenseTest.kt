package io.prismtabs.license

import com.intellij.ui.LicensingFacade
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Plain JUnit 5, same split as [LicenseSignatureVerificationTest]: [PrismTabsLicense.decide] is the
 * freemium truth table with the two reads ([PrismTabsLicense.paidFeaturesEnabled]'s own descriptor
 * and `LicensingFacade` calls) lifted out as parameters, so the paywall's actual
 * decision is testable without an IDE. Every row matters — inverting either operand silently flips
 * the plugin between "everything free" and "everything locked", and nothing else would catch it.
 */
class PrismTabsLicenseTest {

    @Test
    fun `an unlicensed paid build falls back to the free tier`() {
        assertFalse(PrismTabsLicense.decide(isPaidBuild = true, isLicensed = false))
    }

    @Test
    fun `a licensed paid build gets the paid features`() {
        assertTrue(PrismTabsLicense.decide(isPaidBuild = true, isLicensed = true))
    }

    /**
     * `null` is "the facade hasn't finished starting up" (see [PrismTabsLicense.isLicensed]) — no
     * positive evidence of a missing licence, so a subscriber must not be locked out during their
     * IDE's first second alive.
     */
    @Test
    fun `a paid build whose facade has not answered yet gets the paid features`() {
        assertTrue(PrismTabsLicense.decide(isPaidBuild = true, isLicensed = null))
    }

    /**
     * The clause that keeps a build with no `<product-descriptor>` out of the paywall: with no
     * product to license, `isLicensed()` answers `false` for everyone, and without this such a
     * build would lock features that were never for sale.
     */
    @Test
    fun `a build that is not sold at all is never gated, whatever the facade says`() {
        assertTrue(PrismTabsLicense.decide(isPaidBuild = false, isLicensed = false))
        assertTrue(PrismTabsLicense.decide(isPaidBuild = false, isLicensed = null))
        assertTrue(PrismTabsLicense.decide(isPaidBuild = false, isLicensed = true))
    }

    // ---------------------------------------------------------------------------------------------
    // isLicensed(): the other half of paidFeaturesEnabled(), and the half [decide] deliberately
    // knows nothing about — which stamp shapes count as a licence.
    //
    // Runs under plain JUnit 5 like everything above, but only because the facade is installed by
    // assigning `LicensingFacade.INSTANCE` rather than by calling `LicensingFacade.setInstance`.
    // That is not a stylistic choice: `setInstance` also publishes `LicenseStateListener.TOPIC` on
    // `ApplicationManager.getApplication().getMessageBus()`, which is a guaranteed NPE with no
    // Application — measured here, not predicted. `getInstance()` is a bare `getstatic INSTANCE`
    // and `getConfirmationStamp` is a bare read of the public `confirmationStamps` map, so writing
    // the field is exactly what `isLicensed()` sees through the real code path. All four members
    // named here were checked with `javap` per CLAUDE.md rather than assumed.
    //
    // `PaywallUiTest` keeps using `setInstance` for the same job: it runs inside a live sandbox IDE,
    // where the publish is both harmless and more faithful.
    //
    // What these cannot do, and no test can: prove a *valid* licence is accepted, or that the two
    // prefixes route to the verifier each belongs to. Both verifiers reject every input that isn't
    // genuinely JetBrains-signed (see LicenseSignatureVerificationTest), so a swapped dispatch would
    // still answer `false` here and stay invisible. What is covered instead is the property that
    // actually protects the paid tier: every shape that is *not* a licence fails closed, and none of
    // them throws — a throw from `isLicensed` would propagate out of `paidFeaturesEnabled` into the
    // tool window's `refresh`, the file-open listener, and the palette page alike.
    // ---------------------------------------------------------------------------------------------

    /**
     * The single point where the deprecated field is touched, so the suppression stays pinned to the
     * one line that has actually justified it. The deprecation points callers at `setInstance`,
     * which is right everywhere except here — see the note above for why that overload cannot run
     * without an Application.
     */
    @Suppress("DEPRECATION")
    private fun setFacade(facade: LicensingFacade?) {
        LicensingFacade.INSTANCE = facade
    }

    /** `LicensingFacade.INSTANCE` is process-global static state; a leaked one would silently change
     * what every later test in this JVM reads. */
    @AfterEach
    fun clearFacade() = setFacade(null)

    private fun installFacade(stamps: Map<String, String>) =
        setFacade(LicensingFacade().apply { confirmationStamps = stamps })

    @Test
    fun `no facade at all reads as not-yet-known, not as unlicensed`() {
        setFacade(null)
        assertNull(PrismTabsLicense.isLicensed(), "a missing facade is startup, not a missing licence")
    }

    @Test
    fun `a facade holding no stamps is unlicensed`() {
        installFacade(emptyMap())
        assertFalse(PrismTabsLicense.isLicensed()!!)
    }

    /** The stamp must be looked up under *our* product code. Reading whatever stamp happened to be
     * present would hand the paid tier to anyone holding a licence for any other plugin. */
    @Test
    fun `a stamp issued for a different product code is not our licence`() {
        installFacade(mapOf("PSOMEOTHERPLUGIN" to "key:id-AA==-AA==-AA=="))
        assertFalse(PrismTabsLicense.isLicensed()!!)
    }

    @Test
    fun `a stamp with neither known prefix is rejected`() {
        installFacade(mapOf(PrismTabsLicense.PRODUCT_CODE to "definitely-not-a-stamp"))
        assertFalse(PrismTabsLicense.isLicensed()!!)
    }

    @Test
    fun `an empty stamp is rejected`() {
        installFacade(mapOf(PrismTabsLicense.PRODUCT_CODE to ""))
        assertFalse(PrismTabsLicense.isLicensed()!!)
    }

    /** A bare prefix with nothing after it: the shape most likely to trip a verifier into throwing
     * rather than returning, which `paidFeaturesEnabled` has no handler for. */
    @Test
    fun `a key-prefixed stamp with a malformed body is rejected, not thrown`() {
        installFacade(mapOf(PrismTabsLicense.PRODUCT_CODE to "key:"))
        assertFalse(PrismTabsLicense.isLicensed()!!)
    }

    @Test
    fun `a server-stamp-prefixed stamp with a malformed body is rejected, not thrown`() {
        installFacade(mapOf(PrismTabsLicense.PRODUCT_CODE to "stamp:"))
        assertFalse(PrismTabsLicense.isLicensed()!!)
    }

    /** Well-formed in shape, unsigned in fact — the closest a test can get to a forged licence, and
     * the case that matters most: structure alone must never be mistaken for a signature. */
    @Test
    fun `a well-formed but unsigned key is rejected`() {
        val fakeBase64 = java.util.Base64.getEncoder().encodeToString("not a real payload".toByteArray())
        installFacade(mapOf(PrismTabsLicense.PRODUCT_CODE to "key:id-$fakeBase64-$fakeBase64-$fakeBase64"))
        assertFalse(PrismTabsLicense.isLicensed()!!)
    }

    @Test
    fun `a well-formed but unsigned server stamp is rejected`() {
        val fakeBase64 = java.util.Base64.getEncoder().encodeToString("not a real cert".toByteArray())
        installFacade(
            mapOf(
                PrismTabsLicense.PRODUCT_CODE to
                    "stamp:machine:${System.currentTimeMillis()}:machine:SHA1withRSA:$fakeBase64:$fakeBase64",
            ),
        )
        assertFalse(PrismTabsLicense.isLicensed()!!)
    }
}
