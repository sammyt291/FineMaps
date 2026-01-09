package org.finetree.finemaps.core.compression;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Run-Length Encoding compression for map pixel data.
 * Optimized for map data which often has repeated color values.
 */
public final class RLECompression {

    private RLECompression() {
        // Utility class
    }

    /**
     * Compresses pixel data using RLE followed by DEFLATE.
     * This two-stage compression is highly effective for map data.
     *
     * @param data The raw pixel data (16384 bytes for a map)
     * @return Compressed data
     */
    public static byte[] compress(byte[] data) {
        if (data == null || data.length == 0) {
            return new byte[0];
        }

        // First apply RLE
        byte[] rleCompressed = rleEncode(data);

        // Then apply DEFLATE for additional compression
        return deflateCompress(rleCompressed);
    }

    /**
     * Decompresses pixel data that was compressed with compress().
     *
     * @param compressed The compressed data
     * @param expectedLength Expected length of decompressed data
     * @return Decompressed pixel data
     */
    public static byte[] decompress(byte[] compressed, int expectedLength) {
        if (compressed == null || compressed.length == 0) {
            return new byte[expectedLength];
        }

        // First inflate
        byte[] rleCompressed = deflateDecompress(compressed);

        // Then decode RLE
        return rleDecode(rleCompressed, expectedLength);
    }

    /**
     * Run-Length Encode the data.
     * Format: [value, count] pairs where count is 1-255.
     * If count would exceed 255, multiple pairs are used.
     *
     * @param data Raw data
     * @return RLE encoded data
     */
    public static byte[] rleEncode(byte[] data) {
        if (data == null || data.length == 0) {
            return new byte[0];
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        
        int i = 0;
        while (i < data.length) {
            byte value = data[i];
            int count = 1;
            
            // Count consecutive identical values (max 255)
            while (i + count < data.length && data[i + count] == value && count < 255) {
                count++;
            }
            
            out.write(value & 0xFF);
            out.write(count);
            
            i += count;
        }
        
        return out.toByteArray();
    }

    /**
     * Decode RLE data.
     *
     * @param encoded RLE encoded data
     * @param expectedLength Expected output length
     * @return Decoded data
     */
    public static byte[] rleDecode(byte[] encoded, int expectedLength) {
        if (encoded == null || encoded.length == 0) {
            return new byte[expectedLength];
        }

        byte[] result = new byte[expectedLength];
        int outIndex = 0;
        int inIndex = 0;

        while (inIndex < encoded.length - 1 && outIndex < expectedLength) {
            byte value = encoded[inIndex++];
            int count = encoded[inIndex++] & 0xFF;

            for (int j = 0; j < count && outIndex < expectedLength; j++) {
                result[outIndex++] = value;
            }
        }

        return result;
    }

    /**
     * Apply DEFLATE compression.
     *
     * @param data Data to compress
     * @return Compressed data
     */
    private static byte[] deflateCompress(byte[] data) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DeflaterOutputStream dos = new DeflaterOutputStream(baos)) {
            dos.write(data);
            dos.finish();
            return baos.toByteArray();
        } catch (IOException e) {
            // Fallback to uncompressed
            return data;
        }
    }

    /**
     * Apply DEFLATE decompression.
     *
     * @param compressed Compressed data
     * @return Decompressed data
     */
    private static byte[] deflateDecompress(byte[] compressed) {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
             InflaterInputStream iis = new InflaterInputStream(bais);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            
            byte[] buffer = new byte[1024];
            int len;
            while ((len = iis.read(buffer)) > 0) {
                baos.write(buffer, 0, len);
            }
            return baos.toByteArray();
        } catch (IOException e) {
            // Assume data wasn't actually compressed
            return compressed;
        }
    }

    /**
     * Calculates the compression ratio.
     *
     * @param original Original size
     * @param compressed Compressed size
     * @return Compression ratio (e.g., 0.25 means 75% reduction)
     */
    public static double getCompressionRatio(int original, int compressed) {
        if (original == 0) return 1.0;
        return (double) compressed / original;
    }

    /**
     * Estimates the compressed size without actually compressing.
     * Useful for deciding whether to store compressed or not.
     *
     * @param data The data to analyze
     * @return Estimated compressed size
     */
    public static int estimateCompressedSize(byte[] data) {
        if (data == null || data.length == 0) {
            return 0;
        }

        int runs = 1;
        for (int i = 1; i < data.length; i++) {
            if (data[i] != data[i - 1]) {
                runs++;
            }
        }

        // RLE: 2 bytes per run, then DEFLATE typically gives ~50% on that
        return (int) (runs * 2 * 0.5);
    }
}
