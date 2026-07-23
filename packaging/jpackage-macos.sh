#!/usr/bin/env bash
# Builds the macOS installer (.pkg) for PrinterBridge via jpackage.
#
# Prerequisites:
#   - `mvn package` must have run first (produces target/jpackage-input: main jar + flat runtime deps).
#
# NOT TESTED: written against the jpackage documentation, no Mac machine was available
# when this was authored. Validate on an actual macOS runner before relying on it.
#
# Usage: ./packaging/jpackage-macos.sh

set -euo pipefail

VERSION=$(sed -n 's/.*<version>\(.*\)<\/version>.*/\1/p' pom.xml | head -1)
# jpackage --app-version requires a plain numeric X.Y.Z, no "-SNAPSHOT"/qualifier suffix.
APP_VERSION=$(echo "$VERSION" | sed 's/-.*$//')

jpackage \
    --type pkg \
    --input target/jpackage-input \
    --dest target/dist \
    --name PrinterBridge \
    --main-jar "printerbridge-$VERSION.jar" \
    --main-class org.printerbridge.Main \
    --app-version "$APP_VERSION" \
    --java-options "--enable-native-access=ALL-UNNAMED" \
    --vendor "PrinterBridge" \
    --description "Service local d'impression pour PluriBourse"
