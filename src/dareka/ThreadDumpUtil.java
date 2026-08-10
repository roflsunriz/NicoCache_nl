package dareka;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Shared full-depth thread dump rendering for debug and diagnostic APIs. */
final class ThreadDumpUtil {
    private ThreadDumpUtil() {
    }

    static void write(Path destination) throws IOException {
        Path parent = destination.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (BufferedWriter writer = Files.newBufferedWriter(destination,
                StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            writeAll(writer);
        }
    }

    static String capture() throws IOException {
        StringWriter buffer = new StringWriter(64 * 1024);
        try (BufferedWriter writer = new BufferedWriter(buffer)) {
            writeAll(writer);
        }
        return buffer.toString();
    }

    private static void writeAll(BufferedWriter writer) throws IOException {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        ThreadInfo[] infos = threads.dumpAllThreads(true, true);
        for (ThreadInfo info : infos) {
            if (info != null) {
                writeThread(writer, info);
            }
        }
        long[] deadlocked = threads.findDeadlockedThreads();
        writer.append("Deadlocked thread count: ")
                .append(Integer.toString(deadlocked == null
                        ? 0 : deadlocked.length))
                .append('\n');
    }

    // ThreadInfo.toString() limits stack depth, so render every frame here.
    private static void writeThread(BufferedWriter writer, ThreadInfo info)
            throws IOException {
        Thread.State state = info.getThreadState();
        StackTraceElement[] stacktrace = info.getStackTrace();
        LockInfo waitingOn = info.getLockInfo();
        String lockOwnerName = info.getLockOwnerName();
        long lockOwnerId = info.getLockOwnerId();
        MonitorInfo[] monitors = info.getLockedMonitors();
        LockInfo[] synchronizers = info.getLockedSynchronizers();

        writer.append('"').append(info.getThreadName()).append("\" ")
                .append("Id=").append(Long.toString(info.getThreadId()))
                .append(' ').append(state.toString());
        if (waitingOn != null) {
            writer.append(" on ").append(waitingOn.toString());
        }
        if (lockOwnerName != null) {
            writer.append(" owned by \"").append(lockOwnerName)
                    .append("\" Id=").append(Long.toString(lockOwnerId));
        }
        if (info.isSuspended()) {
            writer.append(" (suspended)");
        }
        if (info.isInNative()) {
            writer.append(" (in native)");
        }
        writer.append('\n');

        for (int index = 0; index < stacktrace.length; index++) {
            writer.append("\tat ").append(stacktrace[index].toString())
                    .append('\n');
            if (index == 0 && waitingOn != null
                    && (state == Thread.State.BLOCKED
                    || state == Thread.State.WAITING
                    || state == Thread.State.TIMED_WAITING)) {
                writer.append("\t- waiting on ")
                        .append(waitingOn.toString()).append('\n');
            }
            for (MonitorInfo monitor : monitors) {
                if (monitor.getLockedStackDepth() == index) {
                    writer.append("\t- locked ")
                            .append(monitor.toString()).append('\n');
                }
            }
        }

        if (synchronizers.length > 0) {
            writer.append('\n').append("\tNumber of locked synchronizers = ")
                    .append(Integer.toString(synchronizers.length))
                    .append('\n');
            for (LockInfo synchronizer : synchronizers) {
                writer.append("\t- ").append(synchronizer.toString())
                        .append('\n');
            }
        }
        writer.append('\n');
    }
}
