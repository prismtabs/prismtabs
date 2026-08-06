# Chromatabs

Chrome-style colour groups for IntelliJ editor tabs.

**Status: Phase 0.** This build does nothing useful on purpose. Its only job is to answer
one question before any real code is written: *does a colour returned from
`EditorTabColorProvider` actually get painted on a tab in your IDE, your theme, your version?*

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

## Phase 0 acceptance checklist

In the sandbox IDE, open any project with a few files.

- [ ] **Tint renders.** Open a file with `Test` in its name and a `.md` file. Both tabs should be
      visibly tinted (red-ish and blue-ish respectively).
- [ ] **If nothing renders**, work through these in order and record which one fixes it:
    - `Settings | Appearance & Behavior | File Colors` → is *Use in editor tabs* enabled?
    - Switch theme away from **Islands** (2025.3+ has open bugs where selected-tab background
      colour has no effect — IJPL-237068).
    - Try a different IDE version.
- [ ] **Legibility.** Check the tint in both a light and a dark theme. Tab *text* colour is not
      ours to control, so the tints must work with whatever foreground the theme picks.
- [ ] **Repaint API identified.** Right-click any editor tab → *Chromatabs: Probe Tab Repaint API*.
      Read `idea.log`, find `[Chromatabs probe]`, and write the winning method into the notes below.
      Run this on the oldest and newest platform versions you intend to support and confirm the
      same method exists in both.

**Do not start Phase 1 until the first and last boxes are ticked.** If tinting cannot render in
your environment, the plugin's centre of gravity moves to the tool window and the plan changes.

### Notes (fill these in)

```
Repaint API on 2024.2:  ______________________________
Repaint API on latest:  ______________________________
Theme used for testing: ______________________________
```

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
4. **Theme risk.** Tab tinting is not equally visible under every theme. See the checklist above.

## Compatibility policy

Compile against the **oldest** supported platform and run forward. `sinceBuild = 242`,
`untilBuild` deliberately unset, correctness guarded by the Plugin Verifier rather than a
hard ceiling. Raising `platformVersion` in `gradle.properties` drops older IDEs — don't do it
casually.

## Renaming

The name lives in `gradle.properties` (`pluginName`, `pluginGroup`) plus the `<id>`/`<name>` in
`src/main/resources/META-INF/plugin.xml` and the `io.chromatabs` package path. Note that
"Tab Organizer" is already taken on the JetBrains Marketplace.

## What gets deleted in Phase 1

Everything in `io.chromatabs.phase0`. It is scaffolding for the checklist above, nothing more.
