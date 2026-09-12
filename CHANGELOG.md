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
  tab is shut can be dropped without opening it first, one file or a whole Ctrl-clicked selection
  at a time; hide/show to close a group's tabs and
  remember them, then reopen them together later; marking a group active so files opened while it's active join it automatically;
  and a custom palette editor under **Settings | Tools | PrismTabs**.
- A file that is *already open* joins the active group in one click, from a row in the tab popup
  (`Add to Group "Auth"`). Auto-join only ever sees files the platform actually has to open, so
  before this an already-open file had to be closed and reopened to join the group. Free, like
  every other by-hand assignment.

### Fixed

- Disabling or uninstalling PrismTabs no longer leaves the IDE asking for a restart. Files the
  plugin opened itself — "Show Group", or a member file opened from the tool window — were opened
  synchronously, and the platform stores the stack it was on in the creation trace of every toolbar
  it builds for that editor, which pinned the plugin's class loader for as long as the tab stayed
  open. The IDE reported this as "not unload-safe because class loader cannot be unloaded" and
  wrote a several-hundred-megabyte heap snapshot for it. Those files are now opened off the
  plugin's own stack.
- Tab colours now disappear the moment the plugin is disabled or uninstalled, instead of lingering
  until the next IDE start — which, on a disable, is never prompted for.
