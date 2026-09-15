package io.prismtabs.service

import com.intellij.util.xmlb.XmlSerializer
import io.prismtabs.model.TabGroup
import io.prismtabs.model.TabGroupState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Plain JUnit 5, no platform harness — TabGroupService is deliberately free of IDE dependencies
 * other than the persistence annotations, which are metadata only and do nothing at construction
 * time. Same pattern as the Phase 0 rules test.
 */
class TabGroupServiceTest {

    private val red = TabGroup(id = "red", name = "Red", lightRgb = 0xF6AEA9, darkRgb = 0x9C3333)
    private val blue = TabGroup(id = "blue", name = "Blue", lightRgb = 0xAECBFA, darkRgb = 0x2E5C9C)

    @Test
    fun `unassigned file has no group`() {
        val service = TabGroupService()
        assertNull(service.groupFor("file:///Unassigned.kt"))
    }

    @Test
    fun `assigning a file surfaces its group`() {
        val service = TabGroupService()
        service.assign("file:///A.kt", red)
        assertEquals(red, service.groupFor("file:///A.kt"))
    }

    @Test
    fun `reassigning a file to another group replaces it`() {
        val service = TabGroupService()
        service.assign("file:///A.kt", red)
        service.assign("file:///A.kt", blue)
        assertEquals(blue, service.groupFor("file:///A.kt"))
    }

    @Test
    fun `clearing a file removes its group without touching others`() {
        val service = TabGroupService()
        service.assign("file:///A.kt", red)
        service.assign("file:///B.kt", blue)
        service.clear("file:///A.kt")
        assertNull(service.groupFor("file:///A.kt"))
        assertEquals(blue, service.groupFor("file:///B.kt"))
    }

    @Test
    fun `clearAll unassigns every url in one pass`() {
        val service = TabGroupService()
        service.assign("file:///A.kt", red)
        service.assign("file:///B.kt", red)
        service.assign("file:///C.kt", blue)

        val removal = service.clearAll(listOf("file:///A.kt", "file:///C.kt"))

        assertNull(service.groupFor("file:///A.kt"))
        assertNull(service.groupFor("file:///C.kt"))
        assertEquals(red, service.groupFor("file:///B.kt"), "an unselected member must stay put")
        assertEquals(listOf("file:///A.kt", "file:///C.kt"), removal.removedUrls.sorted())
        // Only blue was emptied: red still holds B.kt. A loop over `clear` would agree here, but
        // not below.
        assertEquals(listOf(blue), removal.emptiedGroups)
    }

    @Test
    fun `clearAll names a group emptied by the batch once, not once per file`() {
        val service = TabGroupService()
        service.assign("file:///A.kt", red)
        service.assign("file:///B.kt", red)

        val removal = service.clearAll(listOf("file:///A.kt", "file:///B.kt"))

        assertEquals(listOf(red), removal.emptiedGroups, "the emptied group must be reported exactly once")
    }

    @Test
    fun `clearAll ignores urls that were never assigned`() {
        val service = TabGroupService()
        service.assign("file:///A.kt", red)

        val removal = service.clearAll(listOf("file:///A.kt", "file:///Never.kt"))

        assertEquals(listOf("file:///A.kt"), removal.removedUrls)
        assertEquals(listOf(red), removal.emptiedGroups)
    }

    @Test
    fun `groups lists every group that has been assigned, ordered`() {
        val service = TabGroupService()
        service.assign("file:///A.kt", red.copy(order = 1))
        service.assign("file:///B.kt", blue.copy(order = 0))
        assertEquals(listOf(blue.copy(order = 0), red.copy(order = 1)), service.groups())
    }

    @Test
    fun `state round-trips through getState and loadState`() {
        val original = TabGroupService()
        original.assign("file:///A.kt", red)
        original.assign("file:///B.kt", blue)

        val restored = TabGroupService()
        restored.loadState(original.state)

        assertEquals(red, restored.groupFor("file:///A.kt"))
        assertEquals(blue, restored.groupFor("file:///B.kt"))
    }

