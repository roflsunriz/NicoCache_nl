package dareka;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * Updates locally installed filters, local assets and extensions from a
 * manifest. User-modified managed files are never overwritten silently.
 */
public final class LocalDependencyUpdater {
    public static final String MANIFEST_URI_PROPERTY =
            "nicocache.dependencies.manifestUri";
    public static final String DATA_ROOT_PROPERTY = "nicocache.dataRoot";
    public static final String CHECK_ONLY_ARGUMENT = "--check-dependency-updates";
    public static final String UPDATE_ARGUMENT = "--update-dependencies";

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final String STATE_FILE = ".dependency-updater-state.properties";
    private static final String BACKUP_DIRECTORY = ".dependency-updater-backups";
    private static final Set<String> ALLOWED_ROOTS = Set.of(
            "local", "nlFilters", "extensions", "lib", "certs", "data");

    private LocalDependencyUpdater() {
    }

    public static boolean handleCommandLine(String[] args) {
        boolean checkOnly = contains(args, CHECK_ONLY_ARGUMENT);
        boolean update = contains(args, UPDATE_ARGUMENT);
        if (!checkOnly && !update) {
            return false;
        }
        try {
            UpdatePlan plan = check();
            printPlan(plan);
            if (update && !plan.updates.isEmpty()) {
                apply(plan);
            }
            return true;
        } catch (Exception error) {
            System.err.println("依存ファイルの更新に失敗しました: " + error.getMessage());
            error.printStackTrace(System.err);
            return true;
        }
    }

    public static UpdatePlan check() throws IOException, InterruptedException {
        URI manifestUri = manifestUri();
        Properties manifest = downloadProperties(manifestUri);
        Path dataRoot = dataRoot();
        Properties state = loadState(dataRoot);
        List<ComponentUpdate> updates = new ArrayList<>();
        List<ComponentUpdate> modified = new ArrayList<>();

        for (String id : componentIds(manifest)) {
            Component component = Component.from(manifest, id, manifestUri);
            Path target = resolveTarget(dataRoot, component.target);
            String currentHash = Files.isRegularFile(target) ? sha256(target) : null;
            String installedHash = state.getProperty("component." + id + ".sha256");
            if (component.sha256.equalsIgnoreCase(currentHash)) {
                continue;
            }
            ComponentUpdate candidate = new ComponentUpdate(
                    component, target, currentHash, installedHash);
            if (currentHash != null && installedHash != null
                    && !installedHash.equalsIgnoreCase(currentHash)) {
                modified.add(candidate);
            } else if (currentHash != null && installedHash == null) {
                // Pre-existing files have unknown provenance. Never overwrite them
                // until they have been explicitly adopted or moved by the user.
                modified.add(candidate);
            } else {
                updates.add(candidate);
            }
        }
        return new UpdatePlan(dataRoot, state, updates, modified);
    }

    public static void apply(UpdatePlan plan)
            throws IOException, InterruptedException {
        if (!plan.modified.isEmpty()) {
            throw new IOException("利用者編集または管理外のファイルがあります。"
                    + "対象を確認してから再実行してください");
        }
        if (plan.updates.isEmpty()) {
            return;
        }

        Files.createDirectories(plan.dataRoot);
        Path backupRoot = plan.dataRoot.resolve(BACKUP_DIRECTORY)
                .resolve(Long.toString(Instant.now().toEpochMilli()));
        List<AppliedUpdate> applied = new ArrayList<>();
        try {
            for (ComponentUpdate update : plan.updates) {
                AppliedUpdate result = installOne(update, backupRoot);
                applied.add(result);
                String prefix = "component." + update.component.id + ".";
                plan.state.setProperty(prefix + "version", update.component.version);
                plan.state.setProperty(prefix + "sha256", update.component.sha256);
                plan.state.setProperty(prefix + "target", update.component.target);
            }
            saveState(plan.dataRoot, plan.state);
        } catch (IOException | InterruptedException error) {
            rollback(applied);
            throw error;
        }
    }

