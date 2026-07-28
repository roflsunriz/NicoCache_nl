package dareka;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

final class FirstRunSetupService {
    interface CertificateGenerator {
        void generate() throws Exception;

        void rollback() throws Exception;
    }

    interface SystemIntegration {
        void apply(SetupOptions options) throws Exception;

        void rollback() throws Exception;
    }

    private final SetupFiles files;
    private final CertificateGenerator certificates;
    private final SystemIntegration systemIntegration;

    FirstRunSetupService(SetupFiles files, CertificateGenerator certificates,
            SystemIntegration systemIntegration) {
        this.files = files;
        this.certificates = certificates;
        this.systemIntegration = systemIntegration;
    }

    static FirstRunSetupService production(
            Path appDirectory, Path dataDirectory) {
        return new FirstRunSetupService(
                new SetupFiles(appDirectory, dataDirectory),
                new PackagedCertificateGenerator(
                        appDirectory, dataDirectory),
                new WindowsSetupIntegration(
                        appDirectory, dataDirectory));
    }

    void apply(SetupOptions options) throws Exception {
        boolean certificateStarted = false;
        boolean integrationStarted = false;
        try {
            files.prepare(options);
            if (options.isHttpsEnabled()) {
                certificateStarted = true;
                certificates.generate();
            }
            if (options.needsSystemIntegration()) {
                integrationStarted = true;
                systemIntegration.apply(options);
            }
            files.complete(options);
        } catch (Exception error) {
            if (integrationStarted) {
                rollback(error, systemIntegration::rollback);
            }
            if (certificateStarted) {
                rollback(error, certificates::rollback);
            }
            rollback(error, files::rollback);
            throw error;
        }
    }

    private static void rollback(Exception original, RollbackAction action) {
        try {
            action.run();
        } catch (Exception rollbackError) {
            original.addSuppressed(rollbackError);
        }
    }

    @FunctionalInterface
    private interface RollbackAction {
        void run() throws Exception;
    }

    static final class SetupFiles {
        private final Path appDirectory;
        private final Path dataDirectory;
        private final List<Path> createdFiles = new ArrayList<>();

        SetupFiles(Path appDirectory) {
            this(appDirectory, appDirectory);
        }

        SetupFiles(Path appDirectory, Path dataDirectory) {
            this.appDirectory = appDirectory.toAbsolutePath().normalize();
            this.dataDirectory = dataDirectory.toAbsolutePath().normalize();
        }

        void prepare(SetupOptions options) throws IOException {
            Files.createDirectories(dataDirectory.resolve("data"));
            createConfig(options.isHttpsEnabled());
            if (options.isProxyConfigured()) {
                copyIfMissing(
                        appDirectory.resolve("proxy_sample.pac"),
                        dataDirectory.resolve("proxy.pac"));
            }
            createGuiProperties();
        }

        void complete(SetupOptions options) throws IOException {
            Properties state = new Properties();
            state.setProperty("version", "1");
            state.setProperty("status", "complete");
            state.setProperty("completedAt", Instant.now().toString());
            state.setProperty("enableHttps",
                    Boolean.toString(options.isHttpsEnabled()));
            state.setProperty("trustCertificate",
                    Boolean.toString(options.isCertificateTrusted()));
            state.setProperty("configureProxy",
                    Boolean.toString(options.isProxyConfigured()));
            state.setProperty("enableAutoStart",
                    Boolean.toString(options.isAutoStartEnabled()));

            Path statePath = dataDirectory.resolve(
                    "data/first-run-setup.properties");
            if (Files.exists(statePath)) {
                throw new IOException("初回セットアップ状態が既に存在します: "
                        + statePath);
            }
            Path temporary = statePath.resolveSibling(
                    statePath.getFileName() + ".setup.tmp");
            try {
                try (var output = Files.newOutputStream(
                        temporary,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE)) {
                    state.store(output, "NicoCache_nl first-run setup state");
                }
                moveAtomically(temporary, statePath);
            } catch (IOException error) {
                Files.deleteIfExists(temporary);
                throw error;
            }
            createdFiles.add(statePath);
        }

        void rollback() throws IOException {
            IOException failure = null;
            for (int index = createdFiles.size() - 1; index >= 0; index--) {
                try {
                    Files.deleteIfExists(createdFiles.get(index));
                } catch (IOException error) {
                    if (failure == null) {
                        failure = error;
                    } else {
                        failure.addSuppressed(error);
                    }
                }
            }
            createdFiles.clear();
            if (failure != null) {
                throw failure;
            }
        }

        private void createConfig(boolean enableHttps) throws IOException {
            Path source = appDirectory.resolve("config.properties.default");
            Path target = dataDirectory.resolve("config.properties");
            if (Files.exists(target)) {
                throw new IOException("設定ファイルが既に存在します: " + target);
            }
            if (!Files.isRegularFile(source)) {
                throw new IOException("既定設定がありません: " + source);
            }

            Path temporary = target.resolveSibling(
                    target.getFileName() + ".setup.tmp");
            try {
                Files.copy(
                        source,
                        temporary,
                        StandardCopyOption.COPY_ATTRIBUTES);
                String option = System.lineSeparator()
                        + "enableMitM=" + enableHttps
                        + System.lineSeparator();
                Files.writeString(
                        temporary,
                        option,
                        StandardCharsets.US_ASCII,
                        StandardOpenOption.APPEND);
                moveAtomically(temporary, target);
            } catch (IOException error) {
                Files.deleteIfExists(temporary);
                throw error;
            }
            createdFiles.add(target);
        }

