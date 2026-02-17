package entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dto.SubblockData;

public class Subblock {

    public final int id;

    public final int blockId;
    public final int rowId;
    public final int colId;
    public final int slotId;
    public final int hLaneId;

    @JsonCreator
    public Subblock(@JsonProperty("id") int id,
                    @JsonProperty("blockId") int blockId,
                    @JsonProperty("rowId") int rowId,
                    @JsonProperty("colId") int colId,
                    @JsonProperty("slotId") int slotId,
                    @JsonProperty("hLaneId") int hLaneId) {
        this.id = id;
        this.blockId = blockId;
        this.rowId = rowId;
        this.colId = colId;
        this.slotId = slotId;
        this.hLaneId = hLaneId;
    }

//    public Subblock(int id, int i, int j, int k) {
//        this.id = id;
//        this.rowId = i;
//        this.colId = j;
//        this.slotId = k;
//        this.hLaneId = (i + 1) / 2;
//    }

    @Override
    public String toString() {
        return "K" + id;
    }

    public boolean isInSameBlock(Subblock that) {
        return this.rowId == that.rowId && this.colId == that.colId;
    }

    // 将 Subblock 转换为 SubblockData
    public SubblockData toData() {
        SubblockData data = new SubblockData();
        data.id = this.id;
        data.blockId = this.blockId;
        data.rowId = this.rowId;
        data.colId = this.colId;
        data.slotId = this.slotId;
        data.hLaneId = this.hLaneId;
        return data;
    }

    // 从 SubblockData 构造 Subblock
    public static Subblock fromData(SubblockData data) {
        return new Subblock(data.id, data.blockId, data.rowId, data.colId, data.slotId, data.hLaneId);
    }

    public boolean isNeighborInSameBlock(Subblock that) {
        return this.colId == that.colId && this.rowId == that.rowId && Math.abs(this.slotId - that.slotId) == 1;
    }

    public boolean isNeighborAcrossLane(Subblock that) {
        return this.colId == that.colId && this.slotId == that.slotId && this.hLaneId == that.hLaneId;
    }

    public int getId() {
        return id;
    }

    @Override
    public final boolean equals(Object o) {
        if (!(o instanceof Subblock)) return false;

        Subblock subblock = (Subblock) o;
        return id == subblock.id;
    }

    @Override
    public int hashCode() {
        return id;
    }
}
