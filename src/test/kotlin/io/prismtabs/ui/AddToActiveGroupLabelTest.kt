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
 * The wording is worth pinning because the row is read against its neighbours: it sits directly
 * above "Add to Group…", and the two are meant to scan as one pair — same verb, one naming the
 * destination outright, one opening the list to choose from.
 */
class AddToActiveGroupLabelTest {

    @Test
    fun `the label names the group the file would join`() {
        assertEquals("Add to Group \"Auth\"", addToActiveGroupLabel("Auth"))
    }

    @Test
    fun `the name is quoted, so the row cannot be mistaken for the list step below it`() {
        // "Add to Group…" opens a chooser; this row commits to one group. The quoted name is the
        // only thing distinguishing them at a glance, so it is not decoration.
        assertTrue(
            addToActiveGroupLabel("Auth").endsWith("\"Auth\""),
            "expected the group name quoted, got ${addToActiveGroupLabel("Auth")}",
        )
    }

    @Test
    fun `a name at the cap is still shown in full`() {
        val exactly40 = "A".repeat(40)
        assertEquals("Add to Group \"$exactly40\"", addToActiveGroupLabel(exactly40))
    }

    @Test
    fun `a name past the cap is truncated so one group cannot stretch the popup`() {
        val label = addToActiveGroupLabel("A".repeat(200))
        assertTrue(
            label.length < "Add to Group \"\"".length + 50,
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
