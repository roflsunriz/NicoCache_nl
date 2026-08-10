package nicocache.diagnostics;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Captures three time-separated dumps, using authenticated API then jcmd. */
final class ThreadDumpCollector {
    private static final int MAX_OUTPUT_BYTES = 4 * 1024 * 1024;
    private final DiagnosticsPaths paths;
    private final CoreProbe probe;
    private final long[] delaysMillis;

    ThreadDumpCollector(DiagnosticsPaths paths, CoreProbe probe) {
        this(paths, probe, new long[] { 0L, 2000L, 3000L });
    }

    ThreadDumpCollector(DiagnosticsPaths paths, CoreProbe probe,
            long[] delaysMillis) {
        this.paths = paths;
        this.probe = probe;
        this.delaysMillis = delaysMillis.clone();
    }

    void collect(long pid, IncidentReport report, Redactor redactor,
            boolean externalFirst) {
        for (int index = 0; index < delaysMillis.length; index++) {
            if (delaysMillis[index] > 0L) {
                try {
                    Thread.sleep(delaysMillis[index]);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    report.errors.add("thread dump wait interrupted");
                    return;
                }
            }
            String snapshot;
            if (externalFirst) {
                try {
                    snapshot = externalDump(pid);
                } catch (Exception attachError) {
                    try {
                        snapshot = probe.diagnosticSnapshot();
                    } catch (Exception controlError) {
                        report.errors.add("thread dump " + (index + 1)
                                + " failed: " + safe(attachError) + "; "
                                + safe(controlError));
                        continue;
                    }
                }
            } else {
                try {
                    snapshot = probe.diagnosticSnapshot();
                } catch (Exception controlError) {
                    try {
                        snapshot = externalDump(pid);
                    } catch (Exception attachError) {
                        report.errors.add("thread dump " + (index + 1)
                                + " failed: " + safe(controlError) + "; "
                                + safe(attachError));
                        continue;
                    }
                }
            }
            report.snapshots.add("Captured " + Instant.now() + "\n"
                    + redactor.redact(snapshot));
        }
    }

    private String externalDump(long pid) throws Exception {
        if (pid <= 0L) {
            throw new IOException("core PID is unavailable");
        }
        if (!Files.isRegularFile(paths.jcmdExecutable())) {
            throw new IOException("jcmd is unavailable");
        }
        Process process = new ProcessBuilder(paths.jcmdExecutable().toString(),
                Long.toString(pid), "Thread.print", "-l")
                .redirectErrorStream(true).start();
        CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> {
            try {
                return readLimited(process.getInputStream());
            } catch (IOException error) {
                throw new java.io.UncheckedIOException(error);
            }
        });
        if (!process.waitFor(8L, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("jcmd timed out");
        }
        byte[] bytes = output.get(2L, TimeUnit.SECONDS);
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            throw new IOException("jcmd exit " + process.exitValue()
                    + ": " + text);
        }
        return text;
    }

    private static byte[] readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(64 * 1024);
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            int accepted = Math.min(read, MAX_OUTPUT_BYTES - total);
            if (accepted > 0) {
                output.write(buffer, 0, accepted);
                total += accepted;
            }
            if (total >= MAX_OUTPUT_BYTES) {
                break;
            }
        }
        return output.toByteArray();
    }

    private static String safe(Throwable error) {
        String message = error.getMessage();
        return message == null ? error.getClass().getSimpleName() : message;
    }
}
