package com.screendiff;

import com.github.romankh3.image.comparison.ImageComparison;
import com.github.romankh3.image.comparison.model.Rectangle;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;

public class ImageComparator {

    record PrepareResult(BufferedImage image, int width, int height, boolean cropped) {}

    public record Result(
            String fileName,
            int oldWidth,
            int oldHeight,
            int newWidth,
            int newHeight,
            double diffPercent,
            int widthDiff,
            int heightDiff,
            boolean oldCropped,
            boolean newCropped,
            int textDiffLines,
            String textBaseName,
            BufferedImage diffOverlayImage,
            String aiJudgment,
            BufferedImage reportOldImage,
            BufferedImage reportNewImage) {}

    /**
     * @param blockSize 最小矩形サイズ（これ以下の差分を無視）
     * @param threshold ピクセル許容差 (0-255)
     * @param trimMargins 四隅の白余白を除去してから比較する
     */
    public static Result compare(
            BufferedImage oldImage,
            BufferedImage newImage,
            int reportOldW,
            int reportOldH,
            boolean oldCropped,
            int reportNewW,
            int reportNewH,
            boolean newCropped,
            File oldRoot,
            File newRoot,
            String displayName,
            String textBaseName,
            int blockSize,
            int threshold) throws IOException {
        BufferedImage img1 = ImageScaleUtil.limitForComparison(oldImage);
        BufferedImage img2 = ImageScaleUtil.limitForComparison(newImage);

        int w = Math.max(img1.getWidth(), img2.getWidth());
        int h = Math.max(img1.getHeight(), img2.getHeight());
        BufferedImage reportOld = padToCanvas(img1, w, h);
        BufferedImage reportNew = padToCanvas(img2, w, h);

        ImageComparison comparison = new ImageComparison(reportOld, reportNew);
        comparison.setMinimalRectangleSize(blockSize);
        comparison.setPixelToleranceLevel(threshold / 255.0);

        List<Rectangle> rectangles = comparison.createMask();
        BufferedImage diffOverlay = createDiffOverlay(rectangles, w, h);
        double diffPercent = diffPercentFromRectangles(rectangles, w, h);
        TextComparator.TextResult textResult = TextComparator.compare(oldRoot, newRoot, textBaseName);

        return new Result(
                displayName,
                reportOldW,
                reportOldH,
                reportNewW,
                reportNewH,
                diffPercent,
                widthDiff(reportOldW, reportNewW),
                heightDiff(reportOldH, reportNewH),
                oldCropped,
                newCropped,
                textResult.diffLineCount(),
                textBaseName,
                diffOverlay,
                "未判定",
                reportOld,
                reportNew);
    }

    static PrepareResult prepare(
            BufferedImage source,
            boolean trimMargins,
            int cropThreshold,
            int cropAmount) {
        BufferedImage img = source;
        if (trimMargins) {
            BufferedImage trimmed = ImageMarginTrimmer.trim(img);
            if (trimmed != img) {
                ImageScaleUtil.dispose(img);
            }
            img = trimmed;
        }

        int width = img.getWidth();
        int height = img.getHeight();
        boolean cropped = false;

        if (cropAmount > 0) {
            BufferedImage croppedImg = ImageCropper.cropFromTop(img, cropThreshold, cropAmount);
            if (croppedImg != img) {
                ImageScaleUtil.dispose(img);
            }
            img = croppedImg;
            cropped = ImageCropper.isCropped(height, cropThreshold, cropAmount);
        }

        return new PrepareResult(img, img.getWidth(), img.getHeight(), cropped);
    }

    static BufferedImage loadFromFile(File file) throws IOException {
        BufferedImage img = ImageIO.read(file);
        if (img == null) {
            throw new IOException("画像を読み込めません: " + file.getAbsolutePath());
        }
        return img;
    }

    /** レポート用画像を解放（HTML 出力後に呼ぶ） */
    public static Result releaseImages(Result result) {
        ImageScaleUtil.dispose(result.diffOverlayImage());
        ImageScaleUtil.dispose(result.reportOldImage());
        ImageScaleUtil.dispose(result.reportNewImage());
        return new Result(
                result.fileName(),
                result.oldWidth(),
                result.oldHeight(),
                result.newWidth(),
                result.newHeight(),
                result.diffPercent(),
                result.widthDiff(),
                result.heightDiff(),
                result.oldCropped(),
                result.newCropped(),
                result.textDiffLines(),
                result.textBaseName(),
                null,
                result.aiJudgment(),
                null,
                null);
    }

    /**
     * 差分画像の赤矩形と同じ領域の面積比率（%）。
     * ライブラリの getDifferencePercent() はしきい値を無視した平均色差のため使わない。
     */
    static double diffPercentFromRectangles(List<Rectangle> rectangles, int width, int height) {
        if (rectangles == null || rectangles.isEmpty() || width <= 0 || height <= 0) {
            return 0.0;
        }
        long diffPixels = rectangles.stream().mapToLong(Rectangle::size).sum();
        return diffPixels * 100.0 / ((long) width * height);
    }

    /** HTML オーバーレイ用 … 透明背景に差分矩形のみ描画（新画像本体は含めない） */
    static BufferedImage createDiffOverlay(List<Rectangle> rectangles, int width, int height) {
        BufferedImage overlay = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        if (rectangles == null || rectangles.isEmpty()) {
            return overlay;
        }
        Graphics2D g = overlay.createGraphics();
        g.setStroke(new BasicStroke(3));
        g.setColor(Color.RED);
        for (Rectangle rectangle : rectangles) {
            int x = rectangle.getMinPoint().x;
            int y = rectangle.getMinPoint().y;
            g.drawRect(x, y, rectangle.getWidth() - 1, rectangle.getHeight() - 1);
        }
        g.setColor(new Color(255, 0, 0, (int) (0.2 * 255)));
        for (Rectangle rectangle : rectangles) {
            g.fillRect(
                    rectangle.getMinPoint().x - 1,
                    rectangle.getMinPoint().y - 1,
                    rectangle.getWidth() - 2,
                    rectangle.getHeight() - 2);
        }
        g.dispose();
        return overlay;
    }

    static int widthDiff(int oldW, int newW) {
        return Math.abs(oldW - newW);
    }

    static int heightDiff(int oldH, int newH) {
        return Math.abs(oldH - newH);
    }

    static BufferedImage loadForReportCanvas(
            File file,
            boolean trimMargins,
            int cropThreshold,
            int cropAmount,
            int canvasW,
            int canvasH) throws IOException {
        BufferedImage img = ImageIO.read(file);
        if (img == null) {
            throw new IOException("画像を読み込めません: " + file.getAbsolutePath());
        }
        PrepareResult prepared = prepare(img, trimMargins, cropThreshold, cropAmount);
        BufferedImage limited = ImageScaleUtil.limitForComparison(prepared.image());
        return padToCanvas(limited, canvasW, canvasH);
    }

    static BufferedImage padToCanvas(BufferedImage img, int canvasW, int canvasH) {
        if (img.getWidth() == canvasW && img.getHeight() == canvasH) {
            return img;
        }
        BufferedImage out = new BufferedImage(canvasW, canvasH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, canvasW, canvasH);
            g.drawImage(img, 0, 0, null);
        } finally {
            g.dispose();
        }
        if (img != out) {
            ImageScaleUtil.dispose(img);
        }
        return out;
    }
}