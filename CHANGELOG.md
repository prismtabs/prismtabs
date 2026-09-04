# PrismTabs Changelog

## [Unreleased]

### Added

- Colour-coded tab groups for editor tabs: right-click a tab for the swatch popup, pick one of
  8 preset colours to start a new group and name it, or add the tab to an existing group. Rename
  and recolour from "Manage Groups…", or list a group's files with "Files…" — every member by
  name, whether or not it still has a tab open, and clicking one reopens it. Unlimited groups, and
  assignments persist across restarts and survive file renames/moves — free forever.
- **Paid** (30-day free trial on new installs, then a subscription): the PrismTabs tool window
  listing every group with its colour and member count, and expanding each one to the files it
  holds — open or closed, with "Remove from Group" on a file's right-click menu, so a member whose
  tab is shut can be dropped without opening it first; hide/show to close a group's tabs and
  remember them, then reopen them together later; marking a group active so files opened while it's active join it automatically;
  and a custom palette editor under **Settings | Tools | PrismTabs**.
- A file that is *already open* joins the active group in one click — a row in the tab popup
  (`Add to Tab Group "Auth"`) and the same item on the Project view's context menu, which is
  also a keymap entry, "Add to Active Tab Group". Auto-join only ever sees files the platform
  actually has to open, so before this an already-open file had to be closed and reopened to join
  the group. Free, like every other by-hand assignment.
