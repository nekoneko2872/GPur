package org.gpur.compute;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded workload and CPU reference for immutable vanilla noise profiles.
 * The wire data contains only initialized noise state and sample coordinates; it
 * never reaches a world, random source, or mutable noise object.
 */
public final class VanillaNoiseBatch {
    public static final int WORKLOAD = 5;
    public static final int HEADER_WORDS = 6;
    public static final int SAMPLE_WORDS = 11;
    public static final int MAX_SAMPLES = 65_536;
    public static final int MAX_PROFILE_RECORDS = 4_096;
    public static final int MAX_PROFILE_WORDS = 3_400_000;
    public static final int MAX_INPUT_WORDS = 4_194_304;
    public static final int MAX_OCTAVES = 64;

    private static final int IMPROVED = 1;
    private static final int PERLIN = 2;
    private static final int NORMAL = 3;
    private static final int BLENDED = 4;
    private static final int IMPROVED_WORDS = 72;
    private static final int PERLIN_HEADER_WORDS = 7;
    private static final int NORMAL_WORDS = 6;
    private static final int BLENDED_WORDS = 15;
    private static final int PERLIN_OCTAVE_WORDS = 3;
    private static final double MAX_ABS_COORDINATE = 1_000_000_000.0;
    private static final double MAX_ABS_SAMPLE_SCALE = 1_000_000.0;
    private static final double MIN_ABS_SAMPLE_SCALE = 1.0e-9;
    private static final double MAX_ABS_AMPLITUDE = 1.0e12;
    private static final double MAX_FREQUENCY_FACTOR = 4_294_967_296.0;
    private static final double NORMAL_INPUT_FACTOR = 1.0181268882175227;
    private static final double PERLIN_WRAP = 33_554_432.0;
    private static final double MAX_GRADIENT_Y_FLOOR = 2_147_483_000.0;

    private VanillaNoiseBatch() {}

    /** A snapshot of the initialized state needed by ImprovedNoise.noise. */
    public static final class ImprovedNoiseProfile implements NoiseProfile {
        private final double xo;
        private final double yo;
        private final double zo;
        private final byte[] permutation;

        public ImprovedNoiseProfile(double xo, double yo, double zo, byte[] permutation) {
            this.xo = xo;
            this.yo = yo;
            this.zo = zo;
            this.permutation = Objects.requireNonNull(permutation, "permutation").clone();
            if (this.permutation.length != 256) {
                throw new IllegalArgumentException("An ImprovedNoise permutation must contain 256 bytes");
            }
            if (!Double.isFinite(xo) || !Double.isFinite(yo) || !Double.isFinite(zo)
                || Math.abs(xo) > 256.0 || Math.abs(yo) > 256.0 || Math.abs(zo) > 256.0) {
                throw new IllegalArgumentException("ImprovedNoise offsets must be finite and within 256");
            }
            boolean[] seen = new boolean[256];
            for (byte value : this.permutation) {
                int unsigned = value & 0xff;
                if (seen[unsigned]) {
                    throw new IllegalArgumentException("ImprovedNoise permutation contains a duplicate value");
                }
                seen[unsigned] = true;
            }
        }

        public double xo() { return this.xo; }
        public double yo() { return this.yo; }
        public double zo() { return this.zo; }
        public byte[] permutation() { return this.permutation.clone(); }
    }

    /** Octaves are in PerlinNoise array order, from the lowest input frequency upward. */
    public static final class PerlinNoiseProfile implements NoiseProfile {
        private final ImprovedNoiseProfile[] noiseLevels;
        private final double[] amplitudes;
        private final double lowestFreqInputFactor;
        private final double lowestFreqValueFactor;

        public PerlinNoiseProfile(
            ImprovedNoiseProfile[] noiseLevels,
            double[] amplitudes,
            double lowestFreqInputFactor,
            double lowestFreqValueFactor
        ) {
            this.noiseLevels = Objects.requireNonNull(noiseLevels, "noiseLevels").clone();
            this.amplitudes = Objects.requireNonNull(amplitudes, "amplitudes").clone();
            this.lowestFreqInputFactor = lowestFreqInputFactor;
            this.lowestFreqValueFactor = lowestFreqValueFactor;
            if (this.noiseLevels.length < 1 || this.noiseLevels.length > MAX_OCTAVES
                || this.noiseLevels.length != this.amplitudes.length) {
                throw new IllegalArgumentException("PerlinNoise octave and amplitude arrays must have the same size in 1..64");
            }
            validatePositiveFactor(lowestFreqInputFactor, "lowestFreqInputFactor");
            validatePositiveFactor(lowestFreqValueFactor, "lowestFreqValueFactor");
            double frequency = lowestFreqInputFactor;
            for (int octave = 0; octave < this.amplitudes.length; octave++) {
                double amplitude = this.amplitudes[octave];
                if (!Double.isFinite(amplitude) || Math.abs(amplitude) > MAX_ABS_AMPLITUDE) {
                    throw new IllegalArgumentException("PerlinNoise amplitude is non-finite or out of range");
                }
                if ((this.noiseLevels[octave] == null) != (amplitude == 0.0)) {
                    throw new IllegalArgumentException("A missing PerlinNoise octave must have zero amplitude");
                }
                if (frequency > MAX_FREQUENCY_FACTOR) {
                    throw new IllegalArgumentException("PerlinNoise frequency exceeds the supported range");
                }
                frequency *= 2.0;
            }
        }

