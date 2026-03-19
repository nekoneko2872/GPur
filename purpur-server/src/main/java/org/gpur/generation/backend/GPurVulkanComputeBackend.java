package org.gpur.generation.backend;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.system.MemoryUtil.memByteBuffer;
import static org.lwjgl.system.MemoryUtil.memSet;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compilation_status_success;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compile_into_spv;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compiler_initialize;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compiler_release;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compute_shader;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_bytes;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_compilation_status;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_error_message;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_result_release;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT;
import static org.lwjgl.vulkan.VK10.VK_NULL_HANDLE;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_COMPUTE;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_COMPUTE_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_COMPUTE_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHARING_MODE_EXCLUSIVE;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_APPLICATION_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_SUBMIT_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkAllocateCommandBuffers;
import static org.lwjgl.vulkan.VK10.vkAllocateDescriptorSets;
import static org.lwjgl.vulkan.VK10.vkAllocateMemory;
import static org.lwjgl.vulkan.VK10.vkBeginCommandBuffer;
import static org.lwjgl.vulkan.VK10.vkBindBufferMemory;
import static org.lwjgl.vulkan.VK10.vkCmdBindDescriptorSets;
import static org.lwjgl.vulkan.VK10.vkCmdBindPipeline;
import static org.lwjgl.vulkan.VK10.vkCmdDispatch;
import static org.lwjgl.vulkan.VK10.vkCreateBuffer;
import static org.lwjgl.vulkan.VK10.vkCreateCommandPool;
import static org.lwjgl.vulkan.VK10.vkCreateComputePipelines;
import static org.lwjgl.vulkan.VK10.vkCreateDescriptorPool;
import static org.lwjgl.vulkan.VK10.vkCreateDescriptorSetLayout;
import static org.lwjgl.vulkan.VK10.vkCreateDevice;
import static org.lwjgl.vulkan.VK10.vkCreateFence;
import static org.lwjgl.vulkan.VK10.vkCreateInstance;
import static org.lwjgl.vulkan.VK10.vkCreatePipelineLayout;
import static org.lwjgl.vulkan.VK10.vkCreateShaderModule;
import static org.lwjgl.vulkan.VK10.vkDestroyBuffer;
import static org.lwjgl.vulkan.VK10.vkDestroyCommandPool;
import static org.lwjgl.vulkan.VK10.vkDestroyDescriptorPool;
import static org.lwjgl.vulkan.VK10.vkDestroyDescriptorSetLayout;
import static org.lwjgl.vulkan.VK10.vkDestroyDevice;
import static org.lwjgl.vulkan.VK10.vkDestroyFence;
import static org.lwjgl.vulkan.VK10.vkDestroyInstance;
import static org.lwjgl.vulkan.VK10.vkDestroyPipeline;
import static org.lwjgl.vulkan.VK10.vkDestroyPipelineLayout;
import static org.lwjgl.vulkan.VK10.vkDestroyShaderModule;
import static org.lwjgl.vulkan.VK10.vkDeviceWaitIdle;
import static org.lwjgl.vulkan.VK10.vkEndCommandBuffer;
import static org.lwjgl.vulkan.VK10.vkEnumeratePhysicalDevices;
import static org.lwjgl.vulkan.VK10.vkFreeCommandBuffers;
import static org.lwjgl.vulkan.VK10.vkFreeMemory;
import static org.lwjgl.vulkan.VK10.vkGetBufferMemoryRequirements;
import static org.lwjgl.vulkan.VK10.vkGetDeviceQueue;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceMemoryProperties;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceProperties;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceQueueFamilyProperties;
import static org.lwjgl.vulkan.VK10.vkMapMemory;
import static org.lwjgl.vulkan.VK10.vkQueueSubmit;
import static org.lwjgl.vulkan.VK10.vkResetCommandBuffer;
import static org.lwjgl.vulkan.VK10.vkResetFences;
import static org.lwjgl.vulkan.VK10.vkUnmapMemory;
import static org.lwjgl.vulkan.VK10.vkUpdateDescriptorSets;
import static org.lwjgl.vulkan.VK10.vkWaitForFences;
import static org.lwjgl.vulkan.VK11.VK_API_VERSION_1_1;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.gpur.generation.GPurBatchRequest;
import org.gpur.generation.GPurBatchResult;
import org.gpur.generation.GPurChunkTerrainData;
import org.gpur.generation.GPurChunkWorkItem;
import org.gpur.generation.GPurComputeMode;
import org.gpur.generation.GPurTerrainProfile;
import org.gpur.gpu.GPurAntiXrayBatchRequest;
import org.gpur.gpu.GPurAntiXrayBatchResult;
import org.gpur.gpu.GPurAntiXraySectionRequest;
import org.gpur.gpu.GPurAntiXraySectionResult;
import org.gpur.gpu.GPurMobSpawnBatchResult;
import org.gpur.gpu.GPurStructureScanResult;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

public final class GPurVulkanComputeBackend implements GPurComputeBackend {
    private static final String SHADER_RESOURCE = "shaders/gpur/noise_batch.comp";
    private static final int WORKLOAD_TERRAIN = 1;
    private static final int WORKLOAD_ANTI_XRAY = 2;
    private static final int WORKLOAD_STRUCTURE_SCAN = 3;
    private static final int WORKLOAD_MOB_SPAWN = 4;
    private static final int WORKLOAD_NOISE_INTERPOLATION = 5;
    private static final int HEADER_INTS = 16;
    private static final int CHUNK_INPUT_STRIDE_INTS = 2;
    private static final int LOCAL_SIZE_X = 64;
    private static final int ANTI_XRAY_SECTION_VOLUME = 16 * 16 * 16;
    private static final int ANTI_XRAY_PADDED_VOLUME = 18 * 18 * 18;
    private static final int STRUCTURE_SCAN_STRIDE_INTS = 2;
    private static final int MOB_SPAWN_VEC3_STRIDE_INTS = 3;
    private static final int TERRAIN_SURFACE_WORDS_PER_CHUNK = 128;
    private static final int INTERPOLATION_CORNERS_PER_CELL = 8;
    private static final int DEFAULT_EXECUTION_CONTEXTS = 3;
    private static final int BUFFER_ALIGNMENT = 4096;
    private static final long COMPUTE_WAIT_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(2L);

