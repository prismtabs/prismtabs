package io.chromatabs.service

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import io.chromatabs.model.PresetColor
import io.chromatabs.model.TabGroupPalette
import io.chromatabs.model.TabGroupPaletteState
import java.awt.Color

/**
 * Application-level, not project-level: the palette is an IDE-wide preference — the same footing
 * as an editor colour scheme — not project data, so [io.chromatabs.ui.TabGroupPaletteConfigurable]
 * is registered as an `applicationConfigurable`, not a per-project one.
 *
 * [presets] lays any customisation over [TabGroupPalette.PRESETS] rather than persisting all 8
 * outright: a slot the user never touched keeps tracking the built-in default if that default ever
 * changes, and it means [setPresets] only has to diff against the current defaults to know what's
 * actually worth persisting — restoring a preset to its default value drops the override for free.
 */
@Service(Service.Level.APP)
@State(name = "TabGroupPaletteState", storages = [Storage("chromatabsPalette.xml")])
class TabGroupPaletteService : PersistentStateComponent<TabGroupPaletteState> {

    @Volatile
    private var overrides: Map<String, PresetColor> = emptyMap()

    fun presets(): List<PresetColor> = TabGroupPalette.PRESETS.map { overrides[it.id] ?: it }

    /** [edited] must cover every id in [TabGroupPalette.PRESETS], same shape [presets] returns. */
    fun setPresets(edited: List<PresetColor>) {
        overrides = edited.filter { it != defaultFor(it.id) }.associateBy { it.id }
    }

    fun resetToDefaults() {
        overrides = emptyMap()
    }

    private fun defaultFor(id: String): PresetColor? = TabGroupPalette.PRESETS.find { it.id == id }

    override fun getState(): TabGroupPaletteState = TabGroupPaletteState().apply {
        overrides = this@TabGroupPaletteService.overrides.values.map { preset ->
            TabGroupPaletteState.PresetEntry().apply {
                id = preset.id
                label = preset.label
                lightRgb = preset.light.rgb and 0xFFFFFF
                darkRgb = preset.dark.rgb and 0xFFFFFF
            }
        }.toMutableList()
    }

    override fun loadState(state: TabGroupPaletteState) {
        overrides = state.overrides.associate { entry ->
            entry.id to PresetColor(entry.id, entry.label, Color(entry.lightRgb), Color(entry.darkRgb))
        }
    }
}
