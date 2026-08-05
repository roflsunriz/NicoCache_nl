package dareka.processor.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dareka.common.CloseUtil;
import dareka.common.Logger;
import dareka.common.TextUtil;

// Flv 2 mp4 converter
public class Flv2mp4 {
    /**
     * Flv形式からMPEG4形式に変換する．
     * @param src 変換元ファイル
     * @param dst 変換先ファイル
     * @return 成功時true
     */
    public static boolean convert(File src, File dst) {
        String vcodec = "copy";
        String acodec = "copy";

        Codecs src_codecs = detectCodecs(src);
        if (src_codecs == null) {
            return false;
        }
        if (!src_codecs.vcodec.equals("h264")) {
            vcodec = "h264";
        }
        if (src_codecs.acodec.equals("mp3")) {
            if (src_codecs.ar < 44100) {
                // Firefoxは 22050 Hz の mp3 を認識できない
                acodec = "aac";
            }
        } else if (!src_codecs.acodec.equals("aac")) {
            acodec = "aac";
        }

        Logger.info("Converting flv to mp4 [vcodec: %s -> %s, acodec %s -> %s]: %s",
                src_codecs.vcodec, vcodec, src_codecs.acodec, acodec, src.getName());

        long t0 = System.currentTimeMillis();

        boolean success = false;
        if (Boolean.getBoolean("enableFfmpegServer")) {
            String[] args_template = {
                "-y", "-i", RemoteFfmpeg.INPUT_FILENAME_PLACEHOLDER,
                "-vcodec", vcodec, "-acodec", acodec,
                "-strict", "-1", // allow non-standard sampling rate
                "-f", "mp4", RemoteFfmpeg.OUTPUT_FILENAME_PLACEHOLDER};
            success = RemoteFfmpeg.convert(src, dst, args_template, null);
            if (!success) {
                Logger.info("Fallback to local ffmpeg.");
            }
        }
        if (!success) {
            String[] command = {"ffmpeg", "-y", "-i", src.getAbsolutePath(),
                "-vcodec", vcodec, "-acodec", acodec,
                "-strict", "-1", // allow non-standard sampling rate
                dst.getAbsolutePath()};
            success = execCommand(command, /* null */ new StringBuilder(), true);
        }

        long t1 = System.currentTimeMillis();
        long tdelta = t1 - t0;

        Logger.info("Done flv2mp4: %s (%.1f sec)", src.getName(), tdelta / 1000.0);

        return success;
    }

    private static final Pattern VIDEO_CODEC_PATTERN = Pattern.compile(": Video: (\\w+)");
    private static final Pattern AUDIO_CODEC_PATTERN = Pattern.compile(": Audio: (\\w+).*?(\\d+) Hz");

    /**
     * ファイルの映像コーデックと音声コーデックを検出する．
     * @param src 検出対象ファイル
     * @return Codecs. 失敗時はnull
     */
    private static Codecs detectCodecs(File src) {
        String vcodec = null, acodec = null;
        int ar = 0;
        String[] command = {"ffmpeg", "-i", src.getAbsolutePath()};
        StringBuilder outputsb = new StringBuilder();
        // ffmpegは出力先ファイルを指定しないと終了コード1を返すので戻り値は無視
        execCommand(command, outputsb, true);

        String output = outputsb.toString();
        Matcher mv = VIDEO_CODEC_PATTERN.matcher(output);
        if (mv.find()) {
            vcodec = mv.group(1);
        }
        Matcher ma = AUDIO_CODEC_PATTERN.matcher(output);
        if (ma.find()) {
            acodec = ma.group(1);
            ar = Integer.parseInt(ma.group(2));
        }
        if (vcodec == null || acodec == null) {
            return null;
        }
        return new Codecs(vcodec, acodec, ar);
    }

    private static class Codecs {
        String vcodec;
        String acodec;
        int ar;
        Codecs(String vcodec, String acodec, int ar) {
            this.vcodec = vcodec;
            this.acodec = acodec;
            this.ar = ar;
        }
    }

    private static boolean execCommand(String[] args, StringBuilder output, boolean showError) {
        boolean error = false;
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
                if (output != null) {
                    output.append(line);
                    output.append('\n');
                }
            }
            int ret = proc.waitFor();
            error = ret != 0;
        } catch (IOException e) {
            Logger.debug(commandLine + " [FAILED]");
            if (showError) {
                Logger.info("Needs `" + args[0] + "' command to convert flv to mp4.");
            }
            return false;
        } catch (InterruptedException e) {
            Logger.error(e);
            error = true;
        } finally {
            if (proc != null) {
                CloseUtil.close(in);
                CloseUtil.close(proc.getOutputStream());
                CloseUtil.close(proc.getErrorStream());
                proc.destroy();
            }
        }
        Logger.debug(commandLine);
        if (output != null) {
            Logger.debug(output.toString());
        }
        return !error;
    }
}
