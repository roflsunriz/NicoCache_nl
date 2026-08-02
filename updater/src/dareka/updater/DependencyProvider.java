package dareka.updater;

import java.util.List;

interface DependencyProvider {
    String checkAll(int javaMajor) throws Exception;

    String updateAll(int javaMajor) throws Exception;

    List<DependencyStatus> inspectAll(int javaMajor) throws Exception;

    String install(String dependencyId, int javaMajor) throws Exception;

    String selfTest() throws Exception;
}