    @Test
    fun `loadState recomputes the cache rather than trusting stale state`() {
        val service = TabGroupService()
        service.assign("file:///A.kt", red)

        val state = service.state
        service.loadState(state)

        assertEquals(red, service.groupFor("file:///A.kt"))
    }

    @Test
    fun `renaming a group updates it for every member`() {
        val service = TabGroupService()
        service.assign("file:///A.kt", red)
        service.assign("file:///B.kt", red)

        service.rename("red", "Errors")

        assertEquals("Errors", service.groupFor("file:///A.kt")?.name)
        assertEquals("Errors", service.groupFor("file:///B.kt")?.name)
    }

    @Test
    fun `renaming an unknown group is a no-op`() {
        val service = TabGroupService()
        service.rename("no-such-group", "Errors")
        assertEquals(emptyList<String>(), service.groups())
    }

    @Test
    fun `recolouring a group updates it for every member`() {
        val service = TabGroupService()
        service.assign("file:///A.kt", red)
        service.assign("file:///B.kt", red)

        service.recolor("red", lightRgb = 0x000000, darkRgb = 0x111111)

        assertEquals(0x000000, service.groupFor("file:///A.kt")?.lightRgb)
        assertEquals(0x111111, service.groupFor("file:///B.kt")?.darkRgb)
    }

    @Test
    fun `deleting a group unassigns every member and clears the group itself`() {
        val service = TabGroupService()
        service.assign("file:///A.kt", red)
        service.assign("file:///B.kt", red)
        service.assign("file:///C.kt", blue)

        service.delete("red")

        assertNull(service.groupFor("file:///A.kt"))
        assertNull(service.groupFor("file:///B.kt"))
        assertEquals(blue, service.groupFor("file:///C.kt"))
        assertEquals(listOf(blue), service.groups())
    }

    @Test
    fun `deleting the active group clears the active group too`() {
        val service = TabGroupService()
        service.assign("file:///A.kt", red)
        service.setActiveGroup("red")

        service.delete("red")

        assertNull(service.activeGroupId())
        assertNull(service.activeGroup())
    }

    @Test
    fun `membersOf lists every file assigned to a group`() {
        val service = TabGroupService()
        service.assign("file:///A.kt", red)
        service.assign("file:///B.kt", blue)
        service.assign("file:///C.kt", red)

        assertEquals(setOf("file:///A.kt", "file:///C.kt"), service.membersOf("red").toSet())
    }

    @Test
    fun `no group is active by default`() {
        val service = TabGroupService()
        assertNull(service.activeGroupId())
        assertNull(service.activeGroup())
    }

    @Test
    fun `setting the active group surfaces it, clearing it surfaces null`() {
        val service = TabGroupService()
        service.assign("file:///A.kt", red)

        service.setActiveGroup("red")
        assertEquals(red, service.activeGroup())

        service.setActiveGroup(null)
        assertNull(service.activeGroup())
    }

    @Test
    fun `remembering open tabs records them in the given order`() {
        val service = TabGroupService()
        service.assign("file:///A.kt", red)
        service.assign("file:///B.kt", red)

        service.rememberOpenTabs("red", listOf("file:///B.kt", "file:///A.kt"))

        assertEquals(listOf("file:///B.kt", "file:///A.kt"), service.groupFor("file:///A.kt")?.rememberedTabUrls)
    }

    @Test
    fun `remembering open tabs replaces what was remembered before`() {
        val service = TabGroupService()
        service.assign("file:///A.kt", red)
        service.rememberOpenTabs("red", listOf("file:///A.kt", "file:///B.kt"))

        service.rememberOpenTabs("red", listOf("file:///A.kt"))

        assertEquals(listOf("file:///A.kt"), service.groupFor("file:///A.kt")?.rememberedTabUrls)
    }

