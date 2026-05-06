package main;

import entity.Instance;
import entity.Solution;
import entity.Subblock;
import entity.VesselPeriod;
import ilog.concert.IloException;
import ilog.cplex.IloCplex;
import solver.CplexFixedSubblockModel;
import solver.CplexOriginalModel;
import solver.DecomposedNeighborhoodSearch;
import util.CplexSession;
import util.RunContext;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.*;

public class Runner {

    public static Solution solveCplexIntegratedModel(Instance instance, Params params, RunContext context, String lpFileName) {
        Solution solution;
        try (CplexSession session = new CplexSession(params, context)) {
            IloCplex cplex = session.getCplex();
            cplex.setOut(LOG_STREAM.get());

            if (context.shouldStop()) {
                LOG_STREAM.get().println("[RunContext] Stop before building integrated model.");
                return null;
            }

            CplexOriginalModel model = CplexOriginalModel.buildCompactIntegratedModel(
                    instance, cplex, params.exportLp ? lpFileName : null);
            model.varLoadOverload.setUB(0);
            model.varUnloadOverload.setUB(0);
            model.exportModelIfRequested();
            if (params.timeLimit != null)
                cplex.setParam(IloCplex.IntParam.TimeLimit, params.timeLimit);
            if (params.threads != null)
                cplex.setParam(IloCplex.Param.Threads, params.threads);
//            cplex.setParam(IloCplex.Param.Emphasis.Memory, true);

            if (context.shouldStop()) {
                LOG_STREAM.get().println("[RunContext] Stop before solving integrated model.");
                return null;
            }

            boolean solved = model.solve();
            LOG_STREAM.get().println("CPLEX status = " + cplex.getStatus());
            LOG_STREAM.get().println("CPLEX solve returned = " + solved);

            try {
                LOG_STREAM.get().println("Best bound = " + cplex.getBestObjValue());
                LOG_STREAM.get().println("Relative gap = " + cplex.getMIPRelativeGap());
            } catch (IloException ignored) {
                LOG_STREAM.get().println("No valid MIP bound/gap available.");
            }

            if (solved) {
                LOG_STREAM.get().println("Solved by CPLEX: Objective=" + cplex.getObjValue() +
                        " [route=" + cplex.getValue(model.objRoute) +
                        ", time=" + cplex.getValue(model.objTime) +
                        ", congestion=" + cplex.getValue(model.objCongestion) + "]");
                solution = model.getSolution();
            } else {
                LOG_STREAM.get().println("No feasible solution returned by CPLEX.");
                solution = null;
            }


        } catch (IloException e) {
            e.printStackTrace(LOG_STREAM.get());
            throw new RuntimeException(e);
        }
        return solution;
    }

    public static Solution solveSequentialDecision(Instance instance, Params params, RunContext context) {
        Solution solution;

        long startTime = System.currentTimeMillis();
        Map<VesselPeriod, Map<Subblock, Map<VesselPeriod, Integer>>> containerAssignment;
        try (CplexSession session = new CplexSession(params, context)) {
            IloCplex cplex = session.getCplex();
            cplex.setOut(LOG_STREAM.get());

            CplexOriginalModel model = CplexOriginalModel.buildYardTemplateStorageAllocationModel(instance, cplex);
            if (params.timeLimit != null) {
                int timeLimit = params.timeLimit / 2;
                if (timeLimit == 0) return null;
                cplex.setParam(IloCplex.IntParam.TimeLimit, timeLimit);
            }
            if (params.threads != null)
                cplex.setParam(IloCplex.Param.Threads, params.threads);
            cplex.setParam(IloCplex.Param.Emphasis.Memory, true);
//            cplex.setParam(IloCplex.Param.MIP.Display, 1);

            model.setPriorityOnY();

            if (model.solve()) {
                LOG_STREAM.get().println("Auxiliary Master Problem: Solved by CPLEX with Objective=" + cplex.getObjValue() +
                        " [route=" + cplex.getValue(model.objRoute) + "]");
                containerAssignment = model.getContainerAssignment();
            } else {
                LOG_STREAM.get().println("Auxiliary Master Problem: Found No TemporarySolution.");
                return null;
            }
        } catch (IloException e) {
            e.printStackTrace(LOG_STREAM.get());
            throw new RuntimeException(e);
        }

        try (CplexSession session = new CplexSession(params, context)) {
            IloCplex cplex = session.getCplex();
            cplex.setOut(LOG_STREAM.get());

            CplexFixedSubblockModel model = new CplexFixedSubblockModel(instance, cplex);
            if (params.timeLimit != null) {
                long timeLimit = params.timeLimit - (System.currentTimeMillis() - startTime) / 1000;
                cplex.setParam(IloCplex.IntParam.TimeLimit, timeLimit);
            }
            if (params.threads != null)
                cplex.setParam(IloCplex.Param.Threads, params.threads);
            cplex.setParam(IloCplex.Param.Emphasis.Memory, true);

            solution = model.solveSP2WithSolution(containerAssignment);
            if (solution != null) {
                solution.calculateObjectives();
                LOG_STREAM.get().println("Sub Problem Solved by CPLEX: Objective=" + cplex.getObjValue() +
                        " [time=" + cplex.getValue(model.objTime) +
                        ", congestion=" + cplex.getValue(model.objCongestion) + "]");
            }


        } catch (IloException e) {
            e.printStackTrace(LOG_STREAM.get());
            throw new RuntimeException(e);
        }

        return solution;
    }

