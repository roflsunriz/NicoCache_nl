package nicocache.launcher;

final class TaskCommandResult {
    final int exitCode;
    final String output;

    TaskCommandResult(int exitCode, String output) {
        this.exitCode = exitCode;
        this.output = output;
    }
}
