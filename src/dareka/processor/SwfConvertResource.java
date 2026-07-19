package dareka.processor;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

import dareka.common.CloseUtil;
import dareka.common.Logger;

//
// 旧プレイヤー用のSWF(AVM1)と新プレイヤー用のSWF(AVM2)を相互に変換して再生できるようにする
// ニコ割(Marquee)の変換は結構複雑なのでサブクラス化したExtensionで実装する予定…
//
public class SwfConvertResource extends URLResource {
	
	public class TagInfo {
		public int tagNum, tagLen, dataLen;
	}
	protected static final int END            =  0;
	protected static final int SHOWFRAME      =  1;
	protected static final int DOACTION       = 12;
	protected static final int FILEATTRIBUTES = 69;
	
	protected static final byte[] tagFileAttributesAS3 = { 0x44,0x11,0x08,0x00,0x00,0x00 };
	//
	//[00c]    53 DOACTION
	//            -=> 96 10 00 00 2a 00 07 01 00 00 00 00 53 79 73 74     ..*.......Syst
	//            -=> 65 6d 00 1c 96 0a 00 00 73 65 63 75 72 69 74 79     em....security
	//            -=> 00 4e 96 0d 00 00 61 6c 6c 6f 77 44 6f 6d 61 69     .N..allowDomai
	//            -=> 6e 00 52 17 00                                      n.R..
	//
	// Disassemble by flasm:
	//
	//    push '*', 1, 'System'
	//    getVariable
	//    push 'security'
	//    getMember
	//    push 'allowDomain'
	//    callMethod
	//    pop
	//
	protected static final byte[] tagDoActionSETUP = { 0x35,0x03,
		(byte)0x96,0x10,0x00,0x00,0x2a,0x00,0x07,0x01,0x00,0x00,0x00,0x00,0x53,0x79,0x73,0x74,
		0x65,0x6d,0x00,0x1c,(byte)0x96,0x0a,0x00,0x00,0x73,0x65,0x63,0x75,0x72,0x69,0x74,0x79,
		0x00,0x4e,(byte)0x96,0x0d,0x00,0x00,0x61,0x6c,0x6c,0x6f,0x77,0x44,0x6f,0x6d,0x61,0x69,
		0x6e,0x00,0x52,0x17,0x00 };
	protected static final byte[] tagDoActionSTOP = { 0x02,0x03,0x07,0x00 };
	
	protected String resource;
	protected short frameRate = 0, frameCount = 0, currentFrame = 0;
	protected int expandLength = 0;
	protected boolean noPadding = false, noBrowserCache = true;
	protected byte[] swfHeader = new byte[8];
	protected String swfHeaderInfo = null;
	
	// 最終的にAS3がtrueならAVM2、falseならAVM1に変換して返す必要がある
	protected boolean isAS3 = false;
	public void setAS3(boolean isAS3) { this.isAS3 = isAS3; }
	
	protected long outputLength = 0L;
	public long getOutputLength() { return this.outputLength; }
	
	protected boolean swfCacheV3 = Boolean.getBoolean("swfCacheV3");
	private boolean swfDebug = Boolean.getBoolean("swfDebug");
	protected static int resourceCount = 0;
	private int debugNum;
	
	protected void debugOut(String mes) {
		if(swfDebug) Logger.info("SWF[" + debugNum + "] " + mes);
	}
	protected void debugOutTag(String mes) {
		debugOut(mes + ", pos = " + contentPosition);
	}
	
	protected OutputStream contentOutput = null;
	protected long contentPosition = 0L;
	
	protected void writeContent(byte[] content, int off, int len) throws IOException {
		if (contentOutput != null) {
			contentOutput.write(content, off, len);
			contentPosition += len;
		}
	}
	
	protected void writeContent(byte[] content) throws IOException {
		writeContent(content, 0, content.length);
	}
	
	public SwfConvertResource(String resource) throws IOException {
		super(resource);
		this.resource = resource;
		
		// AVM2->AVM1変換で増える可能性のあるサイズを設定する(念のため倍プッシュ)
		this.expandLength = (tagDoActionSETUP.length + tagDoActionSTOP.length) * 2;
		
		synchronized (this.getClass()) { this.debugNum = resourceCount++; }
		debugOut("START " + resource);
	}
	
	public long convertToV3(File fileV3) throws IOException {
		setAS3(true);
		swfCacheV3 = false;
		noPadding = true;
		FileOutputStream out = null;
		try {
			out = new FileOutputStream(fileV3);
			transferTo(null, out, null, null);
		} finally {
			CloseUtil.close(out);
		}
		return outputLength;
	}
	
	@Override
	protected void doSetMandatoryResponseHeader(
			HttpResponseHeader responseHeader) {
		super.doSetMandatoryResponseHeader(responseHeader);
		long contentLength = responseHeader.getContentLength();
		if (contentLength > 0 && expandLength > 0 && needsConvert()) {
			// サイズ増加分をcontentLengthに追加して返す
			contentLength += expandLength;
			responseHeader.setContentLength(contentLength);
		}
		debugOut("contentLength = " + contentLength + " in responseHeader.");
		
		// 新旧プレイヤー切り換え時にキャッシュに残っていると不具合が出るので
		if (noBrowserCache) {
			responseHeader.addCacheControlHeaders(0);
			responseHeader.removeHopByHopHeaders();
		}
	}
	
