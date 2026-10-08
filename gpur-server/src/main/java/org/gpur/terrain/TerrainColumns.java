package org.gpur.terrain;

import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.generator.WorldInfo;

/** Supplies the actual custom column to Minecraft structure/spawn queries. */
public final class TerrainColumns {
    private static final BlockState[] PALETTE = {
        Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState(), Blocks.DEEPSLATE.defaultBlockState(),
        Blocks.BEDROCK.defaultBlockState(), Blocks.WATER.defaultBlockState(), Blocks.LAVA.defaultBlockState(),
        Blocks.DIRT.defaultBlockState(), Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.SAND.defaultBlockState(),
        Blocks.SANDSTONE.defaultBlockState(), Blocks.GRAVEL.defaultBlockState(), Blocks.SNOW_BLOCK.defaultBlockState(),
        Blocks.ICE.defaultBlockState()
    };

    private TerrainColumns() {}

    public static NoiseColumn column(GPurTerrainGenerator generator, WorldInfo worldInfo, int x, int z) {
        int[] blocks = generator.baseColumn(worldInfo, x, z);
        BlockState[] states = new BlockState[blocks.length];
        for (int i = 0; i < blocks.length; i++) states[i] = PALETTE[blocks[i]];
        return new NoiseColumn(worldInfo.getMinHeight(), states);
    }
}
