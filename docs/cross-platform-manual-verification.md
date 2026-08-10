# Cross-platform manual verification

CI (`.github/workflows/build.yml`) runs `./gradlew build` — compile plus the plain-JUnit `test`
suite — on Linux, macOS, and Windows runners. That suite is pure JVM/Swing business logic with no
native or OS-specific code paths, so a green matrix there is good evidence the plugin's *logic*
behaves identically everywhere.

It proves nothing about *rendering or interaction* on its own, and the `uiTest` suite (a real
`java.awt.Robot` driving a real desktop session) needed real, OS-specific effort to get reliable even
on Linux (see README "These tests need an X11 display" — Wayland silently drops all robot input).
Windows turned out solvable in CI: GitHub's hosted `windows-latest` runners provide a real
interactive session, so `.github/workflows/ui-tests.yml` runs the automated `uiTest` suite there —
manually triggered (`workflow_dispatch`), not on every push, since Windows runner minutes carry a 2x
plan-minute multiplier and this is meant for occasional use, not continuous coverage.

macOS is not solvable **unattended, in CI** the same way: `remote-robot` needs Screen
Recording/Accessibility TCC permission, and every current GitHub-hosted macOS image runs with SIP
enabled and no supported way to grant that non-interactively (direct `TCC.db` writes are SIP-blocked;
see the workflow file for the specific issues checked). That's a disproportionate amount of
infrastructure to chase for something a human sitting at real hardware solves in one click.

But "not in CI" isn't "not automated at all" — SIP only blocks the *scripted* route, not the normal
one. On a real Mac (owned, borrowed, or a pay-by-the-minute rental like MacinCloud/MacStadium — cheap
for a single short session, unlike CI's need for a persistent or repeatedly-provisioned machine),
granting Screen Recording/Accessibility is a one-time interactive "Allow" click the first time
`runIdeForUiTests` asks for it, exactly like a user installing any screen-recording app. After that,
the automated `uiTest` suite runs identically to Linux and Windows — see "macOS: automated
remote-robot suite on real hardware" below. Use the checklist in this doc for that pass, or for the
manual walkthrough (see "Setup" just below) that exercises chrome the automated suite deliberately
turns off. The **restart-persistence check (item 8) stays manual on every OS regardless** — it needs
a human (or a driven walkthrough, see the Windows section below) to actually kill and relaunch the IDE
process between steps, which nothing inside a single `uiTest` run can exercise.

**Setup for the manual click-through pass:** use `./gradlew runIde`, **not** `runIdeForUiTests` — the
latter's flags (`-Dide.mac.file.chooser.native=false`, `-Dide.mac.message.dialogs.as.sheets=false`,
`-DjbScreenMenuBar.enabled=false`) exist specifically to disable native macOS behaviour that
`remote-robot` can't drive. For a manual pass you want exactly the native behaviour a real user gets,
so those must stay off. (For the automated suite, do the opposite — see below.)

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
  item — confirm **PrismTabs** is still reachable there under **Tools**, not just via the menu.
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
7. **Settings | Tools | PrismTabs** — reachable both via the Settings/Preferences menu item and
   (macOS only) via ⌘,. Rename a preset, **Apply**, then **Restore Defaults** and confirm the
   preset label actually reverts.
8. **Restart persistence** — after steps 2–7, restart the sandbox IDE and confirm group
   assignments, names, colours, collapsed state, and any palette customisation all survived.

## macOS: automated remote-robot suite on real hardware

Unlike Windows, there's no CI workflow for this — it has to be run by hand on an actual Mac, once per
release or when debugging a macOS-specific rendering issue. That's still far less manual effort than
the full checklist above: it's the same asserted suite CI runs on Windows, not eyeballing.

```bash
./gradlew runIdeForUiTests &   # first run: macOS prompts for Screen Recording permission — click
                                # Allow (System Settings → Privacy & Security → Screen Recording);
                                # this and all future runs need no further prompts
./gradlew uiTest \
  --tests "io.prismtabs.uitest.TabGroupPaletteConfigurableUiTest" \
  --tests "io.prismtabs.uitest.TabGroupPopupUiTest" \
  --tests "io.prismtabs.uitest.TabGroupsToolWindowUiTest"
```

Same `--tests` list as `ui-tests.yml`, for the same reason: `WindowsManualVerificationUiTest` (see
below) needs a human-driven IDE restart between its two methods and isn't written to run as a single
`uiTest` invocation. It's named for Windows because that's the only OS it's been run against so far,
but nothing in it is Windows-specific — porting it to drive this same checklist on macOS, restart step
included, is a reasonable next step rather than something blocked by platform.

If the Allow prompt never appears (some macOS versions only prompt the *first* time a process asks,
and a previously-denied grant has to be reset manually): `tccutil reset ScreenCapture` from a
Terminal, then re-run. This is the normal, supported TCC flow — nothing here touches `TCC.db`
directly or needs SIP disabled, which is exactly what makes it work where the CI approach can't.

