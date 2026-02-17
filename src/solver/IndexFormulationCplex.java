package solver;

import entity.*;
import ilog.concert.*;
import ilog.cplex.IloCplex;

import java.util.*;

public class IndexFormulationCplex {
    public final IloCplex cplex;
    private final Instance instance;
    private final int horizon, roads;
    private final double PRECISION = 1e-6;

    public IndexFormulationCplex(Instance instance, IloCplex cplex) {
        this.instance = instance;
        this.horizon = instance.horizon;
        this.roads = instance.roads;
        this.cplex = cplex;
    }

    public IndexBasedSolution partialSolution;

    private Map<VesselPeriod, Map<Integer, Map<Subblock, IloIntVar>>> varY;

    private HashMap<Vessel, HashMap<Subblock, Map<Integer, IloIntVar>>> varX;
    private Map<VesselPeriod, Map<Integer, Map<VesselPeriod, IloIntVar>>> varW; // ip->k->jq

    // ip->k->t, a^M_ip <= t < b^M_ip
    private Map<VesselPeriod, Map<Integer, Map<Integer, IloIntVar>>> varDeltaL;
    // ip->k->jq->t, a^M_jq <= t < b^M_jq, t \in E^i_p
    private Map<VesselPeriod, Map<Integer, Map<VesselPeriod, Map<Integer, IloIntVar>>>> varDeltaU;

    // ip->t, a^M_ip <= t < b^M_ip
    private Map<VesselPeriod, Map<Integer, IloIntVar>> varPiU, varPiL;
    // ip->t, t \in E^i_p
    private Map<VesselPeriod, Map<Integer, IloIntVar>> varPiUD;
    private Map<VesselPeriod, IloIntVar> varIota, varKappa;

    private Map<Subblock, Map<Integer, IloIntVar>> varRho;
    private Map<Integer, Map<Integer, IloIntVar>> varUnloadFlow;
    private Map<Integer, Map<Integer, IloIntVar>> varLoadFlow;

    private IloIntVar varUnloadOverload;
    private IloIntVar varLoadOverload;

    public IloObjective objective;
    public IloLinearNumExpr objTime;
    public IloLinearNumExpr objRoute;
    public IloLinearNumExpr objCongestion;

    private LPMatrix congestionMatrixIndexManager;
    private IloLPMatrix congestionMatrix;


    public static IndexFormulationCplex buildModelGivenSubblockAssignment(
            Instance instance, IloCplex cplex, IndexBasedSolution partialSolution) throws IloException {
        IndexFormulationCplex model = new IndexFormulationCplex(instance, cplex);
        model.partialSolution = partialSolution;

        model.initVarDelta();
        model.initCommonVars();

        model.initBinaryHandlingTimeConstraints();
        model.initWConstraints();
        model.setCommonCongestionConstraints();
        model.setSpecialCongestionConstraintsGivenSubblockAssignment();

        model.setObjGivenSubblockAssignment();

        return model;
    }

    public void changeSubblockAssignment(Map<VesselPeriod, Set<Subblock>> target) throws IloException {

    }


    public static IndexFormulationCplex buildModelGivenTimeAssignment(
            Instance instance, IloCplex cplex, IndexBasedSolution partialSolution) throws IloException {
        IndexFormulationCplex model = new IndexFormulationCplex(instance, cplex);
        model.partialSolution = partialSolution;

        model.initVarY();
        model.initCommonVars();

        model.initYardTemplateConstraints();
        model.setCommonCongestionConstraints();
        model.setSpecialCongestionConstraintsGivenTimeAssignment();

        model.setObjGivenTimeAssignment();

        return model;
    }

    private void setObjGivenSubblockAssignment() throws IloException {
        objRoute = cplex.linearNumExpr();
        partialSolution.forEachSubblockAssignments((ip, m, subblock) -> {
            double loadDistance = instance.getDistanceFromSubblock(ip, subblock);
            for (VesselPeriod jq : instance.getSourceVesselPeriodsOf(ip)) {
                double unloadDistance = instance.getDistanceToSubblock(jq, subblock);
                try {
                    objRoute.addTerm((loadDistance + unloadDistance) * instance.etaRoute, varW.get(ip).get(m).get(jq));
                } catch (IloException e) {
                    throw new RuntimeException(e);
                }
            }
        });

        objTime = cplex.linearNumExpr();
        for (VesselPeriod ip : instance.getVesselPeriods()) {
            objTime.addTerm(ip.getEarlinessCost(), varIota.get(ip));
            objTime.addTerm(ip.getTardinessCost(), varKappa.get(ip));
        }
        objCongestion = cplex.linearNumExpr();
        objCongestion.addTerm(instance.etaCongestion, varUnloadOverload);
        objCongestion.addTerm(instance.etaCongestion, varLoadOverload);
        objective = cplex.addMinimize(cplex.sum(objTime, objRoute, objCongestion));
    }

    private void setObjGivenTimeAssignment() throws IloException {
        objRoute = cplex.linearNumExpr();
        partialSolution.forEachUnloadingTimes((ip, m, jq, schedule) -> {
            int n = schedule.number;
            for (Subblock subblock : instance.getSubblocks()) {
                double unloadDistance = instance.getDistanceToSubblock(jq, subblock);
                double loadDistance = instance.getDistanceFromSubblock(ip, subblock);
                try {
                    objRoute.addTerm((unloadDistance + loadDistance) * n * instance.etaRoute, varY.get(ip).get(m).get(subblock));
                } catch (IloException e) {
                    throw new RuntimeException(e);
                }
            }
        });

        objTime = cplex.linearNumExpr(partialSolution.objTime);

        objCongestion = cplex.linearNumExpr();
        objCongestion.addTerm(instance.etaCongestion, varUnloadOverload);
        objCongestion.addTerm(instance.etaCongestion, varLoadOverload);
        objective = cplex.addMinimize(cplex.sum(objTime, objRoute, objCongestion));

    }