    public static Solution solveRepeatedlyMasterHeuristicIntegratedSubproblemCplex(Instance instance, Params params) {
        DecomposedNeighborhoodSearch searcher = new DecomposedNeighborhoodSearch(instance);
        searcher.setCplexParams(params.timeLimit, params.threads);
        searcher.NEIGHBOR_LIMIT = 0;
        searcher.MAX_EXPLORED_SOLUTION = 0;
        searcher.MAX_NO_BEST_ITERATIONS = 10;
        searcher.MAX_NO_IMPROVED_ITERATIONS = 10;

        searcher.SHAKING_TIMES = 10;
        searcher.NUMBER_CRITICAL_ELEMENTS = instance.getNumVesselPeriods();
        searcher.KEEP_CRITICAL_ELEMENT_ORDER = false;
        searcher.MAX_TABU_SIZE = 0;
        searcher.MAX_SHAKE_ATTEMPTS = 1;

        searcher.setSeed(new Random(0));

        searcher.out = LOG_STREAM.get();
        searcher.newSearch();
        LOG_STREAM.get().println(searcher.searchProcessSummary() + searcher.resultSummary());
        return searcher.getBestSolution();
    }


    public static Solution solveMasterHeuristicIntegratedSubproblemCplex(Instance instance, Params params) {
        DecomposedNeighborhoodSearch searcher = new DecomposedNeighborhoodSearch(instance);
        searcher.setCplexParams(params.timeLimit, params.threads);
        searcher.NEIGHBOR_LIMIT = 0;
        searcher.MAX_EXPLORED_SOLUTION = 0;

        searcher.SHAKING_TIMES = 0;

        searcher.out = LOG_STREAM.get();
        searcher.newSearch();
        LOG_STREAM.get().println(searcher.searchProcessSummary() + searcher.resultSummary());
        return searcher.getBestSolution();
    }

    public static Solution solveDecomposedSearch(Instance instance, Params params) {
        DecomposedNeighborhoodSearch searcher = new DecomposedNeighborhoodSearch(instance);
        searcher.setCplexParams(params.timeLimit, params.threads);

        searcher.SHAKING_TIMES = 10;
        searcher.NEIGHBOR_LIMIT = Math.min(instance.getNumVesselPeriods() * 5 * instance.getNumSubblocks(), 500);
        searcher.MAX_NO_BEST_ITERATIONS = 30;
        searcher.MAX_NO_IMPROVED_ITERATIONS = 3;
        searcher.MAX_EXPLORED_SOLUTION = searcher.NEIGHBOR_LIMIT * searcher.MAX_NO_BEST_ITERATIONS / 2;

        searcher.meetBestAndBreak = true;
        searcher.meetImprovedAndBreak = true;

        searcher.out = LOG_STREAM.get();
        searcher.setSeed(new Random(0));

        searcher.newSearch();

        LOG_STREAM.get().println(searcher.searchProcessSummary() + searcher.resultSummary());
        return searcher.getBestSolution();

    }

