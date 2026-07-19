package dareka.processor.impl;


import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Formatter;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

import dareka.Main;
import dareka.NLConfig;
import dareka.common.CloseUtil;
import dareka.common.Config;
import dareka.common.ConfigObserver;
import dareka.common.FileUtil;
import dareka.common.Logger;
import dareka.common.DiskFreeSpace;
import dareka.common.TextUtil;
import dareka.common.regex.JavaMatcher;
import dareka.common.regex.JavaPattern;
import dareka.extensions.NLFilterListener;
import dareka.extensions.RequestFilter;
import dareka.extensions.Rewriter;
import dareka.processor.HttpHeader;
import dareka.processor.HttpRequestHeader;
import dareka.processor.HttpResponseHeader;

// [nl] スクリプト埋め込み
public class EasyRewriter implements Rewriter, RequestFilter, ConfigObserver {

    private static final Pattern NULL_PATTERN = Pattern.compile("(?!)");
    private static final EasyRewriter INSTANCE_SYS = new EasyRewriter();
    private static final EasyRewriter INSTANCE_USR = new EasyRewriter();

    private static ArrayList<UserFilter> defaultList = new ArrayList<>();
    private static boolean debugMode;
    private static String lastDebugMes;
    private static int lastDebugMesCount;
    private static long checkInterval;
    private static TreeSet<String> idPool = new TreeSet<>();

    static class FilterLists {
        ArrayList<UserFilter> replace = new ArrayList<>();
        ArrayList<UserFilter> config  = new ArrayList<>();
        ArrayList<UserFilter> header  = new ArrayList<>();
    }
    private FilterLists filterLists = new FilterLists();
    private ArrayList<FilterFile> filterFiles = new ArrayList<>();
    private Pattern supportedURLs = NULL_PATTERN;
    private volatile long nextCheckTime;

    static {
        Config.addObserver(INSTANCE_SYS);
    }

    @Override
    public void update(Config config) {
        long n = Long.getLong("nlFilterCheckInterval", 1000);
        checkInterval = n > 0 ? n : 0;
    }

    // [Config]
    private static HashMap<String, UserFilter> confMap = newConfMap();
    private static HashMap<String, UserFilter> newConfMap() {
        HashMap<String, UserFilter> map = new HashMap<>();
        for (UserFilter u : INSTANCE_SYS.filterLists.config) {
            map.put(u.name, u);
        }
        for (UserFilter u : INSTANCE_USR.filterLists.config) {
            map.put(u.name, u);
        }
        return map;
    }

    // nlFilter拡張用のExtension
    private static ArrayList<NLFilterListener> extensions = new ArrayList<>();

    static boolean addListener(NLFilterListener f) {
        return extensions.add(f);
    }

    static String getExtensionVariable(String name, NLFilterListener.Content content) {
        String replaced;
        for (NLFilterListener f : extensions) {
            if ((replaced = f.nlVarReplace(name, content)) != null) {
                return replaced;
            }
        }
        return null;
    }

    private EasyRewriter() {} // avoid instantiation.

    public static EasyRewriter getInstance_sys() {
        return checkAndLoad(INSTANCE_SYS);
    }

    public static EasyRewriter getInstance() {
        return checkAndLoad(INSTANCE_USR);
    }

    private static EasyRewriter checkAndLoad(EasyRewriter instance) {
        long currentTime = System.currentTimeMillis();
        if (currentTime > instance.nextCheckTime) {
            // 更新チェックを必要とする全スレッドを一旦ロックする
            // ロック中に他のスレッドが更新チェックしたなら何もしない
            synchronized (instance) {
                if (currentTime > instance.nextCheckTime) {
                    instance.load();
                    instance.nextCheckTime = currentTime + checkInterval;
                }
            }
        }
        return instance;
    }

    static final Pattern IGNORE_FILENAME_PATTERN = Pattern.compile(
            "\\$|^(?:コピー|Copy)(?:| \\(\\d+\\)) (?:〜|of) ");

    private synchronized void load() {
        ArrayList<FilterFile> newFilterFile = new ArrayList<>();
        if (this == INSTANCE_SYS) {
            if (!addFilterFile("nlFilter_sys.txt", newFilterFile)) {
                Logger.warning("no nlFilter_sys.txt found. please check...");
            }
        } else {
            // ディレクトリから検索、追加(名前順)
            File nlFilters = new File("nlFilters");
            if (nlFilters.isDirectory()) {
                String[] filterArray = nlFilters.list((dir, name) -> {
                    if (IGNORE_FILENAME_PATTERN.matcher(name).find() ||
                            NLConfig.find("nlFilterIgnore", name)) {
                        return false;
                    }
                    return name.endsWith(".txt");
                });
                if (filterArray != null) {
                    Arrays.sort(filterArray);
                    for (String fname : filterArray) {
                        newFilterFile.add(new FilterFile(nlFilters, fname));
                    }
                }
            }
            // 元のnlFilterは最後に追加
            addFilterFile("nlFilter.txt", newFilterFile);
        }

        if (newFilterFile.equals(filterFiles)) {
            boolean modified = false;
            for (FilterFile f : newFilterFile) {
                if (modified = f.isModified()) {
                    break;
                }
            }
            if (!modified) {
                if (this == INSTANCE_USR && filterLists.replace.isEmpty()) {
                    filterLists.replace.addAll(defaultList);
                    updateSupportedURLs();
                }
                return;
            }
        }

        FilterLists newFilterLists = new FilterLists();
        long start = System.nanoTime();
        if (this == INSTANCE_SYS) {
            Logger.info("Loading System Filter:");
        } else {
            Logger.info("Loading User Filters:");
            debugMode = false;
            newFilterLists.replace.addAll(defaultList);
        }
        LST.deleteObservers();
        idPool.clear();
        for (FilterFile f : newFilterFile) {
            Logger.info(f.getPath());
            if (!f.isModified()) {
                for (FilterFile o : filterFiles) {
                    if (f.equals(o)) {
                        f.parsed.addAll(o.parsed);
                        break;
                    }
                }
            }
            if (f.parsed.isEmpty()) {
                parseFilterFile(f);
            }
            for (UserFilter u : f.parsed) {
                if (f.getName().equals("nlFilter.txt")) {
                    u.replaceDelay = u.section == UserFilter.REPLACE;
                }
                addFilter(u, newFilterLists);
            }
        }
        if (this == INSTANCE_USR) {
            Logger.info("[%s] Filters Loading Time: %,dms",
                    new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date()),
                    (System.nanoTime() - start) / 1000000);
        }
        filterFiles = newFilterFile;
        filterLists = newFilterLists;
        confMap = newConfMap();