    private void initVarY() throws IloException {
        varY = new HashMap<>(instance.getNumVesselPeriods());
        for (VesselPeriod ip : instance.getVesselPeriods())
            for (int m = 0; m < partialSolution.getExpectedSubblockNumber(ip); m++)
                for (Subblock subblock : instance.getSubblocks())
                    varY.computeIfAbsent(ip, key -> new HashMap<>())
                            .computeIfAbsent(m, key -> new HashMap<>())
                            .put(subblock, cplex.boolVar(String.format("Y_%d,%d,%d,%d",
                                    ip.getVid(), ip.getPid(), m, subblock.getId())));

        varX = new HashMap<>(instance.getNumVesselPeriods());
        for (Vessel i : instance.getVessels())
            for (Subblock k : instance.getSubblocks())
                for (int t = 0; t < horizon; t++)
                    varX.computeIfAbsent(i, key -> new HashMap<>())
                            .computeIfAbsent(k, key -> new HashMap<>())
                            .put(t, cplex.boolVar(String.format("X_%d,%d,%d",
                                    i.getVid(), k.getId(), t)));
    }

    private void initCommonVars() throws IloException {
        varRho = new HashMap<>(instance.getNumSubblocks());
        for (Subblock k : instance.getSubblocks())
            for (int t = 0; t < horizon; t++) {
                varRho.computeIfAbsent(k, key -> new HashMap<>())
                        .put(t, cplex.boolVar(String.format("Rho_%d,%d", k.getId(), t)));
            }

        varUnloadFlow = new HashMap<>(roads);
        varLoadFlow = new HashMap<>(roads);

        for (int l = 0; l < roads; l++)
            for (int t = 0; t < horizon; t++) {
                varUnloadFlow.computeIfAbsent(l, key -> new HashMap<>())
                        .put(t, cplex.intVar(0, Integer.MAX_VALUE, String.format("UnloadFlow_%d,%d", l, t)));
                varLoadFlow.computeIfAbsent(l, key -> new HashMap<>())
                        .put(t, cplex.intVar(0, Integer.MAX_VALUE, String.format("LoadFlow_%d,%d", l, t)));
            }
        varLoadOverload = cplex.intVar(0, Integer.MAX_VALUE, "largestLoadFlow");
        varUnloadOverload = cplex.intVar(0, Integer.MAX_VALUE, "largestUnloadFlow");
    }


    private void initVarDelta() throws IloException {
        varDeltaL = new HashMap<>(instance.getNumVesselPeriods());
        for (VesselPeriod ip : instance.getVesselPeriods())
            for (int m = 0; m < partialSolution.getExpectedSubblockNumber(ip); m++)
                for (int t : ip.getFeasibleInterval().intStream(instance.horizon))
                    varDeltaL.computeIfAbsent(ip, key -> new HashMap<>())
                            .computeIfAbsent(m, key -> new HashMap<>())
                            .put(t, cplex.boolVar(String.format("DeltaL_%d,%d,%d,%d",
                                    ip.getVid(), ip.getPid(), m, t)));

        varDeltaU = new HashMap<>(instance.getNumVesselPeriods());
        for (VesselPeriod ip : instance.getVesselPeriods())
            for (int m = 0; m < partialSolution.getExpectedSubblockNumber(ip); m++)
                for (VesselPeriod jq : instance.getSourceVesselPeriodsOf(ip))
                    for (int t : ip.getPeriodInterval().intersection(jq.getFeasibleInterval(), instance.horizon))
                        varDeltaU.computeIfAbsent(ip, key -> new HashMap<>())
                                .computeIfAbsent(m, key -> new HashMap<>())
                                .computeIfAbsent(jq, key -> new HashMap<>())
                                .put(t, cplex.boolVar(String.format("DeltaU_%d,%d,%d,%d,%d,%d",
                                        ip.getVid(), ip.getPid(), m, jq.getVid(), jq.getPid(), t)));

        varW = new HashMap<>(instance.getNumVesselPeriods());
        for (VesselPeriod ip : instance.getVesselPeriods())
            for (int m = 0; m < partialSolution.getExpectedSubblockNumber(ip); m++)
                for (VesselPeriod jq : instance.getSourceVesselPeriodsOf(ip))
                    varW.computeIfAbsent(ip, key -> new HashMap<>())
                            .computeIfAbsent(m, key -> new HashMap<>())
                            .put(jq, cplex.intVar(0, instance.spaceCapacity, String.format("W_%d,%d,%d,%d,%d",
                                    ip.getVid(), ip.getPid(), m, jq.getVid(), jq.getPid())));

        varPiU = new HashMap<>(instance.getNumVesselPeriods());
        varPiL = new HashMap<>(instance.getNumVesselPeriods());
        varPiUD = new HashMap<>(instance.getNumVesselPeriods());
        for (VesselPeriod ip : instance.getVesselPeriods()) {
            for (int t : ip.getFeasibleInterval().intStream(instance.horizon)) {
                varPiU.computeIfAbsent(ip, key -> new HashMap<>())
                        .put(t, cplex.boolVar(String.format("PiU_%d,%d,%d", ip.getVid(), ip.getPid(), t)));
                varPiL.computeIfAbsent(ip, key -> new HashMap<>())
                        .put(t, cplex.boolVar(String.format("PiL_%d,%d,%d", ip.getVid(), ip.getPid(), t)));
            }
            for (int t : ip.getPeriodInterval().intStream(instance.horizon)) {
                varPiUD.computeIfAbsent(ip, key -> new HashMap<>())
                        .put(t, cplex.boolVar(String.format("PiUD_%d,%d,%d", ip.getVid(), ip.getPid(), t)));
            }
        }

        varIota = new HashMap<>(instance.getNumVesselPeriods());
        varKappa = new HashMap<>(instance.getNumVesselPeriods());
        for (VesselPeriod ip : instance.getVesselPeriods()) {
            varIota.put(ip, cplex.intVar(0, ip.getRelativeExpectedIntervalStart() - ip.getRelativeFeasibleIntervalStart(),
                    String.format("Iota_%d,%d", ip.getVid(), ip.getPid())));
            varKappa.put(ip, cplex.intVar(0, ip.getRelativeFeasibleIntervalEnd() - ip.getRelativeExpectedIntervalStart(),
                    String.format("Kappa_%d,%d", ip.getVid(), ip.getPid())));
        }
    }

