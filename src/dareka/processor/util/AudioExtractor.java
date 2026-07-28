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

public class AudioExtractor {
    Cache cache;
    File audioFile;
    String requestName, userAgent;
    String cachePath, audioPath, contentType;

    public AudioExtractor(Cache cache, String requestName, String userAgent) {
        this.cache = cache;
        this.requestName = requestName;
        this.userAgent = userAgent;
    }

    private void info(String message) {
        Logger.info("AudioExtractor: " + message);
    }

    public Resource extract() {
        if (cache.exists()) {
            String requestExt = getExt(requestName);
            String cacheExt = cache.getPostfix();
            info(cache.getId() + " " + cache.getTitle() + requestExt);
            if (useFFmpeg(cacheExt)) { // FFmpegがあれば優先
                return ffmpeg(requestExt);
            }
            if (requestExt.equals(".mp3")) {
                if (cacheExt.equals(Cache.FLV)) {
                    return new Flv2mp3(cache);
                } else if (cacheExt.equals(Cache.SWF)) {
                    return swfextract();
                }
            } else if (requestExt.equals(".m4a")) {
                if (cacheExt.equals(Cache.MP4)) {
                    return mp4box();
                }
            }
            info("Cannot extract from '"+requestExt+"' to '"+cacheExt+"'.");
        }
        return StringResource.getNotFound();
    }

    private void setupExecCommand(String ext, String contentType) {
        String tmpdir = System.getProperty("java.io.tmpdir");
        int i = 0;
        do {
            audioFile = new File(tmpdir, "nlAudioExtractor" + (++i) + ext);
        } while (audioFile.exists());
        this.cachePath = cache.getCacheFile().getAbsolutePath();
        this.audioPath = audioFile.getAbsolutePath();
        this.contentType = contentType;
    }

    private boolean useFFmpeg(String cacheExt) {
        String useFFmpeg = System.getProperty("useFFmpeg");
        if (useFFmpeg == null) {
            String[] args = { "ffmpeg", "-version" };
            useFFmpeg = execCommand(args, false) == null ? "false" : "true";
            System.setProperty("useFFmpeg", useFFmpeg);
        }
        // ID3コメントの扱いに非互換があるのでオプション指定でFLVを除外
        if (cacheExt.equals(Cache.FLV) && useFFmpeg.equalsIgnoreCase("noflv")) {
            return false;
        }
        return !useFFmpeg.equalsIgnoreCase("false");
    }

    private Resource ffmpeg(String requestExt) {
        setupExecCommand(requestExt, "audio/" + requestExt.substring(1));
        String source;
        if (cache.getVideoDescriptor().isDir()) {
            source = cachePath + "/master.m3u8";
        } else {
            source = cachePath;
        }
        String[] args = {
                "ffmpeg", "-i", source, "-vn", "-acodec", "copy",
                "-id3v2_version", "3", // ID3v2.3
                "-metadata", "title=" + cache.getTitle(),
                "-metadata", "comment=" + cache.getId(), // ID3v2:COMMタグ非対応
                audioPath };
        execCommand(args);
        return createAudioResource();
    }

    private static final Pattern MP4BOX_TRACK_ID_PATTERN = Pattern.compile(
            "Track #\\s*(\\d+) Info - TrackID\\s*(\\d+)");

    private Resource mp4box() {
        // MP4Boxでは出力ファイルに拡張子不要
        setupExecCommand("", "audio/m4a");

        // 先にAudioトラックを特定する
        String[] preArgs = { "MP4Box", cachePath, "-info" };
        BufferedReader preInputReader = execCommand(preArgs);
        if (preInputReader == null)
            return StringResource.getNotFound();
        String tmpTrackNum = "2";
        String trackNum = "2";
        String infoLine;
        try {
            while((infoLine = preInputReader.readLine()) != null) {
                Matcher m = MP4BOX_TRACK_ID_PATTERN.matcher(infoLine);
                if (m.lookingAt()) {
                    tmpTrackNum = m.group(2);
                } else if (infoLine.contains("Audio Stream")) {
                    trackNum = tmpTrackNum;
                }
            }
        } catch (IOException e) {}
        String[] args = { "MP4Box", cachePath, "-single", trackNum, "-out", audioPath };
        execCommand(args);

        // [GUI] 少し古いMP4Boxは出力ファイル名が違うため、そのファイルも確認する
        audioFile = new File(audioPath + ".mp4");
        if (!audioFile.exists()) {
            audioFile = new File(audioPath + "_track" + trackNum + ".mp4");
        }
        audioPath = audioFile.getAbsolutePath();

        return createAudioResource();
    }

    private Resource swfextract() {
        setupExecCommand(".mp3", "audio/mp3");
        String[] args = { "swfextract", "-m", cachePath, "-o", audioPath };
        execCommand(args);
        return createAudioResource();
    }

    private BufferedReader execCommand(String[] args) {
        return execCommand(args, true);
    }

    private BufferedReader execCommand(String[] args, boolean showError) {
        StringBuilder sb = new StringBuilder();
        String commandLine = TextUtil.join(args, " ");
        Process proc = null;
        BufferedReader in = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(args);
            pb.redirectErrorStream(true);
            proc = pb.start();
            in = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            String line;
            while((line = in.readLine()) != null) {
                sb.append(line);
                sb.append('\n');
            }
            int exitCode = proc.waitFor();
            if (exitCode != 0) {
                Logger.debug(commandLine + " [EXIT " + exitCode + "]");
                if (showError) {
                    info("Command failed: `" + args[0] + "' (exit "
                            + exitCode + ").");
                }
                return null;
            }
        } catch (IOException e) {
            Logger.debug(commandLine + " [FAILED]");
            if (showError) {
                info("Needs `" + args[0] + "' command to extract audio.");
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Logger.debug(commandLine + " [INTERRUPTED]");
            return null;
        } finally {
            if (proc != null) {
                CloseUtil.close(in);
                CloseUtil.close(proc.getOutputStream());
                CloseUtil.close(proc.getErrorStream());
                proc.destroy();
            }
        }
        Logger.debug(commandLine);
        String commandOut = sb.toString();
        Logger.debug(commandOut);
        return new BufferedReader(new StringReader(commandOut));
    }

    private Resource createAudioResource() {
        if (audioFile.length() > 0) {
            try {
                Resource r = new TemporaryFileResource(audioFile);
                r.setResponseHeader(HttpHeader.CONTENT_TYPE, contentType);
                setContentDisposition(r, cache, userAgent, requestName);
                return r; // SUCCESS
            } catch (IOException e) {
                Logger.error(e);
            }
        } else {
            info("Cannot extract audio.");
        }
        audioFile.delete();
        return StringResource.getNotFound();
    }

    public static String getExt(String path) {
        int pos = path.lastIndexOf('.');
        return (pos != -1) ? path.substring(pos).toLowerCase() : "";
    }

    public static void setContentDisposition(Resource r, Cache cache, String userAgent, String downloadName) {
        String ext = getExt(downloadName);
        // ダウンロード時に動画タイトルをつける
        String filenameField = null;
        if (Boolean.getBoolean("useDownloadCacheName")) {
            try {
                filenameField = "filename*=UTF-8''" +
                        URLEncoder.encode(cache.getTitle() + ext, "UTF-8").replace("+", "%20");
            } catch (UnsupportedEncodingException e) {
            }
        }
        if (filenameField == null) {
            filenameField = "filename=\"" + downloadName + "\"";
        }
        r.setResponseHeader("Content-Disposition",
                            "attachment; " + filenameField);
    }
}
