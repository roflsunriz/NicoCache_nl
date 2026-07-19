package dareka.common;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;


/**
 * [nl] ファイル処理関係のユーティリティクラス。
 * 基本的に例外は全てクラス内で処理するので、戻り値で成否を判断すること。
 */
public class FileUtil {
    private FileUtil() {} // avoid instantiation
    private static final int BUF_SIZE = 64 * 1024;

    /**
     * ファイル間でコピーする。コピー先にファイルが存在する場合は上書きする。
     * エラーが発生したらコピー先のファイルは削除される。
     * @param src コピー元のファイル
     * @param dst コピー先のファイル
     * @return コピーしたバイト数。0バイトも有り得る。エラーが発生したら-1
     */
    public static long copy(File src, File dst) {
        long len = -1;
        if (src != null && dst != null) {
            FileInputStream  in  = null;
            FileOutputStream out = null;
            try {
                // コピー元とコピー先が同じならコピーしない
                if (!src.getCanonicalPath().equals(dst.getCanonicalPath())) {
                    in = new FileInputStream(src);
                    FileChannel srcCh = in.getChannel();
                    out = new FileOutputStream(dst);
                    FileChannel dstCh = out.getChannel();
                    len = srcCh.transferTo(0, srcCh.size(), dstCh);
                }
            } catch (IOException e) {
                Logger.debugWithThread(e);
            } finally {
                CloseUtil.close(in);
                CloseUtil.close(out);
                if (len < 0) dst.delete();
            }
        }
        return len;
    }

    /**
     * ストリームからファイルにコピーする。コピー先にファイルが存在する場合は上書きする。
     * エラーが発生したらコピー先のファイルは削除される。
     * @param src コピー元のストリーム
     * @param dst コピー先のファイル
     * @return コピーしたバイト数。0バイトも有り得る。エラーが発生したら-1
     */
    public static int copy(InputStream src, File dst) {
        int len = -1;
        if (src != null && dst != null) {
            FileOutputStream out = null;
            try {
                out = new FileOutputStream(dst);
                len = 0;
                byte[] buf = new byte[BUF_SIZE];
                for (int n; (n = src.read(buf)) != -1; len += n)
                    out.write(buf, 0, n);
            } catch (IOException e) {
                Logger.debugWithThread(e);
                len = -1;
            } finally {
                CloseUtil.close(out);
                if (len < 0) dst.delete();
            }
        }
        return len;
    }

    /**
     * ファイルからストリームにコピーする。
     * @param src コピー元のファイル
     * @param dst コピー先のストリーム
     * @return コピーしたバイト数。0バイトも有り得る。エラーが発生したら-1
     */
    public static int copy(File src, OutputStream dst) {
        int len = -1;
        if (src != null && dst != null) {
            FileInputStream in = null;
            try {
                in = new FileInputStream(src);
                len = 0;
                byte[] buf = new byte[BUF_SIZE];
                for (int n; (n = in.read(buf)) != -1; len += n)
                    dst.write(buf, 0, n);
            } catch (IOException e) {
                Logger.debugWithThread(e);
                len = -1;
            } finally {
                CloseUtil.close(in);
            }
        }
        return len;
    }

    /**
     * バッファからファイルにコピーする。コピー先にファイルが存在する場合は上書きする。
     * エラーが発生したらコピー先のファイルは削除される。
     * @param src コピー元のバッファ
     * @param dst コピー先のファイル
     * @return コピーしたバイト数。0バイトも有り得る。エラーが発生したら-1
     */
    public static int copy(ByteBuffer src, File dst) {
        int len = -1;
        if (src != null && dst != null) {
            FileOutputStream out = null;
            try {
                out = new FileOutputStream(dst);
                FileChannel ch = out.getChannel();
                len = ch.write(src);
                ch.close();
            } catch (IOException e) {
                Logger.debugWithThread(e);
            } finally {
                CloseUtil.close(out);
                if (len < 0) dst.delete();
            }
        }
        return len;
    }

