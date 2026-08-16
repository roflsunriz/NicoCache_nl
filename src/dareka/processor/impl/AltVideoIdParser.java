package dareka.processor.impl;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NicoCacheの代替動画IDを完全一致で解析する。
 */
final class AltVideoIdParser {

    private static final String POSTFIX = "\\.(?:swf|flv|mp4|hls|webm|mkv)";

    private static final Pattern DMC_PATTERN = Pattern.compile(
            "^([a-z]{2}\\d{1,9})(low)?"
                    + "\\[([\\w-]+)(?:,(\\d+))?,(\\d+)\\]"
                    + "(\\w*)(" + POSTFIX + ")$");

    private static final Pattern CLASSIC_PATTERN = Pattern.compile(
            "^([a-z]{2}\\d{1,9})(low)?(" + POSTFIX + ")?$");

    private AltVideoIdParser() {
    }

    static ParsedId parse(String altId) {
        return parse(altId, null);
    }

    /**
     * @param altId 解析する代替動画ID
     * @param classicPostfix 拡張子を含まないclassic IDへ補う拡張子。null可
     * @return 完全一致した解析結果。一致しなければnull
     */
    static ParsedId parse(String altId, String classicPostfix) {
        if (altId == null) {
            return null;
        }

        Matcher dmc = DMC_PATTERN.matcher(altId);
        if (dmc.matches()) {
            try {
                int videoBitrate = dmc.group(4) == null
                        ? 0 : Integer.parseInt(dmc.group(4));
                int audioBitrate = Integer.parseInt(dmc.group(5));
                VideoDescriptor video = VideoDescriptor.newDmc(
                        dmc.group(1), dmc.group(7), dmc.group(2) != null,
                        dmc.group(3), videoBitrate, audioBitrate, dmc.group(6));
                return new ParsedId(altId, dmc.group(1), video);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        Matcher classic = CLASSIC_PATTERN.matcher(altId);
        if (!classic.matches()) {
            return null;
        }
        String postfix = classic.group(3) == null
                ? classicPostfix : classic.group(3);
        VideoDescriptor video = VideoDescriptor.newClassic(
                classic.group(1), postfix, classic.group(2) != null);
        return new ParsedId(altId, classic.group(1), video);
    }

    static final class ParsedId {
        final String altId;
        final String smid;
        final VideoDescriptor video;

        private ParsedId(String altId, String smid, VideoDescriptor video) {
            this.altId = altId;
            this.smid = smid;
            this.video = video;
        }
    }
}
