package dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ScheduleInfo {

    @JsonProperty("srcVpId")
    public int srcVpId;

    @JsonProperty("dstVpId")
    public int dstVpId;

    @JsonProperty("subblockId")
    public int subblockId;

    @JsonProperty("unloadTime")
    public int unloadTime;

    @JsonProperty("loadTime")
    public int loadTime;

    @JsonProperty("number")
    public int number;

    public ScheduleInfo() {
    }

    @Override
    public final boolean equals(Object o) {
        if (!(o instanceof ScheduleInfo that)) return false;

        return srcVpId == that.srcVpId && dstVpId == that.dstVpId && subblockId == that.subblockId && unloadTime == that.unloadTime && loadTime == that.loadTime && number == that.number;
    }

    @Override
    public int hashCode() {
        int result = srcVpId;
        result = 31 * result + dstVpId;
        result = 31 * result + subblockId;
        result = 31 * result + unloadTime;
        result = 31 * result + loadTime;
        result = 31 * result + number;
        return result;
    }
}
