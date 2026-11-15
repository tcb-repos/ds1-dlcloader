// Dead Space DLC Loader - Copyright (C) 2026 teeceebee (tcb-repos)
// Licensed under the GNU General Public License v3 or later; see LICENSE.txt.
// Dead Space (2008) DLC Loader: converts the console DLC for the PC game and installs the loader DLL.
// Simple window around DlcPackages.
//   java DlcLoaderGui.java          (run from the tools folder, or set -Ddlcconv.tools=<tools folder>)
// The user picks their Dead Space PC folder and their own DLC downloads (Xbox 360 packages, PS3 .pkg
// files, or the .zip files they came in); the converted packs are written to <game>\dlc\<pack>\.
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.prefs.Preferences;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

public class DlcLoaderGui {
    static final Preferences PREFS = Preferences.userRoot().node("deadspace-dlc-loader");

    static final String VERSION = "1.0.0";
    static final String AUTHOR = "teeceebee (tcb-repos)";
    JFrame frame = new JFrame("Dead Space DLC Loader " + VERSION);
    JTextField game = new JTextField(PREFS.get("game", ""));
    JLabel gameOk = new JLabel(" ");
    DefaultListModel<Path> files = new DefaultListModel<>();
    JList<Path> fileList = new JList<>(files);
    JRadioButton prefer360 = new JRadioButton("Xbox 360"), preferPs3 = new JRadioButton("PS3");
    JTextField ffmpeg = new JTextField();
    JCheckBox skip = new JCheckBox("Leave out parts that can't be converted yet (the pack may not work in game)");
    JCheckBox loader = new JCheckBox("Install the loader DLL (dinput8.dll) into the game folder", PREFS.getBoolean("loader", true));
    JButton convert = new JButton("Convert");
    JProgressBar progress = new JProgressBar();
    JTextArea log = new JTextArea(14, 80);

