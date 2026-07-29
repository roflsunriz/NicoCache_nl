package dareka;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

final class FirstRunSetup {
    private FirstRunSetup() {
    }

    static boolean runIfRequired(Path appDirectory, Path dataDirectory) {
        Path normalizedApp = appDirectory.toAbsolutePath().normalize();
        Path normalizedData = dataDirectory.toAbsolutePath().normalize();
        if (!isRequired(normalizedApp, normalizedData)) {
            return true;
        }
        return runInteractive(normalizedApp, normalizedData);
    }

    static boolean runInteractive(Path appDirectory, Path dataDirectory) {
        Path normalizedApp = appDirectory.toAbsolutePath().normalize();
        Path normalizedData = dataDirectory.toAbsolutePath().normalize();
        return FirstRunWizard.showAndApply(
                normalizedApp, normalizedData, Locale.getDefault());
    }

    static int runHeadless(Path appDirectory, Path dataDirectory,
            SetupOptions options) {
        Path normalizedApp = appDirectory.toAbsolutePath().normalize();
        Path normalizedData =
                options.getUserDataRoot().toAbsolutePath().normalize();
        if (Files.exists(normalizedApp.resolve("config.properties"))) {
            System.out.println("初回セットアップは既に完了しています。");
            return 0;
        }
        try {
            FirstRunSetupService.production(
                    normalizedApp, normalizedData).apply(options);
            System.out.println("初回セットアップが完了しました。");
            return 0;
        } catch (Exception error) {
            System.err.println("初回セットアップに失敗しました: "
                    + error.getMessage());
            return 1;
        }
    }

    static boolean isRequired(Path appDirectory, Path dataDirectory) {
        if (Boolean.getBoolean("dareka.setup.disable")) {
            return false;
        }
        boolean packaged = System.getProperty("jpackage.app-version") != null;
        boolean forced = Boolean.getBoolean("dareka.setup.force");
        if (!packaged && !forced) {
            return false;
        }
        Path normalizedApp = appDirectory.toAbsolutePath().normalize();
        return !Files.exists(normalizedApp.resolve("config.properties"))
                && Files.isRegularFile(
                        normalizedApp.resolve("config.properties.default"));
    }

    static boolean isRequired(Path appDirectory) {
        return isRequired(appDirectory, appDirectory);
    }
}
