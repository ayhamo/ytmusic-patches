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
        /** 1: Original AutoDJ, 2: Advanced AutoDJ, 3: Automix v2 (AutoDJ plus Orchard's rules). */
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

    public static final class Plan implements Cloneable {
        public String style;            // blend | filter | crossfade | loop
        public boolean fallback;
        public String reason;
        /** The editor's preset whose curves replaced the planner's (editor.md §9), or null. */
        public String preset;
        /** What an override asked for that the planner would have refused or had to move (editor.md §6). */
        public List<String> warnings;
        /** The mix-in was placed in the editor: Automix v2's kick lock leaves it (editor.md §6). */
        public boolean windowIn;
        public boolean v2;
        /** Automix v2's blend (its evidence fields below are set). */
        public boolean v3;
        /** Automix v2's hand-over (the boundary switch) or beatless bridge, both crossfades. */
        public boolean handoff, bridge;
        /** The fallback ladder's shape: dropin | muffle, or null. */
        public String shape;
        public double muffleLead = Double.NaN;
        public double subBassContrast = Double.NaN, swapAt = Double.NaN;
        public String swapMoved;
        public Glide glide;
        public Harmonic harmonic;
        public Trim trim;
        public double clash;
        public String rerouted, vocalsBy, keyClash;
        public double mixOutTrackTime, durationSec, mixInTrackTime;
        public double rate = 1, outRate = 1;
        public int rampBeats;
        public double rampSongSec, beatSec = Double.NaN;
        public int bars;
        public double swapDb, barSec, bpm = Double.NaN, outBpm = Double.NaN, inBpm = Double.NaN;
        public LoopPlan loop;
        /** What each deck does over the transition (spec/automix/plan.schema.json "curves"). */
        public Curves curves;
        /** Automix v2's kick lock applied to mixInTrackTime, or null. */
        public KickLock.Result kick;

        /** A shallow copy (the kick lock moves the copy's cue). */
        public Plan copy() {
            try {
                return (Plan) clone();
            } catch (CloneNotSupportedException e) {
                throw new AssertionError(e);
            }
        }

        @Override
        public String toString() {
            if (loop != null) return loop.toString();
            StringBuilder b = new StringBuilder();
            b.append(String.format(Locale.US, "%s from %.2f s for %.2f s", style, mixOutTrackTime, durationSec));
            if (!style.equals("crossfade")) b.append(String.format(Locale.US, ", %d bars at %.1f BPM", bars, bpm));
            b.append(String.format(Locale.US, ", next song from %.2f s at x%.3f", mixInTrackTime, rate));
            if (outRate > 1.0001) b.append(String.format(Locale.US, ", playing song up to x%.4f over %d beats", outRate, rampBeats));
            if (shape != null) b.append(", ").append(shape);
            if (glide != null) b.append(String.format(Locale.US, ", glide x%.4f over %d beats", glide.q, glide.beats));
            if (trim != null && (trim.outDb != 0 || trim.inDb != 0)) b.append(String.format(Locale.US, ", trim out %.1f / in %.1f dB", trim.outDb, trim.inDb));
            if (reason != null) b.append(" (").append(reason).append(')');
            if (preset != null || warnings != null) {
                b.append(" [editor: ").append(preset != null ? preset : "auto");
                if (warnings != null) for (String w : warnings) b.append("; ").append(w);
                b.append(']');
            }
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
        return plan(s, fromId, toId, from, to, durationSec, pos, earliest, sung, null);
    }

    /**
     * With the editor's override for this pair (editor.md §9; a pick from before the editor is
     * migrated as it's read). Its length comes before the setting; a preset other than Auto
     * replaces the plan's gain, shelves and filters, and the planner's tempo stays.
     */
    public static Plan plan(Settings s, String fromId, String toId, Analysis from, Analysis to, double durationSec,
                            double pos, double earliest, Sung sung, MixOverride ov) {
        int pinned = ov != null ? ov.pinnedBars() : 0;
        if (pinned > 0) {
            Settings p = new Settings();
            p.version = s.version;
            p.keyAware = s.keyAware;
            p.bars = pinned;
            s = p;
        }
        List<String> warnings = new ArrayList<>();
        Plan p = planFor(s, fromId, toId, from, to, durationSec, pos, earliest, sung, ov, warnings);
        p.curves = Curves.blend(p);
        if (ov != null && !ov.loop()) {
            if (!warnings.isEmpty()) p.warnings = warnings;
            if (ov.in != null) p.windowIn = true; // placed by the user: the kick lock leaves it
            boolean both = from != null && to != null;
            Curves pc = !(p.fallback && !both) ? MixOverride.presetCurves(ov, p.durationSec, p.style.equals("crossfade") ? Double.NaN : p.beatSec) : null;
            if (pc != null) {
                // the preset's gain, shelves and filters; the planner's tempo stays
                if (p.curves.out.containsKey("rate")) pc.out.put("rate", p.curves.out.get("rate"));
                if (p.curves.in.containsKey("rate")) pc.in.put("rate", p.curves.in.get("rate"));
                p.curves = pc;
                p.preset = ov.preset;
                // the phone: Morphe's crossfade can't play a preset, our decks can
                if (both) p.fallback = false;
            }
        }
        return p;
    }

    /**
     * Beat match Off (or Sync/Glide out of range): both songs at their own speed, overlapping over
     * the window and length asked for; with neither asked for, the plain crossfade at the song's end.
     */
    static Plan overlap(Analysis cur, Analysis next, Grid fromGrid, MixOverride ov, double earliest, String reason, List<String> warnings) {
        Plan base = fallback(cur, next, earliest, reason);
        MixOverride.Resolved outW = ov != null ? MixOverride.resolveWindow(ov.out, null) : null;
        MixOverride.Resolved inW = ov != null ? MixOverride.resolveWindow(ov.in, null) : null;
        // the length asked for, or the preset's own (Slam: 2 bars)
        int bars = ov != null ? ov.pinnedBars() : 0;
        if (bars == 0 && outW == null && inW == null) return base;
        double end = musicEnd(cur).durationSec, room = end - 0.3 - earliest;
        if (room < 1) {
            if (warnings != null) warnings.add("no room left in this song for the window asked for");
            return base;
        }
        double want = bars > 0 && fromGrid != null ? bars * fromGrid.barSec : base.durationSec > 0 ? base.durationSec : FALLBACK_SEC;
        if (bars > 0 && fromGrid != null && want > room + 0.01 && warnings != null) {
            warnings.add(String.format(Locale.US, "%d bars don't fit before the end: %.1f s", bars, room));
        }
        double D = Math.min(want, room), hi = Math.max(earliest, end - 0.3 - D);
        double mixOut = hi;
        if (outW != null && outW.sec < earliest - 0.01) {
            if (warnings != null) warnings.add(playedNote(outW.sec));
        } else if (outW != null) {
            mixOut = clampWindow(outW.sec, earliest, hi, "the mix-out", warnings, fromGrid);
        }
        double mixIn = inW != null ? clampWindow(inW.sec, 0, Math.max(0, next.durationSec - 10 - D), "the mix-in", warnings, gridOf(next)) : base.mixInTrackTime;
        base.fallback = false;
        base.mixOutTrackTime = mixOut;
        base.durationSec = D;
        base.mixInTrackTime = mixIn;
        base.trim = loudnessTrim(cur, mixOut, D, next, mixIn, D);
        return base;
    }

    /**
     * A saved position held inside [lo, hi]; one past a limit moves to the nearest downbeat inside
     * it (the clamped second itself with no grid there), with the reason shown (editor.md §6).
     */
    static double clampWindow(double t, double lo, double hi, String what, List<String> warnings, Grid grid) {
        double c = clamp(t, lo, hi);
        if (Math.abs(c - t) <= 0.01) return c;
        if (grid != null && grid.barSec > 0) {
            double d = t < lo ? downbeatAtOrAfter(grid, lo) : downbeatAtOrBefore(grid, hi);
            if (d >= lo - 1e-6 && d <= hi + 1e-6) c = d;
        }
        if (warnings != null) {
            warnings.add(String.format(Locale.US, "%s moved to %.1f s (%s)", what, c, t < lo ? "before the song" : "too close to the end"));
        }
        return c;
    }

    /** A saved mix-out the playhead has passed: the planner's own plays (editor.md §6). */
    static String playedNote(double sec) {
        return String.format(Locale.US, "the mix-out at %.1f s has already played: Auto's mix-out instead", sec);
    }

    /** May Automix v2's kick lock move this plan's mix-in (automix.js kickLockAllowed): a blend not placed in the editor. */
    public static boolean kickLockAllowed(Plan p) {
        return p != null && "blend".equals(p.style) && !p.windowIn;
    }

    private static Plan planFor(Settings s, String fromId, String toId, Analysis from, Analysis to, double durationSec,
                                double pos, double earliest, Sung sung, MixOverride pick, List<String> warnings) {
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
        boolean forced = pick != null && pick.forced();
        String bm = pick != null ? pick.beatMatchOrAuto() : "auto";
        String family = pick != null ? pick.family : null;

        if (bm.equals("off") && both) {
            return overlap(cur, next, fromGrid, pick, earliest, family != null || pick.migrated ? "picked in the overview" : "beat match off", warnings);
        }
        boolean v3 = s.version == 3;
        if (fromGrid == null || cur.gridConfidence < MIN_GRID_CONFIDENCE) {
            if (forced && both) {
                warnings.add("no beat grid at this song's end: plays without beat match");
                return overlap(cur, next, null, pick, earliest, "this song has no beat grid at its end", warnings);
            }
            if (v3 && both) return handoff(cur, next, earliest, "this song has no beat grid at its end", sung);
            return fallback(cur, next, earliest, both ? "this song has no beat grid at its end" : "songs not analysed in time");
        }
        boolean toWeak = toGrid == null || next.gridConfidence < MIN_GRID_CONFIDENCE;
        if (forced && both && toWeak) {
            warnings.add("the next song has no beat grid: plays without beat match");
            return overlap(cur, next, fromGrid, pick, earliest, "next song has no beat grid", warnings);
        }
        if (both && (bm.equals("sync") || bm.equals("glide"))) {
            double st = stretchOf(foldRate(fromGrid.bpm / toGrid.bpm));
            if (st > V2_MAX_STRETCH) {
                String why = "tempos " + Math.round(st * 100) + "% apart: " + (bm.equals("sync") ? "Sync" : "Glide") + " stretches at most "
                        + Math.round(V2_MAX_STRETCH * 100) + "%, so this plays without beat match";
                warnings.add(why);
                return overlap(cur, next, fromGrid, pick, earliest, why, warnings);
            }
        }
        if ("filter".equals(family)) {
            Grid g = !toWeak ? toGrid : null;
            return filterMix(s, cur, next, fromGrid, g, requestedBars(s, cur, next, fromGrid, toGrid != null ? toGrid.downbeatSec : 0, true), pos, earliest,
                    "picked in the overview", sung);
        }
        if (v3 && toWeak) return handoff(cur, next, earliest, "next song has no beat grid", sung);
        if (toWeak) {
            return filterMix(s, cur, next, fromGrid, null, requestedBars(s, cur, next, fromGrid, 0, true), pos, earliest, "next song has no beat grid", sung);
        }

        double ratio = foldRate(fromGrid.bpm / toGrid.bpm);
        // v2: never more than V2_MAX_STRETCH of speed-up, never two 808 styles at once, never
        // keys that clash badly; it can't beatmatch those, so it hands over instead (an override
        // is the user's choice: the style gates warn instead)
        if (v3 && stretchOf(ratio) > V2_MAX_STRETCH) {
            String why = "tempos " + Math.round(stretchOf(ratio) * 100) + "% apart (v2 stretches at most " + Math.round(V2_MAX_STRETCH * 100) + "%)";
            if (!forced && (pick == null || pick.bars == 0)) return handoff(cur, next, earliest, why, sung);
            // the user's preset, length or window: played as asked without beat match (editor.md §6)
            warnings.add(why + ": plays without beat match");
            return overlap(cur, next, fromGrid, pick, earliest, why, warnings);
        }
        double contrast = subBassContrast(cur, next);
        if (v3 && contrast >= V2_SUB_BASS_VETO) {
            if (!forced) return handoff(cur, next, earliest, "different sub-bass styles (" + String.format(Locale.US, "%.2f", contrast) + " apart)", sung);
            warnings.add("the productions clash (sub-bass " + String.format(Locale.US, "%.2f", contrast) + " apart)");
        }
        Harmonic harmonic = v3 ? harmonicEvidence(cur, next) : null;
        if (harmonic != null && harmonic.severeClash) {
            if (!forced) return handoff(cur, next, earliest, "the keys clash (" + cur.keyName + " → " + next.keyName + ", " + js(harmonic.score) + ")", sung);
            warnings.add("the keys clash (" + cur.keyName + " → " + next.keyName + ")");
        }
        if (!stretchAllowed(ratio)) {
            String why = "tempos " + Math.round(Math.abs(ratio - 1) * 100) + "% apart";
            if (!forced) return filterMix(s, cur, next, fromGrid, toGrid, requestedBars(s, cur, next, fromGrid, toGrid.downbeatSec, true), pos, earliest, why, sung);
            warnings.add(why + ": too far to stretch, plays without beat match");
            return overlap(cur, next, fromGrid, pick, earliest, why, warnings);
        }
        // Tempo only moves up: the next song faster, the playing one is brought up to it before the
        // blend; slower, it is sped up. Automix v2: over 4% apart, a glide on both songs instead
        // (glideSteps). The editor's Sync: always the speed-up; Glide: always the glide.
        double glideQ = bm.equals("sync") ? Double.NaN
                : bm.equals("glide") ? (Math.abs(ratio - 1) > 1e-4 ? 1 / ratio : Double.NaN)
                : v3 && stretchOf(ratio) > V2_MAX_CONSTANT ? 1 / ratio : Double.NaN;
        boolean gliding = !Double.isNaN(glideQ);
        double rate = gliding ? 1 : ratio < 1 ? 1 : ratio;
        double outRate = gliding ? 1 : ratio < 1 ? 1 / ratio : 1;
        int rampBeats = outRate > 1.0001 ? (int) Math.ceil((outRate - 1) / TEMPO_STEP_PER_BEAT) : 0;
        double rampSongSec = rampBeats * fromGrid.beatSec;
        // AutoDJ's key-aware length (its own toggle); Automix v2: Orchard's key score, always on
        String keyClash = v3 ? (harmonic.shortBlend ? cur.keyName + " → " + next.keyName + ", " + js(harmonic.score) : null)
                : s.version >= 2 && s.keyAware ? keysClash(cur, next) : null;
        boolean pinnedBars = pick != null && pick.pinnedBars() > 0;
        int bars = keyClash != null && !pinnedBars ? keyClashBars(fromGrid) : requestedBars(s, cur, next, fromGrid, toGrid.downbeatSec, false);
        int effBars = effectiveBlendBars(bars, cur.gridConfidence, next.gridConfidence);
        double outSpan = effBars * fromGrid.barSec;
        GlideSteps glide = gliding ? glideSteps(glideQ, effBars * 4, fromGrid.beatSec) : null;
        double dur = glide != null ? glide.total : outSpan / outRate;
        double inPerOut = glide != null ? 1 / glideQ : rate / outRate;

        double first = earliest + rampSongSec;
        boolean v2 = s.version >= 2;
        double mixOut = naturalMixOut(fromGrid, v2 ? musicEnd(cur) : cur, outSpan);
        if (mixOut < pos + rampSongSec) mixOut = phraseAtOrAfter(fromGrid, first);
        if (mixOut < first) mixOut = downbeatAtOrAfter(fromGrid, first);
        // the editor's window (editor.md §6): A's mix-out on A's end grid, clamped to what can still play
        MixOverride.Resolved wOut = pick != null ? MixOverride.resolveWindow(pick.out, fromGrid) : null;
        if (wOut != null && wOut.sec < first - 0.01) {
            warnings.add(playedNote(wOut.sec)); // already played: the planner's own mix-out
        } else if (wOut != null) {
            mixOut = clampWindow(wOut.sec, first, Math.max(first, Math.min(musicEnd(cur).durationSec, cur.durationSec - 0.3) - outSpan), "the mix-out", warnings, fromGrid);
        }
        if (wOut != null && wOut.moved) warnings.add("the mix-out moved with a new beat grid");
        if (mixOut + outSpan > cur.durationSec - 0.3) {
            if (forced) {
                warnings.add("too close to the end for the window asked for");
                return overlap(cur, next, fromGrid, pick, earliest, "too close to the end for a blend", warnings);
            }
            if (v3) return handoff(cur, next, earliest, "too close to the end for a blend", sung);
            return filterMix(s, cur, next, fromGrid, toGrid, bars, pos, earliest, "too close to the end for a blend", sung);
        }

        double introEnd = Double.isNaN(next.introEndSec) ? 0 : next.introEndSec;
        double fullEnergyPhrase = phraseAtOrAfter(toGrid, Math.max(introEnd, toGrid.downbeatSec));
        double mixIn = fullEnergyPhrase - outSpan * inPerOut;
        boolean introShort = mixIn < toGrid.downbeatSec - 1e-6;
        if (mixIn < toGrid.downbeatSec) mixIn = toGrid.downbeatSec;

        Reroute dj = null;
        MixOverride.Resolved wIn = pick != null ? MixOverride.resolveWindow(pick.in, toGrid) : null;
        if (wIn != null) {
            mixIn = clampWindow(wIn.sec, 0, Math.max(0, next.durationSec - 10 - outSpan * inPerOut), "the mix-in", warnings, toGrid);
            if (wIn.moved) warnings.add("the mix-in moved with a new beat grid");
        }
        if (v2 && wIn == null && wOut == null) {
            mixIn = capIncomingSkip(next, toGrid, mixIn);
            dj = rerouteForVocals(cur, next, fromGrid, toGrid, mixOut, mixIn, outSpan, inPerOut, Math.max(first, pos + rampSongSec), sung);
            mixOut = dj.mixOut;
            mixIn = dj.mixIn;
        }
        // v2: the bass changes hands on a bar where the playing song isn't mid-line
        Swap swap = v3 && effBars >= 2 ? vocalSafeSwap(cur, mixOut, outSpan, effBars, sung.out) : null;

        // v2's fallback ladder for a pair that beatmatches but can't blend: a drop-in, then a
        // muffle, else the hand-over (an override plays as asked, with the reason as a warning)
        String shape = null, shapeWhy = null;
        double muffleLead = Double.NaN;
        double djClash = dj != null ? dj.clash : 0;
        if (forced && v3) {
            if (dj == null) {
                double bothSing = vocalOverlap(cur, next, mixOut, outSpan, mixIn, inPerOut, sung);
                djClash = Double.isNaN(bothSing) ? 0 : bothSing;
            }
            if (djClash > V2_CLASH_MAX) warnings.add("both sing over " + Math.round(djClash * 100) + "% of the window");
            if (introShort && wIn == null) warnings.add("no intro in the next song to blend over");
        }
        if (v3 && !forced && (introShort || djClash > V2_CLASH_MAX)) {
            String why = introShort ? "no intro in the next song to blend over" : "both would sing " + Math.round(djClash * 100) + "% of a blend";
            double dSpan = DROP_IN_BARS * fromGrid.barSec;
            GlideSteps dGlide = gliding ? glideSteps(glideQ, DROP_IN_BARS * 4, fromGrid.beatSec) : null;
            double lo = Math.max(first, pos + rampSongSec);
            double natural = naturalMixOut(fromGrid, musicEnd(cur), dSpan);
            for (int k = 0; k * fromGrid.barSec <= MAX_DISCARD_SEC; k++) {
                double o = natural - k * fromGrid.barSec;
                if (o < lo) break;
                double cut = voiceRisk(cur, o + dSpan - 1.5, o + dSpan, sung.out);
                if (!Double.isNaN(cut) && cut >= SWAP_CLEAR) continue;
                double bothSing = vocalOverlap(cur, next, o, dSpan, toGrid.downbeatSec, inPerOut, sung);
                if ((Double.isNaN(bothSing) ? 0 : bothSing) > V2_CLASH_MAX) continue;
                double dDur = dGlide != null ? dGlide.total : dSpan / outRate;
                Plan p = new Plan();
                p.style = "blend";
                p.shape = "dropin";
                p.reason = why + ": a drop-in on the next song's first beat" + (k > 0 ? " (exit " + k + " bar" + (k == 1 ? "" : "s") + " earlier, off a sung line)" : "");
                p.v2 = v2;
                p.v3 = true;
                p.clash = Double.isNaN(bothSing) ? 0 : bothSing;
                p.vocalsBy = dj != null ? dj.by : null;
                p.keyClash = keyClash;
                p.subBassContrast = contrast;
                p.glide = dGlide != null ? new Glide(glideQ, DROP_IN_BARS * 4, fromGrid.beatSec) : null;
                p.harmonic = harmonic;
                p.trim = loudnessTrim(cur, o, dSpan, next, toGrid.downbeatSec, dSpan * inPerOut);
                p.mixOutTrackTime = o;
                p.durationSec = dDur;
                p.mixInTrackTime = toGrid.downbeatSec;
                p.rate = dGlide != null ? dGlide.inRate[0] : rate;
                p.outRate = outRate;
                p.rampBeats = rampBeats;
                p.rampSongSec = rampSongSec;
                p.beatSec = fromGrid.beatSec;
                p.bars = DROP_IN_BARS;
                p.swapDb = SHORT_BLEND_KILL_DB;
                p.barSec = dGlide != null ? dDur / DROP_IN_BARS : fromGrid.barSec / outRate;
                p.bpm = dGlide != null ? fromGrid.bpm * Math.sqrt(glideQ) : fromGrid.bpm * outRate;
                p.outBpm = fromGrid.bpm;
                p.inBpm = toGrid.bpm;
                return p;
            }
            // muffle: the playing song keeps its level while a low-pass closes over it, the next
            // fades in over the bars before the bass swap and takes the bass on it
            int B = effBars * 4;
            double at = swap != null ? swap.at : 0.5;
            int leadBeats = (int) Math.min(MUFFLE_LEAD_BEATS, Math.round(at * B));
            double lead = leadBeats / (double) B;
            double inAtSwap = mixIn + at * outSpan * inPerOut, inAtLead = mixIn + (at - lead) * outSpan * inPerOut;
            double body = bassMean(cur, mixOut, mixOut + at * outSpan);
            double outRisk = voiceRisk(cur, mixOut + at * outSpan - 0.5, mixOut + at * outSpan + 0.5, sung.out);
            double inRisk = voiceRisk(next, inAtSwap - 0.5, inAtSwap + 0.5, sung.in);
            boolean swapsSingers = (Double.isNaN(outRisk) ? 0 : outRisk) >= 0.5 && (Double.isNaN(inRisk) ? 0 : inRisk) >= 0.7;
            if (!introShort && leadBeats >= MUFFLE_MIN_LEAD_BEATS && at + MUFFLE_EXIT_BEATS / (double) B <= 1 + 1e-6
                    && (Double.isNaN(body) || body >= MUFFLE_MIN_BODY) && inAtLead >= MUFFLE_MIN_OPENING_SEC && !swapsSingers) {
                shape = "muffle";
                muffleLead = lead;
                shapeWhy = why + ": a muffle (the playing song closes behind a low-pass)";
            } else {
                return handoff(cur, next, earliest, why + " and no drop-in or muffle fits", sung);
            }
        }

        Plan p = new Plan();
        p.style = "blend";
        p.reason = shapeWhy;
        p.shape = shape;
        p.muffleLead = muffleLead;
        p.v2 = v2;
        p.v3 = v3;
        p.clash = djClash;
        p.rerouted = dj != null ? dj.rerouted : null;
        p.vocalsBy = dj != null ? dj.by : null;
        p.keyClash = keyClash;
        // every blend carries its glide: Classic's too, when the editor's Glide asks for one
        p.glide = glide != null ? new Glide(glideQ, effBars * 4, fromGrid.beatSec) : null;
        if (v3) {
            p.subBassContrast = contrast;
            p.swapAt = swap != null ? swap.at : Double.NaN;
            p.swapMoved = swap != null ? swap.moved : null;
            p.harmonic = harmonic;
            p.trim = loudnessTrim(cur, mixOut, outSpan, next, mixIn, outSpan * inPerOut);
        }
        p.mixOutTrackTime = mixOut;
        p.durationSec = dur;
        p.mixInTrackTime = mixIn;
        p.rate = glide != null ? glide.inRate[0] : rate;
        p.outRate = outRate;
        p.rampBeats = rampBeats;
        p.rampSongSec = rampSongSec;
        p.beatSec = fromGrid.beatSec;
        p.bars = effBars;
        p.swapDb = effBars >= FULL_SWAP_MIN_BARS ? KILL_DB : SHORT_BLEND_KILL_DB;
        p.barSec = glide != null ? dur / effBars : fromGrid.barSec / outRate;
        p.bpm = glide != null ? fromGrid.bpm * Math.sqrt(glideQ) : fromGrid.bpm * outRate;
        p.outBpm = fromGrid.bpm;
        p.inBpm = toGrid.bpm;
        return p;
    }

    /** The phone's DJ_FILTER: incoming on its own downbeat at its own tempo. */
    static Plan filterMix(Settings s, Analysis cur, Analysis next, Grid fromGrid, Grid toGrid, int requested, double pos,
                          double earliest, String reason, Sung sung) {
        int bars = Math.min(requested, FILTER_MAX_BARS);
        double dur = bars * fromGrid.barSec;
        boolean v2 = s.version >= 2;
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

    // ───────── Automix v2 (version 3): Orchard's rules, automix.js `version === 3` ─────────
    static final double V2_MAX_STRETCH = 0.08, V2_MAX_CONSTANT = 0.04;
    static final double V2_SUB_BASS_VETO = 0.9;
    public static final double V2_SWITCH_SEC = 1.5;
    static final double SWAP_SINGING = 0.5, SWAP_CLEAR = 0.3;
    static final double V2_CLASH_MAX = 0.3;
    static final int DROP_IN_BARS = 2;
    static final int MUFFLE_LEAD_BEATS = 8, MUFFLE_MIN_LEAD_BEATS = 4, MUFFLE_EXIT_BEATS = 2;
    static final double MUFFLE_MIN_BODY = 0.3, MUFFLE_MIN_OPENING_SEC = 2;
    static final double BRIDGE_MIN_SEC = 4, BRIDGE_MAX_SEC = 12, BRIDGE_MAX_SKIP_SEC = 16, BEATLESS = 0.3;
    static final double TRUSTED_KEY = 0.65;
    static final double CLASH_CORRELATION = -0.3, CLASH_CONFIDENCE = 0.5, KEY_CONTEXT_SEC = 24;
    static final double TRIM_TOLERANCE_DB = 1, TRIM_MAX_DB = 6;
    static final double LOOP_MARGIN = 0.02;

    /** The speed-up a pair needs, however it is split. */
    static double stretchOf(double ratio) {
        return Math.max(ratio, 1 / ratio) - 1;
    }

    /** A glide as the plan states it: the folded tempo ratio (next / playing), its beats, the playing song's beat. */
    public static final class Glide {
        public final double q, beatOutSec;
        public final int beats;

        Glide(double q, int beats, double beatOutSec) {
            this.q = q;
            this.beats = beats;
            this.beatOutSec = beatOutSec;
        }
    }

    /** glideSteps: each beat's rate on both songs, its output length and start, the next song's beat. */
    public static final class GlideSteps {
        public double[] outRate, inRate, dur, at;
        public double total, beatInSec;
    }

    /**
     * Orchard's glide, one step per beat of the blend: the shared tempo moves geometrically from the
     * playing song's to the next one's; beat k plays the playing song at q^x and the next at
     * q^(x - 1), x = (k + 0.5) / beats, so both play one beat of their own in the same output time.
     */
    public static GlideSteps glideSteps(double q, int beats, double beatOutSec) {
        GlideSteps g = new GlideSteps();
        g.outRate = new double[beats];
        g.inRate = new double[beats];
        g.dur = new double[beats];
        g.at = new double[beats];
        double t = 0;
        for (int k = 0; k < beats; k++) {
            double x = (k + 0.5) / beats;
            g.outRate[k] = Math.pow(q, x);
            g.inRate[k] = Math.pow(q, x - 1);
            g.at[k] = t;
            g.dur[k] = beatOutSec / g.outRate[k];
            t += g.dur[k];
        }
        g.total = t;
        g.beatInSec = beatOutSec / q;
        return g;
    }

    /** The two songs' sub-bass ratios apart, or NaN. */
    static double subBassContrast(Analysis a, Analysis b) {
        if (a == null || b == null || Double.isNaN(a.subBassRatio) || Double.isNaN(b.subBassRatio)) return Double.NaN;
        return Math.abs(a.subBassRatio - b.subBassRatio);
    }

    // keys (Orchard pairTempoHarmony)
    public static final class Harmonic {
        public double score, confidence, keyScore = Double.NaN, chromaScore = Double.NaN;
        public boolean severeClash, shortBlend;
    }

    private static final String[] KEY_NAMES = {"C", "B#", "C#", "DB", "D", "D#", "EB", "E", "FB", "F", "E#", "F#", "GB", "G", "G#", "AB", "A", "A#", "BB", "B", "CB"};
    private static final int[] KEY_ROOTS = {0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 8, 8, 9, 10, 10, 11, 11};

    /** {root, minor ? 1 : 0}, or null. */
    static int[] parsedKey(String name) {
        if (name == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\S+)\\s+(major|minor)$", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(name.trim());
        if (!m.matches()) return null;
        String root = m.group(1).toUpperCase(Locale.ROOT).replaceFirst("♯", "#").replaceFirst("♭", "B");
        for (int i = 0; i < KEY_NAMES.length; i++) if (KEY_NAMES[i].equals(root)) return new int[]{KEY_ROOTS[i], m.group(2).equalsIgnoreCase("minor") ? 1 : 0};
        return null;
    }

    static double keyRelationship(int[] a, int[] b) {
        if (a == null || b == null) return Double.NaN;
        int cw = (b[0] - a[0] + 12) % 12, d = Math.min(cw, 12 - cw);
        if (a[1] != b[1]) {
            if ((a[1] == 0 && cw == 9) || (a[1] == 1 && cw == 3)) return 0.95;
            return d == 0 ? 0.7 : d == 5 ? 0.55 : d == 6 ? 0.05 : 0.35;
        }
        return d == 0 ? 1 : d == 5 ? 0.85 : d == 2 ? 0.65 : d == 1 ? 0.3 : d == 6 ? 0 : 0.45;
    }

    static double cosine(double[] a, double[] b) {
        if (a == null || a.length != 12 || b == null || b.length != 12) return Double.NaN;
        double dot = 0, pa = 0, pb = 0;
        for (int k = 0; k < 12; k++) {
            dot += a[k] * b[k];
            pa += a[k] * a[k];
            pb += b[k] * b[k];
        }
        return pa > 0 && pb > 0 ? clamp(dot / Math.sqrt(pa * pb), 0, 1) : Double.NaN;
    }

    /** The keys on Orchard's table (0.7) and the whole-song pitch profiles (0.3); severe under 0.2, short under 0.55. */
    static Harmonic harmonicEvidence(Analysis a, Analysis b) {
        double keyScore = keyRelationship(parsedKey(a.keyName), parsedKey(b.keyName));
        double chromaScore = cosine(a.chroma, b.chroma);
        double score = !Double.isNaN(keyScore) && !Double.isNaN(chromaScore) ? keyScore * 0.7 + chromaScore * 0.3
                : !Double.isNaN(keyScore) ? keyScore : !Double.isNaN(chromaScore) ? chromaScore : 0.5;
        double confidence = Math.sqrt(clamp(a.keyConfidence, 0, 1) * clamp(b.keyConfidence, 0, 1));
        Harmonic h = new Harmonic();
        h.score = r3(score);
        h.confidence = r3(confidence);
        h.keyScore = r3(keyScore);
        h.chromaScore = r3(chromaScore);
        h.severeClash = confidence >= TRUSTED_KEY && score < 0.2;
        h.shortBlend = confidence >= TRUSTED_KEY && score < 0.55;
        return h;
    }

    /** {profile[12], mean weight} over [from, to], or null. */
    static double[][] chromaProfile(Analysis a, double from, double to) {
        Analysis.ChromaCurve c = a == null ? null : a.chromaCurve;
        if (c == null || c.values.length == 0 || !(to >= from)) return null;
        double[] profile = new double[12];
        double weight = 0;
        int frames = 0;
        for (int i = 0; i < c.values.length; i++) {
            double t = c.start + i * c.step;
            double[] f = c.values[i];
            if (t < from || t > to || f == null) continue;
            for (int k = 0; k < 12; k++) profile[k] += f[k] * c.weights[i];
            weight += c.weights[i];
            frames++;
        }
        return frames > 0 ? new double[][]{profile, {weight / frames}} : null;
    }

    static double pearson(double[] a, double[] b) {
        double ma = 0, mb = 0;
        for (double v : a) ma += v;
        for (double v : b) mb += v;
        ma /= a.length;
        mb /= b.length;
        double cov = 0, pa = 0, pb = 0;
        for (int k = 0; k < a.length; k++) {
            cov += (a[k] - ma) * (b[k] - mb);
            pa += (a[k] - ma) * (a[k] - ma);
            pb += (b[k] - mb) * (b[k] - mb);
        }
        return pa > 0 && pb > 0 ? cov / Math.sqrt(pa * pb) : 0;
    }

    static double tonality(double[][] side) {
        double total = 0, max = Double.NEGATIVE_INFINITY;
        for (double v : side[0]) {
            total += v;
            max = Math.max(max, v);
        }
        if (!(total > 0)) return 0;
        return clamp((max / (total / 12) - 1.3) / 0.4, 0, 1) * clamp(side[1][0], 0, 1);
    }

    /** Where the two songs meet: {correlation, confidence, severe ? 1 : 0}, or null (no chroma). */
    static double[] overlapHarmony(Analysis cur, Analysis next, double outFrom, double outTo, double inFrom, double inTo) {
        double[][] left = chromaProfile(cur, Math.min(outFrom, outTo - KEY_CONTEXT_SEC), outTo);
        double[][] right = chromaProfile(next, inFrom, Math.max(inTo, inFrom + KEY_CONTEXT_SEC));
        if (left == null || right == null) return null;
        double confidence = Math.min(tonality(left), tonality(right)), correlation = pearson(left[0], right[0]);
        return new double[]{r3(correlation), r3(confidence), correlation <= CLASH_CORRELATION && confidence >= CLASH_CONFIDENCE ? 1 : 0};
    }

    // loudness (Orchard loudness.rs, strategy.rs loudness_trim)
    public static final class Trim {
        public double diffDb, outDb, inDb;

        Trim(double diffDb, double outDb, double inDb) {
            this.diffDb = diffDb;
            this.outDb = outDb;
            this.inDb = inDb;
        }
    }

    /** A region's EBU R128 gated loudness from the song's 100 ms levels, or NaN. */
    static double regionLoudness(Analysis a, double from, double dur) {
        Analysis.Curve c = a == null ? null : a.loudnessCurve;
        if (c == null || c.values.length == 0 || !(dur > 0)) return Double.NaN;
        int i0 = (int) Math.max(0, Math.round((from - c.start) / c.step));
        int i1 = (int) Math.min(c.values.length, Math.round((from + dur - c.start) / c.step));
        if (i1 <= i0) return Double.NaN;
        double[] p = new double[i1 - i0];
        for (int i = i0; i < i1; i++) p[i - i0] = Math.pow(10, c.values[i] / 10);
        List<Double> loud = new ArrayList<>();
        for (int i = 0; i + 4 <= p.length; i++) {
            double x = (p[i] + p[i + 1] + p[i + 2] + p[i + 3]) / 4;
            if (lufs(x) > -70) loud.add(x);
        }
        if (loud.isEmpty()) {
            double s = 0;
            for (double v : p) s += v;
            return Math.max(-70, lufs(s / p.length));
        }
        double s = 0;
        for (double x : loud) s += x;
        double rel = lufs(s / loud.size()) - 10;
        double t = 0;
        int n = 0;
        for (double x : loud) {
            if (lufs(x) > rel) {
                t += x;
                n++;
            }
        }
        return lufs(t / n);
    }

    private static double lufs(double x) {
        return -0.691 + 10 * Math.log10(x + 1e-30);
    }

    /** Each region against its whole song; more than 1 dB apart, the louder side is trimmed (at most 6 dB). */
    static Trim loudnessTrim(Analysis cur, double outFrom, double outDur, Analysis next, double inFrom, double inDur) {
        double o = regionLoudness(cur, outFrom, outDur), i = regionLoudness(next, inFrom, inDur);
        double ow = regionLoudness(cur, 0, cur.durationSec), iw = regionLoudness(next, 0, next.durationSec);
        if (Double.isNaN(o) || Double.isNaN(i) || Double.isNaN(ow) || Double.isNaN(iw)) return null;
        double diff = (o - ow) - (i - iw);
        if (Math.abs(diff) <= TRIM_TOLERANCE_DB) return new Trim(r1(diff), 0, 0);
        double t = r1(Math.min(Math.abs(diff), TRIM_MAX_DB));
        return diff > 0 ? new Trim(r1(diff), -t, 0) : new Trim(r1(diff), 0, -t);
    }

    // the hand-over and the beatless bridge (Orchard's boundary hand-off, beatlessBridge)

    /** "Beatmatch or do nothing": a beatless bridge when both edges have one, else the playing song runs out with a short fade. */
    static Plan handoff(Analysis cur, Analysis next, double earliest, String reason, Sung sung) {
        double[] b = new double[3];
        String none = beatlessBridge(cur, next, sung, b);
        if (none == null && b[0] >= earliest) {
            Plan p = new Plan();
            p.style = "crossfade";
            p.bridge = true;
            p.reason = reason + ": a beatless bridge (" + String.format(Locale.US, "%.1f", b[1]) + " s, no beat on either side)";
            p.mixOutTrackTime = b[0];
            p.durationSec = b[1];
            p.mixInTrackTime = b[2];
            p.rate = 1;
            p.trim = loudnessTrim(cur, b[0], b[1], next, b[2], b[1]);
            return p;
        }
        double end = Math.min(musicEnd(cur).durationSec, cur.durationSec - 0.3);
        double mixOut = Math.max(earliest, end - V2_SWITCH_SEC);
        Plan p = new Plan();
        p.style = "crossfade";
        p.handoff = true;
        p.reason = reason + ", so a straight hand-over (no beatless bridge: " + (none != null ? none : "past the playhead") + ")";
        p.mixOutTrackTime = mixOut;
        p.durationSec = Math.max(0, end - mixOut);
        p.mixInTrackTime = 0;
        p.rate = 1;
        return p;
    }

    /** The bass curve's mean over [from, to], or NaN. */
    static double bassMean(Analysis a, double from, double to) {
        Analysis.Curve c = a == null ? null : a.bassCurve;
        if (c == null || c.values.length == 0) return Double.NaN;
        int i0 = (int) Math.max(0, Math.floor((from - c.start) / c.step));
        int i1 = (int) Math.min(c.values.length - 1, Math.floor((to - c.start) / c.step));
        if (i1 < i0) return Double.NaN;
        double s = 0;
        for (int i = i0; i <= i1; i++) s += c.values[i];
        return s / (i1 - i0 + 1);
    }

    /** Orchard beatPresence: 0 in a beatless passage, 1 once kick and bass are in; NaN unmeasured. */
    static double beatPresence(Analysis a, double t) {
        double m = bassMean(a, t - 0.5, t + 0.5);
        return Double.isNaN(m) ? Double.NaN : clamp((m - 0.1) / 0.4, 0, 1);
    }

    static double beatlessRun(Analysis a, double from, double limit, double step) {
        double run = 0;
        for (double t = from; step > 0 ? t < limit : t > limit; t += step) {
            double p = beatPresence(a, t);
            if (Double.isNaN(p) || p >= BEATLESS) break;
            run += Math.abs(step);
        }
        return run;
    }

    /** The bridge into [into] {mixOut, dur, mixIn}; returns null, or why there is none. */
    static String beatlessBridge(Analysis cur, Analysis next, Sung sung, double[] into) {
        if (cur.bassCurve == null || next.bassCurve == null) return "no bass curves";
        if (subBassContrast(cur, next) >= V2_SUB_BASS_VETO) return "the productions clash";
        double end = Math.min(musicEnd(cur).durationSec, cur.durationSec - 0.3);
        double outRun = beatlessRun(cur, end - 0.125, end - BRIDGE_MAX_SEC, -0.25);
        double nextDur = next.durationSec > 0 ? next.durationSec : 60;
        double inRun = beatlessRun(next, 0.125, Math.min(nextDur, 60), 0.25);
        if (Math.min(outRun, inRun) < BRIDGE_MIN_SEC) return "beatless ending " + js(outRun) + " s, opening " + js(inRun) + " s";
        double beat = cur.beatIntervalSec > 0 ? cur.beatIntervalSec : 0.5;
        double dur = Math.floor(Math.min(Math.min(outRun, inRun), BRIDGE_MAX_SEC) / beat) * beat;
        if (dur < BRIDGE_MIN_SEC) return "beatless edges too short";
        double skip = Math.min(Math.max(0, inRun - dur), BRIDGE_MAX_SKIP_SEC);
        double both = vocalOverlap(cur, next, end - dur, dur, skip, 1, sung);
        if ((Double.isNaN(both) ? 0 : both) > 0.25) return "voices meet over the bridge";
        double risk = voiceRisk(next, skip - 0.5, skip + 1, sung.in);
        if ((Double.isNaN(risk) ? 0 : risk) >= 0.5) return "the next song would fade in mid-line";
        double[] keys = overlapHarmony(cur, next, end - dur, end, skip, skip + dur);
        if (keys != null && keys[2] > 0) return "the keys clash where they meet (" + js(keys[0]) + ")";
        into[0] = end - dur;
        into[1] = dur;
        into[2] = skip;
        return null;
    }

    /** Orchard's voice risk over [from, to): the vocal mask mapped to 0..1 (lyric lines: sung or not); NaN unknown. */
    static double voiceRisk(Analysis a, double from, double to, double[][] spans) {
        double sum = 0;
        int n = 0;
        for (double t = from; t < to; t += 0.05) {
            double v = vocalAt(a, t, spans);
            if (Double.isNaN(v)) continue;
            sum += spans != null ? v : clamp((v - 0.15) / 0.45, 0, 1);
            n++;
        }
        return n > 0 ? sum / n : Double.NaN;
    }

    static final class Swap {
        double at;
        String moved;
    }

    /** Orchard chooseBassSwap by the playing song's voice: the bar nearest the middle, moved off a sung line. */
    static Swap vocalSafeSwap(Analysis cur, double mixOut, double outSpan, int bars, double[][] spans) {
        Swap s = new Swap();
        double mid = clamp(Math.round(bars / 2.0), 1, bars - 1) / bars;
        double here = voiceRisk(cur, mixOut + mid * outSpan - 0.5, mixOut + mid * outSpan + 0.5, spans);
        s.at = mid;
        if (Double.isNaN(here) || here < SWAP_SINGING) return s;
        List<Double> moves = new ArrayList<>();
        for (int k = 1; k < bars; k++) {
            double f = k / (double) bars;
            if (f < 0.25 || f > 0.75 || Math.abs(f - mid) < 1e-9) continue;
            double r = voiceRisk(cur, mixOut + f * outSpan - 0.5, mixOut + f * outSpan + 0.5, spans);
            if (!Double.isNaN(r) && r < SWAP_CLEAR) moves.add(f);
        }
        moves.sort((x, y) -> {
            int c = Double.compare(Math.abs(x - 0.5), Math.abs(y - 0.5));
            return c != 0 ? c : Double.compare(x, y);
        });
        if (moves.isEmpty()) {
            s.moved = "sung at the middle, no clear bar";
            return s;
        }
        long by = Math.round((moves.get(0) - mid) * bars);
        s.at = moves.get(0);
        s.moved = "off a sung line, " + (by > 0 ? "+" : "") + by + " bar" + (Math.abs(by) == 1 ? "" : "s");
        return s;
    }

    /** What plays: the loop mix or the blend (automix.js loopOrBlend), with why under Automix v2. */
    public static final class Choice {
        public final boolean loop;
        public final String why;

        Choice(boolean loop, String why) {
            this.loop = loop;
            this.why = why;
        }

        /** The editor's Loop preset: the loop mix whenever one was found (crossfade.js loopPicked). */
        static Choice picked(boolean found) {
            return new Choice(found, "Loop picked for these two");
        }
    }

    /**
     * Loop mix or blend. Before Automix v2 a loop mix that found bars always plays. Under v2 a blend
     * that fell back never beats it; otherwise both are scored as Orchard does on what differs
     * (both singing, the keys, the length) and the blend has to win by LOOP_MARGIN.
     */
    public static Choice loopOrBlend(int version, Plan blend, LoopPlan loop) {
        if (version != 3 || loop == null || loop.none != null) return new Choice(loop != null && loop.none == null, null);
        if (blend == null || blend.fallback || !"blend".equals(blend.style) || blend.shape != null) return new Choice(true, "the blend fell back");
        double b = choiceScore(blend.clash, blend.harmonic != null ? blend.harmonic.score : 0.5, blend.bars * 4);
        double l = choiceScore(Math.min(loop.out.vocal, loop.in.vocal), 1, loop.totalBars * 4);
        return new Choice(b < l + LOOP_MARGIN, "loop " + js(r4(l)) + " vs blend " + js(r4(b)) + " (the blend needs " + js(LOOP_MARGIN) + " more)");
    }

    private static double choiceScore(double clash, double keys, int beats) {
        double vocal = 1 - Math.min(1, clash * 1.15) * 0.72;
        double length = beats == 16 ? 1 : beats == 8 ? 0.85 : beats == 32 ? 0.75 : 0.65;
        return 0.82 * (0.15 * vocal + 0.13 * keys + 0.02 * length);
    }

    static double clamp(double x, double lo, double hi) {
        return Math.min(hi, Math.max(lo, x));
    }

    private static double r1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static double r3(double v) {
        return Double.isNaN(v) ? Double.NaN : Math.round(v * 1000) / 1000.0;
    }

    private static double r4(double v) {
        return Math.round(v * 1e4) / 1e4;
    }

    /** A number as JavaScript prints it in a template string. */
    static String js(double v) {
        if (Double.isNaN(v)) return "null";
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return Long.toString((long) v);
        String s = Double.toString(v);
        return s.contains("E") ? java.math.BigDecimal.valueOf(v).toPlainString() : s;
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
        /** What each deck does over the loop mix (spec/automix/plan.schema.json "curves"). */
        public Curves curves;

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
        return planLoopMix(1, out, outBuffer, inn, inBuffer, earliestOutSec, sung);
    }

    /** The same for an engine version: Automix v2 (3) adds its gates (tempo cap, sub-bass, keys). */
    public static LoopPlan planLoopMix(int version, Analysis out, Audio outBuffer, Analysis inn, Audio inBuffer, double earliestOutSec, Sung sung) {
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
        // v2: the loop ramps the tempo too, so the same cap (a wider gap: a blend or hand-over instead)
        if (version == 3 && stretchOf(target / outBpm) > V2_MAX_STRETCH) {
            return none("tempos " + Math.round(stretchOf(target / outBpm) * 100) + "% apart (v2 stretches at most " + Math.round(V2_MAX_STRETCH * 100) + "%)");
        }
        double contrast = subBassContrast(out, inn);
        if (version == 3 && contrast >= V2_SUB_BASS_VETO) return none("different sub-bass styles (" + String.format(Locale.US, "%.2f", contrast) + " apart)");
        Harmonic harmonic = version == 3 ? harmonicEvidence(out, inn) : null;
        if (harmonic != null && harmonic.severeClash) return none("the keys clash (" + out.keyName + " → " + inn.keyName + ", " + js(harmonic.score) + ")");
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
        p.curves = Curves.loop(p);
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
