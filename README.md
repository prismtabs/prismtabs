# PrismTabs

Colour-coded tab groups for IntelliJ editor tabs.

**Status: Phase 5 (hardening) in progress.** Features are complete through Phase 4: a **PrismTabs
tool window** (right-hand stripe) lists every group with its colour dot, member count and hidden
state, and can rename, recolour, delete, mark **active**, or **hide/show** a group. Hiding is "close and
remember" — IntelliJ has no collapsible in-strip chip and no API to build one, so hiding closes
the group's open tabs and records them, and showing reopens them in order; see
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
short list of what's still left before the actual upload. The
[cross-platform verification pass](docs/cross-platform-manual-verification.md) is no longer on that
list: Windows and macOS are both signed off against the current code, on 2026-08-19, each pass
covering the merged swatch-popup flow and the `forceSaveSettings` fix under it.

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

### Noisy but harmless log output from `buildPlugin` / `runIde`

`buildPlugin` runs a `buildSearchableOptions` subtask that boots a real headless sandbox IDE
(the 2024.2 baseline from `platformVersion`) and walks *every* Settings panel — including stock
JetBrains ones PrismTabs never touches — to index search terms. None of the following indicate a
problem with PrismTabs itself, and the plugin zip is still produced correctly:

- `Conflicting registry key definition for kotlin.mpp.tests.force.gradle` — duplicate
  registration inside the bundled Kotlin plugin. Upstream cosmetic bug.
- `Bundled shared index is not found` — an optional perf feature with no prebuilt stub for this
  IDE build. Informational only.
- `Service not supported: com.jetbrains.WindowMove` (with a long stack trace through
  `AppearanceConfigurable`) — headless Linux JBR doesn't implement native window-move; hit while
  rendering a stock Appearance settings panel during the search-index walk.
- `Dark color scheme is missing` — no theme plugins are loaded in the minimal headless sandbox.
- `Unknown field: CombinedDiff` / `Unknown field: BlockBorder` — theme JSON fields newer than
  this bundled IDE build recognises. Cosmetic.
- `JCEF is manually disabled in headless env` — deliberate; headless builds don't need an
  embedded browser.
- `SEVERE … GradleJvmSupportMatrix … Caused by: java.lang.IllegalArgumentException: 25` — the
  bundled Gradle plugin inside the sandbox IDE tries to parse the shell's default `java` (25.x,
  see "Build" in `CLAUDE.md`) while building the stock *Settings → Build Tools → Gradle*
  configurable, and its version parser predates JDK 25. Caught and non-fatal; doesn't affect
  compilation, which correctly uses the JDK 21 toolchain.

If this verbosity ever becomes actually confusing, the only lever is disabling
`buildSearchableOptions` — not worth doing just to quiet a benign sandbox log.

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

This is also a policy decision, now encoded in `plugin.xml`: an explicit PrismTabs group assignment
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
      reaches the tab background by the same route. PrismTabs also loads and runs on it unmodified.
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

**PrismTabs itself was run on 2026.2**, not just probed by javap: the built plugin
(`prismtabs-0.8.0-SNAPSHOT.zip`) was installed into a standalone 2026.2 sandbox alongside
`robot-server-plugin`, and `PluginManagerCore.getPlugin("io.prismtabs")` reported it loaded and
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
since been fixed upstream — nothing in PrismTabs needed to change for this.

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
Group also worked. No `io.prismtabs` errors or exceptions appeared in `idea.log`.

### The repaint decision

Phase 0 left one question open: how does Phase 1 call `FileEditorManagerImpl.queueUpdateFile`,
which sits on the impl class rather than the public `FileEditorManagerEx` interface? Reflection
was the plan's default fallback. Instead, `io.prismtabs.service.TabRepaint` uses a **compile-time
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

The real swatch popup (`io.prismtabs.ui.TabGroupPopup`) replaces Phase 1's hard-coded Red/Blue/
Clear actions. Right-clicking a tab now shows: a "New Group" caption over 8 Chrome-style preset
dots, a separator, "Add to Group…" (nested — lists existing groups, each with its own dot), and
"Remove from Group" (only when the tab is currently assigned). All of it is one
`AssignTabGroupAction` on `EditorTabPopupMenu`, since the tree is data-driven and can't be
expressed as a static `plugin.xml` `<group>`.

**Acceptance: passed**, verified by `remote-robot` UI tests (`./gradlew uiTest` — see
[UI testing](#ui-testing)) driving a real sandbox IDE end-to-end: a preset click prompts for a
name pre-filled with the colour's label, a typed name wins over that default, cancelling the
prompt creates nothing, and "Remove from Group" clears an assignment — all confirmed by reading
back `TabGroupService` state through the running plugin, not by guessing at Swing internals.
`./gradlew test` (the plain-JUnit suite, including `TabGroupServiceTest` updated for the
`lightRgb`/`darkRgb` split below) also passes.
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

`TabGroupPalette` (`io.prismtabs.model`) is the 8 Chrome hues — grey, blue, red, yellow, green,
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
`prismtabsPalette.xml`) holds only the presets that differ from the built-in default, keyed by
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

`TabGroupPopup`'s two preset lists (`RootStep`'s swatch row and "Recolour…") both
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

