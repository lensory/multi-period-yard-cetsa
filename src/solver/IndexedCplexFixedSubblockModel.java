package solver;

import entity.*;
import ilog.concert.*;
import ilog.cplex.IloCplex;
import main.InstanceGenerator;
import util.MyMathMethods;

import java.util.*;
import java.util.stream.Collectors;

public class IndexedCplexFixedSubblockModel {
    public final IloCplex cplex;
    private final Instance instance;
    private final int horizon, roads;
    private final double PRECISION = 1e-6;

    //    private Map<VesselPeriod, Map<Subblock, Map<VesselPeriod, Integer>>> containerAssignment; // ip -> k -> jqs -> w
    private Map<VesselPeriod, List<Subblock>> subblockAssignment; // solution of Y: ip -> k

    private Map<VesselPeriod, Map<Integer, Map<VesselPeriod, IloIntVar>>> varZ; // ip->k->jq
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

    private IloIntVar varUnloadOverload;
    private IloIntVar varLoadOverload;

    public IloObjective objective;
    public IloLinearNumExpr objTime;
    public IloLinearNumExpr objRoute;
    public IloLinearNumExpr objCongestion;

    public IndexedCplexFixedSubblockModel(Instance instance, IloCplex cplex) {
        this.instance = instance;
        this.horizon = instance.horizon;
        this.roads = instance.roads;
        this.cplex = cplex;
    }

    public IndexedCplexFixedSubblockModel changeSubblockAssignmentTo(Map<VesselPeriod, Set<Subblock>> target) throws IloException {
        Map<VesselPeriod, Map<Integer, SubblockChange>> changes = SubblockChange.getChanges(subblockAssignment, target);

        int changeCnt = 0;
//        System.out.println("Changing subblock assignment");
        for (Map.Entry<VesselPeriod, Map<Integer, SubblockChange>> entry : changes.entrySet()) {
            VesselPeriod ip = entry.getKey();
//            System.out.print(ip + ": ");
            for (Map.Entry<Integer, SubblockChange> pair : entry.getValue().entrySet()) {
                int index = pair.getKey();
                SubblockChange change = pair.getValue();
                if (change.isReplace()) {
//                    System.out.print(change.oldSubblock + "->" + change.newSubblock + ", ");
                    changeSubblockAssignmentInBatch(ip, index, change.oldSubblock, change.newSubblock);
                    changeCnt++;
                } else {
                    throw new IllegalArgumentException("Unsupported change type: " + change);
                }
            }
//            System.out.println();
        }
        int total = subblockAssignment.values().stream().mapToInt(Collection::size).sum();
//        System.out.println("Number of changed subblock: " + changeCnt + "/" + total + "(" + changeCnt * 100.0 / total + "%)");
        return this;
    }

    public void changeSubblockAssignmentInBatch(VesselPeriod ip, Subblock oldSubblock, Subblock newSubblock) throws IloException {
        List<Subblock> subblocks = subblockAssignment.get(ip);
        int index = subblocks.indexOf(oldSubblock);
        subblocks.set(index, newSubblock);
        modifyOneByOne(ip, index, oldSubblock, newSubblock);
    }

    public void changeSubblockAssignmentInBatch(VesselPeriod ip, int index, Subblock oldSubblock, Subblock newSubblock) throws IloException {
        List<Subblock> subblocks = subblockAssignment.get(ip);
        subblocks.set(index, newSubblock);
        modifyOneByOne(ip, index, oldSubblock, newSubblock);
    }

    private IloLPMatrix lpMatrix;
    private Map<VesselPeriod, Map<Integer, Map<Integer, Integer>>> NlinkRhoDeltaL;
    private Map<VesselPeriod, Map<Integer, Map<VesselPeriod, Map<Integer, Integer>>>> NlinkRhoDeltaU;
    private int[][] NconstraintLoadFlows;
    private int[][] NconstraintUnloadFlows;


    public void modifyByLPMatrix(VesselPeriod ip, int index, Subblock oldSubblock, Subblock newSubblock) throws IloException {
        if (lpMatrix == null) {
            Iterator<IloLPMatrix> matrices = cplex.LPMatrixIterator();
            if (matrices.hasNext()) {
                lpMatrix = matrices.next(); // 获取第一个 IloLPMatrix
            }
            NlinkRhoDeltaL = new HashMap<>();
            for (Map.Entry<VesselPeriod, Map<Integer, Map<Integer, IloRange>>> entry : linkRhoDeltaL.entrySet()) {
                VesselPeriod p = entry.getKey();
                for (Map.Entry<Integer, Map<Integer, IloRange>> entry1 : entry.getValue().entrySet()) {
                    int k = entry1.getKey();
                    for (Map.Entry<Integer, IloRange> entry2 : entry1.getValue().entrySet()) {
                        int t = entry2.getKey();
                        IloRange constraint = entry2.getValue();
                        NlinkRhoDeltaL.computeIfAbsent(p, k1 -> new HashMap<>())
                                .computeIfAbsent(k, k1 -> new HashMap<>())
                                .put(t, lpMatrix.getIndex(constraint));
                    }
                }
            }
            NlinkRhoDeltaU = new HashMap<>();
            for (Map.Entry<VesselPeriod, Map<Integer, Map<VesselPeriod, Map<Integer, IloRange>>>> entry : linkRhoDeltaU.entrySet()) {
                VesselPeriod p = entry.getKey();
                for (Map.Entry<Integer, Map<VesselPeriod, Map<Integer, IloRange>>> entry1 : entry.getValue().entrySet()) {
                    int k = entry1.getKey();
                    for (Map.Entry<VesselPeriod, Map<Integer, IloRange>> entry2 : entry1.getValue().entrySet()) {
                        VesselPeriod q = entry2.getKey();
                        for (Map.Entry<Integer, IloRange> entry3 : entry2.getValue().entrySet()) {
                            int t = entry3.getKey();
                            IloRange constraint = entry3.getValue();
                            NlinkRhoDeltaU.computeIfAbsent(p, k1 -> new HashMap<>())
                                    .computeIfAbsent(k, k1 -> new HashMap<>())
                                    .computeIfAbsent(q, k1 -> new HashMap<>())
                                    .put(t, lpMatrix.getIndex(constraint));
                        }
                    }
                }
            }
            NconstraintLoadFlows = new int[roads][instance.horizon];
            for (int l = 0; l < roads; l++)
                for (int t = 0; t < instance.horizon; t++) {
                    NconstraintLoadFlows[l][t] = lpMatrix.getIndex(constraintLoadFlows[l][t]);
                }
            NconstraintUnloadFlows = new int[roads][instance.horizon];
            for (int l = 0; l < roads; l++)
                for (int t = 0; t < instance.horizon; t++) {
                    NconstraintUnloadFlows[l][t] = lpMatrix.getIndex(constraintUnloadFlows[l][t]);
                }

        }


    }

