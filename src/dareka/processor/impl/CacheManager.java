package dareka.processor.impl;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import dareka.Main;
import dareka.common.CloseUtil;
import dareka.common.FileUtil;
import dareka.common.LRUMap;
import dareka.common.Logger;
import dareka.common.M3u8Util;
import dareka.processor.util.FlvParser;
import dareka.processor.util.LocalFlvTemplate;
import dareka.processor.util.Mp4Parser;

class CacheStats {
    public long size;
    public long timestamp;
    public CacheStats(long size, long timestamp) {
        this.size = size;
        this.timestamp = timestamp;
    }
    public CacheStats(File cacheFile) {
        this.size = cacheFile.length();
        this.timestamp = cacheFile.lastModified() / 1000L;
    }
}

class XmlInfo {
    public Document document;
    public long size;
    public int fileCount;
    public XmlInfo(Document doc) {
        document = doc;
        size = 0;
        fileCount = 0;
    }
}

/**
 * [nl] 独自機能を {@linkplain dareka.processor.impl.Cache Cache}
 * クラスに付加するための親クラス。
 * nl固有のクラスではあるが、継承クラスを通して見ることになるので
 * Javadoc の先頭には [nl] プレフィックスを付加する。
 */
public class CacheManager {
    //[xmlfix]
    public static final String VIEW_TEMP = "temp";
    public static final String VIEW_CACHE = "cache";
    public static final String VIEW_ALL = "all";
    public static final String TYPE_DIRLIST = "dirlist";
    public static final String TYPE_TEMPLIST = "templist";
    public static final String TYPE_CACHELIST = "cachelist";
    public static final String TYPE_CACHELIST_ALL = "cachelistall";

    protected static File cacheDir =
            UserDataPaths.configuredFile(
                    System.getProperty("cacheFolder"), "cache");

    protected static ConcurrentHashMap<String, SortedSet<VideoDescriptor>> id2Videos =
            new ConcurrentHashMap<String, SortedSet<VideoDescriptor>>();

    // VideoDescriptorは同値でも異なるデータ(lowなど)を持つことがあるので
    // 正しいlowなどの情報をこれで管理する．
    protected static ConcurrentHashMap<VideoDescriptor, VideoDescriptor> video2Video =
            new ConcurrentHashMap<VideoDescriptor, VideoDescriptor>();

    protected static ConcurrentHashMap<VideoDescriptor, File> video2File =
            new ConcurrentHashMap<VideoDescriptor, File>();

    // 古いAPIとの互換性のために非dmc動画とFileの関係も持っておく
    protected static ConcurrentHashMap<String, File> _id2File =
            new ConcurrentHashMap<String, File>();

    /**
     * [nl] {@link #video2File} の排他制御用ロックとアクセサ
     * <p>
     * 本当は全てのテーブルについて排他制御が必要なんだけど、
     * とりあえず外部からも参照する {@link #video2File} に適用してみる。
     *
     * アクセサは諸々の追加処理についても面倒を見るので、
     * 特に理由がない限りはこちらを使った方が楽で良いです。
     *
     * @see #video2File_get(String)
     * @see #video2File_put(String, File)
     */
    protected static ReentrantReadWriteLock video2FileLock =
            new ReentrantReadWriteLock();

    /**
     * [nl] {@link #video2File} テーブルからキャッシュファイルを取得する。
     * @param video 動画のVideoDescriptor
     * @return キャッシュファイル、存在しなければ null
     */
    protected static File video2File_get(VideoDescriptor video) {
        if (video == null) {
            return null;
        }
        String vid = video.getIdNumber();
        String cid = NLShared.INSTANCE.vid2cid(vid);
        if (!vid.equals(cid)) {
            NicoIdInfoCache.getInstance().putOnlyTypeAndId(
                    video.getIdNumber(), cid);
            String id = video.getType() + cid;
            video = video.replaceId(id);
        }

        File cacheFile = null;
        video2FileLock.readLock().lock();
        try {
            cacheFile = video2File.get(video);
        } finally {
            video2FileLock.readLock().unlock();
        }
        if (cacheFile != null && !cacheFile.exists() &&
                Boolean.getBoolean("checkRealCache")) {
            cacheFile = findCache(video);
            video2File_put(video, cacheFile); // writeLock
        }
        return cacheFile;
    }
    /**
     * videoに対応するキャッシュが実在すればtrue. nltmpは対象外で、store(complete)された
     * キャッシュファイルだけが実在チェック対象.
     * @sine 2025-03-23
     */
    protected static boolean completedCacheExsists(VideoDescriptor video) {
        return null != video2File_get(video);
    };

    /**
     * [nl] {@link #video2File} テーブルにキャッシュファイルを登録する。
     * <b>readLock の中から呼び出すとデッドロックするので注意！</b>
     *
     * @param video 登録する動画の VideoDescriptor
     * @param cacheFile キャッシュファイル、null ならテーブルから削除する
     * @return 以前に登録されていたキャッシュファイル、存在しなければ null
     */
    protected static File video2File_put(VideoDescriptor video, File cacheFile) {
        video2FileLock.writeLock().lock();
        try {
            removeXmlCache(TYPE_CACHELIST, video2dir.get(video));
            removeXmlCache(TYPE_CACHELIST_ALL, null);
            if (cacheFile != null) {
                File dirFile = cacheFile.getParentFile();
                String dir = getPathFromFile(dirFile);
                if (dir != null) {
                    removeXmlCache(TYPE_CACHELIST, dir);
                    video2dir.put(video, dir);
                    if (!dirList.contains(dir)) {
                        dirList.add(dir);
                        dir2File.put(dir, dirFile);
                    }
                    if (!video.isDmc()) {
                        _id2File.put(video.getId() + (video.isLow() ? "low" : ""), cacheFile);
                    }
                    return video2File.put(video, cacheFile);
                }
                return null;
            } else {
                video2dir.remove(video);
                File result = video2File.remove(video);
                unregisterVideoDescriptorIfUnused(video);
                return result;
            }
        } finally {
            video2FileLock.writeLock().unlock();
        }
    }

    // nl上で認識したパス(SP1.13)
    protected static ConcurrentHashMap<VideoDescriptor, String> video2dir =
            new ConcurrentHashMap<>();
    // [nl] 一時ファイルID・ダウンロード中ID
    // インデックス自体の可視性はConcurrentHashMapで保証する。
    // マップ変更とファイル移動を一体で扱う箇所はCache側のロックを使用する。
    protected static ConcurrentHashMap<VideoDescriptor, File> video2Tmp =
            new ConcurrentHashMap<>();
    protected static ConcurrentHashMap<VideoDescriptor, Integer> video2DL =
            new ConcurrentHashMap<>();
    protected static ConcurrentHashMap<VideoDescriptor, Long> video2DLFinalSize =
            new ConcurrentHashMap<>();

    // [nl] ディレクトリリスト(ソートされたpathの集合)
    protected static Set<String> dirList =
            Collections.synchronizedSortedSet(new java.util.TreeSet<>());
    // ディレクトリリストとファイルシステムの対応表(フォルダインポート用)
    protected static Map<String, File> dir2File = new ConcurrentHashMap<>();

    protected static ConcurrentHashMap<VideoDescriptor, CacheStats> statCache =
            new ConcurrentHashMap<>();
    //[xmlfix] XMLキャッシュ
    private static ConcurrentHashMap<String, String> xmlCache =
            new ConcurrentHashMap<>();

    // ローカルで変換済みのmp4をキャッシュするディレクトリ
    protected static File convertedCacheDir =
            UserDataPaths.configuredFile(
                    System.getProperty("convertedCacheFolder"), "cvcache");
    // Flv2Mp4で変換済みのファイル一覧
    protected final static ConcurrentHashMap<VideoDescriptor, File> video2ConvertedMp4 =
            new ConcurrentHashMap<>();

    // 定数・正規表現など
    protected static final String NLTMP = "nltmp";
    protected static final String NLTMP_ = "nltmp_";

    // DMC動画以前の動画キャッシュ形式.
    protected static final Pattern CACHE_FILE_PATTERN =
            Pattern.compile("^(([a-z]{2}\\d{1,9})(low|))(?:_(.*))?(\\.(?:flv|swf|mp4))$");

    // group(1): group(2)から(7)まで.
    // group(2): smid. 英数字動画ID
    // group(3): "low"|"kulow". 最高品質以外にはこれが付く.
    //           kuに関してはChangeLog参照.
    // group(4): []の中その1. video mode. e.g. "1080p", "360p_low", "360p-lowest"
    //           この"_low"はeconomyの意味ではない. DMC動画とdomand(dms)動画で
    //           利用される映像モード名の一部.
    // group(5): []の中その2. video kbps(省略(null)する可能性あり)
    // group(6): []の中その3. audio kbps
    // group(7): []直後. srcId. これは何か？
    // group(8): title
    // group(9): postfix. ドット付き拡張子
    protected static final Pattern DMC_CACHE_FILE_PATTERN =
            Pattern.compile("^(([a-z]{2}\\d{1,9})(low|kulow|)\\[([\\w-]+)(?:,(\\d+))?,(\\d+)\\](\\w*?))(?:_(.*))?(\\.(?:flv|mp4|hls))$");

    // - ニコニコ動画がhlsに完全移行する以前のmp4, flv, swfの部分ダウンロード用パターン.
    // - この時点ではvideo modeにハイフンは入らない.
    protected static final Pattern MAP_FILE_PATTERN =
            Pattern.compile("^\\Q" + NLTMP_ + "\\E(([a-z]{2}\\d{1,9})(low|kulow|)(?:\\[(\\w+)(?:,(\\d+))?,(\\d+)\\](\\w*?))?)(?:_(.*))?(\\.(?:sw_|fl_|mp_))$");
    protected static final Pattern NUMBER_CHARACTER_REFERENCE_PATTERN =
            Pattern.compile("&#(\\d+);");
    protected static final Pattern SMID_PATTERN =
            Pattern.compile("([a-z]{2})(\\d{1,9})(low|)");

    // group(1): smXXX, nmXXX, soXXX, etc...
    // group(2): "" | "low". 最高品質かそうじゃないか.
    //         : 以降省略可.
    // group(3): []の中その1. video mode string.
    // group(4): []の中その2. video kbps. 省略可.
    // group(5): []の中その3. audio kbps.
    // group(6): []後.
    // group(7): "."付き拡張子.
    protected static final Pattern ALTID_PATTERN = Pattern.compile(
        "^([a-z]{2}\\d{1,9})" // smXXX. group(1)
        + "(low|)" // group(2)
        + "(?:" // 省略可用グループ(最後まで).
        + "\\[([\\w-]+)(?:,(\\d+))?,(\\d+)\\]" // [group(3), group(4), group(5)]
        + "(\\w*?)" // srcId_title. group(6)
        + "(\\.(?:swf|flv|mp4|hls|webm|mkv))" // group(7)
        + ")?$");

