package nlfilterlab;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class RepositoryFilters {
    private RepositoryFilters() {
    }

    static List<Path> tracked(Path repositoryRoot) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("git", "-C", repositoryRoot.toString(), "ls-files", "-z", "--", "*.txt")
                .redirectErrorStream(true)
                .start();
        byte[] output = process.getInputStream().readAllBytes();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("git ls-files に失敗しました: " + new String(output, StandardCharsets.UTF_8));
        }
        List<Path> paths = new ArrayList<>();
        for (String relative : new String(output, StandardCharsets.UTF_8).split("\\x00")) {
            if (!relative.isBlank()) {
                Path path = repositoryRoot.resolve(relative).normalize();
                if (path.startsWith(repositoryRoot) && Files.isRegularFile(path)) {
                    paths.add(path);
                }
            }
        }
        paths.sort(Comparator.comparing(path -> path.getFileName().toString()));
        return paths;
    }

    static List<Path> resolveExplicit(Path repositoryRoot, List<String> arguments) {
        List<Path> paths = new ArrayList<>();
        for (String argument : arguments) {
            Path path = Path.of(argument);
            if (!path.isAbsolute()) {
                path = repositoryRoot.resolve(path);
            }
            paths.add(path.normalize());
        }
        paths.sort(Comparator.comparing(path -> path.getFileName().toString()));
        return paths;
    }
}
