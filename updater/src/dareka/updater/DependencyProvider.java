package dareka.updater;

interface DependencyProvider {
    String checkAll(int javaMajor) throws Exception;

    String updateAll(int javaMajor) throws Exception;

    String selfTest() throws Exception;
}
