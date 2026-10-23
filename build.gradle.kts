import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import proguard.gradle.ProGuardTask

// ProGuard publishes no Gradle plugin marker, so its task type comes in on the buildscript
// classpath, as in the IntelliJ Platform Gradle Plugin's own ProGuard recipe.
buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("com.guardsquare:proguard-gradle:${providers.gradleProperty("proguardVersion").get()}")
    }
}

plugins {
    kotlin("jvm") version "2.4.10"
    id("org.jetbrains.intellij.platform") version "2.19.0"
    id("org.jetbrains.changelog") version "2.5.0"
}

group = providers.gradleProperty("pluginGroup").get()
val pluginVersion = providers.gradleProperty("pluginVersion").get()
version = pluginVersion

// Pin client (uiTestImplementation) and server (robotServerPlugin) to the same version —
// see "UI testing" in README for why a mismatch here is a real, silent-failure risk.
val remoteRobotVersion = "0.11.23"

// The IDE releases the Plugin Verifier checks against. pluginVerification.ides.select{} and
// printProductsReleases both read these, so the list CI builds its verify-plugin matrix from is the
// same list a local verifyPlugin runs against. The channels repeat select{}'s own default (IPGP
// 2.19.0, createRecommendedPluginVerifierIdesProvider); printProductsReleases defaults to every
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
    // Tests of the obfuscated jar Marketplace ships. They compile against the plugin's own classes
    // but run against that jar instead (the `obfuscationTest` task sets the runtime classpath), so
    // a name they use that the obfuscator changed fails them.
    create("obfuscationTest") {
        compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
    }
}

val uiTestImplementation by configurations.getting {
    extendsFrom(configurations.testImplementation.get())
}
configurations.getByName("uiTestRuntimeOnly") {
    extendsFrom(configurations.testRuntimeOnly.get())
}
configurations.getByName("obfuscationTestImplementation") {
    extendsFrom(configurations.testImplementation.get())
}
configurations.getByName("obfuscationTestRuntimeOnly") {
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
    // Runtime only, and not for any test here: from the 2025.1 baseline the platform test
    // framework's JUnit5TestSessionListener, which it registers as a JUnit Platform launcher
    // listener, references JUnit 4's junit.framework.TestCase. Without JUnit 4 on the runtime
    // classpath the launcher cannot start at all ("Provider com.intellij.tests.
    // JUnit5TestSessionListener could not be instantiated" <- NoClassDefFoundError:
    // junit/framework/TestCase), so no test runs. 2024.2's test framework did not need it.
    testRuntimeOnly("junit:junit:4.13.2")

    // `kotlin.stdlib.default.dependency = false` (gradle.properties) opts every source set out of
    // the automatic stdlib dependency — main/test get it transitively via the platform's bundled
    // copy instead, but uiTest doesn't depend on the platform at all, so it needs it explicitly.
    uiTestImplementation(kotlin("stdlib"))
    uiTestImplementation("com.intellij.remoterobot:remote-robot:$remoteRobotVersion")
    uiTestImplementation("com.intellij.remoterobot:remote-fixtures:$remoteRobotVersion")
}

// ProGuard's ReTrace, which the `retrace` task runs. A separate artifact from the obfuscator on the
// buildscript classpath, at the same version.
val proguardRetrace: Configuration by configurations.creating {
    isCanBeConsumed = false
}

