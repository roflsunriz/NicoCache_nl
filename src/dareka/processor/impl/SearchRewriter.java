package dareka.processor.impl;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import dareka.common.Logger;
import dareka.extensions.Rewriter;
import dareka.processor.HttpResponseHeader;

public class SearchRewriter implements Rewriter {
    // 公開ABIを維持するためRewriter実装自体は残すが、旧検索HTML書き換えは無効化する。
    private static final Pattern DISABLED_PATTERN = Pattern.compile("(?!)");

    @Override
    public Pattern getRewriterSupportedURLAsPattern() {
        return DISABLED_PATTERN;
    }

    @Override
    public String onMatch(Matcher match, HttpResponseHeader responseHeader, String content)
            throws IOException {
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
