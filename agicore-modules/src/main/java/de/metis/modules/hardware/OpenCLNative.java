package de.metis.modules.hardware;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.util.logging.Logger;

/**
 * Raw OpenCL 2.1 function bindings via Java Panama FFM (Foreign Function & Memory API).
 * <p>
 * Provides direct, zero-copy access to the AMD ROCm OpenCL runtime without JNI.
 * Maps critical OpenCL functions needed for GPU-accelerated tensor operations.
 * <p>
 * Usage:
 * <pre>
 *   OpenCLNative cl = OpenCLNative.load();
 *   var platform = cl.getPlatform();
 *   var device = cl.getDevice(platform);
 *   var ctx = cl.createContext(device);
 *   var queue = cl.createCommandQueue(ctx, device);
 *   // ... allocate buffers, execute kernels, read results ...
 * </pre>
 */
public class OpenCLNative {

    private static final Logger LOG = Logger.getLogger(OpenCLNative.class.getName());

    // OpenCL shared library (full path for dlopen)
    private static final String LIB_NAME = "/opt/rocm/lib/libOpenCL.so";

    // Panama arenas and linker
    private final Arena arena;
    private final Linker linker;
    private final SymbolLookup lib;

    // ── OpenCL type constants ──────────────────────────────────
    public static final int CL_SUCCESS = 0;
    public static final int CL_DEVICE_NOT_FOUND = -1;
    public static final int CL_INVALID_VALUE = -30;
    public static final int CL_INVALID_PLATFORM = -32;
    public static final int CL_INVALID_MEM_OBJECT = -38;
    public static final int CL_MEM_READ_WRITE = 1;
    public static final int CL_MEM_WRITE_ONLY = 2;
    public static final int CL_MEM_READ_ONLY = 4;
    public static final int CL_MEM_COPY_HOST_PTR = 32;
    public static final int CL_MEM_ALLOC_HOST_PTR = 64;
    public static final int CL_QUEUED = 3;
    public static final int CL_RUNNING = 4;
    public static final int CL_COMPLETE = 0;

    // Device info
    public static final int CL_DEVICE_TYPE_GPU = 4;
    public static final int CL_DEVICE_TYPE_CPU = 2;
    public static final int CL_DEVICE_NAME = 0x102B;
    public static final int CL_DEVICE_GLOBAL_MEM_SIZE = 0x101F;
    public static final int CL_DEVICE_MAX_COMPUTE_UNITS = 0x1002;
    public static final int CL_DEVICE_MAX_CLOCK_FREQUENCY = 0x100C;

    // String platform/device info
    public static final int CL_PLATFORM_NAME = 0x0902;
    public static final int CL_PLATFORM_VENDOR = 0x0903;
    public static final int CL_PLATFORM_VERSION = 0x0901;

    // ── Method handles ─────────────────────────────────────────
    private final MethodHandle clGetPlatformIDs;
    private final MethodHandle clGetDeviceIDs;
    private final MethodHandle clCreateContext;
    private final MethodHandle clCreateCommandQueue;
    private final MethodHandle clCreateBuffer;
    private final MethodHandle clEnqueueWriteBuffer;
    private final MethodHandle clEnqueueReadBuffer;
    private final MethodHandle clCreateProgramWithSource;
    private final MethodHandle clBuildProgram;
    private final MethodHandle clCreateKernel;
    private final MethodHandle clSetKernelArg;
    private final MethodHandle clEnqueueNDRangeKernel;
    private final MethodHandle clEnqueueMapBuffer;
    private final MethodHandle clEnqueueUnmapMemObject;
    private final MethodHandle clReleaseMemObject;
    private final MethodHandle clReleaseKernel;
    private final MethodHandle clReleaseProgram;
    private final MethodHandle clReleaseCommandQueue;
    private final MethodHandle clReleaseContext;
    private final MethodHandle clFinish;
    private final MethodHandle clGetPlatformInfo;
    private final MethodHandle clGetDeviceInfo;

