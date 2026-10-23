package io.prismtabs.ui

import io.prismtabs.model.TabGroup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Plain JUnit 5, no platform harness — which is the whole reason `deleteConfirmationMessage` is a
 * function of its own rather than a string built inline in [confirmAndDelete] (that one needs a
 * live `Project` and a modal dialog, so it belongs to `uiTest`). It is also why `hidden` is a
 * parameter: deriving it needs a `FileEditorManager`.
 *
 * The hidden-group line is what these cases are here for: it is the last warning a user gets before
 * deleting a group takes its remembered tabs with it (see the function's own doc), so its condition
 * is worth pinning down rather than leaving to whoever next edits the string.
 */
class DeleteConfirmationMessageTest {

    private val group = TabGroup(id = "red", name = "Red", lightRgb = 0xF6AEA9, darkRgb = 0x9C3333)

    @Test
    fun `a visible group's prompt names it and says what deleting costs`() {
        val visible = group.copy(rememberedTabUrls = listOf("file:///A.kt"))
        assertEquals(
            "Delete \"Red\"? Its files will be unassigned.",
            deleteConfirmationMessage(visible, hidden = false),
        )
    }

    @Test
    fun `a hidden group warns that its remembered tabs will be forgotten`() {
        val hidden = group.copy(rememberedTabUrls = listOf("file:///A.kt", "file:///B.kt", "file:///C.kt"))
        assertEquals(
            "Delete \"Red\"? Its files will be unassigned.\n\n" +
                "This group is hidden. The 3 tabs it would reopen will be forgotten.",
            deleteConfirmationMessage(hidden, hidden = true),
        )
    }

    @Test
    fun `the warning stays grammatical for a single remembered tab`() {
        val hidden = group.copy(rememberedTabUrls = listOf("file:///A.kt"))
        val message = deleteConfirmationMessage(hidden, hidden = true)
        assertTrue(
            message.endsWith("The tab it would reopen will be forgotten."),
            "one tab must not read \"the 1 tabs it would reopen\", got $message",
        )
    }

    /**
     * A group can now be hidden with nothing remembered — its files have simply never been opened —
     * so the warning has to stay off a dialog that would otherwise announce the loss of no tabs.
     */
    @Test
    fun `a hidden group with no remembered tabs leaves nothing to warn about`() {
        val message = deleteConfirmationMessage(group, hidden = true)
        assertFalse(
            message.contains("hidden"),
            "a hidden group with nothing remembered must not warn about tabs, got $message",
        )
    }
}
