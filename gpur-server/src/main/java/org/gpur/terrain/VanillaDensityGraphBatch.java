package org.gpur.terrain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.util.Mth;
import org.gpur.compute.VanillaNoiseBatch;
import org.gpur.compute.VanillaNoiseBatch.BlendedNoiseProfile;
import org.gpur.compute.VanillaNoiseBatch.ImprovedNoiseProfile;
import org.gpur.compute.VanillaNoiseBatch.NoiseProfile;
import org.gpur.compute.VanillaNoiseBatch.NoiseSample;
import org.gpur.compute.VanillaNoiseBatch.NormalNoiseProfile;
import org.gpur.compute.VanillaNoiseBatch.PerlinNoiseProfile;

/**
 * Versioned workload-7 wire exporter, validator and CPU reference for an immutable density DAG.
 * This class does not connect to NoiseChunk or install graph results into mutable noise caches.
 */
public final class VanillaDensityGraphBatch {
    public static final int WORKLOAD = 7;
    public static final int WIRE_VERSION = 1;
    public static final int HEADER_WORDS = 24;
    public static final int NODE_WORDS = 16;
    public static final int SAMPLE_WORDS = 3;
    public static final int OUTPUT_WORDS_PER_SAMPLE = 2;
    public static final int MAX_NODES = 8_192;
    public static final int MAX_EDGES = 32_768;
    public static final int MAX_DEPTH = 256;
    public static final int MAX_PROFILE_ROOTS = 2_048;
    public static final int MAX_INTERVAL_BRANCHES = 1_024;
    public static final int MAX_PROFILE_RECORDS = VanillaNoiseBatch.MAX_PROFILE_RECORDS;
    public static final int MAX_PROFILE_WORDS = VanillaNoiseBatch.MAX_PROFILE_WORDS;
    public static final int MAX_SAMPLES = VanillaNoiseBatch.MAX_SAMPLES;
    public static final int MAX_INPUT_WORDS = VanillaNoiseBatch.MAX_INPUT_WORDS;
    public static final int MAX_NODE_WORK = 16_384;
    public static final int MAX_BATCH_WORK = 8_388_608;
    public static final int MAX_BLOCK_COORDINATE = 30_000_000;
    public static final double MAX_NOISE_COORDINATE = 1_000_000_000.0;

    private static final int OP_CONSTANT = 0;
    private static final int OP_ALIAS = 1;
    private static final int OP_Y_GRADIENT = 2;
    private static final int OP_CLAMP = 3;
    private static final int OP_MAPPED = 4;
    private static final int OP_BINARY = 5;
    private static final int OP_MUL_OR_ADD = 6;
    private static final int OP_RANGE_CHOICE = 7;
    private static final int OP_INTERVAL_SELECT = 8;
    private static final int OP_NOISE = 9;
    private static final int OP_SHIFT = 10;
    private static final int OP_SHIFTED_NOISE = 11;
    private static final int OP_BLENDED = 12;

    private static final int PROFILE_IMPROVED = 1;
    private static final int PROFILE_PERLIN = 2;
    private static final int PROFILE_NORMAL = 3;
    private static final int PROFILE_BLENDED = 4;
    private static final int W5_HEADER_WORDS = 6;
    private static final int W5_SAMPLE_WORDS = VanillaNoiseBatch.SAMPLE_WORDS;
    private static final int W5_PROFILE_START = W5_HEADER_WORDS;
    private static final int W5_PROFILE_SAMPLE_OFFSET = 1;
    private static final int W5_RECORD_IMPROVED_WORDS = 72;
    private static final int W5_RECORD_PERLIN_HEADER_WORDS = 7;
    private static final int W5_RECORD_NORMAL_WORDS = 6;
    private static final int W5_RECORD_BLENDED_WORDS = 15;
    private static final int W5_RECORD_PERLIN_OCTAVE_WORDS = 3;
    private static final long WORK_SATURATION = (long)MAX_BATCH_WORK + 1L;

    private VanillaDensityGraphBatch() {}

    /** Integer block coordinates sampled by the root for one W7 output. */
    public record Sample(int x, int y, int z) {}

    /**
     * Serializes a complete snapshot and sample list. Shared snapshot nodes remain shared by
     * identity; IDs are remapped to deterministic postorder so every child ID precedes its parent.
     */
    public static int[] input(final VanillaDensityGraph.Snapshot snapshot, final List<Sample> samples) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(samples, "samples");
        if (samples.isEmpty() || samples.size() > MAX_SAMPLES) {
            throw invalid("density batch requires 1..65536 samples");
        }
        if (snapshot.nodeCount() < 1 || snapshot.nodeCount() > MAX_NODES
            || snapshot.edgeCount() > MAX_EDGES || snapshot.noiseProfileCount() > MAX_PROFILE_ROOTS) {
            throw invalid("density snapshot exceeds W7 graph limits");
        }
        for (Sample sample : samples) {
            Objects.requireNonNull(sample, "sample");
            checkBlockCoordinate(sample.x());
            checkBlockCoordinate(sample.y());
            checkBlockCoordinate(sample.z());
        }

        List<Integer> postorder = postorder(snapshot);
        int nodeCount = postorder.size();
        int[] remap = new int[nodeCount];
        for (int id = 0; id < nodeCount; id++) remap[postorder.get(id)] = id;

        long thresholdWords = 0L;
        int countedEdges = 0;
        for (int oldId : postorder) {
            VanillaDensityGraph.Op op = snapshot.instructions.get(oldId).op();
            countedEdges = Math.addExact(countedEdges, childCount(op));
            if (op instanceof VanillaDensityGraph.IntervalSelect interval) {
                thresholdWords = Math.addExact(thresholdWords, Math.multiplyExact((long)interval.thresholds().length, 2L));
            }
        }
        if (countedEdges != snapshot.edgeCount() || countedEdges > MAX_EDGES || thresholdWords > Integer.MAX_VALUE) {
            throw invalid("density snapshot edge or interval count is inconsistent");
        }

        int edgeCount = countedEdges;
        int auxWords = Math.toIntExact(Math.addExact(Math.multiplyExact((long)nodeCount, 4L), thresholdWords));
        int[] nodeRecords = new int[Math.multiplyExact(nodeCount, NODE_WORDS)];
        int[] edgeWords = new int[edgeCount];
        int[] aux = new int[auxWords];
        int edgeCursor = 0;
        int auxCursor = nodeCount * 4;
        for (int id = 0; id < nodeCount; id++) {
            int oldId = postorder.get(id);
            VanillaDensityGraph.Instruction instruction = snapshot.instructions.get(oldId);
            putDouble(aux, id * 4, instruction.minValue());
            putDouble(aux, id * 4 + 2, instruction.maxValue());
            edgeCursor = encodeNode(nodeRecords, edgeWords, aux, id, edgeCursor, auxCursor,
                instruction.op(), remap);
            if (instruction.op() instanceof VanillaDensityGraph.IntervalSelect interval) {
                auxCursor += interval.thresholds().length * 2;
            }
        }
        if (edgeCursor != edgeCount || auxCursor != auxWords) {
            throw new IllegalStateException("density wire section sizing changed during export");
        }

        int[] noiseSection = new int[0];
        int[] profileRoots = new int[0];
        int profileRecordCount = 0;
        if (!snapshot.profiles.isEmpty()) {
            List<NoiseSample> profileSamples = new ArrayList<>(snapshot.profiles.size());
            for (NoiseProfile profile : snapshot.profiles) {
                profileSamples.add(new NoiseSample(profile, 0.0, 0.0, 0.0, 0.0, 0.0));
            }
            int[] noiseInput = VanillaNoiseBatch.input(profileSamples);
            profileRecordCount = noiseInput[2];
            int profileWordCount = noiseInput[3];
            noiseSection = Arrays.copyOfRange(noiseInput, W5_PROFILE_START, W5_PROFILE_START + profileWordCount);
            profileRoots = new int[snapshot.profiles.size()];
            int noiseSampleOffset = noiseInput[4];
            for (int profile = 0; profile < profileRoots.length; profile++) {
                profileRoots[profile] = noiseInput[noiseSampleOffset + profile * W5_SAMPLE_WORDS];
            }
        }
        if (profileRoots.length > MAX_PROFILE_ROOTS || noiseSection.length > MAX_PROFILE_WORDS
            || profileRecordCount > MAX_PROFILE_RECORDS) {
            throw invalid("density noise profiles exceed W7 limits");
        }

        long nodeOffset = HEADER_WORDS;
        long edgeOffset = nodeOffset + nodeRecords.length;
        long auxOffset = edgeOffset + edgeWords.length;
        long rootTableOffset = auxOffset + aux.length;
        long profileSectionOffset = rootTableOffset + profileRoots.length;
        long sampleOffset = profileSectionOffset + noiseSection.length;
        long totalWords = sampleOffset + (long)samples.size() * SAMPLE_WORDS;
        if (totalWords > MAX_INPUT_WORDS || totalWords > Integer.MAX_VALUE) {
            throw invalid("density batch exceeds the W7 word limit");
        }

