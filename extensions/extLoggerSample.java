package extensions;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dareka.NLMain;
import dareka.common.LoggerHandler;
import dareka.extensions.Extension2;
import dareka.extensions.ExtensionManager;
import dareka.extensions.Rewriter;
import dareka.processor.HttpResponseHeader;

/**
 * 拡張ロガーのサンプル
 */
public class extLoggerSample implements Extension2, Rewriter {
	private static final Pattern REWRITER_SUPPORTED_PATTERN = Pattern.compile(
			"^http://(?:www|nine|ext)\\.nicovideo\\.jp/.*$");
	private static LoggerHandler logger, guiLogger;
	
	// Extension2 interface
	public void registerExtensions(ExtensionManager mgr) {
		if (logger == null) {
			logger = NLMain.getExtLogger(this, "sample", null);
			logger.debug(REWRITER_SUPPORTED_PATTERN.pattern());
			guiLogger = NLMain.getExtLogger(this, "sampleGUI", null, true);
			guiLogger.info("拡張ロガーでGUIのみにログを出力してみた");
		}
		mgr.registerRewriter(this);
	}
	
	public String getVersionString() {
		return "extLoggerSample";
	}
	
	// Rewriter interface
	public Pattern getRewriterSupportedURLAsPattern() {
		return REWRITER_SUPPORTED_PATTERN;
	}
	
	public String onMatch(Matcher match, HttpResponseHeader responseHeader,
			String content) throws IOException {
		logger.info("URL = " + match.group());
		logger.debug(responseHeader.toString());
		return content;
	}
}
