package util;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class IntervalSet implements Iterable<Integer> {
    private final List<int[]> intervals;

    public IntervalSet() {
        this.intervals = new ArrayList<>();
    }

    public void addInterval(int start, int end) {
        if (start > end) {
            throw new IllegalArgumentException("Start must be less than or equal to end");
        }
        intervals.add(new int[]{start, end});
    }

    @Override
    public Iterator<Integer> iterator() {
        return new IntervalIterator();
    }

    private class IntervalIterator implements Iterator<Integer> {
        private static final int START_INDEX = 0;
        private static final int END_INDEX = 1;

        // The "current" refers to the current value hold by the iterator
        // but next value to be returned in the next call of "next()".
        private int currentIntervalIndex;
        private int currentValue;

        public IntervalIterator() {
            if (!intervals.isEmpty()) {
                currentIntervalIndex = 0;
                currentValue = intervals.get(currentIntervalIndex)[START_INDEX];
            }
        }

        @Override
        public boolean hasNext() {
            return currentIntervalIndex < intervals.size() && currentValue <= intervals.get(currentIntervalIndex)[END_INDEX];
        }

        @Override
        public Integer next() {
            int value = currentValue;
            currentValue++;
            if (currentValue > intervals.get(currentIntervalIndex)[END_INDEX]) {
                currentIntervalIndex++;
                if (currentIntervalIndex < intervals.size()) {
                    currentValue = intervals.get(currentIntervalIndex)[START_INDEX];
                }
            }
            return value;
        }
    }

    // Adding an empty method
    public static IntervalSet empty() {
        return new IntervalSet();
    }

    // Modifying the range method to match IntStream.range behavior
    public static IntervalSet range(int startInclusive, int endExclusive) {
        IntervalSet intervalSet = new IntervalSet();
        if (startInclusive < endExclusive) {
            intervalSet.addInterval(startInclusive, endExclusive - 1);
        }
        return intervalSet;
    }

    // Modifying the rangeClosed method to match IntStream.rangeClosed behavior
    public static IntervalSet rangeClosed(int startInclusive, int endInclusive) {
        IntervalSet intervalSet = new IntervalSet();
        if (startInclusive <= endInclusive) {
            intervalSet.addInterval(startInclusive, endInclusive);
        }
        return intervalSet;
    }

    // Modifying the concat method to accept IntervalSet array and merge
    public static IntervalSet concat(IntervalSet... intervalSets) {
        IntervalSet result = new IntervalSet();
        for (IntervalSet intervalSet : intervalSets) {
            result.intervals.addAll(intervalSet.intervals);
        }
        return result;
    }

    // New skip method
    public IntervalSet skip(int skipCount) {
        IntervalSet result = new IntervalSet();
        int count = 0;
        for (int[] interval : intervals) {
            int start = interval[0];
            int end = interval[1];
            int length = end - start + 1;

            if (count + length <= skipCount) {
                count += length;
            } else {
                int newStart = start + (skipCount - count);
                result.addInterval(newStart, end);
                count = skipCount;
            }
        }
        return result;
    }

    // New limit method
    public IntervalSet limit(int limitCount) {
        IntervalSet result = new IntervalSet();
        int count = 0;
        for (int[] interval : intervals) {
            int start = interval[0];
            int end = interval[1];
            int length = end - start + 1;

            if (count + length <= limitCount) {
                result.addInterval(start, end);
                count += length;
            } else {
                // if count==limitCount, no interval will be added.
                if (count < limitCount) {
                    int newEnd = start + (limitCount - count) - 1;
                    result.addInterval(start, newEnd);
                }
                break;
            }
        }
        return result;
    }

    // New of method
    public static IntervalSet of(int... values) {
        IntervalSet intervalSet = new IntervalSet();
        for (int value : values) {
            intervalSet.addInterval(value, value);
        }
        return intervalSet;
    }

    public static void main(String[] args) {
        IntervalSet intervalSet = new IntervalSet();
        intervalSet.addInterval(1, 3);
        intervalSet.addInterval(5, 7);
        intervalSet.addInterval(10, 12);

        for (int value : intervalSet) {
            System.out.println(value);
        }

        // Testing empty method
        IntervalSet emptySet = IntervalSet.empty();
        System.out.println("Empty Set:");
        for (int value : emptySet) {
            System.out.println(value);
        }

        // Testing range method
        IntervalSet rangeSet = IntervalSet.range(1, 5);
        System.out.println("Range Set:");
        for (int value : rangeSet) {
            System.out.println(value);
        }

        // Testing rangeClosed method
        IntervalSet rangeClosedSet = IntervalSet.rangeClosed(1, 5);
        System.out.println("Range Closed Set:");
        for (int value : rangeClosedSet) {
            System.out.println(value);
        }

        // Testing concat method
        IntervalSet concatSet = IntervalSet.concat(intervalSet, rangeSet);
        System.out.println("Concat Set:");
        for (int value : concatSet) {
            System.out.println(value);
        }

        // Testing skip method
        IntervalSet skipSet = intervalSet.skip(3);
        System.out.println("Skip Set:");
        for (int value : skipSet) {
            System.out.println(value);
        }

        // Testing limit method
        IntervalSet limitSet = intervalSet.limit(5);
        System.out.println("Limit Set:");
        for (int value : limitSet) {
            System.out.println(value);
        }

        // Testing of method
        IntervalSet ofSet = IntervalSet.of(1, 3, 5, 7, 9);
        System.out.println("Of Set:");
        for (int value : ofSet) {
            System.out.println(value);
        }
    }
}