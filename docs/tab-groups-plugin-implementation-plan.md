# Implementation Plan — Chrome-style Tab Groups for IntelliJ

A build plan for Claude Code. Kotlin, Gradle KTS, IntelliJ Platform Gradle Plugin 2.x.

**Read this section first if you are Claude Code:** this plan distinguishes between APIs that are
*confirmed to exist* and APIs whose exact signature must be *verified against the SDK sources on the
machine* before use. Items marked **[VERIFY]** must be checked in decompiled/attached platform sources
before writing code against them. Do not invent method signatures. If a **[VERIFY]** item turns out
not to exist as described, stop and report rather than substituting something plausible.

---

## 0. Decisions already made

| Decision | Value | Reason |
|---|---|---|
| Language | Kotlin | SDK-idiomatic; all modern platform samples and the official plugin template are Kotlin |
| Build | Gradle KTS + `org.jetbrains.intellij.platform` **2.18.1** | Current release of IPGP 2.x |
| Compile baseline | **IntelliJ IDEA 2024.2** (`sinceBuild = 242`) | See §0.1 |
| JDK | **21** | Required for platform 2024.2+; 2026.2+ requires Java 25 to *build against*, but Java 21 bytecode runs fine on the newer JBR |
| `untilBuild` | **unset (open-ended)** | Widest range; guarded by Plugin Verifier instead |
| Tests | **JUnit 5** | Spock is not viable here — see §7 |
| Scope | Full Chrome UX incl. rename / recolour / collapse | With the collapse caveat in §5 |

### 0.1 Why 2024.2 as the baseline, given "widest range possible"

Compile against the **oldest** platform you want to support and let it run forward — this is the
standard IntelliJ compatibility model. The realistic floor options:

- **2024.2 (build 242), JDK 21** ← recommended. New UI is default from here on, so what you see while
  developing matches what users get. Roughly two years of IDE releases covered.
- **2023.1 (build 231), JDK 17** — technically possible (IPGP 2.x is *recommended* from 2022.3), but
  costs you a JDK-17 toolchain, doubles the verification matrix, and covers IDEs a shrinking minority run.

Every API this plugin needs (`EditorTabColorProvider`, `JBPopupFactory`, `PersistentStateComponent`,
`EditorTabPopupMenu`, Kotlin UI DSL v2) predates 2023, so the floor is a policy choice, not a technical one.
Start at 242; dropping to 231 later is a one-line change plus a verifier run.

**Do not set `untilBuild`.** Use `pluginVerification { ides { ... } }` to check 2024.2, 2024.3, 2025.1,
2025.2, 2025.3, 2026.1, 2026.2 in CI.

---

## 1. What the platform actually gives you (confirmed)

| Capability | Mechanism | Status |
|---|---|---|
| Tint an editor tab's **background** | EP `com.intellij.editorTabColorProvider`, `EditorTabColorProvider.getEditorTabColor(Project, VirtualFile): Color?` | **Confirmed** — EP name and signature verified in platform sources |
| Add an action to the **tab right-click menu** | `<add-to-group group-id="EditorTabPopupMenu"/>` | **Confirmed** — group ID used by the platform's own `LangActions.xml` |
| Compact **swatch-dot popup** | `JBPopupFactory.getInstance().createListPopup(step)` with a `BaseListPopupStep` whose `getIconFor()` returns a filled-circle icon | **Confirmed API**, standard pattern |
| Nested "New group… / Add to group…" submenus | `ListPopupStep.onChosen()` returning a further `PopupStep` | Confirmed |
| Persistence | `@State` + `PersistentStateComponent` on a project-level `@Service` | Confirmed |

## 2. What the platform does NOT give you (hard constraints — design around these)

These are not pessimism, they are the actual boundaries. Encode them in the README so future-you
doesn't relitigate them.

1. **Colour is keyed to a *file*, not a *tab instance*.** The same file open in two split windows gets
   the same colour. `getEditorTabColor` receives only `(Project, VirtualFile)`. Chrome's "this tab in
   this window" model does not exist. Accept it.
2. **Tab text colour is effectively not yours.** `getEditorTabForegroundColor` exists but is
   `@ApiStatus.Experimental` and returns a `ColorKey`, not a `Color`; JetBrains' longstanding position
   is that tab text colour is determined by file status. **Design for background-only.** Pick swatch
   colours that keep both light and dark default text legible, or blend toward the theme background.
3. **There is no in-strip group-label chip.** Chrome's collapsible coloured chip spanning several tabs
   has no equivalent and no public API. Reproducing it means custom painting `JBEditorTabs` — internal,
   unstable, will break on IDE upgrades. **Out of scope.** See §5 for the substitute.
4. **Theme risk.** Tab background tinting is visible under the classic New UI, but 2025.3's *Islands*
   theme has open bugs where selected-tab background colour has no effect (IJPL-237068 and duplicates).
   Phase 0 exists specifically to find out whether your setup can show these colours at all.