    private static AppliedUpdate installOne(ComponentUpdate update, Path backupRoot)
            throws IOException, InterruptedException {
        Files.createDirectories(update.target.getParent());
        Path temporary = Files.createTempFile(update.target.getParent(),
                ".dependency-update-", ".download");
        try {
            download(update.component.downloadUri, temporary);
            String actual = sha256(temporary);
            if (!update.component.sha256.equalsIgnoreCase(actual)) {
                throw new IOException("SHA-256が一致しません: "
                        + update.component.id);
            }

            Path backup = null;
            if (Files.exists(update.target)) {
                Path relative = dataRoot().relativize(update.target);
                backup = backupRoot.resolve(relative);
                Files.createDirectories(backup.getParent());
                Files.copy(update.target, backup,
                        StandardCopyOption.COPY_ATTRIBUTES,
                        StandardCopyOption.REPLACE_EXISTING);
            }
            atomicReplace(temporary, update.target);
            return new AppliedUpdate(update.target, backup);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void rollback(List<AppliedUpdate> applied) {
        Collections.reverse(applied);
        for (AppliedUpdate update : applied) {
            try {
                if (update.backup == null) {
                    Files.deleteIfExists(update.target);
                } else {
                    Files.copy(update.backup, update.target,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.COPY_ATTRIBUTES);
                }
            } catch (IOException rollbackError) {
                System.err.println("ロールバックに失敗しました: "
                        + update.target + ": " + rollbackError.getMessage());
            }
        }
    }

    private static void atomicReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static URI manifestUri() throws IOException {
        String value = System.getProperty(MANIFEST_URI_PROPERTY);
        if (value == null || value.isBlank()) {
            throw new IOException("更新マニフェストURLが未設定です (-D"
                    + MANIFEST_URI_PROPERTY + "=https://...)");
        }
        URI uri = URI.create(value);
        requireHttps(uri);
        return uri;
    }

    private static Properties downloadProperties(URI uri)
            throws IOException, InterruptedException {
        Path temporary = Files.createTempFile("nicocache-dependencies-", ".properties");
        try {
            download(uri, temporary);
            Properties properties = new Properties();
            try (InputStream input = Files.newInputStream(temporary)) {
                properties.load(new java.io.InputStreamReader(
                        input, StandardCharsets.UTF_8));
            }
            return properties;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void download(URI uri, Path target)
            throws IOException, InterruptedException {
        requireHttps(uri);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(3))
                .header("User-Agent", "NicoCache_nl dependency updater")
                .build();
        HttpResponse<Path> response = client.send(request,
                HttpResponse.BodyHandlers.ofFile(target));
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + ": " + uri);
        }
        requireHttps(response.uri());
    }

    private static void requireHttps(URI uri) throws IOException {
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IOException("HTTPS以外の取得元は使用できません: " + uri);
        }
    }

