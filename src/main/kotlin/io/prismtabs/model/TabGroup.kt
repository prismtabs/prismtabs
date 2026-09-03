package io.prismtabs.model

/**
 * [lightRgb]/[darkRgb] rather than one flat colour: the resolved colour is built into a
 * [com.intellij.ui.JBColor] at the read boundary ([io.prismtabs.provider.TabGroupColorProvider]),
 * so a tab tinted before a theme switch re-tints itself after one, for free. Keeping both ints
 * here (not a `JBColor` field) is what keeps this class, and [io.prismtabs.service.TabGroupService]
 * around it, plain-JUnit testable with no platform/LAF bootstrap.
 *
 * There is deliberately **no** `hidden` flag. Hiding a group closes its tabs, so "hidden" is a
 * statement about the tab strip — *none of this group's members is open* — and
 * [io.prismtabs.service.TabGroupVisibility.isHidden] derives it from `FileEditorManager` on demand.
 * A stored flag was the earlier design and it drifted the moment a member was reopened from the
 * tool window's tree: the group still called itself hidden with one of its tabs plainly on the
 * strip, "Show Group" became a no-op that only cleared a word, and closing the last tab by hand
 * never made the group read as hidden at all.
 *
 * [rememberedTabUrls] is what survives that: the ordered file URLs that were open, and belonged to
 * this group, the last time *any* of them was — maintained continuously by
 * [io.prismtabs.listener.TabGroupFileListener], not only at the moment of hiding. That is what lets
 * "Show Group" restore the last real arrangement whether the tabs were closed by hiding the group
 * or one at a time by hand. Never cleared, so it outlives a hide/show round trip and a restart;
 * kept on the domain model (not a side table) so it round-trips through [TabGroupState] the same
 * way everything else here does.
 */
data class TabGroup(
    val id: String,
    val name: String,
    val lightRgb: Int,
    val darkRgb: Int,
    val rememberedTabUrls: List<String> = emptyList(),
    val order: Int = 0,
)
