package org.gpur.terrain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.gpur.compute.WorldgenResult;

/**
 * Owner-bound, complete corner results for one density root. A caller must already have
 * verified each numeric part against the
 * corresponding graph and sample request before transferring ownership here.
 */
public final class VanillaCornerGraphFrame implements AutoCloseable {
    public record Lattice(int firstCellX, int firstCellY, int firstCellZ,
                          int cellWidth, int cellHeight, int cellsXZ, int cellsY) {
        public Lattice {
            if (cellWidth < 1 || cellWidth > 16 || cellHeight < 1 || cellHeight > 64
                || cellsXZ < 1 || cellsXZ > 16 || cellsY < 1 || cellsY > 1_024) {
                throw new IllegalArgumentException("Corner lattice dimensions are outside the supported bounds");
            }
            long count = (long)(cellsXZ + 1) * (cellsXZ + 1) * (cellsY + 1);
            if (count > VanillaDensityGraphBatch.MAX_SAMPLES) {
                throw new IllegalArgumentException("Corner lattice exceeds the sample limit");
            }
            checkCoordinate((long)firstCellX * cellWidth);
            checkCoordinate(((long)firstCellX + cellsXZ) * cellWidth);
            checkCoordinate((long)firstCellZ * cellWidth);
            checkCoordinate(((long)firstCellZ + cellsXZ) * cellWidth);
            checkCoordinate((long)firstCellY * cellHeight);
            checkCoordinate(((long)firstCellY + cellsY) * cellHeight);
        }

        public int sampleCount() { return (this.cellsXZ + 1) * (this.cellsXZ + 1) * (this.cellsY + 1); }
        public int columnCount() { return (this.cellsXZ + 1) * (this.cellsXZ + 1); }
        public int columnHeight() { return this.cellsY + 1; }

        private static void checkCoordinate(final long coordinate) {
            if (coordinate < -VanillaDensityGraphBatch.MAX_BLOCK_COORDINATE
                || coordinate > VanillaDensityGraphBatch.MAX_BLOCK_COORDINATE) {
                throw new IllegalArgumentException("Corner lattice exceeds the block-coordinate limit");
            }
        }
    }

    /** Parts must cover complete X/Z columns in x-major, z-next, y-minor order. */
    public record Part(int firstSample, WorldgenResult result) {
        public Part { Objects.requireNonNull(result, "result"); }
    }

    private final Object owner;
    private final DensityFunction root;
    private final Lattice lattice;
    private final List<Part> parts;
    private final int[] partByColumn;
    private final AtomicBoolean closed = new AtomicBoolean();

    private VanillaCornerGraphFrame(final Object owner, final DensityFunction root, final Lattice lattice,
                                    final List<Part> parts, final int[] partByColumn) {
        this.owner = owner;
        this.root = root;
        this.lattice = lattice;
        this.parts = parts;
        this.partByColumn = partByColumn;
    }

    /**
     * Transfers every part, including on failure. A missing, stale, malformed or incomplete part
     * closes all supplied results and returns null; partial root results are never admitted.
     */
    public static VanillaCornerGraphFrame take(final Object owner, final DensityFunction root,
                                               final Lattice lattice, final List<Part> supplied) {
        Objects.requireNonNull(supplied, "parts");
        List<Part> parts = new ArrayList<>(supplied);
        boolean transferred = false;
        try {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(root, "root");
            Objects.requireNonNull(lattice, "lattice");
            if (parts.isEmpty() || parts.size() > lattice.columnCount()) return null;
            int[] partByColumn = new int[lattice.columnCount()];
            Set<WorldgenResult> distinct = Collections.newSetFromMap(new IdentityHashMap<>());
            int nextSample = 0;
            int columnHeight = lattice.columnHeight();
            for (int index = 0; index < parts.size(); index++) {
                Part part = parts.get(index);
                if (part == null || !distinct.add(part.result)) return null;
                int[] words = part.result.readWords();
                if (part.firstSample != nextSample || words == null || words.length == 0
                    || (words.length & 1) != 0 || (words.length / 2) % columnHeight != 0) return null;
                int samples = words.length / 2;
                if (samples > lattice.sampleCount() - nextSample) return null;
                for (int sample = 0; sample < samples; sample++) {
                    if (!Double.isFinite(VanillaDensityGraphBatch.outputValue(words, sample))) return null;
                }
                int firstColumn = nextSample / columnHeight;
                int endColumn = (nextSample + samples) / columnHeight;
                for (int column = firstColumn; column < endColumn; column++) partByColumn[column] = index;
                nextSample += samples;
            }
            if (nextSample != lattice.sampleCount()) return null;
            VanillaCornerGraphFrame frame = new VanillaCornerGraphFrame(owner, root, lattice, List.copyOf(parts), partByColumn);
            transferred = true;
            return frame;
        } finally {
            if (!transferred) parts.forEach(part -> { if (part != null) part.result.close(); });
        }
    }

    /**
     * Copies one complete Y column only for the exact owner, root and lattice coordinates.
     * A refused read leaves output unchanged. A successful read holds the immutable Java array
     * lease for the complete operation, following WorldgenResult's shutdown/readback contract.
     */
    public boolean tryFillColumn(final Object owner, final DensityFunction root,
                                 final int cellX, final int cellZ, final double[] output) {
        Objects.requireNonNull(output, "output");
        if (this.closed.get() || this.owner != owner || this.root != root
            || output.length != this.lattice.columnHeight()) return false;
        long x = (long)cellX - this.lattice.firstCellX;
        long z = (long)cellZ - this.lattice.firstCellZ;
        if (x < 0 || z < 0 || x > this.lattice.cellsXZ || z > this.lattice.cellsXZ) return false;
        int column = (int)x * (this.lattice.cellsXZ + 1) + (int)z;
        Part part = this.parts.get(this.partByColumn[column]);
        int[] words = part.result.readWords();
        if (words == null) return false;
        int firstSample = column * this.lattice.columnHeight() - part.firstSample;
        for (int y = 0; y < output.length; y++) {
            output[y] = VanillaDensityGraphBatch.outputValue(words, firstSample + y);
        }
        part.result.recordConsumption(output.length);
        return true;
    }

    public Lattice lattice() { return this.lattice; }

    public boolean usable() {
        return !this.closed.get() && this.parts.stream().allMatch(part -> part.result.usable());
    }

    @Override
    public void close() {
        if (this.closed.compareAndSet(false, true)) this.parts.forEach(part -> part.result.close());
    }
}
