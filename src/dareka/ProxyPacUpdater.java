package dareka;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

import dareka.common.Logger;

/** 既存の利用者用PACへ専用管理ホストのローカル経路を一度だけ追加する。 */
final class ProxyPacUpdater {
    private static final String HOST = "nicocachenl.test";
    private static final String ROUTE_MARKER =
            "host.toLowerCase() === '" + HOST + "'";
    private static final String FUNCTION = "function FindProxyForURL(url, host) {";
    private static final Charset WINDOWS_31J = Charset.forName("windows-31j");
    private static final byte[] UTF_8_BOM = {
        (byte) 0xef, (byte) 0xbb, (byte) 0xbf
    };
    private static final byte[] UTF_16LE_BOM = {
        (byte) 0xff, (byte) 0xfe
    };
    private static final byte[] UTF_16BE_BOM = {
        (byte) 0xfe, (byte) 0xff
    };

    private ProxyPacUpdater() {
    }

    static void update() {
        Path pac = NicoCachePaths.proxyPacFile().toPath();
        if (!Files.isRegularFile(pac)) {
            return;
        }
        Path temporary = pac.resolveSibling("proxy.pac.rest-api.part");
        try {
            DecodedPac decoded = decode(Files.readAllBytes(pac));
            String source = decoded.text;
            if (source.contains(ROUTE_MARKER)) {
                return;
            }
            int insertAt = decoded.byteIndexAfter(FUNCTION);
            if (insertAt < 0) {
                Logger.warning("proxy.pacへ専用管理ホストを追加できません: FindProxyForURLがありません");
                return;
            }
            String newline = source.contains("\r\n") ? "\r\n"
                    : source.contains("\r") ? "\r" : "\n";
            String route = newline
                    + "  // NicoCache_nl management site and REST API." + newline
                    + "  if (host.toLowerCase() === '" + HOST + "') {" + newline
                    + "    return 'PROXY 127.0.0.1:"
                    + Integer.getInteger("listenPort", 8080) + "';" + newline
                    + "  };" + newline;
            Path backup = pac.resolveSibling("proxy.pac.pre-rest-api.bak");
            if (!Files.exists(backup)) {
                Files.copy(pac, backup, StandardCopyOption.COPY_ATTRIBUTES);
            }
            Files.write(temporary, decoded.insert(insertAt, route));
            try {
                Files.move(temporary, pac, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException error) {
                Files.move(temporary, pac, StandardCopyOption.REPLACE_EXISTING);
            }
            Logger.info("proxy.pacへ" + HOST + "のローカル経路を追加しました");
        } catch (IOException error) {
            Logger.warning("proxy.pacの専用管理ホスト移行に失敗しました: " + error);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException error) {
                Logger.debugWithThread(error);
            }
        }
    }

    private static DecodedPac decode(byte[] bytes) throws IOException {
        if (startsWith(bytes, UTF_8_BOM)) {
            return decode(bytes, UTF_8_BOM.length,
                    StandardCharsets.UTF_8, UTF_8_BOM);
        }
        if (startsWith(bytes, UTF_16LE_BOM)) {
            return decode(bytes, UTF_16LE_BOM.length,
                    StandardCharsets.UTF_16LE, UTF_16LE_BOM);
        }
        if (startsWith(bytes, UTF_16BE_BOM)) {
            return decode(bytes, UTF_16BE_BOM.length,
                    StandardCharsets.UTF_16BE, UTF_16BE_BOM);
        }
        if (looksLikeUtf16(bytes, true)) {
            return decode(bytes, 0, StandardCharsets.UTF_16LE, new byte[0]);
        }
        if (looksLikeUtf16(bytes, false)) {
            return decode(bytes, 0, StandardCharsets.UTF_16BE, new byte[0]);
        }
        try {
            return decode(bytes, 0, StandardCharsets.UTF_8, new byte[0]);
        } catch (CharacterCodingException error) {
            try {
                return decode(bytes, 0, WINDOWS_31J, new byte[0]);
            } catch (CharacterCodingException fallbackError) {
                IOException failure = new IOException(
                        "proxy.pacの文字コードを判定できません");
                failure.addSuppressed(error);
                failure.addSuppressed(fallbackError);
                throw failure;
            }
        }
    }

    private static DecodedPac decode(byte[] bytes, int offset,
            Charset charset, byte[] bom) throws CharacterCodingException {
        CharBuffer decoded = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset));
        return new DecodedPac(decoded.toString(), charset, bom, bytes);
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
        int expectedPositions = 0;
        for (int index = littleEndian ? 1 : 0;
                index < sample; index += 2) {
            expectedPositions++;
            if (bytes[index] == 0) {
                zeroes++;
            }
        }
        return zeroes >= Math.max(2, expectedPositions * 3 / 4);
    }

    private static final class DecodedPac {
        private final String text;
        private final Charset charset;
        private final byte[] bom;
        private final byte[] original;

        private DecodedPac(String text, Charset charset, byte[] bom,
                byte[] original) {
            this.text = text;
            this.charset = charset;
            this.bom = Arrays.copyOf(bom, bom.length);
            this.original = Arrays.copyOf(original, original.length);
        }

        private int byteIndexAfter(String value) throws IOException {
            byte[] encoded = encode(value);
            int index = indexOf(original, encoded, bom.length);
            return index < 0 ? -1 : index + encoded.length;
        }

        private byte[] insert(int offset, String value) throws IOException {
            byte[] inserted = encode(value);
            byte[] result = new byte[original.length + inserted.length];
            System.arraycopy(original, 0, result, 0, offset);
            System.arraycopy(inserted, 0, result, offset, inserted.length);
            System.arraycopy(original, offset, result,
                    offset + inserted.length, original.length - offset);
            return result;
        }

        private byte[] encode(String value) throws IOException {
            try {
                ByteBuffer encoded = charset.newEncoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .encode(CharBuffer.wrap(value));
                byte[] result = new byte[encoded.remaining()];
                encoded.get(result);
                return result;
            } catch (CharacterCodingException error) {
                throw new IOException(
                        "proxy.pacを元の文字コードで保存できません: "
                        + charset.name(), error);
            }
        }

        private static int indexOf(byte[] haystack, byte[] needle,
                int fromIndex) {
            if (needle.length == 0) {
                return fromIndex;
            }
            for (int index = Math.max(0, fromIndex);
                    index <= haystack.length - needle.length; index++) {
                boolean matches = true;
                for (int offset = 0; offset < needle.length; offset++) {
                    if (haystack[index + offset] != needle[offset]) {
                        matches = false;
                        break;
                    }
                }
                if (matches) {
                    return index;
                }
            }
            return -1;
        }
    }
}
