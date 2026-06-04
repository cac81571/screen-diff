package com.screendiff;

import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;

public class App extends JFrame {

    private static final Path HISTORY_FILE = Path.of(System.getProperty("user.home"), ".screendiff_history");
    private static final Path AI_PROMPT_FILE = Path.of(System.getProperty("user.home"), ".screendiff_ai_prompt");
    private static final int MAX_HISTORY = 20;
    private static final String DEFAULT_AI_PROMPT = """
            アップロードしたPDF内の旧画面と新画面を比較してください。
            PDFのページは 旧画面1、新画面1、旧画面2、新画面2、・・・ で構成されています。

            確認ルール：

            1. 表示されているデータ項目の差異
            2. 項目名（ラベル）の差異
            3. 値の差異
            4. 表示件数の差異
            5. 日付・数値・金額フォーマットの差異
            6. ステータス表示の差異
            7. ボタン・リンク・メニューの差異
            8. レイアウト差異（重要度低）

            結果は以下のCSV形式で出力してください。

            画面名（PDF内のファイル名）,判定,差異項目,旧の内容,新の内容,コメント

            判定：
            - NG（重要度：高）
            - NG（重要度：低）
            - 要確認
            - OK（差異がない画面の場合）

            不明な箇所は推測せず「要確認」と記載してください。
            差異がない項目は記載不要です。
            差異項目が0件の画面は「OK」と1行記載してください。
            """;

