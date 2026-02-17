package entity;

import java.util.*;

public class SubblockChange {
    public final Subblock oldSubblock;
    public final Subblock newSubblock;

    public SubblockChange(Subblock oldSubblock, Subblock newSubblock) {
        this.oldSubblock = oldSubblock;
        this.newSubblock = newSubblock;
    }

    public boolean isReplace() {
        return oldSubblock != null && newSubblock != null && !oldSubblock.equals(newSubblock);
    }

    public boolean isAdd() {
        return oldSubblock == null && newSubblock != null;
    }

    public boolean isRemove() {
        return oldSubblock != null && newSubblock == null;
    }

    @Override
    public String toString() {
        return "SubblockChange{" + oldSubblock +
                "->" + newSubblock + '}';
    }

    public static Map<VesselPeriod, Map<Integer, SubblockChange>> getChanges(
            Map<VesselPeriod, List<Subblock>> oldAssignmentArray,
            Map<VesselPeriod, Set<Subblock>> newAssignments) {

        Map<VesselPeriod, Map<Integer, SubblockChange>> changes = new HashMap<>();


        Set<VesselPeriod> allVesselPeriods = new HashSet<>(oldAssignmentArray.keySet());
        allVesselPeriods.addAll(newAssignments.keySet());

        for (VesselPeriod ip : allVesselPeriods) {
            List<Subblock> oldList = oldAssignmentArray.getOrDefault(ip, Collections.emptyList());
            Set<Subblock> newSet = newAssignments.getOrDefault(ip, Collections.emptySet());
            if (new HashSet<>(oldList).size() != oldList.size()) {
                throw new IllegalArgumentException("oldList contains duplicate elements");
            }
//            if (oldList.size() != newSet.size()) {
//                throw new IllegalArgumentException("Length mismatch for " + ip);
//            }

            Set<Subblock> common = new TreeSet<>(Comparator.comparing(Subblock::getId));
            common.addAll(oldList);
            common.retainAll(newSet);

            Set<Subblock> removed = new TreeSet<>(Comparator.comparing(Subblock::getId));
            removed.addAll(oldList);
            removed.removeAll(common);

            Set<Subblock> added = new TreeSet<>(Comparator.comparing(Subblock::getId));
            added.addAll(newSet);
            added.removeAll(common);

//            if (removed.size() != added.size()) {
//                throw new IllegalArgumentException("Size mismatch between removed and added");
//            }
            Map<Subblock, Integer> indexMap = new HashMap<>();
            for (int i = 0; i < oldList.size(); i++) {
                indexMap.put(oldList.get(i), i);
            }

            List<Subblock> removedList = new ArrayList<>(removed);
            List<Subblock> addedList = new ArrayList<>(added);

            int minSize = Math.min(removedList.size(), addedList.size());

            // Step 1: Replace
            for (int i = 0; i < minSize; i++) {
                Subblock oldSubblock = removedList.get(i);
                Subblock newSubblock = addedList.get(i);
                int index = indexMap.get(oldSubblock);
                changes.computeIfAbsent(ip, k -> new HashMap<>())
                        .put(index, new SubblockChange(oldSubblock, newSubblock));
            }

            // Step 2: Remove
            for (int i = minSize; i < removedList.size(); i++) {
                Subblock oldSubblock = removedList.get(i);
                int index = indexMap.get(oldSubblock);
                changes.computeIfAbsent(ip, k -> new HashMap<>())
                        .put(index, new SubblockChange(oldSubblock, null));
            }

            // Step 3: Add
            for (int i = minSize; i < addedList.size(); i++) {
                Subblock newSubblock = addedList.get(i);
                changes.computeIfAbsent(ip, k -> new HashMap<>())
                        .put(minSize - i - 1, new SubblockChange(null, newSubblock));
            }

        }
        return changes;
    }
}
