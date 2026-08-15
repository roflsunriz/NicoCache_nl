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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
            if (options.shutdown) {
                DiagnosticsControl.requestShutdown(paths,
                        java.time.Duration.ofSeconds(15));
                return;
            }
            SingleInstanceLock lock = SingleInstanceLock.tryAcquire(
                    paths.diagnosticsLock());
            if (lock == null) {
                if (!options.hidden) {
                    requestExistingWindow(paths);
                }
                return;
            }
            AtomicBoolean exiting = new AtomicBoolean();
            AtomicReference<DiagnosticsService> serviceHolder =
                    new AtomicReference<>();
            AtomicReference<DiagnosticsWindow> windowHolder =
                    new AtomicReference<>();
            Runnable terminate = () -> {
                if (!exiting.compareAndSet(false, true)) {
                    return;
                }
                DiagnosticsWindow window = windowHolder.get();
                if (window != null) {
                    window.dispose();
                }
                DiagnosticsService active = serviceHolder.get();
                if (active != null) {
                    active.close();
                }
                try {
                    lock.close();
                } catch (IOException ignored) {
                    // The shutdown hook repeats best-effort cleanup.
                }
                System.exit(0);
            };
            DiagnosticsService service = new DiagnosticsService(paths,
                    new CoreProbe(paths), terminate);
            serviceHolder.set(service);
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
                Runnable userExit = () -> {
                    Thread requester = new Thread(() -> {
                        try {
                            if (service.requestCoreShutdown()) {
                                return;
                            }
                            terminate.run();
                        } catch (IOException | InterruptedException error) {
                            if (error instanceof InterruptedException) {
                                Thread.currentThread().interrupt();
                            }
                            String message = error.getMessage() == null
                                    ? error.toString() : error.getMessage();
                            DiagnosticsWindow active = windowHolder.get();
                            if (active == null) {
                                System.err.println(
                                        "NicoCacheDiagnostics: " + message);
                            } else {
                                active.showError(message);
                            }
                        }
                    }, "nicocache-diagnostics-core-shutdown");
                    requester.setDaemon(true);
                    requester.start();
                };
                DiagnosticsWindow window = new DiagnosticsWindow(
                        service, paths, messages, userExit);
                windowHolder.set(window);
                if (!options.hidden) {
                    window.show();
                }
                if (exiting.get()) {
                    window.dispose();
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
        System.out.println("  --shutdown     正常終了要求を実行中の診断アプリへ送信");
    }

    private static final class Options {
        Path applicationRoot;
        Path dataRoot;
        boolean hidden;
        boolean collectNow;
        boolean shutdown;
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
                } else if ("--shutdown".equals(arg)) {
                    options.shutdown = true;
                } else if ("--help".equals(arg) || "-h".equals(arg)) {
                    options.help = true;
                } else {
                    throw new IllegalArgumentException("不明な引数です: " + arg);
                }
            }
            if (options.collectNow && options.shutdown) {
                throw new IllegalArgumentException(
                        "--collect-now と --shutdown は同時に指定できません");
            }
            return options;
        }
    }
}
