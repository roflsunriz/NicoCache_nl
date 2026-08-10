package nicocache.diagnostics;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Per-user-data-root lock retained for the entire watchdog lifetime. */
final class SingleInstanceLock implements AutoCloseable {
    private final Path path;
    private final FileChannel channel;
    private final FileLock lock;

    private SingleInstanceLock(Path path, FileChannel channel, FileLock lock) {
        this.path = path;
        this.channel = channel;
        this.lock = lock;
    }

    static SingleInstanceLock tryAcquire(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                return null;
            }
            return new SingleInstanceLock(path, channel, lock);
        } catch (OverlappingFileLockException error) {
            channel.close();
            return null;
        }
    }

    @Override
    public void close() throws IOException {
        try {
            if (lock.isValid()) {
                lock.release();
            }
        } finally {
            channel.close();
            Files.deleteIfExists(path);
        }
    }
}
