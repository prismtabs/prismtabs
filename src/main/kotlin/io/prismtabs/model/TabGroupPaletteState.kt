package io.prismtabs.model

import com.intellij.util.xmlb.annotations.XCollection

/**
 * Persistable DTO for [io.prismtabs.service.TabGroupPaletteService]'s
 * [com.intellij.openapi.components.State]. Only holds presets that differ from
 * [TabGroupPalette.PRESETS] — see that service's doc for why a diff, not all 8, is stored.
 */
class TabGroupPaletteState {

    @XCollection
    var overrides: MutableList<PresetEntry> = mutableListOf()

    class PresetEntry {
        var id: String = ""
        var label: String = ""
        var lightRgb: Int = 0
        var darkRgb: Int = 0
    }
}
