# Releasing

Pushing a `v*` tag publishes a release to JetBrains Marketplace through
`.github/workflows/publish.yml`: it re-runs `verifyPlugin` and `build`, then `signPlugin` and
`publishPlugin`. There is no approval step, so the tag push is the gate.

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
4. Commit on `master` and push the commit.
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
