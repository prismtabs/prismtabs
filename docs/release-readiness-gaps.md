# Release readiness gaps

Audit taken 2026-08-24, against the README, the implementation plan, `plugin.xml`, `gradle.properties`, `build.gradle.kts` and `.github/workflows/` as they stood at that point — not a snapshot to trust blindly later; re-check each item's "current state" line before acting on it if much time has passed.
Feature work (Phases 0–4) and hardening (Phase 5: dynamic-plugin loading, Plugin Verifier CI matrix, VFS rename remap, palette settings, licence module + gating, cross-platform manual verification) are all done and verified — none of that is repeated here.
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

## ~~2. Sign the plugin build — closed, except for the root key's permanent backup~~

**Current state (2026-08-24):** `build.gradle.kts`'s `intellijPlatform {}` block now has a
`signing {}` sub-block (`certificateChain`, `privateKey`, `password`, all read from environment
variables only — never Gradle properties, so there's no path into a committed file) and a
`publishing { token = ... }` sub-block for item 3. A self-signed three-tier chain (root CA →
intermediate CA → leaf plugin-signing cert, `O=Andrzej Piontek`, 3650-day validity on all three,
root and intermediate correctly carry `basicConstraints CA:TRUE` — the intermediate needed an
explicit `-extfile` pass to get this; `openssl x509 -req` doesn't copy it from the CSR by default,
so a first attempt without it failed `openssl verify` with "invalid CA certificate") was generated
locally and confirmed end-to-end: `openssl verify` passes, the private key's modulus matches the
leaf cert's, and `CERTIFICATE_CHAIN`/`PRIVATE_KEY`/`PRIVATE_KEY_PASSWORD` set as env vars made
`./gradlew signPlugin` actually produce `prismtabs-<version>-signed.zip`.

`CERTIFICATE_CHAIN`, `PRIVATE_KEY` and `PRIVATE_KEY_PASSWORD` are now set as GitHub Actions repo secrets on `yendrrek/prismtabs` (via `gh secret set`, confirmed present with `gh secret list`) — this closes two of `publish.yml`'s three missing secrets (item 3); `PUBLISH_TOKEN` still needs item 4 (vendor account) done first.

**What's NOT done:** the source `.pem` files these secrets were read from still live only in a
Claude session's scratch directory (`/tmp/claude-1000/.../scratchpad/pluginsign/`, files:
`chain.pem`, `private_key.pem` + `private_key.pass`, plus the root/intermediate keys and their
passwords, kept in case the leaf cert ever needs reissuing off the same root before it expires in
2036). That directory is ephemeral and **GitHub does not let you read a secret's value back** once
set — this scratch copy is the only remaining copy of the root and intermediate keys anywhere.
Before it's cleaned up (by the session, or by hand):
- Copy all of it into permanent, encrypted storage (password manager or equivalent) — losing the
  root key means starting the whole chain over, including re-adding the GitHub secrets from a new
  chain.
- Once copied out, delete the scratch directory's copies.

## 3. Wire (or deliberately skip) CI publishing — decided, partially wired

**Decision (2026-08-24):** automate, via a tag-triggered workflow, gated behind a manual approval
so it can't race ahead of the cross-platform testing pass. Reasoning: publishing is a single,
OS-independent packaging step (the signed ZIP is the same bytes regardless of which OS built it),
which is orthogonal to the fact that UI testing itself needs three physical machines (Linux, main
dev box; Windows and Mac, pulled-and-tested separately) — the two don't have to be coupled.

**Current state:** `.github/workflows/publish.yml` added. Triggers on `v*` tags, runs on a single
`ubuntu-latest` runner (`build verifyPlugin` as a safety net, then `signPlugin`, then
`publishPlugin`), and sits behind a `marketplace-publish` GitHub Environment used purely as a
manual-approval gate.

**Still to close before this workflow can actually succeed:**
- ~~Create the `marketplace-publish` environment with a required reviewer~~ — dropped. Required
  reviewers on GitHub Environments need Team/Enterprise for private repos (free-plan private repos
  don't get environment protection rules — that's a public-repo-only freebie), and this is a
  private individual repo on the free plan. `publish.yml` no longer references an environment; the
  gate is the tag push itself — only push a `v*` tag once the Windows/Mac cross-platform pass is
  done, and keep tag pushes out of any scripted/automated flow.
