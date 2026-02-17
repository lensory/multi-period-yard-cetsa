package main;

import dto.*;
import entity.CyclicClosedOpenInterval;
import entity.Instance;
import util.MyMathMethods;

import java.io.IOException;
import java.util.*;
import java.util.stream.IntStream;

public class InstanceGenerator {
    private static final int DEFAULT_TIME_STEP_RATIO = 3;
    // Default parameters for the instance generation
    private static final int DEFAULT_AVERAGE_SUBBLOCK_NUMBER_FOR_EACH_VESSEL = 5;

    private static final int DEFAULT_CONTAINER_NUMBER_PER_VESSEL = DEFAULT_AVERAGE_SUBBLOCK_NUMBER_FOR_EACH_VESSEL *
            Instance.DEFAULT_SUBBLOCK_SPACE_CAPACITY;
    private static final int DEFAULT_MIN_CONTAINER_NUMBER_PER_FLOW = 240;
    private static final int DEFAULT_MAX_CONTAINER_NUMBER_PER_FLOW = 300;
    private static final int DEFAULT_AVERAGE_CONTAINER_NUMBER_PER_FLOW = (DEFAULT_MIN_CONTAINER_NUMBER_PER_FLOW + DEFAULT_MAX_CONTAINER_NUMBER_PER_FLOW) / 2;
    private static final int DEFAULT_AVERAGE_SOURCE_NUMBER = DEFAULT_CONTAINER_NUMBER_PER_VESSEL / DEFAULT_AVERAGE_CONTAINER_NUMBER_PER_FLOW;
    private static final int DEFAULT_MIN_SOURCE_NUMBER = 2;
    private static final int DEFAULT_MAX_SOURCE_NUMBER = 6;


//    private static final int DEFAULT_MAX_QUAY_SPEED = 1800 / DEFAULT_TIME_STEP_RATIO;
//    private static final int DEFAULT_FEASIBLE_EXPECTED_RATIO = 5;
//    private static final int DEFAULT_SLACK_TIME_STEPS = 1 * DEFAULT_TIME_STEP_RATIO;
//    private static final int DEFAULT_EXPECTED_TIME_STEPS = 2 * DEFAULT_CONTAINER_NUMBER_PER_VESSEL / DEFAULT_MAX_QUAY_SPEED;
//    private static final int DEFAULT_FEASIBLE_TIME_STEPS = DEFAULT_EXPECTED_TIME_STEPS * DEFAULT_FEASIBLE_EXPECTED_RATIO;

    private static final int DEFAULT_FEASIBLE_EXPECTED_RATIO = 5;
    private static final int DEFAULT_SLACK_TIME_STEPS = 1 * DEFAULT_TIME_STEP_RATIO;
    private static final int DEFAULT_EXPECTED_TIME_STEPS = 4;
    private static final int DEFAULT_FEASIBLE_TIME_STEPS = DEFAULT_EXPECTED_TIME_STEPS * DEFAULT_FEASIBLE_EXPECTED_RATIO;


    private static final double ETA_CONGESTION = 20;
    private static final double ETA_ROUTE = 5 * 1e-6;
    private static final double ETA_TIME = 1;
    private static final int MAX_UNLOAD_FLOWS = 4;
    private static final int MAX_LOAD_FLOWS = 4;


    private final Map<String, double[]> weightRangeEarlinessTardiness = new LinkedHashMap<>() {{
        put("small", new double[]{2, 6});
        put("medium", new double[]{6, 10});
        put("large", new double[]{10, 14});
    }};

    private final Map<String, Integer> cyclicTimesteps = new LinkedHashMap<>() {{
        put("small", 7 * DEFAULT_TIME_STEP_RATIO);
        put("medium", 10 * DEFAULT_TIME_STEP_RATIO);
        put("large", 14 * DEFAULT_TIME_STEP_RATIO);
    }};


    private Random random;


    private Map<String, Integer> periodForType;
    private Map<String, Integer> numberForType;
    private Map<String, Double> minTimeCostForType;
    private Map<String, Double> maxTimeCostForType;
    private InstanceData data;

    public InstanceGenerator() {
        periodForType = new HashMap<>();
        numberForType = new HashMap<>();
        minTimeCostForType = new HashMap<>();
        maxTimeCostForType = new HashMap<>();
        data = new InstanceData();
    }


