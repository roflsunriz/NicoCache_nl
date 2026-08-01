package nicocache.cmaftomp4;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** ローカルHLS/CMAFプレイリストをFFmpegでMP4へ変換する。 */
public final class FfmpegConverter {
    private static final String ALLOWED_EXTENSIONS =
            "m3u8,cmfv,cmfa,m4s,m4a,mp4,ts,webm,flv,key";
    private static final String PROTOCOL_WHITELIST = "file,crypto,data";

    public void convert(
            ConversionRequest request,
            ConversionListener listener,
            CancellationToken cancellation)
            throws ConversionException {
        validateRequest(request);
        ConversionListener safeListener = listener == null ? emptyListener() : listener;
        CancellationToken safeCancellation =
                cancellation == null ? () -> false : cancellation;
        Path output = request.getOutput();
        Path parent = output.getParent();
        if (parent == null) {
            parent = output.toAbsolutePath().normalize().getParent();
        }
        if (parent == null) {
            throw invalidRequest(Messages.get("error.output-parent"));
        }

        try {
            Files.createDirectories(parent);
        } catch (IOException e) {
            throw invalidRequest(Messages.format("error.output-parent-create", parent), e);
        }
        if (Files.exists(output) && !request.isOverwrite()) {
            throw invalidRequest(Messages.format("error.output-exists", output));
        }

        Path temporary = null;
        Process process = null;
        boolean completed = false;
        try {
            temporary = Files.createTempFile(
                    parent,
                    "." + output.getFileName().toString() + ".",
                    ".part.mp4");
            List<String> command = buildCommand(request, temporary);
            safeListener.onStarted(Collections.unmodifiableList(command));
            process = startProcess(command);
            int exitCode = runProcess(process, safeListener, safeCancellation);
            if (safeCancellation.isCancelled()) {
                throw new ConversionException(
                        ConversionException.Kind.CANCELLED,
                        Messages.get("error.cancelled"));
            }
            if (exitCode != 0) {
                throw new ConversionException(
                        ConversionException.Kind.CONVERSION_FAILED,
                        Messages.format("error.ffmpeg-failed", exitCode));
            }
            if (!Files.isRegularFile(temporary) || Files.size(temporary) == 0) {
                throw new ConversionException(
                        ConversionException.Kind.CONVERSION_FAILED,
                        Messages.get("error.empty-output"));
            }
            moveIntoPlace(temporary, output, request.isOverwrite());
            completed = true;
            safeListener.onFinished(output);
        } catch (ConversionException e) {
            throw e;
        } catch (IOException e) {
            if (process == null) {
                throw new ConversionException(
                        ConversionException.Kind.TOOL_NOT_FOUND,
                        Messages.format("error.ffmpeg-start", request.getFfmpeg()),
                        e);
            }
            throw new ConversionException(
                    ConversionException.Kind.CONVERSION_FAILED,
                    Messages.get("error.io-during-conversion"),
                    e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConversionException(
                    ConversionException.Kind.CANCELLED,
                    Messages.get("error.interrupted"),
                    e);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroy();
                try {
                    if (!process.waitFor(2, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
            }
            if (!completed && temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // 変換失敗の主原因を隠さないため、後始末の失敗は主例外へ置き換えない。
                }
            }
        }
    }

    static List<String> buildCommand(ConversionRequest request, Path temporaryOutput) {
        List<String> command = new ArrayList<>();
        command.add(request.getFfmpeg());
        command.add("-hide_banner");
        command.add("-nostdin");
        command.add("-loglevel");
        command.add("error");
        command.add("-allowed_extensions");
        command.add(ALLOWED_EXTENSIONS);
        command.add("-protocol_whitelist");
        command.add(PROTOCOL_WHITELIST);
        command.add("-safe");
        command.add("0");
        command.add("-i");
        command.add(request.getPlaylist().toString());
        command.add("-map");
        command.add("0:v:0?");
        command.add("-map");
        command.add("0:a:0?");
        command.add("-c");
        command.add("copy");
        command.add("-movflags");
        command.add("+faststart");
        if (request.getTitle() != null) {
            command.add("-metadata");
            command.add("title=" + request.getTitle());
        }
        command.add("-f");
        command.add("mp4");
        command.add("-y");
        command.add(temporaryOutput.toString());
        return command;
    }

    private static void validateRequest(ConversionRequest request) throws ConversionException {
        if (request == null) {
            throw invalidRequest(Messages.get("error.invalid-request"));
        }
        if (!Files.isRegularFile(request.getPlaylist())) {
            throw invalidRequest(Messages.format("error.master-not-file", request.getPlaylist()));
        }
        if (!"master.m3u8".equalsIgnoreCase(request.getPlaylist().getFileName().toString())) {
            throw invalidRequest(Messages.get("error.master-file-required"));
        }
        if (request.getPlaylist().equals(request.getOutput())) {
            throw invalidRequest(Messages.get("error.output-same-as-input"));
        }
        String outputName = request.getOutput().getFileName().toString();
        if (!outputName.toLowerCase(java.util.Locale.ROOT).endsWith(".mp4")) {
            throw invalidRequest(Messages.get("error.output-mp4-required"));
        }
        if (Files.exists(request.getOutput())) {
            try {
                if (Files.isSameFile(request.getPlaylist(), request.getOutput())) {
                    throw invalidRequest(Messages.get("error.output-same-as-input"));
                }
            } catch (IOException e) {
                throw invalidRequest(Messages.get("error.output-same-as-input"), e);
            }
        }
    }

    private static Process startProcess(List<String> command)
            throws IOException, ConversionException {
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            return builder.start();
        } catch (IOException e) {
            throw new ConversionException(
                    ConversionException.Kind.TOOL_NOT_FOUND,
                    Messages.format("error.ffmpeg-start", command.get(0)),
                    e);
        }
    }

    private static int runProcess(
            Process process, ConversionListener listener, CancellationToken cancellation)
            throws IOException, InterruptedException {
        AtomicReference<IOException> outputFailure = new AtomicReference<>();
        Thread outputReader = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    listener.onOutput(line);
                }
            } catch (IOException e) {
                outputFailure.set(e);
            }
        }, "cmaf-to-mp4-ffmpeg-output");
        outputReader.setDaemon(true);
        outputReader.start();

        boolean stopRequested = false;
        long stopRequestedAt = 0L;
        while (process.isAlive()) {
            if (cancellation.isCancelled() && !stopRequested) {
                stopRequested = true;
                stopRequestedAt = System.nanoTime();
                process.destroy();
            } else if (stopRequested
                    && System.nanoTime() - stopRequestedAt > TimeUnit.SECONDS.toNanos(2)) {
                process.destroyForcibly();
            }
            process.waitFor(200, TimeUnit.MILLISECONDS);
        }
        outputReader.join(5_000L);
        if (outputFailure.get() != null) {
            throw outputFailure.get();
        }
        return process.exitValue();
    }

    private static void moveIntoPlace(Path temporary, Path output, boolean overwrite)
            throws IOException, ConversionException {
        try {
            if (overwrite) {
                Files.move(
                        temporary,
                        output,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            try {
                if (overwrite) {
                    Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.move(temporary, output);
                }
            } catch (FileAlreadyExistsException alreadyExists) {
                throw invalidRequest(Messages.format("error.output-exists", output), alreadyExists);
            }
        } catch (FileAlreadyExistsException e) {
            throw invalidRequest(Messages.format("error.output-exists", output), e);
        }
    }

    private static ConversionException invalidRequest(String message) {
        return new ConversionException(ConversionException.Kind.INVALID_REQUEST, message);
    }

    private static ConversionException invalidRequest(String message, Throwable cause) {
        return new ConversionException(ConversionException.Kind.INVALID_REQUEST, message, cause);
    }

    private static ConversionListener emptyListener() {
        return new ConversionListener() {
            @Override
            public void onStarted(List<String> command) {
            }

            @Override
            public void onOutput(String line) {
            }

            @Override
            public void onFinished(Path output) {
            }
        };
    }
}
