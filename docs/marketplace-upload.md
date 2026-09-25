# Upload a release to JetBrains Marketplace by hand

Build, sign and upload a release from the dev machine. Use this for the first upload, which cannot
go through CI, and as the fallback if `.github/workflows/publish.yml` is unavailable. Tag-triggered
CI publishes every later release.

## Sign the plugin

The signing key lives in the KeePassXC vault `~/.local/share/keepassxc/prismtabs-secrets.kdbx`,
group `PrismTabsCICD`. Each file is an *attachment* of the entry with the same name; the entry's
Password field is empty.

1. Load the credentials into the shell. Each command asks for the vault password.

   ```bash
   DB=~/.local/share/keepassxc/prismtabs-secrets.kdbx
   export CERTIFICATE_CHAIN="$(keepassxc-cli attachment-export --stdout "$DB" PrismTabsCICD/chain.pem chain.pem)"
   export PRIVATE_KEY="$(keepassxc-cli attachment-export --stdout "$DB" PrismTabsCICD/private_key.pem private_key.pem)"
   export PRIVATE_KEY_PASSWORD="$(keepassxc-cli attachment-export --stdout "$DB" PrismTabsCICD/private_key.pass private_key.pass)"
   echo "chain=${#CERTIFICATE_CHAIN} key=${#PRIVATE_KEY} pass=${#PRIVATE_KEY_PASSWORD}"
   ```

   All three lengths must be non-zero (the chain is about 5,600). Do not add `-q`: it hides the
   password prompt and every error, so a failed export returns an empty variable.

2. Sign:

   ```bash
   ./gradlew signPlugin --console=plain
   ```

   With an empty `CERTIFICATE_CHAIN` or `PRIVATE_KEY`, `signPlugin` is skipped and Gradle still
   reports `BUILD SUCCESSFUL`. Check that the file exists:

   ```bash
   ls -l build/distributions/prismtabs-<version>-signed.zip
   ```

3. Verify the signature (optional). Run it as a separate Gradle invocation, and give it the root
   certificate only. Gradle 9 rejects `signPlugin verifyPluginSignature` in one command, and the
   verifier reports "not signed by provided certificate authority" when given the full chain.

   ```bash
   keepassxc-cli attachment-export "$DB" PrismTabsCICD/root_certificate.pem root_certificate.pem /dev/shm/root.pem
   CERTIFICATE_CHAIN="$(cat /dev/shm/root.pem)" ./gradlew verifyPluginSignature --console=plain
   rm /dev/shm/root.pem
   ```

4. Clear the secrets from the shell:

   ```bash
   unset CERTIFICATE_CHAIN PRIVATE_KEY PRIVATE_KEY_PASSWORD
   ```

Sign every release with this same key. Never generate a new one.

## Upload

Upload `build/distributions/prismtabs-<version>-signed.zip`, not the unsigned
`prismtabs-<version>.zip` next to it.

Sign again after any change to the code or to `plugin.xml`. `signPlugin` signs the zip that
`buildPlugin` produced, and an existing `-signed.zip` is not rebuilt by a later `buildPlugin` alone.

The Marketplace remembers the `release-date` of every `release-version` it has seen, including from a
refused upload. A different `release-date` for the same `release-version` fails with "You cannot
release updates with the same release version (14) but different release dates". The descriptor in
`plugin.xml` holds the registered pair (`14`, `20260921`); leave it alone.

For the first upload, add the plugin as a **new plugin** (not an update) on the vendor account. Then,
under **Sales Info → Add to Marketplace**:

| Field | Value |
|---|---|
| Product code | `PPRISMTABS` |
| Model | Freemium |
| Scheme | Yearly/Monthly Subscription without a Fallback License |
| Personal price | $14 per year, $1.46 per month |
| Commercial price | $29 per year per user, $3.02 per month per user |
| Community discounts | Open source: free licence (needs the yearly billing period) |
| Trial | 14 days (the default is 30) |
| Licence | `prismtabs.com/eula` URL, or the full EULA text if the form wants text |

Listing text, tags and screenshots are in [`marketplace-listing.md`](marketplace-listing.md) and
`docs/marketplace/`. Re-read `<description>` in `plugin.xml` and `CHANGELOG.md` before uploading.

## Set up CI for later releases

`publish.yml` reads four repository secrets: `CERTIFICATE_CHAIN`, `PRIVATE_KEY`,
`PRIVATE_KEY_PASSWORD` and `PUBLISH_TOKEN`. Push a `v*` tag after the cross-platform pass to publish.
