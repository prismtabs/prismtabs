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
    TabGroupPopup.kt         // BaseListPopupStep tree: swatches + New group… + Add to group… + Remove
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
  - 8 preset colours (Chrome's palette: grey, blue, red, yellow, green, pink, purple, cyan) — as
    `JBColor(light, dark)` pairs so both themes stay legible;
  - separator;
  - `Add to group…` → nested `PopupStep` listing existing groups (each with its colour dot);
  - `New group…` → nested step: pick colour, then inline-edit name;
  - `Remove from group` (only when `hasSubstep == false` and the file is assigned).
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
  the gates stay inert until `plugin.xml` gains a `<product-descriptor>`, which is deliberately
  deferred until the actual release date is known. The cross-platform manual verification pass is
  still outstanding.

---

## 5. Risk register

| Risk | Likelihood | Mitigation |
|---|---|---|
| Colours invisible under Islands / certain themes | Medium | Phase 0 gate; tool window + emoji-dot fallback |
| Repaint API renamed between 2024.2 and 2026.2 | Medium | **[VERIFY]** in Phase 0, check the Incompatible Changes list, guard with reflection only if forced |
| `getEditorTabColor` called so often it lags | Low | Pure map lookup, no locks on the read path |
| File URL keys break on rename | High (eventually) | Fixed: `TabGroupVfsListener` remaps on rename/move |
| JetBrains ships IJPL-59322 natively | Low near-term | The issue is 3+ years old and still open; watch it |

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
