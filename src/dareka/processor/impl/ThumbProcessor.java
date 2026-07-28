package dareka.processor.impl;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dareka.common.CloseUtil;
import dareka.common.Logger;
import dareka.processor.HttpRequestHeader;
import dareka.processor.HttpResponseHeader;
import dareka.processor.Processor;
import dareka.processor.Resource;
import dareka.processor.StringResource;
import dareka.processor.TransferListener;
import dareka.processor.URLResource;

// [nl] サムネイル画像をキャッシュしていくよ！
public class ThumbProcessor implements Processor, TransferListener {
    private static final String[] SUPPORTED_METHODS = new String[] { "GET" };
    private static final Pattern SM_THUMB_PATTERN = Pattern
            .compile("^https?://tn(?:-skr[^\\.]*)?\\.smilevideo\\.jp/smile\\?i=(\\d+)([^\\d].*)?$");

    private static final int SIGNITURE = 1203160629;
    private static final int VERSION = 2;

// kapaer
    private static ConcurrentHashMap<Integer, long[]> id2thum =
            new ConcurrentHashMap<>();
    private static ConcurrentHashMap<Integer, ByteBuffer> id2thum_tmp =
            new ConcurrentHashMap<>();
    private static AtomicLong savedRequest = new AtomicLong();
    private static AtomicLong savedTransfer = new AtomicLong();

    private static File thcache =
            UserDataPaths.userFile("thcache.dat");
    private static File thcacheIndex =
            UserDataPaths.userFile("thIndex.dat");

    private static boolean indexStoreMode = true;

