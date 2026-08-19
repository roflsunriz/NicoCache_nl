package dareka;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import dareka.common.Logger;
import dareka.internal.TextFileCodec;
import dareka.internal.TextFileCodec.DecodedText;

/** Adds the dedicated management host route to the canonical UTF-8 PAC. */
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
        Path temporary = pac.resolveSibling("proxy.pac.rest-api.part");
        try {
            byte[] original = Files.readAllBytes(pac);
            DecodedText decoded = TextFileCodec.decode(original,
                    text -> text.contains(FUNCTION)
                            || text.contains(ROUTE_MARKER));
            String source = decoded.getText();
            if (source.contains(ROUTE_MARKER)) {
                normalizeIfNeeded(pac, temporary, original, decoded);
                return;
            }
            int function = source.indexOf(FUNCTION);
            if (function < 0) {
                Logger.warning("proxy.pacへ専用管理ホストを追加できません。"
                        + "FindProxyForURLがありません。ランチャーの"
                        + "「データルート診断」を開いてください: " + pac);
                return;
            }
            String newline = source.contains("\r\n") ? "\r\n"
                    : source.contains("\r") ? "\r" : "\n";
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
            writeAtomically(temporary, pac,
                    source.substring(0, insertAt) + route
                    + source.substring(insertAt));
            Logger.info("proxy.pacへ" + HOST + "のローカル経路を追加しました");
        } catch (IOException error) {
            Logger.warning("proxy.pacを更新できません。元ファイルは変更していません。"
                    + "ランチャーの「データルート診断」を開いてください: "
                    + error.getMessage());
            Logger.debugWithThread(error);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException error) {
                Logger.debugWithThread(error);
            }
        }
    }

    private static void normalizeIfNeeded(Path pac, Path temporary,
            byte[] original, DecodedText decoded) throws IOException {
        if (decoded.isCanonicalUtf8()) {
            return;
        }
        Path backup = pac.resolveSibling("proxy.pac.pre-utf8.bak");
        if (!Files.exists(backup)) {
            Files.write(backup, original);
        }
        writeAtomically(temporary, pac, decoded.getText());
        Logger.info("proxy.pacを"
                + decoded.getEncoding().getDisplayName()
                + "からUTF-8へ変換しました");
    }

    private static void writeAtomically(Path temporary, Path destination,
            String value) throws IOException {
        Files.writeString(temporary, value, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, destination,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException error) {
            Files.move(temporary, destination,
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
