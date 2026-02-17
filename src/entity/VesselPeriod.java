package entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import dto.VesselPeriodData;

public class VesselPeriod {

    public final int vid;
    public final int pid;

    public final int vpId;
    private final String type;

    private final int horizon;
    private final int absolutePeriodStart;
    private final int absolutePeriodDuration;
    public final int relativeFeasibleIntervalStart;
    public final int relativeExpectedIntervalStart;
    public final int relativeFeasibleIntervalDuration;
    public final int relativeExpectedIntervalDuration;


    public final double berthPosition;
    public final double earlinessCost;
    public final double tardinessCost;

    private final CyclicClosedOpenInterval feasibleInterval;
    private final CyclicClosedOpenInterval expectedInterval;
    private final CyclicClosedOpenInterval periodInterval;

    //    // Transhipment
//    private final HashMap<VesselPeriod, Integer> transshipTo;
//    private final HashMap<VesselPeriod, Integer> transshipFrom;
    public int totalUnloadContainers;
    public int totalLoadContainers;
    public int totalUnloadFlow;
    public int totalLoadFlow;

    public static class Builder {
        private int vid;
        private int pid;
        private int vpId;
        private String type;

        private int horizon;
        private int absolutePeriodStart;
        private int absolutePeriodDuration;

        private int relativeFeasibleIntervalStart;
        private int relativeFeasibleIntervalDuration;
        private int relativeExpectedIntervalStart;
        private int relativeExpectedIntervalDuration;

        private double berthPosition;
        private double earlinessCost;
        private double tardinessCost;

        public Builder(int vpId) {
            this.vpId = vpId;
        }

        public Builder setParentVessel(Vessel vessel, int pid) {
            this.vid = vessel.getVid();
            this.pid = pid;
            this.type = vessel.getType();
            this.horizon = vessel.getHorizon();
            this.absolutePeriodStart = (vessel.getIdleTimeSteps() + pid * vessel.getLengthOfPeriod()) % vessel.getHorizon();
            this.absolutePeriodDuration = vessel.getLengthOfPeriod();

            this.relativeFeasibleIntervalStart = vessel.getRelativeFeasibleIntervalStart();
            this.relativeFeasibleIntervalDuration = vessel.getRelativeFeasibleIntervalDuration();
            this.relativeExpectedIntervalStart = vessel.getRelativeExpectedIntervalStart();
            this.relativeExpectedIntervalDuration = vessel.getRelativeExpectedIntervalDuration();
            return this;
        }

        public Builder setBerthPosition(double berthPosition) {
            this.berthPosition = berthPosition;
            return this;
        }

        public Builder setCost(double earlinessCost, double tardinessCost) {
            this.earlinessCost = earlinessCost;
            this.tardinessCost = tardinessCost;
            return this;
        }

        public VesselPeriod build() {
            return new VesselPeriod(this);
        }
    }

    @JsonCreator
    public VesselPeriod(@JsonProperty("vid") int vid,
                        @JsonProperty("pid") int pid,
                        @JsonProperty("vpId") int vpId,
                        @JsonProperty("type") String type,
                        @JsonProperty("horizon") int horizon,
                        @JsonProperty("absolutePeriodStart") int absolutePeriodStart,
                        @JsonProperty("absolutePeriodDuration") int absolutePeriodDuration,
                        @JsonProperty("relativeFeasibleIntervalStart") int relativeFeasibleIntervalStart,
                        @JsonProperty("relativeFeasibleIntervalDuration") int relativeFeasibleIntervalDuration,
                        @JsonProperty("relativeExpectedIntervalStart") int relativeExpectedIntervalStart,
                        @JsonProperty("relativeExpectedIntervalDuration") int relativeExpectedIntervalDuration,
                        @JsonProperty("berthPosition") double berthPosition,
                        @JsonProperty("earlinessCost") double earlinessCost,
                        @JsonProperty("tardinessCost") double tardinessCost) {
        this.vid = vid;
        this.pid = pid;
        this.vpId = vpId;
        this.type = type;
        this.horizon = horizon;
        this.absolutePeriodStart = absolutePeriodStart;
        this.absolutePeriodDuration = absolutePeriodDuration;
        this.relativeFeasibleIntervalStart = relativeFeasibleIntervalStart;
        this.relativeFeasibleIntervalDuration = relativeFeasibleIntervalDuration;
        this.relativeExpectedIntervalStart = relativeExpectedIntervalStart;
        this.relativeExpectedIntervalDuration = relativeExpectedIntervalDuration;
        this.periodInterval = new CyclicClosedOpenInterval(absolutePeriodStart, absolutePeriodDuration);
        int absoluteFeasibleIntervalStart = (absolutePeriodStart + relativeFeasibleIntervalStart) % horizon;
        this.feasibleInterval = new CyclicClosedOpenInterval(absoluteFeasibleIntervalStart, relativeFeasibleIntervalDuration);
        int absoluteExpectedIntervalStart = (absolutePeriodStart + relativeExpectedIntervalStart) % horizon;
        this.expectedInterval = new CyclicClosedOpenInterval(absoluteExpectedIntervalStart, relativeExpectedIntervalDuration);
//        if (berthPosition < 0)
//            throw new IllegalArgumentException("Invalid berth position.");
        this.berthPosition = berthPosition;
        this.earlinessCost = earlinessCost;
        this.tardinessCost = tardinessCost;
    }


