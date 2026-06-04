package com.screendiff;

import java.awt.image.BufferedImage;
import java.awt.Graphics2D;

/** 画像の先頭から指定高さを切り取る。 */
public final class ImageCropper {

    private ImageCropper() {}

    /**
     * @param cropHeight 切り取り高さ（px）。0 以下ならそのまま返す
     */
    public static BufferedImage cropFromTop(BufferedImage image, int cropHeight) {
        if (image == null || cropHeight <= 0) {
            return image;
        }
        int height = Math.min(cropHeight, image.getHeight());
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
