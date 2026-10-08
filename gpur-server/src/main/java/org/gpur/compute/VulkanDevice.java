package org.gpur.compute;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;
import java.util.HexFormat;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.gpur.GPurConfig;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceIDProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkMemoryBarrier;
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
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
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
import org.lwjgl.vulkan.VkPipelineCacheCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;


/** Owns one logical device. A timed out context is never returned to the pool. */
public final class VulkanDevice implements AutoCloseable {
    private static final String SHADER_RESOURCE = "shaders/gpur/exact.comp";
    private static final int LOCAL_SIZE_X = 64;
    private static final int BUFFER_ALIGNMENT = 4096;
    private static final int MAX_BUFFER_BYTES = 16 * 1024 * 1024;
    private final String deviceName;
    private final String uuid;
    private final VkInstance instance;
    private final VkPhysicalDevice physicalDevice;
    private final VkDevice device;
    private final VkQueue computeQueue;
    private final long descriptorSetLayout;
    private final long pipelineLayout;
    private final long pipeline;
    private final long pipelineCache;
    private final boolean persistentCacheEnabled;
    private final Path cacheDirectory;
    private final int cacheVendorId;
    private final int cacheDeviceId;
    private final byte[] cacheUuid;
    private final String fingerprint;
    private final List<ExecutionContext> executionContexts;
    private final BlockingQueue<ExecutionContext> contexts;
    private final Object queueSubmitLock = new Object();
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();
    private volatile boolean failed;
    private volatile boolean closed;
    private final AtomicInteger busy = new AtomicInteger();

    private VulkanDevice(String uuid, String deviceName, VkInstance instance, VkPhysicalDevice physicalDevice,
                         VkDevice device, VkQueue computeQueue, long descriptorSetLayout, long pipelineLayout,
                         long pipeline, long pipelineCache, boolean persistentCacheEnabled, Path cacheDirectory,
                         int cacheVendorId, int cacheDeviceId, byte[] cacheUuid, String fingerprint,
                         List<ExecutionContext> executionContexts) {
        this.uuid = uuid;
        this.deviceName = deviceName;
        this.instance = instance;
        this.physicalDevice = physicalDevice;
        this.device = device;
        this.computeQueue = computeQueue;
        this.descriptorSetLayout = descriptorSetLayout;
        this.pipelineLayout = pipelineLayout;
        this.pipeline = pipeline;
        this.pipelineCache = pipelineCache;
        this.persistentCacheEnabled = persistentCacheEnabled;
        this.cacheDirectory = cacheDirectory;
        this.cacheVendorId = cacheVendorId;
        this.cacheDeviceId = cacheDeviceId;
        this.cacheUuid = cacheUuid.clone();
        this.fingerprint = fingerprint;
        this.executionContexts = executionContexts;
        this.contexts = new ArrayBlockingQueue<>(executionContexts.size(), false, executionContexts);
    }

    public String name() { return this.deviceName; }
    public String uuid() { return this.uuid; }
    public String fingerprint() { return this.fingerprint; }
    public void disable() { this.failed = true; }
    public boolean available() { return !this.failed && !this.closed; }
    public int busy() { return this.busy.get(); }
    public boolean canAccept(int workload) {
        int count = this.executionContexts.size();
        int occupied = this.busy.get();
        int reserved = Math.max(0, Math.min(GPurConfig.antiXrayGpuReservedContexts, count - 1));
        return this.available() && occupied < count && occupied * 100 < GPurConfig.gpuUsageFallback * count
            && (workload != 1 || occupied < count - reserved);
    }

