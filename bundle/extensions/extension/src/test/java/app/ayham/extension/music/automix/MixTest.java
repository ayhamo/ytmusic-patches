package app.ayham.extension.music.automix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MixTest {

    @Test
    public void laneSetLinearAndExponential() {
        Mix.Lane l = new Mix.Lane(20);
        assertEquals(20, l.at(5), 0); // no events: its resting value
        l.set(20, 0).exp(2000, 10).lin(0, 20).set(7, 30);
        assertEquals(20, l.at(-1), 0);
        assertEquals(200, l.at(5), 1e-9);    // halfway up an exponential ramp: the geometric mean
        assertEquals(2000, l.at(10), 1e-9);
        assertEquals(1000, l.at(15), 1e-9);  // halfway down a linear ramp
        assertEquals(0, l.at(25), 0);        // a set holds the previous value until its time
        assertEquals(7, l.at(30), 0);
        assertEquals(7, l.at(1e6), 0);
    }

    @Test
    public void laneCurveIsSpreadEvenly() {
        Mix.Lane l = new Mix.Lane(0).curve(new float[]{0, 1, 0}, 10, 2);
        assertEquals(0, l.at(10), 0);
        assertEquals(0.5, l.at(10.5), 1e-9);
        assertEquals(1, l.at(11), 1e-9);
        assertEquals(0, l.at(12), 1e-9);
    }

    @Test
    public void equalPowerShapes() {
        float[] in = Mix.ep(true), out = Mix.ep(false);
        assertEquals(Mix.EP, in.length);
        assertEquals(0, in[0], 1e-7);
        assertEquals(1, in[Mix.EP - 1], 1e-7);
        for (int i = 0; i < Mix.EP; i++) assertEquals(1, in[i] * in[i] + out[i] * out[i], 1e-6);
    }

    @Test
    public void djShapesStayInRange() {
        for (String style : new String[]{"blend", "filter"}) {
            for (double clash : new double[]{0, 0.5, 1}) {
                float[][] s = Mix.djShape(style, clash, 16, 124);
                int n = s[0].length;
                for (float[] lane : s) assertEquals(n, lane.length);
                for (int i = 0; i < n; i++) {
                    assertTrue(s[0][i] >= 0 && s[0][i] <= 1.0001);
                    assertTrue(s[1][i] >= 0 && s[1][i] <= 1.0001);
                    for (int k = 2; k < 5; k++) assertTrue(s[k][i] >= 19.9 && s[k][i] <= 20000.1);
                }
                assertEquals(0, s[0][0], 1e-6);       // incoming silent at the start
                assertEquals(1, s[1][0], 1e-6);       // outgoing full at the start
                assertEquals(0, s[1][n - 1], 1e-6);   // and gone at the end
            }
        }
    }

    @Test
    public void blendKnotsAndGainsFollowThePlan() {
        Planner.Plan p = Planner.plan(new Planner.Settings(), "a", "b", PlannerTest.song("a", 120, 200, 0.9),
                PlannerTest.song("b", 120, 180, 0.9), 200, 60, 60 + PlannerTest.EARLIEST_LEAD, null);
        Mix m = Mix.blend(p, "a", "b", 200);
        assertEquals(p.mixOutTrackTime - Mix.HAND_LEAD, m.takeoverSong, 1e-9);
        assertEquals(Mix.HAND_LEAD, m.t0, 1e-9);
        assertEquals(p.durationSec, m.D, 1e-9);
        // the outgoing deck plays on from the takeover at rate 1, reaching the blend on time
        assertEquals(m.takeoverSong, m.out.trackAt(0), 1e-9);
        assertEquals(p.mixOutTrackTime, m.out.trackAt(m.t0), 1e-9);
        assertEquals(1, m.out.rateAt(m.t0 + 1), 1e-9);
        // the incoming deck starts at its cue when the blend starts
        assertEquals(p.mixInTrackTime, m.in.trackAt(m.t0), 1e-9);
        assertEquals(p.mixInTrackTime + 2, m.in.trackAt(m.t0 + 2), 1e-9);
        // equal power gains (version 1)
        assertEquals(1, m.out.gain.at(m.t0), 1e-6);
        assertEquals(Math.sqrt(0.5), m.out.gain.at(m.t0 + m.D / 2), 1e-6);
        assertEquals(Math.sqrt(0.5), m.in.gain.at(m.t0 + m.D / 2), 1e-6);
        assertEquals(0, m.out.gain.at(m.t0 + m.D), 0);
        assertEquals(1, m.in.gain.at(m.t0 + m.D), 0);
        // YT is asked for the next song before the deck needs it, and before the outgoing song ends
        assertTrue(m.askAt <= m.elementAt - Mix.NEXT_LEAD);
        assertTrue(m.askAt < m.outEnd);
        assertTrue(m.in.needFrom <= p.mixInTrackTime && m.in.needTo > m.in.trackAt(m.elementAt));
    }

    @Test
    public void speedUpRampReachesTheBlendOnTheBeat() {
        Planner.Plan p = Planner.plan(new Planner.Settings(), "a", "b", PlannerTest.song("a", 120, 200, 0.9),
                PlannerTest.song("b", 126, 180, 0.9), 200, 60, 60 + PlannerTest.EARLIEST_LEAD, null);
        Mix m = Mix.blend(p, "a", "b", 200);
        assertEquals(p.mixOutTrackTime, m.out.trackAt(m.t0), 1e-9);
        assertEquals(p.outRate, m.out.rateAt(m.t0 + 0.5), 1e-9);
        // during the ramp it never runs slower than 1 or faster than the target
        for (double t = Mix.HAND_LEAD; t < m.t0; t += 0.05) {
            double r = m.out.rateAt(t);
            assertTrue(r >= 1 - 1e-9 && r <= p.outRate + 1e-9);
        }
    }

    @Test
    public void loopMixDecksWrapAndRelease() {
        Planner.LoopPlan lp = PlannerTest.loopPlan();
        Mix m = Mix.loop(lp, "a", "b", 200);
        assertEquals("loop", m.style);
        assertEquals(190.5, m.takeoverSong, 1e-9);
        assertEquals(20, m.D, 1e-9);
        // the outgoing 4-bar loop (8 s) repeats
        assertEquals(190.5, m.out.trackAt(0), 1e-9);
        assertEquals(190.5, m.out.trackAt(8), 1e-9);
        assertEquals(191.5, m.out.trackAt(9), 1e-9);
        // the incoming loop repeats until its release, then plays on past it
        assertEquals(4.5, m.in.trackAt(12), 1e-9);
        assertEquals(8.5, m.in.trackAt(16), 1e-9);
        assertEquals(10.5, m.in.trackAt(18), 1e-9);
        assertEquals(1, m.out.gain.at(0), 1e-9);
        assertEquals(0, m.out.gain.at(16), 1e-9);
        assertEquals(1, m.in.gain.at(13), 1e-9);
        assertTrue(m.askAt <= m.elementAt - Mix.NEXT_LEAD);
    }
}
