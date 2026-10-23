# 2. The IntelliJ Platform

This chapter explains how a plugin hooks into an IntelliJ-based IDE, using the pieces PrismTabs
uses. Each section gives the concept, the PrismTabs code that uses it, and the SDK page to read
next.

## The model: the IDE calls you

A plugin is a JAR with a descriptor, `META-INF/plugin.xml`. The descriptor tells the IDE which of
the plugin's classes to call and when. The plugin almost never starts anything itself; it registers
code that the platform invokes:

| You register…                       | The platform calls it when…                              | PrismTabs example                         |
|-------------------------------------|----------------------------------------------------------|-------------------------------------------|
| An **extension** on an extension point | It needs that kind of contribution                    | `TabGroupColorProvider` — a tab is painted |
| An **action**                       | The user picks a menu item, button or shortcut           | `AssignTabGroupAction` — tab right-click   |
| A **listener** on a topic           | An event is published on the message bus                 | `TabGroupFileListener` — an editor opens   |
| A **service**                       | Code asks for it the first time                          | `TabGroupService` — holds all group state  |

Everything below elaborates one of these four.

The platform has two main scopes. The **application** is the whole IDE process; a **project** is
one open project window. Services, listeners and settings exist at one level or the other.
PrismTabs keeps tab groups per project and the colour palette per application.

## `plugin.xml`

Open `src/main/resources/META-INF/plugin.xml` alongside this section.

| Element                         | Purpose                                                                                |
|---------------------------------|----------------------------------------------------------------------------------------|
| `<id>`, `<name>`, `<vendor>`    | Identity. The ID is permanent once published.                                          |
| `<description>`                 | HTML shown on the Marketplace and in **Settings \| Plugins**                           |
| `<depends>`                     | Required modules or plugins. `com.intellij.modules.platform` makes it work in every IntelliJ-based IDE. |
| `<extensions defaultExtensionNs="com.intellij">` | Contributions to the platform's extension points                      |
| `<applicationListeners>`, `<projectListeners>` | Message bus subscriptions, created lazily by the platform              |
| `<actions>`                     | Menu items and where they appear                                                       |

The build fills in the version, `since-build` and change notes at packaging time
(`patchPluginXml`, configured under `intellijPlatform.pluginConfiguration` in `build.gradle.kts`).
They are absent from the source file on purpose.

Read: <https://plugins.jetbrains.com/docs/intellij/plugin-configuration-file.html>

## Extension points

An extension point (EP) is a named slot the platform defines and consults. A plugin fills it with a
class that implements the EP's interface. PrismTabs registers three:

```xml
<editorTabColorProvider implementation="io.prismtabs.provider.TabGroupColorProvider" order="first"/>
<toolWindow id="PrismTabs" anchor="right" icon="AllIcons.Toolwindows.ToolWindowPalette"
            factoryClass="io.prismtabs.ui.TabGroupsToolWindowFactory"/>
<applicationConfigurable parentId="tools" id="io.prismtabs.settings.palette"
                         displayName="PrismTabs" instance="io.prismtabs.ui.TabGroupPaletteConfigurable"/>
```

The core of the whole plugin is the first one:

```kotlin
internal class TabGroupColorProvider : EditorTabColorProvider, DumbAware {
    override fun getEditorTabColor(project: Project, file: VirtualFile): Color? =
        colorOf(project.service<TabGroupService>().groupFor(file.url))
}
```

The platform asks every registered `EditorTabColorProvider` for a colour when it paints a tab, and
uses the first non-`null` answer. `order="first"` puts this provider ahead of the platform's own
File Colors provider, which would otherwise answer first for test and non-project files.

To find an EP: browse <https://plugins.jetbrains.com/docs/intellij/extension-point-list.html>, or
find a platform feature that does something similar and search the IDE's own `plugin.xml` files for
how it is registered.

Read: <https://plugins.jetbrains.com/docs/intellij/plugin-extensions.html>

## Services

A service is a singleton the platform creates on first use and disposes with its scope.

```kotlin
@Service(Service.Level.PROJECT)
class TabGroupService : PersistentStateComponent<TabGroupState> { … }

val service = project.service<TabGroupService>()                    // project level
private val paletteService = service<TabGroupPaletteService>()      // application level
```

- `@Service` registers it; no `plugin.xml` entry is needed. This is a *light service*.
- A project-level service may take `Project` and a `CoroutineScope` in its constructor, and the
  platform supplies both. `TabStrip(private val project: Project, private val scope: CoroutineScope)`
  uses this.