    public void modifyInBatch(VesselPeriod ip, int index, Subblock oldSubblock, Subblock newSubblock) throws IloException {
        ArrayList<IloIntVar> vars = new ArrayList<>();
        ArrayList<Double> vals = new ArrayList<>();
        // modify the objRoute
        for (VesselPeriod jq : instance.getSourceVesselPeriodsOf(ip)) {
            double oldDistance = instance.getDistanceToSubblock(jq, oldSubblock) + instance.getDistanceFromSubblock(ip, oldSubblock);
            double newDistance = instance.getDistanceToSubblock(jq, newSubblock) + instance.getDistanceFromSubblock(ip, newSubblock);
            vars.add(varW.get(ip).get(index).get(jq));
            vals.add(newDistance * instance.etaRoute);
            objRoute.addTerm(newDistance - oldDistance, varW.get(ip).get(index).get(jq));
        }

        cplex.setLinearCoefs(objective,
                vals.stream().mapToDouble(Double::doubleValue).toArray(),
                vars.toArray(new IloIntVar[0]));


        for (int t : ip.getFeasibleInterval().intStream(instance.horizon)) {
            IloRange constraint = linkRhoDeltaL.get(ip).get(index).get(t);
            cplex.setLinearCoefs(constraint, new double[]{0, 1},
                    new IloIntVar[]{varRho.get(oldSubblock).get(t), varRho.get(newSubblock).get(t)});
        }

        for (VesselPeriod jq : instance.getSourceVesselPeriodsOf(ip)) {
            for (int t : ip.getPeriodInterval().intersection(jq.getFeasibleInterval(), instance.horizon)) {
                IloRange constraint = linkRhoDeltaU.get(ip).get(index).get(jq).get(t);
                cplex.setLinearCoefs(constraint, new double[]{0, 1},
                        new IloIntVar[]{varRho.get(oldSubblock).get(t), varRho.get(newSubblock).get(t)});
            }
        }

        Map<IloRange, Map<IloIntVar, Double>> loadChanges = new HashMap<>();
        for (int t : ip.getFeasibleInterval().intStream(instance.horizon)) {
            IloIntVar deltaL = varDeltaL.get(ip).get(index).get(t);
            for (int l : instance.getRouteFromSubblock(ip, oldSubblock)) {
                IloRange constraint = constraintLoadFlows[l][t];
                loadChanges.computeIfAbsent(constraint, key -> new HashMap<>())
                        .put(deltaL, 0.0);
            }
            for (int l : instance.getRouteFromSubblock(ip, newSubblock)) {
                IloRange constraint = constraintLoadFlows[l][t];
                loadChanges.computeIfAbsent(constraint, key -> new HashMap<>())
                        .put(deltaL, 1.0);
            }
        }
        for (Map.Entry<IloRange, Map<IloIntVar, Double>> entry : loadChanges.entrySet()) {
            IloRange constraint = entry.getKey();
            List<IloIntVar> varList = new ArrayList<>();
            List<Double> valList = new ArrayList<>();
            for (Map.Entry<IloIntVar, Double> e : entry.getValue().entrySet()) {
                varList.add(e.getKey());
                valList.add(e.getValue());
            }
            IloIntVar[] varArray = varList.toArray(new IloIntVar[0]);
            double[] valArray = valList.stream().mapToDouble(Double::doubleValue).toArray();

            cplex.setLinearCoefs(constraint, valArray, varArray);
        }


        Map<IloRange, Map<IloIntVar, Double>> unloadChanges = new HashMap<>();
        for (VesselPeriod jq : instance.getSourceVesselPeriodsOf(ip))
            for (int t : ip.getPeriodInterval().intersection(jq.getFeasibleInterval(), instance.horizon)) {
                IloIntVar deltaU = varDeltaU.get(ip).get(index).get(jq).get(t);
                for (int l : instance.getRouteToSubblock(jq, oldSubblock)) {
                    IloRange constraint = constraintUnloadFlows[l][t];
                    unloadChanges.computeIfAbsent(constraint, key -> new HashMap<>())
                            .put(deltaU, 0.0);
                }
                for (int l : instance.getRouteToSubblock(jq, newSubblock)) {
                    IloRange constraint = constraintUnloadFlows[l][t];
                    unloadChanges.computeIfAbsent(constraint, key -> new HashMap<>())
                            .put(deltaU, 1.0);
                }
            }

        for (Map.Entry<IloRange, Map<IloIntVar, Double>> entry : unloadChanges.entrySet()) {
            IloRange constraint = entry.getKey();
            List<IloIntVar> varList = new ArrayList<>();
            List<Double> valList = new ArrayList<>();
            for (Map.Entry<IloIntVar, Double> e : entry.getValue().entrySet()) {
                varList.add(e.getKey());
                valList.add(e.getValue());
            }
            IloIntVar[] varArray = varList.toArray(new IloIntVar[0]);
            double[] valArray = valList.stream().mapToDouble(Double::doubleValue).toArray();

            cplex.setLinearCoefs(constraint, valArray, varArray);
        }

    }

    public void modifyOneByOne(VesselPeriod ip, int index, Subblock oldSubblock, Subblock newSubblock) throws IloException {

        // modify the objRoute
        for (VesselPeriod jq : instance.getSourceVesselPeriodsOf(ip)) {
            double oldDistance = instance.getDistanceToSubblock(jq, oldSubblock) + instance.getDistanceFromSubblock(ip, oldSubblock);
            double newDistance = instance.getDistanceToSubblock(jq, newSubblock) + instance.getDistanceFromSubblock(ip, newSubblock);
            cplex.setLinearCoef(objective, newDistance * instance.etaRoute, varW.get(ip).get(index).get(jq));
            objRoute.addTerm(newDistance - oldDistance, varW.get(ip).get(index).get(jq));
        }


        for (int t : ip.getFeasibleInterval().intStream(instance.horizon)) {
            IloRange constraint = linkRhoDeltaL.get(ip).get(index).get(t);
            cplex.setLinearCoef(constraint, 0, varRho.get(oldSubblock).get(t));
            cplex.setLinearCoef(constraint, 1, varRho.get(newSubblock).get(t));
        }
        for (VesselPeriod jq : instance.getSourceVesselPeriodsOf(ip)) {
            for (int t : ip.getPeriodInterval().intersection(jq.getFeasibleInterval(), instance.horizon)) {
                IloRange constraint = linkRhoDeltaU.get(ip).get(index).get(jq).get(t);
                cplex.setLinearCoef(constraint, 0, varRho.get(oldSubblock).get(t));
                cplex.setLinearCoef(constraint, 1, varRho.get(newSubblock).get(t));
            }
        }

        for (int t : ip.getFeasibleInterval().intStream(instance.horizon)) {
            IloIntVar deltaL = varDeltaL.get(ip).get(index).get(t);
            for (int l : instance.getRouteFromSubblock(ip, oldSubblock)) {
                IloRange constraint = constraintLoadFlows[l][t];
                cplex.setLinearCoef(constraint, 0, deltaL);
            }
            for (int l : instance.getRouteFromSubblock(ip, newSubblock)) {
                IloRange constraint = constraintLoadFlows[l][t];
                cplex.setLinearCoef(constraint, 1, deltaL);
            }
        }


        for (VesselPeriod jq : instance.getSourceVesselPeriodsOf(ip))
            for (int t : ip.getPeriodInterval().intersection(jq.getFeasibleInterval(), instance.horizon)) {
                IloIntVar deltaU = varDeltaU.get(ip).get(index).get(jq).get(t);
                for (int l : instance.getRouteToSubblock(jq, oldSubblock)) {
                    IloRange constraint = constraintUnloadFlows[l][t];
                    cplex.setLinearCoef(constraint, 0, deltaU);
                }
                for (int l : instance.getRouteToSubblock(jq, newSubblock)) {
                    IloRange constraint = constraintUnloadFlows[l][t];
                    cplex.setLinearCoef(constraint, 1, deltaU);
                }
            }
    }


