package solver;

import entity.*;
import ilog.concert.IloException;
import ilog.cplex.IloCplex;
import util.CapacityLimitedMapPriorityQueue;

import java.io.PrintStream;
import java.util.*;
import java.util.stream.Collectors;

public class DecomposedNeighborhoodSearch {
    public PrintStream out = System.out;

    public int NEIGHBOR_LIMIT = 20;
    public int MAX_NO_BEST_ITERATIONS = 30;
    public int MAX_NO_IMPROVED_ITERATIONS = 10;
    public int MAX_EXPLORED_SOLUTION = 2000;

    public int NUMBER_CRITICAL_ELEMENTS;
    public int SHAKING_TIMES = 0;
    public boolean KEEP_CRITICAL_ELEMENT_ORDER = true;
    public int MAX_TABU_SIZE = 100;
    public int MAX_SHAKE_ATTEMPTS = 20;


    public boolean verbose = true;
    public boolean verboseBriefly = true;
    public boolean verboseLog = true;
    public boolean meetBestAndBreak = false;
    public boolean meetImprovedAndBreak = false;


    private Instance instance;
    private long startTime; // 已存在实例变量
    private Integer timeLimit;
    private long expectedEndTime;
    private Integer threads;

    private double PRECISION = 1e-8;

    private LinkedHashSet<List<VesselPeriod>> tabuPriority = new LinkedHashSet<>();

    private final MasterYardTemplateHeuristic heuristic;
    private final Map<VesselPeriod, Set<VesselPeriod>> conflictPeriods;


    private Solution initialSolution;
    private StringBuilder briefLog = new StringBuilder();

//    private List<VesselPeriod> bestPriority;
//    private Map<VesselPeriod, Map<Subblock, Double>> bestCosts;
//    private Map<VesselPeriod, List<Subblock>> bestPreference;


    private List<VesselPeriod> currentPriority;
    private Map<VesselPeriod, Map<Subblock, Double>> currentCosts;
    //    private Map<VesselPeriod, List<Subblock>> currentPreference;


    // Update in the neighborhood search
    private Map<VesselPeriod, Set<Subblock>> bestAssignment;
    private Solution bestSolution;
    private Map<VesselPeriod, Set<Subblock>> currentAssignment;
    private Solution currentSolution;

    private IloCplex cplex;

    private Random rand;

    public DecomposedNeighborhoodSearch(Instance instance) {
        this.instance = instance;
        heuristic = new MasterYardTemplateHeuristic(instance);
        conflictPeriods = heuristic.conflictPeriods;
        NUMBER_CRITICAL_ELEMENTS = Math.max(instance.getNumVesselPeriods() / 3, 1);
        rand = new Random();

    }

    public void setSeed(Random seed) {
        this.rand = seed;
    }

    public void setCplexParams(Integer timeLimit, Integer threads) {
        this.timeLimit = timeLimit;
        this.threads = threads;
    }


//    private void recordBestPriority(List<VesselPeriod> newPriority) {
//        bestPriority = new ArrayList<>(newPriority);
//    }

//    private void recordBestCosts(Map<VesselPeriod, Map<Subblock, Double>> newCosts) {
//        bestCosts = newCosts.entrySet().stream()
//                .collect(Collectors.toMap(
//                        Map.Entry::getKey,
//                        e -> new HashMap<>(e.getValue()) // 第二层Map拷贝
//                ));
//    }

    private void recordBestSolutionIfNecessary(Map<VesselPeriod, Set<Subblock>> newAssignment, Solution newSolution) {
        Objects.requireNonNull(newSolution, "new solution");
        Objects.requireNonNull(newAssignment, "new assignment");
        if (bestSolution == null || newSolution.getObjAll() < bestSolution.getObjAll() - PRECISION) {
            bestSolution = newSolution;
            bestAssignment = newAssignment.entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            e -> new HashSet<>(e.getValue())
                    ));
        }

    }


//    private void recordBestPreference(Map<VesselPeriod, List<Subblock>> newPreference) {
//        bestPreference = newPreference.entrySet().stream()
//                .collect(Collectors.toMap(
//                        Map.Entry::getKey,
//                        e -> new ArrayList<>(e.getValue()) // 复制子列表
//                ));
//    }


//    public void neighborhoodSearch() {
//        TemporarySolution previousBestSolution = bestSolution;
//        if (verbose) {
//            out.println();
//            out.println("Neighborhood Search Start With Best TemporarySolution: " + bestSolution.briefObjectives());
//        }
//
//        if (verboseLog) {
//            briefLog.append("Neighborhood Search Start With Best TemporarySolution: ").append(bestSolution.briefObjectives()).append("\n");
//        }
//
//        int explored = 0;
//
//        int noBestFoundIteration = 0;
//        int noImprovedIteration = 0;
//
////        if (verboseLog) {
////            briefLog.append(explored).append("\tCurrent TemporarySolution: ").append(currentSolution.briefObjectives()).append("\n");
////        }
//
//        while (noBestFoundIteration < MAX_NO_BEST_ITERATIONS && noImprovedIteration < MAX_NO_IMPROVED_ITERATIONS
//                && explored < MAX_EXPLORED_SOLUTION
//                && (timeLimit == null || (System.currentTimeMillis() - startTime) / 1000 < timeLimit)) {

    /// /            if (verbose) {
    /// /                out.println(explored + "\tCurrent TemporarySolution : " + currentSolution.briefObjectives());
    /// /            }
    /// /            if (verboseLog) {
    /// /                briefLog.append(explored).append("\tCurrent TemporarySolution: ").append(currentSolution.briefObjectives()).append("\n");
    /// /            }
