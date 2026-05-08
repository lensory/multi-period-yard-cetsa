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
            "Usage: java main.Main [key=value...]\n" +
                    "Parameters (all optional, default values shown):\n" +
                    "  config      - Read batch configuration JSON\n" +
                    "  solver      - Solver type [cplex|flow_cplex|sequential|decomposed|local_refinement] (default: cplex)\n" +
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
                    "  cplex_threads - CPLEX thread count (default: no limit)\n" +
                    "  parallel_configs - concurrently running config experiments\n" +
                    "  heap        - launcher child JVM -Xmx value, size format like 512m or 8g\n" +
                    "  rss_limit   - RSS stop limit, size format like 4096m or 4g\n" +
                    "  work_mem    - CPLEX work memory, size format like 2048m or 2g\n" +
                    "  tree_mem    - CPLEX MIP tree memory, size format like 4096m or 4g\n\n" +
                    "Examples:\n" +
                    "  java main.Main solver=sequential small=3 medium=0 large=2 timelimit=1800\n" +
                    "  java main.Main seeds=1,3-5 write=true\n" +
                    "  java main.Main config=configs.json parallel_configs=4 heap=8g";

    public String configFile;
    public String batchName;
    public Integer parallelConfigs;
    public Integer heapMb;
    public List<Experiment> experiments;

    public static Params parse(String[] args) {
        try {
            Map<String, String> cliArgs = Experiment.parseArgMap(args);
            if (cliArgs.containsKey("config")) {
                return parseConfig(cliArgs);
            }
            return parseCliBatch(cliArgs);
        } catch (IllegalArgumentException e) {
            System.err.println("Parameter error: " + e.getMessage());
            System.err.println(USAGE);
            System.exit(1);
            return null;
        }
    }

    private static Params parseCliBatch(Map<String, String> cliArgs) {
        Params launcher = new Params();
        launcher.applyLauncherArgs(launcherArgs(cliArgs));
        launcher.experiments = new ArrayList<>();

        for (Map<String, String> experimentArgs : expandCliExperiments(cliArgs)) {
            ExperimentTemplate template = new ExperimentTemplate();
            template.applyArgs(experimentArgs);
            addConcreteExperiments(launcher.experiments, template);
        }

        if (launcher.experiments.isEmpty()) {
            throw new IllegalArgumentException("No experiments generated from command line");
        }
        for (Experiment experiment : launcher.experiments) {
            if (experiment.batchName == null || experiment.batchName.isBlank()) {
                experiment.batchName = launcher.batchName;
            }
        }
        launcher.validateLauncher();
        return launcher;
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
        Map<String, String> experimentCliOverrides = experimentCliOverrides(cliArgs);

        Params launcher = new Params();
        launcher.configFile = configFile;
        launcher.batchName = configBaseName(configFile);
        if (root.has("launcher")) {
            launcher.applyLauncherArgs(jsonObjectToArgs(root.get("launcher")));
        }
        launcher.applyLauncherArgs(launcherArgs(cliArgs));
        launcher.experiments = new ArrayList<>();

        boolean hasSweep = root.has("sweep") && root.get("sweep").isObject() && root.get("sweep").fieldNames().hasNext();
        boolean hasRuns = root.has("runs") && root.get("runs").isArray() && root.get("runs").size() > 0;

        if (hasSweep) {
            for (Map<String, String> sweepArgs : expandSweep(root.get("sweep"))) {
                ExperimentTemplate template = new ExperimentTemplate();
                template.batchName = launcher.batchName;
                template.applyArgs(defaultArgs);
                template.applyArgs(sweepArgs);
                template.applyArgs(experimentCliOverrides);
                addConcreteExperiments(launcher.experiments, template);
            }
        }

        if (hasRuns) {
            for (JsonNode runNode : root.get("runs")) {
                if (!runNode.isObject()) {
                    throw new IllegalArgumentException("Every item in runs must be a JSON object");
                }
                ExperimentTemplate template = new ExperimentTemplate();
                template.batchName = launcher.batchName;
                template.applyArgs(defaultArgs);
                template.applyArgs(jsonObjectToArgs(runNode));
                template.applyArgs(experimentCliOverrides);
                addConcreteExperiments(launcher.experiments, template);
            }
        }

        if (!hasSweep && !hasRuns) {
            ExperimentTemplate template = new ExperimentTemplate();
            template.batchName = launcher.batchName;
            template.applyArgs(defaultArgs);
            template.applyArgs(experimentCliOverrides);
            addConcreteExperiments(launcher.experiments, template);
        }

        if (launcher.experiments.isEmpty()) {
            throw new IllegalArgumentException("No experiments generated from config file");
        }
        launcher.validateLauncher();
        return launcher;
    }

    private void applyLauncherArgs(Map<String, String> args) {
        for (Map.Entry<String, String> entry : args.entrySet()) {
            String key = Experiment.normalizeKey(entry.getKey());
            String value = entry.getValue();
            switch (key) {
                case "config" -> this.configFile = value;
                case "batch_name" -> this.batchName = Experiment.sanitizeName(value);
                case "parallel_configs", "parallel_runs" -> this.parallelConfigs = Experiment.parseInt(value, key);
                case "heap", "xmx", "heap_mb", "xmx_mb" -> this.heapMb = Experiment.parseMemorySizeMb(value, key);
                default -> throw new IllegalArgumentException("Unknown launcher parameter: " + key);
            }
        }
    }

    private void validateLauncher() {
        if (parallelConfigs != null) {
            checkRange(parallelConfigs, 1, 1024, "parallel_configs");
        }
        if (heapMb != null) {
            checkRange(heapMb, 128, 1024 * 1024, "heap");
        }
    }

    private static Map<String, String> launcherArgs(Map<String, String> args) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : args.entrySet()) {
            String key = Experiment.normalizeKey(entry.getKey());
            if (isLauncherOnlyKey(key) || key.equals("config") || key.equals("batch_name")) {
                result.put(key, entry.getValue());
            }
        }
        return result;
    }

    private static Map<String, String> experimentCliOverrides(Map<String, String> cliArgs) {
        Map<String, String> overrides = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : cliArgs.entrySet()) {
            String key = Experiment.normalizeKey(entry.getKey());
            if (!isLauncherOnlyKey(key) && !key.equals("config") && !key.equals("batch_name")) {
                overrides.put(key, entry.getValue());
            }
        }
        return overrides;
    }

    private static boolean isLauncherOnlyKey(String key) {
        return key.equals("parallel_configs") || key.equals("parallel_runs") ||
                key.equals("heap") || key.equals("xmx") ||
                key.equals("heap_mb") || key.equals("xmx_mb");
    }

    private static List<Map<String, String>> expandCliExperiments(Map<String, String> args) {
        List<Map.Entry<String, List<String>>> dimensions = new ArrayList<>();
        for (Map.Entry<String, String> entry : args.entrySet()) {
            String key = Experiment.normalizeKey(entry.getKey());
            if (!isLauncherOnlyKey(key) && !key.equals("config") && !key.equals("batch_name")) {
                dimensions.add(Map.entry(key, expandCliValues(key, entry.getValue())));
            }
        }

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

    private static List<String> expandCliValues(String rawKey, String value) {
        String key = Experiment.normalizeKey(rawKey);
        if (key.equals("seed") || key.equals("seeds")) {
            List<String> values = new ArrayList<>();
            for (int seed : parseSeeds(value, rawKey)) {
                values.add(Integer.toString(seed));
            }
            return values;
        }
        return splitTopLevelComma(value);
    }

    private static List<Map<String, String>> expandSweep(JsonNode sweepNode) {
        List<Map.Entry<String, List<String>>> dimensions = new ArrayList<>();
        sweepNode.properties().forEach(entry ->
                dimensions.add(Map.entry(Experiment.normalizeKey(entry.getKey()), expandSweepValues(entry.getKey(), entry.getValue()))));

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
        String key = Experiment.normalizeKey(rawKey);
        if (key.equals("seed") || key.equals("seeds")) {
            List<String> values = new ArrayList<>();
            if (valueNode.isArray()) {
                for (JsonNode node : valueNode) {
                    for (int seed : parseSeeds(jsonValueToString(rawKey, node), rawKey)) {
                        values.add(Integer.toString(seed));
                    }
                }
            } else {
                for (int seed : parseSeeds(jsonValueToString(rawKey, valueNode), rawKey)) {
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
                args.put(Experiment.normalizeKey(entry.getKey()), jsonValueToString(entry.getKey(), entry.getValue())));
        return args;
    }

    private static String jsonValueToString(String rawKey, JsonNode node) {
        String key = Experiment.normalizeKey(rawKey);
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

    private static void addConcreteExperiments(List<Experiment> experiments, ExperimentTemplate template) {
        template.validateShape();
        List<int[]> vessels = template.vessels();
        List<Integer> seeds = template.seeds == null ? defaultSeeds() : template.seeds;
        int rows = Objects.requireNonNullElse(template.rows, 4);

        for (int[] tuple : vessels) {
            int cols = template.cols == null ? autoCols(tuple) : template.cols;
            for (int seed : seeds) {
                Experiment experiment = template.base.copySettings();
                if (experiment.batchName == null) {
                    experiment.batchName = template.batchName;
                }
                experiment.setInstanceKey(tuple[0], tuple[1], tuple[2], rows, cols, seed);
                experiment.validate();
                experiments.add(experiment);
            }
        }
    }

    private static int autoCols(int[] vesselTuple) {
        int total = vesselTuple[0] + vesselTuple[1] + vesselTuple[2];
        if (total % 3 != 0) {
            throw new IllegalArgumentException("Total vessel count must be divisible by 3 when cols is unspecified");
        }
        return total / 3;
    }

    private static List<int[]> defaultVessels() {
        return List.of(new int[]{2, 0, 1});
    }

    private static List<Integer> defaultSeeds() {
        List<Integer> seeds = new ArrayList<>();
        for (int seed : IntervalSet.rangeClosed(1, 5)) {
            seeds.add(seed);
        }
        return seeds;
    }

    private static String configBaseName(String configFile) {
        String name = new File(configFile).getName();
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        return Experiment.sanitizeName(name);
    }

    private static String vesselTupleToString(JsonNode node) {
        if (!node.isArray() || node.size() != 3) {
            throw new IllegalArgumentException("Vessel tuple must be an array with 3 integers: " + node);
        }
        return String.format("(%d,%d,%d)", node.get(0).asInt(), node.get(1).asInt(), node.get(2).asInt());
    }

    private static List<String> splitTopLevelComma(String value) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int parenDepth = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '(') {
                parenDepth++;
                current.append(c);
            } else if (c == ')') {
                parenDepth--;
                current.append(c);
            } else if (c == ',' && parenDepth == 0) {
                addSplitValue(values, current);
            } else {
                current.append(c);
            }
        }
        addSplitValue(values, current);
        return values;
    }

    private static void addSplitValue(List<String> values, StringBuilder current) {
        String value = current.toString().trim();
        if (!value.isEmpty()) {
            values.add(value);
        }
        current.setLength(0);
    }

    private static List<Integer> parseSeeds(String seedsStr, String paramName) {
        List<Integer> seeds = new ArrayList<>();
        for (String part : seedsStr.split(",")) {
            part = part.trim();
            if (part.isEmpty()) {
                continue;
            }
            if (part.contains("-")) {
                String[] range = part.split("-");
                int start = Experiment.parseInt(range[0], paramName);
                int end = Experiment.parseInt(range[1], paramName);
                if (start < 0 || end < 0 || start > end) {
                    throw new IllegalArgumentException("Invalid seed range for " + paramName + ": " + part);
                }
                for (int seed : IntervalSet.rangeClosed(start, end)) {
                    seeds.add(seed);
                }
            } else {
                int seed = Experiment.parseInt(part, paramName);
                if (seed < 0) {
                    throw new IllegalArgumentException("Invalid seed value for " + paramName + ": " + part);
                }
                seeds.add(seed);
            }
        }
        return seeds;
    }

    private static List<int[]> parseVessels(String value) {
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
                tuple[i] = Experiment.parseInt(parts[i], "vessels");
            }
            tuples.add(tuple);
        }
        return tuples;
    }

    private static void checkRange(int value, int min, int max, String name) {
        if (value < min || value > max) {
            throw new IllegalArgumentException("Value for " + name + " out of range [" + min + ", " + max + "]: " + value);
        }
    }

    private static class ExperimentTemplate {
        final Experiment base = new Experiment();
        List<int[]> vesselTuples;
        Integer small;
        Integer medium;
        Integer large;
        Integer rows;
        Integer cols;
        List<Integer> seeds;
        String batchName;

        void applyArgs(Map<String, String> args) {
            for (Map.Entry<String, String> entry : args.entrySet()) {
                applyArg(entry.getKey(), entry.getValue());
            }
        }

        void applyArg(String rawKey, String value) {
            String key = Experiment.normalizeKey(rawKey);
            switch (key) {
                case "batch_name" -> {
                    batchName = Experiment.sanitizeName(value);
                    base.batchName = batchName;
                }
                case "vessels", "vessel" -> vesselTuples = parseVessels(value);
                case "small" -> small = Experiment.parseInt(value, key);
                case "medium" -> medium = Experiment.parseInt(value, key);
                case "large" -> large = Experiment.parseInt(value, key);
                case "rows" -> rows = Experiment.parseInt(value, key);
                case "cols" -> cols = Experiment.parseInt(value, key);
                case "seed", "seeds" -> seeds = parseSeeds(value, key);
                default -> base.applyArg(key, value);
            }
            if (base.batchName == null && batchName != null) {
                base.batchName = batchName;
            }
        }

        List<int[]> vessels() {
            if (vesselTuples != null && !vesselTuples.isEmpty()) {
                return vesselTuples;
            }
            if (small != null || medium != null || large != null) {
                return List.of(new int[]{small, medium, large});
            }
            return defaultVessels();
        }

        void validateShape() {
            if (vesselTuples != null && (small != null || medium != null || large != null)) {
                throw new IllegalArgumentException("Cannot specify both 'vessels' and individual vessel counts");
            }
            if (small != null || medium != null || large != null) {
                Objects.requireNonNull(small, "small");
                Objects.requireNonNull(medium, "medium");
                Objects.requireNonNull(large, "large");
            }
        }
    }
}
