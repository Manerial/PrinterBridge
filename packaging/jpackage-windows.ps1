# Builds the Windows installer (.msi) for PrinterBridge via jpackage.
#
# Prerequisites:
#   - `mvn package` must have run first (produces target/jpackage-input: main jar + flat runtime deps).
#   - WiX Toolset v3 (light.exe / candle.exe) must be on PATH — https://wixtoolset.org
#     Not installed on the dev machine this was written on; GitHub Actions' windows-latest
#     runner has it preinstalled, which is where this is expected to actually run in CI.
#
# Usage: powershell -File packaging/jpackage-windows.ps1

$ErrorActionPreference = "Stop"

[xml]$pom = Get-Content pom.xml
$version = $pom.project.version
# jpackage --app-version requires a plain numeric X.Y.Z, no "-SNAPSHOT"/qualifier suffix.
$appVersion = $version -replace '-.*$', ''

jpackage `
    --type msi `
    --input target/jpackage-input `
    --dest target/dist `
    --name PrinterBridge `
    --main-jar "printerbridge-$version.jar" `
    --main-class org.printerbridge.Main `
    --app-version $appVersion `
    --java-options "--enable-native-access=ALL-UNNAMED" `
    --vendor "PrinterBridge" `
    --description "Service local d'impression pour PluriBourse" `
    --win-menu `
    --win-shortcut `
    --win-dir-chooser

# $ErrorActionPreference = "Stop" does NOT apply to a native executable's exit code — only to
# PowerShell/.NET errors. Without this check, a jpackage failure (e.g. WiX Toolset missing from
# PATH) would leave the script exiting 0, and a CI step consuming its output (upload-artifact,
# default "warn" on no files found) could report success despite no .msi ever being produced.
if ($LASTEXITCODE -ne 0) {
    throw "jpackage failed with exit code $LASTEXITCODE"
}
