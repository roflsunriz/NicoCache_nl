package nicocache.diagnostics;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/** Independent always-on GUI watchdog entry point. */
public final class DiagnosticsMain {
    private DiagnosticsMain() {
    }

    public static void main(String[] args) {
        try {
            Options options = Options.parse(args);
            if (options.help) {
                printHelp();
                return;
            }
            DiagnosticsPaths paths = DiagnosticsPaths.resolve(
                    options.applicationRoot, options.dataRoot);
            SingleInstanceLock lock = SingleInstanceLock.tryAcquire(
                    paths.diagnosticsLock());
            if (lock == null) {
                if (!options.hidden) {
                    requestExistingWindow(paths);
                }
                return;
            }
            DiagnosticsService service = new DiagnosticsService(paths);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                service.close();
                try { lock.close(); }
                catch (IOException ignored) { }
            }, "nicocache-diagnostics-shutdown"));
            service.start();

            if (options.collectNow) {
                Path report = service.collectNow().get(120L, TimeUnit.SECONDS);
                System.out.println(report);
                service.close();
                lock.close();
                return;
            }
            if (GraphicsEnvironment.isHeadless()) {
                if (options.hidden) {
                    new CountDownLatch(1).await();
                    return;
                }
                throw new IllegalStateException(
                        "GUIのない環境では --collect-now を指定してください");
            }
            ResourceBundle messages = ResourceBundle.getBundle(
                    "nicocache.diagnostics.messages", Locale.getDefault());
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            SwingUtilities.invokeLater(() -> {
                final DiagnosticsWindow[] holder = new DiagnosticsWindow[1];
                Runnable exit = () -> {
                    if (holder[0] != null) {
                        holder[0].dispose();
                    }
                    service.close();
                    System.exit(0);
                };
                holder[0] = new DiagnosticsWindow(service, paths, messages, exit);
                if (!options.hidden) {
                    holder[0].show();
                }
            });
        } catch (Exception error) {
            System.err.println("NicoCacheDiagnostics: "
                    + (error.getMessage() == null ? error : error.getMessage()));
            System.exit(1);
        }
    }

    private static void requestExistingWindow(DiagnosticsPaths paths) {
        Path request = paths.dataRoot().resolve(
                "data/nicocache-diagnostics-show.request");
        try {
            Files.createDirectories(request.getParent());
            Files.writeString(request, "show", StandardCharsets.US_ASCII,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException ignored) {
            // The running instance remains available from its tray icon.
        }
    }

    private static void printHelp() {
        System.out.println("NicoCacheDiagnostics");
        System.out.println("  --app-root=<path> --data-root=<path>");
        System.out.println("  --hidden       タスクトレイまたはバックグラウンドで起動");
        System.out.println("  --collect-now  1回収集して終了（GUI不要）");
    }

    private static final class Options {
        Path applicationRoot;
        Path dataRoot;
        boolean hidden;
        boolean collectNow;
        boolean help;

        static Options parse(String[] args) {
            Options options = new Options();
            for (String arg : args) {
                if (arg.startsWith("--app-root=")) {
                    options.applicationRoot = Path.of(
                            arg.substring("--app-root=".length()));
                } else if (arg.startsWith("--data-root=")) {
                    options.dataRoot = Path.of(
                            arg.substring("--data-root=".length()));
                } else if ("--hidden".equals(arg)) {
                    options.hidden = true;
                } else if ("--collect-now".equals(arg)) {
                    options.collectNow = true;
                } else if ("--help".equals(arg) || "-h".equals(arg)) {
                    options.help = true;
                } else {
                    throw new IllegalArgumentException("不明な引数です: " + arg);
                }
            }
            return options;
        }
    }
}
