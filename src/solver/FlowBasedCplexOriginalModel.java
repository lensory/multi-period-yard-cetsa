package solver;

import entity.Instance;
import entity.Solution;
import entity.Subblock;
import entity.Vessel;
import entity.VesselPeriod;
import ilog.concert.IloException;
import ilog.concert.IloIntExpr;
import ilog.concert.IloIntVar;
import ilog.concert.IloLinearIntExpr;
import ilog.concert.IloLinearNumExpr;
import ilog.concert.IloNumExpr;
import ilog.cplex.IloCplex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import util.MyMathMethods;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class FlowBasedCplexOriginalModel {
    private static final Logger LOGGER = LoggerFactory.getLogger(FlowBasedCplexOriginalModel.class);

    public final Instance instance;
    public IloCplex cplex;

    private final ModelIndex index;
    private final boolean nameCplexObjects;
    private final String lpExportFileName;

    private final double PRECISION = 1e-6;
    private final int horizon;
    private final int roads;

    private IloIntVar[][] varY;
    private IloIntVar[][] varZ;
    private IloIntVar[][] varW;
    private IloIntVar[][][] varDeltaU;
    private IloIntVar[][][] varDeltaL;
    private IloIntVar[][] varPiU;
    private IloIntVar[][] varPiL;
    private IloIntVar[][] varPiUD;
    private IloIntVar[] varIota;
    private IloIntVar[] varKappa;
    private IloIntVar[][] varRho;

    public IloIntVar varUnloadOverload;
    public IloIntVar varLoadOverload;

    public IloLinearNumExpr objRoute;
    public IloLinearNumExpr objTime;
    public IloLinearNumExpr objCongestion;

    private long totalVariables;
    private long totalConstraints;
    private long totalObjectiveTerms;
    private long totalObjectives;
    private long buildStartNanos;

    private FlowBasedCplexOriginalModel(Instance instance, IloCplex cplex, boolean nameCplexObjects,
                                        String lpExportFileName) {
        this.instance = instance;
        this.cplex = cplex;
        this.horizon = instance.horizon;
        this.roads = instance.roads;
        this.nameCplexObjects = nameCplexObjects;
        this.lpExportFileName = lpExportFileName;
        this.index = ModelIndex.build(instance);
    }

    public static FlowBasedCplexOriginalModel buildIntegratedModel(Instance instance, IloCplex cplex)
            throws IloException {
        return buildIntegratedModel(instance, cplex, null);
    }

    public static FlowBasedCplexOriginalModel buildIntegratedModel(Instance instance, IloCplex cplex,
                                                                   String lpExportFileName)
            throws IloException {
        FlowBasedCplexOriginalModel model =
                new FlowBasedCplexOriginalModel(instance, cplex, lpExportFileName != null, lpExportFileName);

        model.logBuildStart("flow-based integrated model");
        model.runBuildStep("variables/Y", model::initVarY);
        model.runBuildStep("variables/Z", model::initVarZ);
        model.runBuildStep("variables/W", model::initVarW);
        model.runBuildStep("variables/DeltaU", model::initVarDeltaU);
        model.runBuildStep("variables/DeltaL", model::initVarDeltaL);
        model.runBuildStep("variables/Pi", model::initVarPi);
        model.runBuildStep("variables/IotaKappa", model::initVarIotaKappa);
        model.runBuildStep("variables/Rho", model::initVarRho);
        model.runBuildStep("variables/RoadFlow", model::initVarRoadFlow);

        model.runBuildStep("constraints/YardTemplate", model::initYardTemplateConstraints);
        model.runBuildStep("constraints/StorageAllocation", model::initStorageAllocationConstraints);
        model.runBuildStep("constraints/BinaryHandlingTime", model::initBinaryHandlingTimeConstraints);
        model.runBuildStep("constraints/Congestion", model::initCongestionConstraints);
        model.runBuildStep("objective/All", model::initObjective);
        model.runBuildStep("objective/AddMinimize",
                () -> model.addMinimize(cplex.sum(model.objRoute, model.objTime, model.objCongestion)));
        model.logBuildSummary("flow-based integrated model");
        return model;
    }

    private IloIntVar boolVar(String format, Object... args) throws IloException {
        countVariable(format);
        return nameCplexObjects ? cplex.boolVar(String.format(format, args)) : cplex.boolVar();
    }

    private IloIntVar intVar(int lb, int ub, String format, Object... args) throws IloException {
        countVariable(format);
        return nameCplexObjects ? cplex.intVar(lb, ub, String.format(format, args)) : cplex.intVar(lb, ub);
    }

    private void addLe(IloNumExpr lhs, double rhs, String format, Object... args) throws IloException {
        countConstraint(format);
        if (nameCplexObjects)
            cplex.addLe(lhs, rhs, String.format(format, args));
        else
            cplex.addLe(lhs, rhs);
    }

    private void addLe(IloNumExpr lhs, IloNumExpr rhs, String format, Object... args) throws IloException {
        countConstraint(format);
        if (nameCplexObjects)
            cplex.addLe(lhs, rhs, String.format(format, args));
        else
            cplex.addLe(lhs, rhs);
    }

    private void addEq(IloNumExpr lhs, double rhs, String format, Object... args) throws IloException {
        countConstraint(format);
        if (nameCplexObjects)
            cplex.addEq(lhs, rhs, String.format(format, args));
        else
            cplex.addEq(lhs, rhs);
    }

    private void addEq(IloNumExpr lhs, IloNumExpr rhs, String format, Object... args) throws IloException {
        countConstraint(format);
        if (nameCplexObjects)
            cplex.addEq(lhs, rhs, String.format(format, args));
        else
            cplex.addEq(lhs, rhs);
    }

    private void addGe(IloNumExpr lhs, IloNumExpr rhs, String format, Object... args) throws IloException {
        countConstraint(format);
        if (nameCplexObjects)
            cplex.addGe(lhs, rhs, String.format(format, args));
        else
            cplex.addGe(lhs, rhs);
    }

    private void addObjectiveTerm(IloLinearNumExpr objective, double coefficient, IloIntVar var) throws IloException {
        objective.addTerm(coefficient, var);
        totalObjectiveTerms++;
    }

    private void addMinimize(IloNumExpr objective) throws IloException {
        cplex.addMinimize(objective);
        totalObjectives++;
    }

    private void countVariable(String format) {
        totalVariables++;
    }

    private void countConstraint(String format) {
        totalConstraints++;
    }

    private void logBuildStart(String modelName) {
        buildStartNanos = System.nanoTime();
        LOGGER.info("Build {} start: vessels={}, periods={}, flows={}, subblocks={}, horizon={}, roads={}, namedObjects={}",
                modelName, instance.getNumVessels(), instance.getNumVesselPeriods(), index.F,
                instance.getNumSubblocks(), horizon, roads, nameCplexObjects);
    }

    private void runBuildStep(String stepName, BuildStep step) throws IloException {
        long varsBefore = totalVariables;
        long constraintsBefore = totalConstraints;
        long objectiveTermsBefore = totalObjectiveTerms;
        long start = System.nanoTime();
        LOGGER.info("Build step start: {}", stepName);
        step.run();
        LOGGER.info("Build step done: {} elapsed={}s, vars+={}, constraints+={}, objTerms+={}",
                stepName, secondsSince(start),
                totalVariables - varsBefore,
                totalConstraints - constraintsBefore,
                totalObjectiveTerms - objectiveTermsBefore);
    }

    private void logBuildSummary(String modelName) {
        LOGGER.info("Build {} summary: elapsed={}s, variables={}, constraints={}, objectiveTerms={}, objectives={}",
                modelName, secondsSince(buildStartNanos),
                totalVariables,
                totalConstraints,
                totalObjectiveTerms,
                totalObjectives);
    }

    private static String secondsSince(long startNanos) {
        return String.format(Locale.ROOT, "%.3f", (System.nanoTime() - startNanos) / 1_000_000_000.0);
    }

    private interface BuildStep {
        void run() throws IloException;
    }

    public void exportModelIfRequested() throws IloException {
        if (lpExportFileName != null) {
            cplex.exportModel(lpExportFileName);
        }
    }

    private void initVarY() throws IloException {
        varY = new IloIntVar[index.P][index.K];
        for (int u = 0; u < index.P; u++) {
            VesselPeriod vp = index.vps[u];
            for (int k = 0; k < index.K; k++) {
                varY[u][k] = boolVar("Y_%d_%d_%d", vp.getVid(), vp.getPid(), index.subblocks[k].getId());
            }
        }
    }

    private void initVarZ() throws IloException {
        varZ = new IloIntVar[index.F][index.K];
        for (int f = 0; f < index.F; f++) {
            Flow flow = index.flows[f];
            for (int k = 0; k < index.K; k++) {
                varZ[f][k] = boolVar("Z_%d_%d_%d_%d_%d",
                        flow.src.getVid(), flow.src.getPid(), index.subblocks[k].getId(),
                        flow.dst.getVid(), flow.dst.getPid());
            }
        }
    }

    private void initVarW() throws IloException {
        varW = new IloIntVar[index.F][index.K];
        for (int f = 0; f < index.F; f++) {
            Flow flow = index.flows[f];
            for (int k = 0; k < index.K; k++) {
                varW[f][k] = intVar(0, instance.spaceCapacity, "W_%d_%d_%d_%d_%d",
                        flow.src.getVid(), flow.src.getPid(), index.subblocks[k].getId(),
                        flow.dst.getVid(), flow.dst.getPid());
            }
        }
    }

    private void initVarDeltaU() throws IloException {
        varDeltaU = new IloIntVar[index.F][index.K][];
        for (int f = 0; f < index.F; f++) {
            Flow flow = index.flows[f];
            for (int k = 0; k < index.K; k++) {
                int[] times = index.unloadTimesByFlow[f];
                varDeltaU[f][k] = new IloIntVar[times.length];
                for (int s = 0; s < times.length; s++) {
                    varDeltaU[f][k][s] = boolVar("DeltaU_%d_%d_%d_%d_%d_%d",
                            flow.src.getVid(), flow.src.getPid(), index.subblocks[k].getId(),
                            flow.dst.getVid(), flow.dst.getPid(), times[s]);
                }
            }
        }
    }

    private void initVarDeltaL() throws IloException {
        varDeltaL = new IloIntVar[index.P][index.K][];
        for (int u = 0; u < index.P; u++) {
            VesselPeriod vp = index.vps[u];
            for (int k = 0; k < index.K; k++) {
                int[] times = index.loadTimesByVp[u];
                varDeltaL[u][k] = new IloIntVar[times.length];
                for (int s = 0; s < times.length; s++) {
                    varDeltaL[u][k][s] = boolVar("DeltaL_%d_%d_%d_%d",
                            vp.getVid(), vp.getPid(), index.subblocks[k].getId(), times[s]);
                }
            }
        }
    }

    private void initVarPi() throws IloException {
        varPiU = new IloIntVar[index.P][horizon];
        varPiL = new IloIntVar[index.P][horizon];
        varPiUD = new IloIntVar[index.P][horizon];
        for (int u = 0; u < index.P; u++) {
            VesselPeriod vp = index.vps[u];
            for (int t = 0; t < horizon; t++) {
                varPiU[u][t] = boolVar("PiU_%d_%d_%d", vp.getVid(), vp.getPid(), t);
                varPiL[u][t] = boolVar("PiL_%d_%d_%d", vp.getVid(), vp.getPid(), t);
                varPiUD[u][t] = boolVar("PiUD_%d_%d_%d", vp.getVid(), vp.getPid(), t);
            }
        }
    }

    private void initVarIotaKappa() throws IloException {
        varIota = new IloIntVar[index.P];
        varKappa = new IloIntVar[index.P];
        for (int u = 0; u < index.P; u++) {
            VesselPeriod vp = index.vps[u];
            varIota[u] = intVar(0, vp.getRelativeExpectedIntervalStart() - vp.getRelativeFeasibleIntervalStart(),
                    "Iota_%d_%d", vp.getVid(), vp.getPid());
            varKappa[u] = intVar(0, vp.getRelativeFeasibleIntervalEnd() - vp.getRelativeExpectedIntervalStart(),
                    "Kappa_%d_%d", vp.getVid(), vp.getPid());
        }
    }

    private void initVarRho() throws IloException {
        varRho = new IloIntVar[index.K][horizon];
        for (int k = 0; k < index.K; k++) {
            for (int t = 0; t < horizon; t++) {
                varRho[k][t] = boolVar("Rho_%d_%d", index.subblocks[k].getId(), t);
            }
        }
    }

    private void initVarRoadFlow() throws IloException {
        varLoadOverload = intVar(0, Integer.MAX_VALUE, "largestLoadFlow");
        varUnloadOverload = intVar(0, Integer.MAX_VALUE, "largestUnloadFlow");
    }

    private void initYardTemplateConstraints() throws IloException {
        for (int k = 0; k < index.K; k++) {
            for (int t = 0; t < horizon; t++) {
                IloLinearIntExpr expr = cplex.linearIntExpr();
                for (int u : index.activeVpsByTime[t]) {
                    expr.addTerm(1, varY[u][k]);
                }
                addLe(expr, 1, "ConsYardActive_%d_%d", index.subblocks[k].getId(), t);
            }
        }

        for (int u = 0; u < index.P; u++) {
            IloLinearIntExpr expr = cplex.linearIntExpr();
            for (int k = 0; k < index.K; k++) {
                expr.addTerm(1, varY[u][k]);
            }
            addLe(expr, index.requiredSubblocks[u],
                    "ConsYardMax_%d_%d", index.vps[u].getVid(), index.vps[u].getPid());
        }

        for (int f = 0; f < index.F; f++) {
            Flow flow = index.flows[f];
            for (int k = 0; k < index.K; k++) {
                addLe(varZ[f][k], varY[flow.dstIndex][k],
                        "ConsYardZY_%d_%d_%d_%d_%d",
                        flow.src.getVid(), flow.src.getPid(), flow.dst.getVid(), flow.dst.getPid(),
                        index.subblocks[k].getId());
            }
        }
    }

    private void initStorageAllocationConstraints() throws IloException {
        for (int f = 0; f < index.F; f++) {
            Flow flow = index.flows[f];
            for (int k = 0; k < index.K; k++) {
                addLe(varW[f][k], cplex.prod(instance.spaceCapacity, varZ[f][k]),
                        "ConsFlowWZ_%d_%d_%d_%d_%d",
                        flow.dst.getVid(), flow.dst.getPid(), index.subblocks[k].getId(),
                        flow.src.getVid(), flow.src.getPid());
            }
        }

        for (int f = 0; f < index.F; f++) {
            Flow flow = index.flows[f];
            IloLinearIntExpr expr = cplex.linearIntExpr();
            for (int k = 0; k < index.K; k++) {
                expr.addTerm(1, varW[f][k]);
            }
            addEq(expr, flow.containers,
                    "ConsFlowN_%d_%d_%d_%d",
                    flow.dst.getVid(), flow.dst.getPid(), flow.src.getVid(), flow.src.getPid());
        }

        for (int u = 0; u < index.P; u++) {
            VesselPeriod vp = index.vps[u];
            for (int k = 0; k < index.K; k++) {
                IloLinearIntExpr expr = cplex.linearIntExpr();
                for (int f : index.incomingFlowsByVp[u]) {
                    expr.addTerm(1, varW[f][k]);
                }
                addLe(expr, instance.spaceCapacity,
                        "ConsFlowC_%d_%d_%d", vp.getVid(), vp.getPid(), index.subblocks[k].getId());
            }
        }
    }

    private void initBinaryHandlingTimeConstraints() throws IloException {
        for (int f = 0; f < index.F; f++) {
            Flow flow = index.flows[f];
            for (int k = 0; k < index.K; k++) {
                IloLinearIntExpr expr = cplex.linearIntExpr();
                for (IloIntVar delta : varDeltaU[f][k]) {
                    expr.addTerm(1, delta);
                }
                addEq(expr, varZ[f][k],
                        "ConsHandleZ_%d_%d_%d_%d_%d",
                        flow.dst.getVid(), flow.dst.getPid(), index.subblocks[k].getId(),
                        flow.src.getVid(), flow.src.getPid());
            }
        }

        for (int u = 0; u < index.P; u++) {
            VesselPeriod vp = index.vps[u];
            for (int k = 0; k < index.K; k++) {
                IloLinearIntExpr expr = cplex.linearIntExpr();
                for (IloIntVar delta : varDeltaL[u][k]) {
                    expr.addTerm(1, delta);
                }
                addEq(expr, varY[u][k],
                        "ConsHandleY_%d_%d_%d", vp.getVid(), vp.getPid(), index.subblocks[k].getId());
            }
        }

        for (int f = 0; f < index.F; f++) {
            Flow flow = index.flows[f];
            for (int k = 0; k < index.K; k++) {
                for (int s = 0; s < index.unloadTimesByFlow[f].length; s++) {
                    int t = index.unloadTimesByFlow[f][s];
                    addGe(varPiU[flow.srcIndex][t], varDeltaU[f][k][s],
                            "ConsHandlePiU_%d_%d_%d_%d_%d",
                            flow.src.getVid(), flow.dst.getVid(), index.subblocks[k].getId(), t, s);
                    addGe(varPiUD[flow.dstIndex][t], varDeltaU[f][k][s],
                            "ConsHandlePiUD_%d_%d_%d_%d_%d",
                            flow.src.getVid(), flow.dst.getVid(), index.subblocks[k].getId(), t, s);
                }
            }
        }

        for (int u = 0; u < index.P; u++) {
            VesselPeriod vp = index.vps[u];
            for (int k = 0; k < index.K; k++) {
                for (int s = 0; s < index.loadTimesByVp[u].length; s++) {
                    int t = index.loadTimesByVp[u][s];
                    addGe(varPiL[u][t], varDeltaL[u][k][s],
                            "ConsHandlePiL_%d_%d_%d_%d", vp.getVid(), vp.getPid(), index.subblocks[k].getId(), t);
                }
            }
        }

        for (int u = 0; u < index.P; u++) {
            VesselPeriod vp = index.vps[u];
            int relativeTimeStep = 0;
            for (int t : index.periodTimesByVp[u]) {
                if (relativeTimeStep < vp.getRelativeFeasibleIntervalStart()
                        || relativeTimeStep >= vp.getRelativeFeasibleIntervalEnd()) {
                    addLe(cplex.sum(varPiU[u][t], varPiL[u][t]), 0,
                            "ConsHandlePiUL_%d_%d_%d", vp.getVid(), vp.getPid(), t);
                } else {
                    addLe(cplex.sum(varPiU[u][t], varPiL[u][t]), 1,
                            "ConsHandlePiUL_%d_%d_%d", vp.getVid(), vp.getPid(), t);
                }
                relativeTimeStep++;
            }
        }

        for (int u = 0; u < index.P; u++) {
            VesselPeriod vp = index.vps[u];
            for (int pos = 0; pos < index.periodTimesByVp[u].length; pos++) {
                int t = index.periodTimesByVp[u][pos];
                IloLinearIntExpr expr = cplex.linearIntExpr();
                for (int s = pos; s < index.periodTimesByVp[u].length; s++) {
                    int time = index.periodTimesByVp[u][s];
                    expr.addTerm(1, varPiU[u][time]);
                    expr.addTerm(1, varPiUD[u][time]);
                }
                addLe(expr,
                        cplex.prod(2 * (vp.getLengthOfPeriod() - pos), cplex.diff(1, varPiL[u][t])),
                        "ConsHandlePiUUD_%d_%d_%d", vp.getVid(), vp.getPid(), t);
            }
        }

        for (int u = 0; u < index.P; u++) {
            VesselPeriod vp = index.vps[u];
            int expA = vp.getRelativeExpectedIntervalStart();
            int expB = vp.getRelativeExpectedIntervalEnd();
            for (int pos = 0; pos < index.periodTimesByVp[u].length; pos++) {
                int t = index.periodTimesByVp[u][pos];
                IloIntExpr bigM = cplex.prod(vp.getLengthOfPeriod(),
                        cplex.diff(1, cplex.sum(varPiU[u][t], varPiL[u][t])));
                addGe(varIota[u], cplex.diff(expA - pos, bigM),
                        "ConsHandleIota_%d_%d_%d", vp.getVid(), vp.getPid(), t);
                addGe(varKappa[u], cplex.diff(pos + 1 - expB, bigM),
                        "ConsHandleKappa_%d_%d_%d", vp.getVid(), vp.getPid(), t);
            }
        }
    }

    private void initCongestionConstraints() throws IloException {
        IloLinearIntExpr[][] loadAtKT = new IloLinearIntExpr[index.K][horizon];
        IloLinearIntExpr[][][] unloadBySrcAtKT = new IloLinearIntExpr[index.P][index.K][horizon];
        IloLinearIntExpr[][] unloadFlowAtRT = new IloLinearIntExpr[roads][horizon];
        IloLinearIntExpr[][] loadFlowAtRT = new IloLinearIntExpr[roads][horizon];

        for (int k = 0; k < index.K; k++) {
            for (int t = 0; t < horizon; t++) {
                loadAtKT[k][t] = cplex.linearIntExpr();
            }
        }
        for (int u = 0; u < index.P; u++) {
            for (int k = 0; k < index.K; k++) {
                for (int t = 0; t < horizon; t++) {
                    unloadBySrcAtKT[u][k][t] = cplex.linearIntExpr();
                }
            }
        }
        for (int r = 0; r < roads; r++) {
            for (int t = 0; t < horizon; t++) {
                unloadFlowAtRT[r][t] = cplex.linearIntExpr();
                loadFlowAtRT[r][t] = cplex.linearIntExpr();
            }
        }

        for (int f = 0; f < index.F; f++) {
            Flow flow = index.flows[f];
            for (int k = 0; k < index.K; k++) {
                for (int s = 0; s < index.unloadTimesByFlow[f].length; s++) {
                    int t = index.unloadTimesByFlow[f][s];
                    IloIntVar delta = varDeltaU[f][k][s];
                    unloadBySrcAtKT[flow.srcIndex][k][t].addTerm(1, delta);
                    for (int r : index.routeUnload[f][k]) {
                        unloadFlowAtRT[r][t].addTerm(1, delta);
                    }
                }
            }
        }

        for (int u = 0; u < index.P; u++) {
            for (int k = 0; k < index.K; k++) {
                for (int s = 0; s < index.loadTimesByVp[u].length; s++) {
                    int t = index.loadTimesByVp[u][s];
                    IloIntVar delta = varDeltaL[u][k][s];
                    loadAtKT[k][t].addTerm(1, delta);
                    for (int r : index.routeLoad[u][k]) {
                        loadFlowAtRT[r][t].addTerm(1, delta);
                    }
                }
            }
        }

        for (int u = 0; u < index.P; u++) {
            VesselPeriod vp = index.vps[u];
            for (int k = 0; k < index.K; k++) {
                for (int t = 0; t < horizon; t++) {
                    addLe(unloadBySrcAtKT[u][k][t], varRho[k][t],
                            "ConsCongU_%d_%d_%d_%d", vp.getVid(), vp.getPid(), index.subblocks[k].getId(), t);
                }
            }
        }

        for (int k = 0; k < index.K; k++) {
            for (int t = 0; t < horizon; t++) {
                addLe(loadAtKT[k][t], varRho[k][t],
                        "ConsCongL_%d_%d", index.subblocks[k].getId(), t);
            }
        }

        for (int k1 = 0; k1 < index.K; k1++) {
            for (int k2 = k1 + 1; k2 < index.K; k2++) {
                Subblock left = index.subblocks[k1];
                Subblock right = index.subblocks[k2];
                if (left.isNeighborInSameBlock(right) || left.isNeighborAcrossLane(right)) {
                    for (int t = 0; t < horizon; t++) {
                        addLe(cplex.sum(varRho[k1][t], varRho[k2][t]), 1,
                                "ConsCongRho_%d_%d_%d", left.getId(), right.getId(), t);
                    }
                }
            }
        }

        for (int r = 0; r < roads; r++) {
            for (int t = 0; t < horizon; t++) {
                addLe(unloadFlowAtRT[r][t], cplex.sum(instance.maxUnloadFlows, varUnloadOverload),
                        "ConsCongRoadU_%d_%d", r, t);
                addLe(loadFlowAtRT[r][t], cplex.sum(instance.maxLoadFlows, varLoadOverload),
                        "ConsCongRoadL_%d_%d", r, t);
            }
        }
    }

    private void initObjective() throws IloException {
        objRoute = cplex.linearNumExpr();
        for (int f = 0; f < index.F; f++) {
            Flow flow = index.flows[f];
            for (int k = 0; k < index.K; k++) {
                addObjectiveTerm(objRoute, index.distance[f][k] * instance.etaRoute, varW[f][k]);
            }
        }

        objTime = cplex.linearNumExpr();
        for (int u = 0; u < index.P; u++) {
            VesselPeriod vp = index.vps[u];
            addObjectiveTerm(objTime, vp.getEarlinessCost(), varIota[u]);
            addObjectiveTerm(objTime, vp.getTardinessCost(), varKappa[u]);
        }

        objCongestion = cplex.linearNumExpr();
        addObjectiveTerm(objCongestion, instance.etaCongestion, varUnloadOverload);
        addObjectiveTerm(objCongestion, instance.etaCongestion, varLoadOverload);
    }

    public boolean solve() throws IloException {
        return cplex.solve();
    }

    private void validateCplexStatus() throws IloException {
        if (cplex == null) {
            throw new IllegalStateException("Cplex instance is not initialized.");
        }
        IloCplex.Status status = cplex.getStatus();
        if (status != IloCplex.Status.Optimal && status != IloCplex.Status.Feasible) {
            throw new IllegalStateException("Model did not find an optimal or feasible solution. Current status: " + status);
        }
    }

    private int getIntValue(IloIntVar var) throws IloException {
        double v = cplex.getValue(var);
        long lv = Math.round(v);
        if (Math.abs(v - lv) > PRECISION)
            throw new IllegalArgumentException("Not an Integer: " + v);
        if (lv > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Out of bound: " + lv);
        return (int) lv;
    }

    private boolean isBoolTrue(IloIntVar var) throws IloException {
        double v = cplex.getValue(var);
        if (v < 0 - PRECISION || v > 1 + PRECISION)
            throw new IllegalArgumentException("Not a Bool: " + v);
        return Math.abs(v - 1) < PRECISION;
    }

    public void setPriorityOnY() throws IloException {
        for (int u = 0; u < index.P; u++) {
            for (int k = 0; k < index.K; k++) {
                cplex.setPriority(varY[u][k], 1);
            }
        }
    }

    public Solution getSolution() throws IloException {
        IloCplex.Status status = cplex.getStatus();
        if (status != IloCplex.Status.Optimal && status != IloCplex.Status.Feasible) {
            return null;
        }

        Solution solution = new Solution(instance);
        recordSubblockAssignment(solution);
        recordVesselHandling(solution);
        solution.calculateObjectives();
        solution.setGap(cplex.getMIPRelativeGap());
        return solution;
    }

    private void recordSubblockAssignment(Solution solution) throws IloException {
        validateCplexStatus();
        for (int u = 0; u < index.P; u++) {
            for (int k = 0; k < index.K; k++) {
                if (isBoolTrue(varY[u][k])) {
                    solution.setSubBlock(index.vps[u], index.subblocks[k]);
                }
            }
        }
    }

    private void recordVesselHandling(Solution solution) throws IloException {
        validateCplexStatus();

        int[][] containersByDestinationSubblock = new int[index.P][index.K];
        for (int f = 0; f < index.F; f++) {
            Flow flow = index.flows[f];
            for (int k = 0; k < index.K; k++) {
                int containers = getIntValue(varW[f][k]);
                if (containers == 0) {
                    continue;
                }
                int unloadTime = selectedTime(varDeltaU[f][k], index.unloadTimesByFlow[f],
                        "unloading " + flow.src + " -> " + index.subblocks[k] + " -> " + flow.dst);
                solution.setUnloadSchedule(flow.src, flow.dst, index.subblocks[k], unloadTime, containers);
                containersByDestinationSubblock[flow.dstIndex][k] += containers;
            }
        }

        for (int u = 0; u < index.P; u++) {
            for (int k = 0; k < index.K; k++) {
                int containers = containersByDestinationSubblock[u][k];
                if (containers == 0) {
                    continue;
                }
                int loadTime = selectedTime(varDeltaL[u][k], index.loadTimesByVp[u],
                        "loading " + index.subblocks[k] + " -> " + index.vps[u]);
                solution.setLoadSchedule(index.vps[u], index.subblocks[k], loadTime, containers);
            }
        }
    }

    private int selectedTime(IloIntVar[] vars, int[] times, String label) throws IloException {
        List<Integer> selected = new ArrayList<>(1);
        for (int s = 0; s < vars.length; s++) {
            if (isBoolTrue(vars[s])) {
                selected.add(times[s]);
            }
        }
        if (selected.size() != 1) {
            throw new RuntimeException("Expected one selected time for " + label + ", got " + selected);
        }
        return selected.get(0);
    }

    private static int[] toIntArray(Iterable<Integer> values) {
        List<Integer> list = new ArrayList<>();
        for (int value : values) {
            list.add(value);
        }
        int[] array = new int[list.size()];
        for (int i = 0; i < list.size(); i++) {
            array[i] = list.get(i);
        }
        return array;
    }

    private static int[][] toArrayByVp(List<List<Integer>> lists) {
        int[][] result = new int[lists.size()][];
        for (int i = 0; i < lists.size(); i++) {
            List<Integer> list = lists.get(i);
            result[i] = new int[list.size()];
            for (int j = 0; j < list.size(); j++) {
                result[i][j] = list.get(j);
            }
        }
        return result;
    }

    private static final class ModelIndex {
        final int P;
        final int K;
        final int F;
        final VesselPeriod[] vps;
        final Subblock[] subblocks;
        final Flow[] flows;
        final Map<VesselPeriod, Integer> vpIndex;
        final int[][] activeVpsByTime;
        final int[][] periodTimesByVp;
        final int[] requiredSubblocks;
        final int[][] incomingFlowsByVp;
        final int[][] outgoingFlowsByVp;
        final int[][] unloadTimesByFlow;
        final int[][] loadTimesByVp;
        final int[][][] routeUnload;
        final int[][][] routeLoad;
        final double[][] distance;

        private ModelIndex(int p, int k, int f, VesselPeriod[] vps, Subblock[] subblocks, Flow[] flows,
                           Map<VesselPeriod, Integer> vpIndex, int[][] activeVpsByTime, int[][] periodTimesByVp,
                           int[] requiredSubblocks, int[][] incomingFlowsByVp, int[][] outgoingFlowsByVp,
                           int[][] unloadTimesByFlow, int[][] loadTimesByVp, int[][][] routeUnload,
                           int[][][] routeLoad, double[][] distance) {
            P = p;
            K = k;
            F = f;
            this.vps = vps;
            this.subblocks = subblocks;
            this.flows = flows;
            this.vpIndex = vpIndex;
            this.activeVpsByTime = activeVpsByTime;
            this.periodTimesByVp = periodTimesByVp;
            this.requiredSubblocks = requiredSubblocks;
            this.incomingFlowsByVp = incomingFlowsByVp;
            this.outgoingFlowsByVp = outgoingFlowsByVp;
            this.unloadTimesByFlow = unloadTimesByFlow;
            this.loadTimesByVp = loadTimesByVp;
            this.routeUnload = routeUnload;
            this.routeLoad = routeLoad;
            this.distance = distance;
        }

        static ModelIndex build(Instance instance) {
            List<VesselPeriod> vpList = instance.getVesselPeriods();
            List<Subblock> subblockList = instance.getSubblocks();
            VesselPeriod[] vps = vpList.toArray(new VesselPeriod[0]);
            Subblock[] subblocks = subblockList.toArray(new Subblock[0]);
            Map<VesselPeriod, Integer> vpIndex = new LinkedHashMap<>();
            for (int u = 0; u < vps.length; u++) {
                vpIndex.put(vps[u], u);
            }

            List<List<Integer>> activeByTime = new ArrayList<>(instance.horizon);
            for (int t = 0; t < instance.horizon; t++) {
                activeByTime.add(new ArrayList<>());
            }
            int[][] periodTimesByVp = new int[vps.length][];
            int[] requiredSubblocks = new int[vps.length];
            for (int u = 0; u < vps.length; u++) {
                periodTimesByVp[u] = toIntArray(vps[u].getPeriodInterval().intStream(instance.horizon));
                for (int t : periodTimesByVp[u]) {
                    activeByTime.get(t).add(u);
                }
                requiredSubblocks[u] = MyMathMethods.ceilDiv(vps[u].totalLoadContainers, instance.spaceCapacity);
            }

            List<Flow> flowList = new ArrayList<>();
            for (VesselPeriod src : vps) {
                for (VesselPeriod dst : instance.getDestinationVesselPeriodsOf(src)) {
                    int flowId = flowList.size();
                    flowList.add(new Flow(flowId, src, dst, vpIndex.get(src), vpIndex.get(dst),
                            instance.getTransshipmentTo(src, dst)));
                }
            }
            Flow[] flows = flowList.toArray(new Flow[0]);

            List<List<Integer>> incoming = new ArrayList<>(vps.length);
            List<List<Integer>> outgoing = new ArrayList<>(vps.length);
            for (int u = 0; u < vps.length; u++) {
                incoming.add(new LinkedList<>());
                outgoing.add(new LinkedList<>());
            }
            for (Flow flow : flows) {
                outgoing.get(flow.srcIndex).add(flow.id);
                incoming.get(flow.dstIndex).add(flow.id);
            }

            int[][] unloadTimesByFlow = new int[flows.length][];
            double[][] distance = new double[flows.length][subblocks.length];
            int[][][] routeUnload = new int[flows.length][subblocks.length][];
            for (Flow flow : flows) {
                unloadTimesByFlow[flow.id] = toIntArray(
                        flow.dst.getPeriodInterval().intersection(flow.src.getFeasibleInterval(), instance.horizon));
                for (int k = 0; k < subblocks.length; k++) {
                    Subblock subblock = subblocks[k];
                    distance[flow.id][k] = instance.getDistanceToSubblock(flow.src, subblock)
                            + instance.getDistanceFromSubblock(flow.dst, subblock);
                    routeUnload[flow.id][k] = instance.getRouteToSubblock(flow.src, subblock)
                            .stream().mapToInt(Integer::intValue).toArray();
                }
            }

            int[][] loadTimesByVp = new int[vps.length][];
            int[][][] routeLoad = new int[vps.length][subblocks.length][];
            for (int u = 0; u < vps.length; u++) {
                loadTimesByVp[u] = toIntArray(vps[u].getFeasibleInterval().intStream(instance.horizon));
                for (int k = 0; k < subblocks.length; k++) {
                    routeLoad[u][k] = instance.getRouteFromSubblock(vps[u], subblocks[k])
                            .stream().mapToInt(Integer::intValue).toArray();
                }
            }

            return new ModelIndex(
                    vps.length,
                    subblocks.length,
                    flows.length,
                    vps,
                    subblocks,
                    flows,
                    vpIndex,
                    toArrayByVp(activeByTime),
                    periodTimesByVp,
                    requiredSubblocks,
                    toArrayByVp(incoming),
                    toArrayByVp(outgoing),
                    unloadTimesByFlow,
                    loadTimesByVp,
                    routeUnload,
                    routeLoad,
                    distance);
        }
    }

    private static final class Flow {
        final int id;
        final VesselPeriod src;
        final VesselPeriod dst;
        final int srcIndex;
        final int dstIndex;
        final int containers;

        Flow(int id, VesselPeriod src, VesselPeriod dst, int srcIndex, int dstIndex, int containers) {
            this.id = id;
            this.src = src;
            this.dst = dst;
            this.srcIndex = srcIndex;
            this.dstIndex = dstIndex;
            this.containers = containers;
        }
    }
}
