package app.ayham.extension.music.automix;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The YT Music Extension's automix planner (src/automix/automix.js: plan, filterMix, fallback,
 * the AutoDJ v2 additions, planLoopMix and loopSchedule), ported line by line. Times are song
 * seconds. Pure Java: it runs the same on a desktop JVM, where it is checked against automix.js.
 *
 * Version 1 is the extension's "Original AutoDJ" (BitChord web), version 2 its "Advanced
 * AutoDJ" (plus the BitChord phone's ideas); the loop mix is the same in both.
 */
public final class Planner {
    private Planner() {
    }

    /** The extension's automix settings. */
    public static final class Settings {
        /** 1: Original AutoDJ, 2: Advanced AutoDJ. */
        public int version = 1;
        /** Advanced AutoDJ's key-aware blend length. */
        public boolean keyAware;
        /** 0: Auto; else "Up to N bars". */
        public int bars;
    }

    /** Sung spans of each song from synced lyrics ([start, end] seconds), or null: the vocal mask. */
    public static final class Sung {
        public double[][] out, in;
    }

    public static final class Plan {
        public String style;            // blend | filter | crossfade | loop
        public boolean fallback;
        public String reason;
        public boolean v2;
        public double clash;
        public String rerouted, vocalsBy, keyClash;
        public double mixOutTrackTime, durationSec, mixInTrackTime;
        public double rate = 1, outRate = 1;
        public int rampBeats;
        public double rampSongSec, beatSec = Double.NaN;
        public int bars;
        public double swapDb, barSec, bpm = Double.NaN, outBpm = Double.NaN, inBpm = Double.NaN;
        public LoopPlan loop;

        @Override
        public String toString() {
            if (loop != null) return loop.toString();
            StringBuilder b = new StringBuilder();
            b.append(String.format(Locale.US, "%s from %.2f s for %.2f s", style, mixOutTrackTime, durationSec));
            if (!style.equals("crossfade")) b.append(String.format(Locale.US, ", %d bars at %.1f BPM", bars, bpm));
            b.append(String.format(Locale.US, ", next song from %.2f s at x%.3f", mixInTrackTime, rate));
            if (outRate > 1.0001) b.append(String.format(Locale.US, ", playing song up to x%.4f over %d beats", outRate, rampBeats));
            if (reason != null) b.append(" (").append(reason).append(')');
            if (v2 && !style.equals("crossfade")) {
                b.append(String.format(Locale.US, " [AutoDJ: both sing %d%%", Math.round(clash * 100)));
                if (vocalsBy != null) b.append(" (").append(vocalsBy).append(')');
                if (rerouted != null) b.append("; ").append(rerouted);
                if (keyClash != null) b.append("; keys clash (").append(keyClash).append("), short blend");
                b.append(']');
            }
            return b.toString();
        }
    }

    // ───────── grid (BitChord engine/grid.ts) ─────────
    static final int BEATS_PER_BAR = 4;
    static final int BEATS_PER_PHRASE = 32;

    static final class Grid {
        double beatSec, barSec, phraseSec, downbeatSec, bpm;
    }

    static Grid gridOf(Analysis a) {
        if (a == null || !(a.bpm > 0) || !(a.beatIntervalSec > 0) || Double.isNaN(a.firstDownbeatSec)) return null;
        Grid g = new Grid();
        g.beatSec = a.beatIntervalSec;
        g.barSec = g.beatSec * BEATS_PER_BAR;
        g.phraseSec = g.beatSec * BEATS_PER_PHRASE;
        g.downbeatSec = a.firstDownbeatSec;
        g.bpm = a.bpm;
        return g;
    }

    /** The analysis as the outgoing side sees it: its grid fields swapped for the tail grid. */
    public static Analysis asOutgoing(Analysis a) {
        if (!a.tailGridKnown) return a;
        Analysis o = a.copy();
        Analysis.LocalGrid t = a.tailGrid;
        if (t != null) {
            o.bpm = t.bpm;
            o.beatIntervalSec = t.beatIntervalSec;
            o.firstDownbeatSec = t.firstDownbeatSec;
            o.gridConfidence = t.gridConfidence;
        } else {
            o.beatIntervalSec = Double.NaN;
            o.firstDownbeatSec = Double.NaN;
            o.gridConfidence = 0;
        }
        return o;
    }

    static double phraseAtOrAfter(Grid g, double t) {
        return g.downbeatSec + Math.ceil((t - g.downbeatSec) / g.phraseSec) * g.phraseSec;
    }

    static double downbeatAtOrAfter(Grid g, double t) {
        return g.downbeatSec + Math.ceil((t - g.downbeatSec) / g.barSec) * g.barSec;
    }

