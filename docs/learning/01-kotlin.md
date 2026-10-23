# 1. Kotlin

This chapter covers the Kotlin that PrismTabs uses, in roughly the order you need it. Each section
shows a snippet from the repository, says what it does, and names the Java equivalent where one
helps. Kotlin compiles to the same JVM bytecode as Java and calls Java classes directly, which is why
the whole IntelliJ Platform (mostly Java) is usable from it with no wrappers.

## Files, packages and imports

```kotlin
package io.prismtabs.service

import com.intellij.openapi.components.Service
import io.prismtabs.model.TabGroup
```

- A `.kt` file can hold any number of classes, functions and constants. `TabGroupPopup.kt` holds
  one `object`, about a dozen classes and many top-level functions.
- Top-level functions need no enclosing class. `removeFromGroup` in `ui/TabGroupPopup.kt` is called
  from other files as plain `removeFromGroup(project, service, url)`.
- `import java.awt.Color as AwtColor` (in `ui/ColorSwatchIcon.kt`) renames an import to avoid a
  clash.

## Variables and types

```kotlin
val service = project.service<TabGroupService>()   // read-only reference, type inferred
var hidden: Boolean = false                          // mutable, type written out
const val PRODUCT_CODE = "PPRISMTABS"                // compile-time constant
```

- `val` is Java's `final`. Prefer it; the codebase uses `var` only where a value must change.
- Types go after the name (`name: Type`) and are usually inferred.
- `Int`, `Boolean`, `String` are Kotlin types that compile to Java primitives or `java.lang.String`.
- Hex literals work as in Java: `Color(0xDADCE0)` in `model/TabGroupPalette.kt`.

## Functions

```kotlin
fun groupFor(fileUrl: String): TabGroup? = colorCache[fileUrl]

fun assign(fileUrl: String, group: TabGroup): TabGroup? {
    val previousId = assignments[fileUrl]
    // …
    return groups[previousId]?.takeIf { membersOf(it.id).isEmpty() }
}
```

- `fun name(param: Type): ReturnType`. A function that returns nothing returns `Unit` (Java's
  `void`) and omits the return type.
