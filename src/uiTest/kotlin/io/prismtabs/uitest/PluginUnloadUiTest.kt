package io.prismtabs.uitest

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.utils.waitFor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.Duration.ofSeconds

/**
 * What has to be true when a user disables or uninstalls PrismTabs: the plugin's class loader goes,
 * and no tab is left wearing a colour the plugin is no longer there to justify.
 *
 * Both halves have already shipped broken once. The class loader survived a *disable* on 2026.2 on
 * 2026-09-12 — "Plugin io.prismtabs is not unload-safe because class loader cannot be unloaded", an
 * 862 MB heap snapshot, and an IDE that wanted restarting. Reading that snapshot back is what named
 * the cause: twelve live `ActionToolbarImpl`s whose `myCreationTrace` `Throwable` held a PrismTabs
 * class, because the plugin had opened those editors on its own stack. See
 * [io.prismtabs.service.TabOpener]. The colours simply stayed on the strip until the next restart,
 * which a disable never asks for.
 *
 * The scenario is chosen to be the one that broke, not a convenient one: files opened **by the
 * plugin**, through Show Group, and left open at the moment of unload. Opening them any other way
 * does not reproduce it — it is the plugin's own frames beneath the platform's editor construction
 * that do the damage.
 *
 * The plugin is put back in a `finally`. This test unloads the very plugin the rest of the suite
 * exercises, and a sandbox IDE is shared across the whole run.
 *
 * **It runs only on a build with no `<product-descriptor>`**, and skips itself otherwise. That is
 * not a convenience: `DynamicPlugins.checkCanUnloadWithoutRestart` returns "Plugin io.prismtabs is a
 * paid plugin" for any descriptor with a product code that is neither bundled nor JetBrains' own
 * (read out of `DynamicPlugins` with `javap` on 2024.2 — `getProductCode() != null &&
 * !isBundled && !isDevelopedByJetBrains`), so on a paid build the platform refuses *both* halves of
 * this test and the reload in the `finally` cannot put the plugin back either — which then fails
 * every test that runs after it. Comment the tag out in `plugin.xml` to exercise this scenario, as
 * the reported one was: the report came from a locally built, descriptor-less 1.4.14.
 *
 * The gate is inside the frame block, not on the method, because answering it means asking the
 * running IDE.
 */
@ExtendWith(RemoteRobotExtension::class)
class PluginUnloadUiTest {
    init {
        StepsLogger.init()
    }

    @Test
    fun `disabling the plugin releases its class loader and clears the tab colours`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            assumeTrue(
                !isPaidBuild(),
                "the platform refuses to load or unload a paid plugin dynamically — see this class's doc",
            )
            var unloaded = false
            try {
                resetTabGroups()
                assignToNewGroup("Unloadable", listOf("Sample.kt", "Second.kt", "Third.kt"))

                // Show Group is the path the 2026-09-12 snapshot was taken on: the plugin opens the
                // editors itself, and every toolbar the platform builds for them is a chance to
                // capture a PrismTabs frame.
                setGroupHidden("Unloadable", hidden = false)
                waitFor(ofSeconds(20)) { openTabNames().size == 3 }
                // A full Hide/Show round trip, not just one open: closing tabs moves the selection,
                // which builds toolbars of its own, and the tabs have to be open at the end.
                setGroupHidden("Unloadable", hidden = true)
                waitFor(ofSeconds(20)) { openTabNames().isEmpty() }
                setGroupHidden("Unloadable", hidden = false)
                waitFor(ofSeconds(20)) { openTabNames().size == 3 }

                waitFor(ofSeconds(20), errorMessage = "the group's tabs were never coloured") {
                    tabColorOf("Sample.kt") !in listOf("none", "no tab")
                }

                // The defect itself, named precisely, before anything is unloaded: not one of the
                // toolbars the platform built for those editors may hold a PrismTabs frame.
                assertEquals(
                    emptyList<String>(),
                    editorToolbarsNamingPrismTabs(),
                    "a toolbar's creation trace captured a PrismTabs frame; each one of these pins " +
                        "the plugin's class loader for as long as its editor is open",
                )

                val outcome = unloadPrismTabs()
                unloaded = outcome.startsWith("unloaded")

                // ...and its consequence, asserted in full only where it can hold. Below JDK 24
                // every Swing component the platform builds while a plugin is on the stack pins the
                // loader through its captured AccessControlContext, which no plugin can avoid — so
                // there, "still referenced" is not this plugin's bug to fail over. See
                // [IdeaFrame.ideJdkFeature].
                if (ideJdkFeature() >= 24) {
                    assertEquals(
                        "unloaded:collected",
                        outcome,
                        "':retained' is the \"not unload-safe\" dialog, the heap snapshot, and the " +
                            "restart the user has to do",
                    )
                } else {
                    assertTrue(
                        outcome.startsWith("unloaded"),
                        "the platform refused the unload outright: " + outcome,
                    )
                }
                waitFor(ofSeconds(20), errorMessage = "the tabs kept their colour after the unload") {
                    tabColorOf("Sample.kt") == "none"
                }
                assertEquals(
                    listOf("Sample.kt", "Second.kt", "Third.kt"),
                    openTabNames().sorted(),
                    "unloading must leave the user's tabs open — only their colours go",
                )
            } finally {
                if (unloaded) {
                    assertNotEquals(false, reloadPrismTabs(), "PrismTabs could not be loaded back")
                }
            }
        }
    }
}
