package dareka.processor.impl;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dareka.common.CloseUtil;
import dareka.common.Config;
import dareka.common.ConfigObserver;
import dareka.common.FileUtil;
import dareka.common.Logger;
import dareka.processor.HttpHeader;
import dareka.processor.HttpRequestHeader;
import dareka.processor.HttpResponseHeader;
import dareka.processor.LocalFileResource;
import dareka.processor.Processor;
import dareka.processor.Resource;
import dareka.processor.StringResource;
import dareka.processor.URLResource;

// [nl] サムネイル画像をキャッシュしていくよ！(ファイル単位で保存版)
public class ThumbProcessor2 implements Processor, ConfigObserver {
    private static final String[] SUPPORTED_METHODS = new String[] { "GET" };
    private static final Pattern THUMB_URL_PATTERN = Pattern.compile(
            "^https?://(tn(?:-skr\\d)?\\.smilevideo\\.jp/smile\\?i="
                    + "|nicovideo\\.cdn\\.nimg\\.jp/thumbnails/(\\w+)/"
                    + ")(\\d{1,9})(?:\\.(\\d++))?+(?:\\.([ML]))?+(?:|&\\.jpg)$");

    private static final ConcurrentHashMap<Integer, Object> retrievingLocks =
            new ConcurrentHashMap<>();

    private static final int THCACHE_SIGNATURE = 1203160629;
    private static final int THCACHE_VERSION = 2;
    private static final File thcacheFile  = new File("thcache.dat");
    private static final ConcurrentHashMap<Integer, File> thcacheSubFolders =
            new ConcurrentHashMap<>();
    private static final Random rand = new Random();

    private static final String CONTENT_TYPE_JPG  = "image/jpeg";
    private static final String CONTENT_TYPE_GIF  = "image/gif";

