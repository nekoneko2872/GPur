package org.gpur.compute;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.gpur.terrain.VanillaCornerGraphFrame.Lattice;
import org.gpur.terrain.VanillaCornerGraphPreparation;
import org.gpur.terrain.VanillaCornerGraphPreparation.Prepared;
import org.gpur.terrain.VanillaCornerGraphPreparation.RootSnapshot;
import org.gpur.terrain.VanillaDensityGraph;
import org.gpur.terrain.VanillaDensityGraphBatch;
import org.junit.jupiter.api.Test;

/** Source-level ownership contract tests for the off-path all-roots coordinator. */
class VanillaCornerGraphPreparationTest {
    private static final Executor DIRECT = Runnable::run;

    @Test
    void pairsOutOfOrderCompletionsWithExactRootOrderAndLattice() {
        Object owner = new Object();
        Lattice lattice = smallLattice();
        DensityFunction first = DensityFunctions.constant(1.25);
        DensityFunction second = DensityFunctions.constant(-2.5);
        List<RootSnapshot> roots = List.of(snapshot(first), snapshot(second));
        List<CompletableFuture<WorldgenResult>> pending = new ArrayList<>();
        List<int[]> wires = new ArrayList<>();

        CompletableFuture<Prepared> future = VanillaCornerGraphPreparation.prepareAll(owner, lattice, roots, DIRECT, (input, executor) -> {
            wires.add(input);
            CompletableFuture<WorldgenResult> child = new CompletableFuture<>();
            pending.add(child);
            return child;
        });

        assertEquals(2, pending.size());
        Owned secondResult = owned(VanillaDensityGraphBatch.reference(wires.get(1)));
        Owned firstResult = owned(VanillaDensityGraphBatch.reference(wires.get(0)));
        assertTrue(pending.get(1).complete(secondResult.result));
        assertFalse(future.isDone(), "one root cannot publish an incomplete aggregate");
        assertTrue(pending.get(0).complete(firstResult.result));

        Prepared prepared = future.join();
        assertNotNull(prepared);
        assertTrue(prepared.usable());
        assertEquals(2, prepared.rootCount());
        assertTrue(prepared.matchesRoots(owner, lattice, List.of(first, second)));
        assertFalse(prepared.matchesRoots(new Object(), lattice, List.of(first, second)));
        assertFalse(prepared.matchesRoots(owner, new Lattice(-3, -8, 7, 4, 8, 1, 2), List.of(first, second)));
        assertFalse(prepared.matchesRoots(owner, lattice, List.of(second, first)));
        assertFalse(prepared.matchesRoots(owner, lattice, List.of(first)));

        for (int rootIndex = 0; rootIndex < roots.size(); rootIndex++) {
            DensityFunction root = roots.get(rootIndex).root();
            double expected = rootIndex == 0 ? 1.25 : -2.5;
            for (int x = 0; x <= lattice.cellsXZ(); x++) {
                for (int z = 0; z <= lattice.cellsXZ(); z++) {
                    double[] column = new double[lattice.columnHeight()];
                    assertTrue(prepared.tryFillColumn(owner, root, lattice, lattice.firstCellX() + x,
                        lattice.firstCellZ() + z, column));
                    for (double value : column) assertRawEquals(expected, value);
                }
            }
        }
        prepared.close();
        prepared.close();
        assertFalse(prepared.usable());
        assertFalse(prepared.matchesRoots(owner, lattice, List.of(first, second)));
        assertEquals(1, firstResult.releases.get());
        assertEquals(1, secondResult.releases.get());
    }

    @Test
    void staleAnyRootInvalidatesWholePreparedAggregateAndRefusesWithoutMutation() {
        Object owner = new Object();
        Lattice lattice = smallLattice();
        DensityFunction first = DensityFunctions.constant(3.0);
        DensityFunction second = DensityFunctions.constant(4.0);
        List<RootSnapshot> roots = List.of(snapshot(first), snapshot(second));
        List<CompletableFuture<WorldgenResult>> pending = new ArrayList<>();
        List<int[]> wires = new ArrayList<>();
        CompletableFuture<Prepared> future = VanillaCornerGraphPreparation.prepareAll(owner, lattice, roots, DIRECT, (input, executor) -> {
            wires.add(input);
            CompletableFuture<WorldgenResult> child = new CompletableFuture<>();
            pending.add(child);
            return child;
        });
        Owned firstResult = owned(VanillaDensityGraphBatch.reference(wires.get(0)));
        Owned secondResult = owned(VanillaDensityGraphBatch.reference(wires.get(1)));
        pending.get(0).complete(firstResult.result);
        pending.get(1).complete(secondResult.result);
        Prepared prepared = future.join();

        secondResult.valid.set(false);
        assertFalse(prepared.usable());
        assertFalse(prepared.matchesRoots(owner, lattice, List.of(first, second)));
        double[] output = {91.0, -0.0, 93.0};
        long[] before = rawBits(output);
        assertFalse(prepared.tryFillColumn(owner, first, lattice, lattice.firstCellX(), lattice.firstCellZ(), output));
        assertArrayEquals(before, rawBits(output));

        prepared.close();
        assertEquals(1, firstResult.releases.get());
        assertEquals(1, secondResult.releases.get());
    }

