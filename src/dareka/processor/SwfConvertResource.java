package dareka.processor;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 廃止済み上流SWF変換取得処理のバイナリ互換shim。
 * 保存済みSWFはCacheDirProcessorからLocalFileResourceとして配信される。
 */
public class SwfConvertResource extends URLResource {
    public class TagInfo {
        public int tagNum;
        public int tagLen;
        public int dataLen;
    }

    protected static final int END = 0;
    protected static final int SHOWFRAME = 1;
    protected static final int DOACTION = 12;
    protected static final int FILEATTRIBUTES = 69;

    protected static final byte[] tagFileAttributesAS3 = {
        0x44, 0x11, 0x08, 0x00, 0x00, 0x00
    };
    protected static final byte[] tagDoActionSETUP = new byte[0];
    protected static final byte[] tagDoActionSTOP = { 0x02, 0x03, 0x07, 0x00 };

    protected String resource;
    protected short frameRate;
    protected short frameCount;
    protected short currentFrame;
    protected int expandLength;
    protected boolean noPadding;
    protected boolean noBrowserCache = true;
    protected byte[] swfHeader = new byte[8];
    protected String swfHeaderInfo;
    protected boolean isAS3;
    protected long outputLength;
    protected boolean swfCacheV3 = Boolean.getBoolean("swfCacheV3");
    protected static int resourceCount;
    protected OutputStream contentOutput;
    protected long contentPosition;

    public void setAS3(boolean isAS3) {
        this.isAS3 = isAS3;
    }

    public long getOutputLength() {
        return outputLength;
    }

    protected void debugOut(String message) {
    }

    protected void debugOutTag(String message) {
    }

    protected void writeContent(byte[] content, int offset, int length) throws IOException {
        if (contentOutput != null) {
            contentOutput.write(content, offset, length);
            contentPosition += length;
        }
    }

    protected void writeContent(byte[] content) throws IOException {
        writeContent(content, 0, content.length);
    }

    public SwfConvertResource(String resource) throws IOException {
        super(resource);
        this.resource = resource;
        resourceCount++;
    }

    public long convertToV3(File file) throws IOException {
        outputLength = file != null && file.isFile() ? file.length() : 0L;
        return outputLength;
    }

    @Override
    protected void doSetMandatoryResponseHeader(HttpResponseHeader responseHeader) {
        super.doSetMandatoryResponseHeader(responseHeader);
    }

    @Override
    protected void execSendingBodySequence(OutputStream output, InputStream input,
            long contentLength) throws IOException {
        super.execSendingBodySequence(output, input, contentLength);
    }

    protected boolean needsConvert() {
        return false;
    }

    protected void readHeader(InputStream input) throws IOException {
    }

    protected void appendHeader(InputStream input) throws IOException {
    }

    protected void execSendingSwfConvertSequense(OutputStream output, InputStream input,
            long contentLength) throws IOException {
        super.execSendingBodySequence(output, input, contentLength);
    }

    protected byte[] readTagInfo(TagInfo tag, InputStream input) throws IOException {
        return new byte[0];
    }

    protected byte readByte(InputStream input) throws IOException {
        int value = input.read();
        if (value < 0) {
            throw new IOException("unexpected end of SWF stream");
        }
        return (byte) value;
    }

    protected byte[] readBytes(InputStream input, int length) throws IOException {
        byte[] result = input.readNBytes(length);
        if (result.length != length) {
            throw new IOException("unexpected end of SWF stream");
        }
        return result;
    }

    protected boolean procTag(TagInfo tag) throws IOException {
        return false;
    }

    protected int getInt(byte[] value, int offset) {
        return (value[offset] & 0xff)
                | (value[offset + 1] & 0xff) << 8
                | (value[offset + 2] & 0xff) << 16
                | (value[offset + 3] & 0xff) << 24;
    }

    protected void setInt(byte[] value, int offset, int number) {
        value[offset] = (byte) number;
        value[offset + 1] = (byte) (number >>> 8);
        value[offset + 2] = (byte) (number >>> 16);
        value[offset + 3] = (byte) (number >>> 24);
    }

    protected short getShort(byte[] value, int offset) {
        return (short) ((value[offset] & 0xff) | (value[offset + 1] & 0xff) << 8);
    }

    protected int getBits(byte[] value, int bitOffset, int bitLength) {
        int result = 0;
        for (int i = 0; i < bitLength; i++) {
            int index = bitOffset + i;
            int bit = (value[index / 8] >>> (7 - index % 8)) & 1;
            result = (result << 1) | bit;
        }
        return result;
    }
}