    /**
     * The memory survives a show, which is what lets a group be hidden and shown repeatedly without
     * the second Show having forgotten what the first one reopened.
     */
    @Test
    fun `remembering open tabs is never cleared by anything else`() {
        val service = TabGroupService()
        service.assign("file:///A.kt", red)
        service.rememberOpenTabs("red", listOf("file:///A.kt"))

        service.rename("red", "Crimson")
        service.recolor("red", 0x000001, 0x000002)

        assertEquals(listOf("file:///A.kt"), service.groupFor("file:///A.kt")?.rememberedTabUrls)
    }

    @Test
    fun `remembering open tabs for an unknown group is a no-op`() {
        val service = TabGroupService()
        service.rememberOpenTabs("no-such-group", listOf("file:///A.kt"))
        assertEquals(emptyList<String>(), service.groups())
    }

    @Test
    fun `remapping a file's URL moves its assignment to the new URL`() {
        val service = TabGroupService()
        service.assign("file:///old/A.kt", red)

        val remapped = service.remapUrl("file:///old/A.kt", "file:///new/A.kt")

        assertEquals(listOf("file:///new/A.kt"), remapped)
        assertNull(service.groupFor("file:///old/A.kt"))
        assertEquals(red, service.groupFor("file:///new/A.kt"))
    }

    @Test
    fun `remapping a directory URL moves every assignment nested under it`() {
        val service = TabGroupService()
        service.assign("file:///project/src/A.kt", red)
        service.assign("file:///project/src/nested/B.kt", blue)
        service.assign("file:///project/other/C.kt", blue)

        val remapped = service.remapUrl("file:///project/src", "file:///project/renamed")

        assertEquals(
            setOf("file:///project/renamed/A.kt", "file:///project/renamed/nested/B.kt"),
            remapped.toSet(),
        )
        assertNull(service.groupFor("file:///project/src/A.kt"))
        assertNull(service.groupFor("file:///project/src/nested/B.kt"))
        assertEquals(red, service.groupFor("file:///project/renamed/A.kt"))
        assertEquals(blue, service.groupFor("file:///project/renamed/nested/B.kt"))
        assertEquals(blue, service.groupFor("file:///project/other/C.kt"))
    }

    @Test
    fun `remapping does not touch a URL that merely shares a prefix`() {
        val service = TabGroupService()
        service.assign("file:///project/FooBar.kt", red)

        val remapped = service.remapUrl("file:///project/Foo", "file:///project/Renamed")

        assertEquals(emptyList<String>(), remapped)
        assertEquals(red, service.groupFor("file:///project/FooBar.kt"))
    }

    @Test
    fun `remapping an unassigned URL is a no-op`() {
        val service = TabGroupService()
        service.assign("file:///A.kt", red)

        val remapped = service.remapUrl("file:///Unrelated.kt", "file:///StillUnrelated.kt")

        assertEquals(emptyList<String>(), remapped)
        assertEquals(red, service.groupFor("file:///A.kt"))
    }

    @Test
    fun `remapping to the same URL is a no-op`() {
        val service = TabGroupService()
        service.assign("file:///A.kt", red)

        val remapped = service.remapUrl("file:///A.kt", "file:///A.kt")

        assertEquals(emptyList<String>(), remapped)
        assertEquals(red, service.groupFor("file:///A.kt"))
    }

    @Test
    fun `remapping a remembered tab's file also updates the group's rememberedTabUrls`() {
        val service = TabGroupService()
        service.assign("file:///old/A.kt", red)
        service.rememberOpenTabs("red", listOf("file:///old/A.kt"))

        service.remapUrl("file:///old/A.kt", "file:///new/A.kt")

        val group = service.groupFor("file:///new/A.kt")
        assertEquals(listOf("file:///new/A.kt"), group?.rememberedTabUrls)
    }

