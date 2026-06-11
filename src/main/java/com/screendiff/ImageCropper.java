package com.screendiff;

import java.awt.image.BufferedImage;
import java.awt.Graphics2D;

/** 画像の先頭から指定高さを切り取る。 */
public final class ImageCropper {

    private ImageCropper() {}

    /**
     * 切り取りが実際に適用されたとき true。
     *
     * @param cropThreshold この高さより大きい画像にのみ切り取りを適用
     * @param cropAmount    先頭から残す高さ（px）
     */
    public static boolean isCropped(int imageHeight, int cropThreshold, int cropAmount) {
        return cropAmount > 0 && imageHeight > cropThreshold;
    }

    /**
     * @param cropThreshold この高さより大きい画像にのみ切り取りを適用
     * @param cropAmount    先頭から残す高さ（px）。0 以下ならそのまま返す
     */
    public static BufferedImage cropFromTop(BufferedImage image, int cropThreshold, int cropAmount) {
        if (image == null || cropAmount <= 0) {
            return image;
        }
        if (image.getHeight() <= cropThreshold) {
            return image;
        }
        int height = Math.min(cropAmount, image.getHeight());
        if (height >= image.getHeight()) {
            return image;
        }
        BufferedImage out = new BufferedImage(image.getWidth(), height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(image, 0, 0, image.getWidth(), height, 0, 0, image.getWidth(), height, null);
        } finally {
            g.dispose();
        }
        return out;
    }
}
