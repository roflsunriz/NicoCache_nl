package nicocache.cmaftomp4;

import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.swing.SwingUtilities;

/** GUI/CLI共通のエントリーポイント。 */
public final class Main {
    public static final String VERSION = "0.1.0";
    private static final int EXIT_USAGE = 2;
    private static final int EXIT_TOOL = 3;
    private static final int EXIT_CONVERSION = 4;

    private Main() {
    }

    public static void main(String[] args) {
        if (args.length == 0 && !GraphicsEnvironment.isHeadless()) {
            SwingUtilities.invokeLater(() -> new SwingApp().show());
            return;
        }
        int exitCode = runCli(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int runCli(String[] args) {
        final CliOptions options;
        try {
            options = CliOptions.parse(args);
        } catch (CliException e) {
            System.err.println(e.getMessage());
            System.err.println();
            printUsage();
            return EXIT_USAGE;
        }
        if (options.isHelp()) {
            printUsage();
            return 0;
        }
        if (options.isVersion()) {
            System.out.println(Messages.format("version", VERSION));
            return 0;
        }
        if (options.getInput() == null) {
            System.err.println(Messages.get("error.missing-input"));
            System.err.println();
            printUsage();
            return EXIT_USAGE;
        }

        final Path playlist;
        try {
            playlist = CacheLocator.locatePlaylist(options.getInput());
        } catch (Exception e) {
            System.err.println(e.getMessage());
            return EXIT_USAGE;
        }
        Path output = options.getOutput() == null
                ? CacheLocator.defaultOutput(playlist)
                : options.getOutput().toAbsolutePath().normalize();
        String ffmpeg = options.getFfmpeg() == null
                ? ConversionRequest.defaultFfmpeg()
                : options.getFfmpeg();
        ConversionRequest request = new ConversionRequest(
                playlist, output, ffmpeg, options.isForce(), options.getTitle());

        System.out.println(Messages.format("conversion.input", playlist));
        System.out.println(Messages.format("conversion.output", output));
        ConversionListener listener = new ConversionListener() {
            @Override
            public void onStarted(List<String> command) {
                if (options.isVerbose()) {
                    System.err.println(Messages.get("conversion.command"));
                    System.err.println(String.join(" ", command));
                }
            }

            @Override
            public void onOutput(String line) {
                if (line != null && !line.trim().isEmpty()) {
                    System.err.println("[ffmpeg] " + line);
                }
            }

            @Override
            public void onFinished(Path completedOutput) {
                // 完了メッセージは変換処理の終了後にサイズを取得して出力する。
            }
        };
        try {
            new FfmpegConverter().convert(request, listener, () -> false);
            long bytes = Files.size(output);
            System.out.println(Messages.format("conversion.success", output, bytes));
            if (options.isOpenOutput()) {
                try {
                    OutputOpener.openOutputDirectory(output);
                } catch (Exception e) {
                    System.err.println(Messages.format("error.open-output", e.getMessage()));
                }
            }
            return 0;
        } catch (ConversionException e) {
            System.err.println(e.getMessage());
            if (options.isVerbose() && e.getCause() != null) {
                System.err.println(e.getCause().toString());
            }
            return e.getKind() == ConversionException.Kind.TOOL_NOT_FOUND
                    ? EXIT_TOOL
                    : EXIT_CONVERSION;
        } catch (Exception e) {
            System.err.println(Messages.get("error.unexpected"));
            if (options.isVerbose()) {
                e.printStackTrace(System.err);
            }
            return EXIT_CONVERSION;
        }
    }

    static void printUsage() {
        System.out.println(Messages.get("usage.header"));
        System.out.println(Messages.get("usage.command"));
        System.out.println();
        System.out.println(Messages.get("usage.options"));
        System.out.println(Messages.get("usage.input"));
        System.out.println(Messages.get("usage.output"));
        System.out.println(Messages.get("usage.ffmpeg"));
        System.out.println(Messages.get("usage.force"));
        System.out.println(Messages.get("usage.headless"));
        System.out.println(Messages.get("usage.title"));
        System.out.println(Messages.get("usage.lang"));
        System.out.println(Messages.get("usage.verbose"));
        System.out.println(Messages.get("usage.open-output"));
        System.out.println(Messages.get("usage.help"));
        System.out.println(Messages.get("usage.version"));
    }
}
