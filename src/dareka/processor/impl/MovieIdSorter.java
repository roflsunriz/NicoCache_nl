package dareka.processor.impl;

import java.io.File;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;

/**
 * 動画IDをソートし、String配列で返す。
 * 先頭の識別子2文字は無視し、番号のみでソート
 * sortDirection : false = ASC, true = DESC
 */
public class MovieIdSorter implements Comparator<Integer> {
	// static methods
	/**
	 * Map<String, File> から ソートした String[] を返す
	 * @param map			ソートするマップ
	 * @param sortDirection	false = ASC, true = DESC
	 * @return				ソートされたIDの配列
	 */
	public static String[] sort(Map<String, File> map, boolean sortDirection) {
		TreeMap<Integer, String> idTree
					= new TreeMap<Integer, String>(new MovieIdSorter(sortDirection));
		for (String k: map.keySet()) {
			try {
				idTree.put(Integer.valueOf(k.substring(2).replace("low", "")), k);
			} catch (NumberFormatException e) {
				// ignore this item
			}
		}
		return idTree.values().toArray(new String[0]);
		//return sort(map.keySet().toArray(list), sortDirection);
	}
	
	/**
	 * String[] から ソートした String[] を返す
	 * @param idList		ソートするIDの配列
	 * @param sortDirection	false = ASC, true = DESC
	 * @return				ソートされたIDの配列
	 */
	public static String[] sort(String[] idList, boolean sortDirection) {
		TreeMap<Integer, String> idTree
					= new TreeMap<Integer, String>(new MovieIdSorter(sortDirection));
		for (String k: idList) {
			try {
				idTree.put(Integer.valueOf(k.substring(2).replace("low", "")), k);
			} catch (NumberFormatException e) {
				// ignore this item
			}
		}
		return idTree.values().toArray(new String[0]);
	}

	// instance method
	private boolean sortDirection = false;
	private MovieIdSorter(boolean sortDirection) {
		this.sortDirection = sortDirection;
	}
	
	// Comparator<Integer> interface
	public int compare(Integer a, Integer b) {
		return (a == b) ? 0 : ((a < b)^sortDirection ? -1 : 1);
	}
}
