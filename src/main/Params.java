package main;

import util.IntervalSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class Params {
    public static final String USAGE =
            "Usage: java org.example.Runner [key=value...]\n" +
                    "Parameters (all optional, default values shown):\n" +
                    "  solver      - Solver type [cplex|sequential] (default: cplex)\n" +
                    "  vessel      - Number of vessels for different types (e.g., (2,0,1),(2,1,0) )" +
                    "  small       - Number of small vessels (default: 2)\n" +
                    "  medium      - Number of medium vessels (default: 0)\n" +
                    "  large       - Number of large vessels (default: 1)\n" +
                    "  rows        - Yard rows (default: 4)\n" +
                    "  cols        - Yard columns (auto-calculated if not specified)\n" +
                    "  seeds       - Random seed range (e.g. 1-5,7,9-11)\n" +
                    "  write       - Enable solution output [true|false] (default: false)\n" +
                    "  timelimit   - Solver time limit in seconds (default: no limit)\n" +
                    "  threads     - CPU thread count (default: no limit)\n" +
                    "  parallel    - indicator for parallel testing\n\n" +
                    "Examples:\n" +
                    "  java org.example.Runner solver=sequential small=3 large=2 timelimit=1800\n" +
                    "  java org.example.Runner seeds=1,3-5 write=true";


    public SolverType solver;
    public boolean write;
    public Integer timeLimit;
    public Integer threads;
    public boolean parallel;
    public Integer processes;

    public List<VesselConfig> configs;

    private List<int[]> vessels;
    private Integer small;
    private Integer medium;
    private Integer large;
    private Integer rows;
    private Integer cols;
    private IntervalSet seeds;

    public static class VesselConfig {
        int small;
        int medium;
        int large;
        int rows;
        int cols;
        int seed;

        String name;

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
        Params params = new Params();
        try {
            params.parseArgs(args);
            params.validate();
            params.autoFill();

        } catch (IllegalArgumentException e) {
            System.err.println("Parameter error: " + e.getMessage());
            System.err.println(USAGE);
            System.exit(1);
        }
        return params;
    }


    private void parseArgs(String[] args) {
        for (String arg : args) {
            String[] parts = arg.split("=", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid argument format: " + arg);
            }

            String key = parts[0].toLowerCase();
            String value = parts[1];

            switch (key) {
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
                case "timelimit" -> this.timeLimit = parseInt(value, key);
                case "threads" -> this.threads = parseInt(value, key);
                case "processes" -> this.processes = parseInt(value, key);
                default -> throw new IllegalArgumentException("Unknown parameter: " + key);
            }
        }


    }

    // 参数验证方法
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
    }

    // 默认值填充方法
    public void autoFill() {
        // Default parameters
        if (this.solver == null)
            this.solver = SolverType.CPLEX_INTEGRATED_MODEL;

        // default write = false.
        // default timeLimit, threads = no limit.


        if (this.seeds == null)
            this.seeds = IntervalSet.rangeClosed(1, 5);

        // generate configs
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

    // 辅助方法
    private int parseInt(String value, String paramName) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid integer value for " + paramName + ": " + value);
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
        value = value.replaceAll("\\s+", ""); // 去除空格
        String[] tupleStrings = value.split(",(?![^()]*\\))"); // 按逗号分割（忽略括号内的逗号）

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
}