package dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class SubblockData {
    @JsonProperty("id")
    public int id;

    @JsonProperty("blockId")
    public int blockId;

    @JsonProperty("rowId")
    public int rowId;

    @JsonProperty("colId")
    public int colId;

    @JsonProperty("slotId")
    public int slotId;

    @JsonProperty("hLaneId")
    public int hLaneId;

    public SubblockData() {
    }

    @Override
    public final boolean equals(Object o) {
        if (!(o instanceof SubblockData that)) return false;

        return id == that.id && blockId == that.blockId && rowId == that.rowId && colId == that.colId && slotId == that.slotId && hLaneId == that.hLaneId;
    }

    @Override
    public int hashCode() {
        int result = id;
        result = 31 * result + blockId;
        result = 31 * result + rowId;
        result = 31 * result + colId;
        result = 31 * result + slotId;
        result = 31 * result + hLaneId;
        return result;
    }
}
