package io.prismtabs.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Plain JUnit 5, no platform harness — the same split (and the same reason for it) as
 * [CurrentGroupCaptionTest]: the label of the active-group shortcut is a string, while the popup row
 * and the Project view menu item that carry it need a live IDE and belong to `uiTest`.
 *
 * One label serves both surfaces on purpose, so these cases are as much about that as about the
 * wording: the row in PrismTabs' own popup and the item in the Project view's context menu must
 * read identically, or the same operation would look like two.
 */
class AddToActiveGroupLabelTest {

    @Test
    fun `the label names the group the file would join`() {
        assertEquals("Add to Tab Group \"Auth\"", addToActiveGroupLabel("Auth"))
    }

    @Test
    fun `the label says Tab Group, not Group, so it reads outside PrismTabs' own popup`() {
        // The Project view's context menu is shared with the platform and every other plugin
        // installed; "Add to Group" there would name nothing in particular.
        assertTrue(
            addToActiveGroupLabel("Auth").startsWith("Add to Tab Group "),
            "expected the qualified noun, got ${addToActiveGroupLabel("Auth")}",
        )
    }

    @Test
    fun `a name at the cap is still shown in full`() {
        val exactly40 = "A".repeat(40)
        assertEquals("Add to Tab Group \"$exactly40\"", addToActiveGroupLabel(exactly40))
    }

    @Test
    fun `a name past the cap is truncated so one group cannot stretch the popup`() {
        val label = addToActiveGroupLabel("A".repeat(200))
        assertTrue(
            label.length < "Add to Tab Group \"\"".length + 50,
            "an unbounded name must not reach the label, got a ${label.length}-char label",
        )
        assertTrue(label.endsWith("…\""), "a truncated name must say so, got $label")
    }

    @Test
    fun `truncation uses the same ellipsis character as the popup's own labels`() {
        assertFalse(
            addToActiveGroupLabel("A".repeat(200)).contains("..."),
            "the label must not fall back to three full stops",
        )
    }
}
