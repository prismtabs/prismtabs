# Manual test cases

A functional checklist for clicking through every PrismTabs feature by hand in a sandbox IDE
(`./gradlew runIde`). This is the *what* — every behaviour the product promises, one case per
row/bullet, organised by feature area rather than by OS. It complements, and deliberately
duplicates none of the depth of, [`docs/cross-platform-manual-verification.md`](cross-platform-manual-verification.md),
which is the *where* — the same core flow re-run per OS to catch platform chrome, HiDPI, and
native-dialog differences. Run this doc first on whatever machine is convenient; run that one when
doing a release pass across Windows/macOS/Linux.

Automated coverage exists for most of what is below (`./gradlew test` for pure logic,
`./gradlew uiTest` for the `remote-robot` suite — see README "UI testing"). Note the OS, IDE build,
and theme (dark/light) you test with, and be picky — anything that looks off is worth flagging even
if it's not what you set out to test (see CLAUDE.md's UI standards).

**Automation annotations.** Every bullet carries an *Automated* note saying what the test suite
actually asserts, checked against the test bodies rather than their names. **Struck-through
bullets are fully automated and need no manual pass.** Three verdicts:
- **full** — a test drives the real behaviour and asserts every claim in the bullet. Struck through.
- **partial** — some of the bullet is asserted; the note says which part is left for a human eye.
- **not automated** — nothing asserts it, usually because the claim is a visual judgement
  (legibility, crispness) or needs a real IDE restart or a real JetBrains licence.

History: a first pass on 2026-09-14 over-credited several bullets (bold member rows, Rename and
Recolour from the tool window, the free tier's rename/recolour/delete, the registration dialog
being raised); on 2026-09-15 every bullet was re-audited, tests were written for every gap that
could be automated, and each new test was mutation-checked — the product broken on purpose to
confirm the test then fails. Where the checklist described behaviour the product does not have
(Enter on a group row, the member-row context menu, palette edits recolouring existing groups,
F2 as the rename key, dynamic disable of the paid build), the bullet was corrected to what the
product does. Sections that ended up fully automated (swatch add/remove, Manage Groups, group
deletion, empty-group prompts, palette settings, persistence, rename/move remapping, and the
colour-provider interplay) were removed outright, and every remaining section had its own
fully-automated (struck-through) bullets dropped too, rather than kept as dead weight; their
coverage lives in the test suite named inline throughout this doc.

Two suite-wide checks back every automated bullet: a UI test fails if PrismTabs logs a `SEVERE`
to `idea.log` while it runs (`RemoteRobotExtension`), and `WindowsManualVerificationUiTest`, which
performs real restarts, stays opt-in and is **not** counted as coverage here.

**Before you start:** `plugin.xml` ships the `<product-descriptor … optional="true"/>`, so this is
a paid build and a fresh `runIde` sandbox starts **unlicensed** — the tool window (§2), hide/show,
the active group, and palette editing are locked until a trial is started from the registration
dialog (§3). To click through those sections without a trial, comment the descriptor out for the
session and revert it afterwards.

---

## 1. Swatch popup — creating a new group
`DONE`

Right-click any editor tab → **Tab Group…** opens the popup for every case below.

- [x] Popup shows a **"New Group"** caption above a row of all 8 preset colour dots (grey, blue,
      red, yellow, green, pink, purple, cyan), each legible against the popup background.
      *Automated: partial — the caption and all eight presets in order, each dot in its own colour
      (`TabGroupPopupUiTest` "the swatch row offers all eight presets, each dot in its own colour").
      Left for a human: legibility.*
