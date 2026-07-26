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
        FirstRunSetupService service =
                FirstRunSetupService.production(normalized);
        return FirstRunWizard.showAndApply(service, Locale.getDefault());
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
