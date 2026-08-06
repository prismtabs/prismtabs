import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    kotlin("jvm") version "2.4.10"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
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
}