- Get a service when you need it rather than storing it in a static field. A static reference outlives
  the project and leaks it.

Read: <https://plugins.jetbrains.com/docs/intellij/plugin-services.html>

## Persisting state

A service that implements `PersistentStateComponent<S>` is saved to XML automatically:

```kotlin
@State(name = "TabGroupState", storages = [Storage("tabGroups.xml")])
class TabGroupService : PersistentStateComponent<TabGroupState> {
    override fun getState(): TabGroupState = …   // platform calls this to save
    override fun loadState(state: TabGroupState) { … }  // and this after reading the file
}
```

- A project-level `Storage("tabGroups.xml")` lands in the project's `.idea/tabGroups.xml`. The
  application-level palette lands in the IDE's config directory as `prismtabsPalette.xml`. The
  uiTest fixture holds a real example: `src/uiTest/resources/fixture-project/.idea/tabGroups.xml`.
- The state class (`model/TabGroupState.kt`) is a plain class with a no-argument constructor and
  mutable `var` fields, because the platform's `XmlSerializer` fills it by reflection. PrismTabs
  keeps it separate from the immutable domain class `TabGroup` and converts between them in
  `getState` and `loadState`.
- The platform saves on its own schedule (frame deactivation, project close, IDE exit), not on each
  change.

Read: <https://plugins.jetbrains.com/docs/intellij/persisting-state-of-components.html>

## Actions

An action is a class extending `AnAction`, registered in `plugin.xml` and placed into an existing
menu group:

```xml
<action id="PrismTabs.AssignTabGroup" class="io.prismtabs.action.AssignTabGroupAction"
        text="Tab Group…" description="Assign this tab's colour group">
    <add-to-group group-id="EditorTabPopupMenu" anchor="first"/>
</action>
```

```kotlin
internal class AssignTabGroupAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible =
            e.project != null && e.getData(CommonDataKeys.VIRTUAL_FILE) != null
    }

    override fun actionPerformed(e: AnActionEvent) { … }
}
```

- `update` runs often (whenever the menu or toolbar is shown) and decides visibility and text. Keep
  it fast.
- `getActionUpdateThread` declares where `update` runs. `BGT` (background) is the default choice;
  `EDT` is required when `update` touches Swing components, as the tool window's actions do because
  they read the tree's selection.
- `actionPerformed` does the work, always on the UI thread.
- `AnActionEvent` carries a **data context**: whatever the component under the action publishes.
  `e.getData(CommonDataKeys.VIRTUAL_FILE)` is the file of the right-clicked tab. The tool window
  publishes its own selection the same way in `TabGroupsPanel.uiDataSnapshot`, which is what makes
  F4 and **Copy Path** work on its rows.
- Actions can also be created in code and shown in a toolbar or context menu without `plugin.xml`,
  as `TabGroupsPanel` does with `ActionManager.createActionToolbar` and `createActionPopupMenu`.
- `ToggleAction` renders as a checked menu item or a pressed button (`ToggleActiveAction`).
- To run another action programmatically, use `ActionUtil`, never `actionPerformed` directly:
  `actionPerformed` is `@ApiStatus.OverrideOnly`, and `verifyPlugin` fails the build on a call to it.

Read: <https://plugins.jetbrains.com/docs/intellij/basic-action-system.html>

## The message bus and listeners

The platform publishes events on **topics**. A listener subscribes to a topic and receives calls on
an interface.

Declarative subscription in `plugin.xml`, the preferred form:

```xml
<projectListeners>
    <listener class="io.prismtabs.listener.TabGroupFileListener"
              topic="com.intellij.openapi.fileEditor.FileEditorManagerListener"/>
</projectListeners>
```

Programmatic subscription, tied to a lifetime:

```kotlin
val connection = project.messageBus.connect(disposable)
connection.subscribe(TabGroupsChangedListener.TOPIC, TabGroupsChangedListener { refresh() })
```

A plugin can define its own topic. `service/TabGroupTopics.kt` declares `TabGroupsChangedListener`,
and every code path that changes groups publishes on it:

```kotlin
project.messageBus.syncPublisher(TabGroupsChangedListener.TOPIC).tabGroupsChanged()
```

This decouples the tool window from the popup, the VFS listener and the file listener: none of them
needs a reference to the panel.

Topics PrismTabs listens to:

