package dareka.processor.impl;

import java.io.RandomAccessFile;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.FutureTask;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dareka.common.CloseUtil;
import dareka.common.DiskFreeSpace;
import dareka.common.Logger;
import dareka.extensions.CompleteCache;
import dareka.extensions.SystemEventListener;
import dareka.processor.HttpResponseHeader;
import dareka.processor.TransferListener;

public class NicoCachingListener implements TransferListener {
    private static final int BUF_SIZE = 32 * 1024;
    private static final Pattern CONTENT_RANGE_VALUE_PATTERN =
            Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");

    private Cache cache;
    private FutureTask<String> retrieveTitleTask;
    private FileChannel cacheChannel = null;
    private boolean errorOccured;
    private boolean keepCacheOnError = false;

    // [nl]
    InputStream cacheInput = null;
    long currentPosition = 0;
    long fileRangeHead = 0;
    long fileLength = 0;
    boolean partial = false;
    NLEventSource eventSource;
    long requestedPosition = -1; // -1 means no Range header
    MappedRanges mr;
    long nextMapUpdating = 0;
    long nextMapFileUpdating = 0;

    public NicoCachingListener(Cache cache,
            FutureTask<String> retrieveTitleTask, InputStream cacheInput,
            NLEventSource eventSource, long requestedPosition) {
        this.cache = cache;
        this.retrieveTitleTask = retrieveTitleTask;
        this.cacheInput = cacheInput;
        this.eventSource = eventSource;
        this.requestedPosition = requestedPosition;
        errorOccured = false;
    }

    // 非Rangeリクエストに対してレジューム成功時はヘッダを200にする
    @Override
    public void onResponseHeader(HttpResponseHeader responseHeader) {
        NicoCachingProcessor.giantLock.lock();
        try {
            onResponseHeaderCore(responseHeader);
        } finally {
            NicoCachingProcessor.giantLock.unlock();
        }
    }
    public void onResponseHeaderCore(HttpResponseHeader responseHeader) {
        long contentLength = responseHeader.getContentLength();
        int statusCode = responseHeader.getStatusCode();

        fileLength = contentLength;

        if (contentLength <= 0) {
            Logger.warning("Invalid Content-Length: " + contentLength);
            errorOccured = true;
        }

        if (statusCode == 206) {
            String rangeValue =
                    responseHeader.getMessageHeader("Content-Range");
            Matcher m = CONTENT_RANGE_VALUE_PATTERN.matcher(rangeValue);
            long endPosition = contentLength;
            if (m.find()) {
                endPosition = Long.parseLong(m.group(2));
                fileLength = Long.parseLong(m.group(3));
                Logger.info(String.format("Partial download from %,d byte",
                        Long.parseLong(m.group(1))));
                reportProgress();

                // [nl] DLする部分までのサイズを設定し、部分キャッシュ送信フラグをON
                fileRangeHead = currentPosition = Long.parseLong(m.group(1));

                partial = true;
            }
            // [nl] ヘッダを修正
            if (requestedPosition == -1) {
                contentLength = fileLength;
                responseHeader.setStatusCode(200, "OK");
                responseHeader.removeMessageHeader("Content-Range");
                responseHeader.removeMessageHeader("Accept-Ranges");
            } else {
                contentLength = responseHeader.getContentLength() + (currentPosition - requestedPosition);
                String newRangeValue = "bytes " + requestedPosition + "-" + endPosition + "/" + fileLength;
                responseHeader.setMessageHeader("Content-Range", newRangeValue);
            }
            responseHeader.setContentLength(contentLength);
        } else if (statusCode == 416) {
            Logger.warning("動画のレジュームに失敗しました。ブラウザをリロードしてください。");
            try {
                CloseUtil.close(cacheInput);
                cache.deleteTmp();
            } catch (IOException e) {
                Logger.error(e);
            }
            errorOccured = true;
        } else if (statusCode != 200) {
            Logger.warning("Invalid status code: " + statusCode);
            // [nl] 403とかキャッシュされると困るので
            responseHeader.addCacheControlHeaders(0);
            CloseUtil.close(cacheInput);
            errorOccured = true;
            // トークンの有効期限切れなどでキャッシュを消さないように
            keepCacheOnError = true;
        }

        //[nl] 空き容量のチェック(rc2)
        //      指定値以下か動画サイズ+100kb以下で停止
        long freeSize = DiskFreeSpace.get(Cache.getCacheDir());
        long needSize = Long.getLong("needFreeSpace") * 1024 * 1024;
        if (needSize != 0 && !errorOccured) {
            if (freeSize < needSize
                    || Cache.getDLCount(cache.getVideoDescriptor()) == 1
                       && freeSize < fileLength - cache.getCacheFile().length() + 100 * 1024) {
                Logger.warning(String.format(
                        "残り容量が %,d バイトなのでキャッシュしません。", freeSize));
                errorOccured = true;
            }
        }

        try {
            if (!errorOccured) {
                RandomAccessFile fOut = cache.getTmpOutputStream();
                cacheChannel = fOut.getChannel();

                // getTmpOutputStreamで正しいファイル名が決まってからMappedRangesを作る
                mr = cache.getCacheTmpMappedRanges();
                try {
                    if (mr.getSize() != fileLength) {
                        mr.setSize(fileLength);
                        mr.save();
                    }
                } catch (IOException e) {
                    Logger.warning("failed to update a map file.");
                }

                // 先に動画のサイズ分を確保する (sp1.06)
                // NOTE: DLセッション数が1かは確認しない．
                //   NicoCachingProcessorの方で増えるのでレースコンディションの可能性がある．
                if (Boolean.getBoolean("cacheAllocateFirst")) {
                    cacheChannel.write(ByteBuffer.wrap(new byte[1]), fileLength);
                    cacheChannel.truncate(fileLength);
                    cacheChannel.position(currentPosition);
                }
            }
        } catch (IOException e) {
            Logger.warning(cache.getCacheFileName() + ": " + e.toString());
            errorOccured = true;
        }

        if (!errorOccured) {
            // [nl] DL中の動画の最終サイズ
            Cache.setDLFinalSize(cache.getVideoDescriptor(), fileLength);
        }
        if (eventSource != null) {
            eventSource.setResponseHeader(responseHeader);
        }

        nextMapUpdating = System.currentTimeMillis() + 1000;
        // 初回は5秒後にmapファイルを書き出し
        nextMapFileUpdating = System.currentTimeMillis() + 5000;
    }

