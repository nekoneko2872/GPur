package org.gpur.antixray;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.papermc.paper.antixray.BitStorageWriter;
import io.papermc.paper.antixray.ChunkPacketBlockControllerAntiXray;
import io.papermc.paper.antixray.ChunkPacketInfoAntiXray;
import io.papermc.paper.configuration.type.EngineMode;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.GlobalPalette;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.Palette;
import org.bukkit.World;
import org.bukkit.support.environment.VanillaFeature;
import org.gpur.GPurConfig;
import org.gpur.GPurServices;
import org.gpur.compute.ComputeService;
import org.gpur.compute.ExactCompute;
import org.junit.jupiter.api.Test;

/** Compares the actual Paper algorithm with the adapter, independently of GPU availability. */
@VanillaFeature
class AntiXrayParityTest {
    private static void field(Object object, String name, Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        // Mockito's inline mock has the original runtime class.
        field.setAccessible(true);
        field.set(object, value);
    }

    private static ComputeService swapService(ComputeService value) throws Exception {
        Field field = GPurServices.class.getDeclaredField("compute");
        field.setAccessible(true);
        ComputeService previous = (ComputeService)field.get(null);
        field.set(null, value);
        return previous;
    }

    private static ChunkPacketBlockControllerAntiXray controller(int maxHeight) throws Exception {
        var controller = mock(ChunkPacketBlockControllerAntiXray.class, CALLS_REAL_METHODS);
        field(controller, "engineMode", EngineMode.HIDE);
        field(controller, "maxBlockHeight", maxHeight);
        field(controller, "presetBlockStateBits", ThreadLocal.withInitial(() -> new int[1]));
        field(controller, "gpurPackets", new AtomicLong(1)); // Compare results without relying on runtime sampling.
        field(controller, "emptyNearbyChunkSections", new LevelChunkSection[4]);
        field(controller, "presetBlockStateBitsStoneGlobal", new int[]{Block.getId(Blocks.STONE.defaultBlockState())});
        field(controller, "presetBlockStateBitsDeepslateGlobal", new int[]{Block.getId(Blocks.DEEPSLATE.defaultBlockState())});
        field(controller, "presetBlockStateBitsNetherrackGlobal", new int[]{Block.getId(Blocks.NETHERRACK.defaultBlockState())});
        field(controller, "presetBlockStateBitsEndStoneGlobal", new int[]{Block.getId(Blocks.END_STONE.defaultBlockState())});
        boolean[] solid = new boolean[Block.BLOCK_STATE_REGISTRY.size()];
        boolean[] hidden = new boolean[solid.length];
        java.util.Arrays.fill(solid, true);
        solid[Block.getId(Blocks.AIR.defaultBlockState())] = false;
        hidden[Block.getId(Blocks.DIAMOND_ORE.defaultBlockState())] = true;
        field(controller, "solidGlobal", solid);
        field(controller, "obfuscateGlobal", hidden);
        return controller;
    }

    private record Fixture(ChunkPacketInfoAntiXray info, ClientboundLevelChunkWithLightPacket packet) {}

