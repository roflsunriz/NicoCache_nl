package dareka.internal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

/** Shared strict decoder and UTF-8 canonicalizer for managed text files. */
public final class TextFileCodec {
    public enum Encoding {
        UTF8("UTF-8"),
        UTF8_BOM("UTF-8 BOM"),
        UTF16_LE("UTF-16LE"),
        UTF16_BE("UTF-16BE"),
        WINDOWS_31J("Windows-31J"),
        EUC_JP("EUC-JP");

        private final String displayName;

        Encoding(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    public enum FailureReason {
        UNRECOGNIZED,
        AMBIGUOUS,
        INVALID_FORMAT
    }

    public static final class TextDecodingException extends IOException {
        private static final long serialVersionUID = 1L;
        private final FailureReason reason;

        TextDecodingException(FailureReason reason, String message) {
            super(message);
            this.reason = reason;
        }

        public FailureReason getReason() {
            return reason;
        }
    }

    public static final class DecodedText {
        private final String text;
        private final Encoding encoding;
        private final Charset charset;
        private final int bomLength;

        private DecodedText(String text, Encoding encoding, Charset charset,
                int bomLength) {
            this.text = text;
            this.encoding = encoding;
            this.charset = charset;
            this.bomLength = bomLength;
        }

        public String getText() {
            return text;
        }

        public Encoding getEncoding() {
            return encoding;
        }

        public Charset getCharset() {
            return charset;
        }

        public int getBomLength() {
            return bomLength;
        }

        public boolean isCanonicalUtf8() {
            return encoding == Encoding.UTF8;
        }

        public byte[] toCanonicalUtf8() {
            return text.getBytes(StandardCharsets.UTF_8);
        }
    }

    /** Compatibility result for readers that historically inspect a prefix. */
    public static final class PrefixDetection {
        private final Charset charset;
        private final int bomLength;

        private PrefixDetection(Charset charset, int bomLength) {
            this.charset = charset;
            this.bomLength = bomLength;
        }

        public Charset getCharset() {
            return charset;
        }

        public int getBomLength() {
            return bomLength;
        }
    }

    private static final Charset WINDOWS_31J = Charset.forName("windows-31j");
    private static final Charset EUC_JP = Charset.forName("EUC-JP");
    private static final byte[] UTF8_BOM = {
        (byte) 0xef, (byte) 0xbb, (byte) 0xbf
    };
    private static final byte[] UTF16LE_BOM = {
        (byte) 0xff, (byte) 0xfe
    };
    private static final byte[] UTF16BE_BOM = {
        (byte) 0xfe, (byte) 0xff
    };

    private TextFileCodec() {
    }

    public static PrefixDetection detectPrefix(byte[] prefix,
            String expectedStartline, Charset fallback) {
        if (prefix.length >= 3 && startsWith(prefix, UTF8_BOM)) {
            return new PrefixDetection(StandardCharsets.UTF_8,
                    UTF8_BOM.length);
        }
        if (prefix.length >= 3 && startsWith(prefix, UTF16BE_BOM)) {
            return new PrefixDetection(StandardCharsets.UTF_16BE,
                    UTF16BE_BOM.length);
        }
        if (prefix.length >= 3 && startsWith(prefix, UTF16LE_BOM)) {
            return new PrefixDetection(StandardCharsets.UTF_16LE,
                    UTF16LE_BOM.length);
        }
        if (expectedStartline != null) {
            int testSize = Math.min(expectedStartline.length() * 4,
                    prefix.length);
            for (Charset charset : List.of(WINDOWS_31J,
                    StandardCharsets.UTF_8, StandardCharsets.UTF_16BE,
                    StandardCharsets.UTF_16LE, EUC_JP)) {
                String decoded = new String(prefix, 0, testSize, charset);
                if (decoded.startsWith(expectedStartline)) {
                    return new PrefixDetection(charset, 0);
                }
            }
        }
        return new PrefixDetection(fallback, 0);
    }

    public static DecodedText decode(byte[] bytes,
            Predicate<String> validator) throws TextDecodingException {
        Predicate<String> effectiveValidator = validator == null
                ? value -> true : validator;
        if (startsWith(bytes, UTF8_BOM)) {
            return explicit(bytes, UTF8_BOM.length, StandardCharsets.UTF_8,
                    Encoding.UTF8_BOM, effectiveValidator);
        }
        if (startsWith(bytes, UTF16LE_BOM)) {
            return explicit(bytes, UTF16LE_BOM.length,
                    StandardCharsets.UTF_16LE, Encoding.UTF16_LE,
                    effectiveValidator);
        }
        if (startsWith(bytes, UTF16BE_BOM)) {
            return explicit(bytes, UTF16BE_BOM.length,
                    StandardCharsets.UTF_16BE, Encoding.UTF16_BE,
                    effectiveValidator);
        }
        if (looksLikeUtf16(bytes, true)) {
            DecodedText decoded = candidate(bytes, 0,
                    StandardCharsets.UTF_16LE, Encoding.UTF16_LE,
                    effectiveValidator);
            if (decoded != null) {
                return decoded;
            }
        }
        if (looksLikeUtf16(bytes, false)) {
            DecodedText decoded = candidate(bytes, 0,
                    StandardCharsets.UTF_16BE, Encoding.UTF16_BE,
                    effectiveValidator);
            if (decoded != null) {
                return decoded;
            }
        }
        DecodedText utf8 = candidate(bytes, 0, StandardCharsets.UTF_8,
                Encoding.UTF8, value -> true);
        if (utf8 != null) {
            if (effectiveValidator.test(utf8.getText())) {
                return utf8;
            }
            throw new TextDecodingException(FailureReason.INVALID_FORMAT,
                    "UTF-8として読み取れますが、ファイル形式が不正です");
        }

        List<DecodedText> legacyCandidates = new ArrayList<>();
        addCandidate(legacyCandidates, bytes, WINDOWS_31J,
                Encoding.WINDOWS_31J, effectiveValidator);
        addCandidate(legacyCandidates, bytes, EUC_JP,
                Encoding.EUC_JP, effectiveValidator);
        if (legacyCandidates.size() == 1) {
            return legacyCandidates.get(0);
        }
        if (legacyCandidates.size() > 1) {
            throw new TextDecodingException(FailureReason.AMBIGUOUS,
                    "文字コードを一意に判定できません");
        }
        throw new TextDecodingException(FailureReason.UNRECOGNIZED,
                "対応する文字コードとして読み取れません");
    }

    private static DecodedText explicit(byte[] bytes, int offset,
            Charset charset, Encoding encoding, Predicate<String> validator)
            throws TextDecodingException {
        DecodedText decoded = candidate(bytes, offset, charset, encoding,
                validator);
        if (decoded == null) {
            throw new TextDecodingException(FailureReason.INVALID_FORMAT,
                    encoding.getDisplayName()
                    + "として読み取れますが、ファイル形式が不正です");
        }
        return decoded;
    }

    private static void addCandidate(List<DecodedText> candidates,
            byte[] bytes, Charset charset, Encoding encoding,
            Predicate<String> validator) {
        DecodedText candidate = candidate(bytes, 0, charset, encoding,
                validator);
        if (candidate != null) {
            candidates.add(candidate);
        }
    }

    private static DecodedText candidate(byte[] bytes, int offset,
            Charset charset, Encoding encoding, Predicate<String> validator) {
        try {
            CharBuffer decoded = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset,
                            bytes.length - offset));
            String text = decoded.toString();
            if (!validator.test(text)) {
                return null;
            }
            ByteBuffer encoded = charset.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(text));
            byte[] roundTrip = new byte[encoded.remaining()];
            encoded.get(roundTrip);
            byte[] payload = Arrays.copyOfRange(bytes, offset, bytes.length);
            if (!Arrays.equals(payload, roundTrip)) {
                return null;
            }
            return new DecodedText(text, encoding, charset, offset);
        } catch (CharacterCodingException | RuntimeException error) {
            return null;
        }
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (bytes[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private static boolean looksLikeUtf16(byte[] bytes, boolean littleEndian) {
        int sample = Math.min(bytes.length, 32);
        if (sample < 4) {
            return false;
        }
        int zeroes = 0;
        int expected = 0;
        for (int index = littleEndian ? 1 : 0; index < sample; index += 2) {
            expected++;
            if (bytes[index] == 0) {
                zeroes++;
            }
        }
        return zeroes >= Math.max(2, expected * 3 / 4);
    }
}
