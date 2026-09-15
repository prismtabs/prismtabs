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
product does.

Two suite-wide checks back every automated bullet: a UI test fails if PrismTabs logs a `SEVERE`
to `idea.log` while it runs (`RemoteRobotExtension`), and `WindowsManualVerificationUiTest`, which
performs real restarts, stays opt-in and is **not** counted as coverage here.

**Before you start:** `plugin.xml` ships the `<product-descriptor … optional="true"/>`, so this is
a paid build and a fresh `runIde` sandbox starts **unlicensed** — the tool window (§6), hide/show,
the active group (§4) and palette editing (§9) are locked until a trial is started from the
registration dialog (§8). To click through those sections without a trial, comment the
descriptor out for the session and revert it afterwards.

---

## 1. Swatch popup — creating a new group

Right-click any editor tab → **Tab Group…** opens the popup for every case below.

- [x] Popup shows a **"New Group"** caption above a row of all 8 preset colour dots (grey, blue,
      red, yellow, green, pink, purple, cyan), each legible against the popup background.
      *Automated: partial — the caption and all eight presets in order, each dot in its own colour
      (`TabGroupPopupUiTest` "the swatch row offers all eight presets, each dot in its own colour").
      Left for a human: legibility.*
- [x] ~~Clicking a preset dot opens a **"New Tab Group"** name dialog, pre-filled with that colour's
      own label (e.g. clicking Blue pre-fills "Blue").~~
      *Automated: full — `TabGroupPopupUiTest` "clicking a preset dot prompts for a name,
      pre-filled with the colour's label".*
- [x] ~~The name field has focus immediately — no click needed before typing.~~
      *Automated: full — `TabGroupPopupUiTest` "the name prompt opens focused, so typing a name and
      pressing Enter creates the group" (focus owner asserted, then typed without a click).*
- [x] ~~Pressing **Enter** submits the dialog; clicking **OK** also submits it.~~
      *Automated: full — Enter in the test above; OK in "clicking a preset dot prompts for a
      name…".*
- [x] ~~Typing a different name (e.g. "Auth") before submitting creates the group under that name,
      not the colour's default label.~~
      *Automated: full — `TabGroupPopupUiTest` "the name prompt assigns the typed name, not the
      colour's".*
- [x] ~~**Cancelling** the dialog (Esc, or the X) creates no group and assigns nothing — right-click
      the same tab again and confirm no new group exists.~~
      *Automated: full — Cancel in "cancelling the name prompt creates nothing"; Esc and the title
      bar's close in "Escape and the title bar's close button both cancel the name prompt".*
- [x] ~~Right-click the **active** tab and create the group from it — right-clicking never changes
      tab selection (standard IDE behaviour), so this is the case that lands on the selected tab.
      After creation, its background tints with the chosen colour at full strength.~~
      *Automated: full — `TabGroupPopupUiTest` "a group made from the selected tab paints it at full
      strength, and at half once another tab is selected" (selection asserted; the painted tab
      matches the preset exactly).*
