package io.prismtabs.license

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The three `<product-descriptor>` invariants that nothing else in `./gradlew test` can see.
 *
 * Written after `267202a` shipped `release-version="1"`: the Plugin Verifier rejects a value with
 * fewer than two digits as an invalid *descriptor*, so `verifyPlugin` ran zero of its eight
 * verifications and reported green anyway. That made the whole IDE matrix silently unchecked, and a
 * Marketplace upload would have been refused the same way. The only other guard on any of this is
 * a comment in `plugin.xml` asking a human to keep two files in step, which is what failed.
 *
 * Reads the descriptor off the test classpath — `src/main/resources`' own copy, not
 * `patchPluginXml`'s output — so it asserts on the file a developer edits.
 */
class ProductDescriptorTest {

    private val descriptor: Element = run {
        val xml = checkNotNull(javaClass.getResourceAsStream("/META-INF/plugin.xml")) {
            "plugin.xml is not on the test classpath"
        }
        val document = xml.use { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it) }
        val tags = document.getElementsByTagName("product-descriptor")
        assertEquals(1, tags.length, "expected exactly one <product-descriptor> in plugin.xml")
        tags.item(0) as Element
    }

    /**
     * The match [PrismTabsLicense.PRODUCT_CODE]'s doc calls for and cannot enforce: the code the
     * Marketplace issues stamps against lives in `plugin.xml`, while every stamp lookup goes through
     * the constant. A drift between them locks out every genuine subscriber, and `isLicensed()`
     * would answer `false` with nothing looking wrong.
     */
    @Test
    fun `the descriptor's product code is the one the licence check looks stamps up under`() {
        assertEquals(PrismTabsLicense.PRODUCT_CODE, descriptor.getAttribute("code"))
    }

    /** The rule that `267202a` broke, stated as the Verifier states it. */
    @Test
    fun `release-version is an integer of at least two digits`() {
        val releaseVersion = descriptor.getAttribute("release-version")
        assertTrue(
            releaseVersion.matches(Regex("\\d{2,}")),
            "release-version must be an integer with at least two digits, got: '$releaseVersion'",
        )
    }

    /**
     * And the rule that makes the corrected value *right* rather than merely accepted:
     * release-version is `pluginVersion`'s first two components with the dot dropped (1.4.14 -> 14).
     * Those two components are the *major release* in the Marketplace's sense, which PrismTabs cuts
     * as a minor version — deliberately, about once a year, with features in between shipped as
     * patches — and moving release-version resets every user's trial. A minor release that forgot to move it would
     * silently skip the reset it was cut for; moving it without one would hand every lapsed user a
     * fresh trial.
     *
     * The minor must also be a single digit: the Marketplace reads release-version's last digit as
     * a number of its own (20241 -> 2024 and 1), so 1.10 has no valid encoding — "110" would read as
     * 11.0. After 1.9 comes 2.0.
     *
     * `pluginVersion` comes from `gradle.properties` via the test task's system property — the same
     * single source `intellijPlatform.pluginConfiguration.version` reads.
     */
    @Test
    fun `release-version tracks pluginVersion's major and minor`() {
        val pluginVersion = checkNotNull(System.getProperty("prismtabs.pluginVersion")) {
            "the test task must pass gradle.properties' pluginVersion through as a system property"
        }
        val (major, minor) = pluginVersion.split(".")
        assertTrue(
            minor.matches(Regex("\\d")),
            "pluginVersion's minor must be a single digit, or release-version cannot encode it; " +
                "cut the next minor release instead (got $pluginVersion)",
        )
        assertEquals(
            major + minor,
            descriptor.getAttribute("release-version"),
            "release-version must stay in step with pluginVersion ($pluginVersion)",
        )
    }
}