    static double downbeatAtOrBefore(Grid g, double t) {
        return g.downbeatSec + Math.floor((t - g.downbeatSec) / g.barSec) * g.barSec;
    }

    static double naturalMixOut(Grid g, Analysis cur, double dur) {
        return downbeatAtOrBefore(g, cur.durationSec - g.barSec - dur);
    }

    // ───────── Auto blend length (BitChord phone, TransitionPlanner.kt) ─────────
    static final int[] AUTO_BAR_CHOICES = {32, 16, 8, 4};
    static final int AUTO_BEATS = 16;
    static final double AUTO_MAX_SEC = 12, AUTO_CALM_MAX_SEC = 16, AUTO_FILTER_MAX_SEC = 8;
    static final double CALM_ENERGY = 0.7;
    static final int ENERGY_WINDOW_BEATS = 8;
    static final double MAX_NEXT_SHARE = 0.4;

    /** The song's energy over [from, to] as a share of its mean; NaN: no curve. */
    static double energyShare(Analysis a, double from, double to) {
        double[] c = a == null ? null : a.energyCurve;
        if (c == null || c.length == 0 || !(a.durationSec > 0) || !(a.energy > 0)) return Double.NaN;
        double w = a.durationSec / c.length;
        int i0 = (int) Math.max(0, Math.min(c.length - 1, Math.floor(from / w)));
        int i1 = (int) Math.max(i0, Math.min(c.length - 1, Math.floor(Math.max(from, to - 1e-6) / w)));
        double sum = 0;
        for (int i = i0; i <= i1; i++) sum += c[i];
        return sum / (i1 - i0 + 1) / a.energy;
    }

    static int autoBars(Analysis cur, Analysis next, Grid g, double entrySec, boolean filter) {
        double window = ENERGY_WINDOW_BEATS * g.beatSec;
        double exit = cur.durationSec - g.barSec;
        double out = energyShare(cur, exit - window, exit);
        double into = energyShare(next, entrySec, entrySec + window);
        boolean calm = !Double.isNaN(out) && !Double.isNaN(into) && out < CALM_ENERGY && into < CALM_ENERGY;
        int beats = AUTO_BEATS * (calm ? 2 : 1);
        double cap = Math.min(Math.min(beats * g.beatSec, filter ? AUTO_FILTER_MAX_SEC : calm ? AUTO_CALM_MAX_SEC : AUTO_MAX_SEC),
                next.durationSec > 0 ? next.durationSec * MAX_NEXT_SHARE : Double.POSITIVE_INFINITY);
        for (int b : AUTO_BAR_CHOICES) if (b * g.barSec <= cap + 1e-6) return b;
        return AUTO_BAR_CHOICES[AUTO_BAR_CHOICES.length - 1];
    }

    static int requestedBars(Settings s, Analysis cur, Analysis next, Grid g, double entrySec, boolean filter) {
        return s.bars <= 0 ? autoBars(cur, next, g, entrySec, filter) : s.bars;
    }

    // ───────── transition planner (BitChord engine/transition-scheduler.ts) ─────────
    static final double MIN_GRID_CONFIDENCE = 0.2;
    static final double KILL_DB = -12, SHORT_BLEND_KILL_DB = -6;
    static final int FULL_SWAP_MIN_BARS = 8;
    static final double MAX_SLOW_DOWN = 0.1, MAX_SPEED_UP = 0.25;
    public static final double TEMPO_STEP_PER_BEAT = 0.0075;
    static final int FILTER_MAX_BARS = 8;
    static final double FALLBACK_SEC = 12;

    static int effectiveBlendBars(int requested, double fromConf, double toConf) {
        double conf = Math.min(fromConf, toConf);
        if (conf >= 0.6) return requested;
        if (conf >= 0.45) return Math.min(requested, 8);
        return Math.min(requested, 4);
    }

    static boolean stretchAllowed(double rate) {
        return rate >= 1 ? rate - 1 <= MAX_SPEED_UP : 1 - rate <= MAX_SLOW_DOWN;
    }

    static double foldRate(double raw) {
        double best = raw;
        for (double r : new double[]{raw / 2, raw * 2}) if (Math.abs(r - 1) < Math.abs(best - 1)) best = r;
        return best;
    }

    static Analysis stub(String id, double durationSec) {
        Analysis a = new Analysis();
        a.fileKey = id;
        a.durationSec = durationSec;
        a.gridConfidence = 0;
        a.energy = 0.5;
        a.energyCurve = new double[0];
        a.stub = true;
        a.tailGridKnown = false;
        return a;
    }

