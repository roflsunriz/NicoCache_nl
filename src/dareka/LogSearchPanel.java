package dareka;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.DefaultCaret;

/**
 * 1つのログタブに対応する検索UIと表示更新を管理する。
 */
final class LogSearchPanel {
    private static final DateTimeFormatter HISTORY_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.systemDefault());

    final JPanel component = new JPanel(new BorderLayout());
    final JComboBox<LogSearchHistory.Entry> historyCombo =
            new JComboBox<>();
    final JTextField queryField;
    final JCheckBox regexCheckBox = new JCheckBox("正規表現");
    final JCheckBox caseSensitiveCheckBox = new JCheckBox("大/小文字を区別");
    final JButton clearButton = new JButton("解除");
    final JLabel statusLabel = new JLabel("0 行");

    private final JTextArea textArea;
    private final LogBuffer buffer;
    private final LogSearchHistory history;
    private final String historyKey;
    private final Timer refreshTimer;
    private final Color normalQueryBackground;
    private boolean updatingControls;
    private LogSearchHistory.Entry selectedHistoryEntry;
    private LogFilter.Result lastResult =
            new LogFilter.Result("", 0, 0, null);

    LogSearchPanel(JTextArea textArea, JComponent logView, LogBuffer buffer,
            LogSearchHistory history, String historyKey,
            String componentPrefix) {
        this.textArea = textArea;
        this.buffer = buffer;
        this.history = history;
        this.historyKey = historyKey;

        historyCombo.setEditable(true);
        queryField = (JTextField) historyCombo.getEditor()
                .getEditorComponent();
        normalQueryBackground = queryField.getBackground();
        refreshTimer = new Timer(75, event -> refreshNow());
        refreshTimer.setRepeats(false);

        configureIdentities(componentPrefix);
        configureLayout();
        configureHistoryRenderer();
        configureListeners();
        configureKeyboardActions();
        component.add(logView, BorderLayout.CENTER);
        reloadHistory();
        refreshNow();
    }

    JComponent getComponent() {
        return component;
    }

    boolean isFilterActive() {
        return !queryField.getText().isEmpty();
    }

    void requestRefresh() {
        if (!refreshTimer.isRunning()) {
            refreshTimer.start();
        }
    }

    void refreshNow() {
        refreshTimer.stop();
        lastResult = LogFilter.apply(
                buffer.getText(),
                queryField.getText(),
                regexCheckBox.isSelected(),
                caseSensitiveCheckBox.isSelected());
        replaceDisplayedText(lastResult.getText());
        updateStatus(lastResult);
    }

    void commitCurrentSearch() {
        refreshNow();
        if (selectedHistoryEntry != null
                && selectedHistoryEntry.getQuery()
                        .equals(queryField.getText())
                && selectedHistoryEntry.isRegularExpression()
                        == regexCheckBox.isSelected()
                && selectedHistoryEntry.isCaseSensitive()
                        == caseSensitiveCheckBox.isSelected()) {
            return;
        }
        if (!queryField.getText().isBlank() && lastResult.isValid()) {
            history.record(
                    historyKey,
                    queryField.getText(),
                    regexCheckBox.isSelected(),
                    caseSensitiveCheckBox.isSelected());
            selectedHistoryEntry = null;
            reloadHistory();
        }
    }

    void dispose() {
        commitCurrentSearch();
        refreshTimer.stop();
    }

    void clearQuery() {
        selectedHistoryEntry = null;
        queryField.setText("");
        refreshNow();
        queryField.requestFocusInWindow();
    }

    private void configureIdentities(String prefix) {
        component.setName(prefix + ".panel");
        setAccessibleName(component, prefix + ".panel");
        historyCombo.setName(prefix + ".history");
        setAccessibleName(historyCombo, prefix + ".history");
        queryField.setName(prefix + ".query");
        setAccessibleName(queryField, prefix + ".query");
        regexCheckBox.setName(prefix + ".regex");
        setAccessibleName(regexCheckBox, prefix + ".regex");
        caseSensitiveCheckBox.setName(prefix + ".case-sensitive");
        setAccessibleName(
                caseSensitiveCheckBox, prefix + ".case-sensitive");
        clearButton.setName(prefix + ".clear");
        setAccessibleName(clearButton, prefix + ".clear");
        statusLabel.setName(prefix + ".status");
        setAccessibleName(statusLabel, prefix + ".status");
    }

    private void configureLayout() {
        JPanel searchRow = new JPanel(new BorderLayout(4, 0));
        JLabel searchLabel = new JLabel("検索:");
        searchLabel.setLabelFor(queryField);
        searchRow.add(searchLabel, BorderLayout.WEST);
        Dimension comboMinimum = historyCombo.getMinimumSize();
        historyCombo.setMinimumSize(new Dimension(40, comboMinimum.height));
        searchRow.add(historyCombo, BorderLayout.CENTER);
        searchRow.add(clearButton, BorderLayout.EAST);

        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        options.add(regexCheckBox);
        options.add(caseSensitiveCheckBox);
        JPanel optionRow = new JPanel(new BorderLayout(4, 0));
        optionRow.add(options, BorderLayout.WEST);
        optionRow.add(statusLabel, BorderLayout.CENTER);

        JPanel controls = new JPanel(new BorderLayout(0, 2));
        controls.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));
        controls.add(searchRow, BorderLayout.NORTH);
        controls.add(optionRow, BorderLayout.SOUTH);
        component.add(controls, BorderLayout.NORTH);
        Font statusFont = statusLabel.getFont();
        if (statusFont != null) {
            statusLabel.setFont(
                    statusFont.deriveFont(Math.max(10.0f,
                            statusFont.getSize2D() - 1.0f)));
        }
    }

    private void configureHistoryRenderer() {
        historyCombo.setRenderer(new DefaultListCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override
            public Component getListCellRendererComponent(
                    JList<?> list, Object value, int index,
                    boolean selected, boolean cellHasFocus) {
                super.getListCellRendererComponent(
                        list, value, index, selected, cellHasFocus);
                if (value instanceof LogSearchHistory.Entry) {
                    LogSearchHistory.Entry entry =
                            (LogSearchHistory.Entry) value;
                    String modes = entry.isRegularExpression()
                            ? " [正規表現]"
                            : "";
                    if (entry.isCaseSensitive()) {
                        modes += " [大/小文字]";
                    }
                    setText(HISTORY_TIME_FORMAT.format(
                            Instant.ofEpochMilli(entry.getTimestamp()))
                            + " — " + entry.getQuery() + modes);
                }
                return this;
            }
        });
    }

    private void configureListeners() {
        queryField.getDocument().addDocumentListener(
                new DocumentListener() {
                    @Override
                    public void insertUpdate(DocumentEvent event) {
                        queryChanged();
                    }

                    @Override
                    public void removeUpdate(DocumentEvent event) {
                        queryChanged();
                    }

                    @Override
                    public void changedUpdate(DocumentEvent event) {
                        queryChanged();
                    }
                });
        queryField.addActionListener(event -> {
            refreshNow();
            commitCurrentSearch();
        });
        queryField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent event) {
                if (historyCombo.getItemCount() > 0) {
                    SwingUtilities.invokeLater(() -> {
                        if (historyCombo.isDisplayable()
                                && queryField.isFocusOwner()) {
                            historyCombo.showPopup();
                        }
                    });
                }
            }

            @Override
            public void focusLost(FocusEvent event) {
                if (!historyCombo.isPopupVisible()) {
                    commitCurrentSearch();
                }
            }
        });
        historyCombo.addActionListener(event -> {
            if (updatingControls) {
                return;
            }
            Object selected = historyCombo.getSelectedItem();
            if (selected instanceof LogSearchHistory.Entry) {
                applyHistory((LogSearchHistory.Entry) selected);
            }
        });
        regexCheckBox.addActionListener(event -> {
            if (!updatingControls) {
                selectedHistoryEntry = null;
                requestRefresh();
            }
        });
        caseSensitiveCheckBox.addActionListener(event -> {
            if (!updatingControls) {
                selectedHistoryEntry = null;
                requestRefresh();
            }
        });
        clearButton.addActionListener(event -> clearQuery());
    }

    private void configureKeyboardActions() {
        component.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke("control F"), "focus-search");
        component.getActionMap().put("focus-search", new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent event) {
                queryField.requestFocusInWindow();
                queryField.selectAll();
            }
        });
        queryField.getInputMap().put(
                KeyStroke.getKeyStroke("ESCAPE"), "clear-search");
        queryField.getActionMap().put("clear-search", new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent event) {
                clearQuery();
            }
        });
    }

    private void queryChanged() {
        if (!updatingControls) {
            selectedHistoryEntry = null;
            requestRefresh();
        }
    }

    private void applyHistory(LogSearchHistory.Entry entry) {
        updatingControls = true;
        try {
            selectedHistoryEntry = entry;
            regexCheckBox.setSelected(entry.isRegularExpression());
            caseSensitiveCheckBox.setSelected(entry.isCaseSensitive());
            queryField.setText(entry.getQuery());
            queryField.selectAll();
        } finally {
            updatingControls = false;
        }
        refreshNow();
    }

    private void reloadHistory() {
        List<LogSearchHistory.Entry> entries = history.load(historyKey);
        updatingControls = true;
        try {
            Object editorValue = queryField.getText();
            DefaultComboBoxModel<LogSearchHistory.Entry> model =
                    new DefaultComboBoxModel<>();
            for (LogSearchHistory.Entry entry : entries) {
                model.addElement(entry);
            }
            historyCombo.setModel(model);
            historyCombo.getEditor().setItem(editorValue);
        } finally {
            updatingControls = false;
        }
    }

    private void replaceDisplayedText(String replacement) {
        if (textArea.getText().equals(replacement)) {
            return;
        }
        int selectionStart = textArea.getSelectionStart();
        int selectionEnd = textArea.getSelectionEnd();
        int caretPosition = textArea.getCaretPosition();
        boolean selecting = selectionStart != selectionEnd;
        boolean backlog = textArea.getCaret() instanceof DefaultCaret
                && ((DefaultCaret) textArea.getCaret()).getUpdatePolicy()
                        == DefaultCaret.NEVER_UPDATE;

        textArea.setText(replacement);
        int length = replacement.length();
        if (selecting) {
            textArea.select(
                    Math.min(selectionStart, length),
                    Math.min(selectionEnd, length));
        } else if (backlog) {
            textArea.setCaretPosition(Math.min(caretPosition, length));
        } else {
            textArea.setCaretPosition(length);
        }
    }

    private void updateStatus(LogFilter.Result result) {
        if (!result.isValid()) {
            String message = "正規表現エラー: " + result.getError();
            statusLabel.setText(message);
            statusLabel.setToolTipText(message);
            queryField.setToolTipText(message);
            queryField.setBackground(new Color(255, 225, 225));
            return;
        }

        queryField.setBackground(normalQueryBackground != null
                ? normalQueryBackground
                : UIManager.getColor("TextField.background"));
        queryField.setToolTipText(
                "文字列を入力すると行単位で絞り込みます。Enterで履歴に保存します。");
        statusLabel.setToolTipText(null);
        if (isFilterActive()) {
            statusLabel.setText(
                    result.getMatchedLines() + " / "
                    + result.getTotalLines() + " 行");
        } else {
            statusLabel.setText(result.getTotalLines() + " 行");
        }
    }

    private static void setAccessibleName(
            JComponent component, String name) {
        component.getAccessibleContext().setAccessibleName(name);
    }
}
