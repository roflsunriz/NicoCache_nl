package dareka;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** Linux and macOS integration used by the first-run wizard. */
final class UnixSetupIntegration
        implements FirstRunSetupService.SystemIntegration {
    private static final long COMMAND_TIMEOUT_SECONDS = 60L;
    private static final String PROXY_URL = "http://localhost:8080/proxy.pac";
    private static final String STATE_FILE = "data/setup-system-state.properties";

    private final Path appDirectory;
    private final Path dataDirectory;
    private final PlatformSupport.Kind platform;
    private final Path statePath;
    private final Path errorPath;

    UnixSetupIntegration(Path appDirectory, Path dataDirectory) {
        this.appDirectory = appDirectory.toAbsolutePath().normalize();
        this.dataDirectory = dataDirectory.toAbsolutePath().normalize();
        this.platform = PlatformSupport.current();
        this.statePath = this.dataDirectory.resolve(STATE_FILE);
        this.errorPath = this.dataDirectory.resolve(
                "data/setup-unix-error.txt");
    }

    @Override
    public void apply(SetupOptions options) throws Exception {
        requireSupportedPlatform();
        if (Files.exists(statePath)) {
            throw new IOException("初回セットアップのOS設定状態が既に存在します: "
                    + statePath);
        }

        Properties state = new Properties();
        state.setProperty("version", "1");
        state.setProperty("platform", platform.name().toLowerCase(Locale.ROOT));
        state.setProperty("status", "applying");
        writeState(state);
        try {
            if (options.isCertificateTrusted()) {
                applyCertificate(state);
            }
            if (options.isProxyConfigured()) {
                applyProxy(state);
            }
            if (options.isAutoStartEnabled()) {
                applyAutoStart(state);
            }
            state.setProperty("status", "applied");
            writeState(state);
            Files.deleteIfExists(errorPath);
        } catch (Exception error) {
            writeError(errorPath, error);
            throw error;
        }
    }

    @Override
    public void rollback() throws Exception {
        if (!Files.isRegularFile(statePath)) {
            return;
        }
        Properties state = readState();
        IOException failure = null;
        failure = rollbackAction(state, "autostart.applied",
                this::rollbackAutoStart, failure);
        failure = rollbackAction(state, "proxy.applied",
                this::rollbackProxy, failure);
        failure = rollbackAction(state, "certificate.applied",
                this::rollbackCertificate, failure);
        if (failure != null) {
            writeError(errorPath, failure);
            throw failure;
        }
        Files.deleteIfExists(statePath);
        Files.deleteIfExists(errorPath);
    }

    private IOException rollbackAction(Properties state, String key,
            RollbackAction action, IOException failure) {
        if (!Boolean.parseBoolean(state.getProperty(key, "false"))) {
            return failure;
        }
        try {
            action.run(state);
        } catch (Exception error) {
            if (failure == null) {
                failure = new IOException("OS設定の復元に失敗しました", error);
            } else {
                failure.addSuppressed(error);
            }
        }
        return failure;
    }

    private void applyCertificate(Properties state) throws Exception {
        Path certificate = dataDirectory.resolve("certs/ca.cer");
        if (!Files.isRegularFile(certificate)) {
            throw new IOException("信頼登録するCA証明書がありません: " + certificate);
        }
        boolean preexisting;
        if (platform == PlatformSupport.Kind.MACOS) {
            Path keychain = PlatformSupport.userHome().resolve(
                    "Library/Keychains/login.keychain-db");
            state.setProperty("certificate.method", "security");
            state.setProperty("certificate.keychain", keychain.toString());
            String fingerprint = certificateSha1(certificate);
            state.setProperty("certificate.fingerprint", fingerprint);
            preexisting = macCertificatePresent(keychain, fingerprint);
            writeState(state);
        } else {
            state.setProperty("certificate.method", "trust");
            state.setProperty("certificate.path", certificate.toString());
            preexisting = linuxCertificatePresent(certificate);
            writeState(state);
        }
        state.setProperty("certificate.preexisting", Boolean.toString(preexisting));
        writeState(state);
        if (!preexisting) {
            if (platform == PlatformSupport.Kind.MACOS) {
                run(List.of("security", "add-trusted-cert", "-d", "-r",
                        "trustRoot", "-k", state.getProperty("certificate.keychain"),
                        certificate.toString()));
            } else {
                run(List.of("trust", "anchor", "--store", certificate.toString()));
            }
        }
        state.setProperty("certificate.applied", "true");
        writeState(state);
    }

    private void rollbackCertificate(Properties state) throws Exception {
        if (Boolean.parseBoolean(state.getProperty("certificate.preexisting", "false"))) {
            return;
        }
        if ("security".equals(state.getProperty("certificate.method"))) {
            run(List.of("security", "delete-certificate", "-Z",
                    state.getProperty("certificate.fingerprint"),
                    state.getProperty("certificate.keychain")));
        } else {
            run(List.of("trust", "anchor", "--remove",
                    state.getProperty("certificate.path")));
        }
    }

    private void applyProxy(Properties state) throws Exception {
        if (platform == PlatformSupport.Kind.MACOS) {
            applyMacProxy(state);
        } else {
            applyLinuxProxy(state);
        }
        state.setProperty("proxy.applied", "true");
        writeState(state);
    }

    private void rollbackProxy(Properties state) throws Exception {
        if (platform == PlatformSupport.Kind.MACOS) {
            rollbackMacProxy(state);
        } else {
            rollbackLinuxProxy(state);
        }
    }

    private void applyLinuxProxy(Properties state) throws Exception {
        String mode = run(List.of("gsettings", "get",
                "org.gnome.system.proxy", "mode")).stdout.trim();
        String url = run(List.of("gsettings", "get",
                "org.gnome.system.proxy", "autoconfig-url")).stdout.trim();
        state.setProperty("proxy.method", "gsettings");
        state.setProperty("proxy.mode", mode);
        state.setProperty("proxy.url", url);
        writeState(state);
        run(List.of("gsettings", "set", "org.gnome.system.proxy",
                "mode", gSettingsValue("auto")));
        run(List.of("gsettings", "set", "org.gnome.system.proxy",
                "autoconfig-url", gSettingsValue(PROXY_URL)));
    }

    private void rollbackLinuxProxy(Properties state) throws Exception {
        run(List.of("gsettings", "set", "org.gnome.system.proxy",
                "mode", gSettingsValue(state.getProperty("proxy.mode", "'none'"))));
        run(List.of("gsettings", "set", "org.gnome.system.proxy",
                "autoconfig-url", gSettingsValue(state.getProperty("proxy.url", "''"))));
    }

    private void applyMacProxy(Properties state) throws Exception {
        List<String> services = networkServices();
        if (services.isEmpty()) {
            throw new IOException("macOSのネットワークサービスが見つかりません");
        }
        state.setProperty("proxy.method", "networksetup");
        state.setProperty("proxy.count", Integer.toString(services.size()));
        for (int index = 0; index < services.size(); index++) {
            String service = services.get(index);
            String prefix = "proxy." + index + ".";
            state.setProperty(prefix + "service", encode(service));
            ProxyState old = readMacProxy(service);
            state.setProperty(prefix + "enabled", Boolean.toString(old.enabled));
            state.setProperty(prefix + "url", encode(old.url));
            writeState(state);
            run(List.of("networksetup", "-setautoproxyurl", service, PROXY_URL));
            run(List.of("networksetup", "-setautoproxystate", service, "on"));
        }
    }

    private void rollbackMacProxy(Properties state) throws Exception {
        int count = Integer.parseInt(state.getProperty("proxy.count", "0"));
        for (int index = 0; index < count; index++) {
            String prefix = "proxy." + index + ".";
            String service = decode(state.getProperty(prefix + "service", ""));
            String url = decode(state.getProperty(prefix + "url", ""));
            // networksetup accepts a single space as the cleared URL value.
            // Restore this setting even when it was originally disabled or unset.
            run(List.of("networksetup", "-setautoproxyurl", service,
                    url.isBlank() ? " " : url));
            run(List.of("networksetup", "-setautoproxystate", service,
                    Boolean.parseBoolean(state.getProperty(prefix + "enabled", "false"))
                            ? "on" : "off"));
        }
    }

    private List<String> networkServices() throws Exception {
        CommandResult result = run(List.of("networksetup", "-listallnetworkservices"));
        List<String> services = new ArrayList<>();
        String[] lines = result.stdout.split("\\R");
        for (int index = 1; index < lines.length; index++) {
            String service = lines[index].trim();
            if (!service.isEmpty() && !service.startsWith("*")) {
                services.add(service);
            }
        }
        return services;
    }

    private ProxyState readMacProxy(String service) throws Exception {
        CommandResult result = run(List.of("networksetup", "-getautoproxyurl", service));
        boolean enabled = false;
        String url = "";
        for (String line : result.stdout.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.toLowerCase(Locale.ROOT).startsWith("enabled:")) {
                enabled = trimmed.toLowerCase(Locale.ROOT).endsWith("yes");
            } else if (trimmed.toLowerCase(Locale.ROOT).startsWith("url:")) {
                url = trimmed.substring(trimmed.indexOf(':') + 1).trim();
            }
        }
        return new ProxyState(enabled, url);
    }

    private boolean macCertificatePresent(Path keychain, String fingerprint)
            throws Exception {
        CommandResult result = run(List.of("security", "find-certificate", "-a",
                "-Z", keychain.toString()));
        return result.stdout.toUpperCase(Locale.ROOT)
                .contains(fingerprint.toUpperCase(Locale.ROOT));
    }

    private boolean linuxCertificatePresent(Path certificate) throws Exception {
        CommandResult result = run(List.of("trust", "list", "--filter=ca-anchors"));
        String listing = result.stdout.toUpperCase(Locale.ROOT);
        return listing.contains(percentEncodedSha1(certificateSha1(certificate)))
                || listing.contains(percentEncodedSha1(certificatePublicKeySha1(certificate)));
    }

    private void applyAutoStart(Properties state) throws Exception {
        Path file = autoStartFile();
        state.setProperty("autostart.path", file.toString());
        state.setProperty("autostart.existed", Boolean.toString(Files.exists(file)));
        if (Files.isRegularFile(file)) {
            state.setProperty("autostart.content",
                    encode(Files.readString(file, StandardCharsets.UTF_8)));
        }
        writeState(state);
        Files.createDirectories(file.getParent());
        Files.writeString(file, autoStartContent(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        state.setProperty("autostart.applied", "true");
        writeState(state);
    }

    private void rollbackAutoStart(Properties state) throws IOException {
        Path file = Path.of(state.getProperty("autostart.path"));
        if (Boolean.parseBoolean(state.getProperty("autostart.existed", "false"))) {
            Files.writeString(file,
                    decode(state.getProperty("autostart.content", "")),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } else {
            Files.deleteIfExists(file);
        }
    }

    private Path autoStartFile() {
        if (platform == PlatformSupport.Kind.MACOS) {
            return PlatformSupport.userHome().resolve(
                    "Library/LaunchAgents/jp.nicocache.NicoCache_nl.plist");
        }
        String configHome = System.getenv("XDG_CONFIG_HOME");
        Path root = configHome == null || configHome.isBlank()
                ? PlatformSupport.userHome().resolve(".config")
                : Path.of(configHome);
        return root.resolve("autostart/NicoCache_nl.desktop");
    }

    private String autoStartContent() {
        Path launcher = PlatformSupport.launcherPath(appDirectory, platform);
        if (platform == PlatformSupport.Kind.MACOS) {
            return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" "
                    + "\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
                    + "<plist version=\"1.0\"><dict>\n"
                    + "<key>Label</key><string>jp.nicocache.NicoCache_nl</string>\n"
                    + "<key>ProgramArguments</key><array><string>"
                    + xml(launcher.toString())
                    + "</string><string>--headless</string></array>\n"
                    + "<key>RunAtLoad</key><true/>\n"
                    + "</dict></plist>\n";
        }
        return "[Desktop Entry]\n"
                + "Type=Application\n"
                + "Name=NicoCache_nl\n"
                + "Exec=" + desktopExec(launcher) + " --headless\n"
                + "Terminal=false\n"
                + "X-GNOME-Autostart-enabled=true\n";
    }

    private void requireSupportedPlatform() throws IOException {
        if (platform != PlatformSupport.Kind.LINUX
                && platform != PlatformSupport.Kind.MACOS) {
            throw new IOException("LinuxまたはmacOSでのみOS連携を利用できます");
        }
    }

    private CommandResult run(List<String> command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(appDirectory.toFile())
                .redirectErrorStream(true)
                .start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> copy(process.getInputStream(), output),
                "nicocache-setup-command-reader");
        reader.start();
        if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(5L, TimeUnit.SECONDS);
            throw new IOException("OS設定コマンドが時間内に完了しませんでした: "
                    + command.get(0));
        }
        reader.join(Duration.ofSeconds(5).toMillis());
        String text = output.toString(StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            throw new IOException("OS設定コマンドに失敗しました (ExitCode: "
                    + process.exitValue() + "): " + command.get(0)
                    + (text.isBlank() ? "" : System.lineSeparator() + text.trim()));
        }
        return new CommandResult(process.exitValue(), text);
    }

    private static void copy(InputStream input, ByteArrayOutputStream output) {
        try (InputStream source = input) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = source.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
        } catch (IOException ignored) {
            // The process result is still checked by the caller.
        }
    }

    private void writeState(Properties state) throws IOException {
        Files.createDirectories(statePath.getParent());
        Path temporary = statePath.resolveSibling(
                statePath.getFileName() + ".setup.tmp");
        try (var output = Files.newOutputStream(temporary,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            state.store(output, "NicoCache_nl Unix first-run setup state");
        }
        try {
            Files.move(temporary, statePath, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(temporary, statePath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Properties readState() throws IOException {
        Properties state = new Properties();
        try (InputStream input = Files.newInputStream(statePath)) {
            state.load(input);
        }
        return state;
    }

    private static void writeError(Path path, Exception error) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, error.toString() + System.lineSeparator(),
                    StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // The original exception remains the actionable failure.
        }
    }

    private static String certificateSha1(Path certificate) throws Exception {
        X509Certificate parsed;
        try (InputStream input = Files.newInputStream(certificate)) {
            parsed = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(input);
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        return hex(digest.digest(parsed.getEncoded()));
    }

    private static String certificatePublicKeySha1(Path certificate) throws Exception {
        X509Certificate parsed;
        try (InputStream input = Files.newInputStream(certificate)) {
            parsed = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(input);
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        return hex(digest.digest(parsed.getPublicKey().getEncoded()));
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(40);
        for (byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%02X", value & 0xff));
        }
        return result.toString();
    }

    private static String percentEncodedSha1(String fingerprint) {
        StringBuilder result = new StringBuilder(fingerprint.length() / 2 * 3);
        for (int index = 0; index < fingerprint.length(); index += 2) {
            result.append('%').append(fingerprint, index, index + 2);
        }
        return result.toString();
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(
                value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static String unquoteGSettings(String value) {
        String result = value == null ? "" : value.trim();
        if (result.length() >= 2
                && ((result.startsWith("'") && result.endsWith("'"))
                || (result.startsWith("\"") && result.endsWith("\"")))) {
            result = result.substring(1, result.length() - 1);
        }
        return result;
    }

    private static String gSettingsValue(String value) {
        return "'" + unquoteGSettings(value).replace("'", "\\'") + "'";
    }

    private static String desktopExec(Path path) {
        return '"' + path.toString().replace("\\", "\\\\")
                .replace("\"", "\\\"") + '"';
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    @FunctionalInterface
    private interface RollbackAction {
        void run(Properties state) throws Exception;
    }

    private static final class CommandResult {
        final int exitCode;
        final String stdout;

        CommandResult(int exitCode, String stdout) {
            this.exitCode = exitCode;
            this.stdout = stdout;
        }
    }

    private static final class ProxyState {
        final boolean enabled;
        final String url;

        ProxyState(boolean enabled, String url) {
            this.enabled = enabled;
            this.url = url;
        }
    }
}
