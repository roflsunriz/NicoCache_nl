package dareka.processor.util;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;
import java.net.Socket;
import java.net.URLEncoder;

import dareka.common.Config;
import dareka.common.Logger;
import dareka.processor.HttpRequestHeader;
import dareka.processor.HttpResponseHeader;
import dareka.processor.HttpUtil;
import dareka.processor.Resource;
import dareka.processor.impl.Cache;

//flv 2 mp3 converter
public class Flv2mp3 extends Resource
{
    private long fileOffset;
    private long fileLength;
    private RandomAccessFile raf = null;
    private Cache cache = null;
    private boolean writeFailed = false;

    // Resource.transferTo
    @Override
    public boolean transferTo(Socket receiver, HttpRequestHeader requestHeader,
            Config config) throws IOException {

        HttpResponseHeader responseHeader = new HttpResponseHeader(
                "HTTP/1.0 200 OK\r\n\r\n");
        responseHeader.setMessageHeader("Connection", "close");
        responseHeader.setMessageHeader("Content-Type", "audio/mp3");

// ダウンロード時に動画タイトルをつける
        String downloadName = cache.getId() + ".mp3";
        String filenameField = null;
        if (Boolean.getBoolean("useDownloadCacheName")) {
            try {
                filenameField = "filename*=UTF-8''" +
                        URLEncoder.encode(cache.getTitle() + ".mp3", "UTF-8").replace("+", "%20");
            } catch (UnsupportedEncodingException e) {
            }
        }
        if (filenameField == null) {
            filenameField = "filename=\"" + downloadName + "\"";
        }
        responseHeader.setMessageHeader("Content-Disposition",
                "attachment; " + filenameField);

        fireOnResponseHeader(responseHeader);
        HttpUtil.sendHeader(receiver, responseHeader);

        extractMP3(receiver.getOutputStream());
        // サイズが分からないのでchunkedを使わない限りkeep-alive不可能
        return false;
    }

    // コンストラクタ
    public Flv2mp3(Cache cache)
    {
        this.cache = cache;
    }

    // ファイルを開いて処理を開始する
    public void extractMP3(OutputStream out)
    {
        DataOutputStream dout = new DataOutputStream(out);
        try {
            raf = new RandomAccessFile(cache.getCacheFile(), "r");
            fileLength = cache.getCacheFile().length();
            extractMP3impl(dout);
        } catch (FileNotFoundException e) {
        } catch (EOFException e) {
            Logger.warning("Unexpected EOF.");
        }catch (IOException e) {
            Logger.warning("Failed to extract mp3.");
        } finally {
            try {
                if (raf != null)
                    raf.close();
            } catch (IOException e) { }
        }
    }

    // ID3ヘッダを追加する
    private void addID3URL(String desc, String data, DataOutputStream id3)
    {
        String tag = "WXXX";
        try {
            byte[] uDesc = desc.getBytes("ISO-8859-1");
            byte[] uData = data.getBytes("ISO-8859-1");
            id3.write(tag.getBytes("ISO-8859-1"));
            id3.writeInt(uData.length + uDesc.length + 3);
            id3.writeShort(0);
            id3.write(0);       // encoding
            id3.write(uDesc);   // string
            id3.write(0);       // null-string
            id3.write(uData);   // string
            id3.write(0);       // null-string
        } catch (UnsupportedEncodingException e) {
        } catch (IOException e) {
        }
    }
    private void addID3Comment(String desc, String data, DataOutputStream id3)
    {
        String tag = "COMM";
        try {
            byte[] uDesc = desc.getBytes("UTF-16LE");
            byte[] uData = data.getBytes("UTF-16LE");
            id3.write(tag.getBytes("ISO-8859-1"));
            id3.writeInt(uData.length + uDesc.length + 12);
            id3.writeShort(0);
            id3.write(1);       // encoding
            id3.write("eng".getBytes("ISO-8859-1"));
            id3.write(0xFF);    // endian1
            id3.write(0xFE);    // endian2
            id3.write(uDesc);   // Unicode string
            id3.writeShort(0);  // Unicode null-string
            id3.write(0xFF);    // endian1
            id3.write(0xFE);    // endian2
            id3.write(uData);   // Unicode string
            id3.writeShort(0);  // Unicode null-string
        } catch (UnsupportedEncodingException e) {
        } catch (IOException e) {
        }
    }
    private void addID3Text(String tag, String data, DataOutputStream id3)
    {
        try {
            byte[] uData = data.getBytes("UTF-16LE");
            id3.write(tag.getBytes("ISO-8859-1"));
            id3.writeInt(uData.length + 5);
            id3.writeShort(0);
            id3.write(1);       // encoding
            id3.write(0xFF);    // endian1
            id3.write(0xFE);    // endian2
            id3.write(uData);   // Unicode string
            id3.writeShort(0);  // null-string
        } catch (UnsupportedEncodingException e) {
        } catch (IOException e) {
        }
    }

