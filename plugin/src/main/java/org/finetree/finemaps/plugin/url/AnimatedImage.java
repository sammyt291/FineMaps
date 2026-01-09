package org.finetree.finemaps.plugin.url;

import java.awt.image.BufferedImage;
import java.util.List;

public final class AnimatedImage {
    public final List<BufferedImage> frames; // fully composited frames (full canvas)
    public final String format; // "gif", "apng", "webp", "png", "jpeg", etc.

    public AnimatedImage(List<BufferedImage> frames, String format) {
        this.frames = frames;
        this.format = format;
    }

    public boolean isAnimated() {
        return frames != null && frames.size() > 1;
    }
}

