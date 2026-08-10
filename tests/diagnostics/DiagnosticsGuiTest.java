package nicocache.diagnostics;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.imageio.ImageIO;

public final class DiagnosticsGuiTest {
    private DiagnosticsGuiTest() { }

    public static void main(String[] args) throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("Diagnostics GUI tests skipped: headless");
            return;
        }
        Path root = Files.createTempDirectory("diagnostics-gui-");
        DiagnosticsPaths paths = DiagnosticsPaths.resolve(
                root.resolve("app"), root.resolve("data"));
        DiagnosticsService service = new DiagnosticsService(paths);
        ResourceBundle messages = ResourceBundle.getBundle(
                "nicocache.diagnostics.messages", Locale.JAPANESE);
        AtomicReference<DiagnosticsWindow> window = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> window.set(new DiagnosticsWindow(
                service, paths, messages, () -> { })));
        try {
            DiagnosticsWindow value = window.get();
            SwingUtilities.invokeAndWait(value::show);
            JFrame frame = findFrame("diagnostics.window");
            assertTrue(frame != null && frame.isVisible(),
                    "diagnostics frame must be visible");
            assertTrue(frame.getMinimumSize().width >= 420,
                    "minimum width must support compact windows");
            JButton collect = findButton(frame, "diagnostics.collect");
            JButton reports = findButton(frame, "diagnostics.reports");
            JButton hide = findButton(frame, "diagnostics.hide");
            assertTrue(collect != null && reports != null && hide != null,
                    "all dynamic buttons must have stable identities");
            value.heartbeat(new HeartbeatSample(Instant.now(), 99L, "running",
                    true, true, true, 2L, 3L, "",
                    HeartbeatSample.Health.HEALTHY));
            value.collectionStarted("manual-collection");
            SwingUtilities.invokeAndWait(() -> { });
            assertTrue(!collect.isEnabled(),
                    "collection button must disable during capture");
            value.collectionCompleted(root.resolve("report.html"));
            if (args.length > 0) {
                Path preview = Path.of(args[0]).toAbsolutePath().normalize();
                Files.createDirectories(preview.getParent());
                BufferedImage image = new BufferedImage(frame.getWidth(),
                        frame.getHeight(), BufferedImage.TYPE_INT_ARGB);
                SwingUtilities.invokeAndWait(() -> {
                    Graphics2D graphics = image.createGraphics();
                    try {
                        frame.paintAll(graphics);
                    } finally {
                        graphics.dispose();
                    }
                });
                ImageIO.write(image, "png", preview.toFile());
            }
            SwingUtilities.invokeAndWait(hide::doClick);
            assertTrue(!frame.isVisible(), "hide button must hide the window");
            System.out.println("Diagnostics GUI tests passed");
        } finally {
            SwingUtilities.invokeAndWait(() -> window.get().dispose());
            service.close();
        }
    }

    private static JFrame findFrame(String name) {
        for (java.awt.Window window : java.awt.Window.getWindows()) {
            if (window instanceof JFrame && name.equals(window.getName())) {
                return (JFrame) window;
            }
        }
        return null;
    }
    private static JButton findButton(Container parent, String name) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JButton && name.equals(child.getName())) {
                return (JButton) child;
            }
            if (child instanceof Container) {
                JButton nested = findButton((Container) child, name);
                if (nested != null) { return nested; }
            }
        }
        return null;
    }
    private static void assertTrue(boolean value, String message) {
        if (!value) { throw new AssertionError(message); }
    }
}
