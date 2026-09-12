# Manual test cases

A functional checklist for clicking through every PrismTabs feature by hand in a sandbox IDE
(`./gradlew runIde`). This is the *what* — every behaviour the product promises, one case per
row/bullet, organised by feature area rather than by OS. It complements, and deliberately
duplicates none of the depth of, [`docs/cross-platform-manual-verification.md`](cross-platform-manual-verification.md),
which is the *where* — the same core flow re-run per OS to catch platform chrome, HiDPI, and
native-dialog differences. Run this doc first on whatever machine is convenient; run that one when
doing a release pass across Windows/macOS/Linux.

Automated coverage already exists for nearly everything below (`./gradlew test` for pure logic,
`./gradlew uiTest` for the `remote-robot` suite — see README "UI testing"). Testing it by hand too
is still worth doing: a human eye catches misalignment, awkward wording, and "technically correct
but looks wrong" in a way an assertion doesn't. Note the OS, IDE build, and theme (dark/light) you
test with, and be picky — anything that looks off is worth flagging even if it's not what you set
out to test (see CLAUDE.md's UI standards).

**Before you start:** `plugin.xml`'s `<product-descriptor>` is currently commented out in this
working tree (`git diff` shows it), which means paid-feature gating is inert and every feature
below runs unlicensed. Section 8 (licensing) needs it **uncommented** to be tested at all — restore
it, `./gradlew runIde`, and revert afterwards unless you intend to leave it enabled for a release
build.

---

## 1. Swatch popup — creating a new group

Right-click any editor tab → **Tab Group…** opens the popup for every case below.

- [ ] Popup shows a **"New Group"** caption above a row of all 8 preset colour dots (grey, blue,
      red, yellow, green, pink, purple, cyan), each legible against the popup background.
- [ ] Clicking a preset dot opens a **"New Tab Group"** name dialog, pre-filled with that colour's
      own label (e.g. clicking Blue pre-fills "Blue").
- [ ] The name field has focus immediately — no click needed before typing.
- [ ] Pressing **Enter** submits the dialog; clicking **OK** also submits it.
- [ ] Typing a different name (e.g. "Auth") before submitting creates the group under that name,
      not the colour's default label.
- [ ] **Cancelling** the dialog (Esc, or the X) creates no group and assigns nothing — right-click
      the same tab again and confirm no new group exists.
- [ ] After creation, the tab's background tints with the chosen colour at full strength (it's the
      selected tab).
