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
6. **PrismTabs tool window** — opens from its stripe button; lists every group with member count;
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
./gradlew uiTest
```

No `--tests` list needed, on macOS or anywhere else: every `uiTest` class that shouldn't run
unattended self-gates with a JUnit `assumeTrue` rather than relying on a caller to curate an
include/exclude list per machine. `MacSettingsShortcutUiTest` gates on `os.name` starting with
`Mac`, so a bare run picks it up automatically here (and reports skipped on Windows CI) with no
extra flag. `WindowsManualVerificationUiTest` (see below) gates on an explicit
`-Dprismtabs.manualVerification=true` instead of an OS check — nothing in it is actually
Windows-specific (the name and its `TeamWindows` fixture group are historical; confirmed running
unmodified on macOS too, 2026-08-16 and 2026-08-19) — so it skips here by default and has to be
invoked deliberately, which the restart procedure below covers.

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
- **It is the *Gradle daemon's* ancestry that decides this, which makes it change under you** —
  but the live `ppid` chain is *not* the thing to read it off. Daemons are long-lived, shared
  between runs, and get reparented to `launchd`, at which point `ps -o ppid=` shows no bundled
  ancestor at all. Observed within one session on 2026-08-16: the suite went green through a daemon
  whose chain was `IDE <- daemon <- IntelliJ IDEA.app`, and later failed with input dead through
  `IDE <- daemon <- launchd`. Same machine, same code, same permissions UI.

  **Corrected on the 2026-08-19 macOS pass: `<- launchd` on its own does not predict a failure.**
  Every run of that pass — 14/14 automated cases plus both halves of the driven walkthrough, every
  one green — went through a chain that read `IDE(1671) <- daemon(1257) <- launchd(1)` from the
  first `ps` to the last. The one thing known to differ from the failing 2026-08-16 case is
  *provenance*, not ppid: this daemon was started by a `./gradlew` invocation from a Terminal.app
  shell that stayed alive for the whole pass, whereas a daemon that has outlived the session that
  spawned it has no granted app behind it. The natural explanation is that TCC resolves the
  responsible app once, at exec time, so later reparenting cannot revoke it — but treat that as
  inference, not measurement: `launchctl procinfo <pid>` is what prints the recorded responsible
  process and it needs root, so the attribution itself stayed a black box on both passes.

  So: `./gradlew --stop` before a pass, to guarantee the daemon is one *this* shell started, is
  still exactly the right habit — just don't read a `<- launchd` chain afterwards as evidence that
  it failed. **Prove it with the pointer probe instead**, which costs one call and answers the
  actual question (see the bullet above): the 2026-08-19 pass ran `MouseInfo.getPointerInfo()` ->
  `Robot.mouseMove` -> read again from a throwaway `java InputProbe.java` in the same shell before
  starting the IDE, got `before=(1010,284) requested=(1147,375) after=(1147,375)`, and knew input
  was live before a single test had run.
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

It runs against the regular sandbox, so a bare `./gradlew uiTest` picks it up on its own on macOS —
no `--tests` needed, since it `assumeTrue`-skips off macOS by `os.name` rather than needing to be
listed in. Note it needs a *granted* terminal like the rest of the suite — see the permission
section above, which is exactly what delayed this one being run.

## Windows pass: driven by remote-robot, not by hand

The Windows entry below was produced by driving the sandbox IDE through `remote-robot` rather than a
human clicking through it — `src/uiTest/kotlin/io/prismtabs/uitest/WindowsManualVerificationUiTest.kt`
walks the checklist above end-to-end, reusing the same fixtures the permanent `uiTest` suite already
relies on. It's split into two `@Test` methods run as separate invocations with an actual
`runIdeForUiTests` process kill-and-relaunch in between, since checklist item 8 needs a real restart
that nothing inside a single JVM run can exercise. Unlike the regular `uiTest` classes, it
`assumeTrue`-skips unless launched with `-Dprismtabs.manualVerification=true` (see its own doc
comment), so it never runs as part of `ui-tests.yml`'s or this doc's bare `./gradlew uiTest` — same
"on real hardware or a VM, not CI" framing as the rest of this doc — but it's there to rerun
deliberately for a future pass rather than starting from scratch:

```bash
./gradlew uiTest --tests "io.prismtabs.uitest.WindowsManualVerificationUiTest.part1WalkthroughChecklist" \
  -Dprismtabs.manualVerification=true
