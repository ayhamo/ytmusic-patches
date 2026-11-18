package app.ayham.extension.music.automix;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Two players whose readings move every ~250 ms, read every 10 ms: their gap is found, not the step. */
public class StepClockTest {
    /** A reading that updates every period ms (from phase), showing the position at that update. */
    private static long reading(long t, long period, long phase, double offsetMs) {
        long upd = t - Math.floorMod(t - phase, period);
        return Math.round(upd + offsetMs);
    }

    @Test
    public void gapOfSteppedClocks() {
        for (double gap : new double[]{0, 40, -73, 5}) {
            StepClock a = new StepClock(), b = new StepClock();
            double est = Double.NaN;
            for (long t = 0; t < 4000; t += 10) {
                a.see(reading(t, 250, 37, 1000), t, 1f);
                b.see(reading(t, 253, 161, 1000 + gap), t, 1f);
                if (a.n() >= 6 && b.n() >= 6) est = b.at(t, 1f) - a.at(t, 1f);
            }
            assertEquals(gap, est, 10);
        }
    }

    @Test
    public void naiveComparisonWouldSwing() {
        // the old way: our stale reading against YT's fresh one is off by up to a step
        long t = 1240;
        long diff = reading(t, 250, 161, 1000) - reading(t, 250, 37, 1000);
        assertEquals(124, Math.abs(diff), 2);
    }
}
