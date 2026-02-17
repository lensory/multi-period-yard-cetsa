package entity;

import util.IntervalSet;

import java.util.Iterator;

public final class CyclicClosedOpenInterval {
    private final int start;
    private final int length;

    public CyclicClosedOpenInterval(int start, int length) {
        if (start < 0)
            throw new IllegalArgumentException("Start must be >= 0");
        if (length < 0) {
            throw new IllegalArgumentException("length must be >= 0");
        }
        this.start = start;
        this.length = length;
    }

    public int shiftsFromStart(int t, int horizon) {
        if (t < start)
            return horizon + t - start;
        else
            return t - start;
    }

    public int getStart() {
        return start;
    }

    public int getLength() {
        return length;
    }

    public int getEnd() {
        return start + length;
    }

    public CyclicClosedOpenInterval shift(int shift, int horizon) {
        return new CyclicClosedOpenInterval((start + shift) % horizon, length);
    }


    private void validateHorizon(int horizon) {
        if (horizon <= this.start) {
            throw new IllegalArgumentException(
                    String.format("Horizon(%d) should be greater than interval start(%d)", horizon, this.start)
            );
        }
        if (horizon < this.length) {
            throw new IllegalArgumentException(
                    String.format("Horizon(%d) cannot be smaller than interval length(%d)", horizon, this.length)
            );
        }
    }

    public boolean isIntersecting(CyclicClosedOpenInterval that, int horizon) {
        this.validateHorizon(horizon);
        that.validateHorizon(horizon);
        if (this.length == 0 || that.length == 0)
            return false;
        int gap = this.start - that.start;
        return (this.length > -gap || -gap > horizon - that.length)
                && (that.length > gap || gap > horizon - this.length);
    }

    private IntervalSet wrap(int s, int l, int h) {
        if (l <= 0)
            return IntervalSet.empty();
        else if (l + s < h)
            return IntervalSet.range(s, s + l);
        else
            return IntervalSet.concat(IntervalSet.range(s, h),
                    IntervalSet.range(0, s + l - h));
    }


    public IntervalSet intersection(CyclicClosedOpenInterval that, int horizon) {
        this.validateHorizon(horizon);
        that.validateHorizon(horizon);
        CyclicClosedOpenInterval left, right;
        if (this.start <= that.start) {
            left = this;
            right = that;
        } else {
            left = that;
            right = this;
        }
        IntervalSet fromRight = wrap(
                right.start,
                Math.min(right.length, left.start + left.length - right.start), // left.normalEnd = left.start + left.length
                horizon);
        IntervalSet fromLeft = wrap(
                left.start,
                Math.min(left.length, right.start + right.length - horizon - left.start), // right.cyclicEnd = right.start + right.length - horizon
                horizon);

        return IntervalSet.concat(fromRight, fromLeft);
    }

    public IntervalSet intStream(int horizon) {
        validateHorizon(horizon);
        return wrap(this.start, this.length, horizon);
    }

    public Iterator<Integer> iterator(int horizon) {
        validateHorizon(horizon);
        return wrap(this.start, this.length, horizon).iterator();
    }


    public static void main(String[] args) {
        CyclicClosedOpenInterval interval1 = new CyclicClosedOpenInterval(0, 10);
        CyclicClosedOpenInterval interval2 = new CyclicClosedOpenInterval(5, 10);
        System.out.println(interval1);
        System.out.println(interval2);
        System.out.println(interval1.intersection(interval2, 20));
        System.out.println(interval2.intersection(interval1, 20));
        System.out.println(interval1.intersection(interval2, 13));
        System.out.println(interval2.intersection(interval1, 13));
        System.out.println(interval1.intersection(interval2, 10));
        System.out.println(interval2.intersection(interval1, 10));

    }

    @Override
    public String toString() {
        return "[" + getStart() + ", " + getEnd() + ")";
    }
}