    @Test
    void shortOrNonfiniteResultNeverPublishesAndEveryNumericLeaseIsClosed() {
        for (boolean shortResult : new boolean[] {true, false}) {
            Object owner = new Object();
            Lattice lattice = smallLattice();
            DensityFunction root = DensityFunctions.constant(7.0);
            RootSnapshot rootSnapshot = snapshot(root);
            int[] wire = VanillaDensityGraphBatch.input(rootSnapshot.snapshot(), samples(lattice));
            AtomicInteger releases = new AtomicInteger();
            int[] words;
            if (shortResult) {
                words = new int[wire[1] * VanillaDensityGraphBatch.OUTPUT_WORDS_PER_SAMPLE - 2];
            } else {
                words = VanillaDensityGraphBatch.reference(wire);
                words[0] = 0;
                words[1] = 0x7ff8_0000;
            }
            WorldgenResult numeric = new WorldgenResult(words, () -> true, releases::incrementAndGet, ignored -> {});
            CompletableFuture<Prepared> future = VanillaCornerGraphPreparation.prepareAll(owner, lattice,
                List.of(rootSnapshot), DIRECT, (input, executor) -> CompletableFuture.completedFuture(numeric));

            assertNoPrepared(future);
            assertEquals(1, releases.get(), shortResult ? "short output" : "nonfinite decoded value");
        }
    }

    @Test
    void failureClosesPriorResultsAndIgnoresThenClosesLateSuccessfulCompletion() {
        Object owner = new Object();
        Lattice lattice = smallLattice();
        DensityFunction first = DensityFunctions.constant(1.0);
        DensityFunction second = DensityFunctions.constant(2.0);
        DensityFunction third = DensityFunctions.constant(3.0);
        List<int[]> wires = new ArrayList<>();
        List<CompletableFuture<WorldgenResult>> pending = new ArrayList<>();
        CompletableFuture<Prepared> aggregate = VanillaCornerGraphPreparation.prepareAll(owner, lattice,
            List.of(snapshot(first), snapshot(second), snapshot(third)), DIRECT, (input, executor) -> {
                wires.add(input);
                CompletableFuture<WorldgenResult> child = new IgnoresCancellationFuture<>();
                pending.add(child);
                return child;
            });
        assertEquals(3, pending.size());
        Owned completed = owned(VanillaDensityGraphBatch.reference(wires.get(0)));
        Owned late = owned(VanillaDensityGraphBatch.reference(wires.get(2)));
        pending.get(0).complete(completed.result);
        pending.get(1).completeExceptionally(new IllegalStateException("simulated batch failure"));

        assertNoPrepared(aggregate);
        assertEquals(1, completed.releases.get());
        assertEquals(0, late.releases.get());
        assertTrue(pending.get(2).complete(late.result), "simulated submitter deliberately ignores cancellation");
        assertEquals(1, late.releases.get(), "late result is closed without being paired to a different root");
    }

    @Test
    void cancellingAggregateClosesReceivedAndLaterResults() {
        Object owner = new Object();
        Lattice lattice = smallLattice();
        DensityFunction first = DensityFunctions.constant(5.0);
        DensityFunction second = DensityFunctions.constant(6.0);
        List<int[]> wires = new ArrayList<>();
        List<CompletableFuture<WorldgenResult>> pending = new ArrayList<>();
        CompletableFuture<Prepared> aggregate = VanillaCornerGraphPreparation.prepareAll(owner, lattice,
            List.of(snapshot(first), snapshot(second)), DIRECT, (input, executor) -> {
                wires.add(input);
                CompletableFuture<WorldgenResult> child = new IgnoresCancellationFuture<>();
                pending.add(child);
                return child;
            });
        Owned completed = owned(VanillaDensityGraphBatch.reference(wires.get(0)));
        Owned late = owned(VanillaDensityGraphBatch.reference(wires.get(1)));
        pending.get(0).complete(completed.result);

        assertTrue(aggregate.cancel(false));
        assertEquals(1, completed.releases.get());
        assertTrue(pending.get(1).complete(late.result));
        assertEquals(1, late.releases.get());
    }

