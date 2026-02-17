package solver;

import entity.Instance;
import entity.Subblock;
import entity.Vessel;
import entity.VesselPeriod;
import main.InstanceGenerator;
import util.MyMathMethods;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Heuristic solver for the master yard template problem,
 * focusing on assigning subblocks to vessel periods with conflict resolution
 */
public class MasterYardTemplateHeuristic {
    private Instance instance;
    public Map<VesselPeriod, Set<VesselPeriod>> conflictPeriods;

    /**
     * Constructor initializing problem instance and conflict period mappings
     *
     * @param instance Problem instance containing vessel periods and subblocks
     */
    public MasterYardTemplateHeuristic(Instance instance) {
        this.instance = instance;
        this.conflictPeriods = identifyConflictPeriods();
    }


    public Map<VesselPeriod, Set<VesselPeriod>> assignConsistently(
            List<VesselPeriod> vesselPeriodPriority, Map<VesselPeriod, Map<Subblock, Double>> costs) {
        Map<Vessel, Map<Subblock, Double>> vesselSubblockCosts = costs.entrySet().stream()
                .collect(
                        Collectors.groupingBy(
                                entry -> instance.getVessel(entry.getKey().getVid()),
                                Collectors.flatMapping(
                                        entry -> entry.getValue().entrySet().stream(),
                                        Collectors.groupingBy(
                                                Map.Entry::getKey,
                                                Collectors.summingDouble(Map.Entry::getValue)
                                        )
                                )
                        )
                );
        return null;
    }

//    // Strategy 1: assign subblocks to vessel periods one by one.
//    public Map<VesselPeriod, Set<Subblock>> assign(
//            List<VesselPeriod> vesselPeriodPriority,
//            Map<VesselPeriod, Map<Subblock, Double>> costs
//    ) {
//        return null;
//    }


    /**
     * Assign subblocks to vessel periods using a first-fit heuristic based on a priority sequence and preferences
     * <p>
     * This method assigns subblocks to each occurrence of vessel periods in the given sequence. For each vessel period:
     * 1. Selects the first available subblock from its preference list
     * 2. Enforces conflict constraints:
     * - Same subblock cannot be assigned to the same vessel period multiple times
     * - Conflicting vessel periods cannot share the same subblock
     * - Adjacent subblocks are also blocked to prevent spatial conflicts
     *
     * @param allocationSequence List of vessel periods in allocation order (may contain duplicates for multi-requirement)
     * @param subblockPreference Ordered a preference list of subblocks for each vessel period
     * @return A map of assigned subblocks for each vessel period, or null if no valid assignment exists
     */
    public Map<VesselPeriod, Set<Subblock>> assignOneSubblockByPreference(
            List<VesselPeriod> allocationSequence,
            Map<VesselPeriod, List<Subblock>> subblockPreference) {
        Map<VesselPeriod, Set<Subblock>> banned = new HashMap<>();
        for (VesselPeriod vp : instance.getVesselPeriods())
            banned.put(vp, new HashSet<>());

        Map<VesselPeriod, Set<Subblock>> assigned = new HashMap<>();
        for (VesselPeriod vp : instance.getVesselPeriods())
            assigned.put(vp, new HashSet<>());

        Map<Subblock, Set<Subblock>> neighborSubblock = instance.getNeighborSubblock();

        for (VesselPeriod vp : allocationSequence) {
            boolean find = false;
            List<Subblock> preferredK = subblockPreference.get(vp);
            Set<Subblock> bannedSubblocks = banned.get(vp);
            for (Subblock k : preferredK) {
                if (!bannedSubblocks.contains(k)) {
                    assigned.get(vp).add(k);

                    // the same subblock cannot be assigned to the same vessel period twice
                    banned.get(vp).add(k);
                    // the same subblock cannot be assigned to different vessel periods at the same time
                    for (VesselPeriod that : conflictPeriods.get(vp)) {
                        banned.get(that).add(k);
                    }
//                    for (Subblock k2 : neighborSubblock.get(k)) {
//                        banned.get(vp).add(k2);
//                    }

                    find = true;
                    break;
                }
            }

            if (!find)
                return null;
        }
        return assigned;
    }

    public Map<VesselPeriod, Set<Subblock>> assignOneSubblockByCost(
            List<VesselPeriod> allocationSequence,
            Map<VesselPeriod, Map<Subblock, Double>> costs) {
        Map<VesselPeriod, List<Subblock>> preferences = identifyPreference(costs);
        return assignOneSubblockByPreference(allocationSequence, preferences);
    }

    public Map<VesselPeriod, Set<Subblock>> assignNeededSubblocksByPreference(
            List<VesselPeriod> vesselPeriodPriority,
            Map<VesselPeriod, List<Subblock>> subblockPreference) {
        List<VesselPeriod> allocationSequence = identifyAllocationSequence(vesselPeriodPriority);
        return assignOneSubblockByPreference(allocationSequence, subblockPreference);
    }

    public Map<VesselPeriod, Set<Subblock>> assignNeededSubblocksByCost(
            List<VesselPeriod> vesselPeriodPriority,
            Map<VesselPeriod, Map<Subblock, Double>> costs) {
        List<VesselPeriod> allocationSequence = identifyAllocationSequence(vesselPeriodPriority);
        Map<VesselPeriod, List<Subblock>> preferences = identifyPreference(costs);
        return assignOneSubblockByPreference(allocationSequence, preferences);
    }

