package dareka;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import dareka.common.Logger;
import dareka.internal.TextFileCodec;
import dareka.internal.TextFileCodec.DecodedText;
import dareka.internal.TextFileCodec.TextDecodingException;

/** Converts recognized managed/user text files to canonical UTF-8 once read. */
final class UserTextEncodingMigrator {
    private static final String VERSION = "1";
    private static final long MAX_TEXT_BYTES = 16L * 1024L * 1024L;
    private static final Set<String> LOCAL_EXTENSIONS = Set.of(
            ".css", ".csv", ".htm", ".html", ".js", ".json",
            ".m3u8", ".md", ".pac", ".txt", ".xml");
    private static final Set<String> LIST_EXTENSIONS = Set.of(
            ".csv", ".json", ".lst", ".txt");

    private UserTextEncodingMigrator() {
    }

    static Result migrate(Path configurationFile) {
        Path dataRoot = NicoCachePaths.dataRoot();
        Path backupRoot = dataRoot.resolve(
                "data/text-encoding-backups/v" + VERSION);
        List<Target> targets = new ArrayList<>();
        if (configurationFile != null) {
            targets.add(new Target(configurationFile,
                    "application/" + configurationFile.getFileName(),
                    NicoCachePaths::isConfigurationText));
        }
        targets.add(new Target(dataRoot.resolve("proxy.pac"), "proxy.pac",
                text -> text.contains("FindProxyForURL")));
        collect(targets, dataRoot.resolve("nlFilters"), "nlFilters",
                Set.of(".txt"), UserTextEncodingMigrator::isNlFilter);
        collect(targets, dataRoot.resolve("list"), "list",
                LIST_EXTENSIONS, null);
        collect(targets, dataRoot.resolve("local"), "local",
                LOCAL_EXTENSIONS, null);
        collect(targets, dataRoot.resolve("data/cors"), "data/cors",
                Set.of(".conf"), text -> text.startsWith("// CORS設定ファイル"));

        targets.sort(Comparator.comparing(target -> target.relativePath));
        Result result = new Result();
        Set<Path> visited = new HashSet<>();
        for (Target target : targets) {
            Path absolute = target.path.toAbsolutePath().normalize();
            if (!visited.add(absolute)) {
                continue;
            }
            migrate(target, backupRoot, result);
        }
        writeReport(dataRoot, result);
        if (result.converted > 0) {
            Logger.info("利用者テキストをUTF-8へ変換しました: "
                    + result.converted + "件（バックアップ: "
                    + backupRoot + "）");
        }
        if (!result.issues.isEmpty()) {
            Logger.warning("UTF-8へ自動変換できない利用者テキストが"
                    + result.issues.size() + "件あります。元ファイルは変更していません。"
                    + "ランチャーの「データルート診断」を開いてください: "
                    + dataRoot.resolve("data/text-encoding-migration-v1.properties"));
        }
        return result;
    }

    private static void collect(List<Target> targets, Path root,
            String relativeRoot, Set<String> extensions,
            Predicate<String> validator) {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path path : stream.collect(Collectors.toList())) {
                if (Files.isSymbolicLink(path)
                        || !Files.isRegularFile(path,
                                LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                String extension = extension(path.getFileName().toString());
                if (!extensions.contains(extension)) {
                    continue;
                }
                String relative = relativeRoot + "/"
                        + root.relativize(path).toString().replace('\\', '/');
                targets.add(new Target(path, relative, validator));
            }
        } catch (IOException error) {
            Logger.warning("利用者テキストの一覧取得に失敗しました: " + root);
            Logger.debugWithThread(error);
        }
    }

