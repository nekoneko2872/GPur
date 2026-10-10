package org.gpur.terrain;

import it.unimi.dsi.fastutil.doubles.DoubleList;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.gpur.compute.VanillaNoiseBatch;
import org.gpur.compute.VanillaNoiseBatch.NoiseProfile;

/**
 * Immutable, bounded CPU snapshot of a deliberately small vanilla 26.2 density-function subset.
 *
 * <p>This is an interpreter foundation only. It does not modify or bypass NoiseChunk, and any
 * unsupported node rejects the complete requested root. The private Mojang record nodes are
 * admitted by exact class name and exact component shape so a future version change fails closed.
 */
public final class VanillaDensityGraph {
    private static final String DENSITY_FUNCTIONS_PREFIX = DensityFunctions.class.getName() + "$";
    private static final int HARD_MAX_NODES = 16_384;
    private static final int HARD_MAX_EDGES = 65_536;
    private static final int HARD_MAX_DEPTH = 512;
    private static final int HARD_MAX_PROFILES = 4_096;
    private static final int HARD_MAX_BRANCHES = 4_096;
    private static final NoiseSampler EXACT_NOISE_SAMPLER = VanillaDensityGraph::sampleProfileExactly;

    private VanillaDensityGraph() {}

    public static Snapshot snapshot(final DensityFunction root) {
        return snapshot(root, Limits.DEFAULT);
    }

