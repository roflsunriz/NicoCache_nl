package dareka.processor.impl;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dareka.NLConfig;
import dareka.common.FileUtil;
import dareka.common.Logger;
import dareka.common.Pair;
import dareka.common.TextUtil;
import dareka.extensions.SystemEventListener;
import dareka.processor.util.Flv2mp4;


// ロック用Staticオブジェクト
class LockObj {
    static final Object FileMoveLock = new Object();
    static final Object FileStoreLock = new Object();
}

/**
 *
 * Abstraction of cache handling.
 *
 */
// 共有インデックスはCacheManager、個別キャッシュの遷移とファイル操作は
// このクラスが管理する。
public class Cache extends CacheManager {
    public static final String FLV = ".flv";
    public static final String SWF = ".swf";
    public static final String MP4 = ".mp4";
    public static final String HLS = ".hls"; // dmcとdms(domand).

    // Windows では直前の配信で開かれていたファイルが閉じるまで削除できない
    // ことがあるため、短時間だけ再試行してから削除失敗と判定する。
    private static final int DELETE_RETRY_COUNT = 20;
    private static final long DELETE_RETRY_DELAY_MILLIS = 50L;
    private static final Set<String> TMP_DELETE_SET =
            ConcurrentHashMap.newKeySet();

    private VideoDescriptor video;
    private String desc;
    private File cacheFileNew; // [nl] 新規に作成する場合のキャッシュファイル
    private File tmpFile;

    public static void init() {
        CacheManager.init();
    }

    // [nl] フォルダ間移動
    public boolean moveTo(String dir) {
        if (dirList.contains(dir)) {
            File cacheFile = getCacheFile();
            File toFile = new File(dir2File.get(dir), cacheFile.getName());
            return moveCache(cacheFile, toFile);
        }
        return false;
    }

    // [nl] キャッシュファイルに移動
    private boolean moveCache(File src, File dst) {
        if (src != null && dst != null &&
                src.exists() && !dst.exists() && !src.equals(dst)) {
            // まずリネームしてみる
            video2FileLock.writeLock().lock();
            try {
                if (src.renameTo(dst)) {
                    video2File_put(video, dst);
                    return true;
                }
            } finally {
                video2FileLock.writeLock().unlock();
            }
            // 駄目ならコピーしてみる。
            return moveCacheFileByCopy(video, src, dst);
        }
        return false;
    }

    private boolean moveCacheFileByCopy(VideoDescriptor video, File src, File dst) {
        // 直接コピーするとinit()を呼び出すAPIが並行して発行された場合に
        // コピー途中のものを認識して不具合が起きる可能性があるので、
        // まず一時ファイルにコピーしてから改めてリネームする。
        // また、コピーの場合はI/Oを直列化させて競合状態を防ぐ。
        File tmp = new File(dst.getPath().concat(".tmp"));
        synchronized (LockObj.FileMoveLock) {
            if (tmp.exists() || !copyCacheFile(video, src, tmp))
                return false;
            // コピーしている間にsrcが変更される場合があるかも
            if (!src.exists()) {
                if ((src = video2File_get(video)) == null || !src.exists()) {
                    deleteCacheFile(video, tmp);
                    return false;
                }
                dst = new File(dst.getParentFile(), src.getName());
            }
        }
        video2FileLock.writeLock().lock();
        try {
            if (tmp.renameTo(dst)) {
                deleteCacheFile(video, src);
                video2File_put(video, dst);
                return true;
            } else {
                deleteCacheFile(video, tmp); // 失敗(まず有り得ないと思うけど…)
            }
        } finally {
            video2FileLock.writeLock().unlock();
        }
        return false;
    }

    private boolean copyCacheFile(VideoDescriptor video, File src, File dst) {
        if (video.isDir()) {
            return copyDirectoryRecursively(src, dst);
        } else {
            return FileUtil.copy(src, dst) != -1;
        }
    }

    private static boolean deleteCacheFile(VideoDescriptor video, File cacheFile) {
        if (video.isDir()) {
            return deleteDirectoryRecursively(cacheFile);
        } else {
            return deleteFileWithRetry(cacheFile);
        }
    }

