package org.gpur.compute;

import static org.junit.jupiter.api.Assertions.*;

import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldgenParityDiagnosticsTest {
    @TempDir Path directory;

    @Test
    void mismatchIdentifiesAquiferQueryAndRankWithoutDumpingTheWholeInput() {
        int[] input = {6, 2, 0, 0, 0, 3, 4, 3, 1, -13, 2, -17, -64, 3};
        int[] expected = {1, 2, 3, 4, 5, 6, 7, 8};
        int[] actual = expected.clone();
        actual[6] = 9;
        String detail = WorldgenParityDiagnostics.describe(input, expected, actual);
        assertTrue(detail.contains("workload=6"));
        assertTrue(detail.contains("first differing word=6"));
        assertTrue(detail.contains("expected=0x7, actual=0x9"));
        assertTrue(detail.contains("query=-17,-64,3, rank=2"));
    }

    @Test
    void capturedDispatchPreservesRawWordsForAnIndependentNativeReproduction() throws Exception {
        int[] input = {6, 1, Integer.MIN_VALUE, Integer.MAX_VALUE, -1};
        int[] expected = {3, 2, 1, 0};
        int[] actual = {3, 2, 0, 1};
        Path snapshot = WorldgenParityDiagnostics.writeSnapshot(this.directory, input, expected, actual);
        try (DataInputStream saved = new DataInputStream(Files.newInputStream(snapshot))) {
            assertEquals(WorldgenParityDiagnostics.MAGIC, saved.readInt());
            assertEquals(1, saved.readInt());
            for (int[] words : new int[][] {input, expected, actual}) {
                assertEquals(words.length, saved.readInt());
                for (int word : words) assertEquals(word, saved.readInt());
            }
            assertEquals(-1, saved.read());
        }
    }

    @Test
    void wrongLengthCanBeCapturedWithoutComputingAReference() throws Exception {
        Path snapshot = WorldgenParityDiagnostics.writeSnapshot(this.directory, new int[] {6, 1}, null, new int[] {1});
        try (DataInputStream saved = new DataInputStream(Files.newInputStream(snapshot))) {
            assertEquals(WorldgenParityDiagnostics.MAGIC, saved.readInt());
            assertEquals(1, saved.readInt());
            assertEquals(2, saved.readInt());
            assertEquals(6, saved.readInt());
            assertEquals(1, saved.readInt());
            assertEquals(-1, saved.readInt());
            assertEquals(1, saved.readInt());
            assertEquals(1, saved.readInt());
            assertEquals(-1, saved.read());
        }
    }
}
