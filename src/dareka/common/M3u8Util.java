package dareka.common;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class M3u8Util {

    // > RFC 8216 HTTP Live Streaming.
    // これに基くm3u8ファイルのユーティリティー.
    // > Blank lines are ignored.

    // - 改行を含まない1行だけの文字列からURI=""表現の中身を抽出する.
    // - 空のURI attributeにはマッチしない.
    private static final Pattern M3U8_URL_PATTERN = Pattern.compile(
        "(?<=[,:]URI=\")([^\"]+)(?=\")");

    // - m3u8表現のcontentからurl部を抜きだして、それをsfilterに渡す.
    //   その結果をcontentに戻す. 機能型文字列replace処理.
    // - sfilter: f(url,line) => newurl;
    // - sfilterがf(url,line)=>urlならcontentは不変.
    // - URI=""のような空URLはsfilterを通らない.
    public static String replaceURL
    (String content, BiFunction<String,String,String> sfilter) {

        StringBuilder sb = new StringBuilder(content.length() * 2);

        for (String line : content.split("\n")) {
            if (line.isEmpty()) {
                sb.append("\n");
                continue;
            };

            if (! line.startsWith("#")) {
                sb.append(sfilter.apply(line, line));
                sb.append("\n");
                continue;
            };

            if (! line.startsWith("#EXT")) {
                sb.append(line);
                sb.append("\n");
                continue;
            };

            Matcher m = M3U8_URL_PATTERN.matcher(line);
            if (! m.find()) {
                sb.append(line);
                sb.append("\n");
                continue;
            };

            String left = line.substring(0, m.start(1));
            String right = line.substring(m.end(1));
            sb.append(left);
            sb.append(sfilter.apply(m.group(1), line));
            sb.append(right);
            sb.append("\n");
        };

        return sb.toString();
    };

    // m3u8 contentに含まれるURLのsearch部に引数searchを追加する.
    public static String injectURLSearch(String content, String search) {
        return replaceURL(content, (url, line) -> {
                if (url.contains("?")) {
                    return url + "&" + search;
                };
                return url + "?" + search;
            });
    };

    public static ArrayList<String> getUrlList(String content) {

        ArrayList<String> a = new ArrayList<>();

        for (String line : content.split("\n")) {
            if (line.isEmpty()) {
                continue;
            };

            if (! line.startsWith("#")) {
                a.add(line);
                continue;
            };

            if (! line.startsWith("#EXT")) {
                continue;
            };

            Matcher m = M3U8_URL_PATTERN.matcher(line);
            if (! m.find()) {
                continue;
            };

            a.add(m.group(1));
        };
        return a;
    };

    private static final Pattern AUDIO_PATTERN = Pattern.compile
        ("[,:]AUDIO=\"([^\"]*)\"");
    private static final Pattern CODECS_PATTERN = Pattern.compile
        ("[,:]CODECS=\"([^\",]*),([^\",]*)\"");
    // - group(1): basename without extension.
    // - group(2): extension.
    private static final Pattern URL_BASENAME = Pattern.compile
        ("^(?:[^?]*/)?([^?/.]*)([.][^?/]*)(?:[?].*)?$");
    /**
     * - audioSrcIdとvideoSrcIdに対応するコーデックをmapにして返す.
     * - 例: buildSrcIdToCodecsMap(masterm3u8, "a:", "v:")
     *   => {
     *     "v:video-h264-144p":   "avc1.4d401e",
     *     "v:video-h264-360p":   "avc1.4d401e",
     *     "v:video-h264-480p":   "avc1.4d4020",
     *     "v:video-h264-720p":   "avc1.4d4020",
     *     "a:audio-aac-128kbps": "mp4a.40.2",
     *     "a:audio-aac-64kbps":  "mp4a.40.2"
     *   }
     * - videoPrefix, audioPrefix: audioとvideoのsrcIdが衝突する可能性がある場合や、
     *   文字列ベースで区別したい場合に指定する.
     */
    public static Map<String,String> buildSrcIdToCodecMap
    (String masterm3u8, String audioPrefix, String videoPrefix) {

        Map<String,String> a = new HashMap<>();
        String videoCodec = null;
        for (String line: masterm3u8.split("\n")) {
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                Matcher audio = AUDIO_PATTERN.matcher(line);
                Matcher codecs = CODECS_PATTERN.matcher(line);
                if (!audio.find() || !codecs.find()) {
                    continue;
                };
                videoCodec = codecs.group(1);
                String audioCodec = codecs.group(2);
                String audioSrcId = audio.group(1);
                a.put(audioPrefix + audioSrcId, audioCodec);
                continue;
            };
            if (line.isEmpty()
                // - 空白文字で始まる行は規格外. 無視する.
                || line.startsWith(" ")
                || line.startsWith("\t")
                || line.startsWith("#")) {
                continue;
            };
            // - line is url.
            Matcher m = URL_BASENAME.matcher(line);
            if (!m.find()) {
                continue;
            };
            String videoSrcId = m.group(1);
            a.put(videoPrefix + videoSrcId, videoCodec);
        };
        return a;
    };

    public static byte[] buildMasterM3u8ForSaving
    (String audioCodec, String videoCodec) {
        // - キャッシュ保存用master.m3u8内容を作る.
        String n = "\n";
        String s = "#EXTM3U" + n +
            "#EXT-X-VERSION:6" + n +
            "#EXT-X-INDEPENDENT-SEGMENTS" + n +
            "#EXT-X-MEDIA:" +
            "TYPE=AUDIO," +
            "GROUP-ID=\"audio1\"," +
            "NAME=\"Main Audio\"," +
            "DEFAULT=YES," +
            "URI=\"audio.m3u8\"" + n +
            "#EXT-X-STREAM-INF:" +
            "BANDWIDTH=1," +
            "CODECS=\"" + videoCodec + "," + audioCodec + "\"," +
            "AUDIO=\"audio1\"" + n +
            "video.m3u8" + n +
            "";
        return s.getBytes(StandardCharsets.UTF_8);
    };

    /**
     * master.m3u8から最初に見つかるcodecs文字列を返す.
     * return [video codec, audio codec];
     * 見つからない場合は各要素はnull.
     */
    public static String[] getCodecsFromFile(Path path) {
        String[] result = new String[2];
        result[0] = null;
        result[1] = null;
        try(Stream<String> linelist = Files.lines(path, StandardCharsets.UTF_8)) {
            var iterator = linelist.iterator();
            while (iterator.hasNext()) {
                String line = iterator.next();
                if (line.startsWith("#EXT-X-STREAM-INF:")) {
                    Matcher codecs = CODECS_PATTERN.matcher(line);
                    if (!codecs.find()) {
                        continue;
                    };
                    result[0] = codecs.group(1);
                    result[1] = codecs.group(2);
                    return result;
                };
            };
        } catch (IOException e) {
            Logger.info("read error: " + path);
        };
        return result;
    };

    /**
     * - version 2025-03-18以前(これ自体を含まない)のバージョンで生成されたmaster.m3u8
     *   を修正する.
     * - 2025-03-18に追加されたらしいニコニコ動画の複数音声仕様にnicocacheが対応出来て
     *   いなかったために、異常なマスタープレイリストが生成されていた.
     *
     * - 当時も修正現在もmaster.m3u8には一つのvideo.m3u8とaudio.m3u8のみを記述する.
     * - "#EXT-X-MEDIA:TYPE=AUDIO"行が2つ以上あるものが生成されていたため、その場合は
     *   修正し結果をpathに書き込む.
     * - 修正後の文字列をreturnする.
     * - 修正が必要ない場合はcontentをそのままreturnする.
     * - 2年ぐらいしたらこのコードは削除してもいいだろう.
     */
    public static String fix20250325DoubleAudio(String content, File path) {
        String urlpart="https://";
        if (0 > content.indexOf(urlpart)) {
            return content;
        };
        String key = "#EXT-X-MEDIA:TYPE=AUDIO";
        int p1 = content.indexOf(key);
        if (0 > p1) {
            return content;
        };
        int p2 = p1 + key.length();
        int p3 = content.indexOf(key, p2);
        if (0 > p3) {
            return content;
        };
        int p4 = p3 + key.length();
        int p5 = content.indexOf('\n', p4);
        if (0 <= p5) {
            p5 = p5 + 1;
        }
        else {
            p5 = content.length();
        };
        String newContent = content.substring(0, p3) + content.substring(p5);

        FileUtil.copy(newContent, path);
        Logger.info("fix 2025-03-25 double audio: " + path);

        return newContent;
    };
};
