package org.gpur.compute;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.minecraft.core.Holder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.gpur.GPurConfig;
import org.gpur.terrain.VanillaNoiseCache;
import org.gpur.terrain.VanillaNoiseContinuation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VanillaNoiseIntegrationTest {
    private final ConfigSnapshot previous = ConfigSnapshot.capture();

    @BeforeEach
    void enableStrictNoiseBatching() {
        GPurConfig.gpuAccelerationEnabled = true;
        GPurConfig.terrainGpuEnabled = true;
        GPurConfig.gpuAsyncSubmit = true;
        GPurConfig.gpuBufferBudgetMiB = 16;
        GPurConfig.vanillaTerrainEnabled = true;
        GPurConfig.vanillaTerrainMode = VanillaVerificationPolicy.Mode.STRICT;
        GPurConfig.vanillaTerrainMinValues = 1;
        GPurConfig.vanillaTerrainMaxSlabValues = 65_536;
        GPurConfig.vanillaNoiseGpuEnabled = true;
        GPurConfig.vanillaAquiferGpuEnabled = true;
    }

    @AfterEach
    void restoreConfig() {
        this.previous.restore();
    }

    @Test
    void cancellationClassifierUnwrapsOnlyFutureWrappersAndFailsClosed() {
        assertTrue(VanillaNoiseContinuation.isCancellation(new CancellationException("cancelled")));
        assertTrue(VanillaNoiseContinuation.isCancellation(
            new CompletionException(new ExecutionException(new CancellationException("cancelled")))
        ));
        assertTrue(VanillaNoiseContinuation.isCancellation(
            new ExecutionException(new CompletionException(new CancellationException("cancelled")))
        ));

        assertFalse(VanillaNoiseContinuation.isCancellation(new IllegalStateException("real failure")));
        assertFalse(VanillaNoiseContinuation.isCancellation(new RuntimeException(new IllegalStateException("nested real failure"))));
        assertFalse(VanillaNoiseContinuation.isCancellation(
            new CompletionException(new RuntimeException(new CancellationException("wrapped by a real failure")))
        ));

        Throwable tooDeep = new CancellationException("too deep");
        for (int i = 0; i < 32; i++) tooDeep = new CompletionException(tooDeep);
        assertFalse(VanillaNoiseContinuation.isCancellation(tooDeep), "wrapper-depth overflow is rejected fail-closed");
    }

    @Test
    void publicSeededSnapshotsMatchDirectVanillaNoiseObjects() {
        ImprovedNoise improved = new ImprovedNoise(RandomSource.create(0x17e11L));
        PerlinNoise perlin = PerlinNoise.create(RandomSource.create(0x51a9L), -3, 0.5, 1.0, 0.25, 0.0, 0.75);
        NormalNoise normal = NormalNoise.create(RandomSource.create(0x6f2aL), -2, 0.75, 1.0, 0.375, 0.5);
        BlendedNoise blended = new BlendedNoise(RandomSource.create(0x2cbbL), 1.0, 1.0, 8.55515, 4.277575, 2.0);

        VanillaNoiseBatch.NoiseSample[] samples = {
            new VanillaNoiseBatch.NoiseSample(improved.gpurSnapshot(), -17.25, -0.125, 31.875, 0.0, 0.0),
            new VanillaNoiseBatch.NoiseSample(perlin.gpurSnapshot(), 17.375, 0.6875, -88.125, 0.125, 0.25),
            new VanillaNoiseBatch.NoiseSample(normal.gpurSnapshot(), -843.625, -72.125, 918.4375, 0.0, 0.0),
            new VanillaNoiseBatch.NoiseSample(blended.gpurSnapshot(), -187.0, -53.0, 91.0, 0.0, 0.0)
        };
        DensityFunction.FunctionContext blendedContext = context(-187, -53, 91);
        double[] expected = {
            improved.noise(-17.25, -0.125, 31.875, 0.0, 0.0),
            perlin.getValue(17.375, 0.6875, -88.125, 0.125, 0.25),
            normal.getValue(-843.625, -72.125, 918.4375),
            blended.compute(blendedContext)
        };

        int[] output = VanillaNoiseBatch.reference(VanillaNoiseBatch.input(samples));
        for (int i = 0; i < expected.length; i++) {
            assertRawEquals(expected[i], VanillaNoiseBatch.outputValue(output, i), "seeded snapshot profile " + i);
        }
    }

    @Test
    void collectorSnapshotsKnownInitializedNoiseAndLeavesUnknownNodesUntouched() throws Exception {
        NormalNoise noise = NormalNoise.create(RandomSource.create(0x291dL), -2, 0.5, 1.0, 0.25, 0.0);
        Holder<NormalNoise.NoiseParameters> holder = Holder.direct(noise.parameters());
        DensityFunction root = DensityFunctions.noise(holder, 0.5, 0.25).mapAll(new DensityFunction.Visitor() {
            @Override public DensityFunction apply(DensityFunction input) { return input; }
            @Override public DensityFunction.NoiseHolder visitNoise(DensityFunction.NoiseHolder input) {
                return new DensityFunction.NoiseHolder(input.noiseData(), noise);
            }
        });
        int[] coordinates = {-17, -9, 33, 0, 64, 0, 253, 255, -241, 30_000_000, -32, -30_000_000};
        double[] expected = new double[coordinates.length / 3];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = root.compute(context(coordinates[i * 3], coordinates[i * 3 + 1], coordinates[i * 3 + 2]));
        }

        AtomicReference<int[]> captured = new AtomicReference<>();
        VulkanDevice device = backend(captured);
        try (ComputeService service = new ComputeService(Logger.getLogger("noise-integration"), List.of(device))) {
            VanillaNoiseCache.Builder builder = new VanillaNoiseCache.Builder();
            DensityFunctions.gpurCollectStaticNoise(List.of(root), coordinates, builder);
            try (VanillaNoiseCache cache = builder.prepare(service, Runnable::run).join()) {
                assertNotNull(cache);
                assertEquals(VanillaNoiseBatch.WORKLOAD, captured.get()[0]);
                assertEquals(expected.length, captured.get()[1], "one initialized static sampler request per corner");
                try (VanillaNoiseCache.Scope ignored = cache.enter()) {
                    for (int i = 0; i < expected.length; i++) {
                        int offset = i * 3;
                        double actual = root.compute(context(coordinates[offset], coordinates[offset + 1], coordinates[offset + 2]));
                        assertRawEquals(expected[i], actual, "collected corner " + i);
                    }
                }
                assertEquals(expected.length, status(service).consumedValues());
            }
        }

        UnknownDensityFunction unknown = new UnknownDensityFunction();
        DensityFunction graph = DensityFunctions.add(unknown, DensityFunctions.constant(0.5));
        DensityFunctions.gpurCollectStaticNoise(List.of(graph), new int[] {-16, 0, 16}, new VanillaNoiseCache.Builder());
        assertEquals(0, unknown.computeCalls.get(), "collecting a graph must never evaluate an unknown node");
        assertEquals(0, unknown.mapChildrenCalls.get(), "unknown nodes are leaves to the static collector");
    }

    @Test
    void cacheKeysUseSamplerIdentityAndPreserveSignedZeroCoordinates() throws Exception {
        NormalNoise noise = NormalNoise.create(RandomSource.create(0x1101L), -1, 1.0, 0.5, 0.25);
        VanillaNoiseBatch.NormalNoiseProfile profile = noise.gpurSnapshot();
        EqualSampler first = new EqualSampler();
        EqualSampler equalButDistinct = new EqualSampler();
        assertEquals(first, equalButDistinct);
        assertNotSame(first, equalButDistinct);

        AtomicReference<int[]> captured = new AtomicReference<>();
        VulkanDevice device = backend(captured);
        try (ComputeService service = new ComputeService(Logger.getLogger("noise-integration"), List.of(device))) {
            VanillaNoiseCache.Builder builder = new VanillaNoiseCache.Builder();
            builder.add(first, profile, +0.0, -0.0, 0.25);
            builder.add(first, profile, +0.0, -0.0, 0.25); // exact duplicate collapses
            builder.add(first, profile, -0.0, -0.0, 0.25); // raw signed-zero distinction remains
            builder.add(equalButDistinct, profile, +0.0, -0.0, 0.25); // equal() cannot merge sampler identities

            try (VanillaNoiseCache cache = builder.prepare(service, Runnable::run).join()) {
                assertNotNull(cache);
                int[] input = captured.get();
                assertEquals(3, input[1], "duplicate keys collapse while signed zero and sampler identity remain distinct");
                int[] output = VanillaNoiseBatch.reference(input);
                double positiveZero = VanillaNoiseBatch.outputValue(output, 0);
                double negativeZero = VanillaNoiseBatch.outputValue(output, 1);
                double secondSampler = VanillaNoiseBatch.outputValue(output, 2);
                try (VanillaNoiseCache.Scope ignored = cache.enter()) {
                    assertRawEquals(positiveZero, VanillaNoiseCache.lookup(first, +0.0, -0.0, 0.25), "positive zero key");
                    assertRawEquals(negativeZero, VanillaNoiseCache.lookup(first, -0.0, -0.0, 0.25), "negative zero key");
                    assertRawEquals(secondSampler, VanillaNoiseCache.lookup(equalButDistinct, +0.0, -0.0, 0.25), "identity key");
                    assertTrue(Double.isNaN(VanillaNoiseCache.lookup(new EqualSampler(), +0.0, -0.0, 0.25)));
                }
                assertEquals(3, status(service).consumedValues(), "misses do not increment the CPU-consumption counter");
            }
        }
    }

    @Test
    void nestedNoiseAndContinuationScopesRestoreTheirPreviousThreadLocalValues() throws Exception {
        NormalNoise noise = NormalNoise.create(RandomSource.create(0x8873L), -1, 1.0, 0.5, 0.25);
        VanillaNoiseBatch.NormalNoiseProfile profile = noise.gpurSnapshot();
        Object samplerA = new Object();
        Object samplerB = new Object();
        double[] a = {1.25, 2.5, 3.75};
        double[] b = {-2.25, 4.5, -6.75};
        double expectedA = noise.getValue(a[0], a[1], a[2]);
        double expectedB = noise.getValue(b[0], b[1], b[2]);
        VulkanDevice device = backend(null);

        try (ComputeService service = new ComputeService(Logger.getLogger("noise-integration"), List.of(device))) {
            VanillaNoiseCache.Builder builderA = new VanillaNoiseCache.Builder();
            builderA.add(samplerA, profile, a[0], a[1], a[2]);
            VanillaNoiseCache.Builder builderB = new VanillaNoiseCache.Builder();
            builderB.add(samplerB, profile, b[0], b[1], b[2]);
            try (VanillaNoiseCache cacheA = builderA.prepare(service, Runnable::run).join();
                 VanillaNoiseCache cacheB = builderB.prepare(service, Runnable::run).join()) {
                assertTrue(Double.isNaN(VanillaNoiseCache.lookup(samplerA, a[0], a[1], a[2])), "there is no current cache before entering a scope");
                try (VanillaNoiseCache.Scope outer = cacheA.enter()) {
                    assertRawEquals(expectedA, VanillaNoiseCache.lookup(samplerA, a[0], a[1], a[2]), "outer scope");
                    try (VanillaNoiseCache.Scope inner = cacheB.enter()) {
                        assertTrue(Double.isNaN(VanillaNoiseCache.lookup(samplerA, a[0], a[1], a[2])));
                        assertRawEquals(expectedB, VanillaNoiseCache.lookup(samplerB, b[0], b[1], b[2]), "inner scope");
                    }
                    assertRawEquals(expectedA, VanillaNoiseCache.lookup(samplerA, a[0], a[1], a[2]), "outer scope restored after inner close");
                }
                assertTrue(Double.isNaN(VanillaNoiseCache.lookup(samplerA, a[0], a[1], a[2])), "closing the outer scope clears the current cache");
                assertEquals(3, status(service).consumedValues());
            }

            ComputeService outerService = mock(ComputeService.class);
            ComputeService innerService = mock(ComputeService.class);
            when(outerService.tryAcquireVanillaContinuation()).thenReturn(true);
            when(innerService.tryAcquireVanillaContinuation()).thenReturn(true);
            Executor outerExecutor = Runnable::run;
            Executor innerExecutor = Runnable::run;
            assertNull(VanillaNoiseContinuation.current());
            try (VanillaNoiseContinuation outer = VanillaNoiseContinuation.enter(outerService, outerExecutor)) {
                assertSame(outer, VanillaNoiseContinuation.current());
                try (VanillaNoiseContinuation inner = VanillaNoiseContinuation.enter(innerService, innerExecutor)) {
                    assertSame(inner, VanillaNoiseContinuation.current());
                    assertSame(innerService, VanillaNoiseContinuation.current().service());
                }
                assertSame(outer, VanillaNoiseContinuation.current(), "closing an inner continuation restores its parent");
                assertSame(outerExecutor, VanillaNoiseContinuation.current().executor());
            }
            assertNull(VanillaNoiseContinuation.current(), "closing the outer continuation clears the ThreadLocal");

            ComputeService closedService = mock(ComputeService.class);
            Executor rejectedExecutor = Runnable::run;
            assertNull(VanillaNoiseContinuation.enter(closedService, rejectedExecutor), "closed services reject new continuation scopes");
            verify(closedService, never()).releaseVanillaContinuation();
            assertNull(VanillaNoiseContinuation.current(), "rejected entry leaves the current ThreadLocal untouched");
        }
    }

    private static VulkanDevice backend(AtomicReference<int[]> capturedInput) {
        VulkanDevice device = mock(VulkanDevice.class);
        AtomicBoolean available = new AtomicBoolean(true);
        when(device.available()).thenAnswer(ignored -> available.get());
        when(device.uuid()).thenReturn("simulated-noise-integration");
        when(device.name()).thenReturn("Simulated noise GPU");
        doAnswer(ignored -> { available.set(false); return null; }).when(device).disable();
        when(device.computeAsync(any(), anyInt(), anyInt())).thenAnswer(invocation -> {
            int[] input = invocation.getArgument(0);
            if (capturedInput != null) capturedInput.set(input.clone());
            return CompletableFuture.completedFuture(ExactCompute.reference(input));
        });
        return device;
    }

    private static ComputeService.WorldgenDeviceStatus status(ComputeService service) {
        return service.worldgenDevices().stream()
            .filter(status -> status.workload() == VanillaNoiseBatch.WORKLOAD)
            .findFirst()
            .orElseThrow();
    }

    private static DensityFunction.FunctionContext context(int x, int y, int z) {
        return new DensityFunction.SinglePointContext(x, y, z);
    }

    private static void assertRawEquals(double expected, double actual, String message) {
        assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual), message);
    }

    private static final class UnknownDensityFunction implements DensityFunction {
        private final AtomicInteger computeCalls = new AtomicInteger();
        private final AtomicInteger mapChildrenCalls = new AtomicInteger();

        @Override public double compute(DensityFunction.FunctionContext context) {
            this.computeCalls.incrementAndGet();
            throw new AssertionError("The static collector evaluated an unknown density node");
        }
        @Override public void fillArray(double[] output, DensityFunction.ContextProvider contextProvider) {
            throw new AssertionError("The static collector filled an unknown density node");
        }
        @Override public DensityFunction mapChildren(DensityFunction.Visitor visitor) {
            this.mapChildrenCalls.incrementAndGet();
            throw new AssertionError("The static collector traversed an unknown density node");
        }
        @Override public double minValue() { return -1.0; }
        @Override public double maxValue() { return 1.0; }
        @Override public KeyDispatchDataCodec<? extends DensityFunction> codec() { return DensityFunctions.zero().codec(); }
    }

    private static final class EqualSampler {
        @Override public boolean equals(Object other) { return other instanceof EqualSampler; }
        @Override public int hashCode() { return 7; }
    }

    private record ConfigSnapshot(
        boolean gpuAccelerationEnabled,
        boolean terrainGpuEnabled,
        boolean gpuAsyncSubmit,
        int gpuBufferBudgetMiB,
        boolean vanillaTerrainEnabled,
        VanillaVerificationPolicy.Mode vanillaTerrainMode,
        int vanillaTerrainMinValues,
        int vanillaTerrainMaxSlabValues,
        boolean vanillaNoiseGpuEnabled,
        boolean vanillaAquiferGpuEnabled
    ) {
        static ConfigSnapshot capture() {
            return new ConfigSnapshot(
                GPurConfig.gpuAccelerationEnabled,
                GPurConfig.terrainGpuEnabled,
                GPurConfig.gpuAsyncSubmit,
                GPurConfig.gpuBufferBudgetMiB,
                GPurConfig.vanillaTerrainEnabled,
                GPurConfig.vanillaTerrainMode,
                GPurConfig.vanillaTerrainMinValues,
                GPurConfig.vanillaTerrainMaxSlabValues,
                GPurConfig.vanillaNoiseGpuEnabled,
                GPurConfig.vanillaAquiferGpuEnabled
            );
        }

        void restore() {
            GPurConfig.gpuAccelerationEnabled = this.gpuAccelerationEnabled;
            GPurConfig.terrainGpuEnabled = this.terrainGpuEnabled;
            GPurConfig.gpuAsyncSubmit = this.gpuAsyncSubmit;
            GPurConfig.gpuBufferBudgetMiB = this.gpuBufferBudgetMiB;
            GPurConfig.vanillaTerrainEnabled = this.vanillaTerrainEnabled;
            GPurConfig.vanillaTerrainMode = this.vanillaTerrainMode;
            GPurConfig.vanillaTerrainMinValues = this.vanillaTerrainMinValues;
            GPurConfig.vanillaTerrainMaxSlabValues = this.vanillaTerrainMaxSlabValues;
            GPurConfig.vanillaNoiseGpuEnabled = this.vanillaNoiseGpuEnabled;
            GPurConfig.vanillaAquiferGpuEnabled = this.vanillaAquiferGpuEnabled;
        }
    }
}
