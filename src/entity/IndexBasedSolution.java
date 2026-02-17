package entity;

import util.MyMathMethods;

import java.util.*;
import java.util.stream.IntStream;

public class IndexBasedSolution {
    public final Instance instance;
    public boolean allowModification = false;

    public Map<VesselPeriod, Integer> vesselPeriodSubblockNumber;

    // Decisions Variables
    private Map<VesselPeriod, Subblock[]> subblockAssignments; // vesselPeriodSubblockAssignment
    private Map<VesselPeriod, Schedule[]> loadingTimeAssignments; // vesselPeriodLoadingTimeAssignment
    private Map<VesselPeriod, List<Map<VesselPeriod, Schedule>>> sourceUnloadingTimeAssignments; // vesselPeriodSourceUnloadingTimeAssignment

    private Map<VesselPeriod, Integer> auxiliaryEarliness, auxiliaryTardiness;
    private Map<Integer, Map<Integer, Integer>> auxiliaryLoadRoadFlows, auxiliaryUnloadRoadFlows; // R->T->flows

    private int unloadOverload;
    private int loadOverload;


    public Double objRoute, objTime, objCongestion, objAll;


    public IndexBasedSolution(Instance instance) {
        this.instance = instance;
        this.vesselPeriodSubblockNumber = new HashMap<>();
        for (VesselPeriod ip : instance.getVesselPeriods()) {
            vesselPeriodSubblockNumber.put(ip, MyMathMethods.ceilDiv(ip.getTotalLoadContainers(), instance.spaceCapacity));
        }

        this.subblockAssignments = new HashMap<>();
        for (VesselPeriod ip : instance.getVesselPeriods()) {
            subblockAssignments.put(ip, new Subblock[vesselPeriodSubblockNumber.get(ip)]);
        }

        this.loadingTimeAssignments = new HashMap<>();
        for (VesselPeriod ip : instance.getVesselPeriods()) {
            loadingTimeAssignments.put(ip, new Schedule[vesselPeriodSubblockNumber.get(ip)]);
        }
        this.sourceUnloadingTimeAssignments = new HashMap<>();
        for (VesselPeriod ip : instance.getVesselPeriods()) {
            sourceUnloadingTimeAssignments.put(ip, new ArrayList<>(vesselPeriodSubblockNumber.get(ip)));
            for (int i = 0; i < vesselPeriodSubblockNumber.get(ip); i++) {
                sourceUnloadingTimeAssignments.get(ip).add(new HashMap<>());
            }
        }
    }

    public boolean isSubblockAssignmentFeasible() {
        for (VesselPeriod ip : instance.getVesselPeriods()) {
            Subblock[] subblocks = subblockAssignments.get(ip);
            for (Subblock subblock : subblocks) {
                if (subblock == null)
                    return false;
            }
        }
        return true;
    }

    public boolean isOperationScheduleFeasible() {
        for (VesselPeriod ip : instance.getVesselPeriods()) {
            List<Map<VesselPeriod, Schedule>> unloadPlans = sourceUnloadingTimeAssignments.get(ip);
            Schedule[] loadPlans = loadingTimeAssignments.get(ip);
            if (unloadPlans.size() != loadPlans.length)
                throw new IllegalArgumentException("Inconsistent number of plans for " + ip);
            for (int m = 0; m < unloadPlans.size(); m++) {

                Schedule mthLoad = loadPlans[m];
                if (mthLoad == null)
                    return false;

                Map<VesselPeriod, Schedule> mthUnload = unloadPlans.get(m);

                int total = 0;
                for (Schedule unload : mthUnload.values()) {
                    if (unload == null)
                        return false;

                    total += unload.number;
                }

                if (total != mthLoad.number)
                    return false;

            }
        }

        return true;
    }


    public int getExpectedSubblockNumber(VesselPeriod ip) {
        return vesselPeriodSubblockNumber.get(ip);
    }

    public void setMthSubblock(VesselPeriod ip, int m, Subblock subblock) {
        Subblock[] subblocks = subblockAssignments.get(ip);
        if (subblocks == null) {
            System.err.println("Warning: The subblock assignment is null when set " + subblock + " to [" + ip + "," + m + "].");
            subblocks = new Subblock[vesselPeriodSubblockNumber.get(ip)];
            subblockAssignments.put(ip, subblocks);
        }
        if (subblocks[m] != null && !allowModification)
            throw new IllegalArgumentException("The subblock assignment has already been set.");
        else
            subblocks[m] = subblock;


    }

