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
| Pricing | Free. Sales Info in the admin UI is unset. |
| Licence | `https://prismtabs.com/licence`. |

## Technical Information links

| Field | Value |
|---|---|
| Issue tracker | `https://github.com/prismtabs/prismtabs/issues` |
| Documentation | `https://prismtabs.com/docs` |
| Website | `https://prismtabs.com` |
| Source code | The repository URL, once the repository is public. Every listing link must be reachable. |

## Tags

`User Interface`, `Editor`, `Productivity`. Not `Navigation` (PrismTabs does not move between tabs)
and not `Themes` (it is not a colour scheme).

## Screenshots

`docs/marketplace/`, at least 1200 × 760, uploaded in filename order:

1. `01-swatch-popup-and-tabs-dark.png`: the swatch popup on a right-clicked tab.
2. `02-tool-window-and-tabs-dark.png`: the PrismTabs tool window with an active and a hidden group.
3. `03-palette-settings-dark.png`: Settings | Tools | PrismTabs.
4. `04-tool-window-and-tabs-light.png`: shot 2 in the light theme.
5. `05-files-in-group-dark.png`: the `Files…` chooser.

The website hero images are cropped from shots 2 and 4, so re-shoot both sites together.
