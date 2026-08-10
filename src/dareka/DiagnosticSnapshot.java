package dareka;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.time.Instant;

/** Produces the authenticated in-process snapshot consumed by the watchdog. */
final class DiagnosticSnapshot {
    private DiagnosticSnapshot() {
    }

    static String capture(String state, String problem) throws IOException {
        Runtime runtime = Runtime.getRuntime();
        RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        MemoryUsage heap = memoryBean.getHeapMemoryUsage();
        MemoryUsage nonHeap = memoryBean.getNonHeapMemoryUsage();

        StringBuilder json = new StringBuilder(96 * 1024);
        json.append('{')
                .append("\"capturedAt\":\"")
                .append(escape(Instant.now().toString())).append("\",")
                .append("\"state\":\"").append(escape(state))
                .append("\",\"problem\":\"").append(escape(problem))
                .append("\",\"pid\":")
                .append(ProcessHandle.current().pid())
                .append(",\"uptimeMillis\":").append(runtimeBean.getUptime())
                .append(",\"processors\":").append(runtime.availableProcessors())
                .append(",\"heapUsed\":").append(heap.getUsed())
                .append(",\"heapCommitted\":").append(heap.getCommitted())
                .append(",\"heapMax\":").append(heap.getMax())
                .append(",\"nonHeapUsed\":").append(nonHeap.getUsed())
                .append(",\"threadCount\":").append(threadBean.getThreadCount())
                .append(",\"peakThreadCount\":")
                .append(threadBean.getPeakThreadCount())
                .append(",\"daemonThreadCount\":")
                .append(threadBean.getDaemonThreadCount())
                .append(",\"javaVersion\":\"")
                .append(escape(System.getProperty("java.version", "unknown")))
                .append("\",\"javaVendor\":\"")
                .append(escape(System.getProperty("java.vendor", "unknown")))
                .append("\",\"javaVmName\":\"")
                .append(escape(System.getProperty("java.vm.name", "unknown")))
                .append("\",\"osName\":\"")
                .append(escape(System.getProperty("os.name", "unknown")))
                .append("\",\"osVersion\":\"")
                .append(escape(System.getProperty("os.version", "unknown")))
                .append("\",\"osArch\":\"")
                .append(escape(System.getProperty("os.arch", "unknown")))
                .append("\",\"threadDump\":\"")
                .append(escape(ThreadDumpUtil.capture())).append("\"}");
        return json.toString();
    }

    static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
            case '\\':
            case '"':
                escaped.append('\\').append(character);
                break;
            case '\b':
                escaped.append("\\b");
                break;
            case '\f':
                escaped.append("\\f");
                break;
            case '\n':
                escaped.append("\\n");
                break;
            case '\r':
                escaped.append("\\r");
                break;
            case '\t':
                escaped.append("\\t");
                break;
            default:
                if (character < 0x20) {
                    escaped.append(String.format("\\u%04x", (int) character));
                } else {
                    escaped.append(character);
                }
                break;
            }
        }
        return escaped.toString();
    }
}
