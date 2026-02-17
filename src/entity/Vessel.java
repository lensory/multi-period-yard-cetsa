package entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dto.VesselData;

import java.util.ArrayList;
import java.util.Arrays;

public class Vessel {
    public static class Builder {

        private int vid;
        private int horizon;
        private String type;
        private int idleTimeSteps;
        private int lengthOfPeriod;
        private int relativeFeasibleIntervalStart;
        private int relativeFeasibleIntervalDuration;
        private int relativeExpectedIntervalStart;
        private int relativeExpectedIntervalDuration;

        public Builder(int vid) {
            this.vid = vid;
        }

        public Builder setVesselType(String type) {
            this.type = type;
            return this;
        }

        public Builder setPeriodicity(int horizon, int lengthOfPeriod, int idleTimeSteps) {
            this.horizon = horizon;
            this.idleTimeSteps = idleTimeSteps;
            this.lengthOfPeriod = lengthOfPeriod;
            return this;
        }

        public Builder setRelativeFeasibleInterval(int relativeFeasibleIntervalStart, int relativeFeasibleIntervalDuration) {
            this.relativeFeasibleIntervalStart = relativeFeasibleIntervalStart;
            this.relativeFeasibleIntervalDuration = relativeFeasibleIntervalDuration;
            return this;
        }

        public Builder setRelativeExpectedInterval(int relativeExpectedIntervalStart, int relativeExpectedIntervalDuration) {
            this.relativeExpectedIntervalStart = relativeExpectedIntervalStart;
            this.relativeExpectedIntervalDuration = relativeExpectedIntervalDuration;
            return this;
        }

        public Vessel build() {
            if (lengthOfPeriod <= 0) {
                throw new IllegalStateException("lengthOfPeriod must be positive");
            }
            return new Vessel(this);
        }
    }

    private final int vid;
    private final String type;
    private final int horizon;
    private final int lengthOfPeriod;
    private final int idleTimeSteps;
    private final int relativeFeasibleIntervalStart;
    private final int relativeFeasibleIntervalDuration;
    private final int relativeExpectedIntervalDuration;
    private final int relativeExpectedIntervalStart;
    public final VesselPeriod[] periods;

    @JsonCreator
    public Vessel(@JsonProperty("vid") int vid,
                  @JsonProperty("type") String type,
                  @JsonProperty("horizon") int horizon,
                  @JsonProperty("lengthOfPeriod") int lengthOfPeriod,
                  @JsonProperty("idleTimeSteps") int idleTimeSteps,
                  @JsonProperty("relativeFeasibleIntervalStart") int relativeFeasibleIntervalStart,
                  @JsonProperty("relativeFeasibleIntervalDuration") int relativeFeasibleIntervalDuration,
                  @JsonProperty("relativeExpectedIntervalStart") int relativeExpectedIntervalStart,
                  @JsonProperty("relativeExpectedIntervalDuration") int relativeExpectedIntervalDuration) {
        validateIdleTimeSteps(idleTimeSteps, lengthOfPeriod);
        validateInterval("Feasible", relativeFeasibleIntervalStart,
                relativeFeasibleIntervalDuration, lengthOfPeriod);
        validateInterval("Expected", relativeExpectedIntervalStart,
                relativeExpectedIntervalDuration, lengthOfPeriod);

        this.vid = vid;
        this.type = type;
        this.horizon = horizon;
        this.lengthOfPeriod = lengthOfPeriod;
        this.idleTimeSteps = idleTimeSteps;
        this.relativeFeasibleIntervalStart = relativeFeasibleIntervalStart;
        this.relativeFeasibleIntervalDuration = relativeFeasibleIntervalDuration;
        this.relativeExpectedIntervalStart = relativeExpectedIntervalStart;
        this.relativeExpectedIntervalDuration = relativeExpectedIntervalDuration;
        if (horizon % lengthOfPeriod != 0)
            throw new IllegalArgumentException("Horizon must be a multiple of lengthOfPeriod");
        int numberOfPeriods = horizon / lengthOfPeriod;
        periods = new VesselPeriod[numberOfPeriods];
    }

    public Vessel(Builder builder) {
        this(builder.vid, builder.type, builder.horizon, builder.lengthOfPeriod, builder.idleTimeSteps,
                builder.relativeFeasibleIntervalStart, builder.relativeFeasibleIntervalDuration,
                builder.relativeExpectedIntervalStart, builder.relativeExpectedIntervalDuration);
    }

    private void validateIdleTimeSteps(int idle, int lengthOfPeriod) {
        if (idle < 0 || idle >= lengthOfPeriod) {
            throw new IllegalArgumentException(String.format(
                    "Invalid idleTimeSteps: %d (0 <= idle < %d)",
                    idle, lengthOfPeriod));
        }
    }