    private void initYardTemplateConstraints() throws IloException {
        for (VesselPeriod ip : instance.getVesselPeriods())
            for (int m = 0; m < partialSolution.getExpectedSubblockNumber(ip); m++) {
                IloLinearIntExpr expr = cplex.linearIntExpr();
                for (Subblock k : instance.getSubblocks())
                    expr.addTerm(1, varY.get(ip).get(m).get(k));
                cplex.addEq(expr, 1, String.format("ConsYardY%d,%d", ip.getVid(), ip.getPid()));
            }
        for (Subblock k : instance.getSubblocks())
            for (int t = 0; t < horizon; t++) {
                IloLinearIntExpr expr = cplex.linearIntExpr();
                for (Vessel v : instance.getVessels())
                    expr.addTerm(1, varX.get(v).get(k).get(t));
                cplex.addLe(expr, 1, String.format("ConsYardX%d,%d", k.getId(), t));
            }


        for (VesselPeriod vp : instance.getVesselPeriods())
            for (Subblock k : instance.getSubblocks()) {
                IloLinearIntExpr expr = cplex.linearIntExpr();
                for (Integer t : vp.getPeriodInterval().intStream(instance.horizon))
                    expr.addTerm(1, varX.get(instance.getVesselOf(vp)).get(k).get(t));
                for (int m = 0; m < partialSolution.getExpectedSubblockNumber(vp); m++)
                    expr.addTerm(-1 * vp.getPeriodInterval().getLength(), varY.get(vp).get(m).get(k));
                cplex.addEq(expr, 0, String.format("ConsYardXY%d,%d,%d", vp.getVid(), vp.getPid(), k.getId()));
            }
    }

    private void initWConstraints() throws IloException {
        for (VesselPeriod ip : instance.getVesselPeriods())
            for (int m = 0; m < partialSolution.getExpectedSubblockNumber(ip); m++)
                for (VesselPeriod jq : instance.getSourceVesselPeriodsOf(ip)) {
                    IloLinearIntExpr expr = cplex.linearIntExpr();
                    expr.addTerm(1, varW.get(ip).get(m).get(jq));
                    for (int t : ip.getPeriodInterval().intersection(jq.getFeasibleInterval(), instance.horizon)) {
                        expr.addTerm(-instance.spaceCapacity, varDeltaU.get(ip).get(m).get(jq).get(t));
                    }
                    cplex.addLe(expr, 0,
                            String.format("ConsFlowW_%d,%d,%d", ip.getVid(), ip.getPid(), m));
                }

        for (VesselPeriod jq : instance.getVesselPeriods())
            for (VesselPeriod ip : instance.getDestinationVesselPeriodsOf(jq)) {
                int n = instance.getTransshipmentTo(jq, ip);
                IloLinearIntExpr expr = cplex.linearIntExpr();
                for (int m = 0; m < partialSolution.getExpectedSubblockNumber(ip); m++) {
                    expr.addTerm(1, varW.get(ip).get(m).get(jq));
                }
                cplex.addEq(expr, n, String.format("ConsFlowN_%d,%d,%d,%d",
                        ip.getVid(), ip.getPid(), jq.getVid(), jq.getPid()));
            }

        for (VesselPeriod ip : instance.getVesselPeriods())
            for (int m = 0; m < partialSolution.getExpectedSubblockNumber(ip); m++) {
                IloLinearIntExpr expr = cplex.linearIntExpr();
                for (VesselPeriod jq : instance.getSourceVesselPeriodsOf(ip))
                    expr.addTerm(1, varW.get(ip).get(m).get(jq));
                cplex.addLe(expr, instance.spaceCapacity,
                        String.format("ConsFlowC_%d,%d,%d", ip.getVid(), ip.getPid(), m));
            }
    }

