package org.gpur.compute;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.bukkit.support.environment.VanillaFeature;
import org.gpur.terrain.VanillaCornerGraphFrame;
import org.gpur.terrain.VanillaCornerGraphPreparation;
import org.gpur.terrain.VanillaDensityGraph;
import org.junit.jupiter.api.Test;

/** Cancellation must reach numerical work even though preparation combines dependent futures. */
@VanillaFeature
class NoiseBasedPreparationLeaseLifecycleTest {
    @Test
    void closingEveryPreparationLeaseCancelsItsSourceFuture() throws Exception {
        for (String name : List.of("GpurAquiferRankingLease", "GpurStaticNoiseLease", "GpurCornerGraphLease")) {
            Object lease = lease(name);
            CompletableFuture<?> source = new CompletableFuture<>();
            CompletableFuture<?> dependent = track(lease, source);
            close(lease);
            assertTrue(source.isCancelled(), name + " must cancel the source, not just its dependent combination");
            assertNull(dependent.join());
            close(lease);
        }
    }

    @Test
    void lateCornerAggregateIsReleasedWhenSourceCannotHonorCancellation() throws Exception {
        Object lease = lease("GpurCornerGraphLease");
        NonCancellingFuture source = new NonCancellingFuture();
        CompletableFuture<?> dependent = track(lease, source);
        close(lease);
        assertEquals(1, source.cancelRequests.get());
        AtomicLong releases = new AtomicLong();
        var prepared = prepared(releases);
        assertTrue(prepared.usable());
        assertTrue(source.complete(prepared));
        assertSame(prepared, dependent.join());
        assertFalse(prepared.usable(), "released owner must close even a numerically valid late result");
        assertEquals(1, releases.get());
        close(lease);
        assertEquals(1, releases.get());
    }

    @Test
    void completedCornerAggregateIsClosedOnceAndFailedSourcesFallBack() throws Exception {
        Object lease = lease("GpurCornerGraphLease");
        AtomicLong releases = new AtomicLong();
        var prepared = prepared(releases);
        assertSame(prepared, track(lease, CompletableFuture.completedFuture(prepared)).join());
        close(lease);
        close(lease);
        assertFalse(prepared.usable());
        assertEquals(1, releases.get());
        for (String name : List.of("GpurAquiferRankingLease", "GpurStaticNoiseLease", "GpurCornerGraphLease")) {
            Object failedLease = lease(name);
            assertNull(track(failedLease, CompletableFuture.failedFuture(new IllegalStateException("test backend failure"))).join());
            close(failedLease);
        }
    }

    private static VanillaCornerGraphPreparation.Prepared prepared(AtomicLong releases) {
        var root = DensityFunctions.constant(0.0);
        var lattice = new VanillaCornerGraphFrame.Lattice(0, 0, 0, 4, 8, 1, 1);
        return VanillaCornerGraphPreparation.prepareAll(new Object(), lattice,
            List.of(new VanillaCornerGraphPreparation.RootSnapshot(root, VanillaDensityGraph.snapshot(root))), Runnable::run,
            (input, executor) -> CompletableFuture.completedFuture(new WorldgenResult(new int[input[1] * 2],
                () -> true, releases::incrementAndGet, ignored -> {}))).join();
    }

    private static Object lease(String name) throws Exception {
        Class<?> type = Class.forName(NoiseBasedChunkGenerator.class.getName() + "$" + name);
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static CompletableFuture<?> track(Object lease, CompletableFuture<?> source) throws Exception {
        Method method = lease.getClass().getDeclaredMethod("track", CompletableFuture.class);
        method.setAccessible(true);
        return (CompletableFuture<?>)method.invoke(lease, source);
    }

    private static void close(Object lease) throws Exception {
        boolean aquifer = lease.getClass().getSimpleName().equals("GpurAquiferRankingLease");
        Method method = aquifer ? lease.getClass().getDeclaredMethod("close", Aquifer.class)
            : lease.getClass().getDeclaredMethod("close");
        method.setAccessible(true);
        if (aquifer) method.invoke(lease, new Object[] {null}); else method.invoke(lease);
    }

    private static final class NonCancellingFuture extends CompletableFuture<VanillaCornerGraphPreparation.Prepared> {
        private final AtomicLong cancelRequests = new AtomicLong();
        @Override public boolean cancel(boolean mayInterruptIfRunning) { this.cancelRequests.incrementAndGet(); return false; }
    }
}
