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
            File oldFile,
            File newFile,
            File oldRoot,
            File newRoot,
            String relativePath,
            int blockSize,
            int threshold,
            boolean trimMargins,
            int cropThreshold,
            int cropAmount) throws IOException {
        BufferedImage img1 = ImageIO.read(oldFile);
        BufferedImage img2 = ImageIO.read(newFile);
        if (img1 == null || img2 == null) {
            ImageScaleUtil.dispose(img1);
            ImageScaleUtil.dispose(img2);
            throw new IOException("画像を読み込めません: " + oldFile.getName());
        }

        int origOldW = img1.getWidth(), origOldH = img1.getHeight();
        int origNewW = img2.getWidth(), origNewH = img2.getHeight();

        int oldW = origOldW;
        int oldH = origOldH;
        int newW = origNewW;
        int newH = origNewH;

        if (trimMargins) {
            BufferedImage trimmed1 = ImageMarginTrimmer.trim(img1);
            BufferedImage trimmed2 = ImageMarginTrimmer.trim(img2);
            if (trimmed1 != img1) {
                ImageScaleUtil.dispose(img1);
            }
            if (trimmed2 != img2) {
                ImageScaleUtil.dispose(img2);
            }
            img1 = trimmed1;
            img2 = trimmed2;
            oldW = img1.getWidth();
            oldH = img1.getHeight();
            newW = img2.getWidth();
            newH = img2.getHeight();
        }

        int reportOldW = oldW;
        int reportOldH = oldH;
        int reportNewW = newW;
        int reportNewH = newH;

        if (cropAmount > 0) {
            BufferedImage cropped1 = ImageCropper.cropFromTop(img1, cropThreshold, cropAmount);
            BufferedImage cropped2 = ImageCropper.cropFromTop(img2, cropThreshold, cropAmount);
            if (cropped1 != img1) {
                ImageScaleUtil.dispose(img1);
            }
            if (cropped2 != img2) {
                ImageScaleUtil.dispose(img2);
            }
            img1 = cropped1;
            img2 = cropped2;
        }

        img1 = ImageScaleUtil.limitForComparison(img1);
        img2 = ImageScaleUtil.limitForComparison(img2);

        int w = Math.max(img1.getWidth(), img2.getWidth());
        int h = Math.max(img1.getHeight(), img2.getHeight());
        img1 = padToCanvas(img1, w, h);
        img2 = padToCanvas(img2, w, h);

        ImageComparison comparison = new ImageComparison(img1, img2);
        comparison.setMinimalRectangleSize(blockSize);
        comparison.setPixelToleranceLevel(threshold / 255.0);

        List<Rectangle> rectangles = comparison.createMask();
        BufferedImage diffOverlay = createDiffOverlay(rectangles, w, h);
        double diffPercent = diffPercentFromRectangles(rectangles, w, h);
        int widthDiff = widthDiff(reportOldW, reportNewW);
        int heightDiff = heightDiff(reportOldH, reportNewH);
        boolean oldCropped = ImageCropper.isCropped(reportOldH, cropThreshold, cropAmount);
        boolean newCropped = ImageCropper.isCropped(reportNewH, cropThreshold, cropAmount);
        TextComparator.TextResult textResult = TextComparator.compare(oldRoot, newRoot, relativePath);

        ImageScaleUtil.dispose(img1);
        ImageScaleUtil.dispose(img2);

        return new Result(
                relativePath,
                reportOldW,
                reportOldH,
                reportNewW,
                reportNewH,
                diffPercent,
                widthDiff,
                heightDiff,
                oldCropped,
                newCropped,
                textResult.diffLineCount(),
                diffOverlay,
                "未判定",
                null,
                null);
    }

    /** レポート用画像を解放（HTML 出力後に呼ぶ） */
    public static Result releaseImages(Result result) {
        ImageScaleUtil.dispose(result.diffOverlayImage());
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

    /**
     * レポート用に比較時と同じキャンバスへ配置する（旧・新・差分オーバーレイのピクセル位置を一致させる）。
     */
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
        if (trimMargins) {
            BufferedImage trimmed = ImageMarginTrimmer.trim(img);
            if (trimmed != img) {
                ImageScaleUtil.dispose(img);
            }
            img = trimmed;
        }
        if (cropAmount > 0) {
            BufferedImage cropped = ImageCropper.cropFromTop(img, cropThreshold, cropAmount);
            if (cropped != img) {
                ImageScaleUtil.dispose(img);
            }
            img = cropped;
        }
        img = ImageScaleUtil.limitForComparison(img);
        return padToCanvas(img, canvasW, canvasH);
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
