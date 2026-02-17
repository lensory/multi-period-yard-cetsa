package dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class SolutionData {
    @JsonProperty("objAll")
    public double objAll;

    @JsonProperty("objRoute")
    public double objRoute;

    @JsonProperty("objTime")
    public double objTime;

    @JsonProperty("objCongestion")
    public double objCongestion;

    @JsonProperty("gap")
    public double gap;

    @JsonProperty("runningTime")
    public double runningTime;

    @JsonProperty("solverName")
    public String solverName;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @JsonProperty("solverStartTime")
    public LocalDateTime solverStartTime;

    @JsonProperty("turnaroundInfos")
    public List<TurnaroundInfo> turnaroundInfos;

    @JsonProperty("scheduleInfos")
    public List<ScheduleInfo> scheduleInfos;

    public SolutionData() {
    }

    @Override
    public final boolean equals(Object o) {
        if (!(o instanceof SolutionData that)) return false;

        return Double.compare(objAll, that.objAll) == 0 && Double.compare(objRoute, that.objRoute) == 0 && Double.compare(objTime, that.objTime) == 0 && Double.compare(objCongestion, that.objCongestion) == 0 && Double.compare(gap, that.gap) == 0 && Double.compare(runningTime, that.runningTime) == 0 && solverName.equals(that.solverName) && solverStartTime.equals(that.solverStartTime) && turnaroundInfos.equals(that.turnaroundInfos) && scheduleInfos.equals(that.scheduleInfos);
    }

    @Override
    public int hashCode() {
        int result = Double.hashCode(objAll);
        result = 31 * result + Double.hashCode(objRoute);
        result = 31 * result + Double.hashCode(objTime);
        result = 31 * result + Double.hashCode(objCongestion);
        result = 31 * result + Double.hashCode(gap);
        result = 31 * result + Double.hashCode(runningTime);
        result = 31 * result + solverName.hashCode();
        result = 31 * result + solverStartTime.hashCode();
        result = 31 * result + turnaroundInfos.hashCode();
        result = 31 * result + scheduleInfos.hashCode();
        return result;
    }
}