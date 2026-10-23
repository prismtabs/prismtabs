# 3. How PrismTabs works

This chapter maps the source tree and traces each feature from the user's gesture to the code that
handles it. Read it with the IDE open and follow each class name with **Navigate | Class**
(Ctrl+N, Cmd+O on macOS).

## Source map

All production code is under `src/main`, in package `io.prismtabs`.

| Package / file                          | Role                                                                                   |
|-----------------------------------------|----------------------------------------------------------------------------------------|
| `resources/META-INF/plugin.xml`         | Registers everything below with the platform                                            |
| `model/TabGroup.kt`                     | The domain object: id, name, light and dark colour, remembered tabs, order              |
| `model/TabGroupPalette.kt`              | The eight built-in preset colours (`PresetColor`)                                       |
| `model/TabGroupState.kt`, `TabGroupPaletteState.kt` | Mutable XML-serialisable copies of the above, for persistence              |
| `service/TabGroupService.kt`            | Per-project source of truth: groups, file-to-group assignments, active group            |
| `service/TabGroupPaletteService.kt`     | IDE-wide palette: user overrides layered over the presets                               |
| `service/TabGroupTopics.kt`             | `TabGroupsChangedListener`, the "groups changed" event, and `TabGroupNotifier`          |
| `service/TabRepaint.kt`                 | Asks the tab strip to re-query a tab's colour                                           |
| `service/TabGroupVisibility.kt`         | Hide and show a group; whether a group counts as hidden                                 |
| `service/TabStrip.kt`, `java/…/PlatformTabOpen.java`, `PlatformTabClose.java` | Open and close tabs in order, without pinning the plugin's class loader |
| `provider/TabGroupColorProvider.kt`     | Answers the platform's "what colour is this tab?"                                       |
| `action/AssignTabGroupAction.kt`        | The **Tab Group…** item on a tab's context menu                                         |
| `ui/TabGroupPopup.kt`                   | The swatch popup and most group operations (create, assign, rename, delete, remove)     |
| `ui/TabGroupsToolWindow.kt`             | The **PrismTabs** tool window: tree of groups and files, toolbar, context menu          |
| `ui/TabGroupPaletteConfigurable.kt`     | **Settings \| Tools \| PrismTabs**                                                      |
| `ui/RenameGroupDialog.kt`, `ColorSwatchIcon.kt`, `PrismTabsIcons.kt` | Small UI pieces                                            |
| `listener/TabGroupFileListener.kt`      | On editor open: auto-join the active group, remember the group's open tabs              |
| `listener/TabGroupVfsListener.kt`       | On file rename, move or delete: keep assignments correct                                |
| `listener/PrismTabsDynamicPluginListener.kt` | On plugin load or unload without restart: repaint, save, release references       |

Dependencies point one way: `ui`, `listener`, `action` and `provider` call `service`, and `service`
calls `model`. `TabGroupService` and `model` make no live platform calls, which is what lets
`TabGroupServiceTest` run without an IDE.

## The data model

`TabGroupService` holds three things:

```kotlin
private var groups: Map<String, TabGroup> = emptyMap()        // group id -> group
private var assignments: Map<String, String> = emptyMap()     // file URL -> group id
@Volatile private var activeGroupId: String? = null           // not persisted
```

and one derived cache:

```kotlin
@Volatile private var colorCache: Map<String, TabGroup> = emptyMap()   // file URL -> group
```

Facts to hold on to when reading the rest of the code:

- **A file is in at most one group.** `assignments` maps a URL to a single id.
- **Membership is about files, not open tabs.** Closing a tab never changes an assignment. A group
  is *empty* only when no file is assigned to it.
- **"Hidden" is derived, not stored.** A group is hidden when it has members and none is open
  (`TabGroupVisibility.isHidden`). Reopening one member un-hides it; closing the last tab by hand
  hides it.
- **`rememberedTabUrls`** is the ordered list of a group's files that were open the last time any
  were. **Show Group** reopens that list.
- **The active group is session-only.** It is deliberately absent from `TabGroupState`.
- **Every state change replaces a whole map** and then calls `recomputeCache()`, so readers on
  other threads always see a consistent snapshot.
- **The service never publishes events or repaints.** Callers do both after mutating, because both
  need a live IDE. Look for this sequence after every mutation:

  ```kotlin
  service.assign(file.url, group)                 // 1. change state
  TabRepaint.request(project, file)               // 2. repaint affected tabs
  TabGroupNotifier.notifyChanged(project)         // 3. tell the tool window
  ```