    // キャッシュに有る分を送信する
    @Override
    public void onTransferBegin(OutputStream bout) {
        if (partial && cacheInput != null && mr != null) {
            try {
                int len;
                long rest;
                if (requestedPosition == -1) {
                    rest = currentPosition;
                } else {
                    rest = currentPosition - requestedPosition;
                }
                byte[] buf = new byte[BUF_SIZE];
                while ((len = cacheInput.read(buf)) != -1 && rest > 0) {
                    try {
                        bout.write(buf, 0, (len < rest) ? len : (int) rest);
                    } catch (IOException e) {
                        // ブラウザへの書き込みでエラーになった場合は
                        // キャッシュを削除しない
                        keepCacheOnError = true;
                        throw e;
                    }
                    rest -= len;
                }
            } catch (IOException e) {
                // 正常系でも通信中に切断されるので通常はエラー表示しない
                Logger.debug(cache.getCacheFileName() + ": " + e.toString());
                errorOccured = true;
            } finally {
                if (CloseUtil.close(cacheInput) == false) {
                    errorOccured = true;
                }
            }
        }
    }

    @Override
    public void onTransferring(byte[] buf, int length) {
        if (errorOccured || cacheChannel == null || mr == null) {
            return;
        }

        try {
            cacheChannel.write(ByteBuffer.wrap(buf, 0, length), currentPosition);
            currentPosition += length;
        } catch (IOException e) {
            Logger.warning(cache.getCacheFileName() + ": " + e.toString());
            errorOccured = true;
        }

        if (nextMapUpdating <= System.currentTimeMillis()) {
            mr.map(fileRangeHead, currentPosition);
            nextMapUpdating = System.currentTimeMillis() + 1000;

            if (nextMapFileUpdating <= System.currentTimeMillis()) {
                try {
                    mr.save();
                } catch (IOException e) {
                    Logger.warning("failed to update a map file.");
                }
                nextMapFileUpdating = System.currentTimeMillis()
                        + Integer.getInteger("mapFileUpdatingInterval", 30) * 1000;
            }
        }
    }

