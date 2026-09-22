# Release readiness gaps

Audit taken 2026-08-24, against the README, the implementation plan, `plugin.xml`, `gradle.properties`, `build.gradle.kts` and `.github/workflows/` as they stood at that point — not a snapshot to trust blindly later; re-check each item's "current state" line before acting on it if much time has passed.
Feature work (Phases 0–4, plus Phases 6–7: group membership visibility and the active-group / auto-join UI) and hardening (Phase 5: dynamic-plugin loading, Plugin Verifier CI matrix, VFS rename remap, palette settings, licence module + gating, cross-platform manual verification) are all done and verified — none of that is repeated here.
Each item below is written to be picked up standalone in its own session.

---

## ~~1. Arm the paid-feature gate — closed~~

**Closed (2026-09-05):** `plugin.xml` now has
`<product-descriptor code="PPRISMTABS" release-date="20260905" release-version="14" optional="true"/>`,
right after `<vendor>`. `gradle.properties`'s `pluginVersion` is bumped off `-SNAPSHOT` to `1.4.14`.
`release-date` is today's date as a placeholder — it's Marketplace-listing metadata, not validated
against the actual publish date, but it must be bumped to the real day this is tagged and published
(see item 3's "how to actually cut a release") before that tag goes out, since the listing/upload
items (5, 6, 8) are still open and this isn't shipping today.

`release-version`, unlike `release-date`, **is** validated, and the tag first landed with `1`, which
made the built zip an invalid plugin outright — `verifyPlugin` refused it with *"The
`<release-version>` parameter (1) format is invalid. Ensure it is an integer with at least two
digits"* and ran zero of its eight verifications. Corrected to `14`: `pluginVersion`'s first two
components with the dot dropped (1.4.14 → 14), which is the Marketplace convention.
`ProductDescriptorTest` keeps it in step with `pluginVersion`, and re-run `verifyPlugin` after
touching the descriptor all the same: the test checks the two agree, not that the Verifier accepts
the result.

**Superseded in part (2026-09-15):** this item originally had `release-version` moving on every
minor bump. It now moves only on a deliberately cut **major release**, about once a year, because
changing it resets every user's trial — see README "Trial resets and release cadence" and item 3's
release procedure. `release-date` moves with it, to that major release's publish day, and stays put
across the x.y.Z updates in between.

One consequence worth knowing, handled the same day: arming the gate also made
`runIdeForUiTests` an unlicensed paid build, which locked the paid features out from under the
whole uiTest suite (measured in the sandbox: `productCode=PPRISMTABS licenseOptional=true
isLicensed=false paid=false`). `RemoteRobotExtension` now entitles the plugin before each test and
`PaywallUiTest` un-entitles it deliberately — see `IdeaFrame.simulateEntitled` for what that state
can and cannot stand in for, and checklist item 9 in `docs/cross-platform-manual-verification.md`
for the real-licence checks that stay manual.

Verified live under `runIde` on 2026-09-05: the plugin stays loaded, the tool window shows the
free-tier upsell panel, and paid actions raise the register dialog — all three of the checklist's
conditions, confirmed by eye, not just from clean logs. `./gradlew test` passes, and the
`optional="true"` attribute was confirmed to survive `patchPluginXml` into the built
`build/tmp/patchPluginXml/plugin.xml` unchanged.

**Original problem, for context:** `PrismTabsLicense.paidFeaturesEnabled()` returns `true`
unconditionally while `getProductCode()` is null, so every gate was wired but inert without a
`<product-descriptor>`. `optional="true"` is **not** cosmetic: it sets
`IdeaPluginDescriptorImpl.isLicenseOptional`, which is what tells the platform the plugin may load
and run without a licence (the freemium contract). With a bare `<product-descriptor>` the platform
treats PrismTabs as fully paid and **disables the whole plugin** the moment an unlicensed user
dismisses the register dialog — the free tier never loads, and `PrismTabsLicense.decide()`'s "fall
back to the free tier" branch is unreachable in production. Verified against the platform bytecode
(`javap com.intellij.ide.plugins.IdeaPluginDescriptorImpl` → `boolean isLicenseOptional`) and
reproduced live under `runIde` on 2026-09-01, then again with the real `product-descriptor` in
place on 2026-09-05.

This was everything else below's precondition for being meaningful — done first, as planned.

## ~~2. Sign the plugin build — closed~~

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

**Closed (2026-08-26):** the source `.pem` files (`chain.pem`, `private_key.pem` +
`private_key.pass`, plus the root/intermediate keys and their passwords, kept in case the leaf
cert ever needs reissuing off the same root before it expires in 2036) have been copied out of the
now-defunct Claude session scratch directory into permanent, encrypted storage (password manager).
The scratch copy no longer exists.

**Updated (2026-09-05):** the interim copy at `~/Documents/PrismTabs GitHub CI CD secrets/` was
plaintext on disk, not actually encrypted — that's now closed too. All ten files are imported as
attachments in a KeePassXC vault at `~/.local/share/keepassxc/prismtabs-secrets.kdbx`, group
`PrismTabsCICD`, each verified byte-for-byte against the original before it was shredded (`shred
-u -z`) and the plaintext directory removed. To rebuild `signPlugin`'s env vars from the vault:
`keepassxc-cli attachment-export <db> PrismTabsCICD/chain.pem chain.pem <out>` (same pattern for
`private_key.pem`/`private_key.pass`), then export their contents as `CERTIFICATE_CHAIN`/
`PRIVATE_KEY`/`PRIVATE_KEY_PASSWORD`.

## ~~3. Wire (or deliberately skip) CI publishing — closed~~

**Decision (2026-08-24):** automate, via a tag-triggered workflow, gated behind a manual approval
so it can't race ahead of the cross-platform testing pass. Reasoning: publishing is a single,
OS-independent packaging step (the signed ZIP is the same bytes regardless of which OS built it),
which is orthogonal to the fact that UI testing itself needs three physical machines (Linux, main
dev box; Windows and Mac, pulled-and-tested separately) — the two don't have to be coupled.

**Current state:** `.github/workflows/publish.yml` added. Triggers on `v*` tags, runs on a single
`ubuntu-latest` runner (`build verifyPlugin` as a safety net, then `signPlugin`, then
`publishPlugin`). No GitHub Environment gate — dropped, see below; the tag push itself is the gate.

**Closed:**
- ~~Create the `marketplace-publish` environment with a required reviewer~~ — dropped. Required
  reviewers on GitHub Environments need Team/Enterprise for private repos (free-plan private repos
  don't get environment protection rules — that's a public-repo-only freebie), and this is a
  private individual repo on the free plan. `publish.yml` no longer references an environment; the
  gate is the tag push itself — only push a `v*` tag once the Windows/Mac cross-platform pass is
  done, and keep tag pushes out of any scripted/automated flow.
- Item 2 (signing) — fully closed: `build.gradle.kts` is wired, a chain is generated and verified,
  `CERTIFICATE_CHAIN`/`PRIVATE_KEY`/`PRIVATE_KEY_PASSWORD` are set as repo secrets, and the source
  `.pem` files are backed up in permanent, encrypted storage.
- `PUBLISH_TOKEN` — set as a repo secret 2026-08-26 (confirmed via `gh secret list`), sourced from
  a Marketplace permanent token created in the vendor account. `build.gradle.kts`'s read path for
  it (`publishing { token = ... }`) was already wired.

All three of `publish.yml`'s secrets now exist. `signPlugin` and `publishPlugin` both have
everything they need — a `v*` tag push today would sign and publish for real. Nothing left to wire
here; the workflow is fully ready. What's still outstanding is upstream of this workflow: item 4's
trader status/banking (needed for the *listing* to actually be sellable, not for this token to
work) and items 5–8 (the listing itself doesn't exist yet, so there's nothing to publish to until
that's created by hand once).

**How to actually cut a release** (this is the gate — do it deliberately, once the Windows/Mac
cross-platform pass is done):

```bash
# 1. On master, in one commit: set the release version + descriptor, stamp the changelog.
#    `pluginVersion` in gradle.properties has been a plain number since 267202a (2026-09-05) —
#    there is no -SNAPSHOT suffix to strip and no per-commit bump. Move it only here, and only
#    if the number changes. First decide whether this is a MAJOR RELEASE — cut deliberately,
#    about once a year, because it resets every user's trial (README "Trial resets and release
#    cadence"). Conventional Commit types do not decide that on their own:
#      - Update (the default, features included): bump only the third component
#        (1.4.14 -> 1.4.15). Leave release-date and release-version untouched.
#      - Major release: bump the second component and zero the third (1.4.x -> 1.5.0), or, after
#        1.9, the first (1.9.x -> 2.0.0) — the minor must stay one digit (see item 1).
#        Set release-version to the new first two components with the dot dropped ("15", "20")
#        and release-date to the real ship date YYYYMMDD. The very first publish counts as one
#        for release-date, which is still a placeholder.
#      - gradle.properties: pluginVersion -> the release number (if it moves)
#      - ./gradlew patchChangelog  — stamps [Unreleased] with the version and date
#    Then ./gradlew verifyPlugin (nothing else in the build catches a bad release-version),
#    commit on master, push the commit.
# 2. Tag that commit and push the tag.
git tag -a v1.4.14 -m "Release 1.4.14"
git push origin v1.4.14
```

- Annotated tag (`-a`/`-m`), not lightweight — matches the `v*` pattern `publish.yml` triggers on,
  and should match `pluginVersion` so the built artifact's version lines up with the tag.
- `git push origin v1.4.14` pushes only the tag, not commits — the commit it points to must already
  be on `master` and pushed.
- To undo an accidental push: `git push origin :refs/tags/v1.4.14` then `git tag -d v1.4.14` — but
  the workflow may already be running by the time you do this, since there's no approval pause.
- There is no post-release step: the version stays at the released number through the next
  development cycle until the next release cut moves it again.

## ~~4. JetBrains Marketplace vendor account — closed~~

**Current state (2026-08-26):** vendor profile created; a Marketplace permanent token was also
generated and is now set as the `PUBLISH_TOKEN` repo secret (closes item 3). Account is set to
**trader** status and banking details have been submitted. Item closed.

## 5. Upload and configure the product listing

**Current state:** plugin has never been uploaded.

**To close, once items 1–4 are done:**
- Upload the plugin as a **first release** (not an update).
- Under **Sales Info → Add to Marketplace**: confirm/register product code `PPRISMTABS`, choose
  **Freemium**, then the **Yearly Subscription without a Fallback License** scheme, set the
  personal price to $14/year and the commercial price to $29/year per user (matches the website's
  pricing card as of `docs(pricing)` / `d57b11b`, 2026-09-06), and set the trial to **14 days**
  rather than the 30-day default (decided 2026-09-15; the options are 7, 10, 14 or 30 — see README
  "Marketplace publishing prep"). Check that `prismtabs.com`'s pricing card says 14 days too.
- Attach the licence (item 7): paste the `prismtabs.com/eula` URL into the licence field at upload
  time (per the best-practices doc it's added here, not via a `plugin.xml` tag); paste the full
  EULA text as the fallback if the form wants text rather than a link.

## 6. Write the actual listing content

**Current state (2026-09-02):** copy and metadata are done. The drafting is captured in
[`marketplace-listing.md`](marketplace-listing.md) — tagline, description, change notes, Technical
Information link values, and tags — so the upload step (item 5) is a paste job. What changed this
pass:

- `plugin.xml`'s `<description>` was expanded from its first-draft prose to the full listing body:
  the same overview, plus an explicit **Free forever** / **Paid** feature split (matching the
  README's "Marketplace publishing prep" section and the website) as two `<ul>` lists, then the
  existing links block last. No price string in it deliberately — the archive for a version is
  frozen, the EULA carries a price-change clause, and the Marketplace has its own pricing field;
  "a subscription" in the copy, the number lives only on the Sales Info scheme and the website.
  Kept sourced from `plugin.xml` (one copy) — do **not** detach it by editing in the admin UI.
- `CHANGELOG.md`'s `[Unreleased]` entry — which becomes `<change-notes>` at build time — was
  reworded to `naming-and-terminology.md` house style: "Chrome-style colour groups" →
  "Colour-coded tab groups" (item 13 had already fixed this in `plugin.xml`; the changelog had
  drifted) and the "Tab Groups" tool window → the "PrismTabs" tool window.
- **Tagline:** there is no dedicated field — Marketplace and the IDE Plugins panel both show the
  description's first line as the summary. That line ("Colour-coded tab groups for IntelliJ editor
  tabs.") is fit for purpose as-is.

**Updated (2026-09-07):** the "copy done, upload is a paste job" conclusion still holds, but the
2026-09-02 snapshot above predates two passes of feature copy. Since then `plugin.xml`'s
`<description>` and `CHANGELOG.md`'s `[Unreleased]` entry have both absorbed the Phases 6–7 wording
("mark a group active", the PrismTabs tool window expanding to a group's files, "Remove from
Group") and the already-open-file one-click join (`a10055f`); `983296d` also dropped the Project
View active-group entry, leaving only the tab-popup row. Both are still single-sourced from
`plugin.xml` / `CHANGELOG.md` — re-read them at upload time rather than trusting the bullets above.

**Updated (2026-09-20):** the pre-upload re-read is done. `<description>` matches the shipped
build. `CHANGELOG.md`'s `[Unreleased]` entry lost its "Fixed" section, since a first release has no
earlier version for those to be fixes against, and `<vendor email>` is now `contact@prismtabs.com`,
the address the EULA and the website use (`eada49e`). The four Technical Information links all
return 200. `verifyPlugin` reports Compatible on all eight IDEs, 2024.2 to 2026.3. Re-read
`<description>` and the changelog once more at upload time only if either changes before then.

**To close:**
- Under **Technical Information**, fill the dedicated link fields (values in `marketplace-listing.md`):
  *Issue tracker* → the feedback repo, *Documentation* → `prismtabs.com/docs`, *Website* →
  `prismtabs.com`, *Source code* → leave empty (repo is private, item 9; best-practices requires
  external links to be reachable).
- Tags — proposed set (`User Interface`, `Editor`, `Productivity`) in `marketplace-listing.md`;
  JetBrains maps free text onto their controlled list at review.
- ~~**Screenshots.**~~ — closed 2026-09-20 (`1eea92e`). All five shots (≥1200×760) live in
  `docs/marketplace/`, upload in filename order: `01-swatch-popup-and-tabs-dark.png`,
  `02-tool-window-and-tabs-dark.png`, `03-palette-settings-dark.png`,
  `04-tool-window-and-tabs-light.png`, `05-files-in-group-dark.png`. `02` and `04` were re-shot on
  2026-09-20: every member file's path reads in full, `Tests` is marked active and `Docs` hidden,
  and the licensed UI is showing, not the free-tier upsell panel. The website's hero images are
  cropped from these two files (`prismtabs-website` `d5b524a`). `05` was added the same day and
  closes the gap this bullet used to carry: it is the only shot of the free `Files…` chooser, and
  so the only one showing that a group lists every file it holds whether or not that file still
  has a tab open. `01` and `03` were checked against the shipped build the same day: no
  user-visible text changed after they were shot. Per-shot descriptions are in
  [`marketplace-listing.md`](marketplace-listing.md).

## ~~7. Choose and prepare a License/EULA for the paid listing — closed~~

**Closed (2026-09-01):** the EULA is drafted, published, and mirror-synced; the solicitor review
in the first "To close" bullet was always optional and has been deliberately declined (no solicitor
being engaged). The only remaining action — pasting the `prismtabs.com/eula` URL into the licence
field — is a step *inside* item 5's upload, not separate work here, and is recorded there. Nothing
left in this item.

Two non-blocking checks to run before the upload, noted so they aren't missed: the
`contact@prismtabs.com` mailbox (the only contact route in the agreement, and where §2's
interoperability requests must go, and now the vendor email on the listing too) must actually
receive mail — checked 2026-09-20: its MX record points at Purelymail, a test message was
delivered, and a reply sent from the address arrived; and if `EULA.md` is edited before the listing goes live, bump its "Last updated" date
and let the sync check confirm the website copy matches.

**Current state (2026-08-26):** drafted and published; not yet attached to the listing. Found
during an audit against
[`best-practices-for-listing.html`](https://plugins.jetbrains.com/docs/marketplace/best-practices-for-listing.html),
which states plainly that a license — either an open-source license or a EULA — is **mandatory to
publish any plugin on the Marketplace**, selected/uploaded during the listing process. This is
distinct from the repo's own `LICENSE` file question (item 9 below, correctly closed as N/A):
that's about the source repo, this is about the terms a paying user of the *binary* agrees to.

`docs/EULA.md` has a full draft: grant of use split between always-free features and
subscription/trial-gated paid features, explicit no-fallback-licence behaviour matching item 5's
"Yearly Subscription without a Fallback License" scheme, no-warranty/limitation-of-liability
clauses, no-redistribution/no-reverse-engineering restrictions (carved out for UK CDPA 1988
interoperability rights, since those can't be excluded by contract), a data/privacy section
confirming no telemetry (matches item 11's finding), and governing law set to Scots law/Scottish
courts — Andrzej is Scotland-resident. Not legal advice; hasn't been reviewed by a solicitor.

**Published (2026-08-26):** it now renders at `prismtabs.com/eula` (`eula.html` in
`prismtabs-website`, which is the canonical copy; this file is a mirror kept verbatim against it —
diffed word for word, not just eyeballed). A pair of `eula-sync.yml` GitHub Actions workflows, one
in each repository and each reading the other over a non-expiring read-only deploy key, re-run that
comparison on every relevant push and weekly, so an edit on either side that isn't carried across
goes noticed rather than unnoticed; see "Keeping the EULA in sync" in the website repo's
`README.md` for the full mechanism.

**Resolved:**
- Solicitor review — declined. It was always an optional bullet; no solicitor is being engaged.
  The draft is not legal advice and hasn't been reviewed by one.
- Attaching the `prismtabs.com/eula` URL during item 5's upload step (the license link/text is
  added at upload time per the best-practices doc, not via a `plugin.xml` tag; pasting the text
  directly is the fallback if the form wants a link) — carried into item 5 as the place it
  actually happens.

## 8. Submit for review

**Current state:** blocked on everything above. The first upload was submitted and refused over
Internal API usage; that cause is fixed (item 15, closed) and the resubmission needs a version bump.
JetBrains' stated turnaround is about two business days once submitted.

---

## Smaller gaps not on the README's own list

These were noticed during the audit but aren't called out anywhere in the README or plan. None
block publishing on their own, but each is worth a deliberate decision rather than staying an
oversight.

## ~~9. No `LICENSE` file — intentionally N/A~~

**Current state:** repo root has no `LICENSE` or `LICENSE.md`.

**Status:** This is not a gap. The repo is closed-source and private; the plugin is distributed as
a binary via JetBrains Marketplace, not as source. A LICENSE file is relevant only for public
repositories or source-code distribution — neither applies here. "All rights reserved" (the default
absence of a LICENSE file) is the correct stance for proprietary commercial code.

## ~~10. No change-notes anywhere — closed~~

**Status:** Closed 2026-08-24. Added the `org.jetbrains.changelog` Gradle plugin (2.5.0) and a
`CHANGELOG.md` in Keep a Changelog style with the first `[Unreleased]` entry (the free/paid feature
split from "Marketplace publishing prep" above). `build.gradle.kts`'s `pluginConfiguration` now sets
`changeNotes` from `changelog.getUnreleased()`, rendered to HTML — so `patchPluginXml` fills
`plugin.xml`'s `<change-notes>` automatically at build time; confirmed by inspecting
`build/tmp/patchPluginXml/plugin.xml` after a build. Nothing left to do here beyond ordinary
upkeep: add entries to `CHANGELOG.md`'s `[Unreleased]` section as features land, and run
`patchChangelog` to stamp/date a section when cutting a release (item 1's job, not this one's).

## ~~11. Privacy policy — checked, not needed~~

**Current state:** confirmed via `grep` across `src/main/kotlin` that the only network-adjacent
code is `LicenseSignatureVerification`'s reference to JetBrains' own reference implementation in a
doc comment — no outbound HTTP calls anywhere in the plugin. `PrismTabsLicense` only calls the
platform's local `LicensingFacade`. This means JetBrains' privacy-policy-URL requirement (for
plugins that transmit user data) does not apply. No action needed — recorded here so a future
session doesn't have to re-derive it from scratch, and so it gets re-checked if licence
verification ever grows a network call.

## ~~12. Vendor URL pointed at a private repo — closed~~

**Status:** Closed 2026-08-26. Found during the same best-practices audit as item 7: `plugin.xml`'s
`<vendor url="...">` pointed at `github.com/yendrrek/prismtabs`, which is private (item 9), so the
link was unreachable from the internet — a direct violation of the best-practices page's "external
links must be reachable" rule. Fixed by pointing `url` at `https://prismtabs.com/` instead — the
plugin's own public documentation site (confirmed live and reachable before wiring it in).

## ~~13. Description referenced a third-party brand — closed~~

**Status:** Closed 2026-08-26. `plugin.xml`'s `<description>` opened with "Chrome-style colour
groups…" — the best-practices page explicitly lists "brand references you don't own" as something
to avoid. Reworded to "Colour-coded tab groups…". While editing, also fixed a second, unrelated
accuracy issue found the same pass: the description still said the tool window can "collapse … or
expand" a group, but the shipped UI (Phase 4) uses "Hide/Show Group" wording — the description now
says "hide … or show" to match what users actually see.

## ~~14. No public place for users to report bugs — closed~~

**Status:** Closed 2026-08-27. The source repository is private (item 9), so a user who hit a bug
had nowhere to report it but the vendor email — and a Marketplace listing with an empty *Issue
tracker* field gives a prospective buyer no evidence the plugin is maintained.

`github.com/prismtabs/prismtabs-feedback` is now public and holds no code, only issues: a README, a
`SECURITY.md`, and two YAML issue forms. Design decisions worth not re-litigating:

- **One tracker for every edition.** Free, Pro and trial reports all land in the same place. It
  keeps the support links to a single URL and makes active development on the paid tier visible to
  free users, who are also the source of a good share of the feature ideas.
- **Which edition the reporter runs is a form field, not a label.** Labels record triage decisions
  and are applied by the maintainer; a self-reported fact belongs in the issue body, where it stays
  searchable without implying it has been verified.
- **`planned` and `pro` are separate labels, not one `planned-pro`.** They are orthogonal, so
  composing them allows filtering on either axis; a compound label would need a `planned-free`
  twin to answer the mirror question.
- **`blank_issues_enabled: false`, with `config.yml` routing purchases to email.** Licensing,
  billing and refund questions carry order IDs and email addresses, and this tracker is public and
  indexed. This is the one thing here that would be genuinely hard to undo after the fact, so the
  route is closed off before an issue can be opened rather than moderated afterwards.
- **Discussions, wiki and projects are off.** A single surface is less to keep up with for a solo
  maintainer, and a stale public board reads worse than no board.

Issue #1 is pinned and locked: it carries the roadmap filters, a table of where to send what, and
the three most-requested things the platform cannot do (README §"Constraints that are not going
away" 1–3), so those get answered before they are asked.

Still to do here is item 6's Technical Information step — pointing the listing's *Issue tracker*
field at the repository — which cannot happen until the listing exists.

---

## 15. Internal API: closed — the plugin uses none

**Current state:** closed 2026-09-22. All three call sites the Marketplace refused in 1.4.14 have
public replacements, `verifyPlugin` writes no `internal-api-usages.txt` on either end of the matrix
(checked on IU-242.26775.15 and IU-263.5153.40), and `INTERNAL_API_USAGES` is back in
`failureLevel` where the Verifier puts it by default.

- **Open path** — `FileEditorOpenOptions` + `FileEditorManagerEx.openFile` → the public
  `FileEditorManager.openFile` via `PlatformTabOpen`.
- **Licence check** — `PluginManager.getPluginByClass` → a read of the bundled `plugin.xml`.
- **Repaint** — `FileEditorManagerImpl.queueUpdateFile` → the public
  `FileEditorManager.updateFilePresentation(VirtualFile)`, whose implementation calls
  `queueUpdateFile` itself. `requestAll` iterates `FileEditorManager.openFiles`.

**The repaint was twice called impossible, and both times that was wrong.** The method was missed
because a `javap` dump of `FileEditorManager` was read through `head -40`, and it sits below the
cut. On that basis a Marketplace appeal was drafted, `IJPL-256289` was filed, and a working but
unnecessary reimplementation was written and committed (`ff36c2c`) before the Marketplace reviewer
pointed at the actual method. The rule for next time is in CLAUDE.md §"Internal API": never reason
from truncated output.

**Still to do:**

1. Reply to the Marketplace thread: all three call sites now use public API, and thank the reviewer
   for pointing at `updateFilePresentation` — no exemption is being asked for.
2. Close [IJPL-256289](https://youtrack.jetbrains.com/issue/IJPL-256289/editorTabColorProvider-has-no-public-my-answer-has-changed-call)
   as invalid, saying the call already exists. Leaving it open would have JetBrains triage a
   request for something they already ship.
3. Bump `pluginVersion` to 1.4.15 and upload — the Marketplace will not take a second zip under
   `1.4.14`. Item 3's release procedure applies, and `release-version` stays at `14`: a
   third-component update, not a major release, so it must not reset anyone's trial.

**Do not** reintroduce an internal call to make something easier. `acceptedInternalApiUsages` in
`build.gradle.kts` is empty and `verifyInternalApiAllowlist` fails the build on any addition; that
task also fails if `INTERNAL_API_USAGES` is dropped from `failureLevel` again. When something looks
unreachable, check the *annotation* with `javap` rather than the package name — `EditorWindow`,
`EditorComposite` and `TabInfo` all live in `impl` packages and carry no annotation at all. A
Kotlin-`internal` member is genuinely off-limits: the compiler refuses it, and reaching it from Java
to dodge that is the circumvention JetBrains warn about.