    // サムネイルキャッシュの読み込み
    public static void init()
    {
        synchronized (ThumbProcessor.class) {
            if (!Boolean.getBoolean("quickThumbnailCache")) {
                thcacheIndex.delete();
                indexStoreMode = false;
            }

            if (indexStoreMode && thcacheIndex.exists() && thcache.exists()) {
                if (!init_fast()) {
                    thcacheIndex.delete();
                    init_v2();
                }
            } else if (thcache.exists()) {
                thcacheIndex.delete();
                init_v2();
            } else {
                thcacheIndex.delete();
            }
        }
    }

// インデックスを読み込む
    private static boolean init_fast()
    {
        RandomAccessFile raf = null;
        long lastPos = 0;

// まずキャッシュのヘッダを読み込む
        try {
            raf = new RandomAccessFile(thcache, "rw");
            int signiture = raf.readInt();
            int version = raf.readInt();
            if (signiture != SIGNITURE || version != VERSION)
            {
// ヘッダがおかしい
                return false;
            }
            @SuppressWarnings("unused") // 使わないので読み飛ばすだけ
            int size = raf.readInt();
            savedRequest.set(raf.readLong());
            savedTransfer.set(raf.readLong());
        } catch (FileNotFoundException e) {
        } catch (IOException e) {
            return false;
        }
        finally {
            CloseUtil.close(raf);
        }

// インデックス本体を読み込む
        try {
            raf = new RandomAccessFile(thcacheIndex, "rw");
            int signiture = raf.readInt();
            int version = raf.readInt();
            if (signiture != SIGNITURE || version != VERSION)
            {
// ヘッダがおかしかったら作り直す
                return false;
            }

            while (raf.getFilePointer() < raf.length())
            {
                int key = raf.readInt();
                long point = raf.readLong();
                int len = raf.readInt();
                long[] bufPoint = {point, len};

                id2thum.put(key, bufPoint);
                lastPos = raf.getFilePointer();
            }
// ファイルサイズを設定する
// ひょっとしたらごみがついてるかもしれないので
            raf.setLength(lastPos);

        } catch (FileNotFoundException e) {
        } catch (IOException e) {
// エラーがあったら作り直す
            return false;
        }
        finally {
            CloseUtil.close(raf);
        }

        Logger.info("Thumbnail cache v2(quickStart): %,d files (saved %,d requests, %,d bytes)",
                id2thum.size(), savedRequest.get(), savedTransfer.get());

        return true;
    }

// インデックスをすべて保存する
    protected static void storeAllIndex()
    {
        boolean writeFailed = false;
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(thcacheIndex, "rw");
            raf.setLength(0);
            raf.writeInt(SIGNITURE);
            raf.writeInt(VERSION);

            for (Integer key: id2thum.keySet())
            {
                raf.writeInt(key);
                raf.writeLong(id2thum.get(key)[0]);
                raf.writeInt((int)id2thum.get(key)[1]);
            }
        } catch (FileNotFoundException e) {
            indexStoreMode = false;
            writeFailed = true;
            Logger.warning("Store "+thcacheIndex.getName()+" failed! QuickThumbnailCache OFF...");
        } catch (IOException e) {
            writeFailed = true;
            indexStoreMode = false;
            Logger.warning("Store "+thcacheIndex.getName()+" failed! QuickThumbnailCache OFF...");
        }
        finally
        {
            CloseUtil.close(raf);
        }
        if (writeFailed) {
            thcacheIndex.delete();
        }

    }


    // format version 2
    private static void init_v2()
    {
        id2thum.clear();
        id2thum_tmp.clear();
        RandomAccessFile raf = null;
        long lastPos = 0;
        long lastLen = 0;
        int lastKey = 0;

        try {
            raf = new RandomAccessFile(thcache, "rw");
            int signiture = raf.readInt();
            int version = raf.readInt();
            if (signiture != SIGNITURE || version != VERSION)
            {
// ヘッダがおかしかったらいきなり削除
                raf.setLength(0);
                return ;
            }

            @SuppressWarnings("unused") // 使わないので読み飛ばすだけ
            int size = raf.readInt();
            savedRequest.set(raf.readLong());
            savedTransfer.set(raf.readLong());
            while (raf.getFilePointer() < raf.length())
            {
                int key = raf.readInt();
                int len = raf.readInt();
                long[] bufPoint = {raf.getFilePointer(), len};
// 長さが妙だったら一つ前の奴の長さが狂ってると思われるので、
// それも削除する
                if (!(0 < len && len < 100000))
                    throw new IOException();

// ヘッダが妙な奴は保存失敗してるとみなす
// ヘッダからの判定は、JPGとGIFがある(0xff か 0x47('G'))
                int head = raf.read();
                if (head != 0xff && head != 0x47) {
                    throw new IOException();
                }
// 一つ前の奴までは正しいっぽいので、last**を更新
// エラーがあった時は、last**の示すキャッシュも削除される
// 美しくない。適当なクラスでもでっち上げるか。
                lastPos = bufPoint[0];
                lastLen = bufPoint[1];
                lastKey = key;

// EOFを超えてなければseek
                if (lastPos + lastLen > raf.length())
                    throw new IOException();
                raf.seek(lastPos + lastLen);

                id2thum.put(key, bufPoint);
            }
// ファイルサイズを設定する
// ひょっとしたらごみがついてるかもしれないので
            if (lastKey != 0) {
                raf.setLength(lastPos+lastLen);
            }
        } catch (FileNotFoundException e) {
        } catch (IOException e) {
// 読み込みエラーなら、一つ前のキャッシュも削除
// readIntは32ビットなので、x2で8バイト
            id2thum.remove(lastKey);
            try {
                if (lastPos > 8)
                    lastPos -= 8;
                raf.setLength(lastPos);
            } catch (IOException e1) {}
        }
        finally {
            CloseUtil.close(raf);
        }
        if (indexStoreMode) {
            storeAllIndex();
        }
        Logger.info("Thumbnail cache v2: %,d files (saved %,d requests, %,d bytes)",
                id2thum.size(), savedRequest.get(), savedTransfer.get());
    }

    // v2
    protected static void store(boolean silent)
    {
        Set<Map.Entry<Integer, long[]>> kvSet = id2thum.entrySet();
        RandomAccessFile raf = null;
        RandomAccessFile rafIndex = null;
        boolean writefailed = false;
        try {
            raf = new RandomAccessFile(thcache, "rw");
            raf.writeInt(SIGNITURE);
            raf.writeInt(VERSION);
// Index保存、サイズが小さいから同時に書き込んでも断片化はしない･･･と思う
            if (indexStoreMode) {
                rafIndex = new RandomAccessFile(thcacheIndex, "rw");
                rafIndex.writeInt(SIGNITURE);
                rafIndex.writeInt(VERSION);
                rafIndex.seek(rafIndex.length());
            }
// サイズは保存時にはじかれて減るかもしれないけど、今は使ってないから放置
            raf.writeInt(kvSet.size()+id2thum_tmp.size());

            raf.writeLong(savedRequest.get());
            raf.writeLong(savedTransfer.get());
// テンポラリを後ろに追加する
            int size = id2thum_tmp.size();
            raf.seek(raf.length());
            for (Integer key: id2thum_tmp.keySet())
            {
                byte[] data = id2thum_tmp.get(key).array();
// ヘッダが妙な奴は保存しない
// ヘッダからの判定は、JPGとGIFがある(0xff か 0x47('G'))
                if (data[0] == (byte)0xff || data[0] == (byte)0x47) {
                    raf.writeInt(key);
                    raf.writeInt(data.length);
                    long[] bufPoint = {raf.getFilePointer(), data.length};
                    raf.write(data, 0, data.length);
                    id2thum.put(key, bufPoint);
// Index保存
                    if (indexStoreMode) {
                        rafIndex.writeInt(key);
                        rafIndex.writeLong(id2thum.get(key)[0]);
                        rafIndex.writeInt((int)id2thum.get(key)[1]);
                    }
                }
                id2thum_tmp.remove(key);
            }
            if (!silent)
                Logger.info("%d thumbs added", size);
        } catch (FileNotFoundException e) {
            Logger.warning("Unable to store " + thcache.getName());
        } catch (IOException e) {
            writefailed = true;
            Logger.warning("Storing thumb cache is failed!");
        }
        finally
        {
            CloseUtil.close(raf);
            CloseUtil.close(rafIndex);
            if (writefailed)
            {
// 書き込み失敗したら読み直し
// それで壊れてる分を切り捨てる
// ヘッダまで壊れてたら、initで削除される
                init_v2();
            }
        }
    }

    // Processorの実装
    Integer id;
    @Override
    public String[] getSupportedMethods() {
        return SUPPORTED_METHODS;
    }

    @Override
    public Pattern getSupportedURLAsPattern() {
        return SM_THUMB_PATTERN;
    }

    @Override
    public String getSupportedURLAsString() {
        return null;
    }

    @Override
    public Resource onRequest(HttpRequestHeader requestHeader, Socket browser)
            throws IOException {
        Matcher m = SM_THUMB_PATTERN.matcher(requestHeader.getURI());
        if (!m.find())
            return new URLResource(requestHeader.getURI());
        id = Integer.parseInt(m.group(1));

        ByteBuffer buf = id2thum_tmp.get(id);
        long[] bufPoint = id2thum.get(id);

        if (buf != null || bufPoint != null)
        {
            StringResource r;
            if (requestHeader.getMessageHeader("If-Modified-Since") != null) {
                savedRequest.incrementAndGet();
                r = StringResource.getNotModified();
            } else {
                byte[] data;
                if (buf != null) {
                    data = buf.array();
                } else {
// ファイルから読み込む
                    RandomAccessFile raf = null;
                    try {
                        raf = new RandomAccessFile(thcache, "r");
                        raf.seek(bufPoint[0]);
                        data = new byte[(int)bufPoint[1]];
                        raf.readFully(data);
                    } catch (IOException e) {
                        data = null;
                    } finally {
                        CloseUtil.close(raf);
                    }
                }

// そのIDのサムネが読み込みエラーならキャッシュせずに返す
                if (data == null) {
                    return new URLResource(requestHeader.getURI());
                }
                savedTransfer.addAndGet(data.length);
                long sR = savedRequest.incrementAndGet();

                synchronized (ThumbProcessor.class) {
                    if (sR % 20 == 0)
                        store(true);
                }
                r = new StringResource(data);
            }
            r.setResponseHeader("Content-Type", "image/jpeg");
            return r;
        }
        URLResource r = new URLResource(requestHeader.getURI());
        r.addTransferListener(this);
        return r;
    }

    // TransferListenerの実装
    private ByteArrayOutputStream out = null;
    @Override
    public void onResponseHeader(HttpResponseHeader responseHeader) {
        if (responseHeader.getStatusCode() == 200)
            out = new ByteArrayOutputStream();
        //else
            //Logger.info(responseHeader.getReason() + " " + responseHeader.getStatusCode());
    }

    @Override
    public void onTransferBegin(OutputStream receiverOut) { }

    @Override
    public void onTransferring(byte[] buf, int length) {
        if (out != null)
            out.write(buf, 0, length);
    }

    @Override
    public void onTransferEnd(boolean completed) {
        synchronized (ThumbProcessor.class)
        {
            if (completed && out != null && out.size() > 0)
            {
// とりあえずメモリ上のテンポラリに保存
// 溜まったらファイルに書き出して、テンポラリ削除
// 以降はファイルから読み込む
                id2thum_tmp.put(id, ByteBuffer.wrap(out.toByteArray()));
                if (id2thum_tmp.size() >= 30)
                    store(false);
                out = null;
            }
        }
    }
}