    @Override
    public void onTransferEnd(boolean completed) {
        VideoDescriptor video = cache.getVideoDescriptor();
        NicoCachingProcessor.giantLock.lock();
        try {
            try {
                onTransferEndCore(completed);
            } finally {
                // [nl] DL中フラグを消す
                // Cache#store()後じゃないとExtension等に不具合が出るので注意
                // Cache#store()でVideoDescriptorが差し替わることに注意
                Cache.decrementDL(video);
            }
        } finally {
            NicoCachingProcessor.giantLock.unlock();
        }
    }
    public void onTransferEndCore(boolean completed) {
        long lastTail = 0;
        try {
            if (mr != null) {
                if (!errorOccured) {
                    mr.map(fileRangeHead, currentPosition);
                    mr.save();
                }
                lastTail = mr.getLastTail();
            }
        } catch (IOException e) {
            Logger.warning("Error around MappedRanges: " + e.getMessage());
        }

        // [nl] まだDLしてない部分を切り取る (sp1.06)
        // 他のセッションがDL中の部分かもしれないので他にいないときのみ実行．
        // NicoCache終了時に処理するとClosedByInterruptExceptionが投げられて失敗するが気にしない
        if (Boolean.getBoolean("cacheAllocateFirst")) {
            try {
                if (cacheChannel != null && mr != null && Cache.getDLCount(cache.getVideoDescriptor()) == 1) {
                    cacheChannel.truncate(lastTail);
                }
            } catch (IOException e) { }
        }

        if (CloseUtil.close(cacheChannel) == false) {
            errorOccured = true;
        }

        boolean cacheCompleted = false;
        if (!errorOccured && mr != null) {
            cacheCompleted = (mr.isComplete() && Cache.getDLCount(cache.getVideoDescriptor()) == 1);
            if (lastTail > fileLength) {
                Logger.warning("lastTail > fileLength: " + lastTail + " > " + fileLength);
                errorOccured = true;
            }
        }

        // 削除などされる前に再エンコード判定してキャッシュしてしまう
        // 非dmc・非エコノミー・mp4の判定はCache.isReEncodedStrictlyがやってくれる
        if (!errorOccured) {
            cache.isReEncodedStrictly();
        }

        try {
            Wrapupper w =
                    selectWrapupper(cacheCompleted, errorOccured, keepCacheOnError,
                            cache, fileLength, retrieveTitleTask, eventSource);
            w.wrapup();
        } catch (IOException e) {
            Logger.debugWithThread(e);
            Logger.warning(e.toString());
        }

    }

    private void reportProgress() {
        if (!Boolean.getBoolean("reportCachingProgress")) {
            return;
        }
        if (mr == null) {
            return;
        }
        long mappedSize = mr.getMappedSize();
        Logger.info(String.format("   |___ cached: %,d bytes / %,d bytes (%.1f%%)",
                mappedSize, fileLength, (double)mappedSize / fileLength * 100));
    }

    /**
     * Select wrapping up way.
     *
     * This condition selecting is a little bit complex,
     * so make this part a method to be able to test alone.
     *
     * @param completed
     * @param aErrorOccured
     * @param aKeepCacheOnError
     * @param aCache
     * @param aRetrieveTitleTask
     * @return Wrapupper
     */
    Wrapupper selectWrapupper(boolean completed, boolean aErrorOccured,
            boolean aKeepCacheOnError, Cache aCache, long fileLength,
            FutureTask<String> aRetrieveTitleTask, NLEventSource eventSource) {
        // [nl] キャッシュ中の動画を削除
        if (NLShared.INSTANCE.isInDeleteSet(aCache.getVideoId())) {
            NLShared.INSTANCE.removeDeleteSet(aCache.getVideoId());
            return new Deleter(aCache, aRetrieveTitleTask, eventSource);
        }

        // 今キャッシュした非dmc動画が再エンコードされていて かつ
        // dmcキャッシュを持っている時 キャッシュしない
        if (Boolean.getBoolean("removeReEncodedCache")) {
            VideoDescriptor video = aCache.getVideoDescriptor();
            if (!video.isDmc()) {
                // checkはonTransferEndCore内で既に呼ばれている
                ReEncodingInfo.Entry info = ReEncodingInfo.getEntry(video.getId());
                if (info != null && info.reencoded) {
                    VideoDescriptor preferredDmc = CacheManager.getPreferredCachedVideo(
                            video.getId(), true, null);
                    if (preferredDmc != null && !preferredDmc.isLow() &&
                            (!Boolean.getBoolean("useHighBitrateReEncodedCache")
                            || !preferredDmc.hasBitrate()
                            || info.bitrate <= preferredDmc.getVideoBitrate() + preferredDmc.getAudioBitrate())) {
                        // remove cache
                        return new Cleanupper(false, aCache, aRetrieveTitleTask);
                    }
                }
            }
        }

        if (aErrorOccured) {
            return new Cleanupper(aKeepCacheOnError, aCache, aRetrieveTitleTask);
        } else if (!completed) {
            if (Boolean.getBoolean("resumeDownload")) {
                // [nl] エラーじゃなく、単に完了してないだけなら
                return new Suspender(aCache, aRetrieveTitleTask, eventSource);
            } else {
                return new Cleanupper(aKeepCacheOnError, aCache,
                        aRetrieveTitleTask);
            }
        } else { // completed
            return new Completer(aCache, fileLength, aRetrieveTitleTask, eventSource);
        }
    }

    static interface Wrapupper {
        void wrapup() throws IOException;
    }

