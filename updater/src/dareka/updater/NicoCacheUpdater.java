package dareka.updater;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

/** Standalone updater for NicoCache_nl and its managed dependencies. */
public final class NicoCacheUpdater {
    private static final URI RELEASE_URI = URI.create(
            "https://api.github.com/repos/roflsunriz/NicoCache_nl/releases/latest");
    private static final URI ADOPTIUM_RELEASES_URI = URI.create(
            "https://api.adoptium.net/v3/info/available_releases");
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final Pattern TAG_PATTERN = Pattern.compile(
            "\\\"tag_name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern DOWNLOAD_PATTERN = Pattern.compile(
            "\\\"browser_download_url\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern VERSION_PATTERN = Pattern.compile(
            "^v?(\\d+(?:\\.\\d+){1,3})$");
    private static final Pattern SHA256_PATTERN = Pattern.compile(
            "(?i)\\b([0-9a-f]{64})\\b");
    private static final Pattern LTS_RELEASES_PATTERN = Pattern.compile(
            "\\\"available_lts_releases\\\"\\s*:\\s*\\[([^]]*)]");
    private static final Set<Integer> TESTED_LTS = Collections.unmodifiableSet(
            new HashSet<Integer>(Arrays.asList(17, 21)));
    private static final int RECOMMENDED_LTS = 21;
    private static final String[] ENGINE_FILES = {
            "update-runtime-dependencies.ps1",
            "runtime-dependencies.psd1",
            "apply-pending-runtime-update.ps1"
    };

    private final Path updaterRoot;
    private final Path applicationRoot;
    private final JFrame frame;
    private final JTextArea applicationOutput;
    private final JTextArea dependencyOutput;
    private final JButton applicationCheckButton;
    private final JButton applicationUpdateButton;
    private final JButton dependencyCheckButton;
    private final JButton dependencyUpdateButton;
    private final JComboBox<JavaChoice> javaChoice;
    private Release latestRelease;

    private NicoCacheUpdater(Path updaterRoot, Path applicationRoot) {
        this.updaterRoot = updaterRoot;
        this.applicationRoot = applicationRoot;
        frame = new JFrame("NicoCache_nl Updater");
        applicationOutput = createOutput();
        dependencyOutput = createOutput();
        applicationCheckButton = new JButton("更新を確認");
        applicationUpdateButton = new JButton("NicoCache_nlを更新");
        dependencyCheckButton = new JButton("更新を確認");
        dependencyUpdateButton = new JButton("更新可能な項目を適用");
        javaChoice = new JComboBox<JavaChoice>();
        buildUi();
    }

    private static JTextArea createOutput() {
        JTextArea output = new JTextArea();
        output.setEditable(false);
        output.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        output.setLineWrap(false);
        return output;
    }

