package dareka.common;

public class Logger {
    private static LoggerHandler handler;

    static {
        handler = new DefaultLoggerHandler();
    }

    private Logger() {
        // avoid instantiation
    }

    //(夏.03）
    public static LoggerHandler getHandler() {
        return handler;
    }

    public static void setHandler(LoggerHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("handler must not be null");
        }

        Logger.handler = handler;
    }

    /**
     * @param message
     * @see dareka.common.LoggerHandler#debug(java.lang.String)
     */
    public static void debug(String message) {
        handler.debug(message);
    }

    /**
     * @param t
     * @see dareka.common.LoggerHandler#debug(java.lang.Throwable)
     */
    public static void debug(Throwable t) {
        handler.debug(t);
    }

    /**
     * @param message
     * @see dareka.common.LoggerHandler#debugWithThread(java.lang.String)
     */
    public static void debugWithThread(String message) {
        handler.debugWithThread(message);
    }

    /**
     * @param t
     * @see dareka.common.LoggerHandler#debugWithThread(java.lang.Throwable)
     */
    public static void debugWithThread(Throwable t) {
        handler.debugWithThread(t);
    }

    /**
     * @param t
     * @see dareka.common.LoggerHandler#error(java.lang.Throwable)
     */
    public static void error(Throwable t) {
        handler.error(t);
    }

    /**
     * @param format
     * @param arg
     * @see dareka.common.LoggerHandler#info(java.lang.String, java.lang.Object[])
     */
    public static void info(String format, Object... arg) {
        handler.info(format, arg);
    }

    /**
     * @param message
     * @see dareka.common.LoggerHandler#info(java.lang.String)
     */
    public static void info(String message) {
        handler.info(message);
    }

    /**
     * @param message
     * @see dareka.common.LoggerHandler#warning(java.lang.String)
     */
    public static void warning(String message) {
        handler.warning(message);
    }

}
