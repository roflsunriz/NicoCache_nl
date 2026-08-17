package dareka;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import dareka.common.Logger;

/** 既存の利用者用PACへ専用管理ホストのローカル経路を一度だけ追加する。 */
final class ProxyPacUpdater {
    private static final String HOST = "nicocachenl.test";
    private static final String ROUTE_MARKER =
            "host.toLowerCase() === '" + HOST + "'";
    private static final String FUNCTION = "function FindProxyForURL(url, host) {";

    private ProxyPacUpdater() {
    }

    static void update() {
        Path pac = NicoCachePaths.proxyPacFile().toPath();
        if (!Files.isRegularFile(pac)) {
            return;
        }
        try {
            String source = Files.readString(pac, StandardCharsets.UTF_8);
            if (source.contains(ROUTE_MARKER)) {
                return;
            }
            int function = source.indexOf(FUNCTION);
            if (function < 0) {
                Logger.warning("proxy.pacへ専用管理ホストを追加できません: FindProxyForURLがありません");
                return;
            }
            String newline = source.contains("\r\n") ? "\r\n" : "\n";
            int insertAt = function + FUNCTION.length();
            String route = newline
                    + "  // NicoCache_nl management site and REST API." + newline
                    + "  if (host.toLowerCase() === '" + HOST + "') {" + newline
                    + "    return 'PROXY 127.0.0.1:"
                    + Integer.getInteger("listenPort", 8080) + "';" + newline
                    + "  };" + newline;
            Path backup = pac.resolveSibling("proxy.pac.pre-rest-api.bak");
            if (!Files.exists(backup)) {
                Files.copy(pac, backup, StandardCopyOption.COPY_ATTRIBUTES);
            }
            Path temporary = pac.resolveSibling("proxy.pac.rest-api.part");
            Files.writeString(temporary,
                    source.substring(0, insertAt) + route + source.substring(insertAt),
                    StandardCharsets.UTF_8);
            try {
                Files.move(temporary, pac, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException error) {
                Files.move(temporary, pac, StandardCopyOption.REPLACE_EXISTING);
            }
            Logger.info("proxy.pacへ" + HOST + "のローカル経路を追加しました");
        } catch (IOException error) {
            Logger.warning("proxy.pacの専用管理ホスト移行に失敗しました: " + error);
        }
    }
}
