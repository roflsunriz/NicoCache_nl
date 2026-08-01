package nicocache.launcher;

import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.ResourceBundle;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;

final class LauncherSetupDialog {
    private LauncherSetupDialog() {
    }

    static boolean showAndApply(LauncherPaths initial,
            ResourceBundle messages) {
        JTextField dataRoot = new JTextField(
                initial.getDataRoot().toString(),
                32);
        JCheckBox https = new JCheckBox(messages.getString("setup.https"), true);
        JCheckBox trust = new JCheckBox(messages.getString("setup.trust"), false);
        JCheckBox proxy = new JCheckBox(messages.getString("setup.proxy"), false);
        JCheckBox autostart = new JCheckBox(
                messages.getString("setup.autostart"), false);
        JPanel rootPanel = new JPanel(new BorderLayout(8, 8));
        JPanel pathPanel = new JPanel(new GridBagLayout());
        GridBagConstraints labelConstraints = new GridBagConstraints();
        labelConstraints.gridx = 0;
        labelConstraints.gridy = 0;
        labelConstraints.anchor = GridBagConstraints.LINE_START;
        labelConstraints.insets = new Insets(2, 2, 2, 4);
        pathPanel.add(new JLabel(messages.getString("setup.dataRoot")),
                labelConstraints);
        GridBagConstraints fieldConstraints = new GridBagConstraints();
        fieldConstraints.gridx = 1;
        fieldConstraints.gridy = 0;
        fieldConstraints.weightx = 1.0;
        fieldConstraints.fill = GridBagConstraints.HORIZONTAL;
        fieldConstraints.insets = new Insets(2, 2, 2, 4);
        pathPanel.add(dataRoot, fieldConstraints);
        JButton browse = new JButton(messages.getString("setup.browse"));
        browse.setName("setup.dataRoot.browse");
        browse.addActionListener(event -> {
            JFileChooser chooser = new JFileChooser(dataRoot.getText());
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle(messages.getString("setup.browse.title"));
            if (chooser.showOpenDialog(rootPanel) == JFileChooser.APPROVE_OPTION) {
                dataRoot.setText(chooser.getSelectedFile().toPath().toString());
            }
        });
        GridBagConstraints browseConstraints = new GridBagConstraints();
        browseConstraints.gridx = 2;
        browseConstraints.gridy = 0;
        browseConstraints.insets = new Insets(2, 2, 2, 2);
        pathPanel.add(browse, browseConstraints);

        JPanel options = new JPanel(new GridBagLayout());
        GridBagConstraints optionConstraints = new GridBagConstraints();
        optionConstraints.gridx = 0;
        optionConstraints.gridy = 0;
        optionConstraints.anchor = GridBagConstraints.LINE_START;
        options.add(https, optionConstraints);
        optionConstraints.gridy++;
        options.add(trust, optionConstraints);
        optionConstraints.gridy++;
        options.add(proxy, optionConstraints);
        optionConstraints.gridy++;
        options.add(autostart, optionConstraints);
        rootPanel.add(pathPanel, BorderLayout.NORTH);
        rootPanel.add(options, BorderLayout.CENTER);

        int result = JOptionPane.showConfirmDialog(
                null,
                rootPanel,
                messages.getString("setup.title"),
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return false;
        }

        Path selected;
        try {
            selected = Path.of(dataRoot.getText().trim())
                    .toAbsolutePath().normalize();
        } catch (InvalidPathException error) {
            JOptionPane.showMessageDialog(null,
                    messages.getString("setup.invalidPath"),
                    messages.getString("error.title"),
                    JOptionPane.ERROR_MESSAGE);
            return false;
        }
        if (!selected.isAbsolute()) {
            JOptionPane.showMessageDialog(null,
                    messages.getString("setup.absolutePath"),
                    messages.getString("error.title"),
                    JOptionPane.ERROR_MESSAGE);
            return false;
        }

        LauncherPaths setupPaths = LauncherPaths.resolve(
                initial.getApplicationRoot(), selected);
        List<String> arguments = List.of(
                "--setup", "--headless",
                "--user-data-root=" + selected,
                "--https=" + https.isSelected(),
                "--trust-certificate=" + trust.isSelected(),
                "--proxy=" + proxy.isSelected(),
                "--autostart=false");
        try {
            int exitCode = new CoreProcess(setupPaths).runSetup(arguments, false);
            if (exitCode != 0) {
                JOptionPane.showMessageDialog(null,
                        messages.getString("setup.failed") + " (ExitCode: "
                                + exitCode + ")",
                        messages.getString("error.title"),
                        JOptionPane.ERROR_MESSAGE);
                return false;
            }
            if (autostart.isSelected()) {
                new TaskScheduler(setupPaths).install(new TaskDefinition(
                        "NicoCache_nl", TaskDefinition.Schedule.ON_LOGON, 60,
                        true));
            }
            return true;
        } catch (Exception error) {
            JOptionPane.showMessageDialog(null,
                    messages.getString("setup.failed") + ": " + error.getMessage(),
                    messages.getString("error.title"),
                    JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }
}
