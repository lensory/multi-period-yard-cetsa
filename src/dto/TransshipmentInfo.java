package dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class TransshipmentInfo {
    @JsonProperty("srcVpId")
    public int srcVpId;

    @JsonProperty("dstVpId")
    public int dstVpId;

    @JsonProperty("containers")
    public int containers;

    public TransshipmentInfo() {
    }

    @Override
    public final boolean equals(Object o) {
        if (!(o instanceof TransshipmentInfo that)) return false;
        return srcVpId == that.srcVpId && dstVpId == that.dstVpId && containers == that.containers;
    }

    @Override
    public int hashCode() {
        int result = srcVpId;
        result = 31 * result + dstVpId;
        result = 31 * result + containers;
        return result;
    }
}