- Item 2 (signing) — closed: `build.gradle.kts` is wired, a chain is generated and verified, and
  `CERTIFICATE_CHAIN`/`PRIVATE_KEY`/`PRIVATE_KEY_PASSWORD` are set as repo secrets. Only remaining
  loose end is backing up the source `.pem` files permanently — see item 2's "What's NOT done".
- Item 4 (vendor account) — the workflow expects secret `PUBLISH_TOKEN` (a Marketplace permanent
  token from Marketplace account → My Tokens). `build.gradle.kts`'s read path for it
  (`publishing { token = ... }`) is already wired.

`signPlugin` now has everything it needs — a tag push today would sign successfully. Only
`publishPlugin` would still fail, with a clear "not set" style error on `PUBLISH_TOKEN`, until item
4 (vendor account) closes — which is the deliberate, visible failure mode versus a silent no-op.
The actual first release can still go out by hand via the Marketplace web UI if that's reached
before `PUBLISH_TOKEN` exists; nothing here forces CI to be the first upload path.

**How to actually cut a release** (this is the gate — do it deliberately, once the Windows/Mac
cross-platform pass is done):

```bash
# 1. bump pluginVersion in gradle.properties off -SNAPSHOT, commit it on master, push it
git tag -a v1.4.8 -m "Release 1.4.8"
git push origin v1.4.8
```

- Annotated tag (`-a`/`-m`), not lightweight — matches the `v*` pattern `publish.yml` triggers on,
  and should match `pluginVersion` so the built artifact's version lines up with the tag.
- `git push origin v1.4.8` pushes only the tag, not commits — the commit it points to must already
  be on `master` and pushed.
- To undo an accidental push: `git push origin :refs/tags/v1.4.8` then `git tag -d v1.4.8` — but
  the workflow may already be running by the time you do this, since there's no approval pause.

## 4. JetBrains Marketplace vendor account

**Current state (2026-08-25):** vendor profile created. Trader status and banking details are not
done yet — the Marketplace UI hasn't prompted for banking info at account-creation time; it's
expected to ask for it later, likely when actually setting the plugin up for a paid listing (item
5's Sales Info step). External to this repo — only Andrzej can do this.

**To close:**
- Set the profile to **trader** status (required for a paid listing) — check whether this is
  offered directly on the vendor profile or only surfaces once Sales Info / Add to Marketplace is
  reached in item 5.
- Submit banking details under Sales Info, whenever JetBrains actually prompts for them.

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

## ~~8. No `LICENSE` file — intentionally N/A~~

**Current state:** repo root has no `LICENSE` or `LICENSE.md`.

**Status:** This is not a gap. The repo is closed-source and private; the plugin is distributed as
a binary via JetBrains Marketplace, not as source. A LICENSE file is relevant only for public
repositories or source-code distribution — neither applies here. "All rights reserved" (the default
absence of a LICENSE file) is the correct stance for proprietary commercial code.

## ~~9. No change-notes anywhere — closed~~

**Status:** Closed 2026-08-24. Added the `org.jetbrains.changelog` Gradle plugin (2.5.0) and a
`CHANGELOG.md` in Keep a Changelog style with the first `[Unreleased]` entry (the free/paid feature
split from "Marketplace publishing prep" above). `build.gradle.kts`'s `pluginConfiguration` now sets
`changeNotes` from `changelog.getUnreleased()`, rendered to HTML — so `patchPluginXml` fills
`plugin.xml`'s `<change-notes>` automatically at build time; confirmed by inspecting
`build/tmp/patchPluginXml/plugin.xml` after a build. Nothing left to do here beyond ordinary
upkeep: add entries to `CHANGELOG.md`'s `[Unreleased]` section as features land, and run
`patchChangelog` to stamp/date a section when cutting a release (item 1's job, not this one's).

## ~~10. Privacy policy — checked, not needed~~

**Current state:** confirmed via `grep` across `src/main/kotlin` that the only network-adjacent
code is `LicenseSignatureVerification`'s reference to JetBrains' own reference implementation in a
doc comment — no outbound HTTP calls anywhere in the plugin. `PrismTabsLicense` only calls the
platform's local `LicensingFacade`. This means JetBrains' privacy-policy-URL requirement (for
plugins that transmit user data) does not apply. No action needed — recorded here so a future
session doesn't have to re-derive it from scratch, and so it gets re-checked if licence
verification ever grows a network call.
