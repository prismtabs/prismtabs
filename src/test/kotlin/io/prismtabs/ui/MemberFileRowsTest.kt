package io.prismtabs.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Plain JUnit 5, no platform harness — the same split (and the same reason for it) as
 * [CurrentGroupCaptionTest]: what a group's file list *says* about each member is string work over
 * the assignment URLs, while the chooser that paints it needs a live IDE and belongs to `uiTest`.
 *
 * The sort is the case worth pinning hardest. `TabGroupService.membersOf` hands back map iteration
 * order, which is arbitrary after a `loadState`, and this list is the first surface anywhere that
 * would show that as jumbled.
 */
class MemberFileRowsTest {

    private val base = "/home/dev/project"

    private fun rows(vararg urls: String, missing: (String) -> Boolean = { false }) =
        memberFileRows(urls.toList(), base, missing)

    @Test
    fun `a member is its file name plus the directory it sits in, relative to the project`() {
        val row = rows("file://$base/src/main/kotlin/Auth.kt").single()
        assertEquals("Auth.kt", row.name)
        assertEquals("src/main/kotlin", row.directory)
        assertEquals("src/main/kotlin/Auth.kt", row.path)
        assertFalse(row.missing)
    }

    @Test
    fun `a file at the project root has no directory to show`() {
        val row = rows("file://$base/build.gradle.kts").single()
        assertEquals("build.gradle.kts", row.name)
        assertEquals("", row.directory)
        // The rejoined path must not gain a leading slash — it is quoted in the missing-file prompt.
        assertEquals("build.gradle.kts", row.path)
    }

    @Test
    fun `a file outside the project keeps its absolute directory`() {
        val row = rows("file:///etc/hosts").single()
        assertEquals("hosts", row.name)
        assertEquals("/etc", row.directory)
    }

    /** A near-miss prefix is not a parent: `/home/dev/project-old` is its own directory. */
    @Test
    fun `a sibling directory sharing the project's prefix is not treated as inside it`() {
        val row = rows("file:///home/dev/project-old/Auth.kt").single()
        assertEquals("/home/dev/project-old", row.directory)
    }

    @Test
    fun `a project with no base path leaves every directory absolute`() {
        val row = memberFileRows(listOf("file://$base/src/Auth.kt"), null) { false }.single()
        assertEquals("$base/src", row.directory)
    }

    @Test
    fun `a library file inside a jar splits at its last segment`() {
        val row = rows("jar:///home/dev/lib/guava.jar!/com/google/common/base/Optional.class").single()
        assertEquals("Optional.class", row.name)
        assertEquals("/home/dev/lib/guava.jar!/com/google/common/base", row.directory)
    }

    @Test
    fun `rows are ordered by directory, then by name — not by the order membersOf happened to yield`() {
        val ordered = rows(
            "file://$base/src/Zebra.kt",
            "file://$base/Root.kt",
            "file://$base/src/Auth.kt",
            "file://$base/docs/plan.md",
        ).map { it.path }
        assertEquals(
            listOf("Root.kt", "docs/plan.md", "src/Auth.kt", "src/Zebra.kt"),
            ordered,
        )
    }

    @Test
    fun `ordering ignores case, so a lowercase name does not sort below every capital`() {
        val ordered = rows(
            "file://$base/src/beta.kt",
            "file://$base/src/Alpha.kt",
            "file://$base/src/Gamma.kt",
        ).map { it.name }
        assertEquals(listOf("Alpha.kt", "beta.kt", "Gamma.kt"), ordered)
    }

    /**
     * The whole point of the phase: a member whose file is gone is *shown*, in its sorted place,
     * rather than quietly dropped from a list that is meant to be the answer to "what is in here?".
     */
    @Test
    fun `a member whose file no longer resolves is kept, and marked missing`() {
        val rows = rows(
            "file://$base/src/Gone.kt",
            "file://$base/src/Here.kt",
            missing = { it.endsWith("Gone.kt") },
        )
        assertEquals(listOf("Gone.kt", "Here.kt"), rows.map { it.name })
        assertTrue(rows.first().missing, "the deleted file's row must say so")
        assertFalse(rows.last().missing, "a file that is still there must not")
    }

    @Test
    fun `a short directory is painted as it is`() {
        assertEquals("src/main/kotlin", shortenedDirectory("src/main/kotlin"))
    }

    @Test
    fun `a long directory is elided in the middle, keeping the segment the file lives in`() {
        val deep = (1..20).joinToString("/") { "package$it" }
        val shortened = shortenedDirectory(deep)
        assertTrue(shortened.length < deep.length, "a 20-segment path must not be painted in full")
        assertTrue(shortened.endsWith("package20"), "the deepest segment is the informative one, got $shortened")
        assertTrue(shortened.contains("…"), "an elided path must say so, got $shortened")
        assertFalse(shortened.contains("..."), "the ellipsis must match the popup's own labels")
    }

    @Test
    fun `the missing-file prompt quotes the file and the group it would be removed from`() {
        assertEquals(
            "\"src/Gone.kt\" is no longer on disk.\n\nRemove it from \"Auth\"?",
            missingFileMessage("src/Gone.kt", "Auth"),
        )
    }
}
