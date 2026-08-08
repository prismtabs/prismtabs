# Cross-platform manual verification

CI (`.github/workflows/build.yml`) runs `./gradlew build` — compile plus the plain-JUnit `test`
suite — on Linux, macOS, and Windows runners. That suite is pure JVM/Swing business logic with no
native or OS-specific code paths, so a green matrix there is good evidence the plugin's *logic*
behaves identically everywhere.

It proves nothing about *rendering or interaction*, and that's not a gap worth closing in CI: the
`uiTest` suite drives a real `java.awt.Robot` against a real desktop session, and getting that
reliably headless took real, OS-specific effort even on Linux (see README "These tests need an X11
display" — Wayland silently drops all robot input). Reproducing that per-OS in CI (macOS needs
Screen Recording/Accessibility TCC permissions granted to the runner; Windows needs a real
interactive session, not a service account) is a disproportionate amount of infrastructure for what
a five-minute manual pass on real hardware answers directly. Use this checklist for that pass
instead — on real hardware or a VM, not CI.

**Setup:** use `./gradlew runIde`, **not** `runIdeForUiTests` — the latter's flags
(`-Dide.mac.file.chooser.native=false`, `-Dide.mac.message.dialogs.as.sheets=false`,
`-DjbScreenMenuBar.enabled=false`) exist specifically to disable native macOS behaviour that
`remote-robot` can't drive. For a manual pass you want exactly the native behaviour a real user
gets, so those must stay off.

## What's actually OS-sensitive here

The plugin has no hardcoded keyboard shortcuts, no file choosers, and no OS-conditional code paths
(checked: no `SystemInfo.isMac`/`isWindows` branching, no `JFileChooser`/`FileChooserFactory`
calls) — so most of the risk is platform *chrome*, not plugin logic:

- **Dialogs render as native sheets on macOS by default** (`New Tab Group`, `Rename Tab Group`).
  `runIdeForUiTests` turns this off for testability; a real Mac user sees it. Confirm the sheet
  looks right and the text field still receives focus and Enter still submits.
- **Font rendering / HiDPI.** Retina on Mac, and Windows' own DPI scaling, both differ from Linux.
  The palette's colour dots (`ColorSwatchIcon`) and the tool window's icon are the things most
  likely to look soft or misaligned if this bites.
- **Settings entry point.** macOS opens Preferences via ⌘, (Cmd+Comma) instead of a Settings menu
  item — confirm **Chromatabs** is still reachable there under **Tools**, not just via the menu.
- **Theme legibility**, independent of OS but worth re-checking per machine since font smoothing
  changes how a tint reads. README's Phase 0 result now has both themes measured on Linux
  (`IC-242.20224.300`) — this pass is about confirming the same holds on each OS's own font
  rendering, not establishing it for the first time.

## Checklist

Record IDE build, OS version, and theme (dark/light) tested. Repeat for macOS and Windows; Linux is
already covered continuously by local development.

1. **Swatch popup** — right-click a tab → **Tab Group…** opens; all 8 presets are visible and
   legible in both dark and light theme.
2. **Assign a preset** — click a preset dot → tab background tints; unselected tabs are still
   legible (the ~50%-strength blend documented in README "Measured constraint").
3. **New Group** — **New Group…** → pick a colour → the name dialog appears. **On macOS, confirm
   it renders as a sheet attached to the main window**, the text field has focus immediately, and
   both Enter and clicking OK submit it.
4. **Rename / Recolour / active group** — via **Manage Groups…**, rename a group and recolour it;
   confirm every member tab picks up both changes, and that opening a new file while a group is
   marked active colours it automatically.
5. **Remove from Group** — clears the tint back to the platform default.
6. **Tab Groups tool window** — opens from its stripe button; lists every group with member count;
   collapsing closes member tabs and marks the row "collapsed"; expanding reopens them in their
   original order. Confirm the stripe icon and swatch icons aren't blurry at this OS's default
   scaling.
7. **Settings | Tools | Chromatabs** — reachable both via the Settings/Preferences menu item and
   (macOS only) via ⌘,. Rename a preset, **Apply**, then **Restore Defaults** and confirm the
   preset label actually reverts.
8. **Restart persistence** — after steps 2–7, restart the sandbox IDE and confirm group
   assignments, names, colours, collapsed state, and any palette customisation all survived.

## Sign-off log

Append one entry per verification pass — mirrors the "Phase N result" entries in the main README.

```
Date:        YYYY-MM-DD
OS:          e.g. macOS 15.x (Apple Silicon) / Windows 11 24H2
IDE build:   e.g. IC-242.20224.300
Theme(s):    dark / light / both
Result:      PASS / FAIL — issues found (if any)
```