	@Override
	protected void execSendingBodySequence(
			OutputStream out, InputStream in, long contentLength) throws IOException {
		debugOut("contentLength = " + contentLength + ", expandLength = " + expandLength);
		debugOut("isAS3 = " + isAS3 + ", noBrowserCache = " + noBrowserCache);
		if (needsConvert()) {
			in = new BufferedInputStream(in);
			readHeader(in);
			if (swfHeader[0] == 'C') {
				in = new InflaterInputStream(in);
			}
			try {
				// 出力側もバッファリングしてある程度まとめて転送してみる
				out = new BufferedOutputStream(out, BUF_SIZE);
				execSendingSwfConvertSequense(out, in, contentLength);
			} catch (IOException e) {
				CloseUtil.close(out);
				throw e;
			}
		} else {
			debugOut("no convert needed.");
			super.execSendingBodySequence(out, in, contentLength);
		}
		debugOut("END");
		synchronized (this.getClass()) { resourceCount--; }
	}
	
	protected boolean needsConvert() {
		if (Boolean.getBoolean("swfConvertAll"))
			return true;	// 全て変換
		if (!swfCacheV3 && isAS3)
			return true;	// 旧SWFキャッシュ→新プレイヤーで視聴
		if (swfCacheV3 && !isAS3)
			return true;	// 新SWFキャッシュ→旧プレイヤーで視聴
		return false;
	}
	
	// swfHeaderの最初の非圧縮8バイト分を読む
	protected void readHeader(InputStream in) throws IOException {
		for (int i = 0; i < swfHeader.length; i++) {
			swfHeader[i] = readByte(in);
		}
		if ((swfHeader[0] != 'F' && swfHeader[0] != 'C') ||
				swfHeader[1] != 'W' || swfHeader[2] != 'S') {
			throw new IOException(resource + " is not SWF.");
		}
	}
	
	// swfHeaderの残りを読んで末尾に追加
	protected void appendHeader(InputStream in) throws IOException {
		byte firstByte = readByte(in);
		int Nbits = firstByte >> 3; // 5bits
		int rectEnd = swfHeader.length + ((5 + Nbits * 4) + 7) / 8;
		byte[] swfHeaderNew = Arrays.copyOf(swfHeader, rectEnd + 4);
		
		swfHeaderNew[swfHeader.length] = firstByte;
		for (int i = swfHeader.length + 1; i < swfHeaderNew.length; i++) {
			swfHeaderNew[i] = readByte(in);
		}
		swfHeader = swfHeaderNew;
		
		int Xmin = getBits(swfHeader, 64 + 5 + Nbits * 0, Nbits);
		int Xmax = getBits(swfHeader, 64 + 5 + Nbits * 1, Nbits);
		int Ymin = getBits(swfHeader, 64 + 5 + Nbits * 2, Nbits);
		int Ymax = getBits(swfHeader, 64 + 5 + Nbits * 3, Nbits);
		frameRate  = getShort(swfHeader, rectEnd);
		frameCount = getShort(swfHeader, rectEnd + 2);
		
		// signature,version,length,width,height,fps,count
		swfHeaderInfo = String.format("%c%c%c,%d,%d,%d,%d,%d.%d,%d",
				swfHeader[2], swfHeader[1], swfHeader[0], swfHeader[3],
				getInt(swfHeader, 4), (Xmax - Xmin) / 20, (Ymax - Ymin) / 20,
				(frameRate >> 8) & 0xff, frameRate & 0xff, frameCount);
		debugOut("swfHeaderInfo: ".concat(swfHeaderInfo));
	}
	
