package org.gpur.terrain;

import java.nio.file.Path;
import java.util.Random;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

/** Bukkit adapter for the versioned GPur terrain palette. */
public final class GPurTerrainGenerator extends ChunkGenerator {
    private static final Material[] MATERIALS = {
        Material.AIR,
        Material.STONE,
        Material.DEEPSLATE,
        Material.BEDROCK,
        Material.WATER,
        Material.LAVA,
        Material.DIRT,
        Material.GRASS_BLOCK,
        Material.SAND,
        Material.SANDSTONE,
        Material.GRAVEL,
        Material.SNOW_BLOCK,
        Material.ICE
    };

    private final Path worldDirectory;
    private volatile TerrainWorldRegistry.TerrainWorldSpec worldSpec;

    GPurTerrainGenerator(Path worldDirectory, TerrainWorldRegistry.TerrainWorldSpec storedSpec) {
        this.worldDirectory = worldDirectory;
        this.worldSpec = storedSpec;
    }

    @Override
    public void generateNoise(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData chunkData) {
        TerrainWorldRegistry.TerrainWorldSpec spec = validateWorldInfo(worldInfo);
        if (chunkData.getMinHeight() != spec.minY() || chunkData.getMaxHeight() != spec.minY() + spec.height()) {
            throw new IllegalStateException("Chunk height range does not match the permanent GPur terrain marker for " + this.worldDirectory);
        }

        int[] input = TerrainRules.input(spec.seed(), spec.minY(), spec.height(), spec.seaLevel(), chunkX, chunkZ);
        // Retired custom worlds keep their original rules solely to avoid changing saved-world seams.
        int[] palette = TerrainRules.reference(input);
        int expectedLength = spec.height() * 256;
        if (palette == null || palette.length != expectedLength) {
            throw new IllegalStateException("Terrain scheduler returned " + (palette == null ? "null" : palette.length)
                + " palette entries for chunk " + chunkX + "," + chunkZ + "; expected " + expectedLength);
        }

        writePalette(chunkData, palette, spec.minY(), spec.height());
    }

    private static void writePalette(ChunkData chunkData, int[] palette, int minY, int height) {
        // TerrainRules stores each horizontal 16x16 layer in z-major order.
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int yIndex = 0;
                while (yIndex < height) {
                    int paletteId = palette[yIndex * 256 + z * 16 + x];
                    if (paletteId < 0 || paletteId >= MATERIALS.length) {
                        throw new IllegalStateException("Terrain rules emitted unknown block palette id " + paletteId);
                    }
                    int end = yIndex + 1;
                    while (end < height && palette[end * 256 + z * 16 + x] == paletteId) {
                        end++;
                    }
                    if (paletteId != TerrainRules.AIR) {
                        chunkData.setRegion(x, minY + yIndex, z, x + 1, minY + end, z + 1, MATERIALS[paletteId]);
                    }
                    yIndex = end;
                }
            }
        }
    }

    @Override
    public BiomeProvider getDefaultBiomeProvider(WorldInfo worldInfo) {
        validateWorldInfo(worldInfo);
        return new GPurTerrainBiomeProvider(this);
    }

    @Override
    public int getBaseHeight(WorldInfo worldInfo, Random random, int x, int z, HeightMap heightMap) {
        TerrainWorldRegistry.TerrainWorldSpec spec = validateWorldInfo(worldInfo);
        int topSolid = TerrainRules.surfaceHeight(spec.seed(), x, z, spec.minY(), spec.height(), spec.seaLevel());
        boolean includesFluids = heightMap == HeightMap.WORLD_SURFACE
            || heightMap == HeightMap.WORLD_SURFACE_WG
            || heightMap == HeightMap.MOTION_BLOCKING
            || heightMap == HeightMap.MOTION_BLOCKING_NO_LEAVES;
        return includesFluids ? Math.max(topSolid, spec.seaLevel()) + 1 : topSolid + 1;
    }

    /** Return the deterministic palette column used by Craft's internal base-column adapter. */
    public int[] baseColumn(WorldInfo worldInfo, int x, int z) {
        TerrainWorldRegistry.TerrainWorldSpec spec = validateWorldInfo(worldInfo);
        return TerrainRules.column(spec.seed(), x, z, spec.minY(), spec.height(), spec.seaLevel());
    }

    @Override
    public boolean shouldGenerateNoise(WorldInfo worldInfo, Random random, int chunkX, int chunkZ) {
        validateWorldInfo(worldInfo);
        return false;
    }

    @Override
    public boolean shouldGenerateSurface(WorldInfo worldInfo, Random random, int chunkX, int chunkZ) {
        validateWorldInfo(worldInfo);
        return false;
    }

    @Override
    public boolean shouldGenerateCaves(WorldInfo worldInfo, Random random, int chunkX, int chunkZ) {
        validateWorldInfo(worldInfo);
        return false;
    }

    @Override
    public boolean shouldGenerateDecorations(WorldInfo worldInfo, Random random, int chunkX, int chunkZ) {
        validateWorldInfo(worldInfo);
        return true;
    }

    @Override
    public boolean shouldGenerateMobs(WorldInfo worldInfo, Random random, int chunkX, int chunkZ) {
        validateWorldInfo(worldInfo);
        return true;
    }

    @Override
    public boolean shouldGenerateStructures(WorldInfo worldInfo, Random random, int chunkX, int chunkZ) {
        validateWorldInfo(worldInfo);
        return true;
    }

    TerrainWorldRegistry.TerrainWorldSpec validateWorldInfo(WorldInfo worldInfo) {
        TerrainWorldRegistry.TerrainWorldSpec requested = TerrainWorldRegistry.specFor(worldInfo);
        TerrainWorldRegistry.TerrainWorldSpec stored = this.worldSpec;
        if (stored == null) {
            synchronized (this) {
                stored = this.worldSpec;
                if (stored == null) {
                    stored = TerrainWorldRegistry.validateOrCreate(this.worldDirectory, requested);
                    this.worldSpec = stored;
                }
            }
        }
        stored.requireMatches(requested, this.worldDirectory);
        return stored;
    }
}
