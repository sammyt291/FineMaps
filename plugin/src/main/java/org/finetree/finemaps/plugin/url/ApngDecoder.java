package org.finetree.finemaps.plugin.url;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Minimal APNG decoder (no external deps).
 *
 * Produces fully composited frames at the full canvas size.
 */
public final class ApngDecoder {

    private static final byte[] PNG_SIG = new byte[]{
        (byte) 137, 80, 78, 71, 13, 10, 26, 10
    };

    private ApngDecoder() {
    }

    public static boolean looksLikePng(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] sig = new byte[8];
            int r = in.read(sig);
            return r == 8 && Arrays.equals(sig, PNG_SIG);
        }
    }

    public static List<BufferedImage> decode(Path file) throws IOException {
        return decode(file, 0, 0);
    }

    /**
     * Decode an APNG/PNG file into fully-composited frames.
     *
     * @param maxFrames If > 0, hard-cap the number of decoded frames.
     * @param maxCanvasSize If > 0, rejects images where canvas width/height exceeds this value.
     */
    public static List<BufferedImage> decode(Path file, int maxFrames, int maxCanvasSize) throws IOException {
        byte[] all = Files.readAllBytes(file);
        if (all.length < 8 || !Arrays.equals(Arrays.copyOfRange(all, 0, 8), PNG_SIG)) {
            throw new IOException("Not a PNG/APNG file");
        }

        // Parse chunks
        List<Chunk> preDataChunks = new ArrayList<>(); // chunks to copy into each frame PNG (PLTE, tRNS, etc.)
        Chunk ihdr = null;
        boolean hasAcTL = false;

        int pos = 8;
        int canvasW = -1;
        int canvasH = -1;

        // Frame data
        List<Frame> frames = new ArrayList<>();
        Frame current = null;
        boolean seenIDAT = false;

        while (pos + 8 <= all.length) {
            int len = readInt(all, pos);
            pos += 4;
            if (pos + 4 > all.length) break;
            String type = new String(all, pos, 4, StandardCharsets.ISO_8859_1);
            pos += 4;
            if (pos + len + 4 > all.length) break;
            byte[] data = Arrays.copyOfRange(all, pos, pos + len);
            pos += len;
            // skip CRC
            pos += 4;

            if ("IHDR".equals(type)) {
                ihdr = new Chunk(type, data);
                canvasW = readInt(data, 0);
                canvasH = readInt(data, 4);
                if (maxCanvasSize > 0 && (canvasW > maxCanvasSize || canvasH > maxCanvasSize)) {
                    throw new IOException("Image too large. Max size: " + maxCanvasSize + "x" + maxCanvasSize);
                }
                continue;
            }

            if ("acTL".equals(type)) {
                hasAcTL = true;
                continue;
            }

            if ("fcTL".equals(type)) {
                if (maxFrames > 0 && frames.size() >= maxFrames) {
                    // Stop parsing once we've collected enough frames; avoids excessive allocations.
                    break;
                }
                // Start a new frame control
                Frame f = Frame.fromFcTL(data);
                frames.add(f);
                current = f;
                continue;
            }

            if ("IDAT".equals(type)) {
                seenIDAT = true;
                if (current == null) {
                    // No fcTL prior to IDAT: treat default image as frame 0 (spec allows)
                    Frame f = new Frame();
                    f.width = canvasW;
                    f.height = canvasH;
                    f.xOffset = 0;
                    f.yOffset = 0;
                    f.disposeOp = DisposeOp.NONE;
                    f.blendOp = BlendOp.SOURCE;
                    frames.add(0, f);
                    current = f;
                }
                current.idatData.add(data);
                continue;
            }

            if ("fdAT".equals(type)) {
                // fdAT payload: sequence(4) + image data
                if (current == null) {
                    // Malformed but ignore
                    continue;
                }
                if (data.length <= 4) continue;
                byte[] idatPayload = Arrays.copyOfRange(data, 4, data.length);
                current.idatData.add(idatPayload);
                continue;
            }

            if ("IEND".equals(type)) {
                break;
            }

            // Copy ancillary chunks needed for decoding, but only before IDAT stream begins.
            if (!seenIDAT && !type.equals("PLTE") && !type.equals("tRNS") && !type.equals("gAMA") && !type.equals("cHRM") && !type.equals("sRGB") && !type.equals("iCCP") && !type.equals("sBIT") && !type.equals("bKGD") && !type.equals("pHYs") && !type.equals("hIST")) {
                // Keep it simple: we still want critical chunks like PLTE/tRNS; others are optional.
                // We'll include all chunks encountered pre-IDAT that are not animation control chunks.
            }
            if (!seenIDAT && !type.equals("acTL") && !type.equals("fcTL")) {
                preDataChunks.add(new Chunk(type, data));
            }
        }

        if (!hasAcTL || frames.size() <= 1) {
            // Not animated (or single frame) -> fall back to normal ImageIO
            BufferedImage img = ImageIO.read(file.toFile());
            if (img == null) throw new IOException("Failed to decode PNG");
            if (maxCanvasSize > 0 && (img.getWidth() > maxCanvasSize || img.getHeight() > maxCanvasSize)) {
                throw new IOException("Image too large. Max size: " + maxCanvasSize + "x" + maxCanvasSize);
            }
            List<BufferedImage> one = new ArrayList<>();
            one.add(img);
            return one;
        }

        if (ihdr == null || canvasW <= 0 || canvasH <= 0) throw new IOException("Invalid IHDR");

        // Composite frames
        BufferedImage canvas = new BufferedImage(canvasW, canvasH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.setColor(new Color(0, 0, 0, 0));
        g.fillRect(0, 0, canvasW, canvasH);
        g.dispose();

        BufferedImage previousCanvas = null;
        List<BufferedImage> outFrames = new ArrayList<>();

        for (Frame f : frames) {
            if (maxFrames > 0 && outFrames.size() >= maxFrames) break;
            if (f.idatData.isEmpty()) {
                // Some APNGs may have empty frames; skip
                continue;
            }

            if (f.disposeOp == DisposeOp.PREVIOUS) {
                previousCanvas = deepCopy(canvas);
            } else {
                previousCanvas = null;
            }

            BufferedImage frameImg = decodeFramePng(ihdr, preDataChunks, f, canvasW, canvasH);
            if (frameImg == null) throw new IOException("Failed to decode APNG frame");

            // Draw onto canvas with blend op
            Graphics2D cg = canvas.createGraphics();
            if (f.blendOp == BlendOp.SOURCE) {
                cg.setComposite(AlphaComposite.Src);
            } else {
                cg.setComposite(AlphaComposite.SrcOver);
            }
            cg.drawImage(frameImg, f.xOffset, f.yOffset, null);
            cg.dispose();

            outFrames.add(deepCopy(canvas));

            // Dispose
            if (f.disposeOp == DisposeOp.BACKGROUND) {
                Graphics2D dg = canvas.createGraphics();
                dg.setComposite(AlphaComposite.Src);
                dg.setColor(new Color(0, 0, 0, 0));
                dg.fillRect(f.xOffset, f.yOffset, f.width, f.height);
                dg.dispose();
            } else if (f.disposeOp == DisposeOp.PREVIOUS && previousCanvas != null) {
                Graphics2D dg = canvas.createGraphics();
                dg.setComposite(AlphaComposite.Src);
                dg.drawImage(previousCanvas, 0, 0, null);
                dg.dispose();
            }
        }

        if (outFrames.isEmpty()) {
            // Fallback safety
            BufferedImage img = ImageIO.read(file.toFile());
            if (img == null) throw new IOException("Failed to decode APNG");
            outFrames.add(img);
        }
        return outFrames;
    }

    private static BufferedImage decodeFramePng(Chunk ihdr, List<Chunk> preDataChunks, Frame f, int canvasW, int canvasH) throws IOException {
        // Build a standalone PNG for this frame (width/height are frame dimensions)
        byte[] ihdrData = Arrays.copyOf(ihdr.data, ihdr.data.length);
        writeInt(ihdrData, 0, f.width);
        writeInt(ihdrData, 4, f.height);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(PNG_SIG);
        writeChunk(baos, "IHDR", ihdrData);
        for (Chunk c : preDataChunks) {
            if ("IHDR".equals(c.type) || "IDAT".equals(c.type) || "IEND".equals(c.type)) continue;
            if ("acTL".equals(c.type) || "fcTL".equals(c.type)) continue;
            baos.write(toChunkBytes(c.type, c.data));
        }
        for (byte[] idatPayload : f.idatData) {
            writeChunk(baos, "IDAT", idatPayload);
        }
        writeChunk(baos, "IEND", new byte[0]);

        BufferedImage sub = ImageIO.read(new ByteArrayInputStream(baos.toByteArray()));
        if (sub == null) return null;

        // Ensure ARGB
        if (sub.getType() != BufferedImage.TYPE_INT_ARGB) {
            BufferedImage converted = new BufferedImage(sub.getWidth(), sub.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = converted.createGraphics();
            g.setComposite(AlphaComposite.Src);
            g.drawImage(sub, 0, 0, null);
            g.dispose();
            sub = converted;
        }
        return sub;
    }

    private static byte[] toChunkBytes(String type, byte[] data) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        writeChunk(baos, type, data);
        return baos.toByteArray();
    }

    private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
        byte[] typeBytes = type.getBytes(StandardCharsets.ISO_8859_1);
        writeInt(out, data.length);
        out.write(typeBytes);
        out.write(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        writeInt(out, (int) crc.getValue());
    }

    private static void writeInt(ByteArrayOutputStream out, int v) throws IOException {
        out.write((v >>> 24) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static int readInt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16) | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static void writeInt(byte[] b, int off, int v) {
        b[off] = (byte) ((v >>> 24) & 0xFF);
        b[off + 1] = (byte) ((v >>> 16) & 0xFF);
        b[off + 2] = (byte) ((v >>> 8) & 0xFF);
        b[off + 3] = (byte) (v & 0xFF);
    }

    private static BufferedImage deepCopy(BufferedImage src) {
        BufferedImage copy = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = copy.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return copy;
    }

    private static final class Chunk {
        final String type;
        final byte[] data;

        Chunk(String type, byte[] data) {
            this.type = type;
            this.data = data;
        }
    }

    private enum DisposeOp {
        NONE, BACKGROUND, PREVIOUS
    }

    private enum BlendOp {
        SOURCE, OVER
    }

    private static final class Frame {
        int width;
        int height;
        int xOffset;
        int yOffset;
        DisposeOp disposeOp = DisposeOp.NONE;
        BlendOp blendOp = BlendOp.OVER;
        final List<byte[]> idatData = new ArrayList<>();

        static Frame fromFcTL(byte[] data) throws IOException {
            // fcTL: sequence(4), width(4), height(4), x_offset(4), y_offset(4),
            // delay_num(2), delay_den(2), dispose_op(1), blend_op(1)
            if (data.length < 26) throw new IOException("Invalid fcTL length");
            Frame f = new Frame();
            f.width = readInt(data, 4);
            f.height = readInt(data, 8);
            f.xOffset = readInt(data, 12);
            f.yOffset = readInt(data, 16);
            int dispose = data[24] & 0xFF;
            int blend = data[25] & 0xFF;
            f.disposeOp = dispose == 1 ? DisposeOp.BACKGROUND : (dispose == 2 ? DisposeOp.PREVIOUS : DisposeOp.NONE);
            f.blendOp = blend == 0 ? BlendOp.SOURCE : BlendOp.OVER;
            return f;
        }
    }
}

