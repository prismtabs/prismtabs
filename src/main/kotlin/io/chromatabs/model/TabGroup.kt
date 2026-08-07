package io.chromatabs.model

/**
 * [lightRgb]/[darkRgb] rather than one flat colour: the resolved colour is built into a
 * [com.intellij.ui.JBColor] at the read boundary ([io.chromatabs.provider.TabGroupColorProvider]),
 * so a tab tinted before a theme switch re-tints itself after one, for free. Keeping both ints
 * here (not a `JBColor` field) is what keeps this class, and [io.chromatabs.service.TabGroupService]
 * around it, plain-JUnit testable with no platform/LAF bootstrap.
 *
 * [collapsedFileUrls] is Phase 4a's "close-and-remember": the ordered file URLs that were open,
 * and belonged to this group, at the moment it was collapsed. Empty whenever [collapsed] is false.
 * Kept on the domain model (not a side table) so it round-trips through [TabGroupState] the same
 * way everything else here does, and survives a restart per the plan.
 */
data class TabGroup(
    val id: String,
    val name: String,
    val lightRgb: Int,
    val darkRgb: Int,
    val collapsed: Boolean = false,
    val collapsedFileUrls: List<String> = emptyList(),
    val order: Int = 0,
)