- [ ] Click away to another tab — the now-unselected, tinted tab is still legible, at roughly half
      the strength of the selected reading (the platform's ~50% blend — see README "Measured
      constraint").
- [ ] Repeat the whole flow for **every one of the 8 presets** at least once — each should look
      distinct and legible, both selected and unselected.
- [ ] Repeat with the IDE in **Light theme** as well as **Dark** — all 8 presets stay legible in
      both.
- [ ] Clicking the same preset dot twice (on two different files) creates **two separate groups**,
      each with its own auto-incremented/duplicate name if accepted as-is — confirm it does *not*
      silently merge into one shared "Blue" group (this was a deliberate design change — see
      README "Two design choices").

## 2. Swatch popup — adding to an existing group

- [ ] Once at least one group exists, right-clicking an unassigned tab shows an **"Add to
      Group…"** row (nested list) below the swatch row's separator.
- [ ] Opening it lists every existing group by name, each with its own colour dot.
- [ ] Picking a group assigns the tab to it — the tab immediately tints with that group's colour.
- [ ] If a group is marked **active** (see §4), the popup shows an extra row directly under the
      swatch separator: **`Add to Group "<name>"`** with that group's own dot — a one-click
      shortcut that only appears for a currently **unassigned** file.
  - [ ] Clicking that row assigns the file to the active group in one click.
  - [ ] The row disappears once the file is already assigned to any group.
  - [ ] The row disappears if no group is currently active.
- [ ] A file already in a group instead shows **"In group: `<name>`"** as a caption, with
      "Add to Group…" available underneath to move it to a different group.

## 3. Swatch popup — removing an assignment

- [ ] On a tab that's already assigned to a group, right-click → the popup shows an
      **"In group: `<name>`"** caption and a **"Remove from Group"** row.
- [ ] Clicking it clears the tab's tint back to the platform default — immediately, no
      confirmation, unless it's the group's last member (see §7 — a different dialog fires
      instead).
- [ ] Right-clicking the same tab afterwards shows no "In group" caption and no "Remove from
      Group" row — the swatch/creation flow is offered again as if the tab had never been
      assigned.

## 4. Manage Groups — rename, recolour, active group

Once at least one group exists, the popup gains a **"Manage Groups…"** entry (nested: pick a
group, then pick an action).

- [ ] **Rename…** opens a small dialog with a "Name:" text field pre-filled with the current name;
      typing a new name and pressing Enter (or OK) renames it, and **every** tab currently tinted
      with that group updates its tooltip/identity accordingly (tint colour is unaffected by a
      rename).
- [ ] Cancelling Rename leaves the name unchanged.
- [ ] **Recolour…** opens a nested list of the 8 presets; picking one **applies immediately** (no
      further confirmation) and repaints **every member tab**, including ones that aren't
      currently open in a split you're looking at.
- [ ] **Mark as Active Group** — pick a group, mark it active; the entry's label flips to
      **"Clear Active Group"** the next time you open the menu for that same group.
- [ ] With a group active, opening (creating) a **new file** in the IDE automatically tints it
      with the active group's colour, with no popup interaction at all.
- [ ] With a group active, an already-open, unassigned tab does *not* retroactively join it —
      auto-join only fires on `fileOpened`, not on marking active. (Use §2's one-click
      `Add to Group "<name>"` row to join it by hand instead.)
- [ ] Only **one** group can be active at a time — marking a second group active clears the first.
- [ ] **Clear Active Group** turns auto-join off; subsequently opened files are no longer tinted
      automatically.
- [ ] Mark a group active, then **restart the sandbox IDE** — confirm the group is *not* still
      marked active (this is deliberate and session-scoped — see README "Why active group isn't
      persisted").
- [ ] **Files…** (only shown when the group has at least one member) opens a **fresh top-level
      chooser** titled **"Files in `<name>`"**, listing every member by name with its
      project-relative directory in grey, sorted by directory then name.
  - [ ] Clicking a listed file reopens it (or focuses it if already open) as a tab.
  - [ ] A member whose open tab is currently closed still appears in the list.
  - [ ] A member whose underlying file was deleted while the IDE was **closed** (so no VFS event
      ever fired) is shown as **"missing"**, not silently dropped — choosing it offers to unassign
      it.
  - [ ] "Files…" does **not** appear for a group with zero members.

## 5. Deleting a group

- [ ] **Manage Groups…** → a group → **Delete Group** opens a confirmation dialog titled
      **"Delete Tab Group"** with **"Delete"** / **"Cancel"** buttons (not generic Yes/No).
- [ ] Confirming deletes the group; every member tab loses its tint; the group no longer appears
      anywhere (popup, tool window).
- [ ] Cancelling leaves the group and its members untouched.
- [ ] Delete a **hidden** group that has remembered (closed) tabs (§6) — confirm the dialog adds a
      second line warning that "This group is hidden" and that its remembered tab(s) "will be
      forgotten", rather than only the generic "Its files will be unassigned." message.

## 6. PrismTabs tool window

Open it via its right-hand stripe button (or **View | Tool Windows | PrismTabs**).

- [ ] The window lists every existing group as a tree row: colour dot, name, member count
      (`N files`), and a `(active)` / `hidden` suffix where applicable.
- [ ] Expanding a group row shows one child row per member: file-type icon, file name, greyed
      relative directory.
- [ ] An **open** member's row renders in **bold**; a **closed** member renders in regular weight
      — neither greyed nor hidden.
- [ ] **Double-clicking a member row** opens that file as a tab.
- [ ] **Double-clicking a group row** toggles Hide/Show for that group (not expand/collapse — the
      tree's own expand toggle is on the disclosure arrow / arrow keys / Enter only).
- [ ] Pressing **Enter** on a selected group row does *not* hide/show it — only the disclosure
      arrow, a click on the handle, or arrow-key navigation expands it.
- [ ] **Hide Group** (toolbar button or right-click context menu) closes every open tab belonging
      to that group and marks the row **"hidden"**.
- [ ] **Show Group** reopens the tabs that were open when it was last hidden, in their original
      order.
- [ ] A group with **all its tabs manually closed** (via Ctrl+W, not "Hide Group") also reads as
      "hidden" — hidden state is *derived* from what's on the strip, not a stored flag (confirm by
      closing one tab at a time and watching the row transition).
- [ ] Reopening just **one** closed member (double-click its row) is enough to un-hide the group —
      the row's "hidden" suffix disappears immediately.
- [ ] **Active Group** toggle (toolbar and context menu) — right-click a group row, toggle Active
      on; confirm it marks that group active and clears any other group's active state, matching
      §4's popup-driven flow.
- [ ] **Rename…** / **Recolour…** / **Delete Group** are also available from the tool window's
      toolbar and context menu, and behave identically to §4/§5's popup-driven versions.
- [ ] **Right-click on a member (child) row** shows **Remove from Group** — *not* the five
      group-level actions — and clicking it unassigns that one file, with **no confirmation**
      (unless it empties the group — see §7).
- [ ] **Right-click on a group row** does **not** show "Remove from Group" (a group row has no
      single file to remove).
- [ ] **Multi-select several member rows** (Ctrl-click on Linux/Windows, ⌘-click on macOS) across
      the **same** group, right-click → the item reads **"Remove N Files from Group"**, and
      confirms the count matches the selection.
- [ ] Multi-select member rows spanning **two different groups**, right-click → the item reads
      **"Remove N Files from Their Groups"** (plural "Their"), and the five group-only actions
      (Rename/Recolour/Delete/Hide/Show/Active) are all **greyed out** since the selection doesn't
      resolve to one group.
- [ ] **Right-clicking an unselected row selects it first** (matching the Project view's own
      convention) without moving keyboard focus.
- [ ] **Right-clicking inside an existing multi-selection keeps the whole selection** rather than
      collapsing it to the clicked row.
- [ ] **Deleting the selected group** moves the selection onto the group that took its place,
      rather than leaving nothing selected (which would grey the whole toolbar).
- [ ] The selected file row feeds the platform's own file actions — try **F4** ("Jump to Source"),
      **Select in Project View**, and **Copy Path** on a member row and confirm they all work.
- [ ] **Expansion state and selection both survive a refresh** — expand a group, recolour it from
      elsewhere (e.g. the popup), and confirm the group is still expanded and still selected
      afterwards.
- [ ] Tool window icon and the Hide/Show eye icons render crisply (not blurry) at your display's
      scaling.

## 7. Empty groups (three ways to trigger the prompt)

A group becomes "empty" only when its **last assigned file** loses its assignment — closing tabs
alone never empties a group.

- [ ] **"Remove from Group" on a group's last member** (via the tab popup §3, or the tool window
      §6) opens an **"Empty Tab Group"** dialog: `"<name>" has no tabs left. Delete the group?`
      with **"Delete Group"** (focused default) and **"Keep"** buttons.
  - [ ] Choosing **Delete Group** removes the group entirely.
  - [ ] Choosing **Keep** leaves the group in place, now listed as **"0 files"** in the tool
        window, with its own Delete Group action still available later.
- [ ] **Moving a group's last member to a different group** ("Add to Group…", or clicking a preset
      dot to start a brand-new group from that file) triggers the same prompt for the group it
      just left.
