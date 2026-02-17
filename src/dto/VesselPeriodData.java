package dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class VesselPeriodData {
    @JsonProperty("vpId")
    public int vpId;

    @JsonProperty("vid")
    public int vid;

    @JsonProperty("pid")
    public int pid;

    @JsonProperty("type")
    public String type;

    @JsonProperty("absolutePeriodStart")
    public int absolutePeriodStart;

    @JsonProperty("absolutePeriodDuration")
    public int absolutePeriodDuration;

    @JsonProperty("relativeFeasibleIntervalStart")
    public int relativeFeasibleIntervalStart;

    @JsonProperty("relativeFeasibleIntervalDuration")
    public int relativeFeasibleIntervalDuration;

    @JsonProperty("relativeExpectedIntervalStart")
    public int relativeExpectedIntervalStart;

    @JsonProperty("relativeExpectedIntervalDuration")
    public int relativeExpectedIntervalDuration;

    @JsonProperty("berthPosition")
    public double berthPosition;

    @JsonProperty("earlinessCost")
    public double earlinessCost;

    @JsonProperty("tardinessCost")
    public double tardinessCost;

    @JsonProperty("totalUnloadContainers")
    public int totalUnloadContainers;

    @JsonProperty("totalLoadContainers")
    public int totalLoadContainers;

    @JsonProperty("totalUnloadFlow")
    public int totalUnloadFlow;

    @JsonProperty("totalLoadFlow")
    public int totalLoadFlow;

    public VesselPeriodData() {
    }

    @Override
    public final boolean equals(Object o) {
        if (!(o instanceof VesselPeriodData that)) return false;

        return vpId == that.vpId && vid == that.vid && pid == that.pid && absolutePeriodStart == that.absolutePeriodStart && absolutePeriodDuration == that.absolutePeriodDuration && relativeFeasibleIntervalStart == that.relativeFeasibleIntervalStart && relativeFeasibleIntervalDuration == that.relativeFeasibleIntervalDuration && relativeExpectedIntervalStart == that.relativeExpectedIntervalStart && relativeExpectedIntervalDuration == that.relativeExpectedIntervalDuration && Double.compare(berthPosition, that.berthPosition) == 0 && Double.compare(earlinessCost, that.earlinessCost) == 0 && Double.compare(tardinessCost, that.tardinessCost) == 0 && totalUnloadContainers == that.totalUnloadContainers && totalLoadContainers == that.totalLoadContainers && totalUnloadFlow == that.totalUnloadFlow && totalLoadFlow == that.totalLoadFlow && type.equals(that.type);
    }

    @Override
    public int hashCode() {
        int result = vpId;
        result = 31 * result + vid;
        result = 31 * result + pid;
        result = 31 * result + type.hashCode();
        result = 31 * result + absolutePeriodStart;
        result = 31 * result + absolutePeriodDuration;
        result = 31 * result + relativeFeasibleIntervalStart;
        result = 31 * result + relativeFeasibleIntervalDuration;
        result = 31 * result + relativeExpectedIntervalStart;
        result = 31 * result + relativeExpectedIntervalDuration;
        result = 31 * result + Double.hashCode(berthPosition);
        result = 31 * result + Double.hashCode(earlinessCost);
        result = 31 * result + Double.hashCode(tardinessCost);
        result = 31 * result + totalUnloadContainers;
        result = 31 * result + totalLoadContainers;
        result = 31 * result + totalUnloadFlow;
        result = 31 * result + totalLoadFlow;
        return result;
    }
}