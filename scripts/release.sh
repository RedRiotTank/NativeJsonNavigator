#!/usr/bin/env bash
#
# Cut and publish a release with one command.
#
# Usage:
#   scripts/release.sh <version> [options]
#   scripts/release.sh 1.1.0
#   scripts/release.sh 1.1.0 --dry-run
#   scripts/release.sh 1.1.0 --notes notes.md --wait
#
# What it does:
#   1. Verifies a clean, up-to-date master checkout.
#   2. Runs the full test suite (and GPG signing when a local key is available).
#   3. Updates pom.xml (version + SCM tag) and the README dependency snippets.
#   4. Commits, tags (vX.Y.Z), pushes master and the tag.
#   5. Publishes a GitHub Release, which triggers the Maven Central workflow.
#
# Options:
#   --notes <file>  Use custom release notes instead of GitHub-generated notes.
#   --wait          Wait for the release workflow and report the result.
#   --dry-run       Print the plan without changing anything.
#   --yes, -y       Skip the confirmation prompt.
#   --prerelease    Mark release as prerelease on GitHub.
#   --draft         Create release as a draft on GitHub.
#   --sign, -s      Sign the git tag with GPG (git tag -s).
#
set -euo pipefail

REPO=""
NOTES_FILE=""
DRY_RUN=false
ASSUME_YES=false
WAIT=false
PRERELEASE=false
DRAFT=false
SIGN_TAG=false
VERSION=""

usage() {
  sed -n '2,26p' "$0" | sed 's/^# \{0,1\}//'
}

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --notes)
      NOTES_FILE="${2:-}"
      [[ -n "$NOTES_FILE" && -f "$NOTES_FILE" ]] || fail "--notes requires an existing file"
      shift 2
      ;;
    --wait)
      WAIT=true
      shift
      ;;
    --dry-run)
      DRY_RUN=true
      shift
      ;;
    --yes|-y)
      ASSUME_YES=true
      shift
      ;;
    --prerelease)
      PRERELEASE=true
      shift
      ;;
    --draft)
      DRAFT=true
      shift
      ;;
    --sign|-s)
      SIGN_TAG=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    -*)
      fail "Unknown option: $1"
      ;;
    *)
      [[ -z "$VERSION" ]] || fail "Only one version may be supplied"
      VERSION="$1"
      shift
      ;;
  esac
done

[[ -n "$VERSION" ]] || { usage; exit 2; }
[[ -f pom.xml && -x ./mvnw ]] || fail "Run this script from the repository root"
command -v gh >/dev/null || fail "gh (GitHub CLI) is required"
command -v git >/dev/null || fail "git is required"
command -v python3 >/dev/null || fail "python3 is required"

REPO=$(gh repo view --json nameWithOwner -q .nameWithOwner 2>/dev/null) \
  || fail "Could not determine the GitHub repository (is gh authenticated?)"

git fetch --quiet origin master
CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD)
[[ "$CURRENT_BRANCH" == "master" ]] || fail "Releases must be cut from master (currently on '$CURRENT_BRANCH')"
[[ -z "$(git status --porcelain)" ]] || fail "Working tree is not clean. Commit or stash your changes first."
[[ "$(git rev-parse HEAD)" == "$(git rev-parse origin/master)" ]] \
  || fail "Local master is not up to date with origin/master. Pull first."

CURRENT_VERSION=$(python3 - <<'PY'
import xml.etree.ElementTree as ET
ns = {"m": "http://maven.apache.org/POM/4.0.0"}
print(ET.parse("pom.xml").getroot().find("m:version", ns).text.strip())
PY
)

if [[ "$VERSION" != "$CURRENT_VERSION" ]]; then
  IS_VALID=$(python3 - "$CURRENT_VERSION" "$VERSION" <<'PY'
import sys, re

def to_tuple(v):
    parts = re.split(r'[-.]', v)
    res = []
    for p in parts:
        res.append((0, int(p), '') if p.isdigit() else (1, 0, p))
    return res

curr, new = sys.argv[1], sys.argv[2]
print("true" if to_tuple(new) >= to_tuple(curr) else "false")
PY
)
  [[ "$IS_VALID" == "true" ]] || fail "New version $VERSION is lower than current version $CURRENT_VERSION"
fi

git rev-parse -q --verify "refs/tags/v$VERSION" >/dev/null && fail "Tag v$VERSION already exists"
[[ -z "$(git ls-remote --tags origin "refs/tags/v$VERSION")" ]] || fail "Tag v$VERSION already exists on origin"

./scripts/validate-release.sh --version "$VERSION" --tag "v$VERSION"

if ! grep -q "## \[$VERSION\]" CHANGELOG.md; then
  echo "WARNING: CHANGELOG.md has no '## [$VERSION]' section." >&2
  echo "         Release notes will be generated from git history." >&2
fi

echo
echo "Repository : $REPO"
echo "Version    : $CURRENT_VERSION -> $VERSION"
echo "Tag        : v$VERSION"
echo "Notes      : ${NOTES_FILE:-GitHub-generated}"
echo