    public static Solution solveDecomposedOldSearch(Instance instance, Params params) {
        DecomposedNeighborhoodSearch searcher = new DecomposedNeighborhoodSearch(instance);
        searcher.setCplexParams(params.timeLimit, params.threads);

        searcher.SHAKING_TIMES = 10;
        searcher.NEIGHBOR_LIMIT = Math.min(instance.getNumVesselPeriods() * 5 * instance.getNumSubblocks(), 500);
        searcher.MAX_NO_BEST_ITERATIONS = 30;
        searcher.MAX_NO_IMPROVED_ITERATIONS = 3;
        searcher.MAX_EXPLORED_SOLUTION = searcher.NEIGHBOR_LIMIT * searcher.MAX_NO_BEST_ITERATIONS / 2;

        searcher.meetBestAndBreak = true;
        searcher.meetImprovedAndBreak = true;

        searcher.out = LOG_STREAM.get();
        searcher.setSeed(new Random(0));

        searcher.newSearch();

        LOG_STREAM.get().println(searcher.searchProcessSummary() + searcher.resultSummary());
        return searcher.getBestSolution();

    }

    public static Solution solveDecomposedRandomSearch(Instance instance, Params params) {
        DecomposedNeighborhoodSearch searcher = new DecomposedNeighborhoodSearch(instance);
        searcher.setCplexParams(params.timeLimit, params.threads);

        searcher.SHAKING_TIMES = 10;
        searcher.NEIGHBOR_LIMIT = Math.min(instance.getNumVesselPeriods() * 5 * instance.getNumSubblocks(), 500);
        searcher.MAX_NO_BEST_ITERATIONS = 30;
        searcher.MAX_NO_IMPROVED_ITERATIONS = 3;
        searcher.MAX_EXPLORED_SOLUTION = searcher.NEIGHBOR_LIMIT * searcher.MAX_NO_BEST_ITERATIONS / 2;

        searcher.meetBestAndBreak = true;
        searcher.meetImprovedAndBreak = true;

        searcher.out = LOG_STREAM.get();
        searcher.setSeed(new Random(0));

        searcher.CRITICAL_NEIGHBORS = false;
        searcher.verboseBriefly = false;

        searcher.newSearch();

        LOG_STREAM.get().println(searcher.searchProcessSummary() + searcher.resultSummary());
        return searcher.getBestSolution();

    }

    public static Solution solveLocalRefinementSearch(Instance instance, Params params) {
        DecomposedNeighborhoodSearch searcher = new DecomposedNeighborhoodSearch(instance);
        searcher.setCplexParams(params.timeLimit, params.threads);
        searcher.SHAKING_TIMES = 10 * (Math.min(instance.getNumVesselPeriods() * 5 * instance.getNumSubblocks(), 500) * 15);
        searcher.NEIGHBOR_LIMIT = 0;
        searcher.MAX_NO_BEST_ITERATIONS = 0;
        searcher.MAX_NO_IMPROVED_ITERATIONS = 0;
        searcher.MAX_EXPLORED_SOLUTION = 0;

        searcher.meetBestAndBreak = false;
        searcher.meetImprovedAndBreak = false;

        searcher.out = LOG_STREAM.get();
        searcher.setSeed(new Random(0));

        searcher.LOCAL_REFINEMENT = true;

        searcher.newSearch();

        LOG_STREAM.get().println(searcher.searchProcessSummary() + searcher.resultSummary());
        return searcher.getBestSolution();

    }


    public static Solution solve(Instance instance, Params params, RunContext context) {
        return solve(instance, params, context, null);
    }

