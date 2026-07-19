package dareka.processor.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;

public class FlvParser {

    private boolean invalid = false;
    private double duration = 0;

    /**
     * FLVファイルを渡すコンストラクタ．
     * @param file
     */
    public FlvParser(File file) {
        this(file, 0x2000);
    }

    /**
     * FLVファイルを渡すコンストラクタ．
     * @param file
     * @param length 先頭から利用する長さ
     */
    public FlvParser(File file, int length) {
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
     * FLVファイルの先頭をByteBufferで渡すコンストラクタ．
     * @param src
     */
    public FlvParser(ByteBuffer src) {
        parse(src);
    }

    public boolean isInvalid() {
        return invalid;
    }

    public long getDurationInSeconds() {
        return (long)duration;
    }


    private boolean parse(ByteBuffer src) {
        ByteBuffer buf = src.order(ByteOrder.BIG_ENDIAN);
        buf.position(0);
        int end = src.limit();
        int pos = buf.position();

        try {
            pos += parseHeader(buf);
            pos += 4;
            while (pos < end) {
                pos += parseTag(buf, pos);
                pos += 4;
            }
        } catch (BufferUnderflowException e) {
        } catch (InvalidFlvException e) {
            invalid = true;
            return false;
        } catch (Exception e) {
        }
        return true;
    }

    @SuppressWarnings("serial")
    private static class InvalidFlvException extends Exception { }

    private final static int MAGIC_FLV1 = 0x464c5601; // FLV\x01
    private final static byte SCRIPTDATAVALUE_TYPE_NUMBER = 0;
    private final static byte SCRIPTDATAVALUE_TYPE_BOOLEAN = 1;
    private final static byte SCRIPTDATAVALUE_TYPE_STRING = 2;
    private final static byte SCRIPTDATAVALUE_TYPE_OBJECT = 3;
    private final static byte SCRIPTDATAVALUE_TYPE_NULL = 5;
    private final static byte SCRIPTDATAVALUE_TYPE_UNDEFINED = 6;
    private final static byte SCRIPTDATAVALUE_TYPE_REFERENCE = 7;
    private final static byte SCRIPTDATAVALUE_TYPE_ECMA_ARRAY = 8;
    private final static byte SCRIPTDATAVALUE_TYPE_OBJECT_END_MARKER = 9;
    private final static byte SCRIPTDATAVALUE_TYPE_STRICT_ARRAY = 10;
    private final static byte SCRIPTDATAVALUE_TYPE_DATE = 11;
    private final static byte SCRIPTDATAVALUE_TYPE_LONG_STRING = 12;


    private int parseHeader(ByteBuffer buf)
            throws InvalidFlvException, BufferUnderflowException {
        int sigver = buf.getInt();
        if (sigver != MAGIC_FLV1)
            throw new InvalidFlvException();
        buf.get();
        int offset = buf.getInt();
        return offset;
    }

    private int parseTag(ByteBuffer buf, int start)
            throws InvalidFlvException, BufferUnderflowException {
        buf.position(start);
        int len = buf.getInt();
        byte type = (byte)((len >> 24) & 0xff);
        len = len & 0xffffff;

        if (len < 11) throw new InvalidFlvException();
        int end = start + len;

        buf.getInt(); // Timestamp + TimestampExtended
        buf.get(); buf.get(); buf.get(); // StreamID

        if (type == 18) { // SCRIPTDATA
            byte nameValueType = buf.get();
            if (nameValueType != SCRIPTDATAVALUE_TYPE_STRING)
                throw new InvalidFlvException();
            String name = readString(buf);
            if ("onMetaData".equals(name)) {
                byte valueValueType = buf.get();
                if (valueValueType != SCRIPTDATAVALUE_TYPE_ECMA_ARRAY)
                    throw new InvalidFlvException();
                parseOnMetaDataValue(buf);
            }
        }
        return len;
    }

    private void parseOnMetaDataValue(ByteBuffer buf)
            throws InvalidFlvException, BufferUnderflowException {
        buf.getInt(); // ECMAArrayLength but *Approximate*

        while (true) {
            String name = readString(buf);
            byte valueType = buf.get();
            if (valueType == SCRIPTDATAVALUE_TYPE_OBJECT_END_MARKER)
                break;
            if ("duration".equals(name)) {
                duration = readNumber(buf);
            } else {
                skipValue(buf, valueType);
            }
        }
    }

    private double readNumber(ByteBuffer buf)
            throws InvalidFlvException, BufferUnderflowException {
        return buf.getDouble();
    }

    private String readString(ByteBuffer buf)
            throws InvalidFlvException, BufferUnderflowException {
        int len = ushort2int(buf.getShort());
        byte[] bcontent = new byte[len];
        buf.get(bcontent);
        try {
            return new String(bcontent, "ISO-8859-1");
        } catch (UnsupportedEncodingException e) {
            return null;
        }
    }

    private boolean skipValue(ByteBuffer buf)
            throws InvalidFlvException, BufferUnderflowException {
        byte type = buf.get();
        return skipValue(buf, type);
    }

    private boolean skipValue(ByteBuffer buf, byte type)
            throws InvalidFlvException, BufferUnderflowException {
        switch (type) {
            case SCRIPTDATAVALUE_TYPE_NUMBER:
                buf.getDouble();
                break;
            case SCRIPTDATAVALUE_TYPE_BOOLEAN:
                buf.get();
                break;
            case SCRIPTDATAVALUE_TYPE_STRING:
            {
                int len = ushort2int(buf.getShort());
                buf.position(buf.position() + len);
                break;
            }
            case SCRIPTDATAVALUE_TYPE_OBJECT:
                skipObject(buf);
                break;
            case SCRIPTDATAVALUE_TYPE_NULL:
            case SCRIPTDATAVALUE_TYPE_UNDEFINED:
                break;
            case SCRIPTDATAVALUE_TYPE_REFERENCE:
                buf.getShort();
                break;
            case SCRIPTDATAVALUE_TYPE_ECMA_ARRAY:
                skipArray(buf);
                break;
            case SCRIPTDATAVALUE_TYPE_OBJECT_END_MARKER:
                return true;
            case SCRIPTDATAVALUE_TYPE_STRICT_ARRAY:
            {
                long length = uint2long(buf.getInt());
                for (long i = 0; i < length; i++) {
                    skipValue(buf);
                }
                break;
            }
            case SCRIPTDATAVALUE_TYPE_DATE:
            {
                buf.getDouble();
                buf.getShort();
                break;
            }
            case SCRIPTDATAVALUE_TYPE_LONG_STRING:
            {
                long length = uint2long(buf.getInt());
                int curPos = buf.position();
                int newPos = curPos + (int)length;
                if (newPos < curPos) throw new InvalidFlvException();
                buf.position(newPos);
                break;
            }
            default:
                throw new InvalidFlvException();
        }
        return false;
    }

    private void skipObject(ByteBuffer buf)
            throws InvalidFlvException, BufferUnderflowException {
        while (true) {
            String name = readString(buf);
            if (skipValue(buf)) break;
        }
    }

    private void skipArray(ByteBuffer buf)
            throws InvalidFlvException, BufferUnderflowException {
        buf.getInt();
        while (true) {
            String name = readString(buf);
            if (skipValue(buf)) break;
        }
    }


    private int ushort2int(short t) {
        if (t < 0)
            return (int)t + 0x10000;
        else
            return t;
    }

    private long uint2long(int t) {
        if (t < 0)
            return t + 0x100000000L;
        else
            return t;
    }
}