    public static Snapshot snapshot(final DensityFunction root, final Limits limits) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(limits, "limits");
        return new Compiler(limits).compile(root);
    }

    /** Resource limits are checked while traversing, before each node or edge is retained. */
    public record Limits(int maxNodes, int maxEdges, int maxDepth, int maxProfiles, int maxBranches) {
        public static final Limits DEFAULT = new Limits(8_192, 32_768, 256, 2_048, 1_024);

        public Limits {
            if (maxNodes < 1 || maxNodes > HARD_MAX_NODES
                || maxEdges < 0 || maxEdges > HARD_MAX_EDGES
                || maxDepth < 1 || maxDepth > HARD_MAX_DEPTH
                || maxProfiles < 0 || maxProfiles > HARD_MAX_PROFILES
                || maxBranches < 2 || maxBranches > HARD_MAX_BRANCHES) {
                throw new IllegalArgumentException("Density graph limits are outside the hard bounds");
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
        private final List<Instruction> instructions;
        private final List<NoiseProfile> profiles;
        private final int root;
        private final int edgeCount;

        private Snapshot(final List<Instruction> instructions, final List<NoiseProfile> profiles, final int root, final int edgeCount) {
            this.instructions = List.copyOf(instructions);
            this.profiles = List.copyOf(profiles);
            this.root = root;
            this.edgeCount = edgeCount;
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

        public double evaluate(final int blockX, final int blockY, final int blockZ) {
            return this.evaluate(blockX, blockY, blockZ, EXACT_NOISE_SAMPLER);
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

    private enum BinaryKind { ADD, MUL, MIN, MAX }
    private enum ShiftKind { SHIFT, SHIFT_A, SHIFT_B }
    private enum MapKind { ABS, SQUARE, CUBE, HALF_NEGATIVE, QUARTER_NEGATIVE, INVERT, SQUEEZE }

    private sealed interface Op permits Constant, YGradient, Clamp, Mapped, Binary, MulOrAdd,
        RangeChoice, IntervalSelect, Noise, Shift, ShiftedNoise, Blended, Alias {}

    private record Instruction(Op op, double minValue, double maxValue) {}
    private record Constant(double value) implements Op {}
    private record YGradient(int fromY, int toY, double fromValue, double toValue) implements Op {}
    private record Clamp(int input, double min, double max) implements Op {}
    private record Mapped(int input, MapKind kind) implements Op {}
    private record Binary(BinaryKind kind, int first, int second, double secondMin, double secondMax) implements Op {}
    private record MulOrAdd(BinaryKind kind, int input, double argument) implements Op {}
    private record RangeChoice(int input, double minInclusive, double maxExclusive, int whenInRange, int whenOutOfRange) implements Op {}
    private record IntervalSelect(int input, double[] thresholds, int[] branches) implements Op {
        private IntervalSelect {
            thresholds = thresholds.clone();
            branches = branches.clone();
        }
    }
    private record Noise(int profile, double xzScale, double yScale) implements Op {}
    private record Shift(int profile, ShiftKind kind) implements Op {}
    private record ShiftedNoise(int shiftX, int shiftY, int shiftZ, int profile, double xzScale, double yScale) implements Op {}
    private record Blended(int profile) implements Op {}

    private enum VisitState { VISITING, COMPLETE }

    private static final class Compiler {
        private final Limits limits;
        private final IdentityHashMap<DensityFunction, Integer> ids = new IdentityHashMap<>();
        private final IdentityHashMap<DensityFunction, VisitState> states = new IdentityHashMap<>();
        private final IdentityHashMap<Object, Integer> profileIds = new IdentityHashMap<>();
        private final List<Instruction> instructions = new ArrayList<>();
        private final List<NoiseProfile> profiles = new ArrayList<>();
        private int edges;

        private Compiler(final Limits limits) {
            this.limits = limits;
        }

        private Snapshot compile(final DensityFunction root) {
            int rootId = this.node(root, 0);
            if (this.instructions.size() != this.ids.size()) {
                throw new IllegalStateException("Density graph snapshot contains an incomplete node");
            }
            return new Snapshot(this.instructions, this.profiles, rootId, this.edges);
        }

        private int node(final DensityFunction function, final int depth) {
            if (function == null) throw this.unsupported("null density child");
            if (depth > this.limits.maxDepth) throw this.unsupported("density graph exceeds maximum depth " + this.limits.maxDepth);

            Integer known = this.ids.get(function);
            if (known != null) {
                if (this.states.get(function) == VisitState.VISITING) throw this.unsupported("cycle in density graph");
                return known;
            }
            if (this.instructions.size() >= this.limits.maxNodes) throw this.unsupported("density graph exceeds maximum node count " + this.limits.maxNodes);

            int id = this.instructions.size();
            this.ids.put(function, id);
            this.states.put(function, VisitState.VISITING);
            this.instructions.add(null);

            Op op = this.lower(function, depth);
            double minValue;
            double maxValue;
            try {
                minValue = function.minValue();
                maxValue = function.maxValue();
            } catch (RuntimeException failure) {
                throw this.unsupported("failed to read vanilla density bounds for " + function.getClass().getName(), failure);
            }

            this.instructions.set(id, new Instruction(op, minValue, maxValue));
            this.states.put(function, VisitState.COMPLETE);
            return id;
        }

        private Op lower(final DensityFunction function, final int depth) {
            Class<?> type = function.getClass();
            if (type.getNestHost() != DensityFunctions.class && type != BlendedNoise.class) {
                throw this.unsupported("unsupported density node class " + type.getName());
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

        private int child(final Object value, final int depth) {
            if (!(value instanceof DensityFunction child)) throw this.unsupported("density child is not a DensityFunction");
            if (this.edges >= this.limits.maxEdges) throw this.unsupported("density graph exceeds maximum edge count " + this.limits.maxEdges);
            this.edges++;
            return this.node(child, depth + 1);
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

    private record Alias(int input) implements Op {}

    private static Number number(final Object value, final String label) {
        if (!(value instanceof Number number)) throw new UnsupportedGraphException("invalid " + label + " component");
        return number;
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

    private static double sampleProfileExactly(final NoiseProfile profile, final double x, final double y, final double z) {
        VanillaNoiseBatch.NoiseSample sample = new VanillaNoiseBatch.NoiseSample(profile, x, y, z, 0.0, 0.0);
        int[] input = VanillaNoiseBatch.input(sample);
        return VanillaNoiseBatch.outputValue(VanillaNoiseBatch.reference(input), 0);
    }
}