---

## 3. Architecture

```
src/main/kotlin/<pkg>/
  model/
    TabGroup.kt              // id: String, name: String, colorRgb: Int, collapsed: Boolean, order: Int
    TabGroupState.kt         // @Tag-annotated persistable DTOs (plain classes, no Kotlin data-class
                             //    default-arg tricks — the XML serializer needs a no-arg constructor)
  service/
    TabGroupService.kt       // @Service(Service.Level.PROJECT), PersistentStateComponent<TabGroupState>
                             //   - groups: Map<groupId, TabGroup>
                             //   - assignment: Map<fileUrl, groupId>
                             //   - colorCache: Map<fileUrl, Color>   <-- read by the EP, must be O(1)
  provider/
    TabGroupColorProvider.kt // implements EditorTabColorProvider
  ui/
    ColorSwatchIcon.kt       // small filled-circle Icon, JBUI-scaled
    TabGroupPopup.kt         // BaseListPopupStep tree: New Group swatches + Add to group… + Remove
    TabGroupsToolWindow.kt   // Phase 4: the group list / collapse UI
  action/
    AssignTabGroupAction.kt  // the single entry point on EditorTabPopupMenu
  listener/
    TabGroupFileListener.kt  // FileEditorManagerListener — inheritance for newly opened files
src/main/resources/META-INF/plugin.xml
```

**Critical performance rule:** `getEditorTabColor` is called on the EDT during tab painting, often.
It must be a single map lookup against a pre-computed cache. No read actions, no VFS calls, no
`runReadAction`, no PSI, no I/O. Recompute the cache only on mutation.

---

## 4. Phased build

### Phase 0 — De-risk before writing features (do this first, do not skip)

Goal: prove that a hard-coded colour actually appears on a tab **in Andrzej's IDE, theme and IDE version**.

1. Scaffold from the JetBrains **IntelliJ Platform Plugin Template** (GitHub, Kotlin + Gradle KTS).
   The template's current baseline is IPGP 2.16.0 / IDEA 2025.2.x / Gradle 9.5 — bump IPGP to 2.18.1 and
   set the platform dependency to `intellijIdea("2024.2")`.
2. In `gradle.properties`, set `kotlin.stdlib.default.dependency=false` — the platform bundles Kotlin
   stdlib and bundling your own causes conflicts.
3. Implement a throwaway `EditorTabColorProvider` returning a hard-coded `JBColor` for **any** file whose
   name contains `"Test"`. Register it. Run `runIde`.
4. **Acceptance:** matching tabs are visibly tinted.
   - If nothing shows: check `Settings | Appearance & Behavior | File Colors | Use in editor tabs`, then
     try switching away from the Islands theme, then try a different IDE version. Report findings before
     proceeding — if colours can't render in the target environment, the whole approach needs rethinking
     and the emoji-dot fallback in §6 becomes the primary mechanism, not a fallback.
5. Also confirm here: **[VERIFY]** what call forces the tab strip to repaint after a colour change.
   Candidates to inspect in the SDK sources: `FileEditorManagerEx.getInstanceEx(project)` and its
   presentation-refresh methods, and `EditorTabPresentationUtil`. Find the one that exists in 2024.2 *and*
   is still present in 2026.2 (check the "Incompatible Changes" list). Write the answer into the README.

**Do not proceed to Phase 1 until Phase 0 passes.**

### Phase 1 — Model, persistence, single-file colouring

- `TabGroup`, `TabGroupState`, `TabGroupService` with `PersistentStateComponent`, stored in a
  project-level `@Storage("tabGroups.xml")` (i.e. `.idea/tabGroups.xml`, shareable via VCS if wanted).
- Key assignments by `VirtualFile.url`. Note the consequence: renames/moves break assignments. Either
  accept it, or add a `VirtualFileListener` later to remap. **Accept it in Phase 1**, log a TODO.
- Real `TabGroupColorProvider` reading the service's cache.
- A temporary hard-coded action "Assign red / Assign blue / Clear" on `EditorTabPopupMenu` to exercise it.
- **Acceptance:** assign a colour, restart the IDE, colour persists.

### Phase 2 — The Chrome swatch popup

- `ColorSwatchIcon`: a filled circle, `JBUI.scale`-aware, with a subtle border so pale swatches are
  visible on light themes.