    public InstanceGenerator setVesselType(String type, int number) {
        if (!weightRangeEarlinessTardiness.containsKey(type))
            throw new IllegalArgumentException(type + " is not contained in default weightRangeEarlinessTardiness.");
        if (!cyclicTimesteps.containsKey(type)) {
            throw new IllegalArgumentException(type + " is not contained in default cyclicTimesteps.");
        }
        return setVesselType(type, number, cyclicTimesteps.get(type), weightRangeEarlinessTardiness.get(type)[0], weightRangeEarlinessTardiness.get(type)[1]);
    }

    public InstanceGenerator setVesselType(String type, int number, int period, double minTimeCost, double maxTimeCost) {

        periodForType.put(type, period);
        numberForType.put(type, number);
        minTimeCostForType.put(type, minTimeCost);
        maxTimeCostForType.put(type, maxTimeCost);
        return this;
    }

    public InstanceGenerator setDefaultParameters() {
        data.etaRoute = ETA_ROUTE;
        data.etaCongestion = ETA_CONGESTION;
        data.spaceCapacity = Instance.DEFAULT_SUBBLOCK_SPACE_CAPACITY;
        data.maxLoadFlows = MAX_LOAD_FLOWS;
        data.maxUnloadFlows = MAX_UNLOAD_FLOWS;
        return this;
    }


