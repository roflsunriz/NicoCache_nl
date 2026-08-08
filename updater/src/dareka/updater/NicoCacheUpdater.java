package dareka.updater;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

/** Standalone updater for NicoCache_nl and user-wide command-line dependencies. */
public final class NicoCacheUpdater {
    private static final String[] DEPENDENCY_IDS = {
        "temurin", "ffmpeg", "bouncycastle", "ant", "7zip", "gpac"
    };
    private static final URI RELEASE_URI = URI.create(
            "https://api.github.com/repos/roflsunriz/NicoCache_nl/releases/latest");
    private static final URI ADOPTIUM_RELEASES_URI = URI.create(
            "https://api.adoptium.net/v3/info/available_releases");
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final Pattern TAG_PATTERN = Pattern.compile("\\\"tag_name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern DOWNLOAD_PATTERN = Pattern.compile(
            "\\\"browser_download_url\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern VERSION_PATTERN = Pattern.compile("^v?(\\d+(?:\\.\\d+){1,3})$");
    private static final Pattern SHA256_PATTERN = Pattern.compile("(?i)\\b([0-9a-f]{64})\\b");
    private static final Pattern LTS_RELEASES_PATTERN = Pattern.compile(
            "\\\"available_lts_releases\\\"\\s*:\\s*\\[([^]]*)]");
    private static final Set<Integer> TESTED_LTS = Collections.unmodifiableSet(
            new HashSet<Integer>(Arrays.asList(17, 21, 25)));
    private static final int RECOMMENDED_LTS = 25;

    private Path applicationRoot;
    private final JFrame frame = new JFrame("NicoCache_nl Updater");
    private final JLabel targetRootLabel = new JLabel();
    private final JTextArea applicationOutput = createOutput();
    private final JTextArea dependencyOutput = createOutput();
    private final JButton applicationCheckButton = new JButton("更新を確認");
    private final JButton applicationUpdateButton = new JButton("NicoCache_nlを更新");
    private final JButton dependencyCheckButton = new JButton("全てチェック");
    private final JButton dependencyUpdateButton = new JButton("全てインストール");
    private final JButton changeTargetButton = new JButton("変更…");
    private final JComboBox<JavaChoice> javaChoice = new JComboBox<JavaChoice>();
    private final JPanel dependencyRowsPanel = new JPanel();
    private final Map<String, DependencyRow> dependencyRows =
            new LinkedHashMap<String, DependencyRow>();
    private final Map<String, DependencyStatus> dependencyStatuses =
            new LinkedHashMap<String, DependencyStatus>();
    private Release latestRelease;
    private boolean applicationUpdateAvailable;

    private NicoCacheUpdater(Path applicationRoot) {
        this.applicationRoot = applicationRoot.toAbsolutePath().normalize();
        buildUi();
        refreshTargetLabel();
    }

    private static JTextArea createOutput() {
        JTextArea output = new JTextArea();
        output.setEditable(false);
        output.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        output.setLineWrap(false);
        return output;
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        JPanel targetPanel = new JPanel(new BorderLayout(8, 0));
        targetPanel.add(targetRootLabel, BorderLayout.CENTER);
        targetPanel.add(changeTargetButton, BorderLayout.EAST);
        root.add(targetPanel, BorderLayout.NORTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("NicoCache_nl", buildApplicationPanel());
        tabs.addTab("外部依存関係", buildDependencyPanel());
        root.add(tabs, BorderLayout.CENTER);

        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.add(root, BorderLayout.CENTER);
        frame.setPreferredSize(new Dimension(940, 620));
        frame.pack();
        frame.setLocationRelativeTo(null);
        applicationUpdateButton.setEnabled(false);
        applicationCheckButton.addActionListener(event -> checkApplicationUpdate());
        applicationUpdateButton.addActionListener(event -> installApplicationUpdate());
        dependencyCheckButton.addActionListener(event -> checkAllDependencies());
        dependencyUpdateButton.addActionListener(event -> installCheckedDependencies());
        dependencyCheckButton.setName("dependency.checkAll");
        dependencyUpdateButton.setName("dependency.installAll");
        changeTargetButton.addActionListener(event -> chooseTargetRoot());
        javaChoice.setRenderer(new JavaChoiceRenderer());
        javaChoice.addActionListener(event -> {
            JavaChoice selected = (JavaChoice) javaChoice.getSelectedItem();
            if (selected != null && !selected.supported) javaChoice.setSelectedItem(findRecommendedChoice());
            dependencyStatuses.clear();
            resetDependencyRows();
        });
        loadJavaChoices();
        dependencyUpdateButton.setEnabled(false);
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
        JPanel header = new JPanel(new BorderLayout(8, 4));
        JPanel javaPanel = new JPanel();
        javaPanel.add(new JLabel("Temurin LTS:"));
        javaPanel.add(javaChoice);
        header.add(javaPanel, BorderLayout.WEST);
        header.add(new JLabel("各行の確認結果を確認してから、必要な依存関係だけをインストールします。"),
                BorderLayout.CENTER);
        panel.add(header, BorderLayout.NORTH);
        dependencyRowsPanel.setLayout(new BoxLayout(dependencyRowsPanel, BoxLayout.Y_AXIS));
        for (String id : DEPENDENCY_IDS) {
            DependencyRow row = new DependencyRow(id, dependencyDisplayName(id));
            dependencyRows.put(id, row);
            dependencyRowsPanel.add(row.panel);
            dependencyRowsPanel.add(Box.createVerticalStrut(6));
        }
        dependencyRowsPanel.add(Box.createVerticalGlue());
        panel.add(new JScrollPane(dependencyRowsPanel), BorderLayout.CENTER);
        dependencyOutput.setText("「全てチェック」または各行の「更新チェック」で最新版を確認してください。\n"
                + "インストール後は自動的に再確認し、再起動なしで各行のステータスを更新します。\n");
        JScrollPane outputScroll = new JScrollPane(dependencyOutput);
        outputScroll.setPreferredSize(new Dimension(10, 115));
        JPanel footer = new JPanel(new BorderLayout(8, 6));
        footer.add(outputScroll, BorderLayout.CENTER);
        JPanel buttons = new JPanel();
        buttons.add(dependencyCheckButton);
        buttons.add(dependencyUpdateButton);
        footer.add(buttons, BorderLayout.SOUTH);
        panel.add(footer, BorderLayout.SOUTH);
        return panel;
    }

    private void refreshTargetLabel() {
        String status = TargetRootResolver.isInstallation(applicationRoot) ? "検出済み" : "未インストール";
        targetRootLabel.setText("NicoCache_nl本体: " + applicationRoot + "（" + status + "）");
    }

    private void chooseTargetRoot() {
        JFileChooser chooser = new JFileChooser(applicationRoot.toFile());
        chooser.setDialogTitle("NicoCache_nlのインストール先を選択");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        try {
            Path selected = TargetRootResolver.requireInstallation(chooser.getSelectedFile().toPath());
            TargetRootResolver.remember(selected);
            applicationRoot = selected;
            latestRelease = null;
            applicationUpdateAvailable = false;
            applicationUpdateButton.setEnabled(false);
            applicationOutput.setText("更新対象を変更しました: " + applicationRoot + "\n");
            refreshTargetLabel();
        } catch (IOException error) {
            JOptionPane.showMessageDialog(frame, error.getMessage(), "更新対象を変更できません",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void checkApplicationUpdate() {
        applicationUpdateAvailable = false;
        setApplicationBusy(true);
        applicationOutput.setText("最新版を確認しています…\n");
        new SwingWorker<Release, Void>() {
            @Override protected Release doInBackground() throws Exception { return fetchLatestRelease(); }
            @Override protected void done() {
                try {
                    latestRelease = get();
                    String installed = InstalledVersionDetector.detect(applicationRoot);
                    applicationOutput.setText("対象: " + applicationRoot + "\n導入版: " + installed
                            + "\n最新版: " + latestRelease.version + "\n配布物: " + latestRelease.packageName + "\n");
                    applicationUpdateAvailable = "不明".equals(installed)
                            || compareVersions(latestRelease.version, installed) > 0;
                    applicationUpdateButton.setEnabled(applicationUpdateAvailable);
                    if (!applicationUpdateAvailable) applicationOutput.append("既に最新版です。\n");
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
                "NicoCache_nlの更新", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) return;
        setApplicationBusy(true);
        applicationOutput.append("配布物をダウンロードして検証しています…\n");
        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() throws Exception {
                Path packageFile = downloadAndVerify(latestRelease);
                try {
                    return applyApplicationPackage(packageFile, applicationRoot);
                } finally {
                    deleteDownloadedPackage(packageFile);
                }
            }
            @Override protected void done() {
                try {
                    String result = get();
                    applicationOutput.append(result);
                    applicationOutput.append("\n");
                    Path previousRoot = applicationRoot;
                    Path resolvedRoot = TargetRootResolver.resolveAfterApplicationUpdate(previousRoot);
                    ApplicationUpdateCompletion completion = inspectCompletedApplicationUpdate(
                            resolvedRoot, latestRelease == null ? null : latestRelease.version);
                    applicationRoot = completion.applicationRoot;
                    applicationUpdateAvailable = completion.updateAvailable;
                    if (!previousRoot.equals(applicationRoot)) {
                        dependencyStatuses.clear();
                        resetDependencyRows();
                    }
                    refreshTargetLabel();
                    applicationOutput.append(completion.output);
                    if (!applicationUpdateAvailable) applicationOutput.append("最新版を反映しました。\n");
                } catch (Exception error) {
                    applicationOutput.append("更新準備に失敗しました: " + rootMessage(error) + "\n");
                } finally {
                    setApplicationBusy(false);
                }
            }
        }.execute();
    }

    private int selectedJavaMajor() {
        JavaChoice selected = (JavaChoice) javaChoice.getSelectedItem();
        if (selected == null || !selected.supported) return -1;
        return selected.major;
    }

    private void checkAllDependencies() {
        int javaMajor = selectedJavaMajor();
        if (javaMajor < 0) {
            showUnsupportedJavaWarning();
            return;
        }
        dependencyStatuses.clear();
        resetDependencyRows();
        setDependencyBusy(true);
        dependencyOutput.setText("全ての外部依存関係の最新版を確認しています…\n");
        new SwingWorker<List<DependencyStatus>, Void>() {
            @Override protected List<DependencyStatus> doInBackground() throws Exception {
                return new DependencyEngine(applicationRoot).inspectAll(javaMajor);
            }

            @Override protected void done() {
                try {
                    List<DependencyStatus> statuses = get();
                    updateDependencyRows(statuses);
                    dependencyOutput.setText("全ての更新チェックが完了しました。\n"
                            + countUpdates(statuses) + "件の更新があります。\n");
                } catch (Exception error) {
                    dependencyOutput.setText("全ての更新チェックに失敗しました: "
                            + rootMessage(error) + "\n");
                } finally {
                    setDependencyBusy(false);
                }
            }
        }.execute();
    }

    private void checkDependency(String dependencyId) {
        int javaMajor = selectedJavaMajor();
        if (javaMajor < 0) {
            showUnsupportedJavaWarning();
            return;
        }
        dependencyStatuses.remove(dependencyId);
        DependencyRow row = dependencyRows.get(dependencyId);
        if (row != null) row.status.setText("確認中…");
        refreshDependencyButtons();
        setDependencyBusy(true);
        dependencyOutput.setText(dependencyDisplayName(dependencyId)
                + "の最新版を確認しています…\n");
        new SwingWorker<DependencyStatus, Void>() {
            @Override protected DependencyStatus doInBackground() throws Exception {
                return new DependencyEngine(applicationRoot)
                        .inspectDependency(dependencyId, javaMajor);
            }

            @Override protected void done() {
                try {
                    DependencyStatus status = get();
                    updateDependencyRows(java.util.Collections.singletonList(status));
                    dependencyOutput.setText(dependencyDisplayName(dependencyId)
                            + "の更新チェックが完了しました。\n");
                } catch (Exception error) {
                    dependencyOutput.setText(dependencyDisplayName(dependencyId)
                            + "の更新チェックに失敗しました: " + rootMessage(error) + "\n");
                } finally {
                    setDependencyBusy(false);
                }
            }
        }.execute();
    }

    private void installDependency(String dependencyId) {
        DependencyStatus status = dependencyStatuses.get(dependencyId);
        if (status == null || !status.canInstall()) return;
        int answer = JOptionPane.showConfirmDialog(frame,
                status.displayName + "を " + status.latestLabel() + " に更新しますか？",
                "外部依存関係のインストール", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) return;
        installDependencies(java.util.Collections.singletonList(dependencyId));
    }

    private void installCheckedDependencies() {
        List<String> ids = new ArrayList<String>();
        for (DependencyStatus status : dependencyStatuses.values()) {
            if (status.canInstall()) ids.add(status.id);
        }
        if (ids.isEmpty()) {
            dependencyOutput.setText("インストール対象がありません。先に更新チェックを行い、\n"
                    + "新バージョンがある依存関係を確認してください。\n");
            return;
        }
        StringBuilder names = new StringBuilder();
        for (String id : ids) {
            if (names.length() > 0) names.append('、');
            names.append(dependencyStatuses.get(id).displayName);
        }
        int answer = JOptionPane.showConfirmDialog(frame,
                names + "をインストールしますか？\n"
                        + "更新チェック済みで新バージョンがある項目だけを対象にします。",
                "外部依存関係の一括インストール", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE);
        if (answer == JOptionPane.OK_OPTION) installDependencies(ids);
    }

    private void installDependencies(List<String> ids) {
        int javaMajor = selectedJavaMajor();
        if (javaMajor < 0) {
            showUnsupportedJavaWarning();
            return;
        }
        setDependencyBusy(true);
        dependencyOutput.setText("外部依存関係をインストールしています…\n");
        new SwingWorker<DependencyOperationResult, Void>() {
            @Override protected DependencyOperationResult doInBackground() throws Exception {
                DependencyEngine engine = new DependencyEngine(applicationRoot);
                StringBuilder output = new StringBuilder();
                for (String id : ids) {
                    try {
                        output.append(engine.installDependency(id, javaMajor));
                    } catch (Exception error) {
                        output.append(dependencyDisplayName(id)).append("のインストールに失敗しました: ")
                                .append(rootMessage(error)).append('\n');
                    }
                }
                return new DependencyOperationResult(output.toString(),
                        engine.inspectAll(javaMajor));
            }

            @Override protected void done() {
                try {
                    DependencyOperationResult result = get();
                    updateDependencyRows(result.statuses);
                    dependencyOutput.setText(result.output
                            + "インストール後の更新チェックが完了しました。\n");
                } catch (Exception error) {
                    dependencyOutput.setText("インストール後の確認に失敗しました: "
                            + rootMessage(error) + "\n");
                } finally {
                    setDependencyBusy(false);
                }
            }
        }.execute();
    }

    private void updateDependencyRows(List<DependencyStatus> statuses) {
        for (DependencyStatus status : statuses) {
            dependencyStatuses.put(status.id, status);
            DependencyRow row = dependencyRows.get(status.id);
            if (row != null) {
                row.status.setText("導入版: " + status.installedLabel()
                        + " / 最新版: " + status.latestLabel() + " / " + status.message);
            }
        }
        refreshDependencyButtons();
    }

    private void resetDependencyRows() {
        for (DependencyRow row : dependencyRows.values()) {
            row.status.setText("未確認");
        }
        refreshDependencyButtons();
    }

    private void refreshDependencyButtons() {
        boolean busy = !dependencyCheckButton.isEnabled();
        for (DependencyRow row : dependencyRows.values()) {
            DependencyStatus status = dependencyStatuses.get(row.id);
            row.check.setEnabled(!busy);
            row.install.setEnabled(!busy && status != null && status.canInstall());
        }
        dependencyUpdateButton.setEnabled(!busy && dependencyStatuses.values().stream()
                .anyMatch(DependencyStatus::canInstall));
    }

    private void showUnsupportedJavaWarning() {
        JOptionPane.showMessageDialog(frame, "対応済みのTemurin LTSを選択してください。",
                "未対応バージョン", JOptionPane.WARNING_MESSAGE);
    }

    private static int countUpdates(List<DependencyStatus> statuses) {
        int count = 0;
        for (DependencyStatus status : statuses) if (status.updateAvailable) count++;
        return count;
    }

    private static String dependencyDisplayName(String id) {
        if ("temurin".equals(id)) return "Eclipse Temurin JDK";
        if ("ffmpeg".equals(id)) return "FFmpeg";
        if ("bouncycastle".equals(id)) return "Bouncy Castle";
        if ("ant".equals(id)) return "Apache Ant";
        if ("7zip".equals(id)) return "7-Zip";
        if ("gpac".equals(id)) return "GPAC / MP4Box";
        return id;
    }

    private void loadJavaChoices() {
        javaChoice.setModel(new DefaultComboBoxModel<JavaChoice>(new JavaChoice[] {
                new JavaChoice(25, true, true), new JavaChoice(21, true, false),
                new JavaChoice(17, true, false)
        }));
        new SwingWorker<List<Integer>, Void>() {
            @Override protected List<Integer> doInBackground() throws Exception { return fetchAvailableLtsReleases(); }
            @Override protected void done() {
                try {
                    List<JavaChoice> choices = new ArrayList<JavaChoice>();
                    for (Integer major : get()) {
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
        return parseRelease(sendText(RELEASE_URI).body());
    }

    private static Release parseRelease(String json) throws IOException {
        return parseRelease(json, UpdaterPlatform.current());
    }

    private static Release parseRelease(String json, UpdaterPlatform.Kind platform)
            throws IOException {
        Matcher tagMatcher = TAG_PATTERN.matcher(json);
        if (!tagMatcher.find()) throw new IOException("release tag is missing");
        Matcher versionMatcher = VERSION_PATTERN.matcher(tagMatcher.group(1));
        if (!versionMatcher.matches()) throw new IOException("unsupported release tag: " + tagMatcher.group(1));
        String version = versionMatcher.group(1);
        String packageName = packageName(version, platform);
        String checksumName = packageName + ".sha256";
        URI packageUri = null;
        URI checksum = null;
        Matcher matcher = DOWNLOAD_PATTERN.matcher(json);
        while (matcher.find()) {
            String value = matcher.group(1).replace("\\/", "/");
            URI asset = URI.create(value);
            String path = asset.getPath();
            String name = path.substring(path.lastIndexOf('/') + 1);
            if (name.equalsIgnoreCase(packageName)) {
                packageUri = asset;
            } else if (name.equalsIgnoreCase(checksumName)
                    || name.equalsIgnoreCase(checksumName + ".txt")) {
                checksum = asset;
            }
        }
        if (packageUri == null || checksum == null) {
            throw new IOException("NicoCache_nl本体の" + packageDescription(platform)
                    + "またはSHA-256がReleaseにありません");
        }
        return new Release(version, packageUri, checksum, packageName,
                platform == UpdaterPlatform.Kind.LINUX || platform == UpdaterPlatform.Kind.MACOS);
    }

    private static String packageName(String version, UpdaterPlatform.Kind platform)
            throws IOException {
        switch (platform) {
        case WINDOWS:
            return "NicoCache_nl-" + version + ".msi";
        case LINUX:
        case MACOS:
            return "NicoCache_nl-" + version + "-"
                    + UpdaterPlatform.platformId(platform) + "-"
                    + UpdaterPlatform.architecture() + ".zip";
        case OTHER:
        default:
            throw new IOException("未対応のOSではReleaseを選択できません");
        }
    }

    private static String packageDescription(UpdaterPlatform.Kind platform) {
        switch (platform) {
        case WINDOWS: return "MSI";
        case LINUX: return "LinuxアプリイメージZIP";
        case MACOS: return "macOSアプリイメージZIP";
        case OTHER:
        default: return "対応配布物";
        }
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
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json").header("User-Agent", "NicoCache_nl Updater");
        if ("api.github.com".equalsIgnoreCase(uri.getHost())) {
            String token = System.getenv("GITHUB_TOKEN");
            if (token != null && !token.isBlank()) builder.header("Authorization", "Bearer " + token);
            builder.header("X-GitHub-Api-Version", "2022-11-28");
        }
        HttpResponse<String> response = client.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode() + ": " + uri);
        return response;
    }

    private static Path downloadAndVerify(Release release) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        Path directory = Files.createTempDirectory("NicoCache_nl-update-");
        Path partial = directory.resolve(release.packageName + ".download");
        Path packageFile = directory.resolve(release.packageName);
        HttpResponse<Path> binary = client.send(
                HttpRequest.newBuilder(release.packageUri).timeout(Duration.ofMinutes(5))
                        .header("User-Agent", "NicoCache_nl Updater").build(),
                HttpResponse.BodyHandlers.ofFile(partial));
        if (binary.statusCode() != 200) {
            throw new IOException(packageDescription(UpdaterPlatform.current())
                    + " download returned HTTP " + binary.statusCode());
        }
        Matcher checksumMatcher = SHA256_PATTERN.matcher(sendText(release.checksumUri).body());
        if (!checksumMatcher.find()) throw new IOException("SHA-256 value is missing");
        String expected = checksumMatcher.group(1).toLowerCase(Locale.ROOT);
        String actual = sha256(partial);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII))) {
            Files.deleteIfExists(partial);
            throw new IOException("配布物のSHA-256が一致しません");
        }
        Files.move(partial, packageFile, StandardCopyOption.REPLACE_EXISTING);
        return packageFile;
    }

    private static String applyApplicationPackage(Path packageFile, Path root)
            throws Exception {
        ApplicationProcessGuard.requireStopped(root);
        if (UpdaterPlatform.current() == UpdaterPlatform.Kind.WINDOWS) {
            Process installer = new ProcessBuilder("msiexec.exe", "/i", packageFile.toString()).start();
            if (!installer.waitFor(30, java.util.concurrent.TimeUnit.MINUTES)) {
                installer.destroyForcibly();
                throw new IOException("Windows Installerが時間内に終了しませんでした");
            }
            if (installer.exitValue() != 0 && installer.exitValue() != 1641
                    && installer.exitValue() != 3010) {
                throw new IOException("Windows Installerが失敗しました (ExitCode: "
                        + installer.exitValue() + ")");
            }
            return "Windows Installerで更新しました。";
        }
        ArchiveApplicationInstaller.install(packageFile, root, UpdaterPlatform.current());
        return "アプリイメージを更新しました。";
    }

    private static void deleteDownloadedPackage(Path packageFile) {
        if (packageFile == null) return;
        try {
            Path directory = packageFile.getParent();
            Files.deleteIfExists(packageFile);
            if (directory != null) Files.deleteIfExists(directory);
        } catch (IOException ignored) {
            // A failed cleanup must not hide the update result.
        }
    }

    private void setApplicationBusy(boolean busy) {
        applicationCheckButton.setEnabled(!busy);
        applicationUpdateButton.setEnabled(!busy && applicationUpdateAvailable);
        changeTargetButton.setEnabled(!busy);
    }

    private void setDependencyBusy(boolean busy) {
        dependencyCheckButton.setEnabled(!busy);
        dependencyUpdateButton.setEnabled(!busy && dependencyStatuses.values().stream()
                .anyMatch(DependencyStatus::canInstall));
        javaChoice.setEnabled(!busy);
        changeTargetButton.setEnabled(!busy);
        for (DependencyRow row : dependencyRows.values()) {
            DependencyStatus status = dependencyStatuses.get(row.id);
            row.check.setEnabled(!busy);
            row.install.setEnabled(!busy && status != null && status.canInstall());
        }
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

    static String headlessApplicationCheck(Path root) throws Exception {
        Path applicationRoot = TargetRootResolver.requireInstallation(root);
        Release release = fetchLatestReleaseHeadless();
        String installed = InstalledVersionDetector.detect(applicationRoot);
        boolean update = "不明".equals(installed)
                || compareVersions(release.version, installed) > 0;
        return "対象: " + applicationRoot + "\n導入版: " + installed
                + "\n最新版: " + release.version + "\n配布物: " + release.packageName
                + "\n" + (update ? "更新があります。" : "既に最新版です。") + "\n";
    }

    static String headlessApplicationUpdate(Path root) throws Exception {
        Path applicationRoot = TargetRootResolver.requireInstallation(root);
        Release release = fetchLatestReleaseHeadless();
        String installed = InstalledVersionDetector.detect(applicationRoot);
        if (!"不明".equals(installed)
                && compareVersions(release.version, installed) <= 0) {
            return "既に最新版です: " + installed + System.lineSeparator();
        }
        Path packageFile = null;
        try {
            packageFile = downloadAndVerify(release);
            String result = applyApplicationPackage(packageFile, applicationRoot);
            Path resolvedRoot = TargetRootResolver.resolveAfterApplicationUpdate(applicationRoot);
            ApplicationUpdateCompletion completion = inspectCompletedApplicationUpdate(
                    resolvedRoot, release.version);
            return formatHeadlessApplicationUpdateResult(result, completion);
        } finally {
            deleteDownloadedPackage(packageFile);
        }
    }

    static ApplicationUpdateCompletion inspectCompletedApplicationUpdate(
            Path applicationRoot, String latestVersion) {
        Path normalizedRoot = applicationRoot.toAbsolutePath().normalize();
        String installed = InstalledVersionDetector.detect(normalizedRoot);
        boolean update = "不明".equals(installed) || latestVersion == null
                || compareVersions(latestVersion, installed) > 0;
        String output = "更新後の対象: " + normalizedRoot + System.lineSeparator()
                + "更新後の導入版: " + installed + System.lineSeparator();
        return new ApplicationUpdateCompletion(normalizedRoot, installed, update, output);
    }

    static String formatHeadlessApplicationUpdateResult(String result,
            ApplicationUpdateCompletion completion) {
        return result + System.lineSeparator() + completion.output;
    }

    private static Release fetchLatestReleaseHeadless()
            throws IOException, InterruptedException {
        return parseRelease(sendText(RELEASE_URI).body());
    }

    public static void main(String[] args) {
        if (java.awt.GraphicsEnvironment.isHeadless()
                || java.util.Arrays.asList(args).contains("--headless")) {
            System.err.println("GUIを表示できません。ヘッドレス実行では --application-check、"
                    + "--application-update、--dependency-check などを指定してください。");
            return;
        }
        Path applicationRoot = TargetRootResolver.resolve(UpdaterLauncher.argument(args, "--app-root"));
        SwingUtilities.invokeLater(() -> new NicoCacheUpdater(applicationRoot).frame.setVisible(true));
    }

    private static final class DependencyOperationResult {
        final String output;
        final List<DependencyStatus> statuses;
        DependencyOperationResult(String output, List<DependencyStatus> statuses) {
            this.output = output;
            this.statuses = statuses;
        }
    }

    static final class ApplicationUpdateCompletion {
        final Path applicationRoot;
        final String installedVersion;
        final boolean updateAvailable;
        final String output;

        ApplicationUpdateCompletion(Path applicationRoot, String installedVersion,
                boolean updateAvailable, String output) {
            this.applicationRoot = applicationRoot;
            this.installedVersion = installedVersion;
            this.updateAvailable = updateAvailable;
            this.output = output;
        }
    }

    private final class DependencyRow {
        final String id;
        final JPanel panel = new JPanel(new BorderLayout(8, 2));
        final JLabel status = new JLabel("未確認");
        final JButton check = new JButton("更新チェック");
        final JButton install = new JButton("インストール");

        DependencyRow(String id, String name) {
            this.id = id;
            panel.setBorder(javax.swing.BorderFactory.createCompoundBorder(
                    javax.swing.BorderFactory.createEtchedBorder(),
                    javax.swing.BorderFactory.createEmptyBorder(6, 8, 6, 8)));
            JPanel labels = new JPanel();
            labels.setLayout(new BoxLayout(labels, BoxLayout.Y_AXIS));
            JLabel title = new JLabel(name);
            title.setFont(title.getFont().deriveFont(Font.BOLD));
            labels.add(title);
            status.setName("dependency." + id + ".status");
            labels.add(status);
            panel.add(labels, BorderLayout.CENTER);
            JPanel buttons = new JPanel();
            check.setName("dependency." + id + ".check");
            install.setName("dependency." + id + ".install");
            buttons.add(check);
            buttons.add(install);
            panel.add(buttons, BorderLayout.EAST);
            check.addActionListener(event -> checkDependency(this.id));
            install.addActionListener(event -> installDependency(this.id));
            install.setEnabled(false);
        }
    }

    private static final class Release {
        final String version;
        final URI packageUri;
        final URI msiUri;
        final URI checksumUri;
        final String packageName;
        final boolean archive;
        Release(String version, URI packageUri, URI checksumUri, String packageName,
                boolean archive) {
            this.version = version;
            this.packageUri = packageUri;
            this.msiUri = archive ? null : packageUri;
            this.checksumUri = checksumUri;
            this.packageName = packageName;
            this.archive = archive;
        }
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

    @SuppressWarnings("serial")
    private static final class JavaChoiceRenderer extends DefaultListCellRenderer {
        @Override public Component getListCellRendererComponent(javax.swing.JList<?> list,
                Object value, int index, boolean selected, boolean focus) {
            Component component = super.getListCellRendererComponent(list, value, index, selected, focus);
            if (value instanceof JavaChoice && !((JavaChoice) value).supported) component.setEnabled(false);
            return component;
        }
    }
}