    public void setSubblocks(VesselPeriod ip, List<Subblock> subblocks) {
        if (subblocks.size() != vesselPeriodSubblockNumber.get(ip))
            throw new IllegalArgumentException("The number of subblocks in the assignment is not equal to the number of subblocks in the instance.");
        for (int i = 0; i < subblocks.size(); i++)
            setMthSubblock(ip, i, subblocks.get(i));
    }

    public void setMthLoadingPlan(VesselPeriod ip, int m, int loadingTime, int number) {
        Schedule[] loadingTimes = loadingTimeAssignments.get(ip);
        if (loadingTimes == null) {
            System.err.println("Warning: The loading time assignment is null when set " + loadingTime + " to [" + ip + "," + m + "].");
            loadingTimes = new Schedule[vesselPeriodSubblockNumber.get(ip)];
            loadingTimeAssignments.put(ip, loadingTimes);
        }
        if (loadingTimes[m] != null && !allowModification)
            throw new IllegalArgumentException("The loading time assignment has already been set.");
        else
            loadingTimes[m] = new Schedule(loadingTime, number);
    }

    public void setMthSourceUnloadingTime(VesselPeriod ip, int m, VesselPeriod source, int unloadingTime, int number) {
        List<Map<VesselPeriod, Schedule>> sourceUnloadingTimes = sourceUnloadingTimeAssignments.get(ip);
        if (sourceUnloadingTimes == null) {
            System.err.println("Warning: The source unloading time assignment is null when set " + unloadingTime + " to [" + ip + "," + m + "].");
            sourceUnloadingTimes = new ArrayList<>();
            for (int i = 0; i < vesselPeriodSubblockNumber.get(ip); i++) {
                sourceUnloadingTimes.add(new HashMap<>());
            }
            sourceUnloadingTimeAssignments.put(ip, sourceUnloadingTimes);
        }
        if (sourceUnloadingTimes.size() != vesselPeriodSubblockNumber.get(ip))
            throw new IllegalArgumentException("The number of source unloading times in the assignment is not equal to the number of subblocks in the instance.");
        Map<VesselPeriod, Schedule> mthSourceUnloadingTimes = sourceUnloadingTimes.get(m);
        if (mthSourceUnloadingTimes == null) {
            System.err.println("Warning: The mth source unloading time assignment is null when set " + unloadingTime + " to [" + ip + "," + m + "].");
            mthSourceUnloadingTimes = new HashMap<>();
            sourceUnloadingTimes.set(m, mthSourceUnloadingTimes);
        }
        if (mthSourceUnloadingTimes.containsKey(source) && !allowModification)
            throw new IllegalArgumentException("The source unloading time assignment has already been set.");
        mthSourceUnloadingTimes.put(source, new Schedule(unloadingTime, number));
    }


    public void build() {
        if (isOperationScheduleFeasible()) {
            this.auxiliaryEarliness = new LinkedHashMap<>();
            this.auxiliaryTardiness = new LinkedHashMap<>();
            for (VesselPeriod ip : instance.getVesselPeriods()) {
                auxiliaryEarliness.put(ip, 0);
                auxiliaryTardiness.put(ip, 0);
            }

            this.forEachUnloadingTimes((ip, m, jq, schedule) -> {
                changeAdvanceDelay(jq, schedule.time);

            });
            this.forEachLoadingTimes((ip, k, schedule) -> {
                changeAdvanceDelay(ip, schedule.time);
            });

            this.objTime = 0.0;
            for (VesselPeriod ip : instance.getVesselPeriods()) {
                objTime += ip.getEarlinessCost() * auxiliaryEarliness.get(ip);
                objTime += ip.getTardinessCost() * auxiliaryTardiness.get(ip);
            }


        }
        if (isSubblockAssignmentFeasible()) {
            ;
        }

        if (isSubblockAssignmentFeasible() && isOperationScheduleFeasible()) {
            loadOverload = 0;
            unloadOverload = 0;
            this.auxiliaryUnloadRoadFlows = new LinkedHashMap<>();
            this.auxiliaryLoadRoadFlows = new LinkedHashMap<>();
            forEachUnloadingTimes((ip, m, jq, schedule) -> {
                Subblock k = subblockAssignments.get(ip)[m];
                for (int l : instance.getRouteToSubblock(jq, k)) {
                    int f = auxiliaryUnloadRoadFlows.computeIfAbsent(l, key -> new LinkedHashMap<>())
                            .merge(schedule.time, 1, Integer::sum);
                    if (f > instance.maxUnloadFlows + unloadOverload)
                        unloadOverload = f - instance.maxUnloadFlows;
                }
            });
            forEachLoadingTimes((ip, m, schedule) -> {
                Subblock k = subblockAssignments.get(ip)[m];
                for (int l : instance.getRouteFromSubblock(ip, k)) {
                    int f = auxiliaryLoadRoadFlows.computeIfAbsent(l, key -> new LinkedHashMap<>())
                            .merge(schedule.time, 1, Integer::sum);
                    if (f > instance.maxLoadFlows + loadOverload)
                        loadOverload = f - instance.maxLoadFlows;
                }
            });

            objCongestion = instance.etaCongestion * (loadOverload + unloadOverload);

            objRoute = 0.0;
            forEachUnloadingTimes((ip, m, jq, schedule) -> {
                Subblock k = subblockAssignments.get(ip)[m];
                int w = schedule.number;
                objRoute += w * (instance.getDistanceToSubblock(jq, k)
                        + instance.getDistanceFromSubblock(ip, k)) * instance.etaRoute;
            });

            objAll = objRoute + objTime + objCongestion;
        }

    }