    /**
     * The blend from the playing song into the next. from, to: analyses (null: not analysed yet);
     * durationSec: the playing song's length as the player reports it; pos: where it is now;
     * earliest: the first song time the blend can start.
     */
    public static Plan plan(Settings s, String fromId, String toId, Analysis from, Analysis to, double durationSec,
                            double pos, double earliest, Sung sung) {
        boolean both = from != null && to != null;
        Analysis cur;
        if (both) {
            Analysis f = from.copy();
            f.durationSec = durationSec;
            cur = asOutgoing(f);
        } else {
            cur = asOutgoing(stub(fromId, durationSec));
        }
        Analysis next = both ? to : stub(toId, 0);
        Grid fromGrid = gridOf(cur), toGrid = gridOf(next);
        if (sung == null) sung = new Sung();

        if (fromGrid == null || cur.gridConfidence < MIN_GRID_CONFIDENCE) {
            return fallback(cur, next, earliest, both ? "this song has no beat grid at its end" : "songs not analysed in time");
        }
        if (toGrid == null || next.gridConfidence < MIN_GRID_CONFIDENCE) {
            return filterMix(s, cur, next, fromGrid, null, requestedBars(s, cur, next, fromGrid, 0, true), pos, earliest, "next song has no beat grid", sung);
        }

        double ratio = foldRate(fromGrid.bpm / toGrid.bpm);
        if (!stretchAllowed(ratio)) {
            return filterMix(s, cur, next, fromGrid, toGrid, requestedBars(s, cur, next, fromGrid, toGrid.downbeatSec, true), pos, earliest,
                    "tempos " + Math.round(Math.abs(ratio - 1) * 100) + "% apart", sung);
        }
        double rate = ratio < 1 ? 1 : ratio;
        double outRate = ratio < 1 ? 1 / ratio : 1;
        int rampBeats = outRate > 1.0001 ? (int) Math.ceil((outRate - 1) / TEMPO_STEP_PER_BEAT) : 0;
        double rampSongSec = rampBeats * fromGrid.beatSec;
        String keyClash = s.version == 2 && s.keyAware ? keysClash(cur, next) : null;
        int bars = keyClash != null ? keyClashBars(fromGrid) : requestedBars(s, cur, next, fromGrid, toGrid.downbeatSec, false);
        int effBars = effectiveBlendBars(bars, cur.gridConfidence, next.gridConfidence);
        double outSpan = effBars * fromGrid.barSec;
        double dur = outSpan / outRate;

        double first = earliest + rampSongSec;
        boolean v2 = s.version == 2;
        double mixOut = naturalMixOut(fromGrid, v2 ? musicEnd(cur) : cur, outSpan);
        if (mixOut < pos + rampSongSec) mixOut = phraseAtOrAfter(fromGrid, first);
        if (mixOut < first) mixOut = downbeatAtOrAfter(fromGrid, first);
        if (mixOut + outSpan > cur.durationSec - 0.3) {
            return filterMix(s, cur, next, fromGrid, toGrid, bars, pos, earliest, "too close to the end for a blend", sung);
        }

        double introEnd = Double.isNaN(next.introEndSec) ? 0 : next.introEndSec;
        double fullEnergyPhrase = phraseAtOrAfter(toGrid, Math.max(introEnd, toGrid.downbeatSec));
        double mixIn = fullEnergyPhrase - dur * rate;
        if (mixIn < toGrid.downbeatSec) mixIn = toGrid.downbeatSec;

        Reroute dj = null;
        if (v2) {
            mixIn = capIncomingSkip(next, toGrid, mixIn);
            dj = rerouteForVocals(cur, next, fromGrid, toGrid, mixOut, mixIn, outSpan, rate / outRate, Math.max(first, pos + rampSongSec), sung);
            mixOut = dj.mixOut;
            mixIn = dj.mixIn;
        }

        Plan p = new Plan();
        p.style = "blend";
        p.v2 = v2;
        p.clash = dj != null ? dj.clash : 0;
        p.rerouted = dj != null ? dj.rerouted : null;
        p.vocalsBy = dj != null ? dj.by : null;
        p.keyClash = keyClash;
        p.mixOutTrackTime = mixOut;
        p.durationSec = dur;
        p.mixInTrackTime = mixIn;
        p.rate = rate;
        p.outRate = outRate;
        p.rampBeats = rampBeats;
        p.rampSongSec = rampSongSec;
        p.beatSec = fromGrid.beatSec;
        p.bars = effBars;
        p.swapDb = effBars >= FULL_SWAP_MIN_BARS ? KILL_DB : SHORT_BLEND_KILL_DB;
        p.barSec = fromGrid.barSec / outRate;
        p.bpm = fromGrid.bpm * outRate;
        p.outBpm = fromGrid.bpm;
        p.inBpm = toGrid.bpm;
        return p;
    }

