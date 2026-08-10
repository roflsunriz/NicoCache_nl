package runtime;

public final class RuntimeCompatibilityTest {
    private RuntimeCompatibilityTest() {}

    public static void main(String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException(
                    "usage: RuntimeCompatibilityTest <expected-major>");
        }
        int expectedMajor = Integer.parseInt(args[0]);
        int actualMajor = Runtime.version().feature();
        if (actualMajor != expectedMajor) {
            throw new AssertionError("Java major version mismatch: expected="
                    + expectedMajor + ", actual=" + actualMajor);
        }

        System.out.printf(
                "PASS Java %d runtime: version=%s, vendor=%s, vm=%s, os=%s/%s%n",
                actualMajor,
                System.getProperty("java.version"),
                System.getProperty("java.vendor"),
                System.getProperty("java.vm.name"),
                System.getProperty("os.name"),
                System.getProperty("os.arch"));
    }
}
