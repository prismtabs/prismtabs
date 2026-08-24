# Release readiness gaps

Audit taken 2026-08-24, against the README, the implementation plan, `plugin.xml`,
`gradle.properties`, `build.gradle.kts` and `.github/workflows/` as they stood at that point —
not a snapshot to trust blindly later; re-check each item's "current state" line before acting on
it if much time has passed. Feature work (Phases 0–4) and hardening (Phase 5: dynamic-plugin
loading, Plugin Verifier CI matrix, VFS rename remap, palette settings, licence module + gating,
cross-platform manual verification) are all done and verified — none of that is repeated here.
Each item below is written to be picked up standalone in its own session.

---

## 1. Arm the paid-feature gate

**Current state:** `plugin.xml` has no `<product-descriptor>` tag. `gradle.properties`'
`pluginVersion` is `1.4.2-SNAPSHOT`. `PrismTabsLicense.paidFeaturesEnabled()` already returns
`true` unconditionally while `getProductCode()` is null, so every gate is wired but inert.

**To close:**
- Pick the actual release date.
- Bump `pluginVersion` off `-SNAPSHOT` to the first paid release's version.
- Add `<product-descriptor code="PPRISMTABS" release-date="YYYYMMDD" release-version="10"/>` to
  `plugin.xml`.
- Re-run `runIde` once afterwards specifically to see the free tier as an unsubscribed user would
  — this is the first build where the gates do anything, so it's the first real chance to catch an
  over-gating bug live rather than in `PaywallUiTest`.

This is a purely mechanical, low-risk change but it's the one everything else below depends on
being meaningful — do it first.

## 2. Sign the plugin build

**Current state:** `build.gradle.kts` declares `zipSigner()` as a dependency (the tool), but
there is no `signing { certificateChain = ...; privateKey = ... }` block anywhere, and no
`signPlugin` task configuration. No certificate chain or private key exists yet. Grepped the whole
repo for `certificateChain`, `privateKey`, `CERTIFICATE_CHAIN`, `PRIVATE_KEY` — no hits anywhere,
including the workflows.

**Why it matters:** JetBrains Marketplace requires every plugin ZIP to be signed before upload.
Without this, `./gradlew signPlugin` has nothing to sign with and the upload will be rejected.

**To close:**
- Generate a self-signed certificate chain + private key per JetBrains' plugin-signing docs
  (`https://plugins.jetbrains.com/docs/intellij/plugin-signing.html`).
- Wire `signing {}` in `build.gradle.kts`, reading the chain/key/password from environment
  variables or Gradle properties — **never commit the private key**.
- Store the secrets in GitHub Actions secrets if CI is going to publish; otherwise this can stay a
  local-only manual step for the first release.
- Confirm `./gradlew signPlugin` produces a signed zip before the first Marketplace upload.

## 3. Wire (or deliberately skip) CI publishing

**Current state:** `.github/workflows/build.yml` runs `build` and `verifyPlugin` only. There is no
`publishPlugin` job, no `PUBLISH_TOKEN` secret referenced anywhere.

**To close (or explicitly decide against automating):**
- Decide: publish manually via the Marketplace web UI each release, or add a
  `publishPlugin`-driven workflow (typically gated on a git tag).
- If automating: generate a Marketplace permanent token (Marketplace account → My Tokens), store
  it as a repo secret, add a `release` job to a workflow that runs `./gradlew publishPlugin`.
- If staying manual: note that decision somewhere (this file or the README) so a future session
  doesn't assume CI publishing exists and skip the manual upload.

This can reasonably be deferred past the very first release (do the first upload by hand to learn
the Marketplace review flow), but should be a deliberate choice, not an oversight.

## 4. JetBrains Marketplace vendor account

**Current state:** not created. External to this repo — only Andrzej can do this.

**To close:**
- Create the vendor profile on JetBrains Marketplace.
- Set it to **trader** status (required for a paid listing).
- Submit banking details under Sales Info.

## 5. Upload and configure the product listing

**Current state:** plugin has never been uploaded.

**To close, once items 1–4 are done:**
- Upload the plugin as a **first release** (not an update).
- Under **Sales Info → Add to Marketplace**: confirm/register product code `PPRISMTABS`, choose
  **Freemium**, then the **Yearly Subscription without a Fallback License** scheme, set price to
  $10/year, leave the trial at its 30-day default.

## 6. Write the actual listing content

**Current state:** not started. `plugin.xml`'s `<description>` is a reasonable first draft of the
prose but nothing else exists.

**To close:**
- Tagline.
- Full description (can start from `plugin.xml`'s `<description>` and expand).
- At least one screenshot, ≥1200×760 — a good candidate set: the swatch popup mid-flow, the Tab
  Groups tool window with a few groups, and the palette settings page. None currently exist
  anywhere in the repo.
- Tags.

## 7. Submit for review

**Current state:** blocked on everything above. JetBrains' stated turnaround is about two business
days once submitted.

---

## Smaller gaps not on the README's own list

These were noticed during the audit but aren't called out anywhere in the README or plan. None
block publishing on their own, but each is worth a deliberate decision rather than staying an
oversight.

## 8. No `LICENSE` file — intentionally N/A

**Current state:** repo root has no `LICENSE` or `LICENSE.md`.

**Status:** This is not a gap. The repo is closed-source and private; the plugin is distributed as
a binary via JetBrains Marketplace, not as source. A LICENSE file is relevant only for public
repositories or source-code distribution — neither applies here. "All rights reserved" (the default
absence of a LICENSE file) is the correct stance for proprietary commercial code.

## 9. No change-notes anywhere — closed

**Status:** Closed 2026-08-24. Added the `org.jetbrains.changelog` Gradle plugin (2.5.0) and a
`CHANGELOG.md` in Keep a Changelog style with the first `[Unreleased]` entry (the free/paid feature
split from "Marketplace publishing prep" above). `build.gradle.kts`'s `pluginConfiguration` now sets
`changeNotes` from `changelog.getUnreleased()`, rendered to HTML — so `patchPluginXml` fills
`plugin.xml`'s `<change-notes>` automatically at build time; confirmed by inspecting
`build/tmp/patchPluginXml/plugin.xml` after a build. Nothing left to do here beyond ordinary
upkeep: add entries to `CHANGELOG.md`'s `[Unreleased]` section as features land, and run
`patchChangelog` to stamp/date a section when cutting a release (item 1's job, not this one's).

## 10. Privacy policy — checked, not needed

**Current state:** confirmed via `grep` across `src/main/kotlin` that the only network-adjacent
code is `LicenseSignatureVerification`'s reference to JetBrains' own reference implementation in a
doc comment — no outbound HTTP calls anywhere in the plugin. `PrismTabsLicense` only calls the
platform's local `LicensingFacade`. This means JetBrains' privacy-policy-URL requirement (for
plugins that transmit user data) does not apply. No action needed — recorded here so a future
session doesn't have to re-derive it from scratch, and so it gets re-checked if licence
verification ever grows a network call.
