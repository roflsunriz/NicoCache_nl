package dareka.processor.impl;

import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import dareka.common.Logger;
import dareka.common.regex.JavaMatcher;
import dareka.common.regex.JavaPattern;
import dareka.extensions.Rewriter;
import dareka.processor.HttpResponseHeader;

public class SearchRewriter implements Rewriter {
    // Rewriter interface
    static final Pattern SUPPORTED_PATTERN = Pattern
            .compile("^https?://(?:www|nine)\\.nicovideo\\.jp/(?:search|tag)/([-0-9A-Za-z%+\\.]+)(\\?.*)?$");

    static final Pattern TAG_SEARCH_PATTERN = Pattern
            .compile("https?://(?:www|nine)\\.nicovideo\\.jp/tag/");

    @Override
    public Pattern getRewriterSupportedURLAsPattern() {
        return SUPPORTED_PATTERN;
    }

    private String[] writeItem(VideoDescriptor video, String filename) {
        String altid = Cache.videoDescriptorToAltId(video);
        boolean isLow = video.isLow();
        String smid = video.getId();
        String title = Cache.getTitleFromFilename(filename);
        if (title == null) {
            title = "nicocache-unknown-title";
        }
        String background = isLow ? "#fff7f7" : "#f7ffff";
        Cache cache = new Cache(video);
        String extension = cache.getPostfix();

        // 音楽抽出用のリンク
        String mp3 = "";
        if (extension.equals(Cache.FLV) ||
            (extension.equals(Cache.MP4) /* && new File("MP4Box.exe").exists() */) ||
            (extension.equals(Cache.SWF) /* && new File("swfextract.exe").exists() */)
            ) {
            mp3 = "<a href=\"/cache/" + altid + "/audio\">音声</a>・";
        }

        String thumbBlock =
            "<p class=\"font12\" style=\"font-weight:bold;\">" + smid + "</p>\r\n" +
            "<a href=\"watch/" + smid + "\"><img alt=\"" + title + "\" " +
            "src=\"http://tn-skr.smilevideo.jp/smile?i=" + smid.substring(2) +
            "\" width=\"130\" height=\"100\" style=\"border:solid 2px #333\"></a>\r\n";
        String descBlock =
            "<p class=\"font12\"><span>"+
            "<a class=\"cacheResultTitle\" href=\"watch/" + smid + "\">" + title + "</a></span><br>\r\n" +
            "<span>キャッシュ " + extension +" ...</span></p>\r\n" +
            "<span></span><p class=\"font12\">\n" +
                "<a href=\"/cache/" + altid + "/movie\">保存</a>・" +
                mp3 +
                "<a href=\"/cache/" + smid + ".xml\">コメ</a>・" +
                "<a href=\"/cache/rm?" + altid + "\" onclick=\"return confirm('消しますよ？');\">削除</a>\r\n" +
            "</p>\r\n";

        String[] ret = { thumbBlock, descBlock, background };
        return ret;
    }

    // 該当するリクエストの際に呼ばれる
    @Override
    public String onMatch(Matcher match, HttpResponseHeader responseHeader, String content)
            throws IOException {
        if (!Boolean.getBoolean("useSearchExtension") ||
             Boolean.getBoolean("disableSearchRewriter") ||
                (TAG_SEARCH_PATTERN.matcher(match.group(0)).lookingAt() &&
                        !Boolean.getBoolean("insertSearchResultToTagPage"))) {
            return content;
        }
        // 1ページ目なら表示
        if (match.group(2) != null && match.group(2).contains("page=")) {
            return content;
        }

        String query = Cache.tidyTitle(URLDecoder.decode(match.group(1), "UTF-8"));
        Map<VideoDescriptor, File> matched = searchVideo(query, false, true);
        if (matched.isEmpty()) {
            return content;
        }

// 検索結果埋め込み用パターン
        JavaPattern pattern;
        String blockString;
        int index = -1;
        while (true) {
            String confName = "SearchExtConf";
            if (index >= 0)
                confName += index;
            index++;
            JavaPattern[] jp = EasyRewriter.getMatch(confName);
            String[] r = EasyRewriter.getReplace(confName);
            JavaPattern require = EasyRewriter.getRequire(confName);
            boolean notRequire = EasyRewriter.getNotRequire(confName);
            if (jp != null) {
                pattern = jp[0];
                blockString = r[0];
                if (require == null || (require.matcher(content).find() ^ notRequire)) {
                    break;
                }

            } else {
                Logger.info("no SearcExtConf match. do nothing...");
                return content;
            }
        }

        StringBuilder sb = new StringBuilder();
        int resultMax = Integer.getInteger("searchResultMax", Integer.MAX_VALUE);
        int resultCount = 0;
        String buf = blockString;
        for (Map.Entry<VideoDescriptor, File> e : matched.entrySet()) {
            if (resultCount >= resultMax) {
                break;
            }
            String[] item = writeItem(e.getKey(), e.getValue().getName());
            assert item != null && item.length == 3;
            buf = buf.replaceFirst("<thumbBlock>", Matcher.quoteReplacement(item[0]))
                     .replaceFirst("<descBlock>" , Matcher.quoteReplacement(item[1]))
                     .replaceFirst("<colorBlock>", Matcher.quoteReplacement(item[2]));
            if (!buf.contains("<thumbBlock>")) {
                // <thumbBlock>が無くなったら書き出してリセット
                sb.append(buf); buf = blockString;
            }
            resultCount++;
        }
        if (resultCount == 0) {
            return content;
        } else if (buf != blockString) {
            sb.append(buf); // 残りがあれば書き出す
        }
        // 埋め込む
        JavaMatcher jm = pattern.matcher(content);
        if (jm.find()) {
            String resultMes = resultCount + "件";
            if (resultCount >= resultMax) {
                resultMes = "検索結果が多すぎます。先頭" + resultMax + "件を表示します";
            }
            content = jm.replaceFirst("$0" + Matcher.quoteReplacement(
                "<div><a href=\"javascript:void(0)\" onclick=\"var ele=document.getElementsByName('cacheResult');for(var i=0; i<ele.length; i++){ele[i].style.display=(ele[i].style.display!='' ? '' : 'none');}\">" +
                "<b>NicoCacheのキャッシュからの検索結果  （" + resultMes + "）</b></a></div>" + sb));
        }
        return content;
    }

