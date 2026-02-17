package dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class TurnaroundInfo {

    @JsonProperty("vpId")
    public int vpId;

    @JsonProperty("subblocks")
    public List<Integer> subblocks;

    @JsonProperty("exceptedSubblockNumber")
    public int exceptedSubblockNumber;

    @JsonProperty("advance")
    public int advance;

    @JsonProperty("delay")
    public int delay;

    public TurnaroundInfo() {
    }

    @Override
    public final boolean equals(Object o) {
        if (!(o instanceof TurnaroundInfo that)) return false;

        return vpId == that.vpId && exceptedSubblockNumber == that.exceptedSubblockNumber && advance == that.advance && delay == that.delay && subblocks.equals(that.subblocks);
    }

    @Override
    public int hashCode() {
        int result = vpId;
        result = 31 * result + subblocks.hashCode();
        result = 31 * result + exceptedSubblockNumber;
        result = 31 * result + advance;
        result = 31 * result + delay;
        return result;
    }
}