    /**
     * 文字列をutf-8としてファイルにコピーする。
     * コピー先にファイルが存在する場合は上書きする。
     * エラーが発生したらコピー先のファイルは削除される。
     * @param src コピー元のバッファ
     * @param dst コピー先のファイル
     * @return コピーしたバイト数。0バイトも有り得る。エラーが発生したら-1
     */
    public static int copy(String src, File dst) {
        if (src == null) {
            return -1;
        };
        if (dst == null) {
            return -1;
        };
        byte[] srcbytes = src.getBytes(StandardCharsets.UTF_8);

        try (FileOutputStream out = new FileOutputStream(dst)) {
            try (FileChannel ch = out.getChannel()) {
                return ch.write(ByteBuffer.wrap(srcbytes));
            }
        } catch (IOException e) {
            Logger.debugWithThread(e);
            dst.delete();
        }
        return -1;
    }

    /**
     * ファイルからバッファにコピーする。コピー先のバッファはコピー開始位置にマークされる。
     * エラーが発生したらコピー先のバッファはマークした開始位置までリセットされる。
     * @param src コピー元のファイル
     * @param dst コピー先のバッファ
     * @return コピーしたバイト数。0バイトも有り得る。エラーが発生したら-1
     */
    public static int copy(File src, ByteBuffer dst) {
        int len = -1;
        if (src != null && dst != null) {
            dst.mark();
            FileInputStream in = null;
            try {
                in = new FileInputStream(src);
                FileChannel ch = in.getChannel();
                len = ch.read(dst);
                ch.close();
            } catch (IOException e) {
                Logger.debugWithThread(e);
            } finally {
                CloseUtil.close(in);
                if (len < 0) dst.reset();
            }
        }
        return len;
    }

    /**
     * ストリームからストリームにコピーする。
     * @param src コピー元のストリーム
     * @param dst コピー先のストリーム
     * @return コピーしたバイト数。0バイトも有り得る。エラーが発生したら-1
     */
    public static int copy(InputStream src, OutputStream dst) {
        int len = -1;
        if (src != null && dst != null) {
            try {
                len = 0;
                byte[] buf = new byte[BUF_SIZE];
                for (int n; (n = src.read(buf)) != -1; len += n)
                    dst.write(buf, 0, n);
            } catch (IOException e) {
                Logger.debugWithThread(e);
                len = -1;
            }
        }
        return len;
    }

