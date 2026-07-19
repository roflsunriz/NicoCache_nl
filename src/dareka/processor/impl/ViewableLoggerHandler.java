package dareka.processor.impl;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.Time;
import java.text.SimpleDateFormat;
import java.util.ArrayList;

import dareka.common.LoggerHandler;

public class ViewableLoggerHandler implements LoggerHandler {
	private boolean enableDebug = false;
	private LoggerHandler old = null;
	private static int lineCount = 0;
	public ViewableLoggerHandler(LoggerHandler oldHandler) {
		enabled = true;
		this.old = oldHandler;
	}

	private static boolean enabled = false;
	public static boolean isLogging() {
		return enabled;
	}
	
	static ArrayList<String> log = new ArrayList<String>();
	public static String getLogWithTableTag() {
		if (log.size() > 300) {
			ArrayList<String> log2 =new ArrayList<String>();
			log2.addAll(log.subList(log.size() - 250, log.size()));
			log = log2;
		}
		StringBuffer buf = new StringBuffer("<html><head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=utf-8\"></head><body><style type=\"text/css\">td {font-family: ＭＳ ゴシック; white-space: pre; }</style><table>");
		for (String line : log) {
			if (!line.startsWith("DEBUG\0")) {
				String []a = line.split("\0");
				if (a[1].equals("WARN"))
					buf.append("<tr bgcolor=\"#ffeaea\">");
				else
					buf.append("<tr>");
				for (int i=0; i<4; i++)
					buf.append("<td nowrap>").append(a[i]).append("</td>");
				buf.append("</tr>");
			}
		}
		buf.append("</table></body></html>");
		return new String(buf);
	}
	
	private static String timeString() {
		SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss'\n'");
		Time date = new Time(System.currentTimeMillis());
		return df.format(date);
	}
	
	private static synchronized void addLog(String category, String message) {
    	log.add("" + ++lineCount + '\0' + category + '\0' + timeString() + '\0' + message);
	}

    /* (非 Javadoc)
     * @see dareka.common.LoggerHandler2#debug(java.lang.String)
     */
    public void debug(String message) {
    	old.debug(message);
    	if (enableDebug)
    		addLog("DEBUG", message);
    }

    /* (非 Javadoc)
     * @see dareka.common.LoggerHandler2#debug(java.lang.Throwable)
     */
    public void debug(Throwable t) {
    	old.debug(t);
    	if (enableDebug)
    		addLog("DEBUG", getStackTraceString(t));
    }

    /* (非 Javadoc)
     * @see dareka.common.LoggerHandler2#debugWithThread(java.lang.String)
     */
    public void debugWithThread(String message) {
        debug(Thread.currentThread().getName() + ": " + message);
    }

    /* (非 Javadoc)
     * @see dareka.common.LoggerHandler2#debugWithThread(java.lang.Throwable)
     */
    public void debugWithThread(Throwable t) {
    	old.debugWithThread(t);
    	if (enableDebug)
    		addLog("DEBUG", Thread.currentThread().getName() + ": " + getStackTraceString(t));
    }

    /* (非 Javadoc)
     * @see dareka.common.LoggerHandler2#info(java.lang.String)
     */
    public void info(String message) {
    	old.info(message);
    	addLog("INFO ", message);
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
    	old.warning(message);
    	addLog("WARN ", message);
    }

    /* (非 Javadoc)
     * @see dareka.common.LoggerHandler2#error(java.lang.Throwable)
     */
    public void error(Throwable t) {
    	old.error(t);
    	addLog("ERROR", getStackTraceString(t));
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
    
    public static void main(String[] args) {
    	System.out.println(timeString());
    }
}