    private void initBinaryHandlingTimeConstraints() throws IloException {
        for (VesselPeriod ip : instance.getVesselPeriods())
            for (int m = 0; m < partialSolution.getExpectedSubblockNumber(ip); m++)
                for (VesselPeriod jq : instance.getSourceVesselPeriodsOf(ip)) {
                    IloLinearIntExpr expr = cplex.linearIntExpr();
                    for (int t : ip.getPeriodInterval().intersection(jq.getFeasibleInterval(), instance.horizon))
                        expr.addTerm(1, varDeltaU.get(ip).get(m).get(jq).get(t));
                    cplex.addLe(expr, 1, String.format("ConsDeltaU_%d,%d,%d,%d,%d",
                            ip.getVid(), ip.getPid(), m, jq.getVid(), jq.getPid()));
                }
        for (VesselPeriod ip : instance.getVesselPeriods())
            for (int m = 0; m < partialSolution.getExpectedSubblockNumber(ip); m++) {
                IloLinearIntExpr expr = cplex.linearIntExpr();
                for (int t : ip.getFeasibleInterval().intStream(instance.horizon)) {
                    expr.addTerm(1, varDeltaL.get(ip).get(m).get(t));
                }
                cplex.addEq(expr, 1, String.format("ConsDeltaL%d,%d,%d", ip.getVid(), ip.getPid(), m));
            }


        for (VesselPeriod ip : instance.getVesselPeriods()) {
            for (int m = 0; m < partialSolution.getExpectedSubblockNumber(ip); m++) {
                for (VesselPeriod jq : instance.getSourceVesselPeriodsOf(ip)) {
                    for (int t : ip.getPeriodInterval().intersection(jq.getFeasibleInterval(), instance.horizon)) {
                        cplex.addGe(varPiU.get(jq).get(t), varDeltaU.get(ip).get(m).get(jq).get(t),
                                String.format("ConsHandlePiU_%d,%d,%d,%d,%d,%d",
                                        ip.getVid(), ip.getPid(), m, jq.getVid(), jq.getPid(), t));
                    }
                }
            }
        }

        for (VesselPeriod ip : instance.getVesselPeriods()) {
            for (int m = 0; m < partialSolution.getExpectedSubblockNumber(ip); m++) {
                for (int t : ip.getFeasibleInterval().intStream(instance.horizon)) {
                    cplex.addGe(varPiL.get(ip).get(t), varDeltaL.get(ip).get(m).get(t),
                            String.format("ConsHandlePiL_%d,%d,%d,%d", ip.getVid(), ip.getPid(), m, t));
                }

            }
        }

        for (VesselPeriod ip : instance.getVesselPeriods()) {
            for (int m = 0; m < partialSolution.getExpectedSubblockNumber(ip); m++) {
                for (VesselPeriod jq : instance.getSourceVesselPeriodsOf(ip)) {
                    for (int t : ip.getPeriodInterval().intersection(jq.getFeasibleInterval(), instance.horizon)) {
                        cplex.addGe(varPiUD.get(ip).get(t), varDeltaU.get(ip).get(m).get(jq).get(t),
                                String.format("ConsHandlePiUD_%d,%d,%d,%d,%d,%d",
                                        ip.getVid(), ip.getPid(), m, jq.getVid(), jq.getPid(), t));
                    }
                }
            }
        }

        for (VesselPeriod ip : instance.getVesselPeriods())
            for (int t : ip.getFeasibleInterval().intStream(instance.horizon)) {
                cplex.addLe(cplex.sum(varPiU.get(ip).get(t), varPiL.get(ip).get(t)), 1,
                        String.format("ConsHandle_PiU_PiL_%d,%d,%d", ip.getVid(), ip.getPid(), t));
            }

//        for (VesselPeriod ip : instance.getVesselPeriods()) {
//            int relativeTimeStep = 0;
//            for (int t : ip.getFeasibleInterval().intStream(instance.horizon)) {
//                IloLinearIntExpr expr = cplex.linearIntExpr();
//                ip.getFeasibleInterval().intStream(instance.horizon).skip(relativeTimeStep).forEach(s -> {
//                    try {
//                        expr.addTerm(1, varPiU.get(ip).get(s));
//                        expr.addTerm(1, varPiUD.get(ip).get(s));
//                    } catch (IloException e) {
//                        throw new RuntimeException(e);
//                    }
//                });
//                cplex.addLe(expr,
//                        cplex.prod(2 * (ip.getFeasibleInterval().getLength() - relativeTimeStep),
//                                cplex.diff(1, varPiL.get(ip).get(t))),
//                        String.format("ConsHandlePiU_PiUD_%d,%d,%d", ip.getVId(), ip.getPId(), t));
//                relativeTimeStep++;
//            }
//        }

        for (VesselPeriod ip : instance.getVesselPeriods()) {
            int relativeTimeStep = 0;
            for (int t : ip.getFeasibleInterval().intStream(instance.horizon)) {
                // Explanation of relativeTimeStep+1: the first time step is already handled
                // by the previous constraints ConsHandle_PiU_PiL_%d,%d,%d.
                for (int s : ip.getFeasibleInterval().intStream(instance.horizon).skip(relativeTimeStep + 1)) {
                    cplex.addLe(cplex.sum(varPiU.get(ip).get(s), varPiL.get(ip).get(t)),
                            1, String.format("ConsHandlePiU_PiL_%d,%d,%d,%d", ip.getVid(), ip.getPid(), t, s));
                }

                for (int s : ip.getFeasibleInterval().intStream(instance.horizon).skip(relativeTimeStep)) {
                    cplex.addLe(cplex.sum(varPiUD.get(ip).get(s), varPiL.get(ip).get(t)),
                            1, String.format("ConsHandlePiUD_PiL_%d,%d,%d,%d", ip.getVid(), ip.getPid(), t, s));
                }
                relativeTimeStep++;
            }
        }


        for (VesselPeriod ip : instance.getVesselPeriods()) {

            int expA = ip.getRelativeExpectedIntervalStart();
            int expB = ip.getRelativeExpectedIntervalEnd();
            for (int t : ip.getFeasibleInterval().intStream(instance.horizon)) {
                IloIntExpr bigM = cplex.prod(ip.getLengthOfPeriod(),
                        cplex.diff(1, cplex.sum(varPiU.get(ip).get(t), varPiL.get(ip).get(t)))
                );
                int relativeTimeStep = ip.getRelativeTimeWithinPeriod(t, instance.horizon);
                cplex.addGe(varIota.get(ip), cplex.diff(expA - relativeTimeStep, bigM),
                        String.format("ConsHandleIota%d,%d,%d", ip.getVid(), ip.getPid(), t));
                cplex.addGe(varKappa.get(ip), cplex.diff(relativeTimeStep + 1 - expB, bigM),
                        String.format("ConsHandleKappa%d,%d,%d", ip.getVid(), ip.getPid(), t));
            }
        }
    }

    private void setCommonCongestionConstraints() throws IloException {
        for (Subblock k1 : instance.getSubblocks())
            for (Subblock k2 : instance.getSubblocks())
                if ((!k1.equals(k2)) && (k1.isNeighborInSameBlock(k2) || k1.isNeighborAcrossLane(k2)))
                    for (int t = 0; t < horizon; t++) {
                        cplex.addLe(cplex.sum(varRho.get(k1).get(t), varRho.get(k2).get(t)), 1,
                                String.format("ConsCongNeighbor%d,%d,%d", k1.getId(), k2.getId(), t));
                    }

        for (int l = 0; l < roads; l++)
            for (int t = 0; t < horizon; t++) {
                cplex.addLe(varUnloadFlow.get(l).get(t), cplex.sum(instance.maxUnloadFlows, varUnloadOverload),
                        String.format("ConsCongRoadUnload%d,%d", l, t));
                cplex.addLe(varLoadFlow.get(l).get(t), cplex.sum(instance.maxLoadFlows, varLoadOverload),
                        String.format("ConsCongRoadLoad%d,%d", l, t));
            }
    }

