package dareka.updater;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Reports Unix command dependencies without assuming a particular package manager. */
final class UnixDependencyManager implements DependencyProvider {
    @Override
    public String checkAll(int javaMajor) throws Exception {
        validateJavaMajor(javaMajor);
        StringBuilder output = new StringBuilder();
        output.append("Linux/macOSの外部依存関係を確認します。\n");
        output.append("導入・更新は各OSのパッケージ管理を優先します。\n");
        for (Tool tool : tools()) {
            CommandResult result = probe(tool.command);
            output.append(tool.name).append(": ")
                    .append(result.exitCode == 0 ? "利用可能" : "未検出")
                    .append("\n");
            if (result.exitCode != 0) {
                output.append("  推奨: ").append(tool.hint).append("\n");
            }
        }
        return output.toString();
    }

    @Override
    public String updateAll(int javaMajor) throws Exception {
        validateJavaMajor(javaMajor);
        return checkAll(javaMajor)
                + "OSの外部依存関係は、root権限を無断取得しないため自動変更しません。\n"
                + "必要な更新をOSのパッケージ管理で適用してから、もう一度確認してください。\n";
    }

    @Override
    public String selfTest() throws Exception {
        for (Tool tool : tools()) {
            if (tool.command.isEmpty()) {
                throw new IOException("Unix依存関係の自己診断コマンドが空です");
            }
        }
        return "SYSTEM_DEPENDENCY_SELF_TEST_OK unix-package-manager-safe";
    }

    private static void validateJavaMajor(int javaMajor) throws IOException {
        if (javaMajor != 17 && javaMajor != 21 && javaMajor != 25) {
            throw new IOException("未検証のTemurin LTSです: " + javaMajor);
        }
    }

    private static List<Tool> tools() {
        return Arrays.asList(
                new Tool("Java", Arrays.asList("java", "-version"),
                        "JDK 17/21/25をOSのパッケージ管理から導入"),
                new Tool("FFmpeg", Arrays.asList("ffmpeg", "-version"),
                        "FFmpegをOSのパッケージ管理から導入"),
                new Tool("Apache Ant", Arrays.asList("ant", "-version"),
                        "Apache AntをOSのパッケージ管理から導入"),
                new Tool("7-Zip", Arrays.asList("7z", "--help"),
                        "7-Zipまたは7zzをOSのパッケージ管理から導入"));
    }

    private static CommandResult probe(List<String> command) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true).start();
            boolean finished = process.waitFor(Duration.ofSeconds(10).toMillis(),
                    java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new CommandResult(124, "timeout");
            }
            return new CommandResult(process.exitValue(), "");
        } catch (Exception error) {
            return new CommandResult(127, error.getMessage());
        }
    }

    private static final class Tool {
        final String name;
        final List<String> command;
        final String hint;

        Tool(String name, List<String> command, String hint) {
            this.name = name;
            this.command = new ArrayList<>(command);
            this.hint = hint;
        }
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
