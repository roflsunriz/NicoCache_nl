package dareka;

import java.util.stream.Collectors;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/** Application entry point with local dependency update support. */
public final class DependencyAwareMain {
    private DependencyAwareMain() {
    }

    public static void main(String[] args) {
        if (LocalDependencyUpdater.handleCommandLine(args)) {
            return;
        }
        if (shouldCheck()) {
            Thread checker = new Thread(DependencyAwareMain::checkInBackground,
                    "NicoCache_nl dependency update checker");
            checker.setDaemon(true);
            checker.start();
        }
        NLMain.main(args);
    }

    private static boolean shouldCheck() {
        return System.getProperty(LocalDependencyUpdater.MANIFEST_URI_PROPERTY) != null
                && !Boolean.getBoolean("nicocache.dependencies.update.disabled")
                && !"false".equalsIgnoreCase(System.getProperty("dareka.gui"));
    }

    private static void checkInBackground() {
        try {
            Thread.sleep(Long.getLong(
                    "nicocache.dependencies.update.delayMillis", 5000L));
            LocalDependencyUpdater.UpdatePlan plan = LocalDependencyUpdater.check();
            if (plan.updates.isEmpty() && plan.modified.isEmpty()) {
                return;
            }
            SwingUtilities.invokeLater(() -> showUpdateDialog(plan));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            System.err.println("依存ファイルの更新確認に失敗しました: "
                    + error.getMessage());
        }
    }

    private static void showUpdateDialog(LocalDependencyUpdater.UpdatePlan plan) {
        String updates = plan.updates.stream()
                .map(value -> "・" + value.component.name + " "
                        + value.component.version)
                .collect(Collectors.joining("\n"));
        String modified = plan.modified.stream()
                .map(value -> "・" + value.component.name + "（編集済みのため除外）")
                .collect(Collectors.joining("\n"));
        StringBuilder message = new StringBuilder();
        if (!updates.isEmpty()) {
            message.append("更新可能:\n").append(updates).append("\n");
        }
        if (!modified.isEmpty()) {
            message.append("\n自動更新しないファイル:\n").append(modified).append("\n");
        }
        if (plan.updates.isEmpty()) {
            JOptionPane.showMessageDialog(null, message.toString(),
                    "NicoCache_nl 依存ファイル更新",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        message.append("\n更新前にバックアップを作成して適用しますか？");
        int result = JOptionPane.showConfirmDialog(null, message.toString(),
                "NicoCache_nl 依存ファイル更新",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.INFORMATION_MESSAGE);
        if (result != JOptionPane.YES_OPTION) {
            return;
        }
        Thread installer = new Thread(() -> {
            try {
                LocalDependencyUpdater.apply(plan);
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                        null, "依存ファイルを更新しました。",
                        "NicoCache_nl 依存ファイル更新",
                        JOptionPane.INFORMATION_MESSAGE));
            } catch (Exception error) {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                        null, "更新に失敗したため復元しました。\n"
                                + error.getMessage(),
                        "NicoCache_nl 依存ファイル更新",
                        JOptionPane.ERROR_MESSAGE));
            }
        }, "NicoCache_nl dependency updater");
        installer.setDaemon(true);
        installer.start();
    }
}
