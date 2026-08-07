package dareka.processor;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import org.brotli.dec.BrotliInputStream;

import com.github.luben.zstd.ZstdInputStream;

import dareka.common.Logger;

public class HttpUtil {
    private static final int BUF_SIZE = 32 * 1024;

    private HttpUtil() {
        // avoid instantiation
    }

    public static void sendHeader(Socket receiver, HttpHeader header)
            throws IOException {
        sendHeader(receiver.getOutputStream(), header);
    }

    public static void sendHeader(OutputStream receiverOut, HttpHeader header)
            throws IOException {
        byte[] headerBytes = header.getBytes();
        receiverOut.write(headerBytes);
    }

    public static void sendBody(Socket receiver, Socket sender,
            long contentLength) throws IOException {
        SocketChannel senderCh = sender.getChannel();
        SocketChannel receiverCh = receiver.getChannel();

        sendBodyOnChannel(receiverCh, senderCh, contentLength);
    }

    public static void sendBody(OutputStream out, InputStream in,
            long contentLength) throws IOException {
        sendBodyOnChannel(Channels.newChannel(out), Channels.newChannel(in),
                contentLength);

    }

    private static void sendBodyOnChannel(WritableByteChannel receiverCh,
            ReadableByteChannel senderCh, long contentLength)
            throws IOException {
        long maxLength = contentLength == -1 ? Long.MAX_VALUE : contentLength;

        ByteBuffer bbuf = ByteBuffer.allocate(BUF_SIZE);
        int len = 0;
        for (long currentLength = 0; currentLength < maxLength; currentLength +=
                len) {
            bbuf.clear();
            long remain = maxLength - currentLength;
            if (remain < bbuf.limit()) {
                bbuf.limit((int) remain);
            }

            len = senderCh.read(bbuf);
            if (len == -1) {
                break;
            }

            bbuf.flip();
            receiverCh.write(bbuf);
        }

        if (contentLength != -1 && len == -1) {
            Logger.warning("content may be imcomplete.");
        }
    }

    public static InputStream getDecodedInputStream(byte[] content,
            String contentEncoding) throws IOException {
        return getDecodedInputStream(new ByteArrayInputStream(content), contentEncoding);
    }

    /**
     * [nl] エンコーディングに従ってデコードした入力ストリームを返す
     * @param in 入力ストリーム
     * @param contentEncoding エンコーディング
     * @return デコードされた入力ストリーム。未対応Encodingの場合は元のストリーム
     * @throws IOException 入出力エラーが発生した場合
     * @since NicoCache_nl+111111mod
     */
    public static InputStream getDecodedInputStream(InputStream in,
            String contentEncoding) throws IOException {
        String normalized = contentEncoding == null ? null : contentEncoding.trim();
        if (HttpHeader.CONTENT_ENCODING_GZIP.equalsIgnoreCase(normalized)) {
            return new GZIPInputStream(in);
        } else if (HttpHeader.CONTENT_ENCODING_DEFLATE.equalsIgnoreCase(normalized)) {
            return getInflaterInputStream(in);
        } else if ("br".equalsIgnoreCase(normalized)) {
            return new BrotliInputStream(in);
        } else if ("zstd".equalsIgnoreCase(normalized)) {
            return new ZstdInputStream(in);
        } else if (HttpHeader.IDENTITY.equalsIgnoreCase(normalized)) {
            // [nl] 本来Content-Encodingにidentityは使用すべきではないが
            // それでも使用される場合は単に無視する
            // see RFC 2616 3.5 Content Codings
            return in;
        }

        if (contentEncoding != null) {
            Logger.warning("未対応のContent-Encoding「" + contentEncoding
                    + "」を検出しました。本文は変換せずそのまま転送します。"
                    + "NicoCache_nl側で対応を追加してください。");
        }

        return in;
    }

    // [nl] ZLIBヘッダをチェックして適切なInflaterを生成して返す
    // http://stackoverflow.com/questions/3932117/handling-http-contentencoding-deflate
    private static InputStream getInflaterInputStream(InputStream in) throws IOException {
    	if (!in.markSupported()) {
    		in = new BufferedInputStream(in);
    	}
    	byte[] header = new byte[2];
    	in.mark(header.length);
    	in.read(header);
    	in.reset();
    	boolean nowrap = true;
    	byte cm = (byte)(header[0] & 0x0f);
    	if (cm == 8 && (header[0] & 0x80) == 0 && header.length > 1) {
    		int n = (((int) header[0] << 8) + ((int) header[1] & 0xff));
    		if (n % 31 == 0) {
    			nowrap = false; // ZLIBヘッダ有効
    		}
    	}
    	return new InflaterInputStream(in, new Inflater(nowrap));
    }
}