    private void changeAdvanceDelay(VesselPeriod ip, int time) {
        int relativeTime = ip.getRelativeTimeWithinPeriod(time, instance.horizon);
        int expA = ip.getRelativeExpectedIntervalStart();
        int expB = ip.getRelativeExpectedIntervalEnd();
        if (relativeTime < expA) {
            int advance = expA - relativeTime;
            if (!auxiliaryEarliness.containsKey(ip) || advance > auxiliaryEarliness.get(ip))
                auxiliaryEarliness.put(ip, advance);
        }
        if (relativeTime + 1 > expB) {
            int delay = relativeTime + 1 - expB;
            if (!auxiliaryTardiness.containsKey(ip) || delay > auxiliaryTardiness.get(ip))
                auxiliaryTardiness.put(ip, delay);
        }
    }

    @FunctionalInterface
    public interface TriConsumer<T, U, V> {
        void accept(T t, U u, V v);
    }

    @FunctionalInterface
    public interface QuadConsumer<T, U, V, W> {
        void accept(T t, U u, V v, W w);
    }


    /**
     * 遍历 sourceUnloadingTimeAssignments 的四层结构
     *
     * @param consumer 接收四个参数 (ip, m, jq, schedule)
     */
    public void forEachUnloadingTimes(QuadConsumer<VesselPeriod, Integer, VesselPeriod, Schedule> consumer) {
        if (sourceUnloadingTimeAssignments == null || consumer == null) {
            System.out.println("Warning: The source unloading time assignment is null when forEachUnloadingTimes. Unexpected behaviors.");
            return;
        }

        sourceUnloadingTimeAssignments.forEach((ip, ipList) -> {
            if (ipList != null) {
                IntStream.range(0, ipList.size())
                        .forEach(m -> {
                            Map<VesselPeriod, Schedule> mMap = ipList.get(m);
                            if (mMap != null) {
                                mMap.forEach((jq, schedule) -> {
                                    consumer.accept(ip, m, jq, schedule);
                                });
                            }
                        });
            }
        });
    }

    /**
     * 遍历 loadingTimeAssignments 的三层结构
     *
     * @param consumer 接收三个参数 (ip, m, schedule)
     */
    public void forEachLoadingTimes(TriConsumer<VesselPeriod, Integer, Schedule> consumer) {
        if (loadingTimeAssignments == null || consumer == null) {
            System.out.println("Warning: The loading time assignment is null when forEachLoadingTimes. Unexpected behaviors.");
            return;
        }

        loadingTimeAssignments.forEach((ip, ipArray) -> {
            if (ipArray != null) {
                IntStream.range(0, ipArray.length)
                        .forEach(m -> {
                            Schedule schedule = ipArray[m];
                            consumer.accept(ip, m, schedule);
                        });
            }
        });
    }

