package com.screendiff;

import java.awt.*;
import java.awt.image.BufferedImage;

/** HTML 表示用に、縦長画像を先頭・末尾の2区間に分割し中間を省略する。 */
public final class ImageDisplaySplitter {

    private static final int GAP_HEIGHT = 28;

    private ImageDisplaySplitter() {}

    /**
     * @param headHeight 先頭区間の高さ（px）。0 以下なら分割しない
     * @param tailHeight 末尾区間の高さ（px）。0 以下なら分割しない
     */
    public static BufferedImage apply(BufferedImage image, int headHeight, int tailHeight) {
        if (image == null || headHeight <= 0 || tailHeight <= 0) {
            return image;
        }

        int width = image.getWidth();
        int height = image.getHeight();
        int omitted = height - headHeight - tailHeight;
        if (omitted <= 0) {
            return image;
        }

        int outHeight = headHeight + GAP_HEIGHT + tailHeight;
        BufferedImage out = new BufferedImage(width, outHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, outHeight);

            g.drawImage(image, 0, 0, width, headHeight, 0, 0, width, headHeight, null);

            g.setColor(new Color(0xE8EEF5));
            g.fillRect(0, headHeight, width, GAP_HEIGHT);
            g.setColor(new Color(0x555555));
            int fontSize = Math.max(11, Math.min(14, width / 50));
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, fontSize));
            String label = String.format("⋯ 中略 (%d px) ⋯", omitted);
            FontMetrics fm = g.getFontMetrics();
            int textX = Math.max(4, (width - fm.stringWidth(label)) / 2);
            int textY = headHeight + (GAP_HEIGHT + fm.getAscent() - fm.getDescent()) / 2;
            g.drawString(label, textX, textY);

            int tailY = headHeight + GAP_HEIGHT;
            g.drawImage(image, 0, tailY, width, tailY + tailHeight,
                    0, height - tailHeight, width, height, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** 先頭・末尾分割が適用されるか（中間が省略されるか） */
    public static boolean isSplitApplied(BufferedImage image, int headHeight, int tailHeight) {
        if (image == null || headHeight <= 0 || tailHeight <= 0) {
            return false;
        }
        return image.getHeight() - headHeight - tailHeight > 0;
    }

    public static BufferedImage cropHead(BufferedImage image, int headHeight) {
        return cropRegion(image, 0, headHeight);
    }

    public static BufferedImage cropTail(BufferedImage image, int tailHeight) {
        return cropRegion(image, image.getHeight() - tailHeight, tailHeight);
    }

    private static BufferedImage cropRegion(BufferedImage image, int y, int height) {
        BufferedImage out = new BufferedImage(image.getWidth(), height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(image, 0, 0, image.getWidth(), height, 0, y, image.getWidth(), y + height, null);
        } finally {
            g.dispose();
        }
        return out;
    }
}