- **One creation path, not two.** The plan sketched a quick-assign swatch row *and* a separate
  nested "New Group…" step. That shipped first and was then merged: the swatch row is now itself
  the New Group control, labelled by a captioned `ListSeparator` above it, and the "New Group…"
  entry is gone. Two ways to make a group, differing only in whether you got to name it, was a
  choice with no principle behind it — and it left the popup's most prominent control (the dots)
  silently creating groups named after colours. Clicking a dot now prompts, pre-filled with that
  colour's own label, so accepting the default costs one keystroke rather than a decision.
  The trade is that repeat clicks on the same colour no longer converge on one shared group
  (`quickAssign`'s old `id = "preset-<colour>"`); each mints a fresh `UUID`. Putting a second file
  into an existing group is what "Add to Group…" is for, and that reading is now the only one.
- **The name prompt** (`Messages.showInputDialog`) runs from `RootStep`'s `getFinalRunnable()`, not
  inline in `onChosen()` — deferred until the popup has actually closed, so the modal dialog
  doesn't race the popup's close animation. Cancelling the prompt aborts the whole flow; no group
  is created and nothing is assigned.

The caption is a captioned `ListSeparator` on the first entry rather than the step's own title,
because the popup's title bar would label the *whole* tree — including "Add to Group…" and "Manage
Groups…", which create nothing. That it renders at all above the top row is a platform detail worth
recording: `GroupedItemsListRenderer.hasSeparator` suppresses a separator at index 0 **only when its
caption is blank** (`iload_2; ifne` → `StringUtil.isEmptyOrSpaces`, checked by `javap` per CLAUDE.md's
recipe), so a captioned one survives there while the bare `ListSeparator` above "Add to Group…"
would not.

It is also repainted. `SeparatorWithText` defaults its caption to `JBUI.CurrentTheme.Popup.
separatorTextColor()` — `111,115,122` in the sandbox's dark theme, a deliberately muted grey suited
to the incidental dividers it was built for ("Recent Files"), where the caption is a hint and the
items are the content. Here the caption is the *only* label the eight swatches get and it names what
clicking one does, so at that grey it reads as chrome and the eye skips it. `TabGroupPopup`'s
`CaptionedListPopup` overrides `PopupListElementRenderer.createSeparator()` to set
`UIUtil.getListForeground()` (`223,225,229`) instead — not an invented colour, but the exact call
`GroupedElementsRenderer.List` makes for every item's own text, so the two track each other across
themes. `GroupHeaderSeparator.paintComponent` does `g.setColor(getTextForeground())` immediately
before `drawString`, so that field is precisely what gets painted. This is why the popup is built by
constructing `ListPopupImpl` directly instead of calling `JBPopupFactory.createListPopup`: the
factory returns that same class, but typed as a `ListPopup` with no hook for its renderer.

The caption also gets more room beneath it than the platform's vgap leaves. Measured on the rendered
popup, the swatch row sat 3px below the caption's glyphs where a nested step's title gets 11px above
its first row — close enough to look accidental and far enough to read as crowding, one click apart
in the same plugin. `CAPTION_BOTTOM_GAP` (8 unscaled px, through `JBUI.Borders` so it survives
HiDPI) closes exactly that measured shortfall, bringing both to 11px.

The awkward part is that `GroupedElementsRenderer` builds **one** separator component and reuses it
for every row that has one, so widening it at creation would also have pushed "Add to Group…" off
its own bare divider. The border is therefore set per row in `getListCellRendererComponent`, keyed
on whether the separator currently carries a caption, with the platform's own border captured once
beforehand (compounding it every pass would stack insets indefinitely). Reading the caption there is
sound rather than lucky: `configureComponent` calls `setCaption` on *every* row unconditionally, and
`getCaptionAboveOf` yields `""` both for a row with no separator and for a bare `ListSeparator()` —
whose no-arg constructor delegates to `ListSeparator("")`, not null.

`TabGroupPopupUiTest`'s `the New Group caption is painted at the list's own foreground` and `only the
captioned separator gets extra room beneath it` lock both in. The first reads `textForeground` off
the live `GroupHeaderSeparator` rather than sampling screenshot pixels. That distinction was learned
the hard way: the caption is drawn through `UISettings.setupAntialiasing`, and LCD subpixel AA
fringes glyph edges enough that a pixel-sampling check reported an identical value before *and*
after the fix, while the component had genuinely gone from `111,115,122` to `223,225,229`.

The second asserts on *both* halves of the per-row rule — heading widened, every uncaptioned row
untouched — in one test, deliberately: check only the heading and the test still passes on the naive
implementation it exists to rule out. Both tests were confirmed to fail without their fix, not
merely to pass with it (the spacing one by pinning `CAPTION_BOTTOM_GAP` to 0, so it isolates the
gap rather than the caption's existence).

## Phase 3 result

`TabGroupService` gained group CRUD (`rename`, `recolor`, `delete`, `membersOf`) and an
`activeGroupId`/`activeGroup`/`setActiveGroup` trio. `io.prismtabs.listener.TabGroupFileListener`
(registered via `<projectListeners>`, no `<component>`) reads `activeGroup()` on `fileOpened` and
assigns any not-yet-grouped file to it. The swatch popup gained "Manage Groups…" (only shown once
at least one group exists): pick a group, then Rename…/Recolour…/Mark as Active Group (or Clear
Active Group)/Delete Group. Rename is `io.prismtabs.ui.RenameGroupDialog`, a small Kotlin UI DSL
v2 dialog (`panel { row("Name:") { cell(nameField) } }`) per the plan — the one place in the tree
that needs free text rather than a list choice. Recolour is another nested list step over the same
`effectivePresets()` the root swatch row uses, applying immediately and repainting every member via
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
in-popup — `ManageGroupsStep` and `AddToGroupStep` both suffix the active group's label with
"(active)" — and in the tool window, whose row renderer carries the same "active" suffix and whose
toolbar/context menu carry the `Active Group` toggle. Marking a group active from either surface
publishes a change so the other one repaints.

### Checked against the JetBrains UI Guidelines before writing the popup tree

Two concrete rules from `plugins.jetbrains.com/docs/intellij/punctuation.html` were checked against
every new menu entry, not just assumed:

- **Ellipsis only when the action needs further input.** "Rename…" and "Recolour…" both open a
  further step requiring a choice, so both get one; "Mark as Active Group", "Clear Active Group",
  "Active Group", and "Delete Group" complete immediately (a confirmation dialog doesn't count — IntelliJ's own
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

**4a — collapse = close-and-remember.** `io.prismtabs.service.TabGroupCollapse` is a small
IDE-side object next to `TabRepaint`, for the same reason: `FileEditorManager` access only makes
sense from a live IDE, and `TabGroupService` stays plain-JUnit testable. `collapse()` reads
`FileEditorManager.getOpenFiles()`, keeps only the files belonging to the target group, records
that list on the group via `TabGroupService.collapse`, then closes each one
(`FileEditorManager.closeFile`). `expand()` reads the recorded list back off the (pre-mutation)
group and reopens each via `openFile(file, focusEditor = false)`, in the same order. Unlike
`TabRepaint`'s `queueUpdateFile` finding from Phase 0, both `closeFile(VirtualFile)` and
`openFile(VirtualFile, boolean)` are declared on the public `FileEditorManager` interface itself
(verified via `javap` on 2024.2) — no impl-class cast needed here.

**4b — the "PrismTabs" tool window.** `io.prismtabs.ui.TabGroupsToolWindow` (a `ToolWindowFactory`
+ `DumbAware`, registered `anchor="right"`) is the plan's stated substitute for Chrome's in-strip
chip: a `JBList<TabGroup>` showing each group's swatch, name, member count and — new this phase — a
"hidden" (and "active") suffix, with a toolbar (and matching context menu) for Active Group,
Hide/Show Group, Rename…, Recolour…, and Delete Group. Rename, Recolour and Delete don't duplicate `TabGroupPopup`'s logic —
`promptForRenameAndApply`, `confirmAndDelete` and `RecolorStep` were widened from `private` to
`internal` and are invoked directly from here.

**Hide/Show, not Collapse/Expand — and why two icons ship with the plugin.** The action closes a
group's tabs and reopens them; nothing is left folded up in their place, so "collapse" described an
affordance the IDE cannot give us (see 4a) while "hide" describes what actually happens. The eye and
struck-through eye are the settled convention for that pair, and the platform has only half of it:
`AllIcons.Actions.Show` is an eye, but nothing in `AllIcons` is its negative — `Actions.ToggleVisibility`
is an eye badged with a table, `General.TbHidden` a toolbar outline, and `expui/general/hide.svg`
(unreachable from `AllIcons` anyway) a plain dash. Borrowing the platform's eye and hand-drawing only
its counterpart would also split the pair across themes, where the platform's eye changes shape between
the classic and new UI and ours would not, so both are drawn in `src/main/resources/icons` on the 16x16
grid in the platform's own icon greys (`#6C707E` / `#CED0D6`), loaded through `PrismTabsIcons`.
`PrismTabsIconsTest` asserts they stay on the classpath and that the dark variants remain their light
siblings with only the stroke colour swapped. Delete uses `AllIcons.Actions.GC` — the platform's
waste-basket, whatever its field name says — rather than `General.Remove`, a minus sign that means
"take out of this list", not "destroy".

### Keeping the tool window in sync: a small message-bus topic, not polling

The tool window has to reflect changes made from *outside* it — a group created via the swatch
popup while the tool window is already open, say. `TabGroupService` stays free of platform
dependencies (see its doc comment), so it doesn't publish anything itself; a new
`io.prismtabs.service.TabGroupsChangedListener` / `TabGroupNotifier` pair follows the same split
`TabRepaint` already established, and every mutating call site in `TabGroupPopup` and
`TabGroupCollapse` calls `TabGroupNotifier.notifyChanged(project)` right after mutating the service.
The tool window subscribes once, in its panel's `init`, via
`project.messageBus.connect(toolWindow.disposable)`.

**Acceptance: passed**, verified live in `runIdeForUiTests` (`IC-242.20224.300`) by a new
`remote-robot` UI test matching the plan's stated criterion verbatim: collapse a group and see its
tabs disappear from the strip while it stays listed (now marked collapsed) in the tool window;
expand it and see the tabs return in their recorded order. `./gradlew test` and all five
`./gradlew uiTest` tests pass, repeatably (run three times in a row with no failures).

### Selection: right-click acts on the row you clicked, and a delete doesn't empty the selection

Every action here — toolbar and context menu alike — is enabled by the list's Swing selection
(`list.selectedValue`), which makes two selection rules load-bearing rather than cosmetic.

**Right-click selects the row under the cursor** (`installRowSelectingPopupMenu`). Swing's
`BasicListUI` moves a list's selection for the *left* mouse button only, so
`PopupHandler.installPopupMenu` on its own shows a menu that still acts on whatever was selected
before — and, with nothing selected, one whose every item is greyed out. Selecting the clicked row
is what the platform does: right-clicking an unselected file in the Project view selects it first,
and — measured the same way, against a live sandbox — does *not* move keyboard focus, so neither
does this. The two selection-aware helpers the platform ships
(`PopupHandler.installSelectionListPopup`, `installFollowingSelectionTreePopup`) make the opposite
trade: they *suppress* the menu unless the click is already on the selection, which suits a
multi-selection list and would only make this one deader.

**Deleting the selected group falls the selection onto the row that took its place** (`refresh`),
rather than leaving the list with no selection at all — an empty selection disables the toolbar too,
which reads as a dead panel right after a delete. Only when something *was* selected: a list nobody
has touched must not select a row on its own. Group ids survive rename, recolour and collapse, so
this branch is only ever reached by an actual deletion, whether made here or from a tab's popup.

Both rules come from a bug reported on Linux on 2026-08-21: right-clicking a group straight after
deleting one came up greyed out, and a left-click "woke the panel up", which made it look like a
focus problem. Reproduced end-to-end before the fix — with nothing selected the menu read
`[Collapse Group=false, Rename…=false, Recolour…=false, Delete Group=false]`, and the list's
selection after the delete was empty. `TabGroupsToolWindowUiTest`'s second case now drives that
exact sequence and asserts both rules.

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
3. **The tool window narrows the tab strip while it's open.** With "PrismTabs" showing, editor
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

**A gap the matrix itself had, closed 2026-08-19:** the `verify-plugin` job was *failing* on every
push from at least 2026-08-08, and stayed unnoticed because the three-OS `build` matrix beside it
was green the whole time. One `OVERRIDE_ONLY_API_USAGES` problem caused it —
`PrismTabsLicense.showRegisterDialog` invoked `AnAction.actionPerformed` directly, which is
`@ApiStatus.OverrideOnly`. It now goes through `ActionUtil.invokeAction`, the platform's own entry
point on the 2024.2 floor, which also runs the action's `update` first instead of firing a possibly
disabled action, and retires a deprecated `AnActionEvent.createFromDataContext` call along the way.
`verifyPlugin` is green across all seven IDEs it resolves (242 → 262). Full write-up, including the
one deprecation warning that is unavoidable until the compile floor moves to 243, in
[cross-platform manual verification](docs/cross-platform-manual-verification.md)'s "verifyPlugin was
red" section.

`plugin.xml` has no `<component>` entries — only `<projectListeners>`, `<applicationConfigurable>`
and one action — which is what dynamic-plugin-safety requires structurally. That was true by
construction, not verified until now. Checked live in `runIdeForUiTests`
(`IC-242.20224.300`), two ways:

1. `DynamicPlugins.checkCanUnloadWithoutRestart(descriptor)` — the same static check behind the
   IDE's own *Analyze Plugin Dynamic Loading* internal action — returned `null`, meaning it found
   nothing structurally blocking a restart-free unload.
2. The stronger test: actually calling `DynamicPlugins.unloadPlugin` followed by
   `DynamicPlugins.loadPlugin` on the live descriptor, then re-exercising the plugin afterwards.
   `idea.log` shows a clean round trip — `Successfully unloaded plugin io.prismtabs (classloader
   unload checked=true)` followed by `Plugin io.prismtabs loaded without restart in 124 ms`, no
   exception attributed to `io.prismtabs` anywhere in the log for either the unload or the reload.
   Three tabs were then assigned colours through the ordinary plugin UI path and screenshotted,
   confirming the reloaded classes are the ones actually running the feature, not just that
   `isEnabled()` reports `true`.

One nuance worth recording honestly: the log for this run also has 9 `SEVERE` entries from
`#c.i.u.SlowOperations`, each reading "Plugin to blame: Robot server version: 0.11.23" — this is
the platform's assertion that a slow call happened on the EDT, and every one of them blames the
`remote-robot` harness driving `unloadPlugin`/`loadPlugin` synchronously via `invokeAndWait`, not
PrismTabs. `io.prismtabs` is never named as the blamed plugin anywhere in the log. A real user
triggering an unload through the IDE's own Plugins page goes through the asynchronous
`unloadPluginWithProgress` path this test bypassed for determinism, so this artefact is specific to
how the check was driven, not evidence of a problem in the plugin being checked.

## Marketplace publishing prep

**Decision:** PrismTabs will be sold under JetBrains Marketplace's **Freemium** model, priced as a
**Yearly Subscription without a Fallback License** at **$9/year**, with the standard **30-day
trial** of the paid features. This supersedes an earlier Perpetual License decision (see git
history) — reasoning below.

**Why not Perpetual:** the original reasoning was "the plugin has no backend/hosting cost and the
Plugin Verifier CI matrix (see [Compatibility policy](#compatibility-policy)) keeps compatibility
cost low, so there's no recurring cost to fund." That framing missed that Andrzej's own maintenance
time — tracking new IDE versions, triaging bug reports — is a recurring cost even without a hosting
bill. Perpetual decouples that recurring cost from recurring revenue: for a plugin this niche,
new-user acquisition tapers off long before the obligation to keep it compatible does, so revenue
trends toward zero while the maintenance burden doesn't.

**Why not Subscription with a Fallback License:** JetBrains' fallback scheme (modelled on their own
IDE licensing) freezes a lapsed subscriber onto the last version they were entitled to, forever,
rather than cutting them off. Rejected because `untilBuild` is deliberately left unset (see
[Compatibility policy](#compatibility-policy)) — a frozen fallback build doesn't naturally decay via
IDE incompatibility the way it would for a plugin with a hard version ceiling. Once feature velocity
slows, the frozen fallback and the live paid version would converge, undermining the subscription.
The Freemium free tier already answers the question a fallback licence exists for — "don't leave a
lapsed payer with nothing" — without that convergence risk: a lapsed subscriber drops to the free
tier (below), not a frozen paid snapshot.

**Free/paid split**, feature-based rather than quantity-based — capping the *number* of groups would
punish a mid-task user who needs one more, which is exactly the "too limited" outcome to avoid:

- **Free forever:** assigning tabs to the 8 preset colours, unlimited groups, create/rename/recolour
  (Phase 1–3 core), persistence across restarts, and rename/move remapping — the last one is a
  correctness feature (your assignments survive a rename), not a premium one, so it stays free.
- **Paid** (active subscription, or during the 30-day trial): the PrismTabs tool window,
  collapse/expand, active/sticky-group auto-join on newly opened files (Phase 4's tool window +
  Phase 3's inheritance rule), and custom palette editing (Phase 5's settings page). These are the
  workflow-multiplier features a daily user builds a habit around, not ones a casual tab-colourer
  misses.
- **Trial:** new installs get the full paid feature set for 30 days (JetBrains' "reverse trial"
  pattern), then silently downgrade to the free tier above if not subscribed — never a hard
  lockout.

Done so far, none of it published anywhere:

- **Vendor metadata.** `plugin.xml`'s `<vendor>` now carries a real email
  (`yendrrek@protonmail.com`) and URL (`https://prismtabs.com/`, the plugin's own public docs site)
  instead of the bare placeholder name — required for the vendor's site/email to show on the
  Marketplace plugin page. It originally pointed at the GitHub repo instead, but that repo is
  private (see [docs/release-readiness-gaps.md](docs/release-readiness-gaps.md)), and JetBrains'
  listing guidelines require every external link on a plugin page to be reachable from the
  internet — `prismtabs.com` is the reachable public replacement.
- **Plugin icon.** `pluginIcon.svg` / `pluginIcon_dark.svg`, 40×40, reusing the plugin's own
  established colour language rather than inventing a new one: three of the preset swatch colours
  (blue/red/green) from `TabGroupPalette`, light and dark pairs, in the same filled-circle-with-a
  -subtle-border style `ColorSwatchIcon` already uses for exactly the same legibility reason (pale
  presets need a border to stay visible on a light background).
- **Licence verification module**, `io.prismtabs.license.PrismTabsLicense` /
  `LicenseSignatureVerification` — a faithful Kotlin port of JetBrains' own reference
  implementation ([`marketplace-makemecoffee-plugin`'s
  `CheckLicense.java`](https://github.com/JetBrains/marketplace-makemecoffee-plugin)), not a
  reinvented version: `LicensingFacade.getConfirmationStamp(PRODUCT_CODE)` plus full
  JetBrains-root-certificate signature verification for both licence-key and licence-server-ticket
  stamps. `LicenseSignatureVerification` has no `LicensingFacade` dependency at all, so its
  malformed-input reject paths are covered by plain JUnit 5
  (`LicenseSignatureVerificationTest`) — same split as the plan's §7 testing policy, and
  `PrismTabsLicense.decide` (the freemium truth table, with the `PluginManager`/`LicensingFacade`
  reads lifted out as parameters) is unit-tested the same way in `PrismTabsLicenseTest`. All
  `[VERIFY]`-worthy platform calls it uses (`LicensingFacade`, `AnActionEvent.createFromDataContext`,
  `ModalityState.nonModal()`) were checked against the 2024.2 jars the same way as everything else
  in this codebase, not guessed.

- **Free/paid gating, wired at each paid feature.** The feature-based split changed the shape of
  this from what the plan originally described: it is no longer a single gate.
  `AssignTabGroupAction` (the [Phase 2 result](#phase-2-result) entry point) stays *ungated* — it is
  the free-tier action — and instead `PrismTabsLicense.paidFeaturesEnabled()` /
  `requirePaidFeature()` are called from four places: the PrismTabs tool window (`TabGroupsPanel`
  shows an upsell empty-state instead of the list), `toggleCollapse` (the one choke point all three
  collapse/expand entry points funnel through), `toggleActiveGroup` (which both the popup's "Mark
  as Active Group" item and the tool window's `Active Group` toggle call), and
  `TabGroupPaletteConfigurable`. A `false` there means "fall back to the free tier", never "block
  the plugin". Two consequences worth knowing:
  - **The locked side is covered by `PaywallUiTest`.** Every other test runs against an unlocked
    build and always will (no `<product-descriptor>` ⇒ the gate is open), so that class simulates an
    unlicensed paid build inside the running sandbox — setting the descriptor's `productCode` and
    installing a stamp-less `LicensingFacade`, both real platform state, no test-only seam in
    production code — and asserts what a free user actually gets. It covers both directions: that
    each paid feature is withheld, *and* that the free tier still works — the latter guards against
    over-gating, which every locked-side assertion in the class would otherwise pass right through.
    All but that free-tier case are script-driven rather than mouse-driven, so they run on Wayland
    too (see "These tests need an X11 display").
  - **Nothing is ever held hostage.** *Clearing* an active group stays free even though setting one
    is paid, and the locked tool window still offers a one-click "reopen the tabs of N collapsed
    groups" — otherwise a lapsed subscription would leave tabs closed behind the very window needed
    to reopen them. `TabGroupFileListener` re-checks per file open, so auto-join stops the moment a
    subscription lapses rather than at the next restart.
  - **The gates are inert until the plugin is actually for sale.** `paidFeaturesEnabled()` returns
    `true` outright when our own `PluginDescriptor.getProductCode()` is `null`, i.e. when
    `plugin.xml` carries no `<product-descriptor>`. Without that clause every locally built plugin —
    `runIde`, `uiTest` — would lock its own paid features, since `LicensingFacade` has no product to
    issue a stamp for and `isLicensed()` answers `false` for everyone. With it, the gates go live by
    themselves on the day the tag lands.
  - **That tag must be `<product-descriptor … optional="true"/>`, not a bare one.** `optional="true"`
    sets `IdeaPluginDescriptorImpl.isLicenseOptional` (verified in the 2024.2 bytecode), which is
    what permits the plugin to load and run unlicensed so these gates can do their free-tier
    fallback. A bare `<product-descriptor>` marks PrismTabs as fully paid: the platform disables the
    whole plugin for an unlicensed user the moment they dismiss the register dialog, and none of the
    gating above ever executes. Confirmed live under `runIde` on 2026-09-01 (the plugin dropped into
    `config/disabled_plugins.txt`).

**Deliberately not done yet, and why:** `plugin.xml` has **no `<product-descriptor>`** tag.
`<product-descriptor release-date="">` has to be the actual date this version ships, which doesn't
exist yet — writing today's date would just be a fabricated fact sitting in the repo. Adding the tag
is also the thing that would make the IDE start treating `runIde`/`uiTest` as running an unlicensed
paid plugin, which would block the plugin functionality that the completed
[cross-platform manual verification pass](docs/cross-platform-manual-verification.md) needed to
exercise — which is why the gating call sites above are written to key off the tag's presence rather
than being held back alongside it.

**What's left, roughly in order:**

1. Pick the real release date, bump `pluginVersion` off `-SNAPSHOT` (currently `1.2.1-SNAPSHOT`) to
   the first paid release's version, and add `<product-descriptor code="PPRISMTABS"
   release-date="YYYYMMDD" release-version="10" optional="true"/>` to `plugin.xml` — which is also
   what arms the already-wired gating call sites. **`optional="true"` is mandatory for the freemium
   model** (it sets `IdeaPluginDescriptorImpl.isLicenseOptional`, verified in the 2024.2 bytecode):
   without it the platform treats PrismTabs as fully paid and disables the entire plugin for an
   unlicensed user once they dismiss the register dialog, so the free tier never loads and
   `PrismTabsLicense.decide()`'s free-tier branch is dead code in production. Reproduced live under
   `runIde` on 2026-09-01. Re-run `runIde` once afterwards to see the free tier as an unsubscribed
   user would, since that's the first build where the gates do anything — and confirm the plugin
   **stays loaded** (upsell panel, not a missing plugin).
2. Create the JetBrains Marketplace vendor profile, set it to **trader** status, and submit banking
   details (Sales Info tab) — external, account-level, only Andrzej can do this.
3. Upload the plugin (first release, not an update), then in **Sales Info → Add to Marketplace**:
   confirm/register product code `PPRISMTABS`, choose **Freemium**, then the **Yearly Subscription
   without a Fallback License** scheme, set the price to $9/year, leave the trial at its 30-day
   default.
4. Write the actual listing content — tagline, full description, ≥1200×760 screenshots, tags. Not
   started; `plugin.xml`'s `<description>` is a reasonable first draft of the prose but the
   screenshots and tags still need doing.
5. Submit for review (JetBrains' stated turnaround is about two business days).

For a detailed breakdown of each gap and additional ones discovered during release readiness audit,
see [docs/release-readiness-gaps.md](docs/release-readiness-gaps.md) — each item is written to be
tackled standalone in its own session.

Not applicable here: the "inform your users in advance" notice
([`inform-your-users-in-advance.html`](https://plugins.jetbrains.com/docs/marketplace/inform-your-users-in-advance.html))
only applies when an *already-published free plugin* switches to paid. PrismTabs has never been
published, so there are no existing free users to warn.

## Empty groups

A group is **empty** when it has no assigned files. Closing tabs has nothing to do with it: group
membership is a file URL -> group id assignment persisted in `.idea/tabGroups.xml`, so a file stays
in its group with every editor closed and across restarts — and `collapse` deliberately closes
every one of a group's tabs while keeping its members. There is no `fileClosed` handling anywhere
in the plugin, by design.

Three removals can genuinely empty a group, and each one reports it so the UI can offer to delete
the group ([`promptToDeleteEmptied`](src/main/kotlin/io/prismtabs/ui/TabGroupPopup.kt)):

| Removal | Reported by |
| --- | --- |
| "Remove from Group" on the last member | `TabGroupService.clear` |
| Moving the last member into another group ("Add to Group…", or a preset dot creating a new one) | `TabGroupService.assign` |
| The last member being deleted on disk | `TabGroupService.clearTree`, via `TabGroupVfsListener.before` |

Chrome drops a tab group silently once its last tab is gone; this asks first, because a PrismTabs
group is persisted data — name, colour, tool-window row — rather than a strip decoration, and the
removal may have been a misclick. Declining leaves the empty group in place; the tool window shows
it as "0 files" with its own Delete Group action.

Deliberately **not** implemented: a sweep for groups that were already empty before this existed.
The prompt fires on the *transition* to zero members, so a `tabGroups.xml` carrying an empty group
from an earlier build keeps it. A dialog on project open would be intrusive for a case the tool
window already surfaces plainly.

Disk deletion is handled in `before`, not `after`, since that is the only side of the write action
where the file being deleted still yields a URL; the prompt itself is deferred out of the write
action with a non-modal `invokeLater`, because a modal cannot be shown from inside one.

All three triggers are exercised against a live IDE, not only through `TabGroupService`'s unit
tests: [`TabGroupPopupUiTest`](src/uiTest/kotlin/io/prismtabs/uitest/TabGroupPopupUiTest.kt) drives
the two popup paths, and
[`EmptyGroupOnDiskDeleteUiTest`](src/uiTest/kotlin/io/prismtabs/uitest/EmptyGroupOnDiskDeleteUiTest.kt)
the deletion one — deleting the file the way a user does, from the Project tool window, so the
platform's own delete confirmation is in play and the `nonModal` queuing above is demonstrated
rather than argued. That class also covers the batch case a single directory deletion produces.

## UI testing

`TabGroupPopup`, the PrismTabs tool window, the VFS deletion path (`EmptyGroupOnDiskDeleteUiTest`,
see "Empty groups" above) and the free/paid paywall (`PaywallUiTest`, see
"Marketplace publishing prep" above) are covered end-to-end by
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

A bare `./gradlew uiTest` needs no `--tests` curation on any machine: classes that shouldn't run
unattended or off their target platform self-gate with a JUnit `assumeTrue` and simply report
skipped rather than failing or being silently left out. `MacSettingsShortcutUiTest` gates on
`os.name` starting with `Mac`; `WindowsManualVerificationUiTest` gates on an explicit
`-Dprismtabs.manualVerification=true` that neither local dev nor `ui-tests.yml` ever passes (see its
own doc comment, and `docs/cross-platform-manual-verification.md` for how to run it deliberately).

**Locally this runs on Linux and Windows.** Linux needs the X11 workaround below. Windows needs
none: `runIdeForUiTests` boots the sandbox IDE and `remote-robot`'s mouse/keyboard automation works
directly against the real desktop, so the same two commands above are all it takes — no manual
click-through — though it does take over the mouse and keyboard for the run's duration. Verified
locally on Windows on 2026-08-24: 22 of 25 tests passed, the other 3 skipped as designed
(`MacSettingsShortcutUiTest` on non-Mac, and `WindowsManualVerificationUiTest`'s two cases without
`-Dprismtabs.manualVerification=true`). In CI it also runs on Windows, via a manual-trigger-only
workflow ([`.github/workflows/ui-tests.yml`](.github/workflows/ui-tests.yml), `workflow_dispatch`,
not run on every push — Windows runner minutes carry a 2x plan-minute multiplier) that boots
`runIdeForUiTests` and drives it exactly as described above. macOS has no CI path: `remote-robot`
needs Screen Recording/Accessibility TCC permission that cannot be granted non-interactively on
GitHub's hosted macOS images (SIP blocks it — see the workflow file for the specifics). The same
automated suite still runs there, just by hand on real hardware, since that TCC permission is a
normal one-time "Allow" click outside CI; see
[`docs/cross-platform-manual-verification.md`](docs/cross-platform-manual-verification.md) for both
that and the fully manual checklist pass.

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

**Most of `PaywallUiTest` is the one exception** — its script-driven cases were verified 2026-08-11
running under a live GNOME Wayland session (`$XDG_SESSION_TYPE=wayland`), all 4 tests of the day
passing; the class has since grown to 6. Their paywall-specific interactions go through `IdeaFrame`'s
`runJs`/`callJs` helpers (in-process method calls inside the sandbox IDE, same mechanism
`resetTabGroups`/`setGroupCollapsed` already used) rather than a popup or dialog click, so none of
*that* needs `java.awt.Robot`.

**The exception's own exception is the free-tier test**, `the free tier still colours tabs and
manages groups while the licence is lapsed`, which is mouse-driven and so needs X11 like everything
else. That is deliberate and load-bearing rather than an oversight: every gate lives in the UI layer
and never in `TabGroupService`, so a test that the free tier still *works* must click the real popup
(`createGroupViaPopup`, `clickPopupSubstepItem`, and the modal name prompt). Reflecting into the
service instead would route past the only layer a `requirePaidFeature` could be added to, and would
keep passing while an over-gating regression broke the product for every unsubscribed user.

One further caveat for the script-driven cases: assertions that read the tool window's contents go
through `toolWindowGroupRows()`, which opens the window with a real stripe-button `.click()` if it
isn't already showing — but each of those cases opens the window itself via the script-driven
`showTabGroupsToolWindow()` first, so that fallback branch is never actually reached; it stays a live
Robot dependency for whoever calls `toolWindowGroupRows()` without opening the window some other way
first. The other three uiTest classes click through real popups and dialogs throughout and still need
the workaround below.

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

Re-verified 2026-08-17 on the same Xvfb setup after the swatch popup's two creation paths were
merged into one (see [Two design choices…](#two-design-choices-not-spelled-out-in-the-plan)):
`TabGroupPopupUiTest`'s five cases (`clicking a preset dot prompts for a name…` 3.956s, `the name
prompt assigns the typed name…` 7.362s, `cancelling the name prompt creates nothing` 3.732s,
`Remove from Group…` 6.161s, the Phase 3 end-to-end test 21.263s) plus the Phase 4 collapse/expand
test (10.110s) all pass. Note that `saveScreenshot`'s `Robot.createScreenCapture` still cannot be
used from this desktop even with the IDE on `:99` — JDK 21 routes it to the xdg-desktop-portal, which
answers for the *Wayland* session rather than the X display the IDE is on, so it returns the wrong
screen. `RemoteRobotExtension`'s offscreen `Window.paint` capture is unaffected and is what to reach
for when a popup needs photographing on Linux.

Re-verified 2026-09-02 on the same Xvfb setup, after the tool window gained its `Active Group`
toggle (see [Phase 4 result](#phase-4-result)): the whole suite twice in a row, 26 tests, 23 run and
green, 3 skipped by their own `assumeTrue` gates (`MacSettingsShortcutUiTest` is macOS-only, the two
`WindowsManualVerificationUiTest` cases need `-Dprismtabs.manualVerification=true`). The new
`the tool window's Active Group toggle marks the right-clicked group, and clears it again` (6.959s)
is mouse-driven throughout — a real right-click on the list row, then a real click on the
`ActionMenuItem` — so it exercises exactly the path Wayland cannot.

### What it actually took to get working (seven real findings, not guesses)

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
   expecting `Class<T>`. Passing a dot-navigated `Packages.io.prismtabs...` reference failed
   overload resolution live ("Can't find method ...getService(object)"). `java.lang.Class.forName(name,
   true, classLoader)` — a real `Class` object, not a Rhino wrapper — is what actually works. That,
   in turn, needs the *right* classloader: `Class.forName(name)` alone resolves against the
   robot-server-plugin's own classloader, which can't see PrismTabs's classes — IntelliJ gives
   every plugin its own `PluginClassLoader`. `PluginManagerCore.getPlugin(PluginId.getId
   ("io.prismtabs")).getPluginClassLoader()` is what's actually needed.
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
7. **A second way to hit finding 5's exact failure mode: any modal at all wedges the one worker
   thread, not just `Robot.createScreenCapture`.** `EmptyGroupOnDiskDeleteUiTest.createFixtureFile`
   writes a file into the fixture project, which sits inside this repository's own working copy
   (`.idea/vcs.xml` maps it) — so Git integration raises its "Add File to Git" modal, blocking the
   EDT that robot-server dispatches on. `jstack` on the wedged sandbox showed the identical shape as
   finding 5's, just parked one level up the call stack (`VcsVFSListener.performAddingWithConfirmation`
   → `ConfirmationDialog.requestForConfirmation` → `Dialog.show`), and with the identical symptom:
   two real test failures presenting as a suite-wide `SocketTimeoutException` collapse in every class
   that happened to run afterwards. The earlier fix here was to find the dialog and click Cancel —
   the wrong shape, since by the time a script can find it the EDT is already blocked, so the click
   could only land in the gap before the modal loop started. The actual fix is the same shape as
   finding 5's: don't create the dialog in the first place. `ProjectLevelVcsManager.getInstance
   (project).getStandardConfirmation(VcsConfiguration.StandardConfirmation.ADD, vcs).setValue
   (VcsShowConfirmationOption.Value.DO_NOTHING_SILENTLY)`, set for the shared confirmation and every
   active VCS. **The general lesson: any code path that can raise a modal is a robot-server hazard,
   not just the ones already known about — a `jstack` of the wedged sandbox is what actually
   distinguishes "real failure" from "one test's dialog took the whole suite down with it".**

## Constraints that are not going away

Read these before designing anything. They are platform limits, not TODOs.

1. **Colour is keyed to a file, not a tab instance.** `getEditorTabColor` receives only
   `(Project, VirtualFile)`. The same file in two split windows gets the same colour.
2. **Tab text colour is not settable.** `getEditorTabForegroundColor` is experimental and returns a
   `ColorKey`; tab text colour follows file status. Design for background only.
3. **There is no in-strip group chip.** Chrome's collapsible coloured label spanning several tabs
   has no public API. Reproducing it means custom-painting `JBEditorTabs` — internal and unstable.
   The substitute is a "PrismTabs" tool window (Phase 4).
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
`src/main/resources/META-INF/plugin.xml` and the `io.prismtabs` package path. Note that
"Tab Organizer" is already taken on the JetBrains Marketplace.

## What Phase 1 deleted

`io.prismtabs.phase0` is gone — it was scaffolding for the Phase 0 gate above, nothing more.
Two things survived the deletion, carried into `io.prismtabs.provider.TabGroupColorProvider`,
because they are findings rather than scaffolding:

- `order="first"` on the `editorTabColorProvider` registration in `plugin.xml`;
- `DumbAware` on the provider class.

## What Phase 2 deleted

Phase 1's hard-coded Red/Blue/Clear actions (`AssignRedTabGroupAction`, `AssignBlueTabGroupAction`,
`ClearTabGroupAction`) are gone, replaced by the single `io.prismtabs.action.AssignTabGroupAction`
that opens `TabGroupPopup`. `TabGroup.colorRgb` is also gone — see
[the light/dark colour-model change](#the-lightdark-colour-model-change) for why it became
`lightRgb`/`darkRgb`, which is a breaking change to `.idea/tabGroups.xml`'s schema; any
Phase-1-era sandbox state won't load and must be re-assigned.

Drop `order="first"` or `DumbAware` from Phase 1 and the plugin fails quietly — no error, just
files that never get their colour.