| Topic (listener interface)     | Level       | Handler                                 |
|--------------------------------|-------------|-----------------------------------------|
| `FileEditorManagerListener`    | project     | `TabGroupFileListener`, `TabGroupsPanel` |
| `BulkFileListener`             | application (delivered per project) | `TabGroupVfsListener` |
| `DynamicPluginListener`        | application | `PrismTabsDynamicPluginListener`        |
| `ToolWindowManagerListener`    | project     | `TabGroupsPanel`                        |
| `TabGroupsChangedListener`     | project     | `TabGroupsPanel`                        |

Read: <https://plugins.jetbrains.com/docs/intellij/plugin-listeners.html> and
<https://plugins.jetbrains.com/docs/intellij/messaging-infrastructure.html>

## Files: the Virtual File System

The IDE never hands you `java.io.File`. It gives you a `VirtualFile`, a cached view of a file that
can also live inside a JAR or on a remote host.

- `file.url` is a stable string such as `file:///home/me/project/Main.kt`. PrismTabs keys every
  assignment by URL, which survives restarts and needs no live `VirtualFile`.
- `VirtualFileManager.getInstance().findFileByUrl(url)` converts back; it returns `null` for a
  file that no longer exists.
- Changes arrive as `VFileEvent`s on `BulkFileListener`: `VFilePropertyChangeEvent` (rename),
  `VFileMoveEvent`, `VFileDeleteEvent`, and others. `TabGroupVfsListener` handles all three,
  deletions in `before` (while the file still exists) and renames and moves in `after`.
- A directory rename produces one event for the directory, not one per file inside it. That is why
  `TabGroupService.remapUrl` matches by prefix.

Read: <https://plugins.jetbrains.com/docs/intellij/virtual-file-system.html>

## Editors and tabs: `FileEditorManager`

`FileEditorManager.getInstance(project)` is the entry point to open editors:

| Call                                    | Use in PrismTabs                                   |
|-----------------------------------------|----------------------------------------------------|
| `openFiles`                             | Which members of a group are open (`TabGroupVisibility`) |
| `isFileOpen(file)`                      | Bold rows in the tool window                       |
| `openFile(file, focusEditor)`, `closeFile(file)` | Show and hide a group, via `TabStrip`     |
| `updateFilePresentation(file)`          | Make the tab strip ask the colour provider again (`TabRepaint`) |

The colour provider is only asked when the platform repaints a tab's presentation. After changing
an assignment, the code must call `TabRepaint.request`, or the tab keeps its old colour.

Read: <https://plugins.jetbrains.com/docs/intellij/editor-basics.html>

## Threading

The IDE has one UI thread, the **EDT** (Swing's Event Dispatch Thread), and a read/write lock over
its data model.

| Rule                                                            | Where it shows up in PrismTabs                         |
|-----------------------------------------------------------------|--------------------------------------------------------|
| Touch Swing only on the EDT                                     | Actions with `ActionUpdateThread.EDT`; all UI code     |
| Never block the EDT                                             | `TabStrip.open` queues and returns                     |
| Modify files or the model inside a write action                 | Not needed: PrismTabs changes only its own state       |
| Defer work to the EDT with `invokeLater`                        | `TabGroupVfsListener.before` defers the empty-group dialog |
| Choose a `ModalityState` for deferred work                      | `ModalityState.nonModal()` waits until open dialogs close |
| Pass a disposal condition so deferred work is dropped with the project | `project.disposed` as the third argument        |

`BulkFileListener.after` runs inside a write action on the EDT, so `TabGroupService` can change its
plain fields there without locking.

**Dumb mode** is the period while the IDE builds its indexes. Actions and extensions that do not use
indexes implement the `DumbAware` marker interface; otherwise the platform skips them until
indexing finishes. The colour provider, the tab action and the tool window factory are all
`DumbAware`, since a tab should be coloured from the first second.

Read: <https://plugins.jetbrains.com/docs/intellij/threading-model.html> and
<https://plugins.jetbrains.com/docs/intellij/indexing-and-psi-stubs.html>

## Lifetimes: `Disposable`

A `Disposable` is anything with a `dispose()` that must run when its owner goes away. Pass one when
subscribing or registering so the platform cleans up for you:

```kotlin
val panel = TabGroupsPanel(project, toolWindow.disposable)       // lives as long as the tool window
val connection = project.messageBus.connect(disposable)          // unsubscribed with it
DumbAwareAction.create { … }.registerCustomShortcutSet(CommonShortcuts.ENTER, tree, disposable)
```

Forgetting to pass a disposable is the classic memory leak in plugins, and it blocks unloading the
plugin without a restart.

Read: <https://plugins.jetbrains.com/docs/intellij/disposers.html>

## User interface

The platform UI is Swing, restyled. Use the platform's components (`JBTextField`, `Tree`,
`JBPanelWithEmptyText`) rather than raw Swing ones so they follow the theme and the IDE's scaling.