    public boolean solve() throws IloException {
        return cplex.solve();

    }

    public Solution solveSP2WithSolution(Map<VesselPeriod, Map<Subblock, Map<VesselPeriod, Integer>>> containerAssignment) throws IloException {
        Map<VesselPeriod, Map<Subblock, Set<VesselPeriod>>> transferAssignment = containerAssignment.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> entry.getValue().entrySet().stream().collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry1 -> new HashSet<>(entry1.getValue().keySet())
                ))
        ));

        IndexedCplexFixedSubblockModel model = IndexedCplexFixedSubblockModel.buildSP2Model(instance, cplex, transferAssignment);
        boolean solved = model.solve();

        Map<VesselPeriod, Map<Subblock, Map<VesselPeriod, Integer>>> operationSchedule;
        operationSchedule = solved ? model.getSolutionOperationSchedule() : null;

        if (operationSchedule != null) {
            return mergeSolution(containerAssignment, operationSchedule);
        }
        return null;
    }


    public static IndexedCplexFixedSubblockModel buildIntegratedSubproblemModel(
            Instance instance, IloCplex cplex, Map<VesselPeriod, ? extends Collection<Subblock>> subblockAssignment) throws IloException {
        IndexedCplexFixedSubblockModel model = new IndexedCplexFixedSubblockModel(instance, cplex);
        model.setSubblockAssignment(subblockAssignment);

        model.initVarZ();
        model.variableSP1();
        model.variableSP2();
        model.linkZW();
        model.linkZDelta();
        model.constrainSP1();
        model.constrainSP2();
        model.initObjRoute();
        model.initObjSP2();
        model.objective = cplex.addMinimize(cplex.sum(
                model.objRoute,
                model.objTime,
                model.objCongestion
        ));

        return model;
    }

    public static IndexedCplexFixedSubblockModel buildSP2Model(
            Instance instance, IloCplex cplex, Map<VesselPeriod, Map<Subblock, Set<VesselPeriod>>> transferAssignment
    ) throws IloException {
        IndexedCplexFixedSubblockModel model = new IndexedCplexFixedSubblockModel(instance, cplex);
        model.setSubblockAssignmentWithTransferAssignment(transferAssignment);

        model.variableSP2();
        model.constrainSP2();
        model.initObjSP2();
        cplex.addMinimize(cplex.sum(
                model.objTime,
                model.objCongestion
        ));

        for (VesselPeriod ip : instance.getVesselPeriods()) {
            List<Subblock> subblocks = model.subblockAssignment.getOrDefault(ip, Collections.emptyList());
            for (int k = 0; k < subblocks.size(); k++) {
                Subblock subblock = subblocks.get(k);
                for (VesselPeriod jq : instance.getSourceVesselPeriodsOf(ip)) {
                    IloLinearIntExpr expr = cplex.linearIntExpr();
                    for (int t : ip.getPeriodInterval().intersection(jq.getFeasibleInterval(), instance.horizon))
                        expr.addTerm(1, model.varDeltaU.get(ip).get(k).get(jq).get(t));

                    if (transferAssignment.get(ip).get(subblock).contains(jq))
                        cplex.addEq(expr, 1, String.format("ConsHandleZ_%d,%d,%d,%d,%d",
                                ip.getVid(), ip.getPid(), k, jq.getVid(), jq.getPid()));
                    else
                        cplex.addEq(expr, 0, String.format("ConsHandleZ_%d,%d,%d,%d,%d",
                                ip.getVid(), ip.getPid(), k, jq.getVid(), jq.getPid()));
                }
            }
        }


        return model;
    }

    public static IndexedCplexFixedSubblockModel buildSP1(Instance instance, IloCplex cplex, Map<VesselPeriod, Map<Subblock, Set<VesselPeriod>>> transferAssignment) throws IloException {
        IndexedCplexFixedSubblockModel model = new IndexedCplexFixedSubblockModel(instance, cplex);
        model.setSubblockAssignmentWithTransferAssignment(transferAssignment);

        model.variableSP1();
        model.constrainSP1();
        model.initObjRoute();
        cplex.addMinimize(model.objRoute);

        for (VesselPeriod ip : instance.getVesselPeriods()) {
            List<Subblock> subblocks = model.subblockAssignment.getOrDefault(ip, Collections.emptyList());
            for (int k = 0; k < subblocks.size(); k++) {
                Subblock subblock = subblocks.get(k);
                for (VesselPeriod jq : instance.getSourceVesselPeriodsOf(ip))
                    if (!transferAssignment.get(ip).get(subblock).contains(jq))
                        model.varW.get(ip).get(k).get(jq).setUB(0);
            }
        }

        return model;
    }

    private Solution mergeSolution(Map<VesselPeriod, Map<Subblock, Map<VesselPeriod, Integer>>> containerAssignment,
                                   Map<VesselPeriod, Map<Subblock, Map<VesselPeriod, Integer>>> operationSchedule) throws IloException {
        Solution solution = new Solution(instance);
        for (VesselPeriod ip : instance.getVesselPeriods()) {
            for (Subblock k : subblockAssignment.getOrDefault(ip, Collections.emptyList())) {
                solution.setSubBlock(ip, k);
            }
        }

        for (VesselPeriod ip : instance.getVesselPeriods())
            for (Subblock k : subblockAssignment.getOrDefault(ip, Collections.emptyList())) {
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

    public Map<VesselPeriod, List<Subblock>> getSubblockAssignmentArray() {
        Map<VesselPeriod, List<Subblock>> subblockAssignmentArray = new HashMap<>();
        for (Map.Entry<VesselPeriod, List<Subblock>> entry : subblockAssignment.entrySet()) {
            subblockAssignmentArray.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return subblockAssignmentArray;
    }

    public Solution getIntegratedSolution() throws IloException {
        return mergeSolution(getSolutionContainerAssignment(), getSolutionOperationSchedule());
    }

    private void initVarZ() throws IloException {
        varZ = new HashMap<>(instance.getNumVesselPeriods());
        for (VesselPeriod ip : instance.getVesselPeriods()) {
            int numK = subblockAssignment.getOrDefault(ip, Collections.emptyList()).size();
            Map<Integer, Map<VesselPeriod, IloIntVar>> _varZ = new HashMap<>(numK);
            varZ.put(ip, _varZ);
            for (int k = 0; k < numK; k++) {
                HashMap<VesselPeriod, IloIntVar> __varZ = new HashMap<>(instance.getSourceVesselPeriodsOf(ip).size());
                _varZ.put(k, __varZ);
                for (VesselPeriod jq : instance.getSourceVesselPeriodsOf(ip)) {
                    __varZ.put(jq, cplex.boolVar(String.format("Z_%d,%d,%d,%d,%d",
                            k, ip.getVid(), ip.getPid(), jq.getVid(), jq.getPid())));
                }
            }
        }
    }

    private void linkZW() throws IloException {
        for (VesselPeriod ip : instance.getVesselPeriods()) {
            for (int k = 0; k < subblockAssignment.getOrDefault(ip, Collections.emptyList()).size(); k++)
                for (VesselPeriod jq : instance.getSourceVesselPeriodsOf(ip))
                    cplex.addLe(varW.get(ip).get(k).get(jq),
                            cplex.prod(instance.spaceCapacity, varZ.get(ip).get(k).get(jq)),
                            String.format("link_Z_W_%d,%d,%d,%d,%d", ip.getVid(), ip.getPid(), k, jq.getVid(), jq.getPid()));
        }
    }

    private void linkZDelta() throws IloException {
        for (VesselPeriod ip : instance.getVesselPeriods())
            for (int k = 0; k < subblockAssignment.getOrDefault(ip, Collections.emptyList()).size(); k++)
                for (VesselPeriod jq : instance.getSourceVesselPeriodsOf(ip)) {
                    IloLinearIntExpr expr = cplex.linearIntExpr();
                    for (int t : ip.getPeriodInterval().intersection(jq.getFeasibleInterval(), instance.horizon))
                        expr.addTerm(1, varDeltaU.get(ip).get(k).get(jq).get(t));
                    cplex.addEq(expr, varZ.get(ip).get(k).get(jq), String.format("link_Z_DeltaU_%d,%d,%d,%d,%d",
                            ip.getVid(), ip.getPid(), k, jq.getVid(), jq.getPid()));
                }
        for (VesselPeriod ip : instance.getVesselPeriods())
            for (int k = 0; k < subblockAssignment.getOrDefault(ip, Collections.emptyList()).size(); k++) {
                IloLinearIntExpr expr = cplex.linearIntExpr();
                for (int t : ip.getFeasibleInterval().intStream(instance.horizon)) {
                    expr.addTerm(1, varDeltaL.get(ip).get(k).get(t));
                }


                cplex.addEq(expr, 1, String.format("link_Z_DeltaL%d,%d,%d", ip.getVid(), ip.getPid(), k));

//                IloLinearIntExpr rhs = cplex.linearIntExpr();
//                for (VesselPeriod jq : instance.getSourceVesselPeriodsOf(ip))
//                    rhs.addTerm(1, varZ.get(ip).get(k).get(jq));
//                cplex.addLe(expr, rhs, String.format("link_Z_DeltaL_Part1_%d,%d,%d", ip.getVId(), ip.getPId(), k));
//                cplex.addLe(expr, 1, String.format("link_Z_DeltaL_Part2_%d,%d,%d", ip.getVId(), ip.getPId(), k));
//                for (VesselPeriod jq : instance.getSourceVesselPeriodsOf(ip))
//                    cplex.addGe(expr, varZ.get(ip).get(k).get(jq), String.format("link_Z_DeltaL_Part3_%d,%d,%d,%d,%d",
//                            ip.getVId(), ip.getPId(), k, jq.getVId(), jq.getPId()));
            }
    }

    private void setSubblockAssignment(Map<VesselPeriod, ? extends Collection<Subblock>> subblockAssignment) {
        // deepCopy subblockAssignment
        if (this.subblockAssignment != null)
            throw new IllegalStateException("The subblock assignment has already been initialized.");

        this.subblockAssignment = new HashMap<>();

        for (Map.Entry<VesselPeriod, ? extends Collection<Subblock>> entry : subblockAssignment.entrySet()) {
            VesselPeriod ip = entry.getKey();
            Collection<Subblock> subblocks = entry.getValue();

            // check whether the Collection<Subblock> contains duplicate Subblock
            Set<Integer> subblockIds = new HashSet<>();
            boolean hasDuplicates = subblocks.stream().anyMatch(sb -> !subblockIds.add(sb.getId()));

            if (hasDuplicates) {
                throw new IllegalArgumentException(String.format(
                        "Duplicate subblocks found for VesselPeriod [%s]. Subblocks: %s",
                        ip, subblocks));
            }

            this.subblockAssignment.put(ip, new ArrayList<>(subblocks));
        }
    }

    private void setSubblockAssignmentWithTransferAssignment(Map<VesselPeriod, Map<Subblock, Set<VesselPeriod>>> transferAssignment) {
        // subblockAssignment是transferAssignment中第一级的VesselPeriod和第二个Subblock构成的Map
        if (this.subblockAssignment != null)
            throw new IllegalStateException("The subblock assignment has already been initialized.");

        this.subblockAssignment = new HashMap<>();
        for (Map.Entry<VesselPeriod, Map<Subblock, Set<VesselPeriod>>> entry : transferAssignment.entrySet()) {
            this.subblockAssignment.put(
                    entry.getKey(),
                    new ArrayList<>(entry.getValue().keySet()));
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

    public Map<VesselPeriod, Map<Subblock, Map<VesselPeriod, Integer>>> getSolutionContainerAssignment() throws IloException {
        validateCplexStatus();
        if (varW == null || varW.isEmpty()) {
            throw new IllegalStateException("Variable varW is not initialized or is empty.");
        }

        Map<VesselPeriod, Map<Subblock, Map<VesselPeriod, Integer>>> containerAssignment = new HashMap<>(instance.getNumVesselPeriods());
        for (VesselPeriod ip : instance.getVesselPeriods())
            for (int k = 0; k < subblockAssignment.getOrDefault(ip, Collections.emptyList()).size(); k++) {
                Subblock subblock = subblockAssignment.getOrDefault(ip, Collections.emptyList()).get(k);
                for (VesselPeriod jq : instance.getSourceVesselPeriodsOf(ip)) {
                    int w = getIntValue(varW.get(ip).get(k).get(jq));
                    if (w > 0)
                        containerAssignment.computeIfAbsent(ip, key -> new HashMap<>())
                                .computeIfAbsent(subblock, key -> new HashMap<>())
                                .put(jq, w);
                }
            }
        return containerAssignment;
    }

    private void variableSP1() throws IloException {
        initVarW();
    }


    private void initVarW() throws IloException {
        varW = new HashMap<>(instance.getNumVesselPeriods());
        for (VesselPeriod ip : instance.getVesselPeriods()) {
            int numK = subblockAssignment.getOrDefault(ip, Collections.emptyList()).size();
            Map<Integer, Map<VesselPeriod, IloIntVar>> _varW = new HashMap<>(numK);
            varW.put(ip, _varW);
            for (int k = 0; k < numK; k++) {
                Map<VesselPeriod, IloIntVar> __varW = new HashMap<>(instance.getSourceVesselPeriodsOf(ip).size());
                _varW.put(k, __varW);
                for (VesselPeriod jq : instance.getSourceVesselPeriodsOf(ip)) {
                    __varW.put(jq, cplex.intVar(0, instance.spaceCapacity, String.format("W_%d,%d,%d,%d,%d",
                            ip.getVid(), ip.getPid(), k, jq.getVid(), jq.getPid())));
                }
            }
        }
    }


    private void constrainSP1() throws IloException {
        for (VesselPeriod jq : instance.getVesselPeriods())
            for (VesselPeriod ip : instance.getDestinationVesselPeriodsOf(jq)) {
                int n = instance.getTransshipmentTo(jq, ip);

                IloLinearIntExpr expr = cplex.linearIntExpr();
                for (int k = 0; k < subblockAssignment.getOrDefault(ip, Collections.emptyList()).size(); k++) {
                    expr.addTerm(1, varW.get(ip).get(k).get(jq));
                }
                cplex.addEq(expr, n, String.format("ConsFlowN_%d,%d,%d,%d",
                        ip.getVid(), ip.getPid(), jq.getVid(), jq.getPid()));
            }

        for (VesselPeriod ip : instance.getVesselPeriods())
            for (int k = 0; k < subblockAssignment.getOrDefault(ip, Collections.emptyList()).size(); k++) {
                IloLinearIntExpr expr = cplex.linearIntExpr();
                for (VesselPeriod jq : instance.getSourceVesselPeriodsOf(ip))
                    expr.addTerm(1, varW.get(ip).get(k).get(jq));
                cplex.addLe(expr, instance.spaceCapacity,
                        String.format("ConsFlowC_%d,%d,%d", ip.getVid(), ip.getPid(), k));
            }
    }

    private void initObjRoute() throws IloException {
        objRoute = cplex.linearNumExpr();
        for (VesselPeriod ip : instance.getVesselPeriods()) {
            List<Subblock> subblocks = subblockAssignment.getOrDefault(ip, Collections.emptyList());
            for (int k = 0; k < subblocks.size(); k++) {
                Subblock subblock = subblocks.get(k);
                for (VesselPeriod jq : instance.getSourceVesselPeriodsOf(ip)) {
                    double distance = instance.getDistanceToSubblock(jq, subblock) + instance.getDistanceFromSubblock(ip, subblock);
                    objRoute.addTerm(distance * instance.etaRoute, varW.get(ip).get(k).get(jq));
                }
            }
        }
    }


    public Map<VesselPeriod, Map<Subblock, Map<VesselPeriod, Integer>>> getSolutionOperationSchedule() throws IloException {
        validateCplexStatus();
        if (varDeltaL == null || varDeltaL.isEmpty()) {
            throw new IllegalStateException("Variable varDeltaL is not initialized or is empty.");
        }
        if (varDeltaU == null || varDeltaU.isEmpty()) {
            throw new IllegalStateException("Variable varDeltaU is not initialized or is empty.");
        }

        // if 2nd VesselPeriod == 1st VesselPeriod, then it is loading.
        Map<VesselPeriod, Map<Subblock, Map<VesselPeriod, Integer>>> operationSchedule = new HashMap<>();

        for (VesselPeriod ip : instance.getVesselPeriods()) {
            List<Subblock> subblocks = subblockAssignment.getOrDefault(ip, Collections.emptyList());
            for (int k = 0; k < subblocks.size(); k++) {
                Subblock subblock = subblocks.get(k);
                for (int t : ip.getFeasibleInterval().intStream(instance.horizon)) {
                    if (getIntValue(varDeltaL.get(ip).get(k).get(t)) == 1) {
                        Integer preT = operationSchedule.computeIfAbsent(ip, key -> new HashMap<>())
                                .computeIfAbsent(subblock, key -> new HashMap<>())
                                .put(ip, t);
                        if (preT != null)
                            throw new IllegalArgumentException("The subblock " + subblock + " for Vessel Period " + ip + " is loading twice.");

                    }
                }
            }
        }

        for (VesselPeriod ip : instance.getVesselPeriods()) {
            List<Subblock> subblocks = subblockAssignment.getOrDefault(ip, Collections.emptyList());
            for (int k = 0; k < subblocks.size(); k++) {
                Subblock subblock = subblocks.get(k);
                for (VesselPeriod jq : instance.getSourceVesselPeriodsOf(ip))
                    for (int t : ip.getPeriodInterval().intersection(jq.getFeasibleInterval(), instance.horizon)) {
                        if (getIntValue(varDeltaU.get(ip).get(k).get(jq).get(t)) == 1) {
                            if (!operationSchedule.containsKey(ip) || !operationSchedule.get(ip).containsKey(subblock)) {
                                throw new IllegalArgumentException("The subblock " + subblock + " for Vessel Period " + ip + " is not loaded.");
                            }
                            Integer preT = operationSchedule.computeIfAbsent(ip, key -> new HashMap<>())
                                    .computeIfAbsent(subblock, key -> new HashMap<>())
                                    .put(jq, t);
                            if (preT != null)
                                throw new IllegalArgumentException("The subblock " + subblock + " for Vessel Period " + ip
                                        + " from Vessel Period " + jq + " is unloaded twice.");
                        }
                    }
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
        for (VesselPeriod ip : instance.getVesselPeriods()) {
            List<Subblock> subblocks = subblockAssignment.getOrDefault(ip, Collections.emptyList());
            Map<Integer, Map<Integer, IloIntVar>> _varDeltaL = new HashMap<>(subblocks.size());
            varDeltaL.put(ip, _varDeltaL);
            for (int k = 0; k < subblocks.size(); k++) {
                Map<Integer, IloIntVar> __varDeltaL = new HashMap<>(ip.getFeasibleInterval().getLength());
                _varDeltaL.put(k, __varDeltaL);
                for (int t : ip.getFeasibleInterval().intStream(instance.horizon)) {
                    __varDeltaL.put(t, cplex.boolVar(String.format("DeltaL_%d,%d,%d,%d",
                            ip.getVid(), ip.getPid(), k, t)));
                }
            }
        }

        varDeltaU = new HashMap<>(instance.getNumVesselPeriods());
        for (VesselPeriod ip : instance.getVesselPeriods()) {
            List<Subblock> subblocks = subblockAssignment.getOrDefault(ip, Collections.emptyList());
            Map<Integer, Map<VesselPeriod, Map<Integer, IloIntVar>>> _varDeltaU = new HashMap<>(subblocks.size());
            varDeltaU.put(ip, _varDeltaU);
            for (int k = 0; k < subblocks.size(); k++) {
                List<VesselPeriod> source = instance.getSourceVesselPeriodsOf(ip);
                Map<VesselPeriod, Map<Integer, IloIntVar>> __varDeltaU = new HashMap<>(source.size());
                _varDeltaU.put(k, __varDeltaU);
                for (VesselPeriod jq : source) {
                    Map<Integer, IloIntVar> ___varDeltaU = new HashMap<>();
                    __varDeltaU.put(jq, ___varDeltaU);
                    for (int t : ip.getPeriodInterval().intersection(jq.getFeasibleInterval(), instance.horizon)) {
                        ___varDeltaU.put(t, cplex.boolVar(String.format("DeltaU_%d,%d,%d,%d,%d,%d",
                                ip.getVid(), ip.getPid(), k, jq.getVid(), jq.getPid(), t)));
                    }
                }
            }
        }
    }


    private void initVarPi() throws IloException {
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
    }

    private void initVarIotaKappa() throws IloException {
        varIota = new HashMap<>(instance.getNumVesselPeriods());
        varKappa = new HashMap<>(instance.getNumVesselPeriods());
        for (VesselPeriod ip : instance.getVesselPeriods()) {
            varIota.put(ip, cplex.intVar(0, ip.getRelativeExpectedIntervalStart() - ip.getRelativeFeasibleIntervalStart(),
                    String.format("Iota_%d,%d", ip.getVid(), ip.getPid())));
            varKappa.put(ip, cplex.intVar(0, ip.getRelativeFeasibleIntervalEnd() - ip.getRelativeExpectedIntervalStart(),
                    String.format("Kappa_%d,%d", ip.getVid(), ip.getPid())));
        }
    }

    private void initVarRho() throws IloException {
        varRho = new HashMap<>(instance.getNumSubblocks());
        for (Subblock k : instance.getSubblocks()) {
            for (int t = 0; t < horizon; t++) {
                varRho.computeIfAbsent(k, key -> new HashMap<>(horizon))
                        .put(t, cplex.boolVar(String.format("Rho_%d,%d", k.getId(), t)));
            }
        }
    }


    private void constrainSP2() throws IloException {
        initBinaryHandlingTimeConstraints();
        initCongestionConstraints();
    }

    private void initBinaryHandlingTimeConstraints() throws IloException {

        for (VesselPeriod ip : instance.getVesselPeriods()) {
            List<Subblock> subblocks = subblockAssignment.getOrDefault(ip, Collections.emptyList());
            for (int k = 0; k < subblocks.size(); k++) {
                for (VesselPeriod jq : instance.getSourceVesselPeriodsOf(ip)) {
                    for (int t : ip.getPeriodInterval().intersection(jq.getFeasibleInterval(), instance.horizon)) {
                        cplex.addGe(varPiU.get(jq).get(t), varDeltaU.get(ip).get(k).get(jq).get(t),
                                String.format("ConsHandlePiU_%d,%d,%d,%d,%d,%d",
                                        ip.getVid(), ip.getPid(), k, jq.getVid(), jq.getPid(), t));
                    }
                }
            }
        }

        for (VesselPeriod ip : instance.getVesselPeriods()) {
            List<Subblock> subblocks = subblockAssignment.getOrDefault(ip, Collections.emptyList());
            for (int k = 0; k < subblocks.size(); k++) {
                for (int t : ip.getFeasibleInterval().intStream(instance.horizon)) {
                    cplex.addGe(varPiL.get(ip).get(t), varDeltaL.get(ip).get(k).get(t),
                            String.format("ConsHandlePiL_%d,%d,%d,%d", ip.getVid(), ip.getPid(), k, t));
                }

            }
        }

        for (VesselPeriod ip : instance.getVesselPeriods()) {
            List<Subblock> subblocks = subblockAssignment.getOrDefault(ip, Collections.emptyList());
            for (int k = 0; k < subblocks.size(); k++) {
                for (VesselPeriod jq : instance.getSourceVesselPeriodsOf(ip)) {
                    for (int t : ip.getPeriodInterval().intersection(jq.getFeasibleInterval(), instance.horizon)) {
                        cplex.addGe(varPiUD.get(ip).get(t), varDeltaU.get(ip).get(k).get(jq).get(t),
                                String.format("ConsHandlePiUD_%d,%d,%d,%d,%d,%d",
                                        ip.getVid(), ip.getPid(), k, jq.getVid(), jq.getPid(), t));

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

    private Map<VesselPeriod, Map<Integer, Map<Integer, IloRange>>> linkRhoDeltaL;
    private Map<VesselPeriod, Map<Integer, Map<VesselPeriod, Map<Integer, IloRange>>>> linkRhoDeltaU;
    private IloRange[][] constraintLoadFlows;
    private IloRange[][] constraintUnloadFlows;


    private void initCongestionConstraints() throws IloException {

        for (Subblock k1 : instance.getSubblocks())
            for (Subblock k2 : instance.getSubblocks())
                if ((!k1.equals(k2)) && (k1.isNeighborInSameBlock(k2) || k1.isNeighborAcrossLane(k2)))
                    for (int t = 0; t < horizon; t++) {
                        cplex.addLe(cplex.sum(varRho.get(k1).get(t), varRho.get(k2).get(t)), 1,
                                String.format("ConsCongNeighbor%d,%d,%d", k1.getId(), k2.getId(), t));
                    }


        linkRhoDeltaL = new HashMap<>(instance.getNumVesselPeriods());
        for (VesselPeriod ip : instance.getVesselPeriods()) {
            List<Subblock> subblocks = subblockAssignment.getOrDefault(ip, Collections.emptyList());
            for (int k = 0; k < subblocks.size(); k++) {
                Subblock subblock = subblocks.get(k);
                for (int t : ip.getFeasibleInterval().intStream(instance.horizon)) {

                    IloRange constraint = cplex.addGe(cplex.diff(
                            varRho.get(subblock).get(t), varDeltaL.get(ip).get(k).get(t)
                    ), 0, String.format("ConsCongRhoL_%d,%d,%d,%d", ip.getVid(), ip.getPid(), k, t));

                    linkRhoDeltaL.computeIfAbsent(ip, key -> new HashMap<>())
                            .computeIfAbsent(k, key -> new HashMap<>())
                            .put(t, constraint);
                }
            }
        }

        linkRhoDeltaU = new HashMap<>(instance.getNumVesselPeriods());
        for (VesselPeriod ip : instance.getVesselPeriods()) {
            List<Subblock> subblocks = subblockAssignment.getOrDefault(ip, Collections.emptyList());
            for (int k = 0; k < subblocks.size(); k++) {
                Subblock subblock = subblocks.get(k);
                for (VesselPeriod jq : instance.getSourceVesselPeriodsOf(ip))
                    for (int t : ip.getPeriodInterval().intersection(jq.getFeasibleInterval(), instance.horizon)) {
                        IloRange constraint = cplex.addGe(cplex.diff(
                                varRho.get(subblock).get(t), varDeltaU.get(ip).get(k).get(jq).get(t)
                        ), 0, String.format("ConsCongRhoU_%d,%d,%d,%d,%d,%d", ip.getVid(), ip.getPid(), k, jq.getVid(), jq.getPid(), t));
                        linkRhoDeltaU.computeIfAbsent(ip, key -> new HashMap<>())
                                .computeIfAbsent(k, key -> new HashMap<>())
                                .computeIfAbsent(jq, key -> new HashMap<>())
                                .put(t, constraint);
                    }
            }
        }

        IloLinearIntExpr[][] exprU = new IloLinearIntExpr[roads][horizon];
        for (int l = 0; l < roads; l++)
            for (int t = 0; t < horizon; t++) {
                exprU[l][t] = cplex.linearIntExpr();
            }

        for (VesselPeriod ip : instance.getVesselPeriods()) {
            List<Subblock> subblocks = subblockAssignment.getOrDefault(ip, Collections.emptyList());
            for (int k = 0; k < subblocks.size(); k++) {
                Subblock subblock = subblocks.get(k);
                for (VesselPeriod jq : instance.getSourceVesselPeriodsOf(ip)) {
                    for (int l : instance.getRouteToSubblock(jq, subblock))
                        for (int t : ip.getPeriodInterval().intersection(jq.getFeasibleInterval(), instance.horizon)) {
                            exprU[l][t].addTerm(1, varDeltaU.get(ip).get(k).get(jq).get(t));
                        }
                }
            }
        }

        constraintUnloadFlows = new IloRange[roads][horizon];
        for (int l = 0; l < roads; l++)
            for (int t = 0; t < horizon; t++) {
                exprU[l][t].addTerm(-1, varUnloadOverload);
                constraintUnloadFlows[l][t] = cplex.addLe(exprU[l][t], instance.maxUnloadFlows,
                        String.format("ConsCongRoadU%d,%d", l, t));
            }

        IloLinearIntExpr[][] exprL = new IloLinearIntExpr[roads][horizon];
        for (int l = 0; l < roads; l++)
            for (int t = 0; t < horizon; t++) {
                exprL[l][t] = cplex.linearIntExpr();
            }

        for (VesselPeriod ip : instance.getVesselPeriods()) {
            List<Subblock> subblocks = subblockAssignment.getOrDefault(ip, Collections.emptyList());
            for (int k = 0; k < subblocks.size(); k++) {
                Subblock subblock = subblocks.get(k);
                for (int l : instance.getRouteFromSubblock(ip, subblock))
                    for (int t : ip.getFeasibleInterval().intStream(instance.horizon)) {
                        exprL[l][t].addTerm(1, varDeltaL.get(ip).get(k).get(t));
                    }
            }
        }

        constraintLoadFlows = new IloRange[roads][horizon];
        for (int l = 0; l < roads; l++)
            for (int t = 0; t < horizon; t++) {
                exprL[l][t].addTerm(-1, varLoadOverload);
                constraintLoadFlows[l][t] = cplex.addLe(exprL[l][t], instance.maxLoadFlows,
                        String.format("ConsCongRoadL%d,%d", l, t));
            }
    }


    private void initObjSP2() throws IloException {
        objTime = cplex.linearNumExpr();
        for (VesselPeriod ip : instance.getVesselPeriods()) {
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

    private static void compareTwoIntegratedSubproblemModel() throws IloException {
        int[][] instanceConfigurations = new int[][]{
                // only 7-day and 14-day vessels.
                {2, 0, 1, 6, 1},
                {4, 0, 2, 6, 2},
                {6, 0, 3, 6, 3},
                {8, 0, 4, 6, 4},
                {10, 0, 5, 6, 5},

                // 7-day, 10-day and 14-day vessels.
                {4, 1, 1, 6, 2},
                {8, 2, 2, 6, 4},
                {12, 3, 3, 6, 6},
                {16, 4, 4, 6, 8},
                {20, 5, 5, 6, 10},

        };
        for (int[] configuration : instanceConfigurations)
            for (int j = 1; j <= 5; j++) {
                int small = configuration[0];
                int medium = configuration[1];
                int large = configuration[2];
                int rows = configuration[3];
                int cols = configuration[4];
                int seed = j;


                Instance instance = InstanceGenerator.generate(
                        small, medium, large, rows, cols, seed
                );

                System.out.println("Test Instance: " + small + ", " + medium + ", " + large + ", " + rows + ", " + cols + ", " + seed);

                MasterYardTemplateHeuristic heuristic = new MasterYardTemplateHeuristic(instance);
                Map<VesselPeriod, Set<Subblock>> subblockAssignment = heuristic.assignByFirstComeFirstServed();
                if (subblockAssignment == null) {
                    System.out.println("No answer: " + String.format("input/instance_{%d-%d-%d}_{%d-%d}_%d",
                            small, medium, large, rows, cols, seed));
                } else {
                    long startTime;
//                    Map<VesselPeriod, List<Subblock>> subblockAssignmentList;

                    try (IloCplex cplex = new IloCplex()) {
                        cplex.setOut(null);
                        startTime = System.currentTimeMillis();
                        IndexedCplexFixedSubblockModel solver1 = IndexedCplexFixedSubblockModel
                                .buildIntegratedSubproblemModel(instance, cplex, subblockAssignment);

                        if (solver1.solve()) {
                            Solution solution1 = solver1.getIntegratedSolution();
                            System.out.println("Indexed Model: " + solution1.briefObjectives() + " with " + (System.currentTimeMillis() - startTime) * 1. / 1000 + "seconds.");
                        }

                    }
                    try (IloCplex cplex = new IloCplex()) {
                        cplex.setOut(null);
                        startTime = System.currentTimeMillis();
                        CplexFixedSubblockModel solver2 = new CplexFixedSubblockModel(instance, cplex);
                        Solution solution2 = solver2.solveIntegratedSP(subblockAssignment);
                        System.out.println("Original Model: " + solution2.briefObjectives() + " with " + (System.currentTimeMillis() - startTime) * 1. / 1000 + "seconds.");

                    }

                }
            }
    }

    public static Map<VesselPeriod, List<Subblock>> align(
            Map<VesselPeriod, List<Subblock>> oldSubblockAssignmentList,
            Map<VesselPeriod, List<Subblock>> newSubblockAssignmentList) {
        Map<VesselPeriod, List<Subblock>> aligned = new HashMap<>();
        Objects.requireNonNull(oldSubblockAssignmentList, "old assignment");
        Objects.requireNonNull(newSubblockAssignmentList, "new assignment");
        if (!oldSubblockAssignmentList.keySet().equals(newSubblockAssignmentList.keySet()))
            throw new IllegalArgumentException("Inconsistent vessel periods: " +
                    "old=" + oldSubblockAssignmentList.keySet() +
                    ", new=" + newSubblockAssignmentList.keySet());
        for (VesselPeriod ip : oldSubblockAssignmentList.keySet()) {
            List<Subblock> oldList = oldSubblockAssignmentList.get(ip);
            List<Subblock> newList = newSubblockAssignmentList.get(ip);
            if (oldList.size() != newList.size())
                throw new IllegalArgumentException("Inconsistent numbers of assigned subblocks for "
                        + ip + ": old=" + oldList.size() + ", new=" + newList.size());
        }


        for (Map.Entry<VesselPeriod, List<Subblock>> entry : oldSubblockAssignmentList.entrySet()) {
            VesselPeriod ip = entry.getKey();
            List<Subblock> oldList = entry.getValue();
            List<Subblock> newList = newSubblockAssignmentList.getOrDefault(ip, new ArrayList<>());

            // 创建新列表，初始化为 null 或 oldList 大小
            List<Subblock> alignedList = new ArrayList<>(Collections.nCopies(oldList.size(), null));

            // 1. 建立旧列表中 Subblock 到索引的映射
            Map<Subblock, Integer> oldIndexMap = new HashMap<>();
            for (int k = 0; k < oldList.size(); k++) {
                oldIndexMap.put(oldList.get(k), k);
            }

            // 2. 先填充那些也在 oldList 出现过的 Subblock 到原来的位置
            Set<Subblock> used = new HashSet<>();
            for (int k = 0; k < oldList.size(); k++) {
                Subblock oldSubblock = oldList.get(k);
                if (newList.contains(oldSubblock)) {
                    alignedList.set(k, oldSubblock);
                    used.add(oldSubblock);
                }
            }

            // 3. 剩下的未被放置的 new Subblock 插入到空位
            int idx = 0;
            for (Subblock sb : newList) {
                if (used.contains(sb)) continue;

                while (idx < alignedList.size() && alignedList.get(idx) != null) {
                    idx++;
                }

                if (idx < alignedList.size()) {
                    alignedList.set(idx, sb);
                } else {

                    throw new IllegalArgumentException("No more space for " + sb);
//                    alignedList.add(sb);
                }
                idx++;
            }

            aligned.put(ip, alignedList);
        }

        return aligned;
    }

    public static void main(String[] args) throws IloException {
        Random random = new Random(1);
        Instance instance = InstanceGenerator.generate(12, 3, 3, 6, 6, 1);

        for (int i = 1; i <= 5; i++) {

            instance = InstanceGenerator.generate(20, 5, 5, 6, 10, i);

            Instance finalInstance = instance;
            List<Integer> subblockNumbers = instance.getVesselPeriods().stream()
                    .map(ip -> MyMathMethods.ceilDiv(ip.totalLoadContainers, finalInstance.spaceCapacity))
                    .toList();

            System.out.println("input/instance_{20-05-05}_{06-10}_0" + i + ".json");
            System.out.println("Min subblock number: " + subblockNumbers.stream().min(Integer::compareTo).orElse(-1));
            System.out.println("Max subblock number: " + subblockNumbers.stream().max(Integer::compareTo).orElse(-1));
            System.out.println("Average subblock number: " + subblockNumbers.stream().mapToInt(n -> n).average().orElse(-1));
            System.out.println();
        }

        System.exit(0);

//        MasterYardTemplateHeuristic heuristic = new MasterYardTemplateHeuristic(instance);
//        Map<VesselPeriod, Map<Subblock, Double>> costs = heuristic.getDistanceCostsByEqualStorage();
//        List<VesselPeriod> priority = heuristic.getFirstCommeFirstServedPriority();
//        Map<VesselPeriod, Set<Subblock>> oldSubblockAssignment = heuristic.assignNeededSubblocksByCost(priority, costs);
//
//        while (oldSubblockAssignment == null) {
//            Collections.shuffle(priority, random);
//            oldSubblockAssignment = heuristic.assignNeededSubblocksByCost(priority, costs);
//        }
//
//        long startTime;
//
//        try (IloCplex iterativeCplex = new IloCplex()) {
//            iterativeCplex.setParam(IloCplex.IntParam.TimeLimit, 3600);
//            iterativeCplex.setParam(IloCplex.Param.MIP.Limits.Solutions, 1);
////            iterativeCplex.setParam(IloCplex.Param.MIP.Display, 0);
////            iterativeCplex.setOut(null);
//            startTime = System.currentTimeMillis();
//            IndexedCplexFixedSubblockModel model = IndexedCplexFixedSubblockModel.buildIntegratedSubproblemModel(
//                    instance, iterativeCplex, oldSubblockAssignment);
//
//            // limit the congestion
//            model.varLoadOverload.setUB(0);
//            model.varUnloadOverload.setUB(0);
//
//
//            if (model.solve()) {
//                TemporarySolution solution = model.getIntegratedSolution();
//                System.out.println("0: Old Indexed Model: " + solution.briefObjectives() + " with " + (System.currentTimeMillis() - startTime) * 1. / 1000 + " seconds.");
//            } else {
//                System.out.println("0: No solution");
//            }
//            System.out.println("-".repeat(80));
//            for (int i = 0; i < 10; i++) {
//                // Get New Assignment List
//                Collections.shuffle(priority, random);
//                Map<VesselPeriod, Set<Subblock>> newSubblockAssignment = heuristic.assignNeededSubblocksByCost(priority, costs);
//
//                // Modify the model coefficients
//                startTime = System.currentTimeMillis();
//                System.out.println("---->");
//                model.changeSubblockAssignmentTo(newSubblockAssignment);
//                System.out.println("Modify Coefficients with " + (System.currentTimeMillis() - startTime) * 1. / 1000 + " seconds.");
//
//
//                startTime = System.currentTimeMillis();
//                if (model.solve()) {
//                    TemporarySolution solution = model.getIntegratedSolution();
//                    System.out.println((i + 1) + ": Modified and resolve: " + solution.briefObjectives() + " with " + (System.currentTimeMillis() - startTime) * 1. / 1000 + " seconds.");
//                } else {
//                    System.out.println((i + 1) + ": No solution");
//                }
//
////                try (IloCplex cplex = new IloCplex()) {
////                    cplex.setOut(null);
////                    startTime = System.currentTimeMillis();
////                    IndexedCplexFixedSubblockModel newCreatedModel = IndexedCplexFixedSubblockModel.buildIntegratedSubproblemModel(
////                            instance, cplex, model.getSubblockAssignmentArray());
////                    if (newCreatedModel.solve()) {
////                        TemporarySolution solution = newCreatedModel.getIntegratedSolution();
////                        System.out.println("Solve with a new Model: " + solution.briefObjectives() + " with " + (System.currentTimeMillis() - startTime) * 1. / 1000 + " seconds.");
////                    }
////                }
//                System.out.println("-".repeat(80));
//
//            }
//
//        }


    }


}
