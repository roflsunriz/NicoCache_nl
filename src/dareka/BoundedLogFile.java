package dareka;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Appends log entries while retaining at most the newest configured bytes.
 */
final class BoundedLogFile implements Closeable {
    static final long DEFAULT_MAX_BYTES = 1024L * 1024L;

    private final Path path;
    private final Charset charset;
    private final long maxBytes;
    private OutputStream output;
    private long size;

    BoundedLogFile(Path path) throws IOException {
        this(path, Charset.defaultCharset(), DEFAULT_MAX_BYTES);
    }

    BoundedLogFile(Path path, Charset charset, long maxBytes)
            throws IOException {
        if (maxBytes <= 0 || maxBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "maxBytes must be between 1 and Integer.MAX_VALUE");
        }
        this.path = path.toAbsolutePath().normalize();
        this.charset = charset;
        this.maxBytes = maxBytes;
        Path parent = this.path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (Files.exists(this.path)) {
            size = Files.size(this.path);
            if (size > maxBytes) {
                trimFor(0);
            } else {
                openOutput();
            }
        } else {
            openOutput();
        }
    }

    synchronized void println(String message) throws IOException {
        byte[] entry = boundedBytes(
                String.valueOf(message) + System.lineSeparator());
        if (size + entry.length > maxBytes) {
            trimFor(entry.length);
        }
        output.write(entry);
        output.flush();
        size += entry.length;
    }

    synchronized long size() {
        return size;
    }

    @Override
    public synchronized void close() throws IOException {
        closeOutput();
    }

    private byte[] boundedBytes(String value) {
        byte[] encoded = value.getBytes(charset);
        if (encoded.length <= maxBytes) {
            return encoded;
        }

        int low = 0;
        int high = value.length();
        int accepted = 0;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            int end = safeCharacterBoundary(value, middle);
            byte[] candidate = value.substring(0, end).getBytes(charset);
            if (candidate.length <= maxBytes) {
                accepted = end;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return value.substring(0, accepted).getBytes(charset);
    }

    private static int safeCharacterBoundary(String value, int end) {
        if (end > 0 && end < value.length()
                && Character.isHighSurrogate(value.charAt(end - 1))
                && Character.isLowSurrogate(value.charAt(end))) {
            return end - 1;
        }
        return end;
    }

    private void trimFor(int requiredBytes) throws IOException {
        closeOutput();
        int keepLimit = (int) Math.max(0, maxBytes - requiredBytes);
        byte[] retained = readNewestCompleteLines(keepLimit);
        try (OutputStream replacement = Files.newOutputStream(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            replacement.write(retained);
        }
        size = retained.length;
        openOutput();
    }

    private byte[] readNewestCompleteLines(int keepLimit) throws IOException {
        if (keepLimit == 0 || !Files.exists(path)) {
            return new byte[0];
        }
        long fileSize = Files.size(path);
        int readLength = (int) Math.min(fileSize, keepLimit);
        byte[] tail = new byte[readLength];
        try (RandomAccessFile input = new RandomAccessFile(
                path.toFile(), "r")) {
            input.seek(fileSize - readLength);
            input.readFully(tail);
        }
        if (fileSize <= keepLimit) {
            return tail;
        }

        int firstCompleteLine = firstLineStart(tail);
        if (firstCompleteLine >= tail.length) {
            return new byte[0];
        }
        byte[] completeLines = new byte[tail.length - firstCompleteLine];
        System.arraycopy(tail, firstCompleteLine,
                completeLines, 0, completeLines.length);
        return completeLines;
    }

    private static int firstLineStart(byte[] tail) {
        for (int index = 0; index < tail.length; index++) {
            if (tail[index] == '\n') {
                return index + 1;
            }
        }
        return tail.length;
    }

    private void openOutput() throws IOException {
        output = Files.newOutputStream(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
                StandardOpenOption.WRITE);
    }

    private void closeOutput() throws IOException {
        if (output != null) {
            output.close();
            output = null;
        }
    }
}
