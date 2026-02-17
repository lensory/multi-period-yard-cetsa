package solver;

import ilog.concert.*;
import ilog.cplex.IloCplex;
import entity.*;
import util.IntervalSet;

import java.util.*;
import java.util.stream.Collectors;

public class CplexFixedSubblockModel {
    public final IloCplex cplex;
    private final Instance instance;
    private final int horizon, roads;
    private final double PRECISION = 1e-6;

    //    private Map<VesselPeriod, Map<Subblock, Map<VesselPeriod, Integer>>> containerAssignment; // ip -> k -> jqs -> w
    private Map<VesselPeriod, Set<Subblock>> subblockAssignment; // solution of Y: ip -> k

    private HashMap<VesselPeriod, HashMap<VesselPeriod, HashMap<Subblock, IloIntVar>>> varZ;
    private HashMap<VesselPeriod, HashMap<VesselPeriod, HashMap<Subblock, IloIntVar>>> varW;


    private Map<VesselPeriod, Map<Subblock, Map<Integer, IloIntVar>>> varDeltaU, varDeltaL;
    private Map<VesselPeriod, Map<Integer, IloIntVar>> varPiU, varPiL, varPiUD;
    private Map<VesselPeriod, IloIntVar> varIota, varKappa;
    private Map<Subblock, Map<Integer, IloIntVar>> varRho;

    private IloIntVar varUnloadOverload;
    private IloIntVar varLoadOverload;

    public IloLinearNumExpr objTime;
    public IloLinearNumExpr objRoute;
    public IloLinearNumExpr objCongestion;

    public CplexFixedSubblockModel(Instance instance, IloCplex cplex) throws IloException {
        this.instance = instance;
        this.horizon = instance.horizon;
        this.roads = instance.roads;
        this.cplex = cplex;
    }

    public Solution solveIntegratedSP(Map<VesselPeriod, Set<Subblock>> subblockAssignment) throws IloException {
        this.subblockAssignment = subblockAssignment;
        initVarZ();

        variableSP1();
        variableSP2();

        linkZW();
        linkZDelta();

        constrainSP1();
        constrainSP2();

        initObjRoute();
        initObjSP2();
        cplex.addMinimize(cplex.sum(
                objRoute,
                objTime,
                objCongestion
        ));


        if (cplex.solve()) {
            return getIntegratedSolution();
        }
        return null;
    }

    private Solution mergeSolution(Map<VesselPeriod, Map<Subblock, Map<VesselPeriod, Integer>>> containerAssignment,
                                   Map<VesselPeriod, Map<Subblock, Map<VesselPeriod, Integer>>> operationSchedule) throws IloException {
        Solution solution = new Solution(instance);
        for (VesselPeriod ip : instance.getVesselPeriods()) {
            for (Subblock k : subblockAssignment.getOrDefault(ip, Collections.emptySet())) {
                solution.setSubBlock(ip, k);
            }
        }

        for (VesselPeriod ip : instance.getVesselPeriods())
            for (Subblock k : subblockAssignment.getOrDefault(ip, Collections.emptySet())) {
                Map<VesselPeriod, Integer> container = containerAssignment.get(ip).get(k);
                Map<VesselPeriod, Integer> operation = operationSchedule.get(ip).get(k);


                int totalContainers = 0;
                for (VesselPeriod jq : container.keySet()) {
                    int w = container.get(jq);
                    int t = operation.get(jq);
                    totalContainers += w;
                    solution.setUnloadSchedule(jq, ip, k, t, w);
                }
                solution.setLoadSchedule(ip, k, operation.get(ip), totalContainers);
            }
        solution.calculateObjectives();

        return solution;
    }

    private Solution getIntegratedSolution() throws IloException {
        return mergeSolution(getSolutionContainerAssignment(), getSolutionOperationSchedule());
    }

