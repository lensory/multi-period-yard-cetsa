package entity;

import dto.ScheduleInfo;
import dto.SolutionData;
import dto.TurnaroundInfo;
import util.*;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

public class Solution {
    public Instance instance;

    private Map<VesselPeriod, Integer> expectedSubblockNumber;
    private Map<VesselPeriod, List<Subblock>> subblockAssignments;
    private Map<VesselPeriod, Map<Subblock, Map<VesselPeriod, Schedule>>> unloadSchedules;
    private Map<VesselPeriod, Map<Subblock, Schedule>> loadSchedules;


    private Map<VesselPeriod, Integer> auxiliaryEarliness, auxiliaryTardiness;
    private Map<Integer, Map<Integer, Integer>> auxiliaryLoadRoadFlows; // R->T->flows
    private Map<Integer, Map<Integer, Integer>> auxiliaryUnloadRoadFlows; // R->T->flows
    private int unloadOverload;
    private int loadOverload;


    private double gap;
    private double objRoute;
    private double objTime;
    private double objCongestion;
    private double objAll;
    private double runningTime;
    private String solverName;
    private LocalDateTime solverStartTime;
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd HH:mm:ss");


    public Solution(Instance instance) {
        this.instance = instance;

        this.subblockAssignments = instance.getVesselPeriods()
                .stream()
                .collect(Collectors.toMap(ip -> ip, ip -> new LinkedList<>()));


        unloadSchedules = new LinkedHashMap<>();
        loadSchedules = new LinkedHashMap<>();

        this.expectedSubblockNumber = new LinkedHashMap<>();
        for (VesselPeriod ip : instance.getVesselPeriods()) {
            expectedSubblockNumber.put(ip, MyMathMethods.ceilDiv(ip.totalLoadContainers, instance.spaceCapacity));
        }

    }

    public void setSolverName(String solverName) {
        this.solverName = solverName;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.solverStartTime = startTime;
    }

//    public void setObj(double objRoute, double objTime) {
//        this.objRoute = objRoute;
//        this.objTime = objTime;
//        this.objAll = objRoute * instance.ETA_ROUTE + objTime * instance.ETA_TIME;
//    }

    public void setGap(double gap) {
        this.gap = gap;
    }

    public void setRunningTime(double time) {
        this.runningTime = time;
    }

    public void setSubBlock(VesselPeriod ip, Subblock k) {
        Objects.requireNonNull(ip, "VesselPeriod cannot be null");
        Objects.requireNonNull(k, "Subblock cannot be null");
        List<Subblock> assigned = subblockAssignments.get(ip);
        if (!assigned.contains(k))
            assigned.add(k);

    }

    public Map<VesselPeriod, Set<Subblock>> getSubblockAssignments() {
        return subblockAssignments.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> new HashSet<>(entry.getValue())));
    }

    public int getEarliness(VesselPeriod ip) {
        return auxiliaryEarliness.get(ip);
    }

    public int getTardiness(VesselPeriod ip) {
        return auxiliaryTardiness.get(ip);
    }

    public int getUnloadOverload() {
        return unloadOverload;
    }

    public int getLoadOverload() {
        return loadOverload;
    }

    public Map<Integer, Map<Integer, Integer>> getAuxiliaryLoadRoadFlows() {
        return auxiliaryLoadRoadFlows;
    }

    public Map<Integer, Map<Integer, Integer>> getAuxiliaryUnloadRoadFlows() {
        return auxiliaryUnloadRoadFlows;
    }

    public double getRunningTime() {
        return runningTime;
    }

    public String getSubblockSummary() {
        StringBuilder sb = new StringBuilder();
        instance.getVessels().forEach(vessel ->
                vessel.getPeriods().forEach(ip -> {
                    sb.append(ip).append(": ");
                    subblockAssignments.get(ip).forEach(k -> sb.append(k).append(","));
                    sb.append("\n");
                })
        );
        return sb.toString();
    }

    public void summarySubblock() {
        System.out.println("Subblock assignment:");
        System.out.println(getSubblockSummary());
    }