        updateSupportedURLs();
    }

    // フィルタファイルが存在するならリストに追加する
    private boolean addFilterFile(String name, ArrayList<FilterFile> list) {
        FilterFile f = new FilterFile(name);
        if (!f.isFile()) {
            return false;
        }
        return list.add(f);
    }

    // フィルタ読み込み
    private void parseFilterFile(FilterFile file) {
        LineNumberReader reader = null;
        String readed;
        try {
            file.parsed.clear();
            reader = file.getReader();
            int state = -1;
            boolean append = false, specialAppend = false;
            UserFilter u = null;
            StringBuilder sb = new StringBuilder(256);
            while ((readed = reader.readLine()) != null) {
                try {
                    if (state != -1 && u.trimNeeded) {
                        readed = readed.trim();
                    }
                    if (state == -1) {
                        if ((u = parseSection(readed)) != null)
                            state = 0;
                        append = specialAppend = false;
                    } else if (state == 0) {
                        if (readed.startsWith("#")) { // コメントアウト
                            continue;
                        } else if (readed.equalsIgnoreCase("Match<")) {
                            sb.setLength(0);
                            state = 1;
                        } else if (readed.equalsIgnoreCase("Replace<")) {
                            sb.setLength(0);
                            state = 2;
                        } else if (readed.equalsIgnoreCase("Append<")) {
                            if (u.section == UserFilter.STYLE) {
                                parseMatch(u, "(?=</head>)");
                            } else if (u.section == UserFilter.SCRIPT) {
                                parseMatch(u, "(?=</body>)");
                            }
                            append = true;
                            u.each = false;
                            u.replaceOnly = true;
                            sb.setLength(0);
                            state = 2;
                        } else if (!parseOption(u, readed)) {
                            errorLog("Syntax", file.getPath(), reader, readed);
                            state = -1;
                        }
                    } else {
                        if (readed.equals(">")) {
                            switch (state) {
                            case 1:
                                parseMatch(u, sb.toString());
                                state = 0;
                                break;
                            case 2:
                                 // 最後の改行を削除
                                if (!u.each && sb.length() > 0) {
                                    sb.delete(sb.length() - 2, sb.length());
                                }
                                if (specialAppend) {
                                    u.section = UserFilter.REPLACE;
                                    u.name = "script: " + u.name;
                                    u.replaceOnly = false;
                                    sb.append("<CRLF>");
                                }
                                parseReplace(u, sb.toString());
                                state = -1;
                                setIdentifier(u, file);
                                file.parsed.add(u);
                                break;
                            }
                        }
                        if (u.each && sb.length() > 0) {
                            sb.append("\0");
                        }
                        if (append && readed.matches("https?://.*")) {
                            if (sb.length() == 0) {
                                specialAppend = true;
                            }
                            if (specialAppend) {
                                readed = modifySpecialAppend(u, readed);
                            }
                        }
                        sb.append(readed);
                        // 改行を追加(ページの改行コードは無視)
                        if (!u.each && state == 2) {
                            sb.append("\r\n");
                        }
                    }
                } catch (PatternSyntaxException e) {
                    Logger.error(e);
                    state = -1;
                }
            }
        } catch (IOException e) {
            Logger.error(e);
        } finally {
            CloseUtil.close(reader);
        }
    }

    static final Pattern LOCAL_URL_PATTERN = Pattern.compile(
            "(https?://[^/]+\\.nicovideo\\.jp/)(local/[^\\?]+)");

    // URLを読み込むタグに変換
    private String modifySpecialAppend(UserFilter u, String url) {
        Matcher m = LOCAL_URL_PATTERN.matcher(url);
        if (m.matches()) {
            url = m.group(1) + "$TS(" + m.group(2) + ")";
        }
        if (u.section == UserFilter.STYLE) {
            return "<link rel=\"stylesheet\" type=\"text/css\" href=\"" + url + "\" />";
        } else if (u.section == UserFilter.SCRIPT) {
            return "<script type=\"text/javascript\" src=\"" + url + "\"></script>";
        }
        return url;
    }

    // 全フィルタで一意のIDを設定する
    private void setIdentifier(UserFilter u, File file) {
        String id = file.getPath().replaceAll("\\\\", "/")
                .replaceAll("^nlFilters/", "").replaceAll("\\.txt$", "") + "/" +
                (u.name != null ? u.name.replaceAll("[\\*/]", "_") : "(no name)");
        u.id = id;
        int i = 2;
        while (!idPool.add(u.id)) {
            u.id = id + "#" + i++;
        }
    }

    // エラーログを表示する
    static void errorLog(
            String kind, String path, LineNumberReader reader, String readed) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(kind).append(" error");
        if (reader != null) {
            sb.append(" at line ").append(reader.getLineNumber());
        }
        if (path != null) {
            sb.append(" in ").append(path);
        }
        if (readed != null) {
            for (String s : readed.split("[\r\n\0]+")) {
                sb.append("\n => ").append(s);
            }
        }
        Logger.warning(sb.toString());
    }

    // デバッグログを表示する
    static void debugLog(UserFilter u, int indent, String mes, String name) {
        if (!debugMode && (u == null || !u.debugMode)) return;

        StringBuilder sb = new StringBuilder(256);
        if (mes != null) {
            for (int i = 0; i < indent; i++) {
                sb.append("  ");
            }
            sb.append("[Debug]").append(mes);
            if (name != null) {
                sb.append(": ").append(name);
            }
        }
        String debugMes = sb.toString();
        if (lastDebugMes == null) {
            lastDebugMes = debugMes;
        } else if (debugMes.equals(lastDebugMes)) {
            lastDebugMesCount++;
        } else {
            flushDebugLog();
            lastDebugMes = debugMes.length() > 0 ? debugMes : null;
        }
    }
    static void flushDebugLog() {
        if (lastDebugMes != null) {
            StringBuilder sb = new StringBuilder(256);
            sb.append(lastDebugMes);
            if (lastDebugMesCount > 0) {
                sb.append(" *").append(++lastDebugMesCount);
            }
            Logger.info(sb.toString());
            lastDebugMes = null;
            lastDebugMesCount = 0;
        }
    }

    // セクション開始文字列をパースしてUserFilterを生成する
    private UserFilter parseSection(String line) {
        boolean trimNeeded = false;
        if (line.startsWith("[")) {
            String trimmed = line.trim();
            if (trimNeeded = line != trimmed) {
                line = trimmed;
            }
        }
        if (line.equalsIgnoreCase("[Debug]")) {
            debugMode = true;
        } else {
            for (String s : UserFilter.SECTIONS) {
                if (line.equalsIgnoreCase(s)) {
                    UserFilter u = new UserFilter(s);
                    u.trimNeeded = trimNeeded;
                    return u;
                }
            }
        }
        return null;
    }

    // オプションをパースする
    private static boolean parseOption(UserFilter u, String line) {
        String param[] = line.trim().split("\\s*=\\s*", 2);
        if (param.length < 2) {
            return false;
        } else if (param[0].equalsIgnoreCase("Multi")) {
            u.multi = Boolean.parseBoolean(param[1]);
        } else if (param[0].equalsIgnoreCase("EachLine")) {
            u.each = Boolean.parseBoolean(param[1]);
        } else if (param[0].equalsIgnoreCase("URL")) {
            u.url = Pattern.compile("https?://(?:" + param[1] + ")");
        } else if (param[0].equalsIgnoreCase("FullURL")) {
            u.url = Pattern.compile(param[1]);
        } else if (param[0].equalsIgnoreCase("StatusCode")) {
            String[] parts = param[1].split("\\s*,\\s*");
            u.statusCodes = Arrays.stream(parts).mapToInt(Integer::parseInt).toArray();
        } else if (param[0].equalsIgnoreCase("Name")) {
            u.name = param[1];
        } else if (param[0].equalsIgnoreCase("Require")) {
            u.require = new FilterPattern(u, param[1], false);
        } else if (param[0].equalsIgnoreCase("idGroup")) {
            u.idGroupString = param[1].split("\\s*,\\s*");
            if (u.idGroupString[0].startsWith("!")) {
                u.idGroupString[0] = u.idGroupString[0].substring(1);
                u.noCache = true;
            }
            if (!u.idGroupString[0].matches("\\d+")) {
                return false; // 最初のパラメータは数字のみ許容する
            }
            u.idGroup[0] = Integer.parseInt(u.idGroupString[0]);
            if (u.idGroupString.length < 2) {
                u.idGroup[1] = 0;
            } else if (u.idGroupString[1].matches("\\d+")) {
                u.idGroup[1] = Integer.parseInt(u.idGroupString[1]);
            }
        } else if (param[0].equalsIgnoreCase("RequireHeader")) {
            u.requireHeader = new FilterPattern(u, param[1], false);
        } else if (param[0].equalsIgnoreCase("ContentType")) {
            u.contentType = new FilterPattern(u, param[1], false);
        } else if (param[0].equalsIgnoreCase("MatchLocal")) {
            u.matchLocal = Boolean.parseBoolean(param[1]);
        } else if (param[0].equalsIgnoreCase("AddList")) {
            u.addList = param[1];
        } else if (param[0].equalsIgnoreCase("AddVariable")) {
            u.addVariable = param[1];
        } else if (param[0].equalsIgnoreCase("ReplaceOnly")) {
            u.replaceOnly = Boolean.parseBoolean(param[1]);
        } else if (param[0].equalsIgnoreCase("ReplaceDelay")) {
            u.replaceDelay = Boolean.parseBoolean(param[1]);
        } else if (param[0].equalsIgnoreCase("Debug")) {
            u.debugMode = Boolean.parseBoolean(param[1]);
        } else {
            return false;
        }
        return true;
    }

    // Match文字列をパースする
    private static void parseMatch(UserFilter u, String matchStr) {
        u.match = new FilterPattern(u, matchStr, true);
    }

    // Replace文字列をパースする
    private static void parseReplace(UserFilter u, String replace) {
        replace = replace.replaceAll("<CRLF>", "\r\n").replaceAll("<TAB>", "\t");
        if (u.replaceOnly) {
            // 互換性を保つため既にエスケープされているものはエスケープしない
            replace = replace.replaceAll("(?<!\\\\)(?=\\\\[^$])", "\\\\")
                    .replaceAll("(?<!\\\\)(?=(?:\\\\\\\\)*\\$)", "\\\\");
        }
        if (u.each) {
            u.replace = replace.split("\0");
        } else {
            u.replace = new String[1];
            u.replace[0] = replace;
        }
    }

    // 読み込んだフィルタの対象URLへのPatternを作成
    private void updateSupportedURLs() {
        // まず、重複するPatternを除外
        TreeSet<String> patterns = new TreeSet<>();
        for (UserFilter u : filterLists.replace) {
            patterns.add(u.url.pattern());
        }
        // 全てのURLをORで繋げた巨大なパターンを作成
        StringBuilder sb = new StringBuilder();
        for (String s : patterns) {
            // onMatchでURLを取り出すため全マッチ(.*)が必要
            sb.append("(?:").append(s).append(".*)|");
        }
        if (sb.length() > 0) {
            supportedURLs = Pattern.compile(sb.substring(0, sb.length() - 1));
        } else {
            supportedURLs = NULL_PATTERN;
        }
    }

