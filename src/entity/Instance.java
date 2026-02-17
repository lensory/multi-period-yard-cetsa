package entity;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dto.*;
import util.MyMathMethods;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;


public class Instance {
    public static final int DEFAULT_SUBBLOCK_SPACE_CAPACITY = 240;
    public static final int DEFAULT_SUBBLOCK_NUMBER_FOR_EACH_BLOCK = 5;
    public static final int DEFAULT_BLOCK_NUMBER_FOR_EACH_SUPER_BLOCK = 2;
    public static final double DEFAULT_PASSING_LANE_LENGTH = 30.0;// The passing lane is vertical.
    public static final double DEFAULT_SUBBLOCK_WIDTH = 50.0;

    public static final String DEFAULT_NAME_PATTERN = "instance_{%02d-%02d-%02d}_{%02d-%02d}_%02d";

//    public static final int DEFAULT_TIME_UNIT_PER_DAY = 3;
//    public static final int DEFAULT_HOUR_PER_DAY = 24;
//    public static final int DEFAULT_HOUR_PER_TIME_UNIT = DEFAULT_HOUR_PER_DAY / DEFAULT_TIME_UNIT_PER_DAY;


    public double etaCongestion;
    public double etaRoute;
    //    public double etaTime = 1;
    public int spaceCapacity;
    public int maxUnloadFlows;
    public int maxLoadFlows;

    public String name;

    public int horizon; // index from 0 to horizon-1
    public int extension;
    private final TreeMap<Integer, Vessel> vessels;
    private final TreeMap<Integer, VesselPeriod> vesselPeriods;

    // yard configuration
    public int rows;
    public int cols;
    public int yardHorizontalLanes;
    public int yardVerticalLanes;
    public int yardHandlingPositions;
    public int roads;

    private final TreeMap<Integer, Subblock> subblocks;

    private Map<Subblock, Set<Subblock>> neighborSubblock;

    private Map<VesselPeriod, Map<Subblock, Double>> distanceToSubblock;

    private Map<VesselPeriod, Map<Subblock, Double>> distanceFromSubblock;


    private Map<VesselPeriod, Map<Subblock, ArrayList<Integer>>> routeToSubblocks;
    private Map<VesselPeriod, Map<Subblock, ArrayList<Integer>>> routeFromSubblocks;

    private Map<VesselPeriod, Map<VesselPeriod, Integer>> transshipTo;

    private Map<VesselPeriod, Map<VesselPeriod, Integer>> transshipFrom;


    public Instance() {
        this.vessels = new TreeMap<>();
        this.vesselPeriods = new TreeMap<>();
        this.subblocks = new TreeMap<>();

        this.distanceToSubblock = new LinkedHashMap<>();
        this.distanceFromSubblock = new LinkedHashMap<>();
        this.routeToSubblocks = new LinkedHashMap<>();
        this.routeFromSubblocks = new LinkedHashMap<>();
        this.transshipTo = new LinkedHashMap<>();
        this.transshipFrom = new LinkedHashMap<>();
    }

    public Map<VesselPeriod, Map<Subblock, Double>> getDistanceToSubblock() {
        return distanceToSubblock.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> new LinkedHashMap<>(entry.getValue())
                ));
    }

    public Map<VesselPeriod, Map<Subblock, Double>> getDistanceFromSubblock() {
        return distanceFromSubblock.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> new LinkedHashMap<>(entry.getValue())
                ));
    }

    public Map<VesselPeriod, Map<Subblock, ArrayList<Integer>>> getRouteToSubblocks() {
        // deep copy
        return routeToSubblocks.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().entrySet().stream()
                                .collect(Collectors.toMap(
                                        Map.Entry::getKey,
                                        e -> new ArrayList<>(e.getValue())
                                ))
                ));
    }

    public Map<VesselPeriod, Map<Subblock, ArrayList<Integer>>> getRouteFromSubblocks() {
        return routeFromSubblocks.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().entrySet().stream()
                                .collect(Collectors.toMap(
                                        Map.Entry::getKey,
                                        e -> new ArrayList<>(e.getValue())
                                ))
                ));
    }

    public Map<VesselPeriod, Map<VesselPeriod, Integer>> getTransshipTo() {
        return transshipTo.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> new LinkedHashMap<>(entry.getValue())
                ));
    }

    public Map<VesselPeriod, Map<VesselPeriod, Integer>> getTransshipFrom() {
        return transshipFrom.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> new LinkedHashMap<>(entry.getValue())
                ));
    }

    public Map<Subblock, Set<Subblock>> getNeighborSubblock() {
        return neighborSubblock.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> new LinkedHashSet<>(entry.getValue())
                ));
