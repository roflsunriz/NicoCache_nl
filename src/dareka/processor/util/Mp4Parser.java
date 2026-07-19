package dareka.processor.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.Date;

import dareka.common.Logger;

public class Mp4Parser {

    private boolean invalid = false;
    private long ftyp = 0;
    private Date videoCreationTime = null;
    private long durationInSeconds = 0;
    private String videoHandler = null;
    private int mdatOffset = 0;

    /**
     * MP4ファイルを渡すコンストラクタ．
     * @param file
     */
    public Mp4Parser(File file) {
        this(file, 0x20000);
    }

    /**
     * MP4ファイルを渡すコンストラクタ．
     * @param file
     * @param length 先頭から利用する長さ
     */
    public Mp4Parser(File file, int length) {
        try (FileInputStream fis = new FileInputStream(file)) {
            FileChannel channel = fis.getChannel();
            ByteBuffer buf = ByteBuffer.allocate(length);
            buf.clear();
            int read = channel.read(buf);
            if (read == -1) {
                // file size == 0
                buf.limit(0);
                invalid = true;
            } else {
                buf.limit(read);
                parse(buf);
            }
        } catch (IOException e) {
        }
    }

    /**
     * MP4ファイルの先頭をByteBufferで渡すコンストラクタ．
     * 0x10000程度あると望ましいが実際のところ
     * 再エンコード判定ならば必要な情報は512バイト以内に収まっている．
     * 読み込んだ長さをsrc.limitに設定して呼び出すこと．
     * @param src
     */
    public Mp4Parser(ByteBuffer src) {
        parse(src);
    }

    public boolean isInvalid() {
        return invalid;
    }

    public Date getVideoCreationTime() {
        return videoCreationTime;
    }

    public long getDurationInSeconds() {
        return durationInSeconds;
    }

    public String getVideoHandler() {
        return videoHandler;
    }

    public int getMdatOffset() {
        return mdatOffset;
    }

    // 参考: http://xhelmboyx.tripod.com/formats/mp4-layout.txt
    private boolean parse(ByteBuffer src) {
        ByteBuffer buf = src.order(ByteOrder.BIG_ENDIAN);
        buf.position(0);
        int end = src.limit();
        int pos = buf.position();
        try {
            while (pos < end) {
                pos += parse(buf, pos);
            }
        } catch (BufferUnderflowException e) {
        } catch (InvalidMp4Exception e) {
            invalid = true;
            return false;
        } catch (Exception e) {
        }
        if (ftyp != 4) {
            invalid = true;
        }
        Logger.debug("Mp4Parser ctime: " + videoCreationTime);
        Logger.debug("Mp4Parser handler " + videoHandler);
        return true;
    }

    @SuppressWarnings("serial")
    private static class InvalidMp4Exception extends Exception { }

    private final static int EPOCH_DELTA_UNIX_MAC = 0x7c25b080;
    private final static int MAGIC_FTYP = 0x66747970; // ftyp
    private final static int MEDIA_VIDE = 0x76696465; // vide
    private final static int MAGIC_MOOV = 0x6d6f6f76; // moov
    private final static int MAGIC_TRAK = 0x7472616b; // trak
    private final static int MAGIC_MDIA = 0x6d646961; // mdia
    private final static int MAGIC_MDHD = 0x6d646864; // mdhd
    private final static int MAGIC_HDLR = 0x68646c72; // hdlr

    private int tmpMedia = 0;
    private Date tmpCreationTime = null;
    private long tmpDurationInSeconds = 0;
    private String tmpHandler = null;

    private int parse(ByteBuffer buf, int start)
            throws InvalidMp4Exception, BufferUnderflowException {
        buf.position(start);
        int len = buf.getInt();
        if (len < 8) throw new InvalidMp4Exception();
        int end = start + len;
        int magic = buf.getInt();
        switch (magic) {
            case MAGIC_FTYP:
            {
                ftyp = buf.position() - 4;
                break;
            }
            case MAGIC_MOOV:
            case MAGIC_TRAK:
            case MAGIC_MDIA:
            {
                if (magic == MAGIC_MOOV) {
                    mdatOffset = start + len;
                }
                int pos = buf.position();
                while (pos < end && pos < buf.limit()) {
                    pos += parse(buf, pos);
                }
                if (magic == MAGIC_MDIA) {
                    if (tmpMedia == MEDIA_VIDE) {
                        videoCreationTime = tmpCreationTime;
                        durationInSeconds = tmpDurationInSeconds;
                        videoHandler = tmpHandler;
                    }
                }
                break;
            }
            case MAGIC_MDHD:
            {
                int flag = buf.getInt();
                int version = flag >> 24;
                long ctime = 0;
                long mtime = 0;
                int scale = 0;
                long duration = 0;
                boolean error = false;
                switch (version) {
                case 0:
                    ctime = uint2long(buf.getInt());
                    mtime = uint2long(buf.getInt());
                    scale = buf.getInt();
                    duration = uint2long(buf.getInt());
                    break;
                case 1:
                    ctime = buf.getLong();
                    mtime = buf.getLong();
                    scale = buf.getInt();
                    duration = buf.getLong();
                    break;
                default:
                    error = true;
                }
                if (!error) {
                    tmpCreationTime = new Date((ctime - EPOCH_DELTA_UNIX_MAC) * 1000);
                    tmpDurationInSeconds = duration / scale;
                }
                break;
            }
            case MAGIC_HDLR:
            {
                int flag = buf.getInt();
                buf.getInt(); // QUICKTIME type
                tmpMedia = buf.getInt();
                buf.getInt(); // QUICKTIME manufacturer reserved
                buf.getInt(); // QUICKTIME component reserved flags
                buf.getInt(); // QUICKTIME component reserved flags mask
                if (end - buf.position() - 1 < 0 || end - buf.position() - 1 > 512) {
                    throw new InvalidMp4Exception();
                }
                byte[] handler = new byte[end - buf.position() - 1];
                buf.get(handler);
                try {
                    tmpHandler = new String(handler, "UTF-8");
                } catch (UnsupportedEncodingException e) {
                }
            }
        }
        return len;
    }

    private long uint2long(int t) {
        if (t < 0)
            return t + 0x100000000L;
        else
            return t;
    }
}
