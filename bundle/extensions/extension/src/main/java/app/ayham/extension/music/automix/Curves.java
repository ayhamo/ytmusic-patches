package app.ayham.extension.music.automix;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What each deck does over a transition: spec/automix/plan.schema.json "curves", a port of the
 * extension's automix.js blendCurves and loopCurves (checked against its golden plans by
 * SpecFixturesTest). The overview draws these without knowing how the mix was planned.
 *
 * <p>Each curve is a list of [t, v] points, t in seconds from the transition's start (0: the
 * mix-out, or the outgoing loop's start; negative: the outgoing speed-up before a blend). A value
 * holds before the first point and after the last, runs linear between points (Hz: geometric),
 * and two points at one t are a step. An absent curve stays neutral.
 */
public final class Curves {
    public double durationSec;
    /** Curve name (gain, lowpassHz, highpassHz, bassDb, highDb, rate) → its [t, v] points. */
    public final Map<String, double[][]> out = new LinkedHashMap<>(), in = new LinkedHashMap<>();


    /** A blend, filter mix or crossfade plan's curves (automix.js blendCurves). */
    static Curves blend(Planner.Plan m) {
        Curves c = new Curves();
        double D = m.durationSec;
        String style = m.style;
        c.durationSec = D;
        if (m.v2 && !style.equals("crossfade")) {
            float[][] s = Mix.djShape(style, m.clash, D, m.bpm, samples(D));
            c.out.put("gain", points(s[1], D));
            c.in.put("gain", points(s[0], D));
            c.out.put("lowpassHz", points(s[4], D));
            c.out.put("highpassHz", points(s[3], D));
            c.in.put("highpassHz", points(s[2], D));
        } else {
            c.out.put("gain", points(equalPower(65, false), D));
            c.in.put("gain", points(equalPower(65, true), D));
            if (!style.equals("crossfade")) {
                // the bass swaps within one bar at the midpoint
                double mid = D / 2, bar = Math.min(Double.isNaN(m.barSec) ? 0 : m.barSec, D / 2);
                c.in.put("bassDb", new double[][]{{0, m.swapDb}, {mid - bar / 2, m.swapDb}, {mid + bar / 2, 0}});
                c.out.put("bassDb", new double[][]{{mid - bar / 2, 0}, {mid + bar / 2, m.swapDb}});
                if (style.equals("blend")) {
                    c.out.put("highDb", new double[][]{{0, 0}, {D, -8}});
                    c.in.put("highDb", new double[][]{{0, -8}, {D, 0}});
                } else {
                    c.out.put("highpassHz", new double[][]{{0, 20}, {D, 1800}});
                    c.in.put("lowpassHz", new double[][]{{0, 350}, {D, 20000}});
                }
            }
        }
        // tempo: the playing song speeds up beat by beat before the mix-out (blend only)
        if (style.equals("blend") && m.outRate > 1.0001 && m.rampBeats > 0) {
            double R = m.outRate, b = m.beatSec;
            int n = m.rampBeats;
            double t = 0;
            for (int k = 1; k <= n; k++) t -= b / (1 + ((R - 1) * k) / n);
            List<double[]> pts = new ArrayList<>();
            pts.add(new double[]{t, 1});
            for (int k = 1; k <= n; k++) {
                double r = 1 + ((R - 1) * k) / n, prev = pts.get(pts.size() - 1)[1];
                pts.add(new double[]{t, prev});
                pts.add(new double[]{t, r});
                t += b / r;
            }
            pts.add(new double[]{D, R});
            // repeated points dropped (the first step's [t, 1] twice)
            List<double[]> kept = new ArrayList<>();
            for (int i = 0; i < pts.size(); i++) {
                double[] p = pts.get(i);
                if (i == 0 || p[0] != pts.get(i - 1)[0] || p[1] != pts.get(i - 1)[1]) kept.add(p);
            }
            c.out.put("rate", kept.toArray(new double[0][]));
        }
        double rate = style.equals("blend") ? m.rate : 1;
        if (Math.abs(rate - 1) > 1e-9) {
            if (Math.abs(rate - 1) < 0.002) {
                c.in.put("rate", new double[][]{{0, rate}, {D, rate}, {D, 1}});
            } else {
                double sec = Math.ceil(Math.abs(rate - 1) / Mix.GLIDE_STEP) * Mix.GLIDE_STEP_SEC;
                c.in.put("rate", new double[][]{{0, rate}, {D, rate}, {D + sec, 1}});
            }
        }
        return c;
    }

    /** A loop mix's curves, from its schedule (automix.js loopCurves). */
    static Curves loop(Planner.LoopPlan plan) {
        Planner.LoopSchedule x = Planner.loopSchedule(plan, 0);
        double half = x.bar / 2;
        Curves c = new Curves();
        c.durationSec = x.releaseAt;
        c.out.put("gain", new double[][]{{0, 1}, {x.enterEnd, 1}, {x.rampEnd, 0.6}, {x.releaseAt, 0}});
        c.out.put("bassDb", new double[][]{{x.mid - half, 0}, {x.mid + half, Planner.BASS_CUT_DB}});
        c.out.put("highpassHz", new double[][]{{x.rampEnd, 20}, {x.releaseAt, 1500}});
        List<Planner.Change> active = new ArrayList<>();
        for (Planner.Change ch : x.outChanges) if (ch.active) active.add(ch);
        c.out.put("rate", steps(active, x.releaseAt));
        c.in.put("gain", new double[][]{{0, 0}, {x.enterEnd, 0.55}, {x.rampEnd, 1}});
        c.in.put("bassDb", new double[][]{{0, Planner.BASS_CUT_DB}, {x.mid - half, Planner.BASS_CUT_DB}, {x.mid + half, 0}});
        c.in.put("rate", steps(x.inChanges, x.releaseAt));
        return c;
    }

    /** A deck's speed changes as steps, up to [until]. */
    private static double[][] steps(List<Planner.Change> changes, double until) {
        List<double[]> pts = new ArrayList<>();
        for (Planner.Change ch : changes) {
            if (ch.output > until) break;
            if (pts.isEmpty()) {
                pts.add(new double[]{ch.output, ch.rate});
            } else {
                double prev = pts.get(pts.size() - 1)[1];
                if (Math.abs(prev - ch.rate) > 1e-9) {
                    pts.add(new double[]{ch.output, prev});
                    pts.add(new double[]{ch.output, ch.rate});
                }
            }
        }
        return pts.toArray(new double[0][]);
    }

    /** A sampled shape as points over 0..D. */
    private static double[][] points(float[] v, double D) {
        double[][] p = new double[v.length][];
        for (int i = 0; i < v.length; i++) p[i] = new double[]{D * i / (v.length - 1), v[i]};
        return p;
    }

    /** About 8 points a second, at least 65. */
    static int samples(double D) {
        return (int) Math.max(65, Math.ceil(D * 8) + 1);
    }

    private static float[] equalPower(int n, boolean rising) {
        float[] c = new float[n];
        for (int i = 0; i < n; i++) {
            double a = (i / (double) (n - 1)) * Math.PI / 2;
            c[i] = (float) (rising ? Math.sin(a) : Math.cos(a));
        }
        return c;
    }
}