        public ImprovedNoiseProfile[] noiseLevels() { return this.noiseLevels.clone(); }
        public double[] amplitudes() { return this.amplitudes.clone(); }
        public double lowestFreqInputFactor() { return this.lowestFreqInputFactor; }
        public double lowestFreqValueFactor() { return this.lowestFreqValueFactor; }
    }

    /** A NormalNoise snapshot containing its two Perlin profiles and normalization factor. */
    public static final class NormalNoiseProfile implements NoiseProfile {
        private final PerlinNoiseProfile first;
        private final PerlinNoiseProfile second;
        private final double valueFactor;

        public NormalNoiseProfile(PerlinNoiseProfile first, PerlinNoiseProfile second, double valueFactor) {
            this.first = Objects.requireNonNull(first, "first");
            this.second = Objects.requireNonNull(second, "second");
            this.valueFactor = valueFactor;
            validatePositiveFactor(valueFactor, "valueFactor");
        }

        public PerlinNoiseProfile first() { return this.first; }
        public PerlinNoiseProfile second() { return this.second; }
        public double valueFactor() { return this.valueFactor; }
    }

    /** Immutable state used by BlendedNoise.compute, including the three reversed octave profiles. */
    public static final class BlendedNoiseProfile implements NoiseProfile {
        private final PerlinNoiseProfile minLimit;
        private final PerlinNoiseProfile maxLimit;
        private final PerlinNoiseProfile main;
        private final double xzMultiplier;
        private final double yMultiplier;
        private final double xzFactor;
        private final double yFactor;
        private final double smearScaleMultiplier;

        public BlendedNoiseProfile(
            PerlinNoiseProfile minLimit,
            PerlinNoiseProfile maxLimit,
            PerlinNoiseProfile main,
            double xzMultiplier,
            double yMultiplier,
            double xzFactor,
            double yFactor,
            double smearScaleMultiplier
        ) {
            this.minLimit = Objects.requireNonNull(minLimit, "minLimit");
            this.maxLimit = Objects.requireNonNull(maxLimit, "maxLimit");
            this.main = Objects.requireNonNull(main, "main");
            this.xzMultiplier = xzMultiplier;
            this.yMultiplier = yMultiplier;
            this.xzFactor = xzFactor;
            this.yFactor = yFactor;
            this.smearScaleMultiplier = smearScaleMultiplier;
            if (minLimit.noiseLevels.length != 16 || maxLimit.noiseLevels.length != 16 || main.noiseLevels.length != 8) {
                throw new IllegalArgumentException("BlendedNoise requires 16 min/max octaves and 8 main octaves");
            }
            validateBlendedParameters(xzMultiplier, yMultiplier, xzFactor, yFactor, smearScaleMultiplier);
        }

        public PerlinNoiseProfile minLimit() { return this.minLimit; }
        public PerlinNoiseProfile maxLimit() { return this.maxLimit; }
        public PerlinNoiseProfile main() { return this.main; }
        public double xzMultiplier() { return this.xzMultiplier; }
        public double yMultiplier() { return this.yMultiplier; }
        public double xzFactor() { return this.xzFactor; }
        public double yFactor() { return this.yFactor; }
        public double smearScaleMultiplier() { return this.smearScaleMultiplier; }
    }

    public sealed interface NoiseProfile permits ImprovedNoiseProfile, PerlinNoiseProfile, NormalNoiseProfile, BlendedNoiseProfile {}

    /** yScale and yFudge are used by ImprovedNoise and PerlinNoise; other profiles ignore them. */
    public record NoiseSample(
        NoiseProfile profile,
        double x,
        double y,
        double z,
        double yScale,
        double yFudge
    ) {
        public NoiseSample {
            Objects.requireNonNull(profile, "profile");
        }
    }

