# Marketplace listing content

The copy and metadata for the JetBrains Marketplace listing, drafted so the actual
upload (release-readiness-gaps.md item 5) is a paste job, not a writing job. Follows
`naming-and-terminology.md` house style throughout.

Everything here is done, including the four screenshots in `docs/marketplace/`.

---

## Where each piece goes

| Listing surface | Source of the copy |
|---|---|
| Plugin name | `plugin.xml` `<name>` — `PrismTabs`, do not change |
| Description / overview | `plugin.xml` `<description>` — **keep it sourced from the archive**, do not detach it by editing in the admin UI (see item 6's note) |
| Card blurb / "tagline" | Marketplace derives it from the first line of the description; there is no separate field |
| Change notes | `plugin.xml` `<change-notes>`, filled at build time from `CHANGELOG.md`'s `[Unreleased]` via `changelog.getUnreleased()` |
| Additional Information links | **Technical Information** fields in the admin UI (below) — not prose, not `plugin.xml` |
| Tags | Entered at upload; JetBrains maps them onto their controlled vocabulary |
| Screenshots | Uploaded in the admin UI; the four files in `docs/marketplace/`, in filename order |
| Licence | `prismtabs.com/eula` URL, pasted at upload (item 7) |

## Tagline / card blurb

No dedicated field exists. The Marketplace and the IDE's own Plugins panel both show
the **first line** of the description as the one-line summary, so that line is the
tagline:

> Colour-coded tab groups for IntelliJ editor tabs.

Kept deliberately close to the website hero ("Colour groups, built for your IntelliJ
tabs") without being identical — the website lede can be looser; this one has to also
work as a bare list-row summary with no styling around it.

## Description

Lives in `plugin.xml` `<description>` and is mirrored here for review. It expands the
earlier draft with an explicit Free/paid split (matching the README's "Marketplace
publishing prep" section and the website) and a feature list, and keeps the existing
links block last because the description is the only listing surface the IDE's own
Plugins panel renders.

Deliberately **no price string** in this copy: the archive for a given version is
frozen, the EULA carries a price-change clause, and the Marketplace has its own
pricing field. "A paid subscription" here; the number lives only on the Sales Info
scheme and the website pricing card.

```
Colour-coded tab groups for IntelliJ editor tabs.

Right-click a tab for the colour swatch popup: pick one of 8 preset colours to
start a new group and name it, or "Add to Group…" to put the tab in a group you
already have. "Manage Groups…" lets you list a group's files, rename, recolour,
delete, or mark a group active — files opened while a group is active join it
automatically, and a file that was already open joins it in one click, from the
same popup or the Project view's context menu.

The PrismTabs tool window lists every group with its colour and member count,
and can hide a group (closing its tabs, remembering them) or show it again.
Assignments and hidden state persist across restarts and follow a file through a
rename or move. The preset palette itself is editable under
Settings | Tools | PrismTabs.

Free forever
  • Assign tabs to 8 preset colours, each measured for contrast on both light and
    dark editor tabs
  • Unlimited named groups — create, rename, recolour, delete
  • "Files…" lists everything in a group, open tab or not, and reopens any of it
  • Assignments persist across restarts and survive file renames and moves

Paid — a 30-day free trial on every new install, then a subscription
  • The PrismTabs tool window for managing every group at a glance
  • Hide a group to close its tabs and remember them, then show it to reopen them
    together
  • Mark a group active so newly opened files join it automatically
  • Rename or repaint any preset colour under Settings | Tools | PrismTabs

When the trial ends, PrismTabs drops to the Free tier — never a lockout. Your
groups and colours stay either way.

prismtabs.com has the manual, the tier breakdown and the licence agreement. Bugs
and feature requests, for every edition, go to the public feedback tracker —
anything touching a purchase should be emailed instead, as that tracker is public.
```

(The `<description>` renders the two links as anchors and the bullet lists as
`<ul>`; the block above is the plain-text shape of it.)

## Change notes

Sourced from `CHANGELOG.md` `[Unreleased]`. Reworded during this pass to match
`naming-and-terminology.md`: "Chrome-style colour groups" → "Colour-coded tab
groups" (item 13 already made this fix in `plugin.xml`; the changelog had drifted),
and "the \"Tab Groups\" tool window" → "the PrismTabs tool window" (unquoted, per
`naming-and-terminology.md` §1).

## Technical Information links

Fill these in the admin UI. They render in the listing's *Additional Information*
section and are the correct home for these rather than the description prose.

| Field | Value |
|---|---|
| Issue tracker | `https://github.com/yendrrek/prismtabs-feedback` |
| Documentation | `https://prismtabs.com/docs` |
| Website | `https://prismtabs.com` |
| Source code | *leave empty* — the repository is private (item 9) and the best-practices page requires external links to be reachable |

## Tags

Enter at upload; JetBrains maps free-text tags onto their controlled list during
review, so treat these as the intent rather than exact final strings:

- **User Interface** — the plugin's whole surface is IDE chrome
- **Editor** — it acts on editor tabs specifically
- **Productivity** — tab organisation for large working sets

Not `Navigation` (PrismTabs colours and groups tabs, it does not move between them)
and not `Themes` (it tints individual tabs, it is not a colour scheme).

## Screenshots

In `docs/marketplace/`, all ≥ 1200 × 760, in the order they should appear in the
listing (three dark, one light):

1. **`01-swatch-popup-and-tabs-dark.png`** (1394 × 919) — a tab right-clicked, the
   "New Group" caption above the 8 preset dots, "Add to Group…" / "Manage Groups…"
   / "Remove from Group" below the separator, tinted tabs and code behind. The
   one-right-click pitch; it goes first.
2. **`02-tool-window-and-tabs-dark.png`** (1853 × 885) — the PrismTabs tool window
   docked right, listing Tests / Backend / Docs with colour dots and member
   counts, "Backend 1 file · active" showing the active-group marker (pin toggle
   pressed in the stripe), "Docs 3 files · hidden" showing the hide feature, editor
   tabs behind it tinted to match. The paid tier's home.
3. **`03-palette-settings-dark.png`** (1794 × 1045) — the full `Settings | Tools |
   PrismTabs` dialog, all eight presets renamed to a consistent scheme, the light
   and dark `ColorPanel` per row visible.
4. **`04-tool-window-and-tabs-light.png`** (1853 × 885) — the same tool window and
   tinted tab strip as shot 2, in the light theme, no popup. Proves the
   light-theme palette work the README documents.
