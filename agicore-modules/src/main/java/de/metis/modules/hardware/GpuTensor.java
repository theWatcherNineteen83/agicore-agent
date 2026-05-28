package de.metis.modules.hardware;

import java.lang.foreign.*;
import java.util.logging.Logger;

/**
 * Zero-copy GPU tensor abstraction via Panama FFM + OpenCL.
 * <p>
 * Wraps an OpenCL buffer and its mapped host memory segment.
 * Supports float tensors (1D, 2D) with direct read/write from Java
 * without JNI or data copying overhead.
 * <p>
 * Memory model:
 * <pre>
 *   GPU VRAM ←cl_mem→ mapped MemorySegment (heap-accessible)
 *   Java reads/writes directly to mapped segment
 *   clEnqueueUnmapMemObject syncs back to GPU
 * </pre>
 */
public class GpuTensor implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(GpuTensor.class.getName());

    private final OpenCLNative cl;
    private final MemorySegment queue;
    private final MemorySegment buffer;     // cl_mem
    private MemorySegment hostMem;    // mapped host pointer (reassigned on sync)
    private final long sizeBytes;
    private final long elementCount;
    private boolean mapped;

    /**
     * Create a GPU tensor with mapped host access.
     *
     * @param cl     OpenCL native bindings
     * @param ctx    OpenCL context
     * @param queue  Command queue
     * @param count  Number of float elements
     */
    public GpuTensor(OpenCLNative cl, MemorySegment ctx, MemorySegment queue, long count)
            throws Throwable {
        this.cl = cl;
        this.queue = queue;
        this.elementCount = count;
        this.sizeBytes = count * 4; // float = 4 bytes

        // Allocate with CL_MEM_ALLOC_HOST_PTR for zero-copy mapping
        this.buffer = cl.createBuffer(ctx,
                OpenCLNative.CL_MEM_READ_WRITE | OpenCLNative.CL_MEM_ALLOC_HOST_PTR,
                sizeBytes, MemorySegment.NULL);

        // Map buffer to host memory immediately
        var rawMem = cl.mapBuffer(queue, buffer, true,
                OpenCLNative.CL_MEM_READ_WRITE, 0, sizeBytes);
        // Panama FFM: reinterpret the void* with known size
        this.hostMem = rawMem.reinterpret(sizeBytes);
        this.mapped = true;

        LOG.fine(() -> "GpuTensor: " + elementCount + " floats (" + sizeBytes + " bytes)");
    }

    /**
     * Write a float value at the given index (direct to mapped GPU memory).
     */
    public void put(long index, float value) {
        hostMem.set(ValueLayout.JAVA_FLOAT, index * 4, value);
    }

    /**
     * Read a float value at the given index.
     */
    public float get(long index) {
        return hostMem.get(ValueLayout.JAVA_FLOAT, index * 4);
    }

    /**
     * Write a Java float array to the GPU tensor.
     */
    public void write(float[] data) {
        for (int i = 0; i < Math.min(data.length, elementCount); i++) {
            put(i, data[i]);
        }
    }

    /**
     * Read the GPU tensor into a Java float array.
     */
    public float[] read() {
        float[] result = new float[(int) elementCount];
        for (int i = 0; i < elementCount; i++) {
            result[i] = get(i);
        }
        return result;
    }

    /**
     * Synchronize: unmap → remap (flushes writes to GPU, reflects GPU changes).
     */
    public void sync() throws Throwable {
        if (mapped) {
            cl.unmapBuffer(queue, buffer, hostMem);
            mapped = false;
        }
        // Re-map with correct size
        var rawMem = cl.mapBuffer(queue, buffer, true,
                OpenCLNative.CL_MEM_READ_WRITE, 0, sizeBytes);
        this.hostMem = rawMem.reinterpret(sizeBytes);
        this.mapped = true;
        cl.finish(queue);
    }

    public long elementCount() { return elementCount; }
    public long sizeBytes() { return sizeBytes; }
    public MemorySegment buffer() { return buffer; }

    @Override
    public void close() {
        try {
            if (mapped) {
                cl.unmapBuffer(queue, buffer, hostMem);
                mapped = false;
            }
            cl.releaseMemObject(buffer);
        } catch (Throwable e) {
            LOG.warning("GpuTensor close error: " + e.getMessage());
        }
    }
}
