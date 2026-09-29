#!/usr/bin/env bash
# Releases the version in gradle.properties: fast-forwards master to dev, pushes it, waits for
# Build to pass on that commit, then pushes the v<version> tag, which starts Publish.
# See docs/releasing.md for the steps that come before running this.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

version=$(sed -n 's/^pluginVersion *= *//p' gradle.properties | tr -d '[:space:]')
tag="v$version"

fail() { echo "release: $*" >&2; exit 1; }

[ -n "$version" ] || fail "no pluginVersion in gradle.properties"
[ -z "$(git status --porcelain)" ] || fail "working tree is not clean"
git fetch --tags origin
git rev-parse -q --verify "refs/tags/$tag" >/dev/null && fail "$tag already exists"
git merge-base --is-ancestor origin/master dev || fail "dev does not contain origin/master"

read -r -p "Release $tag from $(git rev-parse --short dev)? [y/N] " answer
[ "$answer" = y ] || fail "cancelled"

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
