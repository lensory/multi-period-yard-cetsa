package dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Set;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class InstanceData {
    @JsonProperty("name")
    public String name;

    @JsonProperty("ETA_CONGESTION")
    public double etaCongestion;

    @JsonProperty("ETA_ROUTE")
    public double etaRoute;

    @JsonProperty("SPACE_CAPACITY")
    public int spaceCapacity;

    @JsonProperty("MAX_UNLOAD_FLOWS")
    public int maxUnloadFlows;

    @JsonProperty("MAX_LOAD_FLOWS")
    public int maxLoadFlows;

    @JsonProperty("horizon")
    public int horizon;

    @JsonProperty("extension")
    public int extension;

    @JsonProperty("rows")
    public int rows;

    @JsonProperty("cols")
    public int cols;

    @JsonProperty("roads")
    public int roads;

    @JsonProperty("vessels")
    public List<VesselData> vessels;

    @JsonProperty("vesselPeriods")
    public List<VesselPeriodData> vesselPeriods;

    @JsonProperty("subblocks")
    public List<SubblockData> subblocks;

    @JsonProperty("routingInfos")
    public Set<SubblockRoutingInfo> routingInfos;

    @JsonProperty("transshipmentInfos")
    public Set<TransshipmentInfo> transshipmentInfos;

    public InstanceData() {
    }


    @Override
    public final boolean equals(Object o) {
        if (!(o instanceof InstanceData that)) return false;

        return Double.compare(etaCongestion, that.etaCongestion) == 0
                && Double.compare(etaRoute, that.etaRoute) == 0
                && spaceCapacity == that.spaceCapacity
                && maxUnloadFlows == that.maxUnloadFlows
                && maxLoadFlows == that.maxLoadFlows
                && horizon == that.horizon
                && extension == that.extension
                && rows == that.rows
                && cols == that.cols
                && roads == that.roads
                && name.equals(that.name)
                && vessels.equals(that.vessels)
                && vesselPeriods.equals(that.vesselPeriods)
                && subblocks.equals(that.subblocks)
                && routingInfos.equals(that.routingInfos)
                && transshipmentInfos.equals(that.transshipmentInfos);
    }

    @Override
    public int hashCode() {
        int result = name.hashCode();
        result = 31 * result + Double.hashCode(etaCongestion);
        result = 31 * result + Double.hashCode(etaRoute);
        result = 31 * result + spaceCapacity;
        result = 31 * result + maxUnloadFlows;
        result = 31 * result + maxLoadFlows;
        result = 31 * result + horizon;
        result = 31 * result + extension;
        result = 31 * result + rows;
        result = 31 * result + cols;
        result = 31 * result + roads;
        result = 31 * result + vessels.hashCode();
        result = 31 * result + vesselPeriods.hashCode();
        result = 31 * result + subblocks.hashCode();
        result = 31 * result + routingInfos.hashCode();
        result = 31 * result + transshipmentInfos.hashCode();
        return result;
    }
}
