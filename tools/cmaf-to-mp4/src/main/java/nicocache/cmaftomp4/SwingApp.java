package nicocache.cmaftomp4;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;

/** Swingによる対話画面。同じ変換コアをヘッドレスCLIと共有する。 */
final class SwingApp {
    private final JTextField inputField = new JTextField();
    private final JTextField outputField = new JTextField();
    private final JTextField ffmpegField = new JTextField(ConversionRequest.defaultFfmpeg());
    private final JTextField titleField = new JTextField();
    private final JCheckBox forceCheck = new JCheckBox(Messages.get("gui.force"));
    private final JButton convertButton = new JButton(Messages.get("gui.convert"));
    private final JButton cancelButton = new JButton(Messages.get("gui.cancel"));
    private final JButton openOutputButton = new JButton(Messages.get("gui.open-output"));
    private final JCheckBox openAfterCheck = new JCheckBox(Messages.get("gui.open-after"));
    private final JLabel statusLabel = new JLabel(Messages.get("gui.ready"));
    private final JTextArea logArea = new JTextArea();
    private final JFrame frame = new JFrame(Messages.get("gui.title"));
    private final AtomicBoolean cancellation = new AtomicBoolean(false);
    private SwingWorker<Void, String> activeWorker;
    private boolean closing;