    private class LPMatrix {
        private final Map<VesselPeriod, Integer> mapIp2MStart;
        private int totalIpMPairs;
        private final Map<VesselPeriod, Map<Integer, Integer>> mapIpT2MStart;
        private int totalIpTMPairs;
        private final Map<VesselPeriod, Map<VesselPeriod, Map<Integer, Integer>>> mapIpJqT2MStart;
        private int totalIpJqTMPairs;

        private final int subblocks;
        private final int horizon;
        private final int roads;

        private final int varRhoStart;
        private final int varLoadFlowStart;
        private final int varUnloadFlowStart;

        private final int varRhoNum;
        private final int varLoadFlowNum;
        private final int varUnloadFlowNum;

        private final int constraintSubblockActivityStart;
        private final int constraintLoadFlowStart;
        private final int constraintUnloadFlowStart;

        private final int varYStart;
        private final int varDeltaLoadStart;
        private final int varDeltaUnloadStart;
        private final int varYNum;
        private final int varDeltaLoadNum;
        private final int varDeltaUnloadNum;


        public LPMatrix() {
            this.subblocks = instance.getNumSubblocks();
            this.horizon = instance.getHorizon();
            this.roads = instance.roads;


            this.varRhoStart = 0;
            this.varRhoNum = subblocks * horizon;

            this.varLoadFlowStart = varRhoStart + varRhoNum;
            this.varLoadFlowNum = roads * horizon;

            this.varUnloadFlowStart = varLoadFlowStart + varLoadFlowNum;
            this.varUnloadFlowNum = roads * horizon;

            this.constraintSubblockActivityStart = 0;
            this.constraintLoadFlowStart = constraintSubblockActivityStart + subblocks * horizon;
            this.constraintUnloadFlowStart = constraintLoadFlowStart + roads * horizon;


            this.mapIp2MStart = new HashMap<>();
            this.totalIpMPairs = 0;
            for (VesselPeriod ip : instance.getVesselPeriods()) {
                mapIp2MStart.put(ip, totalIpMPairs);
                totalIpMPairs += instance.getExpectedSubblockNumber(ip);
            }

            this.varYStart = varUnloadFlowStart + varUnloadFlowNum;
            this.varYNum = totalIpMPairs * horizon;

            this.mapIpT2MStart = new HashMap<>();
            this.totalIpTMPairs = 0;
            for (VesselPeriod ip : instance.getVesselPeriods())
                for (int t : ip.getFeasibleInterval().intStream(instance.horizon)) {
                    mapIpT2MStart.computeIfAbsent(ip, key -> new HashMap<>())
                            .put(t, totalIpTMPairs);
                    totalIpTMPairs += instance.getExpectedSubblockNumber(ip);
                }

            this.varDeltaLoadStart = varUnloadFlowStart + varUnloadFlowNum;
            this.varDeltaLoadNum = totalIpTMPairs;


            this.totalIpJqTMPairs = 0;
            this.mapIpJqT2MStart = new HashMap<>();

            for (VesselPeriod ip : instance.getVesselPeriods())
                for (VesselPeriod jq : instance.getSourceVesselPeriodsOf(ip))
                    for (int t : ip.getPeriodInterval().intersection(jq.getFeasibleInterval(), instance.horizon)) {
                        mapIpJqT2MStart.computeIfAbsent(ip, key -> new HashMap<>())
                                .computeIfAbsent(jq, key -> new HashMap<>())
                                .put(t, totalIpJqTMPairs);
                        totalIpJqTMPairs += instance.getExpectedSubblockNumber(ip);
                    }

            this.varDeltaUnloadStart = varDeltaLoadStart + varDeltaLoadNum;
            this.varDeltaUnloadNum = totalIpJqTMPairs;

//            // print all the number of variables
//            System.out.printf("rho: num=%d, id=[%d, %d]%n", varRhoNum, varRhoStart, varRhoStart + varRhoNum - 1);
//            System.out.printf("load flow: num=%d, id=[%d, %d]%n", varLoadFlowNum, varLoadFlowStart, varLoadFlowStart + varLoadFlowNum - 1);
//            System.out.printf("unload flow: num=%d, id=[%d, %d]%n", varUnloadFlowNum, varUnloadFlowStart, varUnloadFlowStart + varUnloadFlowNum - 1);
//            System.out.printf("Y: num=%d, id=[%d, %d]%n", varYNum, varYStart, varYStart + varYNum - 1);
//            System.out.printf("delta load: num=%d, id=[%d, %d]%n", varDeltaLoadNum, varDeltaLoadStart, varDeltaLoadStart + varDeltaLoadNum - 1);
//            System.out.printf("delta unload: num=%d, id=[%d, %d]%n", varDeltaUnloadNum, varDeltaUnloadStart, varDeltaUnloadStart + varDeltaUnloadNum - 1);


        }

        public IloLPMatrix buildCommonMatrix() throws IloException {
            IloLPMatrix matrix = cplex.addLPMatrix();

            varRho.forEach((k, kMap) -> {
                kMap.forEach((t, var) -> {
                    try {
                        int index = matrix.addColumn(var);
                        if (index != getVarRhoIndex(k, t))
                            throw new RuntimeException(String.format("Index mismatch: %d != %d",
                                    index, getVarRhoIndex(k, t)));
                    } catch (IloException e) {
                        throw new RuntimeException(e);
                    }
                });
            });

            varLoadFlow.forEach((l, lMap) -> {
                lMap.forEach((t, var) -> {
                    try {
                        int index = matrix.addColumn(var);
                        if (index != getVarLoadFlowIndex(l, t))
                            throw new RuntimeException(String.format("Index mismatch: %d != %d",
                                    index, getVarLoadFlowIndex(l, t)));
                    } catch (IloException e) {
                        throw new RuntimeException(e);
                    }
                });
            });

            varUnloadFlow.forEach((l, lMap) -> {
                lMap.forEach((t, var) -> {
                    try {
                        int index = matrix.addColumn(var);
                        if (index != getVarUnloadFlowIndex(l, t))
                            throw new RuntimeException(String.format("Index mismatch: %d != %d",
                                    index, getVarUnloadFlowIndex(l, t)));
                    } catch (IloException e) {
                        throw new RuntimeException(e);
                    }
                });
            });

            for (Subblock k : instance.getSubblocks())
                for (int t = 0; t < horizon; t++) {
                    int rhoId = getVarRhoIndex(k, t);
                    matrix.addRow(-instance.getNumVesselPeriods(), 0, new int[]{rhoId}, new double[]{-instance.getNumVesselPeriods()});
                }
            for (int l = 0; l < roads; l++)
                for (int t = 0; t < horizon; t++) {
                    matrix.addRow(0, 0, new int[]{getVarLoadFlowIndex(l, t)}, new double[]{-1});
                }
            for (int l = 0; l < roads; l++)
                for (int t = 0; t < horizon; t++) {
                    matrix.addRow(0, 0, new int[]{getVarUnloadFlowIndex(l, t)}, new double[]{-1});
                }

            return matrix;
        }

