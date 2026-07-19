package extensions;

import javax.swing.JTextArea;

import dareka.NLMain;
import dareka.extensions.Extension2;
import dareka.extensions.ExtensionManager;

/**
 * タブ追加のサンプル
 */
public class addTabSample implements Extension2 {
	private static JTextArea textArea;
	
	// Extension2 interface
	public void registerExtensions(ExtensionManager mgr) {
		if (textArea == null && NLMain.isLaunchGUI()) {
			textArea = new JTextArea();
			textArea.append("Extensionからタブを追加してみた");
			NLMain.addTab("addTabSample", null, textArea, "タブ追加のサンプル");
		}
	}
	
	public String getVersionString() {
		return "addTabSample";
	}
}
