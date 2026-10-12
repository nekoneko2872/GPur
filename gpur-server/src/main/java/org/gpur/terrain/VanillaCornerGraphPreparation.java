package org.gpur.terrain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.gpur.compute.WorldgenResult;

/**
 * Off-path coordinator for complete corner frames across a set of immutable density snapshots.
 * Each submitted W7 request covers whole x/z columns, and every result remains paired with its
 * original root and sample offset until all root frames can be transferred together.
 */
public final class VanillaCornerGraphPreparation {
    private static final int MAX_ROOTS = 64;
    private static final int MAX_REQUESTS = 256;
    private static final long MAX_AGGREGATE_SAMPLES = 8L * VanillaDensityGraphBatch.MAX_SAMPLES;
    private static final long MAX_AGGREGATE_INPUT_WORDS = 2L * VanillaDensityGraphBatch.MAX_INPUT_WORDS;

    private VanillaCornerGraphPreparation() {}

    /** The exact vanilla DensityFunction identity paired with its already-built immutable graph. */
    public record RootSnapshot(DensityFunction root, VanillaDensityGraph.Snapshot snapshot) {
        public RootSnapshot {
            Objects.requireNonNull(root, "root");
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    /**
     * Submits one immutable W7 wire. The implementation must not mutate the input array and must
     * release any internally-created numeric result if it cancels its returned future.
     */
    @FunctionalInterface
    public interface Submitter {
        CompletableFuture<WorldgenResult> submit(int[] immutableInput, Executor continuation);
    }

    /**
     * Prepares every root or none. Unsupported/failed/stale numeric work completes with null;
     * programmer errors and invalid graph/lattice arguments throw before any request is submitted.
     * Cancelling the returned future abandons adoption, cancels outstanding submitter futures, and
     * closes any result already received. If a submitter does not honor cancellation, its late
     * successful result is closed by the still-attached completion callback.
     */
    public static CompletableFuture<Prepared> prepareAll(
        final Object owner,
        final VanillaCornerGraphFrame.Lattice lattice,
        final List<RootSnapshot> roots,
        final Executor continuation,
        final Submitter submitter
    ) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(lattice, "lattice");
        Objects.requireNonNull(roots, "roots");
        Objects.requireNonNull(continuation, "continuation");
        Objects.requireNonNull(submitter, "submitter");
        if (roots.isEmpty() || roots.size() > MAX_ROOTS) {
            throw new IllegalArgumentException("Corner preparation root count is outside the supported bounds");
        }

        List<RootSnapshot> rootCopy = List.copyOf(roots);
        Set<DensityFunction> distinctRoots = Collections.newSetFromMap(new IdentityHashMap<>());
        for (RootSnapshot root : rootCopy) {
            if (!distinctRoots.add(root.root())) throw new IllegalArgumentException("Corner preparation has a duplicate root identity");
        }

        List<RequestPlan> plans = planRequests(rootCopy, lattice);
        Coordinator coordinator = new Coordinator(owner, lattice, rootCopy, plans);
        coordinator.result.whenComplete((prepared, failure) -> {
            if (coordinator.result.isCancelled()) coordinator.abandon();
        });
        coordinator.submit(continuation, submitter);
        return coordinator.result;
    }

    /** A complete all-roots owner lease. Root lookup is by identity, never equals(). */
    public static final class Prepared implements AutoCloseable {
        private final Object owner;
        private final VanillaCornerGraphFrame.Lattice lattice;
        private final List<RootFrame> roots;
        private final List<WorldgenResult> results;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Prepared(
            final Object owner,
            final VanillaCornerGraphFrame.Lattice lattice,
            final List<RootFrame> roots,
            final List<WorldgenResult> results
        ) {
            this.owner = owner;
            this.lattice = lattice;
            this.roots = List.copyOf(roots);
            this.results = List.copyOf(results);
        }

        public int rootCount() { return this.roots.size(); }

        public VanillaCornerGraphFrame.Lattice lattice() { return this.lattice; }

        /** Every root frame and numeric lease remains live in the same prepared aggregate. */
        public boolean usable() {
            if (this.closed.get()) return false;
            for (RootFrame root : this.roots) {
                if (!root.frame.usable()) return false;
            }
            return true;
        }

