package nicocache.diagnostics;

import java.nio.file.Files;
import java.nio.file.Path;

public final class DiagnosticsPathsTest {
    private DiagnosticsPathsTest() { }

    public static void main(String[] args) throws Exception {
        String originalJavaHome = System.getProperty("java.home");
        Path root = Files.createTempDirectory("diagnostics-paths-");
        try {
            System.setProperty("java.home",
                    root.resolve("runtime-without-jcmd").toString());
            DiagnosticsPaths paths = DiagnosticsPaths.resolve(root, root);
            Path jcmd = paths.jcmdExecutable();
            assertTrue(Files.isRegularFile(jcmd),
                    "JDK environment or PATH jcmd must be used when the "
                    + "active runtime has no jcmd");
        } finally {
            System.setProperty("java.home", originalJavaHome);
            Files.deleteIfExists(root);
        }
        System.out.println("Diagnostics path tests passed");
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
}
