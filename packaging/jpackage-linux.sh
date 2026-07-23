#!/usr/bin/env bash
# Builds the Linux installer (.deb) for PrinterBridge via jpackage.
#
# Cible principale : x86_64 (Debian/Ubuntu). jpackage ne fait pas de cross-compilation :
# ce script doit tourner SUR (ou via un runner CI ciblant) l'architecture visée.
# Pour une cible arm64 (Raspberry Pi/Raspbian), le même script fonctionne tel quel
# s'il est exécuté sur un runner/machine arm64 — le binaire produit ne sera valide
# que pour l'architecture de la machine qui l'a construit.
#
# Prerequisites:
#   - `mvn package` doit avoir tourné avant (produit target/jpackage-input : jar + dépendances runtime à plat).
#   - dpkg-deb doit être installé (présent par défaut sur Debian/Ubuntu, y compris le runner
#     GitHub Actions ubuntu-latest).
#
# NOT TESTED: écrit d'après la documentation jpackage, aucune machine Linux disponible
# au moment de l'écriture (environnement de dev Windows). À valider avant usage réel.
#
# Usage: ./packaging/jpackage-linux.sh

set -euo pipefail

VERSION=$(sed -n 's/.*<version>\(.*\)<\/version>.*/\1/p' pom.xml | head -1)
# jpackage --app-version requires a plain numeric X.Y.Z, no "-SNAPSHOT"/qualifier suffix.
APP_VERSION=$(echo "$VERSION" | sed 's/-.*$//')

jpackage \
    --type deb \
    --input target/jpackage-input \
    --dest target/dist \
    --name PrinterBridge \
    --linux-package-name printerbridge \
    --install-dir /opt/printerbridge \
    --resource-dir packaging/linux/resources \
    --main-jar "printerbridge-$VERSION.jar" \
    --main-class org.printerbridge.Main \
    --app-version "$APP_VERSION" \
    --java-options "--enable-native-access=ALL-UNNAMED" \
    --vendor "PrinterBridge" \
    --description "Service local d'impression pour PluriBourse" \
    --linux-menu-group "Utilities" \
    --linux-shortcut \
    --linux-deb-maintainer "TODO Name <TODO@example.org>"
    # TODO: remplacer par un vrai nom + adresse de contact avant publication
    # (convention Debian pour le champ Maintainer : "Nom <email>").
    #
    # --install-dir fixe le chemin d'installation à /opt/printerbridge, pour que le binaire
    # attendu par packaging/linux/resources/postinst (/opt/printerbridge/bin/PrinterBridge)
    # soit prévisible plutôt que de dépendre du comportement par défaut de jpackage.
    # --resource-dir injecte notre postinst/postrm (unité systemd --user), cf. packaging/linux/TESTING.md.
