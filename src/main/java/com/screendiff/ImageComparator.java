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
            double sizeDiffPercent,
            double textDiffPercent,
            BufferedImage diffOverlayImage,
            String aiJudgment,
            BufferedImage reportOldImage,
            BufferedImage reportNewImage) {}

    /**
     * @param blockSize 最小矩形サイズ（これ以下の差分を無視）
     * @param threshold ピクセル許容差 (0-255)
     * @param trimMargins 四隅の白余白を除去してから比較する
     */
    public static Result compare(File oldFile, File newFile, int blockSize, int threshold, boolean trimMargins)
            throws IOException {
        BufferedImage img1 = ImageIO.read(oldFile);
        BufferedImage img2 = ImageIO.read(newFile);

        int origOldW = img1.getWidth(), origOldH = img1.getHeight();
        int origNewW = img2.getWidth(), origNewH = img2.getHeight();

        int oldW = origOldW;
        int oldH = origOldH;
        int newW = origNewW;
        int newH = origNewH;
        BufferedImage reportOld = null;
        BufferedImage reportNew = null;

        if (trimMargins) {
            img1 = ImageMarginTrimmer.trim(img1);
            img2 = ImageMarginTrimmer.trim(img2);
            reportOld = img1;
            reportNew = img2;
            oldW = img1.getWidth();
            oldH = img1.getHeight();
            newW = img2.getWidth();
            newH = img2.getHeight();
        }

        int w = Math.max(img1.getWidth(), img2.getWidth());
        int h = Math.max(img1.getHeight(), img2.getHeight());
        if (img1.getWidth() != w || img1.getHeight() != h) {
            img1 = resizeToFit(img1, w, h);
        }
        if (img2.getWidth() != w || img2.getHeight() != h) {
            img2 = resizeToFit(img2, w, h);
        }

        ImageComparison comparison = new ImageComparison(img1, img2);
        comparison.setMinimalRectangleSize(blockSize);
        comparison.setPixelToleranceLevel(threshold / 255.0);

        List<Rectangle> rectangles = comparison.createMask();
        BufferedImage diffOverlay = createDiffOverlay(rectangles, w, h);
        double diffPercent = diffPercentFromRectangles(rectangles, w, h);
        double sizeDiffPercent = sizeDiffPercent(oldW, oldH, newW, newH);
        TextComparator.TextResult textResult = TextComparator.compare(
                oldFile.getParentFile(), newFile.getParentFile(), oldFile.getName());

        return new Result(
                oldFile.getName(),
                oldW,
                oldH,
                newW,
                newH,
                diffPercent,
                sizeDiffPercent,
                textResult.diffPercent(),
                diffOverlay,
                "未判定",
                reportOld,
                reportNew);
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

    /** 幅・高さの相対差（%）の大きい方 */
    static double sizeDiffPercent(int oldW, int oldH, int newW, int newH) {
        if (oldW == newW && oldH == newH) {
            return 0.0;
        }
        double wDiff = (oldW == 0 && newW == 0) ? 0 : Math.abs(oldW - newW) * 100.0 / Math.max(oldW, newW);
        double hDiff = (oldH == 0 && newH == 0) ? 0 : Math.abs(oldH - newH) * 100.0 / Math.max(oldH, newH);
        return Math.max(wDiff, hDiff);
    }

    private static BufferedImage resizeToFit(BufferedImage img, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return out;
    }
}
