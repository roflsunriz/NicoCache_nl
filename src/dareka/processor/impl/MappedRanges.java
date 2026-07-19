package dareka.processor.impl;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dareka.common.CloseUtil;
import dareka.common.Logger;

public class MappedRanges {

    private File mapfile;
    private boolean loaded = false;

    private LinkedList<Range> ranges;
    private long lastTail = 0;
    private long size = -1;

    public MappedRanges(File mapfile) {
        this.mapfile = mapfile;
        this.ranges = new LinkedList<>();
    }

    private static final Pattern RANGE_PATTERN =
            Pattern.compile("^(\\d+)-(\\d+)$");
    public synchronized void load() throws IOException {
        if (loaded || !mapfile.exists()) {
            return;
        }
        BufferedReader br = new BufferedReader(new FileReader(mapfile));
        try {
            size = Long.parseLong(br.readLine());
            while (true) {
                String line = br.readLine();
                if (line == null) break;

                Matcher m = RANGE_PATTERN.matcher(line);
                if (m.matches()) {
                    long head = Long.parseLong(m.group(1));
                    long tail = Long.parseLong(m.group(2));
                    map(head, tail);
                }
            }
        } catch (NumberFormatException ex) {
            Logger.debug(ex);
            return;
        } finally {
            CloseUtil.close(br);
        }
        loaded = true;
    }

    public synchronized void save() throws IOException {
        BufferedWriter bw = new BufferedWriter(new FileWriter(mapfile));
        try {
            bw.write("" + size + "\n");
            for (Range r : ranges) {
                bw.write("" + r.head + "-" + r.tail + "\n");
            }
        } finally {
            CloseUtil.close(bw);
        }
    }

    public synchronized boolean renameTo(File newfile) throws IOException {
        boolean result = mapfile.renameTo(newfile);
        if (result) {
            mapfile = newfile;
        }
        return result;
    }


    /**
     * [head, tail) を領域に加える
     * @param head 開始位置
     * @param tail  終了位置(の直後)
     */
    public synchronized void map(long head, long tail) {
        if (head < 0 || head >= tail) {
            // ignore
            return;
        }
        Iterator<Range> iterator = ranges.iterator();
        while (iterator.hasNext()) {
            Range r = iterator.next();
            if (r.head <= tail && head <= r.tail) {
                if (head <= r.tail) {
                    head = Math.min(head, r.head);
                }
                if (tail >= r.head) {
                    tail = Math.max(tail, r.tail);
                }
                iterator.remove();
            }
        }
        ranges.add(new Range(head, tail));
        lastTail = Math.max(lastTail, tail);
    }

    /**
     * 最後のブロックの終了位置を返す．
     * @return 最後のブロックの終了位置
     */
    public synchronized long getLastTail() {
        return lastTail;
    }

    public synchronized long findHead(long pos) {
        for (Range r : ranges) {
            if (r.head <= pos && pos < r.tail) {
                return r.head;
            }
        }
        return -1;
    }

    public synchronized long findTail(long pos) {
        for (Range r : ranges) {
            if (r.head <= pos && pos < r.tail) {
                return r.tail;
            }
        }
        return -1;
    }

    public synchronized boolean isMapped(long head, long tail) {
        if (head == -1) {
            head = 0;
        }
        if (tail == -1) {
            if (size == -1) {
                return false;
            }
            tail = size;
        }
        for (Range r : ranges) {
            if (r.head <= head && tail <= r.tail) {
                return true;
            }
        }
        return false;
    }

    public boolean isComplete() {
        return isMapped(-1, -1);
    }

    public synchronized long getMappedSize() {
        long x = 0;
        for (Range r : ranges) {
            x += r.tail - r.head;
        }
        return x;
    }

    public synchronized void setSize(long size) {
        this.size = size;
    }

    public synchronized long getSize() {
        return size;
    }

    public synchronized void clear() {
        size = -1;
        ranges.clear();
    }

    private class Range {

        public long head, tail;

        public Range(long head, long tail) {
            this.head = head;
            this.tail = tail;
        }
    }
}
