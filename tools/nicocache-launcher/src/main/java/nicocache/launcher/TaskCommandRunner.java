package nicocache.launcher;

import java.io.IOException;
import java.util.List;

@FunctionalInterface
interface TaskCommandRunner {
    TaskCommandResult run(String command, List<String> arguments)
            throws IOException;
}
