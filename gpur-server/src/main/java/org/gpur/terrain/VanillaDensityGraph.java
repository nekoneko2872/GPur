package org.gpur.terrain;

import it.unimi.dsi.fastutil.doubles.DoubleList;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.QuartPos;
import net.minecraft.util.CubicSpline;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.gpur.compute.VanillaNoiseBatch;
import org.gpur.compute.VanillaNoiseBatch.NoiseProfile;

/**
 * Immutable, bounded CPU snapshot of a deliberately small vanilla 26.2 density-function subset.
 *
 * <p>This is an off-path interpreter snapshot and does not modify NoiseChunk's live evaluation.
 * The default entry points reject runtime wrappers; the explicit owner-binding overload admits
 * only selected exact NoiseChunk wrapper identities. Any unsupported node rejects the complete
 * requested root. Private Mojang record nodes are admitted by exact class name and component shape
 * so a future version change fails closed.
 */
public final class VanillaDensityGraph {
    private static final String DENSITY_FUNCTIONS_PREFIX = DensityFunctions.class.getName() + "$";
    private static final int HARD_MAX_NODES = 16_384;
    private static final int HARD_MAX_EDGES = 65_536;
    private static final int HARD_MAX_DEPTH = 512;
    private static final int HARD_MAX_PROFILES = 4_096;
    private static final int HARD_MAX_BRANCHES = 4_096;
    private static final int HARD_MAX_SPLINE_POINTS = 8_192;
    private static final int HARD_MAX_TABLE_SIZE = 64;
    private static final int HARD_MAX_TABLE_VALUES = 4_096;
    private static final String MARKER_TYPE_CLASS_NAME = DensityFunctions.class.getName() + "$Marker$Type";

    private VanillaDensityGraph() {}

    public static Snapshot snapshot(final DensityFunction root) {
        return snapshot(root, Limits.DEFAULT);
    }

    public static Snapshot snapshot(final DensityFunction root, final Limits limits) {
        return snapshot(root, limits, WrapperBindings.empty());
    }