    private final JComboBox<String> oldDirCombo = createEditableCombo();
    private final JComboBox<String> newDirCombo = createEditableCombo();
    private final JComboBox<String> outDirCombo = createEditableCombo();
    private final JSlider blockSizeSlider = new JSlider(1, 20, 1);
    private final JSlider thresholdSlider = new JSlider(0, 255, 10);
    private final JSlider jpegQualitySlider = new JSlider(10, 100, 50);
    private final JSpinner blockSizeSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 20, 1));
    private final JSpinner thresholdSpinner = new JSpinner(new SpinnerNumberModel(10, 0, 255, 1));
    private final JSpinner jpegQualitySpinner = new JSpinner(new SpinnerNumberModel(50, 10, 100, 5));
    private final JSpinner splitHeadHeightSpinner = new JSpinner(new SpinnerNumberModel(1500, null, null, 100));
    private final JSpinner splitTailHeightSpinner = new JSpinner(new SpinnerNumberModel(1500, null, null, 100));
    private final JSpinner pdfMaxSizeSpinner = new JSpinner(new SpinnerNumberModel(10, 0, 500, 1));
    private final JLabel pdfMaxSizeLabel = new JLabel("PDF上限サイズ(MB):");
    private final JRadioButton originalFormatRadio = new JRadioButton("変換なし", true);
    private final JRadioButton jpegFormatRadio = new JRadioButton("JPEG変換");
    private final ButtonGroup imageFormatGroup = new ButtonGroup();
    private final JLabel jpegQualityLabel = new JLabel("JPEG品質(%):");
    private final JCheckBox trimMarginsCheck = new JCheckBox("四隅の余白削除", false);
    private final JCheckBox splitDisplayCheck = new JCheckBox("画像2分割表示", true);
    private final JLabel splitHeadHeightLabel = new JLabel("先頭表示高さ(px):");
    private final JLabel splitTailHeightLabel = new JLabel("末尾表示高さ(px):");
    private final JTextArea logArea = new JTextArea(10, 50);
    private final JTextArea aiPromptArea = new JTextArea(20, 60);
    private JButton htmlReportButton;
    private JButton pdfReportButton;
    private volatile boolean reportInProgress;
    /** 処理開始ごとに増やし、前回ジョブの invokeLater ログを無視する */
    private volatile long logSessionId;

    public App() {
        super("Screen Diff Tool");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout(10, 10));

        loadHistory();
        linkSettingControls();
        initAiPromptArea();

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("比較", createCompareTab());
        tabs.addTab("設定", createSettingsTab());
        tabs.addTab("AI", createAiTab());

        add(tabs, BorderLayout.CENTER);

        pack();
        setLocationRelativeTo(null);
    }

    private JPanel createCompareTab() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel inputPanel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 5, 5, 5);
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0; c.gridy = 0;
        inputPanel.add(new JLabel("旧フォルダ:"), c);
        c.gridx = 1; c.weightx = 1;
        inputPanel.add(oldDirCombo, c);
        c.gridx = 2; c.weightx = 0;
        inputPanel.add(createOpenButton(oldDirCombo), c);

        c.gridx = 0; c.gridy = 1; c.weightx = 0;
        inputPanel.add(new JLabel("新フォルダ:"), c);
        c.gridx = 1; c.weightx = 1;
        inputPanel.add(newDirCombo, c);
        c.gridx = 2; c.weightx = 0;
        inputPanel.add(createOpenButton(newDirCombo), c);

        c.gridx = 0; c.gridy = 2; c.weightx = 0;
        inputPanel.add(new JLabel("出力先:"), c);
        c.gridx = 1; c.weightx = 1;
        inputPanel.add(outDirCombo, c);
        c.gridx = 2; c.weightx = 0;
        inputPanel.add(createOpenButton(outDirCombo), c);

        htmlReportButton = new JButton("HTML比較レポート作成");
        htmlReportButton.addActionListener(e -> createHtmlReport());
        pdfReportButton = new JButton("PDF比較レポート作成");
        pdfReportButton.addActionListener(e -> createPdfReport());
        JButton copyAiPromptBtn = new JButton("PDF検証用AIプロンプトコピー");
        copyAiPromptBtn.addActionListener(e -> copyAiPromptToClipboard());
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        btnPanel.add(htmlReportButton);
        btnPanel.add(pdfReportButton);
        btnPanel.add(copyAiPromptBtn);
        c.gridx = 1; c.gridy = 3;
        inputPanel.add(btnPanel, c);

        panel.add(inputPanel, BorderLayout.NORTH);

        logArea.setEditable(false);
        panel.add(new JScrollPane(logArea), BorderLayout.CENTER);
        return panel;
    }

    private void initAiPromptArea() {
        aiPromptArea.setLineWrap(true);
        aiPromptArea.setWrapStyleWord(true);
        aiPromptArea.setText(loadAiPromptText());
    }

    private JPanel createAiTab() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        panel.add(new JLabel("PDF検証用AIプロンプト:"), BorderLayout.NORTH);
        panel.add(new JScrollPane(aiPromptArea), BorderLayout.CENTER);
        return panel;
    }

    private void copyAiPromptToClipboard() {
        saveAiPrompt();
        String text = aiPromptArea.getText();
        if (text.isBlank()) {
            JOptionPane.showMessageDialog(this, "プロンプトが空です。", "エラー", JOptionPane.ERROR_MESSAGE);
            return;
        }
        StringSelection selection = new StringSelection(text);
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, selection);
        log("AIプロンプトをクリップボードにコピーしました");
        JOptionPane.showMessageDialog(this, "クリップボードにコピーしました", "完了", JOptionPane.INFORMATION_MESSAGE);
    }

    private static String loadAiPromptText() {
        if (Files.exists(AI_PROMPT_FILE)) {
            try {
                return Files.readString(AI_PROMPT_FILE, StandardCharsets.UTF_8);
            } catch (IOException ignored) {
            }
        }
        return DEFAULT_AI_PROMPT;
    }

    private void saveAiPrompt() {
        try {
            Files.writeString(AI_PROMPT_FILE, aiPromptArea.getText(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    private JPanel createSettingsTab() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(8, 5, 8, 5);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.WEST;

        c.gridx = 0; c.gridy = 0; c.weightx = 0;
        panel.add(new JLabel("ブロックサイズ:"), c);
        c.gridx = 1; c.weightx = 1;
        panel.add(blockSizeSlider, c);
        c.gridx = 2; c.weightx = 0;
        panel.add(blockSizeSpinner, c);

        c.gridx = 0; c.gridy = 1; c.weightx = 0;
        panel.add(new JLabel("しきい値:"), c);
        c.gridx = 1; c.weightx = 1;
        panel.add(thresholdSlider, c);
        c.gridx = 2; c.weightx = 0;
        panel.add(thresholdSpinner, c);

        c.gridx = 0; c.gridy = 2; c.gridwidth = 3; c.weightx = 1;
        trimMarginsCheck.setToolTipText("四隅の色に近い余白を除去してから比較します");
        panel.add(trimMarginsCheck, c);
        c.gridwidth = 1;

        c.gridx = 0; c.gridy = 3; c.weightx = 0;
        panel.add(new JLabel("画像出力形式:"), c);
        c.gridx = 1; c.gridwidth = 2; c.weightx = 1;
        originalFormatRadio.setToolTipText("HTML レポートに埋め込む旧・新画像の形式");
        jpegFormatRadio.setToolTipText(originalFormatRadio.getToolTipText());
        imageFormatGroup.add(originalFormatRadio);
        imageFormatGroup.add(jpegFormatRadio);
        JPanel formatPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        formatPanel.add(originalFormatRadio);
        formatPanel.add(jpegFormatRadio);
        var formatListener = (java.awt.event.ItemListener) e -> {
            if (e.getStateChange() == java.awt.event.ItemEvent.SELECTED) {
                updateJpegQualityEnabled();
            }
        };
        originalFormatRadio.addItemListener(formatListener);
        jpegFormatRadio.addItemListener(formatListener);
        panel.add(formatPanel, c);
        c.gridwidth = 1;

        c.gridx = 0; c.gridy = 4; c.weightx = 0;
        panel.add(jpegQualityLabel, c);
        c.gridx = 1; c.weightx = 1;
        panel.add(jpegQualitySlider, c);
        c.gridx = 2; c.weightx = 0;
        panel.add(jpegQualitySpinner, c);
        updateJpegQualityEnabled();

        c.gridx = 0; c.gridy = 5; c.gridwidth = 3; c.weightx = 1;
        splitDisplayCheck.setToolTipText("ON のとき、縦長画像を先頭と末尾の2区間に分けて表示します");
        splitHeadHeightLabel.setToolTipText("2分割表示 ON 時、表示する先頭区間の高さ");
        splitTailHeightLabel.setToolTipText("2分割表示 ON 時、表示する末尾区間の高さ");
        splitHeadHeightSpinner.setToolTipText(splitHeadHeightLabel.getToolTipText());
        splitTailHeightSpinner.setToolTipText(splitTailHeightLabel.getToolTipText());
        JPanel splitPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        splitPanel.add(splitDisplayCheck);
        splitPanel.add(splitHeadHeightLabel);
        splitPanel.add(splitHeadHeightSpinner);
        splitPanel.add(splitTailHeightLabel);
        splitPanel.add(splitTailHeightSpinner);
        panel.add(splitPanel, c);
        c.gridwidth = 1;
        splitDisplayCheck.addItemListener(e -> updateSplitDisplayEnabled());
        updateSplitDisplayEnabled();

        c.gridx = 0; c.gridy = 6; c.gridwidth = 3; c.weightx = 1; c.weighty = 0; c.fill = GridBagConstraints.HORIZONTAL;
        pdfMaxSizeLabel.setToolTipText("PDF がこのサイズを超える場合、report-01.pdf, report-02.pdf … に分割（0=分割しない）");
        pdfMaxSizeSpinner.setToolTipText(pdfMaxSizeLabel.getToolTipText());
        JPanel pdfSizePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        pdfSizePanel.add(pdfMaxSizeLabel);
        pdfSizePanel.add(pdfMaxSizeSpinner);
        panel.add(pdfSizePanel, c);
        c.gridwidth = 1;

        c.gridx = 0; c.gridy = 7; c.gridwidth = 3; c.weightx = 1;
        c.fill = GridBagConstraints.BOTH;
        c.weighty = 1;
        panel.add(Box.createVerticalGlue(), c);

        return panel;
    }

    private void linkSettingControls() {
        blockSizeSlider.addChangeListener(e -> blockSizeSpinner.setValue(blockSizeSlider.getValue()));
        blockSizeSpinner.addChangeListener(e -> blockSizeSlider.setValue((int) blockSizeSpinner.getValue()));
        thresholdSlider.addChangeListener(e -> thresholdSpinner.setValue(thresholdSlider.getValue()));
        thresholdSpinner.addChangeListener(e -> thresholdSlider.setValue((int) thresholdSpinner.getValue()));
        jpegQualitySlider.addChangeListener(e -> jpegQualitySpinner.setValue(jpegQualitySlider.getValue()));
        jpegQualitySpinner.addChangeListener(e -> jpegQualitySlider.setValue((int) jpegQualitySpinner.getValue()));

        blockSizeSpinner.setValue(blockSizeSlider.getValue());
        thresholdSpinner.setValue(thresholdSlider.getValue());
        jpegQualitySpinner.setValue(jpegQualitySlider.getValue());
        updateJpegQualityEnabled();
    }

    private void updateJpegQualityEnabled() {
        boolean jpeg = jpegFormatRadio.isSelected();
        jpegQualityLabel.setEnabled(jpeg);
        jpegQualitySlider.setEnabled(jpeg);
        jpegQualitySpinner.setEnabled(jpeg);
    }

    private void updateSplitDisplayEnabled() {
        boolean enabled = splitDisplayCheck.isSelected();
        splitHeadHeightLabel.setEnabled(enabled);
        splitHeadHeightSpinner.setEnabled(enabled);
        splitTailHeightLabel.setEnabled(enabled);
        splitTailHeightSpinner.setEnabled(enabled);
    }

    private int getSplitHeadHeight() {
        return splitDisplayCheck.isSelected() ? (int) splitHeadHeightSpinner.getValue() : 0;
    }

    private int getSplitTailHeight() {
        return splitDisplayCheck.isSelected() ? (int) splitTailHeightSpinner.getValue() : 0;
    }

    private long getPdfMaxSizeBytes() {
        int mb = (int) pdfMaxSizeSpinner.getValue();
        return mb <= 0 ? 0L : (long) mb * 1024 * 1024;
    }

    private ReportGenerator.ImageFormat getImageFormat() {
        return jpegFormatRadio.isSelected()
                ? ReportGenerator.ImageFormat.JPEG
                : ReportGenerator.ImageFormat.ORIGINAL;
    }

    private static JComboBox<String> createEditableCombo() {
        JComboBox<String> combo = new JComboBox<>();
        combo.setEditable(true);
        combo.setPrototypeDisplayValue("X".repeat(40));
        return combo;
    }

    private JButton createOpenButton(JComboBox<String> target) {
        JButton btn = new JButton(UIManager.getIcon("FileView.directoryIcon"));
        btn.setToolTipText("エクスプローラで開く");
        btn.setMargin(new Insets(2, 4, 2, 4));
        btn.addActionListener(e -> {
            String path = ((String) target.getEditor().getItem()).trim();
            if (path.isEmpty()) return;
            File dir = new File(path);
            if (!dir.isDirectory()) {
                JOptionPane.showMessageDialog(this, "フォルダが存在しません: " + path, "エラー", JOptionPane.ERROR_MESSAGE);
                return;
            }
            try {
                Desktop.getDesktop().open(dir);
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, "エクスプローラを開けません: " + ex.getMessage(), "エラー", JOptionPane.ERROR_MESSAGE);
            }
        });
        return btn;
    }

    private String getComboText(JComboBox<String> combo) {
        return ((String) combo.getEditor().getItem()).trim();
    }

    private void addToHistory(JComboBox<String> combo, String path) {
        for (int i = 0; i < combo.getItemCount(); i++) {
            if (combo.getItemAt(i).equals(path)) {
                combo.removeItemAt(i);
                break;
            }
        }
        combo.insertItemAt(path, 0);
        combo.setSelectedIndex(0);
        while (combo.getItemCount() > MAX_HISTORY) {
            combo.removeItemAt(combo.getItemCount() - 1);
        }
    }

    private record ComparisonContext(
            List<ImageComparator.Result> results, File oldDir, File newDir, File outDir) {}

    private record ComparisonStart(
            File oldDir, File newDir, File outDir, File[] oldFiles,
            int blockSize, int threshold, boolean trimMargins) {}

    @FunctionalInterface
    private interface ReportTask {
        String run(ComparisonContext ctx) throws Exception;
    }

    private record ReportOutcome(String dialogMessage, Exception error) {
        static ReportOutcome finished(String message) {
            return new ReportOutcome(message, null);
        }

        static ReportOutcome failed(Exception error) {
            return new ReportOutcome(null, error);
        }
    }

    /** EDT 上で入力検証とログ初期化のみ行う */
    private ComparisonStart beginComparison() {
        String oldPath = getComboText(oldDirCombo);
        String newPath = getComboText(newDirCombo);
        File oldDir = new File(oldPath);
        File newDir = new File(newPath);

        if (!oldDir.isDirectory() || !newDir.isDirectory()) {
            JOptionPane.showMessageDialog(this, "有効なフォルダを指定してください。", "エラー", JOptionPane.ERROR_MESSAGE);
            return null;
        }

        String outPath = getComboText(outDirCombo);
        if (outPath.isEmpty()) {
            JOptionPane.showMessageDialog(this, "出力先フォルダを指定してください。", "エラー", JOptionPane.ERROR_MESSAGE);
            return null;
        }

        File[] oldFiles = oldDir.listFiles((d, name) -> name.matches("(?i).*\\.(png|jpg|jpeg|bmp)"));
        if (oldFiles == null || oldFiles.length == 0) {
            JOptionPane.showMessageDialog(this, "旧フォルダに画像がありません。", "エラー", JOptionPane.ERROR_MESSAGE);
            return null;
        }

        addToHistory(oldDirCombo, oldPath);
        addToHistory(newDirCombo, newPath);
        File outDir = new File(outPath);
        outDir.mkdirs();
        addToHistory(outDirCombo, outPath);
        saveHistory();

        int blockSize = blockSizeSlider.getValue();
        int threshold = thresholdSlider.getValue();
        boolean trimMargins = trimMarginsCheck.isSelected();

        log("パラメータ: ブロックサイズ=" + blockSize + ", しきい値=" + threshold
                + ", 余白削除=" + (trimMargins ? "ON" : "OFF")
                + ", 画像出力=" + (jpegFormatRadio.isSelected() ? "JPEG変換" : "変換なし")
                + ", 画像2分割=" + (splitDisplayCheck.isSelected() ? "ON" : "OFF")
                + (splitDisplayCheck.isSelected()
                        ? ", 先頭=" + splitHeadHeightSpinner.getValue() + "px, 末尾=" + splitTailHeightSpinner.getValue() + "px"
                        : ""));

        return new ComparisonStart(oldDir, newDir, outDir, oldFiles, blockSize, threshold, trimMargins);
    }

    private List<ImageComparator.Result> compareImages(ComparisonStart start) {
        List<ImageComparator.Result> results = new ArrayList<>();
        for (File oldFile : start.oldFiles()) {
            File newFile = new File(start.newDir(), oldFile.getName());
            if (!newFile.exists()) {
                log(oldFile.getName() + " : 新フォルダに存在しません。スキップ。");
                continue;
            }
            try {
                var result = ImageComparator.compare(
                        oldFile, newFile, start.blockSize(), start.threshold(), start.trimMargins());
                results.add(result);
                String line = result.fileName() + " : 差分 " + String.format("%.2f%%", result.diffPercent());
                if (result.textDiffPercent() >= 0) {
                    line += ", テキスト " + String.format("%.1f%%", result.textDiffPercent());
                }
                log(line);
            } catch (Exception ex) {
                log(oldFile.getName() + " : エラー - " + ex.getMessage());
            }
        }
        if (results.isEmpty()) {
            log("比較対象がありません。");
        }
        return results;
    }

    private void clearLog() {
        logArea.setText("");
        logArea.setCaretPosition(0);
    }

    private void runReportTask(ReportTask task) {
        if (reportInProgress) {
            return;
        }
        logSessionId++;
        clearLog();
        ComparisonStart start = beginComparison();
        if (start == null) {
            return;
        }

        reportInProgress = true;
        setReportButtonsEnabled(false);

        new SwingWorker<ReportOutcome, Void>() {
            @Override
            protected ReportOutcome doInBackground() {
                List<ImageComparator.Result> results = compareImages(start);
                if (results.isEmpty()) {
                    return ReportOutcome.finished(null);
                }
                ComparisonContext ctx = new ComparisonContext(results, start.oldDir(), start.newDir(), start.outDir());
                try {
                    return ReportOutcome.finished(task.run(ctx));
                } catch (Exception ex) {
                    return ReportOutcome.failed(ex);
                }
            }

            @Override
            protected void done() {
                reportInProgress = false;
                setReportButtonsEnabled(true);
                try {
                    ReportOutcome outcome = get();
                    if (outcome == null) {
                        return;
                    }
                    if (outcome.error() != null) {
                        logReportError(outcome.error());
                    } else if (outcome.dialogMessage() != null) {
                        JOptionPane.showMessageDialog(
                                App.this, outcome.dialogMessage(), "完了", JOptionPane.INFORMATION_MESSAGE);
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    log("処理が中断されました。");
                } catch (java.util.concurrent.ExecutionException ex) {
                    Throwable cause = ex.getCause();
                    if (cause instanceof Exception e) {
                        logReportError(e);
                    } else {
                        log("レポート出力エラー: " + ex.getMessage());
                    }
                }
            }
        }.execute();
    }

    private void setReportButtonsEnabled(boolean enabled) {
        if (htmlReportButton != null) {
            htmlReportButton.setEnabled(enabled);
        }
        if (pdfReportButton != null) {
            pdfReportButton.setEnabled(enabled);
        }
    }

    private void writeCsvReport(ComparisonContext ctx) throws IOException {
        File csvFile = new File(ctx.outDir(), "report.csv");
        ReportGenerator.writeCsv(ctx.results(), csvFile);
        log("CSV出力: " + csvFile.getAbsolutePath());
    }

    private void createHtmlReport() {
        ReportGenerator.ImageFormat imageFormat = getImageFormat();
        float jpegQuality = jpegQualitySlider.getValue() / 100.0f;
        int splitHeadHeight = getSplitHeadHeight();
        int splitTailHeight = getSplitTailHeight();
        runReportTask(ctx -> {
            writeCsvReport(ctx);
            File htmlFile = new File(ctx.outDir(), "report.html");
            ReportGenerator.writeHtml(
                    ctx.results(),
                    ctx.oldDir(),
                    ctx.newDir(),
                    htmlFile,
                    imageFormat,
                    jpegQuality,
                    splitHeadHeight,
                    splitTailHeight);
            log("HTML出力: " + htmlFile.getAbsolutePath());
            return "HTMLレポート出力完了";
        });
    }

    private void createPdfReport() {
        int splitHeadHeight = getSplitHeadHeight();
        int splitTailHeight = getSplitTailHeight();
        long maxSizeBytes = getPdfMaxSizeBytes();
        runReportTask(ctx -> {
            writeCsvReport(ctx);
            File pdfFile = new File(ctx.outDir(), "report.pdf");
            List<File> pdfFiles = PdfReportGenerator.writePdf(
                    ctx.results(),
                    ctx.oldDir(),
                    ctx.newDir(),
                    pdfFile,
                    splitHeadHeight,
                    splitTailHeight,
                    maxSizeBytes);
            for (File file : pdfFiles) {
                String size = PdfReportGenerator.formatFileSize(file.length());
                log("PDF出力: " + file.getAbsolutePath() + " (" + size + ")");
                if (maxSizeBytes > 0 && file.length() > maxSizeBytes) {
                    log("  警告: 上限 " + PdfReportGenerator.formatFileSize(maxSizeBytes)
                            + " を超えています（1件のみの分割ファイル）");
                }
            }
            if (pdfFiles.size() > 1) {
                log("PDF を " + pdfFiles.size() + " ファイルに分割しました（上限 "
                        + PdfReportGenerator.formatFileSize(maxSizeBytes) + "）");
            }
            return pdfFiles.size() > 1
                    ? "PDFレポート出力完了（" + pdfFiles.size() + " ファイルに分割）"
                    : "PDFレポート出力完了";
        });
    }

    private void logReportError(Exception ex) {
        log("レポート出力エラー: " + ex.getMessage());
        Throwable cause = ex.getCause();
        while (cause != null) {
            log("  原因: " + cause.getMessage());
            cause = cause.getCause();
        }
    }

    private void loadHistory() {
        if (!Files.exists(HISTORY_FILE)) return;
        try {
            Properties props = new Properties();
            props.load(Files.newBufferedReader(HISTORY_FILE));
            loadComboItems(oldDirCombo, props.getProperty("old", ""));
            loadComboItems(newDirCombo, props.getProperty("new", ""));
            loadComboItems(outDirCombo, props.getProperty("out", ""));
            blockSizeSlider.setValue(Integer.parseInt(props.getProperty("blockSize", "1")));
            thresholdSlider.setValue(Integer.parseInt(props.getProperty("threshold", "10")));
            jpegQualitySlider.setValue(Integer.parseInt(props.getProperty("jpegQuality", "50")));
            if ("jpeg".equals(props.getProperty("imageFormat", "original"))) {
                jpegFormatRadio.setSelected(true);
            } else {
                originalFormatRadio.setSelected(true);
            }
            trimMarginsCheck.setSelected(Boolean.parseBoolean(props.getProperty("trimMargins", "false")));
            if (props.containsKey("splitHeadHeight") && props.containsKey("splitTailHeight")) {
                splitHeadHeightSpinner.setValue(Integer.parseInt(props.getProperty("splitHeadHeight", "1500")));
                splitTailHeightSpinner.setValue(Integer.parseInt(props.getProperty("splitTailHeight", "1500")));
            } else {
                int legacyMaxH = Integer.parseInt(props.getProperty("maxDisplayHeight", "3000"));
                int half = legacyMaxH > 0 ? legacyMaxH / 2 : 1500;
                splitHeadHeightSpinner.setValue(half);
                splitTailHeightSpinner.setValue(half);
            }
            if (props.containsKey("splitDisplay")) {
                splitDisplayCheck.setSelected(Boolean.parseBoolean(props.getProperty("splitDisplay")));
            } else {
                int legacyMaxH = Integer.parseInt(props.getProperty("maxDisplayHeight", "3000"));
                splitDisplayCheck.setSelected(legacyMaxH > 0);
            }
            updateSplitDisplayEnabled();
            updateJpegQualityEnabled();
            pdfMaxSizeSpinner.setValue(Integer.parseInt(props.getProperty("pdfMaxSizeMb", "10")));
        } catch (IOException ignored) {}
    }

    private void saveHistory() {
        try {
            Properties props = new Properties();
            props.setProperty("old", comboItemsToString(oldDirCombo));
            props.setProperty("new", comboItemsToString(newDirCombo));
            props.setProperty("out", comboItemsToString(outDirCombo));
            props.setProperty("blockSize", String.valueOf(blockSizeSlider.getValue()));
            props.setProperty("threshold", String.valueOf(thresholdSlider.getValue()));
            props.setProperty("jpegQuality", String.valueOf(jpegQualitySlider.getValue()));
            props.setProperty("imageFormat", jpegFormatRadio.isSelected() ? "jpeg" : "original");
            props.setProperty("trimMargins", String.valueOf(trimMarginsCheck.isSelected()));
            props.setProperty("splitDisplay", String.valueOf(splitDisplayCheck.isSelected()));
            props.setProperty("splitHeadHeight", String.valueOf(splitHeadHeightSpinner.getValue()));
            props.setProperty("splitTailHeight", String.valueOf(splitTailHeightSpinner.getValue()));
            props.setProperty("pdfMaxSizeMb", String.valueOf(pdfMaxSizeSpinner.getValue()));
            props.store(Files.newBufferedWriter(HISTORY_FILE), "Screen Diff History");
            saveAiPrompt();
        } catch (IOException ignored) {}
    }

    private static void loadComboItems(JComboBox<String> combo, String joined) {
        if (joined.isEmpty()) return;
        for (String s : joined.split("\\|")) {
            if (!s.isEmpty()) combo.addItem(s);
        }
    }

    private static String comboItemsToString(JComboBox<String> combo) {
        StringJoiner sj = new StringJoiner("|");
        for (int i = 0; i < combo.getItemCount(); i++) sj.add(combo.getItemAt(i));
        return sj.toString();
    }

    private void log(String msg) {
        long session = logSessionId;
        Runnable append = () -> {
            if (session != logSessionId) {
                return;
            }
            logArea.append(msg + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        };
        if (SwingUtilities.isEventDispatchThread()) {
            append.run();
        } else {
            SwingUtilities.invokeLater(append);
        }
    }

    public static void main(String[] args) {
        FlatLightLaf.setup();
        SwingUtilities.invokeLater(() -> new App().setVisible(true));
    }
}