    public static Solution solve(Instance instance, Params params, RunContext context, String lpFileName) {

        Solution solution = switch (params.solver) {
            case CPLEX_INTEGRATED_MODEL -> solveCplexIntegratedModel(instance, params, context, lpFileName);
            case SEQUENTIAL_DECISION -> solveSequentialDecision(instance, params, context);
            case MASTER_HEURISTIC_INTEGRATED_SUBPROBLEM_CPLEX ->
                    solveMasterHeuristicIntegratedSubproblemCplex(instance, params);
            case REPEATEDLY_MASTER_HEURISTIC_INTEGRATED_SUBPROBLEM_CPLEX ->
                    solveRepeatedlyMasterHeuristicIntegratedSubproblemCplex(instance, params);
            case DECOMPOSED_NEIGHBORHOOD_SEARCH -> solveDecomposedSearch(instance, params);
            case DECOMPOSED_OLD_NEIGHBORHOOD_SEARCH -> solveDecomposedOldSearch(instance, params);
            case DECOMPOSED_RANDOM_SEARCH -> solveDecomposedRandomSearch(instance, params);
            case LOCAL_REFINEMENT_SEARCH -> solveLocalRefinementSearch(instance, params);
        };

        if (solution == null)
            LOG_STREAM.get().println("No solution found.");
        else {
            LOG_STREAM.get().println("TemporarySolution found: " + solution.briefObjectives());
        }
        return solution;
    }

    public static Solution solve(String filename, Params params, RunContext context) {
        Instance instance = Instance.readJson(filename);
        return solve(instance, params, context);
    }

    private static final ThreadLocal<PrintStream> LOG_STREAM =
            ThreadLocal.withInitial(() -> System.out);

    private static final Set<Process> ACTIVE_CHILD_PROCESSES = ConcurrentHashMap.newKeySet();
    private static volatile boolean childCleanupHookInstalled = false;


    public static void parallelRun(Params params) {
        ConcurrentHashMap<Params.VesselConfig, String> summaryMap = new ConcurrentHashMap<>();

        int divisor = params.threads != null ? params.threads : 4;
        int maxAffordable = Runtime.getRuntime().availableProcessors() / divisor - 1;
        int expected = params.processes != null ? Math.min(params.processes, maxAffordable) : maxAffordable;
        int threadCount = Math.min(params.configs.size(), expected);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (Params.VesselConfig config : params.configs) {
                futures.add(executor.submit(() -> processConfig(params, config, summaryMap)));
            }

            for (Future<?> future : futures) {
                future.get(7, TimeUnit.DAYS);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            executor.shutdown();

            StringBuilder orderedSummary = new StringBuilder();
            for (Params.VesselConfig config : params.configs) {
                String result = summaryMap.get(config);
                if (result != null) {
                    orderedSummary.append(result);
                }
            }

            System.out.println("-".repeat(40));
            System.out.println(orderedSummary);
            System.out.println("-".repeat(40));
        }
    }

