package dareka.processor.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dareka.common.CloseUtil;
import dareka.common.Logger;
import dareka.common.TextUtil;
import dareka.processor.HttpHeader;
import dareka.processor.Resource;
import dareka.processor.StringResource;
import dareka.processor.TemporaryFileResource;
import dareka.processor.impl.Cache;


/*
 * hls動画をmp4,webm,mkvに可逆変換する.
 * master.m3u8があることが前提.
 * もし指定された形式でコンテナ出来ないコーデックを使っていたら失敗する.
 */
public class Hls2SingleConverter {
    Cache cache;
    File srcFile;
    String requestName;

    public Hls2SingleConverter(Cache cache, String requestName, String userAgent) {
        this.cache = cache;
        this.requestName = requestName;
    };

    private static void info(String msg) {
        Logger.info("hls to single: " + msg);
    };

    private static String getDotExt(String path) {
        int pos = path.lastIndexOf('.');
        if (pos < 0) {
            return "";
        };
        return path.substring(pos).toLowerCase();
    };

    public Resource convert() {
        if (!cache.exists()) {
            return StringResource.getNotFound();
        };

        String dotext = getDotExt(this.requestName);
        File dest;
        try {
            dest = File.createTempFile("nlHls2Mp4_", dotext);
        }
        catch (IllegalArgumentException e) {
            return StringResource.getInternalError(e);
        }
        catch (IOException e) {
            return StringResource.getInternalError(e);
        };
        dest.deleteOnExit();

        info(cache.getId() + " " + cache.getTitle() + dotext);

        String cachePath = cache.getCacheFile().getAbsolutePath();
        String master = cachePath + "/master.m3u8";

        String[] args = {
            "ffmpeg",
            "-loglevel", "error",
            "-hide_banner", // ffmpeg情報バナー非表示.
            "-allowed_extensions", "m3u8,cmfv,cmfa,key,mp4,m4s,m4a,ts,webm,flv",
            "-protocol_whitelist", "crypto,tcp,tls,https,file",
            "-i", master, // input file
            "-c:v", "copy", // video codec.
            "-c:a", "copy", // audio codec.
            "-id3v2_version", "3", // ID3v2.3
            "-metadata", "title=" + cache.getTitle(),
            "-metadata", "comment=" + cache.getId(), // ID3v2:COMMタグ非対応
            "-y", // 既にあるファイルに上書きする.
            dest.toString()
        };

        BufferedReader commandLog = execCommand(args);

        if (dest.length() == 0) {
            info("cannot convert hls to " + dotext.substring(1) + ".");
            info("args: " + formatStringArray(args));
            String line;
            try {
                while ((line = commandLog.readLine()) != null) {
                    info(line);
                };
            } catch (IOException e) {
                return StringResource.getInternalError(e);
            };
            dest.delete();
            return StringResource.getNotFound();
        };

        String mime = "application/octet-stream";
        if (dotext.equals(".webm")) {
            mime = "video/webm";
        } else if (dotext.equals(".mp4")) {
            mime = "video/mp4";
        } else if (dotext.equals(".mkv")) {
            mime = "video/x-matroska";
        } else if (dotext.equals(".flv")) {
            mime = "video/x-flv";
        };

        try {
            Resource r = new TemporaryFileResource(dest);
            r.setResponseHeader(HttpHeader.CONTENT_TYPE, mime);
            return r; // 正常成功.
        } catch (IOException e) {
            Logger.error(e);
        };

        dest.delete();
        return StringResource.getNotFound();
    };


    private static String formatStringArray(String[] array) {
        StringBuilder builder = new StringBuilder();
        for (String a : array) {
            builder.append(escapeForJava(a));
            builder.append(" ");
        };
        return builder.toString();
    };


    // https://stackoverflow.com/a/29072472
    // answered Mar 16, 2015 at 8:33; Mike Nakis
    private static String escapeForJava(String value)
    {
        StringBuilder builder = new StringBuilder();
        builder.append( "\"" );
        for( char c : value.toCharArray() ) {
            if(c == '\'') {
                builder.append("\\'");
            } else if (c == '\"') {
                builder.append("\\\"");
            } else if (c == '\r') {
                builder.append("\\r");
            } else if (c == '\n') {
                builder.append("\\n");
            } else if (c == '\t') {
                builder.append("\\t");
            } else {
                builder.append(c);
            };
        };
        builder.append("\"");
        return builder.toString();
    };


    private static BufferedReader execCommand(String[] args) {
        return execCommand(args, /*showError*/true);
    };

    private static BufferedReader execCommand
    (String[] args, boolean showError) {

        StringBuilder sb = new StringBuilder();

        String commandLine = String.join(" ", args); // 人間向け文字列.

        Process proc = null;
        BufferedReader in = null;

        try {
            ProcessBuilder pb = new ProcessBuilder(args);
            pb.redirectErrorStream(true);
            proc = pb.start();

            in = new BufferedReader(
                new InputStreamReader(proc.getInputStream()));

            String line;
            while((line = in.readLine()) != null) {
                sb.append(line);
                sb.append('\n');
            };

            proc.waitFor();
        } catch (IOException e) {
            Logger.debug("[FAILED] " + commandLine);
            if (showError) {
                info("command '" + args[0] + "' not found");
            };
            return null;
        } catch (InterruptedException e) {
            Logger.error(e);
        } finally {
            if (proc != null) {
                CloseUtil.close(in);
                CloseUtil.close(proc.getOutputStream());
                CloseUtil.close(proc.getErrorStream());
                proc.destroy();
            };
        };

        Logger.debug(commandLine);
        String commandOut = sb.toString();
        Logger.debug(commandOut);
        return new BufferedReader(new StringReader(commandOut));
    };
};