    void show() {
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.setMinimumSize(new Dimension(640, 430));
        frame.setContentPane(createContent());
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                if (activeWorker != null && !activeWorker.isDone()) {
                    closing = true;
                    cancellation.set(true);
                    statusLabel.setText(Messages.get("gui.cancelling"));
                    return;
                }
                frame.dispose();
            }
        });
        frame.pack();
        frame.setLocationByPlatform(true);
        frame.setVisible(true);
    }

    private JPanel createContent() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        root.add(createForm(), BorderLayout.NORTH);

        logArea.setEditable(false);
        logArea.setLineWrap(false);
        logArea.setWrapStyleWord(false);
        logArea.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        root.add(new JScrollPane(logArea), BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(8, 8));
        bottom.add(statusLabel, BorderLayout.CENTER);
        JPanel actions = new JPanel(new GridLayout(1, 2, 8, 0));
        cancelButton.setEnabled(false);
        actions.add(convertButton);
        actions.add(cancelButton);
        bottom.add(actions, BorderLayout.EAST);
        root.add(bottom, BorderLayout.SOUTH);

        convertButton.addActionListener(event -> startConversion());
        cancelButton.addActionListener(event -> cancelConversion());
        openOutputButton.addActionListener(event -> openOutputDirectory());
        installInputDropTarget();
        return root;
    }

    private JPanel createForm() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createTitledBorder(Messages.get("gui.input-section")));
        addPathRow(form, 0, Messages.get("gui.input"), inputField, Messages.get("gui.browse"), true);
        addTextRow(form, 1, Messages.get("gui.output"), outputField);
        JPanel outputActions = new JPanel(new GridLayout(1, 2, 4, 0));
        JButton saveOutputButton = new JButton(Messages.get("gui.save-as"));
        saveOutputButton.addActionListener(event -> choosePath(outputField, false));
        outputActions.add(saveOutputButton);
        outputActions.add(openOutputButton);
        GridBagConstraints outputButtons = new GridBagConstraints();
        outputButtons.gridx = 2;
        outputButtons.gridy = 1;
        outputButtons.weightx = 0;
        outputButtons.insets = new Insets(4, 4, 4, 4);
        form.add(outputActions, outputButtons);
        addPathRow(form, 2, Messages.get("gui.ffmpeg"), ffmpegField, Messages.get("gui.browse"), false);
        addTextRow(form, 3, Messages.get("gui.title-field"), titleField);
        GridBagConstraints check = new GridBagConstraints();
        check.gridx = 1;
        check.gridy = 4;
        check.gridwidth = 2;
        check.anchor = GridBagConstraints.WEST;
        check.insets = new Insets(4, 4, 4, 4);
        form.add(forceCheck, check);
        GridBagConstraints openAfter = new GridBagConstraints();
        openAfter.gridx = 1;
        openAfter.gridy = 5;
        openAfter.gridwidth = 2;
        openAfter.anchor = GridBagConstraints.WEST;
        openAfter.insets = new Insets(4, 4, 4, 4);
        form.add(openAfterCheck, openAfter);
        return form;
    }

    private void addPathRow(
            JPanel form, int row, String label, JTextField field, String buttonText, boolean input) {
        addTextRow(form, row, label, field);
        JButton browse = new JButton(buttonText);
        browse.addActionListener(event -> choosePath(field, input));
        GridBagConstraints button = new GridBagConstraints();
        button.gridx = 2;
        button.gridy = row;
        button.weightx = 0;
        button.insets = new Insets(4, 4, 4, 4);
        form.add(browse, button);
    }

    private void addTextRow(JPanel form, int row, String label, JTextField field) {
        GridBagConstraints labelConstraints = new GridBagConstraints();
        labelConstraints.gridx = 0;
        labelConstraints.gridy = row;
        labelConstraints.anchor = GridBagConstraints.WEST;
        labelConstraints.insets = new Insets(4, 4, 4, 4);
        form.add(new JLabel(label), labelConstraints);

        field.setName("cmaf-to-mp4-" + row);
        GridBagConstraints fieldConstraints = new GridBagConstraints();
        fieldConstraints.gridx = 1;
        fieldConstraints.gridy = row;
        fieldConstraints.weightx = 1;
        fieldConstraints.fill = GridBagConstraints.HORIZONTAL;
        fieldConstraints.insets = new Insets(4, 4, 4, 4);
        form.add(field, fieldConstraints);
    }

    private void choosePath(JTextField field, boolean input) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(input ? Messages.get("gui.choose-input") : Messages.get("gui.choose-output"));
        chooser.setFileSelectionMode(
                input ? JFileChooser.DIRECTORIES_ONLY : JFileChooser.FILES_ONLY);
        if (!field.getText().trim().isEmpty()) {
            try {
                chooser.setSelectedFile(Paths.get(field.getText().trim()).toFile());
            } catch (InvalidPathException ignored) {
                // 不正な入力は選択ダイアログで安全に置き換えられる。
            }
        }
        int result = input ? chooser.showOpenDialog(frame) : chooser.showSaveDialog(frame);
        if (result != JFileChooser.APPROVE_OPTION) {
            return;
        }
        field.setText(chooser.getSelectedFile().toPath().toString());
        if (input && outputField.getText().trim().isEmpty()) {
            updateDefaultOutput();
        }
    }

    private void installInputDropTarget() {
        inputField.setToolTipText(Messages.get("gui.drop-hint"));
        new DropTarget(inputField, DnDConstants.ACTION_COPY, new DropTargetAdapter() {
            @Override
            public void drop(DropTargetDropEvent event) {
                if (!event.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    event.rejectDrop();
                    return;
                }
                event.acceptDrop(DnDConstants.ACTION_COPY);
                try {
                    @SuppressWarnings("unchecked")
                    List<File> files = (List<File>) event.getTransferable()
                            .getTransferData(DataFlavor.javaFileListFlavor);
                    if (files.size() != 1 || !files.get(0).isDirectory()) {
                        appendLog(Messages.get("gui.drop-folder-only"));
                        event.dropComplete(false);
                        return;
                    }
                    inputField.setText(files.get(0).toPath().toString());
                    updateDefaultOutput();
                    event.dropComplete(true);
                } catch (UnsupportedFlavorException | IOException | RuntimeException e) {
                    appendLog(Messages.format("gui.drop-failed", e.getMessage()));
                    event.dropComplete(false);
                }
            }
        });
    }

    private void updateDefaultOutput() {
        try {
            outputField.setText(CacheLocator.defaultOutput(
                    CacheLocator.locatePlaylist(pathFrom(inputField, Messages.get("gui.input")))).toString());
        } catch (Exception e) {
            appendLog(e.getMessage());
        }
    }

    private void startConversion() {
        final Path playlist;
        final Path output;
        try {
            playlist = CacheLocator.locatePlaylist(pathFrom(inputField, Messages.get("gui.input")));
            output = outputField.getText().trim().isEmpty()
                    ? CacheLocator.defaultOutput(playlist)
                    : pathFrom(outputField, Messages.get("gui.output"));
        } catch (Exception e) {
            showError(e.getMessage());
            return;
        }
        if (outputField.getText().trim().isEmpty()) {
            outputField.setText(output.toString());
        }
        String title = titleField.getText().trim();
        ConversionRequest request = new ConversionRequest(
                playlist,
                output,
                ffmpegField.getText().trim(),
                forceCheck.isSelected(),
                title);
        cancellation.set(false);
        setBusy(true);
        appendLog(Messages.format("conversion.input", playlist));
        appendLog(Messages.format("conversion.output", output));

        activeWorker = new SwingWorker<Void, String>() {
            @Override
            protected Void doInBackground() throws Exception {
                new FfmpegConverter().convert(request, new ConversionListener() {
                    @Override
                    public void onStarted(List<String> command) {
                        publish(Messages.get("gui.running"));
                    }

                    @Override
                    public void onOutput(String line) {
                        if (line != null && !line.trim().isEmpty()) {
                            publish("[ffmpeg] " + line);
                        }
                    }

                    @Override
                    public void onFinished(Path completedOutput) {
                    }
                }, cancellation::get);
                return null;
            }

            @Override
            protected void process(List<String> chunks) {
                for (String chunk : chunks) {
                    appendLog(chunk);
                }
            }

            @Override
            protected void done() {
                try {
                    get();
                    statusLabel.setText(Messages.get("gui.success"));
                    appendLog(Messages.format("gui.created", output));
                    if (openAfterCheck.isSelected()) {
                        openOutputDirectory(output);
                    }
                } catch (CancellationException e) {
                    statusLabel.setText(Messages.get("gui.cancelled"));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    showError(Messages.get("error.interrupted"));
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    showError(cause == null ? e.getMessage() : cause.getMessage());
                } finally {
                    activeWorker = null;
                    setBusy(false);
                    if (closing) {
                        frame.dispose();
                    }
                }
            }
        };
        activeWorker.execute();
    }

    private void cancelConversion() {
        if (activeWorker == null || activeWorker.isDone()) {
            return;
        }
        cancellation.set(true);
        cancelButton.setEnabled(false);
        statusLabel.setText(Messages.get("gui.cancelling"));
    }

    private Path pathFrom(JTextField field, String label) throws InvalidPathException {
        String value = field.getText().trim();
        if (value.isEmpty()) {
            throw new InvalidPathException(value, Messages.format("error.field-required", label));
        }
        return Paths.get(value);
    }

    private void setBusy(boolean busy) {
        convertButton.setEnabled(!busy);
        cancelButton.setEnabled(busy);
        inputField.setEnabled(!busy);
        outputField.setEnabled(!busy);
        ffmpegField.setEnabled(!busy);
        titleField.setEnabled(!busy);
        forceCheck.setEnabled(!busy);
        openAfterCheck.setEnabled(!busy);
        openOutputButton.setEnabled(!busy);
    }

    private void openOutputDirectory() {
        try {
            Path output = outputField.getText().trim().isEmpty()
                    ? CacheLocator.defaultOutput(CacheLocator.locatePlaylist(
                            pathFrom(inputField, Messages.get("gui.input"))))
                    : pathFrom(outputField, Messages.get("gui.output"));
            openOutputDirectory(output);
        } catch (Exception e) {
            showError(e.getMessage());
        }
    }

    private void openOutputDirectory(Path output) {
        try {
            OutputOpener.openOutputDirectory(output);
            statusLabel.setText(Messages.get("gui.output-opened"));
        } catch (Exception e) {
            appendLog(Messages.get("gui.error-prefix") + " " + e.getMessage());
        }
    }

    private void appendLog(String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        logArea.append(message + System.lineSeparator());
        int limit = 100_000;
        if (logArea.getDocument().getLength() > limit) {
            logArea.replaceRange("", 0, logArea.getDocument().getLength() - limit);
        }
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private void showError(String message) {
        statusLabel.setText(Messages.get("gui.failure"));
        appendLog(Messages.get("gui.error-prefix") + " " + message);
    }
}
