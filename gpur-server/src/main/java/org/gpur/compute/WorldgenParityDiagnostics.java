package org.gpur.compute;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.logging.Logger;

/** Failure-only diagnostics; ordinary dispatches never perform filesystem I/O. */
final class WorldgenParityDiagnostics {
    static final int MAGIC = 0x47505552;
    static final int VERSION = 1;
    private static final long MAX_CAPTURE_WORDS = 16L * 1024 * 1024;

    private WorldgenParityDiagnostics() {}

    static String describe(int[] input, int[] expected, int[] actual) {
        int expectedLength = expected == null ? ExactCompute.outputWords(input) : expected.length;
        String detail = "workload=" + input[0] + ", values=" + input[1]
            + ", input words=" + input.length + ", expected/actual words=" + expectedLength + "/" + actual.length;
        if (expected == null) return detail;
        int difference = Arrays.mismatch(expected, actual);
        if (difference < 0) return detail;
        detail += ", first differing word=" + difference;
        if (difference < expected.length && difference < actual.length) {
            detail += ", expected=0x" + Integer.toHexString(expected[difference])
                + ", actual=0x" + Integer.toHexString(actual[difference]);
        }
        if (input[0] == VanillaAquiferBatch.WORKLOAD && difference / 4 < input[1]) {
            int offset = VanillaAquiferBatch.HEADER_WORDS + (difference / 4) * 3;
            detail += ", query=" + input[offset] + "," + input[offset + 1] + "," + input[offset + 2]
                + ", rank=" + difference % 4;
        }
        return detail;
    }

    /** An explicit JVM property is required, used only in isolated validation runs. */
    static void captureIfRequested(Logger logger, int[] input, int[] expected, int[] actual) {
        try {
            String directory = System.getProperty("gpur.worldgen.parity-dump", "");
            if (directory.isBlank()) return;
            Path file = writeSnapshot(Path.of(directory), input, expected, actual);
            logger.warning("Worldgen parity reproduction saved to " + file);
        } catch (IOException | RuntimeException failure) {
            // Quarantine has already happened. A diagnostic failure must not prevent fallback.
            logger.warning("Could not capture worldgen parity reproduction: " + failure.getMessage());
        }
    }

    static Path writeSnapshot(Path directory, int[] input, int[] expected, int[] actual) throws IOException {
        long totalWords = (long)input.length + actual.length + (expected == null ? 0 : expected.length);
        if (totalWords > MAX_CAPTURE_WORDS) throw new IOException("Parity capture exceeds its 64 MiB limit");
        Files.createDirectories(directory);
        Path file = Files.createTempFile(directory, "gpur-parity-w" + input[0] + "-", ".bin");
        try {
            try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
                output.writeInt(MAGIC);
                output.writeInt(VERSION);
                writeWords(output, input);
                writeWords(output, expected);
                writeWords(output, actual);
            }
            return file;
        } catch (IOException | RuntimeException failure) {
            try { Files.deleteIfExists(file); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private static void writeWords(DataOutputStream output, int[] words) throws IOException {
        output.writeInt(words == null ? -1 : words.length);
        if (words != null) for (int word : words) output.writeInt(word);
    }
}
