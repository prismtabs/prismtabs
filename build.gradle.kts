import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    kotlin("jvm") version "2.4.10"
    id("org.jetbrains.intellij.platform") version "2.18.1"
    id("org.jetbrains.changelog") version "2.5.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

// Pin client (uiTestImplementation) and server (robotServerPlugin) to the same version —
// see "UI testing" in README for why a mismatch here is a real, silent-failure risk.
val remoteRobotVersion = "0.11.23"

// The IDE releases the Plugin Verifier checks against. pluginVerification.ides.select{} and
// printProductsReleases both read these, so the list CI builds its verify-plugin matrix from is the
// same list a local verifyPlugin runs against. The channels repeat select{}'s own default (IPGP
// 2.18.1, createRecommendedPluginVerifierIdesProvider); printProductsReleases defaults to every
// channel, so without this the two lists would differ.
val verifiedIdeType = IntelliJPlatformType.IntellijIdea
val verifiedIdeChannels = listOf(
    ProductRelease.Channel.RELEASE,
    ProductRelease.Channel.EAP,
    ProductRelease.Channel.RC,
)

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
    // remote-robot / remote-fixtures live here, not on Maven Central.
    maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
}

sourceSets {
    // Separate from `test`: these are slow, need a live IDE (`runIdeForUiTests`) running
    // out-of-band, and must never run as a side effect of a plain `./gradlew test`.
    create("uiTest") {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
}

val uiTestImplementation by configurations.getting {
    extendsFrom(configurations.testImplementation.get())
}
configurations.getByName("uiTestRuntimeOnly") {
    extendsFrom(configurations.testRuntimeOnly.get())
}

dependencies {
    intellijPlatform {
        // Compile against the OLDEST platform we support and run forward.
        // Do not raise this casually: raising it drops older IDEs.
        intellijIdeaCommunity(providers.gradleProperty("platformVersion").get())

        pluginVerifier()
        zipSigner()
        testFramework(TestFrameworkType.Platform)
    }

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // `kotlin.stdlib.default.dependency = false` (gradle.properties) opts every source set out of
    // the automatic stdlib dependency — main/test get it transitively via the platform's bundled
    // copy instead, but uiTest doesn't depend on the platform at all, so it needs it explicitly.
    uiTestImplementation(kotlin("stdlib"))
    uiTestImplementation("com.intellij.remoterobot:remote-robot:$remoteRobotVersion")
    uiTestImplementation("com.intellij.remoterobot:remote-fixtures:$remoteRobotVersion")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        // Pin to the Kotlin API bundled with the OLDEST supported IDE (2024.2, build 242,
        // ships Kotlin 2.0 — see pluginSinceBuild in gradle.properties). Do not raise this
        // casually: raising it risks NoSuchMethodError on older IDEs. 1.9 is no longer an
        // option: the Kotlin Gradle plugin has dropped support for it.
        apiVersion.set(KotlinVersion.KOTLIN_2_0)
        languageVersion.set(KotlinVersion.KOTLIN_2_0)
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

changelog {
    version = providers.gradleProperty("pluginVersion")
    repositoryUrl = "https://github.com/prismtabs/prismtabs"
}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")
        changeNotes = provider {
            changelog.renderItem(
                changelog.getUnreleased().withHeader(false).withEmptySections(false),
                Changelog.OutputType.HTML,
            )
        }

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            // Deliberately UNSET. Open-ended compatibility, guarded by the Plugin Verifier
            // below rather than by a hard ceiling that locks users out on IDE upgrade.
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            // Deliberately NOT recommended(): that infers the type to query from whatever we
            // actually compile against (IntellijIdeaCommunity, "IC", via dependencies{} above).
            // IPGP's own IntelliJPlatformType.validateVersion() rejects any IC release at build
            // 253 (2025.3) or above outright (confirmed by reading IPGP 2.18.1 sources) — even
            // JetBrains' one remaining IC 2025.3 release (253.28294.334) gets discarded by it,
            // swallowed in a runCatching{}.getOrNull(), so recommended() silently caps at 2025.2.
            //
            // IntellijIdeaUltimate and the unified IntellijIdea type both carry product code
            // "IU", which the release feed has used continuously since long before the IC/IU
            // split — so filtering on it here (rather than on whatever we compile against)
            // covers the *entire* pluginSinceBuild..present range, 2025.3+/2026.x included, in
            // one go, with no local IDE install or extra opt-in needed.
            //
            // This does NOT change what we compile against — dependencies{} above still pins to
            // intellijIdeaCommunity(platformVersion). Verifying against the wider IU matrix is
            // safe and strictly more coverage: IU is a superset of IC, so a plugin compiled
            // against Community runs on both, and Marketplace licensing is per-plugin and
            // independent of IDE edition. Switching the *compile* baseline to Ultimate would be
            // a different, unwanted trade-off (risks silently compiling against an Ultimate-only
            // API with no error to catch it) — this only widens what gets verified.
            //
            // -PverifyIde=<version> verifies that one release only, as printed by
            // printProductsReleases without its "IU-" prefix. CI sets it so each IDE gets its own
            // job: every release in one job needs roughly 45-50 GB of downloaded and extracted
            // IDEs, and a hosted runner has far less. Left unset, as it is locally, the whole range
            // is verified in one go.
            val verifyIde = providers.gradleProperty("verifyIde")
            if (verifyIde.isPresent) {
                create(verifiedIdeType, verifyIde.get())
            } else {
                select {
                    types = listOf(verifiedIdeType)
                    channels = verifiedIdeChannels
                }
            }
        }

        // Default failure levels are COMPATIBILITY_PROBLEMS, INTERNAL_API_USAGES and
        // OVERRIDE_ONLY_API_USAGES. Drop INTERNAL_API_USAGES: TabRepaint deliberately calls
        // FileEditorManagerImpl.queueUpdateFile (see README "The repaint decision") because no
        // stable public API exists for a tab-colour repaint. That call is inherently
        // @ApiStatus.Internal and always trips this category — failing the build on it would
        // make `verifyPlugin` permanently red for a risk already accepted on purpose. What
        // still fails the build is COMPATIBILITY_PROBLEMS, which is what actually catches the
        // scenario the README cares about: queueUpdateFile disappearing on a future IDE.
        failureLevel = listOf(
            VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            VerifyPluginTask.FailureLevel.OVERRIDE_ONLY_API_USAGES,
        )
    }

    // Read from environment variables only — never from Gradle properties, so there's no risk of
    // a value ending up in gradle.properties and getting committed. Locally these are simply
    // unset, so `signPlugin`/`publishPlugin` fail with Gradle's own "no value" error if ever run
    // by hand before the certificate (release-readiness-gaps.md item 2) or Marketplace token
    // (item 4) exist; .github/workflows/publish.yml supplies the same three names from repo
    // secrets when it eventually runs signPlugin/publishPlugin.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

