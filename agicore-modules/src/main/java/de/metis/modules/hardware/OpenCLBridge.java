package de.metis.modules.hardware;

import java.lang.foreign.MemorySegment;
import java.util.Map;
import java.util.logging.Logger;

/**
 * High-level bridge: OpenCL GPU discovery, allocation, and tensor operations.
 * <p>
 * Uses {@link OpenCLNative} for raw OpenCL calls and {@link GpuTensor}
 * for zero-copy tensor management. Provides a clean Java API for GPU computing.
 * <p>
 * Lifecycle: init() → allocate/execute → close()
 */
public class OpenCLBridge implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(OpenCLBridge.class.getName());

    private final OpenCLNative cl;
    private MemorySegment platform;
    private MemorySegment device;
    private MemorySegment context;
    private MemorySegment queue;

    private String deviceName = "unknown";
    private long globalMemBytes = 0;
    private int computeUnits = 0;
    private boolean initialized = false;

    public OpenCLBridge() {
        this.cl = OpenCLNative.load();
    }

    /**
     * Initialize OpenCL: discover GPU, create context and command queue.
     * @return true if GPU found and initialized
     */
    public boolean init() {
        try {
            platform = cl.getPlatform();
            if (platform == null) {
                LOG.warning("No OpenCL platform found");
                return false;
            }

            String platformName = cl.getPlatformInfoString(platform,
                    OpenCLNative.CL_PLATFORM_NAME);
            LOG.info("OpenCL Platform: " + platformName);

            device = cl.getDevice(platform);
            if (device == null) {
                LOG.warning("No GPU device found — falling back to PoCL CPU");
                return false;
            }

            deviceName = cl.getDeviceInfoString(device, OpenCLNative.CL_DEVICE_NAME);
            globalMemBytes = cl.getDeviceInfoLong(device, OpenCLNative.CL_DEVICE_GLOBAL_MEM_SIZE);
            computeUnits = (int) cl.getDeviceInfoLong(device,
                    OpenCLNative.CL_DEVICE_MAX_COMPUTE_UNITS);

            LOG.info("GPU Device: " + deviceName + " | "
                    + (globalMemBytes / (1024 * 1024 * 1024)) + " GB VRAM | "
                    + computeUnits + " CUs");

            context = cl.createContext(device);
            queue = cl.createCommandQueue(context, device);
            initialized = true;

            return true;
        } catch (Throwable e) {
            LOG.severe("OpenCL init failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Allocate a GPU tensor with zero-copy host access.
     */
    public GpuTensor allocate(long elementCount) throws Throwable {
        if (!initialized) throw new IllegalStateException("OpenCLBridge not initialized");
        return new GpuTensor(cl, context, queue, elementCount);
    }

    /**
     * Run a simple vector addition on the GPU as a benchmark.
     * Returns GFLOPS (billions of float ops per second).
     */
    public BenchmarkResult benchmark() {
        if (!initialized) return new BenchmarkResult("not initialized", 0, 0);

        long n = 1_000_000;
        try (var a = allocate(n); var b = allocate(n); var c = allocate(n)) {
            // Fill with test data
            float[] testData = new float[(int) n];
            for (int i = 0; i < n; i++) {
                testData[i] = (float) Math.random();
            }
            a.write(testData);
            b.write(testData);

            // Time a simple element-wise operation (on mapped host memory)
            long start = System.nanoTime();
            for (int i = 0; i < n; i++) {
                c.put(i, a.get(i) + b.get(i));
            }
            long elapsedNs = System.nanoTime() - start;

            // Sync GPU
            c.sync();
            cl.finish(queue);

            double gflops = (n * 2.0) / (elapsedNs / 1.0e9) / 1.0e9;
            long bandwidth = (n * 4 * 3) * 1_000_000_000L / elapsedNs; // bytes/sec (3 buffers)

            return new BenchmarkResult(deviceName, gflops, bandwidth);

        } catch (Throwable e) {
            LOG.warning("GPU benchmark failed: " + e.getMessage());
            return new BenchmarkResult("error: " + e.getMessage(), 0, 0);
        }
    }

    /**
     * Get GPU status as a map (for /api/status).
     */
    public Map<String, Object> status() {
        return Map.of(
                "device", deviceName,
                "vramGb", globalMemBytes / (1024.0 * 1024.0 * 1024.0),
                "computeUnits", computeUnits,
                "initialized", initialized
        );
    }

    public boolean isInitialized() { return initialized; }
    public String deviceName() { return deviceName; }
    public long globalMemBytes() { return globalMemBytes; }
    public int computeUnits() { return computeUnits; }

    @Override
    public void close() {
        try {
            if (queue != null) cl.releaseCommandQueue(queue);
            if (context != null) cl.releaseContext(context);
            LOG.info("OpenCLBridge closed");
        } catch (Throwable e) {
            LOG.warning("OpenCLBridge close error: " + e.getMessage());
        }
    }

    public record BenchmarkResult(String device, double gflops, long bandwidthBytesPerSec) {
        @Override
        public String toString() {
            return String.format("%s: %.1f GFLOPS, %d MB/s",
                    device, gflops, bandwidthBytesPerSec / (1024 * 1024));
        }
    }
}