This doesn't replace the manual checklist pass above: `runIdeForUiTests` deliberately disables native
sheets, the native file chooser, and the screen menu bar so `remote-robot` can drive them, which means
this run never exercises what a real user actually sees for those three things. Run both when doing a
full pre-release pass; the automated suite alone is enough for routine "did I break rendering"
debugging.

## Windows pass: driven by remote-robot, not by hand

The Windows entry below was produced by driving the sandbox IDE through `remote-robot` rather than a
human clicking through it — `src/uiTest/kotlin/io/prismtabs/uitest/WindowsManualVerificationUiTest.kt`
walks the checklist above end-to-end, reusing the same fixtures the permanent `uiTest` suite already
relies on. It's split into two `@Test` methods run as separate invocations with an actual
`runIdeForUiTests` process kill-and-relaunch in between, since checklist item 8 needs a real restart
that nothing inside a single JVM run can exercise. Unlike the three regular `uiTest` classes, it's
deliberately excluded from `ui-tests.yml`'s `--tests` list and doesn't run in CI — same "on real
hardware or a VM, not CI" framing as the rest of this doc — but it's there to rerun for a future pass
rather than starting from scratch.

Two findings worth recording, both about *how* to check this pass rather than about the plugin:

- **A screenshot alone is not sufficient evidence for tint legibility.** The preset colours are
  deliberately deep/saturated (see README's Palette section — chosen to survive the ~50%
  unselected-tab blend), and that same property makes them easy to misjudge by eye in a small
  screenshot: an offscreen-painted image and even a genuine `Robot.createScreenCapture` PNG both
  *looked* untinted on a quick glance during this pass, right up until reading the actual on-screen
  pixel colour via `Robot.getPixelColor` at the tab's exact coordinates proved the tint was correct
  all along (full strength selected, ~50%-blended unselected, matching the documented maths almost
  exactly). Trust a direct pixel/API read over eyeballing an image — `IdeaFrame.pixelColorAtTabCenter`
  is the reusable version.
- **A forced process kill loses unsaved state that a real restart wouldn't.** `TabGroupState`'s
  `@Storage`-backed persistence is debounced, not synchronous per mutation, so simulating "restart"
  via a forced process kill (there's no in-process "Restart IDE" click for `remote-robot` to drive)
  can kill the process before a recent change reaches disk — a real user's normal exit never loses
  this because it saves on the way out. `IdeaFrame.forceSaveSettings`
  (`StoreUtil.saveDocumentsAndProjectsAndApp`, confirmed `public static final` via `javap` on
  `app-client.jar`) must run immediately before the kill for this kind of simulated restart to be a
  fair test — confirmed live: without it, a real assignment made moments earlier read back as
  unassigned after the "restart," which looked exactly like a persistence bug until the missing save
  was the actual explanation.

## Sign-off log

Append one entry per verification pass — mirrors the "Phase N result" entries in the main README.

```
Date:        YYYY-MM-DD
OS:          e.g. macOS 15.x (Apple Silicon) / Windows 11 24H2
IDE build:   e.g. IC-242.20224.300
Theme(s):    dark / light / both
Result:      PASS / FAIL — issues found (if any)
```

```
Date:        2026-08-09
OS:          Windows 11 Business (10.0.26200)
IDE build:   IC-242.20224.300
Theme(s):    dark (New UI "Dark" theme), DPI 120 (125% Windows scaling)
Result:      PASS — all 8 checklist items confirmed. Swatch popup shows all 8 presets legible;
             assigning "Blue" tints the tab at full strength when selected
             (java.awt.Color[r=46,g=92,b=156]) and at the documented ~50% blend when unselected
             (java.awt.Color[r=37,g=61,b=94] vs. an untinted tab's r=30,g=31,b=34), both confirmed
             by reading the live on-screen pixel, not just a screenshot (see finding above); New
             Group's name field has focus immediately (typed and Enter-submitted with no prior
             click); rename/recolour propagate to every member and active-group inheritance works;
             Remove from Group clears the tint; the Tab Groups tool window lists membership,
             collapse/expand works, and its icons and colour dots are crisp at 125% scaling; Settings
             | Tools | PrismTabs is reachable, renders correctly, and Restore Defaults reverts a
             renamed preset; group name/colour/membership all survived a real IDE restart (process
             killed and relaunched, with an explicit force-save first — see finding above). No
             PrismTabs defects found. Screenshots: build/reports/manual/*.png. One environment-only
             observation, not a PrismTabs issue: IntelliJ's bundled Gradle plugin logs a SEVERE
             GradleJvmSupportMatrix error on this machine because its default `java` on PATH is JDK
             25, which this IDE build's version parser can't handle — harmless, and unrelated to
             io.prismtabs (confirmed absent from idea.log's PrismTabs-attributed entries).
```
