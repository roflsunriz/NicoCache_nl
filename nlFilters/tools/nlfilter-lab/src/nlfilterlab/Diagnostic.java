package nlfilterlab;

import java.nio.file.Path;

record Diagnostic(Severity severity, Path file, int line, String code, String message) {
    enum Severity {
        ERROR,
        WARNING
    }

    String display(Path repositoryRoot) {
        Path shown = file;
        if (file != null && repositoryRoot != null) {
            try {
                shown = repositoryRoot.relativize(file.toAbsolutePath().normalize());
            } catch (IllegalArgumentException ignored) {
                // 別ドライブの明示指定は絶対パスで表示する。
            }
        }
        String location = shown == null ? "" : shown.toString();
        if (line > 0) {
            location += ":" + line;
        }
        return severity + " " + location + " [" + code + "] " + message;
    }
}