//        Map<Subblock, Set<Subblock>> unmodifiableView = new LinkedHashMap<>();
//        neighborSubblock.forEach((key, value) ->
//                unmodifiableView.put(key, Collections.unmodifiableSet(value))
//        );
//        return Collections.unmodifiableMap(unmodifiableView);
    }

    public void setDistanceToSubblock(VesselPeriod ip, Subblock subblock, double distance) {
        if (distance < 0)
            throw new IllegalArgumentException("Invalid distance.");
        Map<Subblock, Double> map = distanceToSubblock.computeIfAbsent(ip, k -> new LinkedHashMap<>());
        if (map.containsKey(subblock))
            throw new IllegalArgumentException("Distance already set.");
        map.put(subblock, distance);
    }

    public double getDistanceToSubblock(VesselPeriod ip, Subblock subblock) {
        if (!this.distanceToSubblock.containsKey(ip))
            throw new IllegalArgumentException("Distance not set.");
        Map<Subblock, Double> map = distanceToSubblock.get(ip);
        if (!map.containsKey(subblock))
            throw new IllegalArgumentException("Distance not set.");
        return map.get(subblock);
    }


    public void setDistanceFromSubblock(VesselPeriod ip, Subblock subblock, double distance) {
        if (distance < 0)
            throw new IllegalArgumentException("Invalid distance.");
        Map<Subblock, Double> map = distanceFromSubblock.computeIfAbsent(ip, k -> new LinkedHashMap<>());
        if (map.containsKey(subblock))
            throw new IllegalArgumentException("Distance already set.");
        map.put(subblock, distance);
    }

    public double getDistanceFromSubblock(VesselPeriod ip, Subblock subblock) {
        if (!this.distanceFromSubblock.containsKey(ip))
            throw new IllegalArgumentException("Distance not set.");
        Map<Subblock, Double> map = distanceFromSubblock.get(ip);
        if (!map.containsKey(subblock))
            throw new IllegalArgumentException("Distance not set.");
        return map.get(subblock);
    }

    public void setRouteToSubblock(VesselPeriod ip, Subblock subblock, List<Integer> route) {
        if (route == null)
            throw new IllegalArgumentException("Invalid route.");
        Map<Subblock, ArrayList<Integer>> map = routeToSubblocks.computeIfAbsent(ip, k -> new LinkedHashMap<>());
        if (map.containsKey(subblock))
            throw new IllegalArgumentException("Route already set.");
        map.put(subblock, new ArrayList<>(route));
    }

    public List<Integer> getRouteToSubblock(VesselPeriod ip, Subblock subblock) {
        if (!this.routeToSubblocks.containsKey(ip))
            throw new IllegalArgumentException("Route not set.");
        Map<Subblock, ArrayList<Integer>> map = routeToSubblocks.get(ip);
        if (!map.containsKey(subblock))
            throw new IllegalArgumentException("Route not set.");
        return Collections.unmodifiableList(map.get(subblock));
    }

    public void setRouteFromSubblock(VesselPeriod ip, Subblock subblock, List<Integer> route) {
        if (route == null)
            throw new IllegalArgumentException("Invalid route.");
        Map<Subblock, ArrayList<Integer>> map = routeFromSubblocks.computeIfAbsent(ip, k -> new LinkedHashMap<>());
        if (map.containsKey(subblock))
            throw new IllegalArgumentException("Route already set.");
        map.put(subblock, new ArrayList<>(route));
    }

    public List<Integer> getRouteFromSubblock(VesselPeriod ip, Subblock subblock) {
        if (!this.routeFromSubblocks.containsKey(ip))
            throw new IllegalArgumentException("Route not set.");
        Map<Subblock, ArrayList<Integer>> map = routeFromSubblocks.get(ip);
        if (!map.containsKey(subblock))
            throw new IllegalArgumentException("Route not set.");
        return Collections.unmodifiableList(map.get(subblock));
    }

    public void addTransshipment(VesselPeriod from, VesselPeriod to, int containers) {
        if (containers <= 0)
            throw new IllegalArgumentException("Invalid container number.");
        if (from == null || to == null)
            throw new IllegalArgumentException("VesselPeriod cannot be null");


        Map<VesselPeriod, Integer> fromMap = transshipTo.computeIfAbsent(from, k -> new LinkedHashMap<>());
        Map<VesselPeriod, Integer> toMap = transshipFrom.computeIfAbsent(to, k -> new LinkedHashMap<>());
        if (fromMap.containsKey(to) && toMap.containsKey(from)) {
            int existing = fromMap.get(to);
            if (toMap.get(from) != existing)
                throw new IllegalArgumentException("Inconsistency: Transshipment number from " + from
                        + " -> " + to + " does not match " + to + " -> " + from);
            fromMap.put(to, existing + containers);
            toMap.put(from, existing + containers);

            from.totalUnloadContainers += containers;
            to.totalLoadContainers += containers;
        } else if (!fromMap.containsKey(to) && !toMap.containsKey(from)) {
            fromMap.put(to, containers);
            toMap.put(from, containers);

            from.totalUnloadContainers += containers;
            from.totalUnloadFlow += 1;
            to.totalLoadContainers += containers;
            to.totalLoadFlow += 1;

        } else {
            throw new IllegalArgumentException("Inconsistency: " + from + " -> " + to);
        }
    }


    public List<VesselPeriod> getDestinationVesselPeriodsOf(VesselPeriod from) {
        return new LinkedList<>(this.transshipTo.getOrDefault(from, Collections.emptyMap()).keySet());
    }

    public List<VesselPeriod> getSourceVesselPeriodsOf(VesselPeriod to) {
        return new LinkedList<>(this.transshipFrom.getOrDefault(to, Collections.emptyMap()).keySet());
    }

    public boolean hasTransshipment(VesselPeriod from, VesselPeriod to) {
        return this.transshipTo.containsKey(from) &&
                this.transshipTo.get(from).containsKey(to);
    }

    public int getTransshipmentTo(VesselPeriod from, VesselPeriod to) {
        return this.transshipTo.getOrDefault(from, Collections.emptyMap())
                .getOrDefault(to, 0);
    }

    public void forEachTransshipment(VesselPeriod from, BiConsumer<VesselPeriod, Integer> action) {
        this.transshipTo.getOrDefault(from, Collections.emptyMap()).forEach(action);
    }


    public void setYardConfiguration(int rows, int cols) {
        this.rows = rows;
        this.cols = cols;
        this.yardHorizontalLanes = rows / DEFAULT_BLOCK_NUMBER_FOR_EACH_SUPER_BLOCK + 1;
        this.yardVerticalLanes = cols + 1;
        this.yardHandlingPositions = this.yardHorizontalLanes * cols * DEFAULT_SUBBLOCK_NUMBER_FOR_EACH_BLOCK;
        this.roads = this.yardVerticalLanes * rows / DEFAULT_BLOCK_NUMBER_FOR_EACH_SUPER_BLOCK;

    }

    public void buildNeighborSubblock() {
        neighborSubblock = subblocks.values().stream()
                .collect(Collectors.toMap(Function.identity(),
                        k1 -> subblocks.values().stream()
                                .filter(k2 -> !k1.equals(k2) &&
                                        (k1.isNeighborInSameBlock(k2) || k1.isNeighborAcrossLane(k2)))
                                .collect(Collectors.toCollection(LinkedHashSet::new))
                ));
    }

    public Vessel getVessel(int vId) {
        return this.vessels.get(vId);
    }

    public Vessel getVesselOf(VesselPeriod vp) {
        int vId = vp.getVid();
        return this.vessels.get(vId);
    }

    public void addVessel(Vessel i) {
        if (this.vessels.containsKey(i.getVid()))
            throw new RuntimeException("Duplicate vessel id: " + i.getVid());
        this.vessels.put(i.getVid(), i);
    }

    public List<Vessel> getVessels() {
        return new LinkedList<>(this.vessels.values());
    }

    public int getNumVessels() {
        return this.vessels.size();
    }

    public VesselPeriod getVesselPeriod(int vpId) {
        return this.vesselPeriods.get(vpId);
    }

    public void addVesselPeriod(VesselPeriod ip) {
        if (this.vesselPeriods.containsKey(ip.getVpId()))
            throw new RuntimeException("Duplicate vessel period id: " + ip.getVpId());
        this.vesselPeriods.put(ip.getVpId(), ip);

    }


    public List<VesselPeriod> getVesselPeriods() {
        return new LinkedList<>(this.vesselPeriods.values());
    }

    public int getNumVesselPeriods() {
        return this.vesselPeriods.size();
    }

    public Subblock getSubblock(int subblockId) {
        return this.subblocks.get(subblockId);
    }

    public void addSubblock(Subblock subblock) {
        if (this.subblocks.containsKey(subblock.getId()))
            throw new RuntimeException("Duplicate subblock id: " + subblock.getId());
        this.subblocks.put(subblock.getId(), subblock);
    }

    public List<Subblock> getSubblocks() {
        return new LinkedList<>(this.subblocks.values());
    }

    public int getNumSubblocks() {
        return this.subblocks.size();
    }


    public int getExpectedSubblockNumber(VesselPeriod ip) {
        return MyMathMethods.ceilDiv(ip.getTotalLoadContainers(), this.spaceCapacity);
    }

    public VesselPeriod getVesselPeriodAtOriginalTimeStep(Vessel i, int t) {
        int tip = getExtendedTimeStep(i, t);
        int pId = (tip - i.getIdleTimeSteps()) / i.getLengthOfPeriod();
        return i.getPeriod(pId);
    }

    public int getOriginalTimeStep(VesselPeriod ip, int tip) {
        if (tip < horizon)
            return tip;
        else
            return tip - horizon;
    }

    public int getExtendedTimeStep(Vessel i, int t) {
        if (t < i.getIdleTimeSteps())
            return t + horizon;
        else
            return t;
    }

    public int getHorizon() {
        return horizon;
    }

    public int getRelativeTimeStepWithinPeriod(int t, VesselPeriod ip) {
        return ip.getRelativeTimeWithinPeriod(t, horizon);
    }

    public int getRelativeEarliness(int activityTimeStep, VesselPeriod ip) {
        int relativeTime = getRelativeTimeStepWithinPeriod(activityTimeStep, ip);
        return Math.max(0, ip.getRelativeExpectedIntervalStart() - relativeTime);
    }

    public int getRelativeTardiness(int activityTimeStep, VesselPeriod ip) {
        int relativeTime = getRelativeTimeStepWithinPeriod(activityTimeStep, ip);
        // reason for "relativeTime + 1": activity happens at time step t and the end is t+1.
        return Math.max(0, relativeTime + 1 - ip.getRelativeExpectedIntervalEnd());
    }


    public String summaryVesselPeriods() {
        StringBuilder sb = new StringBuilder("\n");
        for (Vessel vessel : getVessels()) {
            sb.append(vessel.summary()).append(": \n");
            for (VesselPeriod vesselPeriod : vessel.getPeriods()) {
                sb.append("\t").append(vesselPeriod.summary()).append("\n");
            }
        }
        return sb.toString();
    }

    public String summaryTransshipment() {
        StringBuilder sb = new StringBuilder("\n");
        sb.append("Transshipment: \n");
        for (Vessel vessel1 : getVessels())
            for (Vessel vessel2 : getVessels()) {
                sb.append(vessel1).append("->").append(vessel2).append(": ");
                for (VesselPeriod vp1 : vessel1.getPeriods())
                    for (VesselPeriod vp2 : vessel2.getPeriods()) {
                        if (hasTransshipment(vp1, vp2))
                            sb.append(vp1).append("->").append(vp2).
                                    append(" [").append(getTransshipmentTo(vp1, vp2)).append("], ");
                    }
                sb.append("\n");

            }
        sb.append("Total:\n");
        int averageUnload = 0;
        int averageLoad = 0;
        int averageUnloadFlow = 0;
        int averageLoadFlow = 0;
        int size = 0;
        for (Vessel vessel : getVessels()) {
            sb.append(vessel).append(": ");
            for (VesselPeriod vesselPeriod : vessel.getPeriods()) {
                sb.append(vesselPeriod).append("(unload=").append(vesselPeriod.totalUnloadContainers)
                        .append("[").append(vesselPeriod.totalUnloadFlow).append("], ")
                        .append("load=").append(vesselPeriod.totalLoadContainers)
                        .append("[").append(vesselPeriod.totalLoadFlow).append("]").append("), ");
                averageUnload += vesselPeriod.totalUnloadContainers;
                averageLoad += vesselPeriod.totalLoadContainers;
                averageUnloadFlow += vesselPeriod.totalUnloadFlow;
                averageLoadFlow += vesselPeriod.totalLoadFlow;
                size++;
            }
            sb.append("\n");
        }
        sb.append("Average unload: c=").append(averageUnload * 1. / size)
                .append(", f=").append(averageUnloadFlow * 1. / size)
                .append("Average load: c=").append(averageLoad * 1. / size)
                .append(", f=").append(averageLoadFlow * 1. / size).append("\n");

        return sb.toString();
    }

    public String summaryBerthPositions() {
        StringBuilder sb = new StringBuilder("\n");
        for (Vessel vessel : getVessels()) {
            for (VesselPeriod vesselPeriod : vessel.getPeriods()) {
                sb.append(vessel).append(", ").append(vesselPeriod)
                        .append("(").append(vesselPeriod.berthPosition).append(")").append(":\t");
                for (Subblock subblock : getSubblocks()) {
                    sb.append(subblock).append("[to=(").append(this.getDistanceToSubblock(vesselPeriod, subblock)).append(", ");
                    for (int l : this.getRouteToSubblock(vesselPeriod, subblock))
                        sb.append(l).append(" ");
                    sb.append("), from=(").append(this.getDistanceFromSubblock(vesselPeriod, subblock)).append(", ");
                    for (int l : this.getRouteFromSubblock(vesselPeriod, subblock))
                        sb.append(l).append(" ");
                    sb.append(")], ");
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    public InstanceData toData() {
        InstanceData data = new InstanceData();
        data.etaCongestion = this.etaCongestion;
        data.etaRoute = this.etaRoute;
        data.spaceCapacity = this.spaceCapacity;
        data.maxUnloadFlows = this.maxUnloadFlows;
        data.maxLoadFlows = this.maxLoadFlows;
        data.name = this.name;
        data.horizon = this.horizon;
        data.extension = this.extension;
        data.rows = this.rows;
        data.cols = this.cols;
        data.roads = this.roads;
        data.vessels = this.getVessels().stream().map(Vessel::toData).toList();
        data.vesselPeriods = this.getVesselPeriods().stream().map(VesselPeriod::toData).toList();
        data.subblocks = this.getSubblocks().stream().map(Subblock::toData).toList();

        data.routingInfos = new HashSet<>();
        for (VesselPeriod ip : this.getVesselPeriods())
            for (Subblock sb : this.getSubblocks()) {
                SubblockRoutingInfo info = new SubblockRoutingInfo();
                info.subblockId = sb.getId();
                info.vpId = ip.getVpId();
                info.distanceTo = this.getDistanceToSubblock(ip, sb);
                info.distanceFrom = this.getDistanceFromSubblock(ip, sb);
                info.routeTo = new LinkedList<>(this.getRouteToSubblock(ip, sb));
                info.routeFrom = new LinkedList<>(this.getRouteFromSubblock(ip, sb));

                data.routingInfos.add(info);
            }

        data.transshipmentInfos = new HashSet<>();
        for (VesselPeriod jq : this.getVesselPeriods()) {
            Map<VesselPeriod, Integer> transship = this.transshipTo.getOrDefault(jq, Collections.emptyMap());
            List<VesselPeriod> sorted = transship.keySet().stream()
                    .sorted(Comparator.comparingInt(VesselPeriod::getVpId)).toList();
            for (VesselPeriod ip : sorted) {
                TransshipmentInfo info = new TransshipmentInfo();
                info.srcVpId = jq.getVpId();
                info.dstVpId = ip.getVpId();
                info.containers = transship.get(ip);

                data.transshipmentInfos.add(info);
            }
        }

        return data;
    }

    // 从 InstanceData 构造 Instance
    public static Instance fromData(InstanceData data) {
        Instance instance = new Instance();
        instance.etaCongestion = data.etaCongestion;
        instance.etaRoute = data.etaRoute;
        instance.spaceCapacity = data.spaceCapacity;
        instance.maxUnloadFlows = data.maxUnloadFlows;
        instance.maxLoadFlows = data.maxLoadFlows;
        instance.name = data.name;
        instance.horizon = data.horizon;
        instance.extension = data.extension;

        instance.setYardConfiguration(data.rows, data.cols);
        if (instance.roads != data.roads)
            throw new RuntimeException("Inconsistent setting of roads: " + instance.roads + " != " + data.roads);
        instance.buildNeighborSubblock();


        for (VesselData vesselData : data.vessels) {
            Vessel vessel = Vessel.fromData(vesselData, data.horizon);
            instance.addVessel(vessel);
        }

        for (VesselPeriodData periodData : data.vesselPeriods) {
            VesselPeriod period = VesselPeriod.fromData(periodData, data.horizon);
            instance.addVesselPeriod(period);
            instance.getVessel(period.getVid()).addVesselPeriod(period);
        }

        for (SubblockData subblockData : data.subblocks) {
            Subblock subblock = Subblock.fromData(subblockData);
            instance.addSubblock(subblock);
        }

        for (SubblockRoutingInfo info : data.routingInfos) {
            VesselPeriod ip = instance.getVesselPeriod(info.vpId);
            Subblock sb = instance.getSubblock(info.subblockId);
            instance.setDistanceToSubblock(ip, sb, info.distanceTo);
            instance.setDistanceFromSubblock(ip, sb, info.distanceFrom);
            instance.setRouteToSubblock(ip, sb, info.routeTo);
            instance.setRouteFromSubblock(ip, sb, info.routeFrom);
        }

        for (TransshipmentInfo info : data.transshipmentInfos) {
            VesselPeriod jq = instance.getVesselPeriod(info.srcVpId);
            VesselPeriod ip = instance.getVesselPeriod(info.dstVpId);
            instance.addTransshipment(jq, ip, info.containers);
        }

        return instance;
    }

    public static Instance readJson(String path) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            InstanceData data = mapper.readValue(new File(path), InstanceData.class);
            return Instance.fromData(data);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void writeJson(String path) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.writerWithDefaultPrettyPrinter().writeValue(new File(path), this.toData());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) throws JsonProcessingException {


    }
}
