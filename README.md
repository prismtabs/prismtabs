# Chromatabs

Chrome-style colour groups for IntelliJ editor tabs.

**Status: Phase 5 (hardening) in progress.** Features are complete through Phase 4: a **Tab Groups
tool window** (right-hand stripe) lists every group with its colour dot, member count and collapsed
state, and can rename, recolour, delete, or **collapse/expand** a group. Collapse is "close and
remember" — IntelliJ has no collapsible in-strip chip and no API to build one, so collapsing closes
the group's open tabs and records them, and expanding reopens them in order; see
[Phase 4 result](#phase-4-result). Everything is covered end-to-end by real UI tests
(`./gradlew uiTest`, see [UI testing](#ui-testing)) driving an actual sandbox IDE, not just
`./gradlew test`'s plain-JUnit coverage.
[Phase 3 result](#phase-3-result) has group management and the active-group inheritance rule;
[Phase 2 result](#phase-2-result) has the swatch popup's base structure and the light/dark
colour-model change; [Phase 1 result](#phase-1-result) has the repaint decision they build on;
[Phase 0 result](#phase-0-result-passed) is the groundwork under all of it.
The three items Phase 0 left open — light-theme legibility, the newest-platform repaint check, and
the Islands-theme bug — are now closed; see [Checked: all three open items](#checked-all-three-open-items-closed-2026-08-09).
[Phase 5 result](#phase-5-result) has what's left of hardening: dynamic-plugin-loading is now
verified live, alongside the Plugin Verifier CI matrix and palette settings that shipped earlier.
[Marketplace publishing prep](#marketplace-publishing-prep) has the paid-plugin groundwork laid so
far — vendor metadata, plugin icon, and a tested licence-verification module — and the deliberately
short list of what's still left before the actual upload, including a still-open manual
[cross-platform verification pass](docs/cross-platform-manual-verification.md) on macOS and Windows.

---

## Opening the project

1. IntelliJ IDEA → **Open** → select this folder. It imports as a Gradle project.
2. Set the Gradle JVM to **JDK 21** (Settings → Build Tools → Gradle → Gradle JVM).
   This is required by the 2024.2 platform baseline.
3. First sync downloads the IntelliJ Platform artifacts (a few hundred MB). Be patient.

If Gradle sync fails on the wrapper, run `gradle wrapper --gradle-version=9.3.0` once with a
locally installed Gradle, or let IntelliJ regenerate it.

## Running it

```bash
./gradlew test      # fast, no IDE needed
./gradlew runIde    # launches a sandbox IDE with the plugin installed
```

See [UI testing](#ui-testing) for `./gradlew uiTest` — the `remote-robot` suite that drives
`TabGroupPopup` end-to-end in a real sandbox IDE.

## Phase 0 result: PASSED

Verified 2026-08-07 on `IC-242.20224.300` (IntelliJ IDEA 2024.2), New UI, dark theme
(`expUI_dark`, `isNewUI=true`). Both a `Test`-named file and a `.md` file rendered their tints.

Three things had to be fixed to get there. None were platform limits; all three are easy to get
wrong silently, and all three carry forward to Phase 1.

### 1. `order="first"` on the EP registration is mandatory

`EditorTabPresentationUtil.getEditorTabBackgroundColor` iterates `editorTabColorProvider` in
registration order and **returns the first non-null colour**. The platform's own
`EditorTabColorProviderImpl` delegates to `FileColorManager.getFileColor()`, and IntelliJ ships
default File Colors scopes — notably **Tests** and **Non-Project Files**.

Without `order="first"`, every file matching one of those scopes gets the platform's colour and our
provider is *never invoked at all*. This presented as "the `.md` file tints but the test file does
not", which looks like a rule bug and is not one.

This is also a policy decision, now encoded in `plugin.xml`: an explicit Chromatabs group assignment
beats a File Colors scope match, because that is what Chrome does.

### 2. The provider must implement `DumbAware`

The EP is iterated via `lazyDumbAwareExtensions(EP_NAME, project)`, which filters every extension
through `DumbService.isUsableInCurrentContext`. `EditorTabColorProvider` extends `PossiblyDumbAware`,
whose default `isDumbAware()` is literally `this instanceof DumbAware`. A provider that does not
implement the marker is silently skipped during indexing. A pure map lookup is genuinely dumb-safe,
so the marker is honest — but it must be declared.

### 3. Repaint: `queueUpdateFile`, not `updateFileColor` — and no stable API exists

Verified by bytecode inspection on 2024.2. The two are **not** equivalent:

| Call | Path | Effect |
|---|---|---|
| `updateFileColor(file)` | → `EditorsSplitters.scheduleUpdateFileColor` | foreground / text attributes **only** |
| `queueUpdateFile(file)` | → `fileUpdateChannel` → `updateFileBackgroundColor` → `TabInfo.setTabColor` | **the tab background** |

Calling `updateFileColor` succeeds and does nothing visible, which is a convincing false negative.

**Open problem for Phase 1:** both methods are declared on `FileEditorManagerImpl`, *not* on the
`FileEditorManagerEx` interface. `FileEditorManagerEx` exposes only `refreshIcons()`. There is no
stable public API for forcing a tab-colour repaint, so Phase 1 must choose between reflection
(with a version-guard and a verifier check across the support matrix) or finding an indirect
trigger. Decide this deliberately — do not let it default to reflection by accident.

### Measured constraint: unselected tabs render tints at ~50% strength

`JBDefaultTabPainter.getCustomBackground` treats selected and unselected tabs differently:

- **selected** → the colour is used directly, at full strength;
- **unselected** → `ColorUtil.alphaBlending(EditorTabs.inactiveColoredFileBackground, colour)`.

In `expUI_dark` that value is `#1E1F2280` — **alpha 0x80**, i.e. a 50/50 blend toward the default
tab background. `JBEditorTabPainter` does not override this.

The practical consequence: **muted pastels are effectively invisible on unselected tabs.** During
Phase 0 the original pastel tints appeared completely absent for exactly this reason, and only
became obvious once replaced with saturated colours. The Phase 2 palette must be chosen against
this blend, not in isolation — a colour that looks right on the selected tab is not evidence.

### Checked: all three open items, closed 2026-08-09

All three were verified against a *running* IDE, not read off documentation. See
[Verification on 2026.2](#verification-on-20262) for the newest-platform and Islands work, and
[Palette](#palette) for the light-theme measurements.

- [x] **Light theme legibility.** Verified in `runIde` on `IC-242.20224.300` under the **Light**
      theme. Every one of the 8 presets is legible on both selected and unselected tabs, with a
      wide margin — full numbers in [Palette](#palette).
- [x] **Newest platform.** Re-verified on **IntelliJ IDEA 2026.2 (`IU-262.8665.258`)**, the newest
      release at the time of writing. `queueUpdateFile` still exists, is still `public`, and still
      reaches the tab background by the same route. Chromatabs also loads and runs on it unmodified.
- [x] **Islands theme (2025.3+).** **IJPL-237068 does not reproduce on 2026.2.** Islands has its own
      tab painter, and it honours a custom tab colour on selected tabs.

```
Repaint API on 2024.2:  FileEditorManagerImpl.queueUpdateFile(VirtualFile)  [impl class, not the EX interface]
Repaint API on 2026.2:  FileEditorManagerImpl.queueUpdateFile(VirtualFile)  [unchanged — same class, same signature]
Themes verified:        expUI_dark + Light (IC-242.20224.300)
                        Islands Dark + Islands Light (IU-262.8665.258)
```

### Verification on 2026.2

**Downloading 2026.2:** the IntelliJ Platform Gradle Plugin (2.18.1) no longer serves
`intellijIdeaCommunity()` for builds 253+ — Community stopped being republished as a separate
artifact from 2025.3 onward. `intellijIdea("2026.2")` (the unified type, product code `IU`) is what
resolves it; this doesn't change anything about `build.gradle.kts`, which already verifies against
`IntelliJPlatformType.IntellijIdea` for exactly this reason (see the comment above
`pluginVerification`).

**Repaint chain, confirmed unchanged by `javap` on 2026.2, not just "still compiles":**
`FileEditorManagerImpl.queueUpdateFile` is `public final`, still delegates to the same
`fileUpdateChannel` field, which still resolves to `EditorsSplitters.updateFileBackgroundColorAsync`
→ `updateFileBackgroundColor` → `TabInfo.setTabColor`. The colour it reads is still
`EditorTabPresentationUtil.getEditorTabBackgroundColor`'s first non-null result over
`EditorTabColorProvider.EP_NAME`, filtered through `lazyDumbAwareExtensions` — the exact chain
Phase 0 established on 2024.2, byte-for-byte the same shape three major versions later.

**Chromatabs itself was run on 2026.2**, not just probed by javap: the built plugin
(`chromatabs-0.8.0-SNAPSHOT.zip`) was installed into a standalone 2026.2 sandbox alongside
`robot-server-plugin`, and `PluginManagerCore.getPlugin("io.chromatabs")` reported it loaded and
`enabled=true` with zero `SEVERE` log entries for the whole session — no manifest incompatibility,
no missing class, no plugin-verifier surprise the build-time check didn't already predict.

**Islands, checked by two independent methods, both agreeing:** first, calling
`IslandsTabPainter.getEditorTabComposedBgColor` directly with `selected=true, active=true` returned
the requested colour at full strength, not the frame's default background — i.e. the method that
IJPL-237068 says drops the colour was, on this build, not dropping it. Second (belt and braces,
since a unit-level call can't rule out something further up the paint pipeline discarding the
result), three tabs were actually assigned colours through the real plugin UI path and the running
sandbox was screenshotted: on **Islands Dark**, the selected (green) tab measured `#607169` against
an empty-strip background of `#191A1C` — a clearly-visible tint, not the theme's plain
background. The two unselected tabs (blue, red) tinted too, at a slightly lower blend, matching the
selected/unselected split documented above for the classic painter. Islands Light showed the same
pattern the other direction (composed colour lighter than the plain background, distinctly not
equal to it). IJPL-237068 was filed against an earlier Islands build; whatever it described has
since been fixed upstream — nothing in Chromatabs needed to change for this.

---

## Phase 1 result

Model, persistence and a real colour provider now exist (`TabGroup`, `TabGroupState`,
`TabGroupService`, `TabGroupColorProvider`), plus three throwaway actions ("Assign Red" /
"Assign Blue" / "Clear Group") on the tab right-click menu to exercise the path end-to-end.
Assignments are keyed by `VirtualFile.url` and persist to `.idea/tabGroups.xml` via
`@Storage("tabGroups.xml")`, so a rename or move would silently drop the assignment — accepted per
the plan for Phase 1, then fixed ahead of the rest of Phase 5 by `TabGroupVfsListener`, a
`BulkFileListener` that remaps assignments (and collapsed-file records) on rename or move,
including every file nested under a renamed/moved directory. See its doc comment for why
`BulkFileListener` and not the (deprecated) `VirtualFileListener` the plan originally named.

**Acceptance: passed**, verified manually in `runIde` (`IC-242.20224.300`, `expUI_dark`) —
a tab assigned Red via the right-click menu was still red after a full IDE restart, and Clear
Group also worked. No `io.chromatabs` errors or exceptions appeared in `idea.log`.

### The repaint decision

Phase 0 left one question open: how does Phase 1 call `FileEditorManagerImpl.queueUpdateFile`,
which sits on the impl class rather than the public `FileEditorManagerEx` interface? Reflection
was the plan's default fallback. Instead, `io.chromatabs.service.TabRepaint` uses a **compile-time
cast**, not reflection:

```kotlin
(FileEditorManagerEx.getInstanceEx(project) as? FileEditorManagerImpl)?.queueUpdateFile(file)
```

Reasoning: `build.gradle.kts` already runs `pluginVerifier()` against the full supported IDE
matrix (`pluginVerification { ides { select { types = ... } } }`), and the verifier resolves ordinary
method calls — including calls onto non-interface impl classes — against every listed IDE. A
reflective call is invisible to that check: a renamed or removed method would only surface as a
silent runtime no-op on whichever IDE version broke it. A direct call is visible to the verifier
and fails the build the moment a target platform stops shipping the method, which is strictly
earlier and louder than reflection would ever catch it. The `as?` (rather than a hard cast) means
that on any IDE where `FileEditorManagerEx.getInstanceEx` returns something other than
`FileEditorManagerImpl` — headless test fixtures, or a future platform that slips past the
verifier matrix — the repaint request degrades to a silent no-op instead of a `ClassCastException`.

Verified by `javap` on 2024.2: both `queueUpdateFile(VirtualFile)` and `updateFileColor
(VirtualFile)` are `public` (not `internal`/package-private) on `FileEditorManagerImpl`, and
neither carries an `@ApiStatus.Internal`-style annotation, so this is a normal (if fragile)
impl-class dependency, not a hack around access control.

---

## Phase 2 result

The real swatch popup (`io.chromatabs.ui.TabGroupPopup`) replaces Phase 1's hard-coded Red/Blue/
Clear actions. Right-clicking a tab now shows: 8 Chrome-style preset dots, a separator, "Add to
Group…" (nested — lists existing groups, each with its own dot), "New Group…" (nested — pick a
colour, then name it), and "Remove from Group" (only when the tab is currently assigned). All of
it is one `AssignTabGroupAction` on `EditorTabPopupMenu`, since the tree is data-driven and can't
be expressed as a static `plugin.xml` `<group>`.

**Acceptance: passed**, verified by three `remote-robot` UI tests (`./gradlew uiTest` — see
[UI testing](#ui-testing)) driving a real sandbox IDE end-to-end: a preset click assigns the
shared colour group, "New Group…" prompts for a name and assigns the named group, and "Remove
from Group" clears an assignment — all confirmed by reading back `TabGroupService` state through
the running plugin, not by guessing at Swing internals. `./gradlew test` (the plain-JUnit suite,
including `TabGroupServiceTest` updated for the `lightRgb`/`darkRgb` split below) also passes.
Not covered by either suite: light-theme legibility of the palette, since verified separately — see
[Palette](#palette).

### The light/dark colour-model change

Phase 1's `TabGroup.colorRgb` was one flat `Int` — fine for two hard-coded scaffold colours, not
fine for a real 8-colour palette that has to stay legible in both themes. `TabGroup` now carries
`lightRgb`/`darkRgb` instead (persisted as both in `.idea/tabGroups.xml`).

The obvious alternative — store a `JBColor` directly — was rejected: `JBColor`'s theme detection
(`JBColor.isBright()`) reads a lazily-initialised static that expects a running IDE application,
and `TabGroupService` is deliberately plain-JUnit-tested with no platform bootstrap (see §7 of the
plan). Constructing or comparing a `JBColor` inside that test suite risks a headless failure the
moment the lazy is touched.

So the split is: `TabGroup` and `TabGroupService` stay `JBColor`-free and fully testable — the
cache (`TabGroupService.colorCache`) now holds the `TabGroup` itself rather than a resolved
`Color`. Only `TabGroupColorProvider.getEditorTabColor`, which the platform only ever calls from a
live IDE, builds `JBColor(Color(lightRgb), Color(darkRgb))`. This is also why theme switches
retint already-assigned tabs on the next repaint without any cache-invalidation code: `JBColor`
re-resolves against the current LAF every time something reads it, and nothing here holds onto a
resolved value across a repaint.

The same reasoning applies to `ColorSwatchIcon`: it is handed a `JBColor(light, dark)` and paints
whichever variant is active, safe for the same reason — icons only ever paint inside a live IDE.

### Palette

`TabGroupPalette` (`io.chromatabs.model`) is the 8 Chrome hues — grey, blue, red, yellow, green,
pink, purple, cyan — each a `light`/`dark` `java.awt.Color` pair. Both variants continue Phase 0's
measured constraint: chosen deep/saturated enough to still read after `EditorTabs`' ~50%-alpha
blend on unselected tabs, following the same brightness the Phase 1 scaffold proved out for red
and blue (dark variants literally reuse those two exact values).

**Both halves are now verified.** The dark half was confirmed live in Phase 0. The light half was
checked the same way, in `runIde` under the **Light** theme (`IC-242.20224.300`): each preset was
assigned to a real tab and the platform's own `JBEditorTabPainter.getCustomBackground` was called
directly (not re-implemented) to read back what actually paints. Light theme blends the unselected
alpha toward `#FFFFFF80` — pure white — rather than dark theme's near-black `#1E1F2280`, so a pastel
chosen for a white background risks the *opposite* failure Phase 0 found: not too muted against a
dark background, but washed out against a light one. Measured, worst case first:

| preset | selected (vs. white bg) | unselected (vs. white bg) | closest to another preset, unselected |
|---|---|---|---|
| grey | contrast 15.3:1 | contrast 17.9:1 | vs. purple, ΔE 43 |
| yellow | contrast 16.5:1 | contrast 18.5:1 | vs. red, ΔE 55 |
| blue | contrast 12.7:1 | contrast 16.5:1 | vs. cyan, ΔE 29 (closest pair overall) |
| cyan | contrast 14.7:1 | contrast 17.5:1 | vs. blue, ΔE 29 |
| purple | contrast 11.5:1 | contrast 15.8:1 | vs. grey, ΔE 43 |
| pink | contrast 11.5:1 | contrast 15.6:1 | vs. red, ΔE 29 |
| green | contrast 13.4:1 | contrast 16.8:1 | vs. cyan, ΔE 50 |
| red | contrast 11.5:1 | contrast 15.6:1 | vs. pink, ΔE 29 |

Every figure clears WCAG's 3:1 UI-component threshold by a wide margin — text contrast against
black tab-label text is 11.5:1 at the tightest, well past the 4.5:1 text threshold too. The closest
pair (blue/cyan, unselected) is still clearly distinguishable in the actual screenshot taken during
this pass. No palette values changed as a result — the light half already worked, it just hadn't
been checked against the running platform painter before.

### Editing the palette (Phase 5)

`TabGroupPalette.PRESETS` stays a fixed, plain-JUnit-testable constant — nothing above changes.
What became user-editable is a layer on top: `TabGroupPaletteService` (application-level `@Service`,
`chromatabsPalette.xml`) holds only the presets that differ from the built-in default, keyed by
preset id, and `presets()` lays them over `TabGroupPalette.PRESETS` to produce the effective 8. A
slot nobody has touched keeps tracking the default if that default ever changes, and setting a
preset back to its default value silently drops the override — there is no separate "is this
customised" flag to keep in sync.

It's application-level, not project-level: the palette is closer to an editor colour scheme (an IDE
preference) than to project data like the group assignments in `tabGroups.xml`. The settings page,
`TabGroupPaletteConfigurable` (`applicationConfigurable`, under Tools), is Kotlin UI DSL v2's
`BoundSearchableConfigurable`, one row per preset — an editable name field plus a `ColorPanel` each
for light and dark — bound via `Cell.bind`/`bindText` so `DialogPanel` handles per-field
modified/apply/reset itself. That machinery alone can't see the page's own "Restore Defaults" link
coming, though: the link rewrites the whole edit buffer at once and pushes it straight back into
already-in-sync components, which every field's own live-vs-bound comparison reads as unchanged.
`TabGroupPaletteConfigurable` keeps a separate `original`/`edited` baseline and overrides
`isModified`/`apply`/`reset` to account for that case — see the class's doc comment.

`TabGroupPopup`'s three preset lists (`RootStep`'s quick-assign row, "New Group…", "Recolour…") all
read `effectivePresets()` — a small file-scoped helper calling `TabGroupPaletteService.presets()` —
instead of `TabGroupPalette.PRESETS` directly, so a customised name/colour shows up immediately,
with no signature changes needed anywhere the palette used to be threaded through.

Verified live in `runIdeForUiTests`: `TabGroupPaletteConfigurableUiTest` drives the real
`createComponent()` → `reset()` → edit → `isModified()` → `apply()` → "Restore Defaults" → `apply()`
sequence through direct Swing/reflection calls inside the running IDE process, rather than mouse-
driven Settings-tree navigation and a native colour chooser — see `IdeaFrame.exercisePaletteConfigurable`'s
doc for why. `./gradlew test` covers `TabGroupPaletteService`'s override/diff/round-trip logic
separately, the same plain-JUnit split the rest of the service layer uses.

### Two design choices not spelled out in the plan

The plan describes the popup's shape but leaves two behaviours to fill in:

- **Clicking a top-level preset dot** doesn't prompt for a name — it assigns (creating on first
  use) a shared, colour-named group (`id = "preset-<colour>"`, e.g. "Blue"), so repeated quick
  clicks across files converge on one group per colour instead of spawning duplicates. This is the
  fast path; "New Group…" is the deliberate, named path.
- **"New Group…"'s name prompt** (`Messages.showInputDialog`) runs from the colour-pick step's
  `getFinalRunnable()`, not inline in `onChosen()` — deferred until the popup has actually closed,
  so the modal dialog doesn't race the popup's close animation. Cancelling the name prompt aborts
  the whole flow; no group is created.

## Phase 3 result

`TabGroupService` gained group CRUD (`rename`, `recolor`, `delete`, `membersOf`) and an
`activeGroupId`/`activeGroup`/`setActiveGroup` trio. `io.chromatabs.listener.TabGroupFileListener`
(registered via `<projectListeners>`, no `<component>`) reads `activeGroup()` on `fileOpened` and
assigns any not-yet-grouped file to it. The swatch popup gained "Manage Groups…" (only shown once
at least one group exists): pick a group, then Rename…/Recolour…/Mark as Active Group (or Clear
Active Group)/Delete Group. Rename is `io.chromatabs.ui.RenameGroupDialog`, a small Kotlin UI DSL
v2 dialog (`panel { row("Name:") { cell(nameField) } }`) per the plan — the one place in the tree
that needs free text rather than a list choice. Recolour reuses the nested-list-step pattern
already established by "New Group…", applying immediately and repainting every member via
`VirtualFileManager.findFileByUrl`.

**Acceptance: passed**, verified live in `runIdeForUiTests` (`IC-242.20224.300`) by a new
`remote-robot` UI test matching the plan's stated criterion verbatim: create a group in blue, mark
it active, open three files and see all three inherit the colour, rename the group, recolour it to
purple, and see every member (including the two files opened via inheritance, not the popup) follow
both changes. `./gradlew test` and all four `./gradlew uiTest` tests pass.

### Why "active group" isn't persisted

The plan's Phase 3 section asks for "one group can be marked active, and newly opened files
auto-join the active group until it's deactivated." `TabGroupService.activeGroupId` is deliberately
**not** part of `TabGroupState` and does not survive an IDE restart. An assignment is data the user
explicitly made; "active" is closer to a recording toggle — a session-scoped mode, not a fact about
a file. Silently resuming it after a restart the user has likely forgotten about would auto-colour
files they never asked to group, with no visible cause. The active group is instead surfaced
in-popup: `ManageGroupsStep` and `AddToGroupStep` both suffix the active group's label with
"(active)".

### Checked against the JetBrains UI Guidelines before writing the popup tree

Two concrete rules from `plugins.jetbrains.com/docs/intellij/punctuation.html` were checked against
every new menu entry, not just assumed:

- **Ellipsis only when the action needs further input.** "Rename…" and "Recolour…" both open a
  further step requiring a choice, so both get one; "Mark as Active Group", "Clear Active Group",
  and "Delete Group" complete immediately (a confirmation dialog doesn't count — IntelliJ's own
  file-delete confirmation follows the same rule), so none of the three do.
- **A destructive default action needs a concrete label, not a bare affirmative.** Delete Group's
  confirmation uses `MessageDialogBuilder.yesNo(...).yesText("Delete").noText("Cancel")` rather than
  `Messages.showYesNoDialog`'s generic "Yes"/"No", matching how the Project view's own delete
  confirmation reads.

### Two UI-testing findings, both real, neither predicted

1. **The tab's file name lives in `@accessiblename`, not `@text`.** Phase 2's single-tab fixture
   never needed to disambiguate tabs, so its xpath (`//div[@class='EditorTabLabel']`) matched
   whatever was there. The active-group test opens three tabs at once and needs to click a specific
   one; the first attempt guessed `@text='$path']` by analogy with `actionMenuItem`'s xpath and
   failed outright (`WaitForConditionTimeoutException` on every test, including the untouched
   Phase 2 ones — the guess broke the shared helper). Dumping the live tree
   (`curl http://127.0.0.1:8082`) showed the actual attribute is `accessiblename`.
2. **`remote-robot` drives a real `java.awt.Robot` at real screen coordinates on whatever has
   `DISPLAY`.** This machine's sandbox IDE renders on the live desktop (`:0`), not an isolated
   virtual display. A foreground terminal window sitting on top of the sandbox IDE silently
   swallowed every right-click — tests failed with the exact same timeout as finding #1, which
   looked identical from the stack trace alone. Diagnosed by pulling a screenshot from the
   robot-server itself (`GET /screenshot`, a JSON array of signed bytes — decode with
   `bytes((b & 0xFF) for b in data['bytes'])` — not a raw PNG response) rather than guessing from
   symptoms. **If `uiTest` fails with `WaitForConditionTimeoutException` right at the tab
   right-click and nothing about the popup tree changed, check window stacking before suspecting
   the code.**

## Phase 4 result

Two mechanisms, as the plan's Phase 4 section asks for, both built on the same collapsed state now
carried on `TabGroup` (`collapsed: Boolean`, `collapsedFileUrls: List<String>` — the second one new
this phase, persisted through `TabGroupState.GroupEntry` the same way everything else is).

**4a — collapse = close-and-remember.** `io.chromatabs.service.TabGroupCollapse` is a small
IDE-side object next to `TabRepaint`, for the same reason: `FileEditorManager` access only makes
sense from a live IDE, and `TabGroupService` stays plain-JUnit testable. `collapse()` reads
`FileEditorManager.getOpenFiles()`, keeps only the files belonging to the target group, records
that list on the group via `TabGroupService.collapse`, then closes each one
(`FileEditorManager.closeFile`). `expand()` reads the recorded list back off the (pre-mutation)
group and reopens each via `openFile(file, focusEditor = false)`, in the same order. Unlike
`TabRepaint`'s `queueUpdateFile` finding from Phase 0, both `closeFile(VirtualFile)` and
`openFile(VirtualFile, boolean)` are declared on the public `FileEditorManager` interface itself
(verified via `javap` on 2024.2) — no impl-class cast needed here.

**4b — the "Tab Groups" tool window.** `io.chromatabs.ui.TabGroupsToolWindow` (a `ToolWindowFactory`
+ `DumbAware`, registered `anchor="right"`) is the plan's stated substitute for Chrome's in-strip
chip: a `JBList<TabGroup>` showing each group's swatch, name, member count and — new this phase — a
"collapsed" suffix, with a toolbar (and matching context menu) for Collapse/Expand, Rename…,
Recolour…, and Delete Group. Rename, Recolour and Delete don't duplicate `TabGroupPopup`'s logic —
`promptForRenameAndApply`, `confirmAndDelete` and `RecolorStep` were widened from `private` to
`internal` and are invoked directly from here.

### Keeping the tool window in sync: a small message-bus topic, not polling

The tool window has to reflect changes made from *outside* it — a group created via the swatch
popup while the tool window is already open, say. `TabGroupService` stays free of platform
dependencies (see its doc comment), so it doesn't publish anything itself; a new
`io.chromatabs.service.TabGroupsChangedListener` / `TabGroupNotifier` pair follows the same split
`TabRepaint` already established, and every mutating call site in `TabGroupPopup` and
`TabGroupCollapse` calls `TabGroupNotifier.notifyChanged(project)` right after mutating the service.
The tool window subscribes once, in its panel's `init`, via
`project.messageBus.connect(toolWindow.disposable)`.

**Acceptance: passed**, verified live in `runIdeForUiTests` (`IC-242.20224.300`) by a new
`remote-robot` UI test matching the plan's stated criterion verbatim: collapse a group and see its
tabs disappear from the strip while it stays listed (now marked collapsed) in the tool window;
expand it and see the tabs return in their recorded order. `./gradlew test` and all five
`./gradlew uiTest` tests pass, repeatably (run three times in a row with no failures).

### Three more UI-testing findings, all real, none predicted

Phase 3 already found two (see above); Phase 4 found three more, all in the shared UI-test harness
rather than in `TabGroupsToolWindow` itself — the popup and its existing tests turned out to be
sitting closer to several real races than their prior green runs suggested:

1. **A `hasSubstep` row's child list opens on *selection*, not on click.** `ManageGroupsStep` →
   `GroupActionsStep` is `hasSubstep`; IntelliJ's `ListPopup` opens the child window as soon as the
   parent row is selected, so by the time a test's second `click()` lands, the child is usually
   already open — and the click **closes it again**. This was there since Phase 3 but only started
   failing tests once Phase 4's changes shifted timing enough to expose it. Confirmed by dumping
   every open `MyList`'s text around the interaction (see `Popup.kt`'s `clickPopupSubstepItem`
   doc): right after opening "Manage Groups…" the tree already read
   `[…] ;; [1]=DumpGroup ;; [2]=Rename…|Recolour…|…`, and clicking the `DumpGroup` row made `[2]`
   disappear. Fix: **hover** (`RemoteText.moveMouse()`) to select the row instead of clicking it,
   then click the child. `Popup.kt`'s `clickPopupSubstepItem` is the reusable version;
   `clickPopupItem` also gained a text-based wait (rather than "any `MyList` exists") for the same
   underlying race one level up.
2. **`FileEditorManagerListener.fileOpened` does not fire for a file that's already open.** A test
   that inherits a previous test's open tabs sees no active-group inheritance at all, because the
   listener the whole mechanism depends on never runs. `IdeaFrame.resetTabGroups()` (deletes every
   group and clears the active one, so runs don't accumulate duplicate-named groups either — the
   fixture project's `.idea/tabGroups.xml` isn't reset between separate `uiTest` invocations) now
   also closes every editor via `IdeaFrame.closeAllEditors()`, for exactly this reason.
3. **The tool window narrows the tab strip while it's open.** With "Tab Groups" showing, editor
   tabs can clip enough that a `rightClick()` at the tab's *stale* on-screen position misses
   entirely. The tool window test hides it again in a `finally`, and — belt and braces, since
   `remote-robot` clicks are real OS-level clicks that can race a still-settling layout regardless —
   `Popup.kt`'s `openTabGroupPopupOn` retries the whole right-click-then-find-menu gesture rather
   than failing on the first miss.

## Phase 5 result

Most of Phase 5's checklist ("Hardening and release" in the plan) shipped incrementally alongside
earlier phases rather than as one block at the end, and was written up where it landed: the palette
settings page and `TabGroupPaletteService` are in [Phase 2 result](#phase-2-result), the
rename/move remap (`TabGroupVfsListener`) ahead of schedule is in [Phase 1 result](#phase-1-result),
the Plugin Verifier matrix (2024.2 → whatever's current, via `IntelliJPlatformType.IntellijIdea`
in `build.gradle.kts`) runs as a dedicated `verify-plugin` job in CI, and the
[Constraints that are not going away](#constraints-that-are-not-going-away) section below is that
checklist item directly. This section closes the one piece that hadn't been checked anywhere yet:
**dynamic plugin loading.**

`plugin.xml` has no `<component>` entries — only `<projectListeners>`, `<applicationConfigurable>`
and one action — which is what dynamic-plugin-safety requires structurally. That was true by
construction, not verified until now. Checked live in `runIdeForUiTests`
(`IC-242.20224.300`), two ways:

1. `DynamicPlugins.checkCanUnloadWithoutRestart(descriptor)` — the same static check behind the
   IDE's own *Analyze Plugin Dynamic Loading* internal action — returned `null`, meaning it found
   nothing structurally blocking a restart-free unload.
2. The stronger test: actually calling `DynamicPlugins.unloadPlugin` followed by
   `DynamicPlugins.loadPlugin` on the live descriptor, then re-exercising the plugin afterwards.
   `idea.log` shows a clean round trip — `Successfully unloaded plugin io.chromatabs (classloader
   unload checked=true)` followed by `Plugin io.chromatabs loaded without restart in 124 ms`, no
   exception attributed to `io.chromatabs` anywhere in the log for either the unload or the reload.
   Three tabs were then assigned colours through the ordinary plugin UI path and screenshotted,
   confirming the reloaded classes are the ones actually running the feature, not just that
   `isEnabled()` reports `true`.

One nuance worth recording honestly: the log for this run also has 9 `SEVERE` entries from
`#c.i.u.SlowOperations`, each reading "Plugin to blame: Robot server version: 0.11.23" — this is
the platform's assertion that a slow call happened on the EDT, and every one of them blames the
`remote-robot` harness driving `unloadPlugin`/`loadPlugin` synchronously via `invokeAndWait`, not
Chromatabs. `io.chromatabs` is never named as the blamed plugin anywhere in the log. A real user
triggering an unload through the IDE's own Plugins page goes through the asynchronous
`unloadPluginWithProgress` path this test bypassed for determinism, so this artefact is specific to
how the check was driven, not evidence of a problem in the plugin being checked.

## Marketplace publishing prep

**Decision:** Chromatabs will be sold as a **Paid** plugin (no free tier) under a **Perpetual
License** — a one-time purchase, lifetime updates, rather than a subscription. Reasoning: the
plugin has no backend and no ongoing hosting cost, and the Plugin Verifier CI matrix (see
[Compatibility policy](#compatibility-policy)) already keeps the "stay compatible with new IDE
releases" cost low, so there's no recurring cost a subscription would need to fund. A subscription
(with or without fallback) makes more sense for products with continuous infrastructure spend or
heavy feature churn — neither applies here. Trial period: JetBrains' 30-day default, set later in
the Marketplace Sales Info tab, not in code.

Done so far, none of it published anywhere:

- **Vendor metadata.** `plugin.xml`'s `<vendor>` now carries a real email and URL
  (`yendrrek@protonmail.com`, the GitHub repo) instead of the bare placeholder name — required for
  the vendor's site/email to show on the Marketplace plugin page.
- **Plugin icon.** `pluginIcon.svg` / `pluginIcon_dark.svg`, 40×40, reusing the plugin's own
  established colour language rather than inventing a new one: three of the preset swatch colours
  (blue/red/green) from `TabGroupPalette`, light and dark pairs, in the same filled-circle-with-a
  -subtle-border style `ColorSwatchIcon` already uses for exactly the same legibility reason (pale
  presets need a border to stay visible on a light background).
- **Licence verification module**, `io.chromatabs.license.ChromatabsLicense` /
  `LicenseSignatureVerification` — a faithful Kotlin port of JetBrains' own reference
  implementation ([`marketplace-makemecoffee-plugin`'s
  `CheckLicense.java`](https://github.com/JetBrains/marketplace-makemecoffee-plugin)), not a
  reinvented version: `LicensingFacade.getConfirmationStamp(PRODUCT_CODE)` plus full
  JetBrains-root-certificate signature verification for both licence-key and licence-server-ticket
  stamps. `LicenseSignatureVerification` has no `LicensingFacade` dependency at all, so its
  malformed-input reject paths are covered by plain JUnit 5
  (`LicenseSignatureVerificationTest`) — same split as the plan's §7 testing policy. All
  `[VERIFY]`-worthy platform calls it uses (`LicensingFacade`, `AnActionEvent.createFromDataContext`,
  `ModalityState.nonModal()`) were checked against the 2024.2 jars the same way as everything else
  in this codebase, not guessed.

**Deliberately not done yet, and why:** the module above is written and tested but **not called
from anywhere**, and `plugin.xml` has **no `<product-descriptor>`** tag. Both are held back for the
same reason: `<product-descriptor release-date="">` has to be the actual date this version ships,
which doesn't exist yet — writing today's date would just be a fabricated fact sitting in the repo.
Adding the tag is also the thing that would make the IDE start treating `runIde`/`uiTest` as running
an unlicensed paid plugin, which would block exactly the plugin functionality the pending
[cross-platform manual verification pass](docs/cross-platform-manual-verification.md) needs to
exercise. Wiring `ChromatabsLicense` into `AssignTabGroupAction` (the single entry point identified
in [Phase 2 result](#phase-2-result), so the single gate) is one `if` before `TabGroupPopup.show(...)`
away once that tag exists.

**What's left, roughly in order:**

1. Finish the [cross-platform manual verification pass](docs/cross-platform-manual-verification.md)
   (macOS and Windows sign-off log is still empty).
2. Pick the real release date, bump `pluginVersion` off `-SNAPSHOT` (currently `0.8.2-SNAPSHOT`) to
   the first paid release's version, add `<product-descriptor code="PCHROMATABS"
   release-date="YYYYMMDD" release-version="10"/>` to `plugin.xml`, and wire the one `if` in
   `AssignTabGroupAction` described above.
3. Create the JetBrains Marketplace vendor profile, set it to **trader** status, and submit banking
   details (Sales Info tab) — external, account-level, only Andrzej can do this.
4. Upload the plugin (first release, not an update), then in **Sales Info → Add to Marketplace**:
   confirm/register product code `PCHROMATABS`, choose the **Perpetual License** scheme, set the
   price, leave the trial at its 30-day default unless there's a reason not to.
5. Write the actual listing content — tagline, full description, ≥1200×760 screenshots, tags. Not
   started; `plugin.xml`'s `<description>` is a reasonable first draft of the prose but the
   screenshots and tags still need doing.
6. Submit for review (JetBrains' stated turnaround is about two business days).

Not applicable here: the "inform your users in advance" notice
([`inform-your-users-in-advance.html`](https://plugins.jetbrains.com/docs/marketplace/inform-your-users-in-advance.html))
only applies when an *already-published free plugin* switches to paid. Chromatabs has never been
published, so there are no existing free users to warn.

## UI testing

`TabGroupPopup` and the Tab Groups tool window are covered end-to-end by
[`remote-robot`](https://github.com/JetBrains/intellij-ui-test-robot) tests (`src/uiTest/`), not
just eyeballed in `runIde`. They live in their own Gradle source set — slow, need a live IDE — kept
out of the fast `./gradlew test` path entirely.

```bash
./gradlew runIdeForUiTests &   # boots a sandbox IDE with a robot-server listening on :8082
./gradlew uiTest               # runs every uiTest class against it (needs the IDE already up)
```

`runIdeForUiTests` opens `src/uiTest/resources/fixture-project` — a throwaway one-file directory,
not this plugin's own project — on startup, so there is always exactly one editor tab to
right-click; the tests never have to disambiguate between tabs by filename.

**This automated suite runs on Linux only** — see "These tests need an X11 display" below. Neither
it nor `./gradlew build`'s plain-JUnit `test` suite (which CI does run on macOS and Windows) proves
the plugin renders or behaves correctly on those platforms; for that, see
[`docs/cross-platform-manual-verification.md`](docs/cross-platform-manual-verification.md).

### Why `remote-robot`, not IDE Starter + Driver

JetBrains' current official direction for IDE integration tests is Starter + Driver, and IntelliJ
Platform Gradle Plugin 2.x is built around it (`testIdeUi` maps to `TestFrameworkType.Starter`).
It was evaluated and rejected for this plugin specifically: its UI-component layer is documented
as experimental, and no example anywhere shows right-clicking an arbitrary component to open its
context menu — every documented pattern opens a popup *via* a named action (`invokeAction(...)`),
which `EditorTabPopupMenu` isn't. `remote-robot` is the older tool, but `ComponentFixture
.rightClick()` followed by finding the resulting menu is exactly its best-documented, most
battle-tested pattern. See the git history around this decision for the fuller comparison.

### These tests need an X11 display — they do not work under Wayland

`remote-robot` drives a real `java.awt.Robot`. Under a **GNOME Wayland session** the sandbox IDE runs
as an XWayland client and `java.awt.Robot` stops working in both directions, so the suite cannot pass
no matter what the tests say:

- **Input never arrives.** A `robot.rightClick(component)` returns cleanly and delivers *nothing*:
  measured with a global `AWTEventListener` inside the running IDE, a right-click produced **0**
  mouse events, while an event posted straight onto the queue produced 1. The IDE also never holds
  focus during a run — `KeyboardFocusManager.getFocusedWindow()` reads `NONE` throughout — and
  IntelliJ popups close without focus anyway.
- **Screen capture blocks forever.** `Robot.createScreenCapture` cannot read the compositor's
  framebuffer, so JDK 21 routes it to `sun.awt.screencast.ScreencastHelper` — the xdg-desktop-portal
  ScreenCast (PipeWire) API — which waits on a portal grant that never comes. See finding 5 below
  for why one such call used to kill an entire run.

Check with `echo $XDG_SESSION_TYPE`. If it says `wayland`, either log in to a **GNOME on Xorg**
session, or run the sandbox IDE on a dedicated X display (`Xvfb`) and point `runIdeForUiTests` at it:

```bash
sudo dnf install xorg-x11-server-Xvfb   # once; package name varies by distro
Xvfb :99 -screen 0 1920x1080x24 &
DISPLAY=:99 ./gradlew runIdeForUiTests &
./gradlew uiTest   # the client talks HTTP to :8082, not X — no DISPLAY needed here
```

A dedicated display is the better answer regardless: the tests then cannot be disturbed by, and
cannot disturb, whatever else is on the real desktop. Verified 2026-08-08 on this setup: all 5
pre-existing tests plus the new [`TabGroupPaletteConfigurableUiTest`](#editing-the-palette-phase-5)
pass (`clicking a preset dot…` 3.153s, `New Group…` 8.063s, `Remove from Group…` 6.274s, the Phase 3
end-to-end test 22.083s, the Phase 4 collapse/expand test 9.582s) — `KeyboardFocusManager` reports the
frame `active=true focused=true` throughout, and a `robot.rightClick` now measurably delivers 23 real
`MouseEvent`s instead of Wayland's 0.

### What it actually took to get working (six real findings, not guesses)

Every one of these was hit as a live test failure and fixed by re-running against the actual
sandbox IDE — none were predicted in advance:

1. **The client JVM needs `--add-opens java.base/java.lang=ALL-UNNAMED`.** `remote-robot`'s Gson
   response parsing reflects into `Throwable#detailMessage`; JDK 17+'s strong encapsulation blocks
   that silently otherwise (`InaccessibleObjectException`, wrapped twice by Gson and Retrofit into
   an opaque `IllegalArgumentException`). Set in the `uiTest` task's `jvmArgs`.
2. **The tab's concrete Swing class is `EditorTabLabel`, not `TabLabel`.** `TabLabel`
   (`com.intellij.ui.tabs.impl`) is the base class; the file editor subsystem's actual runtime tab
   component is `com.intellij.openapi.fileEditor.impl.EditorTabLabel`, and `remote-robot`'s xpath
   `@class` matches the concrete class, not a superclass. Found by dumping the live component
   hierarchy (`curl http://127.0.0.1:8082`), not by `javap` alone — `javap` only confirmed the
   superclass existed and was right-click-able, not which subclass actually renders.
3. **Rhino's `Packages.x.y.Z` doesn't reliably become a `java.lang.Class<T>` argument.** The popup
   scripts call into `TabGroupService` via `project.getService(...)`, an overloaded Java method
   expecting `Class<T>`. Passing a dot-navigated `Packages.io.chromatabs...` reference failed
   overload resolution live ("Can't find method ...getService(object)"). `java.lang.Class.forName(name,
   true, classLoader)` — a real `Class` object, not a Rhino wrapper — is what actually works. That,
   in turn, needs the *right* classloader: `Class.forName(name)` alone resolves against the
   robot-server-plugin's own classloader, which can't see Chromatabs's classes — IntelliJ gives
   every plugin its own `PluginClassLoader`. `PluginManagerCore.getPlugin(PluginId.getId
   ("io.chromatabs")).getPluginClassLoader()` is what's actually needed.
4. **`callJs` can't return a bare JS `null`.** `Fixture.callJs<T : Serializable>` requires the
   *result itself* to carry Serializable type info; a script whose last expression is `null` fails
   server-side ("Script must return 'Serializable' but 'null' has type 'null'") before it ever
   reaches the client's generic type parameter. `IdeaFrame.groupNameFor` returns `''` for
   "unassigned" and translates it to Kotlin `null` on the client side instead — safe because a real
   [`TabGroup`](#the-lightdark-colour-model-change) name is never blank
   (`InputValidator` in `TabGroupPopup.promptForNameAndCreate`).
5. **A blocking script kills the whole run, not just one test — robot-server has one worker thread.**
   The failure screenshot used to call `java.awt.Robot.createScreenCapture`. Under Wayland that
   blocks forever (see above), and `jstack` on the wedged sandbox IDE showed why that was fatal:
   robot-server runs the request *inline on Ktor's single Netty event-loop thread*
   (`eventLoopGroupProxy-4-1 @call-handler`, parked in `ScreencastHelper.getRGBPixelsImpl`). With
   that thread blocked the server answers nothing else, so every later test died at okhttp's 60s
   timeout inside `beforeTestExecution`'s `escape()` — one real failure presenting as a suite-wide
   collapse of `SocketTimeoutException`s. A client-side timeout would not have helped; the IDE-side
   thread stays wedged regardless. `RemoteRobotExtension` now *paints* the IDE's windows offscreen
   instead, which needs no `Robot`, cannot block, and photographs the IDE rather than the desktop.
6. **Rhino does not block-scope a `const` declared in a loop body — it binds it once.** Measured
   against this runtime by indexing one `Window[]` both ways in a single loop: inline indexing gave
   `SharedOwnerFrame, IdeFrameImpl, MyDialog, HeavyWeightWindow`, while `const w = arr[i]` gave
   `SharedOwnerFrame` **four times** — the first element, forever. (`var` is rebound correctly;
   declaring the same `const` name in two loops even fails outright with "redeclaration of const".)
   This silently broke `RemoteRobotExtension`'s de-iconify/raise block, whose `const f = frames[i]`
   was always Swing's hidden `SharedOwnerFrame`, so the `IdeFrameImpl` test never matched and the
   frame was never actually raised. **Use `var`, or index inline, inside these scripts' loops** —
   and note that the failure mode is a wrong value, not an error. `log` is also already defined in
   robot-server's scope; declaring it throws "redeclaration of var log".

## Constraints that are not going away

Read these before designing anything. They are platform limits, not TODOs.

1. **Colour is keyed to a file, not a tab instance.** `getEditorTabColor` receives only
   `(Project, VirtualFile)`. The same file in two split windows gets the same colour.
2. **Tab text colour is not settable.** `getEditorTabForegroundColor` is experimental and returns a
   `ColorKey`; tab text colour follows file status. Design for background only.
3. **There is no in-strip group chip.** Chrome's collapsible coloured label spanning several tabs
   has no public API. Reproducing it means custom-painting `JBEditorTabs` — internal and unstable.
   The substitute is a "Tab Groups" tool window (Phase 4).
4. **Theme risk.** Tab tinting is not equally visible under every theme, and **unselected tabs
   render tints at roughly half strength** — the platform blends them against
   `EditorTabs.inactiveColoredFileBackground`. Measured in Phase 0; see
   [that section](#measured-constraint-unselected-tabs-render-tints-at-50-strength).
5. **There is no stable API to repaint a tab after a colour change.** The only call that moves the
   tab background is `FileEditorManagerImpl.queueUpdateFile`, which is on the impl class, not the
   `FileEditorManagerEx` interface. See [Phase 0 finding 3](#3-repaint-queueupdatefile-not-updatefilecolor--and-no-stable-api-exists)
   and [the Phase 1 decision](#the-repaint-decision) on how it's called without reflection.

## Compatibility policy

Compile against the **oldest** supported platform and run forward. `sinceBuild = 242`,
`untilBuild` deliberately unset, correctness guarded by the Plugin Verifier rather than a
hard ceiling. Raising `platformVersion` in `gradle.properties` drops older IDEs — don't do it
casually.

## Renaming

The name lives in `gradle.properties` (`pluginName`, `pluginGroup`) plus the `<id>`/`<name>` in
`src/main/resources/META-INF/plugin.xml` and the `io.chromatabs` package path. Note that
"Tab Organizer" is already taken on the JetBrains Marketplace.

## What Phase 1 deleted

`io.chromatabs.phase0` is gone — it was scaffolding for the Phase 0 gate above, nothing more.
Two things survived the deletion, carried into `io.chromatabs.provider.TabGroupColorProvider`,
because they are findings rather than scaffolding:

- `order="first"` on the `editorTabColorProvider` registration in `plugin.xml`;
- `DumbAware` on the provider class.

## What Phase 2 deleted

Phase 1's hard-coded Red/Blue/Clear actions (`AssignRedTabGroupAction`, `AssignBlueTabGroupAction`,
`ClearTabGroupAction`) are gone, replaced by the single `io.chromatabs.action.AssignTabGroupAction`
that opens `TabGroupPopup`. `TabGroup.colorRgb` is also gone — see
[the light/dark colour-model change](#the-lightdark-colour-model-change) for why it became
`lightRgb`/`darkRgb`, which is a breaking change to `.idea/tabGroups.xml`'s schema; any
Phase-1-era sandbox state won't load and must be re-assigned.

Drop `order="first"` or `DumbAware` from Phase 1 and the plugin fails quietly — no error, just
files that never get their colour.
