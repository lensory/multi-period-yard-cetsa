package solver;


import entity.*;
import ilog.concert.*;
import ilog.cplex.IloCplex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import util.MyMathMethods;

import java.io.File;
import java.util.*;

public class CplexOriginalModel {
    private static final Logger LOGGER = LoggerFactory.getLogger(CplexOriginalModel.class);

    public final Instance instance;
    public IloCplex cplex;
    private final boolean nameCplexObjects;
    private final String lpExportFileName;

    private final double PRECISION = 1e-6;
    private final int M = 1000000;
    private final int horizon, roads;

    // Variables
    private HashMap<Vessel, HashMap<Subblock, IloIntVar[]>> varX;
    public HashMap<VesselPeriod, HashMap<Subblock, IloIntVar>> varY;
    private HashMap<VesselPeriod, HashMap<VesselPeriod, HashMap<Subblock, IloIntVar>>> varZ;
    private HashMap<VesselPeriod, HashMap<VesselPeriod, HashMap<Subblock, IloIntVar>>> varW;
    private HashMap<Vessel, HashMap<Vessel, HashMap<Subblock, IloIntVar[]>>> varDeltaU;
    private HashMap<Vessel, HashMap<Subblock, IloIntVar[]>> varDeltaL;

    private HashMap<Vessel, IloIntVar[]> varPiU, varPiL, varPiUD;

    // defined as the time steps after the period start.
    private HashMap<VesselPeriod, IloIntVar> varEpsilonU, varSigmaU, varEpsilonL, varSigmaL, varIota, varKappa;
    private HashMap<Subblock, IloIntVar[]> varRho;

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

    private CplexOriginalModel(Instance instance, IloCplex cplex) throws IloException {
        this(instance, cplex, false, null);
    }

