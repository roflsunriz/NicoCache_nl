package dareka.common;

import java.io.PrintWriter;
import java.io.StringWriter;

public class DefaultLoggerHandler implements LoggerHandler {
    private static /*final*/ boolean DEBUG = Boolean.getBoolean("dareka.debug");
    private static boolean dedupeEnabled = false;

    public static void setDebug(boolean debug) { // [nl]
        DEBUG = debug;
    }

    public static void setDedupe(boolean enabled) {
        dedupeEnabled = enabled;
    }

    /* (非 Javadoc)
     * @see dareka.common.LoggerHandler2#debug(java.lang.String)
     */
    public void debug(String message) {
        if (DEBUG) {
            dedupeMessage(null);
            System.out.println("DEBUG: " + message);
        }
    }

    /* (非 Javadoc)
     * @see dareka.common.LoggerHandler2#debug(java.lang.Throwable)
     */
    public void debug(Throwable t) {
        if (DEBUG) {
            debug(getStackTraceString(t));
        }
    }

    /* (非 Javadoc)
     * @see dareka.common.LoggerHandler2#debugWithThread(java.lang.String)
     */
    public void debugWithThread(String message) {
        if (DEBUG) {
            debug(Thread.currentThread().getName() + ": " + message);
        }
    }

    /* (非 Javadoc)
     * @see dareka.common.LoggerHandler2#debugWithThread(java.lang.Throwable)
     */
    public void debugWithThread(Throwable t) {
        if (DEBUG) {
            debugWithThread(getStackTraceString(t));
        }
    }

    /* (非 Javadoc)
     * @see dareka.common.LoggerHandler2#info(java.lang.String)
     */
    public void info(String message) {
        if (dedupeMessage(message)) {
            System.out.println(message);
        }
    }

    /* (非 Javadoc)
     * @see dareka.common.LoggerHandler2#info(java.lang.String, java.lang.Object)
     */
    public void info(String format, Object... arg) {
        info(String.format(format, arg));
    }

    /* (非 Javadoc)
     * @see dareka.common.LoggerHandler2#warning(java.lang.String)
     */
    public void warning(String message) {
        dedupeMessage(null);
        System.out.println(message);
    }

    /* (非 Javadoc)
     * @see dareka.common.LoggerHandler2#error(java.lang.Throwable)
     */
    public void error(Throwable t) {
        dedupeMessage(null);
        System.out.println(getStackTraceString(t));
    }

    protected String getStackTraceString(Throwable t) {
        if (t == null) {
            return "null";
        }

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        return sw.toString();
    }

    private static String lastMessage = null;
    private static boolean needNewline = false;
    protected static synchronized boolean dedupeMessage(String message) {
        if (!dedupeEnabled) {
            return true;
        }

        if (lastMessage == null || message == null) {
            lastMessage = message;
            if (needNewline) {
                System.out.println();
                needNewline = false;
            }
            return true;
        }
        boolean same = lastMessage.equals(message);
        lastMessage = message;
        if (same) {
            System.out.print("+");
            System.out.flush();
            needNewline = true;
            return false;
        } else {
            if (needNewline) {
                System.out.println();
                needNewline = false;
            }
            return true;
        }
    }
}
