package io.prismtabs.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Plain JUnit 5, no platform harness — the same split (and the same reason for it) as
 * [DeleteConfirmationMessageTest]: the caption over "Remove from Group" is a string, while the
 * separator that carries it needs a live popup and belongs to `uiTest`.
 *
 * The truncation is what these cases are really here for. A group name is free text with only a
 * non-blank check on it, and this caption is the one row of the popup whose width the user controls,
 * so the cap is worth pinning down rather than leaving to whoever next edits the wording.
 */
class CurrentGroupCaptionTest {

    @Test
    fun `the caption names the group the file is in`() {
        assertEquals("In group: Auth", currentGroupCaption("Auth"))
    }

    @Test
    fun `a name at the cap is still shown in full`() {
        val exactly40 = "A".repeat(40)
        assertEquals("In group: $exactly40", currentGroupCaption(exactly40))
    }

    @Test
    fun `a name past the cap is truncated so one group cannot stretch the popup`() {
        val caption = currentGroupCaption("A".repeat(200))
        assertTrue(
            caption.length < "In group: ".length + 50,
            "an unbounded name must not reach the caption, got a ${caption.length}-char caption",
        )
        assertTrue(caption.endsWith("…"), "a truncated name must say so, got $caption")
    }

    @Test
    fun `truncation uses the same ellipsis character as the popup's own labels`() {
        // "Add to Group…", "Rename…" and the rest are single-character ellipses; three full stops
        // here would read as a different typeface's worth of sloppiness one row apart.
        assertTrue(
            !currentGroupCaption("A".repeat(200)).contains("..."),
            "the caption must not fall back to three full stops",
        )
    }
}