    /** Packs immutable profiles followed by samples. Offsets in the records are absolute word offsets. */
    public static int[] input(List<NoiseSample> samples) {
        Objects.requireNonNull(samples, "samples");
        if (samples.isEmpty() || samples.size() > MAX_SAMPLES) {
            throw new IllegalArgumentException("Noise batch requires 1..65536 samples");
        }

        IntBuilder profiles = new IntBuilder();
        IdentityHashMap<NoiseProfile, Integer> offsets = new IdentityHashMap<>();
        int[] sampleProfileOffsets = new int[samples.size()];
        int recordCount = 0;
        for (int i = 0; i < samples.size(); i++) {
            NoiseSample sample = Objects.requireNonNull(samples.get(i), "sample");
            validateSampleValues(sample);
            int beforeCount = offsets.size();
            sampleProfileOffsets[i] = appendProfile(sample.profile(), profiles, offsets);
            recordCount += offsets.size() - beforeCount;
            if (offsets.size() > MAX_PROFILE_RECORDS || profiles.size() > MAX_PROFILE_WORDS) {
                throw new IllegalArgumentException("Noise profile section exceeds its bounded size");
            }
        }
        long sampleOffset = (long)HEADER_WORDS + profiles.size();
        long totalWords = sampleOffset + (long)samples.size() * SAMPLE_WORDS;
        if (recordCount < 1 || recordCount > MAX_PROFILE_RECORDS || totalWords > MAX_INPUT_WORDS) {
            throw new IllegalArgumentException("Noise batch exceeds its bounded size");
        }

        int[] words = new int[(int)totalWords];
        words[0] = WORKLOAD;
        words[1] = samples.size();
        words[2] = recordCount;
        words[3] = profiles.size();
        words[4] = (int)sampleOffset;
        words[5] = SAMPLE_WORDS;
        profiles.copyTo(words, HEADER_WORDS);
        int offset = (int)sampleOffset;
        for (int i = 0; i < samples.size(); i++) {
            NoiseSample sample = samples.get(i);
            words[offset] = sampleProfileOffsets[i];
            putDouble(words, offset + 1, sample.x());
            putDouble(words, offset + 3, sample.y());
            putDouble(words, offset + 5, sample.z());
            putDouble(words, offset + 7, sample.yScale());
            putDouble(words, offset + 9, sample.yFudge());
            offset += SAMPLE_WORDS;
        }
        validate(words);
        return words;
    }

    public static int[] input(NoiseSample... samples) {
        Objects.requireNonNull(samples, "samples");
        return input(Arrays.asList(samples));
    }

    public static void validate(int[] input) {
        Objects.requireNonNull(input, "input");
        if (input.length < HEADER_WORDS || input.length > MAX_INPUT_WORDS || input[0] != WORKLOAD) {
            throw new IllegalArgumentException("Invalid vanilla noise batch header");
        }
        int sampleCount = input[1];
        int recordCount = input[2];
        int profileWords = input[3];
        int sampleOffset = input[4];
        if (sampleCount < 1 || sampleCount > MAX_SAMPLES
            || recordCount < 1 || recordCount > MAX_PROFILE_RECORDS
            || profileWords < 1 || profileWords > MAX_PROFILE_WORDS
            || input[5] != SAMPLE_WORDS
            || sampleOffset != HEADER_WORDS + profileWords
            || (long)sampleOffset + (long)sampleCount * SAMPLE_WORDS != input.length) {
            throw new IllegalArgumentException("Invalid vanilla noise batch dimensions");
        }

        Map<Integer, Integer> profileTypes = new java.util.HashMap<>();
        int offset = HEADER_WORDS;
        int end = sampleOffset;
        int records = 0;
        while (offset < end) {
            if (end - offset < 2) throw new IllegalArgumentException("Truncated vanilla noise profile header");
            int type = input[offset];
            int words = input[offset + 1];
            if (words < 2 || (long)offset + words > end) {
                throw new IllegalArgumentException("Invalid vanilla noise profile record length");
            }
            validateProfileRecord(input, offset, type, words, profileTypes);
            profileTypes.put(offset, type);
            offset += words;
            records++;
        }
        if (offset != end || records != recordCount) {
            throw new IllegalArgumentException("Vanilla noise profile count or section length does not match");
        }

        for (int i = 0; i < sampleCount; i++) {
            int sample = sampleOffset + i * SAMPLE_WORDS;
            Integer type = profileTypes.get(input[sample]);
            if (type == null) throw new IllegalArgumentException("Noise sample refers to an unknown profile offset");
            double x = getDouble(input, sample + 1);
            double y = getDouble(input, sample + 3);
            double z = getDouble(input, sample + 5);
            double yScale = getDouble(input, sample + 7);
            double yFudge = getDouble(input, sample + 9);
            if (!boundedCoordinate(x) || !boundedCoordinate(y) || !boundedCoordinate(z)
                || !boundedSampleScale(yScale) || !boundedSampleScale(yFudge)) {
                throw new IllegalArgumentException("Noise sample coordinates or scales are non-finite or out of range");
            }
            validateScaleReachability(input, input[sample], yScale, profileTypes);
        }
    }

    public static int outputWords(int[] input) {
        validate(input);
        return Math.multiplyExact(input[1], 2);
    }

    /** Returns one raw IEEE-754 double per sample, packed low word first. */
    public static int[] reference(int[] input) {
        validate(input);
        int[] output = new int[outputWordsUnchecked(input[1])];
        int sampleOffset = input[4];
        for (int i = 0; i < input[1]; i++) {
            int sample = sampleOffset + i * SAMPLE_WORDS;
            double value = evaluate(
                input,
                input[sample],
                getDouble(input, sample + 1),
                getDouble(input, sample + 3),
                getDouble(input, sample + 5),
                getDouble(input, sample + 7),
                getDouble(input, sample + 9)
            );
            putDouble(output, i * 2, value);
        }
        return output;
    }