    public InstanceData generate(String name, int horizon, int rows, int cols, int seed) {
        random = new Random(seed);

        data.name = name;
        data.horizon = horizon;


        data.vessels = new ArrayList<>();
        data.vesselPeriods = new ArrayList<>();

        for (String vesselType : periodForType.keySet()) {
            int periodLength = periodForType.get(vesselType);
            int vesselNum = numberForType.get(vesselType);
            if (data.horizon % periodLength != 0)
                throw new IllegalArgumentException("Horizon must be a multiple of period length.");
            for (int i = 0; i < vesselNum; i++) {
                VesselData vessel = new VesselData();
                vessel.vId = data.vessels.size();
                vessel.type = vesselType;
                int periodNum = data.horizon / periodLength;
                vessel.vpIds = new ArrayList<>(periodNum);
                for (int j = 0; j < periodNum; j++) {
                    VesselPeriodData period = new VesselPeriodData();
                    period.vid = vessel.vId;
                    period.pid = j;
                    period.vpId = data.vesselPeriods.size();
                    period.type = vesselType;
                    vessel.vpIds.add(period.vpId);
                    data.vesselPeriods.add(period);
                }
                data.vessels.add(vessel);
            }
        }

        for (VesselData vessel : data.vessels) {
            String type = vessel.type;
            int periodLength = periodForType.get(type);
            int idle = random.nextInt(periodLength);
            int feasibleStart = periodLength - DEFAULT_FEASIBLE_TIME_STEPS;
            int expectedStart = periodLength - DEFAULT_SLACK_TIME_STEPS - DEFAULT_EXPECTED_TIME_STEPS;

            vessel.lengthOfPeriod = periodLength;
            vessel.idleTimeSteps = idle;
            vessel.relativeFeasibleIntervalStart = feasibleStart;
            vessel.relativeFeasibleIntervalDuration = DEFAULT_FEASIBLE_TIME_STEPS;
            vessel.relativeExpectedIntervalStart = expectedStart;
            vessel.relativeExpectedIntervalDuration = DEFAULT_EXPECTED_TIME_STEPS;

            for (int j : vessel.vpIds) {
                VesselPeriodData period = data.vesselPeriods.get(j);
                period.absolutePeriodStart = (period.pid * periodLength + vessel.idleTimeSteps) % data.horizon;
                period.absolutePeriodDuration = periodLength;
                period.relativeFeasibleIntervalStart = vessel.relativeFeasibleIntervalStart;
                period.relativeFeasibleIntervalDuration = vessel.relativeFeasibleIntervalDuration;
                period.relativeExpectedIntervalStart = vessel.relativeExpectedIntervalStart;
                period.relativeExpectedIntervalDuration = vessel.relativeExpectedIntervalDuration;
            }
        }

        for (VesselPeriodData period : data.vesselPeriods) {
            double min = minTimeCostForType.get(period.type);
            double max = maxTimeCostForType.get(period.type);
            period.earlinessCost = random.nextDouble(min, max);
            period.tardinessCost = random.nextDouble(min, max);
        }

        double berth = cols * Instance.DEFAULT_SUBBLOCK_NUMBER_FOR_EACH_BLOCK
                * Instance.DEFAULT_SUBBLOCK_WIDTH; // x-axis
        for (VesselPeriodData period : data.vesselPeriods) {
            period.berthPosition = random.nextDouble(0, berth);
        }

        // extension = max(E_i,p) - T = max(idle)
        data.extension = data.vessels.stream().mapToInt(v -> v.idleTimeSteps).max().orElse(0);

        Map<VesselPeriodData, List<VesselPeriodData>> potentialSourceVesselPeriods = new LinkedHashMap<>();
        for (VesselPeriodData src : data.vesselPeriods) {
            for (VesselPeriodData dest : data.vesselPeriods) {
                if (src.vpId == dest.vpId)
                    continue;
                if (src.vid == dest.vid)
                    continue;
                if (data.horizon <= 0)
                    throw new IllegalArgumentException("Horizon must be initialized with positive value, current: " + horizon);

                CyclicClosedOpenInterval sourceOperation = new CyclicClosedOpenInterval(
                        (src.absolutePeriodStart + src.relativeExpectedIntervalStart) % data.horizon,
                        src.relativeExpectedIntervalDuration);

                CyclicClosedOpenInterval destinationOperation = new CyclicClosedOpenInterval(
                        dest.absolutePeriodStart % data.horizon,
                        dest.relativeExpectedIntervalStart);

                if (destinationOperation.isIntersecting(sourceOperation, horizon))
                    potentialSourceVesselPeriods.computeIfAbsent(dest, key -> new LinkedList<>()).add(src);
            }
        }

        data.transshipmentInfos = new HashSet<>();
//        for (Map.Entry<VesselPeriodData, List<VesselPeriodData>> entry : potentialSourceVesselPeriods.entrySet()) {
//            VesselPeriodData dest = entry.getKey();
//            List<VesselPeriodData> srcList = entry.getValue();
//            double p = Math.min(1. * DEFAULT_AVERAGE_SOURCE_NUMBER / srcList.size(), 1);
//            for (VesselPeriodData src : srcList) {
//                if (random.nextDouble() > p)
//                    continue;
//                int n = DEFAULT_MIN_CONTAINER_NUMBER_PER_FLOW +
//                        random.nextInt(DEFAULT_MAX_CONTAINER_NUMBER_PER_FLOW - DEFAULT_MIN_CONTAINER_NUMBER_PER_FLOW + 1);
//                TransshipmentInfo info = new TransshipmentInfo();
//                info.srcVpId = src.vpId;
//                info.dstVpId = dest.vpId;
//                info.containers = n;
//                data.transshipmentInfos.add(info);
//                src.totalUnloadContainers += n;
//                dest.totalLoadContainers += n;
//                src.totalUnloadFlow += 1;
//                dest.totalLoadFlow += 1;
//            }
//        }

        for (Map.Entry<VesselPeriodData, List<VesselPeriodData>> entry : potentialSourceVesselPeriods.entrySet()) {
            VesselPeriodData dest = entry.getKey();
            List<VesselPeriodData> srcList = entry.getValue();
            int numSrc = Math.min(srcList.size(), random.nextInt(DEFAULT_MIN_SOURCE_NUMBER, DEFAULT_MAX_SOURCE_NUMBER + 1));
            // randomly get numSrc elements from srcList
            List<VesselPeriodData> selected = new ArrayList<>(srcList);
            Collections.shuffle(selected, random);
            selected = selected.subList(0, numSrc);
            for (VesselPeriodData src : selected) {
                int n = DEFAULT_MIN_CONTAINER_NUMBER_PER_FLOW +
                        random.nextInt(DEFAULT_MAX_CONTAINER_NUMBER_PER_FLOW - DEFAULT_MIN_CONTAINER_NUMBER_PER_FLOW + 1);
                TransshipmentInfo info = new TransshipmentInfo();
                info.srcVpId = src.vpId;
                info.dstVpId = dest.vpId;
                info.containers = n;
                data.transshipmentInfos.add(info);
                src.totalUnloadContainers += n;
                dest.totalLoadContainers += n;
                src.totalUnloadFlow += 1;
                dest.totalLoadFlow += 1;
            }


        }


        data.rows = rows;
        data.cols = cols;
        data.roads = (cols + 1) * rows / Instance.DEFAULT_BLOCK_NUMBER_FOR_EACH_SUPER_BLOCK;

        data.subblocks = new ArrayList<>();
        for (int i = 0; i < rows; i++)
            for (int j = 0; j < cols; j++)
                for (int k = 0; k < Instance.DEFAULT_SUBBLOCK_NUMBER_FOR_EACH_BLOCK; k++) {
                    int subblockId = (i * cols + j) * Instance.DEFAULT_SUBBLOCK_NUMBER_FOR_EACH_BLOCK + k;
                    int blockId = i * cols + j;

                    SubblockData subblock = new SubblockData();
                    subblock.id = subblockId;
                    subblock.blockId = blockId;
                    subblock.rowId = i;
                    subblock.colId = j;
                    subblock.slotId = k;
                    subblock.hLaneId = (i + 1) / 2 * cols + j;
                    data.subblocks.add(subblock);
                }

        data.routingInfos = new HashSet<>();
        for (VesselPeriodData period : data.vesselPeriods) {
            double berthPosition = period.berthPosition;
            for (SubblockData subblock : data.subblocks) {
                double junctionInX = subblock.colId * Instance.DEFAULT_SUBBLOCK_WIDTH * Instance.DEFAULT_SUBBLOCK_NUMBER_FOR_EACH_BLOCK;
                double junctionOutX = (subblock.colId + 1) * Instance.DEFAULT_SUBBLOCK_WIDTH * Instance.DEFAULT_SUBBLOCK_NUMBER_FOR_EACH_BLOCK;
                int passedLanes = Math.floorDiv(subblock.rowId + 1, Instance.DEFAULT_BLOCK_NUMBER_FOR_EACH_SUPER_BLOCK);

                SubblockRoutingInfo routingInfo = new SubblockRoutingInfo();
                routingInfo.vpId = period.vpId;
                routingInfo.subblockId = subblock.id;
                routingInfo.distanceTo = Math.abs(junctionInX - berthPosition) + passedLanes * Instance.DEFAULT_PASSING_LANE_LENGTH + subblock.slotId * Instance.DEFAULT_SUBBLOCK_WIDTH;
                routingInfo.distanceFrom = Math.abs(junctionOutX - berthPosition) + passedLanes * Instance.DEFAULT_PASSING_LANE_LENGTH + (Instance.DEFAULT_SUBBLOCK_NUMBER_FOR_EACH_BLOCK - subblock.slotId) * Instance.DEFAULT_SUBBLOCK_WIDTH;
                routingInfo.routeTo = IntStream.range(0, passedLanes).map(l -> l * (cols + 1) + subblock.colId).boxed().toList();
                routingInfo.routeFrom = IntStream.range(0, passedLanes).map(l -> l * (cols + 1) + subblock.colId + 1).boxed().toList().reversed();

                data.routingInfos.add(routingInfo);
            }
        }
        return data;
    }