# kill and relaunch runIdeForUiTests here — see the finding on forced restarts below
./gradlew uiTest --tests "io.prismtabs.uitest.WindowsManualVerificationUiTest.part2VerifyPersistenceAfterRestart" \
  -Dprismtabs.manualVerification=true
```

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

## verifyPlugin was red, and had been for a while

Not a cross-platform finding — `verifyPlugin` is a static bytecode check whose result cannot vary
by host OS, which is exactly why `build.yml` runs it once rather than in the matrix. It is recorded
here because this pass is what surfaced it: running "all the tests" on real hardware included the
one task nobody had run locally in weeks.

`.github/workflows/build.yml`'s `verify-plugin` job had failed on **every** push since at least
2026-08-08 while all three `build` matrix legs stayed green. The failure was a single
`OVERRIDE_ONLY_API_USAGES` problem: `PrismTabsLicense.showRegisterDialog` invoked
`AnAction.actionPerformed(AnActionEvent)` directly, and that method is `@ApiStatus.OverrideOnly`.
That category is in `build.gradle.kts`'s `failureLevel` deliberately — only `INTERNAL_API_USAGES`
was dropped, and only for `TabRepaint.queueUpdateFile` — so the build failed as designed.

Fixed by going through the platform's own entry point,
`ActionUtil.invokeAction(action, dataContext, place, inputEvent, onDone)`, which is present on the
242 floor. It is a better call quite apart from the verifier: it runs the action's `update` via
`lastUpdateAndCheckDumb` and performs the action only if it enabled itself, where a bare
`actionPerformed` fires a disabled action regardless. It also builds its event with the
non-deprecated `createFromInputEvent`, retiring the deprecated `AnActionEvent.createFromDataContext`
call the old code made.

One warning remains and is unavoidable at this floor: `invokeAction` is undeprecated on 242 but
deprecated from 243 on, in favour of `ActionUtil.performAction(..., ActionUiKind)` — which only
exists from 243. Measured across the verifier matrix: 0 deprecation findings on IU-242, 1 on each
of IU-243/251/252/253/261/262. `DEPRECATED_API_USAGES` is not in `failureLevel`, so this is a note,
not a failure — the same accepted trade-off `CaptionedListPopup` already makes for
`SeparatorWithText`. Switch to `performAction` on the day the compile floor moves to 243.

After the fix, `./gradlew verifyPlugin` is green across all seven IDEs it resolves
(IU-242.26775.15, 243.28141.41, 251.29188.72, 252.28539.97, 253.33813.55, 261.27258.48,
262.9437.185), with zero override-only findings anywhere in the reports.

## Light theme: what the 2026-08-19 spot-check actually establishes

Checklist item 1 asks for the swatch popup "in both dark and light theme", and no pass — Linux,
Windows or macOS — had ever run the light half. It was covered here by a **throwaway** `uiTest`
class, written for this pass and deleted afterwards rather than left in the suite: it flips the
sandbox to the platform's default light theme via
`LafManager.setCurrentUIThemeLookAndFeel(getDefaultLightLaf())` + `updateUI()` (all confirmed on the
242 jars with `javap`), re-runs the popup/tint/tool-window/settings checks, and flips back. Its
numbers are in the sign-off entry above.

Two things to know before repeating it:

- **The screenshots are partly stale, and that is the theme switch, not a rendering defect.** A
  runtime `LafManager` swap repaints what gets rebuilt — the popup, the Settings dialog, the tab
  strip, the editor, the status bar all come back light — but leaves the main toolbar, title bar
  and project tree painted dark in `light-01`/`light-02b`. A real user switching theme through
  Settings does not see this. So read those screenshots for the popup and the tabs, and take the
  rest of the frame as an artifact; the **pixel reads and the component-level colour reads are the
  evidence**, exactly as the "do not trust a screenshot" finding above already argues for dark.
- **A permanent version needs the restore in a `finally`.** The throwaway restores the dark theme
  on its happy path only. Left in the suite as written, one failed assertion would strand the
  sandbox in light theme and poison every later test in the same session — `LafManager` is
  application-global, the same hazard `restoreRegisterDialog` already documents for `ActionManager`.

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
             Remove from Group clears the tint; the PrismTabs tool window lists membership,
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

```
Date:        2026-08-19
OS:          macOS 26.6.2 (Apple Silicon, arm64), Retina @ 2x, screen 1440x900, dpi 127
IDE build:   IC-242.20224.300 (JBR 21.0.3), plugin 1.2.1-SNAPSHOT
Theme(s):    dark (New UI "Dark" theme) for the full pass; light spot-checked separately, below
             — the first time any pass has covered checklist item 1's "and light theme" half
