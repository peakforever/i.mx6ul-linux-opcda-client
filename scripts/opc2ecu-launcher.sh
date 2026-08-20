#!/bin/sh

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
APP_HOME=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)

if [ "$#" -eq 0 ]; then
    set -- --collect "$APP_HOME/config/points.json" "$APP_HOME/config/opc.properties"
fi

exec "$APP_HOME/runtime/bin/java" \
    -Xms16m \
    -Xmx48m \
    -XX:+UseSerialGC \
    -Djava.awt.headless=true \
    -jar "$APP_HOME/lib/opcda-probe.jar" \
    "$@"
