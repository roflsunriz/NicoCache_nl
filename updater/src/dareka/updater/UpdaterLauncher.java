package dareka.updater;

import java.nio.file.Path;

/** CLI/GUI entry point kept small so packaged E2E exercises production engine paths. */
public final class UpdaterLauncher {
    private static final int RECOMMENDED_LTS = 21;

    private UpdaterLauncher() {}

    public static void main(String[] args) {
        Path applicationRoot = argument(args, "--app-root") == null
                ? defaultApplicationRoot()
                : Path.of(argument(args, "--app-root")).toAbsolutePath().normalize();
        try {
            if (hasArgument(args, "--self-test")) {
                DependencyEngine engine = new DependencyEngine(applicationRoot);
                System.out.println("SELF_TEST_OK applicationRoot=" + applicationRoot + " engine=java");
                System.out.println(engine.selfTestTransactions());
                return;
            }
            if (hasArgument(args, "--dependency-check")) {
                DependencyEngine engine = new DependencyEngine(applicationRoot);
                System.out.print(engine.checkAll(intArgument(args, "--java-major", RECOMMENDED_LTS)));
                return;
            }
            if (hasArgument(args, "--dependency-update")) {
                DependencyEngine engine = new DependencyEngine(applicationRoot);
                System.out.print(engine.updateAll(intArgument(args, "--java-major", RECOMMENDED_LTS)));
                return;
            }
            NicoCacheUpdater.main(args);
        } catch (Exception error) {
            System.err.println("UPDATER_FAILED: " + rootMessage(error));
            System.exit(1);
        }
    }

    private static Path defaultApplicationRoot() {
        String programFiles = System.getenv("ProgramFiles");
        return programFiles == null
                ? Path.of("NicoCache_nl").toAbsolutePath().normalize()
                : Path.of(programFiles, "NicoCache_nl").toAbsolutePath().normalize();
    }

    private static String argument(String[] args, String name) {
        for (int index = 0; index + 1 < args.length; index++) {
            if (name.equals(args[index])) return args[index + 1];
        }
        return null;
    }

    private static boolean hasArgument(String[] args, String name) {
        for (String value : args) if (name.equals(value)) return true;
        return false;
    }

    private static int intArgument(String[] args, String name, int fallback) {
        String value = argument(args, name);
        return value == null ? fallback : Integer.parseInt(value);
    }

    private static String rootMessage(Exception error) {
        Throwable value = error;
        while (value.getCause() != null) value = value.getCause();
        return value.getMessage() == null ? value.toString() : value.getMessage();
    }
}
