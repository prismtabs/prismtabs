# Marketplace listing

Where each part of the JetBrains Marketplace listing comes from, and the values set by hand in the
admin UI. Copy follows [`naming-and-terminology.md`](naming-and-terminology.md).

## Sources

| Listing surface | Source |
|---|---|
| Name | `plugin.xml` `<name>`. Do not change it. |
| Summary line | The first line of `<description>`. There is no separate tagline field. |
| Description | `plugin.xml` `<description>`. Edit it there, never in the admin UI, which would detach it from the archive. |
| Change notes | `CHANGELOG.md`, rendered into `<change-notes>` at build time. |
| Pricing | Sales Info in the admin UI and the website pricing card. |
| Licence | `https://prismtabs.com/eula`. |

Keep prices out of `<description>`: each version's archive is frozen, and prices can change.

## Technical Information links

| Field | Value |
|---|---|
| Issue tracker | `https://github.com/prismtabs/prismtabs-feedback` |
| Documentation | `https://prismtabs.com/docs` |
| Website | `https://prismtabs.com` |
| Source code | Empty. The repository is private, and every listing link must be reachable. |

## Tags

`User Interface`, `Editor`, `Productivity`. Not `Navigation` (PrismTabs does not move between tabs)
and not `Themes` (it is not a colour scheme).

## Screenshots

`docs/marketplace/`, at least 1200 × 760, uploaded in filename order:

1. `01-swatch-popup-and-tabs-dark.png`: the swatch popup on a right-clicked tab.
2. `02-tool-window-and-tabs-dark.png`: the PrismTabs tool window with an active and a hidden group.
3. `03-palette-settings-dark.png`: Settings | Tools | PrismTabs.
4. `04-tool-window-and-tabs-light.png`: shot 2 in the light theme.
5. `05-files-in-group-dark.png`: the free `Files…` chooser.

Shots 2 and 4 show the licensed UI, not the free-tier upsell panel. The website hero images are
cropped from them, so re-shoot both sites together.