    /**
     * The memory is the one piece of hide/show state that persists — "hidden" itself is derived from
     * the tab strip (see `TabGroupVisibility`), and after a restart a group with nothing open reads
     * as hidden on its own. What has to survive is *what to reopen*.
     */
    @Test
    fun `remembered tabs round-trip through getState and loadState`() {
        val original = TabGroupService()
        original.assign("file:///A.kt", red)
        original.assign("file:///B.kt", red)
        original.rememberOpenTabs("red", listOf("file:///A.kt", "file:///B.kt"))

        val restored = TabGroupService()
        restored.loadState(original.state)

        assertEquals(listOf("file:///A.kt", "file:///B.kt"), restored.groupFor("file:///A.kt")?.rememberedTabUrls)
    }

    @Test
    fun `a restart brings back every group's name, colours and members`() {
        val original = TabGroupService()
        original.assign("file:///A.kt", red.copy(order = 0))
        original.assign("file:///B.kt", red.copy(order = 0))
        original.assign("file:///C.kt", blue.copy(order = 1))

        val restarted = restartedFrom(original)

        assertEquals(listOf(red, blue.copy(order = 1)), restarted.groups())
        assertEquals(setOf("file:///A.kt", "file:///B.kt"), restarted.membersOf("red").toSet())
        assertEquals(listOf("file:///C.kt"), restarted.membersOf("blue"))
        assertEquals(red, restarted.groupFor("file:///B.kt"))
    }

    @Test
    fun `a restart keeps a group that was left with no members`() {
        val original = TabGroupService()
        original.assign("file:///A.kt", red)
        original.clear("file:///A.kt")

        val restarted = restartedFrom(original)

        assertEquals(listOf(red), restarted.groups())
        assertEquals(emptyList<String>(), restarted.membersOf("red"))
    }

    @Test
    fun `a restart brings back the tabs a group remembers, in order`() {
        val original = TabGroupService()
        original.assign("file:///A.kt", red)
        original.assign("file:///B.kt", red)
        original.rememberOpenTabs("red", listOf("file:///B.kt", "file:///A.kt"))

        val restarted = restartedFrom(original)

        assertEquals(listOf("file:///B.kt", "file:///A.kt"), restarted.group("red")?.rememberedTabUrls)
    }

    /** Session-scoped by design: see the doc on `TabGroupService.activeGroupId`. */
    @Test
    fun `a restart never brings back the active group`() {
        val original = TabGroupService()
        original.assign("file:///A.kt", red)
        original.setActiveGroup("red")

        val restarted = restartedFrom(original)

        assertEquals(listOf(red), restarted.groups())
        assertNull(restarted.activeGroupId())
        assertNull(restarted.activeGroup())
    }

    /**
     * What an IDE restart does to this service: its state is written out as XML by the platform's
     * own serializer, and a fresh instance is handed that XML back. Stricter than passing the state
     * object across, which would also pass for a field the serializer silently skips.
     */
    private fun restartedFrom(service: TabGroupService): TabGroupService {
        val xml = XmlSerializer.serialize(service.state)
        return TabGroupService().apply { loadState(XmlSerializer.deserialize(xml, TabGroupState::class.java)) }
    }

    @Test
    fun `clearing the last member reports the group it emptied`() {
        val service = TabGroupService()
        service.assign("file:///A.kt", red)

        assertEquals(red, service.clear("file:///A.kt"))
    }

    @Test
    fun `clearing a member while others remain reports nothing emptied`() {
        val service = TabGroupService()
        service.assign("file:///A.kt", red)
        service.assign("file:///B.kt", red)

        assertNull(service.clear("file:///A.kt"))
    }

    @Test
    fun `clearing an unassigned file reports nothing emptied`() {
        val service = TabGroupService()
        service.assign("file:///A.kt", red)

        assertNull(service.clear("file:///Unassigned.kt"))
        assertEquals(red, service.groupFor("file:///A.kt"))
    }

