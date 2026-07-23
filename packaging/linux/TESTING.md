# Tester la reprise automatique sur crash (Linux/systemd)

**Rien de ce document n'a été vérifié sur une vraie machine** — écrit depuis un environnement de
dev Windows, sans Linux disponible. Sers-t'en comme check-list, pas comme certitude : le point le
plus susceptible d'être faux est signalé explicitement plus bas (chemin d'installation).

Ce qu'on essaie de vérifier : PrinterBridge ne démarre **jamais tout seul** (ni au boot, ni à
l'ouverture de session), mais une fois démarré à la main, un crash le fait redémarrer
automatiquement — sans intervention de l'admin.

## Prérequis

- Une vraie machine Debian/Ubuntu (ou une VM), avec `dpkg-deb` installé (présent par défaut).
- `mvn` et un JDK 21+ avec `jpackage` installés sur cette machine.
- Le dépôt PrinterBridge cloné dessus.

## 1. Construire le `.deb`

```sh
mvn clean package
./packaging/jpackage-linux.sh
```

Le `.deb` doit apparaître dans `target/dist/`.

## 2. Vérifier le contenu AVANT d'installer

C'est le point le plus incertain de tout ce dispositif : `packaging/jpackage-linux.sh` force
`--install-dir /opt/printerbridge`, et `packaging/linux/resources/postinst` suppose que le binaire
final se trouve à `/opt/printerbridge/bin/PrinterBridge`. Vérifie que c'est bien le cas :

```sh
dpkg -c target/dist/printerbridge_*.deb | grep bin/
```

Si le chemin affiché est différent, corrige `ExecStart=` dans
`packaging/linux/resources/postinst` avant de continuer (et reconstruis le `.deb`).

## 3. Installer

```sh
sudo dpkg -i target/dist/printerbridge_*.deb
```

## 4. Vérifier que l'unité systemd est là, mais pas active

```sh
systemctl --user status printerbridge
```

Doit afficher quelque chose comme `inactive (dead)` — l'unité existe mais rien ne tourne. Vérifie
aussi qu'elle n'est pas activée au boot :

```sh
systemctl --user is-enabled printerbridge
```

Doit répondre `disabled` (ou équivalent) — **pas** `enabled`.

## 5. Démarrer manuellement et vérifier que ça répond

```sh
systemctl --user start printerbridge
sleep 2
curl http://127.0.0.1:7420/printers
```

Doit renvoyer une liste JSON (vide ou avec les imprimantes de la machine).

## 6. Simuler un crash et vérifier la reprise automatique

```sh
PID=$(systemctl --user show printerbridge --property MainPID --value)
kill -9 "$PID"
sleep 7   # RestartSec=5 dans l'unité, on laisse un peu de marge
curl http://127.0.0.1:7420/printers
systemctl --user status printerbridge   # doit montrer un PID différent de $PID
```

Si `curl` répond à nouveau et que le PID a changé : la reprise automatique fonctionne.

## 7. Vérifier qu'un arrêt volontaire NE relance PAS le service

```sh
systemctl --user stop printerbridge
sleep 7
systemctl --user status printerbridge   # doit rester "inactive (dead)", pas relancé
```

## 8. Vérifier l'absence de démarrage automatique au boot/login

Redémarre la machine (ou au minimum déconnecte-toi/reconnecte-toi), puis :

```sh
systemctl --user status printerbridge   # doit être inactive, rien lancé tout seul
```

## 9. Désinstaller

```sh
sudo dpkg -r printerbridge
ls /usr/lib/systemd/user/printerbridge.service   # doit ne plus exister
```

**Limite connue** : si le service tournait encore au moment de la désinstallation, `postrm` ne
l'arrête pas (il tourne en root pendant `dpkg`, sans accès à ta session `--user`). Arrête-le à la
main avant (`systemctl --user stop printerbridge`) si besoin.

## Ce qui manque encore, volontairement pas fait ici

Aucun raccourci (icône bureau / menu applications) ne passe encore par `systemctl --user start` —
il faut taper la commande à la main pour l'instant. La raison : le vrai geste "admin clique une
icône" suppose l'icône barre système (pas encore codée), et faire fonctionner un service
`systemd --user` dans une session graphique (accès à `DISPLAY`/Wayland pour `SystemTray`) est un
point d'incertitude à part entière selon la distro/l'environnement de bureau — pas quelque chose à
deviner sans machine réelle pour tester. À reprendre une fois l'icône barre système implémentée.