    private final String deviceName;
    private final VkInstance instance;
    private final VkPhysicalDevice physicalDevice;
    private final VkDevice device;
    private final VkQueue computeQueue;
    private final long descriptorSetLayout;
    private final long pipelineLayout;
    private final long pipeline;
    private final List<ExecutionContext> executionContexts;
    private final BlockingQueue<ExecutionContext> availableExecutionContexts;
    private final ExecutorService dispatchExecutor;
    private final AtomicInteger inFlightBatches = new AtomicInteger();
    private final Object queueSubmitLock = new Object();

    private GPurVulkanComputeBackend(
        final String deviceName,
        final VkInstance instance,
        final VkPhysicalDevice physicalDevice,
        final VkDevice device,
        final VkQueue computeQueue,
        final long descriptorSetLayout,
        final long pipelineLayout,
        final long pipeline,
        final List<ExecutionContext> executionContexts
    ) {
        this.deviceName = deviceName;
        this.instance = instance;
        this.physicalDevice = physicalDevice;
        this.device = device;
        this.computeQueue = computeQueue;
        this.descriptorSetLayout = descriptorSetLayout;
        this.pipelineLayout = pipelineLayout;
        this.pipeline = pipeline;
        this.executionContexts = executionContexts;
        this.availableExecutionContexts = new ArrayBlockingQueue<>(executionContexts.size(), true, executionContexts);
        this.dispatchExecutor = Executors.newFixedThreadPool(executionContexts.size(), new ThreadFactory() {
            private final AtomicInteger threadIds = new AtomicInteger();

            @Override
            public Thread newThread(final Runnable runnable) {
                final Thread thread = new Thread(runnable, "GPur-Vulkan-Compute-" + this.threadIds.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    public static GPurVulkanComputeBackend create() {
        VkInstance instance = null;
        VkPhysicalDevice physicalDevice = null;
        VkDevice device = null;
        long shaderModule = VK_NULL_HANDLE;
        long descriptorSetLayout = VK_NULL_HANDLE;
        long pipelineLayout = VK_NULL_HANDLE;
        long pipeline = VK_NULL_HANDLE;
        List<ExecutionContext> executionContexts = List.of();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkApplicationInfo applicationInfo = VkApplicationInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                .pApplicationName(stack.UTF8("GPur"))
                .pEngineName(stack.UTF8("GPur"))
                .apiVersion(VK_API_VERSION_1_1);

            final VkInstanceCreateInfo instanceCreateInfo = VkInstanceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                .pApplicationInfo(applicationInfo);

            final PointerBuffer instanceHandle = stack.mallocPointer(1);
            checkVk(vkCreateInstance(instanceCreateInfo, null, instanceHandle), "create Vulkan instance");
            instance = new VkInstance(instanceHandle.get(0), instanceCreateInfo);

            final PhysicalDeviceSelection selectedDevice = selectPhysicalDevice(instance, stack);
            physicalDevice = selectedDevice.device();

            final VkDeviceQueueCreateInfo.Buffer queueCreateInfos = VkDeviceQueueCreateInfo.calloc(1, stack);
            queueCreateInfos.get(0)
                .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                .queueFamilyIndex(selectedDevice.queueFamilyIndex())
                .pQueuePriorities(stack.floats(1.0F));

            final VkDeviceCreateInfo deviceCreateInfo = VkDeviceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                .pQueueCreateInfos(queueCreateInfos);

            final PointerBuffer deviceHandle = stack.mallocPointer(1);
            checkVk(vkCreateDevice(selectedDevice.device(), deviceCreateInfo, null, deviceHandle), "create Vulkan logical device");
            device = new VkDevice(deviceHandle.get(0), selectedDevice.device(), deviceCreateInfo);

            final PointerBuffer queueHandle = stack.mallocPointer(1);
            vkGetDeviceQueue(device, selectedDevice.queueFamilyIndex(), 0, queueHandle);
            final VkQueue computeQueue = new VkQueue(queueHandle.get(0), device);

            descriptorSetLayout = createDescriptorSetLayout(device, stack);

            final ByteBuffer spirv = compileShader();
            try {
                shaderModule = createShaderModule(device, spirv, stack);
            } finally {
                org.lwjgl.system.MemoryUtil.memFree(spirv);
            }

            final LongBuffer pipelineLayoutHandle = stack.mallocLong(1);
            final VkPipelineLayoutCreateInfo pipelineLayoutCreateInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                .pSetLayouts(stack.longs(descriptorSetLayout));
            checkVk(vkCreatePipelineLayout(device, pipelineLayoutCreateInfo, null, pipelineLayoutHandle), "create Vulkan pipeline layout");
            pipelineLayout = pipelineLayoutHandle.get(0);

            final VkPipelineShaderStageCreateInfo shaderStageCreateInfo = VkPipelineShaderStageCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                .stage(VK_SHADER_STAGE_COMPUTE_BIT)
                .module(shaderModule)
                .pName(stack.UTF8("main"));

            final VkComputePipelineCreateInfo.Buffer pipelineCreateInfos = VkComputePipelineCreateInfo.calloc(1, stack);
            pipelineCreateInfos.get(0)
                .sType(VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO)
                .stage(shaderStageCreateInfo)
                .layout(pipelineLayout);

            final LongBuffer pipelineHandle = stack.mallocLong(1);
            checkVk(vkCreateComputePipelines(device, VK_NULL_HANDLE, pipelineCreateInfos, null, pipelineHandle), "create Vulkan compute pipeline");
            pipeline = pipelineHandle.get(0);

            executionContexts = createExecutionContexts(device, physicalDevice, descriptorSetLayout, selectedDevice.queueFamilyIndex());

            vkDestroyShaderModule(device, shaderModule, null);
            shaderModule = VK_NULL_HANDLE;

            return new GPurVulkanComputeBackend(
                selectedDevice.deviceName(),
                instance,
                physicalDevice,
                device,
                computeQueue,
                descriptorSetLayout,
                pipelineLayout,
                pipeline,
                executionContexts
            );
        } catch (final RuntimeException ex) {
            destroyExecutionContexts(executionContexts);
            if (shaderModule != VK_NULL_HANDLE && device != null) {
                vkDestroyShaderModule(device, shaderModule, null);
            }
            if (pipeline != VK_NULL_HANDLE && device != null) {
                vkDestroyPipeline(device, pipeline, null);
            }
            if (pipelineLayout != VK_NULL_HANDLE && device != null) {
                vkDestroyPipelineLayout(device, pipelineLayout, null);
            }
            if (descriptorSetLayout != VK_NULL_HANDLE && device != null) {
                vkDestroyDescriptorSetLayout(device, descriptorSetLayout, null);
            }
            if (device != null) {
                vkDestroyDevice(device, null);
            }
            if (instance != null) {
                vkDestroyInstance(instance, null);
            }
            throw ex;
        }
    }

    @Override
    public GPurComputeMode mode() {
        return GPurComputeMode.VULKAN;
    }

    @Override
    public String deviceName() {
        return this.deviceName;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public OptionalInt currentUtilizationPercent() {
        final int contexts = Math.max(1, this.executionContexts.size());
        final int utilization = (int)Math.ceil((this.inFlightBatches.get() * 100.0D) / contexts);
        return OptionalInt.of(Math.min(100, utilization));
    }

    @Override
    public int busyExecutionContexts() {
        return Math.min(this.inFlightBatches.get(), this.executionContexts.size());
    }

    @Override
    public int maxTerrainBatchesInFlight() {
        return this.executionContexts.size();
    }

    @Override
    public CompletableFuture<GPurBatchResult> submitBatch(final GPurBatchRequest request) {
        return CompletableFuture.supplyAsync(() -> this.withExecutionContext(context -> this.executeTerrainBatch(request, context)), this.dispatchExecutor);
    }

    @Override
    public GPurAntiXrayBatchResult submitAntiXrayBatch(final GPurAntiXrayBatchRequest request) {
        return this.tryWithExecutionContext(context -> this.executeAntiXrayBatch(request, context));
    }

    @Override
    public GPurStructureScanResult scanStructureCandidates(
        final int originBlockX,
        final int originBlockZ,
        final List<BlockPos> candidatePositions
    ) {
        return this.tryWithExecutionContext(context -> this.executeStructureScan(originBlockX, originBlockZ, candidatePositions, context));
    }

    @Override
    public GPurMobSpawnBatchResult scanMobSpawnCandidates(final List<Vec3> candidatePositions, final List<Vec3> playerPositions) {
        return this.tryWithExecutionContext(context -> this.executeMobSpawnScan(candidatePositions, playerPositions, context));
    }

    @Override
    public float[] interpolateNoiseColumn(
        final int cellWidth,
        final int cellHeight,
        final int cellCountY,
        final int interpolatorCount,
        final float[] packedCorners
    ) {
        return this.interpolateNoiseSlice(cellWidth, cellHeight, cellCountY, 1, interpolatorCount, packedCorners);
    }

    @Override
    public float[] interpolateNoiseSlice(
        final int cellWidth,
        final int cellHeight,
        final int cellCountY,
        final int cellCountZ,
        final int interpolatorCount,
        final float[] packedCorners
    ) {
        return this.tryWithExecutionContext(
            context -> this.executeNoiseInterpolation(cellWidth, cellHeight, cellCountY, cellCountZ, interpolatorCount, packedCorners, context)
        );
    }

    private GPurBatchResult executeTerrainBatch(final GPurBatchRequest request, final ExecutionContext context) {
        final long startedAt = System.nanoTime();
        this.inFlightBatches.incrementAndGet();

        try {
            final int chunkCount = request.chunks().size();
            final int chunkVolume = request.profile().height() * 256;
            final int totalVoxelCount = chunkCount * chunkVolume;
            final int packedMaterialWordCount = divideCeil(totalVoxelCount, 4);
            final int packedSurfaceWordCount = chunkCount * TERRAIN_SURFACE_WORDS_PER_CHUNK;
            final int inputIntCount = HEADER_INTS + (chunkCount * CHUNK_INPUT_STRIDE_INTS);
            final int outputIntCount = packedMaterialWordCount + packedSurfaceWordCount;

            context.setInputBuffer(this.ensureBufferCapacity(context.inputBuffer(), (long)inputIntCount * Integer.BYTES));
            context.setOutputBuffer(this.ensureBufferCapacity(context.outputBuffer(), (long)Math.max(1, outputIntCount) * Integer.BYTES));
            this.clearBuffer(context.outputBuffer(), context.outputBuffer().size());
            this.writeTerrainInputBuffer(request, context.inputBuffer(), inputIntCount);
            this.executeWorkload(context, Math.max(1, packedMaterialWordCount));

            return new GPurBatchResult(
                this.mode(),
                this.deviceName,
                chunkCount,
                this.currentUtilizationPercent().orElse(0),
                System.nanoTime() - startedAt,
                this.readTerrainOutputBuffer(request, context.outputBuffer(), packedMaterialWordCount)
            );
        } finally {
            this.inFlightBatches.decrementAndGet();
        }
    }

    private GPurAntiXrayBatchResult executeAntiXrayBatch(final GPurAntiXrayBatchRequest request, final ExecutionContext context) {
        this.inFlightBatches.incrementAndGet();

        try {
            final int sectionCount = request.sections().size();
            final int inputStride = ANTI_XRAY_SECTION_VOLUME + ANTI_XRAY_PADDED_VOLUME;
            final int inputIntCount = HEADER_INTS + (sectionCount * inputStride);
            final int outputIntCount = sectionCount * ANTI_XRAY_SECTION_VOLUME;

            context.setInputBuffer(this.ensureBufferCapacity(context.inputBuffer(), (long)inputIntCount * Integer.BYTES));
            context.setOutputBuffer(this.ensureBufferCapacity(context.outputBuffer(), (long)Math.max(1, outputIntCount) * Integer.BYTES));
            this.clearBuffer(context.outputBuffer(), context.outputBuffer().size());
            this.writeAntiXrayInputBuffer(request, context.inputBuffer(), inputIntCount);
            this.executeWorkload(context, Math.max(1, outputIntCount));
            return this.readAntiXrayOutputBuffer(request, context.outputBuffer());
        } finally {
            this.inFlightBatches.decrementAndGet();
        }
    }

    private GPurStructureScanResult executeStructureScan(
        final int originBlockX,
        final int originBlockZ,
        final List<BlockPos> candidatePositions,
        final ExecutionContext context
    ) {
        this.inFlightBatches.incrementAndGet();

        try {
            final int candidateCount = candidatePositions.size();
            final int inputIntCount = HEADER_INTS + (candidateCount * STRUCTURE_SCAN_STRIDE_INTS);
            final int outputIntCount = Math.max(1, candidateCount);

            context.setInputBuffer(this.ensureBufferCapacity(context.inputBuffer(), (long)inputIntCount * Integer.BYTES));
            context.setOutputBuffer(this.ensureBufferCapacity(context.outputBuffer(), (long)outputIntCount * Integer.BYTES));
            this.clearBuffer(context.outputBuffer(), context.outputBuffer().size());
            this.writeStructureScanInputBuffer(originBlockX, originBlockZ, candidatePositions, context.inputBuffer(), inputIntCount);
            this.executeWorkload(context, outputIntCount);
            return this.readStructureScanOutputBuffer(candidateCount, context.outputBuffer());
        } finally {
            this.inFlightBatches.decrementAndGet();
        }
    }

    private GPurMobSpawnBatchResult executeMobSpawnScan(
        final List<Vec3> candidatePositions,
        final List<Vec3> playerPositions,
        final ExecutionContext context
    ) {
        this.inFlightBatches.incrementAndGet();

        try {
            final int candidateCount = candidatePositions.size();
            final int playerCount = playerPositions.size();
            final int inputIntCount = HEADER_INTS + ((candidateCount + playerCount) * MOB_SPAWN_VEC3_STRIDE_INTS);
            final int outputIntCount = Math.max(1, candidateCount);

            context.setInputBuffer(this.ensureBufferCapacity(context.inputBuffer(), (long)inputIntCount * Integer.BYTES));
            context.setOutputBuffer(this.ensureBufferCapacity(context.outputBuffer(), (long)outputIntCount * Integer.BYTES));
            this.clearBuffer(context.outputBuffer(), context.outputBuffer().size());
            this.writeMobSpawnInputBuffer(candidatePositions, playerPositions, context.inputBuffer(), inputIntCount);
            this.executeWorkload(context, outputIntCount);
            return this.readMobSpawnOutputBuffer(candidateCount, context.outputBuffer());
        } finally {
            this.inFlightBatches.decrementAndGet();
        }
    }

    private float[] executeNoiseInterpolation(
        final int cellWidth,
        final int cellHeight,
        final int cellCountY,
        final int cellCountZ,
        final int interpolatorCount,
        final float[] packedCorners,
        final ExecutionContext context
    ) {
        this.inFlightBatches.incrementAndGet();

        try {
            final int cellVolume = cellWidth * cellWidth * cellHeight;
            final int totalCornerCount = interpolatorCount * cellCountZ * cellCountY * INTERPOLATION_CORNERS_PER_CELL;
            if (packedCorners.length != totalCornerCount) {
                throw new IllegalArgumentException(
                    "Expected "
                        + totalCornerCount
                        + " packed interpolation corners, got "
                        + packedCorners.length
                );
            }

            final int inputIntCount = HEADER_INTS + totalCornerCount;
            final int outputValueCount = Math.max(1, interpolatorCount * cellCountZ * cellCountY * cellVolume);
            final int outputIntCount = outputValueCount;

            context.setInputBuffer(this.ensureBufferCapacity(context.inputBuffer(), (long)inputIntCount * Integer.BYTES));
            context.setOutputBuffer(this.ensureBufferCapacity(context.outputBuffer(), (long)outputIntCount * Integer.BYTES));
            this.clearBuffer(context.outputBuffer(), context.outputBuffer().size());
            this.writeNoiseInterpolationInputBuffer(
                cellWidth,
                cellHeight,
                cellCountY,
                cellCountZ,
                interpolatorCount,
                packedCorners,
                context.inputBuffer(),
                inputIntCount
            );
            this.executeWorkload(context, outputValueCount);
            return this.readNoiseInterpolationOutputBuffer(outputValueCount, context.outputBuffer());
        } finally {
            this.inFlightBatches.decrementAndGet();
        }
    }

    private <T> T withExecutionContext(final ContextOperation<T> operation) {
        final ExecutionContext context;
        try {
            context = this.availableExecutionContexts.take();
        } catch (final InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for an available GPur Vulkan execution context", interruptedException);
        }

        try {
            return operation.run(context);
        } finally {
            this.availableExecutionContexts.offer(context);
        }
    }

    private <T> T tryWithExecutionContext(final ContextOperation<T> operation) {
        final ExecutionContext context = this.availableExecutionContexts.poll();
        if (context == null) {
            return null;
        }

        try {
            return operation.run(context);
        } finally {
            this.availableExecutionContexts.offer(context);
        }
    }

    private void executeWorkload(final ExecutionContext context, final int totalInvocations) {
        this.updateDescriptorSet(context, context.inputBuffer(), context.outputBuffer());
        this.dispatch(context, totalInvocations);
    }

    private void dispatch(final ExecutionContext context, final int totalInvocations) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            checkVk(vkResetCommandBuffer(context.commandBuffer(), 0), "reset Vulkan command buffer");

            final VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            checkVk(vkBeginCommandBuffer(context.commandBuffer(), beginInfo), "begin Vulkan command buffer");

            vkCmdBindPipeline(context.commandBuffer(), VK_PIPELINE_BIND_POINT_COMPUTE, this.pipeline);
            vkCmdBindDescriptorSets(context.commandBuffer(), VK_PIPELINE_BIND_POINT_COMPUTE, this.pipelineLayout, 0, stack.longs(context.descriptorSet()), null);
            vkCmdDispatch(context.commandBuffer(), Math.max(1, divideCeil(totalInvocations, LOCAL_SIZE_X)), 1, 1);
            checkVk(vkEndCommandBuffer(context.commandBuffer()), "end Vulkan command buffer");

            final VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                .pCommandBuffers(stack.pointers(context.commandBuffer().address()));

            synchronized (this.queueSubmitLock) {
                checkVk(vkResetFences(this.device, context.fence()), "reset Vulkan fence");
                checkVk(vkQueueSubmit(this.computeQueue, submitInfo, context.fence()), "submit Vulkan compute dispatch");
            }

            checkVk(vkWaitForFences(this.device, context.fence(), true, COMPUTE_WAIT_TIMEOUT_NANOS), "wait for Vulkan compute dispatch");
        }
    }

    private void writeTerrainInputBuffer(final GPurBatchRequest request, final MappedBufferAllocation inputBuffer, final int inputIntCount) {
        final GPurTerrainProfile profile = request.profile();

        final IntBuffer input = inputBuffer.intView();
        input.position(0);
        input.put(0, WORKLOAD_TERRAIN);
        input.put(1, request.chunks().size());
        input.put(2, profile.minY());
        input.put(3, profile.height());
        input.put(4, profile.seaLevel());
        input.put(5, profile.lavaLevel());
        input.put(6, (int)profile.seed());
        input.put(7, (int)(profile.seed() >>> 32));
        for (int headerIndex = 8; headerIndex < HEADER_INTS; headerIndex++) {
            input.put(headerIndex, 0);
        }

        int offset = HEADER_INTS;
        for (final GPurChunkWorkItem chunk : request.chunks()) {
            input.put(offset++, chunk.chunkX());
            input.put(offset++, chunk.chunkZ());
        }

        while (offset < inputIntCount) {
            input.put(offset++, 0);
        }
    }

    private void writeAntiXrayInputBuffer(
        final GPurAntiXrayBatchRequest request,
        final MappedBufferAllocation inputBuffer,
        final int inputIntCount
    ) {
        final IntBuffer input = inputBuffer.intView();
        input.position(0);
        input.put(0, WORKLOAD_ANTI_XRAY);
        input.put(1, request.sections().size());
        for (int headerIndex = 2; headerIndex < HEADER_INTS; headerIndex++) {
            input.put(headerIndex, 0);
        }

        int offset = HEADER_INTS;
        for (final GPurAntiXraySectionRequest section : request.sections()) {
            for (final byte stateFlag : section.stateFlags()) {
                input.put(offset++, Byte.toUnsignedInt(stateFlag));
            }
            for (final byte transparency : section.paddedTransparency()) {
                input.put(offset++, Byte.toUnsignedInt(transparency));
            }
        }

        while (offset < inputIntCount) {
            input.put(offset++, 0);
        }
    }

    private void writeStructureScanInputBuffer(
        final int originBlockX,
        final int originBlockZ,
        final List<BlockPos> candidatePositions,
        final MappedBufferAllocation inputBuffer,
        final int inputIntCount
    ) {
        final IntBuffer input = inputBuffer.intView();
        input.position(0);
        input.put(0, WORKLOAD_STRUCTURE_SCAN);
        input.put(1, candidatePositions.size());
        input.put(2, originBlockX);
        input.put(3, originBlockZ);
        for (int headerIndex = 4; headerIndex < HEADER_INTS; headerIndex++) {
            input.put(headerIndex, 0);
        }

        int offset = HEADER_INTS;
        for (final BlockPos position : candidatePositions) {
            input.put(offset++, position.getX());
            input.put(offset++, position.getZ());
        }

        while (offset < inputIntCount) {
            input.put(offset++, 0);
        }
    }

    private void writeMobSpawnInputBuffer(
        final List<Vec3> candidatePositions,
        final List<Vec3> playerPositions,
        final MappedBufferAllocation inputBuffer,
        final int inputIntCount
    ) {
        final IntBuffer input = inputBuffer.intView();
        input.position(0);
        input.put(0, WORKLOAD_MOB_SPAWN);
        input.put(1, candidatePositions.size());
        input.put(2, playerPositions.size());
        for (int headerIndex = 3; headerIndex < HEADER_INTS; headerIndex++) {
            input.put(headerIndex, 0);
        }

        int offset = HEADER_INTS;
        for (final Vec3 position : candidatePositions) {
            input.put(offset++, Float.floatToIntBits((float)position.x));
            input.put(offset++, Float.floatToIntBits((float)position.y));
            input.put(offset++, Float.floatToIntBits((float)position.z));
        }
        for (final Vec3 playerPosition : playerPositions) {
            input.put(offset++, Float.floatToIntBits((float)playerPosition.x));
            input.put(offset++, Float.floatToIntBits((float)playerPosition.y));
            input.put(offset++, Float.floatToIntBits((float)playerPosition.z));
        }

        while (offset < inputIntCount) {
            input.put(offset++, 0);
        }
    }

    private void writeNoiseInterpolationInputBuffer(
        final int cellWidth,
        final int cellHeight,
        final int cellCountY,
        final int cellCountZ,
        final int interpolatorCount,
        final float[] packedCorners,
        final MappedBufferAllocation inputBuffer,
        final int inputIntCount
    ) {
        final IntBuffer input = inputBuffer.intView();
        input.position(0);
        input.put(0, WORKLOAD_NOISE_INTERPOLATION);
        input.put(1, interpolatorCount);
        input.put(2, cellCountY);
        input.put(3, cellWidth);
        input.put(4, cellHeight);
        input.put(5, cellCountZ);
        for (int headerIndex = 6; headerIndex < HEADER_INTS; headerIndex++) {
            input.put(headerIndex, 0);
        }

        int offset = HEADER_INTS;
        for (final float corner : packedCorners) {
            input.put(offset++, Float.floatToRawIntBits(corner));
        }

        while (offset < inputIntCount) {
            input.put(offset++, 0);
        }
    }

    private List<GPurChunkTerrainData> readTerrainOutputBuffer(
        final GPurBatchRequest request,
        final MappedBufferAllocation outputBuffer,
        final int packedMaterialWordCount
    ) {
        final List<GPurChunkTerrainData> chunks = new ArrayList<>(request.chunks().size());
        final GPurTerrainProfile profile = request.profile();
        final int chunkVolume = profile.height() * 256;
        final IntBuffer output = outputBuffer.intView();
        output.position(0);

        for (int chunkIndex = 0; chunkIndex < request.chunks().size(); chunkIndex++) {
            final GPurChunkWorkItem workItem = request.chunks().get(chunkIndex);
            final byte[] materials = new byte[chunkVolume];
            final int materialWordOffset = (chunkIndex * chunkVolume) / 4;
            for (int voxelIndex = 0; voxelIndex < chunkVolume; voxelIndex++) {
                final int packedWord = output.get(materialWordOffset + (voxelIndex >> 2));
                materials[voxelIndex] = (byte)((packedWord >>> ((voxelIndex & 3) * 8)) & 0xFF);
            }

            final int[] surfaceHeights = new int[256];
            final int surfaceWordOffset = packedMaterialWordCount + (chunkIndex * TERRAIN_SURFACE_WORDS_PER_CHUNK);
            for (int columnIndex = 0; columnIndex < 256; columnIndex++) {
                final int packedHeightWord = output.get(surfaceWordOffset + (columnIndex >> 1));
                final int rawHeight = (columnIndex & 1) == 0 ? packedHeightWord & 0xFFFF : (packedHeightWord >>> 16) & 0xFFFF;
                surfaceHeights[columnIndex] = (short)rawHeight;
            }

            chunks.add(
                new GPurChunkTerrainData(
                    profile.worldKey(),
                    workItem.chunkX(),
                    workItem.chunkZ(),
                    profile.minY(),
                    profile.height(),
                    materials,
                    surfaceHeights
                )
            );
        }

        return chunks;
    }

    private GPurAntiXrayBatchResult readAntiXrayOutputBuffer(final GPurAntiXrayBatchRequest request, final MappedBufferAllocation outputBuffer) {
        final List<GPurAntiXraySectionResult> sections = new ArrayList<>(request.sections().size());
        final IntBuffer output = outputBuffer.intView();
        output.position(0);

        for (int sectionIndex = 0; sectionIndex < request.sections().size(); sectionIndex++) {
            final GPurAntiXraySectionRequest requestSection = request.sections().get(sectionIndex);
            final byte[] mask = new byte[ANTI_XRAY_SECTION_VOLUME];
            final int sectionOffset = sectionIndex * ANTI_XRAY_SECTION_VOLUME;
            for (int blockIndex = 0; blockIndex < ANTI_XRAY_SECTION_VOLUME; blockIndex++) {
                mask[blockIndex] = (byte)output.get(sectionOffset + blockIndex);
            }
            sections.add(new GPurAntiXraySectionResult(requestSection.sectionIndex(), mask));
        }

        return new GPurAntiXrayBatchResult(request.worldKey(), request.chunkX(), request.chunkZ(), sections);
    }

    private GPurStructureScanResult readStructureScanOutputBuffer(final int candidateCount, final MappedBufferAllocation outputBuffer) {
        final float[] distances = new float[candidateCount];
        final IntBuffer output = outputBuffer.intView();
        output.position(0);

        for (int candidateIndex = 0; candidateIndex < candidateCount; candidateIndex++) {
            distances[candidateIndex] = Float.intBitsToFloat(output.get(candidateIndex));
        }

        return new GPurStructureScanResult(distances);
    }

    private GPurMobSpawnBatchResult readMobSpawnOutputBuffer(final int candidateCount, final MappedBufferAllocation outputBuffer) {
        final float[] nearestDistances = new float[candidateCount];
        final IntBuffer output = outputBuffer.intView();
        output.position(0);

        for (int candidateIndex = 0; candidateIndex < candidateCount; candidateIndex++) {
            nearestDistances[candidateIndex] = Float.intBitsToFloat(output.get(candidateIndex));
        }

        return new GPurMobSpawnBatchResult(nearestDistances);
    }

    private float[] readNoiseInterpolationOutputBuffer(final int outputValueCount, final MappedBufferAllocation outputBuffer) {
        final float[] interpolatedValues = new float[outputValueCount];
        final IntBuffer output = outputBuffer.intView();
        output.position(0);

        for (int valueIndex = 0; valueIndex < outputValueCount; valueIndex++) {
            interpolatedValues[valueIndex] = Float.intBitsToFloat(output.get(valueIndex));
        }

        return interpolatedValues;
    }

    private void updateDescriptorSet(
        final ExecutionContext context,
        final MappedBufferAllocation inputBuffer,
        final MappedBufferAllocation outputBuffer
    ) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkDescriptorBufferInfo.Buffer inputBufferInfo = VkDescriptorBufferInfo.calloc(1, stack);
            inputBufferInfo.get(0)
                .buffer(inputBuffer.buffer())
                .offset(0L)
                .range(inputBuffer.size());

            final VkDescriptorBufferInfo.Buffer outputBufferInfo = VkDescriptorBufferInfo.calloc(1, stack);
            outputBufferInfo.get(0)
                .buffer(outputBuffer.buffer())
                .offset(0L)
                .range(outputBuffer.size());

            final VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
            writes.get(0)
                .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                .dstSet(context.descriptorSet())
                .dstBinding(0)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1)
                .pBufferInfo(inputBufferInfo);
            writes.get(1)
                .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                .dstSet(context.descriptorSet())
                .dstBinding(1)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1)
                .pBufferInfo(outputBufferInfo);

            vkUpdateDescriptorSets(this.device, writes, null);
        }
    }

    private void clearBuffer(final MappedBufferAllocation allocation, final long size) {
        memSet(allocation.mappedAddress(), 0, size);
    }

    private MappedBufferAllocation ensureBufferCapacity(final MappedBufferAllocation existing, final long requiredSize) {
        if (existing != null && existing.size() >= requiredSize) {
            return existing;
        }

        if (existing != null) {
            this.destroyMappedBuffer(existing);
        }

        return this.createMappedBuffer(requiredSize);
    }

    private MappedBufferAllocation createMappedBuffer(final long minimumSize) {
        final long size = roundUp(Math.max(minimumSize, BUFFER_ALIGNMENT), BUFFER_ALIGNMENT);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkBufferCreateInfo bufferCreateInfo = VkBufferCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                .size(size)
                .usage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            final LongBuffer bufferHandle = stack.mallocLong(1);
            checkVk(vkCreateBuffer(this.device, bufferCreateInfo, null, bufferHandle), "create Vulkan storage buffer");
            final long buffer = bufferHandle.get(0);

            final VkMemoryRequirements memoryRequirements = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(this.device, buffer, memoryRequirements);

            final VkMemoryAllocateInfo allocateInfo = VkMemoryAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .allocationSize(memoryRequirements.size())
                .memoryTypeIndex(this.findMemoryTypeIndex(memoryRequirements.memoryTypeBits(), stack));

            final LongBuffer memoryHandle = stack.mallocLong(1);
            checkVk(vkAllocateMemory(this.device, allocateInfo, null, memoryHandle), "allocate Vulkan buffer memory");
            final long memory = memoryHandle.get(0);
            checkVk(vkBindBufferMemory(this.device, buffer, memory, 0L), "bind Vulkan buffer memory");

            final PointerBuffer mappedPointer = stack.mallocPointer(1);
            checkVk(vkMapMemory(this.device, memory, 0L, size, 0, mappedPointer), "map Vulkan buffer memory");
            final long mappedAddress = mappedPointer.get(0);
            final ByteBuffer mappedBytes = memByteBuffer(mappedAddress, Math.toIntExact(size)).order(ByteOrder.nativeOrder());
            return new MappedBufferAllocation(buffer, memory, size, mappedAddress, mappedBytes);
        }
    }

    private void destroyMappedBuffer(final MappedBufferAllocation allocation) {
        destroyMappedBuffer(this.device, allocation);
    }

    private int findMemoryTypeIndex(final int memoryTypeBits, final MemoryStack stack) {
        final VkPhysicalDeviceMemoryProperties memoryProperties = VkPhysicalDeviceMemoryProperties.calloc(stack);
        vkGetPhysicalDeviceMemoryProperties(this.physicalDevice, memoryProperties);

        for (int memoryTypeIndex = 0; memoryTypeIndex < memoryProperties.memoryTypeCount(); memoryTypeIndex++) {
            if ((memoryTypeBits & (1 << memoryTypeIndex)) == 0) {
                continue;
            }

            final int flags = memoryProperties.memoryTypes(memoryTypeIndex).propertyFlags();
            if ((flags & (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT))
                == (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) {
                return memoryTypeIndex;
            }
        }

        throw new IllegalStateException("No compatible Vulkan memory type was found for GPur buffers");
    }

    @Override
    public void close() {
        this.dispatchExecutor.shutdownNow();
        checkVk(vkDeviceWaitIdle(this.device), "wait for Vulkan device idle");
        destroyExecutionContexts(this.executionContexts);
        vkDestroyPipeline(this.device, this.pipeline, null);
        vkDestroyPipelineLayout(this.device, this.pipelineLayout, null);
        vkDestroyDescriptorSetLayout(this.device, this.descriptorSetLayout, null);
        vkDestroyDevice(this.device, null);
        vkDestroyInstance(this.instance, null);
    }

    private static List<ExecutionContext> createExecutionContexts(
        final VkDevice device,
        final VkPhysicalDevice physicalDevice,
        final long descriptorSetLayout,
        final int queueFamilyIndex
    ) {
        final List<ExecutionContext> contexts = new ArrayList<>(DEFAULT_EXECUTION_CONTEXTS);
        try {
            for (int index = 0; index < DEFAULT_EXECUTION_CONTEXTS; index++) {
                contexts.add(createExecutionContext(device, physicalDevice, descriptorSetLayout, queueFamilyIndex));
            }
            return contexts;
        } catch (final RuntimeException ex) {
            destroyExecutionContexts(contexts);
            throw ex;
        }
    }

    private static ExecutionContext createExecutionContext(
        final VkDevice device,
        final VkPhysicalDevice physicalDevice,
        final long descriptorSetLayout,
        final int queueFamilyIndex
    ) {
        long commandPool = VK_NULL_HANDLE;
        long descriptorPool = VK_NULL_HANDLE;
        long descriptorSet = VK_NULL_HANDLE;
        long fence = VK_NULL_HANDLE;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkCommandPoolCreateInfo commandPoolCreateInfo = VkCommandPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                .queueFamilyIndex(queueFamilyIndex);
            final LongBuffer commandPoolHandle = stack.mallocLong(1);
            checkVk(vkCreateCommandPool(device, commandPoolCreateInfo, null, commandPoolHandle), "create Vulkan command pool");
            commandPool = commandPoolHandle.get(0);

            final VkCommandBufferAllocateInfo allocateInfo = VkCommandBufferAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                .commandPool(commandPool)
                .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                .commandBufferCount(1);
            final PointerBuffer commandBufferHandle = stack.mallocPointer(1);
            checkVk(vkAllocateCommandBuffers(device, allocateInfo, commandBufferHandle), "allocate Vulkan command buffer");
            final VkCommandBuffer commandBuffer = new VkCommandBuffer(commandBufferHandle.get(0), device);

            descriptorPool = createDescriptorPool(device, stack);
            descriptorSet = allocateDescriptorSet(device, descriptorPool, descriptorSetLayout, stack);

            final VkFenceCreateInfo fenceCreateInfo = VkFenceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO);
            final LongBuffer fenceHandle = stack.mallocLong(1);
            checkVk(vkCreateFence(device, fenceCreateInfo, null, fenceHandle), "create Vulkan fence");
            fence = fenceHandle.get(0);

            return new ExecutionContext(device, commandPool, commandBuffer, descriptorPool, descriptorSet, fence);
        } catch (final RuntimeException ex) {
            if (fence != VK_NULL_HANDLE) {
                vkDestroyFence(device, fence, null);
            }
            if (descriptorPool != VK_NULL_HANDLE) {
                vkDestroyDescriptorPool(device, descriptorPool, null);
            }
            if (commandPool != VK_NULL_HANDLE) {
                vkDestroyCommandPool(device, commandPool, null);
            }
            throw ex;
        }
    }

    private static void destroyExecutionContexts(final List<ExecutionContext> contexts) {
        for (final ExecutionContext context : contexts) {
            context.close();
        }
    }

    private static PhysicalDeviceSelection selectPhysicalDevice(final VkInstance instance, final MemoryStack stack) {
        final IntBuffer physicalDeviceCount = stack.ints(0);
        checkVk(vkEnumeratePhysicalDevices(instance, physicalDeviceCount, null), "enumerate Vulkan physical device count");
        if (physicalDeviceCount.get(0) <= 0) {
            throw new IllegalStateException("No Vulkan-capable GPU was detected");
        }

        final PointerBuffer physicalDevices = stack.mallocPointer(physicalDeviceCount.get(0));
        checkVk(vkEnumeratePhysicalDevices(instance, physicalDeviceCount, physicalDevices), "enumerate Vulkan physical devices");

        for (int deviceIndex = 0; deviceIndex < physicalDevices.capacity(); ++deviceIndex) {
            final VkPhysicalDevice physicalDevice = new VkPhysicalDevice(physicalDevices.get(deviceIndex), instance);
            final IntBuffer queueFamilyCount = stack.ints(0);
            vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, queueFamilyCount, null);

            final VkQueueFamilyProperties.Buffer queueFamilies = VkQueueFamilyProperties.calloc(queueFamilyCount.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, queueFamilyCount, queueFamilies);

            for (int familyIndex = 0; familyIndex < queueFamilies.capacity(); ++familyIndex) {
                if ((queueFamilies.get(familyIndex).queueFlags() & VK_QUEUE_COMPUTE_BIT) == 0) {
                    continue;
                }

                final VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
                vkGetPhysicalDeviceProperties(physicalDevice, properties);
                return new PhysicalDeviceSelection(physicalDevice, familyIndex, properties.deviceNameString());
            }
        }

        throw new IllegalStateException("No Vulkan compute queue family was found");
    }

    private static long createDescriptorSetLayout(final VkDevice device, final MemoryStack stack) {
        final VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(2, stack);
        bindings.get(0)
            .binding(0)
            .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
            .descriptorCount(1)
            .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
        bindings.get(1)
            .binding(1)
            .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
            .descriptorCount(1)
            .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);

        final VkDescriptorSetLayoutCreateInfo createInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
            .pBindings(bindings);
        final LongBuffer descriptorSetLayoutHandle = stack.mallocLong(1);
        checkVk(vkCreateDescriptorSetLayout(device, createInfo, null, descriptorSetLayoutHandle), "create Vulkan descriptor set layout");
        return descriptorSetLayoutHandle.get(0);
    }

    private static long createDescriptorPool(final VkDevice device, final MemoryStack stack) {
        final VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(1, stack);
        poolSizes.get(0)
            .type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
            .descriptorCount(2);

        final VkDescriptorPoolCreateInfo createInfo = VkDescriptorPoolCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
            .pPoolSizes(poolSizes)
            .maxSets(1);
        final LongBuffer descriptorPoolHandle = stack.mallocLong(1);
        checkVk(vkCreateDescriptorPool(device, createInfo, null, descriptorPoolHandle), "create Vulkan descriptor pool");
        return descriptorPoolHandle.get(0);
    }

    private static long allocateDescriptorSet(
        final VkDevice device,
        final long descriptorPool,
        final long descriptorSetLayout,
        final MemoryStack stack
    ) {
        final VkDescriptorSetAllocateInfo allocateInfo = VkDescriptorSetAllocateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
            .descriptorPool(descriptorPool)
            .pSetLayouts(stack.longs(descriptorSetLayout));
        final LongBuffer descriptorSetHandle = stack.mallocLong(1);
        checkVk(vkAllocateDescriptorSets(device, allocateInfo, descriptorSetHandle), "allocate Vulkan descriptor set");
        return descriptorSetHandle.get(0);
    }

    private static long createShaderModule(final VkDevice device, final ByteBuffer spirv, final MemoryStack stack) {
        final VkShaderModuleCreateInfo shaderModuleCreateInfo = VkShaderModuleCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
            .pCode(spirv);
        final LongBuffer shaderModuleHandle = stack.mallocLong(1);
        checkVk(vkCreateShaderModule(device, shaderModuleCreateInfo, null, shaderModuleHandle), "create Vulkan shader module");
        return shaderModuleHandle.get(0);
    }

    private static ByteBuffer compileShader() {
        final String source;
        try (InputStream inputStream = GPurVulkanComputeBackend.class.getClassLoader().getResourceAsStream(SHADER_RESOURCE)) {
            if (inputStream == null) {
                throw new IllegalStateException("Missing shader resource: " + SHADER_RESOURCE);
            }
            source = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException ex) {
            throw new IllegalStateException("Failed to read Vulkan compute shader resource", ex);
        }

        final long compiler = shaderc_compiler_initialize();
        if (compiler == NULL) {
            throw new IllegalStateException("Unable to initialize shaderc compiler");
        }

        final long result = shaderc_compile_into_spv(compiler, source, shaderc_compute_shader, SHADER_RESOURCE, "main", NULL);
        if (result == NULL) {
            shaderc_compiler_release(compiler);
            throw new IllegalStateException("shaderc failed to compile the GPur compute shader");
        }

        try {
            final int status = shaderc_result_get_compilation_status(result);
            if (status != shaderc_compilation_status_success) {
                throw new IllegalStateException(shaderc_result_get_error_message(result));
            }

            final ByteBuffer bytes = shaderc_result_get_bytes(result);
            final ByteBuffer copy = org.lwjgl.system.MemoryUtil.memAlloc(bytes.remaining());
            copy.put(bytes);
            copy.flip();
            return copy;
        } finally {
            shaderc_result_release(result);
            shaderc_compiler_release(compiler);
        }
    }

    private static long roundUp(final long value, final int alignment) {
        final long remainder = value % alignment;
        return remainder == 0L ? value : value + alignment - remainder;
    }

    private static int divideCeil(final int numerator, final int denominator) {
        return (numerator + denominator - 1) / denominator;
    }

    private static void checkVk(final int result, final String action) {
        if (result != VK_SUCCESS) {
            throw new IllegalStateException("Failed to " + action + " (vk result " + result + ")");
        }
    }

    @FunctionalInterface
    private interface ContextOperation<T> {
        T run(ExecutionContext context);
    }

    private record PhysicalDeviceSelection(VkPhysicalDevice device, int queueFamilyIndex, String deviceName) {
    }

    private static final class ExecutionContext {
        private final VkDevice device;
        private final long commandPool;
        private final VkCommandBuffer commandBuffer;
        private final long descriptorPool;
        private final long descriptorSet;
        private final long fence;
        private MappedBufferAllocation inputBuffer;
        private MappedBufferAllocation outputBuffer;

        private ExecutionContext(
            final VkDevice device,
            final long commandPool,
            final VkCommandBuffer commandBuffer,
            final long descriptorPool,
            final long descriptorSet,
            final long fence
        ) {
            this.device = device;
            this.commandPool = commandPool;
            this.commandBuffer = commandBuffer;
            this.descriptorPool = descriptorPool;
            this.descriptorSet = descriptorSet;
            this.fence = fence;
        }

        private VkCommandBuffer commandBuffer() {
            return this.commandBuffer;
        }

        private long descriptorSet() {
            return this.descriptorSet;
        }

        private long fence() {
            return this.fence;
        }

        private MappedBufferAllocation inputBuffer() {
            return this.inputBuffer;
        }

        private void setInputBuffer(final MappedBufferAllocation inputBuffer) {
            this.inputBuffer = inputBuffer;
        }

        private MappedBufferAllocation outputBuffer() {
            return this.outputBuffer;
        }

        private void setOutputBuffer(final MappedBufferAllocation outputBuffer) {
            this.outputBuffer = outputBuffer;
        }

        private void close() {
            if (this.inputBuffer != null) {
                destroyMappedBuffer(this.device, this.inputBuffer);
                this.inputBuffer = null;
            }
            if (this.outputBuffer != null) {
                destroyMappedBuffer(this.device, this.outputBuffer);
                this.outputBuffer = null;
            }
            vkDestroyFence(this.device, this.fence, null);
            vkDestroyDescriptorPool(this.device, this.descriptorPool, null);
            vkDestroyCommandPool(this.device, this.commandPool, null);
        }
    }

    private record MappedBufferAllocation(long buffer, long memory, long size, long mappedAddress, ByteBuffer mappedBytes) {
        private IntBuffer intView() {
            return this.mappedBytes.duplicate().order(ByteOrder.nativeOrder()).asIntBuffer();
        }
    }

    private static void destroyMappedBuffer(final VkDevice device, final MappedBufferAllocation allocation) {
        vkUnmapMemory(device, allocation.memory());
        vkDestroyBuffer(device, allocation.buffer(), null);
        vkFreeMemory(device, allocation.memory(), null);
    }
}