    private void initVarZ() throws IloException {
        varZ = new HashMap<>(instance.getNumVesselPeriods());
        for (VesselPeriod jq : instance.getVesselPeriods()) {
            HashMap<VesselPeriod, HashMap<Subblock, IloIntVar>> _varZ = new HashMap<>();
            varZ.put(jq, _varZ);
            for (VesselPeriod ip : instance.getDestinationVesselPeriodsOf(jq)) {
                HashMap<Subblock, IloIntVar> __varZ = new HashMap<>();
                _varZ.put(ip, __varZ);
                for (Subblock k : subblockAssignment.getOrDefault(ip, Collections.emptySet())) {
                    __varZ.put(k, cplex.boolVar(String.format("Z_%d_%d_%d_%d_%d",
                            jq.getVid(), jq.getPid(), k.getId(), ip.getVid(), ip.getPid())));
                }
            }
        }
    }

    private void linkZW() throws IloException {
        for (VesselPeriod jq : instance.getVesselPeriods())
            for (VesselPeriod ip : instance.getDestinationVesselPeriodsOf(jq))
                for (Subblock k : subblockAssignment.getOrDefault(ip, Collections.emptySet()))
                    cplex.addLe(varW.get(jq).get(ip).get(k),
                            cplex.prod(instance.spaceCapacity, varZ.get(jq).get(ip).get(k)),
                            String.format("ConsFlowWZ%d,%d,%d,%d,%d", ip.getVid(), ip.getPid(), k.getId(), jq.getVid(), jq.getPid()));
    }

    private void linkZDelta() throws IloException {
        for (VesselPeriod ip : instance.getVesselPeriods())
            for (Subblock k : subblockAssignment.getOrDefault(ip, Collections.emptySet()))

                for (VesselPeriod jq : instance.getSourceVesselPeriodsOf(ip)) {
                    IloLinearIntExpr expr = cplex.linearIntExpr();
                    for (int t : ip.getPeriodInterval().intersection(jq.getPeriodInterval(), instance.horizon))
                        expr.addTerm(1, varDeltaU.get(jq).get(k).get(t));
                    cplex.addEq(expr, varZ.get(jq).get(ip).get(k), String.format("ConsHandleZ%d,%d,%d,%d,%d",
                            ip.getVid(), ip.getPid(), k.getId(), jq.getVid(), jq.getPid()));
                }
    }


