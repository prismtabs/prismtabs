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

- **Dialog chrome on macOS** (`New Tab Group`, `Rename Tab Group`). ~~Native sheets~~ — the platform
  has no sheet code path for these at all, established by bytecode below ("Checklist item 3's
  'sheet' question is obsolete"), so both are plain Swing dialogs everywhere. What is still worth
  confirming per-OS is that the text field receives focus and Enter submits.
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

1. **Swatch popup** — right-click a tab → **Tab Group…** opens; the **New Group** caption sits
   above the swatch row, and all 8 presets are visible and legible in both dark and light theme.
2. **Assign a preset** — click a preset dot, accept the pre-filled name → tab background tints;
   unselected tabs are still legible (the ~50%-strength blend documented in README "Measured
   constraint").
3. **New Group** — clicking a preset dot pops the name dialog, pre-filled with that colour's own
   label. Confirm the text field has focus immediately and that both Enter and clicking OK submit
   it; cancelling creates no group. (No sheet check — see "Checklist item 3's 'sheet' question is
   obsolete" below.)
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
  --tests "io.prismtabs.uitest.PaywallUiTest" \
  --tests "io.prismtabs.uitest.TabGroupPaletteConfigurableUiTest" \
  --tests "io.prismtabs.uitest.TabGroupPopupUiTest" \
  --tests "io.prismtabs.uitest.TabGroupsToolWindowUiTest" \
  --tests "io.prismtabs.uitest.MacSettingsShortcutUiTest"
```

`MacSettingsShortcutUiTest` is the one addition to `ui-tests.yml`'s list, and deliberately so: it
covers checklist item 7's ⌘, and `assumeTrue`-skips off macOS, so it belongs in this macOS command
rather than in the Windows CI workflow where it would only ever report as skipped.

Same `--tests` list as `ui-tests.yml`, for the same reason: `WindowsManualVerificationUiTest` (see
below) needs a human-driven IDE restart between its two methods and isn't written to run as a single
`uiTest` invocation. Despite the name, it now runs unmodified on macOS too — confirmed 2026-08-16,
both methods, restart included (see the sign-off log). Nothing in it is Windows-specific; the name
and its `TeamWindows` fixture group are historical.

If the Allow prompt never appears (some macOS versions only prompt the *first* time a process asks,
and a previously-denied grant has to be reset manually): `tccutil reset ScreenCapture` from a
Terminal, then re-run. This is the normal, supported TCC flow — nothing here touches `TCC.db`
directly or needs SIP disabled, which is exactly what makes it work where the CI approach can't.

### Which permission actually blocks the run, and which app it attaches to

Measured on the 2026-08-16 pass, and worth knowing before chasing a failure that looks like a broken
locator:

- **Accessibility, not Screen Recording, is what stops the suite.** Screen Recording governs
  `Robot.getPixelColor`/`createScreenCapture`; *input injection* (`Robot.mouseMove`, clicks, keys)
  is governed by the Accessibility grant. On this machine screen capture already worked while input
  did not, and every one of the four `TabGroupPopupUiTest` cases failed identically with
  `WaitForConditionTimeoutException: Failed to find 'ComponentFixture' by 'text 'Tab Group…''` —
  i.e. the right-click never opened the tab's context menu. That is the *same* symptom as a genuine
  locator bug, and the same one Wayland produces on Linux.
- **Confirm it in one call rather than guessing**, since the two failure modes are visually
  identical: read `MouseInfo.getPointerInfo()`, call `Robot.mouseMove` to a different point, read it
  again. Unchanged means input is being dropped — measured `pointer.before=(810,53)`,
  `requested=(947,144)`, `after=(810,53)` while the grant was missing, and an exact match once it
  was in place. Note there is **no prompt** for this: `Robot` posts CGEvents, which macOS drops
  silently rather than asking, so waiting for an "Allow" dialog that never comes is a dead end.
- **The grant attaches to the *responsible* parent app, not to the IDE under test.** The sandbox IDE
  is a bare JBR `java` binary out of the Gradle cache with no `.app` bundle of its own, so TCC
  charges its requests to whichever bundled app owns the session that launched Gradle — check with
  `ps -o pid=,ppid=` up the chain. Granting Accessibility to the *sandbox* IDE is not a thing you
  can do, and looking for it in the list is wasted time.
- **It is the *Gradle daemon's* ancestry that decides this, which makes it change under you.**
  Daemons are long-lived, shared between runs, and get reparented to `launchd` when the shell that
  spawned them exits — at which point there is no bundled ancestor at all and the grant silently
  stops applying. Observed within one session on 2026-08-16: the suite went green through a daemon
  whose chain was `IDE <- daemon <- IntelliJ IDEA.app`, and later failed with input dead through
  `IDE <- daemon <- launchd`. Same machine, same code, same permissions UI. If a previously-green
  run starts failing on every locator, check the chain before you touch the tests, and
  `./gradlew --stop` to force a fresh daemon under the current shell.
- **Granting a *running* app does not take effect until it is relaunched.** Accessibility is read at
  process start, so ticking Terminal (or iTerm) in the list while it is open changes nothing for the
  session already running inside it — confirmed here, where a previously-green
  `TabGroupPopupUiTest` still failed after the grant. Quit and reopen the terminal app, then start a
  fresh run. The path of least resistance is to run the suite from whichever app is *already*
  granted rather than granting a new one mid-session.

### macOS screen capture is colour-managed — do not assert exact RGB

The Windows pass established that a live pixel read beats eyeballing a screenshot. On macOS there is
a second-order catch: **`Robot.getPixelColor` does not return the bytes that were painted.** The
capture path runs through the display's colour profile, and the round trip is near-identity but not
identity.

Measured with no PrismTabs code involved at all — a bare `JWindow` filled with a known colour, read
straight back off the screen — 4 of 10 sampled colours came back shifted by 1–2 per channel:

```
blue   0x2E5C9C -> 0x2D5C9C  (-1 red)      green  0x1E6B3C -> 0x1F6B3B  (+1 red, -1 blue)
grey   0x5F6368 -> 0x5F6369  (+1 blue)     cyan   0x156C77 -> 0x176C77  (+2 red)
red / yellow / pink / purple, and the two IDE background controls: exact
```

The practical consequence: a selected Blue tab reads `r=45,g=92,b=156` on macOS where Windows
reports `r=46,g=92,b=156` — same painted colour, different capture pipeline. This is not a rendering
defect and not a repaint bug; the ~50% unselected blend (`r=37,g=61,b=94`) and the untinted control
(`r=30,g=31,b=34`) both happen to land on values macOS leaves alone, which is why only the
full-strength assertion tripped. `WindowsManualVerificationUiTest.matchesChannelwise` now compares
per channel with a tolerance of 3 instead of matching the string exactly. That stays meaningful: an
untinted tab is 16/61/122 away from Blue, two orders of magnitude outside the tolerance, so it can
still never pass an untinted or wrongly-coloured tab.

Corollary for anyone adding a pixel assertion: assert *channel-wise with a tolerance*, never
`contains("r=..,g=..,b=..")`, or the test is Windows-only by accident.

This doesn't replace the manual checklist pass above: `runIdeForUiTests` deliberately disables native
sheets, the native file chooser, and the screen menu bar so `remote-robot` can drive them, which means
this run never exercises what a real user actually sees for those three things. Run both when doing a
full pre-release pass; the automated suite alone is enough for routine "did I break rendering"
debugging.

### Checklist item 3's "sheet" question is obsolete — the platform has no sheets

Item 3 asks whether **New Tab Group** renders as a sheet attached to the main window. It cannot, on
any sandbox flag, and this is settled by bytecode rather than observation (IC-2024.2,
`lib/app-client.jar`, via `javap` per CLAUDE.md's recipe):

- `Messages.canShowMacSheetPanel()` and `Messages.isMacSheetEmulation()` are both compiled to a bare
  `iconst_0; ireturn` — hardcoded `false`, no property read, nothing to switch on.
- `com.intellij.ui.mac.MacMessages` declares only `showMessageDialog`, `showOkMessageDialog` and
  `showErrorDialog`. There is **no input-dialog entry point at all**, so
  `Messages.showInputDialog` — what `TabGroupPopup` uses for New Tab Group — has no native path it
  could take even if sheets existed.
- The string `ide.mac.message.dialogs.as.sheets` appears in **no** platform jar. (It does appear in
  `plugins/Kotlin/kotlinc/lib/kotlin-compiler.jar`, which shades an ancient intellij-core — not
  live code, and a good way to fool a naive grep.)

So the free-floating Swing dialog in `03-new-group-name-dialog.png` **is** what a real macOS user
sees, and the automated pass's screenshot is faithful rather than an artifact of the test flags. The
flag stays in `build.gradle.kts` only as harmless insurance for other host versions. Treat item 3 on
macOS as "confirm the dialog is focused and Enter submits" — which the automated pass already
asserts — and drop the sheet clause.

**Item 7's ⌘, is now covered by `MacSettingsShortcutUiTest`** — the one macOS path the rest of the
suite structurally cannot reach, since it goes through `ShowSettingsUtil` and would pass even if the
shortcut were unbound. The test drives a real ⌘, keystroke, then asserts the Settings dialog opened
and that this plugin's page is reachable in it. Verified 2026-08-16: passing in 10.6s, screenshot
`09-cmd-comma-settings.png` showing **Tools › PrismTabs** with all 8 presets rendered. The binding
it exercises reads `Mac OS X 10.5+` / `getActionIds(KeyStroke("meta COMMA"))` -> `[ShowSettings]`.

It runs against the regular sandbox, so add it to any full-pass `--tests` list; it self-skips off
macOS via `assumeTrue`. Note it needs a *granted* terminal like the rest of the suite — see the
permission section above, which is exactly what delayed this one being run.

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
- **That force-save has to be dispatched off the EDT with `ModalityState.nonModal()`, or it
  silently saves nothing.** Found on the 2026-08-19 Windows pass, and the most expensive kind of
  harness bug: part 1 reported PASS while writing no `.idea/tabGroups.xml` at all, and part 2 then
  read every assignment back as `null` — presenting exactly like a persistence bug in the plugin.
  `TransactionGuardImpl` allows model changes only from a *write-safe* modality, and its
  constructor registers exactly one (`ModalityState.nonModal()`); a `remote-robot` script runs
  under `ModalityState:{[JobProviderWithOwnerContext@…]}`, which is not it. The save then dies in
  `FileBasedStorage.getVirtualFile` → `RefreshSessionImpl.<init>` → `assertWriteSafeContext`, and
  the platform logs it as a bare `WARN - StoreUtil - Save settings failed` **without propagating
  anything to the caller**. Two things make it hide: passing the modality is useless from the EDT,
  because `ApplicationImpl.invokeAndWait` runs inline via `runIntendedWriteActionOnCurrentThread`
  and only reaches `wrapLaterInvocation` (which establishes the write-safe context) on the off-EDT
  branch; and the failing VFS refresh is only needed when the storage file does **not exist yet**,
  so any sandbox where `tabGroups.xml` already existed saved cleanly and hid the whole thing. Hence
  `runJs(..., runInEdt = false)` plus an explicit `ModalityState.nonModal()` — see
  `IdeaFrame.forceSaveSettings`'s doc comment. **When re-testing this, delete
  `src/uiTest/resources/fixture-project/.idea/tabGroups.xml` and restart the sandbox IDE first**,
  or the first-write path the bug lives on is never exercised. Note that path is covered by the
  root `.gitignore`'s `.idea/`, so a fresh clone starts without it every time — which is to say the
  broken case was the *default* one, and a machine that had run this pass before was the lucky one.

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

```
Date:        2026-08-16
OS:          macOS 26.6.1 (Apple Silicon, arm64), Retina @ 2x, screen 1440x900, dpi 127
IDE build:   IC-242.20224.300 (JBR 21.0.3)
Theme(s):    dark (New UI "Dark" theme)
Result:      PASS — all 8 checklist items confirmed, driven by remote-robot rather than by hand
             (WindowsManualVerificationUiTest, both methods, unmodified apart from the pixel
             tolerance noted below). Automated suite green too: PaywallUiTest (4),
             TabGroupPaletteConfigurableUiTest (1), TabGroupPopupUiTest (4),
             TabGroupsToolWindowUiTest (1) — 10/10, run twice, the second time against a
             freshly restarted IDE. Fast `./gradlew test` suite: 42/42.

             Item detail: all 8 presets present and legible in the swatch popup; assigning Blue
             tinted the tab at full strength when selected (r=45,g=92,b=156 — see the
             colour-management note above for why this is 46 on Windows and correct on both) and
             at the documented ~50% blend when unselected (r=37,g=61,b=94 vs. an untinted tab's
             r=30,g=31,b=34, both matching Windows exactly); the New Group name field had focus
             immediately (typed and Enter-submitted with no prior click); rename/recolour
             propagated to every member and active-group inheritance worked; Remove from Group
             cleared the tint; the tool window listed membership, collapse/expand worked, and its
             stripe icon and colour dots are crisp at 2x — visibly sharper than the Windows pass,
             with the tint plainly visible in the screenshots rather than needing a pixel read;
             Settings | Tools | PrismTabs rendered correctly with all 8 light/dark swatches and
             Restore Defaults reverted a renamed preset; group name/colour/membership survived a
             real restart (process killed and relaunched, with an explicit force-save first).
             Screenshots: build/reports/manual/*.png.

             No PrismTabs defects found. Three environment/harness-only observations, none of them
             io.prismtabs issues:
             (1) Robot input injection was initially dropped entirely — the Accessibility grant,
                 not Screen Recording, is the gate; see "Which permission actually blocks the run".
             (2) One test assertion was macOS-incompatible, not the plugin: the exact-RGB match on
                 the selected tab, now a channel-wise tolerance. See the colour-management note.
             (3) idea.log SEVEREs are all non-PrismTabs: the same GradleJvmSupportMatrix parser
                 error the Windows entry records (this machine's default `java` on PATH is JDK 25),
                 and TransactionGuardImpl "Write-unsafe context!" whose stack is pure platform save
                 code (ProjectStoreImpl.doSave -> SaveSessionProducerManager -> VFS refresh) with
                 zero io.prismtabs frames, blamed on "Robot server version: 0.11.23" — an artifact
                 of the harness calling forceSaveSettings from a script thread, which a real user's
                 save-on-exit does not do.
                 **Correction (2026-08-19): (3)'s TransactionGuardImpl half was not harmless.**
                 That stack is the force-save aborting outright — see the "dispatched off the EDT
                 with ModalityState.nonModal()" finding above. It stayed invisible here only
                 because this sandbox's .idea/tabGroups.xml already existed, so the save had no new
                 file to create and succeeded anyway; item 8 above is therefore still genuinely
                 verified on macOS, but it passed one deleted file away from a false PASS. It is
                 still true that the stack carries zero io.prismtabs frames and that this is a
                 harness bug rather than a plugin one — only "harmless" was wrong.

             Nothing outstanding for macOS. Item 7's ⌘, keystroke was closed the same day by
             MacSettingsShortcutUiTest (passing, 10.6s, screenshot 09-cmd-comma-settings.png:
             Tools › PrismTabs, all 8 presets). Item 3's sheet clause was retired rather than
             tested — the platform has no sheet code path at all (see "Checklist item 3's 'sheet'
             question is obsolete"), so the dialog the pass already screenshotted is what a real
             user sees.
```

```
Date:        2026-08-19
OS:          Windows 11 Pro (10.0.22000.2538)
IDE build:   IC-242.20224.300 (JBR 21.0.3)
Theme(s):    dark (New UI "Dark" theme), DPI 120 (125% Windows scaling), screen 1536x864
Result:      PASS — first Windows pass covering the merged swatch-popup group-creation flow
             (commit 694cf43, untested on Windows before this). Automated suite green:
             PaywallUiTest (4), TabGroupPaletteConfigurableUiTest (1), TabGroupPopupUiTest (7),
             TabGroupsToolWindowUiTest (1) — 13/13, run twice, the second time after the harness
             fix below. Fast `./gradlew build` (compile + plain-JUnit): 42/42.
             All 8 checklist items confirmed via WindowsManualVerificationUiTest, both methods,
             with a real process kill-and-relaunch between them.

             The two new regression tests for this commit both pass here: the "New Group" caption
             is painted at the list's own foreground, and only the captioned separator gets extra
             room beneath it. Both assert *relatively* (caption vs. UIUtil.getListForeground(),
             heading inset vs. every uncaptioned row) rather than against fixed pixel values, which
             is why neither is sensitive to this machine's 125% scaling — worth keeping that way.
             Screenshot 01-swatch-popup.png confirms the intended layout visually: caption above
             the 8 dots, "Add to Group…" below on its own bare divider, all legible at 125%.

             Item detail: all 8 presets present and legible; assigning Blue tinted the tab at full
             strength when selected (r=46,g=92,b=156) and at the documented ~50% blend when
             unselected (r=37,g=61,b=94 vs. an untinted tab's r=30,g=31,b=34) — identical to the
             2026-08-09 Windows numbers and matching macOS within the documented capture tolerance;
             the New Group dialog was pre-filled with the colour's label, had focus immediately
             (typed and Enter-submitted with no prior click) and named the group from what was
             typed; rename/recolour propagated to every member and active-group inheritance worked;
             Remove from Group cleared the tint; the tool window listed membership, collapse/expand
             worked, icons and dots crisp at 125%; Settings | Tools | PrismTabs rendered and
             Restore Defaults reverted a renamed preset; group name/colour/membership survived a
             real restart. Screenshots: build/reports/manual/*.png.

             No PrismTabs defects found. One real harness defect found and fixed, and it is the
             reason this pass took three attempts:
             (1) IdeaFrame.forceSaveSettings was silently saving nothing. Part 1 reported PASS
                 while writing no .idea/tabGroups.xml at all, and part 2 then read every assignment
                 back as null — which presents exactly like a persistence bug in the plugin and is
                 not one. Root cause is the write-safe-modality rule; see the "dispatched off the
                 EDT with ModalityState.nonModal()" finding above for the full mechanism, and note
                 the correction it forces on the 2026-08-16 macOS entry's observation (3), which
                 recorded this same stack as harmless. Fixed by runJs(..., runInEdt = false) plus
                 an explicit ModalityState.nonModal(); verified by deleting tabGroups.xml,
                 restarting the sandbox, and re-running both parts — the file is now written on the
                 first-write path and idea.log has zero "Save settings failed" entries afterwards.
             (2) Environment-only, not a PrismTabs issue: the IDE shows a "launched with a script
                 launcher ('bin\idea.bat')" notification in every screenshot — that is how
                 runIdeForUiTests starts the sandbox, unrelated to the plugin.
```
