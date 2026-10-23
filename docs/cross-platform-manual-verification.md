# Cross-platform manual verification

`build.yml` runs `./gradlew build` on Linux, macOS and Windows, which covers the plugin's logic. It
says nothing about rendering or input, so before each release, run this pass on Windows and macOS,
against the build that will be tagged. Linux is covered by day-to-day development.

## Per release, on each OS

1. `./gradlew test` and `./gradlew uiTest` (bare — classes self-skip where they don't apply).
2. The by-eye checks in the checklist below, in `./gradlew runIde` — **not** `runIdeForUiTests`,
   whose flags turn off native macOS behaviour a real user gets.
3. One real restart (item 8).
4. One disable and re-enable from Settings | Plugins (item 10).
5. Append a sign-off entry to your own tracking copy of this file (see "Sign-off log" below).

CI can't do this pass: hosted Windows runners fail the tool-window tests (`ui-tests.yml` is kept as
a debugging aid only), and hosted macOS can't grant the TCC permissions `remote-robot` needs.

## Checklist

The numbering is referenced by the sign-off log kept in your tracking copy; don't renumber, and
don't reuse a retired number.

| # | Area | Automated by | Still by hand |
|---|---|---|---|
| 1 | Swatch popup | `TabGroupPopupUiTest` | Caption and 8 presets legible, dark and light |
| 2 | Tab tint | `TabGroupPopupUiTest` (pixel reads), `TabColourUiTest` | Unselected tinted tab legible, both themes |
| 3 | New Group dialog | `TabGroupPopupUiTest` | — |
| 4 | Rename, recolour, active group, Files… | `TabGroupPopupUiTest` | Files… chooser opens centred |
| 5 | Remove from Group | `TabGroupPopupUiTest` | — |
| 6 | Tool window (incl. ⌘/Ctrl multi-select) | `TabGroupsToolWindowUiTest` | Stripe icon and dots crisp at OS scaling |
| 7 | Settings page, ⌘, on macOS | `TabGroupPaletteConfigurableUiTest`, `MacSettingsShortcutUiTest` | — |
| 8 | Restart persistence | `PersistenceUiTest` (save and reload, no restart) | One real restart: groups, colours, palette survive; **Show Group** reopens the last-open set |
| 10 | Disable and re-enable in place | `PluginUnloadUiTest` | From Settings \| Plugins: no restart prompt either way, colours clear on disable and return on re-enable |

## Running the suite on macOS

```bash
./gradlew --stop           # make sure the daemon is one this shell starts
./gradlew runIdeForUiTests &
./gradlew uiTest
```

- **Accessibility is what matters, not Screen Recording.** Without it, robot input is silently
  dropped and tests time out on `Failed to find … 'Tab Group…'`, which looks like a locator bug.
  There is no prompt.
- **Grant it to the terminal app you launch Gradle from**, then quit and reopen that app. The
  sandbox IDE has no bundle of its own to grant. Terminal.app is granted on the current Mac.
- **Check input before a run:** read `MouseInfo.getPointerInfo()`, `Robot.mouseMove` elsewhere,
  read again. Unchanged means input is dropped. Don't judge it from the Gradle daemon's parent in
  `ps`: daemons get reparented to `launchd`, and runs through such a daemon have passed and failed.
- **Screen capture is colour-managed:** pixel reads can be off by 1–2 per channel. Compare pixels
  channel-wise with a tolerance, never by exact RGB.
- There are no macOS sheets for the name dialogs: the platform has no sheet path
  (`Messages.canShowMacSheetPanel()` is hardcoded `false`), so the plain Swing dialog is what users
  see.

## Driven restart walkthrough (item 8)

`WindowsManualVerificationUiTest` walks the checklist with a real process restart. It runs on any
OS, and only with the opt-in flag:

```bash
rm -f src/uiTest/resources/fixture-project/.idea/tabGroups.xml   # exercise the first-write path
./gradlew uiTest --tests "io.prismtabs.uitest.WindowsManualVerificationUiTest.part1WalkthroughChecklist" \
  -Dprismtabs.manualVerification=true
# kill and relaunch runIdeForUiTests
./gradlew uiTest --tests "io.prismtabs.uitest.WindowsManualVerificationUiTest.part2VerifyPersistenceAfterRestart" \
  -Dprismtabs.manualVerification=true
```

Harness pitfalls, each of which once looked like a plugin bug:

- A forced kill skips save-on-exit, so `IdeaFrame.forceSaveSettings` must run first.
- A save or VFS change from a robot script must use `runJs(..., runInEdt = false)` with
  `ModalityState.nonModal()`, because the platform only allows it from a write-safe context.
  Otherwise the save fails silently (only `WARN Save settings failed`), and only when
  `tabGroups.xml` doesn't exist yet. The full mechanism is in `IdeaFrame.forceSaveSettings`'s doc
  comment.
- A feature that adds a modal to an existing flow breaks this walkthrough. Nothing runs it by
  default, so update it along with the feature.
- An "Add File to Git" prompt appears for files created in the fixture project; cancel it.
- The Kotlin plugin shows a single-declaration `Foo.kt` as `Foo` in the Project tree.

Known noise in `idea.log`, none with `io.prismtabs` frames: `GradleJvmSupportMatrix`
`IllegalArgumentException: 25` (JDK 25 on PATH, shows as a red balloon), and Kotlin-plugin
`SlowOperations` while Settings opens.

## Sign-off log

One entry per pass, newest last: date · OS · build · theme — result. Tracked in a copy of this
file kept outside the repo, not here.