    /**
     * キャッシュを検索してマッチした結果のid2Fileマップを生成して返す。
     * 通常検索の場合は、互換性維持の為にフォルダを含めないファイル名を対象に
     * 単語単位(空白文字区切り・大文字小文字無視)のOR検索を行う。
     * 正規表現検索の場合は、フォルダを含めたパスを対象に部分一致検索を行う。
     *
     * @param query 検索文字列
     * @param regex 正規表現検索の場合はtrue、通常検索の場合はfalse
     * @param desc マップの格納順を降順にする場合はtrue、昇順ならfalse
     * @return マッチしたキャッシュのソート済みid2Fileマップ
     * @since NicoCache_nl+110110mod
     */
    @Deprecated
    public static SortedMap<String, File> search(String query, boolean regex, boolean desc) {
        SortedMap<String, File> matched = new TreeMap<>(
                Cache.getSmidComparator(desc));
        for (Map.Entry<VideoDescriptor, File> e : searchVideo(query, regex, desc).entrySet()) {
            VideoDescriptor video = e.getKey();
            matched.put(CacheManager.videoDescriptorToAltId(video), e.getValue());
        }
        return matched;
    }

    /**
     * キャッシュを検索してマッチした結果のvideo2Fileマップを生成して返す。
     * 通常検索の場合は、互換性維持の為にフォルダを含めないファイル名を対象に
     * 単語単位(空白文字区切り・大文字小文字無視)のOR検索を行う。
     * 正規表現検索の場合は、フォルダを含めたパスを対象に部分一致検索を行う。
     *
     * @param query 検索文字列
     * @param regex 正規表現検索の場合はtrue、通常検索の場合はfalse
     * @param desc マップの格納順を降順にする場合はtrue、昇順ならfalse
     * @return マッチしたキャッシュのソート済みvideo2Fileマップ
     */
    public static SortedMap<VideoDescriptor, File> searchVideo(String query, boolean regex, boolean desc) {
        SortedMap<VideoDescriptor, File> matched = new TreeMap<>(
                Cache.getVideoDescriptorComparator(desc));
        Map<VideoDescriptor, File> caches = Cache.getVideo2File();
        if (caches.isEmpty()) {
            return matched;
        }
        if ("low".equals(query)) { // low で検索したときのみ特殊処理
            for (Map.Entry<VideoDescriptor, File> e : caches.entrySet()) {
                if (e.getKey().isLow()) {
                    matched.put(e.getKey(), e.getValue());
                }
            }
        } else if (regex) {
            try {
                Pattern queryPattern = Pattern.compile(query);
                for (Map.Entry<VideoDescriptor, File> e : caches.entrySet()) {
                    if (isMatch(queryPattern, e.getValue())) {
                        matched.put(e.getKey(), e.getValue());
                    }
                }
            } catch (PatternSyntaxException e) {
                Logger.debugWithThread(e);
                Logger.warning("regex search error: " + query);
            }
        } else {
            String[] queryWords = query.toLowerCase().split("\\s+");
            for (Map.Entry<VideoDescriptor, File> e : caches.entrySet()) {
                if (isMatch(queryWords, e.getValue())) {
                    matched.put(e.getKey(), e.getValue());
                }
            }
        }
        return matched;
    }

    // 単語OR検索
    private static boolean isMatch(String[] queryWords, File cacheFile) {
        String title = cacheFile.getName();
        String titleLow = Cache.tidyTitle(title.toLowerCase());
        for (String word : queryWords) {
            if (word.startsWith("-")) {
                // NOT
                if (titleLow.contains(word.substring(1)))
                    return false;
            } else if (!titleLow.contains(word)) {
                // AND
                return false;
            }
        }
        return true;
    }

    // 正規表現検索
    private static boolean isMatch(Pattern queryPattern, File cacheFile) {
        String path = Cache.getPathFromFile(cacheFile);
        if (path != null) {
            return queryPattern.matcher(path).find();
        }
        return false;
    }
}