        int[] words = new int[(int)totalWords];
        words[0] = WORKLOAD;
        words[1] = samples.size();
        words[2] = words.length;
        words[3] = WIRE_VERSION;
        words[4] = nodeCount;
        words[5] = nodeCount - 1;
        words[6] = edgeCount;
        words[7] = auxWords;
        words[8] = profileRoots.length;
        words[9] = profileRecordCount;
        words[10] = noiseSection.length;
        words[11] = (int)nodeOffset;
        words[12] = (int)edgeOffset;
        words[13] = (int)auxOffset;
        words[14] = (int)rootTableOffset;
        words[15] = (int)profileSectionOffset;
        words[16] = (int)sampleOffset;
        words[17] = NODE_WORDS;
        words[18] = SAMPLE_WORDS;
        words[19] = OUTPUT_WORDS_PER_SAMPLE;
        System.arraycopy(nodeRecords, 0, words, words[11], nodeRecords.length);
        System.arraycopy(edgeWords, 0, words, words[12], edgeWords.length);
        System.arraycopy(aux, 0, words, words[13], aux.length);
        for (int i = 0; i < profileRoots.length; i++) {
            words[words[14] + i] = Math.addExact(words[15], profileRoots[i] - W5_HEADER_WORDS);
        }
        System.arraycopy(noiseSection, 0, words, words[15], noiseSection.length);
        rebaseProfileReferences(words, words[15], words[10], W5_HEADER_WORDS, words[15]);
        int sampleWord = words[16];
        for (Sample sample : samples) {
            words[sampleWord] = sample.x();
            words[sampleWord + 1] = sample.y();
            words[sampleWord + 2] = sample.z();
            sampleWord += SAMPLE_WORDS;
        }
        validate(words);
        return words;
    }

    public static int[] input(final VanillaDensityGraph.Snapshot snapshot, final Sample... samples) {
        Objects.requireNonNull(samples, "samples");
        return input(snapshot, Arrays.asList(samples));
    }

    /** Validates the complete bounded W7 wire, including embedded W5 profiles and work bounds. */
    public static void validate(final int[] input) {
        parse(input);
    }

    public static int outputWords(final int[] input) {
        Parsed parsed = parse(input);
        return Math.multiplyExact(parsed.sampleCount, OUTPUT_WORDS_PER_SAMPLE);
    }

    /** Evaluates raw IEEE-754 binary64 values on the CPU in wire order and writes low word first. */
    public static int[] reference(final int[] input) {
        Parsed parsed = parse(input);
        int[] output = new int[Math.multiplyExact(parsed.sampleCount, OUTPUT_WORDS_PER_SAMPLE)];
        List<NoiseProfile> profiles = decodeProfiles(input, parsed);
        for (int sample = 0; sample < parsed.sampleCount; sample++) {
            int sampleOffset = parsed.sampleOffset + sample * SAMPLE_WORDS;
            int x = input[sampleOffset];
            int y = input[sampleOffset + 1];
            int z = input[sampleOffset + 2];
            NoiseOracle noise = new NoiseOracle(profiles);
            double value = evaluateNode(input, parsed, parsed.root, x, y, z, noise, new double[parsed.nodeCount], new byte[parsed.nodeCount]);
            putDouble(output, sample * OUTPUT_WORDS_PER_SAMPLE, value);
        }
        return output;
    }

    public static double outputValue(final int[] output, final int sampleIndex) {
        Objects.requireNonNull(output, "output");
        if ((output.length & 1) != 0 || sampleIndex < 0 || sampleIndex >= output.length / 2) {
            throw new IllegalArgumentException("density output sample index is out of range");
        }
        return getDouble(output, sampleIndex * OUTPUT_WORDS_PER_SAMPLE);
    }

    public static double getDouble(final int[] words, final int offset) {
        Objects.requireNonNull(words, "words");
        if (offset < 0 || (long)offset + 1L >= words.length) throw new IndexOutOfBoundsException("double word offset " + offset);
        return Double.longBitsToDouble(Integer.toUnsignedLong(words[offset]) | ((long)words[offset + 1] << 32));
    }

    private static Parsed parse(final int[] input) {
        Objects.requireNonNull(input, "input");
        if (input.length < HEADER_WORDS || input.length > MAX_INPUT_WORDS || input[0] != WORKLOAD
            || input[2] != input.length || input[3] != WIRE_VERSION) {
            throw invalid("invalid density graph workload/version/word count");
        }
        int sampleCount = input[1];
        int nodeCount = input[4];
        int root = input[5];
        int edgeCount = input[6];
        int auxWords = input[7];
        int profileRootCount = input[8];
        int profileRecordCount = input[9];
        int profileWords = input[10];
        if (sampleCount < 1 || sampleCount > MAX_SAMPLES
            || nodeCount < 1 || nodeCount > MAX_NODES || root != nodeCount - 1
            || edgeCount < 0 || edgeCount > MAX_EDGES
            || auxWords < nodeCount * 4L || profileRootCount < 0 || profileRootCount > MAX_PROFILE_ROOTS
            || profileRecordCount < 0 || profileRecordCount > MAX_PROFILE_RECORDS
            || profileWords < 0 || profileWords > MAX_PROFILE_WORDS
            || input[17] != NODE_WORDS || input[18] != SAMPLE_WORDS || input[19] != OUTPUT_WORDS_PER_SAMPLE
            || input[20] != 0 || input[21] != 0 || input[22] != 0 || input[23] != 0) {
            throw invalid("density graph dimensions or reserved header words are invalid");
        }
        long expectedNodeOffset = HEADER_WORDS;
        long expectedEdgeOffset = expectedNodeOffset + (long)nodeCount * NODE_WORDS;
        long expectedAuxOffset = expectedEdgeOffset + edgeCount;
        long expectedRootOffset = expectedAuxOffset + auxWords;
        long expectedProfileOffset = expectedRootOffset + profileRootCount;
        long expectedSampleOffset = expectedProfileOffset + profileWords;
        long expectedLength = expectedSampleOffset + (long)sampleCount * SAMPLE_WORDS;
        if (input[11] != expectedNodeOffset || input[12] != expectedEdgeOffset || input[13] != expectedAuxOffset
            || input[14] != expectedRootOffset || input[15] != expectedProfileOffset || input[16] != expectedSampleOffset
            || expectedLength != input.length) {
            throw invalid("density graph sections are not contiguous or do not match total words");
        }
        if ((profileRootCount == 0) != (profileRecordCount == 0)
            || (profileRootCount == 0) != (profileWords == 0)) {
            throw invalid("density profile root, record and word counts disagree");
        }

        int nodeOffset = input[11];
        int edgeOffset = input[12];
        int auxOffset = input[13];
        int edgeCursor = 0;
        int auxCursor = nodeCount * 4;
        int[] depth = new int[nodeCount];
        long[] work = new long[nodeCount];
        byte[] profileTypes = new byte[profileRootCount];
        int[] profileRootOffsets = new int[profileRootCount];
        ProfileSection profileSection = validateProfiles(input, profileRootOffsets, profileTypes);
        NoiseProfile[] profiles = decodeProfiles(input, profileRootOffsets);
        boolean[] usedProfiles = new boolean[profileRootCount];
        double[] derivedMin = new double[nodeCount];
        double[] derivedMax = new double[nodeCount];

        for (int node = 0; node < nodeCount; node++) {
            int offset = nodeOffset + node * NODE_WORDS;
            int opcode = input[offset];
            int flags = input[offset + 1];
            int edgeStart = input[offset + 2];
            int edgeSize = input[offset + 3];
            int auxStart = input[offset + 4];
            int auxSize = input[offset + 5];
            if (flags != 0 || edgeStart != edgeCursor || edgeSize < 0 || (long)edgeStart + edgeSize > edgeCount
                || input[offset + 14] != 0 || input[offset + 15] != 0) {
                throw invalid("density node flags, edge span, or reserved words are invalid");
            }
            double minValue = getDouble(input, auxOffset + node * 4);
            double maxValue = getDouble(input, auxOffset + node * 4 + 2);
            if (!Double.isFinite(minValue) || !Double.isFinite(maxValue) || minValue > maxValue) {
                throw invalid("density node bounds must be finite and ordered");
            }
            int data0 = input[offset + 6];
            int data1 = input[offset + 7];
            int data2 = input[offset + 8];
            int data3 = input[offset + 9];
            double p0 = getDouble(input, offset + 10);
            double p1 = getDouble(input, offset + 12);
            if (auxStart < 0 || auxSize < 0 || (long)auxStart + auxSize > auxWords) {
                throw invalid("density node auxiliary span exceeds its section");
            }
            int children = 0;
            int nodeWeight = 1;
            int profileId = -1;

            switch (opcode) {
                case OP_CONSTANT -> {
                    requireNodeShape(edgeSize == 0 && auxStart == 0 && auxSize == 0
                        && data0 == 0 && data1 == 0 && data2 == 0 && data3 == 0 && isPositiveZero(p1)
                        && Double.isFinite(p0), "constant");
                }
                case OP_ALIAS -> {
                    requireNodeShape(edgeSize == 1 && auxStart == 0 && auxSize == 0
                        && allZero(data0, data1, data2, data3) && zeroParameters(p0, p1), "alias");
                    children = 1;
                }
                case OP_Y_GRADIENT -> {
                    requireNodeShape(edgeSize == 0 && auxStart == 0 && auxSize == 0
                        && data2 == 0 && data3 == 0 && Double.isFinite(p0) && Double.isFinite(p1), "Y gradient");
                }
                case OP_CLAMP -> {
                    requireNodeShape(edgeSize == 1 && auxStart == 0 && auxSize == 0
                        && allZero(data0, data1, data2, data3) && Double.isFinite(p0) && Double.isFinite(p1), "clamp");
                    children = 1;
                }
                case OP_MAPPED -> {
                    requireNodeShape(edgeSize == 1 && auxStart == 0 && auxSize == 0
                        && data0 >= 0 && data0 <= 6 && data1 == 0 && data2 == 0 && data3 == 0
                        && zeroParameters(p0, p1), "mapped");
                    children = 1;
                }
                case OP_BINARY -> {
                    requireNodeShape(edgeSize == 2 && auxStart == 0 && auxSize == 0
                        && data0 >= 0 && data0 <= 3 && data1 == 0 && data2 == 0 && data3 == 0
                        && Double.isFinite(p0) && Double.isFinite(p1), "binary");
                    children = 2;
                }
                case OP_MUL_OR_ADD -> {
                    requireNodeShape(edgeSize == 1 && auxStart == 0 && auxSize == 0
                        && data0 >= 0 && data0 <= 1 && data1 == 0 && data2 == 0 && data3 == 0
                        && Double.isFinite(p0) && isPositiveZero(p1), "MulOrAdd");
                    children = 1;
                }
                case OP_RANGE_CHOICE -> {
                    requireNodeShape(edgeSize == 3 && auxStart == 0 && auxSize == 0
                        && allZero(data0, data1, data2, data3)
                        && Double.isFinite(p0) && Double.isFinite(p1), "range choice");
                    children = 3;
                }
                case OP_INTERVAL_SELECT -> {
                    requireNodeShape(edgeSize >= 3 && edgeSize <= MAX_INTERVAL_BRANCHES + 1
                        && auxStart == auxCursor && auxSize == (edgeSize - 2) * 2
                        && allZero(data0, data1, data2, data3) && zeroParameters(p0, p1), "interval select");
                    int thresholdCount = edgeSize - 2;
                    double previousThreshold = Double.NEGATIVE_INFINITY;
                    for (int i = 0; i < thresholdCount; i++) {
                        double threshold = getDouble(input, auxOffset + auxStart + i * 2);
                        if (!Double.isFinite(threshold) || (i > 0 && Double.compare(previousThreshold, threshold) > 0)) {
                            throw invalid("interval thresholds must be finite and ordered");
                        }
                        previousThreshold = threshold;
                    }
                    auxCursor += auxSize;
                    children = edgeSize;
                }
                case OP_NOISE -> {
                    requireNodeShape(edgeSize == 0 && auxStart == 0 && auxSize == 0
                        && data0 >= 0 && data0 < profileRootCount && data1 == 0 && data2 == 0 && data3 == 0
                        && Double.isFinite(p0) && Double.isFinite(p1), "noise");
                    profileId = data0;
                    if (profileTypes[profileId] != PROFILE_NORMAL) throw invalid("Noise root must use a NormalNoise profile");
                    usedProfiles[profileId] = true;
                    nodeWeight = Math.addExact(nodeWeight, profileWork(input, profileRootOffsets[profileId]));
                }
                case OP_SHIFT -> {
                    requireNodeShape(edgeSize == 0 && auxStart == 0 && auxSize == 0
                        && data0 >= 0 && data0 < profileRootCount && data1 >= 0 && data1 <= 2 && data2 == 0 && data3 == 0
                        && zeroParameters(p0, p1), "shift");
                    profileId = data0;
                    if (profileTypes[profileId] != PROFILE_NORMAL) throw invalid("Shift root must use a NormalNoise profile");
                    usedProfiles[profileId] = true;
                    nodeWeight = Math.addExact(nodeWeight, profileWork(input, profileRootOffsets[profileId]));
                }
                case OP_SHIFTED_NOISE -> {
                    requireNodeShape(edgeSize == 3 && auxStart == 0 && auxSize == 0
                        && data0 >= 0 && data0 < profileRootCount && data1 == 0 && data2 == 0 && data3 == 0
                        && Double.isFinite(p0) && Double.isFinite(p1), "shifted noise");
                    profileId = data0;
                    if (profileTypes[profileId] != PROFILE_NORMAL) throw invalid("ShiftedNoise root must use a NormalNoise profile");
                    usedProfiles[profileId] = true;
                    nodeWeight = Math.addExact(nodeWeight, profileWork(input, profileRootOffsets[profileId]));
                    children = 3;
                }
                case OP_BLENDED -> {
                    requireNodeShape(edgeSize == 0 && auxStart == 0 && auxSize == 0
                        && data0 >= 0 && data0 < profileRootCount && data1 == 0 && data2 == 0 && data3 == 0
                        && zeroParameters(p0, p1), "blended noise");
                    profileId = data0;
                    if (profileTypes[profileId] != PROFILE_BLENDED) throw invalid("Blended root must use a BlendedNoise profile");
                    usedProfiles[profileId] = true;
                    nodeWeight = Math.addExact(nodeWeight, 40);
                }
                default -> throw invalid("unknown density opcode " + opcode);
            }

            edgeCursor += edgeSize;
            if (edgeSize != children) throw invalid("density opcode has the wrong child count");
            int maxChildDepth = 0;
            long childWork = 0L;
            long branchWork = 0L;
            for (int childIndex = 0; childIndex < edgeSize; childIndex++) {
                int child = input[edgeOffset + edgeStart + childIndex];
                if (child < 0 || child >= node) throw invalid("density children must be earlier postorder IDs");
                maxChildDepth = Math.max(maxChildDepth, depth[child]);
                if (opcode == OP_RANGE_CHOICE && childIndex == 2) {
                    branchWork = Math.max(work[input[edgeOffset + edgeStart + 1]], work[child]);
                } else if (opcode == OP_INTERVAL_SELECT && childIndex >= 1) {
                    branchWork = Math.max(branchWork, work[child]);
                } else {
                    childWork = saturatedAdd(childWork, work[child]);
                }
            }

            Bounds expectedBounds = deriveBounds(input, offset, edgeOffset + edgeStart,
                derivedMin, derivedMax, profiles);
            if (!Double.isFinite(expectedBounds.minimum) || !Double.isFinite(expectedBounds.maximum)
                || expectedBounds.minimum > expectedBounds.maximum
                || Double.doubleToRawLongBits(expectedBounds.minimum) != Double.doubleToRawLongBits(minValue)
                || Double.doubleToRawLongBits(expectedBounds.maximum) != Double.doubleToRawLongBits(maxValue)) {
                throw invalid("density node declared bounds do not match bounds derived from its operands");
            }
            derivedMin[node] = expectedBounds.minimum;
            derivedMax[node] = expectedBounds.maximum;

            if (opcode == OP_RANGE_CHOICE) {
                childWork = saturatedAdd(work[input[edgeOffset + edgeStart]], branchWork);
            } else if (opcode == OP_INTERVAL_SELECT) {
                childWork = saturatedAdd(work[input[edgeOffset + edgeStart]], branchWork);
            }
            depth[node] = maxChildDepth + 1;
            if (depth[node] > MAX_DEPTH) throw invalid("density graph exceeds explicit interpreter depth");
            work[node] = saturatedAdd(nodeWeight, childWork);
            if (work[node] > MAX_NODE_WORK) throw invalid("density graph per-sample work exceeds W7 limit");

            if (opcode == OP_BINARY) {
                int secondChild = input[edgeOffset + edgeStart + 1];
                double secondMin = getDouble(input, auxOffset + secondChild * 4);
                double secondMax = getDouble(input, auxOffset + secondChild * 4 + 2);
                if (Double.doubleToRawLongBits(p0) != Double.doubleToRawLongBits(secondMin)
                    || Double.doubleToRawLongBits(p1) != Double.doubleToRawLongBits(secondMax)) {
                    throw invalid("binary shortcut bounds do not match their second operand");
                }
            }
        }
        if (edgeCursor != edgeCount || auxCursor != auxWords) throw invalid("density edge or auxiliary words are not fully referenced");
        for (boolean used : usedProfiles) if (!used) throw invalid("density profile root is not referenced by an opcode");
        if (saturatedMultiply(work[root], sampleCount) > MAX_BATCH_WORK) {
            throw invalid("density batch weighted work exceeds W7 limit");
        }
        long validationUnits = (long)nodeCount + edgeCount;
        if (saturatedMultiply(validationUnits, sampleCount) > MAX_BATCH_WORK) {
            throw invalid("density batch validation work exceeds W7 limit");
        }
        validateGraphReachability(input, nodeCount, root, edgeOffset);
        validateSampleDomains(input, sampleCount, nodeCount, nodeOffset, edgeOffset, derivedMin, derivedMax, profiles);
        return new Parsed(sampleCount, nodeCount, root, nodeOffset, edgeOffset, auxOffset, input[16],
            profileSectionOffset(input), profileRootCount, profileRootOffsets, profileSection.recordTypes);
    }

    private static Bounds deriveBounds(
        final int[] input,
        final int record,
        final int edge,
        final double[] minimum,
        final double[] maximum,
        final NoiseProfile[] profiles
    ) {
        int opcode = input[record];
        int data0 = input[record + 6];
        int data1 = input[record + 7];
        double p0 = getDouble(input, record + 10);
        double p1 = getDouble(input, record + 12);
        return switch (opcode) {
            case OP_CONSTANT -> new Bounds(p0, p0);
            case OP_ALIAS -> boundsOf(input[edge], minimum, maximum);
            case OP_Y_GRADIENT -> {
                if (data0 == data1) throw invalid("Y gradient must have distinct vertical endpoints");
                double difference = p1 - p0;
                if (!Double.isFinite(difference)) throw invalid("Y gradient value span overflows");
                yield new Bounds(Math.min(p0, p1), Math.max(p0, p1));
            }
            case OP_CLAMP -> {
                if (p0 > p1) throw invalid("clamp minimum exceeds maximum");
                yield new Bounds(p0, p1);
            }
            case OP_MAPPED -> mappedBounds(data0, boundsOf(input[edge], minimum, maximum));
            case OP_BINARY -> binaryBounds(data0, boundsOf(input[edge], minimum, maximum),
                boundsOf(input[edge + 1], minimum, maximum));
            case OP_MUL_OR_ADD -> {
                Bounds source = boundsOf(input[edge], minimum, maximum);
                Bounds constant = new Bounds(p0, p0);
                yield data0 == 0 ? binaryBounds(1, constant, source) : binaryBounds(0, constant, source);
            }
            case OP_RANGE_CHOICE -> {
                Bounds inRange = boundsOf(input[edge + 1], minimum, maximum);
                Bounds outOfRange = boundsOf(input[edge + 2], minimum, maximum);
                yield new Bounds(Math.min(inRange.minimum, outOfRange.minimum), Math.max(inRange.maximum, outOfRange.maximum));
            }
            case OP_INTERVAL_SELECT -> {
                int branchCount = input[record + 3] - 1;
                double min = Double.MAX_VALUE;
                double max = -Double.MAX_VALUE;
                for (int branch = 0; branch < branchCount; branch++) {
                    Bounds branchBounds = boundsOf(input[edge + branch + 1], minimum, maximum);
                    min = Math.min(branchBounds.minimum, min);
                    max = Math.max(branchBounds.maximum, max);
                }
                yield new Bounds(min, max);
            }
            case OP_NOISE -> symmetricNoiseBounds(profiles[data0], false);
            case OP_SHIFT -> symmetricNoiseBounds(profiles[data0], true);
            case OP_SHIFTED_NOISE -> symmetricNoiseBounds(profiles[data0], false);
            case OP_BLENDED -> blendedNoiseBounds(profiles[data0]);
            default -> throw invalid("cannot derive bounds for density opcode " + opcode);
        };
    }

    /**
     * Computes outward-rounded value ranges for coordinate-domain proofs. Vanilla's declared
     * metadata is kept and checked separately above; several vanilla metadata ranges are not
     * conservative ranges of the evaluated function (notably SQUARE for positive inputs below 1).
     */
    private static Bounds deriveDomainBounds(
        final int[] input,
        final int record,
        final int edge,
        final double[] minimum,
        final double[] maximum,
        final boolean[] metadataEnclosesValues,
        final NoiseProfile[] profiles
    ) {
        int opcode = input[record];
        int data0 = input[record + 6];
        double p0 = getDouble(input, record + 10);
        double p1 = getDouble(input, record + 12);
        return switch (opcode) {
            case OP_CONSTANT -> new Bounds(p0, p0);
            case OP_ALIAS -> boundsOf(input[edge], minimum, maximum);
            case OP_Y_GRADIENT -> {
                double difference = p1 - p0;
                Bounds interpolated = addRanges(new Bounds(p0, p0), multiplyRanges(
                    new Bounds(0.0, 1.0), new Bounds(difference, difference)));
                yield unionRanges(interpolated, new Bounds(Math.min(p0, p1), Math.max(p0, p1)));
            }
            case OP_CLAMP -> {
                Bounds source = boundsOf(input[edge], minimum, maximum);
                yield new Bounds(Mth.clamp(source.minimum, p0, p1), Mth.clamp(source.maximum, p0, p1));
            }
            case OP_MAPPED -> mappedDomainBounds(data0, boundsOf(input[edge], minimum, maximum));
            case OP_BINARY -> binaryDomainBounds(data0,
                boundsOf(input[edge], minimum, maximum), boundsOf(input[edge + 1], minimum, maximum),
                metadataEnclosesValues[input[edge + 1]]);
            case OP_MUL_OR_ADD -> {
                Bounds source = boundsOf(input[edge], minimum, maximum);
                yield data0 == 0 ? multiplyRanges(source, new Bounds(p0, p0)) : addRanges(source, new Bounds(p0, p0));
            }
            case OP_RANGE_CHOICE -> unionRanges(
                boundsOf(input[edge + 1], minimum, maximum), boundsOf(input[edge + 2], minimum, maximum));
            case OP_INTERVAL_SELECT -> {
                Bounds result = boundsOf(input[edge + 1], minimum, maximum);
                for (int branch = 2; branch < input[record + 3]; branch++) {
                    result = unionRanges(result, boundsOf(input[edge + branch], minimum, maximum));
                }
                yield result;
            }
            case OP_NOISE -> widenNoiseRange(symmetricNoiseBounds(profiles[data0], false));
            case OP_SHIFT -> widenNoiseRange(symmetricNoiseBounds(profiles[data0], true));
            case OP_SHIFTED_NOISE -> widenNoiseRange(symmetricNoiseBounds(profiles[data0], false));
            case OP_BLENDED -> widenNoiseRange(blendedNoiseBounds(profiles[data0]));
            default -> throw invalid("cannot derive coordinate-domain bounds for density opcode " + opcode);
        };
    }

    private static Bounds mappedDomainBounds(final int kind, final Bounds input) {
        return switch (kind) {
            case 0 -> {
                double low = input.minimum <= 0.0 && input.maximum >= 0.0
                    ? 0.0 : Math.min(Math.abs(input.minimum), Math.abs(input.maximum));
                double high = Math.max(Math.abs(input.minimum), Math.abs(input.maximum));
                yield new Bounds(roundDown(low), roundUp(high));
            }
            case 1 -> squareRange(input);
            case 2 -> multiplyRanges(squareRange(input), input);
            case 3, 4 -> {
                double factor = kind == 3 ? 0.5 : 0.25;
                Bounds result = null;
                if (input.minimum <= 0.0) {
                    Bounds negative = new Bounds(input.minimum, Math.min(input.maximum, 0.0));
                    result = multiplyRanges(negative, new Bounds(factor, factor));
                }
                if (input.maximum > 0.0) {
                    Bounds positive = new Bounds(Math.max(input.minimum, 0.0), input.maximum);
                    result = result == null ? positive : unionRanges(result, positive);
                }
                yield result == null ? wholeRange() : result;
            }
            case 5 -> divideRanges(new Bounds(1.0, 1.0), input);
            case 6 -> squeezeRange(input);
            default -> throw invalid("unknown mapped function while deriving coordinate-domain bounds");
        };
    }

    private static Bounds squeezeRange(final Bounds input) {
        Bounds clamped = new Bounds(Mth.clamp(input.minimum, -1.0, 1.0), Mth.clamp(input.maximum, -1.0, 1.0));
        Bounds half = divideRanges(clamped, new Bounds(2.0, 2.0));
        Bounds cube = multiplyRanges(squareRange(clamped), clamped);
        Bounds cubicTerm = divideRanges(cube, new Bounds(24.0, 24.0));
        return subtractRanges(half, cubicTerm);
    }

    private static Bounds binaryDomainBounds(
        final int kind, final Bounds first, final Bounds second, final boolean secondMetadataEnclosesValues
    ) {
        return switch (kind) {
            case 0 -> addRanges(first, second);
            case 1 -> multiplyRanges(first, second);
            // If vanilla's second-child bounds enclose its values, its min/max short-circuit is
            // valid and interval min/max is safe. Otherwise only the child-range union is safe.
            case 2 -> secondMetadataEnclosesValues
                ? new Bounds(roundDown(Math.min(first.minimum, second.minimum)),
                    roundUp(Math.min(first.maximum, second.maximum)))
                : unionRanges(first, second);
            case 3 -> secondMetadataEnclosesValues
                ? new Bounds(roundDown(Math.max(first.minimum, second.minimum)),
                    roundUp(Math.max(first.maximum, second.maximum)))
                : unionRanges(first, second);
            default -> throw invalid("unknown binary operation while deriving coordinate-domain bounds");
        };
    }

    private static Bounds squareRange(final Bounds input) {
        double low = input.minimum <= 0.0 && input.maximum >= 0.0
            ? 0.0 : Math.min(input.minimum * input.minimum, input.maximum * input.maximum);
        double high = Math.max(input.minimum * input.minimum, input.maximum * input.maximum);
        return new Bounds(roundDown(low), roundUp(high));
    }

    private static Bounds addRanges(final Bounds first, final Bounds second) {
        double low = first.minimum + second.minimum;
        double high = first.maximum + second.maximum;
        if (Double.isNaN(low) || Double.isNaN(high)) return wholeRange();
        return new Bounds(roundDown(low), roundUp(high));
    }

    private static Bounds subtractRanges(final Bounds first, final Bounds second) {
        double low = first.minimum - second.maximum;
        double high = first.maximum - second.minimum;
        if (Double.isNaN(low) || Double.isNaN(high)) return wholeRange();
        return new Bounds(roundDown(low), roundUp(high));
    }

    private static Bounds multiplyRanges(final Bounds first, final Bounds second) {
        double a = first.minimum * second.minimum;
        double b = first.minimum * second.maximum;
        double c = first.maximum * second.minimum;
        double d = first.maximum * second.maximum;
        if (Double.isNaN(a) || Double.isNaN(b) || Double.isNaN(c) || Double.isNaN(d)) return wholeRange();
        return new Bounds(roundDown(Math.min(Math.min(a, b), Math.min(c, d))),
            roundUp(Math.max(Math.max(a, b), Math.max(c, d))));
    }

    private static Bounds divideRanges(final Bounds numerator, final Bounds denominator) {
        if (denominator.minimum <= 0.0 && denominator.maximum >= 0.0) return wholeRange();
        double a = numerator.minimum / denominator.minimum;
        double b = numerator.minimum / denominator.maximum;
        double c = numerator.maximum / denominator.minimum;
        double d = numerator.maximum / denominator.maximum;
        if (Double.isNaN(a) || Double.isNaN(b) || Double.isNaN(c) || Double.isNaN(d)) return wholeRange();
        return new Bounds(roundDown(Math.min(Math.min(a, b), Math.min(c, d))),
            roundUp(Math.max(Math.max(a, b), Math.max(c, d))));
    }

    private static Bounds unionRanges(final Bounds first, final Bounds second) {
        return new Bounds(Math.min(first.minimum, second.minimum), Math.max(first.maximum, second.maximum));
    }

    private static Bounds wholeRange() {
        return new Bounds(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
    }

    private static double roundDown(final double value) {
        return Double.isFinite(value) ? Math.nextDown(value) : value;
    }

    private static double roundUp(final double value) {
        return Double.isFinite(value) ? Math.nextUp(value) : value;
    }

    private static Bounds widenNoiseRange(final Bounds range) {
        double maximum = Math.max(Math.abs(range.minimum), Math.abs(range.maximum));
        if (!Double.isFinite(maximum)) return wholeRange();
        // Scope this guard to the W5-validated profile subset (finite factors, at most 64
        // octaves, and nonnegative amplitudes). It is not a general bound for arbitrary noise
        // implementations or unrestricted custom DensityFunctions.
        double guard = Math.max(Math.ulp(maximum) * 2048.0, Double.MIN_VALUE * 2048.0);
        double widened = roundUp(maximum + guard);
        return new Bounds(-widened, widened);
    }

    private static Bounds boundsOf(final int node, final double[] minimum, final double[] maximum) {
        return new Bounds(minimum[node], maximum[node]);
    }

    private static Bounds mappedBounds(final int kind, final Bounds input) {
        double first = mapped(kind, input.minimum);
        double second = mapped(kind, input.maximum);
        if (kind == 5) {
            if (input.minimum < 0.0 && input.maximum > 0.0) {
                return new Bounds(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
            }
            return new Bounds(second, first);
        }
        if (kind == 0 || kind == 1) {
            // Match DensityFunctions.Mapped.create exactly, including its conservative
            // lower bound for ABS/SQUARE over negative-only input ranges.
            return new Bounds(Math.max(0.0, input.minimum), Math.max(first, second));
        }
        return new Bounds(first, second);
    }

    private static Bounds binaryBounds(final int kind, final Bounds first, final Bounds second) {
        return switch (kind) {
            case 0 -> new Bounds(first.minimum + second.minimum, first.maximum + second.maximum);
            case 1 -> {
                double lower;
                if (first.minimum > 0.0 && second.minimum > 0.0) {
                    lower = first.minimum * second.minimum;
                } else if (first.maximum < 0.0 && second.maximum < 0.0) {
                    lower = first.maximum * second.maximum;
                } else {
                    lower = Math.min(first.minimum * second.maximum, first.maximum * second.minimum);
                }
                double upper;
                if (first.minimum > 0.0 && second.minimum > 0.0) {
                    upper = first.maximum * second.maximum;
                } else if (first.maximum < 0.0 && second.maximum < 0.0) {
                    upper = first.minimum * second.minimum;
                } else {
                    upper = Math.max(first.minimum * second.minimum, first.maximum * second.maximum);
                }
                yield new Bounds(lower, upper);
            }
            case 2 -> new Bounds(Math.min(first.minimum, second.minimum), Math.min(first.maximum, second.maximum));
            case 3 -> new Bounds(Math.max(first.minimum, second.minimum), Math.max(first.maximum, second.maximum));
            default -> throw invalid("unknown binary operation while deriving bounds");
        };
    }

    private static Bounds symmetricNoiseBounds(final NoiseProfile profile, final boolean shift) {
        if (!(profile instanceof NormalNoiseProfile normal)) throw invalid("density noise operation requires a NormalNoise profile");
        double maximum = (perlinMaximum(normal.first()) + perlinMaximum(normal.second())) * normal.valueFactor();
        if (shift) maximum = maximum * 4.0;
        return new Bounds(-maximum, maximum);
    }

    private static Bounds blendedNoiseBounds(final NoiseProfile profile) {
        if (!(profile instanceof BlendedNoiseProfile blended)) throw invalid("BlendedNoise density operation requires a BlendedNoise profile");
        double maximum = perlinEdgeValue(blended.minLimit(), blended.yMultiplier() + 2.0);
        return new Bounds(-maximum, maximum);
    }

    private static double perlinMaximum(final PerlinNoiseProfile profile) {
        return perlinEdgeValue(profile, 2.0);
    }

    private static double perlinEdgeValue(final PerlinNoiseProfile profile, final double edgeMultiplier) {
        double sum = 0.0;
        double valueFactor = profile.lowestFreqValueFactor();
        double[] amplitudes = profile.amplitudes();
        var levels = profile.noiseLevels();
        for (int octave = 0; octave < amplitudes.length; octave++) {
            if (amplitudes[octave] < 0.0) {
                throw invalid("negative Perlin amplitude is outside the W7 proven noise-bound subset");
            }
            if (levels[octave] != null) {
                sum += amplitudes[octave] * edgeMultiplier * valueFactor;
            }
            valueFactor = valueFactor / 2.0;
        }
        return sum;
    }

    private static ProfileSection validateProfiles(final int[] input, final int[] roots, final byte[] rootTypes) {
        int rootCount = input[8];
        int profileRecords = input[9];
        int profileWords = input[10];
        int profileBase = input[15];
        if (rootCount == 0) return new ProfileSection(new HashMap<>(), new byte[0]);

        Map<Integer, RecordInfo> records = new HashMap<>();
        int cursor = profileBase;
        int end = profileBase + profileWords;
        while (cursor < end) {
            if (end - cursor < 2) throw invalid("truncated W5 profile record");
            int length = input[cursor + 1];
            if (length < 2 || (long)cursor + length > end) throw invalid("invalid W5 profile record span");
            records.put(cursor, new RecordInfo(input[cursor], length));
            cursor += length;
        }
        if (cursor != end || records.size() != profileRecords) throw invalid("W5 profile record count mismatch");

        int w5Length = Math.toIntExact(W5_HEADER_WORDS + (long)profileWords + (long)rootCount * W5_SAMPLE_WORDS);
        if (w5Length > MAX_INPUT_WORDS) throw invalid("embedded W5 validation view exceeds its bound");
        int[] w5 = new int[w5Length];
        w5[0] = VanillaNoiseBatch.WORKLOAD;
        w5[1] = rootCount;
        w5[2] = profileRecords;
        w5[3] = profileWords;
        w5[4] = W5_HEADER_WORDS + profileWords;
        w5[5] = W5_SAMPLE_WORDS;
        System.arraycopy(input, profileBase, w5, W5_HEADER_WORDS, profileWords);
        rebaseProfileReferences(w5, W5_HEADER_WORDS, profileWords, profileBase, W5_HEADER_WORDS);

        Set<Integer> uniqueRoots = new HashSet<>();
        for (int profile = 0; profile < rootCount; profile++) {
            int rootOffset = input[input[14] + profile];
            long relative = (long)rootOffset - profileBase;
            if (relative < 0 || relative >= profileWords || !records.containsKey(rootOffset) || !uniqueRoots.add(rootOffset)) {
                throw invalid("profile root offset is invalid or duplicated");
            }
            int normalizedRoot = W5_HEADER_WORDS + (int)relative;
            roots[profile] = rootOffset;
            w5[w5[4] + profile * W5_SAMPLE_WORDS] = normalizedRoot;
            rootTypes[profile] = (byte)input[rootOffset];
        }
        VanillaNoiseBatch.validate(w5);
        validateProfileReachability(input, roots, records, profileRecords);
        return new ProfileSection(records, rootTypes);
    }

    private static void validateProfileReachability(
        final int[] input,
        final int[] roots,
        final Map<Integer, RecordInfo> records,
        final int expectedRecords
    ) {
        Set<Integer> reached = new HashSet<>();
        ArrayDeque<Integer> stack = new ArrayDeque<>();
        for (int root : roots) stack.push(root);
        while (!stack.isEmpty()) {
            int offset = stack.pop();
            if (!reached.add(offset)) continue;
            RecordInfo info = records.get(offset);
            if (info == null) throw invalid("W5 profile edge is not a record boundary");
            if (info.type == PROFILE_PERLIN) {
                int octaves = input[offset + 2];
                for (int i = 0; i < octaves; i++) {
                    int child = input[offset + W5_RECORD_PERLIN_HEADER_WORDS + i * W5_RECORD_PERLIN_OCTAVE_WORDS + 2];
                    if (child >= 0) stack.push(child);
                }
            } else if (info.type == PROFILE_NORMAL) {
                stack.push(input[offset + 2]);
                stack.push(input[offset + 3]);
            } else if (info.type == PROFILE_BLENDED) {
                stack.push(input[offset + 2]);
                stack.push(input[offset + 3]);
                stack.push(input[offset + 4]);
            }
        }
        if (reached.size() != expectedRecords) throw invalid("W5 profile section contains unreferenced records");
    }

    private static void rebaseProfileReferences(
        final int[] words,
        final int sectionOffset,
        final int sectionWords,
        final int oldBase,
        final int newBase
    ) {
        int end = sectionOffset + sectionWords;
        int offset = sectionOffset;
        while (offset < end) {
            int type = words[offset];
            int length = words[offset + 1];
            if (type == PROFILE_PERLIN) {
                int octaves = words[offset + 2];
                for (int i = 0; i < octaves; i++) {
                    int reference = offset + W5_RECORD_PERLIN_HEADER_WORDS + i * W5_RECORD_PERLIN_OCTAVE_WORDS + 2;
                    if (words[reference] >= 0) words[reference] = Math.addExact(words[reference] - oldBase, newBase);
                }
            } else if (type == PROFILE_NORMAL) {
                words[offset + 2] = Math.addExact(words[offset + 2] - oldBase, newBase);
                words[offset + 3] = Math.addExact(words[offset + 3] - oldBase, newBase);
            } else if (type == PROFILE_BLENDED) {
                words[offset + 2] = Math.addExact(words[offset + 2] - oldBase, newBase);
                words[offset + 3] = Math.addExact(words[offset + 3] - oldBase, newBase);
                words[offset + 4] = Math.addExact(words[offset + 4] - oldBase, newBase);
            }
            offset += length;
        }
        if (offset != end) throw invalid("W5 profile section length changed while rebasing");
    }

    private static int profileWork(final int[] input, final int rootOffset) {
        int type = input[rootOffset];
        if (type == PROFILE_IMPROVED) return 1;
        if (type == PROFILE_PERLIN) return input[rootOffset + 2];
        if (type == PROFILE_NORMAL) {
            return Math.addExact(input[input[rootOffset + 2] + 2], input[input[rootOffset + 3] + 2]);
        }
        if (type == PROFILE_BLENDED) return 40;
        throw invalid("unknown W5 profile root type");
    }

    private static void validateGraphReachability(final int[] input, final int nodeCount, final int root, final int edgeOffset) {
        boolean[] reachable = new boolean[nodeCount];
        reachable[root] = true;
        for (int node = root; node >= 0; node--) {
            if (!reachable[node]) continue;
            int record = input[11] + node * NODE_WORDS;
            int edgeStart = input[record + 2];
            int edgeSize = input[record + 3];
            for (int i = 0; i < edgeSize; i++) reachable[input[edgeOffset + edgeStart + i]] = true;
        }
        for (boolean nodeReachable : reachable) if (!nodeReachable) throw invalid("density DAG contains an unreachable node");
    }

    private static void validateSampleDomains(
        final int[] input,
        final int sampleCount,
        final int nodeCount,
        final int nodeOffset,
        final int edgeOffset,
        final double[] declaredMin,
        final double[] declaredMax,
        final NoiseProfile[] profiles
    ) {
        int sampleOffset = input[16];
        double[] valueMin = new double[nodeCount];
        double[] valueMax = new double[nodeCount];
        boolean[] metadataEnclosesValues = new boolean[nodeCount];
        for (int sample = 0; sample < sampleCount; sample++) {
            int record = sampleOffset + sample * SAMPLE_WORDS;
            int blockX = input[record];
            int blockY = input[record + 1];
            int blockZ = input[record + 2];
            checkBlockCoordinate(blockX);
            checkBlockCoordinate(blockY);
            checkBlockCoordinate(blockZ);
            for (int node = 0; node < nodeCount; node++) {
                int offset = nodeOffset + node * NODE_WORDS;
                int opcode = input[offset];
                int edgeStart = input[offset + 2];
                Bounds sampledBounds = sampledDomainBounds(input, offset, edgeOffset + edgeStart, blockY,
                    valueMin, valueMax, metadataEnclosesValues, profiles);
                valueMin[node] = sampledBounds.minimum;
                valueMax[node] = sampledBounds.maximum;
                metadataEnclosesValues[node] = declaredMin[node] <= sampledBounds.minimum
                    && declaredMax[node] >= sampledBounds.maximum;
                double minX, maxX, minY, maxY, minZ, maxZ;
                if (opcode == OP_NOISE) {
                    double xz = getDouble(input, offset + 10);
                    double y = getDouble(input, offset + 12);
                    requireNoiseCoordinate(blockX * xz, "Noise X");
                    requireNoiseCoordinate(blockY * y, "Noise Y");
                    requireNoiseCoordinate(blockZ * xz, "Noise Z");
                } else if (opcode == OP_SHIFT) {
                    requireNoiseCoordinate(blockX * 0.25, "Shift X");
                    requireNoiseCoordinate(blockY * 0.25, "Shift Y");
                    requireNoiseCoordinate(blockZ * 0.25, "Shift Z");
                } else if (opcode == OP_BLENDED) {
                    requireNoiseCoordinate(blockX, "BlendedNoise X");
                    requireNoiseCoordinate(blockY, "BlendedNoise Y");
                    requireNoiseCoordinate(blockZ, "BlendedNoise Z");
                } else if (opcode == OP_SHIFTED_NOISE) {
                    double xz = getDouble(input, offset + 10);
                    double yScale = getDouble(input, offset + 12);
                    int xNode = input[edgeOffset + edgeStart];
                    int yNode = input[edgeOffset + edgeStart + 1];
                    int zNode = input[edgeOffset + edgeStart + 2];
                    minX = valueMin[xNode];
                    maxX = valueMax[xNode];
                    minY = valueMin[yNode];
                    maxY = valueMax[yNode];
                    minZ = valueMin[zNode];
                    maxZ = valueMax[zNode];
                    requireNoiseRange(blockX * xz, minX, maxX, "ShiftedNoise X");
                    requireNoiseRange(blockY * yScale, minY, maxY, "ShiftedNoise Y");
                    requireNoiseRange(blockZ * xz, minZ, maxZ, "ShiftedNoise Z");
                }
            }
        }
    }

    private static Bounds sampledDomainBounds(
        final int[] input,
        final int record,
        final int edge,
        final int blockY,
        final double[] minimum,
        final double[] maximum,
        final boolean[] metadataEnclosesValues,
        final NoiseProfile[] profiles
    ) {
        if (input[record] == OP_Y_GRADIENT) {
            double value = Mth.clampedMap(blockY, input[record + 6], input[record + 7],
                getDouble(input, record + 10), getDouble(input, record + 12));
            return new Bounds(value, value);
        }
        return deriveDomainBounds(input, record, edge, minimum, maximum, metadataEnclosesValues, profiles);
    }

    private static void requireNoiseRange(final double base, final double minimum, final double maximum, final String label) {
        requireNoiseCoordinate(base + minimum, label);
        requireNoiseCoordinate(base + maximum, label);
    }

    private static void requireNoiseCoordinate(final double value, final String label) {
        if (!Double.isFinite(value) || Math.abs(value) > MAX_NOISE_COORDINATE) {
            throw invalid(label + " exceeds the W5 validated coordinate domain");
        }
    }

    private static List<NoiseProfile> decodeProfiles(final int[] input, final Parsed parsed) {
        if (parsed.profileRootCount == 0) return List.of();
        return List.of(decodeProfiles(input, parsed.profileRootOffsets));
    }

    private static NoiseProfile[] decodeProfiles(final int[] input, final int[] profileRootOffsets) {
        if (profileRootOffsets.length == 0) return new NoiseProfile[0];
        Map<Integer, NoiseProfile> cache = new HashMap<>();
        NoiseProfile[] profiles = new NoiseProfile[profileRootOffsets.length];
        for (int profile = 0; profile < profileRootOffsets.length; profile++) {
            profiles[profile] = decodeProfile(input, profileRootOffsets[profile], cache);
        }
        return profiles;
    }

    private static NoiseProfile decodeProfile(final int[] input, final int offset, final Map<Integer, NoiseProfile> cache) {
        NoiseProfile known = cache.get(offset);
        if (known != null) return known;
        NoiseProfile result;
        int type = input[offset];
        if (type == PROFILE_IMPROVED) {
            byte[] permutation = new byte[256];
            for (int i = 0; i < permutation.length; i++) {
                permutation[i] = (byte)(input[offset + 8 + i / 4] >>> ((i & 3) * 8));
            }
            result = new ImprovedNoiseProfile(getDouble(input, offset + 2), getDouble(input, offset + 4),
                getDouble(input, offset + 6), permutation);
        } else if (type == PROFILE_PERLIN) {
            int octaves = input[offset + 2];
            ImprovedNoiseProfile[] levels = new ImprovedNoiseProfile[octaves];
            double[] amplitudes = new double[octaves];
            for (int i = 0; i < octaves; i++) {
                int octave = offset + W5_RECORD_PERLIN_HEADER_WORDS + i * W5_RECORD_PERLIN_OCTAVE_WORDS;
                amplitudes[i] = getDouble(input, octave);
                int levelOffset = input[octave + 2];
                if (levelOffset >= 0) levels[i] = (ImprovedNoiseProfile)decodeProfile(input, levelOffset, cache);
            }
            result = new PerlinNoiseProfile(levels, amplitudes, getDouble(input, offset + 3), getDouble(input, offset + 5));
        } else if (type == PROFILE_NORMAL) {
            result = new NormalNoiseProfile(
                (PerlinNoiseProfile)decodeProfile(input, input[offset + 2], cache),
                (PerlinNoiseProfile)decodeProfile(input, input[offset + 3], cache),
                getDouble(input, offset + 4)
            );
        } else if (type == PROFILE_BLENDED) {
            result = new BlendedNoiseProfile(
                (PerlinNoiseProfile)decodeProfile(input, input[offset + 2], cache),
                (PerlinNoiseProfile)decodeProfile(input, input[offset + 3], cache),
                (PerlinNoiseProfile)decodeProfile(input, input[offset + 4], cache),
                getDouble(input, offset + 5), getDouble(input, offset + 7), getDouble(input, offset + 9),
                getDouble(input, offset + 11), getDouble(input, offset + 13)
            );
        } else {
            throw invalid("cannot decode unknown W5 profile type " + type);
        }
        cache.put(offset, result);
        return result;
    }

    private static double evaluateNode(
        final int[] input,
        final Parsed parsed,
        final int node,
        final int blockX,
        final int blockY,
        final int blockZ,
        final NoiseOracle noise,
        final double[] values,
        final byte[] states
    ) {
        if (states[node] == 2) return values[node];
        if (states[node] == 1) throw new IllegalStateException("validated density graph contains a cycle");
        states[node] = 1;
        int record = parsed.nodeOffset + node * NODE_WORDS;
        int edgeStart = input[record + 2];
        int edgeSize = input[record + 3];
        int edge = parsed.edgeOffset + edgeStart;
        int data0 = input[record + 6];
        int opcode = input[record];
        double p0 = getDouble(input, record + 10);
        double p1 = getDouble(input, record + 12);
        double value;
        switch (opcode) {
            case OP_CONSTANT -> value = p0;
            case OP_ALIAS -> value = evaluateNode(input, parsed, input[edge], blockX, blockY, blockZ, noise, values, states);
            case OP_Y_GRADIENT -> value = Mth.clampedMap(blockY, input[record + 6], input[record + 7], p0, p1);
            case OP_CLAMP -> value = Mth.clamp(evaluateNode(input, parsed, input[edge], blockX, blockY, blockZ, noise, values, states), p0, p1);
            case OP_MAPPED -> value = mapped(data0, evaluateNode(input, parsed, input[edge], blockX, blockY, blockZ, noise, values, states));
            case OP_BINARY -> {
                double first = evaluateNode(input, parsed, input[edge], blockX, blockY, blockZ, noise, values, states);
                value = switch (data0) {
                    case 0 -> first + evaluateNode(input, parsed, input[edge + 1], blockX, blockY, blockZ, noise, values, states);
                    case 1 -> first == 0.0 ? 0.0 : first * evaluateNode(input, parsed, input[edge + 1], blockX, blockY, blockZ, noise, values, states);
                    case 2 -> first < p0 ? first : Math.min(first, evaluateNode(input, parsed, input[edge + 1], blockX, blockY, blockZ, noise, values, states));
                    case 3 -> first > p1 ? first : Math.max(first, evaluateNode(input, parsed, input[edge + 1], blockX, blockY, blockZ, noise, values, states));
                    default -> throw new IllegalStateException("validated binary opcode changed");
                };
            }
            case OP_MUL_OR_ADD -> {
                double source = evaluateNode(input, parsed, input[edge], blockX, blockY, blockZ, noise, values, states);
                value = data0 == 0 ? source * p0 : source + p0;
            }
            case OP_RANGE_CHOICE -> {
                double source = evaluateNode(input, parsed, input[edge], blockX, blockY, blockZ, noise, values, states);
                int branch = source >= p0 && source < p1 ? input[edge + 1] : input[edge + 2];
                value = evaluateNode(input, parsed, branch, blockX, blockY, blockZ, noise, values, states);
            }
            case OP_INTERVAL_SELECT -> {
                double source = evaluateNode(input, parsed, input[edge], blockX, blockY, blockZ, noise, values, states);
                int auxStart = input[record + 4];
                // The edge list contains the selector input plus N branches, so N + 1
                // edges means the final branch's index is N - 1 (edgeSize - 2).
                int branch = edgeSize - 2;
                for (int i = 0; i < edgeSize - 2; i++) {
                    if (source < getDouble(input, parsed.auxOffset + auxStart + i * 2)) {
                        branch = i;
                        break;
                    }
                }
                value = evaluateNode(input, parsed, input[edge + 1 + branch], blockX, blockY, blockZ, noise, values, states);
            }
            case OP_NOISE -> value = noise.sample(data0, blockX * p0, blockY * p1, blockZ * p0);
            case OP_SHIFT -> {
                double x = blockX * 0.25;
                double y = input[record + 7] == 0 ? blockY * 0.25 : 0.0;
                double z;
                if (input[record + 7] == 2) {
                    x = blockZ * 0.25;
                    y = blockX * 0.25;
                    z = 0.0;
                } else {
                    z = blockZ * 0.25;
                }
                value = noise.sample(data0, x, y, z) * 4.0;
            }
            case OP_SHIFTED_NOISE -> {
                double x = blockX * p0;
                double shiftX = evaluateNode(input, parsed, input[edge], blockX, blockY, blockZ, noise, values, states);
                x = x + shiftX;
                double y = blockY * p1;
                double shiftY = evaluateNode(input, parsed, input[edge + 1], blockX, blockY, blockZ, noise, values, states);
                y = y + shiftY;
                double z = blockZ * p0;
                double shiftZ = evaluateNode(input, parsed, input[edge + 2], blockX, blockY, blockZ, noise, values, states);
                z = z + shiftZ;
                value = noise.sample(data0, x, y, z);
            }
            case OP_BLENDED -> value = noise.sample(data0, blockX, blockY, blockZ);
            default -> throw new IllegalStateException("validated density opcode changed");
        }
        states[node] = 2;
        values[node] = value;
        return value;
    }

    private static double mapped(final int id, final double input) {
        return switch (id) {
            case 0 -> Math.abs(input);
            case 1 -> input * input;
            case 2 -> input * input * input;
            case 3 -> input > 0.0 ? input : input * 0.5;
            case 4 -> input > 0.0 ? input : input * 0.25;
            case 5 -> 1.0 / input;
            case 6 -> {
                double clamped = Mth.clamp(input, -1.0, 1.0);
                yield clamped / 2.0 - clamped * clamped * clamped / 24.0;
            }
            default -> throw new IllegalStateException("validated map opcode changed");
        };
    }

    private static List<Integer> postorder(final VanillaDensityGraph.Snapshot snapshot) {
        List<Integer> order = new ArrayList<>(snapshot.nodeCount());
        byte[] state = new byte[snapshot.nodeCount()];
        visit(snapshot, snapshot.root, 1, state, order);
        if (order.size() != snapshot.nodeCount()) throw invalid("snapshot contains unreachable nodes");
        return order;
    }

    private static void visit(
        final VanillaDensityGraph.Snapshot snapshot,
        final int node,
        final int depth,
        final byte[] state,
        final List<Integer> order
    ) {
        if (node < 0 || node >= state.length || depth > MAX_DEPTH) throw invalid("snapshot node or depth is outside W7 bounds");
        if (state[node] == 1) throw invalid("snapshot contains a cycle");
        if (state[node] == 2) return;
        state[node] = 1;
        for (int child : children(snapshot.instructions.get(node).op())) visit(snapshot, child, depth + 1, state, order);
        state[node] = 2;
        order.add(node);
    }

    private static int encodeNode(
        final int[] records,
        final int[] edges,
        final int[] aux,
        final int node,
        final int edgeCursor,
        final int auxCursor,
        final VanillaDensityGraph.Op op,
        final int[] remap
    ) {
        int record = node * NODE_WORDS;
        int[] childIds = children(op);
        records[record + 2] = edgeCursor;
        records[record + 3] = childIds.length;
        int nextEdge = edgeCursor;
        for (int child : childIds) edges[nextEdge++] = remap[child];
        if (op instanceof VanillaDensityGraph.Constant value) {
            records[record] = OP_CONSTANT;
            putDouble(records, record + 10, value.value());
        } else if (op instanceof VanillaDensityGraph.Alias value) {
            records[record] = OP_ALIAS;
        } else if (op instanceof VanillaDensityGraph.YGradient value) {
            records[record] = OP_Y_GRADIENT;
            records[record + 6] = value.fromY();
            records[record + 7] = value.toY();
            putDouble(records, record + 10, value.fromValue());
            putDouble(records, record + 12, value.toValue());
        } else if (op instanceof VanillaDensityGraph.Clamp value) {
            records[record] = OP_CLAMP;
            putDouble(records, record + 10, value.min());
            putDouble(records, record + 12, value.max());
        } else if (op instanceof VanillaDensityGraph.Mapped value) {
            records[record] = OP_MAPPED;
            records[record + 6] = value.kind().ordinal();
        } else if (op instanceof VanillaDensityGraph.Binary value) {
            records[record] = OP_BINARY;
            records[record + 6] = value.kind().ordinal();
            putDouble(records, record + 10, value.secondMin());
            putDouble(records, record + 12, value.secondMax());
        } else if (op instanceof VanillaDensityGraph.MulOrAdd value) {
            records[record] = OP_MUL_OR_ADD;
            records[record + 6] = value.kind() == VanillaDensityGraph.BinaryKind.MUL ? 0 : 1;
            putDouble(records, record + 10, value.argument());
        } else if (op instanceof VanillaDensityGraph.RangeChoice value) {
            records[record] = OP_RANGE_CHOICE;
            putDouble(records, record + 10, value.minInclusive());
            putDouble(records, record + 12, value.maxExclusive());
        } else if (op instanceof VanillaDensityGraph.IntervalSelect value) {
            records[record] = OP_INTERVAL_SELECT;
            records[record + 4] = auxCursor;
            records[record + 5] = value.thresholds().length * 2;
            int offset = auxCursor;
            for (double threshold : value.thresholds()) {
                putDouble(aux, offset, threshold);
                offset += 2;
            }
        } else if (op instanceof VanillaDensityGraph.Noise value) {
            records[record] = OP_NOISE;
            records[record + 6] = value.profile();
            putDouble(records, record + 10, value.xzScale());
            putDouble(records, record + 12, value.yScale());
        } else if (op instanceof VanillaDensityGraph.Shift value) {
            records[record] = OP_SHIFT;
            records[record + 6] = value.profile();
            records[record + 7] = value.kind().ordinal();
        } else if (op instanceof VanillaDensityGraph.ShiftedNoise value) {
            records[record] = OP_SHIFTED_NOISE;
            records[record + 6] = value.profile();
            putDouble(records, record + 10, value.xzScale());
            putDouble(records, record + 12, value.yScale());
        } else if (op instanceof VanillaDensityGraph.Blended value) {
            records[record] = OP_BLENDED;
            records[record + 6] = value.profile();
        } else {
            throw invalid("unsupported snapshot operation " + op.getClass().getName());
        }
        return nextEdge;
    }

    private static int childCount(final VanillaDensityGraph.Op op) {
        return children(op).length;
    }

    private static int[] children(final VanillaDensityGraph.Op op) {
        if (op instanceof VanillaDensityGraph.Constant || op instanceof VanillaDensityGraph.Noise
            || op instanceof VanillaDensityGraph.Shift || op instanceof VanillaDensityGraph.Blended) return new int[0];
        if (op instanceof VanillaDensityGraph.Alias value) return new int[] {value.input()};
        if (op instanceof VanillaDensityGraph.YGradient) return new int[0];
        if (op instanceof VanillaDensityGraph.Clamp value) return new int[] {value.input()};
        if (op instanceof VanillaDensityGraph.Mapped value) return new int[] {value.input()};
        if (op instanceof VanillaDensityGraph.Binary value) return new int[] {value.first(), value.second()};
        if (op instanceof VanillaDensityGraph.MulOrAdd value) return new int[] {value.input()};
        if (op instanceof VanillaDensityGraph.RangeChoice value) return new int[] {value.input(), value.whenInRange(), value.whenOutOfRange()};
        if (op instanceof VanillaDensityGraph.IntervalSelect value) {
            int[] result = new int[value.branches().length + 1];
            result[0] = value.input();
            System.arraycopy(value.branches(), 0, result, 1, value.branches().length);
            return result;
        }
        if (op instanceof VanillaDensityGraph.ShiftedNoise value) return new int[] {value.shiftX(), value.shiftY(), value.shiftZ()};
        throw invalid("unsupported snapshot operation " + op.getClass().getName());
    }

    private static void checkBlockCoordinate(final int value) {
        if (Math.abs((long)value) > MAX_BLOCK_COORDINATE) throw invalid("block coordinate exceeds W7 worldgen bounds");
    }

    private static int putDouble(final int[] words, final int offset, final double value) {
        long bits = Double.doubleToRawLongBits(value);
        words[offset] = (int)bits;
        words[offset + 1] = (int)(bits >>> 32);
        return offset + 2;
    }

    private static boolean isPositiveZero(final double value) {
        return Double.doubleToRawLongBits(value) == 0L;
    }

    private static boolean zeroParameters(final double first, final double second) {
        return isPositiveZero(first) && isPositiveZero(second);
    }

    private static boolean allZero(final int a, final int b, final int c, final int d) {
        return a == 0 && b == 0 && c == 0 && d == 0;
    }

    private static void requireNodeShape(final boolean valid, final String label) {
        if (!valid) throw invalid("malformed " + label + " density node");
    }

    private static long saturatedAdd(final long first, final long second) {
        if (first >= WORK_SATURATION || second >= WORK_SATURATION || first > WORK_SATURATION - second) return WORK_SATURATION;
        return first + second;
    }

    private static long saturatedMultiply(final long first, final long second) {
        if (first == 0 || second == 0) return 0;
        if (first >= WORK_SATURATION || second >= WORK_SATURATION || first > WORK_SATURATION / second) return WORK_SATURATION;
        return first * second;
    }

    private static int profileSectionOffset(final int[] input) {
        return input[15];
    }

    private static IllegalArgumentException invalid(final String message) {
        return new IllegalArgumentException(message);
    }

    private record RecordInfo(int type, int words) {}
    private record Bounds(double minimum, double maximum) {}
    private record ProfileSection(Map<Integer, RecordInfo> records, byte[] recordTypes) {}
    private record Parsed(
        int sampleCount,
        int nodeCount,
        int root,
        int nodeOffset,
        int edgeOffset,
        int auxOffset,
        int sampleOffset,
        int profileSectionOffset,
        int profileRootCount,
        int[] profileRootOffsets,
        byte[] profileTypes
    ) {}

    private static final class NoiseOracle {
        private final List<NoiseProfile> profiles;
        private final int[][] wires;

        private NoiseOracle(final List<NoiseProfile> profiles) {
            this.profiles = profiles;
            this.wires = new int[profiles.size()][];
        }

        private double sample(final int profileId, final double x, final double y, final double z) {
            int[] wire = this.wires[profileId];
            if (wire == null) {
                wire = VanillaNoiseBatch.input(new NoiseSample(this.profiles.get(profileId), 0.0, 0.0, 0.0, 0.0, 0.0));
                this.wires[profileId] = wire;
            }
            int offset = wire[4] + W5_PROFILE_SAMPLE_OFFSET;
            putDouble(wire, offset, x);
            putDouble(wire, offset + 2, y);
            putDouble(wire, offset + 4, z);
            return VanillaNoiseBatch.outputValue(VanillaNoiseBatch.reference(wire), 0);
        }
    }
}