if [[ "$DRY_RUN" == true ]]; then
  echo "Dry run: no files changed. Release steps that would run:"
  echo "  ./mvnw -B -ntp verify"
  echo "  update pom.xml, README.md"
  echo "  git commit -am 'release: v$VERSION'"
  echo "  git tag -a v$VERSION"
  echo "  git push origin master v$VERSION"
  echo "  gh release create v$VERSION"
  exit 0
fi

if [[ "$ASSUME_YES" != true ]]; then
  read -r -p "Proceed with the release? [y/N] " REPLY
  [[ "$REPLY" =~ ^[Yy]$ ]] || { echo "Aborted."; exit 1; }
fi

echo "==> Running full verification"
if [[ -f "$HOME/.config/native-json-navigator/gpg-passphrase" ]]; then
  export MAVEN_GPG_PASSPHRASE
  MAVEN_GPG_PASSPHRASE=$(cat "$HOME/.config/native-json-navigator/gpg-passphrase")
  ./mvnw -B -ntp -Prelease verify
else
  echo "No local GPG passphrase found; skipping the signature check (CI will sign)."
  ./mvnw -B -ntp verify
fi

if [[ "$VERSION" != "$CURRENT_VERSION" ]]; then
  echo "==> Updating version to $VERSION"
  VERSION="$VERSION" python3 - <<'PY'
import os
import re

version = os.environ["VERSION"]

pom = open("pom.xml", encoding="utf-8").read()
pom, n1 = re.subn(
    r"(<artifactId>native-json-navigator</artifactId>\s*<version>)[^<]+(</version>)",
    rf"\g<1>{version}\g<2>",
    pom,
    count=1,
)
pom, n2 = re.subn(
    r"(<scm>.*?<tag>)v[^<]+(</tag>)",
    rf"\g<1>v{version}\g<2>",
    pom,
    count=1,
    flags=re.S,
)
if n1 != 1 or n2 != 1:
    raise SystemExit("Could not update pom.xml version/tag")
open("pom.xml", "w", encoding="utf-8").write(pom)

readme = open("README.md", encoding="utf-8").read()
readme, n3 = re.subn(
    r"(<artifactId>native-json-navigator</artifactId>\s*<version>)[^<]+(</version>)",
    rf"\g<1>{version}\g<2>",
    readme,
    count=1,
)
readme, n4 = re.subn(
    r"(native-json-navigator:)[0-9][0-9A-Za-z.\-]*",
    rf"\g<1>{version}",
    readme,
)
if n3 != 1 or n4 != 1:
    raise SystemExit("Could not update README.md version snippets")
open("README.md", "w", encoding="utf-8").write(readme)
PY
  ./scripts/validate-release.sh --tag "v$VERSION"
fi

echo "==> Committing and tagging"
git add pom.xml README.md CHANGELOG.md
if ! git diff --cached --quiet; then
  git commit -m "release: v$VERSION"
else
  echo "No file changes to commit (version already set)."
fi
TAG_FLAG="-a"
if [[ "$SIGN_TAG" == true ]]; then
  TAG_FLAG="-s"
fi
git tag $TAG_FLAG "v$VERSION" -m "Native JSON Navigator $VERSION"

echo "==> Pushing master and v$VERSION"
git push origin master
git push origin "v$VERSION"

echo "==> Publishing GitHub release (triggers Maven Central deployment)"
RELEASE_FLAGS=()
if [[ "$PRERELEASE" == true || "$VERSION" =~ [-] ]]; then
  RELEASE_FLAGS+=(--prerelease)
fi
if [[ "$DRAFT" == true ]]; then
  RELEASE_FLAGS+=(--draft)
fi

if [[ -n "$NOTES_FILE" ]]; then
  gh release create "v$VERSION" --repo "$REPO" \
    --title "Native JSON Navigator $VERSION" --notes-file "$NOTES_FILE" "${RELEASE_FLAGS[@]}"
else
  gh release create "v$VERSION" --repo "$REPO" \
    --title "Native JSON Navigator $VERSION" --generate-notes "${RELEASE_FLAGS[@]}"
fi

echo
echo "Release v$VERSION created: https://github.com/$REPO/releases/tag/v$VERSION"
echo "Maven Central publication runs in .github/workflows/release.yml."

if [[ "$WAIT" == true ]]; then
  echo "==> Waiting for the release workflow"
  RUN_ID=""
  for ((attempt = 1; attempt <= 30; attempt++)); do
    RUN_ID=$(gh run list --repo "$REPO" --workflow release.yml --limit 20 \
      --json databaseId,headBranch,event \
      --jq "map(select(.headBranch == \"v$VERSION\" and .event == \"release\")) | .[0].databaseId // empty")
    [[ -n "$RUN_ID" ]] && break
    sleep 5
  done
  [[ -n "$RUN_ID" ]] || fail "Could not find the release workflow run"
  gh run watch "$RUN_ID" --repo "$REPO" --exit-status
  echo "Maven Central publication succeeded."
fi
