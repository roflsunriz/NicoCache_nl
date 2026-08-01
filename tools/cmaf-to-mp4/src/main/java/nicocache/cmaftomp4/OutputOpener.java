package nicocache.cmaftomp4;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** OS標準のファイルマネージャーで出力先フォルダを開く。 */
public final class OutputOpener {
    private OutputOpener() {
    }

    public static void openOutputDirectory(Path output) throws IOException {
        if (output == null) {
            throw new IOException(Messages.get("error.output-required"));
        }
        Path normalized = output.toAbsolutePath().normalize();
        Path directory = Files.isDirectory(normalized) ? normalized : normalized.getParent();
        if (directory == null || !Files.isDirectory(directory)) {
            throw new IOException(Messages.format("error.output-directory-not-found", directory));
        }
        if (!Desktop.isDesktopSupported()
                || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            throw new IOException(Messages.get("error.desktop-open-unsupported"));
        }
        Desktop.getDesktop().open(directory.toFile());
    }
}