        /** Verifies the exact ordered owner/root/lattice set before a whole-root installer starts. */
        public boolean matchesRoots(
            final Object owner,
            final VanillaCornerGraphFrame.Lattice lattice,
            final List<DensityFunction> expectedRoots
        ) {
            if (!this.usable() || this.owner != owner || !this.lattice.equals(lattice)
                || expectedRoots == null || expectedRoots.size() != this.roots.size()) return false;
            for (int index = 0; index < this.roots.size(); index++) {
                if (expectedRoots.get(index) != this.roots.get(index).root) return false;
            }
            return true;
        }

        /** A refusal leaves output unchanged. Lattice equality compares all immutable coordinates and dimensions. */
        public boolean tryFillColumn(
            final Object owner,
            final DensityFunction root,
            final VanillaCornerGraphFrame.Lattice lattice,
            final int cellX,
            final int cellZ,
            final double[] output
        ) {
            Objects.requireNonNull(output, "output");
            if (!this.usable() || this.owner != owner || root == null || !this.lattice.equals(lattice)) return false;
            for (RootFrame entry : this.roots) {
                if (entry.root == root) {
                    return entry.frame.tryFillColumn(owner, root, cellX, cellZ, output);
                }
            }
            return false;
        }

        @Override
        public void close() {
            if (!this.closed.compareAndSet(false, true)) return;
            Throwable failure = closeResults(this.results, null);
            for (RootFrame root : this.roots) {
                try {
                    root.frame.close();
                } catch (RuntimeException | Error closeFailure) {
                    failure = addFailure(failure, closeFailure);
                }
            }
            rethrow(failure);
        }
    }

    private record RootFrame(DensityFunction root, VanillaCornerGraphFrame frame) {}
    private record RequestPlan(int requestIndex, int rootIndex, int firstSample, int sampleCount, int[] input) {}

    private static List<RequestPlan> planRequests(final List<RootSnapshot> roots, final VanillaCornerGraphFrame.Lattice lattice) {
        List<RequestPlan> result = new ArrayList<>();
        long aggregateSamples = 0L;
        long aggregateWords = 0L;
        int columnHeight = lattice.columnHeight();
        int sampleColumns = lattice.cellsXZ() + 1;
        int maximumColumnsPerRequest = Math.min(lattice.columnCount(), VanillaDensityGraphBatch.MAX_SAMPLES / columnHeight);
        if (maximumColumnsPerRequest < 1) {
            throw new IllegalArgumentException("one complete corner column exceeds W7 sample limits");
        }

        for (int rootIndex = 0; rootIndex < roots.size(); rootIndex++) {
            RootSnapshot root = roots.get(rootIndex);
            int firstColumn = 0;
            while (firstColumn < lattice.columnCount()) {
                int columns = Math.min(maximumColumnsPerRequest, lattice.columnCount() - firstColumn);
                int[] input = null;
                int acceptedColumns = columns;
                while (acceptedColumns > 0) {
                    List<VanillaDensityGraphBatch.Sample> samples = samplesForColumns(
                        lattice, firstColumn, acceptedColumns, sampleColumns, columnHeight
                    );
                    try {
                        input = VanillaDensityGraphBatch.input(root.snapshot(), samples);
                        break;
                    } catch (IllegalArgumentException resourceOrValidationFailure) {
                        if (acceptedColumns == 1) throw resourceOrValidationFailure;
                        acceptedColumns = Math.max(1, acceptedColumns / 2);
                    }
                }
                if (input == null) throw new IllegalStateException("W7 request planning produced no input");
                if (result.size() >= MAX_REQUESTS) {
                    throw new IllegalArgumentException("Corner preparation exceeds the aggregate request limit");
                }
                aggregateSamples = Math.addExact(aggregateSamples, input[1]);
                aggregateWords = Math.addExact(aggregateWords, input.length);
                if (aggregateSamples > MAX_AGGREGATE_SAMPLES || aggregateWords > MAX_AGGREGATE_INPUT_WORDS) {
                    throw new IllegalArgumentException("Corner preparation exceeds aggregate sample or wire limits");
                }
                int firstSample = Math.multiplyExact(firstColumn, columnHeight);
                result.add(new RequestPlan(result.size(), rootIndex, firstSample, input[1], input));
                firstColumn = Math.addExact(firstColumn, acceptedColumns);
            }
        }
        return List.copyOf(result);
    }