- [x] Click away to another tab — the now-unselected, tinted tab is still legible, at roughly half
      the strength of the selected reading (the platform's ~50% blend — see README "Measured
      constraint").
      *Automated: partial — the half-strength blend is asserted on the painted tab (same test as
      above). Left for a human: legibility.*
- [x] ~~Right-click a **different, non-active** tab and create the group from it — the right-clicked
      tab does not become active just because it was assigned; it tints at the unselected (~50%)
      strength, and the tab you were actually on stays selected and unaffected.~~
      *Automated: full — `TabGroupPopupUiTest` "a group made from a background tab leaves the
      selection alone and paints that tab at half strength".*
- [x] Repeat the whole flow for **every one of the 8 presets** at least once — each should look
      distinct and legible, both selected and unselected.
      *Automated: partial — every preset starts a group with its own colours and paints them
      (`PaywallUiTest` "every preset still colours a new group while the licence is lapsed"); no two
      presets share a colour in either theme (`TabGroupPaletteTest`). Left for a human: legibility.*
- [x] Repeat with the IDE in **Light theme** as well as **Dark** — all 8 presets stay legible in
      both.
      *Automated: not automated — legibility is a visual judgement. That a tab switches to the light
      variant with the theme is automated in §12.*
- [x] ~~Clicking the same preset dot twice (on two different files) and accepting the pre-filled
      name creates **two separate groups**, both named after the colour — nothing is merged into one
      shared "Blue" group and nothing is renumbered (a deliberate design choice — see README "Two
      design choices").~~
      *Automated: full — `TabGroupPopupUiTest` "accepting the same preset on two files makes two
      groups, not one".*

## 2. Swatch popup — adding to an existing group

- [x] ~~Once at least one group exists, right-clicking an unassigned tab shows an **"Add to
      Group…"** row (nested list) below the swatch row's separator.~~
      *Automated: full — `TabGroupPopupUiTest` "with no group active the one-click row is not
      offered" and "moving a group's last member elsewhere…".*
- [x] ~~Opening it lists every existing group by name, each with its own colour dot.~~
      *Automated: full — `TabGroupPopupUiTest` "Add to Group lists every group by name, each with its
      own colour dot".*
- [x] ~~Picking a group assigns the tab to it — the tab immediately tints with that group's colour.~~
      *Automated: full — `TabGroupPopupUiTest` "picking a group from Add to Group tints the tab at
      once" (strip colour and painted tab).*
- [x] ~~If a group is marked **active** (see §4), the popup shows an extra row directly under the
      swatch separator: **`Add to Group "<name>"`** with that group's own dot — a one-click
      shortcut that only appears for a currently **unassigned** file.~~
      *Automated: full — `TabGroupPopupUiTest` "an already-open file joins the active group from the
      popup in one click".*
  - [x] ~~Clicking that row assigns the file to the active group in one click.~~
        *Automated: full — same test.*
  - [x] ~~The row disappears once the file is already assigned to any group.~~
        *Automated: full — `TabGroupPopupUiTest` "the shortcut is not offered for a file that is
        already in a group".*
  - [x] ~~The row disappears if no group is currently active.~~
        *Automated: full — `TabGroupPopupUiTest` "with no group active the one-click row is not
        offered".*
- [x] ~~A file already in a group instead shows **"In group: `<name>`"** as a caption, with
      "Add to Group…" available underneath to move it to a different group.~~
      *Automated: full — `TabGroupPopupUiTest` "the popup captions which group the file is already
      in", "moving a group's last member elsewhere…".*

## 3. Swatch popup — removing an assignment

- [ ] ~~On a tab that's already assigned to a group, right-click → the popup shows an
      **"In group: `<name>`"** caption and a **"Remove from Group"** row.~~
      *Automated: full — `TabGroupPopupUiTest` "after Remove from Group the tab loses its colour and
      the popup offers a new group again".*
- [ ] ~~Clicking it clears the tab's tint back to the platform default — immediately, no
      confirmation, unless it's the group's last member (see §7 — a different dialog fires
      instead).~~
      *Automated: full — same test (the strip holds no colour afterwards, no dialog) and "Remove from
      Group clears an assignment made via the popup".*
- [ ] ~~Right-clicking the same tab afterwards shows no "In group" caption and no "Remove from
      Group" row — the swatch/creation flow is offered again as if the tab had never been
      assigned.~~
      *Automated: full — same test.*

## 4. Manage Groups — rename, recolour, active group

Once at least one group exists, the popup gains a **"Manage Groups…"** entry (nested: pick a
group, then pick an action).

- [ ] ~~**Rename…** opens a small dialog with a "Name:" text field pre-filled with the current name;
      typing a new name and pressing Enter (or OK) renames it, and **every** member takes the new
      name wherever the group is named — the "In group:" caption, Manage Groups, the tool window —
      while its tint stays as it was.~~
      *Automated: full — `TabGroupPopupUiTest` "Rename starts from the current name, and Enter renames
      every member without recolouring"; OK in "an active group colours newly opened files, and
      rename recolour follow every member".*
- [ ] ~~Cancelling Rename leaves the name unchanged.~~
      *Automated: full — `TabGroupPopupUiTest` "cancelling Rename keeps the old name, whatever was
      typed".*
- [ ] ~~**Recolour…** opens a nested list of the 8 presets; picking one **applies immediately** (no
      further confirmation) and repaints **every member tab**, including members that have no tab
      open (they show the new colour when opened).~~
      *Automated: full — `TabGroupPopupUiTest` "Recolour offers the eight presets and repaints every
      member, open or not".*
- [ ] ~~**Mark as Active Group** — pick a group, mark it active; the entry's label flips to
      **"Clear Active Group"** the next time you open the menu for that same group.~~
      *Automated: full — `TabGroupPopupUiTest` "marking a group active turns its item into Clear
      Active Group".*
- [ ] ~~With a group active, opening (creating) a **new file** in the IDE automatically tints it
      with the active group's colour, with no popup interaction at all.~~
      *Automated: full — `TabGroupPopupUiTest` "a file opened while a group is active joins and takes
      its colour, and none does once it is cleared".*
- [ ] ~~With a group active, an already-open, unassigned tab does *not* retroactively join it —
      auto-join only fires on `fileOpened`, not on marking active. (Use §2's one-click
      `Add to Group "<name>"` row to join it by hand instead.)~~
      *Automated: full — `TabGroupPopupUiTest` "an already-open file joins the active group from the
      popup in one click".*
- [ ] ~~Only **one** group can be active at a time — marking a second group active clears the first.~~
      *Automated: full — `TabGroupPopupUiTest` "marking a second group active clears the first".*
- [ ] ~~**Clear Active Group** turns auto-join off; subsequently opened files are no longer tinted
      automatically.~~
      *Automated: full — `TabGroupPopupUiTest` "a file opened while a group is active joins and takes
      its colour, and none does once it is cleared".*
- [ ] ~~Mark a group active, then **restart the sandbox IDE** — confirm the group is *not* still
      marked active (this is deliberate and session-scoped — see README "Why active group isn't
      persisted").~~
      *Automated: full — `PersistenceUiTest` (the saved state loaded into a fresh service has no
      active group while the running IDE still has one) and `TabGroupServiceTest` "a restart never
      brings back the active group".*
- [ ] ~~**Files…** (only shown when the group has at least one member) opens a **fresh top-level
      chooser** titled **"Files in `<name>`"**, listing every member by name with its
      project-relative directory in grey, sorted by directory then name.~~
      *Automated: full — `TabGroupPopupUiTest` "the file list is titled after its group and sorts its
      greyed directories".*
  - [ ] ~~Clicking a listed file reopens it (or focuses it if already open) as a tab.~~
        *Automated: full — "the file list names every member, tab open or not, and opening one
        brings it back"; "choosing a member that is already open in the file list selects its
        tab…".*
  - [ ] ~~A member whose open tab is currently closed still appears in the list.~~
        *Automated: full — "the file list names every member, tab open or not…".*
  - [ ] ~~A member whose underlying file was deleted while the IDE was **closed** (so no VFS event
        ever fired) is shown as **"missing"**, not silently dropped — choosing it offers to unassign
        it.~~
        *Automated: full — "a member whose file is gone is listed as missing, and can be unassigned
        from there".*
  - [ ] ~~"Files…" does **not** appear for a group with zero members.~~
        *Automated: full — "an emptied group offers no file list".*

## 5. Deleting a group

- [ ] ~~**Manage Groups…** → a group → **Delete Group** opens a confirmation dialog titled
      **"Delete Tab Group"** with **"Delete"** / **"Cancel"** buttons (not generic Yes/No).~~
      *Automated: full — `TabGroupPopupUiTest` "Delete Group from the popup asks with Delete and
      Cancel, and Cancel keeps the group".*
- [ ] ~~Confirming deletes the group; every member tab loses its tint; the group no longer appears
      anywhere (popup, tool window).~~
      *Automated: full — `TabGroupPopupUiTest` "confirming Delete Group untints every member and drops
      the group from the popup and the tool window".*
- [ ] ~~Cancelling leaves the group and its members untouched.~~
      *Automated: full — "Delete Group from the popup asks with Delete and Cancel, and Cancel keeps
      the group".*
- [ ] ~~Delete a **hidden** group that has remembered (closed) tabs (§6) — confirm the dialog adds a
      second line warning that "This group is hidden" and that its remembered tab(s) "will be
      forgotten", rather than only the generic "Its files will be unassigned." message.~~
      *Automated: full — `TabGroupPopupUiTest` "deleting a hidden group warns that the tabs it would
      reopen will be forgotten" (read off the rendered dialog); wording variants in
      `DeleteConfirmationMessageTest`.*

## 6. PrismTabs tool window

Open it via its right-hand stripe button (or **View | Tool Windows | PrismTabs**).

- [ ] ~~The window lists every existing group as a tree row: colour dot, name, member count
      (`N files`), and a `· active` / `· hidden` suffix where applicable.~~
      *Automated: full — `TabGroupsToolWindowUiTest` "rows carry the group's dot, each file's type
      icon, a greyed directory, and bold for an open tab"; suffixes throughout the class.*
- [ ] ~~Expanding a group row shows one child row per member: file-type icon, file name, greyed
      relative directory.~~
      *Automated: full — same test.*
- [ ] ~~An **open** member's row renders in **bold**; a **closed** member renders in regular weight
      — neither greyed nor hidden.~~
      *Automated: full — same test (bold, then regular once the tab closes).*
- [ ] ~~**Double-clicking a member row** opens that file as a tab.~~
      *Automated: full — "a group row expands to its files, which stay listed with every tab
      closed".*
- [ ] ~~**Double-clicking a group row** toggles Hide/Show for that group (not expand/collapse — the
      tree's own expand toggle is on the disclosure arrow / arrow keys / Enter only).~~
      *Automated: full — same test.*
- [ ] ~~Pressing **Enter** on a selected group row **expands or collapses** it — it never hides or
      shows the group.~~
      *Automated: full — `TabGroupsToolWindowUiTest` "Enter expands and collapses a group row without
      hiding it".*
- [ ] ~~**Hide Group** (toolbar button or right-click context menu) closes every open tab belonging
      to that group and marks the row **"hidden"**.~~
      *Automated: full — toolbar in "the toolbar's eye hides a group and shows it again"; context menu
      in "Hide Group and Show Group in the context menu close the group's tabs and reopen them in
      order".*
- [ ] ~~**Show Group** reopens the tabs that were open when it was last hidden, in their original
      order.~~
      *Automated: full — same two tests.*
- [ ] ~~A group with **all its tabs manually closed** (via Ctrl+W, not "Hide Group") also reads as
      "hidden" — hidden state is *derived* from what's on the strip, not a stored flag (confirm by
      closing one tab at a time and watching the row transition).~~
      *Automated: full — "hidden follows the tab strip — reopening a member un-hides a group, closing
      the last tab hides it", "a group hidden by closing its tabs one at a time comes back whole".*
- [ ] ~~Reopening just **one** closed member (double-click its row) is enough to un-hide the group —
      the row's "hidden" suffix disappears immediately.~~
      *Automated: full — "hidden follows the tab strip…".*
- [ ] ~~**Active Group** toggle (toolbar and context menu) — right-click a group row, toggle Active
      on; confirm it marks that group active and clears any other group's active state, matching
      §4's popup-driven flow.~~
      *Automated: full — `TabGroupsToolWindowUiTest` "the Active Group toggle in the context menu and
      the toolbar moves the active mark between groups".*
- [ ] ~~**Rename…** / **Recolour…** / **Delete Group** are also available from the tool window's
      toolbar and context menu, and behave identically to §4/§5's popup-driven versions.~~
      *Automated: full — `TabGroupsToolWindowUiTest` "Rename, Recolour and Delete work from the tool
      window's context menu and toolbar".*
- [ ] ~~**Right-click on a member (child) row** shows **Remove from Group** below the group's own
      actions (which act on the row's parent group), and clicking it unassigns that one file, with
      **no confirmation** (unless it empties the group — see §7).~~
      *Automated: full — "a member row offers Remove from Group, and a group row does not"; "a group
      row expands to its files…" (the parent group's actions enabled on a member row).*
- [ ] ~~**Right-click on a group row** does **not** show "Remove from Group" (a group row has no
      single file to remove).~~
      *Automated: full — "a member row offers Remove from Group, and a group row does not".*
- [ ] ~~**Multi-select several member rows** (Ctrl-click on Linux/Windows, ⌘-click on macOS) across
      the **same** group, right-click → the item reads **"Remove N Files from Group"**, and
      confirms the count matches the selection.~~
      *Automated: full — "multi-selected files are removed together, and the item states the
      count".*
- [ ] ~~Multi-select member rows spanning **two different groups**, right-click → the item reads
      **"Remove N Files from Their Groups"** (plural "Their"), and the five group-only actions
      (Rename/Recolour/Delete/Hide–Show/Active) are all **greyed out** since the selection doesn't
      resolve to one group.~~
      *Automated: full — "a selection spanning two groups still removes, greys the group actions, and
      asks once" (all five asserted).*
- [ ] ~~**Right-clicking an unselected row selects it first** (matching the Project view's own
      convention).~~
      *Automated: full — "right-clicking a group row acts on that row, with nothing selected and
      straight after a delete".*
- [ ] ~~**Right-clicking inside an existing multi-selection keeps the whole selection** rather than
      collapsing it to the clicked row.~~
      *Automated: full — "multi-selected files are removed together…".*
- [ ] ~~**Deleting the selected group** moves the selection onto the group that took its place,
      rather than leaving nothing selected (which would grey the whole toolbar).~~
      *Automated: full — "right-clicking a group row acts on that row…".*
- [ ] ~~The selected file row feeds the platform's own file actions — try **F4** ("Jump to Source"),
      **Select in Project View**, and **Copy Path** on a member row and confirm they all work.~~
      *Automated: full — `TabGroupsToolWindowUiTest` "a member row feeds Jump to Source, Copy Path and
      Select in Project View".*
- [ ] ~~**Expansion state and selection both survive a refresh** — expand a group, recolour it from
      elsewhere (e.g. the popup), and confirm the group is still expanded and still selected
      afterwards.~~
      *Automated: full — `TabGroupsToolWindowUiTest` "a group recoloured from a tab's popup stays
      expanded and selected in the tree".*
- [ ] Tool window icon and the Hide/Show eye icons render crisply (not blurry) at your display's
      scaling.
      *Automated: not automated — a visual judgement (`PrismTabsIconsTest` only checks the SVGs exist
      with the right stroke colour per theme).*

## 7. Empty groups (three ways to trigger the prompt)

A group becomes "empty" only when its **last assigned file** loses its assignment — closing tabs
alone never empties a group.

- [ ] ~~**"Remove from Group" on a group's last member** (via the tab popup §3, or the tool window
      §6) opens an **"Empty Tab Group"** dialog: `"<name>" has no tabs left. Delete the group?`
      with **"Delete Group"** (focused default) and **"Keep"** buttons.~~
      *Automated: full — `TabGroupPopupUiTest` "removing a group's last member offers to delete it"
      (message, both buttons, Delete Group as the default button); tool window route in
      `TabGroupsToolWindowUiTest` "a group kept after its last file is removed…".*
  - [ ] ~~Choosing **Delete Group** removes the group entirely.~~
        *Automated: full — same popup test.*
  - [ ] ~~Choosing **Keep** leaves the group in place, now listed as **"0 files"** in the tool
        window, with its own Delete Group action still available later.~~
        *Automated: full — `TabGroupsToolWindowUiTest` "a group kept after its last file is removed
        lists 0 files and still offers Delete Group".*
- [ ] ~~**Moving a group's last member to a different group** ("Add to Group…", or clicking a preset
      dot to start a brand-new group from that file) triggers the same prompt for the group it
      just left.~~
      *Automated: full — "moving a group's last member elsewhere offers to delete the group it left";
      "starting a new group from a group's last member offers to delete the group it left".*
- [ ] ~~**Deleting the last member's file on disk** (via the Project tool window, using its normal
      delete confirmation) also triggers the prompt — non-modally, after the deletion completes,
      not stacked on top of the platform's own delete dialog.~~
      *Automated: full — `EmptyGroupOnDiskDeleteUiTest` "deleting a group's last member from the
      project view offers to delete the group".*
- [ ] ~~**Deleting an entire directory** that empties **two** groups at once raises a **single**
      **"Empty Tab Groups"** (plural) dialog naming both groups, not two separate dialogs in a
      queue.~~
      *Automated: full — `EmptyGroupOnDiskDeleteUiTest` "deleting a directory empties several groups
      and offers them in one dialog".*
- [ ] ~~Removing a file that leaves a group with at least one member **remaining** does **not**
      trigger any prompt — confirm the negative case explicitly.~~
      *Automated: full — asserted in "Remove from Group clears an assignment made via the popup" and
      "deleting one of several members removes its assignment and offers nothing".*
- [ ] ~~An empty group kept via "Keep" survives an IDE restart, still listed as 0 files (no dialog
      fires again for it).~~
      *Automated: full — `PersistenceUiTest` (the emptied group is saved and loads back with no
      members; loading raises no prompt, which would otherwise block the test's own script) and
      `TabGroupServiceTest` "a restart keeps a group that was left with no members".*

## 8. Licensing / paywall

This build is paid (see "Before you start"), so every case here runs against the real gate.

- [ ] ~~**Unlicensed start.** Launch with no licence. The plugin stays **enabled** — it must not
      appear in `config/disabled_plugins.txt` and must not be disabled in the Plugins list.~~
      *Automated: full — `PaywallUiTest` "a paid build started without a licence comes up enabled
      rather than disabled" (the sandbox is a cold, unlicensed start of the paid build).*
- [ ] ~~With no licence, the tool window shows an **upsell panel** instead of the group list, with
      wording along the lines of "Start the free trial or activate a subscription" rather than an
      empty/broken-looking list.~~
      *Automated: full — `PaywallUiTest` "the tool window is replaced by an upsell, and comes back
      when the licence does".*
- [ ] ~~With no licence, **free-tier features still work fully** from the tab right-click menu:
      creating unlimited groups, assigning all 8 presets, renaming, recolouring, deleting,
      "Files…", and "Remove from Group" all work with no upsell interruption.~~
      *Automated: full — `PaywallUiTest` "the free tier still colours tabs and manages groups while
      the licence is lapsed" and "every preset still colours a new group while the licence is
      lapsed" (both assert the registration dialog was never asked for).*
- [ ] ~~With no licence, attempting a **paid** action — marking a group active, or Hide/Show from
      the tool window (if reachable at all) — raises the **registration dialog** rather than
      silently doing nothing or crashing.~~
      *Automated: full — `PaywallUiTest` "a refused paid action raises the subscription dialog, naming
      what was refused".*
- [ ] From that dialog, **start the 30-day trial** — confirm paid features (tool window listing,
      hide/show, active-group auto-join, palette editing in Settings) all unlock.
      *Automated: partial — every paid feature unlocking once the gate reads licensed is covered in
      `PaywallUiTest`. Left for a human: starting a real trial, which needs a JetBrains Account.*
- [ ] ~~Note the tool window only picks up the new licence state the **next time it's shown** or on
      the next group change — not instantly when the dialog closes. Confirm that's what actually
      happens (re-open the tool window, or make a group change, to see it flip).~~
      *Automated: full — re-showing in "the tool window is replaced by an upsell…"; a group change,
      and nothing before it, in "a subscription started while the locked tool window is open shows
      at the next group change".*
- [ ] **Lapse across a restart:** while licensed/trialling, mark a group active, hide another
      group, and rename a palette preset (§9). Remove the licence (or let the trial state reset in
      your test setup) and **restart the IDE**.
      *Automated: partial — the lapse and the restart are each automated (sub-bullets below). Left for
      a human: removing a real licence and restarting in one sitting.*
  - [ ] ~~The plugin still loads, into the free tier — not disabled.~~
        *Automated: full — the unlicensed-start test above.*
  - [ ] ~~Groups, their colours, and the renamed palette preset all survived.~~
        *Automated: full — `PersistenceUiTest`, and `PaywallUiTest` "groups, hidden state and a saved
        palette all survive a lapse…".*
  - [ ] ~~A newly opened file does **not** auto-join the previously-active group.~~
        *Automated: full — the active group is not restored (§4), and auto-join is refused while
        unlicensed (`PaywallUiTest` "an active group stops auto-joining newly opened files once the
        licence lapses").*
  - [ ] ~~The now-locked tool window offers **"Reopen the tabs of N hidden groups"** (or equivalent
        wording) and clicking it actually reopens the previously-hidden group's tabs — nothing is
        left recoverable only by resubscribing.~~
        *Automated: full — `PaywallUiTest` "a lapsed subscription can still reopen the tabs of a
        hidden group"; the remembered tabs survive a restart (`PersistenceUiTest`).*
- [ ] ~~"Clearing" an active group (not setting one) still works even while unlicensed/lapsed —
      confirm it's not itself gated.~~
      *Automated: full — `PaywallUiTest` "an active group stops auto-joining newly opened files once
      the licence lapses".*

## 9. Settings — palette editing

**Settings/Preferences | Tools | PrismTabs** (also reachable via ⌘, on macOS).

- [ ] ~~The page lists all 8 presets, each with an editable name field and a light/dark colour
      swatch picker.~~
      *Automated: full — `TabGroupPaletteConfigurableUiTest` "the page has a name field and a light
      and a dark colour picker for each of the eight presets".*
- [ ] ~~Edit a preset's **name**, click **Apply** — the swatch row shows the new name on that preset's
      dot.~~
      *Automated: full — `TabGroupPaletteConfigurableUiTest` "a preset edited in Settings is what the
      swatch row offers next, while existing groups keep their colour" (typed into the real Settings
      dialog, Apply, OK).*
- [ ] ~~Edit a preset's **light** and **dark** colours, Apply — the swatch row's dot takes the new
      colour, and a group started from that preset afterwards gets both new colours (the dark
      variant showing in a dark theme, the light in a light one). A group made from that preset
      *before* the edit keeps its own colour, as the page itself says.~~
      *Automated: full — same test; the light/dark resolution with the theme in `TabColourUiTest`
      "switching theme retints a coloured tab straight away, both ways".*
- [ ] ~~Click **Restore Defaults** — confirm every customised field (name and both colours) reverts
      to the shipped default, and this is reflected once **Apply**'d.~~
      *Automated: full — `TabGroupPaletteConfigurableUiTest` "Restore Defaults reverts a preset's name
      and both its colours".*
- [ ] ~~Cancelling the Settings dialog after an edit (without Apply) discards the change.~~
      *Automated: full — `TabGroupPaletteConfigurableUiTest` "cancelling the Settings dialog discards
      an edit" (real dialog; the edit is confirmed pending before Cancel).*
- [ ] ~~Rename a preset, Apply, then **restart the IDE** — confirm the customisation persisted.~~
      *Automated: full — `PersistenceUiTest`; `TabGroupPaletteServiceTest` "a restart brings back a
      renamed and recoloured preset".*
- [ ] ~~This page requires an active subscription/trial (§8) once licensing is enabled — confirm the
      page is locked or shows an upsell when unlicensed, per the free/paid split.~~
      *Automated: full — `PaywallUiTest` "the palette settings page is read-only when locked, and
      editable again once the subscription returns".*

## 10. Persistence across restarts

Do all of the following, then fully restart the sandbox IDE (quit and relaunch, not just reopen a
project), and confirm every item survived:

- [ ] ~~Group names, colours, and file membership.~~
      *Automated: full — `PersistenceUiTest` (saved by the platform, loaded into a fresh service);
      `TabGroupServiceTest` "a restart brings back every group's name, colours and members".*
- [ ] ~~A palette customisation (renamed/recoloured preset).~~
      *Automated: full — `PersistenceUiTest`; `TabGroupPaletteServiceTest` "a restart brings back a
      renamed and recoloured preset".*
- [ ] ~~A group's hidden state is **not** independently stored, but its *remembered* tab set is: a
      group whose tabs are all closed reads as "hidden" purely because nothing is open, and
      **Show Group** correctly reopens the tabs that were open before the restart (not before the
      last hide).~~
      *Automated: full — the remembered tabs load back in order (`PersistenceUiTest`,
      `TabGroupServiceTest` "a restart brings back the tabs a group remembers, in order"); hidden
      derived from the strip and Show reopening the remembered tabs in `TabGroupsToolWindowUiTest`.*
- [ ] ~~The active group is correctly **cleared** after a restart (never restored — see §4).~~
      *Automated: full — see §4.*

## 11. Rename / move remapping

- [ ] ~~Assign a file to a group, then **rename the file** (Project view, **Refactor | Rename** —
      Shift+F6). Confirm the tab keeps its group's tint after the rename and the tool window's
      member row shows the new name.~~
      *Automated: full — `FileRemapUiTest` "a file renamed from the Project view keeps its group, its
      tab colour and its row in the tool window" (Shift+F6 and the real Rename dialog).*
- [ ] ~~Assign a file to a group, then **move it to a different directory** (drag in the Project
      view, or Refactor | Move). Confirm the assignment and tint survive, and the tool window shows
      the file's new relative directory.~~
      *Automated: full — `FileRemapUiTest` "a file moved to another directory keeps its group and
      colour, and the tool window shows where it went" (moved through the VFS, which both gestures
      end in).*
- [ ] ~~**Move (or rename) an entire directory** containing several assigned files. Confirm every
      file nested under it keeps its assignment.~~
      *Automated: full — `FileRemapUiTest` "renaming or moving a directory carries every assigned file
      under it".*
- [ ] ~~A group's **remembered hidden-tab list** (§10) also follows a rename/move — hide a group,
      rename one of its remembered-but-closed members' file, then Show Group and confirm the
      renamed file reopens correctly.~~
      *Automated: full — `FileRemapUiTest` "a hidden group reopens a remembered tab under its new name
      after a rename".*

## 12. Editor tab colour provider interplay

- [ ] ~~Assign a colour to a file that also matches one of the IDE's built-in **File Colors** scopes
      (e.g. a file outside the project, which gets "Non-Project Files", or one matching a custom
      scope you've set up with its own File Color). Confirm the **PrismTabs** colour wins — this is
      the deliberate `order="first"` policy (README "order=first is mandatory"), matching how Chrome
      treats an explicit tab colour as an override.~~
      *Automated: full — `TabColourUiTest` "a group colour wins over the platform's File Colors".*
- [ ] ~~Assign a colour to a file, then toggle the IDE between light and dark theme (Settings |
      Appearance) without restarting — confirm the tab retints correctly on the next repaint with
      no stale colour and no restart needed.~~
      *Automated: full — `TabColourUiTest` "switching theme retints a coloured tab straight away, both
      ways" (painted tab, both directions).*

## 13. Disabling and re-enabling the plugin (optional, advanced)

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
      *Automated: partial — that everything is read back from disk is §10. Left for a human: the
      re-enable and the restart.*
- [ ] Watch `idea.log` (Help | Show Log) during both steps for any `io.prismtabs` `SEVERE`
      entries — there should be none.
      *Automated: not automated — both steps need a restart, which the suite does not perform.*

## 14. General polish (apply throughout, not a separate pass)

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
