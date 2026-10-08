package org.gpur.terrain;

import java.util.List;
import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.WorldInfo;

/** Deterministic biome adapter for the fixed GPur terrain biome palette. */
public final class GPurTerrainBiomeProvider extends BiomeProvider {
    private static final Biome[] BIOMES_BY_ID = {
        Biome.PLAINS,
        Biome.FOREST,
        Biome.DESERT,
        Biome.TAIGA,
        Biome.SNOWY_PLAINS,
        Biome.OCEAN,
        Biome.MEADOW,
        Biome.STONY_PEAKS
    };
    private static final List<Biome> DECLARED_BIOMES = List.of(BIOMES_BY_ID);

    private final GPurTerrainGenerator generator;

    public GPurTerrainBiomeProvider(GPurTerrainGenerator generator) {
        this.generator = generator;
    }

    @Override
    public Biome getBiome(WorldInfo worldInfo, int x, int y, int z) {
        TerrainWorldRegistry.TerrainWorldSpec spec = this.generator.validateWorldInfo(worldInfo);
        int biome = TerrainRules.biome(spec.seed(), x, z, spec.minY(), spec.height(), spec.seaLevel());
        if (biome < 0 || biome >= BIOMES_BY_ID.length) {
            throw new IllegalStateException("Terrain rules emitted unknown biome palette id " + biome);
        }
        return BIOMES_BY_ID[biome];
    }

    @Override
    public List<Biome> getBiomes(WorldInfo worldInfo) {
        this.generator.validateWorldInfo(worldInfo);
        return DECLARED_BIOMES;
    }
}