    private static Fixture fixture(ChunkPacketBlockControllerAntiXray controller, long seed, boolean global,
                                   int minSection, int count, int unwritten, World.Environment environment) {
        var world = mock(ServerLevel.class);
        var bukkitWorld = mock(org.bukkit.craftbukkit.CraftWorld.class);
        when(world.getWorld()).thenReturn(bukkitWorld);
        when(bukkitWorld.getEnvironment()).thenReturn(environment);
        var chunk = mock(LevelChunk.class);
        when(chunk.getLevel()).thenReturn(world);
        when(chunk.getSectionsCount()).thenReturn(count);
        when(chunk.getMinSectionY()).thenReturn(minSection);
        Random random = new Random(seed);
        BlockState replacement = switch (environment) {
            case NETHER -> Blocks.NETHERRACK.defaultBlockState();
            case THE_END -> Blocks.END_STONE.defaultBlockState();
            default -> minSection < 0 ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState();
        };
        BlockState[] states = {Blocks.AIR.defaultBlockState(), replacement, Blocks.DIAMOND_ORE.defaultBlockState()};
        LevelChunkSection[] sections = new LevelChunkSection[count];
        byte[] buffer = new byte[count * (global ? 8192 : 2048)];
        var packet = mock(ClientboundLevelChunkWithLightPacket.class);
        var info = new ChunkPacketInfoAntiXray(packet, chunk, controller);
        info.setBuffer(buffer);
        for (int section = 0; section < count; section++) {
            int[] blocks = new int[4096];
            for (int i = 0; i < blocks.length; i++) blocks[i] = random.nextInt(20) == 0 ? 0 : random.nextBoolean() ? 1 : 2;
            var chunkSection = mock(LevelChunkSection.class);
            when(chunkSection.getBlockState(anyInt(), anyInt(), anyInt())).thenAnswer(inv ->
                states[blocks[((int)inv.getArgument(1) << 8) | ((int)inv.getArgument(2) << 4) | (int)inv.getArgument(0)]]);
            sections[section] = chunkSection;
            int bits = global ? 16 : 4;
            int index = section * (global ? 8192 : 2048);
            BitStorageWriter writer = new BitStorageWriter();
            writer.setBuffer(buffer);
            writer.setBits(bits);
            writer.setIndex(index);
            for (int state : blocks) writer.write(global ? Block.getId(states[state]) : state);
            writer.flush();
            if (section == unwritten) continue;
            Palette<BlockState> palette;
            if (global) palette = new GlobalPalette<>(Block.BLOCK_STATE_REGISTRY);
            else {
                palette = mock(Palette.class);
                when(palette.getSize()).thenReturn(3);
                when(palette.valueFor(anyInt())).thenAnswer(inv -> states[(int)inv.getArgument(0)]);
                when(palette.idFor(any(), any())).thenAnswer(inv -> {
                    for (int i = 0; i < states.length; i++) if (states[i] == inv.getArgument(0)) return i;
                    throw new AssertionError("Unexpected palette insertion");
                });
            }
            info.setBits(section, bits);
            info.setIndex(section, index);
            info.setPalette(section, palette);
            info.setPresetValues(section, new BlockState[]{replacement});
        }
        when(chunk.getSections()).thenReturn(sections);
        LevelChunk[] nearby = new LevelChunk[4];
        for (int direction = 0; direction < 4; direction++) {
            if (direction == (int)(seed % 5)) continue; // Missing-neighbor boundaries also matter.
            var neighbor = mock(LevelChunk.class);
            LevelChunkSection[] neighborSections = new LevelChunkSection[count];
            for (int section = 0; section < count; section++) {
                var neighborSection = mock(LevelChunkSection.class);
                when(neighborSection.getBlockState(anyInt(), anyInt(), anyInt())).thenAnswer(inv ->
                    (((int)inv.getArgument(0) + (int)inv.getArgument(1) + (int)inv.getArgument(2)) % 7 == 0) ? states[0] : states[1]);
                neighborSections[section] = neighborSection;
            }
            when(neighbor.getSections()).thenReturn(neighborSections);
            nearby[direction] = neighbor;
        }
        info.setNearbyChunks(nearby);
        return new Fixture(info, packet);
    }

    private static byte[] cpu(ChunkPacketBlockControllerAntiXray controller, Fixture fixture) throws Exception {
        Method method = controller.getClass().getDeclaredMethod("obfuscateCpu", ChunkPacketInfoAntiXray.class);
        method.setAccessible(true);
        method.invoke(controller, fixture.info());
        return fixture.info().getBuffer();
    }

    @Test void packetBytesMatchPaperAcrossPalettesHeightsDimensionsAndSectionGaps() throws Exception {
        ComputeService service = mock(ComputeService.class);
        when(service.eligible(2)).thenReturn(true);
        when(service.tryCompute(any())).thenAnswer(inv -> ExactCompute.reference(inv.getArgument(0)));
        ComputeService previous = swapService(service);
        GPurConfig.antiXrayGpuEnabled = true;
        GPurConfig.antiXrayGpuMinSections = 1;
        try {
            for (boolean global : new boolean[]{false, true}) {
                for (World.Environment environment : new World.Environment[]{World.Environment.NORMAL, World.Environment.NETHER, World.Environment.THE_END}) {
                    for (int gap : new int[]{-1, 1}) {
                        var controller = controller(64);
                        for (int seed = 0; seed < 4; seed++) {
                            Fixture expected = fixture(controller, seed, global, -4, 3, gap, environment);
                            Fixture actual = fixture(controller, seed, global, -4, 3, gap, environment);
                            byte[] reference = cpu(controller, expected);
                            controller.obfuscate(actual.info());
                            assertArrayEquals(reference, actual.info().getBuffer(), "global=" + global + ", env=" + environment + ", gap=" + gap + ", seed=" + seed);
                            verify(actual.packet(), times(1)).setReady(true);
                        }
                    }
                }
            }
            verify(service, atLeastOnce()).tryCompute(any());
            verify(service, never()).rejectAntiXray();
        } finally {
            swapService(previous);
        }
    }

    @Test void invalidResultCannotLeaveAPartiallyMutatedPacket() throws Exception {
        ComputeService service = mock(ComputeService.class);
        when(service.eligible(2)).thenReturn(true);
        when(service.tryCompute(any())).thenReturn(new int[1]);
        ComputeService previous = swapService(service);
        GPurConfig.antiXrayGpuEnabled = true;
        GPurConfig.antiXrayGpuMinSections = 1;
        try {
            var controller = controller(64);
            Fixture expected = fixture(controller, 9, false, -4, 3, -1, World.Environment.NORMAL);
            Fixture actual = fixture(controller, 9, false, -4, 3, -1, World.Environment.NORMAL);
            byte[] reference = cpu(controller, expected);
            controller.obfuscate(actual.info());
            assertArrayEquals(reference, actual.info().getBuffer());
            verify(actual.packet(), times(1)).setReady(true);
        } finally {
            swapService(previous);
        }
    }
}
