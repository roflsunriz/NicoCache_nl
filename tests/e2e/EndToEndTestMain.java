package e2e;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Black-box tests against the same executable JAR entry point used by users.
 */
public final class EndToEndTestMain {
    private static final Duration START_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(10);
    private static final int CLIENT_TIMEOUT_MILLIS = 5_000;
    private static final List<String> FAILURES = new ArrayList<>();

    private final Path repository;
    private final Path sandbox;
    private final Path productJar;
    private final Path application;
    private final Path data;
    private HttpServer upstream;
    private ExecutorService upstreamExecutor;
    private Process product;
    private int upstreamPort;
    private int productPort;

    private EndToEndTestMain(Path repository, Path sandbox, Path productJar) {
        this.repository = repository;
        this.sandbox = sandbox;
        this.productJar = productJar;
        this.application = sandbox.resolve("application");
        this.data = sandbox.resolve("data");
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException(
                    "usage: EndToEndTestMain <repository> <sandbox> <product-jar>");
        }
        EndToEndTestMain suite = new EndToEndTestMain(
                Path.of(args[0]).toAbsolutePath().normalize(),
                Path.of(args[1]).toAbsolutePath().normalize(),
                Path.of(args[2]).toAbsolutePath().normalize());
        suite.execute();
    }

    private void execute() throws Exception {
        try {
            startUpstream(0);
            prepareSandbox();
            startProduct();

            run("real JAR local-file user flow", this::testLocalFileFlow);
            run("local-file path containment", this::testPathContainment);
            run("malformed and ambiguous HTTP rejection",
                    this::testMalformedRequests);
            run("slow and abruptly disconnected clients",
                    this::testIncompleteAndDisconnectedClients);
            run("parallel user requests and recovery",
                    this::testParallelUsersAndRecovery);
            run("actionable upstream failure response",
                    this::testUpstreamFailure);
        } finally {
            stopProduct();
            stopUpstream();
        }

        if (!FAILURES.isEmpty()) {
            System.err.println("End-to-end failures: " + FAILURES.size());
            for (String failure : FAILURES) {
                System.err.println("  - " + failure);
            }
            throw new AssertionError("end-to-end tests failed");
        }
        System.out.println("End-to-end tests passed: 6");
    }

    private void prepareSandbox() throws IOException {
        Files.createDirectories(application.resolve("defaults"));
        Files.createDirectories(data.resolve("local"));
        Files.createDirectories(data.resolve("extensions"));
        Files.createDirectories(data.resolve("cache"));
        Files.createDirectories(data.resolve("cvcache"));
        Files.createDirectories(data.resolve("thcache"));

        try (var defaults = Files.list(repository.resolve("defaults"))) {
            defaults.filter(Files::isRegularFile).forEach(source -> copy(
                    source,
                    application.resolve("defaults").resolve(source.getFileName())));
        }
        copy(repository.resolve("local/mime.types.default"),
                data.resolve("local/mime.types"));
        copy(repository.resolve("nlFilter_sys.txt"),
                application.resolve("nlFilter_sys.txt"));
        copy(repository.resolve("data/tlsclient/cacerts2"),
                data.resolve("data/tlsclient/cacerts2"));

        Files.writeString(data.resolve("local/e2e.txt"),
                "end-to-end-content", StandardCharsets.UTF_8);
        Files.writeString(data.resolve("secret-outside-local.txt"),
                "must-never-be-served", StandardCharsets.UTF_8);

        productPort = freePort();
        String config = String.join("\n",
                "# NicoCache_nl 設定ファイル",
                "listenPort=" + productPort,
                "proxyHost=127.0.0.1",
                "proxyPort=" + upstreamPort,
                "allowFrom=local",
                "readTimeout=2000",
                "cacheFolder=cache",
                "convertedCacheFolder=cvcache",
                "needFreeSpace=0",
                "cacheAllocateFirst=false",
                "disableDirectoryWatcher=true",
                "disableVideoCacheSystem=true",
                "enableMitm=false",
                "cacheThumbnail=false",
                "cacheGetThumbInfo=false",
                "cacheExtThumb=false",
                "autoCacheComment=false",
                "useSearchExtension=false",
                "scriptOn=0",
                "localFileServer=true",
                "localRewriter=false",
                "mimeTypes=local/mime.types",
                "touchCache=false",
                "title=false",
                "");
        Files.writeString(data.resolve("config.properties"),
                config, StandardCharsets.UTF_8);
    }

    private void startProduct() throws Exception {
        Path log = sandbox.resolve("product.log");
        ProcessBuilder builder = new ProcessBuilder(
                javaExecutable(),
                "-Djava.awt.headless=true",
                "-Dnicocache.applicationRoot=" + application,
                "-Dnicocache.dataRoot=" + data,
                "-jar",
                productJar.toString(),
                "--headless");
        builder.directory(data.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(log.toFile());
        product = builder.start();

        long deadline = System.nanoTime() + START_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (!product.isAlive()) {
                throw new AssertionError(
                        "product exited before listening; log=" + log);
            }
            try (Socket socket = new Socket()) {
                socket.connect(loopback(productPort), 250);
                return;
            } catch (IOException error) {
                Thread.sleep(100L);
            }
        }
        throw new AssertionError(
                "product did not listen within " + START_TIMEOUT + "; log=" + log);
    }

    private void stopProduct() throws Exception {
        if (product == null || !product.isAlive()) {
            return;
        }
        product.destroy();
        if (!product.waitFor(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            product.destroyForcibly();
            if (!product.waitFor(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AssertionError("product process could not be terminated");
            }
        }
    }

    private void testLocalFileFlow() throws Exception {
        Response get = request(localRequest("GET", "/local/e2e.txt", ""));
        assertEquals(200, get.status, "GET status");
        assertEquals("end-to-end-content", get.bodyText(), "GET body");
        assertContains(get.header("content-type"), "text/plain", "GET MIME");
        assertEquals(Integer.toString(get.body.length),
                get.header("content-length"), "GET Content-Length");

        Response head = request(localRequest("HEAD", "/local/e2e.txt", ""));
        assertEquals(200, head.status, "HEAD status");
        assertEquals(0, head.body.length, "HEAD body");
        assertEquals(Integer.toString(get.body.length),
                head.header("content-length"), "HEAD Content-Length");

        Response range = request(localRequest(
                "GET", "/local/e2e.txt", "Range: bytes=4-9\r\n"));
        assertEquals(206, range.status, "Range status");
        assertEquals("to-end", range.bodyText(), "Range body");
        assertEquals("bytes 4-9/18", range.header("content-range"),
                "Range Content-Range");

        Response unsatisfied = request(localRequest(
                "GET", "/local/e2e.txt", "Range: bytes=999-1000\r\n"));
        assertEquals(416, unsatisfied.status, "unsatisfiable Range status");

        Response post = request(localRequest(
                "POST", "/local/e2e.txt", "Content-Length: 0\r\n"));
        assertEquals(405, post.status, "POST status");
    }

    private void testPathContainment() throws Exception {
        for (String path : List.of(
                "/local/../secret-outside-local.txt",
                "/local/%2e%2e/secret-outside-local.txt",
                "/local/%2E%2E%5Csecret-outside-local.txt",
                "/local/%2f..%2fsecret-outside-local.txt")) {
            byte[] raw = requestRaw(localRequest("GET", path, ""));
            Response response = Response.parse(raw);
            assertTrue(response.status == 400 || response.status == 404,
                    "traversal must be rejected for " + path
                    + ": status=" + response.status);
            assertFalse(response.bodyText().contains("must-never-be-served"),
                    "traversal leaked a file for " + path);
        }
    }

    private void testMalformedRequests() throws Exception {
        assertClosedWithoutSuccess("invalid request line",
                "GET\r\nHost: www.nicovideo.jp\r\n\r\n");
        assertClosedWithoutSuccess("unsupported HTTP version",
                "GET http://www.nicovideo.jp/local/e2e.txt HTTP/9.9\r\n"
                + "Host: www.nicovideo.jp\r\n\r\n");
        assertClosedWithoutSuccess("bare LF delimiters",
                "GET http://www.nicovideo.jp/local/e2e.txt HTTP/1.1\n"
                + "Host: www.nicovideo.jp\n\n");
        assertClosedWithoutSuccess("missing Host",
                "GET http://www.nicovideo.jp/local/e2e.txt HTTP/1.1\r\n"
                + "Connection: close\r\n\r\n");
        assertClosedWithoutSuccess("duplicate Host",
                "GET http://www.nicovideo.jp/local/e2e.txt HTTP/1.1\r\n"
                + "Host: www.nicovideo.jp\r\n"
                + "Host: attacker.invalid\r\nConnection: close\r\n\r\n");
        assertClosedWithoutSuccess("invalid field-name whitespace",
                "GET http://www.nicovideo.jp/local/e2e.txt HTTP/1.1\r\n"
                + "Host : www.nicovideo.jp\r\nConnection: close\r\n\r\n");
        assertClosedWithoutSuccess("conflicting Content-Length",
                "POST http://www.nicovideo.jp/local/e2e.txt HTTP/1.1\r\n"
                + "Host: www.nicovideo.jp\r\n"
                + "Content-Length: 0\r\nContent-Length: 4\r\n"
                + "Connection: close\r\n\r\ndata");
        assertClosedWithoutSuccess("Transfer-Encoding and Content-Length",
                "POST http://www.nicovideo.jp/local/e2e.txt HTTP/1.1\r\n"
                + "Host: www.nicovideo.jp\r\n"
                + "Transfer-Encoding: chunked\r\nContent-Length: 4\r\n"
                + "Connection: close\r\n\r\n0\r\n\r\n");

        String oversized = "GET http://www.nicovideo.jp/local/e2e.txt HTTP/1.1\r\n"
                + "Host: www.nicovideo.jp\r\nX-Oversized: "
                + "a".repeat(70 * 1024)
                + "\r\nConnection: close\r\n\r\n";
        byte[] raw = requestRaw(oversized);
        if (raw.length > 0) {
            Response response = Response.parse(raw);
            assertTrue(response.status == 400 || response.status == 431,
                    "oversized header must be 400/431, actual=" + response.status);
        }
    }

    private void testIncompleteAndDisconnectedClients() throws Exception {
        long started = System.nanoTime();
        try (Socket socket = connectedSocket()) {
            socket.setSoTimeout(4_000);
            socket.getOutputStream().write((
                    "GET http://www.nicovideo.jp/local/e2e.txt HTTP/1.1\r\n"
                    + "Host: www.nicovideo.jp\r\nX-Incomplete: value")
                    .getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            int result = socket.getInputStream().read();
            assertEquals(-1, result, "incomplete request must be closed");
        } catch (SocketTimeoutException error) {
            throw new AssertionError(
                    "incomplete request was not closed within the server timeout", error);
        }
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - started);
        assertTrue(elapsedMillis >= 1_500 && elapsedMillis < 4_000,
                "incomplete request timeout out of bounds: " + elapsedMillis + "ms");

        try (Socket socket = connectedSocket()) {
            socket.getOutputStream().write((
                    "GET http://www.nicovideo.jp/local/e2e.txt HTTP/1.1\r\n"
                    + "Host: www.nicovideo.jp\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
        }
        Response recovered = request(localRequest(
                "GET", "/local/e2e.txt", ""));
        assertEquals(200, recovered.status,
                "server must recover after abrupt disconnect");
    }

    private void testParallelUsersAndRecovery() throws Exception {
        ExecutorService users = Executors.newFixedThreadPool(8);
        try {
            List<Callable<String>> operations = new ArrayList<>();
            for (int index = 0; index < 24; index++) {
                final int user = index;
                operations.add(() -> {
                    Response response = request(
                            "GET http://example.invalid/echo?user=" + user
                            + " HTTP/1.1\r\nHost: example.invalid\r\n"
                            + "Connection: close\r\n\r\n");
                    assertEquals(200, response.status,
                            "parallel status for user " + user);
                    return response.bodyText();
                });
            }
            List<Future<String>> futures = users.invokeAll(
                    operations, 15, TimeUnit.SECONDS);
            for (int index = 0; index < futures.size(); index++) {
                Future<String> future = futures.get(index);
                assertFalse(future.isCancelled(),
                        "parallel request timed out for user " + index);
                assertEquals("/echo?user=" + index, future.get(),
                        "parallel response for user " + index);
            }
        } finally {
            users.shutdownNow();
            assertTrue(users.awaitTermination(5, TimeUnit.SECONDS),
                    "parallel user executor leaked");
        }
        Response recovered = request(localRequest("GET", "/local/e2e.txt", ""));
        assertEquals(200, recovered.status,
                "server must remain available after concurrency");
    }

    private void testUpstreamFailure() throws Exception {
        int stoppedPort = upstreamPort;
        stopUpstream();
        try {
            Response response = request(
                    "GET http://example.invalid/unavailable HTTP/1.1\r\n"
                    + "Host: example.invalid\r\nConnection: close\r\n\r\n");
            assertEquals(502, response.status,
                    "upstream connection failure status");
            assertTrue(!response.bodyText().isBlank(),
                    "upstream connection failure must explain the error");
        } finally {
            startUpstream(stoppedPort);
        }
    }

    private void assertClosedWithoutSuccess(String name, String rawRequest)
            throws Exception {
        byte[] raw = requestRaw(rawRequest);
        if (raw.length == 0) {
            return;
        }
        Response response = Response.parse(raw);
        assertTrue(response.status == 400
                        || response.status == 414
                        || response.status == 431
                        || response.status == 505,
                name + " must be rejected as a malformed request, actual="
                + response.status);
    }

    private Response request(String request) throws Exception {
        return Response.parse(requestRaw(request));
    }

    private byte[] requestRaw(String request) throws Exception {
        try (Socket socket = connectedSocket()) {
            socket.setSoTimeout(CLIENT_TIMEOUT_MILLIS);
            socket.getOutputStream().write(
                    request.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            return readHttpResponse(
                    socket.getInputStream(), request.startsWith("HEAD "));
        }
    }

    private Socket connectedSocket() throws IOException {
        Socket socket = new Socket();
        socket.connect(loopback(productPort), 2_000);
        return socket;
    }

    private static byte[] readHttpResponse(InputStream input, boolean head)
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int headerEnd = -1;
        int contentLength = -1;
        while (true) {
            int read = input.read(buffer);
            if (read < 0) {
                break;
            }
            output.write(buffer, 0, read);
            byte[] received = output.toByteArray();
            if (headerEnd < 0) {
                headerEnd = indexOf(received, "\r\n\r\n".getBytes(
                        StandardCharsets.ISO_8859_1));
                if (headerEnd >= 0) {
                    String headerText = new String(
                            received, 0, headerEnd,
                            StandardCharsets.ISO_8859_1);
                    for (String line : headerText.split("\r\n")) {
                        int colon = line.indexOf(':');
                        if (colon > 0
                                && "content-length".equalsIgnoreCase(
                                        line.substring(0, colon).trim())) {
                            contentLength = Integer.parseInt(
                                    line.substring(colon + 1).trim());
                        }
                    }
                    if (head) {
                        contentLength = 0;
                    }
                }
            }
            if (headerEnd >= 0 && contentLength >= 0
                    && received.length >= headerEnd + 4 + contentLength) {
                break;
            }
        }
        return output.toByteArray();
    }

    private static String localRequest(
            String method, String path, String extraHeaders) {
        return method + " http://www.nicovideo.jp" + path + " HTTP/1.1\r\n"
                + "Host: www.nicovideo.jp\r\n"
                + extraHeaders
                + "Connection: close\r\n\r\n";
    }

    private void startUpstream(int port) throws IOException {
        upstream = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        upstream.createContext("/", this::handleUpstream);
        upstreamExecutor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "e2e-upstream");
            thread.setDaemon(true);
            return thread;
        });
        upstream.setExecutor(upstreamExecutor);
        upstream.start();
        upstreamPort = upstream.getAddress().getPort();
    }

    private void stopUpstream() {
        if (upstream != null) {
            upstream.stop(0);
            upstream = null;
        }
        if (upstreamExecutor != null) {
            upstreamExecutor.shutdownNow();
            upstreamExecutor = null;
        }
    }

    private void handleUpstream(HttpExchange exchange) throws IOException {
        try {
            String target = exchange.getRequestURI().getRawPath();
            if (exchange.getRequestURI().getRawQuery() != null) {
                target += "?" + exchange.getRequestURI().getRawQuery();
            }
            byte[] body = target
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set(
                    "Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        } finally {
            exchange.close();
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(
                0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static InetSocketAddress loopback(int port) {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
    }

    private static String javaExecutable() {
        return Path.of(
                System.getProperty("java.home"),
                "bin",
                isWindows() ? "java.exe" : "java").toString();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name")
                .toLowerCase(Locale.ROOT).contains("win");
    }

    private static void copy(Path source, Path target) {
        try {
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) {
            throw new IllegalStateException(
                    "failed to copy " + source + " to " + target, error);
        }
    }

    private static int indexOf(byte[] value, byte[] needle) {
        outer:
        for (int index = 0; index <= value.length - needle.length; index++) {
            for (int offset = 0; offset < needle.length; offset++) {
                if (value[index + offset] != needle[offset]) {
                    continue outer;
                }
            }
            return index;
        }
        return -1;
    }

    private static void run(String name, CheckedRunnable test) {
        try {
            test.run();
            System.out.println("PASS " + name);
        } catch (Throwable error) {
            FAILURES.add(name + ": " + error.getMessage());
            error.printStackTrace(System.err);
        }
    }

    private static void assertEquals(
            Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(
                    message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertContains(
            String actual, String expected, String message) {
        if (actual == null || !actual.contains(expected)) {
            throw new AssertionError(
                    message + ": expected to contain=" + expected
                    + ", actual=" + actual);
        }
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    private static void assertFalse(boolean value, String message) {
        if (value) {
            throw new AssertionError(message);
        }
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }

    private static final class Response {
        private final int status;
        private final Map<String, String> headers;
        private final byte[] body;

        private Response(
                int status, Map<String, String> headers, byte[] body) {
            this.status = status;
            this.headers = headers;
            this.body = body;
        }

        private static Response parse(byte[] raw) {
            int headerEnd = indexOf(raw, "\r\n\r\n".getBytes(
                    StandardCharsets.ISO_8859_1));
            if (headerEnd < 0) {
                throw new AssertionError(
                        "response header is incomplete: " + raw.length + " bytes");
            }
            String headerText = new String(
                    raw, 0, headerEnd, StandardCharsets.ISO_8859_1);
            String[] lines = headerText.split("\r\n");
            String[] statusParts = lines[0].split(" ", 3);
            int statusCode = Integer.parseInt(statusParts[1]);
            Map<String, String> responseHeaders = new LinkedHashMap<>();
            for (int index = 1; index < lines.length; index++) {
                int colon = lines[index].indexOf(':');
                if (colon > 0) {
                    responseHeaders.put(
                            lines[index].substring(0, colon)
                                    .trim().toLowerCase(Locale.ROOT),
                            lines[index].substring(colon + 1).trim());
                }
            }
            byte[] responseBody = java.util.Arrays.copyOfRange(
                    raw, headerEnd + 4, raw.length);
            return new Response(statusCode, responseHeaders, responseBody);
        }

        private String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        private String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }
}
