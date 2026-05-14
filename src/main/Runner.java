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
import solver.FlowBasedCplexOriginalModel;
import util.CplexSession;
import util.RunContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.File;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public class Runner {
    static {
        System.setProperty("logback.configurationFile",
                System.getProperty("logback.configurationFile", "logback.xml"));
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(Runner.class);

    public static Solution solveCplexIntegratedModel(Instance instance, Experiment experiment, RunContext context, String lpFileName) {
        Solution solution;
        try (CplexSession session = new CplexSession(experiment, context)) {
            IloCplex cplex = session.getCplex();

            if (context.shouldStop()) {
                LOGGER.info("Stop before building integrated model.");
                return null;
            }

            CplexOriginalModel model = CplexOriginalModel.buildCompactIntegratedModel(
                    instance, cplex, experiment.exportLp ? lpFileName : null);
            model.varLoadOverload.setUB(0);
            model.varUnloadOverload.setUB(0);
            model.exportModelIfRequested();
            if (experiment.timeLimit != null)
                cplex.setParam(IloCplex.IntParam.TimeLimit, experiment.timeLimit);
            if (experiment.cplexThreads != null)
                cplex.setParam(IloCplex.Param.Threads, experiment.cplexThreads);
//            cplex.setParam(IloCplex.Param.Emphasis.Memory, true);

            if (context.shouldStop()) {
                LOGGER.info("Stop before solving integrated model.");
                return null;
            }

            boolean solved = model.solve();
            LOGGER.info("CPLEX status = " + cplex.getStatus());
            LOGGER.info("CPLEX solve returned = " + solved);

            try {
                LOGGER.info("Best bound = " + cplex.getBestObjValue());
                LOGGER.info("Relative gap = " + cplex.getMIPRelativeGap());
            } catch (IloException ignored) {
                LOGGER.info("No valid MIP bound/gap available.");
            }

            if (solved) {
                LOGGER.info("Solved by CPLEX: Objective=" + cplex.getObjValue() +
                        " [route=" + cplex.getValue(model.objRoute) +
                        ", time=" + cplex.getValue(model.objTime) +
                        ", congestion=" + cplex.getValue(model.objCongestion) + "]");
                solution = model.getSolution();
            } else {
                LOGGER.info("No feasible solution returned by CPLEX.");
                solution = null;
            }


        } catch (IloException e) {
            LOGGER.error("Failed while solving CPLEX integrated model.", e);
            throw new RuntimeException(e);
        }
        return solution;
    }

    public static Solution solveFlowBasedCplexIntegratedModel(Instance instance, Experiment experiment, RunContext context,
                                                              String lpFileName) {
        Solution solution;
        try (CplexSession session = new CplexSession(experiment, context)) {
            IloCplex cplex = session.getCplex();

            if (context.shouldStop()) {
                LOGGER.info("Stop before building flow-based integrated model.");
                return null;
            }

            FlowBasedCplexOriginalModel model = FlowBasedCplexOriginalModel.buildIntegratedModel(
                    instance, cplex, experiment.exportLp || experiment.exportLpOnly ? lpFileName : null);
            model.varLoadOverload.setUB(0);
            model.varUnloadOverload.setUB(0);
            model.exportModelIfRequested();

            if (experiment.exportLpOnly) {
                LOGGER.info("Exported flow-based CPLEX LP only; skip solve because export_lp_only=true.");
                return null;
            }

            if (experiment.timeLimit != null)
                cplex.setParam(IloCplex.IntParam.TimeLimit, experiment.timeLimit);
            if (experiment.cplexThreads != null)
                cplex.setParam(IloCplex.Param.Threads, experiment.cplexThreads);

            if (context.shouldStop()) {
                LOGGER.info("Stop before solving flow-based integrated model.");
                return null;
            }

            boolean solved = model.solve();
            LOGGER.info("CPLEX status = " + cplex.getStatus());
            LOGGER.info("CPLEX solve returned = " + solved);

            try {
                LOGGER.info("Best bound = " + cplex.getBestObjValue());
                LOGGER.info("Relative gap = " + cplex.getMIPRelativeGap());
            } catch (IloException ignored) {
                LOGGER.info("No valid MIP bound/gap available.");
            }

            if (solved) {
                LOGGER.info("Solved by flow-based CPLEX: Objective=" + cplex.getObjValue() +
                        " [route=" + cplex.getValue(model.objRoute) +
                        ", time=" + cplex.getValue(model.objTime) +
                        ", congestion=" + cplex.getValue(model.objCongestion) + "]");
                solution = model.getSolution();
            } else {
                LOGGER.info("No feasible solution returned by flow-based CPLEX.");
                solution = null;
            }
        } catch (IloException e) {
            LOGGER.error("Failed while solving flow-based CPLEX integrated model.", e);
            throw new RuntimeException(e);
        }
        return solution;
    }

    public static Solution solveSequentialDecision(Instance instance, Experiment experiment, RunContext context) {
        Solution solution;

        long startTime = System.currentTimeMillis();
        Map<VesselPeriod, Map<Subblock, Map<VesselPeriod, Integer>>> containerAssignment;
        try (CplexSession session = new CplexSession(experiment, context)) {
            IloCplex cplex = session.getCplex();

            CplexOriginalModel model = CplexOriginalModel.buildYardTemplateStorageAllocationModel(instance, cplex);
            if (experiment.timeLimit != null) {
                int timeLimit = experiment.timeLimit / 2;
                if (timeLimit == 0) return null;
                cplex.setParam(IloCplex.IntParam.TimeLimit, timeLimit);
            }
            if (experiment.cplexThreads != null)
                cplex.setParam(IloCplex.Param.Threads, experiment.cplexThreads);
            cplex.setParam(IloCplex.Param.Emphasis.Memory, true);
//            cplex.setParam(IloCplex.Param.MIP.Display, 1);

            model.setPriorityOnY();

            if (model.solve()) {
                LOGGER.info("Auxiliary Master Problem: Solved by CPLEX with Objective=" + cplex.getObjValue() +
                        " [route=" + cplex.getValue(model.objRoute) + "]");
                containerAssignment = model.getContainerAssignment();
            } else {
                LOGGER.info("Auxiliary Master Problem: Found No TemporarySolution.");
                return null;
            }
        } catch (IloException e) {
            LOGGER.error("Failed while solving sequential decision master problem.", e);
            throw new RuntimeException(e);
        }

        try (CplexSession session = new CplexSession(experiment, context)) {
            IloCplex cplex = session.getCplex();

            CplexFixedSubblockModel model = new CplexFixedSubblockModel(instance, cplex);
            if (experiment.timeLimit != null) {
                long timeLimit = experiment.timeLimit - (System.currentTimeMillis() - startTime) / 1000;
                cplex.setParam(IloCplex.IntParam.TimeLimit, timeLimit);
            }
            if (experiment.cplexThreads != null)
                cplex.setParam(IloCplex.Param.Threads, experiment.cplexThreads);
            cplex.setParam(IloCplex.Param.Emphasis.Memory, true);

            solution = model.solveSP2WithSolution(containerAssignment);
            if (solution != null) {
                solution.calculateObjectives();
                LOGGER.info("Sub Problem Solved by CPLEX: Objective=" + cplex.getObjValue() +
                        " [time=" + cplex.getValue(model.objTime) +
                        ", congestion=" + cplex.getValue(model.objCongestion) + "]");
            }


        } catch (IloException e) {
            LOGGER.error("Failed while solving sequential decision sub problem.", e);
            throw new RuntimeException(e);
        }

        return solution;
    }

    public static Solution solveRepeatedlyMasterHeuristicIntegratedSubproblemCplex(Instance instance, Experiment experiment) {
        DecomposedNeighborhoodSearch searcher = new DecomposedNeighborhoodSearch(instance);
        searcher.setCplexParams(experiment.timeLimit, experiment.cplexThreads);
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

        searcher.newSearch();
        LOGGER.info(searcher.searchProcessSummary() + searcher.resultSummary());
        return searcher.getBestSolution();
    }


    public static Solution solveMasterHeuristicIntegratedSubproblemCplex(Instance instance, Experiment experiment) {
        DecomposedNeighborhoodSearch searcher = new DecomposedNeighborhoodSearch(instance);
        searcher.setCplexParams(experiment.timeLimit, experiment.cplexThreads);
        searcher.NEIGHBOR_LIMIT = 0;
        searcher.MAX_EXPLORED_SOLUTION = 0;

        searcher.SHAKING_TIMES = 0;

        searcher.newSearch();
        LOGGER.info(searcher.searchProcessSummary() + searcher.resultSummary());
        return searcher.getBestSolution();
    }

    public static Solution solveDecomposedSearch(Instance instance, Experiment experiment) {
        DecomposedNeighborhoodSearch searcher = new DecomposedNeighborhoodSearch(instance);
        searcher.setCplexParams(experiment.timeLimit, experiment.cplexThreads);

        searcher.SHAKING_TIMES = 10;
        searcher.NEIGHBOR_LIMIT = Math.min(instance.getNumVesselPeriods() * 5 * instance.getNumSubblocks(), 500);
        searcher.MAX_NO_BEST_ITERATIONS = 30;
        searcher.MAX_NO_IMPROVED_ITERATIONS = 3;
        searcher.MAX_EXPLORED_SOLUTION = searcher.NEIGHBOR_LIMIT * searcher.MAX_NO_BEST_ITERATIONS / 2;

        searcher.meetBestAndBreak = true;
        searcher.meetImprovedAndBreak = true;

        searcher.setSeed(new Random(0));

        searcher.newSearch();

        LOGGER.info(searcher.searchProcessSummary() + searcher.resultSummary());
        return searcher.getBestSolution();

    }

    public static Solution solveDecomposedOldSearch(Instance instance, Experiment experiment) {
        DecomposedNeighborhoodSearch searcher = new DecomposedNeighborhoodSearch(instance);
        searcher.setCplexParams(experiment.timeLimit, experiment.cplexThreads);

        searcher.SHAKING_TIMES = 10;
        searcher.NEIGHBOR_LIMIT = Math.min(instance.getNumVesselPeriods() * 5 * instance.getNumSubblocks(), 500);
        searcher.MAX_NO_BEST_ITERATIONS = 30;
        searcher.MAX_NO_IMPROVED_ITERATIONS = 3;
        searcher.MAX_EXPLORED_SOLUTION = searcher.NEIGHBOR_LIMIT * searcher.MAX_NO_BEST_ITERATIONS / 2;

        searcher.meetBestAndBreak = true;
        searcher.meetImprovedAndBreak = true;

        searcher.setSeed(new Random(0));

        searcher.newSearch();

        LOGGER.info(searcher.searchProcessSummary() + searcher.resultSummary());
        return searcher.getBestSolution();

    }

    public static Solution solveDecomposedRandomSearch(Instance instance, Experiment experiment) {
        DecomposedNeighborhoodSearch searcher = new DecomposedNeighborhoodSearch(instance);
        searcher.setCplexParams(experiment.timeLimit, experiment.cplexThreads);

        searcher.SHAKING_TIMES = 10;
        searcher.NEIGHBOR_LIMIT = Math.min(instance.getNumVesselPeriods() * 5 * instance.getNumSubblocks(), 500);
        searcher.MAX_NO_BEST_ITERATIONS = 30;
        searcher.MAX_NO_IMPROVED_ITERATIONS = 3;
        searcher.MAX_EXPLORED_SOLUTION = searcher.NEIGHBOR_LIMIT * searcher.MAX_NO_BEST_ITERATIONS / 2;

        searcher.meetBestAndBreak = true;
        searcher.meetImprovedAndBreak = true;

        searcher.setSeed(new Random(0));

        searcher.CRITICAL_NEIGHBORS = false;
        searcher.verboseBriefly = false;

        searcher.newSearch();

        LOGGER.info(searcher.searchProcessSummary() + searcher.resultSummary());
        return searcher.getBestSolution();

    }

    public static Solution solveLocalRefinementSearch(Instance instance, Experiment experiment) {
        DecomposedNeighborhoodSearch searcher = new DecomposedNeighborhoodSearch(instance);
        searcher.setCplexParams(experiment.timeLimit, experiment.cplexThreads);
        searcher.SHAKING_TIMES = 10 * (Math.min(instance.getNumVesselPeriods() * 5 * instance.getNumSubblocks(), 500) * 15);
        searcher.NEIGHBOR_LIMIT = 0;
        searcher.MAX_NO_BEST_ITERATIONS = 0;
        searcher.MAX_NO_IMPROVED_ITERATIONS = 0;
        searcher.MAX_EXPLORED_SOLUTION = 0;

        searcher.meetBestAndBreak = false;
        searcher.meetImprovedAndBreak = false;

        searcher.setSeed(new Random(0));

        searcher.LOCAL_REFINEMENT = true;

        searcher.newSearch();

        LOGGER.info(searcher.searchProcessSummary() + searcher.resultSummary());
        return searcher.getBestSolution();

    }


    public static Solution solve(Instance instance, Experiment experiment, RunContext context) {
        return solve(instance, experiment, context, null);
    }

    public static Solution solve(Instance instance, Experiment experiment, RunContext context, String lpFileName) {

        Solution solution = switch (experiment.solver) {
            case CPLEX_INTEGRATED_MODEL -> solveCplexIntegratedModel(instance, experiment, context, lpFileName);
            case FLOW_BASED_CPLEX_INTEGRATED_MODEL ->
                    solveFlowBasedCplexIntegratedModel(instance, experiment, context, lpFileName);
            case SEQUENTIAL_DECISION -> solveSequentialDecision(instance, experiment, context);
            case MASTER_HEURISTIC_INTEGRATED_SUBPROBLEM_CPLEX ->
                    solveMasterHeuristicIntegratedSubproblemCplex(instance, experiment);
            case REPEATEDLY_MASTER_HEURISTIC_INTEGRATED_SUBPROBLEM_CPLEX ->
                    solveRepeatedlyMasterHeuristicIntegratedSubproblemCplex(instance, experiment);
            case DECOMPOSED_NEIGHBORHOOD_SEARCH -> solveDecomposedSearch(instance, experiment);
            case DECOMPOSED_OLD_NEIGHBORHOOD_SEARCH -> solveDecomposedOldSearch(instance, experiment);
            case DECOMPOSED_RANDOM_SEARCH -> solveDecomposedRandomSearch(instance, experiment);
            case LOCAL_REFINEMENT_SEARCH -> solveLocalRefinementSearch(instance, experiment);
        };

        if (solution == null) {
            if (isFlowBasedExportLpOnly(experiment))
                LOGGER.info("No solution requested because export_lp_only=true.");
            else
                LOGGER.info("No solution found.");
        }
        else {
            LOGGER.info("TemporarySolution found: " + solution.briefObjectives());
        }
        return solution;
    }

    private static boolean isFlowBasedExportLpOnly(Experiment experiment) {
        return experiment.exportLpOnly && experiment.solver == SolverType.FLOW_BASED_CPLEX_INTEGRATED_MODEL;
    }

    public static Solution solve(String filename, Experiment experiment, RunContext context) {
        Instance instance = Instance.readJson(filename);
        return solve(instance, experiment, context);
    }

    static File runOutputDir(Experiment experiment, LocalDateTime timestamp) {
        String batchName = experiment.batchName == null || experiment.batchName.isBlank()
                ? "cli"
                : Experiment.sanitizeName(experiment.batchName);
        String dirName = String.format("%02d-%02d-%02d_%02d-%02d_%02d_%s_%s_%s",
                experiment.small, experiment.medium, experiment.large,
                experiment.rows, experiment.cols, experiment.seed,
                Experiment.sanitizeName(experiment.solver.getName()),
                timestamp.format(dateTimeFormatter),
                conflictSuffix());
        return new File(new File("output", batchName), dirName);
    }

    private static String conflictSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private static Instance readInstance(Experiment experiment) {
//        String instanceFile = String.format(
//                "input/" + Instance.DEFAULT_NAME_PATTERN + ".json",
//                experiment.small, experiment.medium, experiment.large, experiment.rows, experiment.cols, experiment.seed
//        );
//
//
//        return Instance.readJson(instanceFile);

        return InstanceGenerator.generate(experiment.small, experiment.medium, experiment.large,
                experiment.rows, experiment.cols, experiment.seed);
    }

    public static boolean runExperiment(Experiment experiment) {
        LocalDateTime timestamp = LocalDateTime.now();

        File runDir = experiment.runOutputDir == null || experiment.runOutputDir.isBlank()
                ? runOutputDir(experiment, timestamp)
                : new File(experiment.runOutputDir);
        if (!runDir.exists() && !runDir.mkdirs()) {
            throw new RuntimeException("Cannot create experiment output directory: " + runDir);
        }

        MDC.put("runOutputDir", runDir.getPath());
        try (RunContext context = new RunContext(experiment, LOGGER)) {
            context.startMonitor();

            try {
                Instance instance = readInstance(experiment);
                LOGGER.info("");
                LOGGER.info("Start to solve instance {} by {}", experiment.name(), experiment.solver.getName());
                LOGGER.info("Experiment output directory: {}", runDir.getPath());
                LOGGER.info("Run context start: {}", context.summary());
                LOGGER.info("Run context config: {}", context.memoryConfigSummary());

                String lpFileName = new File(runDir, "model.lp").getPath();

                Solution solution = solve(instance, experiment, context, lpFileName);

                LOGGER.info("Run context end: {}", context.summary());
                LOGGER.info("Stop reason = {}", context.getStopReason());

                if (solution != null) {
                    solution.setStartTime(timestamp);
                    solution.setSolverName(experiment.solver.getName());
                    solution.setRunningTime(Duration.between(timestamp, LocalDateTime.now())
                            .toMillis() * 1. / 1000);

                    if (experiment.write) {
                        solution.write(runDir.getPath());
                    }
                }
                String result = isFlowBasedExportLpOnly(experiment)
                        ? formatExportOnlySummaryLine(experiment.small, experiment.medium, experiment.large,
                        experiment.rows, experiment.cols, experiment.seed)
                        : formatSummaryLine(
                        experiment.small, experiment.medium, experiment.large,
                        experiment.rows, experiment.cols, experiment.seed, solution
                );

                LOGGER.info(result.stripTrailing());
                return true;
            } catch (Exception e) {
                String errorMsg = String.format("Run failed: vessels=(%d, %d, %d), yard=(%d, %d), seed=%d%n" +
                                "%s: %s%n",
                        experiment.small, experiment.medium, experiment.large,
                        experiment.rows, experiment.cols, experiment.seed,
                        e.getClass().getSimpleName(), e.getMessage());

                LOGGER.error(errorMsg.stripTrailing(), e);
                return false;
            } finally {
                MDC.remove("runOutputDir");
            }


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

    public static String formatExportOnlySummaryLine(int small, int medium, int large,
                                                     int rows, int cols, int seed) {
        return String.format("Vessels=(%d, %d, %d), Yard=(%d, %d), Seed=%d: " +
                        "LP exported, solve skipped%n",
                small, medium, large, rows, cols, seed);
    }


    public static void main(String[] args) {
//        solver=decomposed
//        vessel=(8,0,4)
//        rows=4
//        seed=1-5
//        write=true
//        timelimit=3600
//        cplex_threads=4
        Map<String, String> argMap = Experiment.parseArgMap(args);
        Experiment experiment = Experiment.parseWorkerArgs(argMap);
        boolean success = runExperiment(experiment);
        if (!success) {
            System.exit(1);
        }
    }


}
