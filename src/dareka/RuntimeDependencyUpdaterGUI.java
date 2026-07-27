package dareka;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingWorker;

/**
 * NicoCache_nl 管理下の外部依存関係を確認・更新するGUI。
 * 利用者にターミナル操作を要求しない。
 */
public final class RuntimeDependencyUpdaterGUI {
    private static final String SCRIPT =
            "extensions/update-runtime-dependencies.ps1";

    private final JDialog dialog;
    private final JTextArea output;
    private final JButton checkButton;
    private final JButton updateButton;

    public RuntimeDependencyUpdaterGUI(JFrame owner) {
        dialog = new JDialog(owner, "依存関係の更新", false);
        output = new JTextArea();
        checkButton = new JButton("更新を確認");
        updateButton = new JButton("更新可能な項目を適用");

        output.setEditable(false);
        output.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        output.setLineWrap(false);

        JLabel explanation = new JLabel(
                "Java・FFmpeg・Bouncy Castle・Ant・7-Zipの状態を確認します。");
        JPanel buttons = new JPanel();
        buttons.add(checkButton);
        buttons.add(updateButton);

        dialog.setLayout(new BorderLayout(8, 8));
        dialog.add(explanation, BorderLayout.NORTH);
        dialog.add(new JScrollPane(output), BorderLayout.CENTER);
        dialog.add(buttons, BorderLayout.SOUTH);
        dialog.setPreferredSize(new Dimension(760, 430));
        dialog.pack();
        dialog.setLocationRelativeTo(owner);

        checkButton.addActionListener(event -> run(false));
        updateButton.addActionListener(event -> {
            int answer = JOptionPane.showConfirmDialog(
                    dialog,
                    "NicoCache_nlが管理している依存関係だけを更新します。\n"
                            + "システムに導入されたJava、FFmpeg、Ant、7-Zipは変更しません。\n\n"
                            + "続行しますか？",
                    "依存関係の更新",
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.QUESTION_MESSAGE);
            if (answer == JOptionPane.OK_OPTION) {
                run(true);
            }
        });
    }

    public void show() {
        dialog.setVisible(true);
        run(false);
    }

    private void run(boolean update) {
        checkButton.setEnabled(false);
        updateButton.setEnabled(false);
        output.setText(update ? "更新を準備しています…\n" : "更新を確認しています…\n");

        new SwingWorker<CommandResult, String>() {
            @Override
            protected CommandResult doInBackground() throws Exception {
                return invokeUpdater(update);
            }

            @Override
            protected void done() {
                try {
                    CommandResult result = get();
                    output.setText(result.output);
                    if (result.exitCode != 0) {
                        JOptionPane.showMessageDialog(
                                dialog,
                                "依存関係の処理に失敗しました。詳細は画面内のログを確認してください。",
                                "更新エラー",
                                JOptionPane.ERROR_MESSAGE);
                    } else if (update) {
                        JOptionPane.showMessageDialog(
                                dialog,
                                "依存関係の更新処理が完了しました。\n"
                                        + "Java Runtimeの更新はNicoCache_nl終了後に自動適用される場合があります。",
                                "更新完了",
                                JOptionPane.INFORMATION_MESSAGE);
                    }
                } catch (Exception error) {
                    output.append("\n" + error + "\n");
                    JOptionPane.showMessageDialog(
                            dialog,
                            "更新機能を起動できませんでした。\n" + error.getMessage(),
                            "更新エラー",
                            JOptionPane.ERROR_MESSAGE);
                } finally {
                    checkButton.setEnabled(true);
                    updateButton.setEnabled(true);
                }
            }
        }.execute();
    }

    private static CommandResult invokeUpdater(boolean update)
            throws IOException, InterruptedException {
        Path root = Path.of("").toAbsolutePath().normalize();
        Path script = root.resolve(SCRIPT).normalize();
        if (!script.toFile().isFile()) {
            throw new IOException("更新コンポーネントが見つかりません: " + script);
        }

        List<String> command = new ArrayList<String>();
        command.add(findWindowsPowerShell());
        command.add("-NoLogo");
        command.add("-NoProfile");
        command.add("-NonInteractive");
        command.add("-WindowStyle");
        command.add("Hidden");
        command.add("-ExecutionPolicy");
        command.add("Bypass");
        command.add("-File");
        command.add(script.toString());
        command.add("-Mode");
        command.add(update ? "Update" : "Check");
        command.add("-ApplicationRoot");
        command.add(root.toString());
        if (update) {
            command.add("-NonInteractive");
        }

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(root.toFile());
        builder.redirectErrorStream(true);
        builder.environment().put("POWERSHELL_TELEMETRY_OPTOUT", "1");
        Process process = builder.start();

        StringBuilder text = new StringBuilder();
        Charset consoleCharset = Charset.defaultCharset();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), consoleCharset))) {
            String line;
            while ((line = reader.readLine()) != null) {
                text.append(line).append(System.lineSeparator());
            }
        }
        return new CommandResult(process.waitFor(), text.toString());
    }

    private static String findWindowsPowerShell() {
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot != null && !systemRoot.isBlank()) {
            File executable = Path.of(systemRoot, "System32", "WindowsPowerShell",
                    "v1.0", "powershell.exe").toFile();
            if (executable.isFile()) {
                return executable.getAbsolutePath();
            }
        }
        return "powershell.exe";
    }

    public static void main(String[] args) {
        javax.swing.SwingUtilities.invokeLater(() ->
                new RuntimeDependencyUpdaterGUI(null).show());
    }

    private static final class CommandResult {
        final int exitCode;
        final String output;

        CommandResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }
}