    private double calculateEuclideanDistance(double x1, double y1, double x2, double y2) {
        return Math.sqrt((x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2));
    }

    public static Instance generate(int small, int medium, int large, int rows, int cols, int seed) {
        InstanceGenerator instanceGenerator = new InstanceGenerator();
        if (small > 0)
            instanceGenerator.setVesselType("small", small);
        if (medium > 0)
            instanceGenerator.setVesselType("medium", medium);
        if (large > 0)
            instanceGenerator.setVesselType("large", large);
        instanceGenerator.setDefaultParameters();
        int horizon = MyMathMethods.leastCommonMultiple(instanceGenerator.numberForType.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .mapToInt(entry -> instanceGenerator.periodForType.get(entry.getKey()))
                .toArray()
        );
        String name = String.format(Instance.DEFAULT_NAME_PATTERN,
                small, medium, large, rows, cols, seed);

        InstanceData instanceData = instanceGenerator.generate(name, horizon, rows, cols, seed);

        // validation
        if (!Instance.fromData(instanceData).toData().equals(instanceData))
            throw new RuntimeException("Validation failed.");

        return Instance.fromData(instanceData);
    }

    public static void main(String[] args) throws IOException {
        int[][] instanceConfigurations = new int[][]{
                // only 7-day and 14-day vessels.
//                {2, 0, 1, 6, 1},
//                {4, 0, 2, 6, 2},
//                {6, 0, 3, 6, 3},
//                {8, 0, 4, 6, 4},
//                {10, 0, 5, 6, 5},

                // 7-day, 10-day and 14-day vessels.
                {4, 1, 1, 4, 2},
                {8, 2, 2, 4, 4},
                {12, 3, 3, 4, 6},
                {16, 4, 4, 4, 8},
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


                Instance instance = generate(small, medium, large, rows, cols, seed);

                instance.writeJson("input/" + instance.name + ".json");

            }
        }
    }


}