- `TabGroupPopup`: `BaseListPopupStep<PopupEntry>` where `getIconFor()` returns the swatch. Top level:
  - a `New Group` caption (a captioned `ListSeparator` on the first entry) over 8 preset colours
    (Chrome's palette: grey, blue, red, yellow, green, pink, purple, cyan) — as `JBColor(light, dark)`
    pairs so both themes stay legible. Clicking one prompts for a name, pre-filled with the colour's
    label;
  - separator;
  - `Add to group…` → nested `PopupStep` listing existing groups (each with its colour dot);
  - `Remove from group` (only when the file is currently assigned).

  **Superseded:** this section originally had the swatch row assign immediately *and* a separate
  `New group…` → nested colour step → name prompt. Both shipped, then merged into the single path
  above — see the README's "Two design choices not spelled out in the plan". The list here is still
  the *Phase 2* tree; Phase 3 adds a fifth top-level entry, `Manage Groups…`, shown once at least
  one group exists.
- Wire it to a single `AssignTabGroupAction` on `EditorTabPopupMenu`.
- **Required:** override `getActionUpdateThread()` on every `AnAction` (return `BGT` unless you touch
  Swing). Missing this throws/warns on modern platforms.
- Get the `VirtualFile` from the action's `DataContext` via `CommonDataKeys.VIRTUAL_FILE` —
  on `EditorTabPopupMenu` this is the *clicked* tab's file, which is what you want.
- **Acceptance:** right-click tab → row of coloured dots → click → tab tints immediately (no restart).

### Phase 3 — Groups, inheritance, rename, recolour

- Group CRUD in the service; recolouring a group recomputes the cache for all members and repaints.
- **Inheritance:** `TabGroupFileListener implements FileEditorManagerListener`. On `fileOpened`, if the
  file is already assigned to a group, nothing to do (the provider handles it). The Chrome behaviour you
  actually want is: *a file opened from within a group's context joins that group.* IntelliJ has no such
  context. **Recommended concrete rule:** an explicit "sticky group" — one group can be marked *active*,
  and newly opened files auto-join the active group until it's deactivated. This is implementable,
  predictable, and closer to useful than guessing.
- Rename / recolour via small Kotlin UI DSL v2 dialogs (`panel { row { textField() } }`). **Do not use
  `com.intellij.ui.layout` (UI DSL v1) — it has been removed in the 2026.x platform.**
- **Acceptance:** create "Auth" group in blue, mark active, open three files, all three go blue; rename to
  "Security"; recolour to purple; all three follow.

### Phase 4 — Collapse (the compromised part)

Chrome collapse hides tabs behind a chip. IntelliJ can't. Two mechanisms, implement both:

**4a. Collapse = close-and-remember.**
`collapse(group)` → record each member's open state, then `FileEditorManagerEx.closeFile(file)`.
`expand(group)` → reopen in the recorded order via `openFile(file, focusEditor = false)`.
IntelliJ retains per-file editor state history, so caret/scroll positions generally survive; verify.
State goes in `TabGroupState` so collapse survives restart.

**4b. A "Tab Groups" tool window** — the substitute for Chrome's chip strip.
A `ToolWindowFactory` (registered `<toolWindow anchor="right" ...>`) showing a list: colour dot, group
name, member count, collapse/expand toggle, context menu for rename/recolour/delete. This is the stable,
public-API home for everything the tab strip can't show. It also makes the plugin useful when tab
tinting is invisible (§2.4).

- **Acceptance:** collapse a group → its tabs disappear from the strip, group still listed in the tool
  window; expand → tabs return in order.
- **Explicitly not in scope:** painting a coloured chip inside the tab strip.

### Phase 5 — Hardening and release

- ~~`<idea-plugin>` must be dynamic-plugin-safe: services + listeners only, **no** `<component>`
  entries. Run with `-Didea.is.internal=true` and use *Analyze Plugin Dynamic Loading*.~~ Done — no
  `<component>` entries exist, and `DynamicPlugins.checkCanUnloadWithoutRestart` plus a live
  unload/reload round trip both confirm it in `runIdeForUiTests`. See the README's
  [Phase 5 result](../README.md#phase-5-result).
- ~~Plugin Verifier across the 2024.2 → 2026.2 matrix in GitHub Actions.~~ Done — the `verify-plugin`
  job in `.github/workflows/build.yml` runs `./gradlew verifyPlugin`, which targets
  `IntelliJPlatformType.IntellijIdea` (open-ended, not capped at 2026.2) per `build.gradle.kts`.
- ~~Settings page (`Configurable`) for editing the preset palette.~~ Done — an application-level
  `applicationConfigurable` (parentId `tools`), `TabGroupPaletteConfigurable` (Kotlin UI DSL v2,
  `BoundSearchableConfigurable`), backed by `TabGroupPaletteService`. See `TabGroupPopup`'s
  `effectivePresets()` for how the popup now reads the customised palette instead of the hard-coded
  `TabGroupPalette.PRESETS` constant, and the README's Palette section for the design.
- ~~`VirtualFileListener` to remap assignments on rename/move (the Phase 1 TODO).~~ Done ahead of
  the rest of Phase 5, via `BulkFileListener` on `VFS_CHANGES` (not the deprecated
  `VirtualFileListener`) — see `TabGroupVfsListener` and `TabGroupService.remapUrl`.
- README documenting the four constraints from §2 — set expectations before anyone files an issue asking
  for the chip.
- **In progress:** JetBrains Marketplace paid-plugin publishing prep (Freemium model, Yearly
  Subscription without a Fallback License — see the README's [Marketplace publishing
  prep](../README.md#marketplace-publishing-prep)). Vendor metadata, plugin icon, a tested
  licence-verification module and its call sites (one per paid feature, not a single gate) are done;
  the gates stay inert until `plugin.xml` gains a `<product-descriptor code="PPRISMTABS"
  release-date="…" release-version="…" optional="true"/>`, which is deliberately deferred until the
  actual release date is known. The `optional="true"` is required, not optional: it sets
  `IdeaPluginDescriptorImpl.isLicenseOptional` (verified in the 2024.2 bytecode) and is what lets
  the plugin load unlicensed for the freemium free tier — a bare `<product-descriptor>` makes the
  platform disable the whole plugin for a non-subscriber. See the README's [Marketplace publishing
  prep](../README.md#marketplace-publishing-prep). The cross-platform manual verification pass is
  now done on macOS and Windows; see
  [cross-platform manual verification](cross-platform-manual-verification.md)'s sign-off log.

### Phase 6 — Group membership visibility

**The gap.** A group's members are *files*, but nothing in the UI ever lists them. `membersOf` is
called in exactly two places — to decide emptiness, and to render a count — so the only way to learn
what is in a group is to look at which tabs are currently tinted. Close a tab and its membership
becomes unknowable. It lands hardest on the free tier, which can create and fill groups but has no
tool window: a free user can colour a file, close it, and have no route back to where it went.

Two smaller gaps in the same family, fixed here because they are the same question asked from
somewhere else:

- The tool window row renders `membersOf(...).size` as `"N tabs"`, which contradicts the model —
  `TabGroupService`'s own doc is explicit that a group's emptiness "has nothing to do with how many
  of its files are open". It must read `N files`. Add the string to
  [naming and terminology](naming-and-terminology.md) §3's table so it stays in step.
- `RootStep` has an empty title and offers `Remove from Group` without ever naming the group the file
  is in. Right-clicking a tab should say which group it belongs to — a captioned separator or a step
  title, decided against a live sandbox.

**6a. The tool window becomes a tree** (paid, like the rest of that window). A container with
children is a tree you expand — Bookmarks, Changes, Favourites, Project view all do exactly this, so
it is the platform's own idiom for this question rather than an invention.

- `JBList<TabGroup>` → a two-level tree. Group rows keep today's shape (swatch, name, `N files`, and
  the `hidden` / `active` suffixes); each has one child per member file.
- Child rows: file-type icon, file name, then the project-relative *parent directory* in
  `GRAYED_ATTRIBUTES`. **Never the full path as the primary text** — see the rejected alternative
  below.
- Members whose tab is currently open must be distinguishable from those that are not, since the
  closed ones are the whole point of this phase. The distinction must not read as *disabled*; pick
  the attribute against the rendered tree, the way `CAPTION_BOTTOM_GAP` was measured rather than
  guessed.
- Double-click already toggles hide/show. It must stay bound to **group rows only**; on a file row
  double-click and Enter open the file.
- The five toolbar actions read `list.selectedValue` and are disabled when it is null. They must
  become "the selected group, or the selected file's parent group", or the whole toolbar goes dead
  whenever a child row is selected.
- `refresh()` replaces the model wholesale. A tree must preserve expansion state across every
  `TabGroupsChangedListener` notification, or expanding a group and then recolouring it collapses
  the thing you were looking at.
- Publish the tree's selection into the `DataContext` (`VIRTUAL_FILE`, `VIRTUAL_FILE_ARRAY`,
  `NAVIGATABLE`) so the platform's own F4 / Select in Project View work without being reimplemented.
- `installRowSelectingPopupMenu` is list-specific. Re-decide the tree's context-menu behaviour
  against a live sandbox for the same reason the list version was: the platform's tree helper
  suppresses the menu off-selection, which is the opposite trade from what this window needs.
- `TabGroupsToolWindowUiTest` asserts list behaviour throughout and has to move with this.

  **Delivered** as written, in `TabGroupsToolWindow.kt`. `JBList<TabGroup>` is now a
  `com.intellij.ui.treeStructure.Tree` over a `DefaultTreeModel` with an invisible root; `GroupNode`
  keeps the old row shape (swatch, name, `N files`, `hidden`/`active`) and each holds one
  `MemberNode` per member. Child rows are the popup's own rows — `appendMemberFile`, shared between
  `ColoredListCellRenderer` and `ColoredTreeCellRenderer` via their common `SimpleColoredComponent`
  — plus the open/closed distinction, which is **bold for a member whose tab is open** and full
  regular weight for one that is closed, checked against the rendered tree in a sandbox rather than
  picked off a palette. Double-click stays hide/show on a group row and opens a file row, with
  `toggleClickCount = 0` keeping it off the tree's own expand toggle; Enter expands a group row and
  opens a file row. The five actions take a `() -> TabGroup?` supplier resolving "the selected
  group, or the selected file's parent group". `refresh()` captures the expanded group ids and the
  selection (group id plus member URL) before the wholesale model swap and restores both after, and
  now also runs on `FileEditorManagerListener` open/close, since nothing else would correct a row
  left bold after its tab closed. `uiDataSnapshot` publishes the selected *file* row only. The
  context menu is the tree form of `installRowSelectingPopupMenu`, keeping the same trade for the
  same reason — `getPathForLocation` returning null off-row replaces the list version's manual
  bounds check. `TreeSpeedSearch.installOn(tree, canExpand = true, …)` searches into collapsed
  groups, which is where the file you are looking for is. See the README's "The same answer in the
  tool window, as a tree".

**6b. `Files…` in the tab popup** (free). `GroupActionsStep` gains it as the first item, with a bare
separator below it so reading is visually separated from mutating.

- It must **not** be a nested `PopupStep`. That would put the file names five popups deep from the
  right-click, each a separate window the mouse has to stay inside, and make the most frequently
  asked question the deepest thing in the plugin. Instead `getFinalRunnable` opens a *fresh
  top-level* chooser — exactly what the tool window's `RecolorAction` already does with
  `RecolorStep`. Visual depth stays at one popup, and it gets a real title bar (the group's name)
  and speed search.
- Same row shape as 6a's child rows: name plus greyed relative directory, truncated from the left.
- Sort explicitly by relative path, then name. `membersOf` returns map iteration order, which is
  arbitrary after a `loadState` — today nothing depends on it, and a file list would be the first
  thing to expose it as jumbled.
- A member whose URL no longer resolves must be shown as **missing**, not silently skipped, with the
  option to unassign it. This is real persisted state (see the new risk-register row) and this list
  is the first surface anywhere that makes it visible.

  **Delivered** as written, in `TabGroupPopup.kt`: `GroupActionsStep` leads with `Files…` (only when
  the group has members — an empty group's list would be a dead end), a bare separator under it, and
  `showGroupFiles` opening a `createPopupChooserBuilder` chooser titled `Files in <name>`. The row
  split, sort, left-elision and the missing-file prompt are pure functions
  (`memberFileRows`, `shortenedDirectory`, `missingFileMessage`), unit-tested in
  `MemberFileRowsTest`; the chooser itself is driven end-to-end in `TabGroupPopupUiTest`. Choosing a
  missing row asks "Remove from Group" / "Keep" and routes an emptied group back through
  `promptToDeleteEmptied`. See the README's "Group membership".

**6c. `Remove from Group` on a member row** (in the paid tool window, but a free *operation*).
6a and 6b made a group's membership visible; 6c makes the newly visible thing actionable from the
only surface that can see it. Unassigning already existed on the tab's own right-click menu, which
requires the file to be *open* — precisely the case the tree exists to cover, so a closed member had
to be opened and then closed again just to be dropped.

- The action is in the **context menu only**, not the toolbar: the toolbar has no row under the
  cursor, so a file-only button there would sit disabled for most of the tree's life.
- **Hidden**, not disabled, on a group row, against the platform's general preference for disabling.
  A group row has no one member to remove, and `selectedGroup`'s widening ("the selected group, or
  the selected file's parent group") deliberately does not apply — `selectedMember` answers only for
  a `MemberNode`. The menu describes the row that was right-clicked, and on a group row the other
  five items already do.
- Behind a **separator**, below `Delete Group`. Removing one file and destroying the group that
  file is in must not read as neighbours in one list; `General.Remove` (a minus sign — taking a row
  out of a list) against `Actions.GC` (the waste-basket) carries the same distinction in the icons.
- **No confirmation**, matching the tab popup: one click restores the assignment. Emptying the group
  is the case that asks, through the shared `promptToDeleteEmptied`.
- One removal path for both surfaces: `removeFromGroup` is keyed by **URL**, not `VirtualFile`, so a
  row naming a file that is gone from disk can be unassigned too (assignments are URL-keyed
  throughout); the repaint is skipped when nothing resolves, since a missing file has no tab.
- **Multi-select**, which is why the tree becomes `DISCONTIGUOUS_TREE_SELECTION`: the files worth
  dropping together are rarely adjacent, and gathering them by Ctrl-click is the gesture every
  platform tree offers for exactly this. Three consequences, each load-bearing:
  - The **group** actions must stay unambiguous, so `selectedGroup()` now answers only when every
    selected row resolves to *one* group — one group row, or any number of its files. A selection
    spanning two groups greys Rename, Recolour, Delete and both toggles rather than acting on
    whichever row happens to lead. Removal has no such restriction: "unassign these" is well
    defined however the selection is spread.
  - A right-click **inside** an existing selection must keep it whole (`installRowSelectingPopupMenu`
    now moves the selection only for a click outside it). Without that, opening the menu would
    discard the very rows its multi-file item acts on. This is the platform's own rule, and it is
    the half `installFollowingSelectionTreePopup` gets right while getting the greyed-menu case
    wrong.
  - `refresh()` must restore the **whole** selection, not the lead row: it runs on every editor
    open and close, and a gathered selection of six files collapsing to one because a tab opened
    elsewhere would be worse than no restoration at all.
- The label is a pure function, `removeFromGroupText(fileCount, groupCount)`, unit-tested in
  `RemoveFromGroupTextTest` the way `addToActiveGroupLabel` is: one file keeps the tab popup's exact
  words (same operation, other surface), several state the count as the platform's own multi-row
  actions do, and the *group* half is pluralised honestly — four files out of three groups is not
  "from Group".
- The batch goes through one new `TabGroupService.clearAll(urls)` rather than a loop over `clear`:
  one pass recomputes the colour cache once and reports the batch's emptied groups each named once,
  which is exactly what the single empty-group dialog needs. A loop would recompute per file and
  report a group only via whichever of its members happened to go last.

  **Delivered** in `TabGroupsToolWindow.kt` as `RemoveFromGroupAction`, with the toolbar and context
  menu now built from one shared list of action instances. Driven end-to-end in
  `TabGroupsToolWindowUiTest` — `a member row offers Remove from Group, and a group row does not`
  (every tab closed, so nothing in it could have gone through the tab popup), `Ctrl-clicked files
  are removed together, and the item states the count`, and `a selection spanning two groups still
  removes, greys the group actions, and asks once`.

  **On the free/paid line.** Removal is *not* gated, and must not be: unassigning is a by-hand
  assignment change, which the split above keeps free forever. It is reachable only by subscribers
  and trial users in practice, because the surface it lives on — the tool window — is paid; free
  users keep the same operation on a tab's own right-click menu. The distinction matters if the
  window ever grows a free mode: the action stays as it is, no `requirePaidFeature` around it.

**Why 6b is free and 6a is paid.** Membership visibility is a *correctness* feature — the same
argument the README already makes for rename/move remapping, which stays free because your
assignments surviving a rename is not a premium. A free tier that can fill groups whose contents are
unknowable is precisely the "too limited" outcome the feature-based split exists to avoid. The tool
window's paid value is unaffected: it is hide/show, active-group auto-join, and being always-on and
browsable — not the membership data itself.

One line to hold: opening a *single* member from the free popup stays free, but do **not** add "Open
All Files in Group" there. That is `expand` under another name, and expand is paid.

- **Acceptance: passed.** Assign three files to "Auth", close all three tabs, restart the IDE.
  Right-click any tab → `Tab Group…` → `Manage Groups…` → Auth → `Files…` lists all three by name
  with their directories; Enter on one opens it. Subscribed, the tool window lists Auth as
  `3 files`, expands to the same three, opens one on double-click, and still hides the group on
  double-clicking its own row. Verified live in `runIdeForUiTests` — `TabGroupPopupUiTest`'s three
  6b cases and `TabGroupsToolWindowUiTest`'s `a group row expands to its files, which stay listed
  with every tab closed` — with the whole suite green (31 tests, 28 run, 3 self-skipped) and
  `verifyPlugin` "Compatible" on all eight IDEs.

#### Rejected: a file list nested inside the popup tree

The obvious shape — `Tab Group…` → `Manage Groups…` → group → `Files` → the names — was rejected on
three counts. It nests five levels deep — each level a separate popup window the mouse has to stay
inside — which is well past anything the JetBrains guidelines' menu-nesting advice contemplates, and
buries the most frequently asked question deepest. A menu is also a list of verbs, so rows that do
nothing on click are a tooltip wearing a menu's clothes, and rows that *do* something are a feature
deserving its own popup. And full paths from the project root
as the primary text make the popup as wide as the deepest package while burying the one part the eye
is scanning for; every platform file list (Search Everywhere, Recent Files, Navigate | File,
Bookmarks) renders name-plus-greyed-directory instead.

#### Rejected: a `NamedScope` per group

Superficially the platform-native answer, and it is — to a *different* question. Scopes are how you
**scope work to** a group (Find in Files within it, inspections over it); they are not how you
**enumerate** one. Signatures below verified with `javap` per CLAUDE.md's recipe, not assumed.

1. **A `PackageSet` is a predicate, not a list.** `contains(PsiFile, NamedScopesHolder)` is asked
   about one file at a time, so answering "what is in Auth?" still means reading `membersOf`. The
   Project view filtered to a scope renders the members as a pruned directory tree — a browsing
   surface where the question wants an answer. Scopes are therefore additive work, not a substitute
   for 6a or 6b.
2. **It is not reachable from where the question is asked**, which is the tab strip. Scope selection
   lives in the Project view's filter and the Find in Files / Analyze dropdowns — global IDE
   furniture shared across features. Ten tab groups would put ten permanent entries in every one of
   those dropdowns, for a grouping that is frequently transient.
3. **Two collisions.** `PackageSet` is PSI-based and must implement `getText()`, a pattern string the
   platform serialises and round-trips; PrismTabs keys assignments by `VirtualFile.getUrl()` and is
   `DumbAware` throughout (Phase 1's finding: dropping `DumbAware` breaks colouring silently), so an
   arbitrary URL set fits that contract badly. Separately, `FileColorManager` exposes
   `isEnabledForTabs()` — scope-based file colours reach the tab strip too, so a scope per group
   risks a second colouring path for the same file, competing with `TabGroupColorProvider` and
   switchable in Settings behind the user's back.

Worth keeping as a *later, separate* feature ("search within this group"), with those collisions
recorded as what it would have to answer for. Not part of this phase.

#### [VERIFY] before writing Phase 6

- ~~Which data-provider interface 2024.2 wants for the tree (`UiDataProvider` vs the older
  `DataProvider`).~~ **Checked while writing 6a:** 2024.2 already has
  `UiDataProvider.uiDataSnapshot(DataSink)` and `DataSink`'s `set`/`setNull`/`lazy`, and — the part
  that settled it — `SimpleToolWindowPanel` itself implements `UiCompatibleDataProvider` (which
  extends both `UiDataProvider` and the older `DataProvider`) and already declares
  `uiDataSnapshot`. So the panel overrides that one method and calls `super`; no `getData(String)`
  anywhere, and nothing to guard for the floor.
- ~~The tree expansion-state helpers (`TreeState` / `TreeUtil`) that survive a wholesale model
  swap.~~ **Checked while writing 6a:** both exist on the floor — `TreeState.createOn(JTree)` /
  `applyTo(JTree)`, and `TreeUtil.collectExpandedPaths` / `restoreExpandedPaths` /
  `expandPaths` — but neither is used. `TreeState` identifies nodes through its own
  `calcId`/`calcType`, which is built for `NodeDescriptor`-based trees and falls back to `toString`
  otherwise, and `TreeUtil`'s pair round-trips `TreePath`s whose node objects the swap has just
  thrown away. Recording the expanded groups' **ids** and re-expanding by id after the rebuild is
  both exact and shorter than either, and it is the same key `refresh()` already uses to restore the
  selection.
- ~~`JBPopupFactory.createPopupChooserBuilder`'s renderer / speed-search / item-chosen methods as
  they exist in 2024.2.~~ **Checked while writing 6b:** `createPopupChooserBuilder(List)` returns an
  `IPopupChooserBuilder` carrying `setTitle` / `setRenderer(ListCellRenderer)` /
  `setNamerForFiltering(Function)` / `setItemChosenCallback(Consumer)` / `createPopup`, and
  `PopupFactoryImpl` builds it over a plain `JBList` — which is why the uiTest's chooser helpers
  cannot reuse the `ListPopupImpl$MyList` locators the menu-shaped popups need. A single click
  chooses (`PopupChooserBuilder`'s own `MouseAdapter` → `UIUtil.isActionClick` → `JBPopup.closeOk`),
  and the item-chosen callback runs from the popup's dispose, i.e. after it has closed — so opening
  a modal from it is safe. All read off the bytecode per CLAUDE.md's recipe.
- Confirmed already, no need to re-check: `CustomScopesProvider.CUSTOM_SCOPES_PROVIDER`
  (`ProjectExtensionPointName`, `getCustomScopes(): List<NamedScope>`), `PackageSet.contains` /
  `getText` / `createCopy` / `getNodePriority`, and `FileColorManager.isEnabledForTabs` /
  `getFileColor(VirtualFile)`. What is *not* verified is how a scope's file colour composes with
  `EditorTabColorProvider` at paint time, and in which order — that would need a live check, and
  only matters if the rejected scopes idea is ever revived.

---

### Phase 7 — Hidden as a derived state

**Amends Phase 4a.** Phase 4a stored `collapsed: Boolean` on `TabGroup`. Phase 6a's tree, which
reopens a closed member on a double-click, made that flag disagree with the tab strip in three ways
users hit immediately: a group that still read `· hidden` with one of its tabs open, a "Show Group"
that was a no-op clearing a word, and a group whose tabs were closed one at a time never reading as
hidden at all.

- **Drop the flag.** A group is hidden when it *has members and none of them is open*, derived from
  `FileEditorManager` by `TabGroupVisibility.isHidden`. Every surface reads that one predicate: the
  tool window's suffix, the Hide/Show toggle, the delete warning, the paywall escape hatch. The tree
  takes it from the member rows it has just built, so the group row cannot contradict the bold rows
  beneath it.
- **Keep the memory, maintain it continuously.** `rememberedTabUrls` (was `collapsedFileUrls`) is
  written by `TabGroupFileListener` on every editor open and close — never when the resulting set is
  empty, which is what leaves it holding the arrangement from just before the group went hidden,
  regardless of *how* it got there. `hide()` records after closing, not before, so that upkeep can't
  overwrite it mid-loop. `show()` filters the memory against current membership and falls back to
  every member file when nothing is remembered.
- **Empty groups are neither.** With no members there is nothing to close and nothing to reopen, so
  the Hide/Show toggle is disabled rather than left as a no-op that the stored flag used to let
  through — an empty group could sit there reading `0 files · hidden`.
- **Acceptance:** reopen one member of a hidden group from the tree → the row stops reading
  `hidden` and the toggle offers Hide; close its tabs one at a time → the row reads `hidden` again
  once the last one goes, and Show reopens exactly the set that was last open.
- **Rejected:** wording that reconciles the flag with reality (`group hidden, 1 tab visible`) — it
  narrates a state machine the user should not have to know exists. Also rejected: a per-file `S`/`H`
  letter or eye icon on member rows. Bold already carries "this one is open"; a letter reads as a
  file-type badge beside the actual file-type icon, and an eye promises a per-file toggle that does
  not exist.

---

## 5. Risk register

| Risk | Likelihood | Mitigation |
|---|---|---|
| Colours invisible under Islands / certain themes | Medium | Phase 0 gate; tool window + emoji-dot fallback |
| Repaint API renamed between 2024.2 and 2026.2 | Medium | **[VERIFY]** in Phase 0, check the Incompatible Changes list, guard with reflection only if forced |
| `getEditorTabColor` called so often it lags | Low | Pure map lookup, no locks on the read path |
| File URL keys break on rename | High (eventually) | Fixed: `TabGroupVfsListener` remaps on rename/move |
| JetBrains ships IJPL-59322 natively | Low near-term | The issue is 3+ years old and still open; watch it |
| Assignments for files deleted while the IDE was closed are never cleared | Medium | No VFS event ever fires, so `clearTree` never runs and the stale member keeps its group non-empty. Surfaced as *missing* by Phase 6's file list, with the option to unassign |

## 6. Fallback if tab tinting can't render

`EditorTabTitleProvider` (EP `com.intellij.editorTabTitleProvider`) lets you rewrite the tab's displayed
title. Prefixing with a colour **emoji** (🔴🟠🟡🟢🔵🟣🟤⚫) sidesteps the text-colour restriction entirely,
because emoji glyphs carry their own colour. Caveats: only ~8 usable colours, rendering varies by OS font
stack, and it eats horizontal tab space. **Untested — treat as a spike, not a promise.** Only pursue if
Phase 0 fails.

## 7. Testing — and why not Spock

IntelliJ's plugin test infrastructure is JUnit-based (`BasePlatformTestCase` on JUnit 3/4 semantics, or
the newer JUnit 5 `TestApplication` fixtures). Spock is a Groovy/JUnit-4-runner framework; running it
inside the platform test harness means fighting both the harness's lifecycle and an extra Groovy
toolchain, for no benefit. Recommendation:

- **`TabGroupService` logic, state serialization, cache recomputation** → plain JUnit 5, no platform
  harness. This is where the real logic lives and it should be pure enough to test without an IDE.
- **EP registration, action visibility, colour lookup end-to-end** → a handful of `BasePlatformTestCase`
  tests. Keep these few; they're slow.
- **The popup and tool window** → manual testing in `runIde`. UI-testing them is not worth the cost here.

---

## 8. Bootstrapping prompt for Claude Code

> Build an IntelliJ IDEA plugin in Kotlin called "Tab Groups" following the attached implementation plan.
> Start with Phase 0 only and stop for review before Phase 1.
>
> Scaffold from the JetBrains IntelliJ Platform Plugin Template structure: Gradle KTS,
> `org.jetbrains.intellij.platform` version 2.18.1, `intellijIdea("2024.2")`, JDK 21,
> `sinceBuild = "242"`, no `untilBuild`, `kotlin.stdlib.default.dependency=false`.
>
> Anything marked [VERIFY] in the plan: inspect the attached platform sources and report what you find
> before writing code against it. If a described API doesn't exist, stop and tell me — do not substitute
> something that looks similar.

Feed it the plan file plus §2 verbatim; the constraints are the part most likely to get "helpfully"
designed around.