    static class ThumbInfo {
        int id;
        String subid;
        String size;
        String contentType;
        byte[] contentBody;
        long lastModified;
        Resource resource;
        boolean status404;
        public ThumbInfo(int id, byte[] contentBody) {
            this.id = id;
            this.contentBody = contentBody;
            if (contentBody.length > 0) {
                if (contentBody[0] == (byte)0xff) {
                    this.contentType = CONTENT_TYPE_JPG;
                } else if (contentBody[0] == (byte)0x47) {
                    this.contentType = CONTENT_TYPE_GIF;
                }
            }
            this.lastModified = 0L; // Epoch
            this.resource = new StringResource(contentBody);
            setHeaderInfo();
        }
        public ThumbInfo(int id, String subid, String size, File thumbFile) {
            this.id = id;
            this.subid = subid;
            this.size = size;
            String path = thumbFile.getName().toLowerCase();
            if (path.endsWith(".jpg")) {
                this.contentType = CONTENT_TYPE_JPG;
            } else if (path.endsWith(".gif")) {
                this.contentType = CONTENT_TYPE_GIF;
            }
            this.lastModified = thumbFile.lastModified();
            try {
                this.resource = new LocalFileResource(thumbFile);
                setHeaderInfo();
            } catch (IOException e) {
                Logger.debugWithThread(e);
            }
        }
        public ThumbInfo(int id, String subid, String size,
                String url, HttpRequestHeader requestHeader) {
            this.id = id;
            this.subid = subid;
            this.size = size;
            try {
                fetch(url, requestHeader);
            } catch (IOException e) {
                Logger.debugWithThread(e.toString());
            }
        }
        private void setHeaderInfo() {
            if (resource != null) {
                if (contentType != null) {
                    resource.setResponseHeader(HttpHeader.CONTENT_TYPE, contentType);
                }
                if (lastModified >= 0L) {
                    resource.setLastModified(lastModified);
                }
                if (thcacheExpires > 0) {
                    resource.addCacheControlResponseHeaders(thcacheExpires);
                }
            }
        }
        private void fetch(String url, HttpRequestHeader requestHeader) throws IOException {
            URLResource r = new URLResource(url);
            requestHeader.setURI(url);
            requestHeader.removeMessageHeader(HttpHeader.IF_MODIFIED_SINCE);
            requestHeader.removeMessageHeader("If-None-Match");
            r.setFollowRedirects(true);
            if (thcacheTimeout > 0) {
                // 10%のゆらぎを設けて同時タイムアウトを避ける
                int n = thcacheTimeout / 10;
                r.setTransferTimeout(thcacheTimeout + rand.nextInt(n * 2) - n);
            }
            ByteArrayOutputStream bout = new ByteArrayOutputStream(32 * 1024);
            r.transferTo(null, bout, requestHeader, null);
            ByteArrayInputStream bin = new ByteArrayInputStream(bout.toByteArray());
            HttpResponseHeader rh = new HttpResponseHeader(bin);

            int statusCode = rh.getStatusCode();
            if (statusCode == 200) {
                String s = rh.getMessageHeader(HttpHeader.CONTENT_TYPE);
                if (CONTENT_TYPE_JPG.equalsIgnoreCase(s)) {
                    contentType = CONTENT_TYPE_JPG;
                } else if (CONTENT_TYPE_GIF.equalsIgnoreCase(s)) {
                    contentType = CONTENT_TYPE_GIF;
                }
                lastModified = rh.getLastModified();
                contentBody = new byte[bin.available()];
                bin.read(contentBody);
                resource = StringResource.getRawResource(rh, contentBody);
            }
            status404 = statusCode == 404;
            if (Boolean.getBoolean("swfDebug")) {
                Logger.info("thcache: %s (status=%d,len=%d)",
                        requestHeader.getURI(), statusCode, rh.getContentLength());
            }
        }
        public byte[] getBody() {
            if (resource != null && resource instanceof URLResource) {
                URLResource r = (URLResource)resource;
                try {
                    contentBody = r.getResponseBody();
                } catch (IOException e) {
                    Logger.debugWithThread(e);
                }
            }
            return contentBody;
        }
        public String getExt() {
            if (contentType == CONTENT_TYPE_JPG) {
                return ".jpg";
            } else if (contentType == CONTENT_TYPE_GIF) {
                return ".gif";
            }
            return "";
        }
    }

    private static File thcacheFolder;
    private static int thcacheTimeout, thcacheExpires;
    private static Refs refs;

    public static void init() {
        if (thcacheFolder == null) {
            Config.addObserver(new ThumbProcessor2());
        }
    }

    @Override
    public void update(Config config) {
        thcacheFolder = new File(System.getProperty("thcacheFolder"));
        thcacheFolder.mkdir();
        thcacheSubFolders.clear();
        refs = new Refs(thcacheFolder);
        thcacheTimeout = Integer.getInteger("thcacheTimeout", 0);
        thcacheExpires = Integer.getInteger("thcacheExpires", 0) * 3600 * 24;
    }

