package org.gpur.compute;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.gpur.GPurConfig;
import org.gpur.terrain.TerrainRules;

/** Bounded startup calibration and persistent batch-size choice for custom terrain compute. */
public final class TerrainTuningProfile {
    private static final int PROFILE_SCHEMA = 1;
    private static final int[] CANDIDATES = {1, 2, 4, 8};
    private static final long SAMPLE_SEED = 0x5A17_2C93_7E41_B06DL;
    private static final int SAMPLE_MIN_Y = -64;
    private static final int SAMPLE_HEIGHT = 384;
    private static final int SAMPLE_SEA_LEVEL = 63;
    private static final int[] SAMPLE_CHUNK_COORDINATES = {
        -1_700_003, -1_700_011,
        -1_700_007, -1_700_019,
        -1_700_021, -1_700_029,
        -1_700_033, -1_700_041,
        -1_700_047, -1_700_059,
        -1_700_061, -1_700_073,
        -1_700_079, -1_700_087,
        -1_700_091, -1_700_103
    };

    private TerrainTuningProfile() { }

    /**
     * Returns a validated persisted batch size or calibrates four bounded candidates.
     * A loaded profile still receives one real terrain dispatch and CPU parity check.
     */
    public static int batchSize(VulkanDevice device, Logger logger) {
        Logger log = logger == null ? Logger.getLogger("GPur") : logger;
        int maximum = clampBatch(GPurConfig.terrainMaxBatchChunks);
        if (!GPurConfig.terrainAutoTune) return maximum;

        String fingerprint = device.fingerprint();
        Path directory = GPurConfig.gpuCacheDirectory();
        if (GPurConfig.gpuCacheEnabled) {
            OptionalInt stored = loadStoredBatch(directory, fingerprint, log);
            if (stored.isPresent()) {
                int selected = Math.min(maximum, stored.getAsInt());
                int[] input = sampleInput(selected);
                if (parityCheck(device, input, log)) {
                    return selected;
                }
                log.warning("GPur terrain tuning profile failed its startup parity check for GPU " + device.name());
                return maximum;
            }
        }

        final int[] candidates = Arrays.stream(CANDIDATES).filter(candidate -> candidate <= maximum).toArray();
        double bestNanosPerChunk = Double.POSITIVE_INFINITY;
        int bestBatch = maximum;
        for (int candidate : candidates) {
            int[] input = sampleInput(candidate);
            if (!parityCheck(device, input, log)) {
                log.warning("GPur terrain tuning stopped because a candidate failed its startup parity check for GPU " + device.name());
                return maximum;
            }

            long[] samples = new long[3];
            boolean usable = true;
            for (int repeat = 0; repeat < samples.length; repeat++) {
                long started = System.nanoTime();
                try {
                    if (device.compute(input, TerrainRules.outputWords(input), input[1]) == null) {
                        usable = false;
                        break;
                    }
                } catch (RuntimeException failure) {
                    log.log(Level.WARNING, "GPur terrain tuning dispatch failed for GPU " + device.name(), failure);
                    usable = false;
                    break;
                }
                samples[repeat] = System.nanoTime() - started;
            }
            if (!usable) {
                log.warning("GPur terrain tuning stopped because the GPU could not accept a calibration dispatch: " + device.name());
                return maximum;
            }
            Arrays.sort(samples);
            double nanosPerChunk = (double)samples[1] / candidate;
            if (nanosPerChunk < bestNanosPerChunk) {
                bestNanosPerChunk = nanosPerChunk;
                bestBatch = candidate;
            }
        }

        if (GPurConfig.gpuCacheEnabled) saveStoredBatch(directory, fingerprint, bestBatch, log);
        log.info("GPur terrain batch tuning selected " + bestBatch + " chunk(s) for GPU " + device.name());
        return bestBatch;
    }

    static OptionalInt loadStoredBatch(Path directory, String fingerprint) {
        return loadStoredBatch(directory, fingerprint, null);
    }

