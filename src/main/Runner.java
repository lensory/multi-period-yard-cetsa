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

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.*;

public class Runner {

    public static Solution solveCplexIntegratedModel(Instance instance, Params params) {
        Solution solution;
        try (IloCplex cplex = new IloCplex()) {
            cplex.setOut(LOG_STREAM.get());

            CplexOriginalModel model = CplexOriginalModel.buildCompactIntegratedModel(instance, cplex);

//            model.cplex.exportModel("model.lp");
            if (params.timeLimit != null)
                cplex.setParam(IloCplex.IntParam.TimeLimit, params.timeLimit);
            if (params.threads != null)
                cplex.setParam(IloCplex.Param.Threads, params.threads);
//            cplex.setParam(IloCplex.Param.Emphasis.Memory, true);


            model.solve();

            LOG_STREAM.get().println("Solved by CPLEX: Objective=" + cplex.getObjValue() +
                    " [route=" + cplex.getValue(model.objRoute) +
                    ", time=" + cplex.getValue(model.objTime) +
                    ", congestion=" + cplex.getValue(model.objCongestion) + "]");
            solution = model.getSolution();


        } catch (IloException e) {
            e.printStackTrace(LOG_STREAM.get());
            throw new RuntimeException(e);
        }
        return solution;
    }

    public static Solution solveSequentialDecision(Instance instance, Params params) {
        Solution solution;

        long startTime = System.currentTimeMillis();
        Map<VesselPeriod, Map<Subblock, Map<VesselPeriod, Integer>>> containerAssignment;
        try (IloCplex cplex = new IloCplex()) {
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

        try (IloCplex cplex = new IloCplex()) {
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


    public static Solution solve(Instance instance, Params params) {

        Solution solution = switch (params.solver) {
            case CPLEX_INTEGRATED_MODEL -> solveCplexIntegratedModel(instance, params);
            case SEQUENTIAL_DECISION -> solveSequentialDecision(instance, params);
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

    public static Solution solve(String filename, Params params) {
        Instance instance = Instance.readJson(filename);
        return solve(instance, params);
    }

    private static final ThreadLocal<PrintStream> LOG_STREAM =
            ThreadLocal.withInitial(() -> System.out);


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

    public static void run(Params params) {
        ConcurrentHashMap<Params.VesselConfig, String> summaryMap = new ConcurrentHashMap<>();

        try {
            for (Params.VesselConfig config : params.configs) {
                processConfig(params, config, summaryMap);
            }
        } catch (Exception e) {
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

    private static void processConfig(Params params, Params.VesselConfig config, Map<Params.VesselConfig, String> summaryMap) {
        LocalDateTime timestamp = LocalDateTime.now();

        String logFileName = String.format("log/config_%s_%s_%s.log",
                config.name, params.solver.getName(), timestamp.format(dateTimeFormatter)
        );
        File logFile = new File(logFileName);
        if (!logFile.getParentFile().exists()) {
            logFile.getParentFile().mkdirs();
        }

        Instance instance = readInstance(config);


        try (PrintStream log = new PrintStream(new FileOutputStream(logFileName))) {
            LOG_STREAM.set(log);
            try {
                log.println("\nStart to solve instance " + config.name + " by " + params.solver.getName());
                Solution solution = solve(instance, params);
                if (solution != null) {
                    solution.setStartTime(timestamp);
                    solution.setSolverName(params.solver.getName());
                    solution.setRunningTime(Duration.between(timestamp, LocalDateTime.now())
                            .toMillis() * 1. / 1000);

                    if (params.write) {
                        solution.write(String.format("output/solution_%s_%s_%s",
                                config.name, params.solver.getName(), timestamp.format(dateTimeFormatter)
                        ));
                    }
                }
                String result = formatSummaryLine(
                        config.small, config.medium, config.large,
                        config.rows, config.cols, config.seed, solution
                );

                System.out.println(result);
                log.println(result);
                summaryMap.put(config, result);
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
            }

            LOG_STREAM.remove();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
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
        if (params.parallel)
            parallelRun(params);
        else
            run(params);
    }


}
