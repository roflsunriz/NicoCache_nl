package dareka.updater;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Repairs command names that differ between official portable archives and normal installations. */
final class UserToolAliasRepair {
    private UserToolAliasRepair() {}

    static void repair() throws IOException {
        Path root = userProgramsRoot().resolve("7zip");
        if (!Files.isDirectory(root)) return;
        try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
            Path sevenZa = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equalsIgnoreCase("7za.exe"))
                    .findFirst().orElse(null);
            if (sevenZa == null) return;
            Path sevenZ = sevenZa.resolveSibling("7z.exe");
            if (!Files.isRegularFile(sevenZ)) {
                Files.copy(sevenZa, sevenZ, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static Path userProgramsRoot() {
        String override = System.getProperty("nicocache.updater.userProgramsRoot", "");
        if (!override.isBlank()) return Path.of(override);
        String local = System.getenv("LOCALAPPDATA");
        if (local == null || local.isBlank()) {
            local = Path.of(System.getProperty("user.home"), "AppData", "Local").toString();
        }
        return Path.of(local, "Programs", "NicoCache_nl Dependencies");
    }
}
