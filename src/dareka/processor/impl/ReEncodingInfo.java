package dareka.processor.impl;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dareka.common.Logger;
import dareka.processor.util.Mp4Parser;

public class ReEncodingInfo {

    // 再エンコードされた動画の特徴が変化して正しい判定結果が
    // 得られなくなった場合 ENGINE_VERSION を変更して，
    // 動画IDとバージョンから信用できない判定結果を削除する
    public final static int ENGINE_VERSION = 3;

    public final static int REQUIRED_HEADER_LENGTH = 0x200;

    public static class Entry {
        public boolean reencoded;
        public int bitrate;
        public Date checkAt;

        public Entry(boolean reencoded, int bitrate) {
            this.reencoded = reencoded;
            this.bitrate = bitrate;
            this.checkAt = new Date();
        }

        public Entry(boolean reencoded, int bitrate, Date checkAt) {
            this.reencoded = reencoded;
            this.bitrate = bitrate;
            this.checkAt = checkAt;
        }

        public boolean isComplete() {
            return !reencoded || bitrate != 0;
        }
    }

    private static boolean loaded = false;
    private final static Map<String, Entry> cache =
            Collections.synchronizedMap(new LinkedHashMap<String, Entry>());
    private final static ConcurrentHashMap<String, String> num2id =
            new ConcurrentHashMap<>();
    private final static File DATA_FILE = new File("data/reencoded.csv");
    private final static Pattern VERSION_LINE_PATTERN = Pattern.compile("version,(-?\\d+)");
    private final static Pattern DATA_LINE_PATTERN_VER1 = Pattern.compile("(\\w+),(true|false)");
    private final static Pattern DATA_LINE_PATTERN_VER2 = Pattern.compile("(\\w+),(true|false),(\\d+)");
    private final static Pattern DATA_LINE_PATTERN = Pattern.compile("(\\w+),(true|false),(-?\\d+),(-?\\d+)");
    private final static Date MAC_EPOCH = new Date(-0x7c25b080 * 1000L);

    /**
     * 再エンコードされているか，既に確認済みの結果を得る．
     * @param videoId
     * @return 再エンコードされているか．不明時はnullを返す．
     */
    public static Boolean get(String videoId) {
        Entry result = getEntry(videoId);
        if (result == null) {
            return null;
        }
        return result.reencoded;
    }

    /**
     * 判定済みの合計ビットレート情報を得る．
     * @param videoId
     * @return 再エンコードされているか．不明時は0を返す．
     */
    public static int getBitrate(String videoId) {
        Entry result = getEntry(videoId);
        if (result == null) return 0;
        return result.bitrate;
    }