- [x] Click away to another tab — the now-unselected, tinted tab is still legible, at roughly half
      the strength of the selected reading (the platform's ~50% blend — see README "Measured
      constraint").
      *Automated: partial — the half-strength blend is asserted on the painted tab (same test as
      above). Left for a human: legibility.*
- [x] Repeat the whole flow for **every one of the 8 presets** at least once — each should look
      distinct and legible, both selected and unselected.
      *Automated: partial — every preset starts a group with its own colours and paints them
      (`PaywallUiTest` "every preset still colours a new group while the licence is lapsed"); no two
      presets share a colour in either theme (`TabGroupPaletteTest`). Left for a human: legibility.*
- [x] Repeat with the IDE in **Light theme** as well as **Dark** — all 8 presets stay legible in
      both.
      *Automated: not automated — legibility is a visual judgement. That a tab switches to the light
      variant with the theme is automated (`TabColourUiTest` "switching theme retints a coloured tab
      straight away, both ways").*

## 2. PrismTabs tool window

Open it via its right-hand stripe button (or **View | Tool Windows | PrismTabs**).

- [x] Tool window icon and the Hide/Show eye icons render crisply (not blurry) at your display's
      scaling.
      *Automated: not automated — a visual judgement (`PrismTabsIconsTest` only checks the SVGs exist
      with the right stroke colour per theme).*

## 3. Licensing / paywall

This build is paid (see "Before you start"), so every case here runs against the real gate.

- [ ] From that dialog, **start the 14-day trial** — confirm paid features (tool window listing,
      hide/show, active-group auto-join, palette editing in Settings) all unlock.
      *Automated: partial — every paid feature unlocking once the gate reads licensed is covered in
      `PaywallUiTest`. Left for a human: starting a real trial, which needs a JetBrains Account.*
- [x] **Lapse across a restart:** while licensed/trialling, mark a group active, hide another
      group, and rename a palette preset in Settings. Fake the lapse (recipe below), then confirm
      paid features lock again (tool window, active-group auto-join, palette editing) with **no
      restart prompt** — tab colouring itself is free forever and keeps working throughout: losing
      the licence is not the same as disabling the plugin (see §4), the gate is just re-read the
      next time the affected code runs. Then **restart the IDE** yourself, only to confirm the
      lapsed state and your other changes persisted across it.

      No product code is registered on the Marketplace yet (`docs/release-readiness-gaps.md` item
      5), so there is no real trial or licence to remove locally — fake the licence facade instead,
      live, in the same `runIde` sandbox: **Find Action → "IDE Scripting Console"** (action id
      `IdeScriptingConsole`), not the Groovy plugin's own "Groovy Console" — that one runs your
      script in a separate JVM with no platform `Application`, so
      `LicensingFacade.setInstance` NPEs on `getMessageBus()`. Pick **Groovy** when it asks for a
      language, then paste a snippet as a single line and run it with Ctrl+Enter, which executes the
      current line or selection. Both use fully qualified names, so no `import` line is needed:
      - Unlock, simulating a subscription: `com.intellij.ui.LicensingFacade.setInstance(null)`.
      - Lapse, simulating expiry, no restart needed: `def f = new com.intellij.ui.LicensingFacade();
        f.confirmationStamps = new java.util.HashMap(); com.intellij.ui.LicensingFacade.setInstance(f)`.

      This only simulates entitlement the same way `IdeaFrame.simulateEntitled`/`simulateUnlicensed`
      do for the uiTest suite (the "facade hasn't answered yet" row of `PrismTabsLicense.decide`) —
      it cannot prove a real, JetBrains-signed stamp verifies. That's what the trial/real-key bullet
      above is for, once the plugin is actually listed.
      *Automated: partial — the lapse and the restart are each automated (`PersistenceUiTest`,
      `PaywallUiTest`). Left for a human: restarting the IDE to confirm what survives.*

## 4. Disabling and re-enabling the plugin (optional, advanced)

Only worth doing if you're validating a release candidate, not part of routine feature testing.
The platform never unloads a paid plugin in place, so on this build both steps ask for a restart;
the in-place unload is only possible on a build without the product descriptor, where
`PluginUnloadUiTest` covers it (it skips itself on the paid build).

- [ ] With at least one assigned group and an open tab showing it, go to **Settings | Plugins**,
      find PrismTabs, and **Disable** it. Confirm no error dialog appears and the IDE offers
      **Restart IDE**; after the restart the plugin is off and tabs carry no PrismTabs colour.
      *Automated: partial — that the platform requires a restart for the paid build
      (`PluginUnloadUiTest` "the platform requires a restart to disable the paid build"). Left for a
      human: the Plugins page and the restart.*
- [ ] **Re-enable** it from the same page and restart. Confirm groups, colours and tool window all
      come back correctly.
      *Automated: partial — that everything is read back from disk is covered by `PersistenceUiTest`
      and `TabGroupServiceTest`. Left for a human: the re-enable and the restart.*
- [ ] Watch `idea.log` (Help | Show Log) during both steps for any `io.prismtabs` `SEVERE`
      entries — there should be none.
      *Automated: not automated — both steps need a restart, which the suite does not perform.*

## 5. General polish (apply throughout, not a separate pass)

Per CLAUDE.md's UI standard — check these opportunistically while working through every section
above, not as a final once-over:

- [ ] All icons (stripe icon, Hide/Show eyes, colour swatches, Delete's waste-basket) render
      crisply at your display's scaling, in both themes.
      *Automated: not automated — a visual judgement.*
- [ ] No menu item, dialog title, or button label is truncated, misaligned, or uses the wrong
      punctuation convention (an ellipsis only where further input follows — see README "Checked
      against the JetBrains UI Guidelines").
      *Automated: partial — the exact wording of every label the tests touch is asserted, and the "…"
      of the strings with unit tests (`AddToActiveGroupLabelTest`, `CurrentGroupCaptionTest`,
      `MemberFileRowsTest`). Left for a human: truncation and alignment.*
- [ ] No stray `SEVERE` log entries attributable to `io.prismtabs` appear in `idea.log`
      during any of the above (some non-PrismTabs sandbox noise is expected and documented in
      README "Noisy but harmless log output" — don't confuse the two).
      *Automated: partial — every UI test fails on a PrismTabs `SEVERE` logged while it runs
      (`RemoteRobotExtension`, matching rules pinned in `PrismTabsErrorsTest`). Left for a human: the
      log during the manual cases above.*
