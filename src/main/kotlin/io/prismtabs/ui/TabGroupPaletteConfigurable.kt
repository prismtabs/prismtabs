package io.prismtabs.ui

import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.ColorPanel
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.MutableProperty
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import io.prismtabs.license.PrismTabsLicense
import io.prismtabs.model.PresetColor
import io.prismtabs.model.TabGroupPalette
import io.prismtabs.service.TabGroupPaletteService

/**
 * Phase 5's settings page: lets the user rename and recolour the 8 preset hues that
 * [TabGroupPopup]'s quick-assign row, "New Group…" and "Recolour…" steps offer, persisted via the
 * application-level [TabGroupPaletteService] (see that class's doc for why app-level).
 *
 * `BoundSearchableConfigurable` (UI DSL v2), not manual `isModified`/`apply`/`reset`: each row's two
 * [ColorPanel]s and the name field are wired through [com.intellij.ui.dsl.builder.Cell.bind]/
 * [bindText] onto [edited], so [DialogPanel]'s own binding machinery does the field-level
 * modified/apply/reset work — 24 bound controls is exactly the case
 * [io.prismtabs.ui.RenameGroupDialog]'s doc says a binding earns its keep, unlike that dialog's
 * single field.
 *
 * That machinery alone isn't enough, though: it only compares *live component* vs *bound property*,
 * so it's blind to [edited] itself being replaced wholesale — which is exactly what "Restore
 * Defaults" does. [original] is the separate baseline that makes [isModified] and [reset] correct
 * for that case too; see their doc comments.
 */
internal class TabGroupPaletteConfigurable :
    BoundSearchableConfigurable("PrismTabs", "", "io.prismtabs.settings.palette") {

    private val paletteService = service<TabGroupPaletteService>()

    /** The state as of the last load or apply — what "not modified" means. */
    private var original: List<PresetColor> = paletteService.presets()

    /** The in-progress edit buffer every row's controls are bound to. */
    private val edited: MutableList<PresetColor> = original.toMutableList()

    override fun createPanel(): DialogPanel {
        // Editing the palette is paid; the palette a subscriber already saved keeps being used
        // either way (see the README's free/paid split — the free tier loses features, never data).
        // Read once per panel: a Settings dialog is short-lived and rebuilt on each open, so a
        // subscription activated meanwhile is picked up the next time this page is opened.
        val paidFeatures = PrismTabsLicense.paidFeaturesEnabled()
        lateinit var dialogPanel: DialogPanel
        dialogPanel = panel {
            row {
                comment(
                    "Colours offered by the tab group popup's quick-assign row, “New Group…” " +
                        "and “Recolour…”. Changes apply to newly assigned or recoloured " +
                        "groups; existing groups keep their own colour until recoloured.",
                )
            }
            if (!paidFeatures) {
                row {
                    link("Editing the preset palette is a paid feature — start the free trial") {
                        PrismTabsLicense.requestLicense("Editing the PrismTabs preset palette needs an active subscription.")
                    }
                }
            }
            TabGroupPalette.PRESETS.forEachIndexed { index, default ->
                row("${default.label}:") {
                    textField()
                        .bindText({ edited[index].label }, { edited[index] = edited[index].copy(label = it) })
                        .columns(COLUMNS_MEDIUM)
                        .validationOnInput { if (it.text.isBlank()) error("Name must not be blank") else null }
                    cell(ColorPanel())
                        .label("Light")
                        .bind(
                            { it.selectedColor ?: edited[index].light },
                            { colorPanel, value -> colorPanel.selectedColor = value },
                            MutableProperty({ edited[index].light }, { edited[index] = edited[index].copy(light = it) }),
                        )
                    cell(ColorPanel())
                        .label("Dark")
                        .bind(
                            { it.selectedColor ?: edited[index].dark },
                            { colorPanel, value -> colorPanel.selectedColor = value },
                            MutableProperty({ edited[index].dark }, { edited[index] = edited[index].copy(dark = it) }),
                        )
                }.enabled(paidFeatures)
            }
            row {
                link("Restore Defaults") {
                    edited.clear()
                    edited.addAll(TabGroupPalette.PRESETS)
                    dialogPanel.reset()
                }
            }.enabled(paidFeatures)
        }
        return dialogPanel
    }

    /**
     * The bound controls alone can't see a "Restore Defaults" click coming — it rewrites [edited]
     * directly and pushes it back into already-in-sync components, so every `Cell.bind`'s own
     * live-vs-bound comparison reads "unchanged". Comparing [edited] against [original] catches
     * that case; `super.isModified()` still covers an ordinary in-progress edit that hasn't gone
     * through `apply()` yet.
     */
    override fun isModified(): Boolean = super.isModified() || edited != original

    override fun apply() {
        // The rows above are disabled without a subscription, so the only way here with pending
        // edits is a licence that lapsed while this dialog was open. Discard them via [reset] rather
        // than just refusing to save: leaving the buffer dirty would leave the page permanently
        // "modified" (see [isModified]) with no way for the user to settle it. The saved palette
        // survives either way — the free tier is meant to lose features, not settings.
        //
        // The silent [PrismTabsLicense.paidFeaturesEnabled], not [PrismTabsLicense.requirePaidFeature]:
        // clicking OK in Settings isn't "invoking a paid feature", so throwing a registration dialog
        // at someone who couldn't type in these fields anyway would be noise. The upsell here is the
        // link row [createPanel] renders in its place.
        if (!PrismTabsLicense.paidFeaturesEnabled()) {
            reset()
            return
        }
        super.apply()
        paletteService.setPresets(edited)
        original = edited.toList()
    }

    /** Settings' own "Reset" means "discard my edits", i.e. back to [original] — not the factory
     * defaults, which is what the in-panel "Restore Defaults" link is for. */
    override fun reset() {
        edited.clear()
        edited.addAll(original)
        super.reset()
    }
}