    private static void migrate(Target target, Path backupRoot,
            Result result) {
        try {
            if (!Files.isRegularFile(target.path, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(target.path)) {
                result.skipped++;
                return;
            }
            long size = Files.size(target.path);
            if (size > MAX_TEXT_BYTES) {
                result.issues.add(new Issue(target.relativePath,
                        "ファイルが大きすぎるため自動変換しません（上限16 MiB）"));
                return;
            }
            byte[] original = Files.readAllBytes(target.path);
            DecodedText decoded = TextFileCodec.decode(original,
                    target.validator);
            if (decoded.isCanonicalUtf8()) {
                result.alreadyUtf8++;
                return;
            }
            byte[] canonical = decoded.toCanonicalUtf8();
            DecodedText verified = TextFileCodec.decode(canonical,
                    target.validator);
            if (!verified.isCanonicalUtf8()
                    || !decoded.getText().equals(verified.getText())) {
                throw new IOException("UTF-8変換後の内容検証に失敗しました");
            }
            Path backup = backupPath(backupRoot, target.relativePath,
                    original);
            Files.createDirectories(backup.getParent());
            if (!Files.exists(backup)) {
                Files.write(backup, original);
            }
            writeAtomically(target.path, canonical);
            result.converted++;
            result.convertedFiles.add(new Converted(target.relativePath,
                    decoded.getEncoding().getDisplayName(),
                    backupRoot.getParent().getParent().relativize(backup)
                            .toString().replace('\\', '/')));
        } catch (TextDecodingException error) {
            result.issues.add(new Issue(target.relativePath,
                    friendlyReason(error)));
        } catch (IOException | RuntimeException error) {
            result.issues.add(new Issue(target.relativePath,
                    "安全に変換できませんでした（元ファイルは未変更）: "
                    + safeMessage(error)));
        }
    }

    private static String friendlyReason(TextDecodingException error) {
        switch (error.getReason()) {
        case AMBIGUOUS:
            return "文字コード候補が複数あります。UTF-8で保存し直してください";
        case INVALID_FORMAT:
            return "文字コードは判定できましたが、ファイル形式を確認できません";
        case UNRECOGNIZED:
        default:
            return "文字コードを判定できません。UTF-8で保存し直してください";
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName() : message;
    }

    private static Path backupPath(Path root, String relative, byte[] bytes)
            throws IOException {
        String hash = sha256(bytes).substring(0, 16);
        Path relativePath = Path.of(relative).normalize();
        if (relativePath.isAbsolute() || relativePath.startsWith("..")) {
            throw new IOException("バックアップ相対パスが不正です");
        }
        Path parent = relativePath.getParent();
        String name = relativePath.getFileName() + "." + hash + ".bak";
        return (parent == null ? root : root.resolve(parent)).resolve(name);
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder output = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                output.append(String.format("%02x", value & 0xff));
            }
            return output.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256を利用できません", error);
        }
    }

    private static void writeAtomically(Path destination, byte[] bytes)
            throws IOException {
        Path temporary = destination.resolveSibling(
                destination.getFileName() + ".utf8.part");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, destination,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException error) {
                Files.move(temporary, destination,
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void writeReport(Path dataRoot, Result result) {
        Path report = dataRoot.resolve(
                "data/text-encoding-migration-v1.properties");
        Properties properties = new Properties();
        properties.setProperty("version", VERSION);
        properties.setProperty("scannedAt", Instant.now().toString());
        properties.setProperty("converted", Integer.toString(result.converted));
        properties.setProperty("alreadyUtf8",
                Integer.toString(result.alreadyUtf8));
        properties.setProperty("skipped", Integer.toString(result.skipped));
        properties.setProperty("issues",
                Integer.toString(result.issues.size()));
        for (int index = 0; index < result.convertedFiles.size(); index++) {
            Converted converted = result.convertedFiles.get(index);
            String prefix = "converted." + (index + 1) + ".";
            properties.setProperty(prefix + "path", converted.path);
            properties.setProperty(prefix + "sourceEncoding",
                    converted.sourceEncoding);
            properties.setProperty(prefix + "backup", converted.backup);
        }
        for (int index = 0; index < result.issues.size(); index++) {
            Issue issue = result.issues.get(index);
            String prefix = "issue." + (index + 1) + ".";
            properties.setProperty(prefix + "path", issue.path);
            properties.setProperty(prefix + "message", issue.message);
        }
        Path temporary = report.resolveSibling(report.getFileName() + ".part");
        try {
            Files.createDirectories(report.getParent());
            try (OutputStreamWriter writer = new OutputStreamWriter(
                    Files.newOutputStream(temporary), StandardCharsets.UTF_8)) {
                properties.store(writer,
                        "NicoCache_nl UTF-8 text migration report");
            }
            try {
                Files.move(temporary, report,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException error) {
                Files.move(temporary, report,
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            Logger.warning("UTF-8移行レポートを保存できません: " + report);
            Logger.debugWithThread(error);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException error) {
                Logger.debugWithThread(error);
            }
        }
    }

    private static boolean isNlFilter(String text) {
        return text.startsWith("# nlフィルタ定義")
                || text.contains("[Replace]") || text.contains("[Script]")
                || text.contains("[Style]") || text.contains("[RequestHeader]");
    }

    private static String extension(String name) {
        int separator = name.lastIndexOf('.');
        return separator < 0 ? "" : name.substring(separator).toLowerCase();
    }

    static final class Result {
        private int converted;
        private int alreadyUtf8;
        private int skipped;
        private final List<Converted> convertedFiles = new ArrayList<>();
        private final List<Issue> issues = new ArrayList<>();

        int getConverted() {
            return converted;
        }

        int getIssueCount() {
            return issues.size();
        }
    }

    private static final class Target {
        private final Path path;
        private final String relativePath;
        private final Predicate<String> validator;

        private Target(Path path, String relativePath,
                Predicate<String> validator) {
            this.path = path;
            this.relativePath = relativePath;
            this.validator = validator;
        }
    }

    private static final class Converted {
        private final String path;
        private final String sourceEncoding;
        private final String backup;

        private Converted(String path, String sourceEncoding, String backup) {
            this.path = path;
            this.sourceEncoding = sourceEncoding;
            this.backup = backup;
        }
    }

    private static final class Issue {
        private final String path;
        private final String message;

        private Issue(String path, String message) {
            this.path = path;
            this.message = message;
        }
    }
}
