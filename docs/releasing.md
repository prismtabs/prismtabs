# Releasing

Pushing a `v*` tag publishes a release to JetBrains Marketplace through
`.github/workflows/publish.yml`: it re-runs `verifyPlugin` and `build`, then `signPlugin` and
`publishPlugin`. There is no approval step, so the tag push is the gate.

## Branches and CI

Work on `dev`. `master` receives commits only when a release is cut. GitHub-hosted minutes on
Windows and macOS cost 2× and 10× Linux minutes, and the free allowance is 2,000 a month, so CI
runs where a release needs it and not on every commit.

| Workflow | Runs on | Cost |
|---|---|---|
| `build.yml` (Build) | Push to `master`, every pull request, manual run | About 126 allowance minutes: `./gradlew build` on Linux, macOS and Windows, plus `verifyPlugin` for each IDE |
| `publish.yml` (Publish) | Push of a `v*` tag | About 67 Linux minutes: `verifyPlugin` for each IDE, `build`, `signPlugin`, `publishPlugin` |
| `eula-sync.yml` (EULA sync) | Push or pull request that changes `docs/EULA.md`, manual run | Seconds |
| `ui-tests.yml` (UI Tests) | Manual run only | Windows minutes at 2× |

- A push to `dev` runs nothing. Do not open a pull request from `dev` to `master`: it runs Build,
  and the merge runs it again.
- Run Build by hand with `gh workflow run Build` or from the Actions tab.
- Build does not run `uiTest`. Run it locally, as the [cross-platform pass](cross-platform-manual-verification.md) describes.

Each CI job has a local equivalent, so run these on `dev` before merging:

| CI job | Local command |
|---|---|
| Build | `./gradlew build` on each of Linux, macOS and Windows |
| Verify plugin, all IDEs | `./gradlew verifyPlugin` |
| Verify plugin, one IDE | `./gradlew verifyPlugin -PverifyIde=<version>` |
| List the IDEs | `./gradlew printProductsReleases` |

The unfiltered `verifyPlugin` downloads every IDE at once and needs roughly 45–50 GB of disk.

## Cut a release

1. Run the [cross-platform pass](cross-platform-manual-verification.md) on Windows and macOS
   against the commit you will tag.
2. Choose the version (see [Version numbers](#version-numbers)) and set `pluginVersion` in
   `gradle.properties`. For a major release, also set `release-version` and `release-date` on
   `<product-descriptor>` in `plugin.xml`.
3. Stamp the changelog and check the descriptor:

   ```bash
   ./gradlew patchChangelog
   ./gradlew verifyPlugin
   ```

   `patchChangelog` moves `[Unreleased]` into a section for the version. Nothing but
   `verifyPlugin` catches an invalid `release-version`.
4. Commit on `dev`, fast-forward `master` to it and push `master`. Build runs on that push;
   wait for it to pass before tagging.

   ```bash
   git switch master
   git merge --ff-only dev
   git push origin master
   ```

5. Tag it with an annotated tag that matches `pluginVersion`, and push the tag:

   ```bash
   git tag -a v1.4.16 -m "Release 1.4.16"
   git push origin v1.4.16
   ```

To withdraw a tag pushed by mistake, run `git push origin :refs/tags/v1.4.16` and
`git tag -d v1.4.16`. The workflow may already be running by then; cancel it in GitHub Actions.

## Version numbers

The Marketplace resets every user's trial whenever `release-version` changes. PrismTabs therefore
cuts a major release deliberately, about once a year, and ships everything else, features
included, as updates.

| Release | `pluginVersion` | `release-version` | `release-date` |
|---|---|---|---|
| Update (default) | Bump the third component: 1.4.15 → 1.4.16 | Unchanged | Unchanged |
| Major | Bump the second component, zero the third: 1.4.x → 1.5.0; after 1.9 comes 2.0.0 | First two components without the dot: `15`, `20` | The publish day, `YYYYMMDD` |

- The minor component stays a single digit. `release-version` needs at least two digits, and the
  Marketplace reads its last digit as a separate number. `ProductDescriptorTest` enforces both.
- The Marketplace refuses a different `release-date` for a `release-version` it has already seen,
  including from a refused upload. Change the two together or not at all.

## Secrets

`publish.yml` reads four repository secrets: `CERTIFICATE_CHAIN`, `PRIVATE_KEY`,
`PRIVATE_KEY_PASSWORD` and `PUBLISH_TOKEN`. The signing files are attachments in the KeePassXC
vault `~/.local/share/keepassxc/prismtabs-secrets.kdbx`, group `PrismTabsCICD`, together with the
root and intermediate CA keys needed to reissue the leaf certificate before it expires in 2036.
Sign every release with this key; never generate a new one.

`signPlugin` is skipped, and Gradle still reports success, when `CERTIFICATE_CHAIN` or
`PRIVATE_KEY` is empty. The workflow's "Check release secrets" step fails the run before signing
if any of the four secrets is empty.
