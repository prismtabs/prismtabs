package io.prismtabs.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Plain JUnit 5, no platform harness — the same split as [AddToActiveGroupLabelTest]: the wording of
 * the tool window's removal item is a pure function of the selection's shape, while the menu that
 * carries it needs a live IDE and belongs to `uiTest`.
 *
 * The single-file wording is the load-bearing case: it must stay character-identical to the tab
 * popup's own "Remove from Group", since it is the same operation seen from the other surface.
 */
class RemoveFromGroupTextTest {

    @Test
    fun `one file reads exactly as the tab popup's item does`() {
        assertEquals("Remove from Group", removeFromGroupText(fileCount = 1, groupCount = 1))
    }

    @Test
    fun `an empty selection still has a label, since the platform reads text before enablement`() {
        assertEquals("Remove from Group", removeFromGroupText(fileCount = 0, groupCount = 0))
    }

    @Test
    fun `several files from one group state the count`() {
        assertEquals("Remove 3 Files from Group", removeFromGroupText(fileCount = 3, groupCount = 1))
    }

    @Test
    fun `several files from several groups pluralise the group half too`() {
        // "Remove 4 Files from Group" would be a lie about where they are coming from.
        assertEquals("Remove 4 Files from Their Groups", removeFromGroupText(fileCount = 4, groupCount = 3))
    }
}