- [ ] **Deleting the last member's file on disk** (via the Project tool window, using its normal
      delete confirmation) also triggers the prompt — non-modally, after the deletion completes,
      not stacked on top of the platform's own delete dialog.
- [ ] **Deleting an entire directory** that empties **two** groups at once raises a **single**
      **"Empty Tab Groups"** (plural) dialog naming both groups, not two separate dialogs in a
      queue.
- [ ] Removing a file that leaves a group with at least one member **remaining** does **not**
      trigger any prompt — confirm the negative case explicitly.
- [ ] An empty group kept via "Keep" survives an IDE restart, still listed as 0 files (no dialog
      fires again for it).

## 8. Licensing / paywall

Requires `<product-descriptor>` uncommented in `plugin.xml` (see the note at the top) — otherwise
every feature below runs unconditionally free.

- [ ] **Unlicensed start.** Launch with no licence. The plugin stays **enabled** — it must not
      appear in `config/disabled_plugins.txt` and must not be disabled in the Plugins list.
- [ ] With no licence, the tool window shows an **upsell panel** instead of the group list, with
      wording along the lines of "Start the free trial or activate a subscription" rather than an
      empty/broken-looking list.
- [ ] With no licence, **free-tier features still work fully** from the tab right-click menu:
      creating unlimited groups, assigning all 8 presets, renaming, recolouring, deleting,
      "Files…", and "Remove from Group" all work with no upsell interruption.
- [ ] With no licence, attempting a **paid** action — marking a group active, or Hide/Show from
      the tool window (if reachable at all) — raises the **registration dialog** rather than
      silently doing nothing or crashing.
- [ ] From that dialog, **start the 30-day trial** — confirm paid features (tool window listing,
      hide/show, active-group auto-join, palette editing in Settings) all unlock.
- [ ] Note the tool window only picks up the new licence state the **next time it's shown** or on
      the next group change — not instantly when the dialog closes. Confirm that's what actually
      happens (re-open the tool window, or make a group change, to see it flip).
- [ ] **Lapse across a restart:** while licensed/trialling, mark a group active, hide another
      group, and rename a palette preset (§9). Remove the licence (or let the trial state reset in
      your test setup) and **restart the IDE**.
  - [ ] The plugin still loads, into the free tier — not disabled.
  - [ ] Groups, their colours, and the renamed palette preset all survived.
  - [ ] A newly opened file does **not** auto-join the previously-active group.
  - [ ] The now-locked tool window offers **"Reopen the tabs of N hidden groups"** (or equivalent
        wording) and clicking it actually reopens the previously-hidden group's tabs — nothing is
        left recoverable only by resubscribing.