//            updateCurrentCostsIteratively(noBestFoundIteration);
//            boolean isImprovedFound = false;
//            boolean isBestFound = false;
//            boolean findFeasible = false;
//
//            List<Map<VesselPeriod, Set<Subblock>>> neighbors = generateLimitedNeighbors();
//
//
//            if (neighbors.isEmpty()) {
//                if (verbose)
//                    out.println("No promising neighbors.");
//                if (verboseLog) {
//                    briefLog.append("No promising neighbors.").append("\n");
//                }
//            }
//
//
//            for (Map<VesselPeriod, Set<Subblock>> neighborAssignment : neighbors) {
//                TemporarySolution neighborSolution = evaluateAssignment(neighborAssignment);
//                explored++;
//                if (neighborSolution != null) {
//                    findFeasible = true;
//                    Map<VesselPeriod, Map<Subblock, Double>> neighborCosts = estimateCosts(neighborAssignment, neighborSolution);
//
//                    if (neighborSolution.getObjAll() < bestSolution.getObjAll() - PRECISION) {
//                        if (verbose) {
//                            out.println(explored + "\t*** " + "Neighbor TemporarySolution: " + neighborSolution.briefObjectives());
//                        }
//                        if (verboseLog) {
//                            briefLog.append(explored).append("\t*** ").append("Neighbor TemporarySolution: ").append(neighborSolution.briefObjectives()).append("\n");
//                        }
//
//                        updateBestSolution(neighborAssignment, neighborSolution);
//                        updateCurrentSolution(neighborAssignment, neighborSolution);
//                        updateCurrentCostsAggressively(neighborCosts);
//
//                        isBestFound = true;
//                        isImprovedFound = true;
//                        if (meetBestAndBreak) break;
//                    } else if (neighborSolution.getObjAll() < currentSolution.getObjAll() - PRECISION) {
//                        if (verbose)
//                            out.println(explored + "\t+++ " + "Neighbor TemporarySolution: " + neighborSolution.briefObjectives());
//
//                        if (verboseLog) {
//                            briefLog.append(explored).append("\t+++ ").append("Neighbor TemporarySolution: ").append(neighborSolution.briefObjectives()).append("\n");
//                        }
//                        updateCurrentSolution(neighborAssignment, neighborSolution);
//                        updateCurrentCostsAverage(neighborCosts);
//                        isImprovedFound = true;
//                        if (meetImprovedAndBreak) break;
//
//                    } else {
//                        updateCurrentCostsAverage(neighborCosts);
//                        if (verbose && !verboseBriefly)
//                            out.println(explored + "\t--- " + "Neighbor TemporarySolution: " + neighborSolution.briefObjectives());
//                    }
//
//                }
//            }
//            if (!findFeasible) {
//                if (verbose)
//                    out.println(explored + "\t    " + "No Feasible TemporarySolution in Current TemporarySolution's Neighborhood (" + neighbors.size() + "[<=" + NEIGHBOR_LIMIT + "]).");
//                if (verboseLog) {
//                    briefLog.append(explored).append("\t    " + "No Feasible TemporarySolution in Current TemporarySolution's Neighborhood (").append(neighbors.size()).append("[<=").append(NEIGHBOR_LIMIT).append("]).").append("\n");
//                }
//                break;
//            }
//
//
//            noBestFoundIteration = isBestFound ? 0 : noBestFoundIteration + 1;
//            noImprovedIteration = isImprovedFound ? 0 : noImprovedIteration + 1;
//
//        }
//        if (verbose) {
//            out.println("Neighborhood Search Ends With Best TemporarySolution: " + bestSolution.briefObjectives());
//            out.println();
//        }
//        if (verboseLog) {
//            briefLog.append("Neighborhood Search Start With Best TemporarySolution: ").append(bestSolution.briefObjectives()).append("\n");
//            if (previousBestSolution != null)
//                briefLog.append("\t Improve ").append(100 * (previousBestSolution.getObjAll() - bestSolution.getObjAll()) / previousBestSolution.getObjAll())
//                        .append("% From: ").append(previousBestSolution.briefObjectives()).append("\n");
//
//        }
//
//    }
    private void setCurrentSolutionByHeuristic(List<VesselPeriod> priority, Map<VesselPeriod, Map<Subblock, Double>> costs) {
        currentPriority = deepCopyPriority(priority);
        currentCosts = deepCopyCost(costs);

        currentAssignment = heuristic.assignNeededSubblocksByCost(priority, costs);
        currentSolution = evaluateAssignment(currentAssignment);

        if (currentSolution != null) {
            if (verbose) {
                out.println("Initial TemporarySolution by Heuristic: " + currentSolution.briefObjectives());
            }
            if (verboseLog) {
                briefLog.append("Initial TemporarySolution by Heuristic: ").append(currentSolution.briefObjectives()).append("\n");
            }
        } else {
            if (verbose) {
                out.println("No Initial TemporarySolution Found by Heuristic");
            }
            if (verboseLog) {
                briefLog.append("No Initial TemporarySolution Found by Heuristic").append("\n");
            }
        }


        // Ensure that the initial assignment and solution are feasible.
        int shakes = 1;
        while (currentSolution == null && (timeLimit == null || (System.currentTimeMillis() - startTime) / 1000 < timeLimit)) {
            Collections.shuffle(currentPriority);
            currentAssignment = heuristic.assignNeededSubblocksByCost(priority, costs);
            currentSolution = evaluateAssignment(currentAssignment);


            if (currentSolution != null) {
                if (verbose) {
                    out.println("Shake " + shakes + ": Initial TemporarySolution by Heuristic: " + currentSolution.briefObjectives());
                }
                if (verboseLog) {
                    briefLog.append("Shake ").append(shakes).append(": Initial TemporarySolution by Heuristic: ").append(currentSolution.briefObjectives()).append("\n");
                }
            } else {
                if (verbose) {
                    out.println("Shake " + shakes + ": No Initial TemporarySolution Found by Heuristic");
                }
                if (verboseLog) {
                    briefLog.append("Shake ").append(shakes).append(": No Initial TemporarySolution Found by Heuristic").append("\n");
                }
            }
            shakes++;
        }


    }

    private List<VesselPeriod> deepCopyPriority(List<VesselPeriod> priority) {
        return new ArrayList<>(priority);
    }

    private final int MAX_HEURISTIC_RANDOM_ATTEMPTS = 1000;

    private void findInitialSolution(List<VesselPeriod> shakingPriority, Map<VesselPeriod, Map<Subblock, Double>> shakingCosts) {
        currentPriority = deepCopyPriority(shakingPriority);
        currentCosts = deepCopyCost(shakingCosts);

        currentAssignment = heuristic.assignNeededSubblocksByCost(shakingPriority, shakingCosts);
        currentSolution = evaluateAssignment(currentAssignment);

        if (verbose)
            out.println((currentSolution != null ? "Initial TemporarySolution by Heuristic: " + currentSolution.briefObjectives()
                    : ": No Initial TemporarySolution Found by Heuristic"));
        if (verboseLog)
            briefLog.append(currentSolution != null ? "Initial TemporarySolution by Heuristic: " + currentSolution.briefObjectives()
                    : ": No Initial TemporarySolution Found by Heuristic").append("\n");


        // Ensure that the initial assignment and solution are feasible.
        int shakes = 1;
        while (currentSolution == null && shakes <= MAX_HEURISTIC_RANDOM_ATTEMPTS && (timeLimit == null || (System.currentTimeMillis() - startTime) / 1000 < timeLimit)) {
            Collections.shuffle(currentPriority);
            currentAssignment = heuristic.assignNeededSubblocksByCost(shakingPriority, shakingCosts);
            currentSolution = evaluateAssignment(currentAssignment);

            if (verbose)
                out.println("Shake " + shakes + ": " + (currentSolution != null ? "Initial TemporarySolution by Heuristic: " + currentSolution.briefObjectives()
                        : ": No Initial TemporarySolution Found by Heuristic"));
            if (verboseLog)
                briefLog.append("Shake ").append(shakes).append(": ").append(currentSolution != null ? "Initial TemporarySolution by Heuristic: " + currentSolution.briefObjectives()
                        : ": No Initial TemporarySolution Found by Heuristic").append("\n");

            shakes++;
        }


        if (bestSolution == null || currentSolution.getObjAll() < bestSolution.getObjAll() - PRECISION) {
            bestSolution = currentSolution;
            bestAssignment = currentAssignment.entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            e -> new HashSet<>(e.getValue())
                    ));
        }
    }

    private Map<VesselPeriod, Set<Subblock>> deepCopyOfAssignment(Map<VesselPeriod, Set<Subblock>> assignment) {
        return assignment.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> new HashSet<>(e.getValue())
                ));
    }

    private static Solution solveWithTimeLimit(IndexedCplexFixedSubblockModel solver, long remainingTime) throws IloException {
        if (remainingTime <= 0) {
            return null;
        }
        solver.cplex.setParam(ilog.cplex.IloCplex.IntParam.TimeLimit, remainingTime);
        return solver.solve() ? solver.getIntegratedSolution() : null;
    }

    private static Solution solveWithoutTimeLimit(IndexedCplexFixedSubblockModel solver) throws IloException {
        return solver.solve() ? solver.getIntegratedSolution() : null;
    }

    public boolean CRITICAL_NEIGHBORS = true;

    public boolean NEIGHBORHOOD_SEARCH = true;

    public boolean LOCAL_REFINEMENT = false;


    private class ShakeManager {
        public Map<VesselPeriod, Map<Subblock, Double>> costs;
        public List<VesselPeriod> priority;

        public ShakeManager(Map<VesselPeriod, Map<Subblock, Double>> costs, List<VesselPeriod> priority) {
            // deep copy
            this.costs = costs.entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            entry -> entry.getValue().entrySet().stream()
                                    .collect(Collectors.toMap(
                                            Map.Entry::getKey,
                                            Map.Entry::getValue
                                    ))
                    ));
            this.priority = new ArrayList<>(priority);
        }
    }

    public void newSearch() {
        startTime = System.currentTimeMillis();
        int evaluatedSolutions = 0;

        try (IloCplex cplex = new IloCplex()) {

            cplex.setOut(null);
            cplex.setWarning(null);
            if (threads != null)
                cplex.setParam(IloCplex.Param.Threads, threads);
            cplex.setParam(IloCplex.Param.Emphasis.Memory, true);

            Map<VesselPeriod, Map<Subblock, Double>> shakingCosts = heuristic.getDistanceCostsByEqualStorage();
            List<VesselPeriod> shakingPriority = heuristic.getFirstCommeFirstServedPriority();


            for (int shakes = 0; shakes <= SHAKING_TIMES &&
                    (timeLimit == null || (System.currentTimeMillis() - startTime) / 1000 < timeLimit);
                 shakes++) {

                Solution previousBestSolution = bestSolution;

                IndexedCplexFixedSubblockModel solver = null;

                Map<VesselPeriod, Set<Subblock>> initialHeuristicAssignment = null;
                Solution initialHeuristicSolution = null;
                int heuristicAttempts = 0;
                while (initialHeuristicSolution == null && heuristicAttempts <= MAX_HEURISTIC_RANDOM_ATTEMPTS &&
                        (timeLimit == null || (System.currentTimeMillis() - startTime) / 1000 < timeLimit)) {
                    if (heuristicAttempts > 0)
                        Collections.shuffle(shakingPriority, rand);
                    initialHeuristicAssignment = heuristic.assignNeededSubblocksByCost(shakingPriority, shakingCosts);

                    if (initialHeuristicAssignment != null) {
                        solver = solver == null ? IndexedCplexFixedSubblockModel.buildIntegratedSubproblemModel(instance, cplex, initialHeuristicAssignment) :
                                solver.changeSubblockAssignmentTo(initialHeuristicAssignment);

                        if (timeLimit != null) {
                            long remainingTime = this.timeLimit - (System.currentTimeMillis() - startTime) / 1000;
                            initialHeuristicSolution = solveWithTimeLimit(solver, remainingTime);
                        } else {
                            initialHeuristicSolution = solveWithoutTimeLimit(solver);
                        }
                        evaluatedSolutions++;
                    }
                    if (verbose)
                        out.printf("Attempt %d: %s, Elapsed time = %.2f sec, Evaluated solutions = %d.%n", heuristicAttempts,
                                (initialHeuristicSolution != null ? "Initial TemporarySolution by Heuristic: " + initialHeuristicSolution.briefObjectives()
                                        : "No Initial TemporarySolution Found by Heuristic"),
                                (System.currentTimeMillis() - startTime) * 1. / 1000, evaluatedSolutions);
                    heuristicAttempts++;
                }

                if (initialHeuristicSolution == null)
                    continue;


                currentSolution = initialHeuristicSolution;
                currentAssignment = initialHeuristicAssignment;
                currentCosts = deepCopyCost(shakingCosts);

                if (initialSolution == null)
                    initialSolution = initialHeuristicSolution;


                recordBestSolutionIfNecessary(initialHeuristicAssignment, initialHeuristicSolution);


                if (NEIGHBORHOOD_SEARCH && currentSolution != null) {

                    int explored = 0;
                    int noBestFoundIteration = 0;
                    int noImprovedIteration = 0;

                    while (noBestFoundIteration < MAX_NO_BEST_ITERATIONS && noImprovedIteration < MAX_NO_IMPROVED_ITERATIONS
                            && explored < MAX_EXPLORED_SOLUTION
                            && (timeLimit == null || (System.currentTimeMillis() - startTime) / 1000 < timeLimit)) {

                        updateCurrentCostsIteratively(noBestFoundIteration);
                        boolean isImprovedFound = false;
                        boolean isBestFound = false;
                        boolean findFeasible = false;

                        List<Map<VesselPeriod, Set<Subblock>>> neighbors = CRITICAL_NEIGHBORS ?
                                generateLimitedNeighbors() : generateRandomNeighbors(currentAssignment);

                        if (verbose)
                            out.println(explored + "  Number of neighbors to be explored: " + neighbors.size());
                        for (Map<VesselPeriod, Set<Subblock>> neighborAssignment : neighbors) {
                            if (timeLimit != null && (System.currentTimeMillis() - startTime) / 1000 >= timeLimit)
                                break;

                            Solution neighborSolution;
                            solver.changeSubblockAssignmentTo(neighborAssignment);
                            if (timeLimit != null) {
                                long remainingTime = this.timeLimit - (System.currentTimeMillis() - startTime) / 1000;
                                neighborSolution = solveWithTimeLimit(solver, remainingTime);
                            } else {
                                neighborSolution = solveWithoutTimeLimit(solver);
                            }
                            evaluatedSolutions++;

                            explored++;
                            if (neighborSolution != null) {
                                findFeasible = true;
                                Map<VesselPeriod, Map<Subblock, Double>> neighborCosts = estimateCosts(neighborAssignment, neighborSolution);

                                if (neighborSolution.getObjAll() < bestSolution.getObjAll() - PRECISION) {
                                    if (verbose)
                                        out.printf("%d\t*** Neighbor TemporarySolution: %s, Elapsed time = %.2f sec, Evaluated solutions = %d.%n",
                                                explored, neighborSolution.briefObjectives(),
                                                (System.currentTimeMillis() - startTime) * 1. / 1000, evaluatedSolutions);

                                    updateBestSolution(neighborAssignment, neighborSolution);
                                    updateCurrentSolution(neighborAssignment, neighborSolution);
                                    updateCurrentCostsAggressively(neighborCosts);

                                    isBestFound = true;
                                    isImprovedFound = true;
                                    if (meetBestAndBreak) break;
                                } else if (neighborSolution.getObjAll() < currentSolution.getObjAll() - PRECISION) {
                                    if (verbose)
                                        out.printf("%d\t+++ Neighbor TemporarySolution: %s, Elapsed time = %.2f sec, Evaluated solutions = %d.%n",
                                                explored, neighborSolution.briefObjectives(),
                                                (System.currentTimeMillis() - startTime) * 1. / 1000, evaluatedSolutions);

                                    updateCurrentSolution(neighborAssignment, neighborSolution);
                                    updateCurrentCostsAverage(neighborCosts);
                                    isImprovedFound = true;
                                    if (meetImprovedAndBreak) break;

                                } else {
                                    updateCurrentCostsAverage(neighborCosts);
                                    if (verbose && !verboseBriefly)
                                        out.printf("%d\t--- Neighbor TemporarySolution: %s, Elapsed time = %.2f sec, Evaluated solutions = %d.%n",
                                                explored, neighborSolution.briefObjectives(),
                                                (System.currentTimeMillis() - startTime) * 1. / 1000, evaluatedSolutions);
                                }
                            }
                        }
                        if (!findFeasible) {
                            if (verbose)
                                out.println(explored + "\t    " + "No Feasible TemporarySolution in Current TemporarySolution's Neighborhood (" + neighbors.size() + "[<=" + NEIGHBOR_LIMIT + "]).");
                            break;
                        }
                        noBestFoundIteration = isBestFound ? 0 : noBestFoundIteration + 1;
                        noImprovedIteration = isImprovedFound ? 0 : noImprovedIteration + 1;
                    }
                    if (verbose) {
                        out.println("Neighborhood Search Ends With Best TemporarySolution: " + bestSolution.briefObjectives());
                        if (initialHeuristicSolution != null)
                            out.printf("\t Improve %f %% from %s\n\n", 100 * (initialHeuristicSolution.getObjAll() - bestSolution.getObjAll()) / initialHeuristicSolution.getObjAll(), initialHeuristicSolution.briefObjectives());
                    }
                }
                solver.cplex.clearModel();


                // local refinement
                if (LOCAL_REFINEMENT && currentSolution != null) {

                    IndexBasedSolution current = currentSolution.toIndexBasedSolution();
                    boolean optimizeGivenTimeAssignment = true;
                    boolean flag = true;

                    while (flag &&
                            (timeLimit == null || (System.currentTimeMillis() - startTime) / 1000 < timeLimit)) {
                        if (timeLimit != null)
                            cplex.setParam(IloCplex.IntParam.TimeLimit, timeLimit - (double) (System.currentTimeMillis() - startTime) / 1000);

                        IndexBasedSolution integratedSolution;

                        if (optimizeGivenTimeAssignment) {
                            IndexFormulationCplex model = IndexFormulationCplex.buildModelGivenTimeAssignment(instance, cplex, current);
                            if (model.solve())
                                integratedSolution = IndexBasedSolution.merge(model.getSolutionSubblockAssignment(), current);
                            else
                                integratedSolution = null;
                            optimizeGivenTimeAssignment = false;
                        } else {
                            IndexFormulationCplex model = IndexFormulationCplex.buildModelGivenSubblockAssignment(instance, cplex, current);
                            if (model.solve())
                                integratedSolution = IndexBasedSolution.merge(current, model.getSolutionOperationSchedule());
                            else
                                integratedSolution = null;
                            optimizeGivenTimeAssignment = true;
                        }
                        evaluatedSolutions++;
                        cplex.clearModel();
                        if (integratedSolution == null)
                            break;

                        if (integratedSolution.objAll < current.objAll - PRECISION)
                            current = integratedSolution;
                        else
                            flag = false;
                        if (verbose)
                            out.printf("%s\t+++ Local Refined TemporarySolution: %s, Elapsed time = %.2f sec, Evaluated solutions = %d.%n",
                                    optimizeGivenTimeAssignment ? "OptGivenT" : "OptGivenK", integratedSolution.briefObjectives(),
                                    (System.currentTimeMillis() - startTime) * 1. / 1000, evaluatedSolutions);

                    }

                    Solution refinedSolution = current.toSolution();
                    if (refinedSolution.getObjAll() < bestSolution.getObjAll() - PRECISION) {
                        updateBestSolution(refinedSolution.getSubblockAssignments(), refinedSolution);
                    }
                    if (verbose) {
                        out.println("Local Refinement Ends With Best TemporarySolution: " + bestSolution.briefObjectives());
                        out.printf("\t Improve %f %% from current solution(%s)\n\n", 100 * (currentSolution.getObjAll() - refinedSolution.getObjAll()) / currentSolution.getObjAll(), currentSolution.briefObjectives());
                    }
                    if (refinedSolution.getObjAll() < currentSolution.getObjAll() - PRECISION)
                        updateCurrentSolution(refinedSolution.getSubblockAssignments(), refinedSolution);

                    solver.cplex.clearModel();
                }


                if (verbose) {
                    out.println("Shake " + shakes + " Ends With: " + bestSolution.briefObjectives());
                    if (previousBestSolution != null)
                        out.printf("\t Improve %f %% from %s\n", 100 * (previousBestSolution.getObjAll() - bestSolution.getObjAll()) / previousBestSolution.getObjAll(),
                                previousBestSolution.briefObjectives());
                    out.printf("Elapsed Time = %.2f sec, Evaluated solutions = %d.%n",
                            (System.currentTimeMillis() - startTime) * 1. / 1000, evaluatedSolutions);
                    out.println("-".repeat(100));
                    out.println();
                }


                if (shakes <= SHAKING_TIMES && (timeLimit == null ||
                        (System.currentTimeMillis() - startTime) / 1000 < timeLimit)) {
                    Map<VesselPeriod, Set<Subblock>> referenceAssignment = previousBestSolution != bestSolution ? bestAssignment : currentAssignment;
                    Solution referenceSolution = previousBestSolution != bestSolution ? bestSolution : currentSolution;

                    Map<VesselPeriod, Map<Subblock, Double>> costs = estimateCosts(referenceAssignment, referenceSolution);

                    Map<VesselPeriod, Double> costsOfVp = costs.entrySet().stream()
                            .collect(Collectors.toMap(
                                    Map.Entry::getKey,
                                    entry -> entry.getValue().values().stream()
                                            .mapToDouble(Double::doubleValue).sum()
                                            / entry.getKey().totalLoadContainers
                            ));

                    shakingPriority = tabuCriticalShake(shakingPriority, costsOfVp);
                    costs.forEach((vp, subblockMap) ->
                            subblockMap.forEach((subblock, delta) ->
                                    shakingCosts.get(vp).merge(subblock, delta, (current, val) -> val)
                            )
                    );
                } else {
                    break;
                }
            }


        } catch (IloException e) {
            e.printStackTrace(out);
            throw new RuntimeException(e);
        }


    }


    private List<VesselPeriod> shakePriority(List<VesselPeriod> originalPriority, Map<VesselPeriod, Set<Subblock>> assignment, Solution solution) {
        Map<VesselPeriod, Map<Subblock, Double>> costs = estimateCosts(assignment, solution);

        // sum the costs of all assigned subblock for each vessel period. Then with the VesselPeriod->Double map,
        // get the list of VesselPeriods sorted by the costs (ascending order).

        Map<VesselPeriod, Double> costsOfVp = costs.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().values().stream()
                                .mapToDouble(Double::doubleValue).sum()
                                / entry.getKey().totalLoadContainers
                ));