    /** Null means busy/unavailable; it is safe for the caller to use the CPU reference. */
    public int[] compute(int[] input, int outputWords, int invocations) {
        if (input.length == 0 || outputWords <= 0 || invocations <= 0
                || input.length > MAX_BUFFER_BYTES / 4 || outputWords > MAX_BUFFER_BYTES / 4) {
            throw new IllegalArgumentException("Invalid GPur compute buffer dimensions");
        }
        ExactCompute.validate(input);
        if (input[1] != invocations || outputWords != ExactCompute.outputWords(input)) {
            throw new IllegalArgumentException("Inconsistent GPur dispatch dimensions");
        }
        this.lifecycle.readLock().lock();
        ExecutionContext context = null;
        boolean reusable = false;
        try {
            if (!this.available()) return null;
            synchronized (this.contexts) {
                // Recheck admission while reserving; concurrent callers can invalidate an earlier hint.
                if (!this.canAccept(input[0])) return null;
                context = this.contexts.poll();
                if (context == null) return null;
                this.busy.incrementAndGet();
            }
            // Allocate before swapping so an allocation failure cannot leave a freed buffer attached.
            context.setInputBuffer(this.ensureBufferCapacity(context.inputBuffer(), (long)input.length * 4));
            context.setOutputBuffer(this.ensureBufferCapacity(context.outputBuffer(), (long)outputWords * 4));
            context.inputBuffer().intView().put(input);
            this.updateDescriptorSet(context, context.inputBuffer(), context.outputBuffer());
            this.dispatch(context, invocations);
            int[] result = new int[outputWords];
            context.outputBuffer().intView().get(result);
            reusable = true;
            return result;
        } catch (RuntimeException exception) {
            this.failed = true;
            throw exception;
        } finally {
            if (context != null) {
                this.busy.decrementAndGet();
                if (reusable) this.contexts.offer(context);
            }
            this.lifecycle.readLock().unlock();
        }
    }

    public static VulkanDevice create(String uuid) {
        final int executionContextCount = Math.max(1, Math.min(16, GPurConfig.gpuExecutionContexts));
        VkInstance instance = null;
        VkPhysicalDevice physicalDevice = null;
        VkDevice device = null;
        long shaderModule = VK_NULL_HANDLE;
        long descriptorSetLayout = VK_NULL_HANDLE;
        long pipelineLayout = VK_NULL_HANDLE;
        long pipeline = VK_NULL_HANDLE;
        long pipelineCache = VK_NULL_HANDLE;
        boolean persistentCacheEnabled = GPurConfig.gpuCacheEnabled;
        Path cacheDirectory = GPurConfig.gpuCacheDirectory();
        int cacheVendorId = 0;
        int cacheDeviceId = 0;
        byte[] cacheUuid = new byte[VK_UUID_SIZE];
        String fingerprint = "";
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

            final PhysicalDeviceSelection selectedDevice = selectPhysicalDevice(instance, stack, uuid);
            physicalDevice = selectedDevice.device();
            final VkPhysicalDeviceProperties selectedProperties = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(physicalDevice, selectedProperties);
            cacheVendorId = selectedProperties.vendorID();
            cacheDeviceId = selectedProperties.deviceID();
            selectedProperties.pipelineCacheUUID().get(cacheUuid);
            fingerprint = GpuPipelineCache.fingerprint(
                uuid,
                cacheVendorId,
                cacheDeviceId,
                selectedProperties.driverVersion(),
                cacheUuid,
                shaderSha256(),
                org.gpur.terrain.TerrainRules.VERSION
            );

            final VkDeviceQueueCreateInfo.Buffer queueCreateInfos = VkDeviceQueueCreateInfo.calloc(1, stack);
            queueCreateInfos.get(0)
                .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                .queueFamilyIndex(selectedDevice.queueFamilyIndex())
                .pQueuePriorities(stack.floats(1.0F));

            final VkDeviceCreateInfo deviceCreateInfo = VkDeviceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                .pQueueCreateInfos(queueCreateInfos)
                .pEnabledFeatures(VkPhysicalDeviceFeatures.calloc(stack).shaderFloat64(true));

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

            final byte[] initialCacheData = persistentCacheEnabled
                ? GpuPipelineCache.load(cacheDirectory, fingerprint, cacheVendorId, cacheDeviceId, cacheUuid, Logger.getLogger("GPur")).orElse(null)
                : null;
            final VkPipelineCacheCreateInfo cacheCreateInfo = VkPipelineCacheCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO);
            final LongBuffer pipelineCacheHandle = stack.mallocLong(1);
            int cacheCreateResult;
            ByteBuffer initialCacheBuffer = null;
            if (initialCacheData != null) {
                try {
                    initialCacheBuffer = org.lwjgl.system.MemoryUtil.memAlloc(initialCacheData.length);
                    initialCacheBuffer.put(initialCacheData).flip();
                    cacheCreateInfo.pInitialData(initialCacheBuffer);
                    cacheCreateResult = vkCreatePipelineCache(device, cacheCreateInfo, null, pipelineCacheHandle);
                } finally {
                    cacheCreateInfo.pInitialData(null);
                    if (initialCacheBuffer != null) org.lwjgl.system.MemoryUtil.memFree(initialCacheBuffer);
                }
            } else {
                cacheCreateResult = vkCreatePipelineCache(device, cacheCreateInfo, null, pipelineCacheHandle);
            }
            if (cacheCreateResult != VK_SUCCESS && initialCacheData != null) {
                Logger.getLogger("GPur").warning("GPur Vulkan pipeline cache was rejected; creating a fresh cache");
                pipelineCacheHandle.put(0, VK_NULL_HANDLE);
                cacheCreateResult = vkCreatePipelineCache(device, cacheCreateInfo, null, pipelineCacheHandle);
            }
            if (cacheCreateResult == VK_SUCCESS) {
                pipelineCache = pipelineCacheHandle.get(0);
            } else {
                Logger.getLogger("GPur").log(Level.WARNING,
                    "Vulkan pipeline cache could not be created; continuing without driver cache (vk result {0})", cacheCreateResult);
            }

