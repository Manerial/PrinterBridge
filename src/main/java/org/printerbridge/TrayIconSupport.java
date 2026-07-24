package org.printerbridge;

import java.awt.AWTException;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import org.printerbridge.api.ApiServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gives the admin a visible, always-there sign that PrinterBridge is running (choix produit,
 * cf. CLAUDE.md § Positionnement) and a "Quitter" action for a clean, voluntary shutdown —
 * distinct from a crash, since systemd's Restart=on-failure must not fire on a deliberate quit.
 */
public final class TrayIconSupport {

    private static final Logger LOG = LoggerFactory.getLogger(TrayIconSupport.class);
    // Dessinée en haute résolution puis réduite par setImageAutoSize : un texte/trait fin
    // rendu directement à 16px est flou, une forme simple réduite depuis une image plus
    // grande passe beaucoup mieux à l'échelle.
    private static final int ICON_SIZE = 64;

    private TrayIconSupport() {
    }

    public static void install(int port, Runnable onQuit) {
        if (!SystemTray.isSupported()) {
            LOG.warn("System tray not supported in this environment; running without a tray icon.");
            return;
        }

        PopupMenu menu = new PopupMenu();

        MenuItem testPageItem = new MenuItem("Ouvrir la page de test");
        testPageItem.addActionListener(event -> openTestPage(port));
        menu.add(testPageItem);

        menu.addSeparator();

        MenuItem quitItem = new MenuItem("Quitter");
        quitItem.addActionListener(event -> {
            LOG.info("Quit requested from the tray icon.");
            onQuit.run();
            System.exit(0);
        });
        menu.add(quitItem);

        TrayIcon trayIcon = new TrayIcon(createIcon(), "PrinterBridge (port " + port + ")", menu);
        trayIcon.setImageAutoSize(true);

        try {
            SystemTray.getSystemTray().add(trayIcon);
        } catch (AWTException e) {
            LOG.warn("Failed to install the tray icon; running without one.", e);
        }
    }

    private static void openTestPage(int port) {
        URI uri = URI.create("http://127.0.0.1:" + port + ApiServer.TEST_PAGE_PATH);
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            LOG.warn("No supported way to open a browser automatically; open manually: {}", uri);
            return;
        }
        try {
            Desktop.getDesktop().browse(uri);
        } catch (IOException e) {
            LOG.warn("Failed to open the test page in a browser: {}", uri, e);
        }
    }

    private static Image createIcon() {
        BufferedImage image = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        // Corps de l'imprimante.
        g.setColor(new Color(0x37, 0x47, 0x54));
        g.fillRoundRect(6, 22, ICON_SIZE - 12, 26, 10, 10);

        // Feuille qui dépasse en haut.
        g.setColor(Color.WHITE);
        g.fillRect(16, 6, ICON_SIZE - 32, 20);
        g.setColor(new Color(0xC7, 0xCE, 0xD6));
        g.drawRect(16, 6, ICON_SIZE - 32 - 1, 20 - 1);

        // Fente de sortie, en accent.
        g.setColor(new Color(0x2E, 0x86, 0xC1));
        g.fillRoundRect(10, 30, ICON_SIZE - 20, 6, 4, 4);

        g.dispose();
        return image;
    }
}