        public IloLPMatrix buildMatrixGivenTimeAssignment() throws IloException {
            IloLPMatrix matrix = buildCommonMatrix();

            varY.forEach((ip, ipMap) -> {
                ipMap.forEach((m, mMap) -> {
                    mMap.forEach((k, var) -> {
                        try {
                            int index = matrix.addColumn(var);
                            if (index != getVarYIndex(ip, m, k))
                                throw new RuntimeException(String.format("Index mismatch: %d != %d",
                                        index, getVarYIndex(ip, m, k)));
                        } catch (IloException e) {
                            throw new RuntimeException(e);
                        }
                    });
                });
            });
            return matrix;
        }

        public IloLPMatrix buildMatrixGivenSubblockAssignment() throws IloException {
            IloLPMatrix matrix = buildCommonMatrix();
            for (VesselPeriod ip : instance.getVesselPeriods())
                for (int t : ip.getFeasibleInterval().intStream(instance.horizon))
                    for (int m = 0; m < instance.getExpectedSubblockNumber(ip); m++) {
                        IloIntVar var = varDeltaL.get(ip).get(m).get(t);
                        int index = matrix.addColumn(var);
                        if (index != getVarDeltaLoadIndex(ip, m, t)) {
                            System.out.println(ip + " " + m + " " + t + " -> " + getVarDeltaLoadIndex(ip, m, t));
                            throw new RuntimeException(String.format("DeltaL Index mismatch: %d != %d",
                                    index, getVarDeltaLoadIndex(ip, m, t)));
                        }
                    }

            for (VesselPeriod ip : instance.getVesselPeriods())
                for (VesselPeriod jq : instance.getSourceVesselPeriodsOf(ip))
                    for (int t : ip.getPeriodInterval().intersection(jq.getFeasibleInterval(), instance.horizon))
                        for (int m = 0; m < instance.getExpectedSubblockNumber(ip); m++) {
                            IloIntVar var = varDeltaU.get(ip).get(m).get(jq).get(t);
                            int index = matrix.addColumn(var);
                            if (index != getVarDeltaUnloadIndex(ip, m, jq, t))
                                throw new RuntimeException(String.format("Index mismatch: %d != %d",
                                        index, getVarDeltaUnloadIndex(ip, m, jq, t)));
                        }
            return matrix;
        }


        public int getIpmIndex(VesselPeriod ip, int m) {
            return mapIp2MStart.get(ip) + m;
        }


        public int getVarYIndex(VesselPeriod ip, int m, Subblock k) {
            return varYStart + getIpmIndex(ip, m) * subblocks + k.getId();
        }

        public int getVarDeltaLoadIndex(VesselPeriod ip, int m, int t) {
            return varDeltaLoadStart + mapIpT2MStart.get(ip).get(t) + m;
        }

        public int getVarDeltaUnloadIndex(VesselPeriod ip, int m, VesselPeriod jq, int t) {
            return varDeltaUnloadStart + mapIpJqT2MStart.get(ip).get(jq).get(t) + m;
        }


        public int getVarRhoIndex(Subblock k, int t) {
            return varRhoStart + k.getId() * horizon + t;
        }

        public int getVarLoadFlowIndex(int l, int t) {
            return varLoadFlowStart + l * horizon + t;
        }

        public int getVarUnloadFlowIndex(int l, int t) {
            return varUnloadFlowStart + l * horizon + t;
        }

        public int getConstraintSubblockActivityIndex(Subblock k, int t) {
            return constraintSubblockActivityStart + k.getId() * horizon + t;
        }

        public int getConstraintLoadFlowIndex(int l, int t) {
            return constraintLoadFlowStart + l * horizon + t;
        }

        public int getConstraintUnloadFlowIndex(int l, int t) {
            return constraintUnloadFlowStart + l * horizon + t;
        }
    }


    private class TripletPool {
        private final List<Integer> rows;
        private final List<Integer> cols;
        private final List<Double> values;
        private final Map<Long, Integer> indexMap;


        public TripletPool() {
            this.rows = new ArrayList<>();
            this.cols = new ArrayList<>();
            this.values = new ArrayList<>();
            this.indexMap = new HashMap<>();
        }

        public void add(int row, int col, double val) {
            long key = ((long) row << 32) | (col & 0xFFFFFFFFL);
            Integer index = indexMap.get(key);
            if (index != null) {
                double old = values.get(index);
                values.set(index, old + val);
            } else {
                indexMap.put(key, rows.size());
                rows.add(row);
                cols.add(col);
                values.add(val);
            }
        }

        public int getN() {
            return rows.size();
        }

        public int[] getRowIndices() {
            return rows.stream().mapToInt(Integer::intValue).toArray();
        }

        public int[] getColIndices() {
            return cols.stream().mapToInt(Integer::intValue).toArray();
        }

        public double[] getValues() {
            return values.stream().mapToDouble(Double::doubleValue).toArray();
        }
    }