    private void validateInterval(String type, int start, int length, int lengthOfPeriod) {
        if (start < 0 || start >= lengthOfPeriod) {
            throw new IllegalArgumentException(String.format(
                    "%s start invalid: %d (0 <= start < %d)",
                    type, start, lengthOfPeriod));
        }
        if (length > lengthOfPeriod) {
            throw new IllegalArgumentException(String.format(
                    "%s length exceeds period: %d > %d",
                    type, length, lengthOfPeriod));
        }
    }

    public void addVesselPeriod(VesselPeriod vesselPeriod) {
        if (vesselPeriod.getVid() != this.vid)
            throw new IllegalArgumentException(
                    "VesselPeriod(vpId=" + vesselPeriod.getVpId()
                            + " [vId=" + vesselPeriod.getVid()
                            + ", pId=" + vesselPeriod.getPid()
                            + "]) attempts to associate with Vessel(vId=" + this.vid
                            + ").\nViolates: VesselPeriod must strictly belong to its designated Vessel ("
                            + vesselPeriod.getVid() + " → " + this.vid + ")"
            );
        int pId = vesselPeriod.getPid();
        if (pId < 0 || pId >= periods.length)
            throw new IllegalArgumentException("Invalid insertion order. Expected PId: " + pId
                    + ", Actual PId: " + vesselPeriod.getPid());
        if (periods[pId] != null)
            throw new IllegalArgumentException("VesselPeriod(vpId=" + vesselPeriod.getVpId()
                    + " [vId=" + vesselPeriod.getVid()
                    + ", pId=" + vesselPeriod.getPid()
                    + "]) already exists in Vessel(vId=" + this.vid
                    + ").\nViolates: VesselPeriod must be unique"
            );

        periods[pId] = vesselPeriod;
    }


    public int getVid() {
        return vid;
    }

    public String getType() {
        return type;
    }


    public int getLengthOfPeriod() {
        return lengthOfPeriod;
    }

    public int getHorizon() {
        return horizon;
    }


    public int getIdleTimeSteps() {
        return idleTimeSteps;
    }

    public int getRelativeFeasibleIntervalStart() {
        return this.relativeFeasibleIntervalStart;
    }

    public int getRelativeFeasibleIntervalDuration() {
        return relativeFeasibleIntervalDuration;
    }

    public int getRelativeExpectedIntervalStart() {
        return relativeExpectedIntervalStart;
    }

    public int getRelativeExpectedIntervalDuration() {
        return relativeExpectedIntervalDuration;
    }

    public VesselPeriod getPeriod(int pId) {
        return periods[pId];
    }

    public ArrayList<VesselPeriod> getPeriods() {
        return new ArrayList<>(Arrays.asList(periods));
    }

    @Override
    public String toString() {
        return "Vessel " + vid;
    }

    public String summary() {
        return String.format("Vessel %d: horizon=%d, cycle=%d, idle=%d, " +
                        "feasible=[%d,%d), expected=[%d,%d)",
                vid, horizon, lengthOfPeriod, idleTimeSteps,
                relativeFeasibleIntervalStart, relativeFeasibleIntervalStart + relativeFeasibleIntervalDuration,
                relativeExpectedIntervalStart, relativeExpectedIntervalStart + relativeExpectedIntervalDuration);
    }

    // 将 Vessel 转换为 VesselData
    public VesselData toData() {
        VesselData data = new VesselData();
        data.vId = this.vid;
        data.type = this.type;
//        data.horizon = this.horizon;
        data.lengthOfPeriod = this.lengthOfPeriod;
        data.idleTimeSteps = this.idleTimeSteps;
        data.relativeFeasibleIntervalStart = this.relativeFeasibleIntervalStart;
        data.relativeFeasibleIntervalDuration = this.relativeFeasibleIntervalDuration;
        data.relativeExpectedIntervalStart = this.relativeExpectedIntervalStart;
        data.relativeExpectedIntervalDuration = this.relativeExpectedIntervalDuration;
        data.vpIds = this.getPeriods().stream().map(VesselPeriod::getVpId).toList();
        return data;
    }

    // 从 VesselData 构造 Vessel
    public static Vessel fromData(VesselData data, int horizon) {

        return new Vessel(data.vId, data.type, horizon, data.lengthOfPeriod, data.idleTimeSteps,
                data.relativeFeasibleIntervalStart, data.relativeFeasibleIntervalDuration,
                data.relativeExpectedIntervalStart, data.relativeExpectedIntervalDuration);
    }

    @Override
    public final boolean equals(Object o) {
        if (!(o instanceof Vessel that)) return false;

        return vid == that.vid;
    }

    @Override
    public int hashCode() {
        return vid;
    }
}