    public static double outputValue(int[] output, int sampleIndex) {
        Objects.requireNonNull(output, "output");
        if (output.length == 0 || (output.length & 1) != 0 || sampleIndex < 0 || sampleIndex >= output.length / 2) {
            throw new IllegalArgumentException("Noise output sample index is out of range");
        }
        return getDouble(output, sampleIndex * 2);
    }

    private static int appendProfile(
        NoiseProfile profile,
        IntBuilder words,
        IdentityHashMap<NoiseProfile, Integer> offsets
    ) {
        Integer existing = offsets.get(profile);
        if (existing != null) return existing;
        if (offsets.size() >= MAX_PROFILE_RECORDS) {
            throw new IllegalArgumentException("Noise profile section exceeds its bounded record count");
        }

        int[] childOffsets;
        int type;
        int start;
        if (profile instanceof ImprovedNoiseProfile improved) {
            type = IMPROVED;
            start = HEADER_WORDS + words.size();
            offsets.put(profile, start);
            words.add(type);
            words.add(IMPROVED_WORDS);
            words.addDouble(improved.xo);
            words.addDouble(improved.yo);
            words.addDouble(improved.zo);
            for (int i = 0; i < 256; i += 4) {
                int packed = (improved.permutation[i] & 0xff)
                    | ((improved.permutation[i + 1] & 0xff) << 8)
                    | ((improved.permutation[i + 2] & 0xff) << 16)
                    | ((improved.permutation[i + 3] & 0xff) << 24);
                words.add(packed);
            }
            return start;
        }
        if (profile instanceof PerlinNoiseProfile perlin) {
            type = PERLIN;
            ImprovedNoiseProfile[] levels = perlin.noiseLevels;
            childOffsets = new int[levels.length];
            for (int i = 0; i < levels.length; i++) {
                childOffsets[i] = levels[i] == null ? -1 : appendProfile(levels[i], words, offsets);
            }
            start = HEADER_WORDS + words.size();
            offsets.put(profile, start);
            words.add(type);
            int wordCount = PERLIN_HEADER_WORDS + PERLIN_OCTAVE_WORDS * levels.length;
            words.add(wordCount);
            words.add(levels.length);
            words.addDouble(perlin.lowestFreqInputFactor);
            words.addDouble(perlin.lowestFreqValueFactor);
            for (int i = 0; i < levels.length; i++) {
                words.addDouble(perlin.amplitudes[i]);
                words.add(childOffsets[i]);
            }
            return start;
        }
        if (profile instanceof NormalNoiseProfile normal) {
            type = NORMAL;
            int first = appendProfile(normal.first, words, offsets);
            int second = appendProfile(normal.second, words, offsets);
            start = HEADER_WORDS + words.size();
            offsets.put(profile, start);
            words.add(type);
            words.add(NORMAL_WORDS);
            words.add(first);
            words.add(second);
            words.addDouble(normal.valueFactor);
            return start;
        }
        if (profile instanceof BlendedNoiseProfile blended) {
            int minLimit = appendProfile(blended.minLimit, words, offsets);
            int maxLimit = appendProfile(blended.maxLimit, words, offsets);
            int main = appendProfile(blended.main, words, offsets);
            start = HEADER_WORDS + words.size();
            offsets.put(profile, start);
            words.add(BLENDED);
            words.add(BLENDED_WORDS);
            words.add(minLimit);
            words.add(maxLimit);
            words.add(main);
            words.addDouble(blended.xzMultiplier);
            words.addDouble(blended.yMultiplier);
            words.addDouble(blended.xzFactor);
            words.addDouble(blended.yFactor);
            words.addDouble(blended.smearScaleMultiplier);
            return start;
        }
        throw new IllegalArgumentException("Unsupported vanilla noise profile type");
    }

