package org.gpur.generation;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

record GPurNoiseStageContext(ServerLevel level, ChunkPos chunkPos) {
}