    private static List<VanillaDensityGraphBatch.Sample> samplesForColumns(
        final VanillaCornerGraphFrame.Lattice lattice,
        final int firstColumn,
        final int columnCount,
        final int sampleColumns,
        final int columnHeight
    ) {
        int sampleCount = Math.multiplyExact(columnCount, columnHeight);
        List<VanillaDensityGraphBatch.Sample> samples = new ArrayList<>(sampleCount);
        for (int column = firstColumn; column < firstColumn + columnCount; column++) {
            int xOffset = column / sampleColumns;
            int zOffset = column % sampleColumns;
            int blockX = Math.toIntExact(((long)lattice.firstCellX() + xOffset) * lattice.cellWidth());
            int blockZ = Math.toIntExact(((long)lattice.firstCellZ() + zOffset) * lattice.cellWidth());
            for (int yOffset = 0; yOffset < columnHeight; yOffset++) {
                int blockY = Math.toIntExact(((long)lattice.firstCellY() + yOffset) * lattice.cellHeight());
                samples.add(new VanillaDensityGraphBatch.Sample(blockX, blockY, blockZ));
            }
        }
        return samples;
    }

    private static final class Coordinator {
        private final Object owner;
        private final VanillaCornerGraphFrame.Lattice lattice;
        private final List<RootSnapshot> roots;
        private final List<RequestPlan> plans;
        private final WorldgenResult[] completedResults;
        private final List<CompletableFuture<WorldgenResult>> submitted = new ArrayList<>();
        private final CompletableFuture<Prepared> result = new CompletableFuture<>();
        private int completedCount;
        private boolean terminal;

        private Coordinator(
            final Object owner,
            final VanillaCornerGraphFrame.Lattice lattice,
            final List<RootSnapshot> roots,
            final List<RequestPlan> plans
        ) {
            this.owner = owner;
            this.lattice = lattice;
            this.roots = roots;
            this.plans = plans;
            this.completedResults = new WorldgenResult[plans.size()];
        }

        private void submit(final Executor continuation, final Submitter submitter) {
            for (RequestPlan plan : this.plans) {
                synchronized (this) {
                    if (this.terminal) return;
                }
                CompletableFuture<WorldgenResult> future;
                try {
                    future = submitter.submit(plan.input, continuation);
                } catch (RuntimeException | Error submissionFailure) {
                    this.fail(submissionFailure);
                    return;
                }
                if (future == null) {
                    this.fail(null);
                    return;
                }
                future.whenComplete((value, failure) -> this.finish(plan, value, failure));
                boolean cancelNow;
                synchronized (this) {
                    cancelNow = this.terminal;
                    if (!cancelNow) this.submitted.add(future);
                }
                if (cancelNow) future.cancel(false);
            }
        }

        private void finish(final RequestPlan plan, final WorldgenResult value, final Throwable failure) {
            List<WorldgenResult> close = null;
            WorldgenResult late = null;
            WorldgenResult[] ready = null;
            boolean failed = false;
            Throwable error = unwrap(failure);
            synchronized (this) {
                if (this.terminal) {
                    late = value;
                } else if (error != null || value == null) {
                    this.terminal = true;
                    close = this.presentResults();
                    if (value != null) close.add(value);
                    failed = true;
                } else if (value.wordCount() != Math.multiplyExact(plan.sampleCount, VanillaDensityGraphBatch.OUTPUT_WORDS_PER_SAMPLE)) {
                    this.terminal = true;
                    close = this.presentResults();
                    close.add(value);
                    failed = true;
                    error = new IllegalArgumentException("W7 result length does not match its paired corner request");
                } else {
                    this.completedResults[plan.requestIndex] = value;
                    this.completedCount++;
                    if (this.completedCount == this.plans.size()) {
                        this.terminal = true;
                        ready = this.completedResults.clone();
                    }
                }
            }
            if (late != null) closeResults(List.of(late), null);
            if (failed) {
                Throwable combined = closeResults(close, error);
                if (combined == null) this.result.complete(null); else this.result.completeExceptionally(combined);
                this.cancelSubmitted();
            }
            if (ready != null) this.publish(ready);
        }

