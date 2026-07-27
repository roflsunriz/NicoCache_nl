import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import dareka.NLMain;
import dareka.RuntimeDependencyUpdaterGUI;
import dareka.extensions.Extension2;
import dareka.extensions.ExtensionManager;
import dareka.extensions.SystemEventListener;

/**
 * NicoCache_nl のGUIへ依存関係更新画面の入口を追加する拡張。
 */
public final class RuntimeDependencyUpdater
        implements Extension2, SystemEventListener {
    private boolean tabAdded;

    @Override
    public void registerExtensions(ExtensionManager manager) {
        manager.registerEventListener(this);
    }

    @Override
    public String getVersionString() {
        return "RuntimeDependencyUpdater 1.0";
    }

    @Override
    public synchronized int onSystemEvent(int id, EventSource source) {
        if (id == PERIODIC_CALL && !tabAdded) {
            tabAdded = true;
            SwingUtilities.invokeLater(this::addUpdaterTab);
        } else if (id == SYSTEM_EXIT) {
            launchPendingRuntimeUpdater();
        }
        return RESULT_OK;
    }

    private void addUpdaterTab() {
        JPanel panel = new JPanel(new BorderLayout(12, 12));
        panel.add(new JLabel(
                "Java・FFmpeg・Bouncy Castle・Apache Ant・7-Zipを管理します。"),
                BorderLayout.NORTH);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton openButton = new JButton("依存関係の更新を開く");
        openButton.addActionListener(event ->
                new RuntimeDependencyUpdaterGUI(null).show());
        buttons.add(openButton);
        panel.add(buttons, BorderLayout.CENTER);

        NLMain.addTab("依存関係", null, panel,
                "外部依存関係の現在版確認と安全な更新");
    }

    private void launchPendingRuntimeUpdater() {
        Path root = Path.of("").toAbsolutePath().normalize();
        Path pending = root.resolve(".runtime-dependency-updater")
                .resolve("pending-update.json");
        if (!Files.isRegularFile(pending)) {
            return;
        }

        Path helper = root.resolve("extensions")
                .resolve("apply-pending-runtime-update.ps1");
        if (!Files.isRegularFile(helper)) {
            return;
        }

        String systemRoot = System.getenv("SystemRoot");
        File powershell = systemRoot == null ? null : Path.of(systemRoot,
                "System32", "WindowsPowerShell", "v1.0",
                "powershell.exe").toFile();
        String executable = powershell != null && powershell.isFile()
                ? powershell.getAbsolutePath() : "powershell.exe";

        try {
            new ProcessBuilder(
                    executable,
                    "-NoLogo",
                    "-NoProfile",
                    "-NonInteractive",
                    "-WindowStyle", "Hidden",
                    "-ExecutionPolicy", "Bypass",
                    "-File", helper.toString(),
                    "-ApplicationRoot", root.toString(),
                    "-WaitForProcessId",
                    Long.toString(ProcessHandle.current().pid()))
                    .directory(root.toFile())
                    .start();
        } catch (IOException error) {
            error.printStackTrace();
        }
    }
}
