package main;

public enum SolverType {
    CPLEX_INTEGRATED_MODEL("cplexIntegrated"),
    SEQUENTIAL_DECISION("cplexSequential"),
    MASTER_HEURISTIC_INTEGRATED_SUBPROBLEM_CPLEX("masterHeuristic"),
    REPEATEDLY_MASTER_HEURISTIC_INTEGRATED_SUBPROBLEM_CPLEX("repeatedMasterHeuristic"),
    DECOMPOSED_NEIGHBORHOOD_SEARCH("decomposedNeighborhoodSearch"),
    DECOMPOSED_OLD_NEIGHBORHOOD_SEARCH("decomposedRecreateCplexNeighborhoodSearch"),
    DECOMPOSED_RANDOM_SEARCH("decomposedRandom"),
    LOCAL_REFINEMENT_SEARCH("localRefinement");
    private final String name;

    SolverType(String name) {
        this.name = name;
    }

    public static SolverType fromName(String name) {
        return switch (name.toLowerCase()) {
            case "cplex", "cplex_integrated" -> CPLEX_INTEGRATED_MODEL;
            case "sequential", "sequential_decision" -> SEQUENTIAL_DECISION;
            case "master_heuristic" -> MASTER_HEURISTIC_INTEGRATED_SUBPROBLEM_CPLEX;
            case "repeated_master_heuristic" -> REPEATEDLY_MASTER_HEURISTIC_INTEGRATED_SUBPROBLEM_CPLEX;
            case "decomposed", "decomposed_neighborhood_search" -> DECOMPOSED_NEIGHBORHOOD_SEARCH;
            case "decomposed_old" -> DECOMPOSED_OLD_NEIGHBORHOOD_SEARCH;
            case "decomposed_random" -> DECOMPOSED_RANDOM_SEARCH;
            case "local_refinement" -> LOCAL_REFINEMENT_SEARCH;
            default -> throw new IllegalArgumentException("Unknown solver type: " + name);
        };
    }

    public String getName() {
        return name;
    }
}
