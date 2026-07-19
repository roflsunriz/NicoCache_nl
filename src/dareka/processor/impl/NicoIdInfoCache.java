package dareka.processor.impl;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dareka.common.Logger;

/**
 * Remember type(sm/ax/ca, etc.), title of movies. The size is shrunk
 * automatically when the rest of the free memory is low.
 *
 * <p>
 * This class has responsibility similar to WeakHashMap. However WeakHashMap is
 * not appropriate for cache because it release all entries when the GC works.
 *
 */
public class NicoIdInfoCache {
    private static final NicoIdInfoCache SINGLETON_INSTANCE =
            new NicoIdInfoCache();
    private static final int MAX_RECENT = 50;
    private static final int MAX_RECENT_STICKY = 20;

    private ReferenceQueue<Entry> queue = new ReferenceQueue<>();
    private ConcurrentHashMap<String, EntryReference> id2title =
            new ConcurrentHashMap<>();
    // final is necessary to prevent 2 threads from entering
    // synchronized block when the reference is changed on feature.
    private final LinkedHashSet<Entry> recentEntry = new LinkedHashSet<>();
    private final LinkedHashSet<Entry> recentStickyEntry = new LinkedHashSet<>();

    public static NicoIdInfoCache getInstance() {
        return SINGLETON_INSTANCE;
    }

    /**
     * Get information of id.
     *
     * @param id the number of the movie. (sm/ax/ca is not included)
     * @return information
     */
    public Entry get(String id) {
        expunge();

        EntryReference entryRef = id2title.get(id);
        if (entryRef == null) {
            return null;
        }

        Entry entry = entryRef.get();
        if (entry == null) {
            return null;
        }

        return entry;
    }

    /**
     * Put information of id.
     *
     * @param type sm/ax/ca, etd.
     * @param id the number of the movie. (sm/ax/ca is not included)
     * @param title
     */
    public void put(String type, String id, String title) {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        if (title == null) {
            throw new IllegalArgumentException("title must not be null");
        }

        Entry entry = get(id);
        if (entry != null) {
            entry.type = type;
            entry.title = title;
        } else {
            entry = new Entry(type, id, title);
        }
        constructReference(id, entry);
    }

    /**
     * Put incomplete information of id without the title.
     * If there already is complete information, this method does nothing.
     *
     * @param type sm/ax/ca, etd.
     * @param id the number of the movie. (sm/ax/ca is not included)
     */
    public void putOnlyTypeAndId(String type, String id) {
        Entry existingEntry = get(id);
        if (existingEntry != null && existingEntry.isTitleValid()) {
            return;
        }

        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }

        Entry entry = new Entry(type, id);
        constructReference(id, entry);
    }

    protected void sticky(String id) {
        Entry entry = get(id);
        if (entry == null) {
            return;
        }

        synchronized (recentStickyEntry) {
            if (!recentStickyEntry.remove(entry)) {
                if (recentStickyEntry.size() >= MAX_RECENT_STICKY) {
                    // remove the oldest entry
                    Iterator<Entry> it = recentStickyEntry.iterator();
                    it.next();
                    it.remove();
                }
            }

            recentStickyEntry.add(entry);
        }
    }

    private void constructReference(String id, Entry entry) {
        expunge();

        EntryReference entryRef = new EntryReference(entry, queue);
        id2title.put(id, entryRef);

        // keep strong reference for recent entries to protect them from GC.
        synchronized (recentEntry) {
            if (!recentEntry.remove(entry)) {
                if (recentEntry.size() >= MAX_RECENT) {
                    // remove the oldest entry
                    Iterator<Entry> it = recentEntry.iterator();
                    it.next();
                    it.remove();
                }
            }

            recentEntry.add(entry);
        }
    }

    public int size() {
        expunge();

        return id2title.size();
    }

    private void expunge() {
        EntryReference ref;
        while ((ref = (EntryReference) queue.poll()) != null) {
            String id = ref.getId();
            id2title.remove(id);
            Logger.debugWithThread("title cache expunged: " + id);
        }
    }

    public static class Entry {
        // This String object represents that title is not found.
        // Constants may be shared with other String objects,
        // so we need a new String object.
        // Additionally, this is a kind of the null object pattern.
        private final static String INVALID_TITLE = "nicocache-unknown-title";

        private String type;
        private String id;
        private String title;

        // - WatchVars.javaが最高ビットレート以外を登録する.
        // - domandもこれを利用する.
        // - domandなら"video-h264-480p"みたいな要素のリスト.
        // - "video-h264-360p"と"video-h264-360p-lowest"などsuffix違いが含まれることに
        //   注意.
        // - DMCなら"archive_h264_1080p"みたいな要素のリスト.
        protected final ConcurrentHashMap<String, Boolean> dmcVideoEconomy =
                new ConcurrentHashMap<>();
        // - domandなら"audio-aac-192kbps"みたいな要素のリスト.
        // - DMCなら"archive_aac_192kbps" みたいな要素のリスト.
        protected final ConcurrentHashMap<String, Boolean> dmcAudioEconomy =
                new ConcurrentHashMap<>();

        Entry(String type, String id, String title) {
            this.type = type;
            this.id = id;
            this.title = title;
        }

        Entry(String type, String id) {
            this.type = type;
            this.id = id;
            this.title = INVALID_TITLE;
        }

        /* (非 Javadoc)
         * @see java.lang.Object#equals(java.lang.Object)
         */
        @Override
        public boolean equals(Object obj) {
            if (obj instanceof Entry) {
                Entry other = (Entry) obj;
                return type.equals(other.type) && id.equals(other.id)
                        && title.equals(other.title);
            } else {
                return false;
            }
        }

        /* (非 Javadoc)
         * @see java.lang.Object#hashCode()
         */
        @Override
        public int hashCode() {
            return type.hashCode() ^ id.hashCode() ^ title.hashCode();
        }

        public String getType() {
            return type;
        }

        public String getId() {
            return id;
        }

        public String getTitle() {
            return title;
        }

        public boolean isTitleValid() {
            // intended String comparison. this require identity, not equality.
            if (title == INVALID_TITLE) {
                return false;
            } else {
                return true;
            }
        }

        // 不明ならばnull, lowならばtrue, それ以外でfalse.
        public Boolean getDmcVideoEconomy(String video_src_id) {
            return dmcVideoEconomy.get(video_src_id);
        }
        // 不明ならばnull, lowならばtrue, それ以外でfalse.
        public Boolean getDmcAudioEconomy(String audio_src_id) {
            return dmcAudioEconomy.get(audio_src_id);
        }
        // - audio bitrateで推定する.
        // - 不明ならばnull, lowならばtrue, それ以外でfalse.
        public Boolean estimateDmcAudioEconomyFromKbps(int kbps) {
            // - 2023年までの区切り文字は"_"だが今さら実装はしない.
            // - endsWithしてからindexOfする理由はestimateDmcVideoEconomyFromVideoMode内
            //   のコメントを参照のこと.
            String part = "-" + kbps + "kbps";
            for (Map.Entry<String,Boolean> entry : dmcAudioEconomy.entrySet()) {
                if (entry.getKey().endsWith(part)) {
                    return entry.getValue();
                };
            };
            for (Map.Entry<String,Boolean> entry : dmcAudioEconomy.entrySet()) {
                if (0 <= entry.getKey().indexOf(part)) {
                    return entry.getValue();
                };
            };
            return null;
        };

        // - video mode(例:"1080p")で推定する.
        // - 不明ならばnull, lowならばtrue, それ以外でfalse.
        public Boolean estimateDmcVideoEconomyFromVideoMode(String videoMode) {
            // - 走査一覧には"-lowest"のようなsuffixが付くものと付かないものが両方所属
            //   する.
            // - またこの関数の目的は推定である.
            // - だから現実的に一意厳密であるendsWithで確認してからindexOfによる確認を
            //   する.
            String part = "-" + videoMode;
            for (Map.Entry<String,Boolean> entry : dmcVideoEconomy.entrySet()) {
                if (entry.getKey().endsWith(part)) {
                    return entry.getValue();
                };
            };
            for (Map.Entry<String,Boolean> entry : dmcVideoEconomy.entrySet()) {
                if (0 <= entry.getKey().indexOf(part)) {
                    return entry.getValue();
                };
            };
            return null;
        };
    }

    /**
     * Soft reference to the information. This make it possible for GC
     * collects the Entry object on low free memory.
     *
     */
    static class EntryReference extends SoftReference<Entry> {
        private String id;

        EntryReference(Entry entry, ReferenceQueue<Entry> q) {
            super(entry, q);
            this.id = entry.getId();
        }

        String getId() {
            return id;
        }

    }

}