- [ ] "Clearing" an active group (not setting one) still works even while unlicensed/lapsed —
      confirm it's not itself gated.

## 9. Settings — palette editing

**Settings/Preferences | Tools | PrismTabs** (also reachable via ⌘, on macOS).

- [ ] The page lists all 8 presets, each with an editable name field and a light/dark colour
      swatch picker.
- [ ] Edit a preset's **name**, click **Apply** — reopening the swatch popup shows the new name on
      that preset's dot tooltip (or wherever the label surfaces).
- [ ] Edit a preset's **light** and **dark** colours independently via the colour pickers, Apply —
      confirm both the swatch popup dot and any tab tinted with that preset update to the new
      colour (dark theme shows the dark variant, light theme the light one).
- [ ] Click **Restore Defaults** — confirm every customised field (name and both colours) reverts
      to the shipped default, and this is reflected once **Apply**'d.
- [ ] Cancelling the Settings dialog after an edit (without Apply) discards the change.
- [ ] Rename a preset, Apply, then **restart the IDE** — confirm the customisation persisted.
- [ ] This page requires an active subscription/trial (§8) once licensing is enabled — confirm the
      page is locked or shows an upsell when unlicensed, per the free/paid split.

## 10. Persistence across restarts

Do all of the following, then fully restart the sandbox IDE (quit and relaunch, not just reopen a
project), and confirm every item survived:

- [ ] Group names, colours, and file membership.
- [ ] A palette customisation (renamed/recoloured preset).
- [ ] A group's hidden state is **not** independently stored, but its *remembered* tab set is: a
      group whose tabs are all closed reads as "hidden" purely because nothing is open, and
      **Show Group** correctly reopens the tabs that were open before the restart (not before the
      last hide).
- [ ] The active group is correctly **cleared** after a restart (never restored — see §4).

## 11. Rename / move remapping

- [ ] Assign a file to a group, then **rename the file** (via the Project view, F2). Confirm the
      tab keeps its group's tint after the rename and the tool window's member row shows the new
      name.
- [ ] Assign a file to a group, then **move it to a different directory** (drag in the Project
      view, or Refactor | Move). Confirm the assignment and tint survive, and the tool window shows
      the file's new relative directory.
- [ ] **Move (or rename) an entire directory** containing several assigned files. Confirm every
      file nested under it keeps its assignment.
- [ ] A group's **remembered hidden-tab list** (§10) also follows a rename/move — hide a group,
      rename one of its remembered-but-closed members' file, then Show Group and confirm the
      renamed file reopens correctly.

## 12. Editor tab colour provider interplay

- [ ] Assign a colour to a file that also matches one of the IDE's built-in **File Colors** scopes
      (e.g. a file under **Scratches and Consoles**, or one matching a custom scope you've set up
      with its own File Color). Confirm the **PrismTabs** colour wins — this is the deliberate
      `order="first"` policy (README "order=first is mandatory"), matching how Chrome treats an
      explicit tab colour as an override.
- [ ] Assign a colour to a file, then toggle the IDE between light and dark theme (Settings |
      Appearance) without restarting — confirm the tab retints correctly on the next repaint with
      no stale colour and no restart needed.

## 13. Dynamic plugin loading (optional, advanced)

Only worth doing if you're validating a release candidate, not part of routine feature testing.

- [ ] With at least one assigned group and an open tab showing it, go to **Settings | Plugins**,
      find PrismTabs, and **Disable** it. Confirm no error dialog appears and the IDE doesn't
      require a restart it didn't ask for.
- [ ] **Re-enable** it from the same page (no restart). Confirm groups, colours and tool window all
      come back correctly with no leftover stale state or duplicate registrations.
- [ ] Watch `idea.log` (Help | Show Log) during both steps for any `io.prismtabs` `SEVERE`/`ERROR`
      entries — there should be none.

## 14. General polish (apply throughout, not a separate pass)

Per CLAUDE.md's UI standard — check these opportunistically while working through every section
above, not as a final once-over:

- [ ] All icons (stripe icon, Hide/Show eyes, colour swatches, Delete's waste-basket) render
      crisply at your display's scaling, in both themes.
- [ ] No menu item, dialog title, or button label is truncated, misaligned, or uses the wrong
      punctuation convention (an ellipsis only where further input follows — see README "Checked
      against the JetBrains UI Guidelines").
- [ ] No stray `SEVERE`/`ERROR` log entries attributable to `io.prismtabs` appear in `idea.log`
      during any of the above (some non-PrismTabs sandbox noise is expected and documented in
      README "Noisy but harmless log output" — don't confuse the two).