    /** The phone's DJ_FILTER: incoming on its own downbeat at its own tempo. */
    static Plan filterMix(Settings s, Analysis cur, Analysis next, Grid fromGrid, Grid toGrid, int requested, double pos,
                          double earliest, String reason, Sung sung) {
        int bars = Math.min(requested, FILTER_MAX_BARS);
        double dur = bars * fromGrid.barSec;
        boolean v2 = s.version == 2;
        double mixOut = naturalMixOut(fromGrid, v2 ? musicEnd(cur) : cur, dur);
        if (mixOut < pos) mixOut = phraseAtOrAfter(fromGrid, earliest);
        if (mixOut < earliest) mixOut = downbeatAtOrAfter(fromGrid, earliest);
        double room = cur.durationSec - 0.3 - mixOut;
        if (dur > room) {
            bars = (int) Math.max(1, Math.floor(room / fromGrid.barSec));
            dur = bars * fromGrid.barSec;
        }
        if (room < fromGrid.barSec) return fallback(cur, next, earliest, reason);
        double mixIn = toGrid != null ? toGrid.downbeatSec : Math.min(Double.isNaN(next.introEndSec) ? 0 : next.introEndSec, 1);
        double clash = 0;
        if (v2) {
            double o = vocalOverlap(cur, next, mixOut, dur, mixIn, 1, sung);
            clash = Double.isNaN(o) ? 0 : o;
        }
        Plan p = new Plan();
        p.style = "filter";
        p.reason = reason;
        p.v2 = v2;
        p.clash = clash;
        p.mixOutTrackTime = mixOut;
        p.durationSec = dur;
        p.mixInTrackTime = mixIn;
        p.rate = 1;
        p.bars = bars;
        p.swapDb = KILL_DB;
        p.barSec = fromGrid.barSec;
        p.bpm = fromGrid.bpm;
        p.inBpm = toGrid != null ? toGrid.bpm : Double.NaN;
        return p;
    }

    // ───────── AutoDJ (Automix version 2): BitChord phone ideas ─────────
    static final double VOCAL_SUNG = 0.25;
    static final double REROUTE_OVERLAP = 0.3;
    static final double REROUTE_GAIN = 0.5;
    static final double MAX_DISCARD_SEC = 12;
    static final double MAX_IN_SKIP = 0.25;
    static final int[] ENTRY_SHIFTS = {0, -4, 4, -8, 8};
    static final double KEY_MIN_CONFIDENCE = 0.5;

    static String keysClash(Analysis a, Analysis b) {
        if (a.camelotNum <= 0 || b.camelotNum <= 0 || !(a.keyConfidence >= KEY_MIN_CONFIDENCE) || !(b.keyConfidence >= KEY_MIN_CONFIDENCE)) return null;
        int step = ((a.camelotNum - b.camelotNum) % 12 + 12) % 12;
        boolean ok = a.camelotNum == b.camelotNum || (a.camelotLetter == b.camelotLetter && (step == 1 || step == 11));
        if (ok) return null;
        return (a.keyName != null ? a.keyName : a.camelotNum + "" + a.camelotLetter) + " → "
                + (b.keyName != null ? b.keyName : b.camelotNum + "" + b.camelotLetter);
    }

    static int keyClashBars(Grid g) {
        return 2 * g.barSec >= (g.bpm >= 140 ? 6 : 4) ? 2 : 4;
    }

    static Analysis musicEnd(Analysis a) {
        double end = a.contentEndSec;
        if (!(end > 0) || end >= a.durationSec - 1) return a;
        Analysis o = a.copy();
        o.durationSec = Math.max(end, a.durationSec - 20);
        return o;
    }

    static double capIncomingSkip(Analysis next, Grid toGrid, double mixIn) {
        double cap = next.durationSec > 0 ? next.durationSec * MAX_IN_SKIP : Double.POSITIVE_INFINITY;
        if (mixIn <= cap) return mixIn;
        return Math.max(toGrid.downbeatSec, mixIn - Math.ceil((mixIn - cap) / toGrid.barSec) * toGrid.barSec);
    }

    /** Sung or not at song second t: 1/0 from lyric spans, else the mask; NaN where neither knows. */
    static double vocalAt(Analysis a, double t, double[][] spans) {
        if (spans != null) {
            for (double[] x : spans) if (t >= x[0] && t < x[1]) return 1;
            return 0;
        }
        for (Analysis.VocalWindow w : a.vocals) {
            long i = Math.round((t - w.startSec) * w.fps);
            if (i >= 0 && i < w.curve.length) return w.curve[(int) i];
        }
        return Double.NaN;
    }