    public Map<VesselPeriod, List<Subblock>> identifyPreference(Map<VesselPeriod, Map<Subblock, Double>> costs) {
        return costs.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> entry.getValue().entrySet().stream()
                        .sorted(Comparator.comparingDouble(Map.Entry::getValue))
                        .map(Map.Entry::getKey).collect(Collectors.toList())
        ));
    }

    private List<VesselPeriod> identifyAllocationSequence(List<VesselPeriod> priority) {
        ArrayList<VesselPeriod> allocationSequence = new ArrayList<>();
        for (VesselPeriod vp : priority) {
            int copies = MyMathMethods.ceilDiv(vp.totalLoadContainers, instance.spaceCapacity);
            allocationSequence.addAll(Collections.nCopies(copies, vp));
        }
        return allocationSequence;
    }

    /**
     * Generate FirstComeFirstServed-based period-subblock assignments using nearest subblock preference
     *
     * @return Assignment map or null if no valid assignment exists
     */
    public Map<VesselPeriod, Set<Subblock>> assignByFirstComeFirstServed() {

        List<VesselPeriod> sortedUniqueVesselPeriodsByStartTime = getFirstCommeFirstServedPriority();

        List<VesselPeriod> allocationSequence = identifyAllocationSequence(sortedUniqueVesselPeriodsByStartTime);

        Map<VesselPeriod, Map<Subblock, Double>> costs = getDistanceCostsByEqualStorage();

//        HashMap<VesselPeriod, List<Subblock>> nearestSubblocks = getLoadingBasedPreference();

        return assignOneSubblockByCost(allocationSequence, costs);
    }

    public HashMap<VesselPeriod, List<Subblock>> getLoadingBasedPreference() {
        HashMap<VesselPeriod, List<Subblock>> nearestSubblocks = new HashMap<>();
        for (VesselPeriod vp : instance.getVesselPeriods()) {
            nearestSubblocks.put(vp, instance.getSubblocks().stream()
                    .sorted(Comparator.comparingDouble(subblock -> instance.getDistanceFromSubblock(vp, subblock)))
                    .collect(Collectors.toList()));
        }
        return nearestSubblocks;
    }

    public List<VesselPeriod> getFirstCommeFirstServedPriority() {
        PriorityQueue<VesselPeriod> pq = new PriorityQueue<>(
                Comparator.comparingInt(o1 ->
                        o1.getPeriodInterval().getStart())
        );
        pq.addAll(instance.getVesselPeriods());
        return new LinkedList<>(pq);
    }

    public List<VesselPeriod> getIndexBasedPriority() {
        return new LinkedList<>(instance.getVesselPeriods());
    }

    private Map<VesselPeriod, Set<VesselPeriod>> identifyConflictPeriods() {
        Map<VesselPeriod, Set<VesselPeriod>> conflictPeriods = new HashMap<>();
        for (VesselPeriod vp1 : instance.getVesselPeriods()) {
            HashSet<VesselPeriod> _conflictPeriods = new HashSet<>();
            for (VesselPeriod vp2 : instance.getVesselPeriods()) {
                if (vp1.equals(vp2) || instance.getVesselOf(vp1).equals(instance.getVesselOf(vp2)))
                    continue;
                if (vp1.getPeriodInterval().isIntersecting(vp2.getPeriodInterval(), instance.horizon))
                    _conflictPeriods.add(vp2);
            }
            conflictPeriods.put(vp1, _conflictPeriods);
        }
        return conflictPeriods;
    }

    public Map<VesselPeriod, Map<Subblock, Double>> getDistanceCostsByEqualStorage() {
        Map<VesselPeriod, Map<Subblock, Double>> costs = new HashMap<>(instance.getNumVesselPeriods());
        for (VesselPeriod ip : instance.getVesselPeriods()) {
            int numSubblocks = MyMathMethods.ceilDiv(ip.totalLoadContainers, instance.spaceCapacity);
            Map<Subblock, Double> map = new HashMap<>(instance.getNumSubblocks());
            for (Subblock subblock : instance.getSubblocks()) {
                double routeCost = 0;
                for (VesselPeriod jq : instance.getSourceVesselPeriodsOf(ip)) {
                    routeCost += instance.getTransshipmentTo(jq, ip) * (instance.getDistanceToSubblock(jq, subblock)
                            + instance.getDistanceFromSubblock(ip, subblock));
                }
                map.put(subblock, routeCost / numSubblocks * instance.etaRoute);
            }
            costs.put(ip, map);
        }
        return costs;
    }

    public static void main(String[] args) {
        Instance instance = InstanceGenerator.generate(12, 3, 3, 4, 6, 1);
        MasterYardTemplateHeuristic heuristic = new MasterYardTemplateHeuristic(instance);
        Map<VesselPeriod, Map<Subblock, Double>> costs = heuristic.getDistanceCostsByEqualStorage();
        List<VesselPeriod> priority = heuristic.getFirstCommeFirstServedPriority();
        Map<VesselPeriod, Set<Subblock>> assignment = heuristic.assignNeededSubblocksByCost(priority, costs);
        if (assignment == null) {
            System.out.println("No solution: " + instance);
        }
    }
}
