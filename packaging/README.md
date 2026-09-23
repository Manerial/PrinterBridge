# Paquetage multi-OS (jpackage)

Trois scripts, un par OS cible. Tous partent de `target/jpackage-input` (produit par
`mvn package`, cf. `pom.xml`) : le jar principal + toutes ses dépendances runtime à plat.

| Script | Type | Cible | Statut |
|---|---|---|---|
| `jpackage-linux.sh` | `.deb` | Debian/Ubuntu x86_64 — **cible principale** (aussi valable pour arm64/Raspberry Pi si exécuté sur cette architecture) | Non testé (pas de machine Linux disponible à l'écriture) |
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

Procédure de test détaillée (non validée sur machine réelle) : voir `linux/TESTING.md`.

Premier retour utilisateur réel (v1.0.1) : `systemctl --user start` échouait avec `Failed to
connect to bus` sur une install sans `dbus-user-session` — voir la section Dépannage de
`linux/TESTING.md` et le correctif `--linux-package-deps` dans `jpackage-linux.sh`.
