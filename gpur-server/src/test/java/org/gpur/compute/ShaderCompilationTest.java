package org.gpur.compute;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

/** Checks the shipped shader using the actual CPU compiler, without initializing Vulkan or a GPU. */
class ShaderCompilationTest {
    @Test void allShippedWorkloadsCompileToSpirv() throws Exception {
        var compiler = VulkanDevice.class.getDeclaredMethod("compileShader", String.class);
        compiler.setAccessible(true);
        for (String resource : VulkanShaderSources.ENTRY_POINTS) {
            ByteBuffer spirv = (ByteBuffer)compiler.invoke(null, resource);
            try {
                assertTrue(spirv.remaining() > 20, resource);
                assertEquals(0x07230203, spirv.order(ByteOrder.LITTLE_ENDIAN).getInt(0), resource);
            } finally { MemoryUtil.memFree(spirv); }
        }
    }
}