## Trace: a tab gets its colour

1. The platform paints an editor tab and asks each `editorTabColorProvider` in order.
   `TabGroupColorProvider` is first (`order="first"` in `plugin.xml`).
2. `getEditorTabColor(project, file)` looks up `TabGroupService.groupFor(file.url)`, a single map
   read from `colorCache`. This runs on every tab paint, so it must stay that cheap.
3. `colorOf(group)` returns `JBColor(Color(lightRgb), Color(darkRgb))`, or `null` for an unassigned
   file. `JBColor` picks light or dark at paint time.
4. The platform blends the colour into the tab background. Unselected tabs show it at about half
   strength, which is why the presets are saturated (engineering notes §"Measured constraint").

The platform caches the result. Nothing is recomputed until something calls
`FileEditorManager.updateFilePresentation(file)`, which is all `TabRepaint.request` does.

## Trace: right-click a tab, create a group

1. The user right-clicks a tab. The platform shows `EditorTabPopupMenu`, which includes
   `AssignTabGroupAction` (**Tab Group…**) because `plugin.xml` added it there.
2. `AssignTabGroupAction.update` shows the item only when the data context has a project and a
   `VirtualFile`.
3. `actionPerformed` calls `TabGroupPopup.show(project, file, dataContext)`.
4. `RootStep.buildEntries` builds the popup's rows:
   - eight colour swatches from `TabGroupPaletteService.presets()` under a **New Group** caption;
   - **Add to *active group*** if a group is active and the file is unassigned;
   - **Add to Group…** (a submenu, `AddToGroupStep`);
   - **Manage Groups…** once any group exists (`ManageGroupsStep` → `GroupActionsStep`);
   - **Remove from Group** under an **In group: *name*** caption if the file is assigned.