    public ThumbProcessor2() {
        if (!thcacheFile.exists()) return;

        Logger.info("Converting '" + thcacheFile.getName() + "' to individual files:");
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(thcacheFile, "r");
            if (raf.readInt() == THCACHE_SIGNATURE && raf.readInt() == THCACHE_VERSION) {
                int count = raf.readInt(), converted = 0;
                raf.readLong(); // savedRequest
                raf.readLong(); // savedTransfer
                for (int i = 0; i < count; i++) {
                    int id  = raf.readInt();
                    int len = raf.readInt();
                    byte[] content = new byte[len];
                    raf.readFully(content);
                    ThumbInfo ti = new ThumbInfo(id, content);
                    if (storeCache(ti, false, null)) converted++;
                    if (i % 1000 == 0) System.out.print(".");
                }
                System.out.println();
                Logger.info(String.format(" => %,d files converted, %,d files skipped.",
                        converted, count - converted));
            } else {
                Logger.info(" => invalid cache format, not converted.");
            }
        } catch (IOException e) {
            Logger.error(e);
        } finally {
            CloseUtil.close(raf);
        }
        thcacheFile.renameTo(new File(thcacheFile.getPath() + ".bak"));
    }

    private File getCacheFile(int id, String subid, String size, String ext) {
        Integer key = id / 100000;
        File subFolder = thcacheSubFolders.get(key);
        if (subFolder == null) {
            assert thcacheFolder != null;
            subFolder = new File(thcacheFolder, String.format("%03d", key));
            subFolder.mkdir();
            thcacheSubFolders.put(key, subFolder);
        }
        String _subid = subid != null ? "." + subid : "";
        String _size = size != null ? "." + size : "";
        return new File(subFolder, String.format("%05d%s%s%s",
                id % 100000, _subid, _size, ext));
    }

    private boolean storeCache(ThumbInfo ti, boolean overwrite, Ref ref) {
        if (ref != null) {
            refs.put(ti.id, ref);
        }
        File cacheFile = getCacheFile(ti.id, ti.subid, ti.size, ti.getExt());
        if (!cacheFile.exists() || overwrite) {
            byte[] data = ti.getBody();
            if (data != null && data.length > 0) {
                int len = FileUtil.copy(ByteBuffer.wrap(data), cacheFile);
                if (len > 0 && ti.lastModified >= 0L) {
                    cacheFile.setLastModified(ti.lastModified);
                    Logger.debugWithThread(cacheFile.getPath() + " stored");
                    return true;
                }
            }
        }
        return false;
    }

    // Processorの実装
    @Override
    public String[] getSupportedMethods() {
        return SUPPORTED_METHODS;
    }

    @Override
    public Pattern getSupportedURLAsPattern() {
        return THUMB_URL_PATTERN;
    }

    @Override
    public String getSupportedURLAsString() {
        return null;
    }

    @Override
    public Resource onRequest(HttpRequestHeader requestHeader, Socket browser)
            throws IOException {
        String uri = requestHeader.getURI();
        Matcher m = THUMB_URL_PATTERN.matcher(uri);
        if (!m.find()) {
            Logger.warning("Unexpected thumbnail request: " + uri);
            return new URLResource(uri);
        }
        int id = Integer.parseInt(m.group(3));
        String subid = m.group(4);
        String size = m.group(5);
        Resource r = getThumbResourceFromCache(id, subid, size, requestHeader,
                Boolean.getBoolean("thcacheFixEpoch"));
        if (!Boolean.getBoolean("thcacheLargeSize") && size != null) {
            // thcacheLargeSizeがfalseの時は.Mや.Lを新たにキャッシュしない
            return new URLResource(uri);
        }
        if (r == null) {
            // キャッシュに無ければサムネ鯖から取得
            // 多重取得しないように既に取得中ならキャッシュするまで待つ
            Object lock = retrievingLocks.putIfAbsent(id, m); // mをロックオブジェクトに転用
            if (lock != null) {
                synchronized (lock) {
                    r = getThumbResourceFromCache(id, subid, size, requestHeader, false);
                }
            } else {
                lock = m;
                synchronized (lock) {
                    r = getThumbResourceFromServer(id, subid, size, requestHeader, m);
                    retrievingLocks.remove(id);
                }
            }
        }
        return r != null ? r : StringResource.getNotFound();
    }

    private Resource getThumbResourceFromCache(int id, String subid, String size,
            HttpRequestHeader requestHeader, boolean fixEpoch) {
        if (subid == null) {
            Ref ref = refs.get(id);
            if (ref != null) subid = ref.subid;
        }
        File cacheFile = getCacheFile(id, subid, size, ".jpg");
        if (!cacheFile.exists()) {
            cacheFile = getCacheFile(id, subid, size, ".gif");
            if (!cacheFile.exists()) return null;
        }
        if (fixEpoch && cacheFile.lastModified() == 0L) {
            // サムネ鯖から取得できない場合に備えてEpochを現在時刻にしておく
            cacheFile.setLastModified(System.currentTimeMillis());
            return null;
        }
        if (cacheFile.length() == 0L) {
            return getReplace404(0, null, null);
        }
        return new ThumbInfo(id, subid, size, cacheFile).resource;
    }

    private Resource getThumbResourceFromServer(int id, String subid, String size,
            HttpRequestHeader requestHeader, Matcher m) {
        String url = requestHeader.getURI();
        boolean newref = false;
        if (subid == null) {
            Ref ref = refs.get(id);
            if (ref != null) {
                url = fixUrlSubid(url, ref);
                subid = ref.subid;
            }
        } else {
            newref = true;
        }
        ThumbInfo ti = new ThumbInfo(id, subid, size, url, requestHeader);
        boolean status404 = ti.status404;
        if (ti.resource == null) {
            Logger.info("thcache: " + m.group() + " failed");
            // サムネ鯖が404を返す場合は次回アクセスしないように代替サムネを使う
            if (status404) {
                // fixEpochの場合はキャッシュにあるので取得してみる
                Resource r = getThumbResourceFromCache(id, subid, size, null, false);
                if (r == null) {
                    r = getReplace404(id, subid, size);
                }
                return r;
            }
        } else {
            Ref ref = null;
            if (newref) {
                if (m.group(1).startsWith("tn")) {
                    if (subid == null) {
                        ref = null;
                    } else {
                        ref = Ref.getUrlVer0(subid);
                    }
                } else {
                    ref = Ref.getUrlVer1(subid, m.group(2));
                }
            }
            storeCache(ti, true, ref);
        }
        return ti.resource;
    }

    private static final Pattern FIX_THUMB_URL_PATTERN = Pattern.compile(
            "^.*\\?i=(\\d{1,9})((?!\\.\\d).*)$");
    private String fixUrlSubid(String url, Ref ref) {
        Matcher m = FIX_THUMB_URL_PATTERN.matcher(url);
        if (!m.matches()) {
            return url;
        }
        switch (ref.urlver) {
        case 0:
            return "https://tn.smilevideo.jp/smile?i="
                    + m.group(1) + "." + ref.subid + m.group(2);
        case 1:
            return "https://nicovideo.cdn.nimg.jp/thumbnails/" + ref.dirid + "/"
                    + m.group(1) + "." + ref.subid + m.group(2);
        default:
            // error
            return url;
        }
    }

    static class Ref {
        public String subid;
        public int urlver;
        public String dirid;

        private Ref(String subid, int urlver, String dirid) {
            this.subid = subid;
            this.urlver = urlver;
            this.dirid = dirid;
        }

        public static Ref getUrlVer0(String subid) {
            return new Ref(subid, 0, null);
        }

        public static Ref getUrlVer1(String subid, String dirid) {
            return new Ref(subid, 1, dirid);
        }
    }

    static class Refs {
        private final File thcacheRefFolder;
        private final ConcurrentHashMap<Integer, File> thcacheRefSubFolders =
                new ConcurrentHashMap<>();

        public Refs(File thcacheFolder) {
            thcacheRefFolder = new File(thcacheFolder, "ref");
            if (!thcacheRefFolder.exists()) {
                thcacheRefFolder.mkdir();
                importRefs(thcacheFolder);
            }
        }

        public Ref get(int id) {
            RefFile ref = getRefFile(id);
            if (ref.exists()) {
                return ref.read();
            } else {
                return null;
            }
        }

        public void put(int id, Ref ref) {
            RefFile reffile = getRefFile(id);
            reffile.write(ref);
        }

        RefFile getRefFile(int id) {
            Integer key = id / 100000;
            File subFolder = thcacheRefSubFolders.get(key);
            if (subFolder == null) {
                assert thcacheRefFolder != null;
                subFolder = new File(thcacheRefFolder, String.format("%03d", key));
                subFolder.mkdir();
                thcacheRefSubFolders.put(key, subFolder);
            }
            return new RefFile(subFolder, String.format("%05d.ref", id % 100000), id);
        }

        private void importRefs(File thcacheFolder) {
            Logger.info("importing thcache refs");
            Pattern pat = Pattern.compile("^(\\d++)\\.(\\d++)\\.");
            thcacheFolder.listFiles(dir -> {
                if (!dir.isDirectory() || !dir.getName().matches("\\d{3}")) {
                    return false;
                }
                String id1 = dir.getName();
                dir.listFiles(file -> {
                    Matcher m = pat.matcher(file.getName());
                    if (m.lookingAt()) {
                        String id2 = m.group(1);
                        int id = Integer.parseInt(id1 + id2);
                        String subid = m.group(2);
                        RefFile reffile = getRefFile(id);
                        if (!reffile.exists() ||
                                reffile.lastModified() < file.lastModified()) {
                            reffile.write(Ref.getUrlVer0(subid));
                            reffile.setLastModified(file.lastModified());
                        }
                    }
                    return false;
                });
                return false;
            });
        }

        @SuppressWarnings("serial")
        static class RefFile extends File {
            int id;

            public RefFile(File parent, String filename, int id) {
                super(parent, filename);
                this.id = id;
            }

            public Ref read() {
                try {
                    BufferedReader br = new BufferedReader(new FileReader(this));
                    try {
                        String line = br.readLine();
                        String subid = line.trim();

                        line = br.readLine();
                        int urlver;
                        if (line == null) {
                            // urlverを加える前のバージョンで作られてしまったrefファイル用
                            if (subid.length() <= 5 && id <= 35160000) {
                                return Ref.getUrlVer0(subid);
                            } else {
                                return Ref.getUrlVer1(subid, String.valueOf(this.id));
                            }
                        }
                        urlver = Integer.parseInt(line.trim());
                        switch (urlver) {
                        case 0:
                            return Ref.getUrlVer0(subid);
                        case 1: {
                            line = br.readLine();
                            String dirid = line.trim();
                            return Ref.getUrlVer1(subid, dirid);
                        }
                        default:
                            return null;
                        }
                    } finally {
                        CloseUtil.close(br);
                    }
                } catch (IOException e) {
                    return null;
                }
            }

            public void write(Ref ref) {
                try {
                    BufferedWriter bw = new BufferedWriter(new FileWriter(this));
                    try {
                        bw.write(ref.subid + "\n");
                        bw.write("" + ref.urlver + "\n");
                        if (ref.urlver != 0) {
                            bw.write(ref.dirid + "\n");
                        }
                    } finally {
                        CloseUtil.close(bw);
                    }
                } catch (IOException e) {
                    this.delete();
                }
            }
        }
    }

    private Resource getReplace404(int id, String subid, String size) {
        String replace404 = System.getProperty("thcacheReplace404", "");
        if (replace404.matches("https?://.*")) {
            if (id > 0) {
                createZeroFile(id, subid, size);
            }
            return StringResource.getRedirect(replace404);
        }
        File thumb404 = new File(replace404);
        ThumbInfo ti = new ThumbInfo(id, subid, size, thumb404);
        if (id > 0 && thumb404.length() > 0L) {
            String mode = System.getProperty("thcacheReplace404Mode");
            if ("copy".equals(mode)) {
                File f = getCacheFile(id, subid, size, ti.getExt());
                long len = FileUtil.copy(thumb404, f);
                if (len > 0L && ti.lastModified >= 0L) {
                    f.setLastModified(ti.lastModified);
                }
            } else {
                createZeroFile(id, subid, size);
            }
        }
        return ti.resource;
    }

    private void createZeroFile(int id, String subid, String size) {
        File f = getCacheFile(id, subid, size, ".jpg");
        if (!f.exists()) {
            try {
                new FileOutputStream(f).close();
            } catch (IOException e) {
                Logger.error(e);
            }
        }
    }

}
