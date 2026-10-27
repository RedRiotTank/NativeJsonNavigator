#!/usr/bin/env bash
#
# Validates that the current pom.xml (or --version) is safe to publish.
#
# Usage:
#   scripts/validate-release.sh [--version X.Y.Z] [--tag vX.Y.Z]
#
# Checks:
#   - coordinates are the expected Maven Central coordinates
#   - version is a valid, non-SNAPSHOT release version
#   - when --tag is given, tag must be exactly "v" + version
#   - the version is not already published on Maven Central
#
# Exits 0 when the release can proceed, 1 otherwise.
#
set -euo pipefail

fail() {
  echo "::error::$*" >&2
  echo "ERROR: $*" >&2
  exit 1
}

EXPECTED_GROUP="io.github.redriottank"
EXPECTED_ARTIFACT="native-json-navigator"
VERSION=""
TAG=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --version)
      VERSION="${2:-}"
      shift 2
      ;;
    --tag)
      TAG="${2:-}"
      shift 2
      ;;
    -h|--help)
      sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      fail "Unknown argument: $1"
      ;;
  esac
done

[[ -f pom.xml ]] || fail "pom.xml not found (run from the repository root)"

if [[ -z "$VERSION" ]]; then
  VERSION=$(python3 - <<'PY'
import xml.etree.ElementTree as ET
ns = {"m": "http://maven.apache.org/POM/4.0.0"}
root = ET.parse("pom.xml").getroot()
print(root.find("m:version", ns).text.strip())
PY
)
fi

GROUP=$(python3 - <<'PY'
import xml.etree.ElementTree as ET
ns = {"m": "http://maven.apache.org/POM/4.0.0"}
root = ET.parse("pom.xml").getroot()
print(root.find("m:groupId", ns).text.strip())
PY
)
ARTIFACT=$(python3 - <<'PY'
import xml.etree.ElementTree as ET
ns = {"m": "http://maven.apache.org/POM/4.0.0"}
root = ET.parse("pom.xml").getroot()
print(root.find("m:artifactId", ns).text.strip())
PY
)

[[ "$GROUP" == "$EXPECTED_GROUP" ]] || fail "Unexpected groupId '$GROUP' (expected '$EXPECTED_GROUP')"
[[ "$ARTIFACT" == "$EXPECTED_ARTIFACT" ]] || fail "Unexpected artifactId '$ARTIFACT' (expected '$EXPECTED_ARTIFACT')"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.-]+)?$ ]] \
  || fail "Version '$VERSION' is not a valid semver-like release version"
[[ "$VERSION" != *SNAPSHOT* ]] || fail "SNAPSHOT versions cannot be published to Maven Central"

if [[ -n "$TAG" ]]; then
  [[ "$TAG" == "v$VERSION" ]] || fail "Tag '$TAG' does not match pom version '$VERSION' (expected 'v$VERSION')"
fi

# Central is immutable: refuse to attempt a duplicate publication.
URL="https://repo1.maven.org/maven2/${GROUP//.//}/${ARTIFACT}/${VERSION}/${ARTIFACT}-${VERSION}.pom"
HTTP_CODE=$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 "$URL" || true)
if [[ "$HTTP_CODE" == "200" ]]; then
  fail "Version $VERSION is already published on Maven Central: $URL"
fi
if [[ "$HTTP_CODE" != "404" && "$HTTP_CODE" != "000" ]]; then
  echo "WARNING: could not verify whether $VERSION is already published (HTTP $HTTP_CODE)" >&2
fi

echo "OK: ${GROUP}:${ARTIFACT}:${VERSION}${TAG:+ (tag $TAG)}"