    @Test
    fun `an emptied group survives until it is deleted explicitly`() {
        val service = TabGroupService()
        service.assign("file:///A.kt", red)
        service.clear("file:///A.kt")

        assertEquals(listOf(red), service.groups())
        assertEquals(emptyList<String>(), service.membersOf("red"))
    }

    @Test
    fun `moving a group's last member elsewhere reports the group it emptied`() {
        val service = TabGroupService()
        service.assign("file:///A.kt", red)

        assertEquals(red, service.assign("file:///A.kt", blue))
    }

    @Test
    fun `moving a member while others remain reports nothing emptied`() {
        val service = TabGroupService()
        service.assign("file:///A.kt", red)
        service.assign("file:///B.kt", red)

        assertNull(service.assign("file:///A.kt", blue))
    }

    @Test
    fun `assigning an unassigned file, or reassigning it to its own group, empties nothing`() {
        val service = TabGroupService()

        assertNull(service.assign("file:///A.kt", red))
        assertNull(service.assign("file:///A.kt", red))
    }

    @Test
    fun `clearTree unassigns a deleted file and reports what it removed`() {
        val service = TabGroupService()
        service.assign("file:///A.kt", red)
        service.assign("file:///B.kt", blue)

        val removal = service.clearTree("file:///A.kt")

        assertEquals(listOf("file:///A.kt"), removal.removedUrls)
        assertEquals(listOf(red), removal.emptiedGroups)
        assertNull(service.groupFor("file:///A.kt"))
        assertEquals(blue, service.groupFor("file:///B.kt"))
    }

    @Test
    fun `clearTree unassigns every file nested under a deleted directory`() {
        val service = TabGroupService()
        service.assign("file:///project/src/A.kt", red)
        service.assign("file:///project/src/nested/B.kt", red)
        service.assign("file:///project/other/C.kt", blue)

        val removal = service.clearTree("file:///project/src")

        assertEquals(
            setOf("file:///project/src/A.kt", "file:///project/src/nested/B.kt"),
            removal.removedUrls.toSet(),
        )
        assertEquals(listOf(red), removal.emptiedGroups)
        assertEquals(blue, service.groupFor("file:///project/other/C.kt"))
    }

    @Test
    fun `clearTree emptying several groups at once reports all of them`() {
        val service = TabGroupService()
        service.assign("file:///project/src/A.kt", red)
        service.assign("file:///project/src/B.kt", blue)

        val removal = service.clearTree("file:///project/src")

        assertEquals(setOf(red, blue), removal.emptiedGroups.toSet())
    }

    @Test
    fun `clearTree leaves a group with surviving members alone`() {
        val service = TabGroupService()
        service.assign("file:///project/src/A.kt", red)
        service.assign("file:///project/other/B.kt", red)

        val removal = service.clearTree("file:///project/src")

        assertEquals(listOf("file:///project/src/A.kt"), removal.removedUrls)
        assertEquals(emptyList<TabGroup>(), removal.emptiedGroups)
        assertEquals(red, service.groupFor("file:///project/other/B.kt"))
    }

    @Test
    fun `clearTree does not touch a URL that merely shares a prefix`() {
        val service = TabGroupService()
        service.assign("file:///project/FooBar.kt", red)

        val removal = service.clearTree("file:///project/Foo")

        assertEquals(emptyList<String>(), removal.removedUrls)
        assertEquals(emptyList<TabGroup>(), removal.emptiedGroups)
        assertEquals(red, service.groupFor("file:///project/FooBar.kt"))
    }

    @Test
    fun `clearTree drops a deleted file from a group's remembered tabs`() {
        val service = TabGroupService()
        service.assign("file:///A.kt", red)
        service.assign("file:///B.kt", red)
        service.rememberOpenTabs("red", listOf("file:///A.kt", "file:///B.kt"))

        service.clearTree("file:///A.kt")

        assertEquals(listOf("file:///B.kt"), service.groupFor("file:///B.kt")?.rememberedTabUrls)
    }
}
