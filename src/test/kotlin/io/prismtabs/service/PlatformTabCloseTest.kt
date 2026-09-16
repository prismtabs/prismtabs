package io.prismtabs.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * [PlatformTabClose] only keeps this plugin off the stack beneath the platform's `closeFile` while
 * its consumer is a bound method reference. A lambda in its place compiles to a synthetic `lambda$`
 * method *in that class*, whose frame the toolbar creation trace then records — and the class
 * loader leak is back, silently, on a platform this suite's baseline sandbox does not run.
 */
class PlatformTabCloseTest {

    @Test
    fun `the close is a method reference, not a lambda of this plugin's`() {
        assertEquals(
            emptyList<String>(),
            PlatformTabClose::class.java.declaredMethods.filter { it.isSynthetic }.map { it.name },
        )
    }
}