- A single-expression function uses `=` instead of a block, as `groupFor` does.
- **Default and named arguments** replace Java overloads and builders:

  ```kotlin
  data class TabGroup(
      val id: String,
      val name: String,
      val lightRgb: Int,
      val darkRgb: Int,
      val rememberedTabUrls: List<String> = emptyList(),
      val order: Int = 0,
  )

  TabGroup(id = "red", name = "Red", lightRgb = 0xF6AEA9, darkRgb = 0x9C3333)
  ```

  Omitting a defaulted argument is fine for this plugin's own code. It is **forbidden** on a
  `com.intellij` Kotlin function; see [Rules this repository adds](#rules-this-repository-adds).
- A trailing comma after the last parameter or argument is allowed and used throughout.

## Null safety

Kotlin separates types that can hold `null` (`String?`) from types that cannot (`String`). The
compiler refuses to call a method on a nullable value until you have dealt with the `null`.

```kotlin
override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
    TabGroupPopup.show(project, file, e.dataContext)
}
```

| Operator      | Meaning                                                    | Example in the repo                                          |
|---------------|------------------------------------------------------------|--------------------------------------------------------------|
| `T?`          | The type may be `null`                                     | `fun groupFor(fileUrl: String): TabGroup?`                   |
| `a?.b`        | Call `b` only if `a` is not `null`, otherwise yield `null` | `activeGroupId?.let { groups[it] }`                          |
| `a ?: b`      | `a` unless it is `null`, then `b`                          | `e.project ?: return` — leave the function early             |
| `a!!`         | Assert not `null`, throw if it is                          | Not used in this codebase. Avoid it.                         |
| `as?`         | Cast, or `null` if the type does not match                 | `it.lastPathComponent as? TabGroupsNode`                     |
| `.orEmpty()`  | Replace a `null` collection or string with an empty one    | `tree.selectionPaths.orEmpty()`                              |

`?: return` is the most common idiom here. It replaces Java's `if (x == null) return;` and leaves
`x` typed as non-null for the rest of the function.

Values from Java APIs are *platform types* (`String!` in the IDE tooltip): Kotlin does not know
whether they can be `null`. Treat them as nullable unless the Java side is annotated `@NotNull`.

## Classes

### Primary constructor and properties

```kotlin
internal class TabGroupVfsListener(private val project: Project) : BulkFileListener {
```

The parentheses after the class name are the primary constructor. `private val project` declares
the parameter *and* a private field in one step. `: BulkFileListener` implements an interface; for a
superclass you also call its constructor, as in `: AnAction()`.

Code that must run at construction goes in `init { }`, as in `TabStrip` and `RenameGroupDialog`.

### Properties are fields with accessors

```kotlin
private val paletteService = service<TabGroupPaletteService>()
val members: List<MemberNode> get() = (0 until childCount).map { getChildAt(it) as MemberNode }
```

The second line has no backing field: `get()` runs every time `members` is read. From Kotlin, Java
getters and setters look like properties too: `file.url` calls `getUrl()`, and
`tree.isRootVisible = false` calls `setRootVisible(false)`.

### Data classes

```kotlin
data class PresetColor(val id: String, val label: String, val light: Color, val dark: Color)
```

`data` generates `equals`, `hashCode`, `toString`, destructuring and `copy`. `copy` creates a
modified clone, which is how the immutable `TabGroup` is updated:

```kotlin
groups = groups + (groupId to group.copy(name = name))
```

A `data object` is the singleton form, used for list entries that carry no data, such as
`GroupAction.Rename` in `ui/TabGroupPopup.kt`.

`TabGroupPaletteConfigurable.isModified` relies on the generated `equals` when it compares two lists
of `PresetColor` with `edited != original`.

### `object` and `companion object`

```kotlin
internal object TabRepaint {
    fun request(project: Project, file: VirtualFile) { … }
}
```

`object` declares a singleton: the class and its only instance at once. Call it as
`TabRepaint.request(project, file)`. Java has no equivalent keyword; it compiles to a class with a
static `INSTANCE` field.

A `companion object` is an `object` nested in a class. Its members are reached through the class
name, which makes it Kotlin's replacement for Java `static`:

```kotlin
fun interface TabGroupsChangedListener {
    fun tabGroupsChanged()

    companion object {
        val TOPIC: Topic<TabGroupsChangedListener> = Topic.create(…)
    }
}
// used as TabGroupsChangedListener.TOPIC
```

An anonymous object implements an interface or extends a class inline, like a Java anonymous class:

```kotlin
tree.addMouseListener(object : MouseAdapter() {
    override fun mouseClicked(e: MouseEvent) { … }
})
```

### Sealed classes

```kotlin
private sealed class Request(val files: List<VirtualFile>, val onDone: () -> Unit)
private class Open(files: List<VirtualFile>, val requestFocus: Boolean, onDone: () -> Unit) : Request(files, onDone)
private class Close(files: List<VirtualFile>, onDone: () -> Unit) : Request(files, onDone)
```

A `sealed` class lists all of its subclasses in the same file, so a `when` over it can be checked for
completeness by the compiler. `TabStrip` uses this for its request queue; `TabGroupsNode` in
`ui/TabGroupsToolWindow.kt` uses it for the two kinds of tree row.

### `fun interface`

`fun interface TabGroupsChangedListener` has exactly one abstract method, so a lambda can be passed
wherever one is expected:

```kotlin
connection.subscribe(TabGroupsChangedListener.TOPIC, TabGroupsChangedListener { refresh() })
```

Java interfaces with one method (`Runnable`, `Executor`) work the same way: `Executor { task -> … }`
in `TabStrip`.

### Inheritance and `override`

Classes and methods are `final` unless marked `open` or `abstract`. Overriding needs the `override`
keyword, and `final override` stops further overriding, as in `SelectionAction` in
`ui/TabGroupsToolWindow.kt`.

## Visibility

| Modifier    | Visible to                                            |
|-------------|-------------------------------------------------------|
| `public`    | Everyone. The default, so it is never written.        |
| `internal`  | The same Gradle module — here, the whole plugin       |
| `protected` | The class and its subclasses                          |
| `private`   | The class, or the file for top-level declarations     |

Most classes in this plugin are `internal`: nothing outside the plugin should call them. Several KDoc
comments explain that a function is `internal` rather than `private` so that a test can call it.

## Control flow: `if` and `when` are expressions

```kotlin
e.presentation.text = if (hidden) "Show Group" else "Hide Group"
```

`when` replaces Java's `switch` and `if`/`else if` chains, and also yields a value:

```kotlin
internal fun removeFromGroupText(fileCount: Int, groupCount: Int): String = when {
    fileCount <= 1 -> "Remove from Group"
    groupCount <= 1 -> "Remove $fileCount Files from Group"
    else -> "Remove $fileCount Files from Their Groups"
}
```

With a subject, `is` checks the type and **smart-casts** the value inside that branch:

```kotlin
when (val node = path.lastPathComponent) {
    is GroupNode -> toggleVisibility(project, service, node.group)
    is MemberNode -> chooseMemberFile(project, node.group, service, node.row)
}
```

After `is GroupNode`, `node` has type `GroupNode` without a cast. The same applies after
`if (event !is VFileDeleteEvent) continue` in `TabGroupVfsListener.before`.

Ranges and loops: `for (event in events)`, `(0 until childCount).map { … }`,
`index.coerceIn(groups.indices)`.

## Strings

```kotlin
"Remove $fileCount Files from Group"
"  $count ${if (count == 1) "file" else "files"}"
```

`$name` inserts a variable and `${expression}` inserts any expression. `buildString { append(…) }`
builds a string with a `StringBuilder`, as in `deleteConfirmationMessage`.

## Lambdas and function types

```kotlin
.validationOnInput { if (it.text.isBlank()) error("Name must not be blank") else null }
```

- A lambda is `{ params -> body }`. With one parameter you can omit it and use `it`.
- The last expression is the lambda's result. No `return` needed.
- If the last parameter of a function is a lambda, it goes outside the parentheses (a *trailing
  lambda*). This is why so much Kotlin reads like a block:

  ```kotlin
  ApplicationManager.getApplication().invokeLater(
      { promptToDeleteEmptied(project, service, emptiedGroups) },
      ModalityState.nonModal(),
      project.disposed,
  )
  project.service<TabStrip>().close(files) { TabGroupNotifier.notifyChanged(project) }
  ```

- Function types are written `(A, B) -> R`. `TabStrip.open` takes `onOpened: () -> Unit = {}`, a
  callback with a default of "do nothing".
- `::name` is a function reference: `ToggleActiveAction(project, service, ::selectedGroup)` passes
  the panel's `selectedGroup` function, not its current result. `manager.openFiles.forEach(manager::updateFilePresentation)`
  passes a method bound to `manager`.
- `return` inside a lambda returns from the enclosing *function*. To leave only the lambda, use a
  label: `DumbAwareAction.create { enterOn(tree.selectionPath ?: return@create) }`.

## Collections

Kotlin's `List`, `Set` and `Map` are read-only views; `MutableList` and friends add mutation.
`TabGroupService` keeps its state in read-only maps and replaces the whole map on each change:

```kotlin
private var groups: Map<String, TabGroup> = emptyMap()

groups = groups + (group.id to group)       // new map with one entry added or replaced
groups = groups - groupId                   // new map without that key
assignments = assignments.filterValues { it != groupId }
```

`a to b` creates a `Pair`, which is how map entries are written.

The standard library functions you will meet most:

| Function                                  | Result                                              | Used in                          |
|-------------------------------------------|-----------------------------------------------------|----------------------------------|
| `map { }` / `mapNotNull { }`              | Transform each element, optionally dropping `null`s | `TabGroupVisibility.hide`        |
| `filter { }` / `filterNot { }`            | Keep matching elements                              | `TabGroupService.removeAssignments` |
| `filterIsInstance<T>()`                   | Keep elements of type `T`, typed as `T`             | `TabGroupsPanel.selectedMembers` |
| `any { }` / `none { }`                    | `Boolean` test over the elements                    | `TabGroupService.removeAssignments` |
| `associate { k to v }` / `associateBy { }` / `associateWith { }` | Build a `Map`                  | `TabGroupService.loadState`      |
| `sortedBy { }`                            | Sorted copy                                         | `TabGroupService.groups`         |
| `firstOrNull { }` / `singleOrNull()`      | One element or `null`                               | `TabGroupsPanel.selectedGroup`   |
| `distinctBy { }`                          | Remove duplicates by key                            | `RemoveFromGroupAction.update`   |
| `ifEmpty { }`                             | Replacement when the collection is empty            | `TabGroupVisibility.show`        |
| `toMutableList()` / `toList()` / `toSet()` | Copy into another collection type                  | `TabGroupService.getState`       |

Destructuring unpacks pairs and map entries:

```kotlin
val (oldUrl, newUrl) = urlChange(event) ?: continue
assignments.entries.associate { (url, groupId) -> … }
```

## Scope functions

Five small standard functions run a block with an object in scope. They cause most first-time
confusion, so learn them once:

| Function    | Object inside the block | Returns          | Typical use                                     |
|-------------|-------------------------|------------------|-------------------------------------------------|
| `apply { }` | `this`                  | the object       | Configure an object right after creating it     |
| `let { }`   | `it`                    | the block result | Run code only when a value is not `null` (`?.let`) |
| `also { }`  | `it`                    | the object       | Side effect in a chain                          |
| `run { }`   | `this`                  | the block result | Compute a value using the object's members      |
| `with(x) { }` | `this`                | the block result | Same as `run`, not an extension                 |

From the repository:

```kotlin
private val tree = Tree(treeModel).apply {
    name = GROUP_TREE_NAME
    isRootVisible = false
    showsRootHandles = true
}

override fun getState(): TabGroupState = TabGroupState().apply {
    groups = this@TabGroupService.groups.values.map { … }.toMutableList()
}
```

Inside `apply`, `this` is the new object, so `groups` means `TabGroupState.groups`. The class's own
field of the same name is reached with the labelled `this@TabGroupService`.

Two related helpers:

- `takeIf { condition }` returns the value if the condition holds, else `null`.
- `use { }` closes a `Closeable` afterwards, like Java's try-with-resources
  (`xml.use { … }` in `PrismTabsDynamicPluginListenerTest`).

## Extension functions and properties

Kotlin can add a function to an existing class without subclassing it:

```kotlin
private val DefaultMutableTreeNode.treePath: TreePath get() = TreePath(path)

internal fun SimpleColoredComponent.appendMemberFile(row: MemberFileRow, attributes: SimpleTextAttributes) { … }
```

Callers write `node.treePath` as if `treePath` were a member of the Swing class. Nothing is modified:
it compiles to a static method taking the receiver as its first argument.

The platform ships many extensions. `project.service<TabGroupService>()` is one
(`com.intellij.openapi.components.service`), and so is `thisLogger()`.

## Generics and reified type parameters

`PersistentStateComponent<TabGroupState>` and `Topic<TabGroupsChangedListener>` read as in Java.
`service<T>()` is an `inline` function with a `reified` type parameter, which lets it read `T` at
runtime; that is why it needs no `TabGroupService::class.java` argument. When a Java API does need
the class object, write `TabGroupsChangedListener::class.java`.

## Property delegates and late initialisation

```kotlin
val sandboxRunsObfuscatedBuild: Boolean by lazy { … }
```

`by lazy` computes the value on first read and caches it (`RemoteRobotExtension`, in the UI tests). `lateinit var dialogPanel: DialogPanel`
in `TabGroupPaletteConfigurable` promises the compiler that a non-null `var` is assigned before its
first read, which that code needs because the panel's own lambdas refer to it.

`val uiTestImplementation by configurations.getting` in `build.gradle.kts` is the same `by`
mechanism, provided by Gradle.

## Exceptions

Kotlin has no checked exceptions: nothing forces a `try` or a `throws` clause. `try` is an
expression, and `runCatching { }` wraps a block's result or exception in a `Result`:

```kotlin
private fun declaresOwnProductDescriptor(): Boolean = runCatching {
    // …
}.getOrElse {
    thisLogger().warn("Could not read $PLUGIN_DESCRIPTOR; treating this as a build that is not sold", it)
    false
}
```

## Annotations

Annotations work as in Java. The ones you will see:

- `@Service(Service.Level.PROJECT)`, `@State(…)`: platform metadata, covered in chapter 2.
- `@Volatile`: Java's `volatile`. `TabGroupService.colorCache` is written on one thread and read on
  the UI thread during painting.
- `@XCollection`, `@XMap`: tell the platform's XML serialiser how to store a field.

## Coroutines

Coroutines are Kotlin's lightweight concurrency: a `suspend` function can pause without blocking a
thread. PrismTabs uses them in one place, `service/TabStrip.kt`:

```kotlin
private val requests = Channel<Request>(Channel.UNLIMITED)

init {
    scope.launch {
        for (request in requests) {
            // …
            PlatformTabOpen.openFile(manager, it, request.requestFocus, edt).await()
        }
    }
}

fun open(files: List<VirtualFile>, requestFocus: Boolean, onOpened: () -> Unit = {}) {
    if (files.isEmpty()) return
    requests.trySend(Open(files, requestFocus, onOpened))
}
```

- `scope` is a `CoroutineScope` that the platform injects into the service and cancels when the
  project closes.
- `launch` starts a coroutine that loops forever over the `Channel`, a queue that suspends the
  consumer while it is empty.
- `trySend` enqueues without suspending, so the UI thread never waits.
- `await()` suspends until a Java `CompletableFuture` completes.

Read the Kotlin coroutines guide before changing this file. `CancellationException` must always be
rethrown, as the `catch` block there does.

## Java interoperability

The repository has two Java files, `PlatformTabOpen.java` and `PlatformTabClose.java`, in
`src/main/java`. Kotlin and Java classes in one module call each other freely. They are in Java for
a JVM-level reason explained in their Javadoc: only a Java *bound method reference* runs the
platform's code with no frame of this plugin on the stack.

Interop details that appear in the Kotlin files:

- `Runnable(request.onDone)` converts a Kotlin function to a Java functional interface.
- `preset.light.rgb and 0xFFFFFF` uses `and`, Kotlin's bitwise AND (Java's `&`). Also `or`, `xor`,
  `shl`, `shr`.