    private static Path resolveTarget(Path dataRoot, String target)
            throws IOException {
        Path relative = Path.of(target);
        if (relative.isAbsolute()) {
            throw new IOException("絶対パスは指定できません: " + target);
        }
        Path normalized = relative.normalize();
        if (normalized.getNameCount() < 2
                || !ALLOWED_ROOTS.contains(normalized.getName(0).toString())) {
            throw new IOException("更新対象外の配置先です: " + target);
        }
        Path resolved = dataRoot.resolve(normalized).normalize();
        if (!resolved.startsWith(dataRoot)) {
            throw new IOException("データルート外の配置先です: " + target);
        }
        Path cursor = dataRoot;
        for (Path part : normalized) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) {
                throw new IOException("シンボリックリンク配下は更新できません: " + target);
            }
        }
        return resolved;
    }

    private static Set<String> componentIds(Properties manifest) {
        Set<String> ids = new TreeSet<>();
        for (String name : manifest.stringPropertyNames()) {
            if (name.startsWith("component.") && name.endsWith(".version")) {
                ids.add(name.substring("component.".length(),
                        name.length() - ".version".length()));
            }
        }
        return ids;
    }

    private static Properties loadState(Path dataRoot) throws IOException {
        Properties state = new Properties();
        Path path = dataRoot.resolve(STATE_FILE);
        if (Files.isRegularFile(path)) {
            try (InputStream input = Files.newInputStream(path)) {
                state.load(new java.io.InputStreamReader(
                        input, StandardCharsets.UTF_8));
            }
        }
        return state;
    }

    private static void saveState(Path dataRoot, Properties state) throws IOException {
        Path target = dataRoot.resolve(STATE_FILE);
        Path temporary = Files.createTempFile(dataRoot, ".dependency-state-", ".tmp");
        try (OutputStream output = Files.newOutputStream(temporary)) {
            state.store(new java.io.OutputStreamWriter(
                    output, StandardCharsets.UTF_8),
                    "NicoCache_nl managed dependency state");
        }
        atomicReplace(temporary, target);
    }

    private static Path dataRoot() {
        String configured = System.getProperty(DATA_ROOT_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        return Path.of("").toAbsolutePath().normalize();
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private static boolean contains(String[] args, String expected) {
        for (String value : args) {
            if (expected.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static void printPlan(UpdatePlan plan) {
        for (ComponentUpdate update : plan.updates) {
            System.out.println("UPDATE " + update.component.id + " "
                    + update.component.version + " -> " + update.target);
        }
        for (ComponentUpdate update : plan.modified) {
            System.out.println("SKIP_MODIFIED " + update.component.id + " -> "
                    + update.target);
        }
        if (plan.updates.isEmpty() && plan.modified.isEmpty()) {
            System.out.println("依存ファイルは最新です");
        }
    }

    public static final class UpdatePlan {
        public final Path dataRoot;
        public final Properties state;
        public final List<ComponentUpdate> updates;
        public final List<ComponentUpdate> modified;

        UpdatePlan(Path dataRoot, Properties state,
                List<ComponentUpdate> updates, List<ComponentUpdate> modified) {
            this.dataRoot = dataRoot;
            this.state = state;
            this.updates = List.copyOf(updates);
            this.modified = List.copyOf(modified);
        }
    }

    public static final class ComponentUpdate {
        public final Component component;
        public final Path target;
        public final String currentHash;
        public final String installedHash;

        ComponentUpdate(Component component, Path target,
                String currentHash, String installedHash) {
            this.component = component;
            this.target = target;
            this.currentHash = currentHash;
            this.installedHash = installedHash;
        }
    }

    public static final class Component {
        public final String id;
        public final String name;
        public final String version;
        public final URI downloadUri;
        public final String sha256;
        public final String target;

        Component(String id, String name, String version, URI downloadUri,
                String sha256, String target) {
            this.id = id;
            this.name = name;
            this.version = version;
            this.downloadUri = downloadUri;
            this.sha256 = sha256;
            this.target = target;
        }

        static Component from(Properties values, String id, URI manifestUri)
                throws IOException {
            String prefix = "component." + id + ".";
            String name = required(values, prefix + "name");
            String version = required(values, prefix + "version");
            URI download = manifestUri.resolve(required(values, prefix + "url"));
            requireHttps(download);
            String sha = required(values, prefix + "sha256")
                    .toLowerCase(Locale.ROOT);
            if (!sha.matches("[0-9a-f]{64}")) {
                throw new IOException("不正なSHA-256です: " + id);
            }
            String target = required(values, prefix + "target");
            return new Component(id, name, version, download, sha, target);
        }

        private static String required(Properties values, String key)
                throws IOException {
            String value = values.getProperty(key);
            if (value == null || value.isBlank()) {
                throw new IOException("マニフェスト項目がありません: " + key);
            }
            return value.trim();
        }
    }

    private static final class AppliedUpdate {
        final Path target;
        final Path backup;

        AppliedUpdate(Path target, Path backup) {
            this.target = target;
            this.backup = backup;
        }
    }
}