        private void createGuiProperties() throws IOException {
            Path target = dataDirectory.resolve("NicoCacheGUI.property");
            if (Files.exists(target)) {
                return;
            }
            Path temporary = target.resolveSibling(
                    target.getFileName() + ".setup.tmp");
            try {
                Files.writeString(
                        temporary,
                        "HideWindow=true" + System.lineSeparator()
                                + "LogWindowAlwaysOnTop=false"
                                + System.lineSeparator(),
                        StandardCharsets.US_ASCII,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
                moveAtomically(temporary, target);
            } catch (IOException error) {
                Files.deleteIfExists(temporary);
                throw error;
            }
            createdFiles.add(target);
        }

        private void copyIfMissing(Path source, Path target) throws IOException {
            if (Files.exists(target)) {
                return;
            }
            if (!Files.isRegularFile(source)) {
                throw new IOException("コピー元ファイルがありません: " + source);
            }
            Path temporary = target.resolveSibling(
                    target.getFileName() + ".setup.tmp");
            try {
                Files.copy(source, temporary);
                moveAtomically(temporary, target);
            } catch (IOException error) {
                Files.deleteIfExists(temporary);
                throw error;
            }
            createdFiles.add(target);
        }

        private static void moveAtomically(Path source, Path target)
                throws IOException {
            try {
                Files.move(
                        source,
                        target,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(source, target);
            }
        }
    }

    private static final class PackagedCertificateGenerator
            implements CertificateGenerator {
        private static final String CERTS_DIRECTORY_PROPERTY =
                "nicocacheca.certsDirectory";
        private final Path appDirectory;
        private final Path dataDirectory;
        private final Set<Path> generatedFiles = new HashSet<>();

        private PackagedCertificateGenerator(
                Path appDirectory, Path dataDirectory) {
            this.appDirectory = appDirectory.toAbsolutePath().normalize();
            this.dataDirectory = dataDirectory.toAbsolutePath().normalize();
        }

        @Override
        public void generate() throws Exception {
            Path certificateDirectory = dataDirectory.resolve("certs");
            Files.createDirectories(certificateDirectory);
            Set<Path> existing = listRegularFiles(certificateDirectory);
            if (Files.isRegularFile(certificateDirectory.resolve("ca.cer"))
                    && Files.isRegularFile(
                            certificateDirectory.resolve("site.jks"))) {
                return;
            }

            Path targetsFile = appDirectory.resolve("certificate-targets.txt");
            if (!Files.isRegularFile(targetsFile)) {
                throw new IOException("証明書対象一覧がありません: " + targetsFile);
            }
            List<String> targets = new ArrayList<>();
            for (String line : Files.readAllLines(
                    targetsFile, StandardCharsets.US_ASCII)) {
                String target = line.trim();
                if (!target.isEmpty() && !target.startsWith("#")) {
                    targets.add(target);
                }
            }
            if (targets.isEmpty()) {
                throw new IOException("証明書対象一覧が空です: " + targetsFile);
            }
            String previousCertificateDirectory =
                    System.getProperty(CERTS_DIRECTORY_PROPERTY);
            System.setProperty(
                    CERTS_DIRECTORY_PROPERTY,
                    certificateDirectory.toString());
            try {
                Class<?> generator = Class.forName("nicocacheca.NicoCacheCA");
                generator.getMethod("main", String[].class).invoke(
                        null,
                        (Object) targets.toArray(new String[0]));
            } catch (InvocationTargetException error) {
                Throwable cause = error.getCause();
                if (cause instanceof Exception) {
                    throw (Exception) cause;
                }
                throw error;
            } finally {
                if (previousCertificateDirectory == null) {
                    System.clearProperty(CERTS_DIRECTORY_PROPERTY);
                } else {
                    System.setProperty(
                            CERTS_DIRECTORY_PROPERTY,
                            previousCertificateDirectory);
                }
                recordGeneratedFiles(certificateDirectory, existing);
            }
            for (String required : new String[] {
                    "ca.cer", "ca.jks", "site.jks", "site.targets" }) {
                if (!Files.isRegularFile(certificateDirectory.resolve(required))) {
                    throw new IOException("証明書生成物がありません: " + required);
                }
            }
        }

        @Override
        public void rollback() throws IOException {
            IOException failure = null;
            for (Path generated : generatedFiles) {
                try {
                    Files.deleteIfExists(generated);
                } catch (IOException error) {
                    if (failure == null) {
                        failure = error;
                    } else {
                        failure.addSuppressed(error);
                    }
                }
            }
            generatedFiles.clear();
            if (failure != null) {
                throw failure;
            }
        }

        private void recordGeneratedFiles(Path directory, Set<Path> existing)
                throws IOException {
            for (Path current : listRegularFiles(directory)) {
                if (!existing.contains(current)) {
                    generatedFiles.add(current);
                }
            }
        }

        private static Set<Path> listRegularFiles(Path directory)
                throws IOException {
            Set<Path> files = new HashSet<>();
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(
                    directory)) {
                for (Path entry : entries) {
                    if (Files.isRegularFile(entry)) {
                        files.add(entry.toAbsolutePath().normalize());
                    }
                }
            }
            return files;
        }
    }