    private CplexOriginalModel(Instance instance, IloCplex cplex, boolean nameCplexObjects, String lpExportFileName) throws IloException {
        this.instance = instance;
        this.horizon = instance.horizon;
        this.roads = instance.roads;

        this.cplex = cplex;
        this.nameCplexObjects = nameCplexObjects;
        this.lpExportFileName = lpExportFileName;

//        buildOriginalModel();
//        buildConciseModel();
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
        LOGGER.info("Build {} start: vessels={}, periods={}, subblocks={}, horizon={}, roads={}, namedObjects={}",
                modelName, instance.getNumVessels(), instance.getNumVesselPeriods(),
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

    public static CplexOriginalModel buildOriginalIntegratedModel(Instance instance, IloCplex cplex) throws IloException {
        CplexOriginalModel model = new CplexOriginalModel(instance, cplex);

        model.logBuildStart("original integrated model");
        model.runBuildStep("variables/X", model::initVarX);
        model.runBuildStep("variables/Y", model::initVarY);
        model.runBuildStep("variables/Z", model::initVarZ);
        model.runBuildStep("variables/W", model::initVarW);
        model.runBuildStep("variables/DeltaU", model::initVarDeltaU);
        model.runBuildStep("variables/DeltaL", model::initVarDeltaL);
        model.runBuildStep("variables/EpsilonSigma", model::initVarEpsilonSigma);
        model.runBuildStep("variables/Rho", model::initVarRho);
        model.runBuildStep("variables/RoadFlow", model::initVarRoadFlow);

        model.runBuildStep("constraints/YardTemplate", model::initYardTemplateConstraints);
        model.runBuildStep("constraints/StorageAllocation", model::initStorageAllocationConstraints);
        model.runBuildStep("constraints/OriginalHandlingTime", model::initOriginalHandlingTimeConstraints);
        model.runBuildStep("constraints/Congestion", model::initCongestionConstraints);

        model.runBuildStep("objective/Route", model::initObjRoute);
        model.runBuildStep("objective/Time", model::initObjTime);
        model.runBuildStep("objective/Congestion", model::initObjCongestion);
        model.runBuildStep("objective/AddMinimize", () -> model.addMinimize(cplex.sum(
                model.objRoute,
                model.objTime,
                model.objCongestion
        )));
        model.logBuildSummary("original integrated model");

        return model;
    }

    public static CplexOriginalModel buildCompactIntegratedModel(Instance instance, IloCplex cplex) throws IloException {
        return buildCompactIntegratedModel(instance, cplex, null);
    }

    public static CplexOriginalModel buildCompactIntegratedModel(Instance instance, IloCplex cplex, String lpExportFileName) throws IloException {
        CplexOriginalModel model = new CplexOriginalModel(instance, cplex, lpExportFileName != null, lpExportFileName);

        model.logBuildStart("compact integrated model");
        model.runBuildStep("variables/X", model::initVarX);
        model.runBuildStep("variables/Y", model::initVarY);
        model.runBuildStep("variables/Z", model::initVarZ);
        model.runBuildStep("variables/W", model::initVarW);
        model.runBuildStep("variables/DeltaU", model::initVarDeltaU);
        model.runBuildStep("variables/DeltaL", model::initVarDeltaL);
        model.runBuildStep("variables/EpsilonSigma", model::initVarEpsilonSigma);
        model.runBuildStep("variables/Rho", model::initVarRho);
        model.runBuildStep("variables/Pi", model::initVarPi);
        model.runBuildStep("variables/RoadFlow", model::initVarRoadFlow);

        model.runBuildStep("constraints/YardTemplate", model::initYardTemplateConstraints);
        model.runBuildStep("constraints/StorageAllocation", model::initStorageAllocationConstraints);
        model.runBuildStep("constraints/BinaryHandlingTime", model::initBinaryHandlingTimeConstraints);
        model.runBuildStep("constraints/Congestion", model::initCongestionConstraints);

        model.runBuildStep("objective/Route", model::initObjRoute);
        model.runBuildStep("objective/Time", model::initObjTime);
        model.runBuildStep("objective/Congestion", model::initObjCongestion);
        model.runBuildStep("objective/AddMinimize", () -> model.addMinimize(cplex.sum(
                model.objRoute,
                model.objTime,
                model.objCongestion
        )));
        model.logBuildSummary("compact integrated model");

        return model;
    }

    public static CplexOriginalModel buildYardTemplateStorageAllocationModel(Instance instance, IloCplex cplex) throws IloException {
        CplexOriginalModel model = new CplexOriginalModel(instance, cplex);

        model.logBuildStart("yard-template storage-allocation model");
        model.runBuildStep("variables/X", model::initVarX);
        model.runBuildStep("variables/Y", model::initVarY);
        model.runBuildStep("variables/Z", model::initVarZ);
        model.runBuildStep("variables/W", model::initVarW);

        model.runBuildStep("constraints/YardTemplate", model::initYardTemplateConstraints);
        model.runBuildStep("constraints/StorageAllocation", model::initStorageAllocationConstraints);

        model.runBuildStep("objective/Route", model::initObjRoute);
        model.runBuildStep("objective/AddMinimize", () -> model.addMinimize(model.objRoute));
        model.logBuildSummary("yard-template storage-allocation model");

        return model;
    }


    private void initVarX() throws IloException {
        varX = new HashMap<>(instance.getNumVessels());
        for (Vessel vessel : instance.getVessels()) {
            HashMap<Subblock, IloIntVar[]> _varX = new HashMap<>(instance.getNumSubblocks());
            for (Subblock k : instance.getSubblocks()) {
                IloIntVar[] __varX = new IloIntVar[horizon];
                for (int t = 0; t < horizon; t++) {
                    __varX[t] = boolVar("X_%d_%d_%d", vessel.getVid(), k.getId(), t);
                }
                _varX.put(k, __varX);
            }
            varX.put(vessel, _varX);
        }
    }

    private void initVarY() throws IloException {
        varY = new HashMap<>(instance.getNumVesselPeriods());
        for (VesselPeriod vesselPeriod : instance.getVesselPeriods()) {
            HashMap<Subblock, IloIntVar> _varY = new HashMap<>(instance.getNumSubblocks());
            for (Subblock k : instance.getSubblocks()) {
                _varY.put(k, boolVar("Y_%d_%d_%d", vesselPeriod.getVid(), vesselPeriod.getPid(), k.getId()));
            }
            varY.put(vesselPeriod, _varY);
        }
    }

    private void initVarZ() throws IloException {
        varZ = new HashMap<>();
        for (VesselPeriod vesselPeriod : instance.getVesselPeriods()) {
            HashMap<VesselPeriod, HashMap<Subblock, IloIntVar>> _varZ = new HashMap<>();
            for (VesselPeriod that : instance.getDestinationVesselPeriodsOf(vesselPeriod)) {
                HashMap<Subblock, IloIntVar> __varZ = new HashMap<>();
                for (Subblock k : instance.getSubblocks()) {
                    __varZ.put(k, boolVar("Z_%d_%d_%d_%d_%d",
                            vesselPeriod.getVid(), vesselPeriod.getPid(), k.getId(), that.getVid(), that.getPid()));
                }
                _varZ.put(that, __varZ);
            }
            varZ.put(vesselPeriod, _varZ);
        }
    }

    private void initVarW() throws IloException {
        varW = new HashMap<>();
        for (VesselPeriod vesselPeriod : instance.getVesselPeriods()) {
            HashMap<VesselPeriod, HashMap<Subblock, IloIntVar>> _varW = new HashMap<>();
            for (VesselPeriod that : instance.getDestinationVesselPeriodsOf(vesselPeriod)) {
                HashMap<Subblock, IloIntVar> __varW = new HashMap<>();
                for (Subblock k : instance.getSubblocks()) {
                    __varW.put(k, intVar(0, instance.spaceCapacity, "W_%d_%d_%d_%d_%d",
                            vesselPeriod.getVid(), vesselPeriod.getPid(), k.getId(), that.getVid(), that.getPid()));
                }
                _varW.put(that, __varW);
            }
            varW.put(vesselPeriod, _varW);
        }
    }

    private void initVarDeltaU() throws IloException {
        varDeltaU = new HashMap<>();
        for (Vessel vessel : instance.getVessels()) {
            HashMap<Vessel, HashMap<Subblock, IloIntVar[]>> _varDeltaU = new HashMap<>();
            for (Vessel that : instance.getVessels()) {
                if (vessel == that) continue;
                HashMap<Subblock, IloIntVar[]> __varDeltaU = new HashMap<>();
                for (Subblock k : instance.getSubblocks()) {
                    IloIntVar[] ___varDeltaU = new IloIntVar[horizon];
                    for (int t = 0; t < horizon; t++) {
                        ___varDeltaU[t] = boolVar("DeltaU_%d_%d_%d_%d", vessel.getVid(), that.getVid(), k.getId(), t);
                    }
                    __varDeltaU.put(k, ___varDeltaU);
                }
                _varDeltaU.put(that, __varDeltaU);
            }
            varDeltaU.put(vessel, _varDeltaU);
        }
    }

    private void initVarDeltaL() throws IloException {
        varDeltaL = new HashMap<>();
        for (Vessel vessel : instance.getVessels()) {
            HashMap<Subblock, IloIntVar[]> _varDeltaL = new HashMap<>();
            for (Subblock k : instance.getSubblocks()) {
                IloIntVar[] __varDeltaL = new IloIntVar[horizon];
                for (int t = 0; t < horizon; t++) {
                    __varDeltaL[t] = boolVar("DeltaL_%d_%d_%d", vessel.getVid(), k.getId(), t);
                }
                _varDeltaL.put(k, __varDeltaL);
            }
            varDeltaL.put(vessel, _varDeltaL);
        }
    }

    private void initVarEpsilonSigma() throws IloException {
        varEpsilonU = new HashMap<>();
        varSigmaU = new HashMap<>();
        varEpsilonL = new HashMap<>();
        varSigmaL = new HashMap<>();
        varIota = new HashMap<>();
        varKappa = new HashMap<>();
        for (Vessel v : instance.getVessels())
            for (VesselPeriod ip : v.getPeriods()) {
                varEpsilonU.put(ip, intVar(ip.getRelativeFeasibleIntervalStart(), ip.getRelativeFeasibleIntervalEnd() - 1, "EpsilonU_%d_%d", ip.getVid(), ip.getPid()));
                varEpsilonL.put(ip, intVar(ip.getRelativeFeasibleIntervalStart(), ip.getRelativeFeasibleIntervalEnd() - 1, "EpsilonL_%d_%d", ip.getVid(), ip.getPid()));
                varSigmaU.put(ip, intVar(ip.getRelativeFeasibleIntervalStart(), ip.getRelativeFeasibleIntervalEnd(), "SigmaU_%d_%d", ip.getVid(), ip.getPid()));
                varSigmaL.put(ip, intVar(ip.getRelativeFeasibleIntervalStart(), ip.getRelativeFeasibleIntervalEnd(), "SigmaL_%d_%d", ip.getVid(), ip.getPid()));
                varIota.put(ip, intVar(0, ip.getRelativeExpectedIntervalStart() - ip.getRelativeFeasibleIntervalStart(), "Iota_%d_%d", ip.getVid(), ip.getPid()));
                varKappa.put(ip, intVar(0, ip.getRelativeFeasibleIntervalEnd() - ip.getRelativeExpectedIntervalStart(), "Kappa_%d_%d", ip.getVid(), ip.getPid()));
            }
    }

    private void initVarRho() throws IloException {
        varRho = new HashMap<>(instance.getNumSubblocks());
        for (Subblock k : instance.getSubblocks()) {
            IloIntVar[] _varRho = new IloIntVar[horizon];
            for (int t = 0; t < horizon; t++) {
                _varRho[t] = boolVar("Rho_%d_%d", k.getId(), t);
            }
            varRho.put(k, _varRho);
        }
    }

    private void initVarPi() throws IloException {
        varPiU = new HashMap<>();
        varPiL = new HashMap<>();
        varPiUD = new HashMap<>();
        for (Vessel v : instance.getVessels()) {
            IloIntVar[] _varPiU = new IloIntVar[instance.horizon];
            IloIntVar[] _varPiL = new IloIntVar[instance.horizon];
            IloIntVar[] _varPiUD = new IloIntVar[instance.horizon];
            for (int t = 0; t < horizon; t++) {
                _varPiU[t] = boolVar("PiU_%d_%d", v.getVid(), t);
                _varPiL[t] = boolVar("PiL_%d_%d", v.getVid(), t);
                _varPiUD[t] = boolVar("PiUD_%d_%d", v.getVid(), t);
            }
            varPiU.put(v, _varPiU);
            varPiL.put(v, _varPiL);
            varPiUD.put(v, _varPiUD);
        }
    }

    private void initVarRoadFlow() throws IloException {
        varLoadOverload = intVar(0, Integer.MAX_VALUE, "largestLoadFlow");
        varUnloadOverload = intVar(0, Integer.MAX_VALUE, "largestUnloadFlow");

//        loadFlows = new IloIntVar[roads][horizon];
//        unloadFlows = new IloIntVar[roads][horizon];
//        for (int l = 0; l < roads; l++)
//            for (int t = 0; t < horizon; t++) {
//                loadFlows[l][t] = cplex.intVar(0, M, "loadFlow" + l + "," + t);
//                unloadFlows[l][t] = cplex.intVar(0, M, "unloadFlow" + l + "," + t);
//            }
    }

//    private IloIntVar[][] loadFlows;
//    private IloIntVar[][] unloadFlows;


    private void initYardTemplateConstraints() throws IloException {
// Yard Template Constraints
        for (Subblock k : instance.getSubblocks())
            for (int t = 0; t < horizon; t++) {
                IloLinearIntExpr expr = cplex.linearIntExpr();
                for (Vessel v : instance.getVessels())
                    expr.addTerm(1, varX.get(v).get(k)[t]);
                addLe(expr, 1, "ConsYardX%d,%d", k.getId(), t);
            }


        for (VesselPeriod vp : instance.getVesselPeriods()) {
            IloLinearIntExpr expr = cplex.linearIntExpr();
            for (Subblock k : instance.getSubblocks())
                expr.addTerm(1, varY.get(vp).get(k));
            addLe(expr, MyMathMethods.ceilDiv(vp.totalLoadContainers, instance.spaceCapacity),
                    "ConsYardMax%d,%d", vp.getVid(), vp.getPid());
        }

        for (VesselPeriod vp : instance.getVesselPeriods())
            for (Subblock k : instance.getSubblocks()) {
                IloLinearIntExpr expr = cplex.linearIntExpr();
//                for (int t = vp.getFirstPeriodTimeStep(); t <= vp.getLastPeriodTimeStep(); t++)
//                    expr.addTerm(1, varX.get(vp.vessel).get(k)[getOriginalTimeStep(vp.vessel, t)]);

                vp.getPeriodInterval().intStream(instance.horizon).forEach(t -> {
                    try {
                        expr.addTerm(1, varX.get(instance.getVesselOf(vp)).get(k)[instance.getOriginalTimeStep(vp, t)]);
                    } catch (IloException e) {
                        throw new RuntimeException(e);
                    }
                });
                expr.addTerm(-1 * (vp.getPeriodInterval().getLength()), varY.get(vp).get(k));
                addEq(expr, 0, "ConsYardY%d,%d,%d", vp.getVid(), vp.getPid(), k.getId());
            }

        for (VesselPeriod ip : instance.getVesselPeriods())
            for (Subblock k : instance.getSubblocks()) {
                for (VesselPeriod jq : instance.getSourceVesselPeriodsOf(ip))
                    addLe(varZ.get(jq).get(ip).get(k), varY.get(ip).get(k),
                            "ConsYardZY%d,%d,%d,%d,%d",
                            jq.getVid(), jq.getPid(), ip.getVid(), ip.getPid(), k.getId());
            }
    }

    private void initStorageAllocationConstraints() throws IloException {

        // Storage Allocation Constraints
        for (VesselPeriod ip : instance.getVesselPeriods())
            for (VesselPeriod jq : instance.getSourceVesselPeriodsOf(ip))
                for (Subblock k : instance.getSubblocks())
                    addLe(varW.get(jq).get(ip).get(k),
                            cplex.prod(instance.spaceCapacity, varZ.get(jq).get(ip).get(k)),
                            "ConsFlowWZ%d,%d,%d,%d,%d", ip.getVid(), ip.getPid(), k.getId(), jq.getVid(), jq.getPid());

        for (VesselPeriod ip : instance.getVesselPeriods())
            for (VesselPeriod jq : instance.getSourceVesselPeriodsOf(ip)) {
                IloLinearIntExpr expr = cplex.linearIntExpr();
                for (Subblock k : instance.getSubblocks())
                    expr.addTerm(1, varW.get(jq).get(ip).get(k));
                addEq(expr, instance.getTransshipmentTo(jq, ip),
                        "ConsFlowN%d,%d,%d,%d", ip.getVid(), ip.getPid(),
                        jq.getVid(), jq.getPid());
            }

        for (VesselPeriod ip : instance.getVesselPeriods())
            for (Subblock k : instance.getSubblocks()) {
                IloLinearIntExpr expr = cplex.linearIntExpr();
                for (VesselPeriod jq : instance.getSourceVesselPeriodsOf(ip))
                    expr.addTerm(1, varW.get(jq).get(ip).get(k));
                addLe(expr, instance.spaceCapacity,
                        "ConsFlowC%d,%d,%d", ip.getVid(), ip.getPid(), k.getId());
            }
    }

    private void initOriginalHandlingTimeConstraints() throws IloException {

        // Handling Time Constraints
//        for (Vessel i : instance.getVessels())
//            for (Subblock k : instance.getSubblocks())
//                for (int t = 0; t < horizon; t++) {
//                    IloLinearIntExpr expr = cplex.linearIntExpr();
//                    for (Vessel j : instance.getVessels())
//                        if (!j.equals(i))
//                            expr.addTerm(1, varDeltaU.get(j).get(i).get(k)[t]);
//                    expr.addTerm(1, varDeltaL.get(i).get(k)[t]);
//                    cplex.addLe(expr, cplex.prod(instance.getNumVessels() + 1, varX.get(i).get(k)[t]),
//                            String.format("ConsHandleX%d,%d,%d", i.getVId(), k.getId(), t));
//                }

        for (Vessel i : instance.getVessels())
            for (VesselPeriod ip : i.getPeriods())
                for (VesselPeriod jq : instance.getSourceVesselPeriodsOf(ip))
                    for (Subblock k : instance.getSubblocks()) {
                        IloLinearIntExpr expr = cplex.linearIntExpr();
                        ip.getPeriodInterval().intersection(jq.getPeriodInterval(), instance.horizon).forEach(t -> {
                            try {
                                expr.addTerm(1, varDeltaU.get(instance.getVesselOf(jq)).get(i).get(k)[t]);
                            } catch (IloException e) {
                                throw new RuntimeException(e);
                            }
                        });
//                        for (int tip = ip.getFirstPeriodTimeStep(); tip <= ip.getLastPeriodTimeStep(); tip++) {
//                            int t = getOriginalTimeStep(i, tip);
//                            int tjq = getExtendedTimeStep(instance.getVesselOf(jq), t);
//                            if (tjq >= jq.getFirstPeriodTimeStep() && tjq <= jq.getLastPeriodTimeStep())
//                                expr.addTerm(1, varDeltaU.get(instance.getVesselOf(jq)).get(i).get(k)[t]);
//                        }
                        addEq(expr, varZ.get(jq).get(ip).get(k),
                                "ConsHandleZ%d,%d,%d,%d,%d",
                                i.getVid(), ip.getPid(), k.getId(), jq.getVid(), jq.getPid());
                    }
        for (VesselPeriod ip : instance.getVesselPeriods())
            for (Subblock k : instance.getSubblocks()) {
                IloLinearIntExpr expr = cplex.linearIntExpr();
                ip.getPeriodInterval().intStream(instance.horizon).forEach(t -> {
                    try {
                        expr.addTerm(1, varDeltaL.get(instance.getVesselOf(ip)).get(k)[t]);
                    } catch (IloException e) {
                        throw new RuntimeException(e);
                    }
                });
//                for (int tip = ip.getFirstPeriodTimeStep(); tip <= ip.getLastPeriodTimeStep(); tip++) {
//                    expr.addTerm(1, varDeltaL.get(ip.vessel).get(k)[getOriginalTimeStep(ip.vessel, tip)]);
//                }
                addEq(expr, varY.get(ip).get(k),
                        "ConsHandleY%d,%d,%d",
                        ip.getVid(), ip.getPid(), k.getId());
            }


        for (Vessel j : instance.getVessels())
            for (VesselPeriod jq : j.getPeriods())
                for (Subblock k : instance.getSubblocks())
                    jq.getPeriodInterval().intStream(instance.horizon).forEach(t -> {
                        try {
                            int tjq = jq.getPeriodInterval().shiftsFromStart(t, instance.horizon);
                            for (Vessel i : instance.getVessels())
                                if (i != j) {
                                    IloIntExpr bigM = cplex.prod(jq.getLengthOfPeriod(),
                                            cplex.diff(1, varDeltaU.get(j).get(i).get(k)[t]));

                                    addLe(varEpsilonU.get(jq),
                                            cplex.sum(tjq, bigM),
                                            "ConsHandleEpsilonU%d,%d,%d,%d,%d",
                                            jq.getVid(), jq.getPid(), tjq, i.getVid(), k.getId());
                                    addGe(varSigmaU.get(jq),
                                            cplex.diff(tjq + 1, bigM),
                                            "ConsHandleSigmaU%d,%d,%d,%d,%d",
                                            jq.getVid(), jq.getPid(), tjq, i.getVid(), k.getId());
                                }
                        } catch (IloException e) {
                            throw new RuntimeException(e);
                        }
                    });

//                for (int tjq = jq.getFirstPeriodTimeStep(); tjq <= jq.getLastPeriodTimeStep(); tjq++)
//                    for (Vessel i : instance.getVessels())
//                        if (i != j) {
//                            int t = getOriginalTimeStep(j, tjq);
//                            IloIntExpr bigM = cplex.prod(jq.getLengthOfPeriod(),
//                                    cplex.diff(1, varDeltaU.get(j).get(i).get(k)[t]));
//                            cplex.addLe(varEpsilonU.get(jq),
//                                    cplex.sum(tjq, bigM),
//                                    String.format("ConsHandleEpsilonU%d,%d,%d,%d,%d",
//                                            jq.getVId(), jq.getPId(), tjq, i.getVId(), k.getId()));
//                            cplex.addGe(varSigmaU.get(jq),
//                                    cplex.diff(tjq + 1, bigM),
//                                    String.format("ConsHandleSigmaU%d,%d,%d,%d,%d",
//                                            jq.getVId(), jq.getPId(), tjq, i.getVId(), k.getId()));
//                        }

        for (Vessel i : instance.getVessels())
            for (VesselPeriod ip : i.getPeriods())
                for (Subblock k : instance.getSubblocks())
                    ip.getPeriodInterval().intStream(instance.horizon).forEach(t -> {
                        try {
                            int tip = ip.getPeriodInterval().shiftsFromStart(t, instance.horizon);
                            IloIntExpr bigM = cplex.prod(ip.getLengthOfPeriod(),
                                    cplex.diff(1, varDeltaL.get(i).get(k)[t]));
                            addLe(varEpsilonL.get(ip),
                                    cplex.sum(tip, bigM),
                                    "ConsHandleEpsilonL%d,%d,%d,%d",
                                    ip.getVid(), ip.getPid(), tip, k.getId());
                            addGe(varSigmaL.get(ip),
                                    cplex.diff(tip + 1, bigM),
                                    "ConsHandleSigmaL%d,%d,%d,%d",
                                    ip.getVid(), ip.getPid(), tip, k.getId());
                        } catch (IloException e) {
                            throw new RuntimeException(e);
                        }
                    });
//                    for (int tip = ip.getFirstPeriodTimeStep(); tip <= ip.getLastPeriodTimeStep(); tip++) {
//                        int t = getOriginalTimeStep(i, tip);
//                        IloIntExpr bigM = cplex.prod(ip.getLengthOfPeriod(),
//                                cplex.diff(1, varDeltaL.get(i).get(k)[t]));
//                        cplex.addLe(varEpsilonL.get(ip),
//                                cplex.sum(tip, bigM),
//                                String.format("ConsHandleEpsilonL%d,%d,%d,%d",
//                                        ip.getVId(), ip.getPId(), tip, k.getId()));
//                        cplex.addGe(varSigmaL.get(ip),
//                                cplex.diff(tip + 1, bigM),
//                                String.format("ConsHandleSigmaL%d,%d,%d,%d",
//                                        ip.getVId(), ip.getPId(), tip, k.getId()));
//                    }

        for (Vessel i : instance.getVessels())
            for (VesselPeriod ip : i.getPeriods())
                ip.getPeriodInterval().intStream(instance.horizon).forEach(t -> {
                    try {
                        int tip = ip.getPeriodInterval().shiftsFromStart(t, instance.horizon);
                        for (Vessel j : instance.getVessels())
                            if (!i.equals(j))
                                for (Subblock k : instance.getSubblocks()) {
                                    IloIntExpr bigM = cplex.prod(ip.getLengthOfPeriod(),
                                            cplex.diff(1, varDeltaU.get(j).get(i).get(k)[t]));
                                    addGe(varEpsilonL.get(ip),
                                            cplex.diff(tip + 1, bigM),
                                            "ConsHandleTrans%d,%d,%d,%d,%d",
                                            ip.getVid(), ip.getPid(), tip, j.getVid(), k.getId());
                                }
                    } catch (IloException e) {
                        throw new RuntimeException(e);
                    }
                });

//                for (int tip = ip.getFirstPeriodTimeStep(); tip <= ip.getLastPeriodTimeStep(); tip++)
//                    for (Vessel j : instance.getVessels())
//                        if (!i.equals(j))
//                            for (Subblock k : instance.getSubblocks()) {
//                                int t = getOriginalTimeStep(i, tip);
//                                IloIntExpr bigM = cplex.prod(ip.getLengthOfPeriod(),
//                                        cplex.diff(1, varDeltaU.get(j).get(i).get(k)[t]));
//                                cplex.addGe(varEpsilonL.get(ip),
//                                        cplex.diff(tip + 1, bigM),
//                                        String.format("ConsHandleTrans%d,%d,%d,%d,%d",
//                                                ip.getVId(), ip.getPId(), tip, j.getVId(), k.getId()));
//                            }

        for (Vessel i : instance.getVessels())
            for (VesselPeriod ip : i.getPeriods()) {
                addLe(varEpsilonU.get(ip), varSigmaU.get(ip),
                        "ConsHandleOrderEU_SU%d,%d", ip.getVid(), ip.getPid());
                addLe(varSigmaU.get(ip), varEpsilonL.get(ip),
                        "ConsHandleOrderSU_EL%d,%d", ip.getVid(), ip.getPid());
                addLe(varEpsilonL.get(ip), varSigmaL.get(ip),
                        "ConsHandleOrderEL_SL%d,%d", ip.getVid(), ip.getPid());
                addGe(varIota.get(ip), cplex.diff(ip.getRelativeExpectedIntervalStart(), varEpsilonU.get(ip)),
                        "ConsHandleIotaBase%d,%d", ip.getVid(), ip.getPid());
                addGe(varKappa.get(ip), cplex.diff(varSigmaL.get(ip), ip.getRelativeExpectedIntervalEnd()),
                        "ConsHandleKappaBase%d,%d", ip.getVid(), ip.getPid());
            }

    }

    private void initBinaryHandlingTimeConstraints() throws IloException {

        for (VesselPeriod ip : instance.getVesselPeriods())
            for (VesselPeriod jq : instance.getSourceVesselPeriodsOf(ip))
                for (Subblock k : instance.getSubblocks()) {
                    IloLinearIntExpr expr = cplex.linearIntExpr();
                    ip.getPeriodInterval().intersection(jq.getPeriodInterval(), instance.horizon).forEach(t -> {
                        try {
                            expr.addTerm(1, varDeltaU.get(instance.getVesselOf(jq)).get(instance.getVesselOf(ip)).get(k)[t]);
                        } catch (IloException e) {
                            throw new RuntimeException(e);
                        }
                    });
                    addEq(expr, varZ.get(jq).get(ip).get(k),
                            "ConsHandleZ%d,%d,%d,%d,%d",
                            ip.getVid(), ip.getPid(), k.getId(), jq.getVid(), jq.getPid());
                }
        for (VesselPeriod ip : instance.getVesselPeriods())
            for (Subblock k : instance.getSubblocks()) {
                IloLinearIntExpr expr = cplex.linearIntExpr();
                ip.getPeriodInterval().intStream(instance.horizon).forEach(t -> {
                    try {
                        expr.addTerm(1, varDeltaL.get(instance.getVesselOf(ip)).get(k)[t]);
                    } catch (IloException e) {
                        throw new RuntimeException(e);
                    }
                });
                addEq(expr, varY.get(ip).get(k),
                        "ConsHandleY%d,%d,%d",
                        ip.getVid(), ip.getPid(), k.getId());
            }

        for (Vessel j : instance.getVessels()) {
            HashMap<Vessel, HashMap<Subblock, IloIntVar[]>> _varDeltaU = varDeltaU.get(j);
            for (int t = 0; t < horizon; t++) {
                IloIntVar lhs = varPiU.get(j)[t];
                for (Vessel i : instance.getVessels())
                    if (!i.equals(j))
                        for (Subblock k : instance.getSubblocks()) {
                            IloIntVar rhs = _varDeltaU.get(i).get(k)[t];
                            addGe(lhs, rhs, "ConsHandlePiU%d,%d,%d,%d",
                                    j.getVid(), i.getVid(), k.getId(), t);
                        }
            }
        }

        for (Vessel i : instance.getVessels()) {
            HashMap<Subblock, IloIntVar[]> _varDeltaL = varDeltaL.get(i);
            for (int t = 0; t < horizon; t++) {
                IloIntVar lhs = varPiL.get(i)[t];
                for (Subblock k : instance.getSubblocks()) {
                    IloIntVar rhs = _varDeltaL.get(k)[t];
                    addGe(lhs, rhs, "ConsHandlePiL%d,%d,%d",
                            i.getVid(), k.getId(), t);
                }
            }
        }

        for (Vessel i : instance.getVessels()) {
            for (int t = 0; t < horizon; t++) {
                IloIntVar lhs = varPiUD.get(i)[t];
                for (Vessel j : instance.getVessels())
                    if (!i.equals(j))
                        for (Subblock k : instance.getSubblocks()) {
                            IloIntVar rhs = varDeltaU.get(j).get(i).get(k)[t];
                            addGe(lhs, rhs, "ConsHandlePiUD%d,%d,%d,%d",
                                    j.getVid(), i.getVid(), k.getId(), t);
                        }
            }
        }

        for (VesselPeriod ip : instance.getVesselPeriods()) {
            int a = ip.getRelativeFeasibleIntervalStart();
            int b = ip.getRelativeFeasibleIntervalEnd();
            int relativeTimeStep = 0;
            Iterator<Integer> it = ip.getPeriodInterval().intStream(instance.horizon).iterator();
            while (it.hasNext()) {
                int t = it.next();
                // t \notin [a, b)
                if (relativeTimeStep < a || relativeTimeStep >= b)
                    addLe(cplex.sum(varPiU.get(instance.getVesselOf(ip))[t], varPiL.get(instance.getVesselOf(ip))[t]), 0,
                            "ConsHandlePiUL%d,%d,%d", ip.getVid(), ip.getPid(), t);
                else
                    addLe(cplex.sum(varPiU.get(instance.getVesselOf(ip))[t], varPiL.get(instance.getVesselOf(ip))[t]), 1,
                            "ConsHandlePiUL%d,%d,%d", ip.getVid(), ip.getPid(), t);
                relativeTimeStep++;
            }
        }

        for (VesselPeriod ip : instance.getVesselPeriods()) {
            int relativeTimeStep = 0;
            for (int t : ip.getPeriodInterval().intStream(instance.horizon)) {
                IloLinearIntExpr expr = cplex.linearIntExpr();
                ip.getPeriodInterval().intStream(instance.horizon).skip(relativeTimeStep).forEach(s -> {
                    try {
                        expr.addTerm(1, varPiU.get(instance.getVesselOf(ip))[s]);
                        expr.addTerm(1, varPiUD.get(instance.getVesselOf(ip))[s]);
                    } catch (IloException e) {
                        throw new RuntimeException(e);
                    }
                });


                addLe(expr,
                        cplex.prod(2 * (ip.getLengthOfPeriod() - relativeTimeStep),
                                cplex.diff(1, varPiL.get(instance.getVesselOf(ip))[t])),
                        "ConsHandlePiUUD%d,%d,%d", ip.getVid(), ip.getPid(), t);
                relativeTimeStep++;
            }
        }


        for (VesselPeriod ip : instance.getVesselPeriods()) {
            int relativeTimeStep = 0;
            int expA = ip.getRelativeExpectedIntervalStart();
            int expB = ip.getRelativeExpectedIntervalEnd();

            for (int t : ip.getPeriodInterval().intStream(instance.horizon)) {
                IloIntExpr bigM = cplex.prod(ip.getLengthOfPeriod(),
                        cplex.diff(1, cplex.sum(varPiU.get(instance.getVesselOf(ip))[t], varPiL.get(instance.getVesselOf(ip))[t]))
                );
                addGe(varIota.get(ip),
                        cplex.diff(expA - relativeTimeStep, bigM),
                        "ConsHandleIota%d,%d,%d",
                        ip.getVid(), ip.getPid(), t);
                addGe(varKappa.get(ip),
                        cplex.diff(relativeTimeStep + 1 - expB, bigM),
                        "ConsHandleKappa%d,%d,%d",
                        ip.getVid(), ip.getPid(), t);
                relativeTimeStep++;
            }
        }


    }

    private void initCongestionConstraints() throws IloException {

        // Congestion Constraints
        for (Vessel j : instance.getVessels())
            for (int t = 0; t < horizon; t++)
                for (Subblock k : instance.getSubblocks()) {
                    IloLinearIntExpr expr = cplex.linearIntExpr();
                    for (Vessel i : instance.getVessels())
                        if (i != j)
                            expr.addTerm(1, varDeltaU.get(j).get(i).get(k)[t]);
                    addLe(expr, varRho.get(k)[t], "ConsCongU%d,%d,%d",
                            j.getVid(), k.getId(), t);
                }

        for (int t = 0; t < horizon; t++)
            for (Subblock k : instance.getSubblocks()) {
                IloLinearIntExpr expr = cplex.linearIntExpr();
                for (Vessel i : instance.getVessels())
                    expr.addTerm(1, varDeltaL.get(i).get(k)[t]);
                addLe(expr, varRho.get(k)[t], "ConsCongL%d,%d",
                        k.getId(), t);
            }

        for (Subblock k1 : instance.getSubblocks())
            for (Subblock k2 : instance.getSubblocks())
                if ((!k1.equals(k2)) && (k1.isNeighborInSameBlock(k2) || k1.isNeighborAcrossLane(k2)))
                    for (int t = 0; t < horizon; t++) {
                        addLe(cplex.sum(varRho.get(k1)[t], varRho.get(k2)[t]), 1,
                                "ConsCongRho%d,%d,%d", k1.getId(), k2.getId(), t);
                    }


        IloLinearIntExpr[][] exprU = new IloLinearIntExpr[roads][horizon];

        for (int l = 0; l < roads; l++)
            for (int t = 0; t < horizon; t++) {
                exprU[l][t] = cplex.linearIntExpr();
            }
        for (Vessel j : instance.getVessels())
            for (VesselPeriod jq : j.getPeriods())
                for (Subblock k : instance.getSubblocks()) {
                    for (int l : instance.getRouteToSubblock(jq, k))
                        jq.getPeriodInterval().intStream(instance.horizon).forEach(t -> {
                            for (Vessel i : instance.getVessels())
                                if (i != j) {
                                    try {
                                        exprU[l][t].addTerm(1, varDeltaU.get(j).get(i).get(k)[t]);
                                    } catch (IloException e) {
                                        throw new RuntimeException(e);
                                    }
                                }
                        });
//                        for (int tjq = jq.getFirstExpectedTimeStep(); tjq <= jq.getLastPeriodTimeStep(); tjq++) {
//                            int t = getOriginalTimeStep(j, tjq);
//                            for (Vessel i : instance.getVessels())
//                                if (i != j)
//                                    exprU[l][t].addTerm(1, varDeltaU.get(j).get(i).get(k)[t]);
//                        }
                }
        for (int l = 0; l < roads; l++)
            for (int t = 0; t < horizon; t++) {
                addLe(exprU[l][t], cplex.sum(instance.maxUnloadFlows, varUnloadOverload),
                        "ConsCongRoadU%d,%d", l, t);
            }

        IloLinearIntExpr[][] exprL = new IloLinearIntExpr[roads][horizon];
        for (int l = 0; l < roads; l++)
            for (int t = 0; t < horizon; t++) {
                exprL[l][t] = cplex.linearIntExpr();
            }
        for (Vessel i : instance.getVessels())
            for (VesselPeriod ip : i.getPeriods())
                for (Subblock k : instance.getSubblocks()) {
                    for (int l : instance.getRouteFromSubblock(ip, k))
                        ip.getPeriodInterval().intStream(instance.horizon).forEach(t -> {
                            try {
                                exprL[l][t].addTerm(1, varDeltaL.get(i).get(k)[t]);
                            } catch (IloException e) {
                                throw new RuntimeException(e);
                            }
                        });
//                        for (int tip = ip.getFirstPeriodTimeStep(); tip <= ip.getLastPeriodTimeStep(); tip++) {
//                            int t = getOriginalTimeStep(i, tip);
//                            exprL[l][t].addTerm(1, varDeltaL.get(i).get(k)[t]);
//                        }
                }
        for (int l = 0; l < roads; l++)
            for (int t = 0; t < horizon; t++) {
                addLe(exprL[l][t], cplex.sum(instance.maxLoadFlows, varLoadOverload),
                        "ConsCongRoadL%d,%d", l, t);
            }
    }

    private void initObjRoute() throws IloException {
        objRoute = cplex.linearNumExpr();
        for (VesselPeriod ip : instance.getVesselPeriods())
            for (VesselPeriod jq : instance.getSourceVesselPeriodsOf(ip))
                for (Subblock k : instance.getSubblocks()) {
                    double distance = instance.getDistanceToSubblock(jq, k) + instance.getDistanceFromSubblock(ip, k);
                    addObjectiveTerm(objRoute, distance * instance.etaRoute, varW.get(jq).get(ip).get(k));
                }
    }

    private void initObjTime() throws IloException {
        objTime = cplex.linearNumExpr();
        for (Vessel i : instance.getVessels())
            for (VesselPeriod ip : i.getPeriods()) {
                addObjectiveTerm(objTime, ip.getEarlinessCost(), varIota.get(ip));
                addObjectiveTerm(objTime, ip.getTardinessCost(), varKappa.get(ip));
            }
    }

    private void initObjCongestion() throws IloException {
        objCongestion = cplex.linearNumExpr();
        addObjectiveTerm(objCongestion, instance.etaCongestion, varUnloadOverload);
        addObjectiveTerm(objCongestion, instance.etaCongestion, varLoadOverload);
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
            throw new IllegalArgumentException("Not a Bool");

        return Math.abs(v - 1) < PRECISION;
    }

    public void setPriorityOnY() throws IloException {
        for (VesselPeriod ip : instance.getVesselPeriods())
            for (Subblock k : instance.getSubblocks()) {
                cplex.setPriority(varY.get(ip).get(k), 1);
            }
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

    public Map<VesselPeriod, Set<Subblock>> getSubblockAssignment() throws IloException {
        validateCplexStatus();

        if (varY == null || varY.isEmpty()) {
            throw new IllegalStateException("Variable varY is not initialized or is empty.");
        }

        Map<VesselPeriod, Set<Subblock>> assignment = new HashMap<>(instance.getNumVesselPeriods());
        for (Map.Entry<VesselPeriod, HashMap<Subblock, IloIntVar>> ipEntry : varY.entrySet()) {
            assignment.put(ipEntry.getKey(), new HashSet<>());
            for (Map.Entry<Subblock, IloIntVar> subblockEntry : ipEntry.getValue().entrySet()) {
                if (isBoolTrue(subblockEntry.getValue()))
                    assignment.get(ipEntry.getKey()).add(subblockEntry.getKey());
            }
        }
        return assignment;
    }

    public Map<VesselPeriod, Map<Subblock, Map<VesselPeriod, Integer>>> getContainerAssignment() throws IloException {
        validateCplexStatus();
        if (varW == null || varW.isEmpty()) {
            throw new IllegalStateException("Variable varW is not initialized or is empty.");
        }

        Map<VesselPeriod, Map<Subblock, Map<VesselPeriod, Integer>>> assignment = new HashMap<>(instance.getNumVesselPeriods());
        for (Map.Entry<VesselPeriod, HashMap<VesselPeriod, HashMap<Subblock, IloIntVar>>> jqEntry : varW.entrySet()) {
            VesselPeriod jq = jqEntry.getKey();
            for (Map.Entry<VesselPeriod, HashMap<Subblock, IloIntVar>> ipEntry : jqEntry.getValue().entrySet()) {
                VesselPeriod ip = ipEntry.getKey();
                for (Map.Entry<Subblock, IloIntVar> subblockEntry : ipEntry.getValue().entrySet()) {
                    Subblock k = subblockEntry.getKey();
                    IloIntVar var = subblockEntry.getValue();
                    int w = getIntValue(var);
                    if (w > 0)
                        assignment.computeIfAbsent(ip, key -> new HashMap<>())
                                .computeIfAbsent(k, key -> new HashMap<>())
                                .put(jq, w);
                }
            }
        }
        return assignment;
    }

    private void recordSubblockAssignment(Solution solution) throws IloException {
        validateCplexStatus();

        if (varY == null || varY.isEmpty()) {
            throw new IllegalStateException("Variable varY is not initialized or is empty.");
        }

        for (VesselPeriod ip : instance.getVesselPeriods()) {
            HashMap<Subblock, IloIntVar> _varY = varY.get(ip);
            for (Subblock k : instance.getSubblocks()) {
                if (isBoolTrue((_varY.get(k))))
                    solution.setSubBlock(ip, k);
            }
        }
    }

    private void recordVesselHandling(Solution solution) throws IloException {
        HashMap<VesselPeriod,
                HashMap<VesselPeriod,
                        HashMap<Subblock, LinkedList<Integer>>>> valDeltaUEqualOne = new HashMap<>();
        for (Vessel j : instance.getVessels()) {
            for (Vessel i : instance.getVessels()) {
                if (j == i) continue;
                for (Subblock k : instance.getSubblocks()) {
                    for (int t = 0; t < horizon; t++) {
                        if (isBoolTrue(varDeltaU.get(j).get(i).get(k)[t])) {
                            VesselPeriod jq = instance.getVesselPeriodAtOriginalTimeStep(j, t);
                            VesselPeriod ip = instance.getVesselPeriodAtOriginalTimeStep(i, t);
                            valDeltaUEqualOne.computeIfAbsent(jq, key -> new HashMap<>())
                                    .computeIfAbsent(ip, key -> new HashMap<>())
                                    .computeIfAbsent(k, key -> new LinkedList<>())
                                    .add(t);
                        }
                    }
                }
            }
        }
        HashMap<VesselPeriod, HashMap<Subblock, LinkedList<Integer>>> valDeltaLEqualOne = new HashMap<>();
        for (Vessel i : instance.getVessels()) {
            for (Subblock k : instance.getSubblocks()) {
                for (int t = 0; t < horizon; t++) {
                    if (isBoolTrue(varDeltaL.get(i).get(k)[t])) {
                        VesselPeriod ip = instance.getVesselPeriodAtOriginalTimeStep(i, t);
                        valDeltaLEqualOne.computeIfAbsent(ip, key -> new HashMap<>())
                                .computeIfAbsent(k, key -> new LinkedList<>())
                                .add(t);
                    }
                }
            }
        }

        HashMap<VesselPeriod, HashMap<Subblock, Integer>> subblockContainers = new HashMap<>();
        for (VesselPeriod jq : instance.getVesselPeriods())

            for (VesselPeriod ip : instance.getDestinationVesselPeriodsOf(jq))
                for (Subblock k : instance.getSubblocks()) {
                    int w = getIntValue(varW.get(jq).get(ip).get(k));
                    if (w != 0) {
                        subblockContainers.computeIfAbsent(ip, key -> new HashMap<>())
                                .compute(k, (key, value) -> value == null ? w : value + w);
                        List<Integer> ts = valDeltaUEqualOne.get(jq).get(ip).get(k);
                        if (ts.size() != 1)
                            throw new RuntimeException("Multiple time steps for single unloading task " +
                                    "(" + jq + " -> " + k + " -> " + ip + "): " + ts);
                        solution.setUnloadSchedule(jq, ip, k, ts.get(0), w);
                    }
                }

        subblockContainers.forEach((ip, kMap) ->
                kMap.forEach((k, number) -> {
                    if (number != 0) {
                        List<Integer> ts = valDeltaLEqualOne.get(ip).get(k);
                        if (ts.size() != 1)
                            throw new RuntimeException("Multiple time steps for single loading task " +
                                    "(" + k + " -> " + ip + "): " + ts);
                        solution.setLoadSchedule(ip, k, ts.get(0), number);
                    }
                }));
    }

    public Solution getSolution() throws IloException {

        IloCplex.Status status = cplex.getStatus();
        if (status != IloCplex.Status.Optimal && status != IloCplex.Status.Feasible) {
            return null;
        } else {
            Solution solution = new Solution(instance);

//            cplex.writeSolution("solution1.sol");
            recordSubblockAssignment(solution);
            recordVesselHandling(solution);
            solution.calculateObjectives();

            solution.setGap(cplex.getMIPRelativeGap());

//            if (Math.abs(solution.getObjRoute() - cplex.getValue(objRoute)) > PRECISION ||
//                    Math.abs(solution.getObjTime() - cplex.getValue(objTime)) > PRECISION ||
//                    Math.abs(solution.getObjCongestion() - cplex.getValue(objCongestion)) > PRECISION ||
//                    Math.abs(solution.getObjAll() - cplex.getObjValue()) > PRECISION
//            ) throw new RuntimeException(
//                    "Objective value mismatch details:\n" +
//                            "Total: solution.objAll=" + solution.getObjAll() + " vs Cplex objValue=" + cplex.getObjValue() + "\n" +
//                            "Route: solution.objRoute=" + solution.getObjRoute() + " vs Cplex objRoute=" + cplex.getValue(objRoute) + "\n" +
//                            "Time: solution.objTime=" + solution.getObjTime() + " vs Cplex objTime=" + cplex.getValue(objTime) + "\n" +
//                            "Congestion: solution.objCongestion=" + solution.getObjCongestion() + " vs Cplex congestion=" + cplex.getValue(objCongestion)
//            );
            return solution;
        }
    }

    public boolean solve() throws IloException {
        return cplex.solve();

    }


    public void fixKeyVariables(Solution solution) throws IloException {

        for (Map.Entry<VesselPeriod, Set<Subblock>> entry : solution.getSubblockAssignments().entrySet()) {
            VesselPeriod key = entry.getKey();
            Set<Subblock> ks = entry.getValue();
            for (Subblock k : instance.getSubblocks()) {
                IloIntVar yVar = varY.get(key).get(k);
                if (ks.contains(k)) {
                    yVar.setUB(1);
                    yVar.setLB(1);
                } else {
                    yVar.setUB(0);
                    yVar.setLB(0);
                }
            }
        }


        varW.forEach((jq, ipMap) -> {
            ipMap.forEach((ip, kMap) -> {
                kMap.forEach((k, wVar) -> {
                    try {
                        wVar.setUB(0);
                        wVar.setLB(0);
                    } catch (IloException e) {
                        throw new RuntimeException(e);
                    }
                });
            });
        });


        varDeltaU.forEach((j, jMap) -> {
            jMap.forEach((i, kMap) -> {
                kMap.forEach((k, tArray) -> {
                    for (IloIntVar deltaUVar : tArray)
                        try {
                            deltaUVar.setUB(0);
                            deltaUVar.setLB(0);
                        } catch (IloException e) {
                            throw new RuntimeException(e);
                        }
                });
            });
        });
        solution.forEachUnloadSchedule((ip, k, jq, schedule) -> {
            IloIntVar wVar = varW.get(jq).get(ip).get(k);
            IloIntVar deltaUVar = varDeltaU.get(instance.getVesselOf(jq)).get(instance.getVesselOf(ip)).get(k)[schedule.time];
            try {
                wVar.setUB(schedule.number);
                wVar.setLB(schedule.number);
                deltaUVar.setUB(1);
                deltaUVar.setLB(1);

            } catch (IloException e) {
                throw new RuntimeException(e);
            }
        });


        varDeltaL.forEach((i, kMap) -> {
            kMap.forEach((k, tArray) -> {
                for (IloIntVar deltaLVar : tArray) {
                    try {
                        deltaLVar.setUB(0);
                        deltaLVar.setLB(0);
                    } catch (IloException e) {
                        throw new RuntimeException(e);
                    }
                }
            });
        });
        solution.forEachLoadSchedule((ip, k, schedule) -> {
            IloIntVar deltaLVar = varDeltaL.get(instance.getVesselOf(ip)).get(k)[schedule.time];
            try {
                deltaLVar.setUB(1);
                deltaLVar.setLB(1);
            } catch (IloException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static File[] listInstanceFiles() {
        File inputDir = new File("input");
        File[] files = inputDir.listFiles((dir, name) -> name.startsWith("instance_") && name.endsWith(".json"));
        if (files == null)
            throw new IllegalStateException("Cannot list instance files from " + inputDir.getAbsolutePath());

        Arrays.sort(files, Comparator
                .comparing(CplexOriginalModel::getInstanceScale)
                .thenComparing(CplexOriginalModel::getInstanceSeed)
                .thenComparing(File::getName));
        return files;
    }

    private static String getInstanceScale(File file) {
        String name = file.getName();
        int start = "instance_".length();
        int seedSeparator = name.lastIndexOf('_');
        if (seedSeparator <= start)
            return name;
        return name.substring(start, seedSeparator);
    }

    private static String getInstanceSeed(File file) {
        String name = file.getName();
        int seedSeparator = name.lastIndexOf('_');
        int extension = name.lastIndexOf('.');
        if (seedSeparator < 0 || extension <= seedSeparator)
            return "";
        return name.substring(seedSeparator + 1, extension);
    }

    private static class FeasibilityStats {
        int total;
        int feasible;
        int notProvenFeasible;
        int error;
    }

    public static void main(String[] args) throws IloException {
        Double timeLimit = args.length > 0 ? Double.parseDouble(args[0]) : null;
        Map<String, FeasibilityStats> statsByScale = new LinkedHashMap<>();

        for (File file : listInstanceFiles()) {
            String scale = getInstanceScale(file);
            String seed = getInstanceSeed(file);
            FeasibilityStats stats = statsByScale.computeIfAbsent(scale, key -> new FeasibilityStats());
            stats.total++;

            IloCplex cplex = null;
            try {
                Instance instance = Instance.readJson(file.getPath());

                cplex = new IloCplex();
                if (timeLimit != null)
                    cplex.setParam(IloCplex.IntParam.TimeLimit, timeLimit);
                CplexOriginalModel model = CplexOriginalModel.buildCompactIntegratedModel(instance, cplex);
                model.varLoadOverload.setUB(0);
                model.varUnloadOverload.setUB(0);

                boolean feasible = model.solve();
                IloCplex.Status status = cplex.getStatus();
                if (feasible) {
                    stats.feasible++;
                    System.out.printf("FEASIBLE scale=%s seed=%s file=%s status=%s obj=%.4f%n",
                            scale, seed, file.getName(), status, cplex.getObjValue());
                } else {
                    stats.notProvenFeasible++;
                    System.out.printf("NOT_PROVEN_FEASIBLE scale=%s seed=%s file=%s status=%s%n",
                            scale, seed, file.getName(), status);
                }
            } catch (Exception e) {
                stats.error++;
                System.out.printf("ERROR scale=%s seed=%s file=%s message=%s%n",
                        scale, seed, file.getName(), e.getMessage());
            } finally {
                if (cplex != null)
                    cplex.end();
            }
        }

        System.out.println();
        System.out.println("Summary by scale:");
        statsByScale.forEach((scale, stats) -> System.out.printf(
                "%s total=%d feasible=%d notProvenFeasible=%d error=%d%n",
                scale, stats.total, stats.feasible, stats.notProvenFeasible, stats.error));

    }

}
