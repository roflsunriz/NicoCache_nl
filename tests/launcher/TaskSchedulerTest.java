package nicocache.launcher;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.xml.sax.SAXException;

/** Unit and integration-contract tests for native logon task registration. */
public final class TaskSchedulerTest {
    private TaskSchedulerTest() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("nicocache-task-test-");
        try {
            testWindowsInstallAndCommandContract(root);
            testFailedNativeRegistrationDoesNotPersist(root);
            testUpdateAndRemove(root);
            testLegacyIntervalMigration(root);
            System.out.println("Task scheduler tests passed");
        } finally {
            deleteTree(root);
        }
    }

    private static void testWindowsInstallAndCommandContract(Path root)
            throws Exception {
        Fixture fixture = createFixture(root, "install");
        fixture.commands.whoamiOutput = "TESTDOMAIN\\test-user\r\n";
        TaskDefinition task = new TaskDefinition("NicoCache CI Probe", true);
        new TaskScheduler(fixture.paths, LauncherPaths.Platform.WINDOWS,
                fixture.commands).install(task);

        Invocation create = fixture.commands.find("/Create");
        String nativeName = argumentAfter(create.arguments, "/TN");
        assertEquals("\\" + task.getId(), nativeName,
                "Windows tasks must use the task root path");
        assertFalse(nativeName.contains("\\NicoCache_nl\\"),
                "Windows registration must not require a missing subfolder");
        assertTrue(create.arguments.contains("/XML"),
                "Windows registration must use an XML task definition");
        assertFalse(create.arguments.contains("/TR"),
                "Windows registration must not depend on /TR quoting");

        String taskXml = fixture.commands.xmlContents;
        assertContains(taskXml, "encoding=\"UTF-16\"",
                "Windows task XML must use the Windows-compatible encoding");
        assertContains(taskXml, "<LogonTrigger>",
                "task XML must use a logon trigger");
        assertContains(taskXml, "<UserId>TESTDOMAIN\\test-user</UserId>",
                "task XML must use the active Windows identity");
        assertContains(taskXml, "<Command>",
                "task XML must separate the executable");
        assertContains(taskXml, "<Arguments>",
                "task XML must separate the arguments");
        assertContains(taskXml, "-jar", "task command must launch the JAR");
        assertFalse(taskXml.contains("&quot;-jar&quot;"),
                "JVM options must not be quoted as executable paths");
        assertFalse(taskXml.contains("--headless"),
                "logon task must open the interactive launcher GUI");
        assertFalse(taskXml.contains("--start"),
                "logon task must let the launcher GUI start the core");
        assertContains(taskXml, "--app-root=" + fixture.paths.getApplicationRoot(),
                "task command must preserve the application root");
        assertContains(taskXml, "--data-root=" + fixture.paths.getDataRoot(),
                "task command must preserve the data root");
        String java = Path.of(System.getProperty("java.home"), "bin",
                "javaw.exe").toString();
        if (java.contains(" ")) {
            assertContains(taskXml, "<Command>" + java + "</Command>",
                    "task command must quote a Java path containing spaces");
        }
        assertFalse(taskXml.contains("StartInterval"),
                "task XML must not contain an interval");

        Invocation query = fixture.commands.find("/Query");
        assertEquals(nativeName, argumentAfter(query.arguments, "/TN"),
                "registration must verify the exact native task");
        Properties store = loadStore(fixture.paths.getTaskStore());
        assertEquals("2", store.getProperty("version"),
                "task store schema version");
        assertEquals("on-logon", store.getProperty("task.0.schedule"),
                "task store trigger");
        assertFalse(store.containsKey("task.0.intervalMinutes"),
                "task store must not retain an interval");
    }

    private static void testFailedNativeRegistrationDoesNotPersist(Path root)
            throws Exception {
        Fixture fixture = createFixture(root, "failed-install");
        fixture.commands.failCreate = true;
        boolean failed = false;
        try {
            new TaskScheduler(fixture.paths, LauncherPaths.Platform.WINDOWS,
                    fixture.commands).install(
                            new TaskDefinition("NicoCache Failed Probe", true));
        } catch (IOException expected) {
            failed = true;
        }
        assertTrue(failed, "native registration failure must reach the caller");
        assertFalse(Files.exists(fixture.paths.getTaskStore()),
                "failed native registration must not be saved as successful");
    }

    private static void testUpdateAndRemove(Path root) throws Exception {
        Fixture fixture = createFixture(root, "update-remove");
        TaskScheduler scheduler = new TaskScheduler(fixture.paths,
                LauncherPaths.Platform.WINDOWS, fixture.commands);
        TaskDefinition oldTask = new TaskDefinition("NicoCache Old Probe", true);
        scheduler.install(oldTask);
        TaskDefinition newTask = new TaskDefinition("NicoCache New Probe", true);
        scheduler.update(oldTask, newTask);
        List<TaskDefinition> updated = scheduler.list();
        assertEquals(1, updated.size(), "updated task count");
        assertEquals(newTask.getName(), updated.get(0).getName(),
                "updated task name");

        scheduler.remove(newTask);
        assertEquals(0, scheduler.list().size(), "removed task count");
        assertEquals("0", loadStore(fixture.paths.getTaskStore())
                .getProperty("count"), "removed task store count");
    }

    private static void testLegacyIntervalMigration(Path root) throws Exception {
        Fixture fixture = createFixture(root, "migration");
        Properties legacy = new Properties();
        legacy.setProperty("version", "1");
        legacy.setProperty("count", "1");
        legacy.setProperty("task.0.name", "NicoCache Legacy Probe");
        legacy.setProperty("task.0.schedule", "interval");
        legacy.setProperty("task.0.intervalMinutes", "15");
        legacy.setProperty("task.0.enabled", "true");
        Files.createDirectories(fixture.paths.getTaskStore().getParent());
        try (var output = Files.newOutputStream(fixture.paths.getTaskStore())) {
            legacy.store(output, "legacy task test");
        }

        List<TaskDefinition> tasks = new TaskScheduler(fixture.paths,
                LauncherPaths.Platform.WINDOWS, fixture.commands).list();
        assertEquals(1, tasks.size(), "migrated task count");
        Properties migrated = loadStore(fixture.paths.getTaskStore());
        assertEquals("2", migrated.getProperty("version"),
                "migrated task store schema version");
        assertEquals("on-logon", migrated.getProperty("task.0.schedule"),
                "legacy interval must become logon trigger");
        assertFalse(migrated.containsKey("task.0.intervalMinutes"),
                "legacy interval must be removed");
        assertTrue(tasks.get(0).toString().contains("on-logon"),
                "migrated task display must state the logon trigger");
    }

    private static Fixture createFixture(Path root, String name)
            throws IOException {
        Path fixtureRoot = root.resolve(name);
        Path applicationRoot = fixtureRoot.resolve("Nico Cache Application");
        Path dataRoot = fixtureRoot.resolve("Nico Cache Data");
        Files.createDirectories(applicationRoot);
        Files.write(applicationRoot.resolve("NicoCache_nl.jar"), new byte[] { 0 });
        Files.write(applicationRoot.resolve("NicoCacheLauncher.jar"),
                new byte[] { 0 });
        return new Fixture(LauncherPaths.resolve(applicationRoot, dataRoot),
                new FakeCommandRunner());
    }

    private static Properties loadStore(Path path) throws IOException {
        Properties properties = new Properties();
        try (var input = Files.newInputStream(path)) {
            properties.load(input);
        }
        return properties;
    }

    private static String argumentAfter(List<String> arguments, String option) {
        int index = arguments.indexOf(option);
        if (index < 0 || index + 1 >= arguments.size()) {
            throw new AssertionError("missing option: " + option + " in "
                    + arguments);
        }
        return arguments.get(index + 1);
    }

    private static void deleteTree(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException error) {
                    throw new UncheckedIOException(error);
                }
            });
        } catch (UncheckedIOException error) {
            throw error.getCause();
        }
    }

    private static void assertWellFormedTaskXml(Path path) throws IOException {
        try {
            var document = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder().parse(path.toFile());
            assertEquals("Task", document.getDocumentElement().getNodeName(),
                    "Windows task XML must be well formed");
        } catch (ParserConfigurationException | SAXException error) {
            throw new IOException("Windows task XML is not well formed: " + path,
                    error);
        }
    }

    private static void assertContains(String actual, String expected,
            String message) {
        if (actual == null || !actual.contains(expected)) {
            throw new AssertionError(message + ": expected=" + expected
                    + ", actual=" + actual);
        }
    }

    private static void assertEquals(Object expected, Object actual,
            String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected
                    + ", actual=" + actual);
        }
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    private static void assertFalse(boolean value, String message) {
        if (value) {
            throw new AssertionError(message);
        }
    }

    private static final class Fixture {
        final LauncherPaths paths;
        final FakeCommandRunner commands;

        Fixture(LauncherPaths paths, FakeCommandRunner commands) {
            this.paths = paths;
            this.commands = commands;
        }
    }

    private static final class Invocation {
        final String command;
        final List<String> arguments;

        Invocation(String command, List<String> arguments) {
            this.command = command;
            this.arguments = arguments;
        }
    }

    private static final class FakeCommandRunner implements TaskCommandRunner {
        final List<Invocation> invocations = new ArrayList<>();
        String xmlContents;
        boolean failCreate;
        String whoamiOutput = "";

        @Override
        public TaskCommandResult run(String command, List<String> arguments)
                throws IOException {
            invocations.add(new Invocation(command, List.copyOf(arguments)));
            if (arguments.contains("/XML")) {
                Path xmlPath = Path.of(argumentAfter(arguments, "/XML"));
                byte[] bytes = Files.readAllBytes(xmlPath);
                assertEquals(0xff, bytes[0] & 0xff,
                        "Windows task XML must have a UTF-16LE BOM");
                assertEquals(0xfe, bytes[1] & 0xff,
                        "Windows task XML must have a UTF-16LE BOM");
                assertWellFormedTaskXml(xmlPath);
                xmlContents = Files.readString(xmlPath,
                        StandardCharsets.UTF_16);
            }
            if ("whoami".equals(command)) {
                return new TaskCommandResult(0, whoamiOutput);
            }
            if (failCreate && arguments.contains("/Create")) {
                return new TaskCommandResult(42, "fake schtasks failure");
            }
            return new TaskCommandResult(0, "");
        }

        Invocation find(String option) {
            for (Invocation invocation : invocations) {
                if ("schtasks".equals(invocation.command)
                        && invocation.arguments.contains(option)) {
                    return invocation;
                }
            }
            throw new AssertionError("missing schtasks invocation: " + option);
        }
    }
}
