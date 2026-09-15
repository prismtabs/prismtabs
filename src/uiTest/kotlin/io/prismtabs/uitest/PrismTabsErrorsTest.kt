package io.prismtabs.uitest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** [prismTabsErrors] needs no IDE: it only reads log text. */
class PrismTabsErrorsTest {

    @Test
    fun `an error whose trace runs through the plugin is PrismTabs'`() {
        val log = """
            2026-09-14 23:49:37,630 [3211755] SEVERE - #c.i.o.a.ActionUpdater - boom
            java.lang.IllegalStateException: boom
            	at io.prismtabs.ui.TabGroupsPanel.refresh(TabGroupsToolWindow.kt:201)
            	at com.intellij.util.messages.impl.MessageBusImplKt.invokeMethod(MessageBusImpl.kt:722)
            2026-09-14 23:49:37,631 [3211756]   INFO - #c.i.o.a.Something - fine
        """.trimIndent()

        val errors = prismTabsErrors(log)

        assertEquals(1, errors.size)
        assertTrue(errors.single().contains("at io.prismtabs.ui.TabGroupsPanel.refresh"), "the trace must come with the entry")
    }

    @Test
    fun `an error logged by the plugin, or blamed on it, is PrismTabs'`() {
        val log = """
            2026-09-15 00:15:32,389 [117718] SEVERE - #io.prismtabs.ui.ToggleActiveAction - measured: the full category
            2026-09-14 23:50:00,000 [1] SEVERE - #i.p.s.TabOpener - Could not open a tab group's files
            2026-09-14 23:50:00,001 [2] SEVERE - #c.i.o.a.TransactionGuardImpl - Plugin to blame: PrismTabs version: 1.4.14
        """.trimIndent()

        assertEquals(3, prismTabsErrors(log).size)
    }

    /** The platform appends "Last Action:" to every error; a PrismTabs action being last is not the cause. */
    @Test
    fun `an error that only mentions a PrismTabs action as the last one clicked is not PrismTabs'`() {
        val log = """
            2026-09-14 23:49:37,630 [3211755] SEVERE - #c.i.o.a.TransactionGuardImpl - Write-unsafe context!
            java.lang.Throwable: Write-unsafe context!
            	at com.intellij.remoterobot.services.js.RhinoJavaScriptExecutor.execute(RhinoJavaScriptExecutor.kt:16)
            2026-09-14 23:49:37,632 [3211757] SEVERE - #c.i.o.a.TransactionGuardImpl - Plugin to blame: Robot server version: 0.11.23
            2026-09-14 23:49:37,632 [3211757] SEVERE - #c.i.o.a.TransactionGuardImpl - Last Action: PrismTabs.AssignTabGroup
        """.trimIndent()

        assertEquals(emptyList<String>(), prismTabsErrors(log))
    }

    @Test
    fun `warnings naming the plugin are not errors`() {
        val log = "2026-09-14 23:50:00,000 [1]   WARN - #i.p.s.TabOpener - Could not open a tab group's files\n" +
            "\tat io.prismtabs.service.TabOpener.open(TabOpener.kt:40)"

        assertEquals(emptyList<String>(), prismTabsErrors(log))
    }
}
