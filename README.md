# Chromatabs

Chrome-style colour groups for IntelliJ editor tabs.

**Status: Phase 1 in progress.** Real persistence and colouring now exist: `TabGroupService`
holds group definitions and file assignments, persists them to `.idea/tabGroups.xml`, and
`TabGroupColorProvider` reads its cache on the EDT. Two hard-coded actions ("Assign Red" /
"Assign Blue" / "Clear Group") on the tab right-click menu exercise the whole path until the
Chrome-style swatch popup lands in Phase 2. See [Phase 1 result](#phase-1-result) for the repaint
decision this phase had to make, and [Phase 0 result](#phase-0-result-passed) for the groundwork
it builds on.

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

### Still to check

- [ ] **Light theme legibility.** Only dark (`expUI_dark`) has been verified. Tab *text* colour is
      not ours to control, so tints must work with whatever foreground the theme picks.
- [ ] **Newest platform.** The repaint findings above are from 2024.2 only. Re-run the probe on the
      newest supported IDE and confirm `queueUpdateFile` still exists with the same behaviour, then
      record it here.
- [ ] **Islands theme (2025.3+).** Known open bugs where selected-tab background colour has no
      effect (IJPL-237068). Untested here.

```
Repaint API on 2024.2:  FileEditorManagerImpl.queueUpdateFile(VirtualFile)  [impl class, not the EX interface]
Repaint API on latest:  ______________________________
Theme used for testing: expUI_dark (New UI, dark), IC-242.20224.300
```

---

## Phase 1 result

Model, persistence and a real colour provider now exist (`TabGroup`, `TabGroupState`,
`TabGroupService`, `TabGroupColorProvider`), plus three throwaway actions ("Assign Red" /
"Assign Blue" / "Clear Group") on the tab right-click menu to exercise the path end-to-end.
Assignments are keyed by `VirtualFile.url` and persist to `.idea/tabGroups.xml` via
`@Storage("tabGroups.xml")`, so a rename or move silently drops the assignment — accepted per the
plan, deferred to Phase 5's `VirtualFileListener` remap.

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
matrix (`pluginVerification { ides { recommended() } }`), and the verifier resolves ordinary
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

## What Phase 2 will delete

`io.chromatabs.action.AssignTabGroupAction` (the hard-coded Red/Blue/Clear actions) is Phase 1
scaffolding in the same sense — it exists only to exercise `TabGroupService` before the real
swatch popup exists, and goes away once `TabGroupPopup` replaces it.

Drop either one and the plugin fails quietly — no error, just files that never get their colour.