    public static void launchBatch(Params params) {
        if (params.experiments == null || params.experiments.isEmpty()) {
            throw new IllegalArgumentException("No experiments to launch.");
        }
        installChildCleanupHook();

        int workerThreads = params.experiments.stream()
                .map(p -> p.threads)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(1);
        int defaultProcesses = Math.max(1, Runtime.getRuntime().availableProcessors() / Math.max(workerThreads, 1));
        int processCount = Math.min(params.experiments.size(),
                params.processes != null ? params.processes : defaultProcesses);

        LocalDateTime batchStart = LocalDateTime.now();
        String batchTimestamp = batchStart.format(dateTimeFormatter);
        String summaryFileName = outputPath(params, "log", "batch_" + batchTimestamp + "_summary.csv");
        File summaryFile = new File(summaryFileName);
        if (!summaryFile.getParentFile().exists()) {
            summaryFile.getParentFile().mkdirs();
        }

        System.out.printf("[Launcher] Experiments=%d, processes=%d, heap_mb=%s, fail_fast=%s%n",
                params.experiments.size(), processCount,
                params.heapMb == null ? "default" : params.heapMb,
                params.failFast);

        ExecutorService executor = Executors.newFixedThreadPool(processCount);
        CompletionService<BatchRunResult> completionService = new ExecutorCompletionService<>(executor);
        List<BatchRunResult> results = new ArrayList<>();
        int submitted = 0;
        int completed = 0;
        boolean stopSubmitting = false;

        try {
            while (submitted < processCount && submitted < params.experiments.size()) {
                int next = submitted;
                completionService.submit(() -> runChildJvm(params, params.experiments.get(next)));
                submitted++;
            }

            while (completed < submitted) {
                Future<BatchRunResult> future = completionService.take();
                BatchRunResult result = future.get();
                completed++;
                results.add(result);
                System.out.println(result.summaryLine());

                if (params.failFast && result.exitCode != 0) {
                    stopSubmitting = true;
                }

                if (!stopSubmitting && submitted < params.experiments.size()) {
                    int next = submitted;
                    completionService.submit(() -> runChildJvm(params, params.experiments.get(next)));
                    submitted++;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("[Launcher] Interrupted.");
        } catch (ExecutionException e) {
            System.err.println("[Launcher] Child task failed before producing a result: " + e.getMessage());
            e.printStackTrace();
        } finally {
            executor.shutdownNow();
            destroyActiveChildProcesses();
            writeBatchSummary(summaryFileName, results);
            System.out.println("[Launcher] Batch summary written to " + summaryFileName);
        }
    }

    private static BatchRunResult runChildJvm(Params launcherParams, Params experiment) {
        LocalDateTime start = LocalDateTime.now();
        List<String> command = buildChildCommand(launcherParams, experiment);
        System.out.println("[Launcher] Start " + experiment.briefName() + ": " + String.join(" ", command));

        int exitCode = -1;
        String error = "";
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(new File(System.getProperty("user.dir")));
            builder.inheritIO();

            Process process = builder.start();
            ACTIVE_CHILD_PROCESSES.add(process);
            try {
                exitCode = waitForChildProcess(process);
            } finally {
                ACTIVE_CHILD_PROCESSES.remove(process);
            }
        } catch (IOException e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error = "Interrupted";
        }

        LocalDateTime end = LocalDateTime.now();
        return new BatchRunResult(experiment.briefName(), experiment.solver.getName(),
                exitCode, start, end, String.join(" ", command), error);
    }

    private static List<String> buildChildCommand(Params launcherParams, Params experiment) {
        List<String> command = new ArrayList<>();
        String javaExe = System.getProperty("java.home") + File.separator + "bin" + File.separator +
                (System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java");

        command.add(javaExe);
        command.addAll(inheritableJvmOptions());
        if (launcherParams.heapMb != null) {
            command.add("-Xmx" + launcherParams.heapMb + "m");
        }
        String libraryPath = System.getProperty("java.library.path");
        if (libraryPath != null && !libraryPath.isBlank()) {
            command.add("-Djava.library.path=" + libraryPath);
        }
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(Runner.class.getName());
        command.add("parent_pid=" + ProcessHandle.current().pid());
        command.addAll(experiment.toWorkerArgs());
        return command;
    }

    private static List<String> inheritableJvmOptions() {
        List<String> options = new ArrayList<>();
        for (String option : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (shouldInheritJvmOption(option)) {
                options.add(option);
            }
        }
        return options;
    }

    private static boolean shouldInheritJvmOption(String option) {
        String lower = option.toLowerCase();
        return !lower.startsWith("-xmx")
                && !lower.startsWith("-xms")
                && !lower.startsWith("-xss")
                && !lower.startsWith("-xx:maxheapsize")
                && !lower.startsWith("-xx:initialheapsize")
                && !lower.startsWith("-agentlib")
                && !lower.startsWith("-agentpath")
                && !lower.startsWith("-javaagent")
                && !lower.startsWith("-xrun");
    }

    private static int waitForChildProcess(Process process) throws InterruptedException {
        while (true) {
            if (process.waitFor(1, TimeUnit.SECONDS)) {
                return process.exitValue();
            }
            if (Thread.currentThread().isInterrupted()) {
                destroyProcessTree(process);
                throw new InterruptedException("Interrupted while waiting for child JVM.");
            }
        }
    }

    private static synchronized void installChildCleanupHook() {
        if (childCleanupHookInstalled) {
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(Runner::destroyActiveChildProcesses, "apjor-child-cleanup"));
        childCleanupHookInstalled = true;
    }

    private static void destroyActiveChildProcesses() {
        for (Process process : ACTIVE_CHILD_PROCESSES) {
            destroyProcessTree(process);
        }
    }

    private static void destroyProcessTree(Process process) {
        ProcessHandle handle = process.toHandle();
        handle.descendants().forEach(ProcessHandle::destroy);
        handle.destroy();

        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                handle.descendants().forEach(ProcessHandle::destroyForcibly);
                handle.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handle.descendants().forEach(ProcessHandle::destroyForcibly);
            handle.destroyForcibly();
        }
    }

    private static void startParentWatcher(Long parentPid) {
        if (parentPid == null || parentPid <= 0) {
            return;
        }
        Thread watcher = new Thread(() -> {
            ProcessHandle parent = ProcessHandle.of(parentPid).orElse(null);
            if (parent == null) {
                return;
            }
            while (true) {
                if (!parent.isAlive()) {
                    System.err.println("[Worker] Parent launcher process " + parentPid + " is gone. Exiting worker.");
                    System.exit(130);
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "apjor-parent-watcher");
        watcher.setDaemon(true);
        watcher.start();
    }

    private static void writeBatchSummary(String summaryFileName, List<BatchRunResult> results) {
        try (PrintStream out = new PrintStream(new FileOutputStream(summaryFileName))) {
            out.println("experiment,solver,exit_code,start,end,running_seconds,error,command");
            for (BatchRunResult result : results) {
                out.println(result.csvLine());
            }
        } catch (FileNotFoundException e) {
            System.err.println("[Launcher] Cannot write batch summary: " + e.getMessage());
        }
    }

    private static String outputPath(Params params, String rootDir, String fileName) {
        if (params.batchName == null || params.batchName.isBlank()) {
            return rootDir + File.separator + fileName;
        }
        return rootDir + File.separator + params.batchName + File.separator + fileName;
    }

    private static class BatchRunResult {
        final String experiment;
        final String solver;
        final int exitCode;
        final LocalDateTime start;
        final LocalDateTime end;
        final String command;
        final String error;

        BatchRunResult(String experiment, String solver, int exitCode,
                       LocalDateTime start, LocalDateTime end, String command, String error) {
            this.experiment = experiment;
            this.solver = solver;
            this.exitCode = exitCode;
            this.start = start;
            this.end = end;
            this.command = command;
            this.error = error;
        }

        String summaryLine() {
            return String.format("[Launcher] Done %s exit=%d runningTime=%.2fs%s",
                    experiment, exitCode,
                    Duration.between(start, end).toMillis() / 1000.0,
                    error == null || error.isBlank() ? "" : " error=" + error);
        }

        String csvLine() {
            return String.join(",",
                    csv(experiment),
                    csv(solver),
                    Integer.toString(exitCode),
                    csv(start.toString()),
                    csv(end.toString()),
                    String.format("%.3f", Duration.between(start, end).toMillis() / 1000.0),
                    csv(error),
                    csv(command));
        }

        private static String csv(String value) {
            if (value == null) {
                value = "";
            }
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
    }

    public static boolean run(Params params) {
        ConcurrentHashMap<Params.VesselConfig, String> summaryMap = new ConcurrentHashMap<>();
        boolean success = true;

        try {
            for (Params.VesselConfig config : params.configs) {
                success &= processConfig(params, config, summaryMap);
            }
        } catch (Exception e) {
            success = false;
            System.out.println("[ERROR] Global error: " + e.getMessage());
            e.printStackTrace();
        } finally {

            StringBuilder orderedSummary = new StringBuilder();
            for (Params.VesselConfig config : params.configs) {
                String result = summaryMap.get(config);
                if (result != null) {
                    orderedSummary.append(result);
                }
            }

            System.out.println("-".repeat(40));
            System.out.println(orderedSummary);
            System.out.println("-".repeat(40));
        }
        return success;
    }

    private static Instance readInstance(Params.VesselConfig config) {
//        String instanceFile = String.format(
//                "input/" + Instance.DEFAULT_NAME_PATTERN + ".json",
//                config.small, config.medium, config.large, config.rows, config.cols, config.seed
//        );
//
//
//        return Instance.readJson(instanceFile);

        return InstanceGenerator.generate(config.small, config.medium, config.large, config.rows, config.cols, config.seed);
    }

    private static boolean processConfig(Params params, Params.VesselConfig config, Map<Params.VesselConfig, String> summaryMap) {
        LocalDateTime timestamp = LocalDateTime.now();

        String logFileName = outputPath(params, "log", String.format("config_%s_%s_%s.log",
                config.name, params.solver.getName(), timestamp.format(dateTimeFormatter)
        )
        );
        File logFile = new File(logFileName);
        if (!logFile.getParentFile().exists()) {
            logFile.getParentFile().mkdirs();
        }

        Instance instance = readInstance(config);


        try (PrintStream log = new PrintStream(new FileOutputStream(logFileName));
             RunContext context = new RunContext(params, log)) {
            LOG_STREAM.set(log);
            context.startMonitor();

            try {
                log.println("\nStart to solve instance " + config.name + " by " + params.solver.getName());
                log.println("[RunContext] Start: " + context.summary());

                String lpFileName = outputPath(params, "lp", String.format("config_%s_%s_%s.lp",
                        config.name, params.solver.getName(), timestamp.format(dateTimeFormatter)));
                File lpFile = new File(lpFileName);
                if (params.exportLp && !lpFile.getParentFile().exists()) {
                    lpFile.getParentFile().mkdirs();
                }

                Solution solution = solve(instance, params, context, lpFileName);

                log.println("[RunContext] End: " + context.summary());
                log.println("[RunContext] Stop reason = " + context.getStopReason());

                if (solution != null) {
                    solution.setStartTime(timestamp);
                    solution.setSolverName(params.solver.getName());
                    solution.setRunningTime(Duration.between(timestamp, LocalDateTime.now())
                            .toMillis() * 1. / 1000);

                    if (params.write) {
                        solution.write(outputPath(params, "output", String.format("solution_%s_%s_%s",
                                config.name, params.solver.getName(), timestamp.format(dateTimeFormatter)
                        )));
                    }
                }
                String result = formatSummaryLine(
                        config.small, config.medium, config.large,
                        config.rows, config.cols, config.seed, solution
                );

                System.out.println(result);
                log.println(result);
                summaryMap.put(config, result);
                return true;
            } catch (Exception e) {
                String errorMsg = String.format("[ERROR] Run failed: vessels=(%d, %d, %d), yard=(%d, %d), seed=%d%n" +
                                "Error: %s: %s%n",
                        config.small, config.medium, config.large,
                        config.rows, config.cols, config.seed,
                        e.getClass().getSimpleName(), e.getMessage());

                System.err.println(errorMsg);
                log.print(errorMsg);
                summaryMap.put(config, errorMsg);
                e.printStackTrace(log);
                e.printStackTrace();
                return false;
            } finally {
                LOG_STREAM.remove();
            }


        } catch (FileNotFoundException e) {
            e.printStackTrace();
            return false;
        }
    }


    private static final DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS");


    public static String formatSummaryLine(int small, int medium, int large,
                                           int rows, int cols, int seed,
                                           Solution solution) {
        if (solution != null)
            return String.format("Vessels=(%d, %d, %d), Yard=(%d, %d), Seed=%d: " +
                            "%s, " +
                            "runningTime=%.4fs%n",
                    small, medium, large, rows, cols, seed,
                    solution.briefObjectives(), solution.getRunningTime());
        else
            return String.format("Vessels=(%d, %d, %d), Yard=(%d, %d), Seed=%d: " +
                            "Failed%n",
                    small, medium, large, rows, cols, seed);
    }


    public static void main(String[] args) {
//        parallel=true
//        solver=decomposed
//        vessel=(8,0,4)
//        rows=4
//        seed=1-5
//        write=true
//        timelimit=3600
//        threads=4
        Params params = Params.parse(args);
        if (params.worker) {
            startParentWatcher(params.parentPid);
        }
        if (params.configFile != null && !params.worker)
            launchBatch(params);
        else if (params.parallel)
            parallelRun(params);
        else {
            boolean success = run(params);
            if (params.worker && !success) {
                System.exit(1);
            }
        }
    }


}
