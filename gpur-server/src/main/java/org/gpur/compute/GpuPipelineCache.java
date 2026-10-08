package org.gpur.compute;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Validates and stores Vulkan's implementation-owned pipeline cache blob. */
public final class GpuPipelineCache {
    public static final int HEADER_BYTES = 32;
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    public static final int HEADER_VERSION_ONE = 1;

    private GpuPipelineCache() { }

    /**
     * Returns the cached data only when its standard Vulkan header matches the selected physical device.
     * Filesystem failures and malformed cache data are treated as cache misses.
     */
    public static Optional<byte[]> load(Path directory, String fingerprint, int vendorId, int deviceId, byte[] cacheUuid) {
        return load(directory, fingerprint, vendorId, deviceId, cacheUuid, null);
    }

    public static Optional<byte[]> load(Path directory, String fingerprint, int vendorId, int deviceId, byte[] cacheUuid, Logger logger) {
        Path file = fileFor(directory, fingerprint);
        try {
            long size = Files.size(file);
            if (size < HEADER_BYTES || size > MAX_BYTES) return Optional.empty();
            byte[] data;
            try (InputStream input = Files.newInputStream(file)) {
                data = input.readNBytes(MAX_BYTES + 1);
            }
            if (data.length > MAX_BYTES) return Optional.empty();
            if (!valid(data, vendorId, deviceId, cacheUuid)) return Optional.empty();
            return Optional.of(data);
        } catch (java.nio.file.NoSuchFileException missing) {
            return Optional.empty();
        } catch (IOException | SecurityException exception) {
            if (logger != null) logger.log(Level.WARNING, "Could not read GPur Vulkan pipeline cache; rebuilding it", exception);
            return Optional.empty();
        }
    }

    /** Writes to a sibling temporary file, forces its contents, then replaces the cache atomically. */
    public static boolean save(Path directory, String fingerprint, byte[] data,
                               int vendorId, int deviceId, byte[] cacheUuid) {
        return save(directory, fingerprint, data, vendorId, deviceId, cacheUuid, null);
    }

    public static boolean save(Path directory, String fingerprint, byte[] data,
                               int vendorId, int deviceId, byte[] cacheUuid, Logger logger) {
        if (!valid(data, vendorId, deviceId, cacheUuid)) return false;
        Path target = fileFor(directory, fingerprint);
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            temporary = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = ByteBuffer.wrap(data);
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            forceDirectory(directory);
            return true;
        } catch (IOException | SecurityException exception) {
            if (logger != null) logger.log(Level.WARNING, "Could not save GPur Vulkan pipeline cache", exception);
            return false;
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException | SecurityException ignored) {
                    // A leftover uniquely named temporary file is never loaded as a cache.
                }
            }
        }
    }

    /** Vulkan's standard cache header is little-endian regardless of the host architecture. */
    public static boolean valid(byte[] data, int vendorId, int deviceId, byte[] cacheUuid) {
        if (data == null || data.length < HEADER_BYTES || data.length > MAX_BYTES || cacheUuid == null || cacheUuid.length != 16) {
            return false;
        }
        ByteBuffer header = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        long headerSize = Integer.toUnsignedLong(header.getInt(0));
        if (headerSize < HEADER_BYTES || headerSize > data.length) return false;
        if (header.getInt(4) != HEADER_VERSION_ONE || header.getInt(8) != vendorId || header.getInt(12) != deviceId) {
            return false;
        }
        for (int index = 0; index < cacheUuid.length; index++) {
            if (data[16 + index] != cacheUuid[index]) return false;
        }
        return true;
    }

    /** Stable identity digest; it deliberately contains no display-name or installation-path data. */
    public static String fingerprint(String physicalUuid, int vendorId, int deviceId, int driverVersion,
                                     byte[] cacheUuid, String shaderSha256, int terrainVersion) {
        if (physicalUuid == null || shaderSha256 == null || cacheUuid == null || cacheUuid.length != 16) {
            throw new IllegalArgumentException("Incomplete Vulkan cache identity");
        }
        String identity = physicalUuid + '\n' + Integer.toUnsignedString(vendorId) + '\n'
            + Integer.toUnsignedString(deviceId) + '\n' + Integer.toUnsignedString(driverVersion) + '\n'
            + HexFormat.of().formatHex(cacheUuid) + '\n' + shaderSha256 + '\n' + terrainVersion;
        return sha256(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static Path fileFor(Path directory, String fingerprint) {
        return directory.resolve(sha256(fingerprint.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ".vkpc");
    }

    private static String sha256(byte[] input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void forceDirectory(Path directory) {
        // Directory channels are not supported by every platform. File contents are already forced above.
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException | SecurityException ignored) {
            // The atomic rename is the durability boundary on platforms without directory fsync.
        }
    }
}