//        return sortAllByCost(costsOfVp);
        return tabuCriticalShake(originalPriority, costsOfVp);
    }

    private List<VesselPeriod> tabuCriticalShake(List<VesselPeriod> originalPriority, Map<VesselPeriod, Double> costsOfVp) {
        List<VesselPeriod> criticalElements = originalPriority.stream()
                .sorted(Comparator.comparingDouble(vp -> -costsOfVp.getOrDefault(vp, 0.0)))
                .limit(NUMBER_CRITICAL_ELEMENTS)
                .collect(Collectors.toList());

        List<VesselPeriod> newPriority;
        int attempts = 0;
        boolean isUnique;

        do {
            newPriority = new ArrayList<>(originalPriority);
            newPriority.removeAll(criticalElements);
            if (!KEEP_CRITICAL_ELEMENT_ORDER)
                Collections.shuffle(criticalElements, rand);
            int insertPos = rand.nextInt(newPriority.size() + 1);
            newPriority.addAll(insertPos, criticalElements);

            isUnique = !tabuPriority.contains(newPriority);
            attempts++;

            if (attempts >= MAX_SHAKE_ATTEMPTS) {
                if (verbose) {
                    out.println("Reached the maximal shake attempts for new Priority (" + MAX_SHAKE_ATTEMPTS + ")");
                }
                break;
            }
        } while (!isUnique);

        List<VesselPeriod> tabuEntry = new ArrayList<>(newPriority);
        if (tabuPriority.size() >= MAX_TABU_SIZE) {
            Iterator<List<VesselPeriod>> it = tabuPriority.iterator();
            it.next();
            it.remove();
        }
        tabuPriority.add(tabuEntry);

        return newPriority;
    }

    private List<VesselPeriod> sortAllByCost(Map<VesselPeriod, Double> costsOfVp) {
//        tabuCriticalShake: MAX_ATTEMPT=1, MAX_TABU_SIZE=0,
//        KEEP_CRITICAL_ELEMENT_ORDER=true, NUMBER_CRITICAL_ELEMENTS=VesselPeriods.size()

        // Sorted ALL vessel-periods by costs
        return costsOfVp.entrySet().stream()
                .sorted(Collections.reverseOrder(Map.Entry.comparingByValue()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private List<VesselPeriod> criticalShake(List<VesselPeriod> originalPriority, Map<VesselPeriod, Double> costsOfVp) {
//        tabuCriticalShake: MAX_ATTEMPT=1, MAX_TABU_SIZE=0, KEEP_CRITICAL_ELEMENT_ORDER=false


        // Identify the critical elements (VesselPeriods top-N-highest costs)
        List<VesselPeriod> criticalElements = originalPriority.stream()
                .sorted(Comparator.comparingDouble(vp -> -costsOfVp.getOrDefault(vp, 0.0)))
                .limit(NUMBER_CRITICAL_ELEMENTS)
                .collect(Collectors.toList());
        List<VesselPeriod> newPriority = new ArrayList<>(originalPriority);
//        for (VesselPeriod criticalVp : criticalElements) {
//            newPriority.remove(criticalVp);
//            int newPosition = rand.nextInt(newPriority.size() + 1);
//            newPriority.add(newPosition, criticalVp);
//        }
        newPriority.removeAll(criticalElements);
        Collections.shuffle(criticalElements, rand);
        int insertPos = rand.nextInt(newPriority.size() + 1);
        newPriority.addAll(insertPos, criticalElements);
        return newPriority;
    }


    private Map<VesselPeriod, Map<Subblock, Double>> deepCopyCost(Map<VesselPeriod, Map<Subblock, Double>> costs) {
        return costs.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                e -> new HashMap<>(e.getValue())));
    }

    public Solution getBestSolution() {
        return bestSolution;
    }


    private void updateCurrentCostsIteratively(int iteration) {
        Map<VesselPeriod, Map<Subblock, Double>> costsFromCurrentSolution = estimateCosts(currentAssignment, currentSolution);

        int updateWeight = iteration + 2;
        costsFromCurrentSolution.forEach((vp, subblockMap) ->
                subblockMap.forEach((subblock, delta) ->
                        currentCosts.get(vp).merge(subblock, delta,
                                (current, val) -> current + (val - current) / updateWeight)
                )
        );
    }

    private void updateBestSolution(Map<VesselPeriod, Set<Subblock>> neighborAssignment, Solution neighborSolution) {
        bestSolution = neighborSolution;
        bestAssignment = neighborAssignment;
    }

    private void updateCurrentSolution(Map<VesselPeriod, Set<Subblock>> neighborAssignment, Solution neighborSolution) {
        currentAssignment = neighborAssignment;
        currentSolution = neighborSolution;
    }


    private void updateCurrentCostsAggressively(Map<VesselPeriod, Map<Subblock, Double>> neighborCosts) {
        // Update aggressively with the better cost if it is the best.
        neighborCosts.forEach((vp, subblockMap) ->
                subblockMap.forEach((subblock, delta) ->
                        currentCosts.get(vp).merge(subblock, delta, Math::min)
                )
        );
    }

    private void updateCurrentCostsAverage(Map<VesselPeriod, Map<Subblock, Double>> neighborCosts) {
        // Update moderately with the average.
        neighborCosts.forEach((vp, subblockMap) ->
                subblockMap.forEach((subblock, delta) ->
                        currentCosts.get(vp).merge(subblock, delta,
                                (current, val) -> (val + current) / 2)
                )
        );

    }


    public String searchProcessSummary() {
        return "Brief Search Log:\n" + briefLog.toString() + "\n";
    }

    public String resultSummary() {

        String initial = initialSolution != null ? initialSolution.briefObjectives() : "N/A";
        String best = bestSolution != null ? bestSolution.briefObjectives() : "N/A";
        String improvement = "N/A";

        if (initialSolution != null && bestSolution != null) {
            double improvementValue = initialSolution.getObjAll() == 0 ? 0 :
                    (initialSolution.getObjAll() - bestSolution.getObjAll()) / initialSolution.getObjAll() * 100;
            improvement = String.format("%.2f %%", improvementValue);
        }

        return String.format(
                "\nSummary:\n" +
                        "Initial TemporarySolution: %s\n" +
                        "Best TemporarySolution: %s\n" +
                        "Improvement: %s\n",
                initial, best, improvement
        );
    }


    private static Map<Subblock, Set<VesselPeriod>> transformAssignment(Map<VesselPeriod, Set<Subblock>> subblockAssignment) {
        Map<Subblock, Set<VesselPeriod>> vesselPeriodAssignment = new HashMap<>();
        for (Map.Entry<VesselPeriod, Set<Subblock>> entry : subblockAssignment.entrySet()) {
            for (Subblock subblock : entry.getValue()) {
                vesselPeriodAssignment.computeIfAbsent(subblock, k -> new HashSet<>()).add(entry.getKey());
            }
        }
        return vesselPeriodAssignment;
    }

    private List<Map<VesselPeriod, Set<Subblock>>> generateRandomNeighbors(Map<VesselPeriod, Set<Subblock>> assignment) {
        ArrayList<Map<VesselPeriod, Set<Subblock>>> neighbors = new ArrayList<>(NEIGHBOR_LIMIT);

        Map<Subblock, Set<VesselPeriod>> vesselPeriodAssignment = transformAssignment(assignment);

        for (Map.Entry<VesselPeriod, Set<Subblock>> entry : assignment.entrySet()) {
            VesselPeriod ip = entry.getKey();
            Set<VesselPeriod> conflicts = conflictPeriods.get(ip);
            Set<Subblock> oldSubblockSet = entry.getValue();
            for (Subblock oldK : oldSubblockSet) {
                for (Subblock newK : instance.getSubblocks()) {
                    if (oldSubblockSet.contains(newK)) {
                        continue;
                    }
                    boolean feasible = true;
                    for (VesselPeriod jq : vesselPeriodAssignment.getOrDefault(newK, Collections.emptySet())) {
                        if (conflicts.contains(jq)) {
                            feasible = false;
                            break;
                        }
                    }
                    if (!feasible)
                        continue;

                    // deep copy currentAssignment to neighbor
                    Map<VesselPeriod, Set<Subblock>> neighbor = new HashMap<>();
                    assignment.forEach((vp, subblocks) ->
                            neighbor.put(vp, new HashSet<>(subblocks))
                    );

                    neighbor.get(ip).remove(oldK);
                    neighbor.get(ip).add(newK);
                    if (neighbors.size() < NEIGHBOR_LIMIT) {
                        neighbors.add(neighbor);
                    } else {
                        neighbors.set(rand.nextInt(NEIGHBOR_LIMIT), neighbor);
                    }
                }
            }
        }

        for (Map.Entry<Subblock, Set<VesselPeriod>> entry : vesselPeriodAssignment.entrySet()) {
            Subblock oldK = entry.getKey();
            Set<VesselPeriod> vpOfOldK = entry.getValue();
            for (Subblock newK : instance.getSubblocks())
                if (!oldK.equals(newK)) {
                    Set<VesselPeriod> vpOfNewK = vesselPeriodAssignment.getOrDefault(newK, Collections.emptySet());

                    // deep copy currentAssignment to neighbor
                    Map<VesselPeriod, Set<Subblock>> neighbor = new HashMap<>();
                    assignment.forEach((vp, subblocks) ->
                            neighbor.put(vp, new HashSet<>(subblocks))
                    );

                    for (VesselPeriod ip : vpOfOldK) {
                        neighbor.get(ip).remove(oldK);
                    }

                    for (VesselPeriod ip : vpOfNewK) {
                        neighbor.get(ip).remove(newK);
                    }

                    for (VesselPeriod ip : vpOfOldK) neighbor.get(ip).add(newK);

                    for (VesselPeriod ip : vpOfNewK) neighbor.get(ip).add(oldK);

                    if (neighbors.size() < NEIGHBOR_LIMIT) {
                        neighbors.add(neighbor);
                    } else {
                        neighbors.set(rand.nextInt(NEIGHBOR_LIMIT), neighbor);
                    }
                }
        }

        return neighbors;
    }

    private List<Map<VesselPeriod, Set<Subblock>>> generateLimitedNeighbors() {

        CapacityLimitedMapPriorityQueue<Map<VesselPeriod, Set<Subblock>>, Double>
                neighbors = new CapacityLimitedMapPriorityQueue<>(NEIGHBOR_LIMIT);
        addBatchSwaNeighbors(neighbors);
        addSingleMoveNeighbors(neighbors);
        return neighbors.getSortedKeys();
    }


    private void addBatchSwaNeighbors(CapacityLimitedMapPriorityQueue<Map<VesselPeriod, Set<Subblock>>, Double>
                                              neighbors) {
        Map<Subblock, Set<VesselPeriod>> vesselPeriodAssignment = transformAssignment(currentAssignment);
        for (Map.Entry<Subblock, Set<VesselPeriod>> entry : vesselPeriodAssignment.entrySet()) {
            Subblock oldK = entry.getKey();
            Set<VesselPeriod> vpOfOldK = entry.getValue();
            double oldCost = 0;
            for (VesselPeriod vp : vpOfOldK)
                oldCost += currentCosts.get(vp).get(oldK);
            for (Subblock newK : instance.getSubblocks())
                if (!oldK.equals(newK)) {
                    Set<VesselPeriod> vpOfNewK = vesselPeriodAssignment.getOrDefault(newK, Collections.emptySet());

                    double delta = -oldCost;
                    for (VesselPeriod ip : vpOfNewK)
                        delta -= currentCosts.get(ip).get(newK);

                    for (VesselPeriod ip : vpOfOldK) {
                        delta += currentCosts.get(ip).get(newK);
                    }
                    for (VesselPeriod vp : vpOfNewK) {
                        delta += currentCosts.get(vp).get(oldK);
                    }


                    if (delta < -PRECISION) {
                        // deep copy currentAssignment to neighbor
                        Map<VesselPeriod, Set<Subblock>> neighbor = new HashMap<>();
                        currentAssignment.forEach((vp, subblocks) ->
                                neighbor.put(vp, new HashSet<>(subblocks))
                        );

                        for (VesselPeriod ip : vpOfOldK) {
                            neighbor.get(ip).remove(oldK);
                        }

                        for (VesselPeriod ip : vpOfNewK) {
                            neighbor.get(ip).remove(newK);
                        }

                        for (VesselPeriod ip : vpOfOldK) neighbor.get(ip).add(newK);

                        for (VesselPeriod ip : vpOfNewK) neighbor.get(ip).add(oldK);

                        // validate the subblock numbers
                        for (VesselPeriod ip : currentAssignment.keySet()) {
                            int n = currentAssignment.get(ip).size();
                            int newN = neighbor.get(ip).size();
                            if (newN != n)
                                throw new IllegalStateException();
                        }

                        neighbors.put(neighbor, delta);
                    }
                }
        }
    }


    private void addSingleMoveNeighbors(CapacityLimitedMapPriorityQueue<Map<VesselPeriod, Set<Subblock>>, Double>
                                                neighbors) {
        // For each subblock that are assigned no conflict between the vesselPeriods.
        Map<Subblock, Set<VesselPeriod>> vesselPeriodAssignment = transformAssignment(currentAssignment);

        for (Map.Entry<VesselPeriod, Set<Subblock>> entry : currentAssignment.entrySet()) {
            VesselPeriod ip = entry.getKey();
            Set<VesselPeriod> conflicts = conflictPeriods.get(ip);
            Set<Subblock> oldSubblockSet = entry.getValue();
            for (Subblock oldK : oldSubblockSet) {
                double oldCost = currentCosts.get(ip).get(oldK);
                for (Subblock newK : instance.getSubblocks()) {
                    if (oldSubblockSet.contains(newK)) {
                        continue;
                    }
                    boolean feasible = true;
                    for (VesselPeriod jq : vesselPeriodAssignment.getOrDefault(newK, Collections.emptySet())) {
                        if (conflicts.contains(jq)) {
                            feasible = false;
                            break;
                        }
                    }
                    if (!feasible)
                        continue;

                    double newCost = currentCosts.get(ip).get(newK);
                    if (newCost < oldCost - PRECISION) {
                        // deep copy currentAssignment to neighbor
                        Map<VesselPeriod, Set<Subblock>> neighbor = new HashMap<>();
                        currentAssignment.forEach((vp, subblocks) ->
                                neighbor.put(vp, new HashSet<>(subblocks)) // 创建新的HashSet
                        );

                        neighbor.get(ip).remove(oldK);
                        neighbor.get(ip).add(newK);
                        neighbors.put(neighbor, newCost - oldCost);
                    }
                }
            }
        }

    }


    private Map<VesselPeriod, Map<Subblock, Double>> estimateCosts(Map<VesselPeriod, Set<Subblock>> assignment, Solution solution) {
        Map<VesselPeriod, Map<Subblock, Double>> costs = new HashMap<>();

        // costs of route distance
        solution.forEachUnloadSchedule((ip, k, jq, schedule) -> {
            double distanceToSubblock = instance.getDistanceToSubblock(jq, k);
            double distanceFromSubblock = instance.getDistanceFromSubblock(ip, k);
            int number = schedule.number;
            double cost = (distanceToSubblock + distanceFromSubblock) * number * instance.etaRoute;
            costs.computeIfAbsent(ip, key -> new HashMap<>())
                    .merge(k, cost, Double::sum);
        });

        // costs of time deviation

        Map<VesselPeriod, Double> totalEarlinessWeight = new HashMap<>();

        solution.forEachLoadSchedule((ip, k, schedule) -> {
            int earliness = instance.getRelativeEarliness(schedule.time, ip);
            totalEarlinessWeight.compute(ip, (key, value) -> value == null ? earliness : value + earliness);
        });

        solution.forEachUnloadSchedule((ip, k, jq, schedule) -> {
            int earliness = instance.getRelativeEarliness(schedule.time, jq);
            totalEarlinessWeight.compute(jq, (key, value) -> value == null ? earliness : value + earliness);

        });

        solution.forEachLoadSchedule((ip, k, schedule) -> {
            double total = totalEarlinessWeight.get(ip);
            int earliness = instance.getRelativeEarliness(schedule.time, ip);
            if (total == 0) {
                assert earliness == 0 && solution.getEarliness(ip) == 0;
            } else {
                double ratio = earliness / total;
                double cost = ip.getEarlinessCost() * solution.getEarliness(ip) * ratio;
                costs.get(ip).merge(k, cost, Double::sum);
            }
        });

        solution.forEachUnloadSchedule((ip, k, jq, schedule) -> {
            double total = totalEarlinessWeight.get(jq);
            int earliness = instance.getRelativeEarliness(schedule.time, jq);
            if (total == 0) {
                assert earliness == 0 && solution.getEarliness(jq) == 0;
            } else {
                double ratio = earliness / total;
                double cost = jq.getEarlinessCost() * solution.getEarliness(jq) * ratio;
                costs.get(ip).merge(k, cost, Double::sum);
            }
        });

        Map<VesselPeriod, Double> totalTardinessWeight = new HashMap<>();

        solution.forEachLoadSchedule((ip, k, schedule) -> {
            int tardiness = instance.getRelativeTardiness(schedule.time, ip);
            totalTardinessWeight.compute(ip, (key, value) -> value == null ? tardiness : value + tardiness);
        });

        solution.forEachUnloadSchedule((ip, k, jq, schedule) -> {
            int tardiness = instance.getRelativeTardiness(schedule.time, jq);
            totalTardinessWeight.compute(jq, (key, value) -> value == null ? tardiness : value + tardiness);

        });

        solution.forEachLoadSchedule((ip, k, schedule) -> {
            double total = totalTardinessWeight.get(ip);
            int tardiness = instance.getRelativeTardiness(schedule.time, ip);
//            int tardiness = Math.max(0, ip.getRelativeExpectedIntervalEnd() - relativeTime - 1);
            if (total == 0) {
                assert tardiness == 0 && solution.getTardiness(ip) == 0 : ip + " tardiness=" + solution.getTardiness(ip) + ", <-> "
                        + "load task (" + ip + ", " + k + ", " + schedule.time + "), tardiness=" + tardiness;
            } else {
                double ratio = tardiness / total;
                double cost = ip.getTardinessCost() * solution.getTardiness(ip) * ratio;
                costs.get(ip).merge(k, cost, Double::sum);
            }
        });

        solution.forEachUnloadSchedule((ip, k, jq, schedule) -> {
            double total = totalTardinessWeight.get(jq);
            int tardiness = instance.getRelativeTardiness(schedule.time, jq);
            if (total == 0) {
                assert tardiness == 0 && solution.getTardiness(jq) == 0;
            } else {
                double ratio = tardiness / total;
                double cost = jq.getTardinessCost() * solution.getTardiness(jq) * ratio;
                costs.get(ip).merge(k, cost, Double::sum);
            }
        });

        // costs of congestion
        // road -> time -> coefficient

        Map<Integer, Map<Integer, Double>> loadRoadCoefficients, unloadRoadCoefficients;
        loadRoadCoefficients = calculateOverloadCoefficient(solution.getAuxiliaryLoadRoadFlows(), instance.maxLoadFlows, solution.getLoadOverload());
        unloadRoadCoefficients = calculateOverloadCoefficient(solution.getAuxiliaryUnloadRoadFlows(), instance.maxUnloadFlows, solution.getUnloadOverload());


        solution.forEachLoadSchedule((ip, k, schedule) -> {
            int time = schedule.time;
            double cost = 0;
            for (int road : instance.getRouteFromSubblock(ip, k))
                cost += loadRoadCoefficients.get(road).get(time) * instance.etaCongestion;
            costs.get(ip).merge(k, cost, Double::sum);
        });

        solution.forEachUnloadSchedule((ip, k, jq, schedule) -> {
            int time = schedule.time;
            double cost = 0;
            for (int road : instance.getRouteToSubblock(jq, k))
                cost += unloadRoadCoefficients.get(road).get(time);
            costs.get(ip).merge(k, cost, Double::sum);
        });

        return costs;
    }


//    private int calculateTimeDeviation(int operatingTime, VesselPeriod ip) {
//        int relativeTime = instance.getRelativeTimeStepWithinPeriod(operatingTime, ip);
//        int expA = ip.getRelativeExpectedIntervalStart();
//        int expB = ip.getRelativeExpectedIntervalEnd();
//        return Math.max(0, expA - relativeTime) + Math.max(0, relativeTime + 1 - expB);
//    }


    private Map<Integer, Map<Integer, Double>> calculateOverloadCoefficient(Map<Integer, Map<Integer, Integer>> flows, int expectedFlow, int largestOverload) {
        int totalOverload = flows.values().stream()
                .flatMap(roadMap -> roadMap.values().stream())
                .mapToInt(f -> Math.max(f - expectedFlow, 0))
                .sum();

        return flows.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey,
                                roadEntry -> roadEntry.getValue().entrySet().stream()
                                        .collect(Collectors.toMap(Map.Entry::getKey,
                                                timeEntry -> {
                                                    int f = timeEntry.getValue();
                                                    int over = Math.max(f - expectedFlow, 0);
                                                    return totalOverload == 0 ?
                                                            0 : (over * largestOverload * 1.) / (f * totalOverload);
                                                }
                                        ))
                        )
                );

    }


    private Solution evaluateAssignment(Map<VesselPeriod, Set<Subblock>> assignment) {
        if (assignment == null)
            return null;
        try (IloCplex cplex = new IloCplex()) {
            cplex.setOut(null);
            cplex.setWarning(null);
            if (timeLimit != null) {
                long timeLimit = this.timeLimit - (System.currentTimeMillis() - startTime) / 1000;
                if (timeLimit < 1) {
//                    out.println("Remaining time " + timeLimit + "s is not enough");
                    return null;
                }
                cplex.setParam(IloCplex.IntParam.TimeLimit, timeLimit);
            }
            if (threads != null)
                cplex.setParam(IloCplex.Param.Threads, threads);
            cplex.setParam(IloCplex.Param.Emphasis.Memory, true);

            CplexFixedSubblockModel solver = new CplexFixedSubblockModel(instance, cplex);
            Solution solution = solver.solveIntegratedSP(assignment);
            if (solution != null)
                solution.calculateObjectives();
            return solution;
        } catch (IloException e) {
            e.printStackTrace(out);
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) {
        int[][] instanceConfigurations = new int[][]{
//                // only 7-day and 14-day vessels.
                {2, 0, 1, 4, 1},
//                {4, 0, 2, 4, 2},
//                {6, 0, 3, 4, 3},
//                {8, 0, 4, 4, 4},
//                {10, 0, 5, 4, 5},
//
//                // only 7-day and 10-day vessels.
//                {2, 1, 0, 4, 1},
//                {4, 2, 0, 4, 2},
//                {6, 3, 0, 4, 3},
//                {8, 4, 0, 4, 4},
//                {10, 5, 0, 4, 5},

                // 7-day, 10-day and 14-day vessels.
//                {4, 1, 1, 4, 2},
//                {6, 2, 1, 4, 3},
//                {8, 2, 2, 4, 4},
//                {10, 3, 2, 4, 5},
//                {12, 3, 3, 4, 6},
//                {14, 4, 3, 4, 7},
//                {16, 4, 4, 4, 8},
//                {18, 5, 4, 4, 9},
//                {20, 5, 5, 4, 10},

        };

        StringBuilder sb = new StringBuilder("Running Summary:\n");

        for (int[] configuration : instanceConfigurations) {
            for (int j = 1; j <= 5; j++) {
                int small = configuration[0];
                int medium = configuration[1];
                int large = configuration[2];
                int rows = configuration[3];
                int cols = configuration[4];


                String instanceDir = String.format("input/" + Instance.DEFAULT_NAME_PATTERN + ".json",
                        small, medium, large, rows, cols, j);
                Instance instance = Instance.readJson(instanceDir);

                System.out.println("Instance: " + instanceDir);


                DecomposedNeighborhoodSearch searcher = new DecomposedNeighborhoodSearch(instance);


                searcher.NEIGHBOR_LIMIT = 20;
                searcher.MAX_NO_BEST_ITERATIONS = 30;
                searcher.MAX_NO_IMPROVED_ITERATIONS = 10;
                searcher.MAX_EXPLORED_SOLUTION = 2000;

                searcher.meetBestAndBreak = true;
                searcher.meetImprovedAndBreak = true;
                searcher.SHAKING_TIMES = 2;
                searcher.newSearch();


                Solution result = searcher.getBestSolution();

                sb.append(instanceDir).append("\n");
                if (result == null) {
                    sb.append("No answer found.\n\n");
                } else {
                    sb.append(searcher.searchProcessSummary()).append(searcher.resultSummary()).append("\n");
                    System.out.println(searcher.resultSummary());
                }

                System.out.println();
                System.out.println();
            }
        }

        System.out.println(sb);

    }
}