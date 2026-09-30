# Demo video script

Script and recording notes for the 90-second PrismTabs demo on YouTube. Copy follows
[`naming-and-terminology.md`](naming-and-terminology.md).

## Format

Screen recording with voice-over. No face-cam.

- The subject is small UI detail: tab tints and the swatch popup. A corner overlay covers the IDE.
- Audio quality drives perceived quality. Use a decent microphone in a quiet room.
- An optional 5-second face shot fits the intro or outro.

## Recording setup

| Item | Setting |
|---|---|
| Resolution | 1080p or higher. Zoom to the tab strip and tool window in the edit. |
| IDE zoom | Raised (View | Appearance) so text stays readable on a phone. |
| Project | About 8 to 12 tabs with realistic file names. |
| Second IDE | Run PrismTabs in at least one non-IntelliJ IDE. Name on screen and in the end card only IDEs you have run it in. |
| Cursor | Click highlight on. Slow, deliberate movement. |
| Audio | Record the voice-over after the screen capture and time it to the visuals. |
| Captions | Burned in. Many viewers watch muted. |
| Description | Marketplace link on the first line. Repeat it in a pinned comment. |

Do not state prices. They change and the video does not. Say "14-day free trial".

## Script

| Time | Show | Say |
|---|---|---|
| 0:00–0:08 | IDE with about 12 uncoloured tabs. Slow scroll, then the tab strip in full. | "If you keep a dozen tabs open in your JetBrains IDE, you know the problem: which of these belong together? PrismTabs fixes that." |
| 0:08–0:25 | Right-click a tab and open the swatch popup. Pick a colour and name the group. The tab tints. Add two more tabs to the group, then start a second colour. | "Right-click any tab and pick a colour. Name the group, and the tab is tinted. Add more tabs the same way, or choose 'Add to Group' for a group you already have. Now the strip reads at a glance: backend in blue, tests in green, config in orange." |
| 0:25–0:35 | Switch to the light theme, then back to dark. Cut to a second JetBrains IDE (for example PyCharm or WebStorm) with the same coloured tabs. | "The eight colours are measured for contrast on both light and dark themes, so they stay legible either way. And it is not tied to one IDE: it runs across the JetBrains family." |
| 0:35–0:50 | Open the PrismTabs tool window. Show groups with counts. Expand one. Right-click a file in the list and remove it from its group. | "Open the PrismTabs tool window for the overview: every group, its colour, how many files it holds. Expand one to see its files, and remove a file from a group right there, without opening it first." |
| 0:50–1:05 | Hide a group. Its tabs close. Show the group. The tabs reopen in order. | "Working on the frontend and want the backend out of the way? Hide the group. Its tabs close, and PrismTabs remembers them. Show it again and they all come back, in order." |
| 1:05–1:15 | Mark a group active. Open a new file. It tints and joins the group. | "Mark a group as active, and every file you open joins it automatically. Start a task, and everything lands in one colour." |
| 1:15–1:20 | Restart the IDE. The colours are still there. | "Groups survive restarts, and follow files through renames and moves." |
| 1:20–1:30 | End card: Marketplace listing and prismtabs.com. | "The basics are free forever, and the tool window comes with a 14-day trial. Search for PrismTabs in the JetBrains Marketplace, or follow the link below. It works in IntelliJ IDEA, PyCharm, WebStorm and other JetBrains IDEs." |

## Content boundaries

- The first 8 seconds state the problem. Do not open with a self-introduction.
- The tool window and hide/show stay in. They are the paid differentiators.
- The video does not describe collapsible in-strip group chips or compare PrismTabs to browser tab
  groups. See [`no-imitation-chrome.md`](no-imitation-chrome.md).
- The free/paid split matches [`marketplace-listing.md`](marketplace-listing.md): free covers colours,
  groups and `Files…`; paid covers the tool window, hide/show, active groups and palette editing.
- Settings | Tools | PrismTabs (the editable palette) is out of scope. It fits a separate short.
