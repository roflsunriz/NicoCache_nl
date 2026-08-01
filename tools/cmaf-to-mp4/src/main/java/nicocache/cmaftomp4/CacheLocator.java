package nicocache.cmaftomp4;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** 保存済みキャッシュから変換対象のmaster.m3u8を特定する。 */
public final class CacheLocator {
    private CacheLocator() {
    }

    public static Path locatePlaylist(Path input) throws IOException {
        if (input == null) {
            throw new IOException(Messages.get("error.missing-input"));
        }
        Path normalized = input.toAbsolutePath().normalize();
        if (!Files.exists(normalized)) {
            throw new IOException(Messages.format("error.input-not-found", normalized));
        }
        if (Files.isRegularFile(normalized)) {
            if (isMasterPlaylist(normalized)) {
                return normalized;
            }
            throw new IOException(Messages.get("error.master-file-required"));
        }
        if (!Files.isDirectory(normalized)) {
            throw new IOException(Messages.format("error.input-not-directory", normalized));
        }

        Path direct = normalized.resolve("master.m3u8");
        if (Files.isRegularFile(direct)) {
            return direct;
        }

        List<Path> candidates = findMasterPlaylists(normalized);
        if (candidates.isEmpty()) {
            throw new IOException(Messages.format("error.master-not-found", normalized));
        }
        if (candidates.size() > 1) {
            throw new IOException(formatMultipleCandidates(candidates));
        }
        return candidates.get(0);
    }

    public static List<Path> findMasterPlaylists(Path root) throws IOException {
        Path normalized = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            throw new IOException(Messages.format("error.input-not-directory", normalized));
        }
        try (Stream<Path> paths = Files.walk(normalized)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(CacheLocator::isMasterPlaylist)
                    .sorted(Comparator.comparing(Path::toString))
                    .collect(Collectors.toList());
        }
    }

    public static Path defaultOutput(Path playlist) {
        Path cacheDirectory = playlist.toAbsolutePath().normalize().getParent();
        if (cacheDirectory == null) {
            cacheDirectory = Paths.get(".").toAbsolutePath().normalize();
        }
        String directoryName = cacheDirectory.getFileName() == null
                ? "converted"
                : cacheDirectory.getFileName().toString();
        if (directoryName.toLowerCase().endsWith(".hls")) {
            directoryName = directoryName.substring(0, directoryName.length() - 4);
        }
        if (directoryName.isEmpty()) {
            directoryName = "converted";
        }
        Path outputDirectory = cacheDirectory.getParent() == null
                ? cacheDirectory
                : cacheDirectory.getParent();
        return outputDirectory.resolve(directoryName + ".mp4");
    }

    private static boolean isMasterPlaylist(Path path) {
        return "master.m3u8".equalsIgnoreCase(path.getFileName().toString());
    }

    private static String formatMultipleCandidates(List<Path> candidates) {
        List<String> shown = new ArrayList<>();
        int limit = Math.min(candidates.size(), 10);
        for (int index = 0; index < limit; index++) {
            shown.add("  " + candidates.get(index));
        }
        if (candidates.size() > limit) {
            shown.add("  ...");
        }
        return Messages.format(
                "error.multiple-master", candidates.size(), String.join(System.lineSeparator(), shown));
    }
}
