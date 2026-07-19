package dareka.extensions;

public interface Extension2 {
	
	public abstract void registerExtensions(ExtensionManager mgr);
	
	
	/**
	 * @return バージョン文字列。長いのは自重するべき。
	 */
	public abstract String getVersionString();
}
