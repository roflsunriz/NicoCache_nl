package nicocache.cmaftomp4;

import java.nio.file.Path;

/** FFmpeg変換に必要な入力値。 */
public final class ConversionRequest {
    private final Path playlist;
    private final Path output;
    private final String ffmpeg;
    private final boolean overwrite;
    private final String title;

    public ConversionRequest(
            Path playlist, Path output, String ffmpeg, boolean overwrite, String title) {
        this.playlist = playlist.toAbsolutePath().normalize();
        this.output = output.toAbsolutePath().normalize();
        this.ffmpeg = normalizeFfmpeg(ffmpeg);
        this.overwrite = overwrite;
        this.title = title == null || title.trim().isEmpty() ? null : title.trim();
    }

    public Path getPlaylist() {
        return playlist;
    }

    public Path getOutput() {
        return output;
    }

    public String getFfmpeg() {
        return ffmpeg;
    }

    public boolean isOverwrite() {
        return overwrite;
    }

    public String getTitle() {
        return title;
    }

    public static String defaultFfmpeg() {
        String environment = System.getenv("FFMPEG_BINARY");
        return environment == null || environment.trim().isEmpty()
                ? "ffmpeg"
                : environment.trim();
    }

    private static String normalizeFfmpeg(String value) {
        return value == null || value.trim().isEmpty() ? defaultFfmpeg() : value.trim();
    }
}
