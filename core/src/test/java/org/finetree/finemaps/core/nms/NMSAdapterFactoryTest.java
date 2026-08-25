package org.finetree.finemaps.core.nms;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NMSAdapterFactoryTest {

    @Test
    void parsesStandardMinecraftVersions() {
        assertArrayEquals(new int[] {21, 4}, NMSAdapterFactory.parseMinecraftVersion("1.21.4"));
        assertArrayEquals(new int[] {21, 0}, NMSAdapterFactory.parseMinecraftVersion("1.21-R0.1-SNAPSHOT"));
    }

    @Test
    void extractsMinecraftVersionFromServerDescription() {
        assertArrayEquals(new int[] {21, 11},
            NMSAdapterFactory.parseMinecraftVersion("Paper build 116 (MC: 1.21.11)"));
    }

    @Test
    void doesNotTreatForkReleaseAsMinecraftVersion() {
        assertNull(NMSAdapterFactory.parseMinecraftVersion("26.2.build.116-stable"));
    }
}
