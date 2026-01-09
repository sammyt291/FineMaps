package org.finetree.finemaps.plugin.util;

import java.io.BufferedInputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

/**
 * Minimal NBT reader for vanilla map_*.dat files.
 *
 * Vanilla map files are gzipped NBT. We only need the "colors" byte array (16384 bytes)
 * stored under the "data" compound in most versions.
 *
 * This intentionally does not build a full NBT object model; it streams and skips tags.
 */
public final class VanillaMapDatReader {
    private VanillaMapDatReader() {}

    private static final int TAG_END = 0;
    private static final int TAG_BYTE = 1;
    private static final int TAG_SHORT = 2;
    private static final int TAG_INT = 3;
    private static final int TAG_LONG = 4;
    private static final int TAG_FLOAT = 5;
    private static final int TAG_DOUBLE = 6;
    private static final int TAG_BYTE_ARRAY = 7;
    private static final int TAG_STRING = 8;
    private static final int TAG_LIST = 9;
    private static final int TAG_COMPOUND = 10;
    private static final int TAG_INT_ARRAY = 11;
    private static final int TAG_LONG_ARRAY = 12;

    /**
     * Reads the 128x128 color bytes from a vanilla map file.
     *
     * @param mapDat map_*.dat file
     * @return Optional colors byte[] if found (may be != 16384 for broken/unsupported files)
     */
    public static Optional<byte[]> readColors(File mapDat) throws IOException {
        if (mapDat == null || !mapDat.isFile()) return Optional.empty();

        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(new FileInputStream(mapDat))))) {
            int rootType = in.readUnsignedByte();
            if (rootType != TAG_COMPOUND) {
                return Optional.empty();
            }
            // root name
            in.readUTF();

            Holder holder = new Holder();
            readCompound(in, "", holder);
            return Optional.ofNullable(holder.colors);
        }
    }

    private static final class Holder {
        byte[] colors;
    }

    private static void readCompound(DataInput in, String path, Holder out) throws IOException {
        while (true) {
            int type = in.readUnsignedByte();
            if (type == TAG_END) {
                return;
            }

            String name = in.readUTF();

            // Prefer "data/colors" but also accept "colors" at root if encountered.
            boolean inData = "data".equals(path);
            if (type == TAG_COMPOUND) {
                if ("data".equals(name) && path.isEmpty()) {
                    readCompound(in, "data", out);
                } else {
                    // descend but we don't care about the name for non-root "data"
                    readCompound(in, path.isEmpty() ? name : (path + "/" + name), out);
                }
                if (out.colors != null) {
                    // We found it; continue consuming siblings is unnecessary but safe to return early.
                    // Returning early avoids extra IO on huge map files (rare).
                    return;
                }
                continue;
            }

            if (type == TAG_BYTE_ARRAY) {
                if ((inData && "colors".equals(name)) || (path.isEmpty() && "colors".equals(name))) {
                    int len = in.readInt();
                    byte[] bytes = new byte[len];
                    if (len > 0) {
                        readFully(in, bytes);
                    }
                    out.colors = bytes;
                    return;
                }
            }

            // Not what we need; skip payload.
            skipPayload(in, type);
        }
    }

    private static void skipPayload(DataInput in, int type) throws IOException {
        switch (type) {
            case TAG_BYTE:
                in.readByte();
                return;
            case TAG_SHORT:
                in.readShort();
                return;
            case TAG_INT:
                in.readInt();
                return;
            case TAG_LONG:
                in.readLong();
                return;
            case TAG_FLOAT:
                in.readFloat();
                return;
            case TAG_DOUBLE:
                in.readDouble();
                return;
            case TAG_STRING:
                in.readUTF();
                return;
            case TAG_BYTE_ARRAY: {
                int len = in.readInt();
                skipFully(in, len);
                return;
            }
            case TAG_INT_ARRAY: {
                int len = in.readInt();
                skipFully(in, len * 4L);
                return;
            }
            case TAG_LONG_ARRAY: {
                int len = in.readInt();
                skipFully(in, len * 8L);
                return;
            }
            case TAG_LIST: {
                int elemType = in.readUnsignedByte();
                int len = in.readInt();
                for (int i = 0; i < len; i++) {
                    if (elemType == TAG_COMPOUND) {
                        readCompound(in, "<list>", new Holder()); // skip compound elements
                    } else {
                        skipPayload(in, elemType);
                    }
                }
                return;
            }
            case TAG_COMPOUND:
                readCompound(in, "<compound>", new Holder()); // skip
                return;
            default:
                // Unknown tag; best-effort: treat as end
                return;
        }
    }

    private static void readFully(DataInput in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int read = ((DataInputStream) in).read(buf, off, buf.length - off);
            if (read < 0) throw new IOException("Unexpected EOF");
            off += read;
        }
    }

    private static void skipFully(DataInput in, long bytes) throws IOException {
        long remaining = bytes;
        while (remaining > 0) {
            int step = (int) Math.min(Integer.MAX_VALUE, remaining);
            int skipped = ((DataInputStream) in).skipBytes(step);
            if (skipped <= 0) throw new IOException("Unexpected EOF while skipping");
            remaining -= skipped;
        }
    }
}

