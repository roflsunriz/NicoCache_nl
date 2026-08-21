package dareka.processor.impl;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dareka.common.json.JsonArray;
import dareka.common.json.JsonFalse;
import dareka.common.json.JsonNull;
import dareka.common.json.JsonNumber;
import dareka.common.json.JsonObject;
import dareka.common.json.JsonString;
import dareka.common.json.JsonTrue;
import dareka.common.json.JsonValue;
import dareka.processor.LocalFileResource;

/** local/で実際に見えるオーバーレイをディレクトリ単位で列挙する。 */
final class LocalFileBrowser {
    private static final String PUBLIC_LOCAL_BASE =
            "https://www.nicovideo.jp/local/";

    private LocalFileBrowser() {
    }

    static JsonObject list(String encodedPath) throws IOException {
        List<String> segments = decodePath(encodedPath);
        String relativePath = String.join("/", segments);
        List<RootDirectory> directories = resolveDirectories(segments,
                relativePath);
        Map<String, LocalEntry> merged = new LinkedHashMap<>();
        List<Path> higherPriorityDirectories = new ArrayList<>();

        for (RootDirectory directory : directories) {
            try (DirectoryStream<Path> children = Files.newDirectoryStream(
                    directory.path)) {
                for (Path child : children) {
                    String name = child.getFileName().toString();
                    if (merged.containsKey(name)
                            || isShadowed(higherPriorityDirectories, name)
                            || !Files.exists(child)) {
                        continue;
                    }
                    try {
                        merged.put(name, new LocalEntry(child, name,
                                append(relativePath, name), directory.source));
                    } catch (NoSuchFileException error) {
                        // 列挙中に削除された項目だけを無視して一覧を継続する。
                    }
                }
            }
            higherPriorityDirectories.add(directory.path);
        }

        List<LocalEntry> entries = new ArrayList<>(merged.values());
        entries.sort(Comparator
                .comparing((LocalEntry entry) -> !entry.directory)
                .thenComparing(entry -> entry.name,
                        String.CASE_INSENSITIVE_ORDER)
                .thenComparing(entry -> entry.name));

        JsonArray jsonEntries = new JsonArray();
        for (LocalEntry entry : entries) {
            jsonEntries.add(entry.toJson());
        }
        return new JsonObject()
                .put("path", new JsonString(relativePath))
                .put("parentPath", parentPath(relativePath))
                .put("entries", jsonEntries);
    }

    private static List<String> decodePath(String encodedPath) {
        if (encodedPath == null || encodedPath.isEmpty()
                || "/".equals(encodedPath)) {
            return List.of();
        }
        if (!encodedPath.startsWith("/")) {
            throw new IllegalArgumentException("path must start with slash");
        }
        String value = encodedPath.substring(1);
        if (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.isEmpty()) {
            return List.of();
        }

        List<String> segments = new ArrayList<>();
        for (String encodedSegment : value.split("/", -1)) {
            if (encodedSegment.isEmpty()) {
                throw new IllegalArgumentException("empty path segment");
            }
            String segment;
            try {
                segment = URLDecoder.decode(encodedSegment,
                        StandardCharsets.UTF_8);
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("invalid percent encoding",
                        error);
            }
            if (segment.isEmpty() || ".".equals(segment)
                    || "..".equals(segment) || segment.indexOf('/') >= 0
                    || segment.indexOf('\\') >= 0
                    || segment.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("invalid path segment");
            }
            segments.add(segment);
        }
        return segments;
    }

    private static List<RootDirectory> resolveDirectories(
            List<String> segments, String relativePath) throws IOException {
        List<RootDirectory> result = new ArrayList<>();
        boolean visibleEntryFound = false;
        for (RootDirectory root : List.of(
                new RootDirectory(UserDataPaths.userFile("local").toPath(),
                        "user"),
                new RootDirectory(UserDataPaths.applicationFile("local").toPath(),
                        "application"))) {
            Path candidate = root.path.toAbsolutePath().normalize();
            for (String segment : segments) {
                candidate = candidate.resolve(segment);
            }
            candidate = candidate.normalize();
            if (!candidate.startsWith(root.path.toAbsolutePath().normalize())) {
                throw new IllegalArgumentException("path escapes local root");
            }
            if (!Files.exists(candidate)) {
                continue;
            }
            if (!visibleEntryFound) {
                visibleEntryFound = true;
                if (!Files.isDirectory(candidate)) {
                    throw new NotDirectoryException(relativePath);
                }
            }
            if (Files.isDirectory(candidate)) {
                result.add(new RootDirectory(candidate, root.source));
            }
        }
        if (!visibleEntryFound || result.isEmpty()) {
            throw new NoSuchFileException(relativePath);
        }
        return result;
    }

    private static String append(String parent, String name) {
        return parent.isEmpty() ? name : parent + "/" + name;
    }

    private static boolean isShadowed(List<Path> directories, String name) {
        for (Path directory : directories) {
            if (Files.exists(directory.resolve(name))) {
                return true;
            }
        }
        return false;
    }

    private static JsonValue parentPath(String path) {
        if (path.isEmpty()) {
            return new JsonNull();
        }
        int slash = path.lastIndexOf('/');
        return new JsonString(slash < 0 ? "" : path.substring(0, slash));
    }

    private static String publicUrl(String path, boolean directory) {
        StringBuilder encoded = new StringBuilder(PUBLIC_LOCAL_BASE);
        String delimiter = "";
        for (String segment : path.split("/")) {
            encoded.append(delimiter).append(URLEncoder.encode(segment,
                    StandardCharsets.UTF_8).replace("+", "%20"));
            delimiter = "/";
        }
        if (directory) {
            encoded.append('/');
        }
        return encoded.toString();
    }

    private static final class RootDirectory {
        private final Path path;
        private final String source;

        private RootDirectory(Path path, String source) {
            this.path = path;
            this.source = source;
        }
    }

    private static final class LocalEntry {
        private final Path path;
        private final String name;
        private final String relativePath;
        private final String source;
        private final BasicFileAttributes attributes;
        private final boolean directory;

        private LocalEntry(Path path, String name, String relativePath,
                String source) throws IOException {
            this.path = path;
            this.name = name;
            this.relativePath = relativePath;
            this.source = source;
            this.attributes = Files.readAttributes(path,
                    BasicFileAttributes.class);
            this.directory = attributes.isDirectory();
        }

        private JsonObject toJson() {
            String kind = directory ? "directory"
                    : attributes.isRegularFile() ? "file" : "other";
            JsonValue size = directory ? new JsonNull()
                    : new JsonNumber(attributes.size());
            String mediaType = attributes.isRegularFile()
                    ? LocalFileResource.getMimeType(relativePath) : null;
            return new JsonObject()
                    .put("name", new JsonString(name))
                    .put("path", new JsonString(relativePath))
                    .put("kind", new JsonString(kind))
                    .put("size", size)
                    .put("createdAt", new JsonString(attributes.creationTime()
                            .toInstant().toString()))
                    .put("modifiedAt", new JsonString(attributes.lastModifiedTime()
                            .toInstant().toString()))
                    .put("mediaType", mediaType == null ? new JsonNull()
                            : new JsonString(mediaType))
                    .put("url", new JsonString(publicUrl(relativePath,
                            directory)))
                    .put("source", new JsonString(source))
                    .put("symbolicLink", Files.isSymbolicLink(path)
                            ? new JsonTrue() : new JsonFalse());
        }
    }
}