        private void publish(final WorldgenResult[] values) {
            List<WorldgenResult> allResults = new ArrayList<>(values.length);
            for (WorldgenResult value : values) allResults.add(value);
            List<RootFrame> frames = new ArrayList<>(this.roots.size());
            try {
                Set<WorldgenResult> distinct = Collections.newSetFromMap(new IdentityHashMap<>());
                for (WorldgenResult value : allResults) {
                    if (value == null || !distinct.add(value)) {
                        Throwable failure = closeResults(allResults, null);
                        if (failure == null) this.result.complete(null); else this.result.completeExceptionally(failure);
                        return;
                    }
                }

                List<List<VanillaCornerGraphFrame.Part>> partsByRoot = new ArrayList<>(this.roots.size());
                for (int i = 0; i < this.roots.size(); i++) partsByRoot.add(new ArrayList<>());
                for (RequestPlan plan : this.plans) {
                    partsByRoot.get(plan.rootIndex).add(new VanillaCornerGraphFrame.Part(plan.firstSample, values[plan.requestIndex]));
                }

                for (int i = 0; i < this.roots.size(); i++) {
                    RootSnapshot root = this.roots.get(i);
                    VanillaCornerGraphFrame frame = VanillaCornerGraphFrame.take(this.owner, root.root(), this.lattice, partsByRoot.get(i));
                    if (frame == null) {
                        Throwable failure = closeResults(allResults, null);
                        failure = closeFrames(frames, failure);
                        if (failure == null) this.result.complete(null); else this.result.completeExceptionally(failure);
                        return;
                    }
                    frames.add(new RootFrame(root.root(), frame));
                }

                Prepared prepared;
                try {
                    prepared = new Prepared(this.owner, this.lattice, frames, allResults);
                } catch (RuntimeException | Error constructionFailure) {
                    Throwable failure = closeResults(allResults, constructionFailure);
                    failure = closeFrames(frames, failure);
                    this.result.completeExceptionally(failure);
                    return;
                }
                if (!this.result.complete(prepared)) prepared.close();
            } catch (RuntimeException | Error publicationFailure) {
                Throwable failure = closeResults(allResults, publicationFailure);
                failure = closeFrames(frames, failure);
                this.result.completeExceptionally(failure);
            }
        }

        private void fail(final Throwable failure) {
            List<WorldgenResult> close;
            synchronized (this) {
                if (this.terminal) return;
                this.terminal = true;
                close = this.presentResults();
            }
            Throwable combined = closeResults(close, unwrap(failure));
            if (combined == null) this.result.complete(null); else this.result.completeExceptionally(combined);
            this.cancelSubmitted();
        }

        private void abandon() {
            List<WorldgenResult> close;
            synchronized (this) {
                if (this.terminal) return;
                this.terminal = true;
                close = this.presentResults();
            }
            closeResults(close, null);
            this.cancelSubmitted();
        }

        private void cancelSubmitted() {
            List<CompletableFuture<WorldgenResult>> futures;
            synchronized (this) { futures = List.copyOf(this.submitted); }
            for (CompletableFuture<WorldgenResult> future : futures) future.cancel(false);
        }

        private List<WorldgenResult> presentResults() {
            List<WorldgenResult> result = new ArrayList<>(this.completedCount);
            for (WorldgenResult value : this.completedResults) if (value != null) result.add(value);
            return result;
        }
    }

    private static Throwable closeFrames(final List<RootFrame> frames, Throwable failure) {
        for (RootFrame root : frames) {
            try {
                root.frame.close();
            } catch (RuntimeException | Error closeFailure) {
                failure = addFailure(failure, closeFailure);
            }
        }
        return failure;
    }

    private static Throwable closeResults(final List<WorldgenResult> results, Throwable failure) {
        if (results == null) return failure;
        Set<WorldgenResult> distinct = Collections.newSetFromMap(new IdentityHashMap<>());
        for (WorldgenResult result : results) {
            if (result == null || !distinct.add(result)) continue;
            try {
                result.close();
            } catch (RuntimeException | Error closeFailure) {
                failure = addFailure(failure, closeFailure);
            }
        }
        return failure;
    }

    private static Throwable addFailure(final Throwable failure, final Throwable addition) {
        if (failure == null) return addition;
        if (failure != addition) failure.addSuppressed(addition);
        return failure;
    }

    private static Throwable unwrap(final Throwable failure) {
        return failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
    }

    private static void rethrow(final Throwable failure) {
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
    }
}
