package app.ayham.extension.music.automix;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What both decks play, on one clock: mix seconds, 0 = the moment our deck takes the outgoing song
 * over from YT's player. Built from a plan the way the extension's crossfade.js schedules it
 * (rampOutgoing, scheduleDeckBlend/blendShape/djShape, glideToUnity, scheduleLoopMix). Pure Java.
 */
public final class Mix {
    /** The old song moves to our deck this long before its blend (its speed-up included). */
    public static final double HAND_LEAD = 4;
    /** YT is asked for the next song this long before the deck needs its player. */
    public static final double NEXT_LEAD = 2.5;
    public static final double H = 0.025;

    public String style, outId, inId, note, title, sub;
    public boolean v2;
    public Planner.Plan plan;
    /** Outgoing song second at mix 0, and where the outgoing song ends (mix seconds at its own speed). */
    public double takeoverSong, outEnd;
    /** The blend (or loop mix) on the mix clock. */
    public double t0, D;
    /** YT moves to the next song; its player takes over from the incoming deck no earlier than [elementAt]. */
    public double askAt, elementAt;
    public final Deck out = new Deck(), in = new Deck();

    /** One automation lane, like a Web Audio AudioParam's events: set / linear ramp / exponential ramp. */
    public static final class Lane {
        final List<double[]> pts = new ArrayList<>(); // {time, value, kind}: 0 set, 1 linear, 2 exponential
        final double rest;

        Lane(double rest) {
            this.rest = rest;
        }

        Lane set(double v, double t) {
            pts.add(new double[]{t, v, 0});
            return this;
        }

        Lane lin(double v, double t) {
            pts.add(new double[]{t, v, 1});
            return this;
        }

        Lane exp(double v, double t) {
            pts.add(new double[]{t, v, 2});
            return this;
        }

        /** setValueCurveAtTime: the values evenly over [t, t + d], linearly between them. */
        Lane curve(float[] c, double t, double d) {
            pts.add(new double[]{t, c[0], 0});
            for (int i = 1; i < c.length; i++) pts.add(new double[]{t + d * i / (c.length - 1), c[i], 1});
            return this;
        }

        public boolean empty() {
            return pts.isEmpty();
        }

        public double at(double t) {
            if (pts.isEmpty()) return rest;
            double prevT = Double.NEGATIVE_INFINITY, prevV = rest;
            for (double[] p : pts) {
                if (t < p[0]) {
                    if (p[2] == 0 || prevT == Double.NEGATIVE_INFINITY) return prevV;
                    double f = (t - prevT) / (p[0] - prevT);
                    if (p[2] == 2 && prevV > 0 && p[1] > 0) return prevV * Math.pow(p[1] / prevV, f);
                    return prevV + (p[1] - prevV) * f;
                }
                prevT = p[0];
                prevV = p[1];
            }
            return prevV;
        }
    }

    public static final class Deck {
        /** Beat knots: mix seconds, and the (unwrapped) song second heard then. */
        public double[] knotT, knotP;
        /** A loop [loopA, loopB) song seconds, let go at unwrapped second [release] (then on from loopB). */
        public double loopA, loopB, release = 1e18;
        /** After the last knot: on at rate 1 (the incoming song), or stop. */
        public boolean runOn;
        public double stopAt = 1e9;
        public final Lane gain = new Lane(0), lowDb = new Lane(0), highDb = new Lane(0), hpHz = new Lane(20), lpHz = new Lane(20000);
        /** Song seconds of decoded audio the deck needs. */
        public double needFrom, needTo;

        /** The unwrapped song second at mix second [t]. */
        public double unwrappedAt(double t) {
            int n = knotT.length;
            if (t <= knotT[0]) return knotP[0] + (t - knotT[0]) * rate(0);
            if (t >= knotT[n - 1]) return knotP[n - 1] + (runOn ? t - knotT[n - 1] : 0);
            int i = 1;
            while (knotT[i] < t) i++;
            double f = (t - knotT[i - 1]) / (knotT[i] - knotT[i - 1]);
            return knotP[i - 1] + (knotP[i] - knotP[i - 1]) * f;
        }

        private double rate(int seg) {
            return (knotP[seg + 1] - knotP[seg]) / (knotT[seg + 1] - knotT[seg]);
        }

