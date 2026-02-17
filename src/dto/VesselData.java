package dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class VesselData {
    @JsonProperty("vId")
    public int vId;

    @JsonProperty("type")
    public String type;

    @JsonProperty("lengthOfPeriod")
    public int lengthOfPeriod;

    @JsonProperty("idleTimeSteps")
    public int idleTimeSteps;

    @JsonProperty("relativeFeasibleIntervalStart")
    public int relativeFeasibleIntervalStart;

    @JsonProperty("relativeFeasibleIntervalDuration")
    public int relativeFeasibleIntervalDuration;

    @JsonProperty("relativeExpectedIntervalStart")
    public int relativeExpectedIntervalStart;

    @JsonProperty("relativeExpectedIntervalDuration")
    public int relativeExpectedIntervalDuration;

    @JsonProperty("vpIds")
    public List<Integer> vpIds;

    // 默认构造函数必须存在以支持 Jackson
    public VesselData() {
    }

    @Override
    public final boolean equals(Object o) {
        if (!(o instanceof VesselData that)) return false;

        return vId == that.vId && lengthOfPeriod == that.lengthOfPeriod && idleTimeSteps == that.idleTimeSteps && relativeFeasibleIntervalStart == that.relativeFeasibleIntervalStart && relativeFeasibleIntervalDuration == that.relativeFeasibleIntervalDuration && relativeExpectedIntervalStart == that.relativeExpectedIntervalStart && relativeExpectedIntervalDuration == that.relativeExpectedIntervalDuration && type.equals(that.type) && vpIds.equals(that.vpIds);
    }

    @Override
    public int hashCode() {
        int result = vId;
        result = 31 * result + type.hashCode();
        result = 31 * result + lengthOfPeriod;
        result = 31 * result + idleTimeSteps;
        result = 31 * result + relativeFeasibleIntervalStart;
        result = 31 * result + relativeFeasibleIntervalDuration;
        result = 31 * result + relativeExpectedIntervalStart;
        result = 31 * result + relativeExpectedIntervalDuration;
        result = 31 * result + vpIds.hashCode();
        return result;
    }
}
