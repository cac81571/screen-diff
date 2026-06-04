package com.screendiff;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/** 比較・レポート生成時のメモリ使用量を抑えるための画像縮小 */
final class ImageScaleUtil {

    /** 比較処理に使う最大ピクセル数（約400万） */
    static final long MAX_COMPARE_PIXELS = 4_000_000L;
    /** 比較処理に使う最大辺長（px） */
    static final int MAX_COMPARE_SIDE = 4096;

    private ImageScaleUtil() {}

    static BufferedImage limitForComparison(BufferedImage img) {
        if (img == null) {
            return null;
        }
        int w = img.getWidth();
        int h = img.getHeight();
        long pixels = (long) w * h;
        if (pixels <= MAX_COMPARE_PIXELS && w <= MAX_COMPARE_SIDE && h <= MAX_COMPARE_SIDE) {
            return img;
        }
        double scaleBySide = MAX_COMPARE_SIDE / (double) Math.max(w, h);
        double scaleByArea = Math.sqrt(MAX_COMPARE_PIXELS / (double) pixels);
        double scale = Math.min(scaleBySide, scaleByArea);
        int nw = Math.max(1, (int) Math.round(w * scale));
        int nh = Math.max(1, (int) Math.round(h * scale));
        BufferedImage scaled = scale(img, nw, nh);
        if (scaled != img) {
            dispose(img);
        }
        return scaled;
    }

    static BufferedImage scale(BufferedImage src, int targetW, int targetH) {
        if (src.getWidth() == targetW && src.getHeight() == targetH) {
            return src;
        }
        int type = src.getTransparency() == BufferedImage.OPAQUE
                ? BufferedImage.TYPE_INT_RGB
                : BufferedImage.TYPE_INT_ARGB;
        BufferedImage out = new BufferedImage(targetW, targetH, type);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src.getScaledInstance(targetW, targetH, Image.SCALE_SMOOTH), 0, 0, null);
        g.dispose();
        return out;
    }

    static void dispose(BufferedImage img) {
        if (img != null) {
            img.flush();
        }
    }

}