    /**
     * ファイルの文字セットを判別して{@link java.io.InputStreamReader}を生成して返す。
     * 文字セットを判別するために、日本語文字を含んだ開始文字列を指定する必要がある。
     * <br>
     * 以下のいずれかの場合はデフォルト文字セットで{@link java.io.InputStreamReader}を生成する。
     * <ul>
     * <li>開始文字列で始まっていない
     * <li>開始文字列に日本語文字が含まれていない
     * <li>開始文字列がnull、もしくは空文字列
     * <li>ファイルサイズが小さすぎる
     * </ul>
     * @param file 対象のファイル
     * @param startline 文字セットのテストに使う開始文字列
     * @return {@link java.io.InputStreamReader InputStreamReader}のインスタンス
     * @throws IOException ファイルの入出力で問題が発生した
     */
    public static InputStreamReader getInputStreamReader(
            File file, String startline) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("file must not be null");
        }
        BufferedInputStream in = new BufferedInputStream(new FileInputStream(file));
        if (startline != null) {
            try {
                Info info = getInfo(file, null, startline, in);
                return new InputStreamReader(in, info.charset);
            } catch (IOException e) {
                CloseUtil.close(in);
                throw e;
            }
        }
        return new InputStreamReader(in);
    }

    /**
     * ファイルの文字セットを判別して{@link java.io.OutputStreamWriter}を生成して返す。
     * 文字セットの判別条件は{@link #getInputStreamReader(File, String)}を参照のこと。
     * ファイルが存在する場合はappendモードでファイルをオープンします。
     * ファイルが存在しない場合はデフォルト文字セットでファイルを作成します。
     *
     * @param file 対象のファイル
     * @param startline 文字セットのテストに使う開始文字列
     * @return {@link java.io.OutputStreamWriter OutputStreamWriter}のインスタンス
     * @throws IOException  ファイルの入出力で問題が発生した
     * @see #getInputStreamReader(File, String)
     */
    public static OutputStreamWriter getOutputStreamWriter(
            File file, String startline) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("file must not be null");
        }
        if (!file.exists() || startline == null) {
            return new OutputStreamWriter(new FileOutputStream(file));
        }
        Info info;
        BufferedInputStream in = new BufferedInputStream(new FileInputStream(file));
        try {
            info = getInfo(file, null, startline, in);
        } finally {
            CloseUtil.close(in);
        }
        return new OutputStreamWriter(
                new FileOutputStream(file, true), info.charset);
    }

    public static final boolean DIR_HAS_FILE_ATTRIBUTES;
    static {
        String osName = System.getProperty("os.name", "unknown").toLowerCase();
        if ((osName.contains("mac")) || (osName.contains("darwin"))) {
            // MacOSX
            DIR_HAS_FILE_ATTRIBUTES = false;
        } else if (osName.contains("win")) {
            // Windows
            DIR_HAS_FILE_ATTRIBUTES = true;
        } else {
            // Linux, others
            DIR_HAS_FILE_ATTRIBUTES = false;
        }
    }

    /**
     * ディレクトリ直下のエントリを {@link java.io.File} と
     * {@link java.nio.file.attribute.BasicFileAttributes} のペアで
     * 一覧するStreamを返す。
     * エントリ一覧取得時にIOExceptionが発生した場合は空のStreamを返す。
     * 属性取得時にIOExceptionが発生した場合はattrsがnullになる。
     *
     * @param dir 対象ディレクトリ
     * @return {@link java.util.stream.Stream}のインスタンス
     */
    public static Stream<FileWithBasicFileAttributes> getEntriesStream(File dir) {
        try {
            Path dirPath = dir.toPath();
            if (DIR_HAS_FILE_ATTRIBUTES) {
                // Files.find で attrs のキャッシュが使える (Windows)
                // -> findでキャッシュから属性情報を取得

                // Windows上のJavaではディレクトリ内のファイルリスト取得時に属性情報も同時に取得する
                // https://github.com/openjdk/jdk/blob/jdk8-b120/jdk/src/windows/classes/sun/nio/fs/WindowsDirectoryStream.java#L192
                //
                // Files.find() の第3引数ではそのキャッシュ情報が使用できるので、Windowsでは高速
                // https://github.com/openjdk/jdk/blob/jdk8-b120/jdk/src/share/classes/java/nio/file/Files.java#L3678
                // https://github.com/openjdk/jdk/blob/jdk8-b120/jdk/src/share/classes/java/nio/file/FileTreeIterator.java
                // https://github.com/openjdk/jdk/blob/jdk8-b120/jdk/src/share/classes/java/nio/file/FileTreeWalker.java#L205
                List<FileWithBasicFileAttributes> filesWithAttributes = new ArrayList<>();
                try (Stream<Path> stream = Files.find(dirPath, 1, (path, attrs) -> {
                    if (!path.equals(dirPath)) {
                        filesWithAttributes.add(new FileWithBasicFileAttributes(path.toFile(), attrs));
                    }
                    return false;
                }, FileVisitOption.FOLLOW_LINKS)) {
                    stream.count();
                }
                return filesWithAttributes.stream();
            } else {
                // Files.find で attrs のキャッシュが使えない (Unix)
                // -> 並列にlstatシステムコールを発行できるようにmapで属性情報を取得
                // Files#listはcloseしないとディレクトリのfdがGC後もリークしてしまう
                try (Stream<Path> stream = Files.list(dirPath)) {
                    // listはparallelに動作しないので一旦リストにしてしまう
                    return stream
                        .collect(Collectors.toList()).stream()
                        .map(path -> {
                            BasicFileAttributes attrs;
                            try {
                                attrs = Files.readAttributes(path, BasicFileAttributes.class);
                            } catch (IOException e) {
                                attrs = null;
                            }
                            return new FileWithBasicFileAttributes(path.toFile(), attrs);
                        });
                }
            }
        } catch (IOException e) {
            Logger.debugWithThread(e);
        }
        return Stream.empty();
    }

    public static class FileWithBasicFileAttributes {
        public File file;
        public BasicFileAttributes attrs;

        public FileWithBasicFileAttributes(File file, BasicFileAttributes attrs) {
            this.file = file;
            this.attrs = attrs;
        }
    }

    // TODO いろいろ整理
    public static class Info {
        public long lastModified;
        public String startline, line_separator;
        public Charset charset;

        public Info(File file) {
            this(file, null);
        }
        public Info(File file, String startline) {
            this.lastModified = file.lastModified();
            this.startline = startline;
        }
    }

    public static Info getInfo(File file, Info info, String startline,
            BufferedInputStream in) throws IOException {
        info = getInfo(file, info, startline);
        if (in == null) return info;

        long length = file.length();
        byte[] testBuf = new byte[length > 256L ? 256 : (int) length];
        in.mark(testBuf.length);
        in.read(testBuf);
        in.reset();
        String s = getCharsetNameFromBOM(testBuf, in);
        if (s != null) {
            info.charset = Charset.forName(s);
        } else if (info.startline != null) {
            // 1文字4バイト未満で判定
            int testSize = info.startline.length() * 4;
            if (testSize > testBuf.length) {
                testSize = testBuf.length;
            }
            for (Charset c : SUPPORTED_CHARSET) {
                s = new String(testBuf, 0, testSize, c.name()); // JDK1.1
                if (s.startsWith(info.startline)) {
                    info.charset = c; break;
                }
            }
        }
        if (info.charset == null) {
            info.charset = Charset.defaultCharset();
        }
        info.line_separator = guessLineSeparator(testBuf);

        Logger.debugWithThread(
                file.getPath() + ": charset=" + info.charset +
                getLineSeparatorSymbol(info.line_separator));
        return info;
    }

    public static Info getInfo(File file, Info info, String startline) {
        if (file == null) {
            throw new IllegalArgumentException("file must not be null");
        }
        if (info == null) {
            info = new Info(file, startline);
        } else {
            long lastModified = file.lastModified();
            if (info.lastModified != lastModified) {
                info.lastModified = lastModified;
                info.charset = null;
                info.line_separator = null;
            }
        }
        return info;
    }

    private static final Charset[] SUPPORTED_CHARSET = {
            Charset.forName("MS932"),
            Charset.forName("UTF-8"),
            Charset.forName("UTF-16BE"),
            Charset.forName("UTF-16LE"),
            Charset.forName("EUC-JP")
    };

    private static String getCharsetNameFromBOM(
            byte[] buf, BufferedInputStream in) throws IOException {
        if (buf.length < 3) {
            return null;
        }
        if (buf[0] == (byte)0xEF && buf[1] == (byte)0xBB && buf[2] == (byte)0xBF) {
            if (in != null) in.skip(3);
            return "UTF-8";
        } else if (buf[0] == (byte)0xFE && buf[1] == (byte)0xFF) {
            if (in != null) in.skip(2);
            return "UTF-16BE";
        } else if (buf[0] == (byte)0xFF && buf[1] == (byte)0xFE) {
            if (in != null) in.skip(2);
            return "UTF-16LE";
        }
        return null;
    }

    public static String guessLineSeparator(byte[] testBuf) {
        int cr = 0, lf = 0;
        for (int i = 0; i < testBuf.length; i++) {
            if (testBuf[i] == 0x0a) {
                lf++;
            } else if (testBuf[i] == 0x0d) {
                cr++;
            }
        }
        if (cr > 0 && lf > 0 && Math.abs(cr - lf) < 2) {
            return "\r\n";
        } else if (cr < lf) {
            return "\n";
        } else if (cr > lf) {
            return "\r";
        }
        return System.getProperty("line.separator");
    }

    private static String getLineSeparatorSymbol(String ls) {
        if ("\r\n".equals(ls)) {
            return " CRLF";
        } else if ("\n".equals(ls)) {
            return " LF";
        } else if ("\r".equals(ls)) {
            return " CR";
        }
        return "";
    }
}
