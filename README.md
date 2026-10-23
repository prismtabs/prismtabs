# PrismTabs

Colour-coded tab groups for editor tabs in JetBrains IDEs. Free and open source.

[Install from JetBrains Marketplace](https://plugins.jetbrains.com/plugin/34416-prismtabs) ·
[Manual](https://prismtabs.com/docs) · [Website](https://prismtabs.com)

![A swatch popup on a tab's context menu, and coloured tabs](docs/marketplace/01-swatch-popup-and-tabs-dark.png)

## Features

- Assign tabs to eight preset colours, each tuned for light and dark editor tabs.
- Create unlimited named groups; rename, recolour and delete them.
- **Files…** lists everything in a group, open or not, and reopens any of it.
- The **PrismTabs** tool window shows every group with its colour and member count, and expands
  each one to its files. Right-click a file to remove it from its group without opening it.
- Hide a group to close its tabs and remember them; show it to reopen them together.
- Mark a group active so newly opened files join it.
- Rename or repaint the presets under **Settings | Tools | PrismTabs**.
- Assignments survive restarts, renames and moves.

Requires IntelliJ Platform 2024.2 or newer. The plugin makes no network calls and collects no data.

## Build and run

Requires JDK 21 (Gradle picks it up from the toolchain).

```bash
./gradlew test             # unit tests, no IDE needed
./gradlew runIde           # sandbox IDE with the plugin installed
./gradlew buildPlugin      # plugin zip in build/distributions/
./gradlew verifyPlugin     # Plugin Verifier against every supported IDE
```

The end-to-end UI suite drives a real sandbox IDE with
[`remote-robot`](https://github.com/JetBrains/intellij-ui-test-robot):

```bash
./gradlew runIdeForUiTests &   # sandbox IDE with a robot server on :8082
./gradlew uiTest               # run the suite against it
```

On Linux the suite needs an X11 display; see [UI testing](docs/engineering-notes.md#ui-testing).

## Project layout

| Path | Contents |
| --- | --- |
| `src/main/kotlin/io/prismtabs/` | `model` (groups, palette, persisted state), `service`, `provider` (tab colours), `ui` (popup, tool window, settings), `action`, `listener` |
| `src/test/` | Unit tests |
| `src/uiTest/` | `remote-robot` UI tests |
| `docs/` | Documentation, listed below |
| `scripts/release.sh` | Cuts a release |

Persisted state classes keep their class and property names; renaming one loses users' saved
groups. `SavedStateTest` guards this.

## Documentation

| Document | Covers |
| --- | --- |
| [Engineering notes](docs/engineering-notes.md) | Platform findings, design decisions, platform limits, Internal API policy, UI-test setup |
| [Implementation plan](docs/tab-groups-plugin-implementation-plan.md) | Design and platform API checks |
| [Releasing](docs/releasing.md) | Versioning and the release workflow |
| [CI/CD pipeline](docs/ci-cd-pipeline.html) | The GitHub Actions workflows |
| [Marketplace listing](docs/marketplace-listing.md) | Listing fields and where each comes from |
| [Manual test cases](docs/manual-test-cases.md) · [Cross-platform pass](docs/cross-platform-manual-verification.md) | Checks to run by hand before a release |
| [Learning manual](docs/learning/README.md) | Kotlin and the IntelliJ Platform, taught with this codebase |

## Issues

[Open an issue](https://github.com/prismtabs/prismtabs/issues/new/choose) after checking the
[manual](https://prismtabs.com/docs) and searching existing issues, closed ones included. A 👍 on
an existing issue is how requests are prioritised. Issues are public, so keep personal data out of
them. Send anything private to contact@prismtabs.com, and report suspected security problems as
described in [SECURITY.md](SECURITY.md).

Maintainers apply these labels when triaging:

| Label | Meaning |
| --- | --- |
| `bug` | A defect report |
| `enhancement` | A feature request |
| `question` | A usage question |
| `docs` | The manual is missing or wrong about something |
| `needs-info` | Waiting on the reporter; closed if it goes quiet |
| `confirmed` | Reproduced locally; the report is a real defect |
| `planned` | Accepted and intended for a future release |
| `duplicate` / `wontfix` | Closed, with the reason |

## Licence

[MIT](LICENSE)