Result:      PASS — first macOS pass covering the merged swatch-popup group-creation flow (commit
             694cf43, which post-dates the 2026-08-16 macOS entry by a day and had only ever been
             verified on Linux and Windows) and the first macOS run of the forceSaveSettings fix
             (commit e5d0bdf, found on Windows). Automated suite green: PaywallUiTest (4),
             TabGroupPaletteConfigurableUiTest (1), TabGroupPopupUiTest (7),
             TabGroupsToolWindowUiTest (1), MacSettingsShortcutUiTest (1) — 14/14. Fast
             `./gradlew build` (compile + plain-JUnit): 42/42. All 8 checklist items confirmed via
             WindowsManualVerificationUiTest, both methods, with a real process kill-and-relaunch
             between them.

             e5d0bdf verified on the path it was written for, not just incidentally: this pass
             deleted .idea/tabGroups.xml *before* launching the sandbox, exactly as the
             "dispatched off the EDT with ModalityState.nonModal()" finding instructs, so the
             force-save had to create the file rather than overwrite one. It did — a 961-byte
             tabGroups.xml appeared at part 1's forceSaveSettings, part 2 read both assignments
             back as TeamWindows after the kill, and idea.log has zero "Save settings failed" and
             zero "Write-unsafe context!" entries for the whole session. That closes the gap the
             2026-08-16 entry's own correction flagged: macOS item 8 had passed one deleted file
             away from a false PASS, and now passes on the previously-broken branch.

             694cf43's two regression tests both pass here, and neither needed a Retina-specific
             adjustment — the caption-colour test compares against UIUtil.getListForeground() and
             the spacing test compares the captioned separator's inset against every uncaptioned
             row, so both are scale-independent by construction (same reason they were insensitive
             to Windows' 125% scaling). Screenshot 01-swatch-popup.png shows the intended layout at
             2x: "New Group" caption at full item weight above the 8 dots, clear space beneath it,
             "Add to Group…" below on its own bare divider.

             Item detail: all 8 presets present and legible; assigning Blue tinted the tab at full
             strength when selected (r=45,g=92,b=156 — the documented macOS colour-managed -1 red
             against Windows' r=46, and identical to the 2026-08-16 macOS reading) and at the
             documented ~50% blend when unselected (r=37,g=61,b=94 vs. an untinted tab's
             r=30,g=31,b=34, both matching Windows exactly); the New Group dialog was pre-filled
             with the colour's label, had focus immediately (typed "FocusTest" and Enter-submitted
             with no prior click) and named the group from what was typed; rename/recolour
             propagated to every member and active-group inheritance worked; Remove from Group
             cleared the tint; the tool window listed TeamWindows with 2 tabs, collapse closed its
             tabs and marked the row collapsed, expand reopened them, and its stripe icon and
             colour dots are crisp at 2x; Settings | Tools | PrismTabs rendered all 8 presets with
             their light/dark hex swatches and Restore Defaults reverted a renamed preset; ⌘, is
             covered by MacSettingsShortcutUiTest (passing, 9.3s). Screenshots:
             build/reports/manual/*.png.

             Light theme, spot-checked (see "Light theme" above for how, and for what the
             screenshots do and do not prove): all 8 presets present and legible; the "New Group"
             caption still matched the list foreground exactly (both 0,0,0 at light, against
             223,225,229 at dark — the point of asserting sameness rather than a literal, now
             demonstrated rather than argued); the caption kept its 8px bottom inset while every
             uncaptioned row stayed at 0; Blue rendered r=175,g=203,b=250 selected against the
             painted light preset 0xAECBFA = 174,203,250 (the same +1 capture shift documented
             above) and r=214,g=228,b=252 unselected against an untinted tab's plain white
             255,255,255, so the ~50% blend stays clearly distinguishable on a light background
             too; the tool window listed the group and the settings page rendered all 8 rows with
             both hex swatches, the pale light ones still separated from white by ColorSwatchIcon's
             border. Screenshots: build/reports/manual/light-*.png.

             No PrismTabs defects found. Four environment/harness-only observations, none of them
             io.prismtabs issues:
             (1) The daemon-ancestry rule recorded on 2026-08-16 does not hold as written — every
                 green run here went through `IDE <- daemon <- launchd`. See the correction in
                 "Which permission actually blocks the run".
             (2) Screenshot 08-after-restart.png carries an "IDE error occurred" balloon. It is the
                 GradleJvmSupportMatrix parser error the Windows and 2026-08-16 macOS entries both
                 already record (`Caused by: java.lang.IllegalArgumentException: 25`, this
                 machine's default `java` on PATH being JDK 25); zero io.prismtabs frames in the
                 stack. Worth knowing that it renders as a visible red balloon on macOS, so it
                 looks alarming in an otherwise clean screenshot.
             (3) Two SlowOperations SEVEREs, both blamed on the Kotlin plugin and both raised while
                 the Settings dialog enumerated configurables for item 7
                 (KotlinScriptingSettingsConfigurable.<init> ->
                 ScriptTemplatesFromDependenciesProvider -> FilenameIndex on the EDT). Zero
                 io.prismtabs frames.
             (4) Out of scope for this checklist but found while running everything on this
                 machine: `./gradlew verifyPlugin` was failing, and had been red in CI on every
                 push since at least 2026-08-08 (the `build` matrix was green throughout, which is
                 why it went unnoticed). Cause and fix are unrelated to either platform — see
                 "verifyPlugin was red" above.
```

```
Date:        2026-08-21
OS:          Windows 11 Pro (10.0.26200)
IDE build:   IC-242.20224.300 (JBR 21.0.3), plugin 1.3.2-SNAPSHOT
Theme(s):    dark (New UI "Dark" theme), DPI 120 (125% Windows scaling), screen 1536x864
Result:      PASS — first pass on any platform covering "offer to delete a tab group when its last
             member is removed" (commit 093b957), which had only ever been exercised on the Linux
             machine it was written on. Automated suite green: PaywallUiTest (4),
             TabGroupPaletteConfigurableUiTest (1), TabGroupPopupUiTest (10),
             TabGroupsToolWindowUiTest (1), EmptyGroupOnDiskDeleteUiTest (3) — 19/19 from a bare
             `./gradlew uiTest`, with MacSettingsShortcutUiTest and WindowsManualVerificationUiTest
             both correctly reporting skipped and no `--tests` curation anywhere (the 5e40060
             self-gating mechanism, confirmed working on a second machine). Fast `./gradlew test`:
             55/55. `./gradlew verifyPlugin`: green across all seven IDEs it resolves
             (IU-242.26775.15, 243.28141.41, 251.29188.72, 252.28539.97, 253.33813.55,
             261.27258.48, 262.10315.19 — that last one a newer release than the 262.9437.185 the
             2026-08-19 entry recorded), zero override-only and zero compatibility findings, and
             nothing new from this feature: the remaining deprecation/internal-API notes are the
             four already accepted on purpose (SeparatorWithText, ActionUtil.invokeAction on 243+,
             FileEditorManagerImpl.queueUpdateFile, PluginManager.getPluginByClass). Run here
             because the 2026-08-19 pass is what caught this task having been silently red for
             weeks, and because 093b957 introduced new platform calls — MessageDialogBuilder.yesNo,
             VFileDeleteEvent, ModalityState.nonModal, Project.getDisposed — that had never been
             put through it. All 8 checklist items confirmed via WindowsManualVerificationUiTest,
             both methods, with a real process kill-and-relaunch between them.

             All three ways a group can empty are now verified against a live IDE, where the third
             had no live coverage at all before this pass:
             - "Remove from Group" on the last member, and moving the last member into another
               group — TabGroupPopupUiTest's three new cases plus its reworked negative case, which
               asserts the prompt does *not* appear while a member remains.
             - The last member deleted on disk — the new EmptyGroupOnDiskDeleteUiTest. That trigger
               is the only one that never touches TabGroupPopup: it arrives on
               TabGroupVfsListener.before from inside the write action performing the deletion, so
               nothing about it (the `before`-not-`after` URL read, the deferred non-modal prompt)
               is reachable from a unit test. One of its three cases deletes the file the way a user
               does — selecting it in the Project tool window and pressing Delete — so the
               platform's own delete confirmation is genuinely in play and the `nonModal` "queue
               behind the dialog driving the deletion rather than stack on it" claim is
               demonstrated rather than argued. A second covers the batch case: deleting a
               directory emptied two groups at once and raised a single plural "Empty Tab Groups"
               dialog, not a queue of identical ones.

             Item detail matches the previous Windows entries exactly where it should: all 8 presets
             present and legible; Blue tinted the tab at full strength when selected
             (r=46,g=92,b=156) and at the documented ~50% blend when unselected (r=37,g=61,b=94 vs.
             an untinted tab's r=30,g=31,b=34) — identical to both the 2026-08-09 and 2026-08-19
             Windows readings; the New Group dialog was pre-filled with the colour's label, had
             focus immediately (typed and Enter-submitted with no prior click); rename/recolour
             propagated to every member and active-group inheritance worked; the tool window listed
             membership, collapse/expand worked, icons and dots crisp at 125%; Settings | Tools |
             PrismTabs rendered and Restore Defaults reverted a renamed preset.

             The empty-group prompt itself was read off the screenshot, not just asserted: 05-
             remove-from-group.png shows the "Empty Tab Group" dialog with the question icon,
             `"Blue" has no tabs left. Delete the group?`, and "Delete Group" as the focused default
             beside "Keep". 06-tool-window-listing.png shows the declined group listed as
             `Blue  0 tabs` above `TeamWindows  2 tabs` — the row README's "Empty groups" section
             promises and which nothing had ever checked. Screenshots: build/reports/manual/*.png.

             Persistence was tested on the first-write path deliberately, as the 2026-08-19 finding
             instructs: .idea/tabGroups.xml was deleted and the sandbox restarted before part 1, so
             forceSaveSettings had to create the file rather than overwrite one. It did (984 bytes),
             and part 2 read back `[Blue 0 tabs, TeamWindows 2 tabs]` after the kill-and-relaunch.
             That second row is the one worth noting: an emptied-but-kept group has no assignments
             to reconstruct it from, so it round-trips through tabGroups.xml purely as a group
             entry. "Keep" therefore means kept across restarts, which is the whole argument for
             asking rather than deleting silently the way Chrome does.

             One real defect found and fixed, in the verification harness rather than the plugin:
             (1) WindowsManualVerificationUiTest.part1WalkthroughChecklist was stale against
                 093b957 and could not pass. Checklist item 5 removes Sample.kt from its group, and
                 Sample.kt is that group's *only* member, so the new prompt now fires there. The
                 assignment is cleared before the prompt opens, so item 5's own wait still passed
                 and the failure surfaced one step later as a timeout in item 6's tool-window
                 click — with an unanswered modal sitting in front of the IDE. 093b957 updated
                 TabGroupPopupUiTest but not this walkthrough. Item 5 now answers the prompt with
                 "Keep" and asserts the emptied group is listed as "0 tabs"; part 2 asserts that
                 row survives the restart. Worth recording as a pattern: a feature that adds a
                 modal to an existing flow breaks every driven walkthrough of that flow, and this
                 class only runs when someone deliberately opts in, so nothing was going to catch
                 it until a pass was run.

             Two environment-only observations, neither an io.prismtabs issue:
             (2) An "Add File to Git" modal appears whenever a test creates a file in the fixture
                 project, because that project sits inside this repository's own working copy
                 (.idea/vcs.xml maps it). It swallows robot keystrokes, which is what initially
                 stopped the project-view deletion case from doing anything at all.
                 EmptyGroupOnDiskDeleteUiTest cancels it rather than accepting, so a run never
                 stages anything into the real repository.
             (3) The Project tree renders `Doomed.kt` as `Doomed`: the Kotlin plugin presents a .kt
                 file holding a single top-level declaration by that declaration's name. A tree
                 lookup by file name waits out its full timeout against a tree that is displaying
                 the file perfectly well.

             Also confirmed while writing (2)/(3): VFS mutations driven from a remote-robot script
             must go through `runJs(..., runInEdt = false)` plus an explicit
             `ModalityState.nonModal()`, exactly as IdeaFrame.forceSaveSettings already documents.
             Without it the write still lands but the platform logs a TransactionGuardImpl
             "Write-unsafe context!" SEVERE per call — measured directly here, and cleaned up before
             sign-off, so idea.log has zero SEVEREs attributable to io.prismtabs for this pass.
```

```
Date:        2026-08-21
OS:          macOS 26.6.2 (Apple Silicon, arm64), Retina @ 2x, screen 1440x900, dpi 127
IDE build:   IC-242.20224.300 (JBR 21.0.3), plugin 1.3.3-SNAPSHOT
Theme(s):    dark (New UI "Dark" theme)
Result:      PASS — macOS half of the verification for "offer to delete a tab group when its last
             member is removed" (commit 093b957), following the Windows pass of the same day.
             Automated suite green: PaywallUiTest (4), TabGroupPaletteConfigurableUiTest (1),
             TabGroupPopupUiTest (10), TabGroupsToolWindowUiTest (1), EmptyGroupOnDiskDeleteUiTest
             (3), MacSettingsShortcutUiTest (1) — 20/20 from a bare `./gradlew uiTest`, with both
             WindowsManualVerificationUiTest methods correctly reporting skipped and no `--tests`
             curation anywhere. That is the same 5e40060 self-gating mechanism the Windows entry
             confirmed, now demonstrated from the other side: the Mac-only class runs here and
             skips there, the opt-in class skips on both. Fast `./gradlew test`: 55/55.
             `./gradlew verifyPlugin`: green across all seven IDEs it resolves (IU-242.26775.15,
             243.28141.41, 251.29188.72, 252.28539.97, 253.33813.55, 261.27258.48, 262.10315.19),
             every one "Compatible", zero compatibility problems and zero override-only findings,
             and nothing new from this feature — the reports hold exactly the four accepted notes
             (SeparatorWithText, ActionUtil.invokeAction on 243+, FileEditorManagerImpl
             .queueUpdateFile, PluginManager.getPluginByClass, that last one counted as internal
             only from 262). All 8 checklist items confirmed via WindowsManualVerificationUiTest,
             both methods, with a real process kill-and-relaunch between them.

             All three ways a group can empty are verified against a live IDE here, matching the
             Windows result trigger for trigger: "Remove from Group" on the last member and moving
             the last member into another group via TabGroupPopupUiTest's three cases plus its
             negative case (no prompt while a member remains), and deletion on disk via
             EmptyGroupOnDiskDeleteUiTest — including the project-view Delete case, where the
             platform's own delete confirmation is in play and the `nonModal` "queue behind the
             dialog driving the deletion" claim is exercised rather than argued, and the directory
             case, where two groups emptied at once and raised a single plural dialog.

             The prompt itself was read off the screenshot, not just asserted, and it is worth
             recording how macOS lays it out: 05-remove-from-group.png shows "Empty Tab Group"
             with the question icon, `"Blue" has no tabs left. Delete the group?`, and "Keep" to
             the *left* of the focused default "Delete Group" — native macOS button order, decided
             by the platform's dialog layout rather than by anything PrismTabs positions, so it is
             expected to read reversed against the Windows screenshot of the same prompt. No
             PrismTabs painting is involved in this dialog at all (MessageDialogBuilder.yesNo),
             which is why no light-theme spot-check was repeated for it; the light-theme evidence
             from 2026-08-19 covers what this plugin actually paints.
             06-tool-window-listing.png shows the declined group as `Blue  0 tabs` above
             `TeamWindows  2 tabs`, dots and stripe icon crisp at 2x.

             Item detail matches the previous macOS entries exactly where it should: all 8 presets
             present and legible with the "New Group" caption above the swatch row; Blue tinted
             the tab at full strength when selected (r=45,g=92,b=156 — the documented
             colour-managed -1 red against Windows' r=46, identical to both earlier macOS
             readings) and at the documented ~50% blend when unselected (r=37,g=61,b=94 vs. an
             untinted tab's r=30,g=31,b=34); the New Group dialog had focus immediately (typed and
             Enter-submitted with no prior click); rename/recolour propagated to every member and
             active-group inheritance worked; the tool window listed membership, collapse/expand
             worked; Settings | Tools | PrismTabs rendered and Restore Defaults reverted a renamed
             preset; ⌘, covered by MacSettingsShortcutUiTest (passing, 9.6s).

             Persistence was again tested on the first-write path, as the 2026-08-19 finding
             instructs: .idea/tabGroups.xml was absent and the sandbox restarted before part 1, so
             forceSaveSettings had to create the file. It did (961 bytes — exactly 23 fewer than
             the Windows entry's 984, i.e. the same 23-line file with CRLF endings there), and
             part 2 read back `[Blue 0 tabs, TeamWindows 2 tabs]` after the kill-and-relaunch, with
             both assignments still TeamWindows. The emptied-but-kept group round-trips through
             tabGroups.xml as a bare GroupEntry with no assignments to reconstruct it from, so
             "Keep" means kept across restarts on macOS too.

             No PrismTabs defects found, and no harness defects either — the walkthrough needed no
             changes on this platform, ec8e1d0's item-5 fix having already brought it up to date
             with the new prompt. idea.log for the whole pass carries five SEVEREs, all
             environment-only and all previously recorded: three GradleJvmSupportMatrix parser
             errors (`IllegalArgumentException: 25`, this machine's default `java` on PATH being
             JDK 25 — the same one that renders as the red "IDE error occurred" balloon in
             08-after-restart.png) and two SlowOperations from the Kotlin plugin's
             ScriptTemplatesFromDependenciesProvider while the Settings dialog enumerated
             configurables for item 7. Zero io.prismtabs frames in any of them, and — unlike
             2026-08-16 — zero "Write-unsafe context!" and zero "Save settings failed" entries
             anywhere in the session. Screenshots: build/reports/manual/*.png.

             One environment note for whoever runs the next macOS pass: Terminal.app now holds the
             Accessibility grant on this machine, so the "run it from IDEA's built-in terminal"
             workaround the 2026-08-16 entry needed no longer applies. Confirmed the documented
             way rather than assumed — a throwaway `java InputProbe.java` in the same shell read
             `before=(329,77) requested=(466,168) after=(466,168)`, i.e. input live, before a
             single test ran. The daemon chain was `<- launchd` throughout, as on 2026-08-19, and
             again predicted nothing.
```
