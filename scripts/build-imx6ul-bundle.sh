#!/bin/sh

set -eu

# All temporary files and the credential-bearing archive are private.
umask 077

if [ "$#" -gt 1 ]; then
    printf 'Usage: %s [armhf-jre.tar.gz]\n' "$0" >&2
    exit 2
fi

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
RUNTIME_ARCHIVE=${1:-"$PROJECT_DIR/runtime/zulu8.76.0.17-ca-cp1-jre8.0.402-linux_aarch32hf.tar.gz"}
EXPECTED_SHA256=48896cc85888bf009072efde43701db9e8ce5b7cb355aeeaed5cf8331b9c762f
OUTPUT="$PROJECT_DIR/target/opc2ecu-imx6ul-armhf.tar.gz"

if [ ! -f "$RUNTIME_ARCHIVE" ]; then
    printf 'Runtime archive not found: %s\n' "$RUNTIME_ARCHIVE" >&2
    exit 2
fi
if [ ! -f "$PROJECT_DIR/target/opcda-probe.jar" ]; then
    printf 'Build target/opcda-probe.jar first.\n' >&2
    exit 2
fi

ACTUAL_SHA256=$(sha256sum "$RUNTIME_ARCHIVE" | sed 's/[[:space:]].*$//')
if [ "$ACTUAL_SHA256" != "$EXPECTED_SHA256" ]; then
    printf 'Unexpected Runtime SHA-256: %s\n' "$ACTUAL_SHA256" >&2
    exit 1
fi

STAGING=$(mktemp -d /tmp/opc2ecu-imx6ul.XXXXXX)
BUNDLE_TMP=
cleanup() {
    if [ -n "$BUNDLE_TMP" ]; then rm -f "$BUNDLE_TMP"; fi
    rm -rf "$STAGING"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
BUNDLE_TMP=$(mktemp "$PROJECT_DIR/target/.opc2ecu-bundle.XXXXXX")

mkdir -p "$STAGING/extract" "$STAGING/opc2ecu/bin" \
    "$STAGING/opc2ecu/config" "$STAGING/opc2ecu/lib"
tar -xzf "$RUNTIME_ARCHIVE" -C "$STAGING/extract"
RUNTIME_DIR=$(find "$STAGING/extract" -mindepth 1 -maxdepth 1 -type d | sed -n '1p')
if [ -z "$RUNTIME_DIR" ] || [ ! -x "$RUNTIME_DIR/bin/java" ]; then
    printf 'Runtime archive does not contain bin/java.\n' >&2
    exit 1
fi

mv "$RUNTIME_DIR" "$STAGING/opc2ecu/runtime"
cp "$PROJECT_DIR/target/opcda-probe.jar" "$STAGING/opc2ecu/lib/opcda-probe.jar"
cp "$PROJECT_DIR/config/points.json" "$STAGING/opc2ecu/config/points.json"
cp "$PROJECT_DIR/config/opc.properties" "$STAGING/opc2ecu/config/opc.properties"
cp "$SCRIPT_DIR/opc2ecu-launcher.sh" "$STAGING/opc2ecu/bin/opc2ecu"
chmod +x "$STAGING/opc2ecu/bin/opc2ecu"
chmod 600 "$STAGING/opc2ecu/config/points.json"

tar -czf "$BUNDLE_TMP" -C "$STAGING" opc2ecu
chmod 600 "$BUNDLE_TMP"
mv -f "$BUNDLE_TMP" "$OUTPUT"
printf '[BUNDLE] %s\n' "$OUTPUT"
du -h "$OUTPUT"
sha256sum "$OUTPUT"