    @Test
    void partitionsByValidatedWorkAndRejectsAnOverLimitSingleColumnBeforeSubmitting() {
        Lattice lattice = new Lattice(0, 0, 0, 4, 1, 1, 1_023);
        DensityFunction root = repeatedAdd(DensityFunctions.constant(0.125), 12);
        VanillaDensityGraph.Snapshot snapshot = VanillaDensityGraph.snapshot(root);
        List<Integer> sampleCounts = new ArrayList<>();
        CompletableFuture<Prepared> future = VanillaCornerGraphPreparation.prepareAll(new Object(), lattice,
            List.of(new RootSnapshot(root, snapshot)), DIRECT, (input, executor) -> {
                sampleCounts.add(input[1]);
                return CompletableFuture.completedFuture(resultFor(input));
            });
        Prepared prepared = future.join();
        assertNotNull(prepared);
        assertEquals(List.of(1_024, 1_024, 1_024, 1_024), sampleCounts,
            "two adjacent columns exceed batch work, so each complete column gets one request");
        prepared.close();

        DensityFunction tooExpensive = repeatedAdd(DensityFunctions.constant(0.125), 13);
        VanillaDensityGraph.Snapshot expensiveSnapshot = VanillaDensityGraph.snapshot(tooExpensive);
        DensityFunction earlierRoot = DensityFunctions.constant(2.0);
        AtomicInteger submissions = new AtomicInteger();
        assertThrows(IllegalArgumentException.class, () -> VanillaCornerGraphPreparation.prepareAll(new Object(), lattice,
            List.of(snapshot(earlierRoot), new RootSnapshot(tooExpensive, expensiveSnapshot)), DIRECT, (input, executor) -> {
                submissions.incrementAndGet();
                return CompletableFuture.completedFuture(resultFor(input));
            }));
        assertEquals(0, submissions.get(), "a later root's over-limit column prevents every request from being submitted");
    }

    @Test
    void duplicateRootIdentityIsRejectedBeforeAnyRequestIsSubmitted() {
        DensityFunction root = DensityFunctions.constant(1.0);
        AtomicInteger submissions = new AtomicInteger();
        assertThrows(IllegalArgumentException.class, () -> VanillaCornerGraphPreparation.prepareAll(new Object(), smallLattice(),
            List.of(snapshot(root), snapshot(root)), DIRECT, (input, executor) -> {
                submissions.incrementAndGet();
                return CompletableFuture.completedFuture(resultFor(input));
            }));
        assertEquals(0, submissions.get());
    }

    private static RootSnapshot snapshot(final DensityFunction root) {
        return new RootSnapshot(root, VanillaDensityGraph.snapshot(root));
    }

    private static Lattice smallLattice() {
        return new Lattice(-4, -8, 7, 4, 8, 1, 2);
    }

    private static List<VanillaDensityGraphBatch.Sample> samples(final Lattice lattice) {
        List<VanillaDensityGraphBatch.Sample> result = new ArrayList<>(lattice.sampleCount());
        for (int x = 0; x <= lattice.cellsXZ(); x++) {
            for (int z = 0; z <= lattice.cellsXZ(); z++) {
                for (int y = 0; y <= lattice.cellsY(); y++) {
                    result.add(new VanillaDensityGraphBatch.Sample(
                        (lattice.firstCellX() + x) * lattice.cellWidth(),
                        (lattice.firstCellY() + y) * lattice.cellHeight(),
                        (lattice.firstCellZ() + z) * lattice.cellWidth()
                    ));
                }
            }
        }
        return result;
    }

    private static DensityFunction repeatedAdd(DensityFunction source, final int levels) {
        DensityFunction result = source;
        for (int index = 0; index < levels; index++) result = DensityFunctions.add(result, result);
        return result;
    }

    private static WorldgenResult resultFor(final int[] input) {
        return new WorldgenResult(VanillaDensityGraphBatch.reference(input), () -> true, () -> {}, ignored -> {});
    }

    private static Owned owned(final int[] words) {
        AtomicBoolean valid = new AtomicBoolean(true);
        AtomicInteger releases = new AtomicInteger();
        return new Owned(new WorldgenResult(words, valid::get, releases::incrementAndGet, ignored -> {}), valid, releases);
    }

    private static void assertNoPrepared(final CompletableFuture<Prepared> future) {
        try {
            assertNull(future.join(), "failed or incomplete roots must never publish a partial aggregate");
        } catch (CompletionException expected) {
            assertNotNull(expected.getCause());
        }
    }

    private static long[] rawBits(final double[] values) {
        return Arrays.stream(values).mapToLong(Double::doubleToRawLongBits).toArray();
    }

    private static void assertRawEquals(final double expected, final double actual) {
        assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual));
    }

    private record Owned(WorldgenResult result, AtomicBoolean valid, AtomicInteger releases) {}

    private static final class IgnoresCancellationFuture<T> extends CompletableFuture<T> {
        @Override
        public boolean cancel(final boolean mayInterruptIfRunning) {
            return false;
        }
    }
}