    private void setSpecialCongestionConstraintsGivenTimeAssignment() throws IloException {
        congestionMatrixIndexManager = new LPMatrix();
        congestionMatrix = congestionMatrixIndexManager.buildMatrixGivenTimeAssignment();

        TripletPool pool = new TripletPool();
        partialSolution.forEachUnloadingTimes((ip, m, jq, schedule) -> {
            for (Subblock k : instance.getSubblocks()) {
                int colId = congestionMatrixIndexManager.getVarYIndex(ip, m, k);
                int rowId = congestionMatrixIndexManager.getConstraintSubblockActivityIndex(k, schedule.time);
                pool.add(rowId, colId, 1);
            }
        });

        partialSolution.forEachLoadingTimes((ip, m, schedule) -> {
            for (Subblock k : instance.getSubblocks()) {
                int colId = congestionMatrixIndexManager.getVarYIndex(ip, m, k);
                int rowId = congestionMatrixIndexManager.getConstraintSubblockActivityIndex(k, schedule.time);
                pool.add(rowId, colId, 1);
            }
        });

        partialSolution.forEachLoadingTimes((ip, m, schedule) -> {
            for (Subblock k : instance.getSubblocks()) {
                int colId = congestionMatrixIndexManager.getVarYIndex(ip, m, k);
                for (int l : instance.getRouteFromSubblock(ip, k)) {
                    int rowId = congestionMatrixIndexManager.getConstraintLoadFlowIndex(l, schedule.time);
                    pool.add(rowId, colId, 1);
                }
            }
        });

        partialSolution.forEachUnloadingTimes((ip, m, jq, schedule) -> {
            for (Subblock k : instance.getSubblocks()) {
                int colId = congestionMatrixIndexManager.getVarYIndex(ip, m, k);
                for (int l : instance.getRouteToSubblock(jq, k)) {
                    int rowId = congestionMatrixIndexManager.getConstraintUnloadFlowIndex(l, schedule.time);
                    pool.add(rowId, colId, 1);
                }
            }
        });

        congestionMatrix.setNZs(pool.getRowIndices(), pool.getColIndices(), pool.getValues());
    }

    private void setSpecialCongestionConstraintsGivenSubblockAssignment() throws IloException {
        congestionMatrixIndexManager = new LPMatrix();
        congestionMatrix = congestionMatrixIndexManager.buildMatrixGivenSubblockAssignment();

        TripletPool pool = new TripletPool();

        partialSolution.forEachSubblockAssignments((ip, m, k) -> {
            for (int t : ip.getFeasibleInterval().intStream(instance.horizon)) {
                int colId = congestionMatrixIndexManager.getVarDeltaLoadIndex(ip, m, t);
                int rowId = congestionMatrixIndexManager.getConstraintSubblockActivityIndex(k, t);
                pool.add(rowId, colId, 1);
            }
            for (VesselPeriod jq : instance.getSourceVesselPeriodsOf(ip))
                for (int t : ip.getPeriodInterval().intersection(jq.getFeasibleInterval(), instance.horizon)) {
                    int colId = congestionMatrixIndexManager.getVarDeltaUnloadIndex(ip, m, jq, t);
                    int rowId = congestionMatrixIndexManager.getConstraintSubblockActivityIndex(k, t);
                    pool.add(rowId, colId, 1);
                }
        });

        partialSolution.forEachSubblockAssignments((ip, m, k) -> {
            for (int t : ip.getFeasibleInterval().intStream(instance.horizon)) {
                int colId = congestionMatrixIndexManager.getVarDeltaLoadIndex(ip, m, t);
                for (int l : instance.getRouteFromSubblock(ip, k)) {
                    int rowId = congestionMatrixIndexManager.getConstraintLoadFlowIndex(l, t);
                    pool.add(rowId, colId, 1);
                }
            }

            for (VesselPeriod jq : instance.getSourceVesselPeriodsOf(ip))
                for (int t : ip.getPeriodInterval().intersection(jq.getFeasibleInterval(), instance.horizon)) {
                    int colId = congestionMatrixIndexManager.getVarDeltaUnloadIndex(ip, m, jq, t);
                    for (int l : instance.getRouteToSubblock(jq, k)) {
                        int rowId = congestionMatrixIndexManager.getConstraintUnloadFlowIndex(l, t);
                        pool.add(rowId, colId, 1);
                    }
                }
        });


        congestionMatrix.setNZs(pool.getRowIndices(), pool.getColIndices(), pool.getValues());
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
            throw new IllegalArgumentException("Not a Bool");

        return Math.abs(v - 1) < PRECISION;
    }

    public IndexBasedSolution getSolutionOperationSchedule() throws IloException {
        validateCplexStatus();
        if (varDeltaL == null || varDeltaL.isEmpty()) {
            throw new IllegalStateException("Variable varDeltaL is not initialized or is empty.");
        }
        if (varDeltaU == null || varDeltaU.isEmpty()) {
            throw new IllegalStateException("Variable varDeltaU is not initialized or is empty.");
        }
        if (varW == null || varW.isEmpty()) {
            throw new IllegalStateException("Variable varW is not initialized or is empty.");
        }

        IndexBasedSolution partialSolution = new IndexBasedSolution(instance);

        Map<VesselPeriod, Map<Integer, Integer>> loadNumber = new HashMap<>(instance.getNumVesselPeriods());
        for (VesselPeriod ip : instance.getVesselPeriods()) {
            for (int m = 0; m < instance.getExpectedSubblockNumber(ip); m++)
                for (VesselPeriod jq : instance.getSourceVesselPeriodsOf(ip)) {
                    int w = getIntValue(varW.get(ip).get(m).get(jq));
                    loadNumber.computeIfAbsent(ip, key -> new HashMap<>())
                            .compute(m, (key, value) -> value == null ? w : value + w);
                    for (int t : ip.getPeriodInterval().intersection(jq.getFeasibleInterval(), instance.horizon)) {
                        if (getIntValue(varDeltaU.get(ip).get(m).get(jq).get(t)) == 1) {
                            partialSolution.setMthSourceUnloadingTime(ip, m, jq, t, w);
                        }
                    }
                }
        }

        for (VesselPeriod ip : instance.getVesselPeriods()) {
            for (int m = 0; m < instance.getExpectedSubblockNumber(ip); m++) {
                int w = loadNumber.get(ip).get(m);
                for (int t : ip.getFeasibleInterval().intStream(instance.horizon)) {
                    if (getIntValue(varDeltaL.get(ip).get(m).get(t)) == 1) {
                        partialSolution.setMthLoadingPlan(ip, m, t, w);
                    }
                }
            }
        }

        partialSolution.build();

        return partialSolution;
    }