    private static boolean deleteFileWithRetry(File file) {
        if (!file.exists()) {
            return false;
        }
        for (int attempt = 0; attempt < DELETE_RETRY_COUNT; attempt++) {
            if (file.delete() || !file.exists()) {
                return true;
            }
            try {
                Thread.sleep(DELETE_RETRY_DELAY_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private boolean copyDirectoryRecursively(File src, File dst) {
        File[] src_files = src.listFiles();
        if (src_files == null)
            return false;
        if (dst.exists())
            return false;
        dst.mkdir();
        for (File src_file : src_files) {
            File dst_file = new File(dst, src_file.getName());
            boolean result;
            if (src_file.isDirectory())
                result = copyDirectoryRecursively(src_file, dst_file);
            else
                result = FileUtil.copy(src_file, dst_file) != -1;
            if (!result)
                return false;
        }
        return true;
    }

    private static boolean deleteDirectoryRecursively(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                deleteDirectoryRecursively(file);
            }
        }
        return deleteFileWithRetry(dir);
    }

    // [nl] キャッシュファイルを指定パスにコピー
    public boolean copyTo(String dstPath) {
        return copyTo(new File(dstPath));
    }
    public boolean copyTo(File dstFile) {
        File cacheFile = video2File_get(video);
        if (cacheFile != null && cacheFile.exists()) {
            return !copyCacheFile(video, cacheFile, dstFile);
        }
        return false;
    }

    // [nl] 指定パスからキャッシュファイルにコピー
    public boolean copyFrom(String srcPath) {
        return copyFrom(new File(srcPath));
    }
    public boolean copyFrom(File srcFile) {
        File cacheFile = getCacheFile();
        // 一旦テンポラリファイルにコピーして成功すればキャッシュに置き換える
        File tmp = new File(cacheFile.getPath().concat(".tmp"));
        if (!tmp.exists() && !copyCacheFile(video, srcFile, tmp)) {
            video2FileLock.writeLock().lock();
            try {
                deleteCacheFile(video, cacheFile);
                if (tmp.renameTo(cacheFile)) {
                    video2File_put(video, cacheFile);
                    return true;
                }
            } finally {
                video2FileLock.writeLock().unlock();
            }
        }
        return false;
    }

    /**
     *  [nl] キャッシュを取り除く。Extension が阻害することもある。
     * @param id キャッシュ ID
     * @return キャッシュを取り除いたら true
     */
    @Deprecated
    public static boolean remove(String id) {
        return remove(altIdToVideoDescriptor(id));
    }

    /**
     *  [nl] キャッシュを取り除く。Extension が阻害することもある。
     * @param video キャッシュの VideoDescriptor
     * @return キャッシュを取り除いたら true
     */
    public static boolean remove(VideoDescriptor video) {
        File cacheFile = video2File_get(video);
        if (cacheFile == null || !cacheFile.exists()) {
            return false;
        }
        NLEventSource eventSource = null;
        if (NLShared.INSTANCE.countSystemEventListeners() > 0) {
            eventSource = new NLEventSource(null, null, new Cache(video));
            int result = NLShared.INSTANCE.notifySystemEvent(
                    SystemEventListener.CACHE_REMOVING, eventSource, true);
            if (result != SystemEventListener.RESULT_OK) {
                return false;
            }
        }
        if (deleteCacheFile(video, cacheFile)) {
            video2File_put(video, null);
            unregisterVideoDescriptorIfUnused(video);
            if (eventSource != null) {
                NLShared.INSTANCE.notifySystemEvent(
                        SystemEventListener.CACHE_REMOVED, eventSource, false);
            }
            return true;
        }
        return false;
    }

    // [nl] 一時ファイルを取り除く
    @Deprecated
    public static boolean removeTmp(String id) {
        return removeTmp(altIdToVideoDescriptor(id));
    }

    // [nl] 一時ファイルを取り除く
    public static boolean removeTmp(VideoDescriptor video) {
        if (video == null) {
            return false;
        }
        File tmp = video2Tmp.get(video);
        if (tmp == null || getDLFlag(video))
            return false;
        if (!tmp.exists())
            return false;
        if (!deleteCacheFile(video, tmp))
            return false;
        video2Tmp.remove(video);
        unregisterVideoDescriptorIfUnused(video);
        if (!video.isDir()) {
            File mapFile = getCacheTmpMappedRangesFile(tmp);
            if (mapFile != null && mapFile.exists()) {
                mapFile.delete();
                video2MappedRanges.remove(video);
            }
        }
        //[xmlfix]
        removeXmlCache(TYPE_TEMPLIST, null);
        return true;
    }

    /**
     * 指定した動画IDに属する一時キャッシュをすべて削除する。
     * ダウンロード中の一時キャッシュは安全に処理を終えた時点で削除する。
     * 完成済みキャッシュは対象にしない。
     *
     * @param id 修飾のない動画ID
     * @return 一時キャッシュを削除したか、削除を予約した場合はtrue
     */
    public static boolean removeTmpAll(String id) {
        if (id == null) {
            return false;
        }
        if (id.endsWith("low")) {
            id = id.substring(0, id.length() - "low".length());
        }
        Set<VideoDescriptor> videos = id2Videos.get(id);
        if (videos == null) {
            return false;
        }

        TMP_DELETE_SET.add(id);
        boolean accepted = false;
        boolean failed = false;
        for (VideoDescriptor video : new ArrayList<>(videos)) {
            File tmp = video2Tmp.get(video);
            if (tmp == null || !tmp.exists()) {
                continue;
            }
            accepted = true;
            if (!getDLFlag(video) && !removeTmp(video)) {
                failed = true;
            }
        }
        if (!hasActiveTmp(id)) {
            TMP_DELETE_SET.remove(id);
        }
        return accepted && !failed;
    }

    /** ダウンロード終了時に予約済みの一時キャッシュを削除する。 */
    static void deleteReservedTmpAfterDownload(VideoDescriptor video) {
        if (video == null || !TMP_DELETE_SET.contains(video.getId())
                || getDLFlag(video)) {
            return;
        }
        try {
            if (!new Cache(video).deleteTmp()) {
                Logger.warning("予約した一時キャッシュを削除できません: " + video);
            }
        } catch (IOException e) {
            Logger.warning("予約した一時キャッシュを削除できません: " + video);
            Logger.debugWithThread(e);
        }
        if (!hasActiveTmp(video.getId())) {
            TMP_DELETE_SET.remove(video.getId());
        }
    }

    /** store直前に予約を適用し、完成済みキャッシュへの移動を中止する。 */
    private static boolean consumeReservedTmpBeforeStore(VideoDescriptor video) {
        if (video == null || !TMP_DELETE_SET.contains(video.getId())) {
            return false;
        }
        video2DL.remove(video);
        video2DLFinalSize.remove(video);
        try {
            if (!new Cache(video).deleteTmp()) {
                Logger.warning("予約した一時キャッシュを保存前に削除できません: " + video);
            }
        } catch (IOException e) {
            Logger.warning("予約した一時キャッシュを保存前に削除できません: " + video);
            Logger.debugWithThread(e);
        }
        if (!hasActiveTmp(video.getId())) {
            TMP_DELETE_SET.remove(video.getId());
        }
        return true;
    }

    private static boolean hasActiveTmp(String id) {
        Set<VideoDescriptor> videos = id2Videos.get(id);
        if (videos == null) {
            return false;
        }
        for (VideoDescriptor video : new ArrayList<>(videos)) {
            File tmp = video2Tmp.get(video);
            if (tmp != null && tmp.exists() && getDLFlag(video)) {
                return true;
            }
        }
        return false;
    }

    // [nl] 指定IDに関連する以下のファイルを全て取り除く
    // 通常キャッシュ、エコノミーキャッシュ、それらの一時ファイル
    public static boolean removeAll(String id) {
        return removeAll(id, true);
    }
    public static boolean removeAll(String id, boolean rmDL) {
        boolean removed = false;
        id = id.replaceFirst("low", ""); // for compatibility
        Set<VideoDescriptor> videos = id2Videos.get(id);
        if (videos == null) {
            return false;
        }
        synchronized (videos) {
            for (VideoDescriptor video : videos) {
                if (rmDL) removed |= removeDL(video);
                removed |= remove(video) | removeTmp(video);
            }
        }
        return removed;
    }

    // [nl] ダウンロード中のキャッシュを削除予約する
    private static boolean removeDL(VideoDescriptor video) {
        if (Cache.getDLFlag(video)) {
            NLShared.INSTANCE.addDeleteSet(video.getId());
            return true;
        }
        return false;
    }

    // [nl] 動画URLの識別子から拡張子をセットする
    protected void setPostfix(String vType) {
        applyPostfix(vType);
    }

    private void applyPostfix(String vType) {
        String postfix = null;
        switch (vType.charAt(0)) {
        case 'v':
            postfix = FLV; break;
        case 's':
            postfix = SWF; break;
        case 'm':
            postfix = MP4; break;
        case 'h':
            postfix = HLS; break;
        case '.':
            postfix = vType; break;
        }
        if (postfix != null) {
            video = video.replacePostfix(postfix);
        }
    }

    public String getPostfix() {
        return video.getPostfix();
    }

    public static void cleanup() {
        // [nl] 一時ファイル削除無効(何もしない)
    }

    // [nl] コンストラクタ
    @Deprecated
    public Cache(String cacheId) {
        this(cacheId, FLV);
    }

    @Deprecated
    public Cache(String cacheId, String postfix) {
        this.video = altIdToVideoDescriptor(cacheId, postfix);
        applyPostfix(postfix);
    }

    @Deprecated
    public Cache(String cacheId, String postfix, String desc) {
        this.video = altIdToVideoDescriptor(cacheId, postfix);
        this.desc = TextUtil.stripZeroWithChars(desc); // [nl]
        applyPostfix(postfix);
    }

    public Cache(VideoDescriptor video) {
        this.video = video;
    }

    public Cache(VideoDescriptor video, String desc) {
        this.video = video;
        this.desc = TextUtil.stripZeroWithChars(desc);
    }

    // [nl] 新規キャッシュファイルのインスタンスを作成
    // コンストラクタで作成せずに実際に必要になる時まで遅延させる
    private void setCacheFileNew() {
        if (desc != null) {
            cacheFileNew = getDescribedCacheFile(desc, false);
        } else {
            cacheFileNew = new File(cacheDir, getPrefixString(false) + video.getPostfix());
        }
    }

    private File getCacheTmpFileSub() {
        if (desc != null) {
            return getDescribedCacheFile(desc, true);
        } else {
            return new File(cacheDir, NLTMP_ + getPrefixString(true)
                    + video.getPostfix());
        }
    }

    // [nl] タイトルを取得（無い場合はID）
    public String getTitle() {
        String filename = this.getCacheFileName();
        Matcher m = CACHE_FILE_PATTERN.matcher(filename);
        if (m.find()) {
            if (m.group(4) != null)
                return m.group(4);
            else
                return m.group(1);
        }
        m = DMC_CACHE_FILE_PATTERN.matcher(filename);
        if (m.find()) {
            if (m.group(8) != null)
                return m.group(8);
            else
                return m.group(1);
        }
        return "";
    }

    // [nl] ファイル名のタイトルを変更する
    public boolean setTitle(String newTitle) {
        desc = newTitle;
        setCacheFileNew();
        File cacheFile = video2File_get(video);
        File newFile = new File(cacheFile.getParentFile(), cacheFileNew.getName());
        boolean result = moveCache(cacheFile, newFile);
        if (result) {
            video2File_put(video, newFile);
        }
        return result;
    }

    public void unmarkLow() {
        if (!video.isLow()) {
            return;
        }
        VideoDescriptor oldVideo = video;
        video = video.replaceLow(false);

        // video2{Tmp,File}から消す前に取得する
        File tmpFile = video2Tmp.get(oldVideo);
        File cacheFile = video2File_get(oldVideo);
        // fixTmpFilenameやmoveCacheの前に一度消してkeyを変える
        video2Tmp.remove(oldVideo);
        video2File.remove(oldVideo);
        unregisterVideoDescriptorIfUnused(oldVideo);

        // rename tmp
        if (tmpFile != null && tmpFile.exists()) {
            try {
                video2Tmp.put(video, tmpFile);
                fixTmpFilename();
            } catch (IOException e) {
                Logger.info("failed to unmark low tmp file");
            }
        }
        // rename cache
        if (cacheFile != null) {
            video2File.put(video, cacheFile);
            setCacheFileNew();
            File newFilepath = new File(cacheFile.getParentFile(), cacheFileNew.getName());
            moveCache(cacheFile, newFilepath);
        }
        registerVideoDescriptor(video);
    }

    private class DirInfo implements Comparable<DirInfo> {
        public String path;
        public String name;
        public int depth;
        public DirInfo(String path, String name, int depth) {
            this.path = path;
            this.name = name;
            this.depth = depth;
        }

        // 名前部分が長い・深い順
        @Override
        public int compareTo(DirInfo e) {
            if (this.name.length() == e.name.length())
                return this.depth == e.depth ? 0 : (this.depth < e.depth ? 1 : -1);
            return this.name.length() < e.name.length() ? 1 : -1;
        }
    }

    // [nl]フォルダ名をフィルタとして、ストア先を探す
    protected String suggestStoreFolder() {
        ArrayList<DirInfo> dirs = new ArrayList<>();
        for (String dir : dirList) {
            if (dir.length() == 0 || NLConfig.find("storeFilterIgnore", dir))
                continue;
            String[] parts = dir.split("/");
            int n = parts.length;
            dirs.add(new DirInfo(dir, tidyTitle(parts[n-1].toLowerCase()), n));
        }
        // 検索順にソートして、 "/" を除いておく
        Collections.sort(dirs);

        String test = getCacheFileName().toLowerCase();
        for (DirInfo entry : dirs) {
            String[] patterns = entry.name.split(" +");
            boolean match = true;
            for (String p : patterns) {
                if (p.charAt(0) == '-') {
                    // NOT
                    if (test.contains(p.substring(1)))
                        match = false;
                } else {
                    // AND
                    if (!test.contains(p))
                        match = false;
                }
            }
            if (match)
                return entry.path;
        }

        return "";
    }

    // [nl] タイトル文字列を有る程度整形する
    //      全角英数字→半角英数字
    //      複数の半角全角スペースを1つの半角スペースに
    //      NFC正規化
    private static final Pattern spacePattern = Pattern.compile("[ 　]+");
    public static String tidyTitle(String oldTitle) {
        char[] title = spacePattern.matcher(oldTitle).replaceAll(" ").trim().toCharArray();

        for (int i = 0; i < title.length; i++) {
            char c = title[i];
            if ('０' <= c && c <= '９')
                c = (char)(c - '０' + '0');
            else if ('Ａ' <= c && c <= 'Ｚ')
                c = (char)(c - 'Ａ' + 'A');
            else if ('ａ' <= c && c <= 'ｚ')
                c = (char)(c - 'ａ' + 'a');
            title[i] = c;
        }
        return Normalizer.normalize(new String(title), Normalizer.Form.NFC);
    }

    // [nl] 実体参照(Entity Reference)を置換する
    private static String replaceER(String src) {
        return src.replaceAll("&amp;" , "&")
                  .replaceAll("&lt;"  , "<")
                  .replaceAll("&gt;"  , ">")
                  .replaceAll("&quot;", "\"")
                  .replaceAll("&apos;", "'");
    }

    protected File getDescribedCacheFile(String desc, boolean tmpfile) {
        StringBuffer decodedDesc = new StringBuffer(desc.length());

        Matcher m = NUMBER_CHARACTER_REFERENCE_PATTERN.matcher(desc);

        while (m.find()) {
            m.appendReplacement(decodedDesc,
                    Character.toString((char) Integer.parseInt(m.group(1))));
        }
        m.appendTail(decodedDesc);

        // [nl] タイトル整形
        if (Boolean.getBoolean("tidyTitle")) {
            desc = tidyTitle(desc);
        } else {
            desc = Normalizer.normalize(desc, Normalizer.Form.NFC);
        }

        if (tmpfile) {
            return new File(cacheDir, NLTMP_ + Cache.getSanitizedDescription(
                    getPrefixString(true) + "_" + desc)
                    + video.getPostfix());
        } else {
            return new File(cacheDir, Cache.getSanitizedDescription(
                    getPrefixString(false) + "_" + desc)
                    + video.getPostfix());
        }
    }

    public static String getSanitizedDescription(String decodedDesc) {
        String fileNameCharset = System.getProperty("fileNameCharset");

        String narrowedDesc = narrowCharset(decodedDesc, fileNameCharset);

        // 実体参照を更に実体参照するケースがあるので二回置換する
        narrowedDesc = replaceER(replaceER(narrowedDesc));

        char descArray[] = narrowedDesc.toCharArray();

        for (int i = 0; i < descArray.length; i++) {
            char c = descArray[i];
            switch (c) {
            case '\\': case '\u00a5': c = '¥'; break;
            case '/': c = '／';     break;
            case ':':   c = '：';   break;
            case '*':   c = '＊';   break;
            case '?':   c = '？';   break;
            case '"':   c = '”';   break;
            case '<':   c = '＜';   break;
            case '>':   c = '＞';   break;
            case '|':   c = '｜';   break;
            case '\u203e':// c = '~'; break;
            case '\u301c':// c = '〜'; break;
            case '\u2016': case '\u2212': case '\u00a2':
            case '\u00a3': case '\u00ac':
                c = '-'; break;
            }

            descArray[i] = c;
        }
        return new String(descArray);
    }

    // 一旦目的の文字コードにしてから戻して、対応してない文字を '?' にする
    static String narrowCharset(String str, String charsetName) {
        String narrowedDesc = str;
        if (!charsetName.equals("")) {
            try {
                narrowedDesc =
                        new String(str.getBytes(charsetName), charsetName);
                // characters which are not supported by the charset
                // become '?'
            } catch (UnsupportedEncodingException e) {
                Logger.warning(e.toString());
            }

        }
        return narrowedDesc;
    }

    /**
     * VideoDescriptorを返す
     */
    public VideoDescriptor getVideoDescriptor() {
        return video;
    }

    /**
     * AltIdを返す
     */
    public String getId() {
        return videoDescriptorToAltId(video);
    }

    /**
     * [nl] 動画ID(末尾に"low"を含まない)を返す
     * @return 動画ID
     * @since NicoCache_nl+111111mod
     */
    public String getVideoId() {
        return video.getId();
    }

    /**
     * [nl] エコノミーキャッシュか？
     * @return エコノミーキャッシュなら true
     * @since NicoCache_nl+111111mod
     */
    public boolean isLow() {
        return video.isLow();
    }

    // - ファイルパスを返す.
    // - まだ存在しない可能性もある.
    public File getCacheFile() {
        // [nl] 排他制御が必要なので常にid2Fileから取得する
        File cacheFile = video2File_get(video);
        if (cacheFile != null) {
            setDescribe(cacheFile, true);
            return cacheFile;
        } else if (cacheFileNew == null) {
            setCacheFileNew();
        }
        return cacheFileNew;
    }

    public Path getCachePath() {
        File x = getCacheFile();
        if (x == null) {
            return null;
        };
        return x.toPath();
    };

    public String getCacheFileName() {
        return getCacheFile().getName();
    }

    public String getURLString() {
        return getCacheFile().toURI().toString();
    }

    public void setDescribe(String desc) {
//      cacheFile = getDescribedCacheFile(desc);
        this.desc = desc;
        cacheFileNew = null;
    }

    // [nl] キャッシュファイルから設定
    private void setDescribe(File cacheFile, boolean setAux) {
        if (setDescribeCore(cacheFile.getName(), setAux)) {
            cacheFileNew = null;
        }
    }

    private void setDescribeFromTmp(File tmpCacheFile, boolean setAux) {
        String filename = tmpCacheFile.getName();
        filename = filename.substring(6);
        setDescribeCore(filename, setAux);
    }

    private boolean setDescribeCore(String filename, boolean setAux) {
        Matcher m = CACHE_FILE_PATTERN.matcher(filename);
        if (m.matches()) {
            if(m.group(4) != null) desc = m.group(4);
            if(setAux) video = video.replacePostfix(m.group(5));
            return true;
        }
        m = DMC_CACHE_FILE_PATTERN.matcher(filename);
        if (m.matches()) {
            if(m.group(8) != null) desc = m.group(8);
            if(setAux) {
                boolean low = m.group(3).equals("low");
                if (low != video.isLow()) {
                    video = video.replaceLow(low);
                }
            }
            return true;
        }
        return false;
    }

    @Deprecated
    public void setTmpDescribe(String title) throws IOException {
        String filename = NLTMP_ + Cache.getSanitizedDescription(
                getPrefixString(true) + "_" + title) + video.getPostfix();
        File newName = new File(cacheDir, filename);
        MappedRanges mr = getCacheTmpMappedRanges();
        File file = getCacheTmpFile();
        if (file != null && mr != null && file.renameTo(newName)) {
            video2Tmp.put(video, newName);
            tmpFile = newName;
            mr.renameTo(getCacheTmpMappedRangesFile());
        }
    }

    protected void fixTmpFilename() throws IOException {
        String filename = NLTMP_ + Cache.getSanitizedDescription(
                getPrefixString(true) + "_" + desc) + video.getPostfix();
        File newName = new File(cacheDir, filename);
        MappedRanges mr = getCacheTmpMappedRanges();
        File file = getCacheTmpFile();
        if (file != null && mr != null && file.renameTo(newName)) {
            video2Tmp.put(video, newName);
            tmpFile = newName;
            mr.renameTo(getCacheTmpMappedRangesFile());
        }
    }

    public long length() {
        if (video.isDir()) {
            return getDirectoryFilesizeRecursively(getCacheFile());
        }
        return getCacheFile().length();
    }

    private static long getDirectoryFilesizeRecursively(File dir) {
        File[] files = dir.listFiles();
        if (files == null)
            return 0;
        long result = 0;
        for (File file : files) {
            if (file.isDirectory())
                result += getDirectoryFilesizeRecursively(file);
            else
                result += file.length();
        }
        return result;
    }

    public long tmpLength() throws IOException {
        if (video.isDir()) {
            HlsTmpSegments hlsTmpSegments = HlsTmpSegments.get(video);
            if (hlsTmpSegments == null) {
                return 0;
            }
            return hlsTmpSegments.getCachedSegments().size();
        }
        File file = getCacheTmpFile();
        if (file != null) {
            return file.length();
        } else {
            return 0;
        }
    }

    /**
     * 一時ファイルにキャッシュ済みのサイズを返す．
     * @return キャッシュ済みのサイズ．一時ファイルがない場合などエラー時は-1．
     */
    public long tmpCachedSize() {
        if (!video2Tmp.containsKey(video)) {
            return -1;
        }
        if (video.isDir()) {
            HlsTmpSegments hlsTmpSegments = HlsTmpSegments.get(video);
            if (hlsTmpSegments == null) {
                return -1;
            }
            return hlsTmpSegments.getCachedSegments().size();
        }
        MappedRanges mr = getCacheTmpMappedRanges();
        if (mr == null) {
            return -1;
        }
        return mr.getMappedSize();
    }

    /**
     * 一時ファイルのmapファイルから最終的なサイズを返す．
     * @return 最終的なサイズ．一時ファイルがない場合などエラー時は-1．
     */
    public long tmpFinalSize() {
        if (!video2Tmp.containsKey(video)) {
            return -1;
        }
        if (video.isDir()) {
            HlsTmpSegments hlsTmpSegments = HlsTmpSegments.get(video);
            if (hlsTmpSegments == null) {
                return -1;
            }
            return hlsTmpSegments.getPlaylistSegments().size();
        }
        MappedRanges mr = getCacheTmpMappedRanges();
        if (mr == null) {
            return -1;
        }
        return mr.getSize();
    }

    // nltmpの有無は影響しない.
    public boolean exists() {
        return getCacheFile().exists();
    }

    public void touch() {
        File cacheFile = getCacheFile();
        if (cacheFile.exists()) {
            cacheFile.setLastModified(System.currentTimeMillis());
            statCache.remove(video);
        }
    }

    public InputStream getInputStream() throws IOException {
        //cacheFile.setLastModified(System.currentTimeMillis());
        if (Boolean.getBoolean("touchCache")) touch();
        return new FileInputStream(getCacheFile());
    }

    /**
     * 一時ファイルの入力ストリームを得る
     * @return 一時ファイルの入力ストリーム
     * @throws IOException
     */
    public InputStream getTmpInputStream() throws IOException {
        File cacheTmpFile = getCacheTmpFile();
        if (cacheTmpFile == null) {
            throw new FileNotFoundException();
        }
        return new FileInputStream(cacheTmpFile);
    }

    /**
     * 一時ファイルの出力ストリームを得る
     * [nl] ファイル名が長過ぎる場合は1文字ずつ切り詰めてリトライする
     * @return 一時ファイルの出力ストリーム
     * @throws IOException
     */
    public RandomAccessFile getTmpOutputStream() throws IOException {
        String truncated = null;
        do {
            try {
                RandomAccessFile out = createTmpOutputStream();
                if (truncated != null) {
                    Logger.warning("title truncated: " + truncated);
                }
                return out;
            } catch (FileNotFoundException e) {
                if (desc == null && desc.length() < 60 || !cacheDir.exists()) {
                    throw e;
                }
                video2Tmp.remove(video);
                tmpFile = null;

                // 多分ファイル名が長いので1文字ずつ切り詰めてみる
                truncated = desc.substring(0, desc.length() - 1);
                setDescribe(truncated);
            }
        } while (desc != null && desc.length() > 0);

        // ここまで来たならタイトル無しで返す
        setDescribe(null);
        return createTmpOutputStream();
    }

    // [nl]
    private RandomAccessFile createTmpOutputStream() throws IOException {
        File cacheTmpFile = getCacheTmpFileWithSet();
        return new RandomAccessFile(cacheTmpFile, "rw");
    }

    // - 2024-08-24 getTmpHlsDirectoryという名前から変更. 作成が含まれるため.
    public File prepareTmpHlsDirectory() throws IOException {
        String truncated = null;
        do {
            File dir = createTmpHlsDirectory();
            if (dir != null) {
                if (truncated != null) {
                    Logger.warning("title truncated: " + truncated);
                }
                return dir;
            } else {
                video2Tmp.remove(video);
                tmpFile = null;

                // 多分ファイル名が長いので1文字ずつ切り詰めてみる
                truncated = desc.substring(0, desc.length() - 1);
                setDescribe(truncated);
            }
        } while (desc != null && desc.length() > 0);

        // ここまで来たならタイトル無しで返す
        setDescribe(null);
        return createTmpHlsDirectory();
    }

    private File createTmpHlsDirectory() throws IOException {
        File dir = getCacheTmpFileWithSet();
        if (dir.mkdir()) {
            return dir;
        } else {
            if (dir.exists()) {
                return dir;
            }
            if (!dir.getParentFile().exists()) {
                throw new FileNotFoundException("Parent directory does not exist");
            }
            return null;
        }
    }

    // キャッシュを適切な位置・名前で格納する。
    // [nl] エコノミー版が有れば消すように。振り分け関係を追加
    //      エコノミー版があればその場所に保存するように (#027, #028)
    public void store() throws IOException {
// eco=1を実装したので、同一IDの通常･エコノミーキャッシュが
// 同時に作成されないよう排他制御
        synchronized (LockObj.FileStoreLock) {

            if (consumeReservedTmpBeforeStore(video)) {
                Logger.info("reserved temporary cache removed: " + video);
                return;
            }

            //[xmlfix] 一時ファイル一覧のXMLキャッシュを削除
            removeXmlCache(TYPE_TEMPLIST, null);

            File cacheTmpFile = getCacheTmpFile();
            File cacheMapFile = getCacheTmpMappedRangesFile();

            File parentDir = cacheDir;
            if (hasSuperiorCache()) {
// キャッシュ完了時、より良いキャッシュがあったら保存しない
                deleteTmp();
                Logger.info("superior cache already exists, removed: " + video);
                return;
            }

// すでにあるlow等のキャッシュファイルからタイトルと保存先と最終更新日時を取得
// 最初に見つけたものを使う
            Set<VideoDescriptor> oldVideos = id2Videos.get(video.getId());
            long mtime = 0;
            if (oldVideos != null) {
                for (VideoDescriptor v : oldVideos) {
                    File f = video2File_get(v);
                    if (f != null && f.exists()) {
                        setDescribe(f, false);
                        parentDir = f.getParentFile();
                        mtime = f.lastModified();
                        break;
                    }
                }
            }
// low等のキャッシュファイルと一時ファイルを削除
            for (VideoDescriptor lesserVideo : getLesserCachedVideos()) {
                Logger.info("remove lesser " + lesserVideo);
                Cache.remove(lesserVideo);
                Cache.removeTmp(lesserVideo);
            }

            if (parentDir == cacheDir && Boolean.getBoolean("storeFilter")) {
                String storeFolder = suggestStoreFolder();
                if (!storeFolder.equals("")) {
                    Logger.info("storing folder: " + storeFolder);
                    parentDir = dir2File.get(storeFolder);
                }
            }

            VideoDescriptor oldvideo = video;
            video = video.stripSrcId();
            registerVideoDescriptor(video);

            setCacheFileNew();
            File cacheFile = new File(parentDir, cacheFileNew.getName());

            if (!moveCache(cacheTmpFile, cacheFile)) {
                deleteCacheFile(video, cacheTmpFile);
            }
            if (video.isDir()) {
                CacheManager.HlsTmpSegments.forget(oldvideo);
            }
            if (cacheMapFile != null) {
                cacheMapFile.delete();
            }

            if (Boolean.getBoolean("keepCacheLastModified") && mtime != 0) {
                cacheFile.setLastModified(mtime);
            }

            video2Tmp.remove(oldvideo);
            video2MappedRanges.remove(oldvideo);
            unregisterVideoDescriptorIfUnused(oldvideo);
        }
    }

    public boolean deleteTmp() throws IOException {
        File cacheTmpFile = video2Tmp.remove(video);
        tmpFile = null;
        if (cacheTmpFile == null) {
            return false;
        }
        unregisterVideoDescriptorIfUnused(video);
        if (video.isDir()) {
            CacheManager.HlsTmpSegments.forget(video);
        } else {
            File cacheMapFile = getCacheTmpMappedRangesFile();
            if (cacheMapFile != null) {
                cacheMapFile.delete();
            }
            video2MappedRanges.remove(video);
        }
        return deleteCacheFile(video, cacheTmpFile);
    }

    boolean existsTmp() { // [nl]
        return tmpFile != null && tmpFile.exists();
    }

    protected synchronized File getCacheTmpFile() {
        if (tmpFile == null) {
            File knownTmpFile = video2Tmp.get(video);
            if (knownTmpFile != null) {
                setDescribeFromTmp(knownTmpFile, false);
                tmpFile = knownTmpFile;
            }
        }
        return tmpFile;
    }

    protected Path getCacheTmpPath() {
        File tmp = getCacheTmpFile();
        if (tmp == null) {
            return null;
        };
        return tmp.toPath();
    };

    // tmpFileのtest and setをatomicにするためにsynchronizedが必要。
    protected synchronized File getCacheTmpFileWithSet() {
        if (tmpFile == null) {
            File knownTmpFile = video2Tmp.get(video);
            if (knownTmpFile == null) {
                tmpFile = getCacheTmpFileSub();
            } else {
                setDescribeFromTmp(knownTmpFile, false);
                tmpFile = knownTmpFile;
            }
        }
        video2Tmp.put(video, tmpFile);
        registerVideoDescriptor(video);
        return tmpFile;
    }

    protected static ConcurrentHashMap<VideoDescriptor, MappedRanges> video2MappedRanges =
            new ConcurrentHashMap<VideoDescriptor, MappedRanges>();

    public MappedRanges getCacheTmpMappedRanges() {
        if (video.isDir()) {
            return null;
        }
        MappedRanges mr = video2MappedRanges.get(video);
        if (mr != null) {
            return mr;
        }
        File mapFile = getCacheTmpMappedRangesFile();
        if (mapFile == null) {
            return null;
        }
        mr = new MappedRanges(mapFile);
        MappedRanges old = video2MappedRanges.putIfAbsent(video, mr);
        if (old != null) {
            mr = old;
        }
        try {
            mr.load();
        } catch (IOException e) {
            Logger.error(e);
        }
        return mr;
    }

    protected File getCacheTmpMappedRangesFile() {
        if (video.isDir()) {
            return null;
        }
        return getCacheTmpMappedRangesFile(getCacheTmpFile());
    }

    protected static File getCacheTmpMappedRangesFile(File cacheTmpFile) {
        if (cacheTmpFile == null) {
            return null;
        }
        File dir = cacheTmpFile.getParentFile();
        if (!cacheTmpFile.getName().startsWith(NLTMP_)) {
            return null;
        }
        String cacheTmpFilename = cacheTmpFile.getName();
        String mapFilename = cacheTmpFilename.substring(0, cacheTmpFilename.length() - 1) + "_";
        File mapFile = new File(dir, mapFilename);
        // mapファイルだけが存在している場合は削除
        if (mapFile.exists() && !cacheTmpFile.exists()) {
            mapFile.delete();
        }
        return mapFile;
    }

    // video2ConvertedMp4に対して変換中かどうかを表すロック
    private final static ConcurrentHashMap<VideoDescriptor, Pair<ReentrantLock, Condition>> video2ConvertedMp4Lock =
            new ConcurrentHashMap<>();

    /**
     * FLVからMPEG4に変換したファイルを返す．
     * まだ変換されていないキャッシュの処理には時間がかかる．
     */
    protected File getConvertedMp4FromFlv() {
        File convertedFile;

        // 変換済みのデータがあるか
        convertedFile = video2ConvertedMp4_get(video);
        if (convertedFile != null) {
            return convertedFile;
        }

        ReentrantLock lock = new ReentrantLock();
        Condition cond = lock.newCondition();
        Pair<ReentrantLock, Condition> lockpair = new Pair<>(lock, cond);

        lockpair = video2ConvertedMp4Lock.putIfAbsent(video, lockpair);
        if (lockpair != null) { // 変換中にやってきたスレッド
            Logger.debugWithThread("getConvertedMp4FromFlv: waiting signal");
            lock = lockpair.first;
            cond = lockpair.second;
            try {
                lock.lock();
                // 変換が終わるのを待って返す
                while ((convertedFile = video2ConvertedMp4_get(video)) == null &&
                        video2ConvertedMp4Lock.get(video) != null)
                    cond.awaitUninterruptibly();
            } finally {
                lock.unlock();
            }
            Logger.debugWithThread("getConvertedMp4FromFlv: signaled");
            return convertedFile;
        }

        // ここからは変換処理を行うスレッド
        lockpair = video2ConvertedMp4Lock.get(video);
        lock = lockpair.first;
        cond = lockpair.second;

        File tmpfile;
        try {
            tmpfile = File.createTempFile("tmp_flv2mp4_" + getId() + "_", Cache.MP4, convertedCacheDir);
        } catch (IOException e) {
            return null;
        }
        tmpfile.deleteOnExit();

        String filename = getCacheFileName();
        filename = filename.substring(0, filename.length() - 4) + Cache.MP4;
        File convertedCache = new File(convertedCacheDir, filename);
        try {
            Logger.debugWithThread("getConvertedMp4FromFlv: converting");
            lock.lock();
            boolean success = Flv2mp4.convert(getCacheFile(), tmpfile);
            if (!success) {
                return null;
            }

            if (!tmpfile.renameTo(convertedCache)) {
                Logger.info("failed to move the converted cache to " + convertedCache);
                return null;
            }
            video2ConvertedMp4.put(video, convertedCache);
        } finally {
            try {
                tmpfile.delete();
                // Lockオブジェクトを削除して変換中のスレッドがいない状態を表す
                video2ConvertedMp4Lock.remove(video);
            } finally {
                // 変換処理終了を通知
                cond.signalAll();
                lock.unlock();
            }
        }
        Logger.debugWithThread("getConvertedMp4FromFlv: signaling");
        return convertedCache;

        /*
        // 変換済みのデータがあるか
        convertedFile = video2ConvertedMp4.get(video);
        if (convertedFile != null) {
            // 変換が終わるのを待って返す
            synchronized (convertedFile) {
                // 変換に失敗した場合はnullに変わる
                convertedFile = video2ConvertedMp4.get(video);
                return convertedFile;
            }
        }
        File tmpfile;
        try {
            if (System.getProperty("flv2mp4TmpDir") == null || System.getProperty("flv2mp4TmpDir").isEmpty()) {
                tmpfile = File.createTempFile("nl_" + getId() + "_", Cache.MP4);
            } else {
                tmpfile = File.createTempFile("nl_" + getId() + "_", Cache.MP4,
                        new File(System.getProperty("flv2mp4TmpDir")));
            }
        } catch (IOException e) {
            return null;
        }
        convertedFile = video2ConvertedMp4.putIfAbsent(video, tmpfile);
        if (convertedFile != null) { // 割りこまれた
            tmpfile.delete();
            // 変換が終わるのを待って返す
            synchronized (convertedFile) {
                // 変換に失敗した場合はnullに変わる
                convertedFile = video2ConvertedMp4.get(video);
                return convertedFile;
            }
        }
        tmpfile.deleteOnExit();
        synchronized (tmpfile) {
            boolean success = Flv2mp4.convert(getCacheFile(), tmpfile);
            if (!success) {
                tmpfile.delete();
                video2ConvertedMp4.remove(video);
                return null;
            }
        }
        return tmpfile;
        */
    }

    /**
     * 再エンコードされた動画かを判定する．
     * 不明なときはfalseを返す．
     */
    public boolean isReEncoded() {
        Boolean result = isReEncodedStrictly();
        if (result == null) {
            return false;
        } else {
            return result;
        }
    }

    /**
     * 再エンコードされた動画かを判定する．
     * 不明なときはnullを返す．
     */
    public Boolean isReEncodedStrictly() {
        String id = video.getId();
        VideoDescriptor v = video2Video.get(video);
        if (v == null) {
            return null;
        }
        if (v.isDmc() || v.isLow()) {
            return true;
        }
        if (!Cache.MP4.equals(v.getPostfix())) {
            // mp4以外はdmcが無いはずなのでfalseを返しても良いけど
            return null;
        }

        File file = video2File_get(video);
        long filesize;
        if (file != null && file.exists()) {
            filesize = file.length();
        }
        else {
            file = video2Tmp.get(video);
            if (file == null || !file.exists()) {
                return null;
            }
            MappedRanges mr = getCacheTmpMappedRanges();
            if (!mr.isMapped(0, ReEncodingInfo.REQUIRED_HEADER_LENGTH)) {
                return null;
            }
            filesize = mr.getSize();
        }
        return ReEncodingInfo.check(id, file, filesize);
    }

    /**
     * 削除できるキャッシュの一覧を返す．
     */
    protected VideoDescriptor[] getLesserCachedVideos() {
        VideoDescriptor criterion = this.video;
        ArrayList<VideoDescriptor> result = new ArrayList<>();
        Set<VideoDescriptor> videos = id2Videos.get(criterion.getId());
        if (videos == null) {
            return new VideoDescriptor[0];
        }
        for (VideoDescriptor video : videos) {
            if (criterion.isSuperiorThan(video)) {
                result.add(video);
            }
        }
        boolean useHighBitrateReEncodedCache =
                Boolean.getBoolean("useHighBitrateReEncodedCache");
        if (Boolean.getBoolean("removeReEncodedCache")) {
            if (criterion.isDmc() && !criterion.isLow()
                    && (criterion.getPostfix().equals(Cache.MP4)) || criterion.getPostfix().equals(Cache.HLS)) {
                // 高画質なdmc動画よりビットレートの低い再エンコードされた非dmc動画は悪い
                int criterionBitrate = criterion.getVideoBitrate() + criterion.getAudioBitrate();
                for (VideoDescriptor video : videos) {
                    if (!video.isDmc()) {
                        Cache cache = new Cache(video);
                        if (video.isLow() ||
                                video.getPostfix().equals(Cache.MP4)
                                && cache.exists() && cache.isReEncoded()) {
                            if (useHighBitrateReEncodedCache && criterion.hasBitrate() && !video.isLow()) {
                                int bitrate = ReEncodingInfo.getBitrate(video.getId());
                                // ビットレートが同じ時はdmc優先
                                if (bitrate <= criterionBitrate) {
                                    result.add(video);
                                }
                            } else {
                                result.add(video);
                            }
                        }
                    }
                }
            } else if (!criterion.isDmc() && !criterion.isLow()
                    && criterion.getPostfix().equals(Cache.MP4)) {
                if (!isReEncoded()) {
                    // 再エンコードされていない非dmc動画よりdmc動画は悪い
                    for (VideoDescriptor video : videos) {
                        if (video.isDmc()) {
                            result.add(video);
                        }
                    }
                } else {
                    // 再エンコードされた非dmc動画よりビットレートの低いdmc動画は悪い
                    int criterionBitrate = ReEncodingInfo.getBitrate(criterion.getId());
                    for (VideoDescriptor video : videos) {
                        if (video.isDmc()) {
                            if (useHighBitrateReEncodedCache && video.hasBitrate()) {
                                int bitrate = video.getVideoBitrate() + video.getAudioBitrate();
                                if (bitrate < criterionBitrate) {
                                    result.add(video);
                                }
                            } else {
                                result.add(video);
                            }
                        }
                    }
                }
            }
        }
        return result.toArray(new VideoDescriptor[0]);
    }

    protected boolean hasSuperiorCache() {
        VideoDescriptor criterion = this.video;
        Set<VideoDescriptor> videos = id2Videos.get(criterion.getId());
        if (videos == null) {
            return false;
        }
        for (VideoDescriptor video : videos) {
            if (video.isSuperiorThan(criterion)) {
                File f = video2File_get(video);
                if (f != null && f.exists()) {
                    return true;
                }
            }
        }
        boolean useHighBitrateReEncodedCache =
                Boolean.getBoolean("useHighBitrateReEncodedCache");
        if (Boolean.getBoolean("removeReEncodedCache")) {
            if (!criterion.isDmc() && (criterion.isLow() ||
                    criterion.getPostfix().equals(Cache.MP4)
                    && isReEncoded())) {
                // ビットレートの低い再エンコードされた非dmc動画より高画質なdmc動画が良い
                int criterionBitrate = 0;
                if (!criterion.isLow()) {
                    criterionBitrate = ReEncodingInfo.getBitrate(criterion.getId());
                }
                for (VideoDescriptor video : videos) {
                    if (useHighBitrateReEncodedCache && video.hasBitrate()) {
                        if (video.isDmc()) {
                            Cache cache = new Cache(video);
                            if (cache.exists()) {
                                int bitrate = video.getVideoBitrate() + video.getAudioBitrate();
                                if (criterionBitrate <= bitrate) {
                                    return true;
                                }
                            }
                        }
                    } else {
                        if (video.isDmc() && !video.isLow()) {
                            Cache cache = new Cache(video);
                            if (cache.exists()) {
                                return true;
                            }
                        }
                    }
                }
            } else if (criterion.isDmc() &&
                (criterion.getPostfix().equals(Cache.MP4) || criterion.getPostfix().equals(Cache.HLS))) {
                // dmc動画より再エンコードされていない非dmc動画が良い
                // dmc動画よりビットレートの高いエンコードされた非dmc動画が良い
                int criterionBitrate = criterion.getVideoBitrate() + criterion.getAudioBitrate();
                for (VideoDescriptor video : videos) {
                    if (!video.isDmc() && !video.isLow()
                            && video.getPostfix().equals(Cache.MP4)) {
                        Cache cache = new Cache(video);
                        if (cache.exists()) {
                            if (!cache.isReEncoded()) {
                                return true;
                            } else if (useHighBitrateReEncodedCache && criterion.hasBitrate()) {
                                int bitrate = ReEncodingInfo.getBitrate(video.getId());
                                if (criterionBitrate < bitrate) {
                                    return true;
                                }
                            }
                        }
                    }
                }
            }
        }
        return false;
    }


    private String getPrefixString(boolean withSrcId) {
        return videoDescriptorToPrefixString(video, withSrcId);
    }

    protected static Object getFileMoveLock() {
        return LockObj.FileMoveLock;
    };

    protected static Object FileStoreLock() {
        return LockObj.FileStoreLock;
    };
}