    // 音声部分を抜き出してストリームに書き込む
    private boolean extractMP3impl(DataOutputStream out) throws IOException
    {
        // signiture
        if (raf.readInt() != 0x464C5601)
            return false;
        // offset to 1st chunk
        raf.readByte();
        int dataOffset = raf.readInt();
        seek(dataOffset);

        // ID3タグを設定する
        byte[] id3sig = new byte[3];
        id3sig[0] = 0x49; id3sig[1] = 0x44; id3sig[2] = 0x33;
        ByteArrayOutputStream id3buf = new ByteArrayOutputStream();
        DataOutputStream id3 = new DataOutputStream(id3buf);
        addID3Comment("", cache.getId(), id3);
        addID3Text("TSSE", "NicoCache_nl", id3);
        addID3Text("TIT2", cache.getTitle(), id3);
        addID3URL("", "http://www.nicovideo.jp/watch/" + cache.getId(), id3);

        out.write(id3sig);  // "ID3"
        out.write(3);       // v2.3
        out.write(0);
        out.write(0);       // flag

        byte[] id3data = id3buf.toByteArray();
        int length = id3data.length;
        // エスケープ
        length = ((length & ~0x3FFF) << 2) | ((length & ~0x7F & 0x3FFF) << 1) | length & 0x7F;
        out.writeInt(length);
        out.write(id3data);

        // reads each chunk
        readUInt32();
        while (fileOffset < fileLength) {
            readChunk(out);
            if (writeFailed)
                break;
            readUInt32();
        }
        return true;
    }

    private void readChunk(DataOutputStream out) throws IOException
    {
        long type = readUInt8();
        long dataSize = readUInt24();
        @SuppressWarnings("unused")
        long timeStamp = readUInt24();
        timeStamp |= readUInt8() << 24;
        readUInt24();

        if (dataSize == 0)
            return;
        long media = readUInt8();
        dataSize -= 1;
        fileOffset += dataSize;

        // 音声ストリームで、mp3な時だけ
        if (type == 0x8 && (media >> 4) == 2)
        {
            int chunkSize = Math.toIntExact(dataSize);
            byte[] data = new byte[chunkSize];
            raf.readFully(data);
            try {
                out.write(data);
            } catch (IOException e) {
                writeFailed = true;
            }
        }
        else
            seek(fileOffset);
    }

    private void seek(long offset) throws IOException {
        raf.seek(offset);
        fileOffset = offset;
    }

    private int readUInt8() throws IOException {
        byte b = raf.readByte();
        fileOffset += 1;
        return b & 0xff;
    }

    private long readUInt24() throws IOException {
        int[] x = new int[3];
        x[0] = readUInt8();
        x[1] = readUInt8();
        x[2] = readUInt8();
        return (x[0] << 16) + (x[1] << 8) + (x[2] << 0);
    }

    private long readUInt32() throws IOException {
        fileOffset += 4;
        return (0xFFFFFFFF & raf.readInt());
    }
}