    private static void validateProfileRecord(
        int[] input,
        int offset,
        int type,
        int words,
        Map<Integer, Integer> profileTypes
    ) {
        if (type == IMPROVED) {
            if (words != IMPROVED_WORDS) throw new IllegalArgumentException("Malformed ImprovedNoise profile size");
            for (int i = 0; i < 3; i++) {
                double component = getDouble(input, offset + 2 + i * 2);
                if (!Double.isFinite(component) || Math.abs(component) > 256.0) {
                    throw new IllegalArgumentException("Invalid ImprovedNoise offset");
                }
            }
            boolean[] seen = new boolean[256];
            for (int i = 0; i < 256; i++) {
                int packed = input[offset + 8 + i / 4];
                int value = (packed >>> ((i & 3) * 8)) & 0xff;
                if (seen[value]) throw new IllegalArgumentException("ImprovedNoise permutation is not unique");
                seen[value] = true;
            }
            return;
        }
        if (type == PERLIN) {
            if (words < PERLIN_HEADER_WORDS + PERLIN_OCTAVE_WORDS
                || input[offset + 2] < 1 || input[offset + 2] > MAX_OCTAVES
                || words != PERLIN_HEADER_WORDS + PERLIN_OCTAVE_WORDS * input[offset + 2]) {
                throw new IllegalArgumentException("Malformed PerlinNoise profile size");
            }
            double inputFactor = getDouble(input, offset + 3);
            double valueFactor = getDouble(input, offset + 5);
            validatePositiveFactor(inputFactor, "lowestFreqInputFactor");
            validatePositiveFactor(valueFactor, "lowestFreqValueFactor");
            double frequency = inputFactor;
            int count = input[offset + 2];
            for (int octave = 0; octave < count; octave++) {
                int octaveOffset = offset + PERLIN_HEADER_WORDS + octave * PERLIN_OCTAVE_WORDS;
                double amplitude = getDouble(input, octaveOffset);
                int noiseOffset = input[octaveOffset + 2];
                if (!Double.isFinite(amplitude) || Math.abs(amplitude) > MAX_ABS_AMPLITUDE
                    || frequency > MAX_FREQUENCY_FACTOR) {
                    throw new IllegalArgumentException("Invalid PerlinNoise amplitude or frequency");
                }
                if (noiseOffset == -1) {
                    if (amplitude != 0.0) throw new IllegalArgumentException("Missing PerlinNoise octave has non-zero amplitude");
                } else if (amplitude == 0.0 || noiseOffset >= offset || profileTypes.get(noiseOffset) == null
                    || profileTypes.get(noiseOffset) != IMPROVED) {
                    throw new IllegalArgumentException("PerlinNoise octave refers to an invalid ImprovedNoise profile");
                }
                frequency *= 2.0;
            }
            return;
        }
        if (type == NORMAL) {
            if (words != NORMAL_WORDS) throw new IllegalArgumentException("Malformed NormalNoise profile size");
            int first = input[offset + 2];
            int second = input[offset + 3];
            double valueFactor = getDouble(input, offset + 4);
            if (first >= offset || second >= offset || profileTypes.get(first) == null || profileTypes.get(second) == null
                || profileTypes.get(first) != PERLIN || profileTypes.get(second) != PERLIN) {
                throw new IllegalArgumentException("NormalNoise references invalid PerlinNoise profiles");
            }
            validatePositiveFactor(valueFactor, "valueFactor");
            return;
        }
        if (type == BLENDED) {
            if (words != BLENDED_WORDS) throw new IllegalArgumentException("Malformed BlendedNoise profile size");
            int minLimit = input[offset + 2];
            int maxLimit = input[offset + 3];
            int main = input[offset + 4];
            if (minLimit >= offset || maxLimit >= offset || main >= offset
                || profileTypes.get(minLimit) == null || profileTypes.get(maxLimit) == null || profileTypes.get(main) == null
                || profileTypes.get(minLimit) != PERLIN || profileTypes.get(maxLimit) != PERLIN || profileTypes.get(main) != PERLIN) {
                throw new IllegalArgumentException("BlendedNoise references invalid PerlinNoise profiles");
            }
            if (input[minLimit + 2] != 16 || input[maxLimit + 2] != 16 || input[main + 2] != 8) {
                throw new IllegalArgumentException("BlendedNoise octave profile lengths are invalid");
            }
            validateBlendedParameters(
                getDouble(input, offset + 5),
                getDouble(input, offset + 7),
                getDouble(input, offset + 9),
                getDouble(input, offset + 11),
                getDouble(input, offset + 13)
            );
            return;
        }
        throw new IllegalArgumentException("Unknown vanilla noise profile type " + type);
    }

    private static void validateScaleReachability(int[] input, int root, double yScale, Map<Integer, Integer> types) {
        if (yScale == 0.0) return;
        Integer type = types.get(root);
        if (type == IMPROVED) {
            requireFloorableScale(yScale);
        } else if (type == PERLIN) {
            validatePerlinScaleReachability(input, root, yScale);
        } else if (type == NORMAL || type == BLENDED) {
            // These profiles ignore the ImprovedNoise vertical smear arguments.
        }
    }

    private static void validatePerlinScaleReachability(int[] input, int offset, double yScale) {
        int count = input[offset + 2];
        double frequency = getDouble(input, offset + 3);
        for (int octave = 0; octave < count; octave++) {
            int octaveOffset = offset + PERLIN_HEADER_WORDS + octave * PERLIN_OCTAVE_WORDS;
            if (input[octaveOffset + 2] != -1) {
                double octaveScale = yScale * frequency;
                if (octaveScale != 0.0) requireFloorableScale(octaveScale);
            }
            frequency *= 2.0;
        }
    }

