package io.chromatabs.service

import io.chromatabs.model.TabGroup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.awt.Color

/**
 * Plain JUnit 5, no platform harness — TabGroupService is deliberately free of IDE dependencies
 * other than the persistence annotations, which are metadata only and do nothing at construction
 * time. Same pattern as the Phase 0 rules test.
 */
class TabGroupServiceTest {

    private val red = TabGroup(id = "red", name = "Red", colorRgb = 0xFF0000)
    private val blue = TabGroup(id = "blue", name = "Blue", colorRgb = 0x0000FF)

    @Test
    fun `unassigned file has no colour`() {
        val service = TabGroupService()
        assertNull(service.colorFor("file:///Unassigned.kt"))
    }

    @Test
    fun `assigning a file surfaces its group's colour`() {
        val service = TabGroupService()
        service.assign("file:///A.kt", red)
        assertEquals(Color(0xFF0000), service.colorFor("file:///A.kt"))
    }

    @Test
    fun `reassigning a file to another group replaces its colour`() {
        val service = TabGroupService()
        service.assign("file:///A.kt", red)
        service.assign("file:///A.kt", blue)
        assertEquals(Color(0x0000FF), service.colorFor("file:///A.kt"))
    }

    @Test
    fun `clearing a file removes its colour without touching others`() {
        val service = TabGroupService()
        service.assign("file:///A.kt", red)
        service.assign("file:///B.kt", blue)
        service.clear("file:///A.kt")
        assertNull(service.colorFor("file:///A.kt"))
        assertEquals(Color(0x0000FF), service.colorFor("file:///B.kt"))
    }

    @Test
    fun `state round-trips through getState and loadState`() {
        val original = TabGroupService()
        original.assign("file:///A.kt", red)
        original.assign("file:///B.kt", blue)

        val restored = TabGroupService()
        restored.loadState(original.state)

        assertEquals(Color(0xFF0000), restored.colorFor("file:///A.kt"))
        assertEquals(Color(0x0000FF), restored.colorFor("file:///B.kt"))
    }

    @Test
    fun `loadState recomputes the colour cache rather than trusting stale state`() {
        val service = TabGroupService()
        service.assign("file:///A.kt", red)

        val state = service.state
        service.loadState(state)

        assertEquals(Color(0xFF0000), service.colorFor("file:///A.kt"))
    }
}
