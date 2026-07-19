package dareka.common;

import java.io.IOException;
import java.io.OutputStream;

/**
 * 何もしない出力ストリーム。読み捨てたい場合に利用する。
 */
public class NullOutputStream extends OutputStream {

	@Override
	public void write(int b) throws IOException {
	}

	@Override
	public void write(byte[] b, int off, int len) throws IOException {
	}

}
