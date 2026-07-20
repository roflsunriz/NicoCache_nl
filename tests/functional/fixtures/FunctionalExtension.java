package extensions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.net.Socket;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dareka.Main;
import dareka.extensions.CompleteCache;
import dareka.extensions.Extension2;
import dareka.extensions.ExtensionManager;
import dareka.extensions.RequestFilter;
import dareka.extensions.Rewriter;
import dareka.extensions.SystemEventListener;
import dareka.processor.HttpRequestHeader;
import dareka.processor.HttpResponseHeader;
import dareka.processor.Processor;
import dareka.processor.Resource;
import dareka.processor.StringResource;
import dareka.processor.impl.Cache;
import dareka.processor.impl.CommentSavingProcessor;
import dareka.processor.impl.NicoCachingTitleRetriever;

public class FunctionalExtension implements Extension2, SystemEventListener {
    private static final Pattern URL_PATTERN = Pattern.compile(
            "^https?://www\\.nicovideo\\.jp/functional/extension$");
    private static final Pattern STOP_URL_PATTERN = Pattern.compile(
            "^https?://www\\.nicovideo\\.jp/functional/stop$");
    private static final Pattern REWRITE_PATTERN = Pattern.compile(
            "^http://example\\.invalid/rewrite$");

    @Override
    public void registerExtensions(ExtensionManager manager) {
        writeMarker("extension-registered.txt", "registered");
        NicoCachingTitleRetriever.putTitleCache("sm900010", "Functional CMAF");
        manager.registerProcessor(new FunctionalProcessor());
        manager.registerProcessor(new FunctionalCommentSavingProcessor());
        manager.registerProcessor(new StopProcessor(), true);
        manager.registerRewriter(new FunctionalRewriter());
        manager.registerRequestFilter(new FunctionalRequestFilter());
        manager.registerCompleteCache(new FunctionalCompleteCache());
        manager.registerEventListener(this);
    }

    @Override
    public String getVersionString() {
        return "FunctionalExtension/1";
    }

    @Override
    public int onSystemEvent(int id, EventSource source) {
        writeMarker("extension-event-" + id + ".txt", Integer.toString(id));
        if (id == SYSTEM_EXIT) {
            writeMarker("extension-system-exit.txt", "system-exit");
        }
        return RESULT_OK;
    }

    private static void writeMarker(String name, String value) {
        try {
            Path path = Paths.get(name);
            Files.write(path, value.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("failed to write marker: " + name, e);
        }
    }

    private static final class FunctionalProcessor implements Processor {
        @Override
        public String[] getSupportedMethods() {
            return new String[] { "GET" };
        }

        @Override
        public Pattern getSupportedURLAsPattern() {
            return URL_PATTERN;
        }

        @Override
        public String getSupportedURLAsString() {
            return null;
        }

        @Override
        public Resource onRequest(HttpRequestHeader requestHeader, Socket browser) {
            return new StringResource("extension-ok");
        }
    }

    private static final class StopProcessor implements Processor {
        @Override
        public String[] getSupportedMethods() {
            return new String[] { "GET" };
        }

        @Override
        public Pattern getSupportedURLAsPattern() {
            return STOP_URL_PATTERN;
        }

        @Override
        public String getSupportedURLAsString() {
            return null;
        }

        @Override
        public Resource onRequest(HttpRequestHeader requestHeader, Socket browser) {
            Thread stopper = new Thread(() -> {
                try {
                    Thread.sleep(100L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                Main.stop();
            }, "functional-test-stop");
            stopper.setDaemon(true);
            stopper.start();
            return new StringResource("stopping");
        }
    }

    private static final class FunctionalRewriter implements Rewriter {
        @Override
        public Pattern getRewriterSupportedURLAsPattern() {
            return REWRITE_PATTERN;
        }

        @Override
        public String onMatch(Matcher match, HttpResponseHeader responseHeader,
                String content) {
            return content.replace("rewrite-original", "rewrite-extension");
        }
    }

    private static final class FunctionalRequestFilter implements RequestFilter {
        @Override
        public int onRequest(HttpRequestHeader requestHeader) {
            requestHeader.setMessageHeader("X-Functional-Filter", "registered");
            return OK;
        }
    }

    private static final class FunctionalCompleteCache implements CompleteCache {
        @Override
        public int getPriority() {
            return 0;
        }

        @Override
        public void update() {
        }

        @Override
        public boolean onComplete(Cache cache) {
            writeMarker("extension-complete-cache.txt", cache.getId());
            return false;
        }
    }

    private static final class FunctionalCommentSavingProcessor
            extends CommentSavingProcessor {
        private static final Pattern COMMENT_PATTERN = Pattern.compile(
                "^http://public\\.nvcomment\\.nicovideo\\.jp/v1/threads$");

        FunctionalCommentSavingProcessor() {
            super(Runnable::run);
        }

        @Override
        public Pattern getSupportedURLAsPattern() {
            return COMMENT_PATTERN;
        }
    }
}
