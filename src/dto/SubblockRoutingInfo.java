package dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class SubblockRoutingInfo {
    @JsonProperty("vpId")
    public int vpId;

    @JsonProperty("subblockId")
    public int subblockId;

    @JsonProperty("distanceToSubblock")
    public double distanceTo;

    @JsonProperty("distanceFromSubblock")
    public double distanceFrom;

    @JsonProperty("routeToSubblock")
    public List<Integer> routeTo;

    @JsonProperty("routeFromSubblock")
    public List<Integer> routeFrom;

    public SubblockRoutingInfo() {
    }

    @Override
    public final boolean equals(Object o) {
        if (!(o instanceof SubblockRoutingInfo that)) return false;

        return vpId == that.vpId
                && subblockId == that.subblockId
                && Double.compare(distanceTo, that.distanceTo) == 0
                && Double.compare(distanceFrom, that.distanceFrom) == 0
                && routeTo.equals(that.routeTo)
                && routeFrom.equals(that.routeFrom);
    }

    @Override
    public int hashCode() {
        int result = vpId;
        result = 31 * result + subblockId;
        result = 31 * result + Double.hashCode(distanceTo);
        result = 31 * result + Double.hashCode(distanceFrom);
        result = 31 * result + routeTo.hashCode();
        result = 31 * result + routeFrom.hashCode();
        return result;
    }
}