    // 初期化
    public static synchronized void init() {
        video2FileLock.writeLock().lock();
        try {
            cacheDir = UserDataPaths.configuredFile(
                    System.getProperty("cacheFolder"), "cache");
            cacheDir.mkdir();
            id2Videos.clear();
            video2File.clear();
            video2dir.clear();
            video2Tmp.clear();
            dirList.clear();
            dir2File.clear();
            //[xmlfix]
            xmlCache.clear();

            searchCachesOnADirectory(cacheDir);
            removeUnnecessaryNltmpsWorkaround();

            if (Boolean.getBoolean("convertFlv2Mp4")) {
                convertedCacheDir =
                        UserDataPaths.configuredFile(
                                System.getProperty("convertedCacheFolder"),
                                "cvcache");
                convertedCacheDir.mkdir();
                searchConvertedCachesOnADirectory(convertedCacheDir);
            }
        } finally {
            video2FileLock.writeLock().unlock();
        }
    }

    // キャッシュフォルダ名を取得
    public static String getCacheDir() {
        return cacheDir.getAbsolutePath();
    }

    // キャッシュフォルダを探索
    protected static void searchCachesOnADirectory(File dir) {
        SearchCachesTask task = new SearchCachesTask(dir);
        task.run();
        Logger.debug("SearchCachesTask.max_ntask = " + task.maxNumberOfTask.get());
    }

    private static class SearchCachesTask implements Runnable {

        private final List<Thread> threads = new ArrayList<>();

        private final LinkedBlockingDeque<DirectoryTask> stack =
                new LinkedBlockingDeque<>();
        private final AtomicLong ntask = new AtomicLong(0);

        private final int number_of_threads;

        public SearchCachesTask(File dir) {
            addTask(dir, "", 1, false);
            int cores = Runtime.getRuntime().availableProcessors();
            if (FileUtil.DIR_HAS_FILE_ATTRIBUTES) {
                if (cores >= 4)
                    number_of_threads = 3;
                else if (cores >= 2)
                    number_of_threads = 2;
                else
                    number_of_threads = 1;
            } else {
                if (cores > 2)
                    number_of_threads = 2;
                else
                    number_of_threads = 1;
            }
        }

        public synchronized void run() {
            for (int i = 0; i < number_of_threads; i++) {
                spawn();
            }
            joinAll();
        }

        private void spawn() {
            Thread thread = new Thread(() -> {
                try {
                    while (true) {
                        stack.takeFirst().run();
                        if (ntask.decrementAndGet() == 0) {
                            done();
                        }
                    }
                } catch (InterruptedException e) {
                }
            });
            thread.start();
            threads.add(thread);
        }

        private void done() {
            for (Thread thread : threads) {
                thread.interrupt();
            }
        }

        private void joinAll() {
            for (Thread thread : threads) {
                try {
                    thread.join();
                } catch (InterruptedException e) {
                }
            }
            threads.clear();
        }

        public final AtomicLong maxNumberOfTask = new AtomicLong(0);
        private void addTask(File dir, final String path,
                final int depth, boolean inDotFolder) {
            ntask.incrementAndGet();
            stack.addFirst(new DirectoryTask(dir, path, depth, inDotFolder));
            maxNumberOfTask.getAndAccumulate(ntask.get(), Math::max);
        }

        class DirectoryTask implements Runnable {

            public File dir;
            public String path;
            int depth;
            boolean inDotFolder;

            public DirectoryTask(File dir, final String path,
                    final int depth, boolean inDotFolder) {
                this.dir = dir;
                this.path = path;
                this.depth = depth;
                this.inDotFolder = inDotFolder;
            }

            public void run() {
                // Logger.debugWithThread("[" + ntask + "] " + path);
                // Logger.debugWithThread(ForkJoinPool.commonPool().toString());

                // '#'から始まるパスは無視する
                if (path.startsWith("#") && !inDotFolder) {
                    return;
                }

                if (dirList.contains(path)) {
                    Logger.warning("*** 同名のフォルダがあります(" + path + ")。.folderの名前を変えてください。***");
                }
                dirList.add(path);
                dir2File.put(path, dir);

                Stream<FileUtil.FileWithBasicFileAttributes> entries =
                        FileUtil.getEntriesStream(dir);
                if (!FileUtil.DIR_HAS_FILE_ATTRIBUTES) {
                    entries = entries.parallel();
                }
                entries.forEach(entry -> {
                    if (entry.attrs.isDirectory() && !isHlsCacheDirectory(entry.file)) {
                        String subPath = path + "/" + entry.file.getName();
                        addTask(entry.file, subPath, depth + 1, inDotFolder);
                    } else if (entry.file.getName().endsWith(".folder") && !inDotFolder) {
                        File extDir = parseFolderFile(entry.file);
                        if (extDir != null) {
                            String extName = entry.file.getName().replaceFirst("\\.folder$", "");
                            String subPath = (depth != 1 ? "/" : "") + extName;
                            addTask(extDir, subPath, depth + 1, true);
                        }
                    } else {
                        File file = fixKuLow(entry.file);
                        putCacheFile2Table(file, path, depth);
                    }
                });
            }
        }
    }