    /** The share of the blend where both sing; NaN when the sources cover under half of it. */
    static double vocalOverlap(Analysis cur, Analysis next, double mixOut, double outSpan, double mixIn, double inPerOut, Sung sung) {
        int both = 0, n = 0, steps = 0;
        for (double t = mixOut; t < mixOut + outSpan; t += 0.05) {
            steps++;
            double a = vocalAt(cur, t, sung.out), b = vocalAt(next, mixIn + (t - mixOut) * inPerOut, sung.in);
            if (Double.isNaN(a) || Double.isNaN(b)) continue;
            n++;
            if (a >= VOCAL_SUNG && b >= VOCAL_SUNG) both++;
        }
        return steps > 0 && n >= steps * 0.5 ? both / (double) n : Double.NaN;
    }

    static final class Reroute {
        double mixOut, mixIn, clash;
        String rerouted, by;
    }

    static Reroute rerouteForVocals(Analysis cur, Analysis next, Grid fromGrid, Grid toGrid, double mixOut, double mixIn,
                                    double outSpan, double inPerOut, double earliestOut, Sung sung) {
        String by = (sung.out != null ? "lyrics" : "mask") + "/" + (sung.in != null ? "lyrics" : "mask");
        double primary = vocalOverlap(cur, next, mixOut, outSpan, mixIn, inPerOut, sung);
        Reroute keep = new Reroute();
        keep.mixOut = mixOut;
        keep.mixIn = mixIn;
        keep.clash = Double.isNaN(primary) ? 0 : primary;
        keep.by = by;
        if (Double.isNaN(primary) || primary <= REROUTE_OVERLAP) return keep;
        double inCap = next.durationSec > 0 ? next.durationSec * MAX_IN_SKIP : Double.POSITIVE_INFINITY;
        double bestO = Double.NaN, bestCost = 0, bestMo = 0, bestMi = 0;
        int bestK = 0, bestJ = 0;
        for (int k = 0; k * fromGrid.barSec <= MAX_DISCARD_SEC; k++) {
            for (int j : ENTRY_SHIFTS) {
                if (k == 0 && j == 0) continue;
                double mi = mixIn + j * toGrid.barSec;
                if (mi < toGrid.downbeatSec - 1e-6 || mi > inCap) continue;
                double mo = mixOut - k * fromGrid.barSec;
                double o = vocalOverlap(cur, next, mo, outSpan, mi, inPerOut, sung);
                if (Double.isNaN(o)) continue;
                if (mo < earliestOut) continue;
                double cost = k + Math.abs(j) / 2.0;
                if (Double.isNaN(bestO) || o < bestO - 1e-9 || (Math.abs(o - bestO) < 1e-9 && cost < bestCost)) {
                    bestO = o;
                    bestCost = cost;
                    bestMo = mo;
                    bestMi = mi;
                    bestK = k;
                    bestJ = j;
                }
            }
        }
        if (Double.isNaN(bestO) || bestO > primary * REROUTE_GAIN) {
            keep.rerouted = "both sing " + pct(primary) + " of it, no cleaner point";
            return keep;
        }
        Reroute r = new Reroute();
        r.mixOut = bestMo;
        r.mixIn = bestMi;
        r.clash = bestO;
        r.by = by;
        r.rerouted = "both sang " + pct(primary) + " of it, moved to " + pct(bestO) + " (exit " + bestK + " bars earlier, cue "
                + (bestJ >= 0 ? "+" : "") + bestJ + " bars)";
        return r;
    }

    private static String pct(double x) {
        return Math.round(x * 100) + "%";
    }

    /** AutoMix's fallback: a plain 12 s equal-power fade that ends with the song. */
    static Plan fallback(Analysis cur, Analysis next, double earliest, String reason) {
        double target = cur.durationSec - 0.3 - FALLBACK_SEC;
        double mixOut = Math.max(earliest, target);
        double dur = Math.max(0, Math.min(FALLBACK_SEC, cur.durationSec - 0.3 - mixOut));
        Plan p = new Plan();
        p.style = "crossfade";
        p.fallback = true;
        p.reason = reason;
        p.mixOutTrackTime = mixOut;
        p.durationSec = dur;
        p.mixInTrackTime = Double.isNaN(next.introEndSec) ? 0 : next.introEndSec;
        p.rate = 1;
        p.bars = 0;
        p.swapDb = 0;
        p.barSec = 0;
        return p;
    }

    public static boolean gridOk(Analysis a) {
        return gridOf(a) != null && a.gridConfidence >= MIN_GRID_CONFIDENCE;
    }

