package dareka.updater;

import java.util.List;

interface DependencyProvider {
    static DependencyProvider forPlatform(UpdaterPlatform.Kind platform) throws java.io.IOException {
        switch (platform) {
        case WINDOWS:
            return new WindowsDependencyManager();
        case LINUX:
        case MACOS:
            return new UnixDependencyManager();
        case OTHER:
        default:
            throw new java.io.IOException("対応していないOSでは外部依存関係を管理できません: " + platform);
        }
    }

    String checkAll(int javaMajor) throws Exception;

    String updateAll(int javaMajor) throws Exception;

    List<DependencyStatus> inspectAll(int javaMajor) throws Exception;

    String install(String dependencyId, int javaMajor) throws Exception;

    String selfTest() throws Exception;
}