    // .folderファイルをパースしてディレクトリならFileオブジェクトを返す
    private static File parseFolderFile(File folderFile) {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(folderFile));
            File d = new File(br.readLine());
            if (d.isDirectory()) return d;
        } catch (IOException e) {
            Logger.error(e);
        } finally {
            CloseUtil.close(br);
        }
        return null;
    }

    //[xmlfix]
    protected static void putCacheFile2Table(File file, String dir, int depth) {
        String filename = file.getName();
        VideoDescriptor vd;
        boolean tmp;
        boolean mapfile;
        if (!filename.startsWith(NLTMP_)) {
            vd = getVideoDescriptorFromFilename(filename);
            tmp = false;
            mapfile = false;
        } else {
            vd = getVideoDescriptorFromFilename(filename.substring(6));
            tmp = true;
            mapfile = filename.charAt(filename.length() - 1) == '_';
        }
        if (vd != null) {
            if (depth == 1 && tmp) {
                video2Tmp.put(vd, file);
                Logger.debug("partial cache found: " + videoDescriptorToPrefixString(vd, true) + " => "
                        + file.getPath());
            } else {
                video2File.put(vd, file);
                video2dir.put(vd, dir);
                if (!vd.isDmc()) {
                    _id2File.put(vd.getId() + (vd.isLow() ? "low" : ""), file);
                }
            }
            registerVideoDescriptor(vd);
        } else if (mapfile) {
            // WORKAROUND: remove orphan mapfiles
            Matcher m = MAP_FILE_PATTERN.matcher(filename);
            if (m.matches()) {
                String suffix = null;
                String mapsuffix = filename.substring(filename.length() - 4);
                switch (mapsuffix) {
                case ".fl_":
                    suffix = Cache.FLV; break;
                case ".mp_":
                    suffix = Cache.MP4; break;
                case ".sw_":
                    suffix = Cache.SWF; break;
                }
                if (suffix != null) {
                    String nltmpFilename = filename.substring(0, filename.length() - 4) + suffix;
                    if (!new File(file.getParentFile(), nltmpFilename).exists()) {
                        Logger.info("*** Removing orphan mapfile: " + file.getPath());
                        file.delete();
                    }
                }
            }
        }
    }

    private static final Object findCacheLock = new Object();

    // 指定されたVideoDescriptorのキャッシュをファイルシステムから探索する
    private static File findCache(VideoDescriptor video) {
        // 同じフォルダにあったキャッシュの探索が並列でやってきた場合
        // lruCacheFoundDirsが効かずに効率が悪いので逐次化する
        synchronized (findCacheLock) {
            return findCacheCore(video);
        }
    }

    private static File findCacheCore(VideoDescriptor video) {
        File oldFile = video2File.get(video);
        if (oldFile != null) {
            // 最近見つけたディレクトリに同じファイル名であるか．
            String name = oldFile.getName();
            synchronized (lruCacheFoundDirs) {
                for (File dir : lruCacheFoundDirs) {
                    File file = new File(dir, name);
                    if (file.exists()) {
                        findCacheFound(dir, name);
                        return file;
                    }
                }
            }
            // 同じディレクトリ内に別のファイル名であるか．
            // ただし直下の場合はファイル数が多そうなのでスキャンしない
            File oldDir = oldFile.getParentFile();
            if (!cacheDir.equals(oldDir)) {
                File file = findCacheScanInDir(oldDir, video, false);
                if (file != null) {
                    return file;
                }
            }
            // 既知のディレクトリに同じファイル名であるか．
            // 一つのディレクトリのエントリを全て取得するよりも，
            // 多数のディレクトリに存在判定を行うほうがコストが高そうなのでこの順序
            for (File dir : dir2File.values()) {
                File file = new File(dir, name);
                if (file.exists()) {
                    findCacheFound(dir, name);
                    return file;
                }
            }
        }
        // 知らない場所にあるのでフルスキャンする
        return findCacheScanInDir(cacheDir, video, true);
    }

    private static File findCacheScanInDir(File dir, VideoDescriptor video, boolean rec) {
        File[] files = dir.listFiles();
        if (files == null) {
            // dirが存在しない，あるいはディレクトリではない場合など
            return null;
        }
        for (File file : files) {
            if (file.isFile() || isHlsCacheDirectory(file)) {
                String name = file.getName();
                if (name.startsWith(video.getId())) {
                    VideoDescriptor filevd = getVideoDescriptorFromFilename(name);
                    if (filevd != null && filevd.equals(video)) {
                        findCacheFound(dir, name);
                        return file;
                    }
                }
                if (rec && name.endsWith(".folder")) {
                    file = parseFolderFile(file);
                }
            }
            if (rec && file != null && file.isDirectory() && !isHlsCacheDirectory(file)) {
                File found = findCacheScanInDir(file, video, true);
                if(found != null) return found;
            }
        }
        return null;
    }

    private static final LinkedList<File> lruCacheFoundDirs = new LinkedList<File>();

    private static void findCacheFound(File dir, String name) {
        Logger.info("moved cache found: " + name);
        synchronized (lruCacheFoundDirs) {
            if (lruCacheFoundDirs.size() >= 5) {
                if (!lruCacheFoundDirs.remove(dir)) {
                    lruCacheFoundDirs.removeLast();
                }
            }
            lruCacheFoundDirs.addFirst(dir);
        }
    }


    protected static void searchConvertedCachesOnADirectory(File dir) {
        FileUtil.getEntriesStream(dir).forEach(entry -> {
            if (entry.attrs.isDirectory()) {
                searchConvertedCachesOnADirectory(entry.file);
            } else {
                String filename = entry.file.getName();
                VideoDescriptor vd = getVideoDescriptorFromFilename(filename);
                if (vd != null) {
                    video2ConvertedMp4.put(vd, entry.file);
                }
            }
        });
    }

    protected static File video2ConvertedMp4_get(VideoDescriptor video) {
        if (video == null) {
            return null;
        }
        String vid = video.getIdNumber();
        String cid = NLShared.INSTANCE.vid2cid(vid);
        if (!vid.equals(cid)) {
            NicoIdInfoCache.getInstance().putOnlyTypeAndId(
                    video.getIdNumber(), cid);
            String id = video.getType() + cid;
            video = video.replaceId(id);
        }

        File cacheFile = video2ConvertedMp4.get(video);
        if (cacheFile != null && !cacheFile.exists()) {
            video2ConvertedMp4.remove(video);
            return null;
        }
        return cacheFile;
    }

    // WORKAROUND: 不要な一時キャッシュを削除する
    private static void removeUnnecessaryNltmpsWorkaround() {
        LinkedList<Cache> trash = new LinkedList<>();
        for (VideoDescriptor video : video2Tmp.keySet()) {
            Cache cache = new Cache(video);
            if (video2File.containsKey(video.stripSrcId())
                    || cache.hasSuperiorCache()) {
                trash.add(cache);
            }
        }
        for (Cache cache : trash) {
            try {
                Logger.info("*** Removing unnecessary nltmp: " + cache.getCacheTmpFile().getPath());
                cache.deleteTmp();
            } catch (IOException e) {
                Logger.error(e);
            }
        }
    }

    private static File fixKuLow(File file) {
        String filename = file.getName();
        Matcher m = DMC_CACHE_FILE_PATTERN.matcher(filename);
        if (!m.matches()) {
            return file;
        }

        String low = m.group(3);
        if (!low.equals("kulow")) {
            return file;
        }
        String postfix = m.group(9);
        String smid = m.group(2);
        String videoType = smid.substring(0, 2);
        int videoIdNumber = Integer.parseInt(smid.substring(2));
        String videoMode = m.group(4);
        int videoBitrate = m.group(5) != null ? Integer.parseInt(m.group(5)) : 0;

        boolean toLow = false, toHigh = false;
        long duration;
        if (postfix.equals(Cache.MP4)) {
            Mp4Parser mp4 = new Mp4Parser(file);
            if (mp4.isInvalid()) {
                return file;
            }

            duration = mp4.getDurationInSeconds();
        } else if (postfix.equals(Cache.FLV)) {
            FlvParser flv = new FlvParser(file);
            if (flv.isInvalid()) {
                return file;
            }

            duration = flv.getDurationInSeconds();
        } else {
            return file;
        }

        if ("so".equals(videoType)) {
            toHigh = true;
        } else if ("sm".equals(videoType)) {
            if (videoIdNumber < 31090000) {
                // だいたい2017/04/25より前(生成ルール変更前)
                // nop
            } else if (videoIdNumber < 32380000) {
                // だいたい2017/12/07より前(1080p導入前)
                if (videoBitrate == 1000 && videoMode.equals("540p")
                        && 16*60 <= duration && duration < 31*60) {
                    // だいたい2017/04/25より後(生成ルール変更)
                    // だいたい2017/05/17より前(生成ルール適用)
                    // 16分以上, 30分以下で
                    //   {1000,2000}kbps_720p
                    // が生成されていた．この画質は公開された．
                    // 2017/12/07で判定しているのは安全側に倒した……のだったはず
                    toLow = true;
                } else if (videoIdNumber < 31230000
                        && videoBitrate == 600 && videoMode.equals("360p")
                        && 31*60 <= duration && duration < 61*60) {
                    // だいたい2017/04/25より後(生成ルール変更)
                    // だいたい2017/05/17より前(生成ルール適用)
                    // 31分以上, 60分以下で
                    //   {700,800,900,1000}kbps_{360p,480p,540p}
                    // が生成されていた．これらの画質は長らく
                    // unavailable状態であったが2021/03/15までに削除された．
                    // よって 600kbps_360p が上限画質である．
                    toHigh = true;
                } else {
                    toHigh = true;
                }
            } else {
                // nop
            }
        }

        if (toLow) {
            String newFilename = filename.replaceFirst("kulow", "low");
            File newFile = new File(file.getParentFile(), newFilename);
            if (!file.renameTo(newFile)) {
                return file;
            }
            Logger.info("[kulow->low] %s", getPathFromFile(file));
            return newFile;
        } else if (toHigh) {
            String newFilename = filename.replaceFirst("kulow", "");
            File newFile = new File(file.getParentFile(), newFilename);
            if (!file.renameTo(newFile)) {
                return file;
            }
            Logger.info("[kulow->high] %s", getPathFromFile(file));
            return newFile;
        }
        return file;
    }

    public static boolean isHlsCacheDirectory(File file) {
        return file.isDirectory() && file.getName().endsWith(Cache.HLS);
    }


    /**
     * [nl] 数字のみの ID から、存在するキャッシュの smid 文字列を返す
     * （ID が10桁以上の場合はスレッド ID として扱う）
     *
     * @param id 取得対象の ID 文字列
     * @return キャッシュが存在するならその smid 文字列、存在しなければ null
     */
    public static String id2Smid(String id) {
        if (id == null || !id.matches("\\d+")) {
            return null;
        }
        String smid;
        if (id.length() >= 10) { // threadId
            if (isCached(smid = NLShared.INSTANCE.thread2smid(id))) {
                return smid;
            }
        } else if (id.length() >= 8) {
            // VideoDescriptorキーは同じIDの品質・形式違いを区別するため維持する。
            id = NLShared.INSTANCE.vid2cid(id);
            if (isCached(smid = "sm".concat(id)) ||
                    isCached(smid = "so".concat(id)) ||
                    isCached(smid = "nm".concat(id))) {
                return smid;
            }
        } else {
            id = NLShared.INSTANCE.vid2cid(id);
            String types = "sm,nm,so,za,zb,co,ax,ca,cw,fz,na,yo";
            String[] r = EasyRewriter.getReplace("nlMainConf");
            if (r != null && r.length > 2) {
                types = r[2];
            }
            for (String s : types.split(",")) {
                if (isCached(smid = s + id)) {
                    return smid;
                }
            }
        }
        return null;
    }

    /**
     * [nl] smid 文字列(=英字2文字＋9桁以下の数字)か？(末尾に"low"が付くものを含む)
     *
     * @param smid 判定する文字列 (nullも可)
     * @return smid 文字列なら true
     * @since NicoCache_nl+111111mod
     */
    public static boolean isSmid(String smid) {
        return smid != null && SMID_PATTERN.matcher(smid).matches();
    }

    /**
     * [nl] smid がエコノミーキャッシュ文字列か？
     *
     * @param smid 判定する文字列 (nullも可)
     * @return エコノミーキャッシュ文字列なら true
     * @since NicoCache_nl+111111mod
     */
    public static boolean isLowSmid(String smid) {
        if (smid != null) {
            Matcher m = SMID_PATTERN.matcher(smid);
            return m.matches() && m.group(3).length() > 0;
        }
        return false;
    }

    /**
     * [nl] smid 文字列からエコノミーキャッシュ部分を取り除いて返す(=動画ID)
     *
     * @param smid 対象の文字列 (nullも可)
     * @return 動画ID、smid 文字列では無い場合は null
     */
    public static String stripLow(String smid) {
        if (smid != null) {
            Matcher m = SMID_PATTERN.matcher(smid);
            if (m.matches()) {
                if (m.group(3).length() > 0) {
                    return m.group(1) + m.group(2);
                }
                return smid;
            }
        }
        return null;
    }

    /**
     * [nl] 文字列をパースして、smid なら <b>[normal, economy]</b>
     * の順番で格納した文字列の配列を返す
     *
     * @param smid パース対象の文字列
     * @return smid を格納した配列、smid では無い場合は null
     * @since NicoCache_nl+111111mod
     */
    @Deprecated
    public static String[] parseSmid(String smid) {
        String[] result = null;
        if (smid != null) {
            Matcher m = SMID_PATTERN.matcher(smid);
            if (m.matches()) {
                result = new String[2];
                result[0] = m.group(1) + m.group(2);
                result[1] = m.group(1) + m.group(2) + "low";
            }
        }
        return result;
    }

    /**
     * [nl] 文字列をパースして smid なら数字部分を返す
     *
     * @param smid パース対象の文字列
     * @return smid の数字部分、文字列が smid では無い場合は 0
     * @since NicoCache_nl+111111mod
     */
    public static int parseSmidInt(String smid) {
        if (smid != null) {
            Matcher m = SMID_PATTERN.matcher(smid);
            if (m.matches()) {
                return Integer.parseInt(m.group(2));
            }
        }
        return 0;
    }

    /**
     * [nl] 2つの smid 文字列を比較するコンパレータを取得する
     *
     * @param descending 降順(DESC)に比較するならtrue、昇順(ASC)ならfalse
     * @return 生成したコンパレータ
     * @since NicoCache_nl+111111mod
     */
    public static Comparator<String> getSmidComparator(boolean descending) {
        final boolean desc = descending;
        return (o1, o2) -> {
            int n1 = parseSmidInt(o1);
            int n2 = parseSmidInt(o2);
            return desc ? n2 - n1 : n1 - n2;
        };
    }

    /**
     * 2つの VideoDescriptor を比較するコンパレータを取得する
     *
     * @param descending 降順(DESC)に比較するならtrue、昇順(ASC)ならfalse
     * @return 生成したコンパレータ
     */
    public static Comparator<VideoDescriptor> getVideoDescriptorComparator(boolean descending) {
        final boolean desc = descending;
        return (o1, o2) -> desc ? o1.compareTo(o2) : o2.compareTo(o1);
    }

    /**
     * VideoDescriptorからキャッシュファイルのprefix(_より前の部分)を得る．
     * @param video 登録するVideoDescriptor
     * @param withSrcId srcIdをつけるか
     * @return prefix
     */
    protected static String videoDescriptorToPrefixString(VideoDescriptor video, boolean withSrcId) {
        if (video.isDmc()) {
            return video.getId() + (video.isLow() ? "low" : "")
                    + "[" + video.getVideoMode() +
                    (video.getVideoBitrate() == 0 ? "" : "," + video.getVideoBitrate())
                    + "," + video.getAudioBitrate() + "]"
                    + (withSrcId ? video.getShortenSrcId() : "");
        } else {
            return video.getId() + (video.isLow() ? "low" : "");
        }
    }

    /**
     * VideoDescriptorからキャッシュファイルを一意に特定できる代替IDを得る．
     * 代替IDは非dmc動画に対してはsmidと互換である．
     */
    protected static String videoDescriptorToAltId(VideoDescriptor video) {
        if (!video.isDmc()) {
            return videoDescriptorToPrefixString(video, true);
        } else {
            return videoDescriptorToPrefixString(video, true) + video.getPostfix();
        }
    }

    /**
     * AltIDからVideoDescriptorに変換する．
     * @param altId
     *   非dmc表現として許容されるのはsmidとlowありなしだけでそれ以外を含めてはならない.
     * @param postfix null可. altIdが非dmc表現の時に適用する拡張子
     * @return
     */
    public static VideoDescriptor altIdToVideoDescriptor(String altId, String postfix) {
        Matcher m = ALTID_PATTERN.matcher(altId);
        if (m.matches()) {
            if (m.group(3) != null) {
                // dmc
                int videoBitrate = m.group(4) != null ? Integer.parseInt(m.group(4)) : 0;
                int audioBitrate = Integer.parseInt(m.group(5));
                return VideoDescriptor.newDmc(m.group(1), m.group(7),
                        m.group(2).equals("low"), m.group(3),
                        videoBitrate, audioBitrate, m.group(6));
            } else {
                // classic
                return VideoDescriptor.newClassic(m.group(1), postfix,
                        m.group(2).equals("low"));
            }
        }
        return null;
    }
    public static VideoDescriptor altIdToVideoDescriptor(String altId) {
        return altIdToVideoDescriptor(altId, null);
    }


    /**
     * VideoDescriptorをid2Videosに登録する．
     */
    protected static void registerVideoDescriptor(VideoDescriptor video) {
        synchronized (id2Videos) {
            Set<VideoDescriptor> videos = id2Videos.get(video.getId());
            if (videos == null) {
                id2Videos.putIfAbsent(video.getId(), new ConcurrentSkipListSet<>());
                videos = id2Videos.get(video.getId());
            }
            videos.add(video);
            video2Video.put(video, video);
        }
    }

    protected static void unregisterVideoDescriptorIfUnused(VideoDescriptor video) {
        Set<VideoDescriptor> videos = id2Videos.get(video.getId());
        if (videos == null) {
            return;
        }
        synchronized (id2Videos) {
            if (video2File.containsKey(video) || video2Tmp.containsKey(video)) {
                return;
            }
            videos.remove(video);
            video2Video.remove(video);
            if (videos.isEmpty()) {
                id2Videos.remove(video.getId());
            }
        }
    }

    // 同一情報・同一インスタンスのためのメソッド.
    protected static VideoDescriptor getRegisteredVideoDescriptor(VideoDescriptor video) {
        return video2Video.get(video);
    }

    private static boolean isCached(String id) {
        if (id == null) {
            return false;
        }
        Set<VideoDescriptor> videos = id2Videos.get(id);
        if (videos == null) {
            return false;
        }
        for (VideoDescriptor video : videos) {
            if (video2File.containsKey(video)) {
                return true;
            }
        }
        return false;
    }

    // キャッシュの合計サイズを返す
    public static long size() {
        long sum = 0;

        for (File file : video2File.values()) {
            sum += file.length();
        }

        return sum;
    }

    /**
     * HLS一時キャッシュ内の完成済みセグメントだけを合計する。
     *
     * @param nltmpDir HLS一時キャッシュのルート
     * @param cachedSegments 既に保存完了したセグメントの相対パス
     * @return 完成済みセグメントの合計バイト数
     */
    static long getCachedSegmentBytes(File nltmpDir, Set<String> cachedSegments) {
        if (nltmpDir == null || cachedSegments == null) {
            return 0;
        }
        long total = 0;
        for (String segment : cachedSegments) {
            if (segment == null) {
                continue;
            }
            File segmentFile = new File(nltmpDir, segment);
            if (segmentFile.isFile()) {
                total += segmentFile.length();
            }
        }
        return total;
    }

    // ID/Fileマップを返す
    // 互換性のために非dmcキャッシュだけのmapを返す
    @Deprecated
    public static Map<String, File> getId2File() {
        return Collections.unmodifiableMap(_id2File);
    }

    // - smidに紐付けられたVideoDescriptorのSetを返す.
    // - 紐付いた情報がない場合は空Setを返す.
    // - nltmpもcompleteした状態もファイルを持たないものも返る.
    public static Set<VideoDescriptor> getVideos(String smid) {
        Set<VideoDescriptor> vlist = id2Videos.get(smid);
        if (vlist == null) {
            return Collections.emptySet();
        };
        return Collections.unmodifiableSet(vlist);
    }

    // Video/Fileマップを返す
    public static Map<VideoDescriptor, File> getVideo2File() {
        return Collections.unmodifiableMap(video2File);
    }

    // ファイル名からVideoDescriptorを得る
    protected static VideoDescriptor getVideoDescriptorFromFilename(String filename) {
        Matcher m = CACHE_FILE_PATTERN.matcher(filename);
        if (m.find()) {
            return VideoDescriptor.newClassic(m.group(2), m.group(5), m.group(3).equals("low"));
        }
        m = DMC_CACHE_FILE_PATTERN.matcher(filename);
        if (m.find()) {
            try {
                int videoBitrate = m.group(5) != null ? Integer.parseInt(m.group(5)) : 0;
                int audioBitrate = Integer.parseInt(m.group(6));
                return VideoDescriptor.newDmc(m.group(2), m.group(9), m.group(3).equals("low"),
                        m.group(4), videoBitrate, audioBitrate, m.group(7));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    // ファイル名からタイトルを得る
    protected static String getTitleFromFilename(String filename) {
        if (filename.startsWith(NLTMP_)) {
            filename = filename.substring(6);
        }
        Matcher m = CACHE_FILE_PATTERN.matcher(filename);
        if (m.find()) {
            return m.group(4);
        }
        m = DMC_CACHE_FILE_PATTERN.matcher(filename);
        if (m.find()) {
            try {
                return m.group(8);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * [nl] smid から nl 上で認識されたフォルダ文字列(ファイル名は含まない)を返す
     * 非dmc動画のみに対応している
     *
     * @param smid 対象の smid 文字列
     * @return フォルダ文字列、キャッシュが存在しない場合は null
     */
    @Deprecated
    public static String getPathFromId(String smid) {
        return getPathFromVideoDescriptor(altIdToVideoDescriptor(smid));
    }

    /**
     * [nl] VideoDescriptor から nl 上で認識されたフォルダ文字列(ファイル名は含まない)を返す
     *
     * @param video 対象の動画の VideoDescriptor
     * @return フォルダ文字列、キャッシュが存在しない場合は null
     */
    public static String getPathFromVideoDescriptor(VideoDescriptor video) {
        if (video == null) {
            return null;
        }
        return video2dir.get(video);
    }

    /**
     * [nl] キャッシュファイルから nl 上で認識する path 文字列を返す
     *
     * @param cacheFile キャッシュファイル
     * @return path文字列、キャッシュが存在しない場合はnull
     */
    public static String getPathFromFile(File cacheFile) {
        String path = null;
        String fap = cacheFile.getAbsolutePath(), cap = getCacheDir();
        if (fap.startsWith(cap)) {
            path = fap.substring(cap.length());
        } else {
            // インポートフォルダから検索
            for (Map.Entry<String, File> e : dir2File.entrySet()) {
                String dap = e.getValue().getAbsolutePath();
                if (fap.startsWith(dap)) {
                    path = fap.replace(dap, e.getKey());
                }
            }
        }
        if (path != null) {
            path = path.replace(File.separatorChar, '/').replaceFirst("^/", "");
            if (dirList.contains(path)) {
                // dirListに含まれるなら、そちらのオブジェクトを返す
                // id2pathに登録する場合に同一オブジェクトの方が省メモリ
                for (String dir : dirList) {
                    if(dir.equals(path)) return dir;
                }
            }
        } else {
            Logger.debug("getPathFromFile: cannot get a path from " + fap);
        }
        return path;
    }

    // キャッシュフォルダから見たサブフォルダ名を返す
    @Deprecated
    public static String getDirName(File dir)
    {
        String dirname = dir.getPath().substring(cacheDir.getPath().length());
        dirname = dirname.replace(File.separatorChar, '/').replaceFirst("^/", "");
        return dirname;
    }

    /**
     * [nl] 指定した動画IDのキャッシュが通常かエコノミーかを判定（無ければnull）
     * 非dmc動画のみが対象
     * 代替としてgetPreferredCachedVideo(id)が利用できる
     *
     * @param id 動画ID (末尾に"low"が付くものは含まない)
     * @return 通常キャッシュなら ""(空文字列)、エコノミーキャッシュなら "low"、
     * キャッシュが存在しないなら null
     */
    @Deprecated
    public static String getType(String id) {
        VideoDescriptor video = getPreferredCachedVideo(id, false, null);
        if (video == null) {
            return null;
        }
        return video.isLow() ? "low" : "";
    }

    /**
     * [nl] ダウンロード中フラグをゲット
     * 非dmc動画用
     * @param id
     * @return ダウンロード中ならtrue
     */
    @Deprecated
    public static boolean getDLFlag(String id) {
        return getDLFlag(altIdToVideoDescriptor(id));
    }

    /**
     * [nl] ダウンロード中フラグをゲット
     * @param video
     * @return ダウンロード中ならtrue
     */
    public static boolean getDLFlag(VideoDescriptor video) {
        if (video == null) {
            return false;
        }
        Integer c = video2DL.get(video);
        // containsKey()をしてからget()すると
        // 途中で他のスレッドに割り込まれる可能性がある。
        // ConcurrentHashMapは値にnullを持たないので
        // get()の後でnullチェックすることで
        // 登録されているかどうかを確認する。
        // ただしこれだけでは不十分

        if (c != null && c > 0) {
            return true;
        } else {
            return false;
        }
    }

    /**
     * [nl] ダウンロード中フラグをセット
     * @param id
     * @param size
     */
    @Deprecated
    public static void setDLFlag(String id, int size) {
        VideoDescriptor video = altIdToVideoDescriptor(id);
        if (size >= 0) {
            incrementDL(video);
        } else {
            decrementDL(video);
        }
    }

    /**
     * [nl] ダウンロード中の数
     */
    public static int getDLFlagCount() {
        return video2DL.size();
    }

    /**
     * [nl] ダウンロード中の取得済みサイズ
     * [HTML5] id2DLではなくmapファイルから取得するようになった
     * 非dmc動画のみ対応
     */
    @Deprecated
    public static long getDLSize(String id) {
        return getDLSize(altIdToVideoDescriptor(id));
    }

    /**
     * [nl] ダウンロード中の取得済みサイズ
     * [HTML5] id2DLではなくmapファイルから取得するようになった
     */
    public static long getDLSize(VideoDescriptor video) {
        if (video == null) {
            return 0;
        }
        Integer c = video2DL.get(video);
        if (c != null) {
            Cache cache = new Cache(video);
            MappedRanges mr = cache.getCacheTmpMappedRanges();
            if (mr == null) {
                return 1; // Error
            }
            return mr.getMappedSize();
        } else {
            return 0;
        }
    }

    /**
     * [HTML5] 動画に対する同時実行中のDLセッション数
     * 非dmc動画にのみ対応
     */
    @Deprecated
    public static int getDLCount(String id) {
        return getDLCount(altIdToVideoDescriptor(id));
    }

    /**
     * [HTML5] 動画に対する同時実行中のDLセッション数
     */
    public static int getDLCount(VideoDescriptor video) {
        if (video == null) {
            return 0;
        }
        Integer c = video2DL.get(video);

        if (c != null) {
            return c;
        } else {
            return 0;
        }
    }

    /**
     * [HTML5] DLセッション数を増やす
     * @return 変更後のDLセッション数
     */
    public static synchronized int incrementDL(VideoDescriptor video) {
        Logger.debug("incrementDL: " + video);
        Integer c = video2DL.get(video);
        if (c == null) {
            c = 0;
        }
        c++;
        video2DL.put(video, c);
        return c;
    }

    /**
     * [HTML5] DLセッション数を減らす
     * @return 変更後のDLセッション数
     */
    public static synchronized int decrementDL(VideoDescriptor video) {
        Logger.debug("decrementDL: " + video);
        Integer c = video2DL.get(video);
        if (c == null || c <= 0) {
            Logger.warning("decrementDL is called in spite of no DL session.");
            return 0;
        }
        c--;
        if (c != 0) {
            video2DL.put(video, c);
        } else {
            video2DL.remove(video);
            video2DLFinalSize.remove(video);
        }
        return c;
    }

    /**
     * [nl] ダウンロード中のファイルの最終サイズ設定
     * 非dmc動画のみ対応
     */
    @Deprecated
    public static void setDLFinalSize(String id, int size) {
        VideoDescriptor video = altIdToVideoDescriptor(id);
        setDLFinalSize(video, size);
    }

    /**
     * [nl] ダウンロード中のファイルの最終サイズ設定
     */
    @Deprecated
    public static void setDLFinalSize(VideoDescriptor video, int size) {
        if (size >= 0) {
            video2DLFinalSize.put(video, (long)size);
        } else {
            video2DL.remove(video);
            video2DLFinalSize.remove(video);
        }
    }

    /**
     * [nl] ダウンロード中のファイルの最終サイズ設定
     */
    public static void setDLFinalSize(VideoDescriptor video, long size) {
        if (size >= 0) {
            video2DLFinalSize.put(video, size);
        } else {
            video2DL.remove(video);
            video2DLFinalSize.remove(video);
        }
    }

    /**
     * [nl] ダウンロード中のファイルの最終サイズ取得
     * 非dmc動画のみ対応
     */
    @Deprecated
    public static long getDLFinalSize(String id) {
        return getDLFinalSize(altIdToVideoDescriptor(id));
    }

    /**
     * [nl] ダウンロード中のファイルの最終サイズ取得
     */
    public static long getDLFinalSize(VideoDescriptor video) {
        if (video == null) {
            return 0;
        }
        Long fin = video2DLFinalSize.get(video);

        if (fin != null && fin >= 0) {
            return (long)fin;
        } else {
            return 0;
        }
    }

    /**
     * 最も良いキャッシュを返す．
     *
     * @param id
     * @return 最も適したキャッシュ済みのVideoDescriptor
     * キャッシュ済みの動画が存在しない場合はnullを返す
     */
    public static VideoDescriptor getPreferredCachedVideo(String id) {
        VideoDescriptor videoSmile =
                Cache.getPreferredCachedVideo(id, false, null);

        // 1. 再エンコードされていないsmile
        if (Boolean.getBoolean("useNotReEncodedCache") &&
                videoSmile != null && !videoSmile.isLow()) {
            Cache cache = new Cache(videoSmile);
            Boolean reencoded = cache.isReEncodedStrictly();
            if (reencoded != null && !reencoded) {
                return videoSmile;
            }
        }

        // 2. dmc HLS
        VideoDescriptor videoDmcHls = Cache.getPreferredCachedVideo(id, true, Cache.HLS);
        if (videoDmcHls != null) {
            return videoDmcHls;
        }

        // 3. dmc mp4
        VideoDescriptor videoDmc = Cache.getPreferredCachedVideo(id, true, Cache.MP4);
        if (videoDmc != null) {
            return videoDmc;
        }

        // 4. smile
        if (videoSmile != null) {
            return videoSmile;
        }

        return null;
    }

    /**
     * (dmc, postfix)のもとで最も良いキャッシュのVideoDescriptorを返す．
     * dmcがfalseのとき，postfixはnullで良い．
     * 非nullが返った時はキャッシュが存在することが保証される．
     *
     * @param id
     * @param dmc
     * @param postfix
     * @return 最も適したキャッシュ済みのVideoDescriptor
     * キャッシュ済みの動画が存在しない場合はnullを返す
     */
    public static VideoDescriptor getPreferredCachedVideo(String id, boolean dmc, String postfix) {
        Set<VideoDescriptor> videos = id2Videos.get(id);
        if (videos == null) {
            return null;
        }
        VideoDescriptor preferred = null;
        for (VideoDescriptor video : videos) {
            if (video.isPreferredThan(preferred, dmc, postfix)) {
                File file = video2File_get(video);
                if (file == null) {
                    continue;
                }
                preferred = video;
            }
        }
        return preferred;
    }

    /**
     * smXXX[YYY,ZZZ]_title.hlsのZZZ部分で検索する.
     */
    public static VideoDescriptor getCachedHlsVideoByAudioKbps
    (String smid, int audioKbps) {
        Set<VideoDescriptor> videos = id2Videos.get(smid);
        if (videos == null) {
            return null;
        };
        for (VideoDescriptor video : videos) {
            if (video.getAudioBitrate() == audioKbps) {
                File file = video2File_get(video);
                if (file != null) {
                    return video;
                };
            };
        };
        return null;
    }


    protected static class HlsTmpSegments {
        private static Map<VideoDescriptor, HlsTmpSegments> video2HlsTmpSegments = new LRUMap<>(20);
        private Set<String> playlists = new HashSet<>();
        private Set<String> playlistSegments = new HashSet<>();
        private Set<String> cachedSegments = new HashSet<>();
        private VideoDescriptor video = null;

        // - マスタープレイリストが存在しない場合や、一つ以上のサブプレイリスト
        //   が存在しない場合にtrue.
        // - 定義上は上記だが、マスタープレイリストが存在しないインスタンスを
        //   作成することはない.
        private boolean playlistMissing = false;
        private Set<String> missingPlaylists = new HashSet<>();

        // get-or-createの既存名はExtension ABI互換のため維持する。
        // - master.m3u8を読み直したい時はこれを呼ぶ前にforgetを呼ぶこと.
        public static synchronized HlsTmpSegments get(VideoDescriptor video) {
            HlsTmpSegments instance = video2HlsTmpSegments.get(video);
            if (instance != null) {
                return instance;
            };
            instance = create(video);
            if (instance == null) {
                return null;
            };
            video2HlsTmpSegments.put(video, instance);
            return instance;
        };

        private static HlsTmpSegments create(VideoDescriptor video) {

            File dir = video2Tmp.get(video);

            if (dir == null) {
                // ファイルが削除されたか移動された時にここに来る. 要検証.
                // Logger.info("HlsTmpSegments: error: dir==null: " + video);
                return null;
            };

            if (!dir.exists()) {
                // Logger.info("--- !dir.exists()");
                return null;
            };

            if (!(new File(dir, "master.m3u8").exists())) {
                // Logger.info("--- master.m3u8 not found");
                return null;
            };

            HlsTmpSegments instance = new HlsTmpSegments();
            instance.video = video;
            // ループ用.
            ArrayList<String> playlists = new ArrayList<>();
            instance.playlists.add("master.m3u8");
            playlists.add("master.m3u8");
            URI dirURI = dir.toURI();

            // - instance.{playlists|playlistSegments|cachedSegments}を構築する.
            // - 上記master.m3u8をルートとしてその子達を辿っていく.
            // - 子がm3u8ならばplaylistsに追加して、それもさらに辿る.
            // - m3u8以外ならばsegment(動画部分)として追加.
            // - 追加する際にはそれらはdirからの相対パスでなければならない.
            //   - この要件は要再検討. 絶対パスの方が扱いやすい.
            for ( int playlistIndex = 0;
                  // playlistsは増えるから毎回sizeを取る.
                  playlistIndex < playlists.size();
                  ++playlistIndex) {

                // 個別キャッシュ(smXXX...hls/)ディレクトリからの相対パス.
                String playlist = playlists.get(playlistIndex);
                // javaプロセスからの相対パス.
                File playlistRel = new File(dir, playlist);
                File playlistDir = playlistRel.getParentFile();
                byte[] bytes;
                try {
                    bytes = Files.readAllBytes(Paths.get(playlistRel.getPath()));
                } catch (IOException e) {
                    instance.playlistMissing = true;
                    instance.missingPlaylists.add(playlist);
                    continue;
                };
                String content = new String(bytes, StandardCharsets.UTF_8);
                content = M3u8Util.fix20250325DoubleAudio(content, playlistRel);
                ArrayList<String> urlList = M3u8Util.getUrlList(content);

                for (String rawurl : urlList) {
                    File file = new File(playlistDir, rawurl);
                    // 個別キャッシュディレクトリからの相対パス.
                    String path = dirURI.relativize(file.toURI()).toString();

                    if (rawurl.contains("://") || rawurl.startsWith("/")) {
                        Logger.warning("キャッシュプレイリストに不明な表現があります"
                                       + ": '" + file);
                        // 外部生成ファイルじゃないなら原因はコーディングミス.
                        continue;
                    };

                    if (path.endsWith(".m3u8") || path.contains(".m3u8?")) {
                        instance.playlists.add(path);
                        playlists.add(path);
                        continue;
                    } else if (
                        path.endsWith(".ts") || path.contains(".ts?")
                        || path.endsWith(".cmfv") || path.contains(".cmfv?")
                        || path.endsWith(".cmfa") || path.contains(".cmfa?")
                        || path.endsWith(".m4s") || path.contains(".m4s?")) {
                        // do nothing
                    } else {
                        Logger.info("未知のセグメント拡張子です: '" + rawurl
                                    + "' in " + dir);
                        // fall through
                    };
                    instance.playlistSegments.add(path);
                    if (file.exists()) {
                        instance.cachedSegments.add(path);
                    };
                };
            };

            return instance;
        };

        private List<String> debugSetSort(Set<String> src) {
            List<String> dest = new ArrayList<>(src);
            Collections.sort(dest);
            return dest;
        };

        public String debugFormat() {
            String o = "";
            final String n = "\n";
            {
                String s = "";
                String t = "";
                for (String x : playlists) {
                    s = s + " " + x;
                };
                for (String x : missingPlaylists) {
                    t = t + " " + x;
                };
                o += "-- playlists:" + s + ", missing:" + playlistMissing + ":" + t;
                o += n;
            };
            {
                Set<String> missingSegs = new HashSet<>();
                for (String x : playlistSegments) {
                    if (!cachedSegments.contains(x)) {
                        missingSegs.add(x);
                    };
                };
                String s = "";
                for (String x : debugSetSort(missingSegs)) {
                    s = s + " " + x;
                };
                o += "-- missing segs: " + s + n;
            };
            {
                String s = "";
                for (String x : debugSetSort(playlistSegments)) {
                    s = s + " " + x;
                };
                o += "-- need segs: " + s + n;
            };
            {
                String s = "";
                for (String x : debugSetSort(cachedSegments)) {
                    s = s + " " + x;
                };
                o += "-- cached segs: " + s + n;
            };
            return o;
        };

        public static synchronized void forget(VideoDescriptor video) {
            video2HlsTmpSegments.remove(video);
        }

        public synchronized Set<String> getPlaylists() {
            return Collections.unmodifiableSet(playlists);
        }

        public synchronized Set<String> getPlaylistSegments() {
            return Collections.unmodifiableSet(playlistSegments);
        }

        public synchronized Set<String> getCachedSegments() {
            return Collections.unmodifiableSet(cachedSegments);
        }

        synchronized long getCachedSegmentBytes(File nltmpDir) {
            return CacheManager.getCachedSegmentBytes(nltmpDir, cachedSegments);
        }

        private static String toSegmentPath(String streamId, String segment) {
            return streamId + "/ts/" + segment;
        }

        public synchronized boolean addCachedSegmentAndCheckComplete
        (String streamId, String segment) {
            String segmentPath = toSegmentPath(streamId, segment);
            return addCachedSegmentAndCheckComplete(segmentPath);
        }

        // debug用: private boolean addCachedSegmentAndCheckCompleteOnce = true;

        // - 呼び出し時の追加でcompleteしたならばtrue.
        // - プレイリストに含まれない要素を渡した場合プレイリストの読み直しが
        //   発生する.
        public synchronized boolean addCachedSegmentAndCheckComplete(String segmentPath) {
            if (!playlistSegments.contains(segmentPath)) {
                // -- デバッグ出力: ここから
                // Logger.info("未知の動画セグメント: " + segmentPath
                //             + "プレイリストを再読み込みします");
                // // 1回だけデバッグ出力
                // if (addCachedSegmentAndCheckCompleteOnce) {
                //     addCachedSegmentAndCheckCompleteOnce = false;
                //     debugDumpPlaylistsSegments(this, null);
                // };
                // -- デバッグ出力: ここまで

                // - segmentPathは存在を把握していない名前.
                // - master.m3u8を読み直す.
                HlsTmpSegments newobj = create(video);
                if (newobj == null) {
                    // 現時点では余計確定.
                    return false;
                };
                this.playlistSegments = newobj.playlistSegments;
                this.playlists = newobj.playlists;
                // cachedSegmentsは代入しない.
                if (!playlistSegments.contains(segmentPath)) {
                    // -- デバッグ出力: ここから
                    Logger.info("--- 余計なファイル: " + segmentPath);
                };
            };
            cachedSegments.add(segmentPath);
            return isComplete();
        }

        // - プレイリストに含まれない要素を渡した場合プレイリストの読み直しが
        //   発生する.
        public synchronized void addCachedSegment(String segmentPath) {
            if (!playlistSegments.contains(segmentPath)) {
                // - segmentPathは存在を把握していない要素.
                // - master.m3u8を読み直す.
                HlsTmpSegments newobj = create(video);
                if (newobj == null) {
                    // - 現時点では余計.
                    // - playlistMissingならばそこに含まれているかも知れない.
                    return;
                };
                this.playlistSegments = newobj.playlistSegments;
                this.playlists = newobj.playlists;
                // cachedSegmentsは代入しない.
                if (!playlistSegments.contains(segmentPath)) {
                    // - 現時点では余計.
                    // - playlistMissingならばそこに含まれているかも知れない.
                    Logger.info("--- 余計なファイル: " + segmentPath);
                };
            };
            cachedSegments.add(segmentPath);
        };

        public synchronized boolean isComplete() {
            return !playlistMissing && playlistSegments.size() == cachedSegments.size();
        }

        public synchronized boolean isKnownSegmentsComplete() {
            // Logger.info("--(cachemanager)" + playlistSegments.size() + "/" + cachedSegments.size());
            return playlistSegments.size() == cachedSegments.size();
        };

        public synchronized int undownloadedKnownSegmentsCount() {
            return playlistSegments.size() - cachedSegments.size();
        };

        public synchronized void clearCachedSegments() {
            cachedSegments.clear();
        }

        // プレイリストが欠落していた場合にfalse.
        public synchronized boolean playlistsExist(File nltmpDir) {
            if (playlistMissing) {
                return false;
            };
            for (String playlist : getPlaylists()) {
                if (!new File(nltmpDir, playlist).exists()) {
                    return false;
                };
            };
            return true;
        };

        // - この関数はサブプレイリストファイルの欠落を加味しない.
        // - チェック対象はプレイリストから検出した動画・音声のチャンクファイルのみ.
        // - "known"とは読めなかったplaylistを考慮しないということ.
        // - 読めなかったplaylistに書かれたsegmentsが存在しなくてもtrueを返し
        //   える.
        public synchronized boolean knownSegmentsExist(File nltmpDir) {
            for (String segment : getPlaylistSegments()) {
                if (!new File(nltmpDir, segment).exists()) {
                    return false;
                };
            };
            return true;
        };

        // // - この関数はプレイリストの欠落を加味する.
        // public synchronized boolean hardCheckSegmentsExist(File nltmpDir) {
        //     // 一度キャッシュ済マークを全部消してから実在を確認する.
        //     clearCachedSegments();
        //     for (String segmentPath : getPlaylistSegments()) {
        //         if (new File(nltmpDir, segmentPath).exists()) {
        //             addCachedSegment(segmentPath);
        //         };
        //     };
        //     return false;
        // };

        public synchronized boolean allFilesExist(File nltmpDir) {
            if (playlistMissing) {
                return false;
            };
            if (!playlistsExist(nltmpDir)) {
                return false;
            };
            if (knownSegmentsExist(nltmpDir)) {
                return true;
            };
            return false;
        };
    }

    /**
     * [nl] flvlistを生成して返す
     */
    public static String getFlvList() {
        //[xmlfix]
        String templateFilename = System.getProperty("templateFile", "");
        if (templateFilename.equals("")) {
            templateFilename = "/local/list.html";
        }
        return getFlvList(templateFilename);
    }

    /**
     * [nl] flvlistを生成して返す
     * @param templateFilename テンプレートファイル名
     * @return 生成したflvlist文字列
     */
    public static String getFlvList(String templateFilename) {
        File templateFile = UserDataPaths.configuredFile(
                templateFilename, null);
        if (templateFile.exists()) {
            LocalFlvTemplate template = new LocalFlvTemplate(templateFile);
            try {
                Method method;
                Object[] args;

                /*
                 * ${VERSION}
                 *      Nicocacheのバージョン文字列
                 * ${INIT}
                 *      Cache#init(Config)メソッドの実行結果（戻り値がvoidなので空文字）
                 * ${TEMP_LIST}
                 *      Cache#getTempListAsJson(boolean)メソッドの実行結果
                 *      このキーワードの前に${INIT}キーワードを記述すること。
                 * ${CACHE_LIST}
                 *      Cache#getCacheListAsJson(boolean)メソッドの実行結果
                 *      このキーワードの前に${INIT}キーワードを記述すること。
                 * ${DIR_LIST}
                 *      Cache#getDirListAsJson(boolean)メソッドの実行結果
                 *      このキーワードの前に${INIT}キーワードを記述すること。
                 * ${LOCALFLV_STR}
                 *      Cache#getFlvListForLocalFlv()メソッドの実行結果
                 *      このキーワードの前に${INIT}キーワードを記述すること。
                 */
                //--------------------------------------------------------------------------
                template.assign("VERSION", Main.VER_STRING);
                //--------------------------------------------------------------------------
//              method = Cache.class.getMethod("init", new Class[]{Config.class});
//              args   = new Object[]{config};
                method = Cache.class.getMethod("init", (Class[])null);
                template.assign("INIT", method);
                //--------------------------------------------------------------------------
                method = Cache.class.getMethod("getTempListAsJson", Boolean.TYPE);
                args   = new Object[]{Boolean.FALSE};
                template.assign("TEMP_LIST", method, args);
                //--------------------------------------------------------------------------
                method = Cache.class.getMethod("getCacheListAsJson", Boolean.TYPE);
                args   = new Object[]{Boolean.FALSE};
                template.assign("CACHE_LIST", method, args);
                //--------------------------------------------------------------------------
                method = Cache.class.getMethod("getDirListAsJson", Boolean.TYPE);
                args   = new Object[]{Boolean.FALSE};
                template.assign("DIR_LIST", method, args);
                //--------------------------------------------------------------------------
                method = Cache.class.getMethod("getFlvListForLocalFlv", (Class[])null);
                template.assign("LOCALFLV_STR", method);
                //--------------------------------------------------------------------------

                String result = template.execute();
                template.clear();
                return result;
            } catch (Exception e) {
                Logger.error(e);
            }
        }
        return "<html>\n<head>\n" +
            "\t<meta http-equiv=\"Content-Type\" content=\"text/html; charset=utf-8\">\n" +
            "\t<meta http-equiv=\"Pragma\" content=\"no-cache\">\n" +
            "\t<meta http-equiv=\"Cache-Control\" content=\"no-cache\">\n" +
            "\t<meta http-equiv=\"Expires\" content=\"Thu, 01 Dec 1994 16:00:00 GMT\">\n" +
            "\t<script type=\"text/javascript\" src=\"/local/list.js\"></script>\n" +
            "\t<link rel=\"stylesheet\" type=\"text/css\" href=\"/local/list.css\" charset=\"utf-8\">\n" +
            "\t<title>LocalFLV</title>\n" +
            "</head>\n<body>\n" +
            "<script type=\"text/javascript\">\n" +
            getFlvListAjax() +
            "makeCacheList();\n" +
            "</script>\n</body>\n</html>\n";
        // 途中を';'で区切るとStringBuider生成が分割されて効率が悪い(以下同様)
    }

    public static String getFlvListAjax() {
        // 毎回読み直すことにします。多いとやばいかも
        init();
        return "ncversion = \"" + Main.VER_STRING + "\";\n" +
            "dirList = " + getDirListAsJson(false) + ";\n" +
            "tempList = " + getTempListAsJson(false) + ";\n" +
            "cacheList = " + getCacheListAsJson(false) + ";\n" +
            // ローカルFLVでflvplayerからのヘッダが無かった時用に念のため
                "forLocalFlv = \"" + getFlvListForLocalFlv() + "\";\n";
    }

    public static String getFlvListAsJson() {
        init();
        String str = "{\n\"dirList\":" +
            getDirListAsJson(false) +
            ",\n\"tempList\":" +
            getTempListAsJson(false) +
            ",\n\"cacheList\":" +
            getCacheListAsJson(false) +
            "\n}";

        return str;
    }

    /**
     * [nl] キャッシュのJSONを文字バッファに書き込む。JSONの書式は以下の通り。
     * <pre>"&lt;smid&gt;":["タイトル", "フォルダ", サイズ, 更新日時]</pre>
     * {@link #id2File}の排他制御を行っていないので、
     * 必要なら呼び出し側で{@link #id2FileLock}を使って排他制御を行うこと。
     *
     * @param smid 動画ID
     * @param sb JSONを書き込む文字列バッファ
     * @return JSONを書き込んだらtrue
     */
    @Deprecated
    public static boolean writeJSON(String smid, StringBuilder sb) {
        VideoDescriptor video = altIdToVideoDescriptor(smid);
        return writeJSON(video, sb);
    }

    /**
     * [nl] キャッシュのJSONを文字バッファに書き込む。JSONの書式は以下の通り。
     * <pre>"&lt;altid&gt;":["タイトル", "フォルダ", サイズ, 更新日時]</pre>
     * {@link #id2File}の排他制御を行っていないので、
     * 必要なら呼び出し側で{@link #id2FileLock}を使って排他制御を行うこと。
     *
     * @param video 動画記述子
     * @param sb JSONを書き込む文字列バッファ
     * @return JSONを書き込んだらtrue
     */
    public static boolean writeJSON(VideoDescriptor video, StringBuilder sb) {
        File cacheFile = video2File.get(video);
        if (cacheFile == null || !cacheFile.exists()) {
            return false;
        }
        // 完成済みキャッシュの結果はキャッシュしてみる
        CacheStats stat = statCache.get(video);
        if (stat == null) {
            stat = new CacheStats(cacheFile);
            statCache.put(video, stat);
        }
        String name = cacheFile.getName();
        String dir = video2dir.get(video);
        String title = getTitleFromFilename(name);
        sb.append("\"").append(videoDescriptorToAltId(video));
        sb.append("\":[\"").append(title);
        sb.append("\", \"").append(dir);
        sb.append("\", ").append(stat.size);
        sb.append(", ").append(stat.timestamp);
        sb.append("]");

        return true;
    }

    //[nl] + JSON fix
    public static String getCacheListAsJson(boolean initCache)
    {
        if (initCache) init();
        // 1行平均100文字として初期バッファを確保
        StringBuilder strBuf = new StringBuilder(video2File.size() * 100);
        strBuf.append("{\n");
        if (video2File.size() > 0) {
            video2FileLock.readLock().lock();
            try {
                VideoDescriptor[] videos = video2File.keySet().toArray(new VideoDescriptor[0]);
                Arrays.sort(videos);
                for (VideoDescriptor video : videos) {
                    if (writeJSON(video, strBuf)) strBuf.append(",\n");
                }
            } finally {
                video2FileLock.readLock().unlock();
            }
            int sblen = strBuf.length();
            strBuf.replace(sblen - 2, sblen, "\n}");
        } else {
            strBuf.append("}");
        }

        return strBuf.toString();
    }

    public static String getCacheListAsJson()
    {
        return getCacheListAsJson(true);
    }

    //[nl] + JSON fix
    public static String getTempListAsJson(boolean initCache)
    {
        if (initCache) init();
        // 1行平均100文字として初期バッファを確保
        StringBuilder strBuf = new StringBuilder(video2Tmp.size() * 100);
        strBuf.append("{\n");
        if (video2Tmp.size() > 0)
        {
            VideoDescriptor[] lstTmp = video2Tmp.keySet().toArray(new VideoDescriptor[0]);
            Arrays.sort(lstTmp);
            for (VideoDescriptor k : lstTmp)
            {
                File kFile = video2Tmp.get(k);
                boolean nowDL = getDLFlag(k);
                String fn = kFile.getName().substring(6);
                long lastmod = kFile.lastModified() / 1000;
                long nowSize = kFile.length();
                long realSize = 0;
                if (nowDL) {
                    Object ret = video2DLFinalSize.get(k);
                    if (ret == null) {
                        realSize = -1;
                    } else {
                        realSize = (long)ret;
                    };
                    nowSize = getDLSize(k);
                }
                String title = getTitleFromFilename(fn);

                // "<smid>":["<title>", <nowSize>, <readSize>, <nowDL>, <lastmod>],\n
                strBuf.append("\"").append(videoDescriptorToAltId(k)); // <altid>
                strBuf.append("\":[\"").append(title);
                strBuf.append("\", ").append(nowSize);
                strBuf.append(", ").append(realSize);
                strBuf.append(", ").append(nowDL);
                strBuf.append(", ").append(lastmod);
                strBuf.append("],\n");
            }
            int sblen = strBuf.length();
            strBuf.replace(sblen - 2, sblen, "\n}");
        } else {
            strBuf.append("}");
        }

        return strBuf.toString();
    }

    public static String getTempListAsJson()
    {
        return getTempListAsJson(true);
    }

    //[nl] + JSON fix
    public static String getDirListAsJson(boolean initCache)
    {
        if (initCache) init();

        // 1行平均50文字として初期バッファを確保
        StringBuilder str = new StringBuilder(dirList.size() * 50);
        for (String dir : dirList) {
            if (str.length() == 0) {
                str.append("[");
            } else {
                str.append(", ");
            }
            str.append("\"").append(dir).append("\"");
        }
        str.append("]");

        return new String(str);
    }

    public static String getDirListAsJson() {
        return getDirListAsJson(true);
    }

    // [nl] wrapper専用のリスト
    public static String getFlvListForLocalFlv()
    {
        StringBuilder list = new StringBuilder();
        if (_id2File.size() > 0) {
            for (String k : _id2File.keySet()) { // wrapperってdmc再生できないよね？
                list.append(k).append(".flv ");
            }
        }
        return list.toString();
    }

    /**
     * ローカルフォルダ一覧をXMLで取得します。<br>
     * リクエストパラメータに関しては以下の通りです。
     * <dl>
     *   <dt>type</dt>
     *   <dd>"dirlist"</dd>
     *   <dt>reload</dt>
     *   <dd>
     *      "true" または "false"<br>
     *      "false"の場合、XMLキャッシュがあれば、キャッシュを返す。
     *   </dd>
     *   <dt>ignore</dt>
     *   <dd>除外するフォルダをカンマ区切りで列挙。</dd>
     * </dl>
     * <br>
     * @param query リクエストパラメータ
     * @return XML
     */
    public static String getDirListAsXml(HashMap<String, String> query) {
        String xml = null;
        String type = query.get("type");
        boolean reload = Boolean.parseBoolean(query.get("reload"));

        if (xmlCache.containsKey(type) && !reload) {
            return xmlCache.get(type);
        }

        String ignoreStr = query.get("ignore");
        HashSet<String> ignoreDirs = null;
        if (ignoreStr != null) {
            ignoreDirs = new HashSet<>(Arrays.asList(ignoreStr.split(",")));
        }

        // 初期化
        init();

        try {
            Document doc = createDocument();
            XmlInfo info = new XmlInfo(doc);

            Element root =  doc.createElement("dirList");

            Element tempNode     = createDirElement(info, "一時ファイル", "", VIEW_TEMP);
            Element cacheNode    = createDirElement(info, "キャッシュファイル", "", VIEW_CACHE);
            Element cacheAllNode = createDirElement(info, "キャッシュファイル一覧", "", VIEW_ALL);

            // キャッシュファイルノードをルートに子ノードを生成
            cacheNode = searchLocalCacheDirectory(info, cacheNode, "", ignoreDirs);

            root.appendChild(tempNode);
            root.appendChild(cacheNode);
            root.appendChild(cacheAllNode);

            root.setAttribute("result", "true");

            doc.appendChild(root);

            // DOMをXML文字列に変換
            xml = document2String(doc);

            // XMLをキャッシュする
            xmlCache.put(type, xml);

        } catch (Exception e) {
            Logger.error(e);
            xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>" +
                "<dirTree result=\"false\"></dirTree>";
        }

        return xml;
    }

    /**
     * ローカルフォルダを再帰的に検索し、DOMを構築します。
     *
     * @param info Document情報
     * @param parent 親ノード
     * @param ignoreDirs 除外するディレクトリ情報
     * @return 親ノード
     */
    private static Element searchLocalCacheDirectory(XmlInfo info, Element parent, String parentName, HashSet<String> ignoreDirs) {
        for (String childName : dirList) {
            if (childName.startsWith(parentName) && !childName.equals(parentName)) {
                String dirTitle = childName.substring(parentName.length());
                if (dirTitle.startsWith("/")) dirTitle = dirTitle.substring(1);
                if (dirTitle.contains("/") || ignoreDirs != null && ignoreDirs.contains(dirTitle)) {
                    continue;
                }
                Element child = createDirElement(info, dirTitle, childName, VIEW_CACHE);
                child = searchLocalCacheDirectory(info, child, childName, ignoreDirs);
                parent.appendChild(child);
            }
        }
        return parent;
    }

    /**
     * [xmlfix]
     * ファイル一覧をXMLで取得します。<br>
     * リクエストパラメータに関しては以下の通りです。
     * <dl>
     *   <dt>type</dt>
     *   <dd>
     *      "templist" または "cachelist" または "cachelistall"<br>
     *      "templist"を指定した場合、一時ファイル一覧を返します。<br>
     *      "cachelist"を指定した場合、指定フォルダのキャッシュファイル一覧を返します。<br>
     *      "cachelistall"を指定した場合、全てのキャッシュファイル一覧を返します。<br>
     *   </dd>
     *   <dt>reload</dt>
     *   <dd>
     *      "true" または "false"<br>
     *      "false"の場合、XMLキャッシュがあれば、キャッシュを返す。
     *   </dd>
     *   <dt>path</dt>
     *   <dd>対象フォルダのパス</dd>
     *   <dt>filetype</dt>
     *   <dd>
     *      抽出するファイルの拡張子をカンマ区切りで指定。<br>
     *      デフォルトは、hls,mp4,flv,swfです。
     *   </dd>
     * </dl>
     * <br>
     * @param query リクエストパラメータ
     * @return XML
     */
    public static String getCacheListAsXml(HashMap<String, String> query) {
        String xml = null;
        String type = query.get("type");
        String path = query.get("path"); if(path==null) path="";
        String name = query.get("name"); if(name==null) name="";
        String filetype = query.get("filetype");
        boolean reload = Boolean.parseBoolean(query.get("reload"));

        String key = "";
        if (TYPE_CACHELIST.equals(type)) key = type+":"+path;
        if (TYPE_CACHELIST_ALL.equals(type) || TYPE_TEMPLIST.equals(type)) {
            key = type;
            path = "";
        }
        if (xmlCache.containsKey(key) && !reload) return xmlCache.get(key);

        // 指定フォルダが存在しなかった場合
        if (!dirList.contains(path)) {
            return "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>" +
                    "<dir name=\""+ name +"\" path=\"" + path + "\" result=\"false\"></dir>";
        }

        // 対象ファイルの拡張子を設定
        HashSet<String> target = new HashSet<>();
        if (filetype == null) {
            target.add("hls");
            target.add("mp4");
            target.add("flv");
            target.add("swf");
        } else {
            target.addAll(Arrays.asList(filetype.split(",")));
        }

        try {
            Document doc = createDocument();
            XmlInfo info = new XmlInfo(doc);
            Element root = doc.createElement("dir");

            root = getCacheListAsXml(info, root, path, target, type);

            root.setAttribute("name", name);
            root.setAttribute("path", path);
            root.setAttribute("result", "true");
            root.setAttribute("size", String.valueOf(info.size));
            root.setAttribute("count", String.valueOf(info.fileCount));

            doc.appendChild(root);

            xml = document2String(doc);

            xmlCache.put(key, xml);

        } catch (Exception e) {
            xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>" +
                "<dir name=\""+ name +"\" path=\"" + path + "\" result=\"false\"></dir>";
        }

        return xml;
    }

    /**
     * ファイル一覧のDOMを再帰的に構築します。
     *
     * @param info Document情報
     * @param parent 親ノード
     * @param targetDir 現在のディレクトリ
     * @param target 抽出対象拡張子
     * @param type "templist" または "cachelist" または "cachelistall"
     * @return 親ノード
     */
// init()で作られたディレクトリリストから作成するように変更。
// フォルダごとのリストは作るのが大変なのでディレクトリを読み直す
// 内部リストと不整合が出る可能性もあるが放置
// @param targerDir は、String 内部ディレクトリ名 に
    private static Element getCacheListAsXml(XmlInfo info, Element parent, String targetDir, HashSet<String> target, String type) {
        Element root = parent;

// templist
        if (TYPE_TEMPLIST.equals(type)) {
            for (File file : video2Tmp.values()) {
                String filetype = splitExt(file.getName())[1];
                if ( !target.contains(filetype) ) {
                    continue;
                }
                Element element = createFileElement(info, file);
                if (element != null) root.appendChild(element);
            }
            return root;
        }
// 全キャッシュ
        if (TYPE_CACHELIST_ALL.equals(type)) {
            for (File file : video2File.values()) {
                String filetype = splitExt(file.getName())[1];
                if ( !target.contains(filetype) ) {
                    continue;
                }
                Element element = createFileElement(info, file);
                if (element != null) root.appendChild(element);
            }
            return root;
        }
// 指定されたフォルダ
        for (File file : dir2File.get(targetDir).listFiles()) {
            String fileName = file.getName();
            if (file.isDirectory() || fileName.startsWith(NLTMP_)) {
                continue;
            }
            String filetype = splitExt(fileName)[1];
            if ( !target.contains(filetype) ) {
                continue;
            }
            Element element = createFileElement(info, file);
            if (element != null) root.appendChild(element);
        }

        return root;
    }

    /**
     * Documentオブジェクトを生成します。
     *
     * @return Documenオブジェクト
     * @throws ParserConfigurationException
     */
    private static Document createDocument() throws ParserConfigurationException {

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.newDocument();

        doc.setXmlStandalone(true);
        doc.setXmlVersion("1.0");

        return doc;
    }

    /**
     * ローカルフォルダ一覧用の&lt;dir&gt;エレメントを生成します。
     *
     * @param info Document情報
     * @param name フォルダの名称
     * @param path フォルダのパス
     * @param view "temp" または "cache" または "all"
     * @return 生成したエレメント
     */
    private static Element createDirElement(XmlInfo info, String name, String path, String view) {
        Element dir = info.document.createElement("dir");
        dir.setAttribute("name", name);
        dir.setAttribute("path", path);
        dir.setAttribute("view", view);

        return dir;
    }

    /**
     * 指定したファイルの&lt;file&gt;エレメントを生成します。
     *
     * @param info Document情報
     * @param file ファイル
     * @return 生成したエレメント
     */
    private static Element createFileElement(XmlInfo info, File file) {
        String fileName = file.getName();

        boolean isTempFile = false;
        if (fileName.startsWith(NLTMP_)) {
            fileName = fileName.substring(6);
            isTempFile = true;
        }
        // filetypeが無効になるけど、とりあえず
        VideoDescriptor video = getVideoDescriptorFromFilename(fileName);
        if (video == null) return null;

        String folderName = video2dir.get(video);
        if (folderName == null) folderName = "";

        boolean economy = false;
        if (video.isLow()) {
            economy = true;
            folderName = "";
        }

        String path = (!folderName.equals("") ? "/" : "" ) +
                        folderName + "/" + fileName;


        long lastmod, size;
        CacheStats entry;
        if ((entry = statCache.get(video)) != null) {
            size = entry.size;
            lastmod = entry.timestamp;
        } else {
            size = file.length();
            lastmod = file.lastModified() / 1000;
            entry = new CacheStats(size, lastmod);
            if (!isTempFile) {
                statCache.put(video, entry);
            }
        }

        String title = getTitleFromFilename(fileName);
        //if (null == title) title = id;
        if (null == title) title = "";
        String[] f = splitExt(fileName);

        Element element = info.document.createElement("file");
        element.setAttribute("id", videoDescriptorToAltId(video));
        element.setAttribute("title", title);
        element.setAttribute("path", path);
        element.setAttribute("foldername", folderName);
        element.setAttribute("economy", String.valueOf(economy));
        element.setAttribute("format", f[1]);
        element.setAttribute("size", String.valueOf(size));
        element.setAttribute("selected", "false");
        element.setAttribute("lastmodify", String.valueOf(lastmod));
        element.setAttribute("tempfile", String.valueOf(isTempFile));
        element.setAttribute("downloading", String.valueOf(getDLFlag(video)));

        info.size += size;
        info.fileCount += 1;

        return element;
    }

    /**
     * 拡張子付きのファイル名をファイル名と拡張子に分離します。
     *
     * @param filename ファイル名
     * @return [0]ファイル名、[1]拡張子
     */
    private static String[] splitExt(String filename) {
        String[] f = new String[2];
        int pos = filename.lastIndexOf(".");

        if (pos < 0) {
            f[0] = filename;
            f[1] = "";
        } else {
            f[0] = filename.substring(0, pos);
            f[1] = filename.substring(pos+1);
        }
        return f;
    }

    /**
     * 一時ファイル一覧をXMLで取得します。
     *
     * @param query リクエストパラメータ
     * @return XML
     * @see dareka.processor.impl.Cache#getCacheListAsXml(HashMap)
     */
    public static String getTempListAsXml(HashMap<String, String> query) {
        return getCacheListAsXml(query);
    }

    /**
     * DocumentオブジェクトをXML文字列に変換します。
     *
     * @param doc Documentオブジェクト
     * @return XML
     * @throws TransformerException 変換に失敗した場合
     */
    private static String document2String(Document doc) throws TransformerException {

        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        StringWriter writer = new StringWriter();

        // http://www.w3.org/TR/xslt#output
        transformer.setOutputProperty("version", "1.0");
        transformer.setOutputProperty("indent", "no");
        transformer.setOutputProperty("encoding", "UTF-8");
        transformer.setOutputProperty("standalone", "yes");

        transformer.transform(new DOMSource(doc), new StreamResult(writer));

        return writer.toString();
    }

    /**
     * XmlCacheを削除します。
     *
     * @param type タイプ
     * @param path パス
     */
    protected static void removeXmlCache(String type, String path) {
        if (null == path) {
            removeXmlCache(type);
        } else {
            removeXmlCache(type + ":" + path);
        }
    }

    /**
     * XMLキャッシュを削除します。
     *
     * @param key キー
     */
    private static void removeXmlCache(String key) {
        if (xmlCache.containsKey(key)) {
            xmlCache.remove(key);
        }
    }
}