        /** The song second heard at mix second [t]: the loop and its release applied. */
        public double trackAt(double t) {
            double u = unwrappedAt(t), len = loopB - loopA;
            if (len > 0 && u >= loopB) return u < release ? loopA + (u - loopA) % len : loopB + (u - release);
            return u;
        }

        /** Song seconds per mix second at [t]. */
        public double rateAt(double t) {
            int n = knotT.length;
            if (t >= knotT[n - 1]) return runOn ? 1 : 0;
            if (t < knotT[0]) return rate(0);
            int i = 1;
            while (knotT[i] <= t) i++;
            return rate(i - 1);
        }

        void knots(List<double[]> k) {
            knotT = new double[k.size()];
            knotP = new double[k.size()];
            for (int i = 0; i < k.size(); i++) {
                knotT[i] = k.get(i)[0];
                knotP[i] = k.get(i)[1];
            }
        }
    }

    // ---- the blend's shapes ----

    static final int EP = 65;

    static float[] ep(boolean in) {
        float[] c = new float[EP];
        for (int i = 0; i < EP; i++) {
            double a = (i / (double) (EP - 1)) * Math.PI / 2;
            c[i] = (float) (in ? Math.sin(a) : Math.cos(a));
        }
        return c;
    }

    static double clamp(double x, double a, double b) {
        return Math.max(a, Math.min(b, x));
    }

    static double glide(double a, double b, double x) {
        return a * Math.pow(b / a, clamp(x, 0, 1));
    }

    /** crossfade.js djShape: AutoDJ's fader and filter curves (gIn, gOut, inHp, outHp, outLp). */
    static float[][] djShape(String style, double clashIn, double D, double bpm) {
        double clash = clamp(clashIn, 0, 1);
        double beat = bpm > 0 ? 60 / bpm : 0;
        double grid = beat > 0 ? D / beat : 0;
        java.util.function.DoubleUnaryOperator onBar = (f) -> grid >= 8 ? clamp(Math.round((f * grid) / 4) * 4 / grid, 4 / grid, 1 - 4 / grid) : f;
        double w = grid >= 2 ? 0.5 / grid : 0.2;
        double swapAt = onBar.applyAsDouble(0.5);
        java.util.function.DoubleUnaryOperator h = (p) -> clamp((p - (swapAt - w)) / w, 0, 1);
        final double S = Math.PI / 2;
        int n = (int) Math.max(EP, Math.min(4000, Math.ceil(D * 40)));
        float[] gIn = new float[n], gOut = new float[n], inHp = new float[n], outHp = new float[n], outLp = new float[n];
        boolean filter = style.equals("filter");
        double top = filter ? glide(1200, 2400, clash) : glide(520, 950, clash);
        double openBy = onBar.applyAsDouble(filter ? 0.6 + 0.18 * clash : 0.45 + 0.25 * clash);
        double from = onBar.applyAsDouble(0.3 - 0.18 * clash), floor = glide(2200, 1100, clash);
        double fallFrom = Math.max(0, Math.min(Math.min(swapAt, 0.5), grid != 0 ? 1 - 4 / grid : 0.5));
        for (int i = 0; i < n; i++) {
            double p = i / (double) (n - 1);
            double hp = h.applyAsDouble(p);
            double entry = p >= openBy ? 20 : glide(20, top, Math.pow(1 - p / openBy, 0.35));
            outHp[i] = (float) glide(20, 200, hp);
            double r, f;
            if (filter) {
                outLp[i] = (float) glide(7000, 300, Math.pow(p, 0.75));
                inHp[i] = (float) Math.max(entry, glide(20, 200, 1 - hp));
                r = Math.sin(Math.pow(p, 0.8) * S);
                f = Math.cos(Math.pow(p, 1.6) * S);
            } else {
                outLp[i] = (float) glide(20000, floor, (p - from) / (1 - from));
                inHp[i] = (float) Math.max(glide(20, 200, 1 - hp), entry);
                double rp = Math.sin(p * S), rd = Math.sin(clamp(p / swapAt, 0, 1) * S);
                double fp = Math.cos(p * S), fd = p <= fallFrom ? 1 : Math.cos(((p - fallFrom) / (1 - fallFrom)) * S);
                r = rp + (rd - rp) * (1 - clash);
                f = fp + (fd - fp) * (1 - clash);
            }
            r += (Math.sin(Math.pow(p, 1.65) * S) - r) * clash;
            f += (Math.cos(Math.pow(p, 0.65) * S) - f) * clash;
            double trim = r + f <= 1 ? 1 : Math.max(0.5, 1 / Math.sqrt(r + f));
            gIn[i] = (float) (r * trim);
            gOut[i] = (float) (f * trim);
        }
        return new float[][]{gIn, gOut, inHp, outHp, outLp};
    }