    private static OptionalInt loadStoredBatch(Path directory, String fingerprint, Logger logger) {
        Path profile = profilePath(directory, fingerprint);
        try {
            long size = Files.size(profile);
            if (size < 1 || size > 1024) return OptionalInt.empty();
            byte[] contents;
            try (InputStream input = Files.newInputStream(profile)) {
                contents = input.readNBytes(1025);
            }
            if (contents.length > 1024) return OptionalInt.empty();
            String[] lines = new String(contents, StandardCharsets.UTF_8).split("\\R", -1);
            if (lines.length != 5 || !lines[4].isEmpty()
                    || !lines[0].equals("schema=" + PROFILE_SCHEMA)
                    || !lines[1].equals("fingerprint=" + fingerprint)
                    || !lines[2].equals("terrainVersion=" + TerrainRules.VERSION)
                    || !lines[3].startsWith("batch=")) {
                return OptionalInt.empty();
            }
            int batch = Integer.parseInt(lines[3].substring("batch=".length()));
            return batch >= 1 && batch <= TerrainRules.MAX_BATCH_CHUNKS ? OptionalInt.of(batch) : OptionalInt.empty();
        } catch (java.nio.file.NoSuchFileException missing) {
            return OptionalInt.empty();
        } catch (IOException | SecurityException failure) {
            if (logger != null) logger.log(Level.WARNING, "Could not read GPur terrain tuning profile; recalibrating", failure);
            return OptionalInt.empty();
        } catch (NumberFormatException malformed) {
            return OptionalInt.empty();
        }
    }

    static boolean saveStoredBatch(Path directory, String fingerprint, int batch) {
        return saveStoredBatch(directory, fingerprint, batch, null);
    }

    private static boolean saveStoredBatch(Path directory, String fingerprint, int batch, Logger logger) {
        if (batch < 1 || batch > TerrainRules.MAX_BATCH_CHUNKS) return false;
        Path target = profilePath(directory, fingerprint);
        Path temporary = null;
        String contents = "schema=" + PROFILE_SCHEMA + "\n"
            + "fingerprint=" + fingerprint + "\n"
            + "terrainVersion=" + TerrainRules.VERSION + "\n"
            + "batch=" + batch + "\n";
        try {
            Files.createDirectories(directory);
            temporary = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(contents);
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
        } catch (IOException | SecurityException failure) {
            if (logger != null) logger.log(Level.WARNING, "Could not save GPur terrain tuning profile", failure);
            return false;
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException | SecurityException ignored) {
                    // A uniquely named leftover temporary profile is never loaded.
                }
            }
        }
    }

    private static Path profilePath(Path directory, String fingerprint) {
        Path cachePath = GpuPipelineCache.fileFor(directory, fingerprint);
        String name = cachePath.getFileName().toString().replaceFirst("\\.vkpc$", ".terrain-profile");
        return cachePath.resolveSibling(name);
    }

    private static void forceDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException | SecurityException ignored) {
            // Some platforms do not expose directory fsync; the profile file itself is forced before rename.
        }
    }

    private static int[] sampleInput(int chunks) {
        int[] coordinates = Arrays.copyOf(SAMPLE_CHUNK_COORDINATES, chunks * 2);
        return TerrainRules.input(SAMPLE_SEED, SAMPLE_MIN_Y, SAMPLE_HEIGHT, SAMPLE_SEA_LEVEL, coordinates);
    }

    private static boolean parityCheck(VulkanDevice device, int[] input, Logger logger) {
        final int[] actual;
        try {
            actual = device.compute(input, TerrainRules.outputWords(input), input[1]);
        } catch (RuntimeException failure) {
            logger.log(Level.WARNING, "GPur terrain startup parity dispatch failed for GPU " + device.name(), failure);
            return false;
        }
        if (actual == null) return false;
        if (Arrays.equals(TerrainRules.reference(input), actual)) return true;
        device.disable();
        return false;
    }

    private static int clampBatch(int batch) {
        return Math.max(1, Math.min(TerrainRules.MAX_BATCH_CHUNKS, batch));
    }
}
