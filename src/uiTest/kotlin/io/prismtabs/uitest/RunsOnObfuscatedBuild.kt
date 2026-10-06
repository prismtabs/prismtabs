package io.prismtabs.uitest

/**
 * Marks a uiTest class written for `runIdeForUiTestsObfuscated`, the sandbox running the obfuscated
 * jar Marketplace ships. [RemoteRobotExtension] runs a marked class only against that sandbox and
 * every unmarked class only against the others.
 *
 * A marked class reaches the plugin through the UI, platform APIs and the class names
 * `proguard-rules.pro` keeps, never through a class or member the obfuscator renames.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class RunsOnObfuscatedBuild