## Tests

```kotlin
class TabGroupServiceTest {
    @Test
    fun `reassigning a file to another group replaces it`() {
        val service = TabGroupService()
        service.assign("file:///A.kt", red)
        service.assign("file:///A.kt", blue)
        assertEquals(blue, service.groupFor("file:///A.kt"))
    }
}
```

Backticks allow spaces in a function name, which the tests use as sentences. The tests are plain
JUnit 5 (`org.junit.jupiter.api`). Run one class with:

```bash
./gradlew test --tests 'io.prismtabs.service.TabGroupServiceTest'
```

## Rules this repository adds

- **Pass every argument to a `com.intellij` Kotlin function.** Omitting a defaulted one compiles to a
  call to a generated `foo$default` method whose signature changes when the platform adds a
  parameter, which crashes with `NoSuchMethodError` on a newer IDE.
  `PlatformDefaultArgumentCallsTest` fails the build on a new one. README §"Never omit a default
  argument on a platform Kotlin API" has the full account.
- **Kotlin language and API version are pinned to 2.1** in `build.gradle.kts`, the version bundled
  with the oldest supported IDE. A newer standard library function will not compile; that is the
  pin working.
- **The Kotlin standard library is not bundled** (`kotlin.stdlib.default.dependency = false` in
  `gradle.properties`). The IDE provides it.