    private static void requireFloorableScale(double scale) {
        double maximumRatio = 1.0 / Math.abs(scale) + 1.0;
        if (!Double.isFinite(maximumRatio) || maximumRatio > MAX_GRADIENT_Y_FLOOR) {
            throw new IllegalArgumentException("Noise yScale is too small for the bounded integer floor path");
        }
    }

    private static double evaluate(int[] input, int profileOffset, double x, double y, double z, double yScale, double yFudge) {
        int type = input[profileOffset];
        if (type == IMPROVED) return improved(input, profileOffset, x, y, z, yScale, yFudge);
        if (type == PERLIN) return perlin(input, profileOffset, x, y, z, yScale, yFudge);
        if (type == BLENDED) return blended(input, profileOffset, x, y, z);
        double first = perlin(input, input[profileOffset + 2], x, y, z, 0.0, 0.0);
        double scaledX = x * NORMAL_INPUT_FACTOR;
        double scaledY = y * NORMAL_INPUT_FACTOR;
        double scaledZ = z * NORMAL_INPUT_FACTOR;
        double second = perlin(input, input[profileOffset + 3], scaledX, scaledY, scaledZ, 0.0, 0.0);
        double sum = first + second;
        double valueFactor = getDouble(input, profileOffset + 4);
        return sum * valueFactor;
    }

    private static double perlin(int[] input, int profileOffset, double x, double y, double z, double yScale, double yFudge) {
        int count = input[profileOffset + 2];
        double value = 0.0;
        double frequency = getDouble(input, profileOffset + 3);
        double valueFactor = getDouble(input, profileOffset + 5);
        for (int octave = 0; octave < count; octave++) {
            int octaveOffset = profileOffset + PERLIN_HEADER_WORDS + octave * PERLIN_OCTAVE_WORDS;
            int noiseOffset = input[octaveOffset + 2];
            if (noiseOffset != -1) {
                double noiseValue = improved(
                    input,
                    noiseOffset,
                    wrapPerlin(x * frequency),
                    wrapPerlin(y * frequency),
                    wrapPerlin(z * frequency),
                    yScale * frequency,
                    yFudge * frequency
                );
                double weighted = getDouble(input, octaveOffset) * noiseValue;
                weighted = weighted * valueFactor;
                value = value + weighted;
            }
            frequency = frequency * 2.0;
            valueFactor = valueFactor / 2.0;
        }
        return value;
    }

    private static double blended(int[] input, int profileOffset, double x, double y, double z) {
        int minLimitOffset = input[profileOffset + 2];
        int maxLimitOffset = input[profileOffset + 3];
        int mainOffset = input[profileOffset + 4];
        double xzMultiplier = getDouble(input, profileOffset + 5);
        double yMultiplier = getDouble(input, profileOffset + 7);
        double xzFactor = getDouble(input, profileOffset + 9);
        double yFactor = getDouble(input, profileOffset + 11);
        double smearScaleMultiplier = getDouble(input, profileOffset + 13);
        double limitX = x * xzMultiplier;
        double limitY = y * yMultiplier;
        double limitZ = z * xzMultiplier;
        double mainX = limitX / xzFactor;
        double mainY = limitY / yFactor;
        double mainZ = limitZ / xzFactor;
        double limitSmear = yMultiplier * smearScaleMultiplier;
        double mainSmear = limitSmear / yFactor;
        double blendMin = 0.0;
        double blendMax = 0.0;
        double mainNoiseValue = 0.0;
        double pow = 1.0;

        for (int i = 0; i < 8; i++) {
            int noiseOffset = octaveNoiseOffset(input, mainOffset, i);
            if (noiseOffset != -1) {
                double noiseValue = improved(
                    input,
                    noiseOffset,
                    wrapPerlin(mainX * pow),
                    wrapPerlin(mainY * pow),
                    wrapPerlin(mainZ * pow),
                    mainSmear * pow,
                    mainY * pow
                );
                mainNoiseValue = mainNoiseValue + noiseValue / pow;
            }
            pow = pow / 2.0;
        }

        double factor = (mainNoiseValue / 10.0 + 1.0) / 2.0;
        boolean isMax = factor >= 1.0;
        boolean isMin = factor <= 0.0;
        pow = 1.0;
        for (int i = 0; i < 16; i++) {
            double wx = wrapPerlin(limitX * pow);
            double wy = wrapPerlin(limitY * pow);
            double wz = wrapPerlin(limitZ * pow);
            double yScalePow = limitSmear * pow;
            if (!isMax) {
                int noiseOffset = octaveNoiseOffset(input, minLimitOffset, i);
                if (noiseOffset != -1) {
                    double noiseValue = improved(input, noiseOffset, wx, wy, wz, yScalePow, limitY * pow);
                    blendMin = blendMin + noiseValue / pow;
                }
            }
            if (!isMin) {
                int noiseOffset = octaveNoiseOffset(input, maxLimitOffset, i);
                if (noiseOffset != -1) {
                    double noiseValue = improved(input, noiseOffset, wx, wy, wz, yScalePow, limitY * pow);
                    blendMax = blendMax + noiseValue / pow;
                }
            }
            pow = pow / 2.0;
        }

        double min = blendMin / 512.0;
        double max = blendMax / 512.0;
        double clamped;
        if (factor < 0.0) {
            clamped = min;
        } else if (factor > 1.0) {
            clamped = max;
        } else {
            clamped = lerp(factor, min, max);
        }
        return clamped / 128.0;
    }