    // ───────── the loop mix (BitChord engine/loopMix.ts) ─────────
    static final int LOOP_BARS = 4;
    static final int[] LOOP_LENGTHS = {4, 2, 1};
    static final int ENTER_BARS = 4;
    static final int SETTLE_BARS = 2;
    static final double MAX_BPM_PER_BAR = 2.5;
    static final int MIN_RAMP_BARS = 2, MAX_RAMP_BARS = 24;
    static final double VOCAL_ACTIVE = 0.25;
    static final double MAX_SUNG_SHARE = 0.03;
    static final double MAX_SUNG_RUN_SEC = 0.2;
    static final double LYRIC_GUARD_SEC = 0.3;
    public static final double LYRIC_LEAD_SEC = 0.15, LYRIC_TAIL_SEC = 0.35;
    static final double MIN_LOUDNESS = 0.6;
    static final double OUT_SEARCH_SEC = 60, IN_SEARCH_SEC = 45;
    public static final double OUT_REGION_SEC = OUT_SEARCH_SEC + 5, IN_REGION_SEC = IN_SEARCH_SEC + 40;
    public static final double LOOP_SETUP_SEC = 15;
    public static final double HANDOVER_SEC = 0.025;
    public static final double BASS_CUT_DB = -18;

    /** A decoded song for the loop search: its loudness (channel 0, the extension's rms) and length. */
    public interface Audio {
        double duration();

        double rms(double from, double to);
    }

    public static final class LoopSide {
        public String key;
        public double loopStart, loopEnd;
        public int bars;
        public double beatSec, bpm, vocal;
        public String checkedBy;
    }

    public static final class LoopPlan {
        public LoopSide out, in;
        public double fold;
        public int enterBars, rampBars, totalBars;
        public double exitBars;
        /** Set when a side has no instrumental bars. */
        public String none;

        @Override
        public String toString() {
            if (none != null) return "no loop mix: " + none;
            return String.format(Locale.US, "loop mix: %d-bar loop at %.2f s (%.1f BPM, %s) into a %d-bar loop at %.2f s (%.1f BPM, %s), ramp %d bars, %d bars in all",
                    out.bars, out.loopStart, out.bpm, "lyrics".equals(out.checkedBy) ? "no lyric line" : String.format(Locale.US, "sung %.1f%%", out.vocal * 100),
                    in.bars, in.loopStart, in.bpm, "lyrics".equals(in.checkedBy) ? "no lyric line" : String.format(Locale.US, "sung %.1f%%", in.vocal * 100),
                    rampBars, totalBars);
        }
    }

    /** null when there is no usable grid or a music video; a plan with [none] when a side has no instrumental bars. */
    public static LoopPlan planLoopMix(Analysis out, Audio outBuffer, Analysis inn, Audio inBuffer, double earliestOutSec, Sung sung) {
        if (!(out.beatIntervalSec > 0) || Double.isNaN(out.firstDownbeatSec) || !(out.bpm > 0)) return null;
        if (!(inn.beatIntervalSec > 0) || Double.isNaN(inn.firstDownbeatSec) || !(inn.bpm > 0)) return null;
        if (out.musicVideo || inn.musicVideo) return null;
        if (sung == null) sung = new Sung();
        double outBar = out.beatIntervalSec * 4;
        double inBar = inn.beatIntervalSec * 4;

        double outEnd = Math.min(out.durationSec - 0.5, outBuffer.duration() - 0.1);
        double outFirst = Math.max(earliestOutSec, out.durationSec - OUT_SEARCH_SEC);
        Clean outPick = firstClean(bars -> {
            List<double[]> l = loopsBetween(out.tailBeats, out.firstDownbeatSec, outBar, outFirst, outEnd, bars);
            Collections.reverse(l);
            return l;
        }, outBuffer, out.vocals, sung.out, out.beatIntervalSec);
        if (outPick == null) return none("no instrumental bars near the end of the playing song");
        double inEnd = Math.min(IN_SEARCH_SEC + LOOP_BARS * inBar, inBuffer.duration() - 20);
        Clean inPick = firstClean(bars -> loopsBetween(inn.headBeats, inn.firstDownbeatSec, inBar, 0, inEnd, bars),
                inBuffer, inn.vocals, sung.in, inn.beatIntervalSec);
        if (inPick == null) return none("no instrumental bars in the opening of the next song");
        double outBeat = (outPick.end - outPick.start) / (outPick.bars * 4);
        double inBeat = (inPick.end - inPick.start) / (inPick.bars * 4);
        double outBpm = 60 / outBeat, inBpm = 60 / inBeat;
        double fold = 1;
        for (double f : new double[]{2, 0.5}) {
            if (Math.abs(Math.log(inBpm / f / outBpm)) < Math.abs(Math.log(inBpm / fold / outBpm))) fold = f;
        }
        double target = inBpm / fold;
        int rampBars = (int) Math.min(MAX_RAMP_BARS, Math.max(MIN_RAMP_BARS, Math.ceil(Math.abs(target - outBpm) / MAX_BPM_PER_BAR)));
        double inLoopOutBars = inPick.bars / fold;
        double totalBars = Math.ceil((ENTER_BARS + rampBars + 2) / inLoopOutBars) * inLoopOutBars;
        LoopPlan p = new LoopPlan();
        p.out = side(out.fileKey, outPick, outBeat, outBpm);
        p.in = side(inn.fileKey, inPick, inBeat, inBpm);
        p.fold = fold;
        p.enterBars = ENTER_BARS;
        p.rampBars = rampBars;
        p.totalBars = (int) Math.round(totalBars);
        p.exitBars = totalBars - ENTER_BARS - rampBars;
        return p;
    }

