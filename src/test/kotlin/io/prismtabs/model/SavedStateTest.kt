package io.prismtabs.model

import com.intellij.configurationStore.ComponentSerializationUtil
import com.intellij.configurationStore.deserializeState
import com.intellij.configurationStore.jdomSerializer
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.util.JDOMUtil
import io.prismtabs.service.TabGroupPaletteService
import io.prismtabs.service.TabGroupService
import org.jdom.Element
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A user's groups and palette, as an earlier release saved them, must load and save back unchanged.
 * XmlSerializer names the saved elements and options after the state classes and their properties,
 * so renaming one drops that part of every user's file on update, without an error.
 *
 * The fixtures under `saved-state/` are in the exact format the platform writes `tabGroups.xml` and
 * `prismtabsPalette.xml`. Loading and saving take the component store's own route, which binds a
 * state class differently from `com.intellij.util.xmlb.XmlSerializer`: loading through
 * `deserializeState`, and saving through the JDOM serializer with its default filter, which is
 * what the store's Kotlin-internal `serializeState` does when no settings controller is involved.
 */
class SavedStateTest {

    @Test
    fun `saved tab groups load with every field`() {
        val state = load<TabGroupState>("tabGroups.xml", TAB_GROUP_STATE)

        assertEquals(listOf("446638fa-1d37-4bb8-9810-2600c2006423", "a10c9dc3-1f1f-47e4-b156-d1b00eb6d0d5"), state.groups.map { it.id })
        val (backend, docs) = state.groups
        assertEquals(listOf("Backend", 11455482, 3038364, 0), listOf(backend.name, backend.lightRgb, backend.darkRgb, backend.order))
        assertEquals(listOf("file://\$PROJECT_DIR\$/src/Server.kt", "file://\$PROJECT_DIR\$/src/Routes.kt"), backend.rememberedTabUrls)
        assertEquals(listOf("Docs", 16166569, 10236723, 1), listOf(docs.name, docs.lightRgb, docs.darkRgb, docs.order))
        assertEquals(emptyList<String>(), docs.rememberedTabUrls)
        assertEquals(
            mapOf(
                "file://\$PROJECT_DIR\$/README.md" to "a10c9dc3-1f1f-47e4-b156-d1b00eb6d0d5",
                "file://\$PROJECT_DIR\$/src/Server.kt" to "446638fa-1d37-4bb8-9810-2600c2006423",
            ),
            state.assignments,
        )
    }

    @Test
    fun `saved tab groups save back unchanged`() {
        assertSavesBackUnchanged<TabGroupState>("tabGroups.xml", TAB_GROUP_STATE)
    }

    @Test
    fun `a saved palette loads with every field`() {
        val state = load<TabGroupPaletteState>("prismtabsPalette.xml", PALETTE_STATE)

        val entry = state.overrides.single()
        assertEquals(listOf("pink", "Rose", 14681466, 6816832), listOf(entry.id, entry.label, entry.lightRgb, entry.darkRgb))
    }

    @Test
    fun `a saved palette saves back unchanged`() {
        assertSavesBackUnchanged<TabGroupPaletteState>("prismtabsPalette.xml", PALETTE_STATE)
    }

    /**
     * The platform finds a service's state class through `PersistentStateComponent<T>`'s generic
     * signature, and its component name and file through `@State`. Changing either moves every
     * user's saved state somewhere the plugin no longer reads.
     */
    @Test
    fun `each service declares the state class and storage file users' state is saved under`() {
        val services = listOf(TabGroupService::class.java, TabGroupPaletteService::class.java)

        val declared = services.associate { service ->
            val annotation = service.getAnnotation(State::class.java)
            val stateClass = ComponentSerializationUtil.getStateClass<Any>(service.asSubclass(PersistentStateComponent::class.java))
            annotation.name to listOf(annotation.storages.single().value, stateClass)
        }
        assertEquals(
            mapOf(
                TAB_GROUP_STATE to listOf("tabGroups.xml", TabGroupState::class.java),
                PALETTE_STATE to listOf("prismtabsPalette.xml", TabGroupPaletteState::class.java),
            ),
            declared,
        )
    }

    private inline fun <reified T : Any> load(file: String, componentName: String): T =
        checkNotNull(deserializeState(savedComponent(file, componentName), T::class.java)) {
            "$componentName in $file deserialised to null"
        }

    /** Saves the loaded state the way the component store does and compares it with the file. */
    private inline fun <reified T : Any> assertSavesBackUnchanged(file: String, componentName: String) {
        val saved = savedComponent(file, componentName)
        val resaved = checkNotNull(jdomSerializer.serialize(load<T>(file, componentName), jdomSerializer.getDefaultSerializationFilter(), false)) {
            "$componentName serialised to nothing"
        }
        resaved.name = saved.name
        resaved.setAttribute("name", componentName)
        assertEquals(JDOMUtil.write(saved), JDOMUtil.write(resaved))
    }

    private fun savedComponent(file: String, componentName: String): Element {
        val root = JDOMUtil.load(javaClass, "/saved-state/$file")
        return checkNotNull(root.getChildren("component").singleOrNull { it.getAttributeValue("name") == componentName }) {
            "$file has no <component name=\"$componentName\">"
        }
    }

    private companion object {
        const val TAB_GROUP_STATE = "TabGroupState"
        const val PALETTE_STATE = "TabGroupPaletteState"
    }
}