    /**
     * Snapshots an explicitly prepared NoiseChunk corner graph. Runtime wrappers remain unsupported
     * unless the exact wrapper identity has a matching owner-captured binding.
     */
    public static Snapshot snapshot(final DensityFunction root, final Limits limits, final WrapperBindings bindings) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(bindings, "bindings");
        return new Compiler(limits, bindings).compile(root);
    }

    /** Resource limits are checked while traversing, before each node or edge is retained. */
    public record Limits(
        int maxNodes,
        int maxEdges,
        int maxDepth,
        int maxProfiles,
        int maxBranches,
        int maxSplinePoints,
        int maxTableValues
    ) {
        public static final Limits DEFAULT = new Limits(8_192, 32_768, 256, 2_048, 1_024, HARD_MAX_SPLINE_POINTS, HARD_MAX_TABLE_VALUES);

        public Limits(final int maxNodes, final int maxEdges, final int maxDepth, final int maxProfiles, final int maxBranches) {
            this(maxNodes, maxEdges, maxDepth, maxProfiles, maxBranches, HARD_MAX_SPLINE_POINTS, HARD_MAX_TABLE_VALUES);
        }

        public Limits(
            final int maxNodes,
            final int maxEdges,
            final int maxDepth,
            final int maxProfiles,
            final int maxBranches,
            final int maxSplinePoints
        ) {
            this(maxNodes, maxEdges, maxDepth, maxProfiles, maxBranches, maxSplinePoints, HARD_MAX_TABLE_VALUES);
        }

        public Limits {
            if (maxNodes < 1 || maxNodes > HARD_MAX_NODES
                || maxEdges < 0 || maxEdges > HARD_MAX_EDGES
                || maxDepth < 1 || maxDepth > HARD_MAX_DEPTH
                || maxProfiles < 0 || maxProfiles > HARD_MAX_PROFILES
                || maxBranches < 2 || maxBranches > HARD_MAX_BRANCHES
                || maxSplinePoints < 0 || maxSplinePoints > HARD_MAX_SPLINE_POINTS
                || maxTableValues < 0 || maxTableValues > HARD_MAX_TABLE_VALUES) {
                throw new IllegalArgumentException("Density graph limits are outside the hard bounds");
            }
        }
    }

    /** Identity-keyed, immutable primitive bindings captured by a trusted NoiseChunk owner. */
    public static final class WrapperBindings {
        private static final WrapperBindings EMPTY = new WrapperBindings(new IdentityHashMap<>());
        private final Map<DensityFunction, WrapperBinding> byIdentity;

        private WrapperBindings(final IdentityHashMap<DensityFunction, WrapperBinding> bindings) {
            this.byIdentity = Collections.unmodifiableMap(new IdentityHashMap<>(bindings));
        }

        public static WrapperBindings empty() {
            return EMPTY;
        }

        public static Builder builder() {
            return new Builder();
        }

        private WrapperBinding get(final DensityFunction wrapper) {
            return this.byIdentity.get(wrapper);
        }

        public static final class Builder {
            private final IdentityHashMap<DensityFunction, WrapperBinding> bindings = new IdentityHashMap<>();

            private Builder() {}

            /** Binds an exact Cache2D wrapper; its full child graph must prove Y-independent. */
            public Builder bindCache2D(final DensityFunction wrapper) {
                DensityFunction wrapped = wrapperChild(wrapper, "Cache2D", "Cache2D");
                this.add(wrapper, new CacheAliasBinding(wrapped, CacheKind.CACHE_2D));
                return this;
            }

            /** Explicit opt-in for a CacheOnce wrapper prepared only for proven corner sampling. */
            public Builder bindCacheOnceForCornerPreparation(final DensityFunction wrapper) {
                DensityFunction wrapped = wrapperChild(wrapper, "CacheOnce", "CacheOnce");
                this.add(wrapper, new CacheAliasBinding(wrapped, CacheKind.CACHE_ONCE_CORNER));
                return this;
            }

            /**
             * Captures an exact FlatCache table, with values in the source x + z * sizeXZ order.
             * The tuple must come directly from the owning NoiseChunk; this is a trusted preparation API.
             */
            public Builder bindFlatCache(
                final DensityFunction wrapper,
                final int firstQuartX,
                final int firstQuartZ,
                final int sizeXZ,
                final double[] values
            ) {
                DensityFunction wrapped = wrapperChild(wrapper, "FlatCache", "FlatCache");
                validateTable(sizeXZ, values);
                this.add(wrapper, new FlatTableBinding(wrapped, firstQuartX, firstQuartZ, sizeXZ, values));
                return this;
            }

            public WrapperBindings build() {
                return this.bindings.isEmpty() ? EMPTY : new WrapperBindings(this.bindings);
            }

            private void add(final DensityFunction wrapper, final WrapperBinding binding) {
                if (this.bindings.containsKey(wrapper)) throw new UnsupportedGraphException("NoiseChunk wrapper has more than one primitive binding");
                this.bindings.put(wrapper, binding);
            }
        }
    }

    /** Thrown when the requested root cannot be snapshotted completely and safely. */
    public static final class UnsupportedGraphException extends IllegalArgumentException {
        private UnsupportedGraphException(final String message) {
            super(message);
        }

        private UnsupportedGraphException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Evaluates an immutable initialized noise profile. Implementations supplied to the overload
     * are expected to be deterministic and side-effect free; the default uses VanillaNoiseBatch's
     * CPU reference evaluator.
     */
    @FunctionalInterface
    public interface NoiseSampler {
        double sample(NoiseProfile profile, double x, double y, double z);
    }

    public static final class Snapshot {
        final List<Instruction> instructions;
        final List<NoiseProfile> profiles;
        final Map<NoiseProfile, VanillaNoiseBatch.ExactSampler> exactNoiseSamplers;
        final int root;
        final int edgeCount;
        final int splinePointCount;
        final int tableValueCount;

        private Snapshot(
            final List<Instruction> instructions,
            final List<NoiseProfile> profiles,
            final int root,
            final int edgeCount,
            final int splinePointCount,
            final int tableValueCount
        ) {
            this.instructions = List.copyOf(instructions);
            this.profiles = List.copyOf(profiles);
            List<VanillaNoiseBatch.ExactSampler> packedSamplers = VanillaNoiseBatch.exactSamplers(this.profiles);
            IdentityHashMap<NoiseProfile, VanillaNoiseBatch.ExactSampler> samplersByIdentity = new IdentityHashMap<>();
            for (int index = 0; index < this.profiles.size(); index++) {
                samplersByIdentity.put(this.profiles.get(index), packedSamplers.get(index));
            }
            this.exactNoiseSamplers = Collections.unmodifiableMap(samplersByIdentity);
            this.root = root;
            this.edgeCount = edgeCount;
            this.splinePointCount = splinePointCount;
            this.tableValueCount = tableValueCount;
        }

        public int nodeCount() {
            return this.instructions.size();
        }

        public int edgeCount() {
            return this.edgeCount;
        }

        public int noiseProfileCount() {
            return this.profiles.size();
        }

        public int splinePointCount() {
            return this.splinePointCount;
        }

        public int tableValueCount() {
            return this.tableValueCount;
        }

        public double evaluate(final int blockX, final int blockY, final int blockZ) {
            return this.evaluate(blockX, blockY, blockZ, this::sampleExactly);
        }

        private double sampleExactly(final NoiseProfile profile, final double x, final double y, final double z) {
            VanillaNoiseBatch.ExactSampler sampler = this.exactNoiseSamplers.get(profile);
            if (sampler == null) throw new IllegalArgumentException("Noise profile does not belong to this snapshot");
            return sampler.sample(x, y, z);
        }

        public double evaluate(final int blockX, final int blockY, final int blockZ, final NoiseSampler noiseSampler) {
            Objects.requireNonNull(noiseSampler, "noiseSampler");
            double[] values = new double[this.instructions.size()];
            boolean[] evaluated = new boolean[this.instructions.size()];
            return this.evaluateNode(this.root, blockX, blockY, blockZ, noiseSampler, values, evaluated);
        }

        private double evaluateNode(
            final int index,
            final int blockX,
            final int blockY,
            final int blockZ,
            final NoiseSampler noiseSampler,
            final double[] values,
            final boolean[] evaluated
        ) {
            if (evaluated[index]) return values[index];

            Instruction instruction = this.instructions.get(index);
            Op op = instruction.op;
            double value;
            if (op instanceof Constant constant) {
                value = constant.value;
            } else if (op instanceof Alias alias) {
                value = this.evaluateNode(alias.input, blockX, blockY, blockZ, noiseSampler, values, evaluated);
            } else if (op instanceof SplineConstant splineConstant) {
                value = splineConstant.value;
            } else if (op instanceof SplineMultipoint spline) {
                float input = (float)this.evaluateNode(spline.coordinate, blockX, blockY, blockZ, noiseSampler, values, evaluated);
                int start = Mth.binarySearch(0, spline.locations.length, candidate -> input < spline.locations[candidate]) - 1;
                int lastIndex = spline.locations.length - 1;
                if (start < 0) {
                    float child = (float)this.evaluateNode(spline.values[0], blockX, blockY, blockZ, noiseSampler, values, evaluated);
                    value = splineLinearExtend(input, spline.locations, child, spline.derivatives, 0);
                } else if (start == lastIndex) {
                    float child = (float)this.evaluateNode(spline.values[lastIndex], blockX, blockY, blockZ, noiseSampler, values, evaluated);
                    value = splineLinearExtend(input, spline.locations, child, spline.derivatives, lastIndex);
                } else {
                    float x1 = spline.locations[start];
                    float x2 = spline.locations[start + 1];
                    float t = (input - x1) / (x2 - x1);
                    float y1 = (float)this.evaluateNode(spline.values[start], blockX, blockY, blockZ, noiseSampler, values, evaluated);
                    float y2 = (float)this.evaluateNode(spline.values[start + 1], blockX, blockY, blockZ, noiseSampler, values, evaluated);
                    float d1 = spline.derivatives[start];
                    float d2 = spline.derivatives[start + 1];
                    float a = d1 * (x2 - x1) - (y2 - y1);
                    float b = -d2 * (x2 - x1) + (y2 - y1);
                    value = Mth.lerp(t, y1, y2) + t * (1.0F - t) * Mth.lerp(t, a, b);
                }
            } else if (op instanceof TableLookup table) {
                long x = (long)QuartPos.fromBlock(blockX) - table.firstQuartX;
                long z = (long)QuartPos.fromBlock(blockZ) - table.firstQuartZ;
                if (x < 0 || z < 0 || x >= table.sizeXZ || z >= table.sizeXZ) {
                    throw new IllegalArgumentException("FlatCache table sample lies outside the captured quart bounds");
                }
                int tableIndex = (int)x + (int)z * table.sizeXZ;
                value = table.values[tableIndex];
            } else if (op instanceof YGradient gradient) {
                value = Mth.clampedMap(blockY, gradient.fromY, gradient.toY, gradient.fromValue, gradient.toValue);
            } else if (op instanceof Clamp clamp) {
                value = Mth.clamp(this.evaluateNode(clamp.input, blockX, blockY, blockZ, noiseSampler, values, evaluated), clamp.min, clamp.max);
            } else if (op instanceof Mapped mapped) {
                double input = this.evaluateNode(mapped.input, blockX, blockY, blockZ, noiseSampler, values, evaluated);
                value = transform(mapped.kind, input);
            } else if (op instanceof Binary binary) {
                double first = this.evaluateNode(binary.first, blockX, blockY, blockZ, noiseSampler, values, evaluated);
                value = switch (binary.kind) {
                    case ADD -> first + this.evaluateNode(binary.second, blockX, blockY, blockZ, noiseSampler, values, evaluated);
                    case MUL -> first == 0.0 ? 0.0 : first * this.evaluateNode(binary.second, blockX, blockY, blockZ, noiseSampler, values, evaluated);
                    case MIN -> first < binary.secondMin
                        ? first
                        : Math.min(first, this.evaluateNode(binary.second, blockX, blockY, blockZ, noiseSampler, values, evaluated));
                    case MAX -> first > binary.secondMax
                        ? first
                        : Math.max(first, this.evaluateNode(binary.second, blockX, blockY, blockZ, noiseSampler, values, evaluated));
                };
            } else if (op instanceof MulOrAdd mulOrAdd) {
                double input = this.evaluateNode(mulOrAdd.input, blockX, blockY, blockZ, noiseSampler, values, evaluated);
                value = mulOrAdd.kind == BinaryKind.MUL ? input * mulOrAdd.argument : input + mulOrAdd.argument;
            } else if (op instanceof RangeChoice rangeChoice) {
                double input = this.evaluateNode(rangeChoice.input, blockX, blockY, blockZ, noiseSampler, values, evaluated);
                int branch = input >= rangeChoice.minInclusive && input < rangeChoice.maxExclusive
                    ? rangeChoice.whenInRange
                    : rangeChoice.whenOutOfRange;
                value = this.evaluateNode(branch, blockX, blockY, blockZ, noiseSampler, values, evaluated);
            } else if (op instanceof IntervalSelect intervalSelect) {
                double input = this.evaluateNode(intervalSelect.input, blockX, blockY, blockZ, noiseSampler, values, evaluated);
                int branch = intervalSelect.branches.length - 1;
                for (int i = 0; i < intervalSelect.thresholds.length; i++) {
                    if (input < intervalSelect.thresholds[i]) {
                        branch = i;
                        break;
                    }
                }
                value = this.evaluateNode(intervalSelect.branches[branch], blockX, blockY, blockZ, noiseSampler, values, evaluated);
            } else if (op instanceof Noise noise) {
                double x = blockX * noise.xzScale;
                double y = blockY * noise.yScale;
                double z = blockZ * noise.xzScale;
                value = noiseSampler.sample(this.profiles.get(noise.profile), x, y, z);
            } else if (op instanceof Shift shift) {
                double x = blockX * 0.25;
                double y = shift.kind == ShiftKind.SHIFT ? blockY * 0.25 : 0.0;
                double z;
                if (shift.kind == ShiftKind.SHIFT_B) {
                    x = blockZ * 0.25;
                    y = blockX * 0.25;
                    z = 0.0;
                } else {
                    z = blockZ * 0.25;
                }
                value = noiseSampler.sample(this.profiles.get(shift.profile), x, y, z) * 4.0;
            } else if (op instanceof ShiftedNoise shifted) {
                double baseX = blockX * shifted.xzScale;
                double shiftX = this.evaluateNode(shifted.shiftX, blockX, blockY, blockZ, noiseSampler, values, evaluated);
                double x = baseX + shiftX;
                double baseY = blockY * shifted.yScale;
                double shiftY = this.evaluateNode(shifted.shiftY, blockX, blockY, blockZ, noiseSampler, values, evaluated);
                double y = baseY + shiftY;
                double baseZ = blockZ * shifted.xzScale;
                double shiftZ = this.evaluateNode(shifted.shiftZ, blockX, blockY, blockZ, noiseSampler, values, evaluated);
                double z = baseZ + shiftZ;
                value = noiseSampler.sample(this.profiles.get(shifted.profile), x, y, z);
            } else if (op instanceof Blended blended) {
                value = noiseSampler.sample(this.profiles.get(blended.profile), blockX, blockY, blockZ);
            } else {
                throw new IllegalStateException("Unknown density graph instruction " + op.getClass().getName());
            }

            evaluated[index] = true;
            values[index] = value;
            return value;
        }
    }

    enum BinaryKind { ADD, MUL, MIN, MAX }
    enum ShiftKind { SHIFT, SHIFT_A, SHIFT_B }
    enum MapKind { ABS, SQUARE, CUBE, HALF_NEGATIVE, QUARTER_NEGATIVE, INVERT, SQUEEZE }

    sealed interface Op permits Constant, YGradient, Clamp, Mapped, Binary, MulOrAdd,
        RangeChoice, IntervalSelect, Noise, Shift, ShiftedNoise, Blended, Alias,
        SplineConstant, SplineMultipoint, TableLookup {}

    record Instruction(Op op, double minValue, double maxValue) {}
    record Constant(double value) implements Op {}
    record YGradient(int fromY, int toY, double fromValue, double toValue) implements Op {}
    record Clamp(int input, double min, double max) implements Op {}
    record Mapped(int input, MapKind kind) implements Op {}
    record Binary(BinaryKind kind, int first, int second, double secondMin, double secondMax) implements Op {}
    record MulOrAdd(BinaryKind kind, int input, double argument) implements Op {}
    record RangeChoice(int input, double minInclusive, double maxExclusive, int whenInRange, int whenOutOfRange) implements Op {}
    record IntervalSelect(int input, double[] thresholds, int[] branches) implements Op {
        IntervalSelect {
            thresholds = thresholds.clone();
            branches = branches.clone();
        }

        public double[] thresholds() { return thresholds.clone(); }
        public int[] branches() { return branches.clone(); }
    }
    record Noise(int profile, double xzScale, double yScale) implements Op {}
    record Shift(int profile, ShiftKind kind) implements Op {}
    record ShiftedNoise(int shiftX, int shiftY, int shiftZ, int profile, double xzScale, double yScale) implements Op {}
    record Blended(int profile) implements Op {}
    record SplineConstant(float value) implements Op {}
    record SplineMultipoint(int coordinate, float[] locations, float[] derivatives, int[] values) implements Op {
        SplineMultipoint {
            locations = locations.clone();
            derivatives = derivatives.clone();
            values = values.clone();
        }

        public float[] locations() { return locations.clone(); }
        public float[] derivatives() { return derivatives.clone(); }
        public int[] values() { return values.clone(); }
    }
    record TableLookup(int firstQuartX, int firstQuartZ, int sizeXZ, double[] values) implements Op {
        TableLookup {
            validateTable(sizeXZ, values);
            values = values.clone();
        }

        public double[] values() { return values.clone(); }
    }

    private static int[] children(final Op op) {
        if (op instanceof Constant || op instanceof YGradient || op instanceof Noise || op instanceof Shift
            || op instanceof Blended || op instanceof SplineConstant || op instanceof TableLookup) return new int[0];
        if (op instanceof Alias value) return new int[] {value.input};
        if (op instanceof Clamp value) return new int[] {value.input};
        if (op instanceof Mapped value) return new int[] {value.input};
        if (op instanceof Binary value) return new int[] {value.first, value.second};
        if (op instanceof MulOrAdd value) return new int[] {value.input};
        if (op instanceof RangeChoice value) return new int[] {value.input, value.whenInRange, value.whenOutOfRange};
        if (op instanceof IntervalSelect value) {
            int[] result = new int[value.branches.length + 1];
            result[0] = value.input;
            System.arraycopy(value.branches, 0, result, 1, value.branches.length);
            return result;
        }
        if (op instanceof ShiftedNoise value) return new int[] {value.shiftX, value.shiftY, value.shiftZ};
        if (op instanceof SplineMultipoint value) {
            int[] result = new int[value.values.length + 1];
            result[0] = value.coordinate;
            System.arraycopy(value.values, 0, result, 1, value.values.length);
            return result;
        }
        throw new UnsupportedGraphException("unknown density graph operation " + op.getClass().getName());
    }

    private enum VisitState { VISITING, COMPLETE }
    private enum CacheKind { CACHE_2D, CACHE_ONCE_CORNER }
    private sealed interface WrapperBinding permits CacheAliasBinding, FlatTableBinding {
        DensityFunction wrapped();
    }
    private record CacheAliasBinding(DensityFunction wrapped, CacheKind kind) implements WrapperBinding {}
    private record FlatTableBinding(
        DensityFunction wrapped,
        int firstQuartX,
        int firstQuartZ,
        int sizeXZ,
        double[] values
    ) implements WrapperBinding {
        FlatTableBinding {
            validateTable(sizeXZ, values);
            values = values.clone();
        }

        public double[] values() { return values.clone(); }
    }

    private static final class Compiler {
        private final Limits limits;
        private final WrapperBindings wrapperBindings;
        private final IdentityHashMap<Object, Integer> ids = new IdentityHashMap<>();
        private final IdentityHashMap<Object, VisitState> states = new IdentityHashMap<>();
        private final IdentityHashMap<Object, Integer> profileIds = new IdentityHashMap<>();
        private final List<Instruction> instructions = new ArrayList<>();
        private final List<Integer> heights = new ArrayList<>();
        private final List<NoiseProfile> profiles = new ArrayList<>();
        private int edges;
        private int splinePoints;
        private int tableValues;
        private byte[] yIndependenceState = new byte[0];
        private boolean[] yIndependenceResults = new boolean[0];

        private Compiler(final Limits limits, final WrapperBindings wrapperBindings) {
            this.limits = limits;
            this.wrapperBindings = wrapperBindings;
        }

        private Snapshot compile(final DensityFunction root) {
            int rootId = this.node(root, 0);
            if (this.instructions.size() != this.ids.size()) {
                throw new IllegalStateException("Density graph snapshot contains an incomplete node");
            }
            return new Snapshot(this.instructions, this.profiles, rootId, this.edges, this.splinePoints, this.tableValues);
        }

        private int node(final DensityFunction function, final int depth) {
            if (function == null) throw this.unsupported("null density child");
            Integer known = this.known(function, depth);
            if (known != null) return known;
            int id = this.reserve(function);

            Op op = this.lower(function, depth);
            double minValue;
            double maxValue;
            try {
                minValue = function.minValue();
                maxValue = function.maxValue();
            } catch (RuntimeException failure) {
                throw this.unsupported("failed to read vanilla density bounds for " + function.getClass().getName(), failure);
            }
            this.finish(function, id, op, minValue, maxValue, depth);
            return id;
        }

        private Op lower(final DensityFunction function, final int depth) {
            WrapperBinding binding = this.wrapperBindings.get(function);
            if (binding != null) return this.lowerBoundWrapper(function, binding, depth);

            Class<?> type = function.getClass();
            if (type.getNestHost() != DensityFunctions.class && type != BlendedNoise.class) {
                throw this.unsupported("unsupported density node class " + type.getName());
            }

            if (this.isNode(type, "Spline") && function instanceof DensityFunctions.Spline spline) {
                return new Alias(this.splineChild(spline.spline(), depth));
            }

            if (this.isNode(type, "BlendAlpha") && function instanceof Enum<?> alpha && alpha.name().equals("INSTANCE")) {
                return new Constant(1.0);
            }
            if (this.isNode(type, "BlendOffset") && function instanceof Enum<?> offset && offset.name().equals("INSTANCE")) {
                return new Constant(0.0);
            }
            if (this.isNode(type, "Constant")) {
                this.requireComponents(type, "value");
                return new Constant(number(this.component(function, "value"), "constant value").doubleValue());
            }
            if (this.isNode(type, "YClampedGradient")) {
                this.requireComponents(type, "fromY", "toY", "fromValue", "toValue");
                return new YGradient(
                    number(this.component(function, "fromY"), "gradient fromY").intValue(),
                    number(this.component(function, "toY"), "gradient toY").intValue(),
                    number(this.component(function, "fromValue"), "gradient fromValue").doubleValue(),
                    number(this.component(function, "toValue"), "gradient toValue").doubleValue()
                );
            }
            if (this.isNode(type, "Clamp")) {
                this.requireComponents(type, "input", "minValue", "maxValue");
                int input = this.child(this.component(function, "input"), depth);
                return new Clamp(
                    input,
                    number(this.component(function, "minValue"), "clamp minimum").doubleValue(),
                    number(this.component(function, "maxValue"), "clamp maximum").doubleValue()
                );
            }
            if (this.isNode(type, "Mapped")) {
                this.requireComponents(type, "type", "input", "minValue", "maxValue");
                Object mappedType = this.component(function, "type");
                int input = this.child(this.component(function, "input"), depth);
                return new Mapped(input, mapKind(mappedType));
            }
            if (this.isNode(type, "MulOrAdd")) {
                this.requireComponents(type, "specificType", "input", "minValue", "maxValue", "argument");
                BinaryKind kind = switch (enumName(this.component(function, "specificType"), "MulOrAdd type")) {
                    case "MUL" -> BinaryKind.MUL;
                    case "ADD" -> BinaryKind.ADD;
                    default -> throw this.unsupported("unknown vanilla MulOrAdd type");
                };
                int input = this.child(this.component(function, "input"), depth);
                double argument = number(this.component(function, "argument"), "MulOrAdd argument").doubleValue();
                return new MulOrAdd(kind, input, argument);
            }
            if (function instanceof DensityFunctions.TwoArgumentSimpleFunction twoArgument && this.isNode(type, "Ap2")) {
                this.requireComponents(type, "type", "argument1", "argument2", "minValue", "maxValue");
                BinaryKind kind = switch (twoArgument.type()) {
                    case ADD -> BinaryKind.ADD;
                    case MUL -> BinaryKind.MUL;
                    case MIN -> BinaryKind.MIN;
                    case MAX -> BinaryKind.MAX;
                };
                int first = this.child(twoArgument.argument1(), depth);
                int second = this.child(twoArgument.argument2(), depth);
                Instruction right = this.instructions.get(second);
                return new Binary(kind, first, second, right.minValue, right.maxValue);
            }
            if (this.isNode(type, "RangeChoice")) {
                this.requireComponents(type, "input", "minInclusive", "maxExclusive", "whenInRange", "whenOutOfRange");
                int input = this.child(this.component(function, "input"), depth);
                double min = number(this.component(function, "minInclusive"), "range minimum").doubleValue();
                double max = number(this.component(function, "maxExclusive"), "range maximum").doubleValue();
                int inRange = this.child(this.component(function, "whenInRange"), depth);
                int outOfRange = this.child(this.component(function, "whenOutOfRange"), depth);
                return new RangeChoice(input, min, max, inRange, outOfRange);
            }
            if (this.isNode(type, "IntervalSelect")) {
                this.requireComponents(type, "input", "thresholds", "functions");
                int input = this.child(this.component(function, "input"), depth);
                Object thresholdsObject = this.component(function, "thresholds");
                Object functionsObject = this.component(function, "functions");
                if (!(thresholdsObject instanceof DoubleList thresholds) || !(functionsObject instanceof List<?> functions)) {
                    throw this.unsupported("malformed IntervalSelect components");
                }
                if (functions.size() < 2 || functions.size() > this.limits.maxBranches
                    || thresholds.size() != functions.size() - 1) {
                    throw this.unsupported("IntervalSelect branch/threshold count is invalid or over limit");
                }
                double[] thresholdValues = new double[thresholds.size()];
                for (int i = 0; i < thresholdValues.length; i++) thresholdValues[i] = thresholds.getDouble(i);
                int[] branches = new int[functions.size()];
                for (int i = 0; i < branches.length; i++) branches[i] = this.child(functions.get(i), depth);
                return new IntervalSelect(input, thresholdValues, branches);
            }
            if (this.isNode(type, "Noise")) {
                this.requireComponents(type, "noise", "xzScale", "yScale");
                int profile = this.profile(this.component(function, "noise"));
                return new Noise(
                    profile,
                    number(this.component(function, "xzScale"), "noise XZ scale").doubleValue(),
                    number(this.component(function, "yScale"), "noise Y scale").doubleValue()
                );
            }
            if (this.isNode(type, "Shift") || this.isNode(type, "ShiftA") || this.isNode(type, "ShiftB")) {
                this.requireComponents(type, "offsetNoise");
                ShiftKind kind = this.isNode(type, "Shift") ? ShiftKind.SHIFT
                    : this.isNode(type, "ShiftA") ? ShiftKind.SHIFT_A : ShiftKind.SHIFT_B;
                return new Shift(this.profile(this.component(function, "offsetNoise")), kind);
            }
            if (this.isNode(type, "ShiftedNoise")) {
                this.requireComponents(type, "shiftX", "shiftY", "shiftZ", "xzScale", "yScale", "noise");
                int shiftX = this.child(this.component(function, "shiftX"), depth);
                int shiftY = this.child(this.component(function, "shiftY"), depth);
                int shiftZ = this.child(this.component(function, "shiftZ"), depth);
                int profile = this.profile(this.component(function, "noise"));
                return new ShiftedNoise(
                    shiftX,
                    shiftY,
                    shiftZ,
                    profile,
                    number(this.component(function, "xzScale"), "shifted noise XZ scale").doubleValue(),
                    number(this.component(function, "yScale"), "shifted noise Y scale").doubleValue()
                );
            }
            if (type == BlendedNoise.class) {
                int profile = this.profile(function);
                return new Blended(profile);
            }
            if (function instanceof DensityFunctions.HolderHolder holderHolder && type == DensityFunctions.HolderHolder.class) {
                if (!holderHolder.function().isBound()) throw this.unsupported("unbound density holder");
                return new Alias(this.child(holderHolder.function().value(), depth));
            }

            throw this.unsupported("unsupported density node class " + type.getName());
        }

        private Op lowerBoundWrapper(final DensityFunction function, final WrapperBinding binding, final int depth) {
            if (binding instanceof CacheAliasBinding alias) {
                String nestedClass = alias.kind == CacheKind.CACHE_2D ? "Cache2D" : "CacheOnce";
                String markerName = nestedClass;
                DensityFunction actualChild = wrapperChild(function, nestedClass, markerName);
                if (actualChild != alias.wrapped) throw this.unsupported("NoiseChunk alias binding no longer matches its wrapped child identity");
                int child = this.child(alias.wrapped, depth);
                if (alias.kind == CacheKind.CACHE_2D && !this.isYIndependent(child)) {
                    throw this.unsupported("Cache2D child graph is not proven independent of Y");
                }
                return new Alias(child);
            }
            if (binding instanceof FlatTableBinding table) {
                DensityFunction actualChild = wrapperChild(function, "FlatCache", "FlatCache");
                if (actualChild != table.wrapped) throw this.unsupported("FlatCache binding no longer matches its wrapped child identity");
                validateTable(table.sizeXZ, table.values);
                if (table.values.length > this.limits.maxTableValues - this.tableValues) {
                    throw this.unsupported("density graph exceeds maximum captured FlatCache table value count " + this.limits.maxTableValues);
                }
                this.tableValues += table.values.length;
                return new TableLookup(table.firstQuartX, table.firstQuartZ, table.sizeXZ, table.values);
            }
            throw this.unsupported("unknown primitive NoiseChunk wrapper binding");
        }

        private boolean isYIndependent(final int node) {
            if (this.yIndependenceState.length < this.instructions.size()) {
                this.yIndependenceState = Arrays.copyOf(this.yIndependenceState, this.instructions.size());
                this.yIndependenceResults = Arrays.copyOf(this.yIndependenceResults, this.instructions.size());
            }
            return this.isYIndependentNode(node);
        }

        private boolean isYIndependentNode(final int node) {
            if (node < 0 || node >= this.instructions.size() || this.instructions.get(node) == null) {
                throw this.unsupported("Cache2D child graph contains an incomplete node");
            }
            if (this.yIndependenceState[node] == 2) return this.yIndependenceResults[node];
            if (this.yIndependenceState[node] == 1) throw this.unsupported("cycle while proving Cache2D child Y independence");
            this.yIndependenceState[node] = 1;
            Op op = this.instructions.get(node).op;
            boolean independent;
            if (op instanceof Constant || op instanceof SplineConstant || op instanceof TableLookup) {
                independent = true;
            } else if (op instanceof Shift shift) {
                independent = shift.kind != ShiftKind.SHIFT;
            } else if (op instanceof YGradient || op instanceof Noise || op instanceof ShiftedNoise || op instanceof Blended) {
                independent = false;
            } else {
                independent = true;
                for (int child : children(op)) {
                    if (!this.isYIndependentNode(child)) {
                        independent = false;
                        break;
                    }
                }
            }
            this.yIndependenceResults[node] = independent;
            this.yIndependenceState[node] = 2;
            return independent;
        }

        private int child(final Object value, final int depth) {
            if (!(value instanceof DensityFunction child)) throw this.unsupported("density child is not a DensityFunction");
            return this.edge(child, depth);
        }

        private int splineChild(final CubicSpline<?> spline, final int depth) {
            if (spline == null) throw this.unsupported("spline child is null");
            return this.edge(spline, depth);
        }

        private int edge(final Object child, final int parentDepth) {
            if (this.edges >= this.limits.maxEdges) throw this.unsupported("density graph exceeds maximum edge count " + this.limits.maxEdges);
            this.edges++;
            if (child instanceof DensityFunction densityFunction) return this.node(densityFunction, parentDepth + 1);
            if (child instanceof CubicSpline<?> spline) return this.splineNode(spline, parentDepth + 1);
            throw this.unsupported("graph edge child is neither a density function nor a cubic spline");
        }

        private int splineNode(final CubicSpline<?> spline, final int depth) {
            Integer known = this.known(spline, depth);
            if (known != null) return known;
            int id = this.reserve(spline);
            Op op;
            if (spline instanceof CubicSpline.Constant<?> constant) {
                float value = constant.value();
                requireFinite(value, "spline constant");
                op = new SplineConstant(value);
            } else if (spline instanceof CubicSpline.Multipoint<?> multipoint) {
                if (!(multipoint.coordinate() instanceof DensityFunctions.Spline.Coordinate coordinate)) {
                    throw this.unsupported("spline coordinate is not a DensityFunctions.Spline.Coordinate");
                }
                float[] locations = multipoint.locations().clone();
                float[] derivatives = multipoint.derivatives().clone();
                List<?> children = multipoint.values();
                if (locations.length == 0 || locations.length != derivatives.length || locations.length != children.size()) {
                    throw this.unsupported("spline multipoint array/list lengths disagree");
                }
                if (locations.length > this.limits.maxBranches) {
                    throw this.unsupported("spline multipoint exceeds maximum point count per node " + this.limits.maxBranches);
                }
                if (locations.length > this.limits.maxSplinePoints - this.splinePoints) {
                    throw this.unsupported("density graph exceeds maximum total spline point count " + this.limits.maxSplinePoints);
                }
                this.splinePoints += locations.length;
                for (int i = 0; i < locations.length; i++) {
                    requireFinite(locations[i], "spline location");
                    requireFinite(derivatives[i], "spline derivative");
                    if (i > 0 && locations[i] <= locations[i - 1]) {
                        throw this.unsupported("spline locations are not strictly increasing");
                    }
                }

                int coordinateId = this.child(coordinate.function(), depth);
                int[] valueIds = new int[children.size()];
                for (int i = 0; i < valueIds.length; i++) {
                    Object value = children.get(i);
                    if (!(value instanceof CubicSpline<?> childSpline)) {
                        throw this.unsupported("spline point value is not a CubicSpline");
                    }
                    valueIds[i] = this.splineChild(childSpline, depth);
                }
                op = new SplineMultipoint(coordinateId, locations, derivatives, valueIds);
            } else {
                throw this.unsupported("unsupported CubicSpline implementation " + spline.getClass().getName());
            }

            float minimum;
            float maximum;
            try {
                minimum = spline.minValue();
                maximum = spline.maxValue();
            } catch (RuntimeException failure) {
                throw this.unsupported("failed to read vanilla spline bounds", failure);
            }
            requireFinite(minimum, "spline minimum");
            requireFinite(maximum, "spline maximum");
            if (minimum > maximum) throw this.unsupported("spline minimum exceeds maximum");
            this.finish(spline, id, op, minimum, maximum, depth);
            return id;
        }

        private Integer known(final Object node, final int depth) {
            if (depth > this.limits.maxDepth) {
                throw this.unsupported("density graph exceeds maximum depth " + this.limits.maxDepth);
            }
            Integer known = this.ids.get(node);
            if (known == null) return null;
            if (this.states.get(node) == VisitState.VISITING) throw this.unsupported("cycle in density/spline graph");
            if ((long)depth + this.heights.get(known) - 1L > this.limits.maxDepth) {
                throw this.unsupported("density/spline graph exceeds maximum depth through a shared child");
            }
            return known;
        }

        private int reserve(final Object node) {
            if (this.instructions.size() >= this.limits.maxNodes) {
                throw this.unsupported("density graph exceeds maximum node count " + this.limits.maxNodes);
            }
            int id = this.instructions.size();
            this.ids.put(node, id);
            this.states.put(node, VisitState.VISITING);
            this.instructions.add(null);
            this.heights.add(0);
            return id;
        }

        private void finish(final Object source, final int id, final Op op, final double minValue, final double maxValue, final int depth) {
            int height = 1;
            for (int child : children(op)) {
                if (child < 0 || child >= this.heights.size() || this.heights.get(child) < 1) {
                    throw this.unsupported("graph node refers to an incomplete child");
                }
                height = Math.max(height, this.heights.get(child) + 1);
            }
            if ((long)depth + height - 1L > this.limits.maxDepth) {
                throw this.unsupported("density/spline graph exceeds maximum depth " + this.limits.maxDepth);
            }
            this.instructions.set(id, new Instruction(op, minValue, maxValue));
            this.heights.set(id, height);
            this.states.put(source, VisitState.COMPLETE);
        }

        private int profile(final Object source) {
            Object sampler;
            java.util.function.Supplier<NoiseProfile> snapshotter;
            if (source instanceof DensityFunction.NoiseHolder holder) {
                NormalNoise normal = holder.noise();
                if (normal == null || normal.getClass() != NormalNoise.class) {
                    throw this.unsupported("noise holder is not initialized with an exact NormalNoise sampler");
                }
                sampler = normal;
                snapshotter = normal::gpurSnapshot;
            } else if (source instanceof BlendedNoise blended) {
                sampler = blended;
                snapshotter = blended::gpurSnapshot;
            } else {
                throw this.unsupported("noise source is not a supported initialized vanilla sampler");
            }

            Integer known = this.profileIds.get(sampler);
            if (known != null) return known;
            if (this.profiles.size() >= this.limits.maxProfiles) throw this.unsupported("density graph exceeds maximum noise profile count " + this.limits.maxProfiles);
            NoiseProfile profile;
            try {
                profile = snapshotter.get();
            } catch (RuntimeException failure) {
                throw this.unsupported("could not snapshot initialized vanilla noise", failure);
            }
            int id = this.profiles.size();
            this.profileIds.put(sampler, id);
            this.profiles.add(Objects.requireNonNull(profile, "noise profile"));
            return id;
        }

        private boolean isNode(final Class<?> type, final String simpleName) {
            return type.getNestHost() == DensityFunctions.class && type.getName().equals(DENSITY_FUNCTIONS_PREFIX + simpleName);
        }

        private void requireComponents(final Class<?> type, final String... names) {
            if (!type.isRecord()) throw this.unsupported("expected vanilla record node " + type.getName());
            java.lang.reflect.RecordComponent[] components = type.getRecordComponents();
            if (components.length != names.length) throw this.unsupported("vanilla record shape changed for " + type.getName());
            for (int i = 0; i < names.length; i++) {
                if (!components[i].getName().equals(names[i])) throw this.unsupported("vanilla record shape changed for " + type.getName());
            }
        }

        private Object component(final Object record, final String name) {
            try {
                Method accessor = record.getClass().getDeclaredMethod(name);
                if (!accessor.trySetAccessible()) throw this.unsupported("cannot access vanilla record component " + name);
                return accessor.invoke(record);
            } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException failure) {
                throw this.unsupported("cannot read vanilla record component " + name, failure);
            }
        }

        private UnsupportedGraphException unsupported(final String reason) {
            return new UnsupportedGraphException(reason);
        }

        private UnsupportedGraphException unsupported(final String reason, final Throwable cause) {
            return new UnsupportedGraphException(reason, cause);
        }
    }

    record Alias(int input) implements Op {}

    private static Number number(final Object value, final String label) {
        if (!(value instanceof Number number)) throw new UnsupportedGraphException("invalid " + label + " component");
        return number;
    }

    private static void requireFinite(final float value, final String label) {
        if (!Float.isFinite(value)) throw new UnsupportedGraphException(label + " is not finite");
    }

    private static DensityFunction wrapperChild(
        final DensityFunction wrapper,
        final String nestedClass,
        final String expectedMarkerName
    ) {
        if (wrapper == null) throw new UnsupportedGraphException("NoiseChunk wrapper is null");
        Class<?> type = wrapper.getClass();
        String expectedName = NoiseChunk.class.getName() + "$" + nestedClass;
        if (type.getNestHost() != NoiseChunk.class || type.getClassLoader() != NoiseChunk.class.getClassLoader()
            || !type.getName().equals(expectedName)) {
            throw new UnsupportedGraphException("only the exact NoiseChunk$" + nestedClass + " wrapper can be bound");
        }
        if (!(wrapper instanceof DensityFunctions.MarkerOrMarked marker)) {
            throw new UnsupportedGraphException("NoiseChunk wrapper marker type does not match " + nestedClass);
        }
        Object markerType = marker.type();
        if (!(markerType instanceof Enum<?> markerEnum)
            || !markerEnum.getDeclaringClass().getName().equals(MARKER_TYPE_CLASS_NAME)
            || markerEnum.getDeclaringClass().getNestHost() != DensityFunctions.class
            || markerEnum.getDeclaringClass().getClassLoader() != DensityFunctions.class.getClassLoader()
            || !markerEnum.name().equals(expectedMarkerName)) {
            throw new UnsupportedGraphException("NoiseChunk wrapper marker type does not match " + nestedClass);
        }
        DensityFunction wrapped = marker.wrapped();
        if (wrapped == null) throw new UnsupportedGraphException("NoiseChunk wrapper has a null wrapped child");
        return wrapped;
    }

    private static void validateTable(final int sizeXZ, final double[] values) {
        if (values == null || sizeXZ < 1 || sizeXZ > HARD_MAX_TABLE_SIZE
            || (long)sizeXZ * sizeXZ != values.length || values.length > HARD_MAX_TABLE_VALUES) {
            throw new UnsupportedGraphException("FlatCache table dimensions are invalid or exceed the hard bound");
        }
        for (double value : values) {
            if (!Double.isFinite(value)) throw new UnsupportedGraphException("FlatCache table contains a non-finite value");
        }
    }

    private static float splineLinearExtend(final float input, final float[] locations, final float value, final float[] derivatives, final int index) {
        float derivative = derivatives[index];
        return derivative == 0.0F ? value : value + derivative * (input - locations[index]);
    }

    private static String enumName(final Object value, final String label) {
        if (!(value instanceof Enum<?> enumValue)) throw new UnsupportedGraphException("invalid " + label + " component");
        return enumValue.name();
    }

    private static MapKind mapKind(final Object value) {
        return switch (enumName(value, "mapped function type")) {
            case "ABS" -> MapKind.ABS;
            case "SQUARE" -> MapKind.SQUARE;
            case "CUBE" -> MapKind.CUBE;
            case "HALF_NEGATIVE" -> MapKind.HALF_NEGATIVE;
            case "QUARTER_NEGATIVE" -> MapKind.QUARTER_NEGATIVE;
            case "INVERT" -> MapKind.INVERT;
            case "SQUEEZE" -> MapKind.SQUEEZE;
            default -> throw new UnsupportedGraphException("unknown vanilla mapped function " + value);
        };
    }

    private static double transform(final MapKind kind, final double input) {
        return switch (kind) {
            case ABS -> Math.abs(input);
            case SQUARE -> input * input;
            case CUBE -> input * input * input;
            case HALF_NEGATIVE -> input > 0.0 ? input : input * 0.5;
            case QUARTER_NEGATIVE -> input > 0.0 ? input : input * 0.25;
            case INVERT -> 1.0 / input;
            case SQUEEZE -> {
                double clamped = Mth.clamp(input, -1.0, 1.0);
                yield clamped / 2.0 - clamped * clamped * clamped / 24.0;
            }
        };
    }

}