    private static LoopPlan none(String why) {
        LoopPlan p = new LoopPlan();
        p.none = why;
        return p;
    }

    private static LoopSide side(String key, Clean c, double beatSec, double bpm) {
        LoopSide s = new LoopSide();
        s.key = key;
        s.loopStart = c.start;
        s.loopEnd = c.end;
        s.bars = c.bars;
        s.beatSec = beatSec;
        s.bpm = bpm;
        s.vocal = c.vocal;
        s.checkedBy = c.checkedBy;
        return s;
    }

    /** [bars]-bar loops in [from, to]: on the model's own downbeats where it has them, else the constant grid. */
    static List<double[]> loopsBetween(Analysis.BeatSide side, double anchor, double bar, double from, double to, int bars) {
        List<double[]> loops = new ArrayList<>();
        if (side != null && side.downbeats.length >= bars + 1) {
            double beat = 60 / side.bpm;
            double[] downs = side.downbeats;
            for (double start : downs) {
                if (start < from) continue;
                double target = start + bars * 4 * beat;
                double end = downs[0];
                for (double d : downs) if (Math.abs(d - target) < Math.abs(end - target)) end = d;
                if (Math.abs(end - target) > beat / 4 || end > to) continue;
                loops.add(new double[]{start, end});
            }
            if (!loops.isEmpty()) return loops;
        }
        if (to < from) return loops;
        double len = bars * bar;
        for (double t = anchor + Math.ceil((from - anchor) / bar) * bar; t + len <= to; t += bar) loops.add(new double[]{t, t + len});
        return loops;
    }

    interface Candidates {
        List<double[]> of(int bars);
    }

    static final class Clean {
        double start, end, vocal;
        int bars;
        String checkedBy;
    }

    static Clean firstClean(Candidates candidates, Audio buffer, List<Analysis.VocalWindow> vocals, double[][] sung, double beatSec) {
        for (int bars : LOOP_LENGTHS) {
            List<double[]> loops = candidates.of(bars);
            if (loops.isEmpty()) continue;
            double[] louds = new double[loops.size()];
            for (int i = 0; i < louds.length; i++) {
                double[] l = loops.get(i);
                double min = Double.POSITIVE_INFINITY;
                for (int b = 0; b < bars; b++) {
                    min = Math.min(min, buffer.rms(l[0] + (b * (l[1] - l[0])) / bars, l[0] + ((b + 1) * (l[1] - l[0])) / bars));
                }
                louds[i] = min;
            }
            double[] sorted = louds.clone();
            Arrays.sort(sorted);
            int at = (int) Math.floor(louds.length * 0.6);
            double typical = at < sorted.length ? sorted[at] : 0;
            for (int i = 0; i < loops.size(); i++) {
                double[] l = loops.get(i);
                if (louds[i] < typical * MIN_LOUDNESS || louds[i] <= 0.01) continue;
                if (sung != null) {
                    double from = l[0] - LYRIC_GUARD_SEC;
                    boolean hit = false;
                    for (double[] x : sung) if (x[0] < l[1] && x[1] > from) { hit = true; break; }
                    if (hit) continue;
                    Clean c = new Clean();
                    c.start = l[0];
                    c.end = l[1];
                    c.bars = bars;
                    c.vocal = 0;
                    c.checkedBy = "lyrics";
                    return c;
                }
                double[] m = maskStats(vocals, l[0] - beatSec, l[1]);
                if (m == null || m[0] > MAX_SUNG_SHARE || m[1] > MAX_SUNG_RUN_SEC) continue;
                Clean c = new Clean();
                c.start = l[0];
                c.end = l[1];
                c.bars = bars;
                c.vocal = m[0];
                c.checkedBy = "mask";
                return c;
            }
        }
        return null;
    }