    private static int octaveNoiseOffset(int[] input, int perlinOffset, int octave) {
        int count = input[perlinOffset + 2];
        int arrayIndex = count - 1 - octave;
        if (arrayIndex < 0) return -1;
        return input[perlinOffset + PERLIN_HEADER_WORDS + arrayIndex * PERLIN_OCTAVE_WORDS + 2];
    }

    private static double improved(
        int[] input,
        int profileOffset,
        double sampleX,
        double sampleY,
        double sampleZ,
        double yScale,
        double yFudge
    ) {
        double x = sampleX + getDouble(input, profileOffset + 2);
        double y = sampleY + getDouble(input, profileOffset + 4);
        double z = sampleZ + getDouble(input, profileOffset + 6);
        int xf = (int)Math.floor(x);
        int yf = (int)Math.floor(y);
        int zf = (int)Math.floor(z);
        double xr = x - xf;
        double yr = y - yf;
        double zr = z - zf;
        double yrFudge;
        if (yScale != 0.0) {
            double fudgeLimit;
            if (yFudge >= 0.0 && yFudge < yr) {
                fudgeLimit = yFudge;
            } else {
                fudgeLimit = yr;
            }
            yrFudge = Math.floor(fudgeLimit / yScale + 1.0E-7F) * yScale;
        } else {
            yrFudge = 0.0;
        }
        return sampleAndLerp(input, profileOffset, xf, yf, zf, xr, yr - yrFudge, zr, yr);
    }

    private static double sampleAndLerp(
        int[] input,
        int profileOffset,
        int x,
        int y,
        int z,
        double xr,
        double yr,
        double zr,
        double yrOriginal
    ) {
        int x0 = permutation(input, profileOffset, x);
        int x1 = permutation(input, profileOffset, x + 1);
        int xy00 = permutation(input, profileOffset, x0 + y);
        int xy01 = permutation(input, profileOffset, x0 + y + 1);
        int xy10 = permutation(input, profileOffset, x1 + y);
        int xy11 = permutation(input, profileOffset, x1 + y + 1);
        double d000 = gradientDot(input, profileOffset, permutation(input, profileOffset, xy00 + z), xr, yr, zr);
        double d100 = gradientDot(input, profileOffset, permutation(input, profileOffset, xy10 + z), xr - 1.0, yr, zr);
        double d010 = gradientDot(input, profileOffset, permutation(input, profileOffset, xy01 + z), xr, yr - 1.0, zr);
        double d110 = gradientDot(input, profileOffset, permutation(input, profileOffset, xy11 + z), xr - 1.0, yr - 1.0, zr);
        double d001 = gradientDot(input, profileOffset, permutation(input, profileOffset, xy00 + z + 1), xr, yr, zr - 1.0);
        double d101 = gradientDot(input, profileOffset, permutation(input, profileOffset, xy10 + z + 1), xr - 1.0, yr, zr - 1.0);
        double d011 = gradientDot(input, profileOffset, permutation(input, profileOffset, xy01 + z + 1), xr, yr - 1.0, zr - 1.0);
        double d111 = gradientDot(input, profileOffset, permutation(input, profileOffset, xy11 + z + 1), xr - 1.0, yr - 1.0, zr - 1.0);
        double xAlpha = smoothstep(xr);
        double yAlpha = smoothstep(yrOriginal);
        double zAlpha = smoothstep(zr);
        return lerp3(xAlpha, yAlpha, zAlpha, d000, d100, d010, d110, d001, d101, d011, d111);
    }

    private static int permutation(int[] input, int profileOffset, int index) {
        int wrapped = index & 0xff;
        int packed = input[profileOffset + 8 + (wrapped >>> 2)];
        return (packed >>> ((wrapped & 3) * 8)) & 0xff;
    }

    private static double gradientDot(int[] input, int profileOffset, int hash, double x, double y, double z) {
        int gradient = hash & 15;
        double xPart = gradientX(gradient) * x;
        double yPart = gradientY(gradient) * y;
        double xy = xPart + yPart;
        double zPart = gradientZ(gradient) * z;
        return xy + zPart;
    }

    private static int gradientX(int gradient) {
        return switch (gradient) {
            case 0, 2, 4, 6, 12 -> 1;
            case 1, 3, 5, 7, 14 -> -1;
            default -> 0;
        };
    }

    private static int gradientY(int gradient) {
        return switch (gradient) {
            case 0, 1, 8, 10, 12, 14 -> 1;
            case 2, 3, 9, 11, 13, 15 -> -1;
            default -> 0;
        };
    }

