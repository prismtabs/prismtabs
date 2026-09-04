# Naming and terminology

How PrismTabs names things in anything a user reads — the IDE UI, the
Marketplace listing, the website (`prismtabs-website`), the README, and support
replies. The goal is that the same element is called the same thing everywhere,
and that the brand name is used where it earns its keep and stays out of the way
elsewhere.

This is a house style, not a description of the code. Internal identifiers
(`TabGroupService`, `io.prismtabs.ui.TabGroupsToolWindow`, `POPUP_PLACE`, …) are
exempt and need not follow it.

---

## The three layers

Every user-facing noun in this project falls into one of three layers. Decide
which layer a word is in, then style it accordingly.

### 1. Brand — chrome the user navigates *to*

**Always `PrismTabs`.** One word, capital P, capital T.

This is the name on any piece of IDE furniture a user has to find by name:

- the tool window / right-hand stripe button
- `View | Tool Windows | PrismTabs`
- `Settings | Tools | PrismTabs`
- the plugin itself on the Marketplace

Rationale: the stripe button is the plugin's only permanently visible piece of
screen real estate inside the IDE, and its label doubles — via
`ToolWindowEP`, which has no separate display-name attribute — as the
`View | Tool Windows` entry and the string users type into Search Everywhere to
re-find the plugin. A tool window called "Tab Groups" would be a description of
its contents that competes with the brand for that one slot. This is already
noted in `plugin.xml` next to the `<toolWindow>` tag; this document is the
general form of that decision.

So: **"the PrismTabs tool window"**, never "the Tab Groups tool window".

### 2. Concept — the thing the user manipulates

**Lowercase `tab group` / `tab groups` / `colour group`** in running prose.

There is no feature called "Tab Groups". There is a product, PrismTabs, and it
manages tab groups. Capitalising the concept turns it into a second sub-brand
that then has to be kept consistent with the first one — which is exactly the
drift this document exists to stop.

- ✅ "assign a tab to a colour group"
- ✅ "hide a tab group without losing what was open in it"
- ✅ "a real tab groups panel"
- ❌ "a real Tab Groups panel"
- ❌ "open the Tab Groups on the right-hand stripe" (name the window: "open
  PrismTabs on the right-hand stripe")

British spelling as everywhere else: **colour**, not color.

### 3. Literal UI strings — quoted verbatim

When you are telling the user which exact menu item, button, or dialog title to
look for, **quote it exactly as it appears, with its own capitalisation**, and
keep it in sync with the source string.

| String the user sees | Where it is defined |
|---|---|
| `Tab Group…` (editor-tab right-click menu) | `plugin.xml`, `<action id="PrismTabs.AssignTabGroup" text="Tab Group…">` |
| `New Group` (caption over the swatch row) | `TabGroupPopup.kt`, `ListSeparator("New Group")` |
| `Add to Group…`, `Manage Groups…`, `Remove from Group` | `TabGroupPopup.kt`, `textFor(entry)` |
| `Files…` (a group's file list, in `Manage Groups…`) | `TabGroupPopup.kt`, `GroupActionsStep.getTextFor` |
| `Files in <name>` (that list's own title), `missing` (its suffix on a member whose file is gone) | `TabGroupPopup.kt`, `showGroupFiles`, `MemberFileRenderer` |
| `Mark as Active Group` / `Clear Active Group`, `Delete Group`, `Hide Group` / `Show Group` | `TabGroupPopup.kt`, `TabGroupsToolWindow.kt` |
| `Active Group` (tool window toolbar/context-menu toggle) | `TabGroupsToolWindow.kt`, `ToggleActiveAction` |
| `N files` / `1 file` (tool window group row member count) | `TabGroupsToolWindow.kt`, `TabGroupsTreeRenderer` |
| `In group: <name>` (caption over `Remove from Group`) | `TabGroupPopup.kt`, `currentGroupCaption` |
| `New Tab Group`, `Rename Tab Group`, `Missing File` (dialog titles) | `TabGroupPopup.kt`, `RenameGroupDialog.kt` |
| `Add to Tab Group "<name>"` (tab popup row, and the same item on the Project view's context menu) | `TabGroupPopup.kt`, `addToActiveGroupLabel`; `plugin.xml`, `<action id="PrismTabs.AddToActiveGroup">` |
| `Add to Active Tab Group` (that action's static Keymap/Find Action listing — `update()` rewrites it to `Add to Tab Group "<name>"` wherever a group is actually active) | `plugin.xml`, `<action id="PrismTabs.AddToActiveGroup" text="…">` |

`Tab Group…` in layer 3 and `tab group` in layer 2 co-exist on purpose: one is a
button you click, the other is what you get. If a source string changes, update
the docs that quote it — do not "correct" the quote to match house style.

---

## Quick test

Before writing "Tab Group" with capitals, ask: **am I quoting a string the user
will literally see on screen?**

- Yes → layer 3, quote it verbatim, check it against source.
- No, I mean the panel/stripe/settings page → layer 1, it's **PrismTabs**.
- No, I mean the concept → layer 2, it's lowercase **tab group**.

---

## Common fixes

| Instead of | Write |
|---|---|
| the Tab Groups tool window | the PrismTabs tool window |
| View → Tool Windows → Tab Groups | View → Tool Windows → PrismTabs |
| open "Tab Groups" on the stripe | open "PrismTabs" on the stripe |
| A real Tab Groups panel | A real tab groups panel |
| create a Tab Group | create a tab group (or: use **New Group**, if pointing at the UI) |
| the Prism Tabs plugin | the PrismTabs plugin |
