package org.finetree.finemaps.plugin.url;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AnimationRegistryTest {
    @Test
    void computesSmallestRectangularPatch() {
        byte[] previous = new byte[128 * 128];
        byte[] current = previous.clone();
        current[20 * 128 + 10] = 1;
        current[22 * 128 + 12] = 2;

        AnimationRegistry.MapPatch patch = AnimationRegistry.MapPatch.between(previous, current);

        assertNotNull(patch);
        assertEquals(10, patch.startX());
        assertEquals(20, patch.startY());
        assertEquals(3, patch.width());
        assertEquals(3, patch.height());
        assertArrayEquals(new byte[] {1, 0, 0, 0, 0, 0, 0, 0, 2}, patch.pixels());
    }

    @Test
    void suppressesUnchangedFrames() {
        byte[] frame = new byte[128 * 128];
        AnimationRegistry.MapPatch patch = AnimationRegistry.MapPatch.between(frame, frame.clone());
        assertNotNull(patch);
        assertTrue(patch.isEmpty());
    }

    @Test
    void requestsFullUpdateWithoutValidBaseline() {
        assertNull(AnimationRegistry.MapPatch.between(null, new byte[128 * 128]));
        assertNull(AnimationRegistry.MapPatch.between(new byte[4], new byte[128 * 128]));
    }

    @Test
    void bandwidthLimiterAllowsOneOversizedAtomicBatchThenThrottles() {
        AnimationRegistry.BandwidthLimiter limiter = new AnimationRegistry.BandwidthLimiter(1024);
        assertTrue(limiter.tryConsume(4096));
        assertFalse(limiter.tryConsume(4096));
    }
}