    public static void main(String[] a) {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> new DlcLoaderGui().show());
    }

    void show() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(new EmptyBorder(10, 10, 10, 10));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 3, 3, 3);
        c.fill = GridBagConstraints.HORIZONTAL;

        // game folder
        row(p, c, 0, "Dead Space PC folder:", game, button("Browse...", e -> pickGame()));
        c.gridx = 1; c.gridy = 1; c.gridwidth = 2; p.add(gameOk, c); c.gridwidth = 1;
        game.getDocument().addDocumentListener(new Change(this::checkGame));
        checkGame();

        // DLC files
        c.gridx = 0; c.gridy = 2; c.anchor = GridBagConstraints.NORTHWEST; p.add(new JLabel("DLC files:"), c);
        fileList.setVisibleRowCount(8);
        fileList.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                Path path = (Path) v;
                return super.getListCellRendererComponent(l, path.getFileName() + "   (" + describe(path) + ")", i, s, f);
            }
        });
        fileList.setTransferHandler(new TransferHandler() {      // drag and drop files or folders
            @Override public boolean canImport(TransferSupport s) { return s.isDataFlavorSupported(DataFlavor.javaFileListFlavor); }
            @Override @SuppressWarnings("unchecked") public boolean importData(TransferSupport s) {
                try { for (File f : (List<File>) s.getTransferable().getTransferData(DataFlavor.javaFileListFlavor)) add(f.toPath()); return true; }
                catch (Exception e) { return false; }
            }
        });
        c.gridx = 1; c.gridy = 2; c.weightx = 1; c.weighty = 0.4; c.fill = GridBagConstraints.BOTH;
        p.add(new JScrollPane(fileList), c);
        c.weightx = 0; c.weighty = 0; c.fill = GridBagConstraints.HORIZONTAL;
        JPanel fb = new JPanel(new GridLayout(0, 1, 0, 4));
        fb.add(button("Add files...", e -> pickFiles()));
        fb.add(button("Add folder...", e -> pickFolder()));
        fb.add(button("Remove", e -> { for (Path x : fileList.getSelectedValuesList()) files.removeElement(x); }));
        fb.add(button("Clear", e -> files.clear()));
        c.gridx = 2; c.anchor = GridBagConstraints.NORTH; p.add(fb, c);
        c.gridx = 1; c.gridy = 3; c.gridwidth = 2;
        p.add(note("Add your Xbox 360 packages, PS3 .pkg files, or the .zip files they came in. You can also drag them onto the list."), c);
        c.gridwidth = 1;

        // preference
        ButtonGroup g = new ButtonGroup(); g.add(prefer360); g.add(preferPs3);
        (PREFS.getBoolean("ps3", false) ? preferPs3 : prefer360).setSelected(true);
        JPanel pr = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        pr.add(prefer360); pr.add(Box.createHorizontalStrut(12)); pr.add(preferPs3);
        c.gridx = 0; c.gridy = 4; p.add(new JLabel("If a pack is in both:"), c);
        c.gridx = 1; c.gridwidth = 2; p.add(pr, c); c.gridwidth = 1;

        // ffmpeg
        String ff = PREFS.get("ffmpeg", "");
        if (ff.isBlank()) { String found = SbkAudio.findFfmpeg(); if (!found.equals("ffmpeg")) ff = found; }
        ffmpeg.setText(ff);
        row(p, c, 5, "ffmpeg.exe:", ffmpeg, button("Browse...", e -> pickFfmpeg()));
        c.gridx = 1; c.gridy = 6; c.gridwidth = 2;
        p.add(note("Used to read the console sound banks (Xbox 360 XMA and PS3 MP3 audio). A small ffmpeg build is included "
            + "(tools\\ffmpeg); change this only to use a different ffmpeg.exe."), c);
        c.gridy = 7; p.add(loader, c);
        c.gridy = 8; p.add(skip, c);

        // convert + progress
        c.gridx = 0; c.gridy = 9; c.gridwidth = 3;
        JPanel run = new JPanel(new BorderLayout(8, 0));
        run.add(convert, BorderLayout.WEST); run.add(progress, BorderLayout.CENTER);
        progress.setStringPainted(true); progress.setString("");
        convert.addActionListener(e -> start());
        p.add(run, c);
        c.gridy = 10; c.weighty = 0.6; c.fill = GridBagConstraints.BOTH;
        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        p.add(new JScrollPane(log), c);
        c.gridy = 11; c.weighty = 0; c.fill = GridBagConstraints.HORIZONTAL;
        p.add(note("Dead Space DLC Loader " + VERSION + " by " + AUTHOR + ". Free software under the GNU GPL v3 or later (see LICENSE.txt). "
            + "No game files are included; it converts the DLC you own."), c);

        frame.setContentPane(p);
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.pack();
        frame.setMinimumSize(new Dimension(760, 560));
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    // ------------------------------------------------------------------ helpers
    static JButton button(String t, java.awt.event.ActionListener l) { JButton b = new JButton(t); b.addActionListener(l); return b; }
    static JLabel note(String t) { JLabel l = new JLabel("<html><div style='width:420px'><small>" + t + "</small></div></html>"); l.setForeground(Color.GRAY.darker()); return l; }
    static void row(JPanel p, GridBagConstraints c, int y, String label, JComponent field, JComponent btn) {
        c.gridx = 0; c.gridy = y; c.weightx = 0; p.add(new JLabel(label), c);
        c.gridx = 1; c.weightx = 1; p.add(field, c);
        c.gridx = 2; c.weightx = 0; p.add(btn, c);
    }
    record Change(Runnable r) implements javax.swing.event.DocumentListener {
        public void insertUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
        public void removeUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
        public void changedUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
    }

    static String describe(Path p) {
        try {
            return switch (DlcPackages.kind(p)) {
                case ZIP -> "zip";
                case PS3_PKG -> "PS3 package";
                case X360_STFS -> "Xbox 360 package";
                case UNKNOWN -> "not a DLC package";
            };
        } catch (IOException e) { return "unreadable"; }
    }

    void checkGame() {
        String t = game.getText().trim();
        boolean ok = !t.isEmpty() && DlcPackages.isGameFolder(Path.of(t));
        gameOk.setText(t.isEmpty() ? "Pick the folder that contains Dead Space.exe" : ok ? "Dead Space.exe found. Packs go to " + Path.of(t).resolve("dlc") : "Dead Space.exe is not in this folder");
        gameOk.setForeground(ok ? new Color(0, 120, 0) : Color.RED.darker());
    }

    void add(Path p) {
        if (Files.isDirectory(p)) {
            try (var s = Files.walk(p)) {
                for (Path f : s.filter(Files::isRegularFile).sorted().toList())
                    try { if (DlcPackages.kind(f) != DlcPackages.Kind.UNKNOWN) add(f); } catch (IOException ignored) {}
            } catch (IOException ignored) {}
            return;
        }
        if (!files.contains(p)) files.addElement(p);
    }

    void pickGame() {
        JFileChooser ch = new JFileChooser(game.getText());
        ch.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (ch.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) game.setText(ch.getSelectedFile().getPath());
    }
    void pickFiles() {
        JFileChooser ch = new JFileChooser(PREFS.get("lastDir", ""));
        ch.setMultiSelectionEnabled(true);
        if (ch.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        for (File f : ch.getSelectedFiles()) add(f.toPath());
        PREFS.put("lastDir", ch.getCurrentDirectory().getPath());
    }
    void pickFolder() {
        JFileChooser ch = new JFileChooser(PREFS.get("lastDir", ""));
        ch.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (ch.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        add(ch.getSelectedFile().toPath());
        PREFS.put("lastDir", ch.getSelectedFile().getPath());
    }
    void pickFfmpeg() {
        JFileChooser ch = new JFileChooser(ffmpeg.getText());
        if (ch.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) ffmpeg.setText(ch.getSelectedFile().getPath());
    }

    // ------------------------------------------------------------------ run
    void start() {
        String gt = game.getText().trim();
        if (gt.isEmpty() || !DlcPackages.isGameFolder(Path.of(gt))) { message("Pick your Dead Space PC folder first (the one with Dead Space.exe)."); return; }
        if (files.isEmpty()) { message("Add at least one DLC file."); return; }
        String ff = ffmpeg.getText().trim();
        if (ff.isEmpty() || !Files.isRegularFile(Path.of(ff))) { message("Pick ffmpeg.exe (the included one is tools\\ffmpeg\\ffmpeg.exe). It's needed to convert the DLC sound banks."); return; }
        String ffProblem = SbkAudio.checkDecoders(ff);
        if (ffProblem != null) { message("This ffmpeg.exe can't be used: " + ffProblem + ".\nPick the included tools\\ffmpeg\\ffmpeg.exe instead."); return; }
        Path gameDir = Path.of(gt);
        try {
            int longPaths = DlcPackages.tooLongPaths(gameDir);
            if (longPaths > 0 && JOptionPane.showConfirmDialog(frame,
                    "The game folder's path is too long (" + gameDir.toAbsolutePath().normalize().toString().length() + " characters).\n"
                    + longPaths + " of the game's own files end up longer than Windows' 260-character path limit there,\n"
                    + "and Dead Space crashes when it loads a level that uses them, with or without the DLC.\n\n"
                    + "Move the game to a folder with a shorter path (" + DlcPackages.MAX_GAME_PATH + " characters or less), "
                    + "for example C:\\Games\\Dead Space.\n\nConvert into this folder anyway?",
                    "Dead Space DLC Loader", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
        } catch (IOException e) { message("Can't check the game folder: " + e.getMessage()); return; }
        boolean installLoader = loader.isSelected();
        if (installLoader) {
            try {
                DlcPackages.LoaderState st = DlcPackages.loaderState(gameDir);
                if (st == DlcPackages.LoaderState.NOT_SHIPPED) { message("The loader DLL (loader\\dinput8.dll) is missing from the DLC Loader folder. Untick \"Install the loader DLL\" to convert anyway."); return; }
                if (st == DlcPackages.LoaderState.OTHER && JOptionPane.showConfirmDialog(frame,
                        "The game folder already has a dinput8.dll from another mod (for example an ASI loader or another DLC loader).\n"
                        + "Only one dinput8.dll can be used. Replace it with this loader DLL? The old file is kept as dinput8.dll.bak.",
                        "Dead Space DLC Loader", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
                    installLoader = false;
                }
            } catch (IOException e) { message("Can't check the game folder: " + e.getMessage()); return; }
        }
        PREFS.put("game", gt); PREFS.put("ffmpeg", ff); PREFS.putBoolean("ps3", preferPs3.isSelected()); PREFS.putBoolean("loader", loader.isSelected());
        SbkAudio.FFMPEG = ff;
        List<Path> in = Collections.list(files.elements());
        boolean ps3 = preferPs3.isSelected(), skipIt = skip.isSelected(), doLoader = installLoader;
        setBusy(true);
        log.setText("");
        PrintStream out = new PrintStream(new TextAreaStream(), true, StandardCharsets.UTF_8);
        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() throws Exception {
                Path work = Files.createTempDirectory("dsdlc");
                try {
                    List<DlcPackages.Pack> all = DlcPackages.findPacks(in, work, out);
                    if (all.isEmpty()) return "No DLC packs were found in the selected files.";
                    List<DlcPackages.Pack> packs = DlcPackages.choose(all, ps3, out);
                    List<String> failed = DlcPackages.convert(packs, gameDir, gameDir, skipIt, out, (d, t, w) ->
                        SwingUtilities.invokeLater(() -> { progress.setMaximum(Math.max(1, t)); progress.setValue(d); progress.setString(w); }));
                    int ok = packs.size() - failed.size();
                    if (doLoader && ok > 0) { out.println(); DlcPackages.installLoader(gameDir, out); }
                    return ok + " of " + packs.size() + " packs converted into " + gameDir.resolve("dlc")
                        + (failed.isEmpty() ? "." : ".\nNot converted (unsupported formats): " + String.join(", ", failed))
                        + (doLoader ? "" : "\n\nThe game also needs the loader DLL (dinput8.dll) to load them.");
                } finally { DlcPackages.deleteTree(work); }
            }
            @Override protected void done() {
                setBusy(false);
                try { String r = get(); out.println(); out.println(r); message(r); }
                catch (Exception e) {
                    Throwable t = e.getCause() == null ? e : e.getCause();
                    out.println("ERROR: " + t);
                    message("Conversion stopped: " + t.getMessage());
                }
            }
        }.execute();
    }

    void setBusy(boolean b) {
        convert.setEnabled(!b); game.setEnabled(!b); fileList.setEnabled(!b); ffmpeg.setEnabled(!b); loader.setEnabled(!b); skip.setEnabled(!b);
        progress.setIndeterminate(false);
        if (!b) progress.setString("");
    }
    void message(String m) { JOptionPane.showMessageDialog(frame, m, "Dead Space DLC Loader", JOptionPane.INFORMATION_MESSAGE); }

    /** Sends log text to the text area, line by line, on the Swing thread. */
    class TextAreaStream extends OutputStream {
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        @Override public synchronized void write(int b) {
            if (b == '\n') flushLine(); else buf.write(b);
        }
        @Override public synchronized void flush() { }
        void flushLine() {
            String line = buf.toString(StandardCharsets.UTF_8).replace("\r", "");
            buf.reset();
            SwingUtilities.invokeLater(() -> { log.append(line + "\n"); log.setCaretPosition(log.getDocument().getLength()); });
        }
    }
}
