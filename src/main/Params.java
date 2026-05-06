package main;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import util.IntervalSet;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class Params {
    public static final String USAGE =
            "Usage: java main.Runner [key=value...]\n" +
                    "Parameters (all optional, default values shown):\n" +
                    "  config      - Read batch configuration JSON\n" +
                    "  worker      - Internal launcher flag [true|false] (default: false)\n" +
                    "  solver      - Solver type [cplex|sequential|decomposed|local_refinement] (default: cplex)\n" +
                    "  vessel      - Number of vessels for different types (e.g., (2,0,1),(2,1,0) )\n" +
                    "  small       - Number of small vessels (default: 2)\n" +
                    "  medium      - Number of medium vessels (default: 0)\n" +
                    "  large       - Number of large vessels (default: 1)\n" +
                    "  rows        - Yard rows (default: 4)\n" +
                    "  cols        - Yard columns (auto-calculated if not specified)\n" +
                    "  seeds       - Random seed range (e.g. 1-5,7,9-11)\n" +
                    "  write       - Enable solution output [true|false] (default: false)\n" +
                    "  export_lp   - Export CPLEX original model LP [true|false] (default: false)\n" +
                    "  timelimit   - Solver time limit in seconds (default: no limit)\n" +
                    "  threads     - CPU thread count (default: no limit)\n" +
                    "  parallel    - legacy same-JVM parallel testing [true|false] (default: false)\n" +
                    "  processes   - launcher child JVM count\n" +
                    "  heap_mb      - launcher child JVM -Xmx value\n" +
                    "  fail_fast   - stop launching after first failed child process [true|false] (default: false)\n\n" +
                    "Examples:\n" +
                    "  java main.Runner solver=sequential small=3 medium=0 large=2 timelimit=1800\n" +
                    "  java main.Runner seeds=1,3-5 write=true\n" +
                    "  java main.Runner config=configs.json processes=4 heap_mb=8192";


    public SolverType solver;
    public boolean write;
    public boolean exportLp;
    public Integer timeLimit;
    public Integer threads;
    public boolean parallel;
    public Integer processes;
    public Integer workMemMb;
    public Integer treeMemMb;
    public Integer rssLimitMb;
    public Integer nodeFile;
    public Integer mipDisplay;
    public String workDir;
    public String mipEmphasis;
    public boolean memoryEmphasis;

    public Long rssCheckIntervalMs;
    public Long memoryLogIntervalMs;

    public String configFile;
    public String batchName;
    public boolean worker;
    public Integer heapMb;
    public boolean failFast;
    public Long parentPid;

    public List<VesselConfig> configs;
    public List<Params> experiments;

    private List<int[]> vessels;
    private Integer small;
    private Integer medium;
    private Integer large;
    private Integer rows;
    private Integer cols;
    private IntervalSet seeds;

    public static class VesselConfig {
        public int small;
        public int medium;
        public int large;
        public int rows;
        public int cols;
        public int seed;

        public String name;

        public VesselConfig(int small, int medium, int large, int rows, int cols, int seed) {
            this.small = small;
            this.medium = medium;
            this.large = large;
            this.rows = rows;
            this.cols = cols;
            this.seed = seed;
            this.name = String.format("{%02d-%02d-%02d}_{%02d-%02d}_%02d",
                    small, medium, large,
                    rows, cols, seed);
        }

        public String toString() {
            return name;
        }
    }

    public static Params parse(String[] args) {
        try {
            Map<String, String> cliArgs = parseArgMap(args);
            if (cliArgs.containsKey("config") && !parseOptionalBoolean(cliArgs.get("worker"))) {
                return parseConfig(cliArgs);
            }

            Params params = new Params();
            params.applyArgs(cliArgs);
            params.validate();
            params.autoFill();
            return params;
        } catch (IllegalArgumentException e) {
            System.err.println("Parameter error: " + e.getMessage());
            System.err.println(USAGE);
            System.exit(1);
            return null;
        }
    }

    private static Params parseConfig(Map<String, String> cliArgs) {
        String configFile = cliArgs.get("config");
        JsonNode root;
        try {
            root = new ObjectMapper().readTree(new File(configFile));
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read config file " + configFile + ": " + e.getMessage(), e);
        }
        if (!root.isObject()) {
            throw new IllegalArgumentException("Config file root must be a JSON object");
        }

        Map<String, String> defaultArgs = jsonObjectToArgs(root.path("defaults"));
        Map<String, String> workerCliOverrides = workerCliOverrides(cliArgs);

        Params launcher = new Params();
        launcher.configFile = configFile;
        launcher.batchName = configBaseName(configFile);
        launcher.applyArgs(defaultArgs);
        if (root.has("launcher")) {
            launcher.applyArgs(jsonObjectToArgs(root.get("launcher")));
        }
        launcher.applyArgs(cliArgs);
        launcher.experiments = new ArrayList<>();

        boolean hasSweep = root.has("sweep") && root.get("sweep").isObject() && root.get("sweep").fieldNames().hasNext();
        boolean hasRuns = root.has("runs") && root.get("runs").isArray() && root.get("runs").size() > 0;

        if (hasSweep) {
            for (Map<String, String> sweepArgs : expandSweep(root.get("sweep"))) {
                Params experiment = new Params();
                experiment.batchName = launcher.batchName;
                experiment.applyArgs(defaultArgs);
                experiment.applyArgs(sweepArgs);
                experiment.applyArgs(workerCliOverrides);
                addConcreteExperiments(launcher.experiments, experiment);
            }
        }

        if (hasRuns) {
            for (JsonNode runNode : root.get("runs")) {
                if (!runNode.isObject()) {
                    throw new IllegalArgumentException("Every item in runs must be a JSON object");
                }
                Params experiment = new Params();
                experiment.batchName = launcher.batchName;
                experiment.applyArgs(defaultArgs);
                experiment.applyArgs(jsonObjectToArgs(runNode));
                experiment.applyArgs(workerCliOverrides);
                addConcreteExperiments(launcher.experiments, experiment);
            }
        }

        if (!hasSweep && !hasRuns) {
            Params experiment = new Params();
            experiment.batchName = launcher.batchName;
            experiment.applyArgs(defaultArgs);
            experiment.applyArgs(workerCliOverrides);
            addConcreteExperiments(launcher.experiments, experiment);
        }

        if (launcher.experiments.isEmpty()) {
            throw new IllegalArgumentException("No experiments generated from config file");
        }
        launcher.validateLauncher();
        return launcher;
    }

    private static void addConcreteExperiments(List<Params> experiments, Params experiment) {
        experiment.validate();
        experiment.autoFill();

        for (VesselConfig config : experiment.configs) {
            Params concrete = experiment.copyRunSettings();
            concrete.vessels = new ArrayList<>();
            concrete.vessels.add(new int[]{config.small, config.medium, config.large});
            concrete.small = null;
            concrete.medium = null;
            concrete.large = null;
            concrete.rows = config.rows;
            concrete.cols = config.cols;
            concrete.seeds = IntervalSet.of(config.seed);
            concrete.configs = new ArrayList<>();
            concrete.configs.add(config);
            concrete.worker = true;
            concrete.batchName = experiment.batchName;
            experiments.add(concrete);
        }
    }

    private static String configBaseName(String configFile) {
        String name = new File(configFile).getName();
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        return sanitizeName(name);
    }

    private static String sanitizeName(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static List<Map<String, String>> expandSweep(JsonNode sweepNode) {
        List<Map.Entry<String, List<String>>> dimensions = new ArrayList<>();
        sweepNode.properties().forEach(entry ->
                dimensions.add(Map.entry(normalizeKey(entry.getKey()), expandSweepValues(entry.getKey(), entry.getValue()))));

        List<Map<String, String>> results = new ArrayList<>();
        results.add(new LinkedHashMap<>());
        for (Map.Entry<String, List<String>> dimension : dimensions) {
            List<Map<String, String>> next = new ArrayList<>();
            for (Map<String, String> partial : results) {
                for (String value : dimension.getValue()) {
                    Map<String, String> copy = new LinkedHashMap<>(partial);
                    copy.put(dimension.getKey(), value);
                    next.add(copy);
                }
            }
            results = next;
        }
        return results;
    }

    private static List<String> expandSweepValues(String rawKey, JsonNode valueNode) {
        String key = normalizeKey(rawKey);
        if (key.equals("seed") || key.equals("seeds")) {
            List<String> values = new ArrayList<>();
            if (valueNode.isArray()) {
                for (JsonNode node : valueNode) {
                    for (int seed : new Params().parseSeeds(jsonValueToString(rawKey, node), rawKey)) {
                        values.add(Integer.toString(seed));
                    }
                }
            } else {
                for (int seed : new Params().parseSeeds(jsonValueToString(rawKey, valueNode), rawKey)) {
                    values.add(Integer.toString(seed));
                }
            }
            return values;
        }

        if (key.equals("vessel") || key.equals("vessels")) {
            List<String> values = new ArrayList<>();
            if (valueNode.isArray() && valueNode.size() > 0 && valueNode.get(0).isArray()) {
                for (JsonNode tuple : valueNode) {
                    values.add(vesselTupleToString(tuple));
                }
            } else {
                values.add(jsonValueToString(rawKey, valueNode));
            }
            return values;
        }

        if (valueNode.isArray()) {
            List<String> values = new ArrayList<>();
            for (JsonNode node : valueNode) {
                values.add(jsonValueToString(rawKey, node));
            }
            return values;
        }
        return List.of(jsonValueToString(rawKey, valueNode));
    }

    private static Map<String, String> jsonObjectToArgs(JsonNode objectNode) {
        Map<String, String> args = new LinkedHashMap<>();
        if (objectNode == null || objectNode.isMissingNode() || objectNode.isNull()) {
            return args;
        }
        if (!objectNode.isObject()) {
            throw new IllegalArgumentException("Expected a JSON object");
        }

        objectNode.properties().forEach(entry ->
                args.put(normalizeKey(entry.getKey()), jsonValueToString(entry.getKey(), entry.getValue())));
        return args;
    }

    private static String jsonValueToString(String rawKey, JsonNode node) {
        String key = normalizeKey(rawKey);
        if (key.equals("vessel") || key.equals("vessels")) {
            if (node.isTextual()) {
                return node.asText();
            }
            if (node.isArray() && node.size() > 0 && node.get(0).isArray()) {
                List<String> tuples = new ArrayList<>();
                for (JsonNode tuple : node) {
                    tuples.add(vesselTupleToString(tuple));
                }
                return String.join(",", tuples);
            }
            return vesselTupleToString(node);
        }

        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isNumber() || node.isBoolean()) {
            return node.asText();
        }
        if (node.isArray()) {
            List<String> values = new ArrayList<>();
            for (JsonNode item : node) {
                values.add(jsonValueToString(rawKey, item));
            }
            return String.join(",", values);
        }
        throw new IllegalArgumentException("Unsupported JSON value for " + rawKey + ": " + node);
    }

    private static String vesselTupleToString(JsonNode node) {
        if (!node.isArray() || node.size() != 3) {
            throw new IllegalArgumentException("Vessel tuple must be an array with 3 integers: " + node);
        }
        return String.format("(%d,%d,%d)", node.get(0).asInt(), node.get(1).asInt(), node.get(2).asInt());
    }

    private static Map<String, String> parseArgMap(String[] args) {
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

    private static Map<String, String> workerCliOverrides(Map<String, String> cliArgs) {
        Map<String, String> overrides = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : cliArgs.entrySet()) {
            String key = normalizeKey(entry.getKey());
            if (!isLauncherOnlyKey(key) && !key.equals("config")) {
                overrides.put(key, entry.getValue());
            }
        }
        return overrides;
    }

    private static boolean isLauncherOnlyKey(String key) {
        return key.equals("processes") || key.equals("heap_mb") || key.equals("xmx_mb") ||
                key.equals("fail_fast");
    }

    private static boolean parseOptionalBoolean(String value) {
        return value != null && Boolean.parseBoolean(value);
    }

    private static String normalizeKey(String key) {
        return key.toLowerCase().replace("-", "_");
    }

    private void applyArgs(Map<String, String> args) {
        for (Map.Entry<String, String> entry : args.entrySet()) {
            applyArg(entry.getKey(), entry.getValue());
        }
    }

    private void applyArg(String rawKey, String value) {
        String key = normalizeKey(rawKey);

        switch (key) {
            case "config" -> this.configFile = value;
            case "batch_name" -> this.batchName = sanitizeName(value);
            case "worker" -> this.worker = parseBoolean(value, key);
            case "parallel" -> this.parallel = parseBoolean(value, key);
            case "solver" -> this.solver = SolverType.fromName(value);
            case "small" -> this.small = parseInt(value, key);
            case "medium" -> this.medium = parseInt(value, key);
            case "large" -> this.large = parseInt(value, key);
            case "vessels", "vessel" -> this.vessels = parseVessels(value);
            case "rows" -> this.rows = parseInt(value, key);
            case "cols" -> this.cols = parseInt(value, key);
            case "seed", "seeds" -> this.seeds = parseSeeds(value, key);
            case "write" -> this.write = parseBoolean(value, key);
            case "export_lp", "exportlp" -> this.exportLp = parseBoolean(value, key);
            case "timelimit", "time_limit" -> this.timeLimit = parseInt(value, key);
            case "threads" -> this.threads = parseInt(value, key);
            case "processes" -> this.processes = parseInt(value, key);
            case "heap_mb", "xmx_mb" -> this.heapMb = parseInt(value, key);
            case "fail_fast" -> this.failFast = parseBoolean(value, key);
            case "parent_pid" -> this.parentPid = parseLong(value, key);
            case "work_mem", "work_mem_mb" -> this.workMemMb = parseInt(value, key);
            case "tree_mem", "tree_memory", "tree_mem_mb" -> this.treeMemMb = parseInt(value, key);
            case "rss_limit", "rss_limit_mb" -> this.rssLimitMb = parseInt(value, key);
            case "rss_check_interval", "rss_check_interval_ms" -> this.rssCheckIntervalMs = parseLong(value, key);
            case "memory_log_interval", "memory_log_interval_ms" -> this.memoryLogIntervalMs = parseLong(value, key);
            case "node_file" -> this.nodeFile = parseInt(value, key);
            case "work_dir" -> this.workDir = value;
            case "mip_display" -> this.mipDisplay = parseInt(value, key);
            case "mip_emphasis" -> this.mipEmphasis = value.toLowerCase();
            case "memory_emphasis" -> this.memoryEmphasis = parseBoolean(value, key);
            default -> throw new IllegalArgumentException("Unknown parameter: " + key);
        }
    }

    public void validate() {
        if ((this.vessels != null && !this.vessels.isEmpty())
                && (this.small != null || this.medium != null || this.large != null)) {
            throw new IllegalArgumentException("Cannot specify both 'vessels' and individual vessel counts");
        }

        if (this.vessels != null && !this.vessels.isEmpty()) {
            for (int[] tuple : this.vessels) {
                checkNonNegative(tuple[0], "small");
                checkNonNegative(tuple[1], "medium");
                checkNonNegative(tuple[2], "large");
            }
        }
        if (this.small != null || this.medium != null || this.large != null) {
            Objects.requireNonNull(this.small, "small");
            Objects.requireNonNull(this.medium, "medium");
            Objects.requireNonNull(this.large, "large");
            checkNonNegative(this.small, "small");
            checkNonNegative(this.medium, "medium");
            checkNonNegative(this.large, "large");
        }

        if (this.rows != null)
            checkNonNegative(this.rows, "rows");
        if (this.cols != null)
            checkNonNegative(this.cols, "cols");

        if (this.timeLimit != null)
            checkRange(this.timeLimit, 1, 86400, "timelimit");
        if (this.threads != null)
            checkRange(this.threads, 1, 32, "threads");
        if (this.processes != null)
            checkRange(this.processes, 1, 1024, "processes");
        if (this.heapMb != null)
            checkRange(this.heapMb, 128, 1024 * 1024, "heap_mb");
        if (this.workMemMb != null)
            checkRange(this.workMemMb, 128, 1024 * 1024, "work_mem");

        if (this.treeMemMb != null)
            checkRange(this.treeMemMb, 128, 1024 * 1024, "tree_mem");

        if (this.rssLimitMb != null)
            checkRange(this.rssLimitMb, 512, 1024 * 1024, "rss_limit");
        if (this.rssCheckIntervalMs != null)
            checkLongRange(this.rssCheckIntervalMs, 100, 86_400_000, "rss_check_interval_ms");
        if (this.memoryLogIntervalMs != null)
            checkLongRange(this.memoryLogIntervalMs, 1000, 86_400_000, "memory_log_interval_ms");

        if (this.nodeFile != null)
            checkRange(this.nodeFile, 0, 3, "node_file");
    }

    private void validateLauncher() {
        if (this.processes != null)
            checkRange(this.processes, 1, 1024, "processes");
        if (this.heapMb != null)
            checkRange(this.heapMb, 128, 1024 * 1024, "heap_mb");
    }

    public void autoFill() {
        if (this.solver == null)
            this.solver = SolverType.CPLEX_INTEGRATED_MODEL;

        if (this.seeds == null)
            this.seeds = IntervalSet.rangeClosed(1, 5);

        if (this.vessels == null || this.vessels.isEmpty()) {
            this.vessels = new ArrayList<>();
            if (this.small != null && this.medium != null && this.large != null) {
                this.vessels.add(new int[]{this.small, this.medium, this.large});
            } else if (this.small == null && this.medium == null && this.large == null) {
                this.vessels.add(new int[]{2, 0, 1});
            } else {
                throw new IllegalArgumentException("Either all or none of small, medium, and large must be specified");
            }
        }

        this.configs = new ArrayList<>();
        for (int[] tuple : this.vessels) {
            int small = tuple[0];
            int medium = tuple[1];
            int large = tuple[2];

            int rows = Objects.requireNonNullElse(this.rows, 4);

            int cols;
            if (this.cols == null) {
                int total = small + medium + large;
                if (total % 3 != 0) {
                    throw new IllegalArgumentException("Total vessel count must be divisible by 3 when cols is unspecified");
                }
                cols = total / 3;
            } else {
                cols = this.cols;
            }

            for (int seed : this.seeds)
                this.configs.add(new VesselConfig(small, medium, large,
                        rows, cols, seed));
        }
    }

    public List<String> toWorkerArgs() {
        if (this.configs == null || this.configs.size() != 1) {
            throw new IllegalStateException("Worker args require exactly one concrete VesselConfig");
        }

        VesselConfig config = this.configs.get(0);
        List<String> args = new ArrayList<>();
        args.add("worker=true");
        args.add("solver=" + solver.getName());
        args.add("small=" + config.small);
        args.add("medium=" + config.medium);
        args.add("large=" + config.large);
        args.add("rows=" + config.rows);
        args.add("cols=" + config.cols);
        args.add("seed=" + config.seed);
        args.add("write=" + write);
        args.add("export_lp=" + exportLp);
        args.add("memory_emphasis=" + memoryEmphasis);

        addOptionalArg(args, "timelimit", timeLimit);
        addOptionalArg(args, "threads", threads);
        addOptionalArg(args, "work_mem_mb", workMemMb);
        addOptionalArg(args, "tree_mem_mb", treeMemMb);
        addOptionalArg(args, "rss_limit_mb", rssLimitMb);
        addOptionalArg(args, "rss_check_interval_ms", rssCheckIntervalMs);
        addOptionalArg(args, "memory_log_interval_ms", memoryLogIntervalMs);
        addOptionalArg(args, "node_file", nodeFile);
        addOptionalArg(args, "work_dir", workDir);
        addOptionalArg(args, "mip_display", mipDisplay);
        addOptionalArg(args, "mip_emphasis", mipEmphasis);
        addOptionalArg(args, "batch_name", batchName);
        return args;
    }

    private static void addOptionalArg(List<String> args, String key, Object value) {
        if (value != null) {
            args.add(key + "=" + value);
        }
    }

    public String briefName() {
        if (configs == null || configs.isEmpty()) {
            return solver == null ? "unfilled" : solver.getName();
        }
        return configs.get(0).name + "_" + solver.getName();
    }

    private Params copyRunSettings() {
        Params copy = new Params();
        copy.solver = this.solver;
        copy.write = this.write;
        copy.exportLp = this.exportLp;
        copy.timeLimit = this.timeLimit;
        copy.threads = this.threads;
        copy.parallel = false;
        copy.workMemMb = this.workMemMb;
        copy.treeMemMb = this.treeMemMb;
        copy.rssLimitMb = this.rssLimitMb;
        copy.nodeFile = this.nodeFile;
        copy.mipDisplay = this.mipDisplay;
        copy.workDir = this.workDir;
        copy.mipEmphasis = this.mipEmphasis;
        copy.memoryEmphasis = this.memoryEmphasis;
        copy.rssCheckIntervalMs = this.rssCheckIntervalMs;
        copy.memoryLogIntervalMs = this.memoryLogIntervalMs;
        copy.batchName = this.batchName;
        return copy;
    }

    private int parseInt(String value, String paramName) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid integer value for " + paramName + ": " + value);
        }
    }

    private long parseLong(String value, String paramName) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid long value for " + paramName + ": " + value);
        }
    }

    private boolean parseBoolean(String value, String paramName) {
        if (value.equalsIgnoreCase("true")) {
            return true;
        } else if (value.equalsIgnoreCase("false")) {
            return false;
        } else {
            throw new IllegalArgumentException("Invalid boolean value for " + paramName + ": " + value);
        }
    }

    private IntervalSet parseSeeds(String seedsStr, String paramName) {
        IntervalSet seeds = IntervalSet.empty();
        for (String part : seedsStr.split(",")) {
            part = part.trim();
            if (part.isEmpty()) {
                continue;
            }
            if (part.contains("-")) {
                String[] range = part.split("-");
                int start = Integer.parseInt(range[0]);
                int end = Integer.parseInt(range[1]);
                if (start < 0 || end < 0 || start > end)
                    throw new IllegalArgumentException("Invalid seed range for " + paramName + ": " + part);

                seeds = IntervalSet.concat(seeds, IntervalSet.rangeClosed(start, end));
            } else {
                int seed = Integer.parseInt(part);
                if (seed < 0)
                    throw new IllegalArgumentException("Invalid seed value for " + paramName + ": " + part);
                seeds = IntervalSet.concat(seeds, IntervalSet.of(seed));
            }
        }
        return seeds;
    }

    private List<int[]> parseVessels(String value) {
        List<int[]> tuples = new ArrayList<>();
        value = value.replaceAll("\\s+", "");
        String[] tupleStrings = value.split(",(?![^()]*\\))");

        for (String tupleStr : tupleStrings) {
            if (!tupleStr.startsWith("(") || !tupleStr.endsWith(")")) {
                throw new IllegalArgumentException("Invalid vessel tuple format: " + tupleStr);
            }
            String[] parts = tupleStr.substring(1, tupleStr.length() - 1).split(",");
            if (parts.length != 3) {
                throw new IllegalArgumentException("Vessel tuple must contain exactly 3 values: " + tupleStr);
            }
            int[] tuple = new int[3];
            for (int i = 0; i < 3; i++) {
                tuple[i] = parseInt(parts[i], "vessels");
            }
            tuples.add(tuple);
        }
        return tuples;
    }

    private void checkNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException("Negative value for " + name + ": " + value);
        }
    }

    private void checkRange(int value, int min, int max, String name) {
        if (value < min || value > max) {
            throw new IllegalArgumentException("Value for " + name + " out of range [" + min + ", " + max + "]: " + value);
        }
    }

    private void checkLongRange(long value, long min, long max, String name) {
        if (value < min || value > max) {
            throw new IllegalArgumentException("Value for " + name + " out of range [" + min + ", " + max + "]: " + value);
        }
    }
}