tasks {
    test {
        useJUnitPlatform()
        // ProductDescriptorTest asserts plugin.xml's release-version against the single source
        // intellijPlatform.pluginConfiguration.version already reads; a `-D` on the ./gradlew
        // command line would not reach this forked JVM, and neither would the property itself.
        systemProperty("prismtabs.pluginVersion", providers.gradleProperty("pluginVersion").get())
    }

    // Lists the releases verifyPlugin checks against, one per line; CI turns this into its
    // verify-plugin matrix. Matches pluginVerification.ides.select{} (see verifiedIdeType).
    printProductsReleases {
        types = listOf(verifiedIdeType)
        channels = verifiedIdeChannels
    }

    // Handy while developing: opens a sandbox IDE with the plugin installed.
    runIde {
        // Uncomment to test against a specific installed IDE instead of the downloaded one:
        // localPath = file("/path/to/IntelliJ IDEA.app/Contents")
        jvmArgumentProviders += CommandLineArgumentProvider {
            listOf("-Didea.is.internal=true")
        }
    }

    // See README "UI testing": run this in the background, THEN `./gradlew uiTest` against it —
    // it's a long-lived IDE process, not something a normal task dependency can wait on.
    register<Test>("uiTest") {
        description = "Runs remote-robot UI tests against a `runIdeForUiTests` IDE started separately."
        group = "verification"
        testClassesDirs = sourceSets["uiTest"].output.classesDirs
        classpath = sourceSets["uiTest"].runtimeClasspath
        useJUnitPlatform()
        // remote-robot's Gson response parsing reflects into java.lang.Throwable fields; JDK 17+'s
        // strong encapsulation blocks that without this. Confirmed necessary by a live failure
        // (InaccessibleObjectException on Throwable#detailMessage), not a guess.
        jvmArgs("--add-opens", "java.base/java.lang=ALL-UNNAMED")
        // A `-D` on the `./gradlew` command line only sets a system property on this build's own
        // JVM — it does not reach the forked JVM this task runs tests in unless copied across
        // explicitly. WindowsManualVerificationUiTest's assumeTrue opt-in gate relies on this.
        systemProperty("prismtabs.manualVerification", System.getProperty("prismtabs.manualVerification", "false"))
    }
}

