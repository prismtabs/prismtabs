#!/usr/bin/env bash
# Cuts a release from dev: bumps the version, stamps the changelog, verifies the plugin, commits and
# pushes dev, fast-forwards master to it, waits for Build to pass, then pushes the v<version> tag,
# which starts Publish. See docs/releasing.md.
#
# Usage: scripts/release.sh [patch|minor|X.Y.Z] [--dry-run]
#   patch    third component +1 (default); release-version and release-date stay as they are
#   minor    second component +1, third reset; sets release-version and release-date, which resets
#            every user's trial
#   X.Y.Z    an explicit patch version, for example 1.4.20
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

kind=patch
dry_run=false
for arg in "$@"; do
  case "$arg" in
    --dry-run) dry_run=true ;;
    patch | minor) kind=$arg ;;
    [0-9]*.[0-9]*.[0-9]*) kind=explicit; explicit=$arg ;;
    *) echo "release: unknown argument '$arg'" >&2; exit 1 ;;
  esac
done

fail() { echo "release: $*" >&2; exit 1; }

current=$(sed -n 's/^pluginVersion *= *//p' gradle.properties | tr -d '[:space:]')
[ -n "$current" ] || fail "no pluginVersion in gradle.properties"
IFS=. read -r major minor patch <<<"$current"

case "$kind" in
  patch) version="$major.$minor.$((patch + 1))" ;;
  minor)
    if [ "$minor" -ge 9 ]; then major=$((major + 1)); minor=0; else minor=$((minor + 1)); fi
    version="$major.$minor.0"
    ;;
  explicit) version=$explicit ;;
esac
tag="v$version"

# The Marketplace's release-version is the first two components without the dot: 1.5 -> 15.
release_version="${version%%.*}$(echo "$version" | cut -d. -f2)"
release_date=$(date +%Y%m%d)

echo "Current version: $current"
echo "Release:         $version ($kind)"
if [ "$kind" = minor ]; then
  echo "release-version: $release_version, release-date: $release_date (resets every user's trial)"
fi
$dry_run && exit 0

[ "$(git branch --show-current)" = dev ] || fail "not on dev"
[ -z "$(git status --porcelain)" ] || fail "working tree is not clean"
git fetch --tags origin
git rev-parse -q --verify "refs/tags/$tag" >/dev/null && fail "$tag already exists"
[ "$(git rev-parse dev)" = "$(git rev-parse origin/dev)" ] || fail "dev is not in sync with origin/dev"
git merge-base --is-ancestor origin/master dev || fail "dev does not contain origin/master"

# patchChangelog would stamp an empty section, so refuse to release without entries.
awk '/^## \[Unreleased\]/ {on = 1; next} /^## \[/ {on = 0} on && /^- / {found = 1} END {exit !found}' CHANGELOG.md \
  || fail "CHANGELOG.md has no entries under [Unreleased]"

read -r -p "Release $tag from $(git rev-parse --short dev)? [y/N] " answer
[ "$answer" = y ] || fail "cancelled"

sed -i "s/^pluginVersion *=.*/pluginVersion = $version/" gradle.properties
if [ "$kind" = minor ]; then
  sed -i -E "s/(<product-descriptor [^>]*)release-date=\"[0-9]+\"/\1release-date=\"$release_date\"/; \
             s/(<product-descriptor [^>]*)release-version=\"[0-9]+\"/\1release-version=\"$release_version\"/" \
    src/main/resources/META-INF/plugin.xml
fi

./gradlew patchChangelog
./gradlew verifyPlugin

git add gradle.properties CHANGELOG.md src/main/resources/META-INF/plugin.xml
git commit -q -m "chore(release): release version $version" -m "Releases version $version."
git push origin dev

git switch master
git merge --ff-only origin/master
git merge --ff-only dev
git push origin master

sha=$(git rev-parse HEAD)
echo "Waiting for Build on $sha..."
run=""
for _ in $(seq 1 60); do
  run=$(gh run list --workflow Build --commit "$sha" --json databaseId -q '.[0].databaseId')
  [ -n "$run" ] && break
  sleep 10
done
[ -n "$run" ] || fail "no Build run appeared for $sha; tag not pushed"
gh run watch "$run" --exit-status || fail "Build failed; tag not pushed"

git tag -a "$tag" -m "Release $version"
git push origin "$tag"
git switch dev
echo "Pushed $tag. Publish: gh run list --workflow Publish --limit 1"
