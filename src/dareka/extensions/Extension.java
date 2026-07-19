package dareka.extensions;

public interface Extension {
	public enum Type 
	{ Extension1, Processor1, Rewriter1, RequestFilter1 }
	
	/**
	 * インターフェイスを探す。
	 * 
	 * @param type インターフェイスのID
	 * @return サポートしている場合はクラスのインスタンス。サポートしていなければnull。
	 * スレッドセーフでなければ、自分をnewして返す。
	 */
	public abstract Object queryInterface(Type type);
	
	
	/**
	 * @return バージョン文字列。長いのは自重するべき。
	 */
	public abstract String getVersionString();
}