intellijPlatformTesting {
    runIde {
        // Opens the throwaway fixture project on startup so there's always exactly one editor tab
        // to right-click — see README for why exactly one, not "the plugin's own project".
        val fixtureProject =
            layout.projectDirectory.dir("src/uiTest/resources/fixture-project").asFile.absolutePath

        // Needed by every remote-robot sandbox regardless of platform chrome: a robot-server to
        // drive, and no first-run walls (privacy policy, consents, tips, project trust) for it to
        // get stuck behind. Both variants below listen on the same port, so the `uiTest` client
        // needs no changes to target either — but that also means only one may run at a time.
        val robotServerArgs = listOf(
            "-Drobot-server.port=8082",
            "-Djb.privacy.policy.text=<!--999.999-->",
            "-Djb.consents.confirmation.enabled=false",
            "-Didea.trust.all.projects=true",
            "-Dide.show.tips.on.startup.default.value=false",
            "-Didea.is.internal=true",
            // Replaces the `-Didea.required.plugins.id=io.prismtabs` the Gradle plugin puts on every
            // sandbox (a later -D wins, and these arguments come after its own). From 2026.2 the
            // platform resolves a dynamic unload against a module graph that honours that property,
            // so it refuses to unload PrismTabs at all — "was expected not to be loaded but was
            // included in the module graph" — which leaves PluginUnloadUiTest unable to do what a
            // user's Disable does. The robot server is what this sandbox genuinely cannot run
            // without; naming it keeps the property's own guarantee, just not for the plugin under
            // test.
            "-Didea.required.plugins.id=com.jetbrains.test.robot-server-plugin",
        )

        val uiTestIdeArgs = robotServerArgs + listOf(
            // Native macOS chrome that remote-robot cannot introspect or drive. The
            // screen-menu-bar pair is the one that actually bites: with the global
            // NSMenu in play the menu bar is not Swing, so nothing in the suite can
            // see it. See the note below on the variant that leaves it on precisely
            // to cover that gap.
            "-DjbScreenMenuBar.enabled=false",
            "-Dapple.laf.useScreenMenuBar=false",
            "-Dide.mac.file.chooser.native=false",
            // Vestigial on the 2024.2 baseline and kept only for older/newer hosts:
            // nothing in the platform reads this property any more, and
            // Messages.canShowMacSheetPanel()/isMacSheetEmulation() are both compiled
            // to a bare `iconst_0; ireturn` (confirmed by javap on app-client.jar), so
            // no Messages dialog can render as a native sheet whatever this says.
            "-Dide.mac.message.dialogs.as.sheets=false",
        )

        // See README "UI testing": run this in the background, THEN `./gradlew uiTest` against it.
        register("runIdeForUiTests") {
            task {
                args(fixtureProject)
                jvmArgumentProviders += CommandLineArgumentProvider { uiTestIdeArgs }
            }
            plugins {
                robotServerPlugin(remoteRobotVersion)
            }
        }

        // The same sandbox on the newest platform rather than the compile baseline, run the same way
        // (only one of the two at a time: both listen on the robot-server port). Needed because the
        // platform's behaviour moves under the plugin: from 2026.2, moving the selection between a tab
        // with tab actions of its own (a Markdown preview) and one without rebuilds the tab strip's
        // entry-point toolbar, which 2024.2 reuses — so the class-loader leak PluginUnloadUiTest
        // guards against on Hide Group only reproduces here.
        //
        // Run from a locally installed IDE rather than one the Gradle plugin downloads. A downloaded
        // IDE is extracted into Gradle's transform cache, which Gradle 9 holds immutable, and a
        // 2026.2 IDE (measured) writes into its own install directory the moment JCEF starts (Chromium
        // creates `plugins/jcef-plugin/jcef/extensions`); a Markdown preview is enough, and this
        // sandbox exists to open one. The next launch then fails "the contents of the immutable
        // workspace have been modified" until the cache is deleted by hand. An installed IDE is an
        // ordinary directory that tolerates that write, and the platform version is whatever that
        // install is on.
        //
        // The path is machine-specific, so it is read from `uiTestLatestIdePath` in the user-level
        // ~/.gradle/gradle.properties, never this repository's (see README "The newest platform").
        // The check is deferred to when the task's own IDE is resolved, so configuring the build —
        // CI's included — never needs it.
        register("runIdeForUiTestsLatest") {
            localPath = layout.dir(
                providers.gradleProperty("uiTestLatestIdePath")
                    .orElse(
                        provider {
                            throw GradleException(
                                "runIdeForUiTestsLatest needs uiTestLatestIdePath in ~/.gradle/gradle.properties: " +
                                    "the directory of an installed IntelliJ IDEA 2026.2 or later, e.g. a Toolbox app directory.",
                            )
                        },
                    )
                    .map { file(it) },
            )
            task {
                args(fixtureProject)
                jvmArgumentProviders += CommandLineArgumentProvider { uiTestIdeArgs }
            }
            plugins {
                robotServerPlugin(remoteRobotVersion)
            }
        }

        // A second "native macOS chrome" variant (screen menu bar left ON, to drive ⌘, through the
        // real menu layer) was built and measured here on 2026-08-16, and deliberately removed
        // again — it cannot work, for a reason worth not rediscovering:
        //
        // With JetBrains' screen menu bar enabled, the IDE frame reports focusedWindow and
        // activeWindow as IdeFrameImpl but **focusOwner as NONE**, so AWT has no component to
        // deliver key events to and discards them. Measured with a global AWTEventListener: a real
        // Robot mouse click still registered (mouse=1), every keystroke registered 0, and even a
        // KeyEvent posted directly onto the system event queue registered 0 — while the identical
        // control on the regular sandbox gave focusOwner=EditorComponentImpl and key=1. So the
        // variant loses keyboard automation entirely and gains nothing testable.
        //
        // ⌘, is covered against this regular sandbox instead (MacSettingsShortcutUiTest): it is an
        // ordinary keymap binding — `meta COMMA` -> ShowSettings, read live from the active
        // "Mac OS X 10.5+" keymap — not something the screen menu bar owns.
    }
}
