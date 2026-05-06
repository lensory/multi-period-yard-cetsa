package util;

import ilog.concert.IloException;
import ilog.cplex.IloCplex;
import main.Params;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class RunContext implements AutoCloseable {
    private static final long DEFAULT_RSS_CHECK_INTERVAL_MS = 1000L;
    private static final long DEFAULT_MEMORY_LOG_INTERVAL_MS = 30_000L;
    private static final long BYTES_PER_MB = 1024L * 1024L;


    public enum StopReason {
        NONE,
        TIME_LIMIT,
        MEMORY_LIMIT,
        MANUAL_STOP
    }

    private final Params params;
    private final PrintStream out;


    private final long startTimeMillis;
    private final Long deadlineMillis;
    private final Integer rssLimitMb;
    private final long checkIntervalMillis;
    private final long memoryLogIntervalMillis;
    private long nextMemoryLogMillis;


    private final AtomicBoolean finished = new AtomicBoolean(false);
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);

    private volatile StopReason stopReason = StopReason.NONE;

    private final AtomicBoolean memoryExceeded = new AtomicBoolean(false);

    private final ConcurrentHashMap<IloCplex, IloCplex.Aborter> aborters =
            new ConcurrentHashMap<>();
    private Thread monitorThread;

    public RunContext(Params params, PrintStream out) {
        this.params = params;
        this.out = out;

        this.startTimeMillis = System.currentTimeMillis();
        this.deadlineMillis = params.timeLimit == null ? null :
                this.startTimeMillis + params.timeLimit * 1000L;
        this.rssLimitMb = params.rssLimitMb;
        this.checkIntervalMillis = params.rssCheckIntervalMs == null ? DEFAULT_RSS_CHECK_INTERVAL_MS : params.rssCheckIntervalMs;
        this.memoryLogIntervalMillis = params.memoryLogIntervalMs != null ? params.memoryLogIntervalMs :
                (params.rssLimitMb == null || params.rssLimitMb <= 0 ? 0L : DEFAULT_MEMORY_LOG_INTERVAL_MS);
        this.nextMemoryLogMillis = this.startTimeMillis;

    }

    public void startMonitor() {
        if ((params.rssLimitMb == null || params.rssLimitMb <= 0)
                && deadlineMillis == null
                && memoryLogIntervalMillis <= 0) {
            return;
        }

        monitorThread = new Thread(() -> {
            while (!finished.get()) {
                long now = System.currentTimeMillis();
                if (deadlineMillis != null && System.currentTimeMillis() >= deadlineMillis) {
                    requestStop(StopReason.TIME_LIMIT, "Global time limit reached.");
                }

                if (memoryLogIntervalMillis > 0 && now >= nextMemoryLogMillis) {
                    out.println(memorySummary() + memoryLimitsSummary());
                    nextMemoryLogMillis = now + memoryLogIntervalMillis;
                }

                if (rssLimitMb != null && rssLimitMb > 0) {
                    long rssMb = getCurrentRssMb();
                    if (rssMb > 0 && rssMb >= rssLimitMb) {
                        requestStop(
                                StopReason.MEMORY_LIMIT,
                                "RSS memory limit exceeded: " + rssMb + " MB >= " + rssLimitMb + " MB."
                        );
                    }
                }

                if (shouldStop()) {
                    abortAllCplexSolvers();
                    return;
                }

                try {
                    Thread.sleep(checkIntervalMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });

        monitorThread.setDaemon(true);
        monitorThread.start();
    }

    public void requestStop(StopReason reason, String message) {
        if (stopRequested.compareAndSet(false, true)) {
            stopReason = reason;
            out.println("[RunContext] Stop requested: " + reason + ". " + message);
            abortAllCplexSolvers();
        }
    }


    public boolean shouldStop() {
        return stopRequested.get();
    }

    public StopReason getStopReason() {
        return stopReason;
    }

    public long elapsedMillis() {
        return System.currentTimeMillis() - startTimeMillis;
    }

    public double elapsedSeconds() {
        return elapsedMillis() / 1000.0;
    }

    public double remainingSeconds() {
        if (deadlineMillis == null) {
            return Double.POSITIVE_INFINITY;
        }
        return Math.max(0.0, (deadlineMillis - System.currentTimeMillis()) / 1000.0);
    }

    public boolean hasTimeLeft() {
        return deadlineMillis == null || remainingSeconds() > 0.0;
    }


    public static long getCurrentRssMb() {
        Path status = Path.of("/proc/self/status");

        try (BufferedReader reader = Files.newBufferedReader(status, StandardCharsets.US_ASCII)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("VmRSS:")) {
                    // Example: "VmRSS:   123456 kB"
                    String[] parts = line.trim().split("\\s+");
                    long kb = Long.parseLong(parts[1]);
                    return kb / 1024;
                }
            }
        } catch (IOException | NumberFormatException e) {
            return getCurrentRssMbFromProcessTool();
        }

        return getCurrentRssMbFromProcessTool();
    }

    private static long getCurrentRssMbFromProcessTool() {
        long pid = ProcessHandle.current().pid();
        String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (osName.contains("win")) {
            return getWindowsWorkingSetMb(pid);
        }
        return getUnixRssMb(pid);
    }

    private static long getWindowsWorkingSetMb(long pid) {
        ProcessBuilder builder = new ProcessBuilder("tasklist", "/FI", "PID eq " + pid, "/FO", "CSV", "/NH");
        try {
            Process process = builder.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                if (line == null || line.isBlank() || line.toLowerCase(Locale.ROOT).contains("no tasks")) {
                    return -1;
                }
                process.waitFor();
                String[] fields = parseCsvLine(line);
                if (fields.length < 5) {
                    return -1;
                }
                String memory = fields[4].replaceAll("[^0-9]", "");
                if (memory.isBlank()) {
                    return -1;
                }
                long kb = Long.parseLong(memory);
                return kb / 1024;
            }
        } catch (IOException | InterruptedException | NumberFormatException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return -1;
        }
    }

    private static long getUnixRssMb(long pid) {
        ProcessBuilder builder = new ProcessBuilder("ps", "-o", "rss=", "-p", Long.toString(pid));
        try {
            Process process = builder.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.US_ASCII))) {
                String line = reader.readLine();
                process.waitFor();
                if (line == null || line.isBlank()) {
                    return -1;
                }
                long kb = Long.parseLong(line.trim());
                return kb / 1024;
            }
        } catch (IOException | InterruptedException | NumberFormatException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return -1;
        }
    }

    private static String[] parseCsvLine(String line) {
        List<String> fields = new java.util.ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (c == ',' && !quoted) {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(c);
            }
        }
        fields.add(field.toString());
        return fields.toArray(String[]::new);
    }

    public static long getUsedHeapMb() {
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
    }

    public static long getMaxHeapMb() {
        return Runtime.getRuntime().maxMemory() / 1024 / 1024;
    }

    public static String memorySummary() {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memoryBean.getHeapMemoryUsage();
        MemoryUsage nonHeap = memoryBean.getNonHeapMemoryUsage();
        long rssMb = getCurrentRssMb();
        long heapUsedMb = bytesToMb(heap.getUsed());
        long heapCommittedMb = bytesToMb(heap.getCommitted());
        long heapMaxMb = bytesToMb(heap.getMax());
        long nonHeapUsedMb = bytesToMb(nonHeap.getUsed());
        long nonHeapCommittedMb = bytesToMb(nonHeap.getCommitted());
        long directMb = bufferPoolMb("direct");
        long mappedMb = bufferPoolMb("mapped");
        long knownJvmMb = nonNegative(heapUsedMb) + nonNegative(nonHeapUsedMb) +
                nonNegative(directMb) + nonNegative(mappedMb);
        long nativeApproxMb = rssMb < 0 ? -1 : Math.max(0, rssMb - knownJvmMb);

        return String.format(
                "[RunContext] Memory: RSS=%d MB, heap=%d/%d/%d MB (used/committed/max), " +
                        "nonHeap=%d/%d MB (used/committed), direct=%d MB, mapped=%d MB, " +
                        "nativeOtherApprox=%d MB",
                rssMb,
                heapUsedMb, heapCommittedMb, heapMaxMb,
                nonHeapUsedMb, nonHeapCommittedMb,
                directMb, mappedMb,
                nativeApproxMb
        );
    }

    private String memoryLimitsSummary() {
        return String.format(
                ", limits: rss_limit=%s MB, work_mem=%s MB, tree_mem=%s MB, heap_max=%d MB",
                params.rssLimitMb == null ? "none" : params.rssLimitMb.toString(),
                params.workMemMb == null ? "default" : params.workMemMb.toString(),
                params.treeMemMb == null ? "default" : params.treeMemMb.toString(),
                getMaxHeapMb()
        );
    }

    private static long bytesToMb(long bytes) {
        return bytes < 0 ? -1 : bytes / BYTES_PER_MB;
    }

    private static long nonNegative(long value) {
        return Math.max(0, value);
    }

    private static long bufferPoolMb(String poolName) {
        List<BufferPoolMXBean> pools = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class);
        long bytes = 0L;
        for (BufferPoolMXBean pool : pools) {
            if (pool.getName().toLowerCase().contains(poolName)) {
                bytes += Math.max(0L, pool.getMemoryUsed());
            }
        }
        return bytesToMb(bytes);
    }

    public void registerCplex(IloCplex cplex) throws IloException {
        IloCplex.Aborter aborter = new IloCplex.Aborter();
        cplex.use(aborter);
        aborters.put(cplex, aborter);

        if (shouldStop()) {
            aborter.abort();
        }
    }

    public void unregisterCplex(IloCplex cplex) {
        if (cplex != null) {
            aborters.remove(cplex);
        }
    }

    private void abortAllCplexSolvers() {
        for (IloCplex.Aborter aborter : aborters.values()) {
            try {
                aborter.abort();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void close() {
        finished.set(true);
        if (monitorThread != null) {
            monitorThread.interrupt();
        }
        aborters.clear();
    }

    public String summary() {
        return String.format(
                "elapsed=%.2fs, remaining=%.2fs, RSS=%d MB, heap=%d/%d MB (used/max), stop=%s",
                elapsedSeconds(),
                remainingSeconds(),
                getCurrentRssMb(),
                getUsedHeapMb(),
                getMaxHeapMb(),
                getStopReason()
        );
    }
}
