package com.example.finemaps.plugin.url;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;

/**
 * Streams fully-composited GIF frames without holding all frames in memory.
 *
 * This is designed for long animations: we composite onto a single canvas, snapshot each frame,
 * and immediately pass it to a consumer.
 */
public final class GifFrameStreamer {

    private GifFrameStreamer() {
    }

    public interface FrameConsumer {
        void onFrame(int index, BufferedImage compositedCanvasFrame) throws IOException;
    }

    public static int stream(File file, int maxFrames, int maxCanvasSize, FrameConsumer consumer) throws IOException {
        if (file == null) throw new IllegalArgumentException("file is null");
        if (consumer == null) throw new IllegalArgumentException("consumer is null");

        try (ImageInputStream stream = ImageIO.createImageInputStream(file)) {
            if (stream == null) throw new IOException("Could not open GIF");
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
            if (!readers.hasNext()) throw new IOException("No GIF ImageIO reader available");

            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, false, false);

                int num;
                try {
                    num = reader.getNumImages(true);
                } catch (Throwable ignored) {
                    num = Integer.MAX_VALUE; // unknown
                }

                int limit = num;
                if (maxFrames > 0) {
                    limit = Math.min(limit, Math.max(1, maxFrames));
                }

                // Determine logical screen size from stream metadata when possible; else use first frame
                int canvasW = -1, canvasH = -1;
                try {
                    IIOMetadata sm = reader.getStreamMetadata();
                    if (sm != null) {
                        javax.imageio.metadata.IIOMetadataNode root = (javax.imageio.metadata.IIOMetadataNode) sm.getAsTree(sm.getNativeMetadataFormatName());
                        javax.imageio.metadata.IIOMetadataNode lsd = (javax.imageio.metadata.IIOMetadataNode) root.getElementsByTagName("LogicalScreenDescriptor").item(0);
                        if (lsd != null) {
                            canvasW = Integer.parseInt(lsd.getAttribute("logicalScreenWidth"));
                            canvasH = Integer.parseInt(lsd.getAttribute("logicalScreenHeight"));
                        }
                    }
                } catch (Throwable ignored) {
                }

                BufferedImage first = toArgb(reader.read(0));
                if (first == null) throw new IOException("Failed to decode GIF");
                if (canvasW <= 0) canvasW = first.getWidth();
                if (canvasH <= 0) canvasH = first.getHeight();
                if (maxCanvasSize > 0 && (canvasW > maxCanvasSize || canvasH > maxCanvasSize)) {
                    throw new IOException("Image too large. Max size: " + maxCanvasSize + "x" + maxCanvasSize);
                }

                BufferedImage canvas = new BufferedImage(canvasW, canvasH, BufferedImage.TYPE_INT_ARGB);
                Graphics2D cg = canvas.createGraphics();
                cg.setComposite(AlphaComposite.Src);
                cg.setColor(new Color(0, 0, 0, 0));
                cg.fillRect(0, 0, canvasW, canvasH);
                cg.dispose();

                BufferedImage prevCanvas = null;

                int produced = 0;
                for (int i = 0; i < limit; i++) {
                    BufferedImage frame = toArgb(i == 0 ? first : reader.read(i));
                    if (frame == null) break; // best-effort

                    IIOMetadata meta;
                    try {
                        meta = reader.getImageMetadata(i);
                    } catch (Throwable t) {
                        meta = null;
                    }

                    GifFrameInfo info = GifFrameInfo.from(meta);
                    if ("restoreToPrevious".equals(info.disposalMethod)) {
                        prevCanvas = deepCopy(canvas);
                    } else {
                        prevCanvas = null;
                    }

                    Graphics2D g = canvas.createGraphics();
                    g.setComposite(AlphaComposite.SrcOver);
                    g.drawImage(frame, info.left, info.top, null);
                    g.dispose();

                    // Snapshot *before* disposal and hand off
                    consumer.onFrame(i, deepCopy(canvas));
                    produced++;

                    // Dispose
                    if ("restoreToBackgroundColor".equals(info.disposalMethod)) {
                        Graphics2D dg = canvas.createGraphics();
                        dg.setComposite(AlphaComposite.Src);
                        dg.setColor(new Color(0, 0, 0, 0));
                        dg.fillRect(info.left, info.top, info.width, info.height);
                        dg.dispose();
                    } else if ("restoreToPrevious".equals(info.disposalMethod) && prevCanvas != null) {
                        Graphics2D dg = canvas.createGraphics();
                        dg.setComposite(AlphaComposite.Src);
                        dg.drawImage(prevCanvas, 0, 0, null);
                        dg.dispose();
                    }
                }
                return produced;
            } finally {
                reader.dispose();
            }
        }
    }

    private static BufferedImage toArgb(BufferedImage src) {
        if (src == null) return null;
        if (src.getType() == BufferedImage.TYPE_INT_ARGB) return src;
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    private static BufferedImage deepCopy(BufferedImage src) {
        BufferedImage copy = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = copy.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return copy;
    }

    private static final class GifFrameInfo {
        final int left;
        final int top;
        final int width;
        final int height;
        final String disposalMethod;

        private GifFrameInfo(int left, int top, int width, int height, String disposalMethod) {
            this.left = left;
            this.top = top;
            this.width = width;
            this.height = height;
            this.disposalMethod = disposalMethod != null ? disposalMethod : "none";
        }

        static GifFrameInfo from(IIOMetadata meta) {
            int left = 0, top = 0, width = 0, height = 0;
            String disposal = "none";
            if (meta == null) return new GifFrameInfo(left, top, width, height, disposal);
            try {
                String fmt = meta.getNativeMetadataFormatName();
                javax.imageio.metadata.IIOMetadataNode root = (javax.imageio.metadata.IIOMetadataNode) meta.getAsTree(fmt);
                javax.imageio.metadata.IIOMetadataNode imgDesc = (javax.imageio.metadata.IIOMetadataNode) root.getElementsByTagName("ImageDescriptor").item(0);
                if (imgDesc != null) {
                    left = Integer.parseInt(imgDesc.getAttribute("imageLeftPosition"));
                    top = Integer.parseInt(imgDesc.getAttribute("imageTopPosition"));
                    width = Integer.parseInt(imgDesc.getAttribute("imageWidth"));
                    height = Integer.parseInt(imgDesc.getAttribute("imageHeight"));
                }
                javax.imageio.metadata.IIOMetadataNode gce = (javax.imageio.metadata.IIOMetadataNode) root.getElementsByTagName("GraphicControlExtension").item(0);
                if (gce != null) {
                    disposal = gce.getAttribute("disposalMethod");
                }
            } catch (Throwable ignored) {
            }
            return new GifFrameInfo(left, top, width, height, disposal);
        }
    }
}

