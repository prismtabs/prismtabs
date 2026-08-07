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
}
