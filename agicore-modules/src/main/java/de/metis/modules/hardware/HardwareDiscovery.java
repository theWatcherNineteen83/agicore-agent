package de.metis.modules.hardware;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Discovers and profiles the host hardware Metis runs on.
 * <p>
 * Reads from /proc, /sys, and system commands to build a complete
 * picture of CPU, GPU, RAM, and storage capabilities.
 */
public class HardwareDiscovery {

    /**
     * Full hardware profile of the current host.
     */
    public record HardwareProfile(
            String hostname,
            String osName,
            String osVersion,
            String kernelVersion,
            CpuInfo cpu,
            long totalRamMb,
            long availableRamMb,
            List<GpuInfo> gpus,
            List<DiskInfo> disks
    ) {
        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("Host: %s | OS: %s %s | Kernel: %s\n",
                    hostname, osName, osVersion, kernelVersion));
            sb.append(String.format("CPU: %s (%d cores, %d threads) %s\n",
                    cpu.model(), cpu.physicalCores(), cpu.logicalThreads(), cpu.arch()));
            if (cpu.hasSIMD()) {
                sb.append("  SIMD: " + String.join(", ", cpu.simdFeatures()) + "\n");
            }
            sb.append(String.format("RAM: %d MB total, %d MB available\n", totalRamMb, availableRamMb));
            for (var gpu : gpus) {
                sb.append(String.format("GPU: %s — %d MB VRAM (%s)\n",
                        gpu.model(), gpu.vramMb(), gpu.driver()));
            }
            for (var disk : disks) {
                sb.append(String.format("Disk: %s — %d GB total, %d GB free (%s)\n",
                        disk.mountPoint(), disk.totalGb(), disk.freeGb(), disk.fsType()));
            }
            return sb.toString().strip();
        }

        /** Check if this host can run large LLMs (>=16 GB VRAM). */
        public boolean canRunLargeModels() {
            return gpus.stream().anyMatch(g -> g.vramMb() >= 16000);
        }

        /** Check if AMD GPU with ROCm is available. */
        public boolean hasROCm() {
            return gpus.stream().anyMatch(g -> g.model().toLowerCase().contains("radeon")
                    || g.model().toLowerCase().contains("amd"));
        }
    }

    public record CpuInfo(
            String model,
            int physicalCores,
            int logicalThreads,
            String arch,
            double maxFreqGhz,
            List<String> simdFeatures
    ) {
        public boolean hasSIMD() { return !simdFeatures.isEmpty(); }
        public boolean hasAVX2() { return simdFeatures.contains("avx2"); }
        public boolean hasAVX512() { return simdFeatures.contains("avx512"); }
    }

    public record GpuInfo(String model, long vramMb, String driver) {}

    public record DiskInfo(String mountPoint, String device, long totalGb, long freeGb, String fsType) {}

    /**
     * Discover the complete hardware profile for the current machine.
     */
    public static HardwareProfile discover() {
        String hostname = readSysCommand("hostname");
        String osName = System.getProperty("os.name");
        String osVersion = System.getProperty("os.version");
        String kernelVersion = readSysCommand("uname -r");

        CpuInfo cpu = discoverCpu();
        long totalRam = discoverTotalRam();
        long availableRam = discoverAvailableRam();
        List<GpuInfo> gpus = discoverGpus();
        List<DiskInfo> disks = discoverDisks();

        return new HardwareProfile(
                hostname, osName, osVersion, kernelVersion,
                cpu, totalRam, availableRam, gpus, disks);
    }

    // ── CPU Discovery ─────────────────────────────────────────────

    private static CpuInfo discoverCpu() {
        String model = "unknown";
        int physicalCores = 0;
        int logicalThreads = Runtime.getRuntime().availableProcessors();
        String arch = System.getProperty("os.arch");
        double maxFreq = 0;
        List<String> simd = new ArrayList<>();

        try {
            // Read CPU info from /proc/cpuinfo
            String cpuinfo = Files.readString(Path.of("/proc/cpuinfo"));
            Set<String> uniquePhysicalIds = new HashSet<>();
            Set<String> uniqueCoreIds = new HashSet<>();

            for (String line : cpuinfo.split("\n")) {
                line = line.strip();
                if (line.startsWith("model name")) {
                    model = line.substring(line.indexOf(':') + 1).strip();
                }
                if (line.startsWith("physical id")) {
                    uniquePhysicalIds.add(line.substring(line.indexOf(':') + 1).strip());
                }
                if (line.startsWith("core id")) {
                    uniqueCoreIds.add(line.substring(line.indexOf(':') + 1).strip());
                }
                if (line.startsWith("cpu MHz")) {
                    try {
                        double mhz = Double.parseDouble(line.substring(line.indexOf(':') + 1).strip());
                        maxFreq = Math.max(maxFreq, mhz / 1000.0);
                    } catch (NumberFormatException ignored) {}
                }
                if (line.startsWith("flags")) {
                    String flags = line.substring(line.indexOf(':') + 1).strip().toLowerCase();
                    for (String flag : flags.split(" ")) {
                        String normalized = flag.strip();
                        if (normalized.equals("avx") || normalized.equals("avx2") || normalized.equals("avx512f")
                                || normalized.equals("sse4_1") || normalized.equals("sse4_2")) {
                            String simdName = normalized.replace("_", ".");
                            if (!simd.contains(simdName)) simd.add(simdName);
                        }
                    }
                }
            }

            physicalCores = uniqueCoreIds.isEmpty() ? logicalThreads : uniqueCoreIds.size();

        } catch (Exception e) {
            model = arch + " (fallback)";
        }

        return new CpuInfo(model, physicalCores, logicalThreads, arch, maxFreq, simd);
    }

    // ── RAM Discovery ─────────────────────────────────────────────

    private static long discoverTotalRam() {
        try {
            String meminfo = Files.readString(Path.of("/proc/meminfo"));
            for (String line : meminfo.split("\n")) {
                if (line.startsWith("MemTotal:")) {
                    String kb = line.replaceAll("[^0-9]", "");
                    return Long.parseLong(kb) / 1024; // KB → MB
                }
            }
        } catch (Exception ignored) {}
        return Runtime.getRuntime().maxMemory() / (1024 * 1024);
    }

    private static long discoverAvailableRam() {
        try {
            String meminfo = Files.readString(Path.of("/proc/meminfo"));
            for (String line : meminfo.split("\n")) {
                if (line.startsWith("MemAvailable:")) {
                    String kb = line.replaceAll("[^0-9]", "");
                    return Long.parseLong(kb) / 1024; // KB → MB
                }
            }
        } catch (Exception ignored) {}
        return Runtime.getRuntime().freeMemory() / (1024 * 1024);
    }

    // ── GPU Discovery ─────────────────────────────────────────────

    private static List<GpuInfo> discoverGpus() {
        List<GpuInfo> gpus = new ArrayList<>();

        // Try ROCm (AMD)
        String rocmInfo = readSysCommand("rocm-smi --showproductname --csv 2>/dev/null");
        if (rocmInfo != null && !rocmInfo.isBlank() && !rocmInfo.contains("command not found")) {
            for (String line : rocmInfo.split("\n")) {
                if (line.startsWith("GPU") || line.isBlank()) continue;
                String model = line.contains(",") ? line.split(",")[1].strip() : line.strip();
                // Skip generic entries
                if (model.contains("Card series") || model.isBlank()) continue;
                long vram = discoverRocmVram();
                gpus.add(new GpuInfo(model, vram, "ROCm"));
            }
        }

        // Try nvidia-smi
        String nvidiaInfo = readSysCommand("nvidia-smi --query-gpu=name,memory.total --format=csv,noheader 2>/dev/null");
        if (nvidiaInfo != null && !nvidiaInfo.isBlank() && !nvidiaInfo.contains("command not found")) {
            for (String line : nvidiaInfo.split("\n")) {
                String[] parts = line.split(",");
                String model = parts[0].strip();
                long vram = 0;
                if (parts.length > 1) {
                    try { vram = Long.parseLong(parts[1].strip().replaceAll("[^0-9]", "")); }
                    catch (NumberFormatException ignored) {}
                }
                gpus.add(new GpuInfo(model, vram, "NVIDIA"));
            }
        }

        // Fallback: check /sys/class/drm for GPU info
        if (gpus.isEmpty()) {
            try {
                var drmDir = Path.of("/sys/class/drm");
                if (Files.exists(drmDir)) {
                    try (var stream = Files.list(drmDir)) {
                        stream.filter(p -> p.getFileName().toString().startsWith("card"))
                                .forEach(card -> {
                                    try {
                                        Path deviceDir = card.resolve("device");
                                        String vendor = Files.readString(deviceDir.resolve("vendor"))
                                                .strip().replace("0x", "");
                                        // Only if it's AMD (0x1002) or NVIDIA (0x10de)
                                        if (vendor.equals("1002") || vendor.equals("10de")) {
                                            String model = detectGpuFromPci(card);
                                            gpus.add(new GpuInfo(model, -1, vendor.equals("1002") ? "AMD" : "NVIDIA"));
                                        }
                                    } catch (Exception ignored) {}
                                });
                    }
                }
            } catch (Exception ignored) {}
        }

        // Last resort: lspci
        if (gpus.isEmpty()) {
            String lspci = readSysCommand("lspci | grep -i 'vga\\|3d\\|display' 2>/dev/null");
            if (lspci != null && !lspci.isBlank()) {
                for (String line : lspci.split("\n")) {
                    String model = line.replaceAll(".*: ", "").strip();
                    gpus.add(new GpuInfo(model, -1, "unknown"));
                }
            }
        }

        return gpus;
    }

    private static long discoverRocmVram() {
        String info = readSysCommand("rocm-smi --showmeminfo vram --csv 2>/dev/null");
        if (info == null || info.isBlank()) return -1;
        for (String line : info.split("\n")) {
            if (line.startsWith("GPU")) continue;
            try {
                String[] parts = line.split(",");
                if (parts.length > 1) {
                    long raw = Long.parseLong(parts[1].strip().replaceAll("[^0-9]", ""));
                    // rocm-smi reports bytes or MB depending on version
                    return raw > 1_000_000_000 ? raw / (1024 * 1024) : raw;
                }
            } catch (NumberFormatException ignored) {}
        }
        return -1;
    }

    private static String detectGpuFromPci(Path card) {
        try {
            // Try to read from PCI vendor/device files
            Path deviceDir = card.resolve("device");
            String deviceId = Files.readString(deviceDir.resolve("device")).strip().replace("0x", "");
            return "GPU " + deviceId.substring(0, 4);
        } catch (Exception e) {
            return "Unknown GPU";
        }
    }

    // ── Disk Discovery ─────────────────────────────────────────────

    private static List<DiskInfo> discoverDisks() {
        List<DiskInfo> disks = new ArrayList<>();
        String df = readSysCommand("df -BG --type=ext4 --type=xfs --type=btrfs 2>/dev/null");
        if (df == null || df.isBlank()) {
            // Fallback
            var root = Path.of("/");
            try {
                var store = Files.getFileStore(root);
                long total = store.getTotalSpace() / (1024 * 1024 * 1024);
                long free = store.getUsableSpace() / (1024 * 1024 * 1024);
                disks.add(new DiskInfo("/", "root", total, free, store.type()));
            } catch (IOException ignored) {}
            return disks;
        }

        for (String line : df.split("\n")) {
            if (line.startsWith("Filesystem")) continue;
            String[] parts = line.split("\\s+");
            if (parts.length < 6) continue;
            try {
                long total = Long.parseLong(parts[1].replace("G", ""));
                long free = Long.parseLong(parts[3].replace("G", ""));
                disks.add(new DiskInfo(parts[5], parts[0], total, free, "ext4"));
            } catch (NumberFormatException ignored) {}
        }
        return disks;
    }

    // ── Helpers ────────────────────────────────────────────────────

    private static String readSysCommand(String cmd) {
        try {
            Process proc = new ProcessBuilder("sh", "-c", cmd)
                    .redirectErrorStream(true)
                    .start();
            String output = new String(proc.getInputStream().readAllBytes());
            proc.waitFor();
            return output.strip();
        } catch (Exception e) {
            return null;
        }
    }
}
