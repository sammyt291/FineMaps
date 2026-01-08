package com.example.finemaps.plugin.url;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Decodes static + animated images into fully-composited frames.
 *
 * - GIF: uses ImageIO + metadata to composite frames
 * - APNG: uses a minimal built-in APNG decoder (no deps)
 * - WEBP: uses ImageIO reader (requires a WEBP ImageIO plugin on classpath; we shade TwelveMonkeys WEBP)
 */
public final class GenericImageDecoder {

    private GenericImageDecoder() {
    }

    public static AnimatedImage decode(Path file, String hintFromUrl) throws IOException {
        return decode(file, hintFromUrl, 0, 0);
    }

    /**
     * Decode a static or animated image into fully-composited frames.
     *
     * @param maxFrames If > 0, hard-cap the number of decoded frames for animated images.
     * @param maxCanvasSize If > 0, rejects images where width/height exceeds this value.
     */
    public static AnimatedImage decode(Path file, String hintFromUrl, int maxFrames, int maxCanvasSize) throws IOException {
        String hint = hintFromUrl != null ? hintFromUrl.toLowerCase() : "";
        if (hint.endsWith(".apng") || (hint.endsWith(".png") && ApngDecoder.looksLikePng(file))) {
            // Try APNG first (will fall back to normal PNG if not animated)
            List<BufferedImage> frames = ApngDecoder.decode(file, maxFrames, maxCanvasSize);
            String fmt = frames.size() > 1 ? "apng" : "png";
            return new AnimatedImage(frames, fmt);
        }

        if (hint.endsWith(".gif")) {
            return new AnimatedImage(decodeGif(file.toFile(), maxFrames, maxCanvasSize), "gif");
        }

        if (hint.endsWith(".webp")) {
            // WebP reader provided by shaded plugin.
            List<BufferedImage> frames = decodeWithImageIOSequence(file.toFile(), maxFrames, maxCanvasSize);
            if (frames.isEmpty()) throw new IOException("Failed to decode WEBP");
            return new AnimatedImage(frames, "webp");
        }

        // Generic ImageIO fallback (PNG/JPEG/etc) - if reader supports sequences, keep frames.
        List<BufferedImage> frames = decodeWithImageIOSequence(file.toFile(), maxFrames, maxCanvasSize);
        if (frames.isEmpty()) throw new IOException("Failed to decode image");
        String fmt = frames.size() > 1 ? "animated" : "static";
        return new AnimatedImage(frames, fmt);
    }

    private static List<BufferedImage> decodeWithImageIOSequence(File file, int maxFrames, int maxCanvasSize) throws IOException {
        try (ImageInputStream iis = ImageIO.createImageInputStream(file)) {
            if (iis == null) return new ArrayList<>();
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                return new ArrayList<>();
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, false, false);
                int num;
                try {
                    num = reader.getNumImages(true);
                } catch (Exception ignored) {
                    num = 1;
                }
                int limit = Math.max(1, num);
                if (maxFrames > 0) {
                    limit = Math.min(limit, Math.max(1, maxFrames));
                }

                List<BufferedImage> frames = new ArrayList<>();
                for (int i = 0; i < limit; i++) {
                    BufferedImage img = reader.read(i);
                    if (img != null) {
                        if (maxCanvasSize > 0 && (img.getWidth() > maxCanvasSize || img.getHeight() > maxCanvasSize)) {
                            throw new IOException("Image too large. Max size: " + maxCanvasSize + "x" + maxCanvasSize);
                        }
                        frames.add(toArgb(img));
                    }
                }
                return frames;
            } finally {
                reader.dispose();
            }
        }
    }

    private static List<BufferedImage> decodeGif(File file, int maxFrames, int maxCanvasSize) throws IOException {
        try (ImageInputStream stream = ImageIO.createImageInputStream(file)) {
            if (stream == null) throw new IOException("Could not open GIF");
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
            if (!readers.hasNext()) throw new IOException("No GIF ImageIO reader available");

            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, false, false);
                int num = reader.getNumImages(true);
                if (num <= 1) {
                    BufferedImage img = reader.read(0);
                    List<BufferedImage> one = new ArrayList<>();
                    if (img != null) {
                        if (maxCanvasSize > 0 && (img.getWidth() > maxCanvasSize || img.getHeight() > maxCanvasSize)) {
                            throw new IOException("Image too large. Max size: " + maxCanvasSize + "x" + maxCanvasSize);
                        }
                        one.add(toArgb(img));
                    }
                    return one;
                }

                int limit = num;
                if (maxFrames > 0) limit = Math.min(limit, Math.max(1, maxFrames));

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

                List<BufferedImage> frames = new ArrayList<>();
                BufferedImage prevCanvas = null;

                for (int i = 0; i < limit; i++) {
                    BufferedImage frame = toArgb(reader.read(i));
                    IIOMetadata meta = reader.getImageMetadata(i);

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

                    frames.add(deepCopy(canvas));

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

                return frames;
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