    /** {share sung, longest sung run s}, or null when the mask covers under 80% of [from, to). */
    static double[] maskStats(List<Analysis.VocalWindow> windows, double from, double to) {
        int sung = 0, n = 0, run = 0, longest = 0;
        double covered = 0, fps = 1;
        for (Analysis.VocalWindow w : windows) {
            int a = (int) Math.max(0, Math.ceil((from - w.startSec) * w.fps));
            int b = (int) Math.min(w.curve.length, Math.floor((to - w.startSec) * w.fps));
            for (int i = a; i < b; i++) {
                n++;
                if (w.curve[i] > VOCAL_ACTIVE) {
                    sung++;
                    run++;
                    longest = Math.max(longest, run);
                } else {
                    run = 0;
                }
            }
            if (b > a) {
                covered += (b - a) / w.fps;
                fps = w.fps;
            }
        }
        return n > 0 && covered >= (to - from) * 0.8 ? new double[]{sung / (double) n, longest / fps} : null;
    }

    /** One deck tempo/position change (the extension's LoopDeck schedule entry). */
    public static final class Change {
        public double output, input, rate;
        public boolean active;
        public double[] loop;

        Change(double output, double input, double rate, boolean active, double[] loop) {
            this.output = output;
            this.input = input;
            this.rate = rate;
            this.active = active;
            this.loop = loop;
        }
    }

    public static final class LoopSchedule {
        public double t0;
        public double[] beatTimes;
        public List<Change> outChanges = new ArrayList<>(), inChanges = new ArrayList<>();
        public double releaseAt, elementAt, elementTrackTime, enterEnd, rampEnd, mid, bar, handoffAt;
        public int beats, settleBeats, rampFrom, rampTo;
    }

    /** The whole loop mix on one beat clock from [t0] (the audio time the outgoing song reaches its loop start). */
    public static LoopSchedule loopSchedule(LoopPlan plan, double t0) {
        LoopSide out = plan.out, inn = plan.in;
        double fold = plan.fold;
        int beats = plan.totalBars * 4;
        int settleBeats = (int) Math.round((SETTLE_BARS * 4) / fold);
        int rampFrom = plan.enterBars * 4;
        int rampTo = (plan.enterBars + plan.rampBars) * 4;
        double target = inn.bpm / fold;
        double[] beatTimes = new double[beats + settleBeats + 1];
        beatTimes[0] = t0;
        for (int k = 0; k < beats + settleBeats; k++) beatTimes[k + 1] = beatTimes[k] + 60 / tempo(k, rampFrom, rampTo, out.bpm, target);
        int outLoopBeats = out.bars * 4;
        int inLoopBeats = inn.bars * 4;
        double inPerBeat = fold * inn.beatSec;
        LoopSchedule x = new LoopSchedule();
        double[] outLoop = {out.loopStart, out.loopEnd}, inLoop = {inn.loopStart, inn.loopEnd};
        for (int k = 0; k < beats; k++) {
            double t = beatTimes[k], T = tempo(k, rampFrom, rampTo, out.bpm, target);
            x.outChanges.add(new Change(t, out.loopStart + (k % outLoopBeats) * out.beatSec, (T * out.beatSec) / 60, true, outLoop));
            x.inChanges.add(new Change(t, inn.loopStart + ((k * fold) % inLoopBeats) * inn.beatSec, (T * inPerBeat) / 60, true, inLoop));
        }
        double releaseAt = beatTimes[beats];
        x.outChanges.add(new Change(releaseAt + 0.05, out.loopStart, 1, false, null));
        x.inChanges.add(new Change(releaseAt, inn.loopEnd, 1, true, null));
        double elementAt = beatTimes[beats + settleBeats];
        double elementTrackTime = inn.loopEnd + (elementAt - releaseAt);
        x.inChanges.add(new Change(elementAt + 0.1, elementTrackTime + 0.1, 1, false, null));
        int midBeat = (int) Math.round((rampFrom + rampTo) / 2.0);
        x.t0 = t0;
        x.beatTimes = beatTimes;
        x.releaseAt = releaseAt;
        x.elementAt = elementAt;
        x.elementTrackTime = elementTrackTime;
        x.enterEnd = beatTimes[rampFrom];
        x.rampEnd = beatTimes[rampTo];
        x.mid = beatTimes[midBeat];
        x.bar = 4 * (60 / tempo(midBeat, rampFrom, rampTo, out.bpm, target));
        x.handoffAt = beatTimes[midBeat];
        x.beats = beats;
        x.settleBeats = settleBeats;
        x.rampFrom = rampFrom;
        x.rampTo = rampTo;
        return x;
    }

    static double tempo(int k, int rampFrom, int rampTo, double outBpm, double target) {
        return k < rampFrom ? outBpm : k >= rampTo ? target : outBpm + ((target - outBpm) * (k - rampFrom + 0.5)) / (rampTo - rampFrom);
    }
}
