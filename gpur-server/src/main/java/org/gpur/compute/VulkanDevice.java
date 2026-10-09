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
import static org.lwjgl.vulkan.VK10.VK_COMMAND_POOL_CREATE_TRANSIENT_BIT;
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
import static org.lwjgl.vulkan.VK10.vkGetFenceStatus;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceMemoryProperties;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceProperties;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceQueueFamilyProperties;
import static org.lwjgl.vulkan.VK10.vkMapMemory;
import static org.lwjgl.vulkan.VK10.vkQueueSubmit;
import static org.lwjgl.vulkan.VK10.vkResetCommandPool;
import static org.lwjgl.vulkan.VK10.vkResetFences;
import static org.lwjgl.vulkan.VK10.vkUnmapMemory;
import static org.lwjgl.vulkan.VK10.vkUpdateDescriptorSets;
import static org.lwjgl.vulkan.VK10.vkWaitForFences;
import static org.lwjgl.vulkan.VK11.VK_API_VERSION_1_1;

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
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferMemoryBarrier;
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
import org.lwjgl.vulkan.VkMappedMemoryRange;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineCacheCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;


/** Owns one logical device. Timed-out contexts stay held until their fence signals or idle teardown is confirmed. */
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
    // Startup prepares optional entries before admission; later entries belong to the native worker.
    private final java.util.Map<Integer, Long> worldgenPipelines = new java.util.HashMap<>();
    private final long pipelineCache;
    private final boolean persistentCacheEnabled;
    private final Path cacheDirectory;
    private final int cacheVendorId;
    private final int cacheDeviceId;
    private final byte[] cacheUuid;
    private final String fingerprint;
    private final List<ExecutionContext> executionContexts;
    private final BlockingQueue<ExecutionContext> contexts;
    private final BlockingQueue<ComputeJob> asyncJobs;
    private final Thread asyncThread;
    private final int batchMaxJobs;
    private final long batchWaitNanos;
    private final long timeoutNanos;
    private final long queueByteLimit;
    private final long bufferBudgetBytes;
    private final long deviceLocalThresholdBytes;
    private final boolean timestampsEnabled;
    private final int timestampValidBits;
    private final double timestampPeriodNanos;
    private final AtomicLong nextTicket = new AtomicLong();
    private final AtomicLong queuedBytes = new AtomicLong();
    private final AtomicLong residentBytes = new AtomicLong();
    private final AtomicLong allocationCount = new AtomicLong();
    private final AtomicLong submittedJobs = new AtomicLong();
    private final AtomicLong completedJobs = new AtomicLong();
    private final AtomicLong rejectedJobs = new AtomicLong();
    private final AtomicLong timedOutJobs = new AtomicLong();
    private final AtomicLong failedJobs = new AtomicLong();
    private final AtomicLong batchesSubmitted = new AtomicLong();
    private final AtomicLong inputBytes = new AtomicLong();
    private final AtomicLong outputBytes = new AtomicLong();
    private final AtomicLong submitCpuNanos = new AtomicLong();
    private final AtomicLong hostWriteNanos = new AtomicLong();
    private final AtomicLong readbackNanos = new AtomicLong();
    private final AtomicLong queuedNanos = new AtomicLong();
    private final AtomicLong gpuSamples = new AtomicLong();
    private final AtomicLong gpuNanos = new AtomicLong();
    private final AtomicLong lastGpuNanos = new AtomicLong();
    private final AtomicLong fallbackFenceNanos = new AtomicLong();
    private final AtomicInteger inFlightBatches = new AtomicInteger();
    private final AtomicInteger admittedJobs = new AtomicInteger();
    private int consecutiveTimeoutBatches;
    private final Object queueSubmitLock = new Object();
    private final Object admissionLock = new Object();
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();
    private volatile boolean failed;
    private volatile boolean closed;
    private final AtomicInteger busy = new AtomicInteger();
    private final AtomicInteger usableContextCount;
    private final java.util.concurrent.atomic.AtomicBoolean closeStarted = new java.util.concurrent.atomic.AtomicBoolean();
    private final CountDownLatch closeComplete = new CountDownLatch(1);
    private final VulkanCloseLifecycle closeLifecycle = new VulkanCloseLifecycle();

    private VulkanDevice(String uuid, String deviceName, VkInstance instance, VkPhysicalDevice physicalDevice,
                         VkDevice device, VkQueue computeQueue, long descriptorSetLayout, long pipelineLayout,
                         long pipeline, long pipelineCache, boolean persistentCacheEnabled, Path cacheDirectory,
                         int cacheVendorId, int cacheDeviceId, byte[] cacheUuid, String fingerprint,
                         List<ExecutionContext> executionContexts, int batchMaxJobs, long batchWaitNanos,
                         int queueCapacity, long queueByteLimit, long bufferBudgetBytes, long deviceLocalThresholdBytes,
                         long timeoutNanos,
                         boolean timestampsEnabled, int timestampValidBits, double timestampPeriodNanos) {
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
        this.batchMaxJobs = Math.max(1, Math.min(executionContexts.size(), batchMaxJobs));
        this.batchWaitNanos = Math.max(0L, batchWaitNanos);
        this.timeoutNanos = Math.max(1L, timeoutNanos);
        this.queueByteLimit = Math.max(1L, queueByteLimit);
        this.bufferBudgetBytes = Math.max(1L, bufferBudgetBytes);
        this.deviceLocalThresholdBytes = Math.max(0L, deviceLocalThresholdBytes);
        this.timestampsEnabled = timestampsEnabled && timestampValidBits > 0;
        this.timestampValidBits = timestampValidBits;
        this.timestampPeriodNanos = timestampPeriodNanos;
        this.asyncJobs = new ArrayBlockingQueue<>(Math.max(1, queueCapacity));
        this.usableContextCount = new AtomicInteger(executionContexts.size());
        this.asyncThread = new Thread(this::runAsync, "GPur-Vulkan-" + uuid.substring(0, Math.min(8, uuid.length())));
        this.asyncThread.setDaemon(true);
        this.asyncThread.start();
    }

    public String name() { return this.deviceName; }
    public String uuid() { return this.uuid; }
    public String fingerprint() { return this.fingerprint; }
    public void disable() {
        synchronized (this.admissionLock) {
            this.failed = true;
        }
        this.asyncThread.interrupt();
    }
    public boolean available() { return !this.failed && !this.closed && this.usableContextCount.get() > 0; }
    public int busy() { return this.busy.get(); }
    public boolean canAccept(int workload) {
        int count = this.usableContextCount.get();
        int occupied = this.busy.get();
        int reserved = Math.max(0, Math.min(GPurConfig.antiXrayGpuReservedContexts, count - 1));
        return this.available() && occupied < count && occupied * 100 < GPurConfig.gpuUsageFallback * count
            && (workload != 1 || occupied < count - reserved);
    }

    /** A non-blocking request. The input is copied before it enters the bounded queue. */
    public CompletableFuture<int[]> computeAsync(int[] input, int outputWords, int invocations) {
        validateRequest(input, outputWords, invocations);
        if (!this.available()) {
            this.rejectedJobs.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }
        final long bytes = (long) input.length * Integer.BYTES;
        if (!reserveQueuedBytes(bytes)) {
            this.rejectedJobs.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }
        final int[] immutableInput;
        try {
            immutableInput = input.clone();
        } catch (OutOfMemoryError allocationFailure) {
            this.queuedBytes.addAndGet(-bytes);
            this.rejectedJobs.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }
        final ComputeJob job;
        try {
            job = new ComputeJob(this.nextTicket.incrementAndGet(), immutableInput, outputWords,
                invocations, bytes, System.nanoTime());
        } catch (OutOfMemoryError allocationFailure) {
            this.queuedBytes.addAndGet(-bytes);
            this.rejectedJobs.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }
        synchronized (this.admissionLock) {
            int contextLimit = this.usableContextCount.get();
            int reserved = Math.max(0, Math.min(GPurConfig.antiXrayGpuReservedContexts, contextLimit - 1));
            int outstanding = this.admittedJobs.get();
            boolean reservedLimitReached = input[0] == 1 && outstanding >= contextLimit - reserved;
            if (!this.available() || reservedLimitReached) {
                this.queuedBytes.addAndGet(-bytes);
                this.rejectedJobs.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            }
            this.admittedJobs.incrementAndGet();
            if (!this.asyncJobs.offer(job)) {
                this.admittedJobs.decrementAndGet();
                this.queuedBytes.addAndGet(-bytes);
                this.rejectedJobs.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            }
        }
        LockSupport.unpark(this.asyncThread);
        return job.result;
    }

    private boolean reserveQueuedBytes(long bytes) {
        for (;;) {
            long current = this.queuedBytes.get();
            if (bytes > this.queueByteLimit - current) return false;
            if (this.queuedBytes.compareAndSet(current, current + bytes)) return true;
        }
    }

    private static void validateRequest(int[] input, int outputWords, int invocations) {
        if (input == null || input.length == 0 || outputWords <= 0 || invocations <= 0
                || input.length > MAX_BUFFER_BYTES / Integer.BYTES || outputWords > MAX_BUFFER_BYTES / Integer.BYTES) {
            throw new IllegalArgumentException("Invalid GPur compute buffer dimensions");
        }
        ExactCompute.validate(input);
        if (input[1] != invocations || outputWords != ExactCompute.outputWords(input)) {
            throw new IllegalArgumentException("Inconsistent GPur dispatch dimensions");
        }
    }

    /** Null means busy/unavailable; it is safe for the caller to use the CPU reference. */
    public int[] compute(int[] input, int outputWords, int invocations) {
        validateRequest(input, outputWords, invocations);
        if (Thread.currentThread() == this.asyncThread) return null;
        if (!this.canAccept(input[0])) return null;
        CompletableFuture<int[]> result = this.computeAsync(input, outputWords, invocations);
        try {
            return result.get(this.timeoutNanos + this.batchWaitNanos + TimeUnit.MILLISECONDS.toNanos(100), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            result.cancel(false);
            return null;
        } catch (ExecutionException | TimeoutException failure) {
            result.cancel(false);
            return null;
        }
    }

    public DeviceMetrics metrics() {
        long completed = this.completedJobs.get();
        return new DeviceMetrics(this.submittedJobs.get(), completed, this.rejectedJobs.get(), this.timedOutJobs.get(),
            this.failedJobs.get(), this.batchesSubmitted.get(), this.inputBytes.get(), this.outputBytes.get(),
            this.submitCpuNanos.get(), this.hostWriteNanos.get(), this.readbackNanos.get(), this.queuedNanos.get(),
            this.gpuSamples.get(), this.gpuNanos.get(), this.lastGpuNanos.get(), this.fallbackFenceNanos.get(),
            this.allocationCount.get(), this.residentBytes.get(),
            this.asyncJobs.size(), this.inFlightBatches.get(), this.available());
    }

    public static VulkanDevice create(String uuid) {
        final int executionContextCount = Math.max(1, Math.min(16, GPurConfig.gpuExecutionContexts));
        final int configuredBatchMaxJobs = Math.max(1, Math.min(16, GPurConfig.gpuBatchMaxJobs));
        final long configuredBatchWaitNanos = TimeUnit.MICROSECONDS.toNanos(Math.max(0, Math.min(10_000, GPurConfig.gpuBatchWaitMicros)));
        final int configuredQueueCapacity = Math.max(1, Math.min(256, GPurConfig.gpuAsyncQueueCapacity));
        final long configuredBufferBudgetBytes = Math.max(1L, Math.min(4096L, GPurConfig.gpuBufferBudgetMiB)) * 1024L * 1024L;
        final long configuredDeviceLocalThresholdBytes = Math.max(0L, Math.min(MAX_BUFFER_BYTES * 2L, GPurConfig.gpuDeviceLocalThresholdBytes));
        final long configuredTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(1, GPurConfig.gpuTimeoutMillis));
        final boolean configuredTimestampsEnabled = GPurConfig.gpuTimestampEnabled;
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
            final double timestampPeriodNanos = selectedProperties.limits().timestampPeriod();
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
                executionContextCount,
                configuredTimestampsEnabled && selectedDevice.timestampValidBits() > 0
            );

            vkDestroyShaderModule(device, shaderModule, null);
            shaderModule = VK_NULL_HANDLE;

            return new VulkanDevice(uuid, selectedDevice.deviceName(), instance, physicalDevice, device,
                computeQueue, descriptorSetLayout, pipelineLayout, pipeline, pipelineCache,
                persistentCacheEnabled, cacheDirectory, cacheVendorId, cacheDeviceId, cacheUuid, fingerprint, executionContexts,
                configuredBatchMaxJobs, configuredBatchWaitNanos, configuredQueueCapacity,
                configuredBufferBudgetBytes, configuredBufferBudgetBytes, configuredDeviceLocalThresholdBytes, configuredTimeoutNanos,
                configuredTimestampsEnabled, selectedDevice.timestampValidBits(), timestampPeriodNanos);
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

    private void recordDispatch(final ExecutionContext context, final int workload, final int totalInvocations,
                                final long inputBytes, final long outputBytes) {
        final long selectedPipeline = this.pipelineFor(workload);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            checkVk(vkResetCommandPool(this.device, context.commandPool(), 0), "reset Vulkan command pool");

            final VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            checkVk(vkBeginCommandBuffer(context.commandBuffer(), beginInfo), "begin Vulkan command buffer");

            if (context.deviceLocal()) {
                final VkBufferCopy.Buffer inputCopy = VkBufferCopy.calloc(1, stack)
                    .srcOffset(0L).dstOffset(0L).size(inputBytes);
                vkCmdCopyBuffer(context.commandBuffer(), context.inputStaging().buffer(), context.inputBuffer().buffer(), inputCopy);
                VkBufferMemoryBarrier.Buffer inputBarrier = VkBufferMemoryBarrier.calloc(1, stack);
                inputBarrier.get(0).sType(VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                    .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .buffer(context.inputBuffer().buffer()).offset(0L).size(inputBytes);
                vkCmdPipelineBarrier(context.commandBuffer(), VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, null, inputBarrier, null);
            }

            if (context.queryPool() != VK_NULL_HANDLE) {
                vkCmdResetQueryPool(context.commandBuffer(), context.queryPool(), 0, 2);
            }

            vkCmdBindPipeline(context.commandBuffer(), VK_PIPELINE_BIND_POINT_COMPUTE, selectedPipeline);
            vkCmdBindDescriptorSets(context.commandBuffer(), VK_PIPELINE_BIND_POINT_COMPUTE, this.pipelineLayout, 0, stack.longs(context.descriptorSet()), null);
            if (context.queryPool() != VK_NULL_HANDLE) {
                vkCmdWriteTimestamp(context.commandBuffer(), VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, context.queryPool(), 0);
            }
            vkCmdDispatch(context.commandBuffer(), Math.max(1, divideCeil(totalInvocations, LOCAL_SIZE_X)), 1, 1);
            if (context.queryPool() != VK_NULL_HANDLE) {
                vkCmdWriteTimestamp(context.commandBuffer(), VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, context.queryPool(), 1);
            }
            if (context.deviceLocal()) {
                VkBufferMemoryBarrier.Buffer outputBarrier = VkBufferMemoryBarrier.calloc(1, stack);
                outputBarrier.get(0).sType(VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .buffer(context.outputBuffer().buffer()).offset(0L).size(outputBytes);
                vkCmdPipelineBarrier(context.commandBuffer(), VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, outputBarrier, null);
                final VkBufferCopy.Buffer outputCopy = VkBufferCopy.calloc(1, stack)
                    .srcOffset(0L).dstOffset(0L).size(outputBytes);
                vkCmdCopyBuffer(context.commandBuffer(), context.outputBuffer().buffer(), context.outputStaging().buffer(), outputCopy);
                VkBufferMemoryBarrier.Buffer hostBarrier = VkBufferMemoryBarrier.calloc(1, stack);
                hostBarrier.get(0).sType(VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                    .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .buffer(context.outputStaging().buffer()).offset(0L).size(outputBytes);
                vkCmdPipelineBarrier(context.commandBuffer(), VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_HOST_BIT, 0, null, hostBarrier, null);
            } else {
                VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
                barrier.get(0).sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT);
                vkCmdPipelineBarrier(context.commandBuffer(), VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_PIPELINE_STAGE_HOST_BIT, 0, barrier, null, null);
            }
            checkVk(vkEndCommandBuffer(context.commandBuffer()), "end Vulkan command buffer");
        }
    }

    private void runAsync() {
        final List<SubmittedBatch> inFlight = new ArrayList<>(this.executionContexts.size());
        try {
            while (!this.closed) {
                this.collectCompleted(inFlight);
                this.expireQueuedJobs();
                if (this.closed) break;
                if (!this.available()) {
                    this.failQueuedJobs();
                    this.waitForProgress(inFlight);
                    continue;
                }
                int availableContexts = this.contexts.size();
                if (availableContexts == 0) {
                    this.waitForProgress(inFlight);
                    continue;
                }
                List<ComputeJob> batch = new ArrayList<>(this.batchMaxJobs);
                ComputeJob first = this.asyncJobs.poll();
                while (first != null && (first.result().isCancelled() || this.isExpired(first, System.nanoTime()))) {
                    if (this.isExpired(first, System.nanoTime())) this.timedOutJobs.incrementAndGet();
                    this.finishJob(first, null, false);
                    first = this.asyncJobs.poll();
                }
                if (first == null) {
                    this.waitForProgress(inFlight);
                    continue;
                }
                batch.add(first);
                int targetCount = Math.min(this.batchMaxJobs, availableContexts);
                long deadline = System.nanoTime() + this.batchWaitNanos;
                while (batch.size() < targetCount && !this.closed) {
                    long remaining = deadline - System.nanoTime();
                    if (this.batchWaitNanos > 0L && remaining <= 0L) break;
                    try {
                        ComputeJob next = this.batchWaitNanos > 0L
                            ? this.asyncJobs.poll(remaining, TimeUnit.NANOSECONDS) : this.asyncJobs.poll();
                        if (next == null) break;
                        if (next.result().isCancelled() || this.isExpired(next, System.nanoTime())) {
                            if (this.isExpired(next, System.nanoTime())) this.timedOutJobs.incrementAndGet();
                            this.finishJob(next, null, false);
                        }
                        else batch.add(next);
                    } catch (InterruptedException interrupted) {
                        if (this.closed) break;
                        if (!this.available()) break;
                    }
                }
                for (Iterator<ComputeJob> iterator = batch.iterator(); iterator.hasNext();) {
                    ComputeJob job = iterator.next();
                    if (this.isExpired(job, System.nanoTime())) {
                        iterator.remove();
                        this.timedOutJobs.incrementAndGet();
                        this.finishJob(job, null, false);
                    }
                }
                if (batch.isEmpty()) continue;
                if (this.closed || !this.available()) {
                    for (ComputeJob job : batch) this.finishJob(job, null, false);
                    if (this.closed) break;
                    continue;
                }
                this.submitBatch(batch, inFlight);
            }
        } catch (RuntimeException | LinkageError | OutOfMemoryError failure) {
            this.failed = true;
            this.failedJobs.incrementAndGet();
            Logger.getLogger("GPur").log(Level.WARNING, "Vulkan async executor failed for GPU " + this.deviceName, failure);
        } finally {
            this.failQueuedJobs();
            for (SubmittedBatch batch : inFlight) {
                for (SubmittedJob submitted : batch.jobs()) {
                    this.finishJob(submitted.job(), null, false);
                    this.busy.decrementAndGet();
                }
            }
            inFlight.clear();
            this.inFlightBatches.set(0);
        }
    }

    private void submitBatch(List<ComputeJob> jobs, List<SubmittedBatch> inFlight) {
        final List<SubmittedJob> prepared;
        try {
            prepared = new ArrayList<>(jobs.size());
        } catch (OutOfMemoryError hostAllocationFailure) {
            this.failedJobs.addAndGet(jobs.size());
            for (ComputeJob job : jobs) this.finishJob(job, null, false);
            return;
        }
        final long submitStart = System.nanoTime();
        SubmittedBatch submittedBatch = null;
        boolean registeredBatch = false;
        boolean queueSubmitted = false;
        try {
            for (ComputeJob job : jobs) {
                if (job.result().isCancelled()) {
                    this.finishJob(job, null, false);
                    continue;
                }
                ExecutionContext context = this.contexts.peek();
                if (context == null) {
                    this.finishJob(job, null, false);
                    continue;
                }
                SubmittedJob preparedJob = new SubmittedJob(job, context);
                this.contexts.poll();
                this.busy.incrementAndGet();
                prepared.add(preparedJob);
                long writeStart = System.nanoTime();
                try {
                    long inputSize = job.admittedBytes();
                    long outputSize = (long) job.outputWords() * Integer.BYTES;
                    this.ensureContextBuffers(context, inputSize, outputSize);
                    BufferAllocation hostInput = context.deviceLocal() ? context.inputStaging() : context.inputBuffer();
                    int workload = job.input()[0];
                    hostInput.intView().put(job.input());
                    this.flushMappedBuffer(hostInput);
                    job.releaseInput();
                    this.hostWriteNanos.addAndGet(System.nanoTime() - writeStart);
                    this.updateDescriptorSet(context, context.inputBuffer(), context.outputBuffer());
                    this.recordDispatch(context, workload, job.invocations(), inputSize, outputSize);
                } catch (RuntimeException | OutOfMemoryError allocationFailure) {
                    if (!isAllocationFailure(allocationFailure)) throw allocationFailure;
                    prepared.remove(preparedJob);
                    this.failedJobs.incrementAndGet();
                    this.busy.decrementAndGet();
                    if (this.available()) this.contexts.offer(context);
                    this.finishJob(job, null, false);
                }
            }
            if (prepared.isEmpty()) return;
            if (this.closed || !this.available()) {
                for (SubmittedJob submitted : prepared) {
                    this.busy.decrementAndGet();
                    if (this.available()) this.contexts.offer(submitted.context());
                    this.finishJob(submitted.job(), null, false);
                }
                return;
            }
            submittedBatch = new SubmittedBatch(List.copyOf(prepared));
            // Register before submission so an allocation failure after vkQueueSubmit cannot
            // make submitted command buffers look reusable to the worker.
            inFlight.add(submittedBatch);
            this.inFlightBatches.incrementAndGet();
            registeredBatch = true;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer commandBuffers = stack.mallocPointer(prepared.size());
                for (SubmittedJob submitted : prepared) commandBuffers.put(submitted.context().commandBuffer().address());
                commandBuffers.flip();
                final VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .pCommandBuffers(commandBuffers);
                long fence = prepared.get(0).context().fence();
                synchronized (this.admissionLock) {
                    if (this.available()) {
                        synchronized (this.queueSubmitLock) {
                            checkVk(vkResetFences(this.device, fence), "reset Vulkan batch fence");
                            checkVk(vkQueueSubmit(this.computeQueue, submitInfo, fence), "submit Vulkan compute batch");
                        }
                        queueSubmitted = true;
                    }
                }
                if (!queueSubmitted) {
                    inFlight.remove(submittedBatch);
                    this.inFlightBatches.decrementAndGet();
                    registeredBatch = false;
                    for (SubmittedJob submitted : prepared) {
                        this.busy.decrementAndGet();
                        this.finishJob(submitted.job(), null, false);
                    }
                    return;
                }
            }
            long submittedAt = System.nanoTime();
            long queueDelay = 0L;
            for (SubmittedJob submitted : prepared) {
                queueDelay += Math.max(0L, submittedAt - submitted.job().enqueuedAtNanos());
                this.submittedJobs.incrementAndGet();
                this.inputBytes.addAndGet(submitted.job().admittedBytes());
                this.outputBytes.addAndGet((long) submitted.job().outputWords() * Integer.BYTES);
            }
            this.queuedNanos.addAndGet(queueDelay);
            this.submitCpuNanos.addAndGet(submittedAt - submitStart);
            this.batchesSubmitted.incrementAndGet();
            submittedBatch.markSubmitted(submittedAt, this.timeoutNanos);
        } catch (OutOfMemoryError hostAllocationFailure) {
            this.failedJobs.addAndGet(jobs.size());
            if (queueSubmitted) {
                this.failed = true;
                for (SubmittedJob submitted : prepared) this.finishJob(submitted.job(), null, false);
            } else {
                if (registeredBatch) {
                    inFlight.remove(submittedBatch);
                    this.inFlightBatches.decrementAndGet();
                }
                for (SubmittedJob submitted : prepared) {
                    this.busy.decrementAndGet();
                    if (!this.failed && !this.closed) this.contexts.offer(submitted.context());
                    this.finishJob(submitted.job(), null, false);
                }
            }
            for (ComputeJob job : jobs) {
                if (prepared.stream().noneMatch(item -> item.job() == job)) this.finishJob(job, null, false);
            }
        } catch (RuntimeException | LinkageError failure) {
            this.failed = true;
            this.failedJobs.incrementAndGet();
            if (registeredBatch && !queueSubmitted) {
                inFlight.remove(submittedBatch);
                this.inFlightBatches.decrementAndGet();
            }
            for (SubmittedJob submitted : prepared) {
                if (!queueSubmitted) this.busy.decrementAndGet();
                this.finishJob(submitted.job(), null, false);
            }
            for (ComputeJob job : jobs) {
                if (prepared.stream().noneMatch(item -> item.job() == job)) this.finishJob(job, null, false);
            }
            throw failure;
        }
    }

    private void collectCompleted(List<SubmittedBatch> inFlight) {
        long now = System.nanoTime();
        for (Iterator<SubmittedBatch> iterator = inFlight.iterator(); iterator.hasNext();) {
            SubmittedBatch batch = iterator.next();
            int status = vkGetFenceStatus(this.device, batch.jobs().get(0).context().fence());
            VulkanBatchLifecycle.FenceEvent event = status == VK_SUCCESS
                ? VulkanBatchLifecycle.FenceEvent.SIGNALED
                : status == VK_NOT_READY ? VulkanBatchLifecycle.FenceEvent.NOT_READY : VulkanBatchLifecycle.FenceEvent.FAILED;
            VulkanBatchLifecycle.Outcome outcome = batch.lifecycle().onFence(
                event, now >= batch.deadlineNanos(), batch.jobs().size());
            if (outcome.timedOut()) {
                this.consecutiveTimeoutBatches++;
                this.timedOutJobs.addAndGet(batch.jobs().size());
                this.usableContextCount.addAndGet(outcome.usableContextDelta());
                for (SubmittedJob submitted : batch.jobs()) this.finishJob(submitted.job(), null, false);
                if (this.consecutiveTimeoutBatches >= 3) this.failed = true;
                continue;
            }
            if (!outcome.terminal()) continue;

            iterator.remove();
            this.inFlightBatches.decrementAndGet();
            if (outcome.usableContextDelta() != 0) {
                this.usableContextCount.addAndGet(outcome.usableContextDelta());
            }
            if (outcome.disableDevice()) {
                this.failed = true;
                this.failedJobs.incrementAndGet();
            }
            if (outcome.settleJobsNull()) {
                for (SubmittedJob submitted : batch.jobs()) {
                    this.finishJob(submitted.job(), null, false);
                    this.busy.decrementAndGet();
                }
                continue;
            }

            if (outcome.publishResults()) {
                this.consecutiveTimeoutBatches = 0;
                long readStart = System.nanoTime();
                for (SubmittedJob submitted : batch.jobs()) {
                    try {
                        if (this.available() && !submitted.job().result().isCancelled()) {
                            try {
                                submitted.setOutput(new int[submitted.job().outputWords()]);
                                BufferAllocation hostOutput = submitted.context().deviceLocal()
                                    ? submitted.context().outputStaging() : submitted.context().outputBuffer();
                                this.invalidateMappedBuffer(hostOutput);
                                hostOutput.intView().get(submitted.output());
                            } catch (OutOfMemoryError allocationFailure) {
                                this.failedJobs.incrementAndGet();
                                submitted.setOutput(null);
                            }
                            boolean hasGpuSample = this.recordGpuTimestamp(submitted.context());
                            if (!hasGpuSample) this.fallbackFenceNanos.addAndGet(Math.max(0L, now - batch.submittedAtNanos()));
                        }
                    } catch (RuntimeException | LinkageError | OutOfMemoryError readbackFailure) {
                        this.failed = true;
                        this.failedJobs.incrementAndGet();
                        submitted.setOutput(null);
                    }
                }
                this.readbackNanos.addAndGet(System.nanoTime() - readStart);
            }

            // A signaled fence makes every command buffer and staging allocation in this
            // batch reusable. Return all slots before invoking any future continuation.
            for (SubmittedJob submitted : batch.jobs()) {
                this.busy.decrementAndGet();
                if (outcome.reclaimContexts() && !this.failed && !this.closed) this.contexts.offer(submitted.context());
            }
            if (outcome.publishResults()) {
                for (SubmittedJob submitted : batch.jobs()) {
                    this.finishJob(submitted.job(), submitted.output(), true);
                }
            }
        }
    }

    private void waitForWork() {
        Thread.interrupted();
        if (this.closed || !this.asyncJobs.isEmpty()) return;
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1L));
    }

    private boolean isExpired(ComputeJob job, long now) {
        return VulkanBatchLifecycle.queueExpired(job.enqueuedAtNanos(), now, this.timeoutNanos);
    }

    private void expireQueuedJobs() {
        long now = System.nanoTime();
        for (Iterator<ComputeJob> iterator = this.asyncJobs.iterator(); iterator.hasNext();) {
            ComputeJob job = iterator.next();
            if (this.isExpired(job, now)) {
                iterator.remove();
                this.timedOutJobs.incrementAndGet();
                this.finishJob(job, null, false);
            }
        }
    }

    private void waitForProgress(List<SubmittedBatch> inFlight) {
        if (inFlight.isEmpty() && this.asyncJobs.isEmpty()) {
            this.waitForWork();
            return;
        }
        Thread.interrupted();
        long pollNanos = this.batchWaitNanos > 0L
            ? Math.min(TimeUnit.MICROSECONDS.toNanos(250L), Math.max(TimeUnit.MICROSECONDS.toNanos(50L), this.batchWaitNanos))
            : TimeUnit.MICROSECONDS.toNanos(100L);
        LockSupport.parkNanos(pollNanos);
    }

    private void failQueuedJobs() {
        ComputeJob job;
        while ((job = this.asyncJobs.poll()) != null) this.finishJob(job, null, false);
    }

    private void finishJob(ComputeJob job, int[] value, boolean completed) {
        if (!job.released().compareAndSet(false, true)) return;
        if (completed) this.completedJobs.incrementAndGet();
        try {
            if (!job.result().isCancelled()) {
                boolean publishResult;
                synchronized (this.admissionLock) {
                    publishResult = value != null && this.available();
                }
                job.result().complete(publishResult ? value : null);
            }
        } finally {
            // Keep admission charged while user continuations run inline. This bounds both
            // queued work and completed-but-unconsumed results without a common-pool backlog.
            this.queuedBytes.addAndGet(-job.admittedBytes());
            this.admittedJobs.decrementAndGet();
        }
    }

    private boolean recordGpuTimestamp(ExecutionContext context) {
        if (!this.timestampsEnabled || context.queryPool() == VK_NULL_HANDLE || this.timestampValidBits <= 0
                || !(this.timestampPeriodNanos > 0.0) || !Double.isFinite(this.timestampPeriodNanos)) return false;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer results = stack.mallocLong(2);
            int status = vkGetQueryPoolResults(this.device, context.queryPool(), 0, 2,
                results, (long) Long.BYTES, VK_QUERY_RESULT_64_BIT);
            if (status == VK_ERROR_DEVICE_LOST) this.disable();
            if (status != VK_SUCCESS) return false;
            long ticks = results.get(1) - results.get(0);
            if (this.timestampValidBits < Long.SIZE) ticks &= (1L << this.timestampValidBits) - 1L;
            if (ticks < 0L) return false;
            double nanos = ticks * this.timestampPeriodNanos;
            if (!Double.isFinite(nanos) || nanos < 0.0 || nanos > Long.MAX_VALUE) return false;
            this.gpuNanos.addAndGet((long) nanos);
            this.lastGpuNanos.set((long) nanos);
            this.gpuSamples.incrementAndGet();
            return true;
        }
    }

    private void updateDescriptorSet(
        final ExecutionContext context,
        final BufferAllocation inputBuffer,
        final BufferAllocation outputBuffer
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

    private void ensureContextBuffers(ExecutionContext context, long inputSize, long outputSize) {
        if (!context.deviceLocal() && this.deviceLocalThresholdBytes > 0L
                && inputSize + outputSize >= this.deviceLocalThresholdBytes) {
            List<BufferAllocation> staged = new ArrayList<>(4);
            try {
                long inputCapacity = growthTarget(null, inputSize);
                long outputCapacity = growthTarget(null, outputSize);
                staged.add(this.createBuffer(inputCapacity, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, true, false));
                staged.add(this.createBuffer(outputCapacity, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT, true, false));
                staged.add(this.createBuffer(inputCapacity, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, false, true));
                staged.add(this.createBuffer(outputCapacity, VK_BUFFER_USAGE_TRANSFER_DST_BIT, false, true));
                if (context.inputBuffer() != null) this.destroyBufferAllocation(context.inputBuffer());
                if (context.outputBuffer() != null) this.destroyBufferAllocation(context.outputBuffer());
                context.setInputBuffer(staged.get(0));
                context.setOutputBuffer(staged.get(1));
                context.setInputStaging(staged.get(2));
                context.setOutputStaging(staged.get(3));
                context.setDeviceLocal(true);
                staged.clear();
            } catch (RuntimeException | OutOfMemoryError unavailable) {
                if (!isAllocationFailure(unavailable)) throw unavailable;
                for (BufferAllocation allocation : staged) this.destroyBufferAllocation(allocation);
            }
        }

        if (context.deviceLocal()) {
            try {
                context.setInputBuffer(this.ensureBufferCapacity(context.inputBuffer(), inputSize,
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, true, false));
                context.setOutputBuffer(this.ensureBufferCapacity(context.outputBuffer(), outputSize,
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT, true, false));
                context.setInputStaging(this.ensureBufferCapacity(context.inputStaging(), inputSize,
                    VK_BUFFER_USAGE_TRANSFER_SRC_BIT, false, true));
                context.setOutputStaging(this.ensureBufferCapacity(context.outputStaging(), outputSize,
                    VK_BUFFER_USAGE_TRANSFER_DST_BIT, false, true));
                return;
            } catch (RuntimeException | OutOfMemoryError unavailable) {
                if (!isAllocationFailure(unavailable)) throw unavailable;
                this.destroyContextBuffers(context);
                context.setDeviceLocal(false);
            }
        }

        try {
            context.setInputBuffer(this.ensureBufferCapacity(context.inputBuffer(), inputSize,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false, true));
            context.setOutputBuffer(this.ensureBufferCapacity(context.outputBuffer(), outputSize,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false, true));
        } catch (RuntimeException | OutOfMemoryError unavailable) {
            if (!isAllocationFailure(unavailable)) throw unavailable;
            this.destroyContextBuffers(context);
            context.setDeviceLocal(false);
            context.setInputBuffer(this.ensureBufferCapacity(null, inputSize, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false, true));
            context.setOutputBuffer(this.ensureBufferCapacity(null, outputSize, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false, true));
        }
    }

    private BufferAllocation ensureBufferCapacity(BufferAllocation existing, long requiredSize, int usage,
                                                  boolean deviceLocal, boolean mapHost) {
        if (existing != null && existing.size() >= requiredSize) return existing;
        BufferAllocation replacement = this.createBuffer(growthTarget(existing, requiredSize), usage, deviceLocal, mapHost);
        if (existing != null) this.destroyBufferAllocation(existing);
        return replacement;
    }

    private static long growthTarget(BufferAllocation existing, long requiredSize) {
        return VulkanMemoryPolicy.growthTarget(existing == null ? 0L : existing.size(), requiredSize,
            MAX_BUFFER_BYTES, BUFFER_ALIGNMENT);
    }

    private void destroyContextBuffers(ExecutionContext context) {
        if (context.inputBuffer() != null) this.destroyBufferAllocation(context.inputBuffer());
        if (context.outputBuffer() != null) this.destroyBufferAllocation(context.outputBuffer());
        if (context.inputStaging() != null) this.destroyBufferAllocation(context.inputStaging());
        if (context.outputStaging() != null) this.destroyBufferAllocation(context.outputStaging());
        context.setInputBuffer(null);
        context.setOutputBuffer(null);
        context.setInputStaging(null);
        context.setOutputStaging(null);
    }

    private BufferAllocation createBuffer(final long minimumSize, final int usage, final boolean deviceLocal,
                                         final boolean mapHost) {
        final long size = roundUp(Math.max(minimumSize, BUFFER_ALIGNMENT), BUFFER_ALIGNMENT);
        long buffer = VK_NULL_HANDLE;
        long memory = VK_NULL_HANDLE;
        boolean mapped = false;
        boolean budgetReserved = false;
        long allocationSize = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkBufferCreateInfo bufferCreateInfo = VkBufferCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                .size(size)
                .usage(usage)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            final LongBuffer bufferHandle = stack.mallocLong(1);
            checkVk(vkCreateBuffer(this.device, bufferCreateInfo, null, bufferHandle), "create Vulkan buffer");
            buffer = bufferHandle.get(0);

            final VkMemoryRequirements memoryRequirements = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(this.device, buffer, memoryRequirements);
            allocationSize = memoryRequirements.size();
            if (!this.reserveResidentBytes(allocationSize)) {
                throw new BufferBudgetExceededException();
            }
            budgetReserved = true;

            final int memoryTypeIndex = deviceLocal
                ? this.findDeviceLocalMemoryTypeIndex(memoryRequirements.memoryTypeBits(), stack)
                : this.findMemoryTypeIndex(memoryRequirements.memoryTypeBits(), stack);
            final VkMemoryAllocateInfo allocateInfo = VkMemoryAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .allocationSize(allocationSize)
                .memoryTypeIndex(memoryTypeIndex);

            final LongBuffer memoryHandle = stack.mallocLong(1);
            checkVk(vkAllocateMemory(this.device, allocateInfo, null, memoryHandle), "allocate Vulkan buffer memory");
            memory = memoryHandle.get(0);
            checkVk(vkBindBufferMemory(this.device, buffer, memory, 0L), "bind Vulkan buffer memory");

            long mappedAddress = NULL;
            ByteBuffer mappedBytes = null;
            if (mapHost) {
                final PointerBuffer mappedPointer = stack.mallocPointer(1);
                checkVk(vkMapMemory(this.device, memory, 0L, allocationSize, 0, mappedPointer), "map Vulkan buffer memory");
                mapped = true;
                mappedAddress = mappedPointer.get(0);
                mappedBytes = memByteBuffer(mappedAddress, Math.toIntExact(size)).order(ByteOrder.nativeOrder());
            }
            final int memoryFlags = this.getMemoryTypeFlags(memoryTypeIndex, stack);
            this.allocationCount.incrementAndGet();
            return new BufferAllocation(buffer, memory, size, allocationSize, mappedAddress, mappedBytes,
                (memoryFlags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0);
        } catch (RuntimeException | LinkageError | OutOfMemoryError failure) {
            if (mapped) vkUnmapMemory(this.device, memory);
            if (buffer != VK_NULL_HANDLE) vkDestroyBuffer(this.device, buffer, null);
            if (memory != VK_NULL_HANDLE) vkFreeMemory(this.device, memory, null);
            if (budgetReserved) this.residentBytes.addAndGet(-allocationSize);
            throw failure;
        }
    }

    private void destroyBufferAllocation(final BufferAllocation allocation) {
        destroyBufferAllocation(this.device, allocation);
        this.residentBytes.addAndGet(-allocation.allocationSize());
    }

    private boolean reserveResidentBytes(long bytes) {
        return VulkanMemoryPolicy.tryReserve(this.residentBytes, this.bufferBudgetBytes, bytes);
    }

    private static boolean isAllocationFailure(Throwable failure) {
        return failure instanceof BufferBudgetExceededException
            || failure instanceof UnsupportedDeviceLocalMemoryException
            || failure instanceof OutOfMemoryError
            || failure instanceof VulkanResultException vkFailure
                && (vkFailure.result() == VK_ERROR_OUT_OF_HOST_MEMORY || vkFailure.result() == VK_ERROR_OUT_OF_DEVICE_MEMORY);
    }

    private void flushMappedBuffer(BufferAllocation allocation) {
        if (allocation.hostCoherent()) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMappedMemoryRange.Buffer range = VkMappedMemoryRange.calloc(1, stack);
            range.get(0).sType(VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE).memory(allocation.memory())
                .offset(0L).size(VK_WHOLE_SIZE);
            checkVk(vkFlushMappedMemoryRanges(this.device, range), "flush Vulkan host buffer writes");
        }
    }

    private void invalidateMappedBuffer(BufferAllocation allocation) {
        if (allocation.hostCoherent()) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMappedMemoryRange.Buffer range = VkMappedMemoryRange.calloc(1, stack);
            range.get(0).sType(VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE).memory(allocation.memory())
                .offset(0L).size(VK_WHOLE_SIZE);
            checkVk(vkInvalidateMappedMemoryRanges(this.device, range), "invalidate Vulkan host buffer reads");
        }
    }

    private int findMemoryTypeIndex(final int memoryTypeBits, final MemoryStack stack) {
        final VkPhysicalDeviceMemoryProperties memoryProperties = VkPhysicalDeviceMemoryProperties.calloc(stack);
        vkGetPhysicalDeviceMemoryProperties(this.physicalDevice, memoryProperties);

        int coherentFallback = -1;
        int noncoherentFallback = -1;
        for (int i = 0; i < memoryProperties.memoryTypeCount(); i++) {
            if ((memoryTypeBits & (1 << i)) == 0) continue;
            int flags = memoryProperties.memoryTypes(i).propertyFlags();
            if ((flags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) == 0) continue;
            if ((flags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) == 0) {
                if (noncoherentFallback < 0) noncoherentFallback = i;
                continue;
            }
            if ((flags & VK_MEMORY_PROPERTY_HOST_CACHED_BIT) != 0) return i;
            if (coherentFallback < 0) coherentFallback = i;
        }
        if (coherentFallback >= 0) return coherentFallback;
        if (noncoherentFallback >= 0) return noncoherentFallback;

        throw new IllegalStateException("No compatible Vulkan memory type was found for GPur buffers");
    }

    private int findDeviceLocalMemoryTypeIndex(final int memoryTypeBits, final MemoryStack stack) {
        final VkPhysicalDeviceMemoryProperties memoryProperties = VkPhysicalDeviceMemoryProperties.calloc(stack);
        vkGetPhysicalDeviceMemoryProperties(this.physicalDevice, memoryProperties);
        int fallback = -1;
        for (int i = 0; i < memoryProperties.memoryTypeCount(); i++) {
            if ((memoryTypeBits & (1 << i)) == 0) continue;
            int flags = memoryProperties.memoryTypes(i).propertyFlags();
            if ((flags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) == 0) continue;
            if ((flags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) == 0) return i;
            if (fallback < 0) fallback = i;
        }
        if (fallback >= 0) return fallback;
        throw new UnsupportedDeviceLocalMemoryException();
    }

    private int getMemoryTypeFlags(int index, MemoryStack stack) {
        final VkPhysicalDeviceMemoryProperties memoryProperties = VkPhysicalDeviceMemoryProperties.calloc(stack);
        vkGetPhysicalDeviceMemoryProperties(this.physicalDevice, memoryProperties);
        return memoryProperties.memoryTypes(index).propertyFlags();
    }

    @Override
    public void close() {
        if (Thread.currentThread() == this.asyncThread) {
            ForkJoinPool.commonPool().execute(this::close);
            return;
        }
        if (!this.closeStarted.compareAndSet(false, true)) {
            if (Thread.holdsLock(this.admissionLock)) return;
            boolean interrupted = false;
            while (this.closeComplete.getCount() != 0L) {
                try {
                    this.closeComplete.await();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
            return;
        }
        try {
            synchronized (this.admissionLock) {
                this.closed = true;
                this.closeLifecycle.requestClose();
            }
            this.asyncThread.interrupt();
            if (Thread.currentThread() != this.asyncThread) {
                boolean interrupted = false;
                while (this.asyncThread.isAlive()) {
                    try {
                        this.asyncThread.join();
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                }
                if (interrupted) Thread.currentThread().interrupt();
            }
            this.lifecycle.writeLock().lock();
            try {
                // No host dispatch or queue submission can race resource destruction.
                final int idleResult;
                try {
                    idleResult = vkDeviceWaitIdle(this.device);
                } catch (RuntimeException | LinkageError idleFailure) {
                    Logger.getLogger("GPur").log(Level.WARNING,
                        "Keeping Vulkan resources for GPU " + this.deviceName + " because vkDeviceWaitIdle failed", idleFailure);
                    return;
                }
                if (idleResult != VK_SUCCESS && idleResult != VK_ERROR_DEVICE_LOST) {
                    Logger.getLogger("GPur").log(Level.WARNING,
                        "Keeping Vulkan resources for GPU {0} because vkDeviceWaitIdle failed (vk result {1})",
                        new Object[] {this.deviceName, idleResult});
                    return;
                }
                this.closeLifecycle.confirmDeviceIdle();
                if (!this.closeLifecycle.mayDestroyNativeResources()) {
                    throw new IllegalStateException("Vulkan native destruction attempted before device idle");
                }
                for (ExecutionContext context : this.executionContexts) {
                    if (context.inputBuffer() != null) this.residentBytes.addAndGet(-context.inputBuffer().allocationSize());
                    if (context.outputBuffer() != null) this.residentBytes.addAndGet(-context.outputBuffer().allocationSize());
                    if (context.inputStaging() != null) this.residentBytes.addAndGet(-context.inputStaging().allocationSize());
                    if (context.outputStaging() != null) this.residentBytes.addAndGet(-context.outputStaging().allocationSize());
                }
                destroyExecutionContexts(this.executionContexts);
                vkDestroyPipeline(this.device, this.pipeline, null);
                for (long worldgenPipeline : this.worldgenPipelines.values()) {
                    vkDestroyPipeline(this.device, worldgenPipeline, null);
                }
                this.worldgenPipelines.clear();
                if (this.persistentCacheEnabled && this.pipelineCache != VK_NULL_HANDLE) this.savePipelineCache();
                if (this.pipelineCache != VK_NULL_HANDLE) vkDestroyPipelineCache(this.device, this.pipelineCache, null);
                vkDestroyPipelineLayout(this.device, this.pipelineLayout, null);
                vkDestroyDescriptorSetLayout(this.device, this.descriptorSetLayout, null);
                vkDestroyDevice(this.device, null);
                vkDestroyInstance(this.instance, null);
                this.closeLifecycle.markDestroyed();
            } finally {
                this.lifecycle.writeLock().unlock();
            }
        } finally {
            this.closeComplete.countDown();
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
        final int executionContextCount,
        final boolean timestampsEnabled
    ) {
        final List<ExecutionContext> contexts = new ArrayList<>(executionContextCount);
        try {
            for (int index = 0; index < executionContextCount; index++) {
                contexts.add(createExecutionContext(device, physicalDevice, descriptorSetLayout, queueFamilyIndex, timestampsEnabled));
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
        final int queueFamilyIndex,
        final boolean timestampsEnabled
    ) {
        long commandPool = VK_NULL_HANDLE;
        long descriptorPool = VK_NULL_HANDLE;
        long descriptorSet = VK_NULL_HANDLE;
        long fence = VK_NULL_HANDLE;
        long queryPool = VK_NULL_HANDLE;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkCommandPoolCreateInfo commandPoolCreateInfo = VkCommandPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                .flags(VK_COMMAND_POOL_CREATE_TRANSIENT_BIT)
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

            if (timestampsEnabled) {
                final VkQueryPoolCreateInfo queryCreateInfo = VkQueryPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO)
                    .queryType(VK_QUERY_TYPE_TIMESTAMP)
                    .queryCount(2);
                final LongBuffer queryHandle = stack.mallocLong(1);
                checkVk(vkCreateQueryPool(device, queryCreateInfo, null, queryHandle), "create Vulkan timestamp query pool");
                queryPool = queryHandle.get(0);
            }
            return new ExecutionContext(device, commandPool, commandBuffer, descriptorPool, descriptorSet, fence, queryPool);
        } catch (final RuntimeException | LinkageError | OutOfMemoryError ex) {
            if (queryPool != VK_NULL_HANDLE) vkDestroyQueryPool(device, queryPool, null);
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
                return new PhysicalDeviceSelection(device, j, queues.get(j).timestampValidBits(), properties.deviceNameString());
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
        return compileShader(SHADER_RESOURCE);
    }

    private static ByteBuffer compileShader(String resource) {
        final String source = VulkanShaderSources.source(resource);

        final long compiler = shaderc_compiler_initialize();
        if (compiler == NULL) {
            throw new IllegalStateException("Unable to initialize shaderc compiler");
        }

        final long result = shaderc_compile_into_spv(compiler, source, shaderc_compute_shader, resource, "main", NULL);
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
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String entryPoint : VulkanShaderSources.ENTRY_POINTS) {
                digest.update(entryPoint.getBytes(StandardCharsets.UTF_8));
                digest.update((byte)0);
                digest.update(VulkanShaderSources.source(entryPoint).getBytes(StandardCharsets.UTF_8));
                digest.update((byte)0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Failed to fingerprint Vulkan compute shader", failure);
        }
    }

    /** Startup only: compile optional kernels before dispatch deadlines or chunk futures exist. */
    void prepareWorldgenPipelines(boolean noise, boolean aquifer) {
        this.lifecycle.writeLock().lock();
        try {
            synchronized (this.admissionLock) {
                if (!this.available() || this.admittedJobs.get() != 0) {
                    throw new IllegalStateException("Worldgen pipelines must be prepared before admitting GPU jobs");
                }
                if (noise) this.pipelineFor(VanillaNoiseBatch.WORKLOAD);
                if (aquifer) this.pipelineFor(VanillaAquiferBatch.WORKLOAD);
            }
        } finally {
            this.lifecycle.writeLock().unlock();
        }
    }

    /** Separate pipelines keep noise register/loop costs out of distance and interpolation shaders. */
    private long pipelineFor(int workload) {
        if (workload >= 1 && workload <= 4) return this.pipeline;
        Long existing = this.worldgenPipelines.get(workload);
        if (existing != null) return existing;
        String resource = switch (workload) {
            case 5 -> VulkanShaderSources.NOISE;
            case 6 -> VulkanShaderSources.AQUIFER;
            default -> throw new IllegalArgumentException("Unknown compute workload: " + workload);
        };
        ByteBuffer spirv = compileShader(resource);
        long module = VK_NULL_HANDLE;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            module = createShaderModule(this.device, spirv, stack);
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                .stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer info = VkComputePipelineCreateInfo.calloc(1, stack);
            info.get(0).sType(VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO).stage(stage).layout(this.pipelineLayout);
            LongBuffer handle = stack.callocLong(1);
            int status = vkCreateComputePipelines(this.device, this.pipelineCache, info, null, handle);
            if (status != VK_SUCCESS) {
                if (handle.get(0) != VK_NULL_HANDLE) vkDestroyPipeline(this.device, handle.get(0), null);
                checkVk(status, "create exact worldgen pipeline");
            }
            long created = handle.get(0);
            try {
                this.worldgenPipelines.put(workload, created);
            } catch (RuntimeException | Error failedRegistration) {
                vkDestroyPipeline(this.device, created, null);
                throw failedRegistration;
            }
            return created;
        } finally {
            if (module != VK_NULL_HANDLE) vkDestroyShaderModule(this.device, module, null);
            org.lwjgl.system.MemoryUtil.memFree(spirv);
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
            throw new VulkanResultException(result, "Failed to " + action + " (vk result " + result + ")");
        }
    }

    private record PhysicalDeviceSelection(VkPhysicalDevice device, int queueFamilyIndex, int timestampValidBits, String deviceName) {
    }

    public record DeviceMetrics(long submittedJobs, long completedJobs, long rejectedJobs, long timedOutJobs,
                                long failedJobs, long batchesSubmitted, long inputBytes, long outputBytes,
                                long submitCpuNanos, long hostWriteNanos, long readbackNanos, long queuedNanos,
                                long gpuSamples, long gpuNanos, long lastGpuNanos, long fallbackFenceNanos,
                                long allocations, long residentBytes,
                                int queueDepth, int inFlightBatches, boolean available) {}

    private static final class ComputeJob {
        private final long ticket;
        private volatile int[] input;
        private final int outputWords;
        private final int invocations;
        private final long admittedBytes;
        private final long enqueuedAtNanos;
        private final CompletableFuture<int[]> result = new CompletableFuture<>();
        private final java.util.concurrent.atomic.AtomicBoolean released = new java.util.concurrent.atomic.AtomicBoolean();

        private ComputeJob(long ticket, int[] input, int outputWords, int invocations, long admittedBytes, long enqueuedAtNanos) {
            this.ticket = ticket;
            this.input = input;
            this.outputWords = outputWords;
            this.invocations = invocations;
            this.admittedBytes = admittedBytes;
            this.enqueuedAtNanos = enqueuedAtNanos;
        }

        private long ticket() { return this.ticket; }
        private int[] input() { return this.input; }
        private void releaseInput() { this.input = null; }
        private int outputWords() { return this.outputWords; }
        private int invocations() { return this.invocations; }
        private long admittedBytes() { return this.admittedBytes; }
        private long enqueuedAtNanos() { return this.enqueuedAtNanos; }
        private CompletableFuture<int[]> result() { return this.result; }
        private java.util.concurrent.atomic.AtomicBoolean released() { return this.released; }
    }

    private static final class SubmittedJob {
        private final ComputeJob job;
        private final ExecutionContext context;
        private int[] output;

        private SubmittedJob(ComputeJob job, ExecutionContext context) {
            this.job = job;
            this.context = context;
        }

        private ComputeJob job() { return this.job; }
        private ExecutionContext context() { return this.context; }
        private int[] output() { return this.output; }
        private void setOutput(int[] output) { this.output = output; }
    }
    private static final class SubmittedBatch {
        private final List<SubmittedJob> jobs;
        private final VulkanBatchLifecycle lifecycle = new VulkanBatchLifecycle();
        private long submittedAtNanos;
        private long deadlineNanos;

        private SubmittedBatch(List<SubmittedJob> jobs) {
            this.jobs = jobs;
        }

        private List<SubmittedJob> jobs() { return this.jobs; }
        private long submittedAtNanos() { return this.submittedAtNanos; }
        private long deadlineNanos() { return this.deadlineNanos; }
        private VulkanBatchLifecycle lifecycle() { return this.lifecycle; }
        private void markSubmitted(long submittedAtNanos, long timeoutNanos) {
            this.submittedAtNanos = submittedAtNanos;
            this.deadlineNanos = submittedAtNanos + timeoutNanos;
        }
    }

    private static final class BufferBudgetExceededException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static final class UnsupportedDeviceLocalMemoryException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static final class VulkanResultException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private final int result;

        private VulkanResultException(int result, String message) {
            super(message);
            this.result = result;
        }

        private int result() { return this.result; }
    }

    private static final class ExecutionContext {
        private final VkDevice device;
        private final long commandPool;
        private final VkCommandBuffer commandBuffer;
        private final long descriptorPool;
        private final long descriptorSet;
        private final long fence;
        private final long queryPool;
        private BufferAllocation inputBuffer;
        private BufferAllocation outputBuffer;
        private BufferAllocation inputStaging;
        private BufferAllocation outputStaging;
        private boolean deviceLocal;

        private ExecutionContext(
            final VkDevice device,
            final long commandPool,
            final VkCommandBuffer commandBuffer,
            final long descriptorPool,
            final long descriptorSet,
            final long fence,
            final long queryPool
        ) {
            this.device = device;
            this.commandPool = commandPool;
            this.commandBuffer = commandBuffer;
            this.descriptorPool = descriptorPool;
            this.descriptorSet = descriptorSet;
            this.fence = fence;
            this.queryPool = queryPool;
        }

        private VkCommandBuffer commandBuffer() {
            return this.commandBuffer;
        }

        private long commandPool() {
            return this.commandPool;
        }

        private long descriptorSet() {
            return this.descriptorSet;
        }

        private long fence() {
            return this.fence;
        }

        private long queryPool() {
            return this.queryPool;
        }

        private BufferAllocation inputBuffer() {
            return this.inputBuffer;
        }

        private void setInputBuffer(final BufferAllocation inputBuffer) {
            this.inputBuffer = inputBuffer;
        }

        private BufferAllocation outputBuffer() {
            return this.outputBuffer;
        }

        private void setOutputBuffer(final BufferAllocation outputBuffer) {
            this.outputBuffer = outputBuffer;
        }

        private BufferAllocation inputStaging() { return this.inputStaging; }
        private void setInputStaging(BufferAllocation inputStaging) { this.inputStaging = inputStaging; }
        private BufferAllocation outputStaging() { return this.outputStaging; }
        private void setOutputStaging(BufferAllocation outputStaging) { this.outputStaging = outputStaging; }
        private boolean deviceLocal() { return this.deviceLocal; }
        private void setDeviceLocal(boolean deviceLocal) { this.deviceLocal = deviceLocal; }

        private void close() {
            if (this.inputBuffer != null) {
                destroyBufferAllocation(this.device, this.inputBuffer);
                this.inputBuffer = null;
            }
            if (this.outputBuffer != null) {
                destroyBufferAllocation(this.device, this.outputBuffer);
                this.outputBuffer = null;
            }
            if (this.inputStaging != null) {
                destroyBufferAllocation(this.device, this.inputStaging);
                this.inputStaging = null;
            }
            if (this.outputStaging != null) {
                destroyBufferAllocation(this.device, this.outputStaging);
                this.outputStaging = null;
            }
            vkDestroyFence(this.device, this.fence, null);
            if (this.queryPool != VK_NULL_HANDLE) vkDestroyQueryPool(this.device, this.queryPool, null);
            vkDestroyDescriptorPool(this.device, this.descriptorPool, null);
            vkDestroyCommandPool(this.device, this.commandPool, null);
        }
    }

    private record BufferAllocation(long buffer, long memory, long size, long allocationSize,
                                          long mappedAddress, ByteBuffer mappedBytes, boolean hostCoherent) {
        private IntBuffer intView() {
            if (this.mappedBytes == null) throw new IllegalStateException("Vulkan device-local buffer is not host mapped");
            return this.mappedBytes.duplicate().order(ByteOrder.nativeOrder()).asIntBuffer();
        }
    }

    private static void destroyBufferAllocation(final VkDevice device, final BufferAllocation allocation) {
        if (allocation.mappedBytes() != null) vkUnmapMemory(device, allocation.memory());
        vkDestroyBuffer(device, allocation.buffer(), null);
        vkFreeMemory(device, allocation.memory(), null);
    }
}
