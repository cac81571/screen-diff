package com.screendiff;

import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.awt.image.BufferedImage;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class PdfReportGenerator {

    private static final float MARGIN = 36f;
    private static final float TITLE_SIZE = 11f;
    private static final float METRICS_SIZE = 9f;
    private static final float HEADER_HEIGHT = 38f;

    private PdfReportGenerator() {}

    /** TTC 読み込み後は PDF 保存完了までコレクションを開いたままにする */
    private static final class FontHandle implements Closeable {
        private final PDFont font;
        private final Closeable resource;

        FontHandle(PDFont font, Closeable resource) {
            this.font = font;
            this.resource = resource;
        }

        PDFont font() {
            return font;
        }

        @Override
        public void close() throws IOException {
            if (resource != null) {
                resource.close();
            }
        }
    }

    public static List<File> writePdf(
            List<ImageComparator.Result> results,
            File oldDir,
            File newDir,
            File output,
            int splitHeadHeight,
            int splitTailHeight,
            long maxSizeBytes) throws IOException {
        if (results.isEmpty()) {
            throw new IOException("PDF 出力対象がありません");
        }
        if (maxSizeBytes <= 0) {
            writeSinglePdf(results, oldDir, newDir, output, splitHeadHeight, splitTailHeight);
            return List.of(output);
        }

        File probeFile = new File(output.getParentFile(), output.getName() + ".probe.part");
        try {
            writeSinglePdf(results, oldDir, newDir, probeFile, splitHeadHeight, splitTailHeight);
            if (probeFile.length() <= maxSizeBytes) {
                Files.move(probeFile.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING);
                return List.of(output);
            }
        } finally {
            Files.deleteIfExists(probeFile.toPath());
        }

        List<File> outputs = new ArrayList<>();
        int index = 0;
        int part = 1;
        while (index < results.size()) {
            List<ImageComparator.Result> chunk = buildResultChunk(
                    results, index, maxSizeBytes, oldDir, newDir, output.getParentFile(),
                    splitHeadHeight, splitTailHeight);
            File partFile = partOutputFile(output, part++);
            writeSinglePdf(chunk, oldDir, newDir, partFile, splitHeadHeight, splitTailHeight);
            outputs.add(partFile);
            index += chunk.size();
        }
        return outputs;
    }

    private static void writeSinglePdf(
            List<ImageComparator.Result> results,
            File oldDir,
            File newDir,
            File output,
            int splitHeadHeight,
            int splitTailHeight) throws IOException {
        File tempFile = new File(output.getParentFile(), output.getName() + ".part");
        try (PDDocument doc = new PDDocument();
             FontHandle fontHandle = loadJapaneseFont(doc)) {
            PDFont font = fontHandle.font();

            for (var r : results) {
                BufferedImage oldImg = ReportGenerator.loadReportImage(
                        r.reportOldImage(), new File(oldDir, r.fileName()));
                BufferedImage newImg = ReportGenerator.loadReportImage(
                        r.reportNewImage(), new File(newDir, r.fileName()));
                String metrics = ReportGenerator.comparisonMetricsLine(r);
                writePdfPagesForImage(doc, font, "旧", r.fileName(), metrics, oldImg, splitHeadHeight, splitTailHeight);
                writePdfPagesForImage(doc, font, "新", r.fileName(), metrics, newImg, splitHeadHeight, splitTailHeight);
            }

            doc.save(tempFile);
            if (tempFile.length() == 0) {
                throw new IOException("PDF の書き込みに失敗しました（出力が空です）");
            }
            Files.move(tempFile.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            if (tempFile.exists()) {
                Files.deleteIfExists(tempFile.toPath());
            }
            throw e;
        }
    }

    private static List<ImageComparator.Result> buildResultChunk(
            List<ImageComparator.Result> results,
            int startIndex,
            long maxSizeBytes,
            File oldDir,
            File newDir,
            File workDir,
            int splitHeadHeight,
            int splitTailHeight) throws IOException {
        List<ImageComparator.Result> chunk = new ArrayList<>();
        chunk.add(results.get(startIndex));
        File probeFile = new File(workDir, ".pdf-chunk-probe.part");
        try {
            writeSinglePdf(chunk, oldDir, newDir, probeFile, splitHeadHeight, splitTailHeight);
            for (int i = startIndex + 1; i < results.size(); i++) {
                List<ImageComparator.Result> trial = new ArrayList<>(chunk);
                trial.add(results.get(i));
                writeSinglePdf(trial, oldDir, newDir, probeFile, splitHeadHeight, splitTailHeight);
                if (probeFile.length() > maxSizeBytes) {
                    break;
                }
                chunk = trial;
            }
        } finally {
            Files.deleteIfExists(probeFile.toPath());
        }
        return chunk;
    }

    private static File partOutputFile(File baseOutput, int partIndex) {
        String name = baseOutput.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : ".pdf";
        return new File(baseOutput.getParentFile(), base + "-" + String.format("%02d", partIndex) + ext);
    }

    static String formatFileSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
    }

    private static void writePdfPagesForImage(
            PDDocument doc,
            PDFont font,
            String label,
            String fileName,
            String metricsLine,
            BufferedImage image,
            int splitHeadHeight,
            int splitTailHeight) throws IOException {
        if (ImageDisplaySplitter.isSplitApplied(image, splitHeadHeight, splitTailHeight)) {
            writeImagePage(doc, font, label + "　" + fileName + "（先頭）", metricsLine,
                    ImageDisplaySplitter.cropHead(image, splitHeadHeight));
            writeImagePage(doc, font, label + "　" + fileName + "（末尾）", metricsLine,
                    ImageDisplaySplitter.cropTail(image, splitTailHeight));
        } else {
            writeImagePage(doc, font, label + "　" + fileName, metricsLine, image);
        }
    }

    private static void writeImagePage(
            PDDocument doc,
            PDFont font,
            String title,
            String metricsLine,
            BufferedImage img) throws IOException {
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        float pageW = page.getMediaBox().getWidth();
        float pageH = page.getMediaBox().getHeight();
        float maxW = pageW - MARGIN * 2;
        float maxH = pageH - MARGIN * 2 - HEADER_HEIGHT;

        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            float y = pageH - MARGIN;
            y = drawText(cs, font, TITLE_SIZE, MARGIN, y, title);
            y -= 4;
            y = drawText(cs, font, METRICS_SIZE, MARGIN, y, metricsLine);
            y -= 6;
            drawScaledImage(doc, cs, img, MARGIN, y, maxW, maxH);
        }
    }

    private static float drawScaledImage(
            PDDocument doc,
            PDPageContentStream cs,
            BufferedImage img,
            float x,
            float topY,
            float maxW,
            float maxH) throws IOException {
        if (img == null || img.getWidth() <= 0 || img.getHeight() <= 0) {
            return 0;
        }
        float scale = Math.min(maxW / img.getWidth(), maxH / img.getHeight());
        float w = img.getWidth() * scale;
        float h = img.getHeight() * scale;
        PDImageXObject xObject = LosslessFactory.createFromImage(doc, img);
        cs.drawImage(xObject, x, topY - h, w, h);
        return h;
    }

    private static float drawText(
            PDPageContentStream cs, PDFont font, float size, float x, float y, String text) throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y - size);
        cs.showText(sanitizePdfText(text));
        cs.endText();
        return y - size;
    }

    /** フォントに無い記号を置換（— 等） */
    private static String sanitizePdfText(String text) {
        return text.replace('—', '-');
    }

    private static FontHandle loadJapaneseFont(PDDocument doc) throws IOException {
        Logger fontboxLog = Logger.getLogger("org.apache.fontbox");
        Level previousLevel = fontboxLog.getLevel();
        fontboxLog.setLevel(Level.SEVERE);
        try {
            return loadJapaneseFontInternal(doc);
        } finally {
            fontboxLog.setLevel(previousLevel);
        }
    }

    private static FontHandle loadJapaneseFontInternal(PDDocument doc) throws IOException {
        IOException lastError = null;

        for (String path : new String[]{"C:/Windows/Fonts/NotoSansJP-Regular.otf"}) {
            File file = new File(path);
            if (file.isFile()) {
                try {
                    return new FontHandle(PDType0Font.load(doc, file), null);
                } catch (IOException e) {
                    lastError = e;
                }
            }
        }

        record TtcFont(String path, String... names) {}
        TtcFont[] ttcFonts = {
                new TtcFont("C:/Windows/Fonts/YuGothM.ttc", "Yu Gothic Medium", "Yu Gothic", "游ゴシック Medium"),
                new TtcFont("C:/Windows/Fonts/msgothic.ttc", "MS-Gothic", "ＭＳ ゴシック", "MS UI Gothic"),
                new TtcFont("C:/Windows/Fonts/meiryo.ttc", "Meiryo", "メイリオ"),
                new TtcFont("/System/Library/Fonts/Hiragino Sans GB.ttc", "Hiragino Sans GB"),
                new TtcFont("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc", "Noto Sans CJK JP", "Noto Sans CJK Regular"),
                new TtcFont("/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc", "Noto Sans CJK JP", "Noto Sans CJK Regular")
        };

        for (TtcFont candidate : ttcFonts) {
            File file = new File(candidate.path);
            if (!file.isFile()) {
                continue;
            }
            TrueTypeCollection collection = new TrueTypeCollection(file);
            try {
                TrueTypeFont ttf = findFont(collection, candidate.names);
                if (ttf != null) {
                    PDFont font = PDType0Font.load(doc, ttf, true);
                    return new FontHandle(font, collection);
                }
                collection.close();
            } catch (IOException e) {
                collection.close();
                lastError = e;
            }
        }

        if (lastError != null) {
            throw new IOException(
                    "日本語 PDF 用フォントを読み込めません（Meiryo / MS Gothic 等を確認してください）: "
                            + lastError.getMessage(),
                    lastError);
        }
        throw new IOException("日本語 PDF 用フォントが見つかりません（Meiryo / MS Gothic 等をインストールしてください）");
    }

    private static TrueTypeFont findFont(TrueTypeCollection collection, String... names) throws IOException {
        for (String name : names) {
            TrueTypeFont font = collection.getFontByName(name);
            if (font != null) {
                return font;
            }
        }
        TrueTypeFont[] first = new TrueTypeFont[1];
        collection.processAllFonts(font -> {
            if (first[0] == null) {
                first[0] = font;
            }
        });
        return first[0];
    }
}
