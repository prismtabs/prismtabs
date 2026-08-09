package io.prismtabs.model

import java.awt.Color

/**
 * One of Chrome's eight tab-group hues. Plain `java.awt.Color`, not `JBColor` — this stays
 * plain-JUnit testable; [io.prismtabs.provider.TabGroupColorProvider] is where a light/dark pair
 * becomes a theme-aware colour, and [io.prismtabs.ui.ColorSwatchIcon] is where a pair becomes a
 * theme-aware icon. Both [light] and [dark] are chosen to stay visible after `EditorTabs` blends
 * unselected-tab colours to ~50% strength (see README's Phase 0 finding) — a plain pastel would
 * vanish under that blend, which is why these read as saturated rather than muted.
 */
data class PresetColor(val id: String, val label: String, val light: Color, val dark: Color)

object TabGroupPalette {

    val PRESETS: List<PresetColor> = listOf(
        PresetColor("grey", "Grey", Color(0xDADCE0), Color(0x5F6368)),
        PresetColor("blue", "Blue", Color(0xAECBFA), Color(0x2E5C9C)),
        PresetColor("red", "Red", Color(0xF6AEA9), Color(0x9C3333)),
        PresetColor("yellow", "Yellow", Color(0xFDE293), Color(0x8A6D1D)),
        PresetColor("green", "Green", Color(0xA8DAB5), Color(0x2D6A4F)),
        PresetColor("pink", "Pink", Color(0xF7A8D2), Color(0x9C3372)),
        PresetColor("purple", "Purple", Color(0xD3B3F5), Color(0x5C3C9C)),
        PresetColor("cyan", "Cyan", Color(0x99E3F5), Color(0x1D6E7A)),
    )
}