    /**
     * 再エンコードされているか，既に確認済みの結果を得る．
     * @param videoId
     * @return Entry．不明時はnullを返す．
     */
    public static Entry getEntry(String videoId) {
        load();
        Entry result = cache.get(videoId);
        if (result != null) {
            return result;
        }

        try {
            int videoNum = Integer.parseInt(videoId.substring(2));

            // sm33435000ぐらい: (く) smileへのアップロード廃止
            if (33435000 < videoNum) {
                return new Entry(true, 0);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return null;
    }

    public static Boolean getFromNumber(String videoNum) {
        Entry result = getEntryFromNumber(videoNum);
        if (result == null) {
            return null;
        }
        return result.reencoded;
    }

    public static Entry getEntryFromNumber(String videoNum) {
        load();
        String videoId = num2id.get(videoNum);
        if (videoId != null) {
            Entry result = cache.get(videoId);
            if (result != null) {
                return result;
            }
        }

        try {
            int _videoNum = Integer.parseInt(videoNum);

            // sm33435000ぐらい: (く) smileへのアップロード廃止
            if (33435000 < _videoNum) {
                return new Entry(true, 0);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return null;
    }

    // appendとsaveの順序によっては増殖するため同期
    protected static synchronized void put(String videoId, Entry newEntry) {
        load();
        Entry oldEntry = cache.get(videoId);
        if (oldEntry != null && oldEntry.bitrate != 0) {
            return;
        }
        cache.put(videoId, newEntry);
        if (oldEntry != null) {
            save();
        } else {
            append(videoId, newEntry);
        }
        num2id.put(videoId.substring(2), videoId);
    }

    /**
     * 再エンコードされているかを確認する．結果はキャッシュされる．
     * キャッシュ済みの時はキャッシュから結果を返す．
     * このメソッドでは動画のビットレートを計算することができない．
     * 判定に失敗した場合はnullを返す．
     * @param videoId
     * @param header データの先頭から．最低0x200バイト必要．
     *                データの長さをlimitに設定して呼び出すこと．
     * @return 再エンコードされているか．不明時はnullを返す．
     */
    @Deprecated
    public static Boolean check(String videoId, ByteBuffer header) {
        Entry result = checkEntry(videoId, header, 0);
        if (result == null) {
            return null;
        }
        return result.reencoded;
    }

    /**
     * 再エンコードされているかを確認する．結果はキャッシュされる．
     * キャッシュ済みの時はキャッシュから結果を返す．
     * このメソッドでは動画のビットレートを計算することができない．
     * 判定に失敗した場合はnullを返す．
     * @param videoId
     * @param file 動画ファイル
     * @return 再エンコードされているか．不明時はnullを返す．
     */
    @Deprecated
    public static Boolean check(String videoId, File file) {
        Entry result = checkEntry(videoId, file, 0);
        if (result == null) {
            return null;
        }
        return result.reencoded;
    }

    /**
     * 再エンコードされているかを確認する．結果はキャッシュされる．
     * キャッシュ済みの時はキャッシュから結果を返す．
     * 判定に失敗した場合はnullを返す．
     * @param videoId
     * @param file 動画ファイル
     * @param filesize 動画データ全体の長さ
     * @return 再エンコードされているか．不明時はnullを返す．
     */
    public static Boolean check(String videoId, File file, long filesize) {
        Entry result = checkEntry(videoId, file, filesize);
        if (result == null) {
            return null;
        }
        return result.reencoded;
    }

    /**
     * 再エンコードされているかを確認する．
     * 判定に失敗した場合はnullを返す．
     * @param header データの先頭から．最低0x200バイト必要．
     *                データの長さをlimitに設定して呼び出すこと．
     * @return 再エンコードされているか．不明時はnullを返す．
     */
    public static Boolean check(ByteBuffer header) {
        Entry result = checkEntry(header, 0);
        if (result == null) {
            return null;
        }
        return result.reencoded;
    }

    /**
     * 再エンコードされているかを確認する．
     * 判定に失敗した場合はnullを返す．
     * @param file 動画ファイル
     * @return 再エンコードされているか．不明時はnullを返す．
     */
    public static Boolean check(File file) {
        Entry result = checkEntry(file, 0);
        if (result == null) {
            return null;
        }
        return result.reencoded;
    }

    /**
     * 再エンコードされているかを確認して関連情報とともに返す．結果はキャッシュされる．
     * キャッシュ済みの時はキャッシュから結果を返す．
     * 判定に失敗した場合はnullを返す．
     * @param videoId
     * @param header データの先頭から．最低0x200バイト必要．
     *                データの長さをlimitに設定して呼び出すこと．
     * @param filesize 動画データ全体の長さ
     * @return Entry．不明時はnullを返す．
     */
    public static Entry checkEntry(String videoId, ByteBuffer header, long filesize) {
        Entry c = getEntry(videoId);
        if (c != null && c.isComplete()) {
            return c;
        }
        Entry result = checkEntry(header, filesize);
        if (result == null) {
            return null;
        }
        put(videoId, result);
        return result;
    }

    /**
     * 再エンコードされているかを確認して関連情報とともに返す．結果はキャッシュされる．
     * キャッシュ済みの時はキャッシュから結果を返す．
     * 判定に失敗した場合はnullを返す．
     * @param videoId
     * @param file 動画ファイル
     * @param filesize 動画データ全体の長さ
     * @return 再エンコードされているか．不明時はnullを返す．
     */
    public static Entry checkEntry(String videoId, File file, long filesize) {
        Entry c = getEntry(videoId);
        if (c != null && c.isComplete()) {
            return c;
        }
        Entry result = checkEntry(file, filesize);
        if (result == null) {
            return null;
        }
        put(videoId, result);
        return result;
    }

    /**
     * 再エンコードされているかを確認して関連情報とともに返す．
     * 判定に失敗した場合はnullを返す．
     * @param header データの先頭から．最低0x200バイト必要．
     *                データの長さをlimitに設定して呼び出すこと．
     * @param filesize 動画データ全体の長さ．0を渡すとビットレートを計算しない．
     * @return 判定結果のEntry．不明時はnullを返す．
     */
    public static Entry checkEntry(ByteBuffer header, long filesize) {
        if (header == null || header.limit() < REQUIRED_HEADER_LENGTH) {
            return null;
        }
        Mp4Parser mp4 = new Mp4Parser(header);
        Boolean reencoded = checkCore(mp4);
        if (reencoded == null) {
            return null;
        }

        int bitrate;
        if (filesize != 0) {
            bitrate = calculateWholeBitrate(mp4, filesize);
        } else {
            bitrate = 0;
        }
        return new Entry(reencoded, bitrate, new Date());
    }

    /**
     * 再エンコードされているかを確認する．
     * 判定に失敗した場合はnullを返す．
     * @param file 動画ファイル
     * @param filesize 動画データ全体の長さ．0を渡すとビットレートを計算しない．
     * @return 判定結果のEntry．不明時はnullを返す．
     */
    public static Entry checkEntry(File file, long filesize) {
        if (file == null || !file.exists()) {
            return null;
        }
        Mp4Parser mp4 = new Mp4Parser(file, REQUIRED_HEADER_LENGTH);
        Boolean reencoded = checkCore(mp4);
        if (reencoded == null) {
            return null;
        }

        int bitrate;
        if (filesize != 0) {
            bitrate = calculateWholeBitrate(mp4, filesize);
        } else {
            bitrate = 0;
        }
        return new Entry(reencoded, bitrate, new Date());
    }

    private static Boolean checkCore(Mp4Parser mp4) {
        if (mp4.isInvalid()) {
            return null;
        }

        String videoHandler = mp4.getVideoHandler();
        Date videoCreationTime = mp4.getVideoCreationTime();

        if (videoHandler == null || videoCreationTime == null) {
            return false;
        }
        if (videoHandler.equals("VideoHandler")
                && (videoCreationTime.equals(new Date(0)) || videoCreationTime.equals(MAC_EPOCH))) {
            // Re-encoded
            return true;
        }
        return false;
    }

    private static int calculateWholeBitrate(Mp4Parser mp4, long filesize) {
        if (mp4.isInvalid()) {
            return 0;
        }
        long durationInSeconds = mp4.getDurationInSeconds();
        int mdatOffset = mp4.getMdatOffset();
        if (durationInSeconds == 0) {
            return 0;
        }
        return (int)((filesize - mdatOffset) * 8 / durationInSeconds / 1000);
    }

    private static void load() {
        if (!loaded) {
            loadCore();
        }
    }
    private static synchronized void loadCore() {
        if (loaded) return;
        cache.clear();
        if (!DATA_FILE.exists()) {
            try {
                File parent = DATA_FILE.toPath().getParent().toFile();
                if (!parent.exists()) {
                    parent.mkdir();
                }
                DATA_FILE.createNewFile();
            } catch (IOException e) {
                Logger.error(e);
                return;
            }
        }
        int version = 0;
        try (BufferedReader br = new BufferedReader(new FileReader(DATA_FILE))) {
            String line;
            if ((line = br.readLine()) != null) {
                Matcher m;
                line = line.trim();
                if ((m = VERSION_LINE_PATTERN.matcher(line)).matches()) {
                    version = Integer.parseInt(m.group(1));
                } else if ((m = DATA_LINE_PATTERN_VER1.matcher(line)).matches()) {
                    // version 0 対応はそのうち消す
                    Entry entry = new Entry(Boolean.parseBoolean(m.group(2)), 0, new Date());
                    cache.put(m.group(1), entry);
                    num2id.put(m.group(1).substring(2), m.group(1));
                }
            }
            if (version == ENGINE_VERSION) {
                while ((line = br.readLine()) != null) {
                    Matcher m = DATA_LINE_PATTERN.matcher(line);
                    if (m.matches()) {
                        boolean reencoded = Boolean.parseBoolean(m.group(2));
                        int bitrate = Integer.parseInt(m.group(3));
                        Date checkAt = new Date(Integer.parseInt(m.group(4)) * 1000L);
                        Entry entry = new Entry(reencoded, bitrate, checkAt);
                        cache.put(m.group(1), entry);
                        num2id.put(m.group(1).substring(2), m.group(1));
                    }
                }
            } else if (version == 0 || version == 1) {
                while ((line = br.readLine()) != null) {
                    Matcher m = DATA_LINE_PATTERN_VER1.matcher(line);
                    if (m.matches()) {
                        Entry entry = new Entry(Boolean.parseBoolean(m.group(2)), 0, new Date());
                        cache.put(m.group(1), entry);
                        num2id.put(m.group(1).substring(2), m.group(1));
                    }
                }
            } else if (version == 2 || version == -2) {
                while ((line = br.readLine()) != null) {
                    Matcher m = DATA_LINE_PATTERN_VER2.matcher(line);
                    if (m.matches()) {
                        String videoId = m.group(1);
                        boolean reencoded = Boolean.parseBoolean(m.group(2));
                        int bitrate = Integer.parseInt(m.group(3));
                        // 影響範囲が一般の動画に広がったときには
                        // 条件から !videoId.startsWith("so") を外す
                        if (!videoId.startsWith("so") || reencoded || version == -2) {
                            Entry entry = new Entry(reencoded, bitrate, new Date());
                            cache.put(videoId, entry);
                            num2id.put(videoId.substring(2), videoId);
                        }
                    }
                }
            }
        } catch (NumberFormatException | IOException e) {
            Logger.error(e);
        }

        if (version != ENGINE_VERSION) {
            save();
        }
        loaded = true;
    }

    private static synchronized void save() {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(DATA_FILE, false))) {
            bw.write("version," + ENGINE_VERSION);
            bw.newLine();

            for (Map.Entry<String, Entry> e : cache.entrySet()) {
                String videoId = e.getKey();
                Entry info = e.getValue();
                bw.write(videoId + "," + info.reencoded + "," + info.bitrate + "," + info.checkAt.getTime()/1000L);
                bw.newLine();
            }
        } catch (IOException e) {
        }
    }

    private static synchronized void append(String videoId, Entry entry) {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(DATA_FILE, true))) {
            bw.write(videoId + "," + entry.reencoded + "," + entry.bitrate + "," + entry.checkAt.getTime()/1000L);
            bw.newLine();
        } catch (IOException e) {
        }
    }
}
