package nicocache.launcher;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

final class ControlClient {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private ControlClient() {
    }

    static Properties readStatus(Path statusFile) throws IOException {
        if (!Files.isRegularFile(statusFile)) {
            throw new IOException("本体の管理状態ファイルがありません: " + statusFile);
        }
        Properties properties = new Properties();
        try (var input = Files.newInputStream(statusFile)) {
            properties.load(input);
        }
        requireProperty(properties, "host");
        requireProperty(properties, "port");
        requireProperty(properties, "token");
        requireProperty(properties, "pid");
        return properties;
    }

    static Properties readStatusIfPresent(Path statusFile) {
        try {
            return readStatus(statusFile);
        } catch (IOException error) {
            return null;
        }
    }

    static boolean isAlive(Properties status) {
        if (status == null) {
            return false;
        }
        try {
            long pid = Long.parseLong(status.getProperty("pid"));
            return ProcessHandle.of(pid).map(ProcessHandle::isAlive)
                    .orElse(false);
        } catch (NumberFormatException error) {
            return false;
        }
    }

    static HttpResponse<String> getStatus(LauncherPaths paths)
            throws IOException, InterruptedException {
        return request(paths, "GET", "/api/control/status");
    }

    static HttpResponse<String> ping(LauncherPaths paths)
            throws IOException, InterruptedException {
        return request(paths, "GET", "/api/control/ping");
    }

    static HttpResponse<String> post(LauncherPaths paths, String endpoint)
            throws IOException, InterruptedException {
        return request(paths, "POST", endpoint);
    }

    static void waitUntilReady(LauncherPaths paths, Process process,
            Duration timeout) throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        IOException last = null;
        while (System.nanoTime() < deadline) {
            if (process != null && !process.isAlive()) {
                throw new IOException("本体が起動直後に終了しました");
            }
            Properties status = readStatusIfPresent(paths.getControlStatusFile());
            if (isAlive(status)) {
                try {
                    HttpResponse<String> response = ping(paths);
                    if (response.statusCode() == 200) {
                        return;
                    }
                    last = new IOException("管理APIの応答が不正です: "
                            + response.statusCode());
                } catch (IOException | InterruptedException error) {
                    if (error instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                        throw new IOException("管理APIの待機が中断されました", error);
                    }
                    last = (IOException) error;
                }
            }
            sleep(100L);
        }
        throw new IOException("本体の管理APIが時間内に起動しませんでした",
                last);
    }

    static void waitForExit(Properties status, Duration timeout)
            throws InterruptedException {
        if (status == null) {
            return;
        }
        long pid;
        try {
            pid = Long.parseLong(status.getProperty("pid"));
        } catch (NumberFormatException error) {
            return;
        }
        ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
        if (handle == null) {
            return;
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        while (handle.isAlive() && System.nanoTime() < deadline) {
            Thread.sleep(100L);
        }
    }

    private static HttpResponse<String> request(LauncherPaths paths,
            String method, String endpoint)
            throws IOException, InterruptedException {
        Properties status = readStatus(paths.getControlStatusFile());
        String host = status.getProperty("host");
        if (!"127.0.0.1".equals(host)) {
            throw new IOException("管理APIの接続先がループバックではありません");
        }
        int port;
        try {
            port = Integer.parseInt(status.getProperty("port"));
        } catch (NumberFormatException error) {
            throw new IOException("管理APIのポート番号が不正です", error);
        }
        if (port < 1 || port > 65535) {
            throw new IOException("管理APIのポート番号が不正です: " + port);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://" + host + ":" + port + endpoint))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + status.getProperty("token"));
        if ("POST".equals(method)) {
            builder.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            builder.GET();
        }
        return HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build()
                .send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void requireProperty(Properties properties, String key)
            throws IOException {
        if (properties.getProperty(key) == null
                || properties.getProperty(key).isBlank()) {
            throw new IOException("管理APIの状態ファイルに " + key
                    + " がありません");
        }
    }

    private static void sleep(long milliseconds) throws IOException {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("管理APIの待機が中断されました", error);
        }
    }
}
