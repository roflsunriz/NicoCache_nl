package dareka;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.ComponentOrientation;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.lang.reflect.InvocationTargetException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;

import dareka.common.Logger;

final class FirstRunWizard {
    private FirstRunWizard() {
    }

    static boolean showAndApply(FirstRunSetupService service, Locale locale) {
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
                    new FirstRunWizardPanel.Listener() {
                        @Override
                        public void apply(SetupOptions options) {
                            dialog.setDefaultCloseOperation(
                                    WindowConstants.DO_NOTHING_ON_CLOSE);
                            panelReference[0].setBusy(true);
                            new SwingWorker<Void, Void>() {
                                @Override
                                protected Void doInBackground() throws Exception {
                                    service.apply(options);
                                    return null;
                                }

                                @Override
                                protected void done() {
                                    try {
                                        get();
                                        completed.set(true);
                                        dialog.dispose();
                                    } catch (Exception error) {
                                        dialog.setDefaultCloseOperation(
                                                WindowConstants.DISPOSE_ON_CLOSE);
                                        panelReference[0].setBusy(false);
                                        Throwable cause = error.getCause() == null
                                                ? error : error.getCause();
                                        JOptionPane.showMessageDialog(
                                                dialog,
                                                messages.text("error.apply")
                                                        + System.lineSeparator()
                                                        + cause.getMessage(),
                                                messages.text("error.title"),
                                                JOptionPane.ERROR_MESSAGE);
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
}

final class FirstRunWizardPanel extends JPanel {
    private static final long serialVersionUID = 1L;

    interface Listener {
        void apply(SetupOptions options);

        void cancel();
    }

    private final SetupMessages messages;
    private final Listener listener;
    private final CardLayout cards = new CardLayout();
    private final JPanel cardPanel = new JPanel(cards);
    private final JButton backButton;
    private final JButton nextButton;
    private final JButton cancelButton;
    private final JButton applyButton;
    private final JCheckBox httpsCheckBox;
    private final JCheckBox proxyCheckBox;
    private final JCheckBox autoStartCheckBox;
    private final JTextArea summary;
    private final JLabel stepLabel;
    private final JLabel busyLabel;
    private int step;

    FirstRunWizardPanel(SetupMessages messages, Locale locale,
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

        httpsCheckBox = checkBox(
                "setup.https",
                messages.text("option.https"),
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
            proxyCheckBox.setEnabled(enabled);
            if (!enabled) {
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

        cardPanel.add(welcomePanel(), "0");
        cardPanel.add(optionsPanel(), "1");
        cardPanel.add(summaryPanel(), "2");
        add(cardPanel, BorderLayout.CENTER);

        backButton = button("setup.back", messages.text("button.back"));
        nextButton = button("setup.next", messages.text("button.next"));
        cancelButton = button("setup.cancel", messages.text("button.cancel"));
        applyButton = button("setup.apply", messages.text("button.apply"));
        backButton.addActionListener(event -> showStep(step - 1));
        nextButton.addActionListener(event -> showStep(step + 1));
        cancelButton.addActionListener(event -> listener.cancel());
        applyButton.addActionListener(event -> listener.apply(options()));

        busyLabel = new JLabel(" ");
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING, 8, 0));
        buttons.add(busyLabel);
        buttons.add(cancelButton);
        buttons.add(backButton);
        buttons.add(nextButton);
        buttons.add(applyButton);
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

    private JPanel optionsPanel() {
        JPanel panel = verticalPanel();
        panel.add(paragraph(messages.text("options.body")));
        panel.add(Box.createVerticalStrut(16));
        panel.add(httpsCheckBox);
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

    private void showStep(int requestedStep) {
        step = Math.max(0, Math.min(2, requestedStep));
        if (step == 2) {
            updateSummary();
        }
        cards.show(cardPanel, Integer.toString(step));
        stepLabel.setText(messages.text("step." + (step + 1)));
        backButton.setEnabled(step > 0);
        nextButton.setVisible(step < 2);
        applyButton.setVisible(step == 2);
    }

    private void updateSummary() {
        StringBuilder text = new StringBuilder();
        appendChoice(text, httpsCheckBox.isSelected(),
                messages.text("summary.https.on"),
                messages.text("summary.https.off"));
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
                httpsCheckBox.isSelected(),
                proxyCheckBox.isSelected(),
                autoStartCheckBox.isSelected());
    }

    void setBusy(boolean busy) {
        backButton.setEnabled(!busy && step > 0);
        nextButton.setEnabled(!busy);
        cancelButton.setEnabled(!busy);
        applyButton.setEnabled(!busy);
        httpsCheckBox.setEnabled(!busy);
        proxyCheckBox.setEnabled(!busy && httpsCheckBox.isSelected());
        autoStartCheckBox.setEnabled(!busy);
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

    JCheckBox getHttpsCheckBox() {
        return httpsCheckBox;
    }

    JCheckBox getProxyCheckBox() {
        return proxyCheckBox;
    }

    JCheckBox getAutoStartCheckBox() {
        return autoStartCheckBox;
    }

    JTextArea getSummary() {
        return summary;
    }

    private static boolean isRightToLeft(Locale locale) {
        String language = locale.getLanguage();
        return "ar".equals(language) || "ur".equals(language);
    }
}
