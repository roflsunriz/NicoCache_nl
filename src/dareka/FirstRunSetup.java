package dareka;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

final class FirstRunSetup {
    private FirstRunSetup() {
    }

    static boolean runIfRequired(Path appDirectory) {
        Path normalized = appDirectory.toAbsolutePath().normalize();
        if (!isRequired(normalized)) {
            return true;
        }
        return runInteractive(normalized);
    }

    static boolean runInteractive(Path appDirectory) {
        Path normalized = appDirectory.toAbsolutePath().normalize();
        FirstRunSetupService service =
                FirstRunSetupService.production(normalized);
        return FirstRunWizard.showAndApply(service, Locale.getDefault());
    }

    static int runHeadless(Path appDirectory, SetupOptions options) {
        Path normalized = appDirectory.toAbsolutePath().normalize();
        if (Files.exists(normalized.resolve("config.properties"))) {
            System.out.println("初回セットアップは既に完了しています。");
            return 0;
        }
        try {
            FirstRunSetupService.production(normalized).apply(options);
            System.out.println("初回セットアップが完了しました。");
            return 0;
        } catch (Exception error) {
            System.err.println("初回セットアップに失敗しました: "
                    + error.getMessage());
            return 1;
        }
    }

    static boolean isRequired(Path appDirectory) {
        if (Boolean.getBoolean("dareka.setup.disable")) {
            return false;
        }
        boolean packaged = System.getProperty("jpackage.app-version") != null;
        boolean forced = Boolean.getBoolean("dareka.setup.force");
        if (!packaged && !forced) {
            return false;
        }
        Path normalized = appDirectory.toAbsolutePath().normalize();
        return !Files.exists(normalized.resolve("config.properties"))
                && Files.isRegularFile(
                        normalized.resolve("config.properties.default"));
    }
}
