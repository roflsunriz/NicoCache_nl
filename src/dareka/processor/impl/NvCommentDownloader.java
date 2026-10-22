package dareka.processor.impl;

import java.io.IOException;

import dareka.processor.HttpRequestHeader;
import dareka.processor.URLResource;
import dareka.processor.util.CommentDownloader;

/** 現行nvcomment v1のコメントJSONをwatchページの情報から取得する。 */
final class NvCommentDownloader {
    private NvCommentDownloader() {
    }

    static boolean hasContext(WatchVars watchVars) {
        return CommentDownloader.hasNvCommentContext(watchVars);
    }

    static URLResource getResource(String videoId, WatchVars watchVars,
            HttpRequestHeader requestHeader) throws IOException {
        return CommentDownloader.getNvCommentURLResource(
                videoId, watchVars, requestHeader);
    }
}