// 他クラスからの取得用
    public static JavaPattern[] getMatch(String name) {
        UserFilter u = confMap.get(name);
        if (u != null) {
            return u.match.patterns;
        }
        return null;
    }

    public static String[] getReplace(String name) {
        UserFilter u = confMap.get(name);
        if (u != null) {
            return u.replace;
        }
        return null;
    }

    public static Pattern getURL(String name) {
        UserFilter u = confMap.get(name);
        if (u != null) {
            return u.url;
        }
        return null;
    }

    public static JavaPattern getRequire(String name) {
        UserFilter u = confMap.get(name);
        if (u != null && u.require != null) {
            return u.require.patterns[0];
        }
        return null;
    }

    public static boolean getNotRequire(String name) {
        UserFilter u = confMap.get(name);
        if (u != null && u.require != null) {
            return u.require.not;
        }
        return false;
    }

    static boolean checkDebugMode() {
        return debugMode;
    }

    private void addFilter(UserFilter u, FilterLists lists) {
        if (u.isValid()) {
            String prefix = "", suffix = "";
            if (u.section == UserFilter.CONFIG) {
                lists.config.add(u);
                prefix = "config: ";
            } else if (u.section == UserFilter.REQUEST_HEADER) {
                if (u.match.patterns.length == u.replace.length) {
                    lists.header.add(u);
                    prefix = "header: ";
                } else {
                    Logger.warning("  ******** headerFilter needs same " +
                            "Match&Replace number at " + u.name + " ********");
                }
            } else {
                lists.replace.add(u);
                if (u.section == UserFilter.STYLE) {
                    prefix = " style: ";
                } else if (u.section == UserFilter.SCRIPT) {
                    prefix = "script: ";
                }
            }
            if (u.trimNeeded) {
                suffix = " [TRIMMED]";
            }
            if (u.debugMode) {
                suffix += " [DEBUGGING]";
            }
            Logger.info("  " + prefix + u.name + suffix);
            Logger.debug(u.id);
        } else {
            if (u.name == null) {
                Logger.warning("  ******** Filter has some errors ********");
            } else {
                Logger.warning("  ******** " + u.name + " has some errors ********");
            }
        }
    }

    // リロード時に影響を受けないフィルタ(夏.01)
    public static void addSystemFilter(String name, String uri, String match, String replace) {
        UserFilter u = new UserFilter();
        try {
            u.name = name;
            u.url = Pattern.compile(uri);
            parseMatch(u, match);
            parseReplace(u, replace);
            defaultList.add(u);
        } catch(PatternSyntaxException e) {
            Logger.warning(name + "フィルタの正規表現がおかしいです。");
        }
    }

    // Rewriter interface
    @Override
    public Pattern getRewriterSupportedURLAsPattern() {
        if (!Main.isDirectoryWatching()) {
            checkAndLoad(this);
        }
        return supportedURLs;
    }

    @Override
    public String onMatch(Matcher match, HttpResponseHeader responseHeader, String content)
            throws IOException {
        String url = match.group();
        return applyUserFilter(url, content, null, responseHeader,
                getMatchedUserFilter(url, null, responseHeader));
    }

    /**
     * 文字列に対して nlFilter を適用する
     *
     * @param url フィルタ対象の URL
     * @param content フィルタを適用する文字列
     * @see #replace(String, String, HttpRequestHeader, HttpResponseHeader)
     */
    public static String replace(String url, String content) {
        return replace(url, content, null, null);
    }

    /**
     * 文字列に対して nlFilter を適用する
     *
     * @param url フィルタ対象の URL (requestHeader を指定した場合は null も可)
     * @param content フィルタを適用する文字列
     * @param requestHeader リクエストヘッダ、不要な場合は null
     * @param responseHeader レスポンスヘッダ、不要な場合は null
     * @return フィルタ処理した文字列
     * @since NicoCache_nl+120609mod
     */
    public static String replace(String url, String content,
            HttpRequestHeader requestHeader, HttpResponseHeader responseHeader) {
        if (url == null && requestHeader != null) {
            url = requestHeader.getURI();
        }
        ArrayList<UserFilter> userFilters;
        String replaced;
        userFilters = INSTANCE_SYS.getMatchedUserFilter(
                url, requestHeader, responseHeader);
        replaced = INSTANCE_SYS.applyUserFilter(
                url, content, requestHeader, responseHeader, userFilters);
        userFilters = INSTANCE_USR.getMatchedUserFilter(
                url, requestHeader, responseHeader);
        replaced = INSTANCE_USR.applyUserFilter(
                url, content, requestHeader, responseHeader, userFilters);
        return replaced;
    }

    static final Pattern HOST_PATTERN = Pattern.compile(
            "https?://([^/]++)/?");

    // idGroupに対応した置換有無の確認用なので内容に意味は無い
    static final String[] DUMMY_REPLACES = { "0", "1", "2" };

    // RequestFilter interface
    @Override
    public int onRequest(HttpRequestHeader requestHeader) throws IOException {
        if (!Main.isDirectoryWatching()) {
            checkAndLoad(this);
        }
        String uri = requestHeader.getURI();
        boolean replaced = false;
        for (UserFilter u : filterLists.header) {
            for (int i = 0; i < u.match.patterns.length; i++) {
                if (!u.match.update(i, null)) {
                    continue;
                }
                JavaMatcher jm = u.match.patterns[i].matcher(uri);
                if (!jm.matches() || selectReplace(u, uri, jm, DUMMY_REPLACES, null) == null) {
                    continue;
                }
                if (debugMode || u.debugMode) {
                    Logger.info("[Debug] HeaderFilter : " + u.name + " : " + uri);
                }
                try {
                    uri = jm.replaceFirst(u.replace[i]);
                } catch (IndexOutOfBoundsException e) {
                    Logger.error(e);
                    Logger.warning("invalid replace: " +
                            u.replace[i] + " on " + u.name + " at " + i);
                    // エラーが起きた場合は安全のためリクエストを破棄
                    return RequestFilter.DROP;
                }
                Logger.debugWithThread(" => " + uri + " replaced");
                replaced = true;
            }
        }
        if (replaced) {
            requestHeader.setParameter("nl-URI", requestHeader.getURI());
            requestHeader.setURI(uri);
            Matcher m = HOST_PATTERN.matcher(uri);
            if (m.lookingAt()) {
                requestHeader.setMessageHeader("Host", m.group(1));
            }
        }
        return RequestFilter.OK;
    }

    /** URLにマッチするユーザーフィルタのリストを生成して返す */
    ArrayList<UserFilter> getMatchedUserFilter(String url,
            HttpRequestHeader requestHeader, HttpResponseHeader responseHeader) {
        if (url == null && requestHeader != null) {
            url = requestHeader.getURI();
        }
        ArrayList<UserFilter> matched =
            new ArrayList<>(filterLists.replace.size());
        for (UserFilter u : filterLists.replace) {
            if (!u.url.matcher(url).lookingAt()) {
                continue;
            }
            if (u.requireHeader != null && (requestHeader == null ||
                    !u.requireHeader.find(requestHeader.toString()))) {
                continue;
            }
            if (u.contentType != null && (responseHeader == null ||
                    !u.contentType.find(responseHeader.getMessageHeader(
                            HttpHeader.CONTENT_TYPE)))) {
                continue;
            }
            if (responseHeader != null) {
                int statusCode = responseHeader.getStatusCode();
                if (u.statusCodes != null) {
                    if (!Arrays.stream(u.statusCodes).anyMatch(code -> code == statusCode))
                        continue;
                } else {
                    // For backward compatibility
                    if (statusCode != 200 && statusCode != 403 && statusCode != 404 && statusCode != 503)
                        continue;
                }
            }
            if (LocalDirProcessor.isSupportedURL(url)) {
                // local以外もマッチする場合は互換性維持のためMatchLocalを確認
                // local限定でマッチする場合は新規フィルタなので素通し
                int pos = url.indexOf("local/");
                if (pos > 0 && u.url.matcher(url.substring(0, pos)).lookingAt()) {
                    if (!u.matchLocal) continue;
                }
            }
            matched.add(u);
        }
        return matched;
    }

    /** コンテンツ文字列にユーザーフィルタを適用する */
    String applyUserFilter(String url, String content,
            HttpRequestHeader requestHeader, HttpResponseHeader responseHeader,
            ArrayList<UserFilter> userFilters) {
        if (userFilters == null || userFilters.isEmpty()) {
            return content;
        }
        FilterContent fc = new FilterContent(
                url, content, requestHeader, responseHeader);

        if (extensions.size() > 0 && this == INSTANCE_USR) {
            for (NLFilterListener f : extensions) {
                f.nlFilterBegin(fc);
            }
        }

        StringBuilder sbStyle  = new StringBuilder(4096);
        StringBuilder sbScript = new StringBuilder(8192);
        ArrayList<UserFilter> delayedFilters = new ArrayList<>();

        HashMap<String, Long> profile = new HashMap<>();

        boolean urlShown = false;
        for (UserFilter u : userFilters) {
            if (!debugMode && u.debugMode && !urlShown) {
                Logger.info("[Debug]Filter Processing URL: " + url);
                urlShown = true;
            }
            debugLog(u, 1, "MatchURL", u.id);
            long t0 = System.currentTimeMillis();
            if (u.require == null || u.require.find(fc.content)) {
                if (u.section == UserFilter.REPLACE) {
                    if (u.replaceDelay) {
                        delayedFilters.add(u);
                    } else {
                        doReplaces(u, fc);
                    }
                } else if (u.section == UserFilter.STYLE) {
                    wrapAndConcat(sbStyle, u);
                } else if (u.section == UserFilter.SCRIPT) {
                    wrapAndConcat(sbScript, u);
                } else {
                    Logger.warning("Unknown section name: " + u.section);
                }
            }
            long t1 = System.currentTimeMillis();
            long tdelta = t1 - t0;
            if (u.id != null) {
                profile.put(u.id, tdelta);
            }
        }
        if (sbStyle.length() > 0) {
            doAppend(APPEND_STYLE, sbStyle, fc);
        }
        if (sbScript.length() > 0) {
            doAppend(APPEND_SCRIPT, sbScript, fc);
        }
        for (UserFilter u : delayedFilters) {
            long t0 = System.currentTimeMillis();
            doReplaces(u, fc);
            long t1 = System.currentTimeMillis();
            long tdelta = t1 - t0;
            if (u.id != null) {
                profile.put(u.id, profile.get(u.id) + tdelta);
            }
        }
        flushDebugLog();

        long slowThreshold = Long.getLong("slowFilter", 0);
        if (slowThreshold != 0) {
            for (UserFilter u : userFilters) {
                if (!profile.containsKey(u.id)) continue;
                long tdelta = profile.get(u.id);
                if (slowThreshold <= tdelta) {
                    Logger.info("slow filter: %s (%d ms) on %s", u.id, tdelta, url);
                }
            }
        }
        if (Boolean.getBoolean("enableFilterProfiler")) {
            TreeSet<ProfileEntry> orderedProfile = new TreeSet<>();
            for (UserFilter u : userFilters) {
                if (!profile.containsKey(u.id)) continue;
                long tdelta = profile.get(u.id);
                orderedProfile.add(new ProfileEntry(tdelta, u));
            }

            StringBuilder sb = new StringBuilder();
            Formatter formatter = new Formatter(sb);
            sb.append("---- nlFilter profiler ------------------------------\n");
            sb.append("url: ").append(url).append('\n');
            int i = 0;
            for (ProfileEntry e : orderedProfile) {
                formatter.format("%6d ms %s\n", e.time, e.filter.id);
                if (++i == 10) break;
            }
            sb.append("-----------------------------------------------------");
            Logger.info(sb.toString());
        }

        return fc.content;
    }

    private static class ProfileEntry implements Comparable<ProfileEntry> {
        long time;
        UserFilter filter;

        ProfileEntry(long time, UserFilter filter) {
            this.time = time;
            this.filter = filter;
        }

        @Override
        public int compareTo(ProfileEntry other) {
            if (this.time > other.time) return -1;
            if (this.time < other.time) return 1;
            return this.filter.id.compareTo(other.filter.id);
        }
    }

    private static final JavaPattern APPEND_STYLE  = JavaPattern.compile("(?=</head>)");
    private static final JavaPattern APPEND_SCRIPT = JavaPattern.compile("(?=</body>)");

    private void wrapAndConcat(StringBuilder sb, UserFilter u) {
        if (u.section == UserFilter.STYLE) {
            sb.append("<style type=\"text/css\">");
        } else if (u.section == UserFilter.SCRIPT) {
            sb.append("<script type=\"text/javascript\">");
        }
        sb.append("\r\n/* ");
        sb.append(u.id);
        sb.append(" */\r\n");
        sb.append(u.replace[0]);
        if (u.section == UserFilter.STYLE) {
            sb.append("\r\n</style>\r\n");
        } else if (u.section == UserFilter.SCRIPT) {
            sb.append("\r\n</script>\r\n");
        }
    }

    private void doAppend(JavaPattern type, StringBuilder sb, FilterContent fc) {
        fc.setMatcher(type);
        if (fc.find()) {
            fc.appendReplacement(sb.toString());
            fc.updateContent();
        }
    }

    // コンテンツの置換処理を実行する
    private void doReplaces(UserFilter u, FilterContent fc) {
        for (int i = 0; i < u.match.patterns.length; i++) {
            if (!u.match.update(i, fc)) {
                continue;
            }
            fc.setMatcher(u.match.patterns[i]);

            String[] replaces = null;
            boolean replaced = false;
            while (fc.find()) {
                try {
                    if (u.replaceOnly) {
                        fc.appendReplacement(u.replace[i]);
                        replaced = true;
                    } else {
                        if (replaces == null) {
                            replaces = initReplaces(u, i, fc);
                        }
                        String replace = u.match.replace(
                                selectReplace(u, fc.url, fc.mc, replaces, fc.thread2id), i, fc);
                        if (replace != null && doReplacement(u, fc, replace)) {
                            replaced = true;
                            debugLog(u, 2, "MatchReplace", u.id);
                        }
                    }
                } catch (Exception e) {
                    Logger.warning("FILTER PROCESSING ERROR: " + u.id);
                    Logger.error(e);
                }
                if (!u.multi) break;
            }
            if (replaced) {
                fc.updateContent();
            } else {
                debugLog(u, 3, "noReplace", u.id);
            }
        }
    }

    static final Pattern REPLACE_SPLIT_PATTERN = Pattern.compile(
            "^([\\s\\S]*?)\\n*<(\\w*)\\$(\\w*)>\\n*([\\s\\S]*)$");
    static final Pattern DMC_REPLACE_SPLIT_PATTERN1 = Pattern.compile(
            "^([\\s\\S]*?)\\n*<\\$>\\n*([\\s\\S]*)<\\$>\\n*([\\s\\S]*)<\\$>\\n*([\\s\\S]*)$");
    static final Pattern DMC_REPLACE_SPLIT_PATTERN2 = Pattern.compile(
            "^([\\s\\S]*?)\\n*<([^<>]*?)\\$\\$([^<>]*?)\\$\\$([^<>]*?)\\$\\$([^<>]*?)>\\n*([\\s\\S]*)$");

    private String[] initReplaces(UserFilter u, int index, FilterContent fc) {
        String[] replaces = new String[5];
        if (index < u.replace.length) {
            replaces[0] = fc.replaceStaticVariables(u.replace[index]);
        } else {
            replaces[0] = "";
        }
        if (u.idGroup[0] > 0) {
            Matcher m;
            if ((m = DMC_REPLACE_SPLIT_PATTERN1.matcher(replaces[0])).matches()) {
                replaces[1] = m.group(1);
                replaces[2] = m.group(2);
                replaces[3] = m.group(3);
                replaces[4] = m.group(4);
            } else if ((m = DMC_REPLACE_SPLIT_PATTERN2.matcher(replaces[0])).matches()) {
                replaces[1] = m.group(1) + m.group(2) + m.group(6);
                replaces[2] = m.group(1) + m.group(3) + m.group(6);
                replaces[3] = m.group(1) + m.group(4) + m.group(6);
                replaces[4] = m.group(1) + m.group(5) + m.group(6);
            } else if ((m = REPLACE_SPLIT_PATTERN.matcher(replaces[0])).matches()) {
                if ("".equals(m.group(2)) && "".equals(m.group(3))) {
                    replaces[1] = m.group(1);
                    replaces[2] = m.group(4);
                } else {
                    replaces[1] = m.group(1) + m.group(2) + m.group(4);
                    replaces[2] = m.group(1) + m.group(3) + m.group(4);
                }
                replaces[3] = replaces[1];
                replaces[4] = replaces[2];
            } else {
                replaces[1] = replaces[2] = replaces[3] = replaces[4] = replaces[0];
            }
        }
        for (int i = 0; i < replaces.length; i++) {
            if (replaces[i] == null || replaces[i].length() == 0) {
                continue;
            }
            // 先に<$>を処理した後で確認する必要がある
            if (replaces[i].contains("$URL")) {
                replaces[i] = replaceURLGroup(u.url.matcher(fc.url), replaces[i]);
            }
            if (replaces[i].contains("$RequireHeader")) {
                replaces[i] = replaceRequesHeaderGroup(u.requireHeader.patterns[0].matcher(fc.getRequestHeader().toString()), replaces[i]);
            }
        }
        return replaces;
    }

    // idGroupの指定に応じて置換文字列を選択して返す
    private static String selectReplace(
            UserFilter u, String url, JavaMatcher mc, String[] replaces,
            HashMap<String, String> thread2id) {
        if (u.idGroup[0] <= 0) {
            return replaces[0];
        }
        // キャッシュの存在条件で置換するフィルタ
        String eachSmid, eachId = null;
        try {
            eachSmid = mc.group(u.idGroup[0]);
            if (u.idGroup[1] > 0) {
                eachId = mc.group(u.idGroup[1]);
            }
        } catch (IndexOutOfBoundsException e) {
            Logger.error(e);
            Logger.warning("invalid idGroup: " + u.name);
            return null;
        }
        boolean eachSmidIsThread = eachSmid != null && eachSmid.matches("\\d{10,}");
        if (thread2id != null && eachSmidIsThread) {
            if (eachId != null) {
                if (eachId.matches("\\d{1,9}")) {
                    thread2id.putIfAbsent(eachSmid, eachId);
                }
            } else {
                eachId = thread2id.get(eachSmid);
            }
        }
        if ((eachSmid == null || eachSmidIsThread) && eachId != null) {
            eachSmid = Cache.id2Smid(eachId);
        }
        if (eachSmid == null) {
            return u.noCache ? replaces[0] : null;
        }
        if (TextUtil.isVideoId(eachSmid)) {
            VideoDescriptor video = Cache.getPreferredCachedVideo(eachSmid);
            if (!u.noCache && video != null) {
                // キャッシュが存在する場合に置換
                return replaces[video.isDmc() ? (video.isLow() ? 4 : 3):
                                                (video.isLow() ? 2 : 1)]
                        .replaceAll("<eachSmid>", eachSmid);
            } else if (u.noCache && video == null) {
                // キャッシュが存在しない場合に置換
                return replaces[0];
            }
        } else if (extensions.size() > 0) {
            if (u.idGroupString.length == 1) {
                eachId = null;
            } else {
                eachId = u.idGroupString[1];
                if (eachId.matches("URL\\d+")) { // URLグループ参照
                    int g = Integer.parseInt(eachId.substring(3));
                    Matcher m = u.url.matcher(url);
                    if (m.find() && m.groupCount() <= g) {
                        eachId = m.group(g);
                    }
                }
            }
            for (NLFilterListener f : extensions) {
                int type = f.idGroup(eachSmid, eachId);
                if (type == NLFilterListener.NOT_SUPPORTED) {
                    continue;
                } else if (!u.noCache && type != NLFilterListener.NOT_EXISTS) {
                    return replaces[type == NLFilterListener.EXISTS_DMCLOW ? 4:
                                    type == NLFilterListener.EXISTS_DMC ? 3:
                                    type == NLFilterListener.EXISTS_LOW ? 2: 1]
                            .replaceAll("<eachSmid>", eachSmid);
                } else if (u.noCache && type == NLFilterListener.NOT_EXISTS) {
                    return replaces[0];
                }
            }
        }
        return null; // 置換しない
    }

    private boolean doReplacement(UserFilter u, FilterContent fc, String replace) {
        if ((replace = fc.replaceVariables(replace, this == INSTANCE_SYS)) == null) {
            return false;
        }

        replace = replaceTS(replace, fc);
        replace = replaceREENCODED(replace, fc);
        replace = replaceREENCODED_BITRATE(replace, fc);
        replace = replaceCaseWhen(replace, fc);
        fc.appendReplacement(replace);
        boolean replaced = true;

        if (u.addList != null) {
            String value = fc.getReplace();
            if (value != null && value.length() > 0) {
                LST.append(u.addList, value, false);
                debugLog(u, 2, "AddList(" + u.addList + ")", value);
            }
            replaced = false;
        }
        if (u.addVariable != null) {
            String value = fc.getReplace();
            if (value != null && value.length() > 0) {
                // TODO セパレータを設定
                String newValue = fc.appendVariable(u.addVariable, value, "");
                debugLog(u, 2, "AddVariable(" + u.addVariable + ")", newValue);
            }
            replaced = false;
        }

        return replaced;
    }

    private static final ArrayList<Pattern> macroURLPatternCache = new ArrayList<>();

    private static Pattern getMacroURLPattern(int index) {
        if (macroURLPatternCache.size() <= index) {
            synchronized (macroURLPatternCache) {
                while (macroURLPatternCache.size() <= index) {
                    macroURLPatternCache.add(null);
                }
            }
        }
        Pattern pattern = macroURLPatternCache.get(index);
        if (pattern == null) {
            synchronized (macroURLPatternCache) {
                if ((pattern = macroURLPatternCache.get(index)) == null) {
                    pattern = FilterPattern.compile("URL" + index + "(?!\\d)");
                    macroURLPatternCache.set(index, pattern);
                }
            }
        }
        return pattern;
    }

    // URLのグループ参照を置換する
    private String replaceURLGroup(Matcher mURL, String replace) {
        if (mURL.lookingAt()) {
            for (int i = 0; i <= mURL.groupCount(); i++) {
                replace = getMacroURLPattern(i).matcher(replace).replaceAll(
                        mURL.group(i) != null ? mURL.group(i) : "");
            }
        }
        return replace;
    }

    private static final ArrayList<Pattern> macroRequireHeaderPatternCache = new ArrayList<>();

    private static Pattern getMacroRequireHeaderPattern(int index) {
        if (macroRequireHeaderPatternCache.size() <= index) {
            synchronized (macroRequireHeaderPatternCache) {
                while (macroRequireHeaderPatternCache.size() <= index) {
                    macroRequireHeaderPatternCache.add(null);
                }
            }
        }
        Pattern pattern = macroRequireHeaderPatternCache.get(index);
        if (pattern == null) {
            synchronized (macroRequireHeaderPatternCache) {
                if ((pattern = macroRequireHeaderPatternCache.get(index)) == null) {
                    pattern = FilterPattern.compile("RequireHeader" + index + "(?!\\d)");
                    macroRequireHeaderPatternCache.set(index, pattern);
                }
            }
        }
        return pattern;
    }

    // URLのグループ参照を置換する
    private String replaceRequesHeaderGroup(JavaMatcher mRequireHeader, String replace) {
        if (mRequireHeader.find()) {
            for (int i = 0; i <= mRequireHeader.groupCount(); i++) {
                replace = getMacroRequireHeaderPattern(i).matcher(replace).replaceAll(
                        mRequireHeader.group(i) != null ? mRequireHeader.group(i) : "");
            }
        }
        return replace;
    }

    static final Pattern MACRO_TS_PATTERN = FilterPattern.compile(
            "TS", "([^?)]*)(\\?[^)]+)?");

    // タイムスタンプ置換
    private String replaceTS(String replace, FilterContent fc) {
        Matcher m = MACRO_TS_PATTERN.matcher(replace);
        if (m.find()) {
            StringBuffer sb = new StringBuffer();
            do {
                String replacement;
                if (m.group(1).length() > 0) {
                    // ファイルが存在する場合のみパラメータを付加
                    long lastModified = new File(m.group(1)).lastModified();
                    if (lastModified > 0L) {
                        replacement = m.group(1) +
                                (m.group(2) != null ? m.group(2) : "?") +
                                (lastModified / 1000L);
                    } else {
                        replacement = m.group(1);
                    }
                } else {
                    // ファイル指定が無い場合は現在時刻に置換
                    replacement = String.valueOf(fc.startTime / 1000L);
                }
                m.appendReplacement(sb, replacement);
            } while (m.find());
            replace = m.appendTail(sb).toString();
        }
        return replace;
    }

    static final Pattern MACRO_REENCODED_PATTERN = FilterPattern.compile(
            "REENCODED", "([^)]*)");

    // 再エンコード判定置換
    private String replaceREENCODED(String replace, FilterContent fc) {
        Matcher m = MACRO_REENCODED_PATTERN.matcher(replace);
        if (m.find()) {
            StringBuffer sb = new StringBuffer();
            do {
                // $1などは本来，最後に置換結果を結合するときに処理されるが
                // $REENCODED()の引数で先に処理しなければならないため先に処理する
                String smid = replaceGroupHolderHack(m.group(1), fc);
                Boolean reencoded;
                boolean smidIsNumber = smid.matches("\\d+");
                if (smidIsNumber && smid.length() < 10) {
                    reencoded = ReEncodingInfo.getFromNumber(smid);
                } else {
                    if (smidIsNumber) {
                        smid = Cache.id2Smid(smid);
                    }
                    if (smid == null) {
                        reencoded = null;
                    } else {
                        reencoded = ReEncodingInfo.get(smid);
                    }
                }
                String replacement = "" + reencoded; // true/false/null
                m.appendReplacement(sb, replacement);
            } while (m.find());
            replace = m.appendTail(sb).toString();
        }
        return replace;
    }

    static final Pattern MACRO_REENCODED_BITRATE_PATTERN = FilterPattern.compile(
            "REENCODED_BITRATE", "([^)]*)");

    // 再エンコード判定置換
    private String replaceREENCODED_BITRATE(String replace, FilterContent fc) {
        Matcher m = MACRO_REENCODED_BITRATE_PATTERN.matcher(replace);
        if (m.find()) {
            StringBuffer sb = new StringBuffer();
            do {
                // $1などは本来，最後に置換結果を結合するときに処理されるが
                // $REENCODED()の引数で先に処理しなければならないため先に処理する
                String smid = replaceGroupHolderHack(m.group(1), fc);
                ReEncodingInfo.Entry info;
                String replacement;
                boolean smidIsNumber = smid.matches("\\d+");
                if (smidIsNumber && smid.length() < 10) {
                    info = ReEncodingInfo.getEntryFromNumber(smid);
                } else {
                    if (smidIsNumber) {
                        smid = Cache.id2Smid(smid);
                    }
                    if (smid == null) {
                        info = null;
                    } else {
                        info = ReEncodingInfo.getEntry(smid);
                    }
                }
                if (info == null) {
                    replacement = "0";
                } else {
                    replacement = "" + info.bitrate;
                }
                m.appendReplacement(sb, replacement);
            } while (m.find());
            replace = m.appendTail(sb).toString();
        }
        return replace;
    }

    static final Pattern GROUP_REPLACEMENT_PATTERN = Pattern.compile("\\$(\\d)");

    // $1などを置換する．エスケープ無効．
    private String replaceGroupHolderHack(String replace, FilterContent fc) {
        StringBuffer sb = new StringBuffer();
        Matcher m = GROUP_REPLACEMENT_PATTERN.matcher(replace);
        while (m.find()) {
            String replacement;
            try {
                replacement = fc.mc.group(Integer.parseInt(m.group(1)));
            } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
                replacement = m.group(); // like $9
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static final Pattern CASE_PATTERN = Pattern.compile(
            "<nlcase\\s+\"((?:(?!\">).)*?)\">((?:(?!<nlcase\\b).)*?)</nlcase>", Pattern.DOTALL);
    static final Pattern CASE_WHEN_PATTERN = Pattern.compile(
            "<when\\s+(?:\"(.*?)\"|else)>(.*?)(?:$|(?=<when))", Pattern.DOTALL);

    private String replaceCaseWhen(String replace, FilterContent fc) {
        while (true) {
            Matcher m = CASE_PATTERN.matcher(replace);
            if (!m.find()) {
                break;
            }
            String var = m.group(1);
            StringBuffer sb = new StringBuffer();
            do {
                String replacement = "";
                String whenstr = m.group(2);
                Matcher m2 = CASE_WHEN_PATTERN.matcher(whenstr);
                while (m2.find()) {
                    String when = m2.group(1);
                    String body = m2.group(2);
                    if (when == null || var.equals(when)) {
                        replacement = body;
                        break;
                    }
                }
                m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            } while (m.find());
            replace = m.appendTail(sb).toString();
        }
        return replace;
    }

    /**
     *  nlFilterが管理するLSTにアクセスするためのユーティリティクラス
     *  @since NicoCache_nl+110110mod
     */
    public static class LST {

        static class Entry {
            static final ConcurrentHashMap<String, Entry> entries =
                new ConcurrentHashMap<>();
            FilterFile file;
            Pattern pattern;
            TreeSet<String> set;
            String startline;
            private Entry(String list) {
                if (list.startsWith("!")) {
                    file = new FilterFile(list.substring(1));
                } else {
                    file = new FilterFile(list);
                }
            }
            static String flip(String list) {
                if (list.startsWith("!")) {
                    return list.substring(1);
                } else {
                    return "!".concat(list);
                }
            }
            static Entry get(String list) {
                Entry entry = entries.get(list);
                if (entry == null) {
                    entries.put(list, entry = new Entry(list));
                }
                if (entry.file.isModified()) {
                    entry.pattern = null;
                    clear(flip(list));
                }
                return entry;
            }
            static void clear(String list) {
                Entry entry = entries.get(list);
                if (entry != null) {
                    entry.pattern = null;
                }
            }
        }

        static boolean isQuote(String list) {
            return list.startsWith("!");
        }

        static boolean isModified(String list) {
            return Entry.get(list).pattern == null;
        }

        // LSTの更新を監視するための簡易オブザーバーを実装
        interface Observer {
            public void updateLST(String list);
        }

        static final List<Observer> observers =
            Collections.synchronizedList(new ArrayList<>());

        static void addObserver(Observer o) {
            observers.add(o);
        }

        static void deleteObserver(Observer o) {
            observers.remove(o);
        }

        static void deleteObservers() {
            observers.clear();
        }

        static void notifyObservers(String list) {
            String flipList = Entry.flip(list);
            Entry.clear(flipList);
            for (Observer o : observers) {
                o.updateLST(list);
                o.updateLST(flipList);
            }
            for (NLFilterListener f : extensions) {
                f.updateLST(list);
                f.updateLST(flipList);
            }
        }

        /**
         * LSTパターン(LSTの内容全てをORで繋いだパターン)を返す。
         *
         * @param list リスト名
         * @return LSTパターン、存在しない場合は"(?!)"をパターンにして返す
         */
        public static Pattern getPattern(String list) {
            Entry entry = Entry.get(list);
            if (entry.pattern != null) {
                return entry.pattern;
            }
            synchronized (entry) {
                if (readSet(entry, false)) {
                    makePattern(list, entry);
                }
            }
            return entry.pattern != null ? entry.pattern : NULL_PATTERN;
        }

        /**
         * LSTの末尾に文字列を追加する。重複文字列は追加しない。
         * LSTに正規表現のメタ文字を含まない場合は、
         * LSTパターンに完全一致するかどうかで重複チェックを行う。
         * メタ文字を含む場合は、毎回ファイルから文字列を読み込み重複チェックを行う。
         *
         * @param list リスト名
         * @param input 追加する文字列
         * @param regex 正規表現のメタ文字を含むか？
         * @return 正常に追加できればtrue
         */
        public static boolean append(String list, String input, boolean regex) {
            Entry entry = Entry.get(list);
            synchronized (entry) {
                try { // 重複チェック
                    if (regex) {
                        // メタ文字を含む場合は毎回読み込んで判定
                        if (readSet(entry, false) && entry.set.contains(input)) {
                            return true;
                        }
                    } else {
                        // メタ文字を含まない場合はパターンマッチで判定
                        if (entry.pattern == null) {
                            readSet(entry, false);
                            makePattern(list, entry);
                        }
                        if (entry.pattern != null &&
                                entry.pattern.matcher(input).matches()) {
                            return true;
                        }
                    }
                } finally {
                    entry.set = null;
                }
                entry.file.getParentFile().mkdirs();
                boolean written = false;
                BufferedWriter writer = null;
                try {
                    writer = entry.file.getWriter(true);
                    writer.write(input);
                    writer.newLine();
                    written = true;
                    return true;
                } catch (IOException e) {
                    Logger.debugWithThread(e);
                    errorLog("Write", entry.file.getPath(), null, input);
                } finally {
                    CloseUtil.close(writer);
                    if (written) {
                        Logger.debugWithThread("append(" + list + "): " + input);
                        entry.file.setModified();
                        appendPattern(list, entry, input);
                    }
                }
            }
            return false;
        }

        /**
         * LSTに文字列が含まれているか？
         *
         * @param list リスト名
         * @param line 確認する文字列
         * @return 文字列が完全一致すればtrue
         * @since NicoCache_nl+111111mod
         */
        public static boolean contains(String list, String line) {
            return getPattern(list).matcher(line).matches();
        }

        /**
         * LSTから文字列に完全一致する行を削除して、更にtrimして書き出す。
         *
         * @param list リスト名
         * @param line 削除する文字列
         * @return 書き出しに成功したらtrue
         * @see #trim(String, boolean)
         * @since NicoCache_nl+111111mod
         */
        public static boolean remove(String list, String line) {
            Entry entry = Entry.get(list);
            synchronized (entry) {
                if (readSet(entry, TextUtil.isVideoId(line))) {
                    entry.set.remove(line);
                    if (writeSet(entry)) {
                        Logger.debugWithThread(list + " removed: " + line);
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * LSTの重複文字列と前後の空白を省いて、更に辞書昇順でソートして書き出す。
         * ファイル内のコメント行は1行目の文字コード判定文字列を除いて全て失われるので注意。
         *
         * @param list リスト名
         * @param smid trueなら動画IDとみなしてソートする
         * @return 書き出しに成功したらtrue
         */
        public static boolean trim(String list, boolean smid) {
            Entry entry = Entry.get(list);
            synchronized (entry) {
                if (readSet(entry, smid)) {
                    if (writeSet(entry)) {
                        Logger.debugWithThread(list + " trimed");
                        return true;
                    }
                }
            }
            return false;
        }

        // ファイルから読み込んでセットを作成
        static boolean readSet(Entry entry, boolean smid) {
            if (!entry.file.canRead()) {
                return false;
            }
            if (smid) {
                entry.set = new TreeSet<>(Cache.getSmidComparator(false));
            } else {
                entry.set = new TreeSet<>();
            }
            LineNumberReader reader = null;
            String line = null;
            try {
                reader = entry.file.getReader();
                while ((line = reader.readLine()) != null) {
                    if (reader.getLineNumber() == 1 &&
                            line.startsWith(FilterFile.CHARTEST_LINE)) {
                        entry.startline = line;
                    }
                    line = line.trim();
                    if (line.length() == 0 || line.startsWith("#")) {
                        continue;
                    }
                    entry.set.add(line); // TreeSetに取り込んで重複を省く
                }
                return true;
            } catch (IOException e) {
                Logger.debugWithThread(e);
                errorLog("Read", entry.file.getPath(), reader, line);
                entry.set = null;
            } finally {
                CloseUtil.close(reader);
            }
            return false;
        }

        // セットをファイルに書き出し
        static boolean writeSet(Entry entry) {
            boolean written = false;
            BufferedWriter writer = null;
            try {
                if (entry.set != null && entry.set.size() > 0) {
                    writer = entry.file.getWriter(false);
                    if (entry.startline != null) {
                        writer.write(entry.startline);
                        writer.newLine();
                    }
                    for (String s : entry.set) {
                        writer.write(s);
                        writer.newLine();
                    }
                    written = true;
                }
            } catch (IOException e) {
                Logger.debugWithThread(e);
                errorLog("Write", entry.file.getPath(), null, null);
            } finally {
                CloseUtil.close(writer);
                if (written) {
                    entry.file.setModified();
                }
                entry.set = null;
            }
            return written;
        }

        // パターンを作成
        static void makePattern(String list, Entry entry) {
            if (entry.set != null && entry.set.size() > 0) {
                StringBuilder sb = new StringBuilder(entry.file.intLength());
                sb.append("(");
                boolean quote = isQuote(list);
                for (String s : entry.set) {
                    appendBuffer(sb, s, quote);
                }
                compileBuffer(sb, list, entry);
            }
            entry.set = null;
        }

        // 既存パターンに追加してパターンを再作成
        static void appendPattern(String list, Entry entry, String input) {
            StringBuilder sb;
            if (entry.pattern != null) {
                sb = new StringBuilder(entry.pattern.pattern());
                sb.replace(sb.length() - 1, sb.length(), "|");
            } else {
                sb = new StringBuilder("(");
            }
            appendBuffer(sb, input, isQuote(list));
            compileBuffer(sb, list, entry);
        }

        static void appendBuffer(StringBuilder sb, String input, boolean escape) {
            if (escape) {
                sb.append("\\Q").append(input).append("\\E|");
            } else {
                sb.append(input).append("|");
            }
        }

        static void compileBuffer(StringBuilder sb, String list, Entry entry) {
            sb.replace(sb.length() - 1, sb.length(), ")");
            try {
                entry.pattern = Pattern.compile(sb.toString());
            } catch (PatternSyntaxException e) {
                Logger.warning("Pattern syntax error: " + entry.file.getPath());
            }
            notifyObservers(list);
        }
    }

    static class UserFilter {
        static final String REPLACE = "[Replace]";
        static final String CONFIG = "[Config]"; // TODO 廃止
        static final String REQUEST_HEADER = "[RequestHeader]";
        static final String STYLE  = "[Style]";
        static final String SCRIPT = "[Script]";
        static final String[] SECTIONS = new String[] {
            REPLACE, CONFIG, REQUEST_HEADER, STYLE, SCRIPT
        };

        String section, name, id;
        boolean trimNeeded; // ブラウザからのコピペ対策用
        Pattern url;
        int[] statusCodes;
        FilterPattern match, require, requireHeader, contentType;
        String[] replace;
        boolean multi, each, matchLocal, replaceOnly, replaceDelay;
        boolean noCache;
        int[] idGroup = { -1, -1 };
        String[] idGroupString;
        String addList, addVariable;
        boolean debugMode;

        UserFilter() {
            this.section = REPLACE;
        }

        UserFilter(String section) {
            for (String constant : SECTIONS) {
                if (constant.equalsIgnoreCase(section)) {
                    this.section = constant;
                    break;
                }
            }
            if (this.section == null) {
                throw new IllegalArgumentException("invalid section: " + section);
            }
        }

        boolean isValid() {
            return name != null && match != null && replace != null &&
//              (section != REPLACE || url != null); // [Replace]以外はURL省略可
                    (url != null || section == REQUEST_HEADER || section == CONFIG);
        }
    }


    // 動的更新できるマクロ($LSTとか)を含むパターンを保持するクラス
    static class FilterPattern {
        UserFilter u;
        boolean not;
        String[] contents;
        JavaPattern[] patterns;
        ArrayList<MacroBase> macros = new ArrayList<>();

        FilterPattern(UserFilter u, String content, boolean match) {
            this.u = u;
            if (match && u.each) {
                contents = content.split("\0");
            } else {
                contents = new String[1];
                if (content.startsWith("!")) {
                    not = true;
                    contents[0] = content.substring(1);
                } else {
                    contents[0] = content;
                }
            }
            patterns = new JavaPattern[contents.length];

            // この辺りはもう少し何とかしたいが…
            if (MacroLST.isMatch(content)) {
                macros.add(new MacroLST(contents));
            }
            if (match && MacroINC.isMatch(content)) {
                macros.add(new MacroINC(contents));
            }
            if (match && MacroSET.isMatch(content)) {
                macros.add(new MacroSET(contents));
            }

            for (int i = 0; i < contents.length; i++) {
                updatePattern(i, null);
            }
        }

        private boolean updatePattern(int index, FilterContent fc) {
            String content = contents[index];
            for (MacroBase m : macros) {
                content = m.update(content, index);
                if (content == null) {
                    patterns[index] = JavaPattern.compile("(?!)");
                    return false;
                }
            }
            if (macros.size() > 0) {
                EasyRewriter.debugLog(u, 2, "Update", u.id);
            }
            patterns[index] = JavaPattern.compile(content);
            return true;
        }

        /** パターンを動的更新する */
        boolean update(int index, FilterContent fc) {
            boolean doUpdate = false;
            if (patterns[index] == null) {
                doUpdate = true;
            } else {
                for (MacroBase m : macros) {
                    if (doUpdate = m.needsUpdate(index)) {
                        break;
                    }
                }
            }
            return doUpdate ? updatePattern(index, fc) : true;
        }

        /** notを考慮したパターンの部分一致検索 */
        boolean find(String content) {
            assert patterns.length == 1;
            return content != null && update(0, null) &&
                    patterns[0].matcher(content).find() ^ not;
        }

        // Replaceに使う内容を置換する(nullならReplaceしない)
        String replace(String content, int index, FilterContent fc) {
            if (content != null) {
                for (MacroBase m : macros) {
                    if ((content = m.found(content, index, fc)) == null) {
                        break;
                    }
                }
            }
            return content;
        }

        /** $name(body)形式のマクロをパターンにコンパイルする */
        static Pattern compile(String name, String body) {
            return Pattern.compile("(?<!\\\\)(?:\\\\\\\\)*\\$" + name +
                    "\\(" + body + "\\)");
        }

        static Pattern compile(String name) {
            return Pattern.compile("(?<!\\\\)(?:\\\\\\\\)*\\$" + name);
        }

        static abstract class MacroBase {
            ArrayList<Object>[] data;  // 汎用データ
            boolean[] changed;         // 変更フラグ

            @SuppressWarnings("unchecked") // 総称型の配列を扱うために必要
            MacroBase(String[] contents) {
                data = (ArrayList<Object>[])new ArrayList<?>[contents.length];
                for (int i = 0; i < data.length; i++) {
                    data[i] = new ArrayList<>();
                }
                changed = new boolean[contents.length];
            }

            // Match処理の前にupdateを呼び出す必要があるか？
            boolean needsUpdate(int index) { return changed[index]; }

            // Match処理の前にパターンを作り直すために呼ばれる
            // nullを返すとMatch処理を中断する
            // デフォルト実装はマッチする文字列を削除したパターンを作成する
            String update(String content, int index/*, FilterContent fc*/) {
                Matcher m = getMatcher(content);
                if (m != null && m.find()) {
                    data[index].clear();
                    StringBuffer sb = new StringBuffer();
                    do {
                        updateData(m, index, sb);
                    } while (m.find());
                    if (sb.length() > 0) {
                        content = m.appendTail(sb).toString();
                    } else {
                        content = m.reset().replaceAll("");
                    }
                    changed[index] = false;
                }
                return content;
            }

            // デフォルト実装で使用するMatcherを返す
            Matcher getMatcher(String str) { return null; }

            // デフォルト実装でdataを更新するために呼ばれる
            void updateData(Matcher m, int index, StringBuffer sb) {}

            // Match処理の後にマッチする度に呼ばれる
            // nullを返すとReplace処理を中断する
            // デフォルト実装は何もしない
            String found(String replace, int index, FilterContent fc) {
                return replace;
            }
        }

        // $LST
        static class MacroLST extends MacroBase implements EasyRewriter.LST.Observer {
            static Pattern PATTERN = compile(
                    "LST", "\"?([^\"\\)]*)\"?");

            static boolean isMatch(String content) {
                return PATTERN.matcher(content).find();
            }
            MacroLST(String[] contents) {
                super(contents);
                EasyRewriter.LST.addObserver(this);
            }
            @Override
            boolean needsUpdate(int index) {
                for (Object o : data[index]) {
                    if (EasyRewriter.LST.isModified((String) o)) {
                        return true;
                    }
                }
                return super.needsUpdate(index);
            }
            @Override
            Matcher getMatcher(String content) {
                return PATTERN.matcher(content);
            }
            @Override
            void updateData(Matcher m, int index, StringBuffer sb) {
                String list = m.group(1);
                String group = EasyRewriter.LST.getPattern(list).pattern();
                data[index].add(list);
                m.appendReplacement(sb, Matcher.quoteReplacement(group));
            }
            @Override
            public void updateLST(String list) {
                for (int i = 0; i < data.length; i++) {
                    if (data[i].contains(list)) {
                        changed[i] = true;
                    }
                }
            }
        }

        // $INC
        static class MacroINC extends MacroBase {
            static Pattern PATTERN = compile(
                    "INC", "(" + FilterContent.NLVAR_CHARS + ")");

            static boolean isMatch(String content) {
                return PATTERN.matcher(content).find();
            }
            MacroINC(String[] contents) {
                super(contents);
            }
            @Override
            Matcher getMatcher(String content) {
                return PATTERN.matcher(content);
            }
            @Override
            void updateData(Matcher m, int index, StringBuffer sb) {
                data[index].add(m.group(1));
            }
            @Override
            String found(String replace, int index, FilterContent fc) {
                for (Object o : data[index]) {
                    String name = (String) o;
                    try {
                        int value = Integer.parseInt(fc.getVariable(name, "0"));
                        fc.setVariable(name, String.valueOf(++value));
                    } catch (NumberFormatException e) {
                        Logger.warning("$INC() failed: <nlVar:"+name+"> is not a number.");
                    }
                }
                return replace;
            }
        }

        // $SET
        static class MacroSET extends MacroBase {
            static Pattern PATTERN = compile(
                    "SET", "(" + FilterContent.NLVAR_CHARS + ")\\s*=\\s*([^\\)]*)");

            static boolean isMatch(String content) {
                return PATTERN.matcher(content).find();
            }
            MacroSET(String[] contents) {
                super(contents);
            }
            class NameValue {
                String name, value;
                NameValue(String name, String value) {
                    this.name = name;
                    this.value = value;
                }
            }
            @Override
            Matcher getMatcher(String content) {
                return PATTERN.matcher(content);
            }
            @Override
            void updateData(Matcher m, int index, StringBuffer sb) {
                data[index].add(new NameValue(m.group(1), m.group(2)));
            }
            @Override
            String found(String replace, int index, FilterContent fc) {
                for (Object o : data[index]) {
                    NameValue nv = (NameValue) o;
                    fc.setVariable(nv.name, nv.value);
                }
                return replace;
            }
        }

    }

    @SuppressWarnings("serial")
    static class FilterFile extends File {
        public static final String CHARTEST_LINE = "# nlフィルタ定義";

        ArrayList<UserFilter> parsed = new ArrayList<>();

        private static final ConcurrentHashMap<String, FileUtil.Info> infos =
                new ConcurrentHashMap<String, FileUtil.Info>();

        public FilterFile(File parent, String child) {
            super(parent, child);
            putInfoIfNone();
        }

        public FilterFile(String pathname) {
            super(pathname);
            putInfoIfNone();
        }

        public FilterFile(String parent, String child) {
            super(parent, child);
            putInfoIfNone();
        }

        private void putInfoIfNone() {
            if (!infos.containsKey(getPath())) {
                infos.put(getPath(), FileUtil.getInfo(this, null, CHARTEST_LINE));
            }
        }

        private FileUtil.Info updateInfo() {
            FileUtil.Info info = FileUtil.getInfo(
                    this, infos.get(getPath()), CHARTEST_LINE);
            infos.put(getPath(), info);
            return info;
        }

        // 最初にオブジェクトを作ってからファイルが更新されているか？
        // canRead,getReader,getWriterを呼び出すまで有効
        boolean isModified() {
            FileUtil.Info info = infos.get(getPath());
            long curr = this.lastModified();
            return info != null ? info.lastModified != curr : 0L != curr;
        }

        // 書き込み終了時に呼び出す
        void setModified() {
            FileUtil.Info info = infos.get(getPath());
            if (info != null) {
                info.lastModified = this.lastModified();
            }
        }

        @Override
        public boolean canRead() {
            updateInfo();
            return this.isFile() && super.canRead();
        }

        LineNumberReader getReader() throws IOException {
            InputStreamReader in;
            FileInputStream fis = new FileInputStream(this);
            FileUtil.Info info = updateInfo();
            if (info.charset != null) {
                in = new InputStreamReader(fis, info.charset);
            } else {
                BufferedInputStream bis = new BufferedInputStream(fis);
                info = FileUtil.getInfo(this, info, CHARTEST_LINE, bis);
                in = new InputStreamReader(bis, info.charset);
            }
            return new LineNumberReader(in);
        }

        BufferedWriter getWriter(boolean append) throws IOException {
            FileUtil.Info info = updateInfo();
            if (!this.exists()) {
                info.charset = Charset.defaultCharset();
                info.line_separator = System.getProperty("line.separator");
                return new BufferedWriter(
                        new OutputStreamWriter(new FileOutputStream(this)));
            } else if (!append) {
                // 追記しない場合は念のためバックアップを残しておく
                this.renameTo(new File(this.getPath().concat(".bak")));
            }
            if (info.charset == null || info.line_separator == null) {
                BufferedInputStream in = new BufferedInputStream(new FileInputStream(this));
                try {
                    info = FileUtil.getInfo(this, info, CHARTEST_LINE, in);
                } finally {
                    CloseUtil.close(in);
                }
            }
            return new LineSeparatorWriter(new OutputStreamWriter(
                    new FileOutputStream(this, append), info.charset), info.line_separator);
        }

        class LineSeparatorWriter extends BufferedWriter {
            String line_separator;
            public LineSeparatorWriter(OutputStreamWriter out, String ls) {
                super(out);
                line_separator = ls;
            }
            @Override
            public void newLine() throws IOException {
                if (line_separator == null) {
                    super.newLine();
                } else {
                    write(line_separator);
                }
            }
        }

        int intLength() {
            if (length() > Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
            return (int)length();
        }
    }

    // 置換対象のコンテンツを保持するクラス
    static class FilterContent implements NLFilterListener.Content {
        /** nlVar名に使える文字の集合 */
        public static final String NLVAR_CHARS = "[^\\s\"#\\$\\(\\)<>=]+";
        public static final String NLVAR_CONFIG_PREFIX = "config!";

        static final Pattern VIDEO_ID_PATTERN = Pattern.compile(
        //          "\"videoId\", \"([a-z]{2}\\d+)\"");
        // videoIdの取得のみなのでパフォーマンスを考慮してWatchVarsは使わずに対応(Zero)
                "(?:(?<!\\w)videoId|&quot;video&quot;:\\{[^}]+?(?<!\\w)id)(?:\", \"|&quot;:&quot;)([a-z]{2}\\d+)(?!\\w)");
        static final Pattern WATCH_PAGE_PATTERN = Pattern.compile(
                "^https?://www\\.nicovideo\\.jp/watch/(\\w{2}\\d+)(\\?.*)?$");
        static final Pattern NLVAR_PATTERN = Pattern.compile(
                "<nlVar:(" + NLVAR_CHARS + ")>");

        final String url;
        final long startTime = System.currentTimeMillis();
        String content;
        HttpRequestHeader requestHeader;
        HttpResponseHeader responseHeader;
        StringBuffer sb;
        JavaMatcher mc;
        int last, skip;

        HashMap<String, String> nlVars = new HashMap<>();
        HashMap<String, String> thread2id = new HashMap<>();
        String id = "", smid = "", memoryId = "", freeSpace = "";

        FilterContent(String url, String content,
                HttpRequestHeader requestHeader, HttpResponseHeader responseHeader) {
            this.url = url;
            this.content = content;
            this.requestHeader = requestHeader;
            this.responseHeader = responseHeader;
            sb = new StringBuffer(content.length() * 2);

            // watchページ限定でsmidを取得
            Matcher m = WATCH_PAGE_PATTERN.matcher(url);
            if (m.matches()) {
                smid = m.group(1);
                memoryId = smid;
                if (smid.matches("\\d+")) {
                    m = VIDEO_ID_PATTERN.matcher(content);
                    if (m.find()) {
                        smid = m.group(1);
                    }
                }
                if (smid.length() > 2) {
                    id = smid.substring(2);
                }
            }
            // キャッシュ空き容量
            long freeSize = DiskFreeSpace.get(Cache.getCacheDir());
            if (freeSize < Long.MAX_VALUE) {
                freeSpace = String.format("%.2f", freeSize / 1024.0 / 1024 / 1024);
            }

            // 組み込み変数
            nlVars.put("VERSION", Main.VER_STRING);
        }

        @Override
        public String getURL() {
            return url;
        }

        @Override
        public String getContent() {
            return content;
        }

        @Override
        public HttpRequestHeader getRequestHeader() {
            return requestHeader;
        }

        @Override
        public HttpResponseHeader getResponseHeader() {
            return responseHeader;
        }

        @Override
        public String getVariable(String name) {
            return getVariable(name, null);
        }

        @Override
        public String getVariable(String name, String def) {
            String value = null;
            if (name.startsWith(NLVAR_CONFIG_PREFIX)) {
                String config = name.substring(NLVAR_CONFIG_PREFIX.length());
                value = System.getProperty(config);
            }
            if (value == null) {
                value = nlVars.get(name);
            }
            return value != null ? value : def;
        }

        @Override
        public String setVariable(String name, String value) {
            if (value == null) {
                return nlVars.remove(name);
            }
            return nlVars.put(name, value);
        }

        String appendVariable(String name, String value, String separator) {
            String oldValue = getVariable(name);
            String newValue = value;
            if (oldValue != null) {
                newValue = oldValue + separator + value;
            }
            nlVars.put(name, newValue);
            return newValue;
        }

        String replaceStaticVariables(String s) {
            // 従来のものは互換性維持のためnlVarで扱わない
            return s.replaceAll("<id>", id)
                    .replaceAll("<smid>", smid)
                    .replaceAll("<memoryId>", memoryId)
                    .replaceAll("<freeSpace>", freeSpace);
        }

        String replaceVariables(String s, boolean sys) {
            String value;
            Matcher m = NLVAR_PATTERN.matcher(s);
            if (m.find()) {
                StringBuffer sb = new StringBuffer();
                do {
                    if ((value = getReplace(m.group(1), sys)) == null) {
                        return null;
                    }
                    m.appendReplacement(sb, value);
                } while (m.find());
                s = m.appendTail(sb).toString();
            }
            return s;
        }

        String getReplace(String name, boolean sys) {
            String value;
            if (sys) {
                value = getVariable(name);
                if (value == null) {
                    value = EasyRewriter.getExtensionVariable(name, this);
                }
            } else {
                value = EasyRewriter.getExtensionVariable(name, this);
                if (value == null) {
                    value = getVariable(name);
                }
            }
            return value;
        }

        void setMatcher(JavaPattern pattern) {
            mc = pattern.matcher(content);
            sb.setLength(0);
            last = 0; skip = -1;
        }

        boolean find() {
            boolean found = mc.find();
            if (found && mc.start() >= 0 && last >= 0) {
                skip = sb.length() + mc.start() - last;
            } else {
                skip = -1;
            }
            return found;
        }

        String group(int num) {
            return mc.group(num);
        }

        void appendReplacement(String replacement) {
            mc.appendReplacement(sb, replacement);
            last = mc.end();
        }

        String getReplace() {
            return skip < 0 ? null : sb.substring(skip);

        }

        void updateContent() {
            mc.appendTail(sb);
            content = sb.toString();
            sb.setLength(0);
        }
    }

}

