package com.screendiff;

import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;

public class App extends JFrame {

    private static final Path HISTORY_FILE = Path.of(System.getProperty("user.home"), ".screendiff_history");
    private static final Path AI_IMAGE_PROMPT_FILE =
            Path.of(System.getProperty("user.home"), ".screendiff_ai_image_prompt");
    /** AIプロンプト … 比較タブからコピー時に置換 */
    static final String VAR_OLD_DIR = "${OLD_DIR}";
    static final String VAR_NEW_DIR = "${NEW_DIR}";
    static final String VAR_INCLUDE_SUBFOLDERS = "${INCLUDE_SUBFOLDERS}";
    private static final String LEGACY_OLD_DIR_PLACEHOLDER = "（旧フォルダのフルパスを記載）";
    private static final String LEGACY_NEW_DIR_PLACEHOLDER = "（新フォルダのフルパスを記載）";
    private static final int MAX_HISTORY = 20;

    private final JComboBox<String> oldDirCombo = createEditableCombo();
    private final JComboBox<String> newDirCombo = createEditableCombo();
    private final JComboBox<String> outDirCombo = createEditableCombo();
    private final JSlider blockSizeSlider = new JSlider(1, 20, 1);
    private final JSlider thresholdSlider = new JSlider(0, 255, 10);
    private final JSlider jpegQualitySlider = new JSlider(10, 100, 50);
    private final JSpinner blockSizeSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 20, 1));
    private final JSpinner thresholdSpinner = new JSpinner(new SpinnerNumberModel(10, 0, 255, 1));
    private final JSpinner jpegQualitySpinner = new JSpinner(new SpinnerNumberModel(50, 10, 100, 5));
    private final JSpinner cropThresholdSpinner = new JSpinner(new SpinnerNumberModel(3000, 1, null, 100));
    private final JSpinner cropAmountSpinner = new JSpinner(new SpinnerNumberModel(1000, 1, null, 100));
    private final JRadioButton pngFormatRadio = new JRadioButton("PNG変換", true);
    private final JRadioButton jpegFormatRadio = new JRadioButton("JPEG変換");
    private final JRadioButton htmlInlineRadio = new JRadioButton("HTML内に埋め込み", true);
    private final JRadioButton htmlExternalRadio = new JRadioButton("別フォルダ (report_assets/)");
    private final ButtonGroup imageFormatGroup = new ButtonGroup();
    private final ButtonGroup htmlImagePlacementGroup = new ButtonGroup();
    private final JLabel jpegQualityLabel = new JLabel("JPEG品質(%):");
    private final JCheckBox includeSubfoldersCheck = new JCheckBox("サブフォルダも比較する", false);
    private final JCheckBox trimMarginsCheck = new JCheckBox("四隅の余白削除", false);
    private final JCheckBox cropImageCheck = new JCheckBox("画像を先頭から切り取る", true);
    private final JLabel cropThresholdLabel = new JLabel("切取条件(px):");
    private final JLabel cropAmountLabel = new JLabel("実際の切取量(px):");
    private final JTextArea logArea = new JTextArea(10, 50);
    private final JTextArea aiImagePromptArea = new JTextArea(20, 60);
    private JButton htmlReportButton;
    private JButton cancelReportButton;
    private SwingWorker<ReportOutcome, Void> activeReportWorker;
    private volatile boolean reportInProgress;
    /** 処理開始ごとに増やし、前回ジョブの invokeLater ログを無視する */
    private volatile long logSessionId;

    public App() {
        super("Screen Diff Tool");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout(10, 10));

        loadHistory();
        linkSettingControls();
        initAiPromptAreas();

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("比較", createCompareTab());
        tabs.addTab("設定", createSettingsTab());
        tabs.addTab("AIプロンプト", createAiImageTab());

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

        c.gridx = 0; c.gridy = 3; c.gridwidth = 3; c.weightx = 1;
        includeSubfoldersCheck.setToolTipText("ON のとき、旧・新フォルダ以下のサブフォルダ内の画像も比較します（相対パスが一致するものをペアにします）");
        inputPanel.add(includeSubfoldersCheck, c);
        c.gridwidth = 1;

        htmlReportButton = new JButton("HTMLレポート作成");
        htmlReportButton.addActionListener(e -> createHtmlReport());
        cancelReportButton = new JButton("中断");
        cancelReportButton.setEnabled(false);
        cancelReportButton.addActionListener(e -> requestReportCancel());
        JButton copyImageAiPromptBtn = new JButton("画像比較用AIプロンプト");
        copyImageAiPromptBtn.setToolTipText(
                "AIプロンプト タブのプロンプトをコピー（" + VAR_OLD_DIR + " / " + VAR_NEW_DIR + " を比較タブのフォルダパスに置換）");
        copyImageAiPromptBtn.addActionListener(e -> copyImageAiPromptToClipboard());
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        btnPanel.add(htmlReportButton);
        btnPanel.add(cancelReportButton);
        btnPanel.add(copyImageAiPromptBtn);
        c.gridx = 1; c.gridy = 4;
        inputPanel.add(btnPanel, c);

        panel.add(inputPanel, BorderLayout.NORTH);

        logArea.setEditable(false);
        panel.add(new JScrollPane(logArea), BorderLayout.CENTER);
        return panel;
    }

    private void initAiPromptAreas() {
        configurePromptArea(aiImagePromptArea, loadImagePromptText());
    }

    private static void configurePromptArea(JTextArea area, String text) {
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setText(text);
    }

    private JPanel createAiPromptTab(JTextArea promptArea, String label) {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        panel.add(new JLabel(label), BorderLayout.NORTH);
        panel.add(new JScrollPane(promptArea), BorderLayout.CENTER);
        return panel;
    }

    private JPanel createAiImageTab() {
        JPanel panel = createAiPromptTab(aiImagePromptArea, "画像比較用AIプロンプト:");
        JLabel hint = new JLabel(
                "<html>初期テンプレートは <code>" + VAR_OLD_DIR + "</code> / <code>" + VAR_NEW_DIR + "</code> / "
                        + "<code>" + VAR_INCLUDE_SUBFOLDERS + "</code> を使用します。"
                        + "比較タブからコピー時に比較タブの設定へ置換します。</html>");
        hint.setBorder(BorderFactory.createEmptyBorder(8, 10, 0, 10));
        panel.add(hint, BorderLayout.SOUTH);
        return panel;
    }

    private void copyImageAiPromptToClipboard() {
        saveImagePrompt();
        String oldPath = resolveFolderPathForPrompt(getComboText(oldDirCombo));
        String newPath = resolveFolderPathForPrompt(getComboText(newDirCombo));
        if (oldPath == null || newPath == null) {
            showError("旧フォルダと新フォルダを指定してください。");
            return;
        }
        String text = substituteImagePromptVariables(aiImagePromptArea.getText(), oldPath, newPath);
        copyPromptToClipboard(text, "画像比較用AIプロンプト");
    }

    private String substituteImagePromptVariables(String template, String oldPath, String newPath) {
        return template
                .replace(VAR_OLD_DIR, oldPath)
                .replace(VAR_NEW_DIR, newPath)
                .replace(VAR_INCLUDE_SUBFOLDERS, includeSubfoldersCheck.isSelected() ? "ON" : "OFF");
    }

    private String loadImagePromptText() {
        String defaultPrompt = AiImagePromptDefaults.loadDefault();
        if (!Files.exists(AI_IMAGE_PROMPT_FILE)) {
            writePromptFile(AI_IMAGE_PROMPT_FILE, defaultPrompt);
            return defaultPrompt;
        }
        try {
            String loaded = Files.readString(AI_IMAGE_PROMPT_FILE, StandardCharsets.UTF_8);
            String normalized = normalizeImagePromptVariables(loaded);
            if (!normalized.equals(loaded)) {
                writePromptFile(AI_IMAGE_PROMPT_FILE, normalized);
            }
            return normalized;
        } catch (IOException ignored) {
        }
        return defaultPrompt;
    }

    private String normalizeImagePromptVariables(String text) {
        if (text == null || text.isBlank()) {
            return AiImagePromptDefaults.loadDefault();
        }
        return text.replace(LEGACY_OLD_DIR_PLACEHOLDER, VAR_OLD_DIR)
                .replace(LEGACY_NEW_DIR_PLACEHOLDER, VAR_NEW_DIR);
    }

    private static String resolveFolderPathForPrompt(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        return Paths.get(path.trim()).toAbsolutePath().normalize().toString();
    }

    private void copyPromptToClipboard(String text, String label) {
        if (text.isBlank()) {
            showError("プロンプトが空です。");
            return;
        }
        StringSelection selection = new StringSelection(text);
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, selection);
        log(label + "をクリップボードにコピーしました");
        JOptionPane.showMessageDialog(this, "クリップボードにコピーしました", "完了", JOptionPane.INFORMATION_MESSAGE);
    }

    private void saveImagePrompt() {
        writePromptFile(AI_IMAGE_PROMPT_FILE, aiImagePromptArea.getText());
    }

    private static void writePromptFile(Path file, String text) {
        try {
            Files.writeString(file, text, StandardCharsets.UTF_8);
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
        pngFormatRadio.setToolTipText("HTML レポートに埋め込む旧・新画像を PNG で出力");
        jpegFormatRadio.setToolTipText("HTML レポートに埋め込む旧・新画像を JPEG で出力");
        imageFormatGroup.add(pngFormatRadio);
        imageFormatGroup.add(jpegFormatRadio);
        JPanel formatPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        formatPanel.add(pngFormatRadio);
        formatPanel.add(jpegFormatRadio);
        var formatListener = (java.awt.event.ItemListener) e -> {
            if (e.getStateChange() == java.awt.event.ItemEvent.SELECTED) {
                updateJpegQualityEnabled();
            }
        };
        pngFormatRadio.addItemListener(formatListener);
        jpegFormatRadio.addItemListener(formatListener);
        panel.add(formatPanel, c);
        c.gridwidth = 1;

        c.gridx = 0; c.gridy = 4; c.weightx = 0;
        panel.add(new JLabel("HTML画像配置:"), c);
        c.gridx = 1; c.gridwidth = 2; c.weightx = 1;
        htmlInlineRadio.setToolTipText("画像を HTML 内に Base64 で埋め込み、report.html 1 ファイルにまとめます");
        htmlExternalRadio.setToolTipText("画像を report_assets/ フォルダに書き出し、HTML から参照します");
        htmlImagePlacementGroup.add(htmlInlineRadio);
        htmlImagePlacementGroup.add(htmlExternalRadio);
        JPanel placementPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        placementPanel.add(htmlInlineRadio);
        placementPanel.add(htmlExternalRadio);
        panel.add(placementPanel, c);
        c.gridwidth = 1;

        c.gridx = 0; c.gridy = 5; c.weightx = 0;
        panel.add(jpegQualityLabel, c);
        c.gridx = 1; c.weightx = 1;
        panel.add(jpegQualitySlider, c);
        c.gridx = 2; c.weightx = 0;
        panel.add(jpegQualitySpinner, c);
        updateJpegQualityEnabled();

        c.gridx = 0; c.gridy = 6; c.gridwidth = 3; c.weightx = 1;
        cropImageCheck.setToolTipText("ON のとき、切取条件を満たす画像の先頭から実際の切取量まで切り取ります");
        panel.add(cropImageCheck, c);
        c.gridwidth = 1;

        c.gridx = 0; c.gridy = 7; c.weightx = 0;
        cropThresholdLabel.setToolTipText("画像高さがこの値より大きい場合に切り取りを適用します");
        cropThresholdSpinner.setToolTipText(cropThresholdLabel.getToolTipText());
        panel.add(cropThresholdLabel, c);
        c.gridx = 1; c.gridwidth = 2; c.weightx = 1;
        panel.add(cropThresholdSpinner, c);
        c.gridwidth = 1;

        c.gridx = 0; c.gridy = 8; c.weightx = 0;
        cropAmountLabel.setToolTipText("切り取り時に先頭から残す高さ（px）");
        cropAmountSpinner.setToolTipText(cropAmountLabel.getToolTipText());
        panel.add(cropAmountLabel, c);
        c.gridx = 1; c.gridwidth = 2; c.weightx = 1;
        panel.add(cropAmountSpinner, c);
        c.gridwidth = 1;
        cropImageCheck.addItemListener(e -> updateCropEnabled());
        updateCropEnabled();

        c.gridx = 0; c.gridy = 9; c.gridwidth = 3; c.weightx = 1;
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

    private void updateCropEnabled() {
        boolean enabled = cropImageCheck.isSelected();
        cropThresholdLabel.setEnabled(enabled);
        cropThresholdSpinner.setEnabled(enabled);
        cropAmountLabel.setEnabled(enabled);
        cropAmountSpinner.setEnabled(enabled);
    }

    private int getCropThreshold() {
        return cropImageCheck.isSelected() ? (int) cropThresholdSpinner.getValue() : 0;
    }

    private int getCropAmount() {
        return cropImageCheck.isSelected() ? (int) cropAmountSpinner.getValue() : 0;
    }

    private ReportGenerator.ImageFormat getImageFormat() {
        return jpegFormatRadio.isSelected()
                ? ReportGenerator.ImageFormat.JPEG
                : ReportGenerator.ImageFormat.PNG;
    }

    private ReportGenerator.HtmlImagePlacement getHtmlImagePlacement() {
        return htmlExternalRadio.isSelected()
                ? ReportGenerator.HtmlImagePlacement.EXTERNAL
                : ReportGenerator.HtmlImagePlacement.INLINE;
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
                showError("フォルダが存在しません: " + path);
                return;
            }
            try {
                Desktop.getDesktop().open(dir);
            } catch (IOException ex) {
                showError("エクスプローラを開けません: " + ex.getMessage());
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
            List<ImageComparator.Result> results,
            File oldDir,
            File newDir,
            File outDir,
            boolean trimMargins) {}

    private record ComparisonStart(
            File oldDir,
            File newDir,
            File outDir,
            List<String> relativeImagePaths,
            List<String> newRelativeImagePaths,
            int blockSize,
            int threshold,
            boolean trimMargins,
            int cropThreshold,
            int cropAmount) {}

    @FunctionalInterface
    private interface ReportTask {
        String run(ComparisonContext ctx, java.util.function.BooleanSupplier cancelled) throws Exception;
    }

    private record ReportOutcome(String dialogMessage, Throwable error, boolean wasCancelled) {
        static ReportOutcome finished(String message) {
            return new ReportOutcome(message, null, false);
        }

        static ReportOutcome failed(Throwable error) {
            return new ReportOutcome(null, error, false);
        }

        static ReportOutcome cancelled() {
            return new ReportOutcome(null, null, true);
        }
    }

    /** EDT 上で入力検証とログ初期化のみ行う */
    private ComparisonStart beginComparison() {
        String oldPath = getComboText(oldDirCombo);
        String newPath = getComboText(newDirCombo);
        File oldDir = new File(oldPath);
        File newDir = new File(newPath);

        if (!oldDir.isDirectory() || !newDir.isDirectory()) {
            showError("有効なフォルダを指定してください。");
            return null;
        }

        String outPath = getComboText(outDirCombo);
        if (outPath.isEmpty()) {
            showError("出力先フォルダを指定してください。");
            return null;
        }

        boolean includeSubfolders = includeSubfoldersCheck.isSelected();
        List<String> relativePaths;
        List<String> newRelativePaths;
        try {
            relativePaths = ImageScanUtil.listRelativeImagePaths(oldDir, includeSubfolders);
            newRelativePaths = ImageScanUtil.listRelativeImagePaths(newDir, includeSubfolders);
        } catch (IOException e) {
            showError("画像一覧を取得できません: " + e.getMessage());
            return null;
        }
        if (relativePaths.isEmpty()) {
            showError(includeSubfolders
                    ? "旧フォルダ（サブフォルダ含む）に画像がありません。"
                    : "旧フォルダに画像がありません。");
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
                + ", サブフォルダ=" + (includeSubfolders ? "ON" : "OFF")
                + ", 余白削除=" + (trimMargins ? "ON" : "OFF")
                + ", 画像出力=" + (jpegFormatRadio.isSelected() ? "JPEG変換" : "PNG変換")
                + ", HTML画像配置=" + (htmlExternalRadio.isSelected() ? "別フォルダ" : "HTML内")
                + ", 先頭切り取り=" + (cropImageCheck.isSelected() ? "ON" : "OFF")
                + (cropImageCheck.isSelected()
                ? ", 切取条件=" + cropThresholdSpinner.getValue() + "px, 切取量="
                        + cropAmountSpinner.getValue() + "px"
                : ""));

        return new ComparisonStart(
                oldDir, newDir, outDir, relativePaths, newRelativePaths, blockSize, threshold, trimMargins,
                getCropThreshold(), getCropAmount());
    }

    private boolean compareImages(
            ComparisonStart start,
            List<ImageComparator.Result> results,
            java.util.function.BooleanSupplier cancelled) {
        List<ImageTextGroupUtil.ComparisonUnit> units = ImageTextGroupUtil.buildComparisonUnits(
                start.relativeImagePaths(), start.newRelativeImagePaths());
        for (ImageTextGroupUtil.ComparisonUnit unit : units) {
            if (cancelled.getAsBoolean()) {
                log("処理を中断しました。");
                releaseResultImages(results);
                return false;
            }
            try {
                CombinedSide oldSide = buildCombinedSide(
                        start.oldDir(), unit.oldImagePaths(), start.trimMargins(),
                        start.cropThreshold(), start.cropAmount());
                CombinedSide newSide = buildCombinedSide(
                        start.newDir(), unit.newImagePaths(), start.trimMargins(),
                        start.cropThreshold(), start.cropAmount());
                var result = ImageComparator.compare(
                        oldSide.image(),
                        newSide.image(),
                        oldSide.reportWidth(),
                        oldSide.reportHeight(),
                        oldSide.anyCropped(),
                        newSide.reportWidth(),
                        newSide.reportHeight(),
                        newSide.anyCropped(),
                        start.oldDir(),
                        start.newDir(),
                        unit.displayName(),
                        unit.textBaseName(),
                        start.blockSize(),
                        start.threshold());
                results.add(result);
                String line = result.fileName() + " : 差分 " + String.format("%.2f%%", result.diffPercent());
                if (result.textDiffLines() >= 0) {
                    line += ", テキスト " + result.textDiffLines() + "行";
                }
                if (unit.isCombined()) {
                    line += combineCountLabel(unit.oldImagePaths().size(), unit.newImagePaths().size());
                }
                log(line);
            } catch (OutOfMemoryError oom) {
                logError(unit.displayName() + " : メモリ不足（画像が大きすぎます）。設定でJPEG変換を有効にするか、件数を減らしてください。");
            } catch (Throwable t) {
                logError(unit.displayName() + " : 比較エラー", t);
            }
        }
        logSkippedGroups(start.relativeImagePaths(), start.newRelativeImagePaths(), units);
        if (results.isEmpty()) {
            log("比較対象がありません。");
        }
        return true;
    }

    private record CombinedSide(BufferedImage image, int reportWidth, int reportHeight, boolean anyCropped) {}

    private CombinedSide buildCombinedSide(
            File root,
            List<String> imagePaths,
            boolean trimMargins,
            int cropThreshold,
            int cropAmount) throws IOException {
        List<ImageComparator.PrepareResult> prepared = new ArrayList<>();
        for (String path : imagePaths) {
            BufferedImage loaded = ImageComparator.loadFromFile(ImageScanUtil.resolve(root, path));
            prepared.add(ImageComparator.prepare(loaded, trimMargins, cropThreshold, cropAmount));
        }
        int width = 0;
        int height = 0;
        boolean anyCropped = false;
        for (ImageComparator.PrepareResult part : prepared) {
            width = Math.max(width, part.width());
            height += part.height();
            anyCropped |= part.cropped();
        }
        if (prepared.size() > 1) {
            height += ImageCombiner.GAP_PX * (prepared.size() - 1);
        }

        List<BufferedImage> images = prepared.stream().map(ImageComparator.PrepareResult::image).toList();
        BufferedImage combined = prepared.size() == 1
                ? images.get(0)
                : ImageCombiner.combineVertically(images, ImageCombiner.GAP_PX);
        if (prepared.size() > 1) {
            for (ImageComparator.PrepareResult part : prepared) {
                if (part.image() != combined) {
                    ImageScaleUtil.dispose(part.image());
                }
            }
        }
        return new CombinedSide(combined, width, height, anyCropped);
    }

    private static String combineCountLabel(int oldCount, int newCount) {
        if (oldCount == newCount) {
            return " （" + oldCount + "枚結合）";
        }
        return " （旧" + oldCount + "枚+新" + newCount + "枚結合）";
    }

    private void logSkippedGroups(
            List<String> oldPaths,
            List<String> newPaths,
            List<ImageTextGroupUtil.ComparisonUnit> units) {
        Set<String> compared = new HashSet<>();
        for (ImageTextGroupUtil.ComparisonUnit unit : units) {
            compared.add(unit.textBaseName());
        }
        Set<String> checked = new HashSet<>();
        for (String path : oldPaths) {
            String textBase = ImageTextGroupUtil.resolveTextBase(path, oldPaths, newPaths);
            if (checked.contains(textBase)) {
                continue;
            }
            checked.add(textBase);
            if (compared.contains(textBase)) {
                continue;
            }
            List<String> newMembers = ImageTextGroupUtil.collectGroupPaths(
                    newPaths,
                    ImageTextGroupUtil.dirFromTextBase(textBase),
                    ImageTextGroupUtil.baseNameFromTextBase(textBase));
            if (newMembers.isEmpty()) {
                log(textBase + " : 新フォルダに対応画像がありません。スキップ。");
            }
        }
    }

    private static void releaseResultImages(List<ImageComparator.Result> results) {
        for (int i = 0; i < results.size(); i++) {
            results.set(i, ImageComparator.releaseImages(results.get(i)));
        }
    }

    private void requestReportCancel() {
        if (activeReportWorker != null) {
            activeReportWorker.cancel(true);
            log("中断を要求しました…");
        }
    }

    private void clearLog() {
        logArea.setText("");
        logArea.setCaretPosition(0);
    }

    private void runReportTask(ReportTask task) {
        if (reportInProgress) {
            logError("処理が実行中です。完了までお待ちください。");
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

        SwingWorker<ReportOutcome, Void> worker = new SwingWorker<>() {
            @Override
            protected ReportOutcome doInBackground() {
                List<ImageComparator.Result> results = new ArrayList<>();
                if (!compareImages(start, results, this::isCancelled)) {
                    return ReportOutcome.cancelled();
                }
                if (isCancelled()) {
                    releaseResultImages(results);
                    log("処理を中断しました。");
                    return ReportOutcome.cancelled();
                }
                if (results.isEmpty()) {
                    return ReportOutcome.finished(null);
                }
                ComparisonContext ctx = new ComparisonContext(
                        results, start.oldDir(), start.newDir(), start.outDir(), start.trimMargins());
                try {
                    return ReportOutcome.finished(task.run(ctx, this::isCancelled));
                } catch (InterruptedIOException e) {
                    log("処理を中断しました。");
                    return ReportOutcome.cancelled();
                } catch (Throwable t) {
                    return ReportOutcome.failed(t);
                }
            }

            @Override
            protected void done() {
                reportInProgress = false;
                activeReportWorker = null;
                setReportButtonsEnabled(true);
                try {
                    ReportOutcome outcome = get();
                    if (outcome == null) {
                        return;
                    }
                    if (outcome.wasCancelled()) {
                        return;
                    }
                    if (outcome.error() != null) {
                        logError("レポート出力エラー", outcome.error());
                        JOptionPane.showMessageDialog(
                                App.this,
                                "処理中にエラーが発生しました。ログを確認してください。",
                                "エラー",
                                JOptionPane.ERROR_MESSAGE);
                    } else if (outcome.dialogMessage() != null) {
                        JOptionPane.showMessageDialog(
                                App.this, outcome.dialogMessage(), "完了", JOptionPane.INFORMATION_MESSAGE);
                    }
                } catch (java.util.concurrent.CancellationException ex) {
                    // cancel(true) による正常な中断（get() はキャンセル時にこの例外を投げる）
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    log("処理を中断しました。");
                } catch (java.util.concurrent.ExecutionException ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    logError("レポート出力エラー", cause);
                    JOptionPane.showMessageDialog(
                            App.this,
                            "処理中にエラーが発生しました。ログを確認してください。",
                            "エラー",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        activeReportWorker = worker;
        worker.execute();
    }

    private void setReportButtonsEnabled(boolean enabled) {
        if (htmlReportButton != null) {
            htmlReportButton.setEnabled(enabled);
        }
        if (cancelReportButton != null) {
            cancelReportButton.setEnabled(!enabled);
        }
    }

    private void writeCsvReport(ComparisonContext ctx) throws IOException {
        File csvFile = new File(ctx.outDir(), "report.csv");
        ReportGenerator.writeCsv(ctx.results(), csvFile);
        log("CSV出力: " + csvFile.getAbsolutePath());
    }

    private void createHtmlReport() {
        ReportGenerator.ImageFormat imageFormat = getImageFormat();
        ReportGenerator.HtmlImagePlacement imagePlacement = getHtmlImagePlacement();
        float jpegQuality = jpegQualitySlider.getValue() / 100.0f;
        int cropThreshold = getCropThreshold();
        int cropAmount = getCropAmount();
        runReportTask((ctx, cancelled) -> {
            if (cancelled.getAsBoolean()) {
                throw new InterruptedIOException("中断されました");
            }
            writeCsvReport(ctx);
            if (cancelled.getAsBoolean()) {
                throw new InterruptedIOException("中断されました");
            }
            File htmlFile = new File(ctx.outDir(), "report.html");
            ReportGenerator.writeHtml(
                    ctx.results(),
                    ctx.oldDir(),
                    ctx.newDir(),
                    htmlFile,
                    imageFormat,
                    jpegQuality,
                    cropThreshold,
                    cropAmount,
                    ctx.trimMargins(),
                    imagePlacement,
                    cancelled);
            log("HTML出力: " + htmlFile.getAbsolutePath()
                    + (imagePlacement == ReportGenerator.HtmlImagePlacement.INLINE
                    ? "（画像は HTML 内に埋め込み）"
                    : "（画像は report_assets/ に出力）"));
            return "HTMLレポート出力完了";
        });
    }

    private void showError(String message) {
        logError(message);
        JOptionPane.showMessageDialog(this, message, "エラー", JOptionPane.ERROR_MESSAGE);
    }

    private void logError(String message) {
        log("【エラー】 " + message);
    }

    private void logError(String context, Throwable t) {
        log("【エラー】 " + context + ": " + throwableMessage(t));
        logThrowableCauses(t.getCause());
    }

    private void logThrowableCauses(Throwable cause) {
        while (cause != null) {
            log("  原因: " + throwableMessage(cause));
            cause = cause.getCause();
        }
    }

    private static String throwableMessage(Throwable t) {
        if (t == null) {
            return "(不明)";
        }
        String msg = t.getMessage();
        if (msg != null && !msg.isBlank()) {
            return t.getClass().getSimpleName() + " - " + msg;
        }
        return t.getClass().getSimpleName();
    }

    private void loadCropSettings(Properties props) {
        int legacyHeight = 0;
        if (props.containsKey("cropHeight")) {
            legacyHeight = Integer.parseInt(props.getProperty("cropHeight", "1500"));
        } else if (props.containsKey("splitHeadHeight")) {
            legacyHeight = Integer.parseInt(props.getProperty("splitHeadHeight", "1500"));
        } else if (props.containsKey("maxDisplayHeight")) {
            int legacyMaxH = Integer.parseInt(props.getProperty("maxDisplayHeight", "3000"));
            legacyHeight = legacyMaxH > 0 ? legacyMaxH / 2 : 1500;
        }

        if (props.containsKey("cropThreshold")) {
            cropThresholdSpinner.setValue(Integer.parseInt(props.getProperty("cropThreshold", "3000")));
        } else if (legacyHeight > 0) {
            cropThresholdSpinner.setValue(legacyHeight);
        }

        if (props.containsKey("cropAmount")) {
            cropAmountSpinner.setValue(Integer.parseInt(props.getProperty("cropAmount", "1000")));
        } else if (legacyHeight > 0) {
            cropAmountSpinner.setValue(legacyHeight);
        }

        if (props.containsKey("cropImage")) {
            cropImageCheck.setSelected(Boolean.parseBoolean(props.getProperty("cropImage")));
        } else if (props.containsKey("splitDisplay")) {
            cropImageCheck.setSelected(Boolean.parseBoolean(props.getProperty("splitDisplay")));
        } else if (props.containsKey("maxDisplayHeight")) {
            int legacyMaxH = Integer.parseInt(props.getProperty("maxDisplayHeight", "3000"));
            cropImageCheck.setSelected(legacyMaxH > 0);
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
            if ("jpeg".equals(props.getProperty("imageFormat", "png"))) {
                jpegFormatRadio.setSelected(true);
            } else {
                pngFormatRadio.setSelected(true);
            }
            if ("external".equals(props.getProperty("htmlImagePlacement", "inline"))) {
                htmlExternalRadio.setSelected(true);
            } else {
                htmlInlineRadio.setSelected(true);
            }
            includeSubfoldersCheck.setSelected(Boolean.parseBoolean(props.getProperty("includeSubfolders", "false")));
            trimMarginsCheck.setSelected(Boolean.parseBoolean(props.getProperty("trimMargins", "false")));
            loadCropSettings(props);
            updateCropEnabled();
            updateJpegQualityEnabled();
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
            props.setProperty("imageFormat", jpegFormatRadio.isSelected() ? "jpeg" : "png");
            props.setProperty("htmlImagePlacement", htmlExternalRadio.isSelected() ? "external" : "inline");
            props.setProperty("includeSubfolders", String.valueOf(includeSubfoldersCheck.isSelected()));
            props.setProperty("trimMargins", String.valueOf(trimMarginsCheck.isSelected()));
            props.setProperty("cropImage", String.valueOf(cropImageCheck.isSelected()));
            props.setProperty("cropThreshold", String.valueOf(cropThresholdSpinner.getValue()));
            props.setProperty("cropAmount", String.valueOf(cropAmountSpinner.getValue()));
            props.store(Files.newBufferedWriter(HISTORY_FILE), "Screen Diff History");
            saveImagePrompt();
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
