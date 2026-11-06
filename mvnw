#!/usr/bin/env sh
set -eu

BASE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROPERTIES="$BASE_DIR/.mvn/wrapper/maven-wrapper.properties"
DIST_URL=$(sed -n 's/^distributionUrl=//p' "$PROPERTIES")
EXPECTED_SHA512=$(sed -n 's/^distributionSha512Sum=//p' "$PROPERTIES")
[ -n "$DIST_URL" ] && [ -n "$EXPECTED_SHA512" ] || { echo "Invalid Maven wrapper properties" >&2; exit 1; }
DIST_NAME=${DIST_URL##*/}
MAVEN_VERSION=$(printf '%s' "$DIST_NAME" | sed -E 's/^apache-maven-([0-9.]+)-bin\.tar\.gz$/\1/')
[ "$MAVEN_VERSION" != "$DIST_NAME" ] || { echo "Unsupported Maven distribution: $DIST_NAME" >&2; exit 1; }
CACHE_ROOT=${MAVEN_USER_HOME:-${HOME:?HOME is not set}/.m2}/wrapper/dists
INSTALL_DIR="$CACHE_ROOT/apache-maven-$MAVEN_VERSION"
MAVEN="$INSTALL_DIR/bin/mvn"

if [ ! -x "$MAVEN" ]; then
  mkdir -p "$CACHE_ROOT"
  TMP_DIR=$(mktemp -d "$CACHE_ROOT/.maven-wrapper.XXXXXX")
  trap 'rm -rf "$TMP_DIR"' EXIT HUP INT TERM
  ARCHIVE="$TMP_DIR/$DIST_NAME"
  if command -v curl >/dev/null 2>&1; then
    curl --fail --location --silent --show-error "$DIST_URL" --output "$ARCHIVE"
  elif command -v wget >/dev/null 2>&1; then
    wget -q "$DIST_URL" -O "$ARCHIVE"
  else
    echo "Maven wrapper requires curl or wget to download Maven" >&2; exit 1
  fi
  if command -v sha512sum >/dev/null 2>&1; then
    ACTUAL_SHA512=$(sha512sum "$ARCHIVE" | awk '{print $1}')
  elif command -v shasum >/dev/null 2>&1; then
    ACTUAL_SHA512=$(shasum -a 512 "$ARCHIVE" | awk '{print $1}')
  else
    echo "Maven wrapper requires sha512sum or shasum for integrity verification" >&2; exit 1
  fi
  [ "$ACTUAL_SHA512" = "$EXPECTED_SHA512" ] || { echo "Maven distribution SHA-512 mismatch" >&2; exit 1; }
  tar -xzf "$ARCHIVE" -C "$TMP_DIR"
  [ -x "$TMP_DIR/apache-maven-$MAVEN_VERSION/bin/mvn" ] || { echo "Maven archive has an unexpected layout" >&2; exit 1; }
  rm -rf "$INSTALL_DIR"
  mv "$TMP_DIR/apache-maven-$MAVEN_VERSION" "$INSTALL_DIR"
fi
exec "$MAVEN" -f "$BASE_DIR/pom.xml" "$@"