	protected void execSendingSwfConvertSequense(
			OutputStream out, InputStream in, long contentLength) throws IOException {
		// ヘッダ先頭は圧縮無し
		out.write(swfHeader);
		contentOutput = out;
		
		// それ以降は圧縮有り
		Deflater def = null;
		DeflaterOutputStream defout = null;
		if (swfHeader[0] == 'C') {
			def = new Deflater(Deflater.BEST_COMPRESSION);
			defout = new DeflaterOutputStream(out, def);
			contentOutput = defout;
		}
		int swfLength = getInt(swfHeader, 4);
		if (expandLength > 0) {
			contentLength += expandLength;
			swfLength += expandLength;
			setInt(swfHeader, 4, swfLength);
			debugOut("expanded: contentLength = " + contentLength +
					", swfLength = " + swfLength);
		}
		// ヘッダの残りを処理
		int uncompressed = swfHeader.length; // 非圧縮部分の長さ
		appendHeader(in);
		writeContent(swfHeader, uncompressed, swfHeader.length - uncompressed);
		if (isAS3) {
			debugOutTag("insert FILEATTRIBUTES");
			writeContent(tagFileAttributesAS3);
		}
		// それ以降はENDタグまでループ処理
		TagInfo ti = new TagInfo();
		while (true) {
			byte[] tag = readTagInfo(ti, in);
			boolean removed = procTag(ti);
			if (removed) {
				in.skip(ti.dataLen);
			} else {
				writeContent(tag);
				writeContent(readBytes(in, ti.dataLen));
			}
			if (ti.tagNum == END) break;
		}
		// サイズ拡張した分を0で埋める
		while (swfLength - uncompressed > contentPosition) {
			contentOutput.write(0); contentPosition++;
		}
		contentOutput = null;
		assert(swfLength == uncompressed + contentPosition);
		
		// 出力サイズが入力サイズより小さいことがあるので残りを0で埋める
		if(def != null) {
			defout.finish();
			outputLength = uncompressed + def.getBytesWritten();
		} else {
			outputLength = uncompressed + contentPosition;
		}
		long paddingLength = contentLength - outputLength;
		if (!noPadding && paddingLength > 0) {
			for (int i = 0; i < paddingLength; i++)
				out.write(0);
			outputLength += paddingLength;
		}
		out.flush();
		debugOut("outputLength = " + outputLength + ", paddingLength = " + paddingLength);
	}
	
	protected byte[] readTagInfo(TagInfo ti, InputStream in) throws IOException {
		byte[] result;
		byte[] buf = readBytes(in, 2);
		short tag = getShort(buf, 0);
		ti.tagNum = tag >> 6;
		ti.dataLen = tag & 0x3f;
		if (ti.dataLen == 0x3f) {
			result = new byte[6];
			result[0] = buf[0]; result[1] = buf[1];
			buf = readBytes(in, 4);
			ti.dataLen = getInt(buf, 0);
			ti.tagLen = ti.dataLen + 6;
			result[2] = buf[0]; result[3] = buf[1]; result[4] = buf[2]; result[5] = buf[3];
		} else {
			ti.tagLen = ti.dataLen + 2;
			result = buf;
		}
//Logger.info("tagNum="+ti.tagNum+", tagLen="+ti.tagLen+", dataLen="+ti.dataLen);
		return result;
	}
	
	protected byte readByte(InputStream in) throws IOException {
		int n = in.read();
		if(n == -1) throw new IOException(resource + " unexpected EOS.");
		return (byte)(n & 0xff);
	}
	
	protected byte[] readBytes(InputStream in, int len) throws IOException {
		byte[] buf = new byte[len];
		int readed = 0, n = 0;
		while ((n = in.read(buf, readed, len)) != -1) {
			readed += n; len -= n;
			if(readed == buf.length) break;
		}
		if (n == -1) throw new IOException(resource + " unexpected EOS.");
		return buf;
	}
	
	protected boolean procTag(TagInfo ti) throws IOException {
		if (isAS3) {
			// flvWrapper→新プレイヤーで視聴（AVM1->AVM2変換）
			switch (ti.tagNum) {
			case FILEATTRIBUTES:
				debugOutTag("remove FILEATTRIBUTES");
				return true;	// REMOVE (既にヘッダ直後に出力済み)
			case DOACTION:
				debugOutTag("remove DOACTION");
				return true;	// REMOVE
			}
		} else {
			// newPlayer→旧プレイヤーで視聴（AVM2->AVM1変換）
			switch (ti.tagNum) {
			case FILEATTRIBUTES:
				debugOutTag("remove FILEATTRIBUTES and insert DOACTION[SETUP]");
				writeContent(tagDoActionSETUP);
				return true;	// REMOVE
			case SHOWFRAME:
				currentFrame++;
				if (currentFrame == frameCount - 1) {
					debugOutTag("insert DOACTION[STOP] after " + currentFrame + " frames");
					writeContent(tagDoActionSTOP);
				}
				return false;	// NOT REMOVE
			}
		}
		return false;
	}
	
	protected int getInt(byte[] content, int pos) {
		// LITTLE ENDIAN
		return (content[pos] & 0xff) +
			((content[1+pos] & 0xff) <<  8) +
			((content[2+pos] & 0xff) << 16) +
			((content[3+pos] & 0xff) << 24);
	}
	
	protected void setInt(byte[] content, int pos, int data) {
		// LITTLE ENDIAN
		content[0+pos] = (byte)(data & 0xff);
		content[1+pos] = (byte)((data >>  8) & 0xff);
		content[2+pos] = (byte)((data >> 16) & 0xff);
		content[3+pos] = (byte)((data >> 24) & 0xff);
	}
	
	protected short getShort(byte[] content, int pos) {
		// LITTLE ENDIAN
		return (short)((content[pos] & 0xff) + ((content[1+pos] & 0xff) << 8));
	}
	
	protected int getBits(byte[] content, int start, int bits) {
		int n = 0, index, shift;
		for (int i = 0; i < bits; i++) {
			n <<= 1;
			index = (start + i) / 8;
			shift = 7 - ((start + i) % 8);
			if((content[index] & (1 << shift)) != 0) n += 1;
		}
		return n;
	}
	
}
