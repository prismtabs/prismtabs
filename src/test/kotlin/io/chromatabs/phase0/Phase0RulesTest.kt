package io.chromatabs.phase0

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Deliberately a plain JUnit 5 test with no IntelliJ test harness.
 *
 * This is the pattern to keep: push real logic into platform-free classes and
 * test them fast here. Reserve BasePlatformTestCase for the handful of things
 * that genuinely need a running IDE (EP registration, action visibility).
 */
class Phase0RulesTest {

    @Test
    fun `files with Test in the name get the test tint`() {
        assertEquals(Phase0Rules.Tint.TEST, Phase0Rules.tintFor("Phase0RulesTest.kt"))
        assertEquals(Phase0Rules.Tint.TEST, Phase0Rules.tintFor("integration_test_helper.kt"))
    }

    @Test
    fun `markdown files get the markdown tint`() {
        assertEquals(Phase0Rules.Tint.MARKDOWN, Phase0Rules.tintFor("README.md"))
    }

    @Test
    fun `test naming wins over extension when both match`() {
        assertEquals(Phase0Rules.Tint.TEST, Phase0Rules.tintFor("TestNotes.md"))
    }

    @Test
    fun `everything else is untinted`() {
        assertEquals(Phase0Rules.Tint.NONE, Phase0Rules.tintFor("Application.kt"))
        assertEquals(Phase0Rules.Tint.NONE, Phase0Rules.tintFor("build.gradle.kts"))
    }
}
