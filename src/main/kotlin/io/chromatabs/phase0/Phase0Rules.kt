package io.chromatabs.phase0

/**
 * Phase 0 tinting rules, deliberately kept free of any IntelliJ Platform types
 * so they can be unit-tested with plain JUnit and no test harness.
 *
 * This whole file is throwaway. Phase 1 replaces it with a real assignment map
 * held in TabGroupService.
 */
object Phase0Rules {

    enum class Tint { TEST, MARKDOWN, NONE }

    fun tintFor(fileName: String): Tint = when {
        fileName.contains("Test", ignoreCase = true) -> Tint.TEST
        fileName.endsWith(".md", ignoreCase = true) -> Tint.MARKDOWN
        else -> Tint.NONE
    }
}