### Popups

`TabGroupPopup` is a multi-level list popup built from **steps**. Each level is a
`BaseListPopupStep<T>` that supplies text, icons and separators for its items, and `onChosen`
returns either the next step (a submenu) or `PopupStep.FINAL_CHOICE`:

```kotlin
override fun onChosen(selectedValue: Entry, finalChoice: Boolean): PopupStep<*>? =
    when (selectedValue) {
        Entry.AddToGroup -> AddToGroupStep(project, file, service)   // opens a submenu
        Entry.RemoveFromGroup -> {
            finalRunnable = Runnable { removeFromGroup(project, service, file.url) }
            PopupStep.FINAL_CHOICE
        }
        …
    }
```

Work that opens a dialog goes in `getFinalRunnable`, which runs after the popup has closed.

Read: <https://plugins.jetbrains.com/docs/intellij/popups.html>

### Dialogs

`RenameGroupDialog` extends `DialogWrapper`: call `init()` in `init { }`, build the content in
`createCenterPanel`, validate in `doValidate`, and show it with `showAndGet()`, which returns `true`
on OK. For a single question, `Messages.showInputDialog` and `MessageDialogBuilder.yesNo` are
enough (`promptForNameAndCreate`, `confirmAndDelete`).

Read: <https://plugins.jetbrains.com/docs/intellij/dialog-wrapper.html>

### Kotlin UI DSL

Forms are built with a Kotlin DSL rather than layout managers:

```kotlin
override fun createCenterPanel(): JComponent = panel {
    row("Name:") {
        cell(nameField).focused()
    }
}
```

`TabGroupPaletteConfigurable.createPanel` is a larger example: one `row` per preset, `textField()`
bound to data with `bindText`, and `ColorPanel` cells bound with `bind`. Bound fields let the
platform implement `isModified`, `apply` and `reset` for you.

Read: <https://plugins.jetbrains.com/docs/intellij/kotlin-ui-dsl-version-2.html>

### Settings pages

`applicationConfigurable` in `plugin.xml` adds a page under **Settings**. `parentId="tools"` places
it under **Tools**. The class extends `BoundSearchableConfigurable`, which pairs with the UI DSL.

Read: <https://plugins.jetbrains.com/docs/intellij/settings-guide.html>

### Tool windows

A `ToolWindowFactory` registered with `<toolWindow>` creates the content once, when the user first
opens the window:

```kotlin
override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val panel = TabGroupsPanel(project, toolWindow.disposable)
    val content = ContentFactory.getInstance().createContent(panel, "", false)
    toolWindow.contentManager.addContent(content)
}
```

`TabGroupsPanel` extends `SimpleToolWindowPanel`, which provides the toolbar-plus-content layout.
Inside it:

- `Tree` with a `DefaultTreeModel` holds the rows; the model is rebuilt on each change.
- `ColoredTreeCellRenderer` draws each row with `append(text, attributes)` and an `icon`.
- `TreeSpeedSearch.installOn` gives type-to-filter.
- `PopupHandler` shows the context menu.

Read: <https://plugins.jetbrains.com/docs/intellij/tool-windows.html>

### Colours, sizes and icons

- `JBColor(light, dark)` picks a colour by the current theme at paint time, so no code runs on a
  theme switch.
- `JBUI.scale(16)` converts a size to the current UI scale. Never hard-code pixels.
- `AllIcons.*` holds the platform's icons. A plugin's own SVGs load with
  `IconLoader.getIcon("/icons/showGroup.svg", PrismTabsIcons::class.java)`, and a sibling
  `showGroup_dark.svg` is picked for dark themes automatically.

Read: <https://plugins.jetbrains.com/docs/intellij/icons.html>

## Dynamic plugins

Installing, updating or disabling a plugin can happen without an IDE restart, if the plugin
releases everything it holds. The platform checks this by requiring the plugin's class loader to be
garbage-collectable after unload.

PrismTabs supports it through:

- Declarative registration only (`plugin.xml` listeners, light services), never a startup activity
  that subscribes by hand.
- `PrismTabsDynamicPluginListener`, which on `beforePluginUnload` saves state, stops colouring tabs
  and repaints them, and clears platform caches that hold its objects.
- `TabStrip` with `PlatformTabOpen` and `PlatformTabClose`, which keep plugin classes off the call
  stack when the platform builds toolbars.

This is the most advanced part of the codebase. `PluginUnloadUiTest` checks it end to end.

Read: <https://plugins.jetbrains.com/docs/intellij/dynamic-plugins.html>

## Build and run

The build uses the IntelliJ Platform Gradle Plugin (`org.jetbrains.intellij.platform` in
`build.gradle.kts`). It downloads the IDE to compile against and provides these tasks:

| Command                  | What it does                                                                |
|--------------------------|-----------------------------------------------------------------------------|
| `./gradlew test`         | Unit tests. No IDE starts.                                                  |
| `./gradlew runIde`       | Starts a sandbox IDE with the plugin installed                             |
| `./gradlew buildPlugin`  | Builds the distributable ZIP into `build/distributions`                     |
| `./gradlew verifyPlugin` | Runs the Plugin Verifier against a range of IDE releases                    |
| `./gradlew uiTest`       | UI tests against a running `runIdeForUiTests` IDE                           |

Compatibility settings live in `gradle.properties`:

- `platformVersion = 2024.2` is the IDE compiled against, deliberately the **oldest** supported
  release.
- `pluginSinceBuild = 242` is the lowest IDE build that may install the plugin. There is no upper
  bound; `verifyPlugin` checks newer releases instead.

Read: <https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html> and
<https://plugins.jetbrains.com/docs/intellij/verifying-plugin-compatibility.html>

## Finding and checking platform API

- **Read the platform's source in the IDE.** Ctrl+click any platform class. With no sources jar the
  IDE shows decompiled bytecode, which is enough for signatures and annotations.
- **Check signatures with `javap`** using the recipe in `CLAUDE.md` §"Verifying a platform API".
  Dump the whole class; never reason from a truncated listing.
- **Avoid `@ApiStatus.Internal`** members. JetBrains Marketplace refuses plugins that use them, and
  `verifyPlugin` fails the build on one. `@ApiStatus.Experimental` and `@ApiStatus.OverrideOnly`
  carry their own restrictions.
- **Learn from the IDE's own plugins.** The bundled plugins use the same API. Search their
  `plugin.xml` files for an EP name to see real registrations.
- **Ask the community.** The JetBrains Platform forum and the SDK's code samples repository cover
  most questions.

Read: <https://plugins.jetbrains.com/docs/intellij/explore-api.html> and
<https://plugins.jetbrains.com/docs/intellij/api-internal.html>

## Testing

PrismTabs tests at three levels:

| Level                  | Location           | Needs an IDE | Example                                   |
|------------------------|--------------------|--------------|-------------------------------------------|
| Plain JUnit 5          | `src/test/kotlin`  | No           | `TabGroupServiceTest`, `RemoveFromGroupTextTest` |
| Bytecode checks        | `src/test/kotlin`  | No           | `PlatformDefaultArgumentCallsTest`, `PlatformStackHandOffTest` |
| UI tests (remote-robot) | `src/uiTest/kotlin` | Yes, driven by mouse and keyboard | `TabGroupPopupUiTest`, `PluginUnloadUiTest` |

The design choice that makes the first level possible: model and service classes avoid live
platform calls, and anything that needs one (`FileEditorManager`, `JBColor`) lives in a separate
class. `TabGroupService` has no `FileEditorManager` access; `TabGroupVisibility` and `TabRepaint`
do. Pure wording functions such as `deleteConfirmationMessage` are split out of UI code so they can
be tested.

Engineering notes §"UI testing" explains how to run the UI suite, including the X11 requirement on Linux.

Read: <https://plugins.jetbrains.com/docs/intellij/testing-plugins.html>

## Exercises

1. Add a second action to `EditorTabPopupMenu` that shows the tab's group name in a balloon
   (`Messages.showInfoMessage` is enough). Register it in `plugin.xml`, implement `update` so it
   only shows for an assigned file, and try it in `./gradlew runIde`. Do not commit it.
2. In the sandbox IDE, create a group, then open the sandbox project's `.idea/tabGroups.xml` and
   match each element to a field in `TabGroupState`.
3. Put a breakpoint in `TabGroupColorProvider.getEditorTabColor`, start `runIde` in debug mode
   (the Gradle tool window's debug button on the task), and switch tabs. Note how often it is called.
4. Find where `TabGroupsChangedListener.TOPIC` is published (**Find Usages** on
   `TabGroupNotifier.notifyChanged`) and list each user action that triggers a tool window refresh.