    private OpenCLNative() {
        this.arena = Arena.ofShared();
        this.linker = Linker.nativeLinker();
        this.lib = SymbolLookup.libraryLookup(LIB_NAME, arena);

        // Map all OpenCL functions
        this.clGetPlatformIDs = bind("clGetPlatformIDs",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.clGetDeviceIDs = bind("clGetDeviceIDs",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.clCreateContext = bind("clCreateContext",
                FunctionDescriptor.of(ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.clCreateCommandQueue = bind("clCreateCommandQueue",
                FunctionDescriptor.of(ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS));
        this.clCreateBuffer = bind("clCreateBuffer",
                FunctionDescriptor.of(ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.clEnqueueWriteBuffer = bind("clEnqueueWriteBuffer",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.clEnqueueReadBuffer = bind("clEnqueueReadBuffer",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.clCreateProgramWithSource = bind("clCreateProgramWithSource",
                FunctionDescriptor.of(ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.clBuildProgram = bind("clBuildProgram",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.clCreateKernel = bind("clCreateKernel",
                FunctionDescriptor.of(ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.clSetKernelArg = bind("clSetKernelArg",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS));
        this.clEnqueueNDRangeKernel = bind("clEnqueueNDRangeKernel",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.clEnqueueMapBuffer = bind("clEnqueueMapBuffer",
                FunctionDescriptor.of(ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS));
        this.clEnqueueUnmapMemObject = bind("clEnqueueUnmapMemObject",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.clReleaseMemObject = bind("clReleaseMemObject",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        this.clReleaseKernel = bind("clReleaseKernel",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        this.clReleaseProgram = bind("clReleaseProgram",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        this.clReleaseCommandQueue = bind("clReleaseCommandQueue",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        this.clReleaseContext = bind("clReleaseContext",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        this.clFinish = bind("clFinish",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        this.clGetPlatformInfo = bind("clGetPlatformInfo",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.clGetDeviceInfo = bind("clGetDeviceInfo",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS));

        LOG.info("OpenCLNative loaded: " + LIB_NAME + " (Panama FFM)");
    }

    // ── Factory ─────────────────────────────────────────────────

    private static volatile OpenCLNative INSTANCE;

    public static OpenCLNative load() {
        if (INSTANCE == null) {
            synchronized (OpenCLNative.class) {
                if (INSTANCE == null) {
                    INSTANCE = new OpenCLNative();
                }
            }
        }
        return INSTANCE;
    }

    // ── Method binding helper ──────────────────────────────────

    private MethodHandle bind(String name, FunctionDescriptor desc) {
        return linker.downcallHandle(
                lib.find(name).orElseThrow(() ->
                        new RuntimeException("OpenCL function not found: " + name)),
                desc);
    }

    // ── OpenCL API wrappers ────────────────────────────────────

    /**
     * Get first available GPU platform.
     * Returns null if no GPU platform found.
     */
    public MemorySegment getPlatform() throws Throwable {
        // Get number of platforms
        var countSeg = arena.allocate(ValueLayout.JAVA_INT);
        MemorySegment nullPtr = MemorySegment.NULL;
        int err = (int) clGetPlatformIDs.invoke(0, nullPtr, countSeg);
        if (err != CL_SUCCESS) return null;

        int count = countSeg.get(ValueLayout.JAVA_INT, 0);
        if (count == 0) return null;

        // Allocate platform array
        var platforms = arena.allocate(ValueLayout.ADDRESS, count);
        err = (int) clGetPlatformIDs.invoke(count, platforms, MemorySegment.NULL);
        if (err != CL_SUCCESS) return null;

        // Return first platform (usually AMD ROCm)
        return platforms.get(ValueLayout.ADDRESS, 0);
    }

    /**
     * Get first GPU device on the given platform.
     */
    public MemorySegment getDevice(MemorySegment platform) throws Throwable {
        var numDevices = arena.allocate(ValueLayout.JAVA_INT);
        int err = (int) clGetDeviceIDs.invoke(
                platform, CL_DEVICE_TYPE_GPU, 0, MemorySegment.NULL, numDevices);
        if (err != CL_SUCCESS) return null;

        int count = numDevices.get(ValueLayout.JAVA_INT, 0);
        if (count == 0) return null;

        var devices = arena.allocate(ValueLayout.ADDRESS, count);
        err = (int) clGetDeviceIDs.invoke(
                platform, CL_DEVICE_TYPE_GPU, count, devices, MemorySegment.NULL);
        if (err != CL_SUCCESS) return null;

        return devices.get(ValueLayout.ADDRESS, 0);
    }

    /**
     * Create OpenCL context for a single device.
     */
    public MemorySegment createContext(MemorySegment device) throws Throwable {
        // Allocate pointer to device array
        var devicePtr = arena.allocate(ValueLayout.ADDRESS);
        devicePtr.set(ValueLayout.ADDRESS, 0, device);
        return (MemorySegment) clCreateContext.invoke(
                MemorySegment.NULL, 1, devicePtr,
                MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL);
    }

    /**
     * Create command queue.
     */
    public MemorySegment createCommandQueue(MemorySegment ctx, MemorySegment device)
            throws Throwable {
        return (MemorySegment) clCreateCommandQueue.invoke(
                ctx, device, 0, MemorySegment.NULL);
    }

    /**
     * Create a GPU buffer. Returns the cl_mem handle.
     * @param flags CL_MEM_* flags (e.g., CL_MEM_READ_WRITE)
     * @param sizeBytes buffer size in bytes
     * @param hostPtr optional host pointer for CL_MEM_COPY_HOST_PTR, or NULL
     */
    public MemorySegment createBuffer(MemorySegment ctx, long flags, long sizeBytes,
                                       MemorySegment hostPtr) throws Throwable {
        return (MemorySegment) clCreateBuffer.invoke(
                ctx, flags, sizeBytes, hostPtr, MemorySegment.NULL);
    }

    /**
     * Write data to a GPU buffer.
     */
    public int writeBuffer(MemorySegment queue, MemorySegment buffer, boolean blocking,
                            long offset, long size, MemorySegment hostData) throws Throwable {
        return (int) clEnqueueWriteBuffer.invoke(
                queue, buffer, blocking ? 1 : 0, offset, size, hostData,
                0, MemorySegment.NULL, MemorySegment.NULL);
    }

    /**
     * Read data from a GPU buffer.
     */
    public int readBuffer(MemorySegment queue, MemorySegment buffer, boolean blocking,
                           long offset, long size, MemorySegment hostData) throws Throwable {
        return (int) clEnqueueReadBuffer.invoke(
                queue, buffer, blocking ? 1 : 0, offset, size, hostData,
                0, MemorySegment.NULL, MemorySegment.NULL);
    }

    /**
     * Map a GPU buffer to host-accessible memory (zero-copy).
     * Returns a MemorySegment pointing to the mapped region.
     */
    public MemorySegment mapBuffer(MemorySegment queue, MemorySegment buffer,
                                    boolean blocking, long flags, long offset,
                                    long size) throws Throwable {
        return (MemorySegment) clEnqueueMapBuffer.invoke(
                queue, buffer, blocking ? 1 : 0, flags, offset, size,
                0, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL);
    }

    /** Unmap a previously mapped buffer. */
    public int unmapBuffer(MemorySegment queue, MemorySegment buffer,
                            MemorySegment mappedPtr) throws Throwable {
        return (int) clEnqueueUnmapMemObject.invoke(
                queue, buffer, mappedPtr, 0, MemorySegment.NULL, MemorySegment.NULL);
    }

    /** Wait for all commands in the queue to finish. */
    public int finish(MemorySegment queue) throws Throwable {
        return (int) clFinish.invoke(queue);
    }

    // ── String info queries ────────────────────────────────────

    /**
     * Get platform info string (name, vendor, version).
     */
    public String getPlatformInfoString(MemorySegment platform, int paramName) throws Throwable {
        var size = arena.allocate(ValueLayout.JAVA_LONG);
        int err = (int) clGetPlatformInfo.invoke(
                platform, paramName, 0, MemorySegment.NULL, size);
        if (err != CL_SUCCESS) return "unknown";

        long len = size.get(ValueLayout.JAVA_LONG, 0);
        var buf = arena.allocate(len);
        clGetPlatformInfo.invoke(platform, paramName, len, buf, MemorySegment.NULL);
        return buf.getString(0);
    }

    /**
     * Get device info string (name, etc.).
     */
    public String getDeviceInfoString(MemorySegment device, int paramName) throws Throwable {
        var size = arena.allocate(ValueLayout.JAVA_LONG);
        int err = (int) clGetDeviceInfo.invoke(
                device, paramName, 0, MemorySegment.NULL, size);
        if (err != CL_SUCCESS) return "unknown";

        long len = size.get(ValueLayout.JAVA_LONG, 0);
        var buf = arena.allocate(len);
        clGetDeviceInfo.invoke(device, paramName, len, buf, MemorySegment.NULL);
        return buf.getString(0);
    }

    /**
     * Get device info long (global memory, compute units, etc.).
     */
    public long getDeviceInfoLong(MemorySegment device, int paramName) throws Throwable {
        var buf = arena.allocate(ValueLayout.JAVA_LONG);
        int err = (int) clGetDeviceInfo.invoke(
                device, paramName, ValueLayout.JAVA_LONG.byteSize(), buf, MemorySegment.NULL);
        if (err != CL_SUCCESS) return -1;
        return buf.get(ValueLayout.JAVA_LONG, 0);
    }

    /** Release OpenCL resources. */
    public int releaseMemObject(MemorySegment obj) throws Throwable {
        return (int) clReleaseMemObject.invoke(obj);
    }
    public int releaseKernel(MemorySegment k) throws Throwable {
        return (int) clReleaseKernel.invoke(k);
    }
    public int releaseContext(MemorySegment ctx) throws Throwable {
        return (int) clReleaseContext.invoke(ctx);
    }
    public int releaseCommandQueue(MemorySegment q) throws Throwable {
        return (int) clReleaseCommandQueue.invoke(q);
    }

    /** Helper: allocate a pointer slot in the arena. */
}