- **British spelling** in identifiers and text: `colour`, `recolour`, `licence` (noun).

## Exercises

1. In a scratch file, write a `data class Point(val x: Int, val y: Int)`, create one, `copy` it with a
   new `y`, and print both. Then destructure it: `val (x, y) = point`.
2. Rewrite `removeFromGroupText` in `ui/TabGroupsToolWindow.kt` with `if`/`else` in a scratch file,
   then compare it with the `when` version.
3. Add a test to `TabGroupServiceTest` that renames a directory with `remapUrl` and asserts that a
   file inside it keeps its group. Run it with the command above.
4. Find every use of `?.let` in `src/main/kotlin` (**Edit | Find | Find in Files**) and, for three of
   them, say aloud what happens when the value is `null`.
5. Work through the Kotlin Koans sections "Introduction" and "Collections".

## Further reading

| Topic                    | Link                                                        |
|--------------------------|-------------------------------------------------------------|
| Syntax overview          | <https://kotlinlang.org/docs/basic-syntax.html>             |
| Idioms                   | <https://kotlinlang.org/docs/idioms.html>                   |
| Null safety              | <https://kotlinlang.org/docs/null-safety.html>              |
| Data classes             | <https://kotlinlang.org/docs/data-classes.html>             |
| Sealed classes           | <https://kotlinlang.org/docs/sealed-classes.html>           |
| Objects and companions   | <https://kotlinlang.org/docs/object-declarations.html>      |
| Scope functions          | <https://kotlinlang.org/docs/scope-functions.html>          |
| Extensions               | <https://kotlinlang.org/docs/extensions.html>               |
| Lambdas                  | <https://kotlinlang.org/docs/lambdas.html>                  |
| Collections              | <https://kotlinlang.org/docs/collections-overview.html>     |
| Delegated properties     | <https://kotlinlang.org/docs/delegated-properties.html>     |
| Coroutines               | <https://kotlinlang.org/docs/coroutines-overview.html>      |
| Calling Java from Kotlin | <https://kotlinlang.org/docs/java-interop.html>             |