    public IndexBasedSolution getSolutionSubblockAssignment() throws IloException {
        validateCplexStatus();

        if (varY == null || varY.isEmpty()) {
            throw new IllegalStateException("Variable varY is not initialized or is empty.");
        }

        IndexBasedSolution partialSolution = new IndexBasedSolution(instance);

        for (VesselPeriod ip : instance.getVesselPeriods()) {
            for (int m = 0; m < instance.getExpectedSubblockNumber(ip); m++) {
                for (Subblock k : instance.getSubblocks()) {
                    if (isBoolTrue(varY.get(ip).get(m).get(k)))
                        partialSolution.setMthSubblock(ip, m, k);
                }
            }
        }

        partialSolution.build();

        return partialSolution;
    }

    public static Solution solve(Instance instance, Integer timeLimit, Integer threads) throws IloException {
        Random random = new Random(1);
        long startTime = System.currentTimeMillis();
        MasterYardTemplateHeuristic heuristic = new MasterYardTemplateHeuristic(instance);
        Map<VesselPeriod, Map<Subblock, Double>> costs = heuristic.getDistanceCostsByEqualStorage();
        List<VesselPeriod> priority = heuristic.getFirstCommeFirstServedPriority();
        Map<VesselPeriod, Set<Subblock>> initialSubblockAssignment = heuristic.assignNeededSubblocksByCost(priority, costs);
        while (initialSubblockAssignment == null) {
            Collections.shuffle(priority, random);
            initialSubblockAssignment = heuristic.assignNeededSubblocksByCost(priority, costs);
        }
        IndexBasedSolution current = new IndexBasedSolution(instance);
        for (Map.Entry<VesselPeriod, Set<Subblock>> entry : initialSubblockAssignment.entrySet()) {
            VesselPeriod ip = entry.getKey();
            Set<Subblock> ks = entry.getValue();
            current.setSubblocks(ip, new ArrayList<>(ks));
        }
        current.build();

        try (IloCplex cplex = new IloCplex()) {
            if (timeLimit != null)
                cplex.setParam(IloCplex.IntParam.TimeLimit, timeLimit - (double) (System.currentTimeMillis() - startTime) / 1000);
            IndexFormulationCplex model = IndexFormulationCplex.buildModelGivenSubblockAssignment(instance, cplex, current);
            model.solve();
            IndexBasedSolution solutionOperation = model.getSolutionOperationSchedule();
            current = IndexBasedSolution.merge(current, solutionOperation);
        }

        boolean solveTimeProblem = true;
        boolean flag = true;

        while (flag &&
                (timeLimit == null || (System.currentTimeMillis() - startTime) / 1000 < timeLimit)) {
            try (IloCplex cplex = new IloCplex()) {

                if (timeLimit != null)
                    cplex.setParam(IloCplex.IntParam.TimeLimit, timeLimit - (double) (System.currentTimeMillis() - startTime) / 1000);

                IndexBasedSolution integratedSolution;

                if (solveTimeProblem) {
                    IndexFormulationCplex model = IndexFormulationCplex.buildModelGivenTimeAssignment(instance, cplex, current);
                    model.solve();
                    integratedSolution = IndexBasedSolution.merge(model.getSolutionSubblockAssignment(), current);
                    solveTimeProblem = false;
                } else {
                    IndexFormulationCplex model = IndexFormulationCplex.buildModelGivenSubblockAssignment(instance, cplex, current);
                    model.solve();
                    integratedSolution = IndexBasedSolution.merge(current, model.getSolutionOperationSchedule());
                    solveTimeProblem = true;
                }

                if (integratedSolution.objAll < current.objAll - 1e-6)
                    current = integratedSolution;
                else
                    flag = false;
            }
        }

        return current.toSolution();
    }

    public static void main(String[] args) throws IloException {
        Instance instance = Instance.readJson("input/instance_{04-01-01}_{06-02}_01.json");
        Random random = new Random(1);
        long startTime = System.currentTimeMillis();
        MasterYardTemplateHeuristic heuristic = new MasterYardTemplateHeuristic(instance);
        Map<VesselPeriod, Map<Subblock, Double>> costs = heuristic.getDistanceCostsByEqualStorage();
        List<VesselPeriod> priority = heuristic.getFirstCommeFirstServedPriority();
        Map<VesselPeriod, Set<Subblock>> initialSubblockAssignment = heuristic.assignNeededSubblocksByCost(priority, costs);
        while (initialSubblockAssignment == null) {
            Collections.shuffle(priority, random);
            initialSubblockAssignment = heuristic.assignNeededSubblocksByCost(priority, costs);
        }
        IndexBasedSolution current = new IndexBasedSolution(instance);
        for (Map.Entry<VesselPeriod, Set<Subblock>> entry : initialSubblockAssignment.entrySet()) {
            VesselPeriod ip = entry.getKey();
            Set<Subblock> ks = entry.getValue();
            current.setSubblocks(ip, new ArrayList<>(ks));
        }
        current.build();

        try (IloCplex cplex = new IloCplex()) {
            IndexFormulationCplex model = IndexFormulationCplex.buildModelGivenSubblockAssignment(instance, cplex, current);
            model.solve();
            IndexBasedSolution solutionOperation = model.getSolutionOperationSchedule();
            current = IndexBasedSolution.merge(current, solutionOperation);
        }
    }
}
