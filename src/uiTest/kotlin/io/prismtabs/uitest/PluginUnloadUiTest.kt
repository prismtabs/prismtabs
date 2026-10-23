package io.prismtabs.uitest

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.utils.waitFor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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
 * [io.prismtabs.service.TabStrip]. The colours simply stayed on the strip until the next restart,
 * which a disable never asks for.
 *
 * The scenario is chosen to be the one that broke, not a convenient one: files opened **by the
 * plugin**, through Show Group, and left open at the moment of unload. Opening them any other way
 * does not reproduce it — it is the plugin's own frames beneath the platform's editor construction
 * that do the damage. It broke a second time on 2026-09-16, from the other direction: Hide Group
 * closing a selected Markdown tab, with the group left hidden up to the Disable. That half only
 * reproduces on 2026.2 and later, so run this against `runIdeForUiTestsLatest` as well as the
 * baseline sandbox — see the scenario's own comment, and engineering notes "The newest platform" for setting that
 * task up.
 *
 * The plugin is put back in a `finally`. This test unloads the very plugin the rest of the suite
 * exercises, and a sandbox IDE is shared across the whole run.
 */
@ExtendWith(RemoteRobotExtension::class)
class PluginUnloadUiTest {
    init {
        StepsLogger.init()
    }

    @Test
    fun `disabling the plugin releases its class loader and clears the tab colours`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            var unloaded = false
            try {
                resetTabGroups()
                createFixtureFile(HIDDEN_MARKDOWN)
                assignToNewGroup("Unloadable", listOf("Sample.kt", "Second.kt"))
                assignToNewGroup("Hideable", listOf(HIDDEN_MARKDOWN))

                // Show Group is the path the 2026-09-12 snapshot was taken on: the plugin opens the
                // editors itself, and every toolbar the platform builds for them is a chance to
                // capture a PrismTabs frame.
                setGroupHidden("Unloadable", hidden = false)
                waitFor(ofSeconds(20)) { openTabNames().size == 2 }
                // A full Hide/Show round trip, not just one open, and the tabs have to be open at
                // the end.
                setGroupHidden("Unloadable", hidden = true)
                waitFor(ofSeconds(20)) { openTabNames().isEmpty() }
                setGroupHidden("Unloadable", hidden = false)
                waitFor(ofSeconds(20)) { openTabNames().size == 2 }

                // The 2026-09-16 snapshot's path: Hide Group closing the *selected* tab while others
                // stay open, so the strip moves the selection to a neighbour on the plugin's stack.
                // From 2026.2 the tab strip's entry-point toolbar is rebuilt whenever the selection
                // moves between a tab whose editor has tab actions of its own (a Markdown file's
                // preview editor has) and one whose editor has none — read off the snapshot: the
                // retained toolbar's strip held one Markdown tab, and a Kotlin tab selected. Nothing
                // replaces that toolbar until the selection moves again, so the group has to stay
                // hidden up to the unload. The round trip above cannot catch it: Kotlin tabs alone
                // never rebuild the toolbar, and its Show moves the selection on a clean stack.
                setGroupHidden("Hideable", hidden = false)
                waitFor(ofSeconds(20)) { openTabNames().size == 3 }
                openFile(HIDDEN_MARKDOWN)
                waitFor(ofSeconds(20)) { selectedTabName() == HIDDEN_MARKDOWN }
                waitFor(
                    ofSeconds(20),
                    errorMessage = "$HIDDEN_MARKDOWN never got a tab with tab actions, so this " +
                        "scenario would pass without exercising anything",
                ) { tabHasTabActions(HIDDEN_MARKDOWN) }
                setGroupHidden("Hideable", hidden = true)
                waitFor(ofSeconds(20)) { openTabNames().size == 2 }

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
                    listOf("Sample.kt", "Second.kt"),
                    openTabNames().sorted(),
                    "unloading must leave the user's tabs open — only their colours go",
                )

                // Re-enabling is the same round trip in reverse, and has to put back what the unload
                // took: the platform re-registers the tab-colour provider but repaints nothing, so
                // the tabs left open across it stay uncoloured unless the plugin asks. The flag drops
                // first because reloadPrismTabs consumes its stashed descriptor either way: a second
                // attempt from the `finally` would have nothing to load and mask this failure.
                unloaded = false
                assertEquals(true, reloadPrismTabs(), "PrismTabs could not be loaded back")
                waitFor(ofSeconds(20), errorMessage = "the tabs did not get their colour back after a reload") {
                    tabColorOf("Sample.kt") !in listOf("none", "no tab")
                }
            } finally {
                try {
                    if (unloaded) {
                        assertNotEquals(false, reloadPrismTabs(), "PrismTabs could not be loaded back")
                    }
                } finally {
                    // Groups before the file: deleting the last member of a group raises PrismTabs'
                    // modal "delete the empty group?" prompt, which would block the sandbox's EDT
                    // for every test that runs after this one.
                    resetTabGroups()
                    deleteFixtureFile(HIDDEN_MARKDOWN)
                }
            }
        }
    }

    private companion object {
        const val HIDDEN_MARKDOWN = "Hideable.md"
    }

    /**
     * The platform's own verdict, asked up front: it is what makes Settings | Plugins install,
     * update, disable and uninstall PrismTabs in place rather than offering "Restart IDE". A
     * `<product-descriptor>` is one thing that turns it into a refusal ("is a paid plugin").
     */
    @Test
    fun `the platform can disable the plugin without a restart`(remoteRobot: RemoteRobot) {
        remoteRobot.idea {
            assertNull(unloadWithoutRestartRefusal(), "the platform refuses to unload PrismTabs without a restart")
        }
    }
}
