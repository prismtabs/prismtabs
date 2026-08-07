package io.chromatabs.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/**
 * The plan's Phase 3 rename flow — the one place in the group-management tree that needs free
 * text rather than a list choice, hence a UI DSL v2 dialog (`panel { row { textField() } }`)
 * rather than another [TabGroupPopup] nested step. Reads back from the [JBTextField] directly
 * instead of a `bindText`-bound property: with a single field there is nothing a binding buys
 * over just asking the component, and it avoids relying on [com.intellij.openapi.ui.DialogPanel]'s
 * apply-on-OK behaviour matching what [doValidate] just checked.
 */
internal class RenameGroupDialog(project: Project, initialName: String) : DialogWrapper(project) {

    private val nameField = JBTextField(initialName)

    init {
        title = "Rename Tab Group"
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Name:") {
            cell(nameField).focused()
        }
    }

    override fun doValidate(): ValidationInfo? =
        if (nameField.text.isBlank()) ValidationInfo("Name must not be blank", nameField) else null

    fun newName(): String = nameField.text.trim()
}
