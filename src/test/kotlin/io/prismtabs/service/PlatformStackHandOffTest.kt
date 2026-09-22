package io.prismtabs.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * [PlatformTabOpen] and [PlatformTabClose] only keep this plugin off the stack beneath the
 * platform's own open and close while their consumers are **bound method references**. A lambda in
 * either one's place compiles to a synthetic `lambda$` method *in that class*, whose frame the
 * toolbar creation trace then records — and the class loader leak is back, silently, on a platform
 * this suite's baseline sandbox does not run.
 *
 * Asserted on both, and in one place, because the two are deliberately the same shape and the
 * mistake is the same mistake: the open is the easier of the two to get wrong, since the platform's
 * method takes a second argument and the obvious way to supply it is a closure.
 */
class PlatformStackHandOffTest {

    @Test
    fun `the open is a method reference, not a lambda of this plugin's`() {
        assertNoSyntheticMethods(PlatformTabOpen::class.java)
    }

    @Test
    fun `the close is a method reference, not a lambda of this plugin's`() {
        assertNoSyntheticMethods(PlatformTabClose::class.java)
    }

    private fun assertNoSyntheticMethods(handOff: Class<*>) {
        assertEquals(
            emptyList<String>(),
            handOff.declaredMethods.filter { it.isSynthetic }.map { it.name },
            "${handOff.simpleName} must hand the platform's own method over as a bound method " +
                "reference — a lambda puts a frame of this plugin's on the stack beneath it",
        )
    }
}