    public Map<VesselPeriod, Map<Subblock, Map<VesselPeriod, Integer>>> solveSP1(Map<VesselPeriod, Map<Subblock, Set<VesselPeriod>>> transferAssignment) throws IloException {
        // subblockAssignment是transferAssignment中第一级的VesselPeriod和第二个Subblock构成的Map
        this.subblockAssignment = transferAssignment.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> new HashSet<>(entry.getValue().keySet())
        ));

        variableSP1();
        constrainSP1();
        initObjRoute();
        cplex.addMinimize(objRoute);

        for (VesselPeriod jq : instance.getVesselPeriods())
            for (VesselPeriod ip : instance.getDestinationVesselPeriodsOf(jq))
                for (Subblock k : subblockAssignment.getOrDefault(ip, Collections.emptySet())) {
                    if (!transferAssignment.get(ip).get(k).contains(jq))
                        varW.get(jq).get(ip).get(k).setUB(0);
                }

        if (cplex.solve()) {
            return getSolutionContainerAssignment();
        }
        return null;
    }

    private Map<VesselPeriod, Map<Subblock, Map<VesselPeriod, Integer>>> getSolutionContainerAssignment() throws IloException {
        Map<VesselPeriod, Map<Subblock, Map<VesselPeriod, Integer>>> containerAssignment = new HashMap<>(instance.getNumVesselPeriods());
        for (VesselPeriod jq : instance.getVesselPeriods()) {
            for (VesselPeriod ip : instance.getDestinationVesselPeriodsOf(jq)) {
                for (Subblock k : subblockAssignment.getOrDefault(ip, Collections.emptySet())) {
                    int w = getIntValue(varW.get(jq).get(ip).get(k));
                    if (w > 0)
                        containerAssignment.computeIfAbsent(ip, key -> new HashMap<>())
                                .computeIfAbsent(k, key -> new HashMap<>())
                                .put(jq, w);
                }
            }
        }
        return containerAssignment;
    }

    private void variableSP1() throws IloException {
        initVarW();
    }


    private void initVarW() throws IloException {
        varW = new HashMap<>(instance.getNumVesselPeriods());
        for (VesselPeriod jq : instance.getVesselPeriods()) {
            HashMap<VesselPeriod, HashMap<Subblock, IloIntVar>> _varW = new HashMap<>();
            varW.put(jq, _varW);
            for (VesselPeriod ip : instance.getDestinationVesselPeriodsOf(jq)) {
                HashMap<Subblock, IloIntVar> __varW = new HashMap<>();
                _varW.put(ip, __varW);
                for (Subblock k : subblockAssignment.getOrDefault(ip, Collections.emptySet())) {
                    __varW.put(k, cplex.intVar(0, instance.spaceCapacity, String.format("W_%d_%d_%d_%d_%d",
                            jq.getVid(), jq.getPid(), k.getId(), ip.getVid(), ip.getPid())));
                }
            }
        }
    }


    private void constrainSP1() throws IloException {
        for (VesselPeriod jq : instance.getVesselPeriods())
            for (VesselPeriod ip : instance.getDestinationVesselPeriodsOf(jq)) {
                IloLinearIntExpr expr = cplex.linearIntExpr();
                for (IloIntVar w : varW.get(jq).get(ip).values())
                    expr.addTerm(1, w);
                cplex.addEq(expr, instance.getTransshipmentTo(jq, ip),
                        String.format("ConsFlowN%d,%d,%d,%d", ip.getVid(), ip.getPid(),
                                jq.getVid(), jq.getPid()));
            }

        for (VesselPeriod ip : instance.getVesselPeriods())
            for (Subblock k : subblockAssignment.getOrDefault(ip, Collections.emptySet())) {
                IloLinearIntExpr expr = cplex.linearIntExpr();
                for (VesselPeriod jq : instance.getSourceVesselPeriodsOf(ip))
                    expr.addTerm(1, varW.get(jq).get(ip).get(k));
                cplex.addLe(expr, instance.spaceCapacity,
                        String.format("ConsFlowC%d,%d,%d", ip.getVid(), ip.getPid(), k.getId()));
            }
    }

    private void initObjRoute() throws IloException {
        objRoute = cplex.linearNumExpr();
        for (VesselPeriod ip : instance.getVesselPeriods())
            for (Subblock k : subblockAssignment.getOrDefault(ip, Collections.emptySet()))
                for (VesselPeriod jq : instance.getSourceVesselPeriodsOf(ip)) {
                    double distance = instance.getDistanceToSubblock(jq, k) + instance.getDistanceFromSubblock(ip, k);
                    objRoute.addTerm(distance * instance.etaRoute, varW.get(jq).get(ip).get(k));
                }
    }

    public Solution solveSP2WithSolution(Map<VesselPeriod, Map<Subblock, Map<VesselPeriod, Integer>>> containerAssignment) throws IloException {
        Map<VesselPeriod, Map<Subblock, Set<VesselPeriod>>> transferAssignment = containerAssignment.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> entry.getValue().entrySet().stream().collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry1 -> new HashSet<>(entry1.getValue().keySet())
                ))
        ));

        Map<VesselPeriod, Map<Subblock, Map<VesselPeriod, Integer>>> operationSchedule = solveSP2(transferAssignment);

        if (operationSchedule != null) {
            return mergeSolution(containerAssignment, operationSchedule);
        }
        return null;
    }

    public Map<VesselPeriod, Map<Subblock, Map<VesselPeriod, Integer>>> solveSP2(Map<VesselPeriod, Map<Subblock, Set<VesselPeriod>>> transferAssignment) throws IloException {
        // subblockAssignment是transferAssignment中第一级的VesselPeriod和第二个Subblock构成的Map
        this.subblockAssignment = transferAssignment.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> new HashSet<>(entry.getValue().keySet())
        ));

        variableSP2();

        for (VesselPeriod ip : instance.getVesselPeriods())
            for (Subblock k : subblockAssignment.getOrDefault(ip, Collections.emptySet()))
                for (VesselPeriod jq : instance.getSourceVesselPeriodsOf(ip)) {
                    IloLinearIntExpr expr = cplex.linearIntExpr();
                    for (int t : ip.getPeriodInterval().intersection(jq.getPeriodInterval(), instance.horizon))
                        expr.addTerm(1, varDeltaU.get(jq).get(k).get(t));

                    if (transferAssignment.get(ip).get(k).contains(jq))
                        cplex.addEq(expr, 1, String.format("ConsHandleZ%d,%d,%d,%d,%d",
                                ip.getVid(), ip.getPid(), k.getId(), jq.getVid(), jq.getPid()));
                    else
                        cplex.addEq(expr, 0, String.format("ConsHandleZ%d,%d,%d,%d,%d",
                                ip.getVid(), ip.getPid(), k.getId(), jq.getVid(), jq.getPid()));
                }

        constrainSP2();
        initObjSP2();
        cplex.addMinimize(cplex.sum(
                objTime,
                objCongestion
        ));
        if (cplex.solve()) {
            return getSolutionOperationSchedule();
        }
        return null;
    }

    private Map<VesselPeriod, Map<Subblock, Map<VesselPeriod, Integer>>> getSolutionOperationSchedule() throws IloException {
        // if 2nd VesselPeriod == 1st VesselPeriod, then it is loading.
        Map<VesselPeriod, Map<Subblock, Map<VesselPeriod, Integer>>> operationSchedule = new HashMap<>();

        for (VesselPeriod ip : instance.getVesselPeriods())
            for (Subblock k : subblockAssignment.getOrDefault(ip, Collections.emptySet()))
                for (int t : periodTime(ip)) {
                    if (getIntValue(varDeltaL.get(ip).get(k).get(t)) == 1) {
                        Integer preT = operationSchedule.computeIfAbsent(ip, key -> new HashMap<>())
                                .computeIfAbsent(k, key -> new HashMap<>())
                                .put(ip, t);
                        if (preT != null)
                            throw new IllegalArgumentException("The subblock " + ip + " is operated twice.");

                    }
                }
        for (VesselPeriod jq : instance.getVesselPeriods())
            for (VesselPeriod ip : instance.getDestinationVesselPeriodsOf(jq))
                for (Subblock k : subblockAssignment.getOrDefault(ip, Collections.emptySet()))
                    for (int t : intersectionTime(ip, jq)) {
                        if (getIntValue(varDeltaU.get(jq).get(k).get(t)) == 1) {
                            if (!operationSchedule.containsKey(ip) || !operationSchedule.get(ip).containsKey(k)) {
                                throw new IllegalArgumentException("The subblock " + ip + " is not loaded.");
                            }
                            Integer preT = operationSchedule.computeIfAbsent(ip, key -> new HashMap<>())
                                    .computeIfAbsent(k, key -> new HashMap<>())
                                    .put(jq, t);
                            if (preT != null)
                                throw new IllegalArgumentException("The subblock " + ip + " is operated twice.");
                        }
                    }
        return operationSchedule;
    }

    private void variableSP2() throws IloException {
        initVarDelta();
        initVarPi();
        initVarIotaKappa();
        initVarRho();
        initVarRoadFlow();
    }

    private void initVarRoadFlow() throws IloException {
        varLoadOverload = cplex.intVar(0, Integer.MAX_VALUE, "largestLoadFlow");
        varUnloadOverload = cplex.intVar(0, Integer.MAX_VALUE, "largestUnloadFlow");
    }

    private void initVarDelta() throws IloException {
        varDeltaL = new HashMap<>(instance.getNumVesselPeriods());
        for (VesselPeriod ip : instance.getVesselPeriods())
            for (Subblock k : subblockAssignment.getOrDefault(ip, Collections.emptySet()))
                for (int t : periodTime(ip)) {
                    varDeltaL.computeIfAbsent(ip, key -> new HashMap<>())
                            .computeIfAbsent(k, key -> new HashMap<>())
                            .put(t, cplex.boolVar(String.format("DeltaL_%d_%d_%d_%d", ip.getVid(), ip.getPid(), k.getId(), t)));
                }

        varDeltaU = new HashMap<>(instance.getNumVesselPeriods());
        for (VesselPeriod jq : instance.getVesselPeriods())
            for (VesselPeriod ip : instance.getDestinationVesselPeriodsOf(jq))
                for (Subblock k : subblockAssignment.getOrDefault(ip, Collections.emptySet()))
                    for (int t : intersectionTime(ip, jq)) {
                        varDeltaU.computeIfAbsent(jq, key -> new HashMap<>())
                                .computeIfAbsent(k, key -> new HashMap<>())
                                .put(t, cplex.boolVar(String.format("DeltaU_%d_%d_%d_%d", jq.getVid(), jq.getPid(), k.getId(), ip.getPid())));
                    }
    }


    private void initVarPi() throws IloException {
        varPiU = new HashMap<>();
        varPiL = new HashMap<>();
        varPiUD = new HashMap<>();
        for (VesselPeriod ip : instance.getVesselPeriods()) {
            for (int t : periodTime(ip)) {
                varPiU.computeIfAbsent(ip, key -> new HashMap<>())
                        .put(t, cplex.boolVar(String.format("PiU_%d_%d_%d", ip.getVid(), ip.getPid(), t)));
                varPiL.computeIfAbsent(ip, key -> new HashMap<>())
                        .put(t, cplex.boolVar(String.format("PiL_%d_%d_%d", ip.getVid(), ip.getPid(), t)));
                varPiUD.computeIfAbsent(ip, key -> new HashMap<>())
                        .put(t, cplex.boolVar(String.format("PiUD_%d_%d_%d", ip.getVid(), ip.getPid(), t)));
            }
        }
    }

    private void initVarIotaKappa() throws IloException {
        varIota = new HashMap<>();
        varKappa = new HashMap<>();
        for (Vessel v : instance.getVessels())
            for (VesselPeriod ip : v.getPeriods()) {
                varIota.put(ip, cplex.intVar(0, ip.getRelativeExpectedIntervalStart() - ip.getRelativeFeasibleIntervalStart(),
                        String.format("Iota_%d_%d", ip.getVid(), ip.getPid())));
                varKappa.put(ip, cplex.intVar(0, ip.getRelativeFeasibleIntervalEnd() - ip.getRelativeExpectedIntervalStart(),
                        String.format("Kappa_%d_%d", ip.getVid(), ip.getPid())));
            }
    }

    private void initVarRho() throws IloException {
        varRho = new HashMap<>(instance.getNumSubblocks());
        for (Subblock k : instance.getSubblocks()) {
            for (int t = 0; t < horizon; t++) {
                varRho.computeIfAbsent(k, key -> new HashMap<>(horizon))
                        .put(t, cplex.boolVar(String.format("Rho_%d_%d", k.getId(), t)));
            }
        }
    }


    private void constrainSP2() throws IloException {
        initBinaryHandlingTimeConstraints();
        initCongestionConstraints();
    }

    private void initBinaryHandlingTimeConstraints() throws IloException {
        for (VesselPeriod ip : instance.getVesselPeriods())
            for (Subblock k : subblockAssignment.getOrDefault(ip, Collections.emptySet())) {
                IloLinearIntExpr expr = cplex.linearIntExpr();
                for (int t : periodTime(ip)) {
                    expr.addTerm(1, varDeltaL.get(ip).get(k).get(t));
                }
                cplex.addEq(expr, 1, String.format("ConsHandleY%d,%d,%d",
                        ip.getVid(), ip.getPid(), k.getId()));
            }

        for (VesselPeriod jq : instance.getVesselPeriods())
            for (VesselPeriod ip : instance.getDestinationVesselPeriodsOf(jq))
                for (Subblock k : subblockAssignment.getOrDefault(ip, Collections.emptySet()))
                    for (int t : intersectionTime(ip, jq)) {
                        cplex.addGe(varPiU.get(jq).get(t), varDeltaU.get(jq).get(k).get(t),
                                String.format("ConsHandlePiU%d,%d,%d,%d", jq.getVid(), jq.getPid(), k.getId(), t));
                    }

        for (VesselPeriod ip : instance.getVesselPeriods())
            for (Subblock k : subblockAssignment.getOrDefault(ip, Collections.emptySet()))
                for (int t : periodTime(ip)) {
                    cplex.addGe(varPiL.get(ip).get(t), varDeltaL.get(ip).get(k).get(t),
                            String.format("ConsHandlePiL%d,%d,%d,%d", ip.getVid(), ip.getPid(), k.getId(), t));

                }

        for (VesselPeriod ip : instance.getVesselPeriods())
            for (Subblock k : subblockAssignment.getOrDefault(ip, Collections.emptySet()))
                for (VesselPeriod jq : instance.getSourceVesselPeriodsOf(ip))
                    for (int t : intersectionTime(ip, jq)) {
                        cplex.addGe(varPiUD.get(ip).get(t), varDeltaU.get(jq).get(k).get(t),
                                String.format("ConsHandlePiUD%d,%d,%d,%d", ip.getVid(), ip.getPid(), k.getId(), t));
                    }


        for (VesselPeriod ip : instance.getVesselPeriods()) {
            int a = ip.getRelativeFeasibleIntervalStart();
            int b = ip.getRelativeFeasibleIntervalEnd();
            int relativeTimeStep = 0;
            for (int t : periodTime(ip)) {
                // t \notin [a, b)
                if (relativeTimeStep < a || relativeTimeStep >= b)
                    cplex.addLe(cplex.sum(varPiU.get(ip).get(t), varPiL.get(ip).get(t)), 0,
                            String.format("ConsHandlePiUL%d,%d,%d", ip.getVid(), ip.getPid(), t));
                else
                    cplex.addLe(cplex.sum(varPiU.get(ip).get(t), varPiL.get(ip).get(t)), 1,
                            String.format("ConsHandlePiUL%d,%d,%d", ip.getVid(), ip.getPid(), t));
                relativeTimeStep++;
            }
        }

        for (VesselPeriod ip : instance.getVesselPeriods()) {
            int relativeTimeStep = 0;
            for (int t : ip.getPeriodInterval().intStream(instance.horizon)) {
                IloLinearIntExpr expr = cplex.linearIntExpr();
                ip.getPeriodInterval().intStream(instance.horizon).skip(relativeTimeStep).forEach(s -> {
                    try {
                        expr.addTerm(1, varPiU.get(ip).get(s));
                        expr.addTerm(1, varPiUD.get(ip).get(s));
                    } catch (IloException e) {
                        throw new RuntimeException(e);
                    }
                });
                cplex.addLe(expr,
                        cplex.prod(2 * (ip.getLengthOfPeriod() - relativeTimeStep),
                                cplex.diff(1, varPiL.get(ip).get(t))),
                        String.format("ConsHandlePiUUD%d,%d,%d", ip.getVid(), ip.getPid(), t));
                relativeTimeStep++;
            }
        }

        for (VesselPeriod ip : instance.getVesselPeriods()) {
            int relativeTimeStep = 0;
            int expA = ip.getRelativeExpectedIntervalStart();
            int expB = ip.getRelativeExpectedIntervalEnd();
            for (int t : periodTime(ip)) {
                IloIntExpr bigM = cplex.prod(ip.getLengthOfPeriod(),
                        cplex.diff(1, cplex.sum(varPiU.get(ip).get(t), varPiL.get(ip).get(t)))
                );
                cplex.addGe(varIota.get(ip),
                        cplex.diff(expA - relativeTimeStep, bigM),
                        String.format("ConsHandleIota%d,%d,%d",
                                ip.getVid(), ip.getPid(), t));
                cplex.addGe(varKappa.get(ip),
                        cplex.diff(relativeTimeStep + 1 - expB, bigM),
                        String.format("ConsHandleKappa%d,%d,%d",
                                ip.getVid(), ip.getPid(), t));
                relativeTimeStep++;
            }
        }
    }


    private void initCongestionConstraints() throws IloException {
        for (VesselPeriod ip : instance.getVesselPeriods())
            for (Subblock k : subblockAssignment.getOrDefault(ip, Collections.emptySet()))
                for (int t : periodTime(ip)) {
                    cplex.addGe(varRho.get(k).get(t), varDeltaL.get(ip).get(k).get(t),
                            String.format("ConsCongRhoL%d,%d,%d,%d", ip.getVid(), ip.getPid(), k.getId(), t));
                }

        for (VesselPeriod ip : instance.getVesselPeriods())
            for (Subblock k : subblockAssignment.getOrDefault(ip, Collections.emptySet()))
                for (VesselPeriod jq : instance.getSourceVesselPeriodsOf(ip))
                    for (int t : intersectionTime(ip, jq)) {
                        cplex.addGe(varRho.get(k).get(t), varDeltaU.get(jq).get(k).get(t),
                                String.format("ConsCongRhoU%d,%d,%d,%d", jq.getVid(), jq.getPid(), k.getId(), t));

                    }


        for (Subblock k1 : instance.getSubblocks())
            for (Subblock k2 : instance.getSubblocks())
                if ((!k1.equals(k2)) && (k1.isNeighborInSameBlock(k2) || k1.isNeighborAcrossLane(k2)))
                    for (int t = 0; t < horizon; t++) {
                        cplex.addLe(cplex.sum(varRho.get(k1).get(t), varRho.get(k2).get(t)), 1,
                                String.format("ConsCongNeighbor%d,%d,%d", k1.getId(), k2.getId(), t));
                    }


        IloLinearIntExpr[][] exprU = new IloLinearIntExpr[roads][horizon];
        for (int l = 0; l < roads; l++)
            for (int t = 0; t < horizon; t++) {
                exprU[l][t] = cplex.linearIntExpr();
            }

        for (VesselPeriod ip : instance.getVesselPeriods())
            for (Subblock k : subblockAssignment.getOrDefault(ip, Collections.emptySet()))
                for (VesselPeriod jq : instance.getSourceVesselPeriodsOf(ip)) {
                    for (int l : instance.getRouteToSubblock(jq, k)) {
                        for (int t : intersectionTime(ip, jq))
                            exprU[l][t].addTerm(1, varDeltaU.get(jq).get(k).get(t));
                    }
                }

        for (int l = 0; l < roads; l++)
            for (int t = 0; t < horizon; t++) {
                cplex.addLe(exprU[l][t], cplex.sum(instance.maxUnloadFlows, varUnloadOverload),
                        String.format("ConsCongRoadU%d,%d", l, t));
            }

        IloLinearIntExpr[][] exprL = new IloLinearIntExpr[roads][horizon];
        for (int l = 0; l < roads; l++)
            for (int t = 0; t < horizon; t++) {
                exprL[l][t] = cplex.linearIntExpr();
            }

        for (VesselPeriod ip : instance.getVesselPeriods())
            for (Subblock k : subblockAssignment.getOrDefault(ip, Collections.emptySet())) {
                for (int l : instance.getRouteFromSubblock(ip, k))
                    for (int t : periodTime(ip)) {
                        exprL[l][t].addTerm(1, varDeltaL.get(ip).get(k).get(t));
                    }
            }

        for (int l = 0; l < roads; l++)
            for (int t = 0; t < horizon; t++) {
                cplex.addLe(exprL[l][t], cplex.sum(instance.maxLoadFlows, varLoadOverload),
                        String.format("ConsCongRoadL%d,%d", l, t));
            }
    }

    private IntervalSet periodTime(VesselPeriod ip) {
        return ip.getPeriodInterval().intStream(instance.horizon);
    }

    private IntervalSet intersectionTime(VesselPeriod ip, VesselPeriod jq) {
        return ip.getPeriodInterval().intersection(jq.getPeriodInterval(), instance.horizon);
    }

    private void initObjSP2() throws IloException {
        objTime = cplex.linearNumExpr();
        for (Vessel i : instance.getVessels())
            for (VesselPeriod ip : i.getPeriods()) {
                objTime.addTerm(ip.getEarlinessCost(), varIota.get(ip));
                objTime.addTerm(ip.getTardinessCost(), varKappa.get(ip));
            }
        objCongestion = cplex.linearNumExpr();
        objCongestion.addTerm(instance.etaCongestion, varUnloadOverload);
        objCongestion.addTerm(instance.etaCongestion, varLoadOverload);

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

    public static void main(String[] args) throws IloException {

        int[][] instanceConfigurations = new int[][]{
                // only 7-day and 14-day vessels.
//                {2, 0, 1, 4, 1},
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
//
//                // 7-day, 10-day and 14-day vessels.
//                {4, 1, 1, 4, 2},
//                {8, 2, 2, 4, 4},
//                {12, 3, 3, 4, 6},
//                {16, 4, 4, 4, 8},
                {20, 5, 5, 4, 10},

        };
        for (int[] configuration : instanceConfigurations) {
            for (int j = 1; j <= 5; j++) {
                int small = configuration[0];
                int medium = configuration[1];
                int large = configuration[2];
                int rows = configuration[3];
                int cols = configuration[4];
                int seed = j;

                String fileName = String.format("input/" + Instance.DEFAULT_NAME_PATTERN + ".json",
                        small, medium, large, rows, cols, seed);

                Instance instance = Instance.readJson(fileName);
                MasterYardTemplateHeuristic heuristic = new MasterYardTemplateHeuristic(instance);
                Map<VesselPeriod, Set<Subblock>> subblockAssignment = heuristic.assignByFirstComeFirstServed();
                if (subblockAssignment == null) {
                    System.out.println("No answer: " + String.format(fileName,
                            small, medium, large, rows, cols, seed));
                } else {
                    // Print Subblock Assignment
//                    for (VesselPeriod vp : result.keySet()) {
//                        System.out.print(vp + ": ");
//                        for (Subblock sb : result.get(vp)) {
//                            System.out.print(sb + ",");
//                        }
//                        System.out.println();
//                    }


                    CplexFixedSubblockModel solver = new CplexFixedSubblockModel(instance, new IloCplex());
                    Solution solution = solver.solveIntegratedSP(subblockAssignment);


                    if (Math.abs(solution.getObjRoute() - solver.cplex.getValue(solver.objRoute)) > solver.PRECISION ||
                            Math.abs(solution.getObjTime() - solver.cplex.getValue(solver.objTime)) > solver.PRECISION ||
                            Math.abs(solution.getObjCongestion() - solver.cplex.getValue(solver.objCongestion)) > solver.PRECISION ||
                            Math.abs(solution.getObjAll() - solver.cplex.getObjValue()) > solver.PRECISION
                    ) throw new RuntimeException(
                            "Objective value mismatch details:\n" +
                                    "Total: solution.objAll=" + solution.getObjAll() + " vs Cplex objValue=" + solver.cplex.getObjValue() + "\n" +
                                    "Route: solution.objRoute=" + solution.getObjRoute() + " vs Cplex objRoute=" + solver.cplex.getValue(solver.objRoute) + "\n" +
                                    "Time: solution.objTime=" + solution.getObjTime() + " vs Cplex objTime=" + solver.cplex.getValue(solver.objTime) + "\n" +
                                    "Congestion: solution.objCongestion=" + solution.getObjCongestion() + " vs Cplex congestion=" + solver.cplex.getValue(solver.objCongestion)
                    );

//                    s.summary();
                    System.out.println(solution.briefObjectives());
                }
            }
        }

    }

}
