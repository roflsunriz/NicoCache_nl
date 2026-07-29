package dareka;

import java.nio.file.Path;

/**
 * Packaged launcher that fixes the application root before configuration is
 * loaded.
 */
final class UserDataMain {
    private UserDataMain() {
    }

    public static void main(String[] args) {
        String packagedLauncher = System.getProperty("jpackage.app-path");
        if (packagedLauncher != null && !packagedLauncher.isBlank()) {
            Path executable =
                    Path.of(packagedLauncher).toAbsolutePath().normalize();
            Path applicationRoot = executable.getParent();
            if (applicationRoot != null) {
                System.setProperty(
                        NicoCachePaths.APPLICATION_ROOT_PROPERTY,
                        applicationRoot.toString());
            }
        }
        NLMain.main(args);
    }
}
