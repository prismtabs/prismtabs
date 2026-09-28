# Why PrismTabs paints no imitation chrome on the tab strip

Design-rationale note. PrismTabs deliberately does not build browser-style
tab-group furniture into the editor tab strip; it colours real tabs and puts
group management in a tool window. This document records why.

The user-facing form of this is the manual's FAQ entry *"Is there a collapsible
group bar in the tab strip, like browser tab groups?"* in
`prismtabs-website/docs.html`.

---

## What "chrome" means here

"Chrome" is used in its UI-design sense, not the browser of the same name. It is
the framing around content: toolbars, title bars, borders, scrollbars, status
bars, and tab bars. A web browser's "chrome" is everything on screen that is not
the page itself; an IDE's chrome is everything that is not the editor text.

The **tab strip** is the horizontal row of editor tabs across the top of the
editor area. It is a piece of IDE chrome that the platform owns and paints.

## What "imitation chrome layered over the tab strip" would be

It is the thing PrismTabs deliberately does **not** build: fake UI widgets
custom-painted on top of the platform's tab strip to imitate the tab-group
furniture that browsers such as Chrome, Edge, or Arc show inline. Concretely,
that would mean drawing our own:

- collapsible group "chips" or pills wedged between tabs, spanning the tabs that
  belong to one group;
- group header labels, coloured underlines, or bracket/separator glyphs painted
  into the strip;
- expand/collapse carets and per-group close buttons living in the tab row.

It would *look* like native tab-group UI, but it would actually be PrismTabs
painting over `JBEditorTabs` and keeping its own overlay in sync with the
platform's tab layout on every scroll, split, drag, theme change, and window
resize.

## Why PrismTabs does not do it

Two platform limits make it a bad idea. They are documented under **"Constraints
that are not going away"** in the plugin `README.md`; the short version:

1. **There is no public API for an in-strip group chip.** The browser-style
   collapsible label spanning several tabs has no supported entry point.
   Reproducing it means custom-painting `JBEditorTabs`, which is internal,
   unstable, and free to change shape between IDE builds. (`README.md`,
   constraint 3.)
2. **Tab text colour is not ours to set.** `getEditorTabForegroundColor` is
   experimental and tied to file status, so even the colouring we *do* ship is
   background-only. (`README.md`, constraint 2.)

Custom-painted chrome built on internal classes is exactly the kind of fragile,
version-brittle hack that breaks silently on the next IDE update and across the
New UI / old UI and light / dark / custom-theme matrix. The sentence is a
promise that PrismTabs stays on supported APIs so it keeps working.

## What PrismTabs does instead

**Colouring: the sanctioned per-tab API.**
`io.prismtabs.provider.TabGroupColorProvider` implements the platform's
`EditorTabColorProvider` extension point. The platform calls
`getEditorTabColor(project, file)` for each tab and PrismTabs returns the group's
`JBColor`; the platform then paints its own tab background in that colour. It is
the real tab being tinted by the real tab painter — nothing is drawn over the
strip, and a theme switch simply re-tints on the next repaint. See
`src/main/kotlin/io/prismtabs/provider/TabGroupColorProvider.kt`.

**Group management: a dedicated tool window.**
Everything a group needs that will not fit on an individual tab — its name,
member count, hidden state, and the Rename / Recolour / Delete / Hide-Show
actions — lives in the PrismTabs tool window, the right-hand stripe
(`src/main/kotlin/io/prismtabs/ui/TabGroupsToolWindow.kt`). This is the plan's
explicit substitute for the browser in-strip chip.

**Hiding a group: close-and-remember, not a collapsed chip.**
Because there is no chip to fold away, `io.prismtabs.service.TabGroupCollapse`
implements "hide" as: record which of the group's files are open, in
`FileEditorManager` order, then close them; "show" reopens exactly those, in the
same order. The group stays listed in the tool window, marked hidden, the whole
time. See `src/main/kotlin/io/prismtabs/service/TabGroupCollapse.kt`.

## What the reader is being promised

- The tab colours they see are genuine editor-tab backgrounds, so they render
  consistently with the rest of the IDE and survive platform updates.
- PrismTabs will not fight the tab strip for layout, so tabs still scroll, split,
  drag, and reorder exactly as they do without the plugin.
- Group-level UI is in one predictable place (the tool window), not scattered as
  overlay widgets across the tab row.
- The trade-off is deliberate and stated plainly in the manual: there is no
  single collapsible in-strip element for a whole group; the tool window is how
  you see and act on a group as a unit.

## One-line version

PrismTabs tints real editor tabs through `EditorTabColorProvider` and puts group
management in the PrismTabs tool window, rather than custom-painting fake
browser-style group chrome onto the platform's tab strip.
