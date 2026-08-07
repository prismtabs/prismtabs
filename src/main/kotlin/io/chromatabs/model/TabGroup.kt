package io.chromatabs.model

/**
 * [lightRgb]/[darkRgb] rather than one flat colour: the resolved colour is built into a
 * [com.intellij.ui.JBColor] at the read boundary ([io.chromatabs.provider.TabGroupColorProvider]),
 * so a tab tinted before a theme switch re-tints itself after one, for free. Keeping both ints
 * here (not a `JBColor` field) is what keeps this class, and [io.chromatabs.service.TabGroupService]
 * around it, plain-JUnit testable with no platform/LAF bootstrap.
 */
data class TabGroup(
    val id: String,
    val name: String,
    val lightRgb: Int,
    val darkRgb: Int,
    val collapsed: Boolean = false,
    val order: Int = 0,
)
