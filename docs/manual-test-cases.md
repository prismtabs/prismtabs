# Manual test cases

A functional checklist for clicking through every PrismTabs feature by hand in a sandbox IDE
(`./gradlew runIde`). This is the *what* — every behaviour the product promises, one case per
row/bullet, organised by feature area rather than by OS. It complements, and deliberately
duplicates none of the depth of, [`docs/cross-platform-manual-verification.md`](cross-platform-manual-verification.md),
which is the *where* — the same core flow re-run per OS to catch platform chrome, HiDPI, and
input differences. Run this doc first on whatever machine is convenient; run that one when doing a
release pass on Windows and macOS (Linux is covered by day-to-day development).

Automated coverage exists for most of what is below (`./gradlew test` for pure logic,
`./gradlew uiTest` for the `remote-robot` suite — see engineering notes "UI testing"). Note the OS, IDE build,
and theme (dark/light) you test with, and be picky — anything that looks off is worth flagging even
if it's not what you set out to test (see CLAUDE.md's UI standards).

**Automation annotations.** Every bullet carries an *Automated* note saying what the test suite
actually asserts, checked against the test bodies rather than their names. **Struck-through
bullets are fully automated and need no manual pass.** Three verdicts:
- **full** — a test drives the real behaviour and asserts every claim in the bullet. Struck through.
- **partial** — some of the bullet is asserted; the note says which part is left for a human eye.
- **not automated** — nothing asserts it, usually because the claim is a visual judgement
  (legibility, crispness) or needs a real IDE restart.

History: a first pass on 2026-09-14 over-credited several bullets (bold member rows, Rename and
Recolour from the tool window, rename/recolour/delete from the popup); on 2026-09-15 every bullet was re-audited, tests were written for every gap that
could be automated, and each new test was mutation-checked — the product broken on purpose to
confirm the test then fails. Where the checklist described behaviour the product does not have
(Enter on a group row, the member-row context menu, palette edits recolouring existing groups,
F2 as the rename key), the bullet was corrected to what the
product does. Sections that ended up fully automated (swatch add/remove, Manage Groups, group
deletion, empty-group prompts, palette settings, persistence, rename/move remapping, and the
colour-provider interplay) were removed outright, and every remaining section had its own
fully-automated (struck-through) bullets dropped too, rather than kept as dead weight; their
coverage lives in the test suite named inline throughout this doc.

Two suite-wide checks back every automated bullet: a UI test fails if PrismTabs logs a `SEVERE`
to `idea.log` while it runs (`RemoteRobotExtension`), and `WindowsManualVerificationUiTest`, which
performs real restarts, stays opt-in and is **not** counted as coverage here.

---

## 1. Swatch popup — creating a new group

Right-click any editor tab → **Tab Group…** opens the popup for every case below.

- [ ] Popup shows a **"New Group"** caption above a row of all 8 preset colour dots (grey, blue,
      red, yellow, green, pink, purple, cyan), each legible against the popup background.
      *Automated: partial — the caption and all eight presets in order, each dot in its own colour
      (`TabGroupPopupUiTest` "the swatch row offers all eight presets, each dot in its own colour").
      Left for a human: legibility.*
- [ ] Click away to another tab — the now-unselected, tinted tab is still legible, at roughly half
      the strength of the selected reading (the platform's ~50% blend — see engineering notes "Measured
      constraint").
      *Automated: partial — the half-strength blend is asserted on the painted tab (same test as
      above). Left for a human: legibility.*
- [ ] Repeat the whole flow for **every one of the 8 presets** at least once — each should look
      distinct and legible, both selected and unselected.
      *Automated: partial — every preset starts a group with its own colours and paints them
      (`TabGroupPopupUiTest` "every preset starts a group and colours the tab"); no two
      presets share a colour in either theme (`TabGroupPaletteTest`). Left for a human: legibility.*
- [ ] Repeat with the IDE in **Light theme** as well as **Dark** — all 8 presets stay legible in
      both.
      *Automated: not automated — legibility is a visual judgement. That a tab switches to the light
      variant with the theme is automated (`TabColourUiTest` "switching theme retints a coloured tab
      straight away, both ways").*

## 2. PrismTabs tool window

Open it via its right-hand stripe button (or **View | Tool Windows | PrismTabs**).

- [ ] Tool window icon and the Hide/Show eye icons render crisply (not blurry) at your display's
      scaling.
      *Automated: not automated — a visual judgement (`PrismTabsIconsTest` only checks the SVGs exist
      with the right stroke colour per theme).*

## 3. Disabling and re-enabling the plugin (optional, advanced)

Only worth doing if you're validating a release candidate, not part of routine feature testing.

- [ ] With at least one assigned group and an open tab showing it, go to **Settings | Plugins**,
      find PrismTabs, and **Disable** it. Confirm no error dialog appears, the IDE does not ask
      for a restart, and the tab loses its PrismTabs colour straight away.
      *Automated: partial — `PluginUnloadUiTest` disables the plugin in place, asserts its class
      loader is released and the tab colours clear, and asserts the platform needs no restart
      ("the platform can disable the plugin without a restart"). Left for a human: the Plugins
      page itself.*
- [ ] **Re-enable** it from the same page. Confirm groups, colours and the tool window all come
      back correctly, again without a restart.
      *Automated: partial — `PluginUnloadUiTest` covers the in-place round trip (a group created
      just before the disable survives it, and the tabs left open get their colour back). Left for
      a human: the Plugins page itself.*
- [ ] Watch `idea.log` (Help | Show Log) during both steps for any `io.prismtabs` `SEVERE`
      entries — there should be none.
      *Automated: partial — `RemoteRobotExtension` fails `PluginUnloadUiTest` on any PrismTabs
      `SEVERE` logged while it runs. Left for a human: the same check on the Plugins page route.*

## 4. General polish (apply throughout, not a separate pass)

Per CLAUDE.md's UI standard — check these opportunistically while working through every section
above, not as a final once-over:

- [ ] All icons (stripe icon, Hide/Show eyes, colour swatches, Delete's waste-basket) render
      crisply at your display's scaling, in both themes.
      *Automated: not automated — a visual judgement.*
- [ ] No menu item, dialog title, or button label is truncated, misaligned, or uses the wrong
      punctuation convention (an ellipsis only where further input follows — see engineering notes "Checked
      against the JetBrains UI Guidelines").
      *Automated: partial — the exact wording of every label the tests touch is asserted, and the "…"
      of the strings with unit tests (`AddToActiveGroupLabelTest`, `CurrentGroupCaptionTest`,
      `MemberFileRowsTest`). Left for a human: truncation and alignment.*
- [ ] No stray `SEVERE` log entries attributable to `io.prismtabs` appear in `idea.log`
      during any of the above (some non-PrismTabs sandbox noise is expected and documented in
      engineering notes "Noisy but harmless log output" — don't confuse the two).
      *Automated: partial — every UI test fails on a PrismTabs `SEVERE` logged while it runs
      (`RemoteRobotExtension`, matching rules pinned in `PrismTabsErrorsTest`). Left for a human: the
      log during the manual cases above.*
