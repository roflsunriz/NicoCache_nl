package dareka.common;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DiskFreeSpace {
	private DiskFreeSpace() {
        // avoid instantiation.
	}
	
	public static long get(String dirName) {
		try {
			// Java 6ならメソッドを利用して取得
//			return new File(dirName).getFreeSpace();
			return new File(dirName).getUsableSpace();
		} catch (Error e) {
			// 利用できないときは通常のコマンドによる取得にする
		}

		try {
			String os = System.getProperty("os.name");
			String command;
			if (os.startsWith("Windows NT") || os.equals("Windows 2000")
					|| os.equals("Windows XP") || os.equals("Windows Vista")) {
				command = "cmd.exe /c dir \"" + dirName + "\"";
			} else if (os.startsWith("Windows")) {
				command = "command.com /c dir \"" + dirName + "\"";
			} else {
				command = "df -k " + dirName;
			}

			Process dirProcess = Runtime.getRuntime().exec(command);
			if (dirProcess == null) {
				return Long.MAX_VALUE;
			}

			BufferedReader inputReader = new BufferedReader(new InputStreamReader(
					dirProcess.getInputStream()));
			String line, freeSpace = null;

			while ((line = inputReader.readLine()) != null) {
				freeSpace = line;
			}
			inputReader.close();
			dirProcess.getErrorStream().close();
			dirProcess.getOutputStream().close();
			dirProcess.destroy();

			if (freeSpace == null) {
				return Long.MAX_VALUE;
			}

			freeSpace = freeSpace.replace(",", "");
			Matcher m = Pattern.compile("\\d{2,}+(?! *%)").matcher(freeSpace);

			long freeSize = Long.MAX_VALUE;
			while (m.find()) {
				freeSize = Long.parseLong(m.group(0));
			}

			if (command.startsWith("df")) {
				if (freeSize < 100) {
					return Long.MAX_VALUE;
				}
				return freeSize * 1024;
			}

			return freeSize;
		} catch (Exception exception) {
			return Long.MAX_VALUE;
		}
	}
}