//    public void setArrivalAdvance(VesselPeriod vesselPeriod, int time) {
//        arrivalAdvance.put(vesselPeriod, time);
//    }
//
//    public void setArrivalDelay(VesselPeriod vesselPeriod, int time) {
//        arrivalDelay.put(vesselPeriod, time);
//    }

    public void setUnloadSchedule(VesselPeriod jq, VesselPeriod ip, Subblock subBlock, int time, int number) {
        unloadSchedules.computeIfAbsent(ip, key -> new LinkedHashMap<>())
                .computeIfAbsent(subBlock, key -> new LinkedHashMap<>())
                .put(jq, new Schedule(time, number));

        this.setSubBlock(ip, subBlock);
    }

    public void setLoadSchedule(VesselPeriod ip, Subblock subBlock, int time, int number) {
        loadSchedules.computeIfAbsent(ip, key -> new LinkedHashMap<>())
                .put(subBlock, new Schedule(time, number));

        this.setSubBlock(ip, subBlock);
    }

    public void addScheduleProgressively(VesselPeriod jq, VesselPeriod ip, Subblock subBlock,
                                         int unloadTime, int loadTime, int number) {
        Map<VesselPeriod, Schedule> unloadSchedule = unloadSchedules.computeIfAbsent(ip, key -> new LinkedHashMap<>())
                .computeIfAbsent(subBlock, key -> new LinkedHashMap<>());
        if (!unloadSchedule.containsKey(jq)) {
            unloadSchedule.put(jq, new Schedule(unloadTime, number));
        } else {
            Schedule schedule = unloadSchedule.get(jq);
            if (schedule.time != unloadTime)
                throw new IllegalStateException();
            schedule.number += number;
        }
        Map<Subblock, Schedule> loadSchedule = loadSchedules.computeIfAbsent(ip, key -> new LinkedHashMap<>());
        if (!loadSchedule.containsKey(subBlock)) {
            loadSchedule.put(subBlock, new Schedule(loadTime, number));
        } else {
            Schedule schedule = loadSchedule.get(subBlock);
            if (schedule.time != loadTime)
                throw new IllegalStateException();
            schedule.number += number;
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


    public void calculateObjectives() {
        this.auxiliaryEarliness = new LinkedHashMap<>();
        this.auxiliaryTardiness = new LinkedHashMap<>();
        for (VesselPeriod ip : instance.getVesselPeriods()) {
            auxiliaryEarliness.put(ip, 0);
            auxiliaryTardiness.put(ip, 0);
        }

        this.auxiliaryUnloadRoadFlows = new LinkedHashMap<>();
        this.auxiliaryLoadRoadFlows = new LinkedHashMap<>();
        loadOverload = 0;
        unloadOverload = 0;

        forEachUnloadSchedule((ip, subBlock, jq, schedule) -> {
            changeAdvanceDelay(jq, schedule.time);
            for (int l : instance.getRouteToSubblock(jq, subBlock)) {
                int f = auxiliaryUnloadRoadFlows.computeIfAbsent(l, k -> new LinkedHashMap<>())
                        .merge(schedule.time, 1, Integer::sum);
                if (f > instance.maxUnloadFlows + unloadOverload)
                    unloadOverload = f - instance.maxUnloadFlows;
            }
        });
        forEachLoadSchedule((ip, subBlock, schedule) -> {
            changeAdvanceDelay(ip, schedule.time);
            for (int l : instance.getRouteFromSubblock(ip, subBlock)) {
                int f = auxiliaryLoadRoadFlows.computeIfAbsent(l, k -> new LinkedHashMap<>())
                        .merge(schedule.time, 1, Integer::sum);
                if (f > instance.maxLoadFlows + loadOverload)
                    loadOverload = f - instance.maxLoadFlows;
            }
        });


        objTime = 0;
        for (VesselPeriod ip : instance.getVesselPeriods()) {
            int earliness = auxiliaryEarliness.get(ip);
            int tardiness = auxiliaryTardiness.get(ip);
            double earlinessCost = ip.getEarlinessCost();
            double tardinessCost = ip.getTardinessCost();
            objTime += earliness * earlinessCost + tardiness * tardinessCost;
        }

        objRoute = 0;
        for (Map.Entry<VesselPeriod, Map<Subblock, Map<VesselPeriod, Schedule>>> e : unloadSchedules.entrySet()) {
            VesselPeriod ip = e.getKey();
            Map<Subblock, Map<VesselPeriod, Schedule>> subblockMap = e.getValue();
            for (Map.Entry<Subblock, Map<VesselPeriod, Schedule>> mapEntry : subblockMap.entrySet()) {
                Subblock k = mapEntry.getKey();
                Map<VesselPeriod, Schedule> map = mapEntry.getValue();
                for (Map.Entry<VesselPeriod, Schedule> entry : map.entrySet()) {
                    VesselPeriod jq = entry.getKey();
                    Schedule schedule = entry.getValue();
                    objRoute += schedule.number * (instance.getDistanceToSubblock(jq, k)
                            + instance.getDistanceFromSubblock(ip, k)) * instance.etaRoute;
                }
            }
        }
        objCongestion = instance.etaCongestion * (loadOverload + unloadOverload);

        objAll = objRoute + objTime + objCongestion;

    }

    public void summarySchedule() {
        for (VesselPeriod ip : instance.getVesselPeriods()) {
            String header = String.format("VP%d: T%d->T%d;",
                    ip.vpId,
                    auxiliaryEarliness.getOrDefault(ip, -1),
                    auxiliaryTardiness.getOrDefault(ip, -1));
            System.out.println(header);
            for (Subblock k : subblockAssignments.getOrDefault(ip, Collections.emptyList())) {
                System.out.println("Subblock " + k + ": ");
                Schedule load = loadSchedules.getOrDefault(ip, Collections.emptyMap())
                        .get(k);
                System.out.println("Load: " + String.format("T%d-N%d", load.time, load.number));
                for (Map.Entry<VesselPeriod, Schedule> entry : unloadSchedules.getOrDefault(ip, Collections.emptyMap())
                        .get(k).entrySet()) {
                    VesselPeriod jq = entry.getKey();
                    Schedule unload = entry.getValue();
                    System.out.println("From " + jq + ": " + String.format("T%d-N%d", unload.time, unload.number));
                }


            }

        }
    }

    public static class Schedule {
        public Integer time, number;

        public Schedule(Integer time, Integer number) {
            this.time = time;
            this.number = number;
        }
    }

    public SolutionData toData() {
        SolutionData data = new SolutionData();
        data.objAll = this.objAll;
        data.objRoute = this.objRoute;
        data.objTime = this.objTime;
        data.objCongestion = this.objCongestion;
        data.gap = this.gap;
        data.runningTime = this.runningTime;
        data.solverName = this.solverName;
        data.solverStartTime = this.solverStartTime;


        data.turnaroundInfos = new LinkedList<>();
        for (VesselPeriod ip : this.instance.getVesselPeriods()) {
            TurnaroundInfo turnaroundInfo = new TurnaroundInfo();
            turnaroundInfo.vpId = ip.getVpId();
            turnaroundInfo.exceptedSubblockNumber = this.expectedSubblockNumber.get(ip);
            turnaroundInfo.subblocks = subblockAssignments.get(ip).
                    stream().map(Subblock::getId).toList();
            turnaroundInfo.advance = this.auxiliaryEarliness.getOrDefault(ip, 0);
            turnaroundInfo.delay = this.auxiliaryTardiness.getOrDefault(ip, 0);
            data.turnaroundInfos.add(turnaroundInfo);
        }

        data.scheduleInfos = new LinkedList<>();
        for (VesselPeriod ip : instance.getVesselPeriods()) {
            Map<Subblock, Map<VesselPeriod, Schedule>> unloadSchedules = this.unloadSchedules.get(ip);
            Map<Subblock, Schedule> loadSchedules = this.loadSchedules.get(ip);
            assert loadSchedules.keySet().equals(unloadSchedules.keySet());
            List<Subblock> sortedSubblocks = loadSchedules.keySet().stream()
                    .sorted(Comparator.comparingInt(Subblock::getId)).toList();
            for (Subblock k : sortedSubblocks) {
                Schedule load = loadSchedules.get(k);
                Map<VesselPeriod, Schedule> unloads = unloadSchedules.get(k);
                List<VesselPeriod> sortedSources = unloads.keySet().stream()
                        .sorted(Comparator.comparingInt(VesselPeriod::getVpId)).toList();
                for (VesselPeriod jq : sortedSources) {
                    Schedule unload = unloads.get(jq);
                    ScheduleInfo scheduleInfo = new ScheduleInfo();
                    scheduleInfo.srcVpId = jq.getVpId();
                    scheduleInfo.dstVpId = ip.getVpId();
                    scheduleInfo.subblockId = k.getId();
                    scheduleInfo.unloadTime = unload.time;
                    scheduleInfo.loadTime = load.time;
                    scheduleInfo.number = unload.number;
                    data.scheduleInfos.add(scheduleInfo);
                }

                assert load.number == unloads.values().stream()
                        .mapToInt(schedule -> schedule.number).sum();
            }
        }


        return data;
    }

    public static Solution fromData(SolutionData data, Instance instance) {
        Solution solution = new Solution(instance);


        solution.objAll = data.objAll;
        solution.objRoute = data.objRoute;
        solution.objTime = data.objTime;
        solution.objCongestion = data.objCongestion;
        solution.setGap(data.gap);
        solution.setStartTime(data.solverStartTime);
        solution.setRunningTime(data.runningTime);
        solution.setSolverName(data.solverName);

        for (ScheduleInfo info : data.scheduleInfos) {
            solution.addScheduleProgressively(
                    instance.getVesselPeriod(info.srcVpId),
                    instance.getVesselPeriod(info.dstVpId),
                    instance.getSubblock(info.subblockId),
                    info.unloadTime, info.loadTime, info.number
            );
        }

        solution.calculateObjectives();

        return solution;
    }

    private enum CsvFile {
        // 移除CsvFile相关的序列化功能，因为现在使用新的序列化方法
        // 如果需要保留CSV功能可以重新添加，但根据需求重点实现toData/fromData方法
        OVERALL_SOLUTION("overallSolution.csv",
                new String[]{"obj", "objRoute", "objTime", "objCongestion", "gap", "time", "solverName", "solverStartTime"}, new boolean[8],
                Solution::readOverallSolution, Solution::writeOverallSolution),
        SUBBLOCK_SOLUTION("subblockSolution.csv",
                new String[]{"vpId", "subblockIds"},
                new boolean[]{false, true},
                Solution::readSubblockSolution, Solution::writeSubblockSolution),
        ARRIVAL_DEVIATION("handlingInterval.csv",
                new String[]{"vpId", "advance", "delay"},
                Solution::readArrivalDeviation, Solution::writeArrivalDeviation),
        UNLOAD_SCHEDULE("unloadSchedule.csv",
                new String[]{"srcVpId", "dstVpId", "time", "subblockId", "number"},
                Solution::readUnloadSchedule, Solution::writeUnloadSchedule),
        LOAD_SCHEDULE("loadSchedule.csv",
                new String[]{"vpId", "time", "subblockId", "number"},
                Solution::readLoadSchedule, Solution::writeLoadSchedule);

        public final CsvFileHandler<Solution> handler;

        CsvFile(String fileName, String[] headers, boolean[] isSequenceFlags,
                BiThrowableConsumer<Solution, CSVReader> reader, BiThrowableConsumer<Solution, CSVWriter> writer) {
            this.handler = new CsvFileHandler<>(fileName, headers, isSequenceFlags, reader, writer);
        }

        CsvFile(String fileName, String[] headers,
                BiThrowableConsumer<Solution, CSVReader> reader, BiThrowableConsumer<Solution, CSVWriter> writer) {
            this.handler = new CsvFileHandler<>(fileName, headers, reader, writer);
        }
    }

    public void read(String dir) {
        try {
            Path dirPath = Paths.get(dir);
            if (!Files.isDirectory(dirPath)) {
                throw new NotDirectoryException("Path is not a directory: " + dir);
            }
            for (CsvFile file : CsvFile.values()) {
                file.handler.read(this, dirPath);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    public void validate() {
        // check subblock number
        for (VesselPeriod ip : instance.getVesselPeriods()) {
            int assigned = subblockAssignments.getOrDefault(ip, Collections.emptyList()).size();
            int supposed = MyMathMethods.ceilDiv(ip.totalLoadContainers, instance.spaceCapacity);
            if (assigned != supposed) {
                throw new IllegalArgumentException("Subblock number mismatch for VP" + ip.vpId + " is " + assigned + " but supposed to be " + supposed);
            }
        }
        // check subblock conflicts in time dimension
        Map<Subblock, Set<VesselPeriod>> reversedSubblockAssignments = new HashMap<>();
        for (Map.Entry<VesselPeriod, List<Subblock>> entry : subblockAssignments.entrySet()) {
            for (Subblock k : entry.getValue()) {
                reversedSubblockAssignments.computeIfAbsent(k, k1 -> new HashSet<>()).add(entry.getKey());
            }
        }
        reversedSubblockAssignments.forEach((k, periods) -> {
            for (VesselPeriod vp1 : periods)
                for (VesselPeriod vp2 : periods) {
                    if (vp1.getVpId() == vp2.getVpId())
                        continue;
                    if (vp1.getPeriodInterval().isIntersecting(vp2.getPeriodInterval(), instance.horizon)) {
                        throw new IllegalArgumentException("Subblock " + k + " is assigned to " + vp1 + " and " + vp2 + " in time dimension");
                    }
                }
        });

        // check transshipment
        for (VesselPeriod ip : instance.getVesselPeriods()) {
            Map<Subblock, Schedule> loadPlans = loadSchedules.getOrDefault(ip, Collections.emptyMap());
            Map<Subblock, Map<VesselPeriod, Schedule>> unloadPlans = unloadSchedules.getOrDefault(ip, Collections.emptyMap());

            Set<Subblock> assignedSubblocks = new HashSet<>(subblockAssignments.getOrDefault(ip, Collections.emptyList()));
            Set<VesselPeriod> sourceVesselPeriods = new HashSet<>(instance.getSourceVesselPeriodsOf(ip));

            if (!assignedSubblocks.containsAll(loadPlans.keySet()))
                throw new IllegalArgumentException("Subblock " + loadPlans.keySet() + " is not assigned to " + ip);
            if (!assignedSubblocks.containsAll(unloadPlans.keySet()))
                throw new IllegalArgumentException("Subblock " + unloadPlans.keySet() + " is not assigned to " + ip);
            if (!loadPlans.keySet().equals(unloadPlans.keySet()))
                throw new IllegalArgumentException("Subblock " + loadPlans.keySet() + " is not equal to " + unloadPlans.keySet());


            Map<VesselPeriod, Integer> containersFromJq = new HashMap<>();
            for (Subblock k : loadPlans.keySet()) {
                Schedule loadSchedule = loadPlans.get(k);
                int relativeLoadTimeForIp = instance.getRelativeTimeStepWithinPeriod(loadSchedule.time, ip);
                if (relativeLoadTimeForIp < ip.getRelativeExpectedIntervalStart() - auxiliaryEarliness.get(ip)
                        || relativeLoadTimeForIp >= ip.getRelativeExpectedIntervalEnd() + auxiliaryTardiness.get(ip))
                    throw new IllegalArgumentException("Subblock " + k + " has load schedule at " + relativeLoadTimeForIp + " but supposed to be within " + ip.getExpectedInterval());
                if (relativeLoadTimeForIp < ip.getRelativeFeasibleIntervalStart() || relativeLoadTimeForIp >= ip.getRelativeFeasibleIntervalEnd())
                    throw new IllegalArgumentException("Subblock " + k + " has load schedule at " + relativeLoadTimeForIp + " but supposed to be within " + ip.getFeasibleInterval());


                Map<VesselPeriod, Schedule> unloadSchedules = unloadPlans.get(k);

                if (!sourceVesselPeriods.containsAll(unloadSchedules.keySet()))
                    throw new IllegalArgumentException("VesselPeriod " + unloadSchedules.keySet() + " is not assigned to " + k);

                int containersUnloadToK = 0;
                for (Map.Entry<VesselPeriod, Schedule> entry : unloadSchedules.entrySet()) {
                    VesselPeriod jq = entry.getKey();
                    Schedule schedule = entry.getValue();
                    containersUnloadToK += schedule.number;
                    containersFromJq.compute(jq, (vp, n) -> n == null ? schedule.number : n + schedule.number);

                    int relativeUnloadTimeForIp = instance.getRelativeTimeStepWithinPeriod(schedule.time, ip);
                    if (relativeUnloadTimeForIp < 0 || relativeUnloadTimeForIp >= ip.getLengthOfPeriod())
                        throw new IllegalArgumentException("Subblock " + k + " has unload schedule at " + relativeUnloadTimeForIp + " but supposed to be within " + ip.getLengthOfPeriod());
                    if (relativeUnloadTimeForIp >= relativeLoadTimeForIp)
                        throw new IllegalArgumentException("Subblock " + k + " has unload schedule at " + relativeUnloadTimeForIp + " but supposed to be before load schedule at " + relativeLoadTimeForIp);


                    int relativeUnloadTimeForJq = instance.getRelativeTimeStepWithinPeriod(schedule.time, jq);
                    if (relativeUnloadTimeForJq < jq.getRelativeExpectedIntervalStart() - auxiliaryEarliness.get(jq)
                            || relativeUnloadTimeForJq >= jq.getRelativeExpectedIntervalEnd() + auxiliaryTardiness.get(jq))
                        throw new IllegalArgumentException("Subblock " + k + " has unload schedule at " + relativeUnloadTimeForJq + " but supposed to be within " + jq.getExpectedInterval());
                    if (relativeUnloadTimeForJq < jq.getRelativeFeasibleIntervalStart() || relativeUnloadTimeForJq >= jq.getRelativeFeasibleIntervalEnd())
                        throw new IllegalArgumentException("Subblock " + k + " has unload schedule at " + relativeUnloadTimeForJq + " but supposed to be within " + jq.getFeasibleInterval());
                }
                if (containersUnloadToK > instance.spaceCapacity)
                    throw new IllegalArgumentException("Subblock " + k + " has " + containersUnloadToK + " containers but supposed to be " + instance.spaceCapacity);
                if (containersUnloadToK != loadSchedule.number)
                    throw new IllegalArgumentException("Subblock " + k + " has " + loadSchedule.number + " containers but supposed to be " + containersUnloadToK);


            }
            for (Map.Entry<VesselPeriod, Integer> entry : containersFromJq.entrySet()) {
                VesselPeriod jq = entry.getKey();
                int containers = entry.getValue();
                if (containers != instance.getTransshipmentTo(jq, ip))
                    throw new IllegalArgumentException("Transshipment from " + jq + " to " + ip + " is " + containers + " but supposed to be " + instance.getTransshipmentTo(jq, ip));
            }
        }

        Map<VesselPeriod, Integer> earliestRelativeLoadTime = new HashMap<>();
        Map<VesselPeriod, Integer> latestRelativeUnloadTime = new HashMap<>();
        unloadSchedules.forEach((ip, ipMap) -> {
            ipMap.forEach((k, kMap) -> {
                kMap.forEach((jq, schedule) -> {
                    int relativeUnloadTimeForJq = instance.getRelativeTimeStepWithinPeriod(schedule.time, jq);
                    latestRelativeUnloadTime.compute(jq, (vp, t) ->
                            t == null ? relativeUnloadTimeForJq : Math.max(t, relativeUnloadTimeForJq));
                });
            });
        });

        loadSchedules.forEach((ip, ipMap) -> {
            ipMap.forEach((k, schedule) -> {
                int relativeLoadTimeForIp = instance.getRelativeTimeStepWithinPeriod(schedule.time, ip);
                earliestRelativeLoadTime.compute(ip, (vp, t) ->
                        t == null ? relativeLoadTimeForIp : Math.min(t, relativeLoadTimeForIp));

            });
        });


        for (VesselPeriod ip : instance.getVesselPeriods()) {
            if (!earliestRelativeLoadTime.containsKey(ip) || !latestRelativeUnloadTime.containsKey(ip))
                continue;
            int load = earliestRelativeLoadTime.get(ip);
            int unload = latestRelativeUnloadTime.get(ip);
            if (unload >= load)
                throw new IllegalArgumentException("VesselPeriod " + ip + " has load schedule at " + load + " and unload schedule at " + unload);
        }


    }

    public void write(String dir) {
        try {
            Path dirPath = Files.createDirectories(Paths.get(dir));
            if (!Files.isWritable(dirPath)) {
                throw new AccessDeniedException("Directory not writable: " + dir);
            }

            for (CsvFile file : CsvFile.values()) {
                file.handler.write(this, dirPath);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    private void readOverallSolution(CSVReader cr) throws IOException {
        cr.readLine();
        objAll = cr.getDoubleAt(0);
        objRoute = cr.getDoubleAt(1);
        objTime = cr.getDoubleAt(2);
        objCongestion = cr.getDoubleAt(3);
        gap = cr.getDoubleAt(4);
        runningTime = cr.getDoubleAt(5);
        solverName = cr.getValueAt(6, CSVReader.parseString);
        solverStartTime = LocalDateTime.parse(cr.getValueAt(7, CSVReader.parseString), DATE_TIME_FORMATTER);

    }

    private void writeOverallSolution(CSVWriter csv) throws IOException {
        csv.writeLine(
                objAll, objRoute, objTime, objCongestion,
                gap, runningTime, solverName, solverStartTime.format(DATE_TIME_FORMATTER)
        );
    }


    public void writeSubblockSolution(CSVWriter csv) throws IOException {
        for (VesselPeriod ip : instance.getVesselPeriods())
            csv.writeLine(ip.getVpId(), subblockAssignments.get(ip).stream()
                    .map(Subblock::getId).collect(Collectors.toList()));
    }

    public void readSubblockSolution(CSVReader cr) throws IOException {
        while (cr.readLine() != null) {
            VesselPeriod ip = instance.getVesselPeriod(cr.getIntegerAt(0));
            cr.getValueAt(1, CSVReader.parseIntArray).stream()
                    .map(instance::getSubblock)
                    .forEach(k -> setSubBlock(ip, k));
        }

    }


    public void writeArrivalDeviation(CSVWriter csv) throws IOException {
        for (VesselPeriod ip : instance.getVesselPeriods())
            csv.writeLine(ip.getVpId(), auxiliaryEarliness.get(ip), auxiliaryTardiness.get(ip));
    }

    public void readArrivalDeviation(CSVReader cr) throws IOException {
        while (cr.readLine() != null) {
            VesselPeriod ip = instance.getVesselPeriod(cr.getIntegerAt(0));
//            setArrivalAdvance(ip, cr.getIntegerAt(1));
//            setArrivalDelay(ip, cr.getIntegerAt(2));
        }
    }

    public void writeUnloadSchedule(CSVWriter bw) throws IOException {
        unloadSchedules.forEach((ip, ipMap) -> {
            ipMap.forEach((k, jqMap) -> {
                for (Map.Entry<VesselPeriod, Schedule> entry : jqMap.entrySet()) {
                    VesselPeriod jq = entry.getKey();
                    Schedule schedule = entry.getValue();
                    bw.writeLine(jq.getVpId(), ip.getVpId(), schedule.time, k.getId(), schedule.number);
                }
            });
        });
    }

    public void readUnloadSchedule(CSVReader cr) throws IOException {
        while (cr.readLine() != null) {
            VesselPeriod jq = instance.getVesselPeriod(cr.getIntegerAt(0));
            VesselPeriod ip = instance.getVesselPeriod(cr.getIntegerAt(1));
            int time = cr.getIntegerAt(2);
            Subblock subBlock = instance.getSubblock(cr.getIntegerAt(3));
            int number = cr.getIntegerAt(4);
            setUnloadSchedule(jq, ip, subBlock, time, number);
        }
    }

    public void writeLoadSchedule(CSVWriter bw) throws IOException {
        loadSchedules.forEach((ip, ipMap) -> {
            ipMap.forEach((k, schedule) -> {
                bw.writeLine(ip.getVpId(), schedule.time, k.getId(), schedule.number);
            });
        });
    }

    public void readLoadSchedule(CSVReader cr) throws IOException {
        while (cr.readLine() != null) {
            VesselPeriod ip = instance.getVesselPeriod(cr.getIntegerAt(0));
            int time = cr.getIntegerAt(1);
            Subblock subBlock = instance.getSubblock(cr.getIntegerAt(2));
            int number = cr.getIntegerAt(3);
            setLoadSchedule(ip, subBlock, time, number);
        }
    }

    public double getObjAll() {
        return objAll;
    }

    public double getObjRoute() {
        return objRoute;
    }

    public double getObjTime() {
        return objTime;
    }

    public double getObjCongestion() {
        return objCongestion;
    }

    public String briefObjectives() {
        return String.format("obj=%.10f [route=%.2f, time=%.2f, congestion=%.2f]", objAll, objRoute, objTime, objCongestion);
    }


    public void summary() {
        summarySubblock();
        summarySchedule();

    }

//    // 将 TemporarySolution 转换为 SolutionData
//    public SolutionData toData() {
//        SolutionData data = new SolutionData();
//        data.objAll = this.objAll;
//        data.objRoute = this.objRoute;
//        data.objTime = this.objTime;
//        data.objCongestion = this.objCongestion;
//        data.gap = this.gap;
//        data.time = this.time;
//        data.solverName = this.solverName;
//        data.solverStartTime = this.solverStartTime;
//
//        // 转换 subblockAssignments
//        data.subblockAssignments = new LinkedHashMap<>();
//        for (Map.Entry<VesselPeriod, List<Subblock>> entry : subblockAssignments.entrySet()) {
//            VesselPeriod ip = entry.getKey();
//            List<Integer> subblockIds = entry.getValue().stream()
//                    .map(Subblock::getId)
//                    .toList();
//            data.subblockAssignments.put(ip.getVpId(), subblockIds);
//        }
//
//        // 转换 arrivalAdvance 和 arrivalDelay
//        data.arrivalAdvance = new LinkedHashMap<>();
//        data.arrivalDelay = new LinkedHashMap<>();
//        for (VesselPeriod ip : instance.getVesselPeriods()) {
//            data.arrivalAdvance.put(ip.getVpId(), arrivalAdvance.get(ip));
//            data.arrivalDelay.put(ip.getVpId(), arrivalDelay.get(ip));
//        }
//
//        return data;
//    }

    // 注意：fromData 需要 Instance 上下文，建议放在 TemporarySolution 的静态工厂方法中实现

    @FunctionalInterface
    public interface TriConsumer<T, U, V> {
        void accept(T t, U u, V v);
    }

    @FunctionalInterface
    public interface QuadConsumer<T, U, V, W> {
        void accept(T t, U u, V v, W w);
    }

    /**
     * 遍历 unloadSchedules 的四层结构
     *
     * @param consumer 接收四个参数 (ip, k, jq, schedule)
     */
    public void forEachUnloadSchedule(QuadConsumer<VesselPeriod, Subblock, VesselPeriod, Schedule> consumer) {
        if (unloadSchedules == null || consumer == null) return;

        unloadSchedules.forEach((ip, ipMap) -> {
            if (ipMap == null) return;
            ipMap.forEach((k, kMap) -> {
                if (kMap == null) return;
                kMap.forEach((jq, schedule) -> {
                    consumer.accept(ip, k, jq, schedule);
                });
            });
        });
    }

    /**
     * 遍历 loadSchedules 的三层结构
     *
     * @param consumer 接收三个参数 (ip, k, schedule)
     */
    public void forEachLoadSchedule(TriConsumer<VesselPeriod, Subblock, Schedule> consumer) {
        if (loadSchedules == null || consumer == null) return;

        loadSchedules.forEach((ip, ipMap) -> {
            if (ipMap == null) return;
            ipMap.forEach((k, schedule) -> {
                consumer.accept(ip, k, schedule);
            });
        });
    }

    public IndexBasedSolution toIndexBasedSolution() {
        IndexBasedSolution result = new IndexBasedSolution(this.instance);

        for (VesselPeriod ip : instance.getVesselPeriods()) {
            Map<Subblock, Integer> subblockPositionIndex = new HashMap<>();
            for (Subblock k : subblockAssignments.getOrDefault(ip, Collections.emptyList())) {
                int m = subblockPositionIndex.size();
                result.setMthSubblock(ip, m, k);
                subblockPositionIndex.put(k, m);
            }

            loadSchedules.getOrDefault(ip, Collections.emptyMap()).forEach((k, schedule) ->
                    result.setMthLoadingPlan(ip, subblockPositionIndex.get(k), schedule.time, schedule.number));

            unloadSchedules.getOrDefault(ip, Collections.emptyMap()).forEach((k, kMap) -> {
                int m = subblockPositionIndex.get(k);
                kMap.forEach((jq, schedule) -> {
                    result.setMthSourceUnloadingTime(ip, m, jq, schedule.time, schedule.number);
                });
            });
        }

        result.build();
        return result;
    }

    public static void main(String[] args) {
        Instance instance = Instance.readJson("input/instance_{04-01-01}_{06-02}_01.json");
        Solution solution = new Solution(instance);
        solution.read("output/solution_{04-01-01}_{06-02}_01_localRefinement_20250831T120738783");
        solution.calculateObjectives();
        solution.validate();
    }
}