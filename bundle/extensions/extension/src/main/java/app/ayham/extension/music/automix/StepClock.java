package app.ayham.extension.music.automix;

/**
 * A player's clock from readings that move in steps: each change is an anchor (the reading
 * less the time it was first seen, times the speed); the line is the median anchor. Seen up to
 * one tick late, the same for both players, so the comparison isn't biased.
 */
final class StepClock {
    private final double[] anchors = new double[8];
    private int count, next;
    private long last = Long.MIN_VALUE;

    boolean see(long value, long now, float speed) {
        if (value == last) return false;
        boolean first = last == Long.MIN_VALUE;
        last = value;
        if (first) return false; // the first reading's change time is unknown
        anchors[next] = value - now * (double) speed;
        next = (next + 1) % anchors.length;
        count = Math.min(count + 1, anchors.length);
        return true;
    }

    int n() {
        return count;
    }

    double at(long now, float speed) {
        double[] a = java.util.Arrays.copyOf(anchors, count);
        java.util.Arrays.sort(a);
        double med = count % 2 == 1 ? a[count / 2] : (a[count / 2 - 1] + a[count / 2]) / 2;
        return med + now * (double) speed;
    }

    void reset() {
        count = 0;
        next = 0;
        last = Long.MIN_VALUE;
    }
}
