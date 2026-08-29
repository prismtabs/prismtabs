package io.prismtabs.ui

import io.prismtabs.model.TabGroup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Plain JUnit 5, no platform harness — which is the whole reason `deleteConfirmationMessage` is a
 * function of its own rather than a string built inline in [confirmAndDelete] (that one needs a
 * live `Project` and a modal dialog, so it belongs to `uiTest`). Same split as
 * [io.prismtabs.license.PrismTabsLicense.decide] and its test.
 *
 * The hidden-group line is what these cases are here for: it is the last warning a user gets before
 * deleting a group takes its remembered tabs with it (see the function's own doc), so its condition
 * is worth pinning down rather than leaving to whoever next edits the string.
 */
class DeleteConfirmationMessageTest {

    private val visible = TabGroup(id = "red", name = "Red", lightRgb = 0xF6AEA9, darkRgb = 0x9C3333)

    @Test
    fun `a visible group's prompt names it and says what deleting costs`() {
        assertEquals("Delete \"Red\"? Its files will be unassigned.", deleteConfirmationMessage(visible))
    }

    @Test
    fun `a hidden group warns that its remembered tabs will not be reopened`() {
        val hidden = visible.copy(
            collapsed = true,
            collapsedFileUrls = listOf("file:///A.kt", "file:///B.kt", "file:///C.kt"),
        )
        assertEquals(
            "Delete \"Red\"? Its files will be unassigned.\n\n" +
                "This group is hidden. The 3 tabs it closed will not be reopened.",
            deleteConfirmationMessage(hidden),
        )
    }

    @Test
    fun `the warning stays grammatical for a single remembered tab`() {
        val hidden = visible.copy(collapsed = true, collapsedFileUrls = listOf("file:///A.kt"))
        assertTrue(
            deleteConfirmationMessage(hidden).endsWith("The tab it closed will not be reopened."),
            "one tab must not read \"the 1 tabs it closed\", got ${deleteConfirmationMessage(hidden)}",
        )
    }

    @Test
    fun `hiding a group with nothing open leaves nothing to warn about`() {
        val hidden = visible.copy(collapsed = true, collapsedFileUrls = emptyList())
        assertFalse(
            deleteConfirmationMessage(hidden).contains("hidden"),
            "a hidden group that closed no tabs must not warn about tabs, got ${deleteConfirmationMessage(hidden)}",
        )
    }
}
