import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import dareka.extensions.Extension2;
import dareka.extensions.ExtensionManager;
import dareka.extensions.SystemEventListener;

/**
 * Applies a runtime replacement staged by the standalone updater after
 * NicoCache_nl exits. The updater GUI itself is intentionally not embedded in
 * NicoCache_nl.
 */
public final class RuntimeDependencyUpdater
        implements Extension2, SystemEventListener {
    @Override
    public void registerExtensions(ExtensionManager manager) {
        manager.registerEventListener(this);
    }

    @Override
    public String getVersionString() {
        return "RuntimeDependencyUpdater exit hook 2.0";
    }

    @Override
    public int onSystemEvent(int id, EventSource source) {
        if (id == SYSTEM_EXIT) {
            launchPendingRuntimeUpdater();
        }
        return RESULT_OK;
    }

    private void launchPendingRuntimeUpdater() {
        Path root = Path.of("").toAbsolutePath().normalize();
        Path pending = root.resolve(".runtime-dependency-updater")
                .resolve("pending-update.json");
        Path helper = root.resolve("extensions")
                .resolve("apply-pending-runtime-update.ps1");
        if (!Files.isRegularFile(pending) || !Files.isRegularFile(helper)) {
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
