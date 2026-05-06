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
            case "cplex", "cplex_integrated", "cplexintegrated", "cplex_integrated_model" -> CPLEX_INTEGRATED_MODEL;
            case "sequential", "sequential_decision", "cplexsequential" -> SEQUENTIAL_DECISION;
            case "master_heuristic", "masterheuristic" -> MASTER_HEURISTIC_INTEGRATED_SUBPROBLEM_CPLEX;
            case "repeated_master_heuristic", "repeatedmasterheuristic" ->
                    REPEATEDLY_MASTER_HEURISTIC_INTEGRATED_SUBPROBLEM_CPLEX;
            case "decomposed", "decomposed_neighborhood_search", "decomposedneighborhoodsearch" ->
                    DECOMPOSED_NEIGHBORHOOD_SEARCH;
            case "decomposed_old", "decomposedrecreatecplexneighborhoodsearch" -> DECOMPOSED_OLD_NEIGHBORHOOD_SEARCH;
            case "decomposed_random", "decomposedrandom" -> DECOMPOSED_RANDOM_SEARCH;
            case "local_refinement", "localrefinement" -> LOCAL_REFINEMENT_SEARCH;
            default -> throw new IllegalArgumentException("Unknown solver type: " + name);
        };
    }

    public String getName() {
        return name;
    }
}