5. Clicking a swatch sets `finalRunnable`; after the popup closes, `promptForNameAndCreate` asks for
   a name (pre-filled with the colour's label), then creates a `TabGroup` with a fresh UUID.
6. `assignTab` performs the standard sequence: `service.assign`, `TabGroupVisibility.rememberOpenTabs`,
   `TabRepaint.request`, `TabGroupNotifier.notifyChanged`.
7. If moving the file emptied its previous group, `service.assign` returns that group and
   `promptToDeleteEmptied` offers to delete it.

The other popup items follow the same shape. Their handlers are top-level functions in the second
half of `TabGroupPopup.kt`. The `internal` ones — `toggleActiveGroup`, `promptForRenameAndApply`,
`confirmAndDelete`, `removeFromGroups` — are also called by the tool window, so both surfaces behave
identically.

## Trace: open a file while a group is active

1. The user marks a group active (popup **Manage Groups… → *group* → Mark as Active Group**, or the
   pin button in the tool window). `toggleActiveGroup` calls
   `service.setActiveGroup`.
2. The user opens any file. The platform publishes `FileEditorManagerListener.fileOpened`.
3. `TabGroupFileListener.fileOpened` receives it, because `plugin.xml` registers it under
   `<projectListeners>`.
4. `autoJoinActiveGroup` returns early if there is no active group or the file is already assigned.
   Otherwise it runs the standard sequence.
5. Whether or not it joined, if the file is in a group, `TabGroupVisibility.rememberOpenTabs`
   records that group's currently open files as `rememberedTabUrls`.

There is no `fileClosed` handler here on purpose; the class's KDoc explains why recording on close
would lose the arrangement **Show Group** needs.

## Trace: rename, move or delete a file

1. The user renames a file, moves it, or deletes it. The VFS publishes a batch of events on
   `VirtualFileManager.VFS_CHANGES`.
2. `TabGroupVfsListener` receives them. It takes the `Project` in its constructor, which is how a
   project listener on an application topic knows which project it belongs to.
3. **Rename or move** (`after`): `urlChange` builds the old and new URL from the event's paths, and
   `service.remapUrl(old, new)` rewrites every assignment equal to the old URL or under it as a
   directory. The listener then repaints the affected tabs and notifies the tool window.
4. **Delete** (`before`, while the file still exists): `service.clearTree(url)` removes the
   assignments. If a group became empty, the delete-group prompt is deferred with `invokeLater`,
   because a dialog cannot open inside the write action that is deleting the file.

## Trace: hide and show a group

Hiding is *close and remember*: IntelliJ has no collapsible group chip in the tab strip (engineering notes
§"Constraints that are not going away").

1. The user double-clicks a group in the tool window, or presses the eye button
   (`ToggleVisibilityAction`). Both reach `toggleVisibility`.
2. **Hide** (`TabGroupVisibility.hide`): records the group's open files in order as
   `rememberedTabUrls`, then asks `TabStrip.close(files)`.
3. **Show** (`TabGroupVisibility.show`): takes `rememberedTabUrls` filtered to current members (or
   all members sorted, if nothing is remembered) and asks `TabStrip.open(files)`.
4. `TabStrip` puts the request on a channel. One coroutine drains the channel in order and hands
   each file to `PlatformTabOpen.openFile` or `PlatformTabClose.closeFile`, which run the platform
   call on the EDT.
5. After the last file, the callback publishes `TabGroupsChangedListener`, and the tool window
   redraws the group as hidden or shown.

Why the detour through a queue and two Java files: the platform records the call stack when it
builds toolbars for a new editor, and a plugin class on that stack keeps the plugin's class loader
alive after uninstall. `TabStrip`'s KDoc has the full analysis. If you only need to open a file in
new code, still go through `TabStrip`.

## Trace: the tool window stays in sync

`TabGroupsPanel` (in `TabGroupsToolWindow.kt`) subscribes to three events in its `init` block and
calls `refresh()` on each:

| Event                                   | Why                                                     |
|-----------------------------------------|---------------------------------------------------------|
| `TabGroupsChangedListener`              | A group or assignment changed anywhere                  |
| `FileEditorManagerListener.fileOpened` / `fileClosed` | Open files are drawn bold; hidden state changes |

`refresh()` rebuilds the whole tree from `TabGroupService`, restoring which groups were expanded and
which rows were selected. At a few dozen rows, rebuilding is simpler than diffing. The renderer
(`TabGroupsTreeRenderer`) only reads fields already computed on the node, so painting makes no
service or VFS calls.

The toolbar and context menu actions extend `SelectionAction`, receive `::selectedGroup` as a
function, and re-evaluate it in each `update`. A selection spanning two groups returns `null`, which
disables the group actions.

## Trace: edit the palette

1. **Settings | Tools | PrismTabs** creates `TabGroupPaletteConfigurable`.
2. `createPanel` builds one row per preset with the UI DSL, bound to the `edited` list.
3. On **OK** or **Apply**, `apply()` calls `TabGroupPaletteService.setPresets(edited)`, which stores
   only presets that differ from the built-in defaults.
4. New groups take their colours from `TabGroupPaletteService.presets()`. Existing groups keep the
   colours they were created with; `TabGroup` stores its own RGB values.

## Trace: install, update or uninstall without a restart

`PrismTabsDynamicPluginListener` listens on `DynamicPluginListener` and ignores events for other
plugins.

- **`pluginLoaded`:** repaint all open tabs so they pick up colours immediately.
- **`beforePluginUnload`:** in independent `try` blocks, save all state to disk, stop the colour
  provider and repaint, clear the platform's icon caches, and release the clipboard. Each step
  removes one path by which the platform could keep a PrismTabs object alive.

## Conventions you will see everywhere

- **Long KDoc on every class.** It records the reason for each non-obvious decision. Read it before
  changing the class; most "simplifications" are traps it describes.
- **`internal` over `private` where a test needs access,** with a note saying so.
- **Pure functions split out of UI code** for testing: `removeFromGroupText`,
  `deleteConfirmationMessage`, `currentGroupCaption`, `memberFileRows`.
- **Every group change ends with repaint and notify**, as in [The data model](#the-data-model).
- **No internal platform API.** `verifyPlugin` fails the build on one.
- **Wording follows `docs/naming-and-terminology.md`**: "Recolour…", "Remove from Group",
  "Hide Group", not "Collapse".

## Exercises

1. Set breakpoints in `TabGroupService.assign`, `TabRepaint.request` and
   `TabGroupsPanel.refresh`. In `runIde` under the debugger, create a group from a tab and watch the
   order in which they are hit.
2. Rename a grouped file in the sandbox IDE with a breakpoint in `TabGroupVfsListener.after`.
   Inspect the event and the old and new URLs.
3. Read `TabGroupVisibility.hide` and `show`, then predict what **Show Group** reopens after you
   hide a group, open one member by hand, and close it again. Check the prediction in `runIde`.
4. Find every caller of `TabGroupNotifier.notifyChanged`. For one of them, remove the call locally,
   run `runIde`, and describe what goes stale in the tool window. Revert the change.
5. Pick one `docs/engineering-notes.md` section referenced from a KDoc comment and read it in full alongside the
   class.
