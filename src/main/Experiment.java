package main;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Experiment {
    public SolverType solver;
    public boolean write;
    public boolean exportLp;
    public Integer timeLimit;
    public Integer cplexThreads;
    public Integer workMemMb;
    public Integer treeMemMb;
    public Integer rssLimitMb;
    public Integer heapMb;
    public Integer memoryBudgetMb;
    public Integer nodeFile;
    public Integer mipDisplay;
    public Integer simplexDisplay;
    public Integer barrierDisplay;
    public Integer cplexLogLimitMb;
    public String workDir;
    public String mipEmphasis;
    public boolean memoryEmphasis;
    public Long rssCheckIntervalMs;
    public Long memoryLogIntervalMs;
    public String batchName;
    public String runOutputDir;
    public Long parentPid;

    public int small;
    public int medium;
    public int large;
    public int rows;
    public int cols;
    public int seed;

    private Integer inputSmall;
    private Integer inputMedium;
    private Integer inputLarge;
    private Integer inputRows;
    private Integer inputCols;
    private Integer inputSeed;

    public static Experiment parseWorkerArgs(Map<String, String> args) {
        Experiment experiment = new Experiment();
        experiment.applyArgs(args);
        experiment.fillConcreteDefaults();
        experiment.validate();
        return experiment;
    }

    static Map<String, String> parseArgMap(String[] args) {
        Map<String, String> argMap = new LinkedHashMap<>();
        for (String arg : args) {
            String[] parts = arg.split("=", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid argument format: " + arg);
            }
            argMap.put(normalizeKey(parts[0]), parts[1]);
        }
        return argMap;
    }

    static String normalizeKey(String key) {
        return key.toLowerCase().replace("-", "_");
    }

    void applyArgs(Map<String, String> args) {
        for (Map.Entry<String, String> entry : args.entrySet()) {
            applyArg(entry.getKey(), entry.getValue());
        }
    }

    void applyArg(String rawKey, String value) {
        String key = normalizeKey(rawKey);

        switch (key) {
            case "worker" -> {
            }
            case "parent_pid" -> this.parentPid = parseLong(value, key);
            case "batch_name" -> this.batchName = sanitizeName(value);
            case "solver" -> this.solver = SolverType.fromName(value);
            case "small" -> this.inputSmall = parseInt(value, key);
            case "medium" -> this.inputMedium = parseInt(value, key);
            case "large" -> this.inputLarge = parseInt(value, key);
            case "rows" -> this.inputRows = parseInt(value, key);
            case "cols" -> this.inputCols = parseInt(value, key);
            case "seed", "seeds" -> this.inputSeed = parseInt(value, key);
            case "write" -> this.write = parseBoolean(value, key);
            case "export_lp", "exportlp" -> this.exportLp = parseBoolean(value, key);
            case "timelimit", "time_limit" -> this.timeLimit = parseInt(value, key);
            case "cplex_threads" -> this.cplexThreads = parseInt(value, key);
            case "work_mem", "work_mem_mb" -> this.workMemMb = parseMemorySizeMb(value, key);
            case "tree_mem", "tree_memory", "tree_mem_mb" -> this.treeMemMb = parseMemorySizeMb(value, key);
            case "rss_limit", "rss_limit_mb" -> this.rssLimitMb = parseMemorySizeMb(value, key);
            case "heap", "xmx", "heap_mb", "xmx_mb" -> this.heapMb = parseMemorySizeMb(value, key);
            case "memory_budget", "memory_budget_mb" -> this.memoryBudgetMb = parseMemorySizeMb(value, key);
            case "rss_check_interval", "rss_check_interval_ms" -> this.rssCheckIntervalMs = parseLong(value, key);
            case "memory_log_interval", "memory_log_interval_ms" -> this.memoryLogIntervalMs = parseLong(value, key);
            case "node_file" -> this.nodeFile = parseInt(value, key);
            case "work_dir" -> this.workDir = value;
            case "mip_display" -> this.mipDisplay = parseInt(value, key);
            case "simplex_display" -> this.simplexDisplay = parseInt(value, key);
            case "barrier_display" -> this.barrierDisplay = parseInt(value, key);
            case "cplex_log_limit", "cplex_log_limit_mb", "cplex_log_max", "cplex_log_max_mb" ->
                    this.cplexLogLimitMb = parseMemorySizeMb(value, key);
            case "mip_emphasis" -> this.mipEmphasis = value.toLowerCase();
            case "memory_emphasis" -> this.memoryEmphasis = parseBoolean(value, key);
            case "run_output_dir" -> this.runOutputDir = value;
            default -> throw new IllegalArgumentException("Unknown parameter: " + key);
        }
    }

    void setInstanceKey(int small, int medium, int large, int rows, int cols, int seed) {
        this.inputSmall = small;
        this.inputMedium = medium;
        this.inputLarge = large;
        this.inputRows = rows;
        this.inputCols = cols;
        this.inputSeed = seed;
        fillConcreteDefaults();
    }

    Experiment copySettings() {
        Experiment copy = new Experiment();
        copy.solver = this.solver;
        copy.write = this.write;
        copy.exportLp = this.exportLp;
        copy.timeLimit = this.timeLimit;
        copy.cplexThreads = this.cplexThreads;
        copy.workMemMb = this.workMemMb;
        copy.treeMemMb = this.treeMemMb;
        copy.rssLimitMb = this.rssLimitMb;
        copy.heapMb = this.heapMb;
        copy.memoryBudgetMb = this.memoryBudgetMb;
        copy.nodeFile = this.nodeFile;
        copy.mipDisplay = this.mipDisplay;
        copy.simplexDisplay = this.simplexDisplay;
        copy.barrierDisplay = this.barrierDisplay;
        copy.cplexLogLimitMb = this.cplexLogLimitMb;
        copy.workDir = this.workDir;
        copy.mipEmphasis = this.mipEmphasis;
        copy.memoryEmphasis = this.memoryEmphasis;
        copy.rssCheckIntervalMs = this.rssCheckIntervalMs;
        copy.memoryLogIntervalMs = this.memoryLogIntervalMs;
        copy.batchName = this.batchName;
        copy.runOutputDir = this.runOutputDir;
        copy.parentPid = this.parentPid;
        return copy;
    }

    void fillConcreteDefaults() {
        this.solver = this.solver == null ? SolverType.CPLEX_INTEGRATED_MODEL : this.solver;
        this.small = inputSmall == null ? 2 : inputSmall;
        this.medium = inputMedium == null ? 0 : inputMedium;
        this.large = inputLarge == null ? 1 : inputLarge;
        this.rows = inputRows == null ? 4 : inputRows;
        int total = small + medium + large;
        if (inputCols == null) {
            if (total % 3 != 0) {
                throw new IllegalArgumentException("Total vessel count must be divisible by 3 when cols is unspecified");
            }
            this.cols = total / 3;
        } else {
            this.cols = inputCols;
        }
        this.seed = inputSeed == null ? 1 : inputSeed;
    }

    void validate() {
        checkNonNegative(small, "small");
        checkNonNegative(medium, "medium");
        checkNonNegative(large, "large");
        checkNonNegative(rows, "rows");
        checkNonNegative(cols, "cols");
        checkNonNegative(seed, "seed");
        if (timeLimit != null) {
            checkRange(timeLimit, 1, 86400, "timelimit");
        }
        if (cplexThreads != null) {
            checkRange(cplexThreads, 1, 32, "cplex_threads");
        }
        if (workMemMb != null) {
            checkRange(workMemMb, 128, 1024 * 1024, "work_mem");
        }
        if (treeMemMb != null) {
            checkRange(treeMemMb, 128, 1024 * 1024, "tree_mem");
        }
        if (rssLimitMb != null) {
            checkRange(rssLimitMb, 512, 1024 * 1024, "rss_limit");
        }
        if (heapMb != null) {
            checkRange(heapMb, 128, 1024 * 1024, "heap");
        }
        if (memoryBudgetMb != null) {
            checkRange(memoryBudgetMb, 512, 1024 * 1024, "memory_budget");
        }
        if (rssCheckIntervalMs != null) {
            checkLongRange(rssCheckIntervalMs, 100, 86_400_000, "rss_check_interval_ms");
        }
        if (memoryLogIntervalMs != null) {
            checkLongRange(memoryLogIntervalMs, 1000, 86_400_000, "memory_log_interval_ms");
        }
        if (nodeFile != null) {
            checkRange(nodeFile, 0, 3, "node_file");
        }
        if (mipDisplay != null) {
            checkRange(mipDisplay, 0, 5, "mip_display");
        }
        if (simplexDisplay != null) {
            checkRange(simplexDisplay, 0, 2, "simplex_display");
        }
        if (barrierDisplay != null) {
            checkRange(barrierDisplay, 0, 2, "barrier_display");
        }
        if (cplexLogLimitMb != null) {
            checkRange(cplexLogLimitMb, 1, 1024 * 1024, "cplex_log_limit");
        }
    }

    public String name() {
        return String.format("{%02d-%02d-%02d}_{%02d-%02d}_%02d",
                small, medium, large, rows, cols, seed);
    }

    public String briefName() {
        return name() + "_" + solver.getName();
    }

    public List<String> toWorkerArgs() {
        List<String> args = new ArrayList<>();
        args.add("worker=true");
        args.add("solver=" + solver.getName());
        args.add("small=" + small);
        args.add("medium=" + medium);
        args.add("large=" + large);
        args.add("rows=" + rows);
        args.add("cols=" + cols);
        args.add("seed=" + seed);
        args.add("write=" + write);
        args.add("export_lp=" + exportLp);
        args.add("memory_emphasis=" + memoryEmphasis);

        addOptionalArg(args, "timelimit", timeLimit);
        addOptionalArg(args, "cplex_threads", cplexThreads);
        addOptionalMemoryArg(args, "work_mem", workMemMb);
        addOptionalMemoryArg(args, "tree_mem", treeMemMb);
        addOptionalMemoryArg(args, "rss_limit", rssLimitMb);
        addOptionalArg(args, "rss_check_interval_ms", rssCheckIntervalMs);
        addOptionalArg(args, "memory_log_interval_ms", memoryLogIntervalMs);
        addOptionalArg(args, "node_file", nodeFile);
        addOptionalArg(args, "work_dir", workDir);
        addOptionalArg(args, "mip_display", mipDisplay);
        addOptionalArg(args, "simplex_display", simplexDisplay);
        addOptionalArg(args, "barrier_display", barrierDisplay);
        addOptionalMemoryArg(args, "cplex_log_limit", cplexLogLimitMb);
        addOptionalArg(args, "mip_emphasis", mipEmphasis);
        addOptionalArg(args, "batch_name", batchName);
        addOptionalArg(args, "run_output_dir", runOutputDir);
        return args;
    }

    private static void addOptionalArg(List<String> args, String key, Object value) {
        if (value != null) {
            args.add(key + "=" + value);
        }
    }

    private static void addOptionalMemoryArg(List<String> args, String key, Integer valueMb) {
        if (valueMb != null) {
            args.add(key + "=" + valueMb + "m");
        }
    }

    static int parseInt(String value, String paramName) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid integer value for " + paramName + ": " + value);
        }
    }

    static long parseLong(String value, String paramName) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid long value for " + paramName + ": " + value);
        }
    }

    static int parseMemorySizeMb(String value, String paramName) {
        String normalized = value.trim().toLowerCase();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Invalid memory size for " + paramName + ": " + value);
        }

        int split = 0;
        while (split < normalized.length() && Character.isDigit(normalized.charAt(split))) {
            split++;
        }
        if (split == 0) {
            throw new IllegalArgumentException("Invalid memory size for " + paramName + ": " + value);
        }

        long amount;
        try {
            amount = Long.parseLong(normalized.substring(0, split));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid memory size for " + paramName + ": " + value);
        }

        String unit = normalized.substring(split);
        long bytes;
        try {
            bytes = Math.multiplyExact(amount, memoryUnitMultiplier(unit, paramName, value));
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Memory size for " + paramName + " is too large: " + value);
        }

        long mb = (bytes + 1024L * 1024L - 1) / (1024L * 1024L);
        if (mb > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Memory size for " + paramName + " is too large: " + value);
        }
        return (int) mb;
    }

    private static long memoryUnitMultiplier(String unit, String paramName, String value) {
        return switch (unit) {
            case "", "m", "mb" -> 1024L * 1024L;
            case "k", "kb" -> 1024L;
            case "g", "gb" -> 1024L * 1024L * 1024L;
            case "t", "tb" -> 1024L * 1024L * 1024L * 1024L;
            default -> throw new IllegalArgumentException("Invalid memory unit for " + paramName + ": " + value);
        };
    }

    static boolean parseBoolean(String value, String paramName) {
        if (value.equalsIgnoreCase("true")) {
            return true;
        } else if (value.equalsIgnoreCase("false")) {
            return false;
        }
        throw new IllegalArgumentException("Invalid boolean value for " + paramName + ": " + value);
    }

    static String sanitizeName(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static void checkNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException("Negative value for " + name + ": " + value);
        }
    }

    private static void checkRange(int value, int min, int max, String name) {
        if (value < min || value > max) {
            throw new IllegalArgumentException("Value for " + name + " out of range [" + min + ", " + max + "]: " + value);
        }
    }

    private static void checkLongRange(long value, long min, long max, String name) {
        if (value < min || value > max) {
            throw new IllegalArgumentException("Value for " + name + " out of range [" + min + ", " + max + "]: " + value);
        }
    }
}