            final LongBuffer pipelineHandle = stack.mallocLong(1);
            checkVk(vkCreateComputePipelines(device, pipelineCache, pipelineCreateInfos, null, pipelineHandle), "create Vulkan compute pipeline");
            pipeline = pipelineHandle.get(0);

            executionContexts = createExecutionContexts(
                device,
                physicalDevice,
                descriptorSetLayout,
                selectedDevice.queueFamilyIndex(),
                executionContextCount
            );

            vkDestroyShaderModule(device, shaderModule, null);
            shaderModule = VK_NULL_HANDLE;

            return new VulkanDevice(uuid, selectedDevice.deviceName(), instance, physicalDevice, device,
                computeQueue, descriptorSetLayout, pipelineLayout, pipeline, pipelineCache,
                persistentCacheEnabled, cacheDirectory, cacheVendorId, cacheDeviceId, cacheUuid, fingerprint, executionContexts);
        } catch (final RuntimeException | LinkageError | OutOfMemoryError ex) {
            destroyExecutionContexts(executionContexts);
            if (shaderModule != VK_NULL_HANDLE && device != null) {
                vkDestroyShaderModule(device, shaderModule, null);
            }
            if (pipeline != VK_NULL_HANDLE && device != null) {
                vkDestroyPipeline(device, pipeline, null);
            }
            if (pipelineCache != VK_NULL_HANDLE && device != null) {
                vkDestroyPipelineCache(device, pipelineCache, null);
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
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
            barrier.get(0).sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT);
            vkCmdPipelineBarrier(context.commandBuffer(), VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_PIPELINE_STAGE_HOST_BIT, 0, barrier, null, null);
            checkVk(vkEndCommandBuffer(context.commandBuffer()), "end Vulkan command buffer");

            final VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                .pCommandBuffers(stack.pointers(context.commandBuffer().address()));

            synchronized (this.queueSubmitLock) {
                checkVk(vkResetFences(this.device, context.fence()), "reset Vulkan fence");
                checkVk(vkQueueSubmit(this.computeQueue, submitInfo, context.fence()), "submit Vulkan compute dispatch");
            }

            checkVk(vkWaitForFences(this.device, context.fence(), true, TimeUnit.MILLISECONDS.toNanos(GPurConfig.gpuTimeoutMillis)), "wait for Vulkan compute dispatch");
        }
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

    private MappedBufferAllocation ensureBufferCapacity(final MappedBufferAllocation existing, final long requiredSize) {
        if (existing != null && existing.size() >= requiredSize) {
            return existing;
        }

        MappedBufferAllocation replacement = this.createMappedBuffer(requiredSize);
        if (existing != null) this.destroyMappedBuffer(existing);
        return replacement;
    }

    private MappedBufferAllocation createMappedBuffer(final long minimumSize) {
        final long size = roundUp(Math.max(minimumSize, BUFFER_ALIGNMENT), BUFFER_ALIGNMENT);
        long buffer = VK_NULL_HANDLE;
        long memory = VK_NULL_HANDLE;
        boolean mapped = false;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkBufferCreateInfo bufferCreateInfo = VkBufferCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                .size(size)
                .usage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            final LongBuffer bufferHandle = stack.mallocLong(1);
            checkVk(vkCreateBuffer(this.device, bufferCreateInfo, null, bufferHandle), "create Vulkan storage buffer");
            buffer = bufferHandle.get(0);

            final VkMemoryRequirements memoryRequirements = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(this.device, buffer, memoryRequirements);

            final VkMemoryAllocateInfo allocateInfo = VkMemoryAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .allocationSize(memoryRequirements.size())
                .memoryTypeIndex(this.findMemoryTypeIndex(memoryRequirements.memoryTypeBits(), stack));

            final LongBuffer memoryHandle = stack.mallocLong(1);
            checkVk(vkAllocateMemory(this.device, allocateInfo, null, memoryHandle), "allocate Vulkan buffer memory");
            memory = memoryHandle.get(0);
            checkVk(vkBindBufferMemory(this.device, buffer, memory, 0L), "bind Vulkan buffer memory");

            final PointerBuffer mappedPointer = stack.mallocPointer(1);
            checkVk(vkMapMemory(this.device, memory, 0L, size, 0, mappedPointer), "map Vulkan buffer memory");
            mapped = true;
            final long mappedAddress = mappedPointer.get(0);
            final ByteBuffer mappedBytes = memByteBuffer(mappedAddress, Math.toIntExact(size)).order(ByteOrder.nativeOrder());
            return new MappedBufferAllocation(buffer, memory, size, mappedAddress, mappedBytes);
        } catch (RuntimeException | LinkageError | OutOfMemoryError failure) {
            if (mapped) vkUnmapMemory(this.device, memory);
            if (buffer != VK_NULL_HANDLE) vkDestroyBuffer(this.device, buffer, null);
            if (memory != VK_NULL_HANDLE) vkFreeMemory(this.device, memory, null);
            throw failure;
        }
    }

    private void destroyMappedBuffer(final MappedBufferAllocation allocation) {
        destroyMappedBuffer(this.device, allocation);
    }

    private int findMemoryTypeIndex(final int memoryTypeBits, final MemoryStack stack) {
        final VkPhysicalDeviceMemoryProperties memoryProperties = VkPhysicalDeviceMemoryProperties.calloc(stack);
        vkGetPhysicalDeviceMemoryProperties(this.physicalDevice, memoryProperties);

        int fallback = -1;
        for (int i = 0; i < memoryProperties.memoryTypeCount(); i++) {
            if ((memoryTypeBits & (1 << i)) == 0) continue;
            int flags = memoryProperties.memoryTypes(i).propertyFlags();
            int required = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
            if ((flags & required) != required) continue;
            if ((flags & VK_MEMORY_PROPERTY_HOST_CACHED_BIT) != 0) return i;
            if (fallback < 0) fallback = i;
        }
        if (fallback >= 0) return fallback;

        throw new IllegalStateException("No compatible Vulkan memory type was found for GPur buffers");
    }

    @Override
    public void close() {
        this.lifecycle.writeLock().lock();
        try {
            if (this.closed) return;
            this.closed = true;
            // No host dispatch or queue submission can race resource destruction.
            vkDeviceWaitIdle(this.device);
            destroyExecutionContexts(this.executionContexts);
            vkDestroyPipeline(this.device, this.pipeline, null);
            if (this.persistentCacheEnabled && this.pipelineCache != VK_NULL_HANDLE) this.savePipelineCache();
            if (this.pipelineCache != VK_NULL_HANDLE) vkDestroyPipelineCache(this.device, this.pipelineCache, null);
            vkDestroyPipelineLayout(this.device, this.pipelineLayout, null);
            vkDestroyDescriptorSetLayout(this.device, this.descriptorSetLayout, null);
            vkDestroyDevice(this.device, null);
            vkDestroyInstance(this.instance, null);
        } finally {
            this.lifecycle.writeLock().unlock();
        }
    }

    private void savePipelineCache() {
        ByteBuffer data = null;
        try {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer size = stack.mallocPointer(1);
                int result = vkGetPipelineCacheData(this.device, this.pipelineCache, size, null);
                if (result != VK_SUCCESS || size.get(0) < GpuPipelineCache.HEADER_BYTES || size.get(0) > GpuPipelineCache.MAX_BYTES) {
                    Logger.getLogger("GPur").log(Level.WARNING,
                        "Could not read a bounded Vulkan pipeline cache for GPU {0} (vk result {1})", new Object[] {this.deviceName, result});
                    return;
                }
                data = org.lwjgl.system.MemoryUtil.memAlloc(Math.toIntExact(size.get(0)));
                result = vkGetPipelineCacheData(this.device, this.pipelineCache, size, data);
                if (result != VK_SUCCESS || size.get(0) > data.capacity()) {
                    Logger.getLogger("GPur").log(Level.WARNING,
                        "Could not read Vulkan pipeline cache data for GPU {0} (vk result {1})", new Object[] {this.deviceName, result});
                    return;
                }
                byte[] bytes = new byte[Math.toIntExact(size.get(0))];
                data.position(0).limit(bytes.length).get(bytes);
                GpuPipelineCache.save(this.cacheDirectory, this.fingerprint, bytes,
                    this.cacheVendorId, this.cacheDeviceId, this.cacheUuid, Logger.getLogger("GPur"));
            }
        } catch (RuntimeException | OutOfMemoryError failure) {
            Logger.getLogger("GPur").log(Level.WARNING, "Could not save Vulkan pipeline cache for GPU " + this.deviceName, failure);
        } finally {
            if (data != null) org.lwjgl.system.MemoryUtil.memFree(data);
        }
    }

    private static List<ExecutionContext> createExecutionContexts(
        final VkDevice device,
        final VkPhysicalDevice physicalDevice,
        final long descriptorSetLayout,
        final int queueFamilyIndex,
        final int executionContextCount
    ) {
        final List<ExecutionContext> contexts = new ArrayList<>(executionContextCount);
        try {
            for (int index = 0; index < executionContextCount; index++) {
                contexts.add(createExecutionContext(device, physicalDevice, descriptorSetLayout, queueFamilyIndex));
            }
            return contexts;
        } catch (final RuntimeException | LinkageError | OutOfMemoryError ex) {
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
        } catch (final RuntimeException | LinkageError | OutOfMemoryError ex) {
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

    public record Info(String uuid, String name, boolean float64) {}

    private static String uuid(VkPhysicalDevice physicalDevice, MemoryStack stack) {
        VkPhysicalDeviceIDProperties id = VkPhysicalDeviceIDProperties.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ID_PROPERTIES);
        VkPhysicalDeviceProperties2 properties = VkPhysicalDeviceProperties2.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2).pNext(id.address());
        vkGetPhysicalDeviceProperties2(physicalDevice, properties);
        byte[] bytes = new byte[VK_UUID_SIZE];
        id.deviceUUID().get(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public static List<Info> discover() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkInstanceCreateInfo create = VkInstanceCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                .pApplicationInfo(VkApplicationInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_APPLICATION_INFO).apiVersion(VK_API_VERSION_1_1));
            PointerBuffer handle = stack.mallocPointer(1);
            checkVk(vkCreateInstance(create, null, handle), "discover GPUs");
            VkInstance instance = new VkInstance(handle.get(0), create);
            try {
                IntBuffer count = stack.ints(0);
                checkVk(vkEnumeratePhysicalDevices(instance, count, null), "enumerate GPUs");
                PointerBuffer devices = stack.mallocPointer(count.get(0));
                checkVk(vkEnumeratePhysicalDevices(instance, count, devices), "enumerate GPUs");
                java.util.Map<String, Info> result = new java.util.LinkedHashMap<>();
                for (int i = 0; i < devices.capacity(); i++) {
                    VkPhysicalDevice device = new VkPhysicalDevice(devices.get(i), instance);
                    VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
                    vkGetPhysicalDeviceProperties(device, properties);
                    if (properties.apiVersion() < VK_API_VERSION_1_1) continue;
                    VkPhysicalDeviceFeatures features = VkPhysicalDeviceFeatures.calloc(stack);
                    vkGetPhysicalDeviceFeatures(device, features);
                    String uuid = uuid(device, stack);
                    result.putIfAbsent(uuid, new Info(uuid, properties.deviceNameString(), features.shaderFloat64()));
                }
                return List.copyOf(result.values());
            } finally {
                vkDestroyInstance(instance, null);
            }
        }
    }

    private static PhysicalDeviceSelection selectPhysicalDevice(VkInstance instance, MemoryStack stack, String uuid) {
        IntBuffer count = stack.ints(0);
        checkVk(vkEnumeratePhysicalDevices(instance, count, null), "enumerate GPUs");
        PointerBuffer devices = stack.mallocPointer(count.get(0));
        checkVk(vkEnumeratePhysicalDevices(instance, count, devices), "enumerate GPUs");
        for (int i = 0; i < devices.capacity(); i++) {
            VkPhysicalDevice device = new VkPhysicalDevice(devices.get(i), instance);
            if (!uuid.equals(uuid(device, stack))) continue;
            VkPhysicalDeviceFeatures features = VkPhysicalDeviceFeatures.calloc(stack);
            vkGetPhysicalDeviceFeatures(device, features);
            if (!features.shaderFloat64()) continue;
            IntBuffer families = stack.ints(0);
            vkGetPhysicalDeviceQueueFamilyProperties(device, families, null);
            VkQueueFamilyProperties.Buffer queues = VkQueueFamilyProperties.calloc(families.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(device, families, queues);
            for (int j = 0; j < queues.capacity(); j++) {
                if ((queues.get(j).queueFlags() & VK_QUEUE_COMPUTE_BIT) == 0 || queues.get(j).queueCount() == 0) continue;
                VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
                vkGetPhysicalDeviceProperties(device, properties);
                return new PhysicalDeviceSelection(device, j, properties.deviceNameString());
            }
        }
        throw new IllegalStateException("No suitable Vulkan 1.1 FP64 compute GPU for UUID " + uuid);
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
        try (InputStream inputStream = VulkanDevice.class.getClassLoader().getResourceAsStream(SHADER_RESOURCE)) {
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

    private static String shaderSha256() {
        try (InputStream inputStream = VulkanDevice.class.getClassLoader().getResourceAsStream(SHADER_RESOURCE)) {
            if (inputStream == null) throw new IllegalStateException("Missing shader resource: " + SHADER_RESOURCE);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(inputStream.readAllBytes()));
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Failed to fingerprint Vulkan compute shader", failure);
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