    public VesselPeriod(Builder builder) {
        this(builder.vid, builder.pid, builder.vpId, builder.type, builder.horizon,
                builder.absolutePeriodStart, builder.absolutePeriodDuration,
                builder.relativeFeasibleIntervalStart, builder.relativeFeasibleIntervalDuration,
                builder.relativeExpectedIntervalStart, builder.relativeExpectedIntervalDuration,
                builder.berthPosition, builder.earlinessCost, builder.tardinessCost);
    }


    public int getRelativeTimeWithinPeriod(int t, int horizon) {
        if (t >= horizon || t < 0) {
            throw new IllegalArgumentException("Time " + t + " is not within the horizon " + horizon);
        }
        if (t >= periodInterval.getStart()) {
            if (t - periodInterval.getStart() >= periodInterval.getLength())
                throw new IllegalArgumentException("Time " + t + " is not within the interval " + periodInterval);
            return t - periodInterval.getStart();
        } else {
            if (t + horizon - periodInterval.getStart() >= periodInterval.getLength())
                throw new IllegalArgumentException("Time " + t + " is not within the interval " + periodInterval);
            return t + horizon - periodInterval.getStart();
        }

    }

    public int getRelativeExpectedIntervalStart() {
        return this.relativeExpectedIntervalStart;
    }

    public int getRelativeExpectedIntervalEnd() {
        return this.relativeExpectedIntervalStart + this.relativeExpectedIntervalDuration;
    }

    public int getRelativeFeasibleIntervalStart() {
        return this.relativeFeasibleIntervalStart;
    }

    public int getRelativeFeasibleIntervalEnd() {
        return this.relativeFeasibleIntervalStart + this.relativeFeasibleIntervalDuration;
    }


    @JsonIgnore
    public CyclicClosedOpenInterval getFeasibleInterval() {
        return feasibleInterval;
    }

    @JsonIgnore
    public CyclicClosedOpenInterval getExpectedInterval() {
        return expectedInterval;
    }

    @JsonIgnore
    public CyclicClosedOpenInterval getPeriodInterval() {
        return periodInterval;
    }

    public int getHorizon() {
        return horizon;
    }

    public int getAbsolutePeriodStart() {
        return absolutePeriodStart;
    }

    public int getAbsolutePeriodDuration() {
        return absolutePeriodDuration;
    }

    public int getRelativeFeasibleIntervalDuration() {
        return relativeFeasibleIntervalDuration;
    }

    public int getRelativeExpectedIntervalDuration() {
        return relativeExpectedIntervalDuration;
    }

    public double getBerthPosition() {
        return berthPosition;
    }

    public double getEarlinessCost() {
        return earlinessCost;
    }

    public double getTardinessCost() {
        return tardinessCost;
    }

    public int getTotalUnloadContainers() {
        return totalUnloadContainers;
    }

    public int getTotalLoadContainers() {
        return totalLoadContainers;
    }

    public int getTotalUnloadFlow() {
        return totalUnloadFlow;
    }

    public int getTotalLoadFlow() {
        return totalLoadFlow;
    }

    public int getLengthOfPeriod() {
        return this.absolutePeriodDuration;
    }

    public int getVpId() {
        return this.vpId;
    }

    public int getVid() {
        return this.vid;

    }

    public int getPid() {
        return this.pid;
    }

    public String getType() {
        return type;
    }

    @Override
    public String toString() {
        return String.format("VP%d,%d(%d)", vid, pid, vpId);
    }

    public String summary() {
        return this + ": " +
                "period=" + periodInterval + ", " +
                "feasible=" + feasibleInterval + ", " +
                "expected=" + expectedInterval;
    }

    // 将 VesselPeriod 转换为 VesselPeriodData
    public VesselPeriodData toData() {
        VesselPeriodData data = new VesselPeriodData();
        data.vpId = this.vpId;
        data.vid = this.vid;
        data.pid = this.pid;
        data.type = this.type;
        data.absolutePeriodStart = this.absolutePeriodStart;
        data.absolutePeriodDuration = this.absolutePeriodDuration;
        data.relativeFeasibleIntervalStart = this.relativeFeasibleIntervalStart;
        data.relativeFeasibleIntervalDuration = this.relativeFeasibleIntervalDuration;
        data.relativeExpectedIntervalStart = this.relativeExpectedIntervalStart;
        data.relativeExpectedIntervalDuration = this.relativeExpectedIntervalDuration;
        data.berthPosition = this.berthPosition;
        data.earlinessCost = this.earlinessCost;
        data.tardinessCost = this.tardinessCost;
        data.totalUnloadContainers = this.totalUnloadContainers;
        data.totalLoadContainers = this.totalLoadContainers;
        data.totalUnloadFlow = this.totalUnloadFlow;
        data.totalLoadFlow = this.totalLoadFlow;
        return data;
    }

    // 从 VesselPeriodData 构造 VesselPeriod
    public static VesselPeriod fromData(VesselPeriodData data, int horizon) {
        return new VesselPeriod(data.vid, data.pid, data.vpId, data.type, horizon,
                data.absolutePeriodStart, data.absolutePeriodDuration,
                data.relativeFeasibleIntervalStart, data.relativeFeasibleIntervalDuration,
                data.relativeExpectedIntervalStart, data.relativeExpectedIntervalDuration,
                data.berthPosition, data.earlinessCost, data.tardinessCost);
    }


    @Override
    public final boolean equals(Object o) {
        if (!(o instanceof VesselPeriod that)) return false;

        return vpId == that.vpId;
    }

    @Override
    public int hashCode() {
        return vpId;
    }
}
