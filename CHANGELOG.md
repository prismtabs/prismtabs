# PrismTabs Changelog

## [Unreleased]

### Fixed

- The message in the PrismTabs tool window for a user without a licence was cut off at both edges at
  the default tool window width. It now wraps onto two lines.

## [1.4.17] - 2026-10-02

### Fixed

- Starting the free trial, or activating a subscription, now unlocks the PrismTabs tool window as
  soon as the registration dialog closes. An already open tool window used to stay locked until it
  was closed and reopened.

## [1.4.16] - 2026-09-28

### Changed

- PrismTabs now requires IntelliJ Platform 2025.1 or newer (previously 2024.2).
- PrismTabs now depends on the JetBrains Marketplace Licensing plugin. IntelliJ IDEA and the other
  JetBrains IDEs bundle it; in an IDE that does not, such as Android Studio, the IDE installs it
  together with PrismTabs.

### Fixed

- In an IDE without the JetBrains Marketplace Licensing plugin, the paid features were available
  without a trial or subscription.

## [1.4.15] - 2026-09-22

### Fixed

- Replaced every use of internal IntelliJ Platform API with public equivalents. No user-visible
  behaviour changes; this only concerns Marketplace review, which the 1.4.14 upload had been
  refused over.

## [1.4.14] - 2026-09-21

### Added

- Colour-coded tab groups for editor tabs: right-click a tab for the swatch popup, pick one of
  8 preset colours to start a new group and name it, or add the tab to an existing group. Rename
  and recolour from "Manage Groups…", or list a group's files with "Files…" — every member by
  name, whether or not it still has a tab open, and clicking one reopens it. Unlimited groups, and
  assignments persist across restarts and survive file renames/moves — free forever.
- **Paid** (14-day free trial, then a subscription): the PrismTabs tool window
  listing every group with its colour and member count, and expanding each one to the files it
  holds — open or closed, with "Remove from Group" on a file's right-click menu, so a member whose
  tab is shut can be dropped without opening it first, one file or a whole Ctrl-clicked selection
  at a time; hide/show to close a group's tabs and
  remember them, then reopen them together later; marking a group active so files opened while it's active join it automatically;
  and a custom palette editor under **Settings | Tools | PrismTabs**.
- A file that is *already open* joins the active group in one click, from a row in the tab popup
  (`Add to Group "Auth"`). Free, like every other by-hand assignment.
- PrismTabs can be disabled or uninstalled without restarting the IDE, and tab colours clear the
  moment it is.

[Unreleased]: https://github.com/prismtabs/prismtabs/compare/v1.4.17...HEAD
[1.4.17]: https://github.com/prismtabs/prismtabs/compare/v1.4.16...v1.4.17
[1.4.16]: https://github.com/prismtabs/prismtabs/compare/v1.4.15...v1.4.16
[1.4.15]: https://github.com/prismtabs/prismtabs/compare/v1.4.14...v1.4.15
[1.4.14]: https://github.com/prismtabs/prismtabs/commits/v1.4.14
