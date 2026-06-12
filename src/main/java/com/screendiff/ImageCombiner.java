package com.screendiff;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;

/** 複数画像を縦方向に結合する（画像間に余白を挿入） */
final class ImageCombiner {

    /** 結合時の画像間余白（px） */
    static final int GAP_PX = 20;

    private ImageCombiner() {}

    static BufferedImage combineVertically(List<BufferedImage> images, int gapPx) {
        if (images.isEmpty()) {
            throw new IllegalArgumentException("結合する画像がありません");
        }
        if (images.size() == 1) {
            return images.get(0);
        }

        int width = 0;
        int height = 0;
        for (BufferedImage img : images) {
            width = Math.max(width, img.getWidth());
            height += img.getHeight();
        }
        height += gapPx * (images.size() - 1);

        BufferedImage combined = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = combined.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            int y = 0;
            for (int i = 0; i < images.size(); i++) {
                BufferedImage img = images.get(i);
                g.drawImage(img, 0, y, null);
                y += img.getHeight();
                if (i < images.size() - 1) {
                    y += gapPx;
                }
            }
        } finally {
            g.dispose();
        }
        return combined;
    }
}
