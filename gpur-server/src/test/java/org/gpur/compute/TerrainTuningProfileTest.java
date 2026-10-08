package org.gpur.compute;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalInt;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TerrainTuningProfileTest {
    @TempDir Path temporaryDirectory;

    @Test
    void profileRoundTripsAndAtomicReplacementKeepsACompleteChoice() throws Exception {
        String fingerprint = "stable-gpu-key";

        assertTrue(TerrainTuningProfile.saveStoredBatch(temporaryDirectory, fingerprint, 4));
        assertEquals(OptionalInt.of(4), TerrainTuningProfile.loadStoredBatch(temporaryDirectory, fingerprint));
        assertTrue(TerrainTuningProfile.saveStoredBatch(temporaryDirectory, fingerprint, 8));
        assertEquals(OptionalInt.of(8), TerrainTuningProfile.loadStoredBatch(temporaryDirectory, fingerprint));

        try (Stream<Path> files = Files.list(temporaryDirectory)) {
            var paths = files.toList();
            assertEquals(1, paths.size());
            assertTrue(paths.getFirst().getFileName().toString().matches("[0-9a-f]{64}\\.terrain-profile"));
        }
    }

    @Test
    void profileRejectsWrongFingerprintSchemaVersionAndBatch() throws Exception {
        String fingerprint = "expected-fingerprint";
        assertTrue(TerrainTuningProfile.saveStoredBatch(temporaryDirectory, fingerprint, 2));
        Path profile;
        try (Stream<Path> files = Files.list(temporaryDirectory)) {
            profile = files.findFirst().orElseThrow();
        }

        assertTrue(TerrainTuningProfile.loadStoredBatch(temporaryDirectory, "other-fingerprint").isEmpty());

        Files.writeString(profile, "schema=2\nfingerprint=" + fingerprint + "\nterrainVersion=1\nbatch=2\n",
            StandardCharsets.UTF_8);
        assertTrue(TerrainTuningProfile.loadStoredBatch(temporaryDirectory, fingerprint).isEmpty());

        Files.writeString(profile, "schema=1\nfingerprint=" + fingerprint + "\nterrainVersion=999\nbatch=2\n",
            StandardCharsets.UTF_8);
        assertTrue(TerrainTuningProfile.loadStoredBatch(temporaryDirectory, fingerprint).isEmpty());

        Files.writeString(profile, "schema=1\nfingerprint=" + fingerprint + "\nterrainVersion=1\nbatch=0\n",
            StandardCharsets.UTF_8);
        assertTrue(TerrainTuningProfile.loadStoredBatch(temporaryDirectory, fingerprint).isEmpty());

        Files.writeString(profile, "schema=1\nfingerprint=" + fingerprint + "\nterrainVersion=1\nbatch=9\n",
            StandardCharsets.UTF_8);
        assertTrue(TerrainTuningProfile.loadStoredBatch(temporaryDirectory, fingerprint).isEmpty());
    }

    @Test
    void rejectsBatchWritesOutsideSupportedRange() {
        assertFalse(TerrainTuningProfile.saveStoredBatch(temporaryDirectory, "fingerprint", 0));
        assertFalse(TerrainTuningProfile.saveStoredBatch(temporaryDirectory, "fingerprint", 9));
        assertTrue(TerrainTuningProfile.loadStoredBatch(temporaryDirectory, "fingerprint").isEmpty());
    }
}
