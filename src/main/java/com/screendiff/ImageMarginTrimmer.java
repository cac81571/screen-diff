package com.screendiff;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * 四隅の色を基準に、近い色の余白を除去する。
 * 上端は左上・右上、左端は左上・左下 … の隅色と比較する。
 */
public final class ImageMarginTrimmer {

    private static final int CORNER_PATCH = 5;
    /** 隅の色から各 RGB チャンネル許容差 */
    private static final int COLOR_TOLERANCE = 32;
    /** 四隅の代表色が互いにこの差以内なら平均を基準にする */
    private static final int CORNER_CONSISTENCY = 40;
    /** 行/列のこの割合以上が余白色なら削除（JPEG ノイズ対策） */
    private static final double BACKGROUND_LINE_RATIO = 0.995;

    private record Rgb(int r, int g, int b) {}

    private record CornerColors(Rgb topLeft, Rgb topRight, Rgb bottomLeft, Rgb bottomRight) {}

    private ImageMarginTrimmer() {}

    public static BufferedImage trim(BufferedImage img) {
        BufferedImage current = img;
        BufferedImage previous;
        do {
            previous = current;
            CornerColors corners = detectCornerColors(previous);
            current = trimOnce(previous, corners);
        } while (current.getWidth() != previous.getWidth() || current.getHeight() != previous.getHeight());
        return current;
    }

    private static CornerColors detectCornerColors(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        Rgb topLeft = averagePatch(img, 0, 0);
        Rgb topRight = averagePatch(img, Math.max(0, w - CORNER_PATCH), 0);
        Rgb bottomLeft = averagePatch(img, 0, Math.max(0, h - CORNER_PATCH));
        Rgb bottomRight = averagePatch(img, Math.max(0, w - CORNER_PATCH), Math.max(0, h - CORNER_PATCH));

        if (cornersAreConsistent(topLeft, topRight, bottomLeft, bottomRight)) {
            Rgb avg = average(topLeft, topRight, bottomLeft, bottomRight);
            return new CornerColors(avg, avg, avg, avg);
        }
        return new CornerColors(topLeft, topRight, bottomLeft, bottomRight);
    }

    private static BufferedImage trimOnce(BufferedImage img, CornerColors corners) {
        int w = img.getWidth();
        int h = img.getHeight();
        if (w == 0 || h == 0) {
            return img;
        }

        int top = 0;
        int bottom = h - 1;
        int left = 0;
        int right = w - 1;

        while (top <= bottom && isBackgroundRow(img, top, left, right, corners.topLeft(), corners.topRight())) {
            top++;
        }
        while (bottom >= top && isBackgroundRow(img, bottom, left, right, corners.bottomLeft(), corners.bottomRight())) {
            bottom--;
        }
        while (left <= right && isBackgroundColumn(img, left, top, bottom, corners.topLeft(), corners.bottomLeft())) {
            left++;
        }
        while (right >= left && isBackgroundColumn(img, right, top, bottom, corners.topRight(), corners.bottomRight())) {
            right--;
        }

        if (top > bottom || left > right) {
            return img;
        }
        if (top == 0 && left == 0 && right == w - 1 && bottom == h - 1) {
            return img;
        }

        int cw = right - left + 1;
        int ch = bottom - top + 1;
        BufferedImage out = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(img, 0, 0, cw, ch, left, top, right + 1, bottom + 1, null);
        g.dispose();
        return out;
    }

    private static boolean isBackgroundRow(BufferedImage img, int y, int left, int right, Rgb refA, Rgb refB) {
        int background = 0;
        int total = right - left + 1;
        for (int x = left; x <= right; x++) {
            if (isMarginColor(img.getRGB(x, y), refA, refB)) {
                background++;
            }
        }
        return (double) background / total >= BACKGROUND_LINE_RATIO;
    }

    private static boolean isBackgroundColumn(BufferedImage img, int x, int top, int bottom, Rgb refA, Rgb refB) {
        int background = 0;
        int total = bottom - top + 1;
        for (int y = top; y <= bottom; y++) {
            if (isMarginColor(img.getRGB(x, y), refA, refB)) {
                background++;
            }
        }
        return (double) background / total >= BACKGROUND_LINE_RATIO;
    }

    private static boolean isMarginColor(int argb, Rgb refA, Rgb refB) {
        int a = (argb >> 24) & 0xff;
        if (a < 32) {
            return true;
        }
        return isSimilar(argb, refA) || isSimilar(argb, refB);
    }

    private static boolean isSimilar(int argb, Rgb ref) {
        int r = (argb >> 16) & 0xff;
        int g = (argb >> 8) & 0xff;
        int b = argb & 0xff;
        return Math.abs(r - ref.r()) <= COLOR_TOLERANCE
                && Math.abs(g - ref.g()) <= COLOR_TOLERANCE
                && Math.abs(b - ref.b()) <= COLOR_TOLERANCE;
    }

    private static Rgb averagePatch(BufferedImage img, int x0, int y0) {
        int x1 = Math.min(img.getWidth(), x0 + CORNER_PATCH);
        int y1 = Math.min(img.getHeight(), y0 + CORNER_PATCH);
        long sumR = 0;
        long sumG = 0;
        long sumB = 0;
        int count = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int argb = img.getRGB(x, y);
                int alpha = (argb >> 24) & 0xff;
                if (alpha < 32) {
                    continue;
                }
                sumR += (argb >> 16) & 0xff;
                sumG += (argb >> 8) & 0xff;
                sumB += argb & 0xff;
                count++;
            }
        }
        if (count == 0) {
            return new Rgb(255, 255, 255);
        }
        return new Rgb((int) (sumR / count), (int) (sumG / count), (int) (sumB / count));
    }

    private static boolean cornersAreConsistent(Rgb tl, Rgb tr, Rgb bl, Rgb br) {
        return maxChannelDiff(tl, tr) <= CORNER_CONSISTENCY
                && maxChannelDiff(tl, bl) <= CORNER_CONSISTENCY
                && maxChannelDiff(tl, br) <= CORNER_CONSISTENCY
                && maxChannelDiff(tr, bl) <= CORNER_CONSISTENCY
                && maxChannelDiff(tr, br) <= CORNER_CONSISTENCY
                && maxChannelDiff(bl, br) <= CORNER_CONSISTENCY;
    }

    private static int maxChannelDiff(Rgb a, Rgb b) {
        return Math.max(
                Math.max(Math.abs(a.r() - b.r()), Math.abs(a.g() - b.g())),
                Math.abs(a.b() - b.b()));
    }

    private static Rgb average(Rgb a, Rgb b, Rgb c, Rgb d) {
        return new Rgb(
                (a.r() + b.r() + c.r() + d.r()) / 4,
                (a.g() + b.g() + c.g() + d.g()) / 4,
                (a.b() + b.b() + c.b() + d.b()) / 4);
    }
}
