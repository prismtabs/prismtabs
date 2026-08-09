package io.prismtabs.service

import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic

/**
 * Fired after any [TabGroupService] mutation that Phase 4b's "Tab Groups" tool window needs to
 * reflect — a group created, renamed, recoloured, deleted, collapsed or expanded. [TabGroupService]
 * itself stays platform-free (see its doc comment), so publishing is each call site's job, the same
 * split already used for [TabRepaint].
 */
fun interface TabGroupsChangedListener {
    fun tabGroupsChanged()

    companion object {
        val TOPIC: Topic<TabGroupsChangedListener> =
            Topic.create("PrismTabs tab groups changed", TabGroupsChangedListener::class.java)
    }
}

internal object TabGroupNotifier {
    fun notifyChanged(project: Project) {
        project.messageBus.syncPublisher(TabGroupsChangedListener.TOPIC).tabGroupsChanged()
    }
}