    private void buildUi() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("NicoCache_nl", buildApplicationPanel());
        tabs.addTab("外部依存関係", buildDependencyPanel());
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.add(tabs, BorderLayout.CENTER);
        frame.setPreferredSize(new Dimension(900, 560));
        frame.pack();
        frame.setLocationRelativeTo(null);
        applicationUpdateButton.setEnabled(false);
        applicationCheckButton.addActionListener(event -> checkApplicationUpdate());
        applicationUpdateButton.addActionListener(event -> installApplicationUpdate());
        dependencyCheckButton.addActionListener(event -> runDependencyUpdater(false));
        dependencyUpdateButton.addActionListener(event -> runDependencyUpdater(true));
        javaChoice.setRenderer(new JavaChoiceRenderer());
        javaChoice.addActionListener(event -> {
            JavaChoice selected = (JavaChoice) javaChoice.getSelectedItem();
            if (selected != null && !selected.supported) {
                javaChoice.setSelectedItem(findRecommendedChoice());
            }
        });
        loadJavaChoices();
    }

    private JPanel buildApplicationPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.add(new JLabel("NicoCache_nl本体の最新版を確認し、SHA-256を検証して更新します。"),
                BorderLayout.NORTH);
        panel.add(new JScrollPane(applicationOutput), BorderLayout.CENTER);
        JPanel buttons = new JPanel();
        buttons.add(applicationCheckButton);
        buttons.add(applicationUpdateButton);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildDependencyPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        JPanel header = new JPanel();
        header.add(new JLabel("Temurin LTS:"));
        header.add(javaChoice);
        header.add(new JLabel("未対応LTSは自動表示されますが選択できません。"));
        panel.add(header, BorderLayout.NORTH);
        panel.add(new JScrollPane(dependencyOutput), BorderLayout.CENTER);
        JPanel buttons = new JPanel();
        buttons.add(dependencyCheckButton);
        buttons.add(dependencyUpdateButton);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private void checkApplicationUpdate() {
        setApplicationBusy(true);
        applicationOutput.setText("最新版を確認しています…\n");
        new SwingWorker<Release, Void>() {
            @Override protected Release doInBackground() throws Exception { return fetchLatestRelease(); }
            @Override protected void done() {
                try {
                    latestRelease = get();
                    String installed = readInstalledVersion();
                    applicationOutput.setText("対象: " + applicationRoot + "\n導入版: " + installed
                            + "\n最新版: " + latestRelease.version + "\n配布物: "
                            + latestRelease.msiUri + "\n");
                    applicationUpdateButton.setEnabled("不明".equals(installed)
                            || compareVersions(latestRelease.version, installed) > 0);
                    if (!applicationUpdateButton.isEnabled()) applicationOutput.append("既に最新版です。\n");
                } catch (Exception error) {
                    applicationOutput.append("確認に失敗しました: " + rootMessage(error) + "\n");
                } finally {
                    applicationCheckButton.setEnabled(true);
                }
            }
        }.execute();
    }

    private void installApplicationUpdate() {
        if (latestRelease == null) return;
        int answer = JOptionPane.showConfirmDialog(frame,
                "NicoCache_nl " + latestRelease.version + " をダウンロードして更新しますか？\n"
                        + "対象: " + applicationRoot + "\n実行中のNicoCache_nlは先に終了してください。",
                "NicoCache_nlの更新", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) return;
        setApplicationBusy(true);
        applicationOutput.append("配布物をダウンロードして検証しています…\n");
        new SwingWorker<Path, Void>() {
            @Override protected Path doInBackground() throws Exception { return downloadAndVerify(latestRelease); }
            @Override protected void done() {
                try {
                    Path msi = get();
                    new ProcessBuilder("msiexec.exe", "/i", msi.toString()).start();
                    applicationOutput.append("Windows Installerを起動しました。\n");
                } catch (Exception error) {
                    applicationOutput.append("更新準備に失敗しました: " + rootMessage(error) + "\n");
                } finally {
                    setApplicationBusy(false);
                }
            }
        }.execute();
    }

    private void runDependencyUpdater(boolean update) {
        JavaChoice selected = (JavaChoice) javaChoice.getSelectedItem();
        if (selected == null || !selected.supported) {
            JOptionPane.showMessageDialog(frame, "対応済みのTemurin LTSを選択してください。",
                    "未対応バージョン", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (update) {
            int answer = JOptionPane.showConfirmDialog(frame,
                    "NicoCache_nl管理下の依存関係だけを更新します。\n対象: " + applicationRoot
                            + "\nUpdater自身やシステムPATH上の依存関係は変更しません。",
                    "外部依存関係の更新", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.QUESTION_MESSAGE);
            if (answer != JOptionPane.OK_OPTION) return;
        }
        setDependencyBusy(true);
        dependencyOutput.setText(update ? "更新を準備しています…\n" : "更新を確認しています…\n");
        final int javaMajor = selected.major;
        new SwingWorker<CommandResult, Void>() {
            @Override protected CommandResult doInBackground() throws Exception {
                return invokeDependencyUpdater(update, javaMajor);
            }
            @Override protected void done() {
                try {
                    CommandResult result = get();
                    dependencyOutput.setText(result.output);
                    if (result.exitCode != 0) dependencyOutput.append("\n終了コード: " + result.exitCode + "\n");
                } catch (Exception error) {
                    dependencyOutput.append("\n起動に失敗しました: " + rootMessage(error) + "\n");
                } finally {
                    setDependencyBusy(false);
                }
            }
        }.execute();
    }

    private void loadJavaChoices() {
        javaChoice.setModel(new DefaultComboBoxModel<JavaChoice>(new JavaChoice[] {
                new JavaChoice(21, true, true), new JavaChoice(17, true, false) }));
        new SwingWorker<List<Integer>, Void>() {
            @Override protected List<Integer> doInBackground() throws Exception { return fetchAvailableLtsReleases(); }
            @Override protected void done() {
                try {
                    List<Integer> releases = get();
                    List<JavaChoice> choices = new ArrayList<JavaChoice>();
                    for (Integer major : releases) {
                        choices.add(new JavaChoice(major.intValue(), TESTED_LTS.contains(major),
                                major.intValue() == RECOMMENDED_LTS));
                    }
                    choices.sort(Comparator.comparingInt((JavaChoice value) -> value.major).reversed());
                    javaChoice.setModel(new DefaultComboBoxModel<JavaChoice>(choices.toArray(new JavaChoice[0])));
                    javaChoice.setSelectedItem(findRecommendedChoice());
                } catch (Exception error) {
                    dependencyOutput.append("Temurin LTS一覧の取得に失敗しました。内蔵一覧を使用します。\n");
                }
            }
        }.execute();
    }

    private JavaChoice findRecommendedChoice() {
        for (int index = 0; index < javaChoice.getItemCount(); index++) {
            JavaChoice choice = javaChoice.getItemAt(index);
            if (choice.recommended) return choice;
        }
        return javaChoice.getItemCount() == 0 ? null : javaChoice.getItemAt(0);
    }

    private Release fetchLatestRelease() throws IOException, InterruptedException {
        String json = sendText(RELEASE_URI).body();
        Matcher tagMatcher = TAG_PATTERN.matcher(json);
        if (!tagMatcher.find()) throw new IOException("release tag is missing");
        Matcher versionMatcher = VERSION_PATTERN.matcher(tagMatcher.group(1));
        if (!versionMatcher.matches()) throw new IOException("unsupported release tag: " + tagMatcher.group(1));
        URI msi = null;
        URI checksum = null;
        Matcher matcher = DOWNLOAD_PATTERN.matcher(json);
        while (matcher.find()) {
            String value = matcher.group(1).replace("\\/", "/");
            String lower = value.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".msi")) msi = URI.create(value);
            else if (lower.endsWith(".sha256") || lower.endsWith(".sha256.txt")) checksum = URI.create(value);
        }
        if (msi == null || checksum == null) throw new IOException("MSIまたはSHA-256がReleaseにありません");
        return new Release(versionMatcher.group(1), msi, checksum);
    }

    private List<Integer> fetchAvailableLtsReleases() throws IOException, InterruptedException {
        Matcher matcher = LTS_RELEASES_PATTERN.matcher(sendText(ADOPTIUM_RELEASES_URI).body());
        if (!matcher.find()) throw new IOException("available_lts_releases is missing");
        List<Integer> result = new ArrayList<Integer>();
        for (String value : matcher.group(1).split(",")) {
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) result.add(Integer.valueOf(trimmed));
        }
        return result;
    }

    private static HttpResponse<String> sendText(URI uri) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json").header("User-Agent", "NicoCache_nl Updater").build();
        HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode() + ": " + uri);
        return response;
    }

    private Path downloadAndVerify(Release release) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        Path directory = Files.createTempDirectory("NicoCache_nl-update-");
        Path partial = directory.resolve("NicoCache_nl.msi.download");
        Path msi = directory.resolve("NicoCache_nl-" + release.version + ".msi");
        HttpResponse<Path> binary = client.send(
                HttpRequest.newBuilder(release.msiUri).timeout(Duration.ofMinutes(5))
                        .header("User-Agent", "NicoCache_nl Updater").build(),
                HttpResponse.BodyHandlers.ofFile(partial));
        if (binary.statusCode() != 200) throw new IOException("MSI download returned HTTP " + binary.statusCode());
        Matcher checksumMatcher = SHA256_PATTERN.matcher(sendText(release.checksumUri).body());
        if (!checksumMatcher.find()) throw new IOException("SHA-256 value is missing");
        String expected = checksumMatcher.group(1).toLowerCase(Locale.ROOT);
        String actual = sha256(partial);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII))) {
            Files.deleteIfExists(partial);
            throw new IOException("MSIのSHA-256が一致しません");
        }
        Files.move(partial, msi, StandardCopyOption.REPLACE_EXISTING);
        return msi;
    }

    private Path engineDirectory() {
        return updaterRoot.resolve("app").resolve("extensions");
    }

    private void validateEngineLayout() throws IOException {
        Path engine = engineDirectory();
        for (String file : ENGINE_FILES) {
            if (!Files.isRegularFile(engine.resolve(file))) {
                throw new IOException("依存関係更新エンジンが見つかりません: " + engine.resolve(file));
            }
        }
        if (samePath(updaterRoot, applicationRoot) || samePath(engine, applicationRoot)) {
            throw new IOException("Updater自身とNicoCache_nl対象ルートが分離されていません");
        }
    }

    private CommandResult invokeDependencyUpdater(boolean update, int javaMajor)
            throws IOException, InterruptedException {
        validateEngineLayout();
        Path script = engineDirectory().resolve("update-runtime-dependencies.ps1");
        Files.createDirectories(applicationRoot);
        List<String> command = new ArrayList<String>();
        command.add(findPowerShell());
        command.add("-NoLogo");
        command.add("-NoProfile");
        command.add("-NonInteractive");
        command.add("-ExecutionPolicy");
        command.add("Bypass");
        command.add("-File");
        command.add(script.toString());
        command.add("-Mode");
        command.add(update ? "Update" : "Check");
        command.add("-ApplicationRoot");
        command.add(applicationRoot.toString());
        command.add("-JavaMajor");
        command.add(Integer.toString(javaMajor));
        command.add("-NonInteractive");
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(applicationRoot.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), Charset.defaultCharset()))) {
            String line;
            while ((line = reader.readLine()) != null) text.append(line).append(System.lineSeparator());
        }
        return new CommandResult(process.waitFor(), text.toString());
    }

    private String readInstalledVersion() {
        Path versionFile = applicationRoot.resolve("version.txt");
        try {
            if (Files.isRegularFile(versionFile)) {
                String value = Files.readString(versionFile, StandardCharsets.UTF_8).trim();
                Matcher matcher = VERSION_PATTERN.matcher(value);
                if (matcher.matches()) return matcher.group(1);
            }
        } catch (IOException ignored) { }
        return "不明";
    }

    private void setApplicationBusy(boolean busy) {
        applicationCheckButton.setEnabled(!busy);
        applicationUpdateButton.setEnabled(!busy && latestRelease != null);
    }

    private void setDependencyBusy(boolean busy) {
        dependencyCheckButton.setEnabled(!busy);
        dependencyUpdateButton.setEnabled(!busy);
        javaChoice.setEnabled(!busy);
    }

    private static String findPowerShell() {
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot != null) {
            File executable = Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toFile();
            if (executable.isFile()) return executable.getAbsolutePath();
        }
        return "powershell.exe";
    }

    private static Path detectUpdaterRoot() {
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath != null && !appPath.isBlank()) {
            Path parent = Path.of(appPath).toAbsolutePath().normalize().getParent();
            if (parent != null) return parent;
        }
        return Path.of(".").toAbsolutePath().normalize();
    }

    private static Path defaultApplicationRoot() {
        String programFiles = System.getenv("ProgramFiles");
        return programFiles == null ? Path.of("NicoCache_nl").toAbsolutePath().normalize()
                : Path.of(programFiles, "NicoCache_nl").toAbsolutePath().normalize();
    }

    private static boolean samePath(Path left, Path right) {
        return left.toAbsolutePath().normalize().equals(right.toAbsolutePath().normalize());
    }

    private static String argument(String[] args, String name) {
        for (int i = 0; i + 1 < args.length; i++) if (name.equals(args[i])) return args[i + 1];
        return null;
    }

    private static boolean hasArgument(String[] args, String name) {
        for (String arg : args) if (name.equals(arg)) return true;
        return false;
    }

    private static int intArgument(String[] args, String name, int fallback) {
        String value = argument(args, name);
        return value == null ? fallback : Integer.parseInt(value);
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (java.io.InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
            }
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) result.append(String.format("%02x", value & 0xff));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static int compareVersions(String left, String right) {
        String[] leftParts = left.split("\\.");
        String[] rightParts = right.split("\\.");
        int length = Math.max(leftParts.length, rightParts.length);
        for (int index = 0; index < length; index++) {
            int leftValue = index < leftParts.length ? Integer.parseInt(leftParts[index]) : 0;
            int rightValue = index < rightParts.length ? Integer.parseInt(rightParts[index]) : 0;
            if (leftValue != rightValue) return Integer.compare(leftValue, rightValue);
        }
        return 0;
    }

    private static String rootMessage(Exception error) {
        Throwable value = error;
        while (value.getCause() != null) value = value.getCause();
        return value.getMessage() == null ? value.toString() : value.getMessage();
    }

    public static void main(String[] args) {
        Path updaterRoot = argument(args, "--updater-root") == null ? detectUpdaterRoot()
                : Path.of(argument(args, "--updater-root")).toAbsolutePath().normalize();
        Path applicationRoot = argument(args, "--app-root") == null ? defaultApplicationRoot()
                : Path.of(argument(args, "--app-root")).toAbsolutePath().normalize();
        if (hasArgument(args, "--self-test") || hasArgument(args, "--dependency-check")) {
            try {
                NicoCacheUpdater updater = new NicoCacheUpdater(updaterRoot, applicationRoot);
                updater.validateEngineLayout();
                if (hasArgument(args, "--dependency-check")) {
                    CommandResult result = updater.invokeDependencyUpdater(false,
                            intArgument(args, "--java-major", RECOMMENDED_LTS));
                    System.out.print(result.output);
                    if (result.exitCode != 0) System.exit(result.exitCode);
                } else {
                    System.out.println("SELF_TEST_OK updaterRoot=" + updaterRoot
                            + " applicationRoot=" + applicationRoot
                            + " engineRoot=" + updater.engineDirectory());
                }
                updater.frame.dispose();
                return;
            } catch (Exception error) {
                System.err.println("SELF_TEST_FAILED: " + rootMessage(error));
                System.exit(1);
                return;
            }
        }
        SwingUtilities.invokeLater(() -> new NicoCacheUpdater(updaterRoot, applicationRoot).frame.setVisible(true));
    }

    private static final class Release {
        final String version;
        final URI msiUri;
        final URI checksumUri;
        Release(String version, URI msiUri, URI checksumUri) {
            this.version = version;
            this.msiUri = msiUri;
            this.checksumUri = checksumUri;
        }
    }

    private static final class CommandResult {
        final int exitCode;
        final String output;
        CommandResult(int exitCode, String output) { this.exitCode = exitCode; this.output = output; }
    }

    private static final class JavaChoice {
        final int major;
        final boolean supported;
        final boolean recommended;
        JavaChoice(int major, boolean supported, boolean recommended) {
            this.major = major;
            this.supported = supported;
            this.recommended = recommended;
        }
        @Override public String toString() {
            String suffix = recommended ? "（推奨）" : supported ? "" : "（未対応）";
            return "Java " + major + " LTS" + suffix;
        }
        @Override public boolean equals(Object other) {
            return other instanceof JavaChoice && ((JavaChoice) other).major == major;
        }
        @Override public int hashCode() { return major; }
    }

    private static final class JavaChoiceRenderer extends DefaultListCellRenderer {
        @Override public Component getListCellRendererComponent(javax.swing.JList<?> list,
                Object value, int index, boolean selected, boolean focus) {
            Component component = super.getListCellRendererComponent(list, value, index, selected, focus);
            if (value instanceof JavaChoice && !((JavaChoice) value).supported) component.setEnabled(false);
            return component;
        }
    }
}
