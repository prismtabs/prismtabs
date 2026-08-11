package io.prismtabs.license

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Plain JUnit 5, same split as [LicenseSignatureVerificationTest]: [PrismTabsLicense.decide] is the
 * freemium truth table with the two platform reads ([PrismTabsLicense.paidFeaturesEnabled]'s own
 * `PluginManager` and `LicensingFacade` calls) lifted out as parameters, so the paywall's actual
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
     * The clause that lets the gates be wired before `plugin.xml` gains its `<product-descriptor>`:
     * with no product to license, `isLicensed()` answers `false` for everyone, and without this a
     * `runIde`/`uiTest`/locally built plugin would lock features that were never for sale.
     */
    @Test
    fun `a build that is not sold at all is never gated, whatever the facade says`() {
        assertTrue(PrismTabsLicense.decide(isPaidBuild = false, isLicensed = false))
        assertTrue(PrismTabsLicense.decide(isPaidBuild = false, isLicensed = null))
        assertTrue(PrismTabsLicense.decide(isPaidBuild = false, isLicensed = true))
    }
}
