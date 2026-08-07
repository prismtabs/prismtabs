package io.chromatabs.model

import com.intellij.util.xmlb.annotations.XCollection
import com.intellij.util.xmlb.annotations.XMap

/**
 * Persistable DTO for TabGroupService's [com.intellij.openapi.components.State]. XmlSerializer
 * needs a plain no-arg-constructor class with mutable `var` properties — this is deliberately not
 * the immutable domain [TabGroup] type.
 */
class TabGroupState {

    @XCollection
    var groups: MutableList<GroupEntry> = mutableListOf()

    /** fileUrl -> groupId. */
    @XMap
    var assignments: MutableMap<String, String> = mutableMapOf()

    class GroupEntry {
        var id: String = ""
        var name: String = ""
        var lightRgb: Int = 0
        var darkRgb: Int = 0
        var collapsed: Boolean = false

        @XCollection
        var collapsedFileUrls: MutableList<String> = mutableListOf()
        var order: Int = 0
    }
}
