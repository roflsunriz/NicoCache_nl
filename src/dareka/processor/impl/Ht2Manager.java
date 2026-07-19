package dareka.processor.impl;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;

import dareka.common.Logger;

public class Ht2Manager {

    private final HashMap<String, Ht2Entry> container;

    public Ht2Manager() {
        this.container = new HashMap<>(32);
    }

    public void update(Ht2Entry entry) {
        boolean needGc = false;
        synchronized (container) {
            if (container.size() > 30) {
                needGc = true;
            }
        }
        if (needGc) {
            gc();
        }
        synchronized (container) {
            container.put(entry.getKey(), entry);
        }
    }

    public Ht2Entry get(String key) {
        synchronized (container) {
            return container.get(key);
        }
    }

    public void gc() {
        Logger.debug("Ht2Manager.gc: start");
        long now = System.currentTimeMillis();
        synchronized (container) {
            Collection<Ht2Entry> containerEntries = container.values();
            Iterator<Ht2Entry> iterator = containerEntries.iterator();
            while (iterator.hasNext()) {
                Ht2Entry e = iterator.next();
                if (e.getExpiredOn() < now) {
                    Logger.debug("Ht2Manager.gc: removing " + e.getKey());
                    iterator.remove();
                }
            }
        }
        Logger.debug("Ht2Manager.gc: end");
    }
}
