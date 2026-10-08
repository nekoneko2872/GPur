package org.gpur.compute;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GpuPipelineCacheTest {
    private static final int VENDOR = 0x1234;
    private static final int DEVICE = 0x5678;
    private static final byte[] UUID = HexFormat.of().parseHex("00112233445566778899aabbccddeeff");

    @TempDir Path temporaryDirectory;

    @Test
    void cacheRoundTripsAndReplacementLeavesOnlyCompleteFiles() throws Exception {
        String fingerprint = "device-fingerprint";
        byte[] first = cacheBlob(41);
        byte[] replacement = cacheBlob(73);

        assertTrue(GpuPipelineCache.save(temporaryDirectory, fingerprint, first, VENDOR, DEVICE, UUID));
        assertArrayEquals(first, GpuPipelineCache.load(temporaryDirectory, fingerprint, VENDOR, DEVICE, UUID).orElseThrow());
        assertTrue(GpuPipelineCache.save(temporaryDirectory, fingerprint, replacement, VENDOR, DEVICE, UUID));
        assertArrayEquals(replacement, GpuPipelineCache.load(temporaryDirectory, fingerprint, VENDOR, DEVICE, UUID).orElseThrow());

        try (Stream<Path> files = Files.list(temporaryDirectory)) {
            assertEquals(1, files.count(), "Temporary files must not be mistaken for or left beside the durable cache");
        }
    }

    @Test
    void malformedAndMismatchedHeadersAreCacheMisses() throws Exception {
        String fingerprint = "bad-data";
        byte[] valid = cacheBlob(40);
        Path file = GpuPipelineCache.fileFor(temporaryDirectory, fingerprint);

        byte[] truncatedHeader = Arrays.copyOf(valid, GpuPipelineCache.HEADER_BYTES - 1);
        Files.write(file, truncatedHeader);
        assertTrue(GpuPipelineCache.load(temporaryDirectory, fingerprint, VENDOR, DEVICE, UUID).isEmpty());

        byte[] wrongVersion = valid.clone();
        ByteBuffer.wrap(wrongVersion).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 2);
        Files.write(file, wrongVersion);
        assertTrue(GpuPipelineCache.load(temporaryDirectory, fingerprint, VENDOR, DEVICE, UUID).isEmpty());

        byte[] tooSmallHeader = valid.clone();
        ByteBuffer.wrap(tooSmallHeader).order(ByteOrder.LITTLE_ENDIAN).putInt(0, GpuPipelineCache.HEADER_BYTES - 1);
        Files.write(file, tooSmallHeader);
        assertTrue(GpuPipelineCache.load(temporaryDirectory, fingerprint, VENDOR, DEVICE, UUID).isEmpty());

        byte[] oversizedHeader = valid.clone();
        ByteBuffer.wrap(oversizedHeader).order(ByteOrder.LITTLE_ENDIAN).putInt(0, valid.length + 1);
        Files.write(file, oversizedHeader);
        assertTrue(GpuPipelineCache.load(temporaryDirectory, fingerprint, VENDOR, DEVICE, UUID).isEmpty());

        Files.write(file, valid);
        assertTrue(GpuPipelineCache.load(temporaryDirectory, fingerprint, VENDOR + 1, DEVICE, UUID).isEmpty());
        assertTrue(GpuPipelineCache.load(temporaryDirectory, fingerprint, VENDOR, DEVICE + 1, UUID).isEmpty());
        byte[] otherUuid = UUID.clone();
        otherUuid[0]++;
        assertTrue(GpuPipelineCache.load(temporaryDirectory, fingerprint, VENDOR, DEVICE, otherUuid).isEmpty());

        Files.write(file, new byte[GpuPipelineCache.MAX_BYTES + 1]);
        assertTrue(GpuPipelineCache.load(temporaryDirectory, fingerprint, VENDOR, DEVICE, UUID).isEmpty());
    }

    @Test
    void fingerprintAndFilenameTrackDeviceDriverShaderAndTerrainIdentity() {
        String base = fingerprint("physical-uuid", VENDOR, DEVICE, 17, UUID, "shader-a", 1);
        assertEquals(base, fingerprint("physical-uuid", VENDOR, DEVICE, 17, UUID, "shader-a", 1));
        assertNotEquals(base, fingerprint("other-uuid", VENDOR, DEVICE, 17, UUID, "shader-a", 1));
        assertNotEquals(base, fingerprint("physical-uuid", VENDOR + 1, DEVICE, 17, UUID, "shader-a", 1));
        assertNotEquals(base, fingerprint("physical-uuid", VENDOR, DEVICE + 1, 17, UUID, "shader-a", 1));
        assertNotEquals(base, fingerprint("physical-uuid", VENDOR, DEVICE, 18, UUID, "shader-a", 1));
        byte[] otherUuid = UUID.clone();
        otherUuid[15]++;
        assertNotEquals(base, fingerprint("physical-uuid", VENDOR, DEVICE, 17, otherUuid, "shader-a", 1));
        assertNotEquals(base, fingerprint("physical-uuid", VENDOR, DEVICE, 17, UUID, "shader-b", 1));
        assertNotEquals(base, fingerprint("physical-uuid", VENDOR, DEVICE, 17, UUID, "shader-a", 2));

        Path cacheFile = GpuPipelineCache.fileFor(temporaryDirectory, base);
        assertTrue(cacheFile.getFileName().toString().matches("[0-9a-f]{64}\\.vkpc"));
        assertFalse(cacheFile.toString().contains("physical-uuid"));
    }

    private static byte[] cacheBlob(int length) {
        byte[] blob = new byte[length];
        ByteBuffer header = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(GpuPipelineCache.HEADER_BYTES).putInt(GpuPipelineCache.HEADER_VERSION_ONE)
            .putInt(VENDOR).putInt(DEVICE).put(UUID);
        for (int i = GpuPipelineCache.HEADER_BYTES; i < blob.length; i++) blob[i] = (byte)i;
        return blob;
    }

    private static String fingerprint(String uuid, int vendor, int device, int driver, byte[] cacheUuid,
                                      String shaderHash, int terrainVersion) {
        return GpuPipelineCache.fingerprint(uuid, vendor, device, driver, cacheUuid, shaderHash, terrainVersion);
    }
}
