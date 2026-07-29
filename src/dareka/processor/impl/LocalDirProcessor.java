package dareka.processor.impl;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dareka.Main;
import dareka.common.Logger;
import dareka.processor.HttpHeader;
import dareka.processor.HttpRequestHeader;
import dareka.processor.Processor;
import dareka.processor.Resource;
import dareka.processor.StringResource;

// [nl] localフォルダ・wrapper関係のアドレス処理
public class LocalDirProcessor implements Processor {
    // 任意のMethodにマッチ
    private static final String[] SUPPORTED_METHODS = new String[] { null };
    private static final Pattern LOCAL_DIR_PATTERN = Pattern
        .compile("^https?://[^/]+\\.nicovideo\\.jp/((?:flv|nico)player\\.swf|flvplayer_wrapper\\.swf|flv_booster\\.swf|local(/?|/[^?]*|\\w+\\.\\w+))(\\?.*)?$");

    // - 2024-12-16 追記. 変更前の表現は次のようなものだった.
    //   ..."local(/?|/[-_\\w./]+|\\w+\\.\\w+)"...
    // - 括弧内3番目(最後)の表現によって"https://nicovideo.jp/localabc.txt"のような
    //   "/"が抜けたurlもキャッチする.
    // - 一応残す. この仕様は不要ではないか？

    @Override
    public String[] getSupportedMethods() {
        return SUPPORTED_METHODS;
    }

    @Override
    public Pattern getSupportedURLAsPattern() {
        return LOCAL_DIR_PATTERN;
    }

    @Override
    public String getSupportedURLAsString() {
        return null;
    }

    @Override
    public Resource onRequest(HttpRequestHeader requestHeader, Socket browser)
            throws IOException {
        // GET以外も捕まえてサーバに存在しないURLへのリクエストが飛ぶのを防ぐ
        if (!HttpHeader.GET.equals(requestHeader.getMethod())
                && !HttpHeader.HEAD.equals(requestHeader.getMethod())) {
            return StringResource.getMethodNotAllowed();
        }

        String uri = requestHeader.getURI();
        Matcher m = LOCAL_DIR_PATTERN.matcher(uri);
        if (m.find()) {
            File file = null;
            String path = m.group(1);
            if (path.equals("flvplayer_wrapper.swf")) {
// 常にlocalから返すように変更(swfConvert04)
                file = getLocalFile(path, null);
            }
            //ローカルにnicoplayer.swfがある時はそれを利用する(夏.07)
            else if (path.equals("flv_booster.swf") || path.equals("nicoplayer.swf")) {
                // booster
                file = getLocalFile(path, null);
            }
            else if ("/list.js".equals(m.group(2))) {
                file = getLocalFile(m.group(2), m.group(3));
                if (file == null || !file.exists()) {
                    file = getLocalFile("list.js.default", null);
                }
            }
            else if (m.group(2) != null && !m.group(2).equals("")
                                        && !m.group(2).equals("/")) {
                file = getLocalFile(m.group(2), m.group(3));
                if (file == null) {
                    Logger.info("(LocalDirProcessor)invalid path: " + m.group(2));
                    return StringResource.getNotFound();
                };
            }
            // fileが存在しない・読めない場合の処理はここから先が行なう.
            return Main.getRewriterProcessor().localRewriter(
                    uri, file, requestHeader);
        }
        return StringResource.getNotFound();
    }

    /**
     * LocalDirProcessorがサポートする(=処理する)URLか？
     * @param url 判定するURL文字列
     * @return サポートするURLならtrue
     */
    public static boolean isSupportedURL(String url) {
        return LOCAL_DIR_PATTERN.matcher(url).matches();
    }

    /**
     * @param path パーセントエンコード表現を含むファイルパス. null不可.
     *        "/"で分割後にUTF-8としてデコードしてFileコンストラクタへ渡される.
     * @param query "?"から始まるパラメーター. null可. 使わない.
     */
    public static File getLocalFile(String path, String query) {
        if (path.startsWith("/")) {
            path = path.substring(1);
        };

        String list[] = path.split("/");

        if (list.length == 0) {
            return null;
        };

        try {
            for (int i = 0, end = list.length; i < end; ++i) {
                list[i] = URLDecoder.decode(list[i], "UTF-8");
                if (list[i].isEmpty()
                        || ".".equals(list[i])
                        || "..".equals(list[i])
                        || list[i].indexOf('/') >= 0
                        || list[i].indexOf('\\') >= 0
                        || list[i].indexOf('\0') >= 0) {
                    return null;
                }
            };
        }
        catch (UnsupportedEncodingException | IllegalArgumentException e) {
            Logger.warning("(LocalDirProcessor)failed to decode url: " + path);
            return null;
        };

        List<Path> candidates = new ArrayList<>();
        for (File rootFile : localRoots()) {
            Path root = rootFile.toPath().toAbsolutePath().normalize();
            Path candidate = root;
            for (String segment : list) {
                candidate = candidate.resolve(segment);
            }
            candidate = candidate.normalize();
            if (!candidate.startsWith(root)) {
                return null;
            }
            if (!candidates.contains(candidate)) {
                candidates.add(candidate);
            }
        }
        for (Path candidate : candidates) {
            if (Files.exists(candidate)
                    || Files.exists(Path.of(candidate.toString() + ".gz"))) {
                return candidate.toFile();
            }
        }
        return candidates.isEmpty() ? null : candidates.get(0).toFile();
    }

    private static File[] localRoots() {
        return new File[] {
                UserDataPaths.userFile("local"),
                UserDataPaths.applicationFile("local")
        };
    }
}