    // ---- building ----

    /**
     * A blend, filter mix or crossfade (not a fallback onto Morphe's crossfade). [outDuration]: the
     * playing song's length as the player reports it.
     */
    public static Mix blend(Planner.Plan p, String outId, String inId, double outDuration) {
        Mix m = new Mix();
        m.plan = p;
        m.style = p.style;
        m.v2 = p.v2 && !p.style.equals("crossfade");
        m.outId = outId;
        m.inId = inId;
        double R = p.outRate > 0 ? p.outRate : 1, b = p.beatSec, mixOut = p.mixOutTrackTime, rate = p.rate;
        int n = p.style.equals("blend") ? p.rampBeats : 0;
        double rampSong = n * (b > 0 ? b : 0);
        m.takeoverSong = mixOut - HAND_LEAD - rampSong;
        // outgoing: rate 1 to the ramp, one 0.75% step per beat up to R (rampOutgoing), R through the blend
        List<double[]> ok = new ArrayList<>();
        ok.add(new double[]{0, m.takeoverSong});
        double t = HAND_LEAD, pos = mixOut - rampSong;
        ok.add(new double[]{t, pos});
        for (int k = 1; k <= n; k++) {
            double r = 1 + ((R - 1) * k) / n;
            t += b / r;
            pos += b;
            ok.add(new double[]{t, pos});
        }
        m.t0 = t;
        m.D = p.durationSec;
        ok.add(new double[]{m.t0 + m.D + 1, mixOut + (m.D + 1) * R});
        m.out.knots(ok);
        m.out.stopAt = m.t0 + m.D + 0.2;
        m.out.needFrom = m.takeoverSong - 1;
        m.out.needTo = mixOut + (m.D + 1) * R + 1;
        m.outEnd = m.t0 + (outDuration - mixOut) / R;

        // incoming: [rate] through the blend, then back to its own tempo in 8 steps 3 s apart (glideToUnity)
        double mixIn = p.mixInTrackTime;
        List<double[]> ik = new ArrayList<>();
        ik.add(new double[]{m.t0, mixIn});
        double it = m.t0 + m.D, ip = mixIn + m.D * rate;
        ik.add(new double[]{it, ip});
        if (Math.abs(rate - 1) < 0.002) {
            ik.add(new double[]{it + 0.5, ip + 0.5 * rate});
            it += 0.5;
        } else {
            for (int i = 0; i < 8; i++) {
                double r = rate + (1 - rate) * i / 8.0;
                it += 3;
                ip += 3 * r;
                ik.add(new double[]{it, ip});
            }
        }
        m.in.knots(ik);
        m.in.runOn = true;
        m.elementAt = it + 0.5;
        m.in.needFrom = mixIn - 1;
        m.in.needTo = m.in.trackAt(m.elementAt) + 30;
        m.askAt = Math.min(Math.min(m.t0 + m.D / 2, m.t0 + m.D - 1.5 - NEXT_LEAD), m.outEnd - 1.5);

        // gains: AutoDJ's fader for a version 2 blend or filter mix, else equal power
        float[] gIn, gOut;
        float[][] dj = m.v2 ? djShape(p.style, p.clash, m.D, p.bpm) : null;
        if (dj != null) {
            gIn = dj[0];
            gOut = dj[1];
        } else {
            gIn = ep(true);
            gOut = ep(false);
        }
        m.out.gain.set(0, -H).lin(1, 0).set(1, m.t0).curve(gOut, m.t0, m.D).set(0, m.t0 + m.D);
        m.in.gain.set(0, 0).curve(gIn, m.t0, m.D).set(1, m.t0 + m.D);

        // the EQ and filters (blendShape)
        double mid = m.t0 + m.D / 2, bar = Math.min(p.barSec, m.D / 2);
        if (dj != null) {
            m.in.hpHz.set(dj[2][0], 0).curve(dj[2], m.t0, m.D);
            double ease = Math.max(0.01, m.t0 - 0.03);
            m.out.lpHz.set(20000, ease).lin(dj[4][0], m.t0 - 0.001).curve(dj[4], m.t0, m.D);
            m.out.hpHz.set(20, ease).lin(dj[3][0], m.t0 - 0.001).curve(dj[3], m.t0, m.D);
        } else if (p.style.equals("blend") || p.style.equals("filter")) {
            m.in.lowDb.set(p.swapDb, 0).set(p.swapDb, mid - bar / 2).lin(0, mid + bar / 2);
            m.out.lowDb.set(0, mid - bar / 2).lin(p.swapDb, mid + bar / 2);
            if (p.style.equals("blend")) {
                m.out.highDb.set(0, m.t0).lin(-8, m.t0 + m.D);
                m.in.highDb.set(-8, 0).set(-8, m.t0).lin(0, m.t0 + m.D);
            } else {
                m.out.hpHz.set(20, m.t0).exp(1800, m.t0 + m.D);
                m.in.lpHz.set(350, 0).set(350, m.t0).exp(20000, m.t0 + m.D);
            }
        }

        String name = m.v2 ? "Advanced AutoDJ" : "Automix";
        m.title = p.style.equals("blend") ? name : p.style.equals("filter") ? name + " · filter mix" : "Automix · crossfade";
        m.sub = p.style.equals("crossfade")
                ? String.format(Locale.US, "%.1f s%s", m.D, p.reason != null ? " · " + p.reason : "")
                : String.format(Locale.US, "%d bars · %.1f s · %d BPM%s", p.bars, m.D, Math.round(p.bpm),
                p.style.equals("blend") && Math.abs(rate - 1) > 0.002 ? String.format(Locale.US, " · ×%.3f", rate) : "");
        m.note = p.toString();
        return m;
    }

