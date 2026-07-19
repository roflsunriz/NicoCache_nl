package dareka.processor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dareka.common.CloseUtil;
import dareka.common.Config;
import dareka.common.HttpIOException;

/**
 * Resource which is retrieved from a TCP/IP connection.
 *
 * <p>
 * This class cannot handle onTransferXXX events.
 *
 */
public class HostportResource extends Resource {
    private static final Pattern HOSTPORT_PATTERN =
        Pattern.compile("^([^:]+):(\\d+)$");

    private InetSocketAddress host;

    private volatile Selector processingSelector;

    public HostportResource(String resource) throws HttpIOException {
        Matcher m = HOSTPORT_PATTERN.matcher(resource);
        if (!m.find()) {
            throw new HttpIOException("invalid hostport: " + resource);
        }

        host = new InetSocketAddress(m.group(1), Integer.parseInt(m.group(2)));
    }

    @Override
    public boolean transferTo(Socket receiver, HttpRequestHeader requestHeader,
            Config config) throws IOException {
        if (isSinkholeAddress()) {
            HttpResponseHeader responseHeader = new HttpResponseHeader(
                "HTTP/1.1 503 Service Unavailable\r\n"
                + "Content-Length: 0\r\n"
                + "Connection: close\r\n\r\n");
            execSendingHeaderSequence(
                receiver.getOutputStream(), responseHeader);
            return false;
        };

        // Socket#isClosed()
        // Socket#isInputShutdown()
        // Socket#isOutputShutdown()
        // のいずれでも終了を検知できないので
        // 回避のために非ブロックI/Oを使って実装する
        SocketChannel sc = getServerChannelForConnect();

        try {
            handleConnectOnChannel(sc, receiver.getChannel());
        } finally {
            CloseUtil.close(sc);
        }

        return false;
    }

    private boolean isSinkholeAddress() {
        return !host.isUnresolved() && host.getAddress().isAnyLocalAddress();
    }

    @Override
    public void stopTransfer() {
        super.stopTransfer();
        CloseUtil.close(processingSelector);
    }

    /* (非 Javadoc)
     * @see dareka.processor.Resource#doSetMandatoryHeader(dareka.processor.HttpResponseHeader)
     */
    @Override
    protected void doSetMandatoryResponseHeader(
            HttpResponseHeader responseHeader) {
        responseHeader.setMessageHeader(HttpHeader.CONNECTION,
                HttpHeader.CONNECTION_CLOSE);
    }

    /**
     * 接続するアウトバウンド側のChannelを取得する。
     * 設定によってProxyを経由する。
     *
     * @return 接続したアウトバウンド側のチャネル。
     * @throws IOException
     */
    private SocketChannel getServerChannelForConnect() throws IOException {
        SocketChannel sc = SocketChannel.open();
        try { // ensure sc.close() in case of error.
            String proxyHost = System.getProperty("proxyHost");
            int proxyPort = Integer.getInteger("proxyPort");

            // [nl] SSLセカンダリプロキシの選択
            if (!Boolean.getBoolean("proxySSL") || proxyHost.equals("")) {
                sc.connect(host);
            } else {
                Socket proxy = sc.socket();
                proxy.connect(new InetSocketAddress(proxyHost, proxyPort));

                HttpRequestHeader requestHeader =
                    new HttpRequestHeader("CONNECT " + host.getHostName() + ":"
                            + host.getPort() + " HTTP/1.1\r\n\r\n");
                requestHeader.setMessageHeader(HttpHeader.CONNECTION,
                        HttpHeader.CONNECTION_CLOSE);
                HttpUtil.sendHeader(proxy, requestHeader);

                HttpResponseHeader responseHeader =
                    new HttpResponseHeader(proxy.getInputStream());

                if (responseHeader.getStatusCode() != 200) {
                    throw new HttpIOException("failed to connect: "
                            + responseHeader.toString());
                }
            }
        } catch (IOException | RuntimeException e) {
            CloseUtil.close(sc);
            throw e;
        }

        return sc;
    }

    /**
     * CONNECT処理。Channelのcloseを見やすくするために分割。
     *
     * @param sc
     * @param bc
     * @throws IOException
     */
    private void handleConnectOnChannel(SocketChannel sc, SocketChannel bc)
    throws IOException {
        HttpResponseHeader responseHeader =
            new HttpResponseHeader(
            "HTTP/1.1 200 Connection established\r\n\r\n");

        execSendingHeaderSequence(bc.socket().getOutputStream(), responseHeader);

        sc.configureBlocking(false);
        bc.configureBlocking(false);

        processingSelector = Selector.open();
        try {
            if (isStopped()) {
                // これ以降にprocessingSelectorをopen()してはいけない。
                return;
            }

            SelectionKey scKey =
                sc.register(processingSelector, SelectionKey.OP_READ);
            SelectionKey bcKey =
                bc.register(processingSelector, SelectionKey.OP_READ);
            scKey.attach(bc); // 後で対になる側を取得できるようにしておく
            bcKey.attach(sc);

            handleConnectOnSelector(processingSelector);
        } finally {
            CloseUtil.close(processingSelector);
            processingSelector = null;
        }
    }

    /**
     * CONNECT処理。Selectorのcloseを見やすくするために分割。
     *
     * @param sel
     * @throws IOException
     */
    private void handleConnectOnSelector(Selector sel) throws IOException {
        ByteBuffer bbuf = ByteBuffer.allocate(BUF_SIZE);
        int len;

        int timeout = Integer.getInteger("readTimeout");

        // キーセットが0個の状態でselect()を呼ぶと永遠に待ってしまう。
        // しかし取り消されたキーセットは選択操作を行わないと実際に削除されない。
        // そこで更新させるためだけにselectNow()を呼ぶが
        // こうするとselect()の戻り値が0になることがある。
        int selcount;
        while ((selcount = sel.selectNow()) >= 0 && sel.keys().size() > 0
                && (selcount > 0 || sel.select(timeout) >= 0)) {
            Set<SelectionKey> selKeys = sel.selectedKeys();

            if (selKeys.isEmpty()) {
                // timeout
                break;
            }

            // for (SelectionKey key : selKeys)をするとなぜか
            // ConcurrentModificationExceptionになることがある
            for (Iterator<SelectionKey> ite = selKeys.iterator(); ite.hasNext();) {
                SelectionKey key = ite.next();
                ite.remove();

                SocketChannel readCh = (SocketChannel) key.channel();
                SocketChannel writeCh = (SocketChannel) key.attachment();

                bbuf.clear();
                try {
                    len = readCh.read(bbuf);
                } catch (IOException e) {
                    // select()を使っているので
                    // 「既存の接続はリモート ホストに強制的に切断されました。」
                    // で切断されることはないかも知れないが
                    // 念のため対応しておく
                    len = -1;
                }

                if (len == -1) {
                    key.cancel();
                    writeCh.socket().shutdownOutput();
                } else {
                    bbuf.flip();
                    while (bbuf.hasRemaining()) {
                        writeCh.write(bbuf);
                    }
                }
            }
        }
    }

}
