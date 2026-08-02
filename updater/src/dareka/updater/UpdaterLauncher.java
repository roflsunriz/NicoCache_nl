package dareka.updater;

import java.nio.file.Path;

/** CLI/GUI entry point kept small so packaged E2E exercises production engine paths. */
public final class UpdaterLauncher {
    private static final int RECOMMENDED_LTS = 25;

    private UpdaterLauncher() {}

    public static void main(String[] args) {
        String explicitRoot = argument(args, "--app-root");
        Path applicationRoot = TargetRootResolver.resolve(explicitRoot);
        try {
            if (hasArgument(args, "--print-target-root")) {
                System.out.println(applicationRoot);
                return;
            }
            if (hasArgument(args, "--validate-target-root")) {
                System.out.println(TargetRootResolver.requireInstallation(applicationRoot));
                return;
            }
            if (hasArgument(args, "--installed-version")) {
                TargetRootResolver.requireInstallation(applicationRoot);
                System.out.println(InstalledVersionDetector.detect(applicationRoot));
                return;
            }
            if (hasArgument(args, "--application-check")) {
                System.out.print(NicoCacheUpdater.headlessApplicationCheck(applicationRoot));
                return;
            }
            if (hasArgument(args, "--application-update")) {
                System.out.print(NicoCacheUpdater.headlessApplicationUpdate(applicationRoot));
                return;
            }
            if (hasArgument(args, "--assert-application-stopped")) {
                TargetRootResolver.requireInstallation(applicationRoot);
                ApplicationProcessGuard.requireStopped(applicationRoot);
                System.out.println("APPLICATION_STOPPED");
                return;
            }
            if (hasArgument(args, "--self-test")) {
                DependencyEngine engine = new DependencyEngine(applicationRoot);
                System.out.println("SELF_TEST_OK applicationRoot=" + applicationRoot + " engine=java");
                System.out.println(engine.selfTestTransactions());
                return;
            }
            if (hasArgument(args, "--dependency-check")) {
                DependencyEngine engine = new DependencyEngine(applicationRoot);
                int javaMajor = intArgument(args, "--java-major", RECOMMENDED_LTS);
                String dependency = argument(args, "--dependency");
                if (dependency == null) {
                    System.out.print(engine.checkAll(javaMajor));
                } else {
                    DependencyStatus status = engine.inspectDependency(dependency, javaMajor);
                    System.out.println(status.displayName + ": 導入版=" + status.installedLabel()
                            + ", 最新版=" + status.latestLabel() + " " + status.message
                            + (status.canInstall() ? " [インストール可能]" : " [インストール不可]"));
                }
                return;
            }
            if (hasArgument(args, "--dependency-update")) {
                TargetRootResolver.requireInstallation(applicationRoot);
                DependencyEngine engine = new DependencyEngine(applicationRoot);
                int javaMajor = intArgument(args, "--java-major", RECOMMENDED_LTS);
                String dependency = argument(args, "--dependency");
                System.out.print(dependency == null
                        ? engine.updateAll(javaMajor)
                        : engine.installDependency(dependency, javaMajor));
                return;
            }
            if (hasArgument(args, "--headless")) {
                System.out.println("ヘッドレス実行では --self-test、--installed-version、"
                        + "--application-check、--application-update、"
                        + "--dependency-check、--dependency-update のいずれかを指定してください。");
                System.exit(2);
                return;
            }
            NicoCacheUpdater.main(args);
        } catch (Exception error) {
            System.err.println("UPDATER_FAILED: " + rootMessage(error));
            System.exit(1);
        }
    }

    static String argument(String[] args, String name) {
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
