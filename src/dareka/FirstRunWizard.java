package dareka;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.ComponentOrientation;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;

import dareka.common.Logger;

final class FirstRunWizard {
    private FirstRunWizard() {
    }

    static boolean showAndApply(
            Path appDirectory, Path defaultDataDirectory, Locale locale) {
        AtomicBoolean completed = new AtomicBoolean(false);
        Runnable show = () -> {
            SetupMessages messages = new SetupMessages(locale);
            JDialog dialog = new JDialog(
                    (Window) null,
                    messages.text("window.title"),
                    JDialog.ModalityType.APPLICATION_MODAL);
            FirstRunWizardPanel[] panelReference =
                    new FirstRunWizardPanel[1];
            FirstRunWizardPanel panel = new FirstRunWizardPanel(
                    messages,
                    locale,
                    defaultDataDirectory,
                    new FirstRunWizardPanel.Listener() {
                        @Override
                        public void apply(SetupOptions options) {
                            dialog.setDefaultCloseOperation(
                                    WindowConstants.DO_NOTHING_ON_CLOSE);
                            panelReference[0].setBusy(true);
                            new SwingWorker<Void, Void>() {
                                @Override
                                protected Void doInBackground() throws Exception {
                                    FirstRunSetupService.production(
                                            appDirectory,
                                            options.getUserDataRoot())
                                            .apply(options);
                                    return null;
                                }

                                @Override
                                protected void done() {
                                    try {
                                        get();
                                        completed.set(true);
                                        dialog.setDefaultCloseOperation(
                                                WindowConstants.DISPOSE_ON_CLOSE);
                                        panelReference[0].showResult(
                                                options, null);
                                    } catch (Exception error) {
                                        dialog.setDefaultCloseOperation(
                                                WindowConstants.DISPOSE_ON_CLOSE);
                                        Throwable cause = error.getCause() == null
                                                ? error : error.getCause();
                                        panelReference[0].showResult(
                                                options, cause);
                                    }
                                }
                            }.execute();
                        }

                        @Override
                        public void cancel() {
                            dialog.dispose();
                        }
                    });
            panelReference[0] = panel;
            dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            dialog.setContentPane(panel);
            dialog.setMinimumSize(new Dimension(600, 430));
            dialog.setPreferredSize(new Dimension(720, 500));
            dialog.pack();
            dialog.setLocationRelativeTo(null);
            dialog.setVisible(true);
        };

        try {
            if (SwingUtilities.isEventDispatchThread()) {
                show.run();
            } else {
                SwingUtilities.invokeAndWait(show);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        } catch (InvocationTargetException error) {
            Logger.error(error.getCause());
            return false;
        }
        return completed.get();
    }
    static final class FirstRunWizardPanel extends JPanel {
    private static final long serialVersionUID = 1L;

    interface Listener {
        void apply(SetupOptions options);

        void cancel();
    }

    private final transient SetupMessages messages;
    private final transient Listener listener;
    private final CardLayout cards = new CardLayout();
    private final JPanel cardPanel = new JPanel(cards);
    private final JButton backButton;
    private final JButton nextButton;
    private final JButton cancelButton;
    private final JButton applyButton;
    private final JButton finishButton;
    private final JButton browseButton;
    private final JTextField dataRootField;
    private final JLabel dataRootError;
    private final JCheckBox httpsCheckBox;
    private final JCheckBox certificateCheckBox;
    private final JCheckBox proxyCheckBox;
    private final JCheckBox autoStartCheckBox;
    private final JTextArea summary;
    private final JTextArea resultBody;
    private final JTextArea resultSummary;
    private final JLabel stepLabel;
    private final JLabel busyLabel;
    private int step;

    FirstRunWizardPanel(SetupMessages messages, Locale locale,
            Path defaultDataDirectory,
            Listener listener) {
        super(new BorderLayout(16, 16));
        setName("setup.root");
        this.messages = messages;
        this.listener = listener;
        setBorder(BorderFactory.createEmptyBorder(20, 24, 16, 24));
        setPreferredSize(new Dimension(720, 500));
        JLabel title = new JLabel(messages.text("window.title"));
        title.setName("setup.title");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 22.0f));
        stepLabel = new JLabel();
        stepLabel.setName("setup.step");
        JPanel heading = new JPanel(new BorderLayout(0, 6));
        heading.add(title, BorderLayout.NORTH);
        heading.add(stepLabel, BorderLayout.SOUTH);
        add(heading, BorderLayout.NORTH);

        dataRootField = new JTextField(
                defaultDataDirectory.toAbsolutePath().normalize().toString());
        dataRootField.setName("setup.dataRoot");
        dataRootField.getAccessibleContext().setAccessibleName(
                "setup.dataRoot");
        browseButton = button(
                "setup.dataRoot.browse",
                messages.text("button.browse"));
        browseButton.addActionListener(event -> chooseDataRoot());
        dataRootError = new JLabel(" ");
        dataRootError.setName("setup.dataRoot.error");

        httpsCheckBox = checkBox(
                "setup.https",
                messages.text("option.https"),
                true);
        certificateCheckBox = checkBox(
                "setup.ca",
                messages.text("option.ca"),
                true);
        proxyCheckBox = checkBox(
                "setup.proxy",
                messages.text("option.proxy"),
                true);
        autoStartCheckBox = checkBox(
                "setup.autostart",
                messages.text("option.autostart"),
                true);
        httpsCheckBox.addItemListener(event -> {
            boolean enabled = httpsCheckBox.isSelected();
            certificateCheckBox.setEnabled(enabled);
            proxyCheckBox.setEnabled(enabled);
            if (!enabled) {
                certificateCheckBox.setSelected(false);
                proxyCheckBox.setSelected(false);
            }
        });

        summary = new JTextArea();
        summary.setName("setup.summary");
        summary.setEditable(false);
        summary.setLineWrap(true);
        summary.setWrapStyleWord(true);
        summary.setOpaque(false);
        summary.setFont(summary.getFont().deriveFont(15.0f));

        resultBody = paragraph("");
        resultBody.setName("setup.result.body");
        resultSummary = new JTextArea();
        resultSummary.setName("setup.result.summary");
        resultSummary.setEditable(false);
        resultSummary.setLineWrap(true);
        resultSummary.setWrapStyleWord(true);
        resultSummary.setFont(resultSummary.getFont().deriveFont(15.0f));

        cardPanel.add(welcomePanel(), "0");
        cardPanel.add(dataRootPanel(), "1");
        cardPanel.add(optionsPanel(), "2");
        cardPanel.add(summaryPanel(), "3");
        cardPanel.add(resultPanel(), "4");
        add(cardPanel, BorderLayout.CENTER);

        backButton = button("setup.back", messages.text("button.back"));
        nextButton = button("setup.next", messages.text("button.next"));
        cancelButton = button("setup.cancel", messages.text("button.cancel"));
        applyButton = button("setup.apply", messages.text("button.apply"));
        finishButton = button("setup.finish", messages.text("button.finish"));
        backButton.addActionListener(event -> showStep(step - 1));
        nextButton.addActionListener(event -> showNextStep());
        cancelButton.addActionListener(event -> listener.cancel());
        applyButton.addActionListener(event -> listener.apply(options()));
        finishButton.addActionListener(event -> listener.cancel());

        busyLabel = new JLabel(" ");
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING, 8, 0));
        buttons.add(busyLabel);
        buttons.add(cancelButton);
        buttons.add(backButton);
        buttons.add(nextButton);
        buttons.add(applyButton);
        buttons.add(finishButton);
        add(buttons, BorderLayout.SOUTH);
        applyComponentOrientation(isRightToLeft(locale)
                ? ComponentOrientation.RIGHT_TO_LEFT
                : ComponentOrientation.LEFT_TO_RIGHT);
        showStep(0);
    }

    private JPanel welcomePanel() {
        JPanel panel = verticalPanel();
        panel.add(paragraph(messages.text("welcome.body")));
        panel.add(Box.createVerticalStrut(18));
        panel.add(paragraph(messages.text("welcome.safety")));
        panel.add(Box.createVerticalGlue());
        return panel;
    }

    private JPanel dataRootPanel() {
        JPanel panel = verticalPanel();
        panel.add(paragraph(messages.text("dataRoot.body")));
        panel.add(Box.createVerticalStrut(16));
        JLabel label = new JLabel(messages.text("dataRoot.label"));
        label.setLabelFor(dataRootField);
        panel.add(label);
        panel.add(Box.createVerticalStrut(6));
        JPanel input = new JPanel(new BorderLayout(8, 0));
        input.setAlignmentX(LEFT_ALIGNMENT);
        input.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        input.add(dataRootField, BorderLayout.CENTER);
        input.add(browseButton, BorderLayout.LINE_END);
        panel.add(input);
        panel.add(Box.createVerticalStrut(6));
        panel.add(dataRootError);
        panel.add(Box.createVerticalGlue());
        return panel;
    }

    private JPanel optionsPanel() {
        JPanel panel = verticalPanel();
        panel.add(paragraph(messages.text("options.body")));
        panel.add(Box.createVerticalStrut(16));
        panel.add(httpsCheckBox);
        panel.add(Box.createVerticalStrut(10));
        panel.add(certificateCheckBox);
        panel.add(Box.createVerticalStrut(10));
        panel.add(proxyCheckBox);
        panel.add(Box.createVerticalStrut(10));
        panel.add(autoStartCheckBox);
        panel.add(Box.createVerticalGlue());
        return panel;
    }

    private JPanel summaryPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(paragraph(messages.text("summary.body")), BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(summary);
        scroll.setBorder(BorderFactory.createEmptyBorder(16, 0, 0, 0));
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    private JPanel resultPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(resultBody, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(resultSummary);
        scroll.setBorder(BorderFactory.createEmptyBorder(16, 0, 0, 0));
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    private JPanel verticalPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        return panel;
    }

    private JTextArea paragraph(String text) {
        JTextArea paragraph = new JTextArea(text);
        paragraph.setEditable(false);
        paragraph.setLineWrap(true);
        paragraph.setWrapStyleWord(true);
        paragraph.setOpaque(false);
        paragraph.setFont(paragraph.getFont().deriveFont(15.0f));
        paragraph.setAlignmentX(LEFT_ALIGNMENT);
        paragraph.setRows(3);
        paragraph.setMaximumSize(new Dimension(
                Integer.MAX_VALUE,
                paragraph.getPreferredSize().height));
        return paragraph;
    }

    private static JCheckBox checkBox(String name, String text,
            boolean selected) {
        JCheckBox checkBox = new JCheckBox(text, selected);
        checkBox.setName(name);
        checkBox.getAccessibleContext().setAccessibleName(name);
        checkBox.setFont(checkBox.getFont().deriveFont(15.0f));
        return checkBox;
    }

    private static JButton button(String name, String text) {
        JButton button = new JButton(text);
        button.setName(name);
        button.getAccessibleContext().setAccessibleName(name);
        return button;
    }

    private void chooseDataRoot() {
        JFileChooser chooser = new JFileChooser(dataRootField.getText());
        chooser.setDialogTitle(messages.text("dataRoot.dialog"));
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            dataRootField.setText(
                    chooser.getSelectedFile().toPath()
                            .toAbsolutePath().normalize().toString());
            validateDataRoot();
        }
    }

    private void showNextStep() {
        if (step == 1 && !validateDataRoot()) {
            return;
        }
        showStep(step + 1);
    }

    private boolean validateDataRoot() {
        try {
            Path selected = Path.of(dataRootField.getText().trim());
            if (!selected.isAbsolute()) {
                dataRootError.setText(
                        messages.text("dataRoot.error.absolute"));
                return false;
            }
            dataRootField.setText(
                    selected.toAbsolutePath().normalize().toString());
            dataRootError.setText(" ");
            return true;
        } catch (InvalidPathException error) {
            dataRootError.setText(messages.text("dataRoot.error.invalid"));
            return false;
        }
    }

    private void showStep(int requestedStep) {
        step = Math.max(0, Math.min(3, requestedStep));
        if (step == 3) {
            updateSummary();
        }
        cards.show(cardPanel, Integer.toString(step));
        stepLabel.setText(messages.text("step." + (step + 1)));
        backButton.setVisible(true);
        backButton.setEnabled(step > 0);
        nextButton.setVisible(step < 3);
        cancelButton.setVisible(true);
        applyButton.setVisible(step == 3);
        finishButton.setVisible(false);
    }

    private void updateSummary() {
        StringBuilder text = new StringBuilder();
        text.append(messages.text("summary.dataRoot"))
                .append(' ')
                .append(dataRootField.getText());
        appendChoice(text, httpsCheckBox.isSelected(),
                messages.text("summary.https.on"),
                messages.text("summary.https.off"));
        appendChoice(text, certificateCheckBox.isSelected(),
                messages.text("summary.ca.on"),
                messages.text("summary.ca.off"));
        appendChoice(text, proxyCheckBox.isSelected(),
                messages.text("summary.proxy.on"),
                messages.text("summary.proxy.off"));
        appendChoice(text, autoStartCheckBox.isSelected(),
                messages.text("summary.autostart.on"),
                messages.text("summary.autostart.off"));
        if (httpsCheckBox.isSelected()) {
            text.append(System.lineSeparator())
                    .append(messages.text("summary.firefox"));
        }
        summary.setText(text.toString());
        summary.setCaretPosition(0);
    }

    private static void appendChoice(StringBuilder text, boolean selected,
            String enabledText, String disabledText) {
        if (text.length() > 0) {
            text.append(System.lineSeparator());
        }
        text.append(selected ? enabledText : disabledText);
    }

    private SetupOptions options() {
        return new SetupOptions(
                Path.of(dataRootField.getText().trim()),
                httpsCheckBox.isSelected(),
                certificateCheckBox.isSelected(),
                proxyCheckBox.isSelected(),
                autoStartCheckBox.isSelected());
    }

    void showResult(SetupOptions appliedOptions, Throwable error) {
        boolean successful = error == null;
        setBusy(false);
        resultBody.setText(messages.text(successful
                ? "result.body.success"
                : "result.body.failure"));
        resultBody.setCaretPosition(0);

        StringBuilder text = new StringBuilder();
        appendResult(text, messages.text("result.option.https"),
                appliedOptions.isHttpsEnabled(), successful);
        appendResult(text, messages.text("result.option.ca"),
                appliedOptions.isCertificateTrusted(), successful);
        appendResult(text, messages.text("result.option.proxy"),
                appliedOptions.isProxyConfigured(), successful);
        appendResult(text, messages.text("result.option.autostart"),
                appliedOptions.isAutoStartEnabled(), successful);
        if (!successful) {
            String detail = error.getMessage();
            if (detail == null || detail.isBlank()) {
                detail = error.getClass().getSimpleName();
            }
            text.append(System.lineSeparator())
                    .append(System.lineSeparator())
                    .append(messages.text("result.error"))
                    .append(' ')
                    .append(detail);
        }
        resultSummary.setText(text.toString());
        resultSummary.setCaretPosition(0);

        step = 4;
        cards.show(cardPanel, "4");
        stepLabel.setText(messages.text("step.5"));
        busyLabel.setText(" ");
        backButton.setVisible(!successful);
        backButton.setEnabled(!successful);
        nextButton.setVisible(false);
        cancelButton.setVisible(false);
        applyButton.setVisible(false);
        finishButton.setVisible(true);
        finishButton.setEnabled(true);
    }

    private void appendResult(StringBuilder text, String option,
            boolean selected, boolean successful) {
        if (text.length() > 0) {
            text.append(System.lineSeparator());
        }
        String status;
        if (!selected) {
            status = messages.text("result.status.skipped");
        } else if (successful) {
            status = messages.text("result.status.success");
        } else {
            status = messages.text("result.status.failure");
        }
        text.append(option).append(": ").append(status);
    }

    void setBusy(boolean busy) {
        backButton.setEnabled(!busy && step > 0);
        nextButton.setEnabled(!busy);
        cancelButton.setEnabled(!busy);
        applyButton.setEnabled(!busy);
        finishButton.setEnabled(!busy);
        httpsCheckBox.setEnabled(!busy);
        certificateCheckBox.setEnabled(!busy && httpsCheckBox.isSelected());
        proxyCheckBox.setEnabled(!busy && httpsCheckBox.isSelected());
        autoStartCheckBox.setEnabled(!busy);
        dataRootField.setEnabled(!busy);
        browseButton.setEnabled(!busy);
        busyLabel.setText(busy ? messages.text("status.applying") : " ");
    }

    int getStep() {
        return step;
    }

    JButton getBackButton() {
        return backButton;
    }

    JButton getNextButton() {
        return nextButton;
    }

    JButton getCancelButton() {
        return cancelButton;
    }

    JButton getApplyButton() {
        return applyButton;
    }

    JButton getFinishButton() {
        return finishButton;
    }

    JCheckBox getHttpsCheckBox() {
        return httpsCheckBox;
    }

    JCheckBox getCertificateCheckBox() {
        return certificateCheckBox;
    }

    JCheckBox getProxyCheckBox() {
        return proxyCheckBox;
    }

    JCheckBox getAutoStartCheckBox() {
        return autoStartCheckBox;
    }

    JTextField getDataRootField() {
        return dataRootField;
    }

    JLabel getDataRootError() {
        return dataRootError;
    }

    JTextArea getSummary() {
        return summary;
    }

    JTextArea getResultBody() {
        return resultBody;
    }

    JTextArea getResultSummary() {
        return resultSummary;
    }

    private static boolean isRightToLeft(Locale locale) {
        String language = locale.getLanguage();
        return "ar".equals(language) || "ur".equals(language);
    }
    }
}
