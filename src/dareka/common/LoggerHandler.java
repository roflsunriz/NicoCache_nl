package dareka.common;

public interface LoggerHandler {

    void debug(String message);

    void debug(Throwable t);

    void debugWithThread(String message);

    void debugWithThread(Throwable t);

    void info(String message);

    void info(String format, Object... arg);

    void warning(String message);

    void error(Throwable t);

}
