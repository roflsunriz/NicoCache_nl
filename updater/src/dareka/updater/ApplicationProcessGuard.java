package dareka.updater;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Detects running processes whose executable is inside the selected NicoCache_nl installation. */
final class ApplicationProcessGuard {
    private ApplicationProcessGuard() {}

    static void requireStopped(Path applicationRoot) throws IOException {
        List<String> running = findRunning(applicationRoot);
        if (!running.isEmpty()) {
            throw new IOException("NicoCache_nlが実行中です。終了してから再実行してください: "
                    + String.join(", ", running));
        }
    }

    static List<String> findRunning(Path applicationRoot) {
        Path normalizedRoot = applicationRoot.toAbsolutePath().normalize();
        long currentPid = ProcessHandle.current().pid();
        List<String> result = new ArrayList<>();
        ProcessHandle.allProcesses().forEach(process -> {
            if (process.pid() == currentPid || !process.isAlive()) return;
            String command = process.info().command().orElse("");
            if (command.isBlank()) return;
            try {
                Path executable = Path.of(command).toAbsolutePath().normalize();
                String fileName = executable.getFileName() == null
                        ? executable.toString() : executable.getFileName().toString();
                String lowerName = fileName.toLowerCase(Locale.ROOT);
                if (executable.startsWith(normalizedRoot)
                        && (lowerName.equals("nicocache_nl.exe")
                        || lowerName.equals("java.exe")
                        || lowerName.equals("javaw.exe"))) {
                    result.add(fileName + " (PID " + process.pid() + ")");
                }
            } catch (RuntimeException ignored) {
                // A disappearing or inaccessible process must not break enumeration.
            }
        });
        return result;
    }
}
