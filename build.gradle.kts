import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    kotlin("jvm") version "2.4.10"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

// Pin client (uiTestImplementation) and server (robotServerPlugin) to the same version —
// see "UI testing" in README for why a mismatch here is a real, silent-failure risk.
val remoteRobotVersion = "0.11.23"

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

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            // Deliberately UNSET. Open-ended compatibility, guarded by the Plugin Verifier
            // below rather than by a hard ceiling that locks users out on IDE upgrade.
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            // Extend this list as new platform versions ship.
            recommended()
        }
    }
}

tasks {
    test {
        useJUnitPlatform()
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
    }
}

intellijPlatformTesting {
    runIde {
        register("runIdeForUiTests") {
            task {
                // Opens the throwaway fixture project on startup so there's always exactly one
                // editor tab to right-click — see README for why exactly one, not "the plugin's
                // own project".
                args(layout.projectDirectory.dir("src/uiTest/resources/fixture-project").asFile.absolutePath)
                jvmArgumentProviders += CommandLineArgumentProvider {
                    listOf(
                        "-Drobot-server.port=8082",
                        "-Dide.mac.message.dialogs.as.sheets=false",
                        "-Djb.privacy.policy.text=<!--999.999-->",
                        "-Djb.consents.confirmation.enabled=false",
                        "-Dide.mac.file.chooser.native=false",
                        "-DjbScreenMenuBar.enabled=false",
                        "-Dapple.laf.useScreenMenuBar=false",
                        "-Didea.trust.all.projects=true",
                        "-Dide.show.tips.on.startup.default.value=false",
                        "-Didea.is.internal=true",
                    )
                }
            }
            plugins {
                robotServerPlugin(remoteRobotVersion)
            }
        }
    }
}