    /** The loop mix, its clock from the outgoing loop's start (scheduleLoopMix). */
    public static Mix loop(Planner.LoopPlan lp, String outId, String inId, double outDuration) {
        Planner.LoopSchedule x = Planner.loopSchedule(lp, 0);
        Mix m = new Mix();
        m.style = "loop";
        m.outId = outId;
        m.inId = inId;
        m.takeoverSong = lp.out.loopStart;
        m.t0 = 0;
        m.D = x.elementAt;
        int beats = x.beats;
        List<double[]> ok = new ArrayList<>(), ik = new ArrayList<>();
        for (int k = 0; k <= beats; k++) {
            ok.add(new double[]{x.beatTimes[k], lp.out.loopStart + k * lp.out.beatSec});
            ik.add(new double[]{x.beatTimes[k], lp.in.loopStart + k * lp.fold * lp.in.beatSec});
        }
        m.out.knots(ok);
        m.out.loopA = lp.out.loopStart;
        m.out.loopB = lp.out.loopEnd;
        m.out.stopAt = x.releaseAt + 0.05;
        m.out.needFrom = lp.out.loopStart - 1;
        m.out.needTo = lp.out.loopEnd + 1;
        m.in.knots(ik);
        m.in.loopA = lp.in.loopStart;
        m.in.loopB = lp.in.loopEnd;
        m.in.release = ik.get(beats)[1];
        m.in.runOn = true;
        m.in.needFrom = lp.in.loopStart - 1;
        m.in.needTo = x.elementTrackTime + 30;
        m.elementAt = x.elementAt;
        m.outEnd = outDuration - lp.out.loopStart;
        m.askAt = Math.min(Math.min(x.handoffAt, x.elementAt - 1.5 - NEXT_LEAD), m.outEnd - 1.5);

        double cut = Planner.BASS_CUT_DB;
        m.out.gain.set(0, -H).lin(1, 0).set(1, x.enterEnd).lin(0.6, x.rampEnd).lin(0, x.releaseAt);
        m.in.gain.set(0, 0).lin(0.55, x.enterEnd).lin(1, x.rampEnd);
        m.in.lowDb.set(cut, 0).set(cut, x.mid - x.bar / 2).lin(0, x.mid + x.bar / 2);
        m.out.lowDb.set(0, x.mid - x.bar / 2).lin(cut, x.mid + x.bar / 2);
        m.out.hpHz.set(20, x.rampEnd).exp(1500, x.releaseAt);

        m.title = "Automix · loop mix";
        m.sub = String.format(Locale.US, "%d-bar loop · %d→%d BPM · %.1f s", lp.out.bars, Math.round(lp.out.bpm), Math.round(lp.in.bpm), m.D);
        m.note = lp.toString();
        return m;
    }
}