    private static int gradientZ(int gradient) {
        return switch (gradient) {
            case 4, 5, 8, 9, 13 -> 1;
            case 6, 7, 10, 11, 15 -> -1;
            default -> 0;
        };
    }

    private static double smoothstep(double value) {
        double squared = value * value;
        double cubed = squared * value;
        double sixX = value * 6.0;
        double inner = sixX - 15.0;
        double scaledInner = value * inner;
        double curve = scaledInner + 10.0;
        return cubed * curve;
    }

    private static double lerp(double alpha, double from, double to) {
        double difference = to - from;
        double scaled = alpha * difference;
        return from + scaled;
    }

    private static double lerp2(double x, double y, double n00, double n10, double n01, double n11) {
        double lower = lerp(x, n00, n10);
        double upper = lerp(x, n01, n11);
        return lerp(y, lower, upper);
    }

    private static double lerp3(
        double x,
        double y,
        double z,
        double n000,
        double n100,
        double n010,
        double n110,
        double n001,
        double n101,
        double n011,
        double n111
    ) {
        double lower = lerp2(x, y, n000, n100, n010, n110);
        double upper = lerp2(x, y, n001, n101, n011, n111);
        return lerp(z, lower, upper);
    }

    private static double wrapPerlin(double value) {
        double turns = Math.floor(value / PERLIN_WRAP + 0.5);
        double scaledTurns = turns * PERLIN_WRAP;
        return value - scaledTurns;
    }

    private static void validateSampleValues(NoiseSample sample) {
        if (!boundedCoordinate(sample.x()) || !boundedCoordinate(sample.y()) || !boundedCoordinate(sample.z())
            || !boundedSampleScale(sample.yScale()) || !boundedSampleScale(sample.yFudge())) {
            throw new IllegalArgumentException("Noise sample coordinates or scales are non-finite or out of range");
        }
    }

    private static boolean boundedCoordinate(double value) {
        return Double.isFinite(value) && Math.abs(value) <= MAX_ABS_COORDINATE;
    }

    private static boolean boundedSampleScale(double value) {
        return Double.isFinite(value) && Math.abs(value) <= MAX_ABS_SAMPLE_SCALE
            && (value == 0.0 || Math.abs(value) >= MIN_ABS_SAMPLE_SCALE);
    }

    private static void validatePositiveFactor(double value, String name) {
        if (!Double.isFinite(value) || value <= 0.0 || value > MAX_ABS_AMPLITUDE) {
            throw new IllegalArgumentException(name + " must be finite, positive, and bounded");
        }
    }

    private static void validateBlendedParameters(
        double xzMultiplier,
        double yMultiplier,
        double xzFactor,
        double yFactor,
        double smearScaleMultiplier
    ) {
        requireRange(xzMultiplier, 0.684412, 684_412.0, "xzMultiplier");
        requireRange(yMultiplier, 0.684412, 684_412.0, "yMultiplier");
        requireRange(xzFactor, 0.001, 1_000.0, "xzFactor");
        requireRange(yFactor, 0.001, 1_000.0, "yFactor");
        requireRange(smearScaleMultiplier, 1.0, 8.0, "smearScaleMultiplier");
    }

    private static void requireRange(double value, double min, double max, String name) {
        if (!Double.isFinite(value) || value < min || value > max) {
            throw new IllegalArgumentException(name + " is outside the vanilla BlendedNoise codec range");
        }
    }

    private static int outputWordsUnchecked(int sampleCount) {
        return Math.multiplyExact(sampleCount, 2);
    }

    public static double getDouble(int[] words, int offset) {
        Objects.requireNonNull(words, "words");
        if (offset < 0 || offset + 1 >= words.length) throw new IndexOutOfBoundsException("double word offset " + offset);
        return Double.longBitsToDouble(Integer.toUnsignedLong(words[offset]) | ((long)words[offset + 1] << 32));
    }

    private static void putDouble(int[] words, int offset, double value) {
        long bits = Double.doubleToRawLongBits(value);
        words[offset] = (int)bits;
        words[offset + 1] = (int)(bits >>> 32);
    }

    private static final class IntBuilder {
        private int[] values = new int[128];
        private int size;

        private int size() { return this.size; }

        private void add(int value) {
            if (this.size >= MAX_PROFILE_WORDS) {
                throw new IllegalArgumentException("Noise profile section exceeds its bounded word count");
            }
            if (this.size == this.values.length) this.values = Arrays.copyOf(this.values, this.values.length * 2);
            this.values[this.size++] = value;
        }

        private void addDouble(double value) {
            long bits = Double.doubleToRawLongBits(value);
            this.add((int)bits);
            this.add((int)(bits >>> 32));
        }

        private void copyTo(int[] destination, int offset) {
            System.arraycopy(this.values, 0, destination, offset, this.size);
        }
    }
}
