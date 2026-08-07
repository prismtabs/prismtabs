package io.chromatabs.service

import io.chromatabs.model.TabGroup
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
    fun `collapsing a group records it as collapsed with the given open files`() {
        val service = TabGroupService()
        service.assign("file:///A.kt", red)
        service.assign("file:///B.kt", red)

        service.collapse("red", listOf("file:///A.kt", "file:///B.kt"))

        val group = service.groupFor("file:///A.kt")
        assertEquals(true, group?.collapsed)
        assertEquals(listOf("file:///A.kt", "file:///B.kt"), group?.collapsedFileUrls)
    }

    @Test
    fun `expanding a collapsed group clears collapsed and its recorded files`() {
        val service = TabGroupService()
        service.assign("file:///A.kt", red)
        service.collapse("red", listOf("file:///A.kt"))

        service.expand("red")

        val group = service.groupFor("file:///A.kt")
        assertEquals(false, group?.collapsed)
        assertEquals(emptyList<String>(), group?.collapsedFileUrls)
    }

    @Test
    fun `collapsing an unknown group is a no-op`() {
        val service = TabGroupService()
        service.collapse("no-such-group", listOf("file:///A.kt"))
        assertEquals(emptyList<String>(), service.groups())
    }

    @Test
    fun `expanding an unknown group is a no-op`() {
        val service = TabGroupService()
        service.expand("no-such-group")
        assertEquals(emptyList<String>(), service.groups())
    }

    @Test
    fun `collapsed state and recorded files round-trip through getState and loadState`() {
        val original = TabGroupService()
        original.assign("file:///A.kt", red)
        original.assign("file:///B.kt", red)
        original.collapse("red", listOf("file:///A.kt", "file:///B.kt"))

        val restored = TabGroupService()
        restored.loadState(original.state)

        val group = restored.groupFor("file:///A.kt")
        assertEquals(true, group?.collapsed)
        assertEquals(listOf("file:///A.kt", "file:///B.kt"), group?.collapsedFileUrls)
    }
}
