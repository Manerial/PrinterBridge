package org.printerbridge;

import io.javalin.Javalin;
import java.util.concurrent.atomic.AtomicBoolean;
import org.printerbridge.api.ApiServer;

public final class Main {

    private static final int PORT = 7420;

    private Main() {
    }

    public static void main(String[] args) {
        Javalin app = ApiServer.start(PORT);

        AtomicBoolean stopped = new AtomicBoolean(false);
        Runnable stopOnce = () -> {
            if (stopped.compareAndSet(false, true)) {
                app.stop();
            }
        };

        // `systemctl --user stop` (SIGTERM) runs shutdown hooks but never calls the tray icon's
        // "Quitter" handler below — without this, a supervised stop would kill the JVM without
        // draining Jetty/in-flight Bluetooth writes gracefully. `stopped` keeps the two paths from
        // double-stopping when "Quitter" itself triggers this hook via its own System.exit(0).
        Runtime.getRuntime().addShutdownHook(new Thread(stopOnce, "printerbridge-shutdown"));

        TrayIconSupport.install(PORT, stopOnce);
    }
}
