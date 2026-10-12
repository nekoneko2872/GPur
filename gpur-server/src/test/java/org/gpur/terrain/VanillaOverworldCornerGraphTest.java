package org.gpur.terrain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.bukkit.support.environment.VanillaFeature;
import org.junit.jupiter.api.Test;

/** CPU and wire closure gates; no hardware acceleration or production adoption is claimed here. */
@VanillaFeature
class VanillaOverworldCornerGraphTest {
    @Test
    void allEightDefaultOverworldRootsMatchUntouchedCpuCornerSlices() throws Exception {
        for (int[] origin : new int[][] {{0, 0}, {-32, -48}}) {
            List<VanillaOverworldCornerGraphFixture.RootCase> roots =
                VanillaOverworldCornerGraphFixture.capture(origin[0], origin[1]);
            assertEquals(8, roots.size());
            int splinePoints = 0;
            boolean includesFlatTable = false;
            for (VanillaOverworldCornerGraphFixture.RootCase root : roots) {
                assertEquals(1_225, root.samples().size());
                splinePoints += root.graph().splinePointCount();
                includesFlatTable |= root.graph().tableValueCount() > 0;
                int[] expected = root.expectedWords();
                int[] actual = new int[expected.length];
                for (int i = 0; i < root.samples().size(); i++) {
                    VanillaDensityGraphBatch.Sample position = root.samples().get(i);
                    long bits = Double.doubleToRawLongBits(root.graph().evaluate(position.x(), position.y(), position.z()));
                    actual[i * 2] = (int)bits;
                    actual[i * 2 + 1] = (int)(bits >>> 32);
                }
                assertArrayEquals(expected, actual, "raw CPU graph corners: root=" + root.rootIndex()
                    + " origin=" + origin[0] + "," + origin[1]);
                // Four complete XZ columns fit the bounded native-job budget. Cover every
                // corner without weakening the exporter limits for the larger root corpus.
                int samplesPerBatch = 4 * 49;
                for (int start = 0; start < root.samples().size(); start += samplesPerBatch) {
                    int end = Math.min(root.samples().size(), start + samplesPerBatch);
                    int[] input = VanillaDensityGraphBatch.input(root.graph(), root.samples().subList(start, end));
                    assertArrayEquals(Arrays.copyOfRange(expected, start * 2, end * 2),
                        VanillaDensityGraphBatch.reference(input), "raw wire corners: root=" + root.rootIndex()
                            + " origin=" + origin[0] + "," + origin[1] + " samples=" + start + ".." + end);
                }
            }
            // The original owner already evaluated terrain splines into FlatCache tables.
            // Preserve those table values; dedicated TerrainProvider fixtures test the
            // spline opcodes without inventing extra work in the default corner graph.
            assertEquals(0, splinePoints, "default terrain splines are captured behind FlatCache tables");
            assertTrue(includesFlatTable, "closure must exercise captured runtime FlatCache tables");
        }
    }
}