    class Cleanupper implements Wrapupper {
        private boolean keepCacheOnError;
        private Cache cache;
        private FutureTask<String> retrieveTitleTask;

        Cleanupper(boolean keepCacheOnError, Cache cache,
                FutureTask<String> retrieveTitleTask) {
            this.keepCacheOnError = keepCacheOnError;
            this.cache = cache;
            this.retrieveTitleTask = retrieveTitleTask;
        }

        @Override
        public void wrapup() throws IOException {
            if (!keepCacheOnError) {
                cache.deleteTmp();
                Logger.debugWithThread(cache.getCacheFileName() + " deleted");
            }

            if (retrieveTitleTask != null) {
                retrieveTitleTask.cancel(true);
            }

            Logger.info("disconnected: " + cache.getCacheFileName());
            if (keepCacheOnError) {
                reportProgress();
            }
        }
    }

    class Suspender implements Wrapupper {
        private Cache cache;
        private FutureTask<String> retrieveTitleTask;
        private NLEventSource eventSource;

        Suspender(Cache cache, FutureTask<String> retrieveTitleTask,
                NLEventSource eventSource) {
            this.cache = cache;
            this.retrieveTitleTask = retrieveTitleTask;
            this.eventSource = eventSource;
        }

        @Override
        public void wrapup() throws IOException {
            String title;
            try {
                if (retrieveTitleTask != null
                        && (title = retrieveTitleTask.get()) != null) {
                    cache.setDescribe(title);
                    cache.fixTmpFilename();
                }
            } catch (Exception e) {
                Logger.warning("title retrieving failed: " + e.toString());
            }

            // [nl] 中断時のイベント通知
            if (eventSource != null) {
                NLShared.INSTANCE.notifySystemEvent(
                        SystemEventListener.CACHE_SUSPENDED, eventSource, false);
                if (!cache.existsTmp()) {
                    Logger.debug("no temporary cache exists: " + cache.getId());
                    return;
                }
            }

            Logger.info("suspended: " + cache.getCacheFileName());
            reportProgress();
        }
    }

    static class Completer implements Wrapupper {
        private Cache cache;
        private long fileLength;
        private FutureTask<String> retrieveTitleTask;
        private NLEventSource eventSource;

        Completer(Cache cache, long fileLength,
                FutureTask<String> retrieveTitleTask, NLEventSource eventSource) {
            this.cache = cache;
            this.fileLength = fileLength;
            this.retrieveTitleTask = retrieveTitleTask;
            this.eventSource = eventSource;
        }

        @Override
        public void wrapup() throws IOException {
            String title;
            try {
                if (retrieveTitleTask != null
                        && (title = retrieveTitleTask.get()) != null) {
                    cache.setDescribe(title);
                }
            } catch (Exception e) {
                Logger.warning("title retrieving failed: " + e.toString());
            }

            cache.store();

            // [nl] 存在チェックと完了時のイベント通知
            if (!cache.exists()) {
                Logger.debug("no complete cache exists: " + cache.getId());
                return;
            }
            for (CompleteCache entry : NLShared.INSTANCE.getCompleteEntries()) {
                try {
                    if (entry.onComplete(cache)) {
                        break; // trueが返ってきた時点で終了
                    }
                } catch (Throwable t) {
                    Logger.error(t); // エラーは無視して続行
                }
            }
            if (eventSource != null) {
                NLShared.INSTANCE.notifySystemEvent(
                        SystemEventListener.CACHE_COMPLETED, eventSource, false);
            }

            Logger.info("cache completed: " + cache.getCacheFileName());
        }
    }

    // [nl] キャッシュ中の動画を削除用
    static class Deleter implements Wrapupper {
        private Cache cache;
        private FutureTask<String> retrieveTitleTask;
        private NLEventSource eventSource;

        Deleter(Cache cache, FutureTask<String> retrieveTitleTask,
                NLEventSource eventSource) {
            this.cache = cache;
            this.retrieveTitleTask = retrieveTitleTask;
            this.eventSource = eventSource;
        }

        @Override
        public void wrapup() throws IOException {
            // キャッシュ完了直後なので一時ファイルのまま
            if (cache.deleteTmp()) {
                if (eventSource != null) {
                    NLShared.INSTANCE.notifySystemEvent(
                            SystemEventListener.CACHE_REMOVED, eventSource, false);
                }
                Cache.removeAll(cache.getVideoId(), false);
                Logger.info("cache removed: " + cache.getCacheFileName());
            } else {
                Logger.info("cache removing failed: " + cache.getCacheFileName());
            }
            if (retrieveTitleTask != null) {
                retrieveTitleTask.cancel(true);
            }
        }
    }

}
