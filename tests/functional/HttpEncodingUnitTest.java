package functional;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import com.github.luben.zstd.ZstdOutputStream;

import dareka.common.DefaultLoggerHandler;
import dareka.common.Logger;
import dareka.common.LoggerHandler;
import dareka.processor.HttpHeaderUtil;
import dareka.processor.HttpUtil;

/** HTTP圧縮方式の交渉と展開に関する回帰テスト。 */
public final class HttpEncodingUnitTest {
    private static final byte[] CONTENT =
            "encoding-functional-test".getBytes(StandardCharsets.UTF_8);

    private HttpEncodingUnitTest() {
    }

    public static void run() throws Exception {
        testAcceptEncodingAdjustment();
        testSupportedDecoders();
        testUnknownEncodingNotification();
    }

    private static void testAcceptEncodingAdjustment() {
        assertEquals("gzip, br, zstd, deflate",
                HttpHeaderUtil.adjustAcceptEncoding(
                        "gzip, br, zstd, deflate, dcb, dcz, compress, bzip2, sdch"),
                "未対応方式だけをAccept-Encodingから削除する");
        assertEquals("GZip;q=1.0,ZSTD;q=0.5,deflate;q=0,br;q=0",
                HttpHeaderUtil.adjustAcceptEncoding(
                        "GZip;q=1.0,ZSTD;q=0.5,deflate;q=0,br;q=0"),
                "大小文字とweightを保って対応方式だけを残す");
        assertEquals("gzip;q=0.5,deflate;q=0.5,br;q=0.5,zstd;q=0.5",
                HttpHeaderUtil.adjustAcceptEncoding("*;q=0.5"),
                "wildcardを展開可能な全方式へ置き換える");
        assertEquals("identity",
                HttpHeaderUtil.adjustAcceptEncoding("dcb, dcz, *;q=0"),
                "利用可能な圧縮方式がなければidentityにする");
        assertTrue(HttpHeaderUtil.isSupportedEncoding(" GZIP "),
                "対応方式の判定は大小文字と周辺空白を無視する");
    }

    private static void testSupportedDecoders() throws Exception {
        assertDecoded(CONTENT, "identity");
        assertDecoded(gzip(CONTENT), "gzip");
        assertDecoded(gzip(CONTENT), " GZIP ");
        assertDecoded(deflate(CONTENT, false), "deflate");
        assertDecoded(deflate(CONTENT, true), "deflate");
        assertDecoded(Base64.getDecoder().decode(
                "iw2ATmljb0NhY2hlIEJyb3RsaSBkZWNvZGUgdGVzdAM="), "br",
                "NicoCache Brotli decode test");
        assertDecoded(zstd(CONTENT), "zstd");
    }

    private static void testUnknownEncodingNotification() throws Exception {
        LoggerHandler original = Logger.getHandler();
        RecordingLogger logger = new RecordingLogger();
        Logger.setHandler(logger);
        try {
            InputStream decoded = HttpUtil.getDecodedInputStream(CONTENT, "futurezip");
            assertTrue(Arrays.equals(CONTENT, decoded.readAllBytes()),
                    "未知の方式は本文を変換せずに返す");
            assertContains(logger.warning, "futurezip",
                    "警告に未知の方式名を含める");
            assertContains(logger.warning, "対応を追加してください",
                    "GUIログへ対応追加を促す");
        } finally {
            Logger.setHandler(original);
        }
    }

    private static void assertDecoded(byte[] encoded, String encoding)
            throws Exception {
        assertDecoded(encoded, encoding, new String(CONTENT,
                StandardCharsets.UTF_8));
    }

    private static void assertDecoded(byte[] encoded, String encoding,
            String expected) throws Exception {
        InputStream input = HttpUtil.getDecodedInputStream(encoded, encoding);
        if (input == null) {
            throw new AssertionError("対応方式が未対応扱いになった: " + encoding);
        }
        try (InputStream decoded = input) {
            assertEquals(expected, new String(decoded.readAllBytes(),
                    StandardCharsets.UTF_8),
                    encoding + "の展開結果");
        }
    }

    private static byte[] gzip(byte[] source) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(source);
        }
        return output.toByteArray();
    }

    private static byte[] deflate(byte[] source, boolean raw) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflate = new DeflaterOutputStream(
                output, new Deflater(Deflater.DEFAULT_COMPRESSION, raw))) {
            deflate.write(source);
        }
        return output.toByteArray();
    }

    private static byte[] zstd(byte[] source) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZstdOutputStream zstd = new ZstdOutputStream(output)) {
            zstd.write(source);
        }
        return output.toByteArray();
    }

    private static void assertEquals(Object expected, Object actual,
            String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected
                    + ", actual=" + actual);
        }
    }

    private static void assertContains(String actual, String expected,
            String message) {
        if (actual == null || !actual.contains(expected)) {
            throw new AssertionError(message + ": expected fragment="
                    + expected + ", actual=" + actual);
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class RecordingLogger extends DefaultLoggerHandler {
        private String warning;

        @Override
        public void warning(String message) {
            warning = message;
        }
    }
}
