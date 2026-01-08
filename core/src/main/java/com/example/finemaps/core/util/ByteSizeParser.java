package com.example.finemaps.core.util;

/**
 * Parses human-friendly byte sizes like "512K", "10M", or "1G".
 * If no suffix is provided, K is assumed (safer default).
 */
public final class ByteSizeParser {

    private ByteSizeParser() {
    }

    /**
     * Parses a size string into bytes.
     * Supports suffix K, M, G (case-insensitive) with no space.
     * If no suffix is provided, K is assumed.
     *
     * Examples:
     * - "512K" -> 524288
     * - "10M"  -> 10485760
     * - "1G"   -> 1073741824
     * - "500"  -> 512000 (assumes K)
     */
    public static long parseToBytes(String raw) {
        if (raw == null) throw new IllegalArgumentException("size is null");
        String s = raw.trim();
        if (s.isEmpty()) throw new IllegalArgumentException("size is empty");

        char last = s.charAt(s.length() - 1);
        long multiplier;
        String numberPart;

        if (Character.isLetter(last)) {
            numberPart = s.substring(0, s.length() - 1).trim();
            switch (Character.toUpperCase(last)) {
                case 'K':
                    multiplier = 1024L;
                    break;
                case 'M':
                    multiplier = 1024L * 1024L;
                    break;
                case 'G':
                    multiplier = 1024L * 1024L * 1024L;
                    break;
                default:
                    throw new IllegalArgumentException("unknown size suffix '" + last + "' in '" + raw + "'");
            }
        } else {
            // No suffix -> assume K for safety.
            numberPart = s;
            multiplier = 1024L;
        }

        if (numberPart.isEmpty()) throw new IllegalArgumentException("missing number in '" + raw + "'");

        long value;
        try {
            value = Long.parseLong(numberPart);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid size number '" + numberPart + "' in '" + raw + "'", e);
        }

        if (value < 0) throw new IllegalArgumentException("size must be >= 0");

        // Guard overflow
        if (value > Long.MAX_VALUE / multiplier) {
            return Long.MAX_VALUE;
        }
        return value * multiplier;
    }
}