dependencies {
    proguardRetrace("com.guardsquare:proguard-retrace:${providers.gradleProperty("proguardVersion").get()}")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        // Pin to the Kotlin API bundled with the OLDEST supported IDE (2025.1, build 251,
        // ships Kotlin 2.1.10 — read from kotlin.KotlinVersionCurrentValue with javap; see
        // pluginSinceBuild in gradle.properties). Do not raise this casually: raising it risks
        // NoSuchMethodError on older IDEs.
        apiVersion.set(KotlinVersion.KOTLIN_2_1)
        languageVersion.set(KotlinVersion.KOTLIN_2_1)
        // The compiler may flag 2.1 as deprecated, but the pin above is deliberate: the warning
        // cannot be acted on until pluginSinceBuild moves to an IDE bundling a newer Kotlin.
        // This flag silences only those version-deprecation warnings, nothing else.
        freeCompilerArgs.add("-Xsuppress-version-warnings")
        jvmDefault.set(JvmDefaultMode.NO_COMPATIBILITY)
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
        // `pluginVersion` is the eagerly-read gradle property from the top of this file, NOT
        // `version` — inside pluginConfiguration that name resolves to the Property<String>
        // itself, whose toString() never matches a changelog section, silently emptying the notes.
        // Renders the section for the version being built, falling back to [Unreleased] before
        // `patchChangelog` has stamped one. The fallback is not optional: `patchChangelog` MOVES
        // the entries out of [Unreleased] into the stamped section, so reading [Unreleased] alone
        // ships an empty <change-notes/> on every build after a release is stamped — including the
        // zip that gets uploaded.
        changeNotes = provider {
            changelog.renderItem(
                (changelog.getOrNull(pluginVersion) ?: changelog.getUnreleased())
                    .withHeader(false)
                    .withEmptySections(false),
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
            // 253 (2025.3) or above outright (confirmed by reading IPGP 2.19.0 sources) — even
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
            // against Community runs on both. Switching the *compile* baseline to Ultimate would be
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

        // The Verifier's own default, restored in full — INTERNAL_API_USAGES included. It was
        // dropped for as long as TabRepaint called FileEditorManagerImpl.queueUpdateFile, since
        // the category is one switch and cannot accept that one call while refusing the next.
        // That call is gone (README "Internal API: what the Marketplace asked for"), the plugin
        // uses no internal API at all, and Marketplace refuses an upload over this category — so
        // the level that matches the plugin's actual position is the strict one.
        //
        // `verifyInternalApiAllowlist` below is kept alongside it deliberately, not redundantly:
        // it names the offending member and the IDE that reported it, and it fails if anyone
        // drops this level again to get a build green. 1.4.14 was refused by the Marketplace over
        // two calls this list would have caught, both reported on every run into a file that
        // failed nothing and so was never read.
        //
        // MISSING_DEPENDENCIES because a `<depends>` the IDE cannot resolve stops the plugin
        // loading there at all, and without this level the Verifier only prints it ("1 missing
        // mandatory dependency") and still builds green.
        failureLevel = listOf(
            VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            VerifyPluginTask.FailureLevel.INTERNAL_API_USAGES,
            VerifyPluginTask.FailureLevel.OVERRIDE_ONLY_API_USAGES,
            VerifyPluginTask.FailureLevel.MISSING_DEPENDENCIES,
        )
    }

    // Read from environment variables only — never from Gradle properties, so there's no risk of
    // a value ending up in gradle.properties and getting committed. .github/workflows/publish.yml
    // supplies them from repository secrets; docs/releasing.md says where the originals live.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

// The obfuscated plugin jar the `proguard` task writes. Named like composedJar's, since a sandbox,
// and so the installed plugin, uses this file name.
val proguardJar = layout.buildDirectory.file("proguard/${project.name}-$pluginVersion.jar")
// Turns a user's obfuscated stack trace back into source names with ProGuard's retrace. The
// publish workflow keeps each release's copy.
val proguardMapping = layout.buildDirectory.file("proguard/mapping.txt")

tasks {
    test {
        useJUnitPlatform()
        // ProductDescriptorTest asserts plugin.xml's release-version against the single source
        // intellijPlatform.pluginConfiguration.version already reads; a `-D` on the ./gradlew
        // command line would not reach this forked JVM, and neither would the property itself.
        systemProperty("prismtabs.pluginVersion", providers.gradleProperty("pluginVersion").get())
    }

    val jdkModules = project.javaToolchains.launcherFor(project.java.toolchain)
        .map { launcher -> launcher.metadata.installationPath.dir("jmods") }
    // Obfuscates the plugin jar with the rules in proguard-rules.pro (README "Obfuscation"). The
    // main sandbox takes this jar in place of composedJar's, so everything built from that sandbox
    // is obfuscated: buildPlugin's zip, and through it signPlugin, publishPlugin and verifyPlugin,
    // and runIde. Of the uiTest sandboxes only runIdeForUiTestsObfuscated takes it: the others'
    // scripts call into the plugin's classes by name.
    val proguard by registering(ProGuardTask::class) {
        description = "Obfuscates the plugin jar that the sandbox, and so the distribution zip, is built from."
        group = "build"
        configuration("proguard-rules.pro")
        injars(composedJar.map { it.archiveFile })
        outjars(proguardJar)
        // Exactly what the plugin compiles against, plus the JDK it targets; ProGuard needs the
        // class hierarchy of everything the plugin references to rename and preverify it.
        libraryjars(sourceSets.main.get().compileClasspath)
        libraryjars(
            mapOf("jarfilter" to "!**.jar", "filter" to "!module-info.class"),
            jdkModules,
        )
        printmapping(proguardMapping)
    }

    prepareSandbox {
        pluginJar = proguardJar
        dependsOn(proguard)
    }

    // ./gradlew retrace -PstackTrace=<file> [-Pmapping=<file>] prints the stack trace with source
    // names. The mapping defaults to this build's; for a released version, pass the one the publish
    // workflow kept for its tag.
    register<JavaExec>("retrace") {
        description = "Decodes an obfuscated stack trace with a ProGuard mapping file."
        group = "help"
        classpath = proguardRetrace
        mainClass = "proguard.retrace.ReTrace"
        val stackTrace = providers.gradleProperty("stackTrace").orNull?.let { file(it).absolutePath }
        val mapping = providers.gradleProperty("mapping").orNull?.let { file(it).absolutePath }
        if (mapping == null) dependsOn(proguard)
        args(listOfNotNull(mapping ?: proguardMapping.get().asFile.absolutePath, stackTrace))
        doFirst {
            require(stackTrace != null) { "pass the file holding the stack trace: -PstackTrace=<file>" }
        }
    }

    // Tests the obfuscated jar against the contracts the rules exist for: every class plugin.xml
    // names is still there, and state saved by an unobfuscated build loads. Runs in `check`, so CI
    // and the publish workflow's `build` both gate on it.
    val obfuscationTest by registering(Test::class) {
        description = "Runs the obfuscationTest suite against the obfuscated plugin jar."
        group = "verification"
        val obfuscationTestSourceSet = sourceSets["obfuscationTest"]
        testClassesDirs = obfuscationTestSourceSet.output.classesDirs
        // The obfuscated jar stands in for the plugin's own classes: sourceSets.main.output is
        // deliberately absent, so nothing can fall back to an unobfuscated copy.
        classpath = obfuscationTestSourceSet.output +
            files(proguardJar).builtBy(proguard) +
            sourceSets.main.get().compileClasspath +
            configurations["obfuscationTestRuntimeClasspath"]
        systemProperty("prismtabs.obfuscatedJar", proguardJar.get().asFile.absolutePath)
        useJUnitPlatform()
    }

    check {
        dependsOn(obfuscationTest)
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

// Internal platform APIs this plugin is allowed to call — deliberately **empty**, and the intent
// is that it stays that way. Every call the Marketplace refused in 1.4.14 now has a public
// equivalent; README "Internal API: what the Marketplace asked for" records what each one became.
//
// An entry here is a decision to ship an API the Marketplace may refuse, so it belongs in a
// review with the reasoning written down, not in a red build someone wants green. Entries are
// signature-free: a parameter list is spelled differently across the matrix and pinning one would
// fail on an IDE rather than on a change. Format, as `verifyInternalApiAllowlist` normalises a
// finding — the platform member without its signature, then the PrismTabs class using it:
//
//     "com.example.PlatformClass.member used by io.prismtabs.some.Caller"
val acceptedInternalApiUsages = emptySet<String>()

/**
 * Fails the build on any `@ApiStatus.Internal` platform call that is not [acceptedInternalApiUsages].
 *
 * `pluginVerification.failureLevel` cannot express this: INTERNAL_API_USAGES is one switch over the
 * whole category, so accepting TabRepaint's `queueUpdateFile` — which has to be accepted, nothing
 * public repaints a tab background — silences every other internal call as well. That is not a
 * hypothetical hole. It is how `FileEditorManagerEx.openFile` and `PluginManager.getPluginByClass`
 * reached a Marketplace upload and got 1.4.14 refused: both were reported by the Verifier on every
 * run, into `internal-api-usages.txt`, which nothing read because nothing failed. Worse, neither was
 * internal when it was written — JetBrains annotated them later (261 and 262), so no compiler
 * warning existed either, and the Verifier's silenced report was the only signal there ever was.
 *
 * Reads the Verifier's own output rather than the plugin's bytecode: which members carry the
 * annotation is a fact about each IDE in the matrix, not about this plugin, and the Verifier is the
 * thing that already resolves one against the other. `verificationReportsDirectory` is read off the
 * task instead of hardcoded so the two cannot drift.
 *
 * Finalizes `verifyPlugin` rather than merely depending on it, so a plain `./gradlew verifyPlugin`
 * — what a developer and `.github/workflows/verify-plugin.yml` both run — cannot skip it. It does
 * not depend on `verifyPlugin` in turn, deliberately: that would make re-reading a report already
 * on disk cost the 45-50 GB of IDE downloads the whole matrix needs, and the point of a separate
 * task is that it is cheap enough to run on its own.
 */
val verifyInternalApiAllowlist = tasks.register("verifyInternalApiAllowlist") {
    description = "Fails on any internal platform API usage the Verifier reports that is not accepted in build.gradle.kts."
    group = "verification"

    val reportsDirectory = tasks.named<VerifyPluginTask>("verifyPlugin").flatMap { it.verificationReportsDirectory }
    val accepted = acceptedInternalApiUsages
    // The report is rewritten by every run and is cheap to re-read; nothing here is worth caching,
    // and a stale "up to date" on this particular check would defeat its purpose.
    outputs.upToDateWhen { false }

    doLast {
        val reportsRoot = reportsDirectory.get().asFile

        // One directory per verified IDE. None at all means verifyPlugin produced nothing to check
        // — never that the plugin is clean — so say so rather than pass.
        val ideReports = reportsRoot.listFiles()?.filter { it.isDirectory }.orEmpty().sortedBy { it.name }
        check(ideReports.isNotEmpty()) {
            "no Plugin Verifier reports under $reportsRoot, so nothing was checked. If verifyPlugin " +
                "itself failed above, fix that first — this task has no output to read until it succeeds."
        }

        // "Internal <kind> <member> is <verb> in <user>. This <kind> is marked with @…Internal …"
        // `by` as well as `in`: an internal supertype is reported as "is extended by"/"is
        // implemented by", and that is a usage to catch, not a format change to fall over on.
        val usage = Regex("""^Internal \w+ (.+?) is \w+ (?:in|by) (.+)$""")

        // Signature off both sides, and the JVM's synthetic-class suffix off the caller: `(Project,
        // VirtualFile) : void` and `TabStrip$1` are spelled by the compiler and the IDE version, not
        // by the decision being accepted, and pinning either would make this fail on a recompile.
        fun normalise(line: String): String? {
            val head = line.substringBeforeLast(". This ")
            val (member, user) = usage.find(head)?.destructured ?: return null
            val calledMember = member.substringBefore('(').substringBefore(" : ").trim()
            val callingClass = user.substringBefore('(')
                .let { if ('(' in user) it.substringBeforeLast('.') else it }
                .substringBefore('$')
                .trim()
            return "$calledMember used by $callingClass"
        }

        val offenders = sortedMapOf<String, MutableSet<String>>()
        val seen = mutableSetOf<String>()
        var reportsRead = 0

        ideReports.forEach { ide ->
            // Absent when the Verifier found no internal usage at all on that IDE — a pass, not a
            // gap: the file is written only when there is something to write.
            ide.walkTopDown().filter { it.isFile && it.name == "internal-api-usages.txt" }.forEach { report ->
                reportsRead++
                report.readLines()
                    .filter { it.isNotBlank() }
                    .forEach { line ->
                        val normalised = normalise(line.trim())
                            ?: error("could not parse a Plugin Verifier finding in $report — its report format has changed, so this check is no longer reading what it thinks it is:\n  $line")
                        seen += normalised
                        if (normalised !in accepted) {
                            offenders.getOrPut(normalised) { sortedSetOf() } += ide.name
                        }
                    }
            }
        }

        if (offenders.isNotEmpty()) {
            error(
                buildString {
                    appendLine("Internal platform API used without being accepted in build.gradle.kts:")
                    offenders.forEach { (finding, ides) -> appendLine("  $finding  [${ides.joinToString(", ")}]") }
                    appendLine()
                    appendLine("JetBrains Marketplace refuses an upload over these — it is what got 1.4.14 refused.")
                    appendLine("Replace the call with public API; README \"Internal API: what the Marketplace asked for\"")
                    appendLine("records the public alternatives already checked and rejected for the repaint.")
                    appendLine("If it genuinely has no public equivalent, that is a deliberate decision to review, and")
                    append("it goes in acceptedInternalApiUsages in build.gradle.kts with the reasoning written down.")
                },
            )
        }

        // Not a failure: a platform that de-internalises an accepted call is good news, and going red
        // for it on one EAP build of the matrix would be the wrong way to hear it.
        (accepted - seen).forEach {
            logger.lifecycle("verifyInternalApiAllowlist: no longer reported, so the entry can go: $it")
        }
        logger.lifecycle("verifyInternalApiAllowlist: ${ideReports.size} IDE report(s), $reportsRead with internal usage, all accepted.")
    }
}

tasks.named("verifyPlugin") {
    finalizedBy(verifyInternalApiAllowlist)
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

        // The regular uiTest sandbox, but running the obfuscated jar Marketplace ships, run the same
        // way (only one of the uiTest sandboxes at a time: all listen on the robot-server port).
        // Against it `./gradlew uiTest` runs only the classes annotated @ObfuscatedBuildUiTest, which
        // drive the plugin through the UI and platform APIs alone; every other class skips, since
        // their scripts reach the plugin's classes by names the obfuscator changes. The system
        // property is how RemoteRobotExtension tells this sandbox from the others.
        register("runIdeForUiTestsObfuscated") {
            task {
                args(fixtureProject)
                jvmArgumentProviders += CommandLineArgumentProvider {
                    uiTestIdeArgs + "-Dprismtabs.uiTest.obfuscatedBuild=true"
                }
            }
            prepareSandboxTask {
                pluginJar = proguardJar
                dependsOn(tasks.named("proguard"))
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
