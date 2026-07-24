# PrinterBridge

Small native service that owns the local printers (Bluetooth thermal, network/A4) on the machine
running PluriBourse (a toy/clothing swap fundraiser management platform — separate repo) and
exposes them over a local HTTP/WebSocket API, so PluriBourse's Dockerized backend never has to talk
to the hardware directly.

Status: early pilot, not yet published/signed. See [CLAUDE.md](CLAUDE.md) (in French) for the full
design rationale, architecture, and open questions — this README is just a quick entry point.

## Build

```sh
mvn clean package
```

Produces `target/jpackage-input` (main jar + flat runtime dependencies), ready for `jpackage`.

## Test

```sh
mvn test
```

Unit tests only — no test in this suite prints to a real printer or launches the running app; a
few tests that need real hardware/discovered printers to assert anything meaningful skip
themselves (reported as `SKIPPED`) when none is available on the machine running them.

## Package

Platform installers are built via `jpackage`, wrapped by scripts in `packaging/`:

- `packaging/jpackage-linux.sh` — `.deb` (Debian/Ubuntu, x86_64 — primary target)
- `packaging/jpackage-windows.ps1` — `.msi` (requires WiX Toolset v3 on PATH)
- `packaging/jpackage-macos.sh` — `.pkg`

See `packaging/README.md` for details, and `packaging/linux/TESTING.md` for a manual test
checklist of the Linux systemd integration.

## Run

```sh
java -jar target/printerbridge-*.jar
```

Starts the API on `http://127.0.0.1:7420`, loopback-only. A system tray icon confirms it's running
and offers a "Quitter" action for a clean shutdown (degrades gracefully — logs a warning and
continues without an icon — in headless environments).