    public void forEachSubblockAssignments(TriConsumer<VesselPeriod, Integer, Subblock> consumer) {
        if (subblockAssignments == null || consumer == null) {
            System.out.println("Warning: The subblock assignment is null when forEachSubblockAssignments. Unexpected behaviors.");
            return;
        }
        subblockAssignments.forEach((ip, ipArray) -> {
            if (ipArray != null) {
                IntStream.range(0, ipArray.length)
                        .forEach(m -> {
                            Subblock subblock = ipArray[m];
                            consumer.accept(ip, m, subblock);
                        });
            }
        });
    }

    public static IndexBasedSolution merge(IndexBasedSolution subblockAssignmentSolution,
                                           IndexBasedSolution operationScheduleSolution) {
        if (subblockAssignmentSolution == null || operationScheduleSolution == null)
            throw new IllegalArgumentException("The subblock assignment solution or operation schedule solution is null.");

        if (!subblockAssignmentSolution.isSubblockAssignmentFeasible())
            throw new IllegalArgumentException("The subblock assignment solution is infeasible.");
        if (!operationScheduleSolution.isOperationScheduleFeasible())
            throw new IllegalArgumentException("The operation schedule solution is infeasible.");

        if (!subblockAssignmentSolution.instance.equals(operationScheduleSolution.instance))
            throw new IllegalArgumentException("The instances are not equal.");


        IndexBasedSolution result = new IndexBasedSolution(subblockAssignmentSolution.instance);

        subblockAssignmentSolution.forEachSubblockAssignments((ip, m, subblock) -> {
            result.setMthSubblock(ip, m, subblock);
        });
        operationScheduleSolution.forEachUnloadingTimes((ip, m, jq, schedule) -> {
            result.setMthSourceUnloadingTime(ip, m, jq, schedule.time, schedule.number);
        });
        operationScheduleSolution.forEachLoadingTimes((ip, m, schedule) -> {
            result.setMthLoadingPlan(ip, m, schedule.time, schedule.number);
        });

        result.build();
        return result;
    }


    public static class Schedule {
        public Integer time, number;

        public Schedule(Integer time, Integer number) {
            this.time = time;
            this.number = number;
        }
    }

    public String briefObjectives() {
        return String.format("obj=%.10f [route=%.2f, time=%.2f, congestion=%.2f]", objAll, objRoute, objTime, objCongestion);
    }


    public Map<Integer, SubblockChange> suggestNewSubblock(Subblock[] old, Set<Subblock> target) {
        if (old == null || target == null)
            throw new IllegalArgumentException("The old subblocks or the target subblocks are null.");
        if (old.length != target.size())
            throw new IllegalArgumentException("The old subblocks and the target subblocks are not equal.");
        if (old.length == 0)
            return new HashMap<>();
        assert (new HashSet<>(Arrays.asList(old)).size() == old.length);

        Subblock[] toChange = new Subblock[old.length];
        Set<Subblock> remain = new HashSet<>(target);
        for (int i = 0; i < old.length; i++) {
            Subblock k = old[i];
            if (target.contains(k)) {
                toChange[i] = null;
                remain.remove(k);
            } else {
                toChange[i] = k;
            }
        }

        Map<Integer, SubblockChange> changes = new HashMap<>();
        LinkedList<Subblock> remainList = new LinkedList<>(remain);
        for (int i = 0; i < toChange.length; i++) {
            if (toChange[i] != null) {
                changes.put(i, new SubblockChange(toChange[i], remainList.pop()));
            }
        }

        assert (remainList.isEmpty());

        return changes;
    }

    public Solution toSolution() {
        Solution solution = new Solution(instance);
        for (VesselPeriod ip : instance.getVesselPeriods()) {
            for (Subblock k : subblockAssignments.getOrDefault(ip, new Subblock[0])) {
                solution.setSubBlock(ip, k);
            }
        }

        for (VesselPeriod ip : instance.getVesselPeriods())
            for (int m = 0; m < subblockAssignments.get(ip).length; m++) {
                Subblock k = subblockAssignments.get(ip)[m];
                Schedule load = loadingTimeAssignments.get(ip)[m];
                solution.setLoadSchedule(ip, k, load.time, load.number);
                Map<VesselPeriod, Schedule> unloads = sourceUnloadingTimeAssignments.get(ip).get(m);
                for (Map.Entry<VesselPeriod, Schedule> entry : unloads.entrySet()) {
                    VesselPeriod jq = entry.getKey();
                    Schedule unload = entry.getValue();
                    solution.setUnloadSchedule(jq, ip, k, unload.time, unload.number);
                }
            }

        solution.calculateObjectives();
        return solution;
    }


}
