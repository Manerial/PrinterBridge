# Paquetage multi-OS (jpackage)

Trois scripts, un par OS cible. Tous partent de `target/jpackage-input` (produit par
`mvn package`, cf. `pom.xml`) : le jar principal + toutes ses dépendances runtime à plat.

| Script | Type | Cible | Statut |
|---|---|---|---|
| `jpackage-linux.sh` | `.deb` | Debian/Ubuntu x86_64 — **cible principale** (aussi valable pour arm64/Raspberry Pi si exécuté sur cette architecture) | Lancement validé de bout en bout (23 septembre 2026, WSL2/Ubuntu + systemd) — matériel Bluetooth/imprimante réel toujours non testé |
| `jpackage-windows.ps1` | `.msi` | Windows — secondaire | Testé jusqu'au point de blocage WiX Toolset (absent sur cette machine de dev) |
| `jpackage-macos.sh` | `.pkg` | Mac — secondaire | Non testé (pas de Mac disponible) |

## `linux/resources/` — overrides jpackage pour le `.deb`

`jpackage --resource-dir` permet de remplacer certains fichiers que jpackage génère normalement
tout seul. On l'utilise pour deux scripts que dpkg exécute à l'installation/désinstallation :

- **`postinst`** — installe une unité `systemd --user` (`/usr/lib/systemd/user/printerbridge.service`,
  `Restart=on-failure`), **sans jamais l'activer ni la démarrer**. L'admin doit la démarrer
  explicitement (`systemctl --user start printerbridge`) — voir `linux/TESTING.md` pour la procédure
  complète et le pourquoi (tension entre lancement manuel et reprise automatique sur crash, cf. CLAUDE.md).
- **`postrm`** — supprime cette unité à la désinstallation.

Procédure de test détaillée : voir `linux/TESTING.md`.

Retours utilisateur réels ayant fait évoluer ce script (voir CLAUDE.md pour le détail) :
- **v1.0.1** — `systemctl --user start` échouait avec `Failed to connect to bus` sur une install
  sans `dbus-user-session` ; corrigé via `--linux-package-deps "dbus-user-session"`.
- **v1.1.0** — `--install-dir /opt/printerbridge` produisait un dossier imbriqué
  (`/opt/printerbridge/printerbridge/bin/...`) au lieu du chemin attendu par `postinst`
  (`ExecStart=`) ; corrigé en `--install-dir /opt`. `dpkg -i` échouait aussi sur `xdg-utils`
  manquant (ajouté par jpackage à cause de `--linux-shortcut`) — la procédure recommande
  maintenant `apt install ./printerbridge_*.deb`, qui résout les dépendances tout seul (voir
  section Dépannage de `linux/TESTING.md`).
- **23 septembre 2026** — lancement validé de bout en bout (build, install, `systemctl --user
  start`, `GET /printers` répond) en environnement WSL2/Ubuntu avec systemd activé. Toujours pas
  testé : matériel Bluetooth/imprimante réel, et l'hypothèse `BIND_HOST`/Docker natif (cf.
  CLAUDE.md).