    private static final class WindowsSetupIntegration
            implements SystemIntegration {
        private static final long TIMEOUT_SECONDS = 300L;
        private final Path appDirectory;
        private final Path dataDirectory;
        private final Path statePath;
        private final Path errorPath;
        private final Path rollbackErrorPath;

        private WindowsSetupIntegration(
                Path appDirectory, Path dataDirectory) {
            this.appDirectory = appDirectory.toAbsolutePath().normalize();
            this.dataDirectory = dataDirectory.toAbsolutePath().normalize();
            this.statePath = this.dataDirectory.resolve(
                    "data/setup-system-state.json");
            this.errorPath = this.dataDirectory.resolve(
                    "data/setup-windows-error.txt");
            this.rollbackErrorPath = this.dataDirectory.resolve(
                    "data/setup-windows-rollback-error.txt");
        }

        @Override
        public void apply(SetupOptions options) throws Exception {
            Path script = appDirectory.resolve(
                    "setup/windows/first-run-setup.ps1");
            if (!Files.isRegularFile(script)) {
                throw new IOException("Windows設定スクリプトがありません: " + script);
            }
            String appPath = System.getProperty("jpackage.app-path");
            Path launcher = appPath == null || appPath.isBlank()
                    ? appDirectory.resolve("NicoCache_nl.exe")
                    : Path.of(appPath);
            List<String> command = new ArrayList<>();
            command.add("powershell.exe");
            command.add("-WindowStyle");
            command.add("Hidden");
            command.add("-NoProfile");
            command.add("-NonInteractive");
            command.add("-ExecutionPolicy");
            command.add("Bypass");
            command.add("-File");
            command.add(script.toString());
            command.add("-Action");
            command.add("Apply");
            command.add("-StatePath");
            command.add(statePath.toString());
            command.add("-ErrorPath");
            command.add(errorPath.toString());
            command.add("-CaCertificatePath");
            command.add(dataDirectory.resolve("certs/ca.cer").toString());
            command.add("-AutoConfigUrl");
            command.add("http://localhost:8080/proxy.pac");
            command.add("-LauncherPath");
            command.add(launcher.toAbsolutePath().normalize().toString());
            if (options.isCertificateTrusted()) {
                command.add("-EnableCertificate");
            }
            if (options.isProxyConfigured()) {
                command.add("-EnableProxy");
            }
            if (options.isAutoStartEnabled()) {
                command.add("-EnableAutoStart");
            }
            run(command, errorPath);
        }

        @Override
        public void rollback() throws Exception {
            if (!Files.isRegularFile(statePath)) {
                return;
            }
            Path script = appDirectory.resolve(
                    "setup/windows/first-run-setup.ps1");
            run(List.of(
                    "powershell.exe",
                    "-WindowStyle",
                    "Hidden",
                    "-NoProfile",
                    "-NonInteractive",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-File",
                    script.toString(),
                    "-Action",
                    "Rollback",
                    "-StatePath",
                    statePath.toString(),
                    "-ErrorPath",
                    rollbackErrorPath.toString()),
                    rollbackErrorPath);
        }

        private void run(List<String> command, Path processErrorPath)
                throws Exception {
            Files.deleteIfExists(processErrorPath);
            Process process = new ProcessBuilder(command)
                    .directory(appDirectory.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5L, TimeUnit.SECONDS);
                throw failure(
                        "Windows設定処理が時間内に完了しませんでした",
                        processErrorPath);
            }
            if (process.exitValue() != 0) {
                throw failure(
                        "Windows設定処理に失敗しました (ExitCode: "
                                + process.exitValue() + ")",
                        processErrorPath);
            }
            Files.deleteIfExists(processErrorPath);
        }

        private static IOException failure(String message, Path errorPath) {
            String detail = "";
            try {
                if (Files.isRegularFile(errorPath)) {
                    detail = Files.readString(
                            errorPath,
                            StandardCharsets.UTF_8).trim();
                    if (detail.startsWith("\uFEFF")) {
                        detail = detail.substring(1);
                    }
                }
            } catch (IOException readError) {
                return new IOException(
                        message + System.lineSeparator()
                                + "診断ファイルを読み取れません: " + errorPath,
                        readError);
            }
            if (detail.isEmpty()) {
                return new IOException(message);
            }
            return new IOException(
                    message + System.lineSeparator()
                            + detail + System.lineSeparator()
                            + "診断ファイル: " + errorPath);
        }
    }
}
