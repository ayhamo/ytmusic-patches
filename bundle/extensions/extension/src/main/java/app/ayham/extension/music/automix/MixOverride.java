package app.ayham.extension.music.automix;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A user's edit of one pair's transition (spec/automix/editor.md, override.schema.json), saved per
 * pair under "fromId>toId". A field left out is Auto; an override with nothing but Auto in it is
 * no override (Auto is the reset). A port of automix.js normalizeOverride, migratePick,
 * presetCurves and resolveWindow, checked against spec/automix/editor-cases.json.
 */
public final class MixOverride {
    public static final String[] PRESETS = {"auto", "smooth", "rise", "blend", "wave", "melt", "slam", "loop", "custom"};
    public static final String[] VOLUMES = {"smooth", "crossfade", "overlap", "cutInFadeOut", "fadeInCutOut", "centerCut"};
    public static final String[] EQS = {"centerBassSwap", "endBassSwap", "threeBandFade", "quickBassCut", "none"};
    public static final String[] EFFECTS = {"none", "lpOut", "hpIn", "lpBoth", "hpBoth", "lpInHpOut"};
    public static final String[] BEAT_MATCH = {"auto", "sync", "glide", "off"};
    public static final int[] BARS = {2, 4, 8, 16, 32};
    /** Unlocked overrides kept (oldest saved first out); locked ones have a cap of their own. */
    public static final int MAX_UNLOCKED = 300, MAX_LOCKED = 300;

    static final double CUT_DB = Planner.BASS_CUT_DB, LP_CLOSED = 400, HP_CLOSED = 1000, CUT_RAMP_SEC = 0.008;

    /** null: Auto. */
    public String preset;
    /**
     * Read from a pick saved before the editor as blend, filter or crossfade (the master's preset "auto"
     * after migratePick): the planner's reason is "picked in the overview". Not saved: an edit saves it
     * normalized, as the master does.
     */
    public boolean migrated;
    /** volume, eq, effect: only with preset custom. */
    public String volume, eq, effect;
    /** 0: Auto. */
    public int bars;
    /** null: Auto. */
    public String beatMatch;
    public Position out, in;
    public boolean lock;
    /** Only in picks migrated from {style, bars}: blend | filter, the planner's own mix forced. */
    public String family;
    public int engine;
    public long savedAt;

    /** A window position on a song's grid (override.schema.json $defs.position). */
    public static final class Position {
        public int bar, beat, nudgeMs;
        public double sec;

        public Position(int bar, int beat, int nudgeMs, double sec) {
            this.bar = bar;
            this.beat = beat;
            this.nudgeMs = nudgeMs;
            this.sec = sec;
        }
    }

    /** A saved position read back on a song's grid: song seconds, and whether the grid moved under it. */
    public static final class Resolved {
        public final double sec;
        public final boolean moved, snapped;
        /** The bar it snapped to when moved, else the saved one. */
        public final int bar;

        Resolved(double sec, boolean moved, boolean snapped, int bar) {
            this.sec = sec;
            this.moved = moved;
            this.snapped = snapped;
            this.bar = bar;
        }
    }

    public MixOverride copy() {
        MixOverride o = new MixOverride();
        o.preset = preset;
        o.volume = volume;
        o.eq = eq;
        o.effect = effect;
        o.bars = bars;
        o.beatMatch = beatMatch;
        o.out = out == null ? null : new Position(out.bar, out.beat, out.nudgeMs, out.sec);
        o.in = in == null ? null : new Position(in.bar, in.beat, in.nudgeMs, in.sec);
        o.lock = lock;
        o.family = family;
        o.engine = engine;
        o.savedAt = savedAt;
        o.migrated = migrated;
        return o;
    }

    /** The preset as the planner reads it ("auto" when none). */
    public String presetOrAuto() {
        return preset == null ? "auto" : preset;
    }

    public String beatMatchOrAuto() {
        return beatMatch == null ? "auto" : beatMatch;
    }

    /** The loop mix asked for. */
    public boolean loop() {
        return "loop".equals(preset);
    }

    /** The user's own choice beyond Auto: the planner's style gates warn instead of refusing (editor.md §6). */
    public boolean forced() {
        return (preset != null && !preset.equals("auto") && !preset.equals("loop"))
                || "sync".equals(beatMatch) || "glide".equals(beatMatch) || out != null || in != null;
    }

    /** The length the window asks for in bars (a preset's own default when Auto), 0: Auto. */
    public int pinnedBars() {
        if (loop()) return 0;
        if (bars > 0) return bars;
        return "slam".equals(preset) ? 2 : 0;
    }

    // ---------------------------------------------------------------------------------------------
    // reading and writing (override.schema.json; a pick from before the editor migrates as read)

    /** An override or a pick from before the editor ({style, bars}), as saved JSON; null: Auto. */
    @SuppressWarnings("unchecked")
    public static MixOverride fromJson(Object json) {
        if (!(json instanceof Map)) return null;
        Map<String, Object> m = (Map<String, Object>) json;
        MixOverride o = new MixOverride();
        if (m.containsKey("style")) {
            // editor.md §1: today's picks migrate as they're read
            Object st = m.get("style");
            if ("loop".equals(st)) o.preset = "loop";
            else if ("blend".equals(st) || "filter".equals(st)) o.family = (String) st;
            else if ("crossfade".equals(st)) o.beatMatch = "off";
            o.bars = intOf(m.get("bars"), 0);
            MixOverride n = normalize(o);
            if (n != null) n.migrated = "blend".equals(st) || "filter".equals(st) || "crossfade".equals(st);
            return n;
        }
        o.preset = str(m.get("preset"));
        Object lanes = m.get("lanes");
        if (lanes instanceof Map) {
            Map<String, Object> l = (Map<String, Object>) lanes;
            o.volume = str(l.get("volume"));
            o.eq = str(l.get("eq"));
            o.effect = str(l.get("effect"));
        }
        o.bars = intOf(m.get("bars"), 0);
        o.beatMatch = str(m.get("beatMatch"));
        Object w = m.get("window");
        if (w instanceof Map) {
            o.out = position(((Map<String, Object>) w).get("out"));
            o.in = position(((Map<String, Object>) w).get("in"));
        }
        o.lock = Boolean.TRUE.equals(m.get("lock"));
        o.family = str(m.get("family"));
        o.engine = intOf(m.get("engine"), 0);
        o.savedAt = m.get("savedAt") instanceof Number ? ((Number) m.get("savedAt")).longValue() : 0;
        return normalize(o);
    }

    @SuppressWarnings("unchecked")
    private static Position position(Object json) {
        if (!(json instanceof Map)) return null;
        Map<String, Object> p = (Map<String, Object>) json;
        Object bar = p.get("bar"), sec = p.get("sec");
        if (!(bar instanceof Number) || !(sec instanceof Number)) return null;
        double b = ((Number) bar).doubleValue(), s = ((Number) sec).doubleValue();
        if (b != Math.rint(b) || Double.isNaN(s) || Double.isInfinite(s)) return null;
        return new Position((int) b, intOf(p.get("beat"), 0), intOf(p.get("nudgeMs"), 0), s);
    }

    /**
     * The override as saved (automix.js normalizeOverride): unknown values and Auto fields dropped,
     * null when nothing but Auto is left. Picking a preset drops a migrated pick's family.
     */
    public static MixOverride normalize(MixOverride o) {
        if (o == null) return null;
        MixOverride n = new MixOverride();
        if (o.preset != null && !o.preset.equals("auto") && Arrays.asList(PRESETS).contains(o.preset)) n.preset = o.preset;
        if ("custom".equals(n.preset)) {
            // only the schema's values (override.schema.json lanes)
            if (Arrays.asList(VOLUMES).contains(o.volume) && Arrays.asList(EQS).contains(o.eq) && Arrays.asList(EFFECTS).contains(o.effect)) {
                n.volume = o.volume;
                n.eq = o.eq;
                n.effect = o.effect;
            } else {
                n.preset = null;
            }
        }
        if (n.preset == null && ("blend".equals(o.family) || "filter".equals(o.family))) n.family = o.family;
        if (!n.loop()) {
            for (int b : BARS) if (o.bars == b) n.bars = b;
            n.out = clean(o.out);
            n.in = clean(o.in);
        }
        if ("sync".equals(o.beatMatch) || "glide".equals(o.beatMatch) || "off".equals(o.beatMatch)) n.beatMatch = o.beatMatch;
        if (n.preset == null && n.family == null && n.bars == 0 && n.out == null && n.in == null && n.beatMatch == null) return null;
        n.lock = o.lock;
        n.engine = o.engine;
        n.savedAt = o.savedAt;
        return n;
    }

    private static Position clean(Position p) {
        if (p == null || Double.isNaN(p.sec) || Double.isInfinite(p.sec)) return null;
        int nudge = (int) Math.max(-2000, Math.min(2000, Math.round(p.nudgeMs / 10.0) * 10));
        int beat = p.beat >= 1 && p.beat <= 3 ? p.beat : 0; // as normalizeOverride: beats 1 to 3, else on the downbeat
        return new Position(p.bar, beat, nudge, Math.round(p.sec * 1e3) / 1e3);
    }

    /** As saved (override.schema.json), fields in the schema's order. */
    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("v", 1);
        if (preset != null) m.put("preset", preset);
        if ("custom".equals(preset)) {
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("volume", volume);
            l.put("eq", eq);
            l.put("effect", effect);
            m.put("lanes", l);
        }
        if (bars > 0) m.put("bars", bars);
        if (beatMatch != null) m.put("beatMatch", beatMatch);
        if (out != null || in != null) {
            Map<String, Object> w = new LinkedHashMap<>();
            if (out != null) w.put("out", positionJson(out));
            if (in != null) w.put("in", positionJson(in));
            m.put("window", w);
        }
        if (lock) m.put("lock", true);
        if (family != null) m.put("family", family);
        if (engine > 0) m.put("engine", engine);
        if (savedAt > 0) m.put("savedAt", savedAt);
        return m;
    }

    private static Map<String, Object> positionJson(Position p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bar", p.bar);
        if (p.beat != 0) m.put("beat", p.beat);
        if (p.nudgeMs != 0) m.put("nudgeMs", p.nudgeMs);
        m.put("sec", p.sec);
        return m;
    }

    // ---------------------------------------------------------------------------------------------
    // presets and lanes as curves (editor.md §2, §3)

    /** volume, eq, effect of a preset; null for auto, loop or an unknown one. */
    static String[] lanesOf(String preset) {
        if (preset == null) return null;
        switch (preset) {
            case "smooth": return new String[]{"smooth", "centerBassSwap", "none"};
            case "rise": return new String[]{"overlap", "endBassSwap", "lpInHpOut"};
            case "blend": return new String[]{"overlap", "threeBandFade", "none"};
            case "wave": return new String[]{"overlap", "centerBassSwap", "lpBoth"};
            case "melt": return new String[]{"crossfade", "centerBassSwap", "hpBoth"};
            case "slam": return new String[]{"centerCut", "none", "none"};
            default: return null;
        }
    }

    /** The lanes this override plays (a preset's, or the custom ones), or null: the planner's own. */
    public String[] lanes() {
        return "custom".equals(preset) ? new String[]{volume, eq, effect} : lanesOf(preset);
    }

    /**
     * The curves of a preset or custom lanes over a window of D seconds (automix.js presetCurves):
     * gain, the shelves and the filters, no rate. beatSec: the beat, NaN with beat match Off.
     * Null for Auto, Loop, an unknown shape or no window.
     */
    public static Curves presetCurves(MixOverride o, double D, double beatSec) {
        String[] lanes = o == null ? null : o.lanes();
        if (lanes == null || !(D > 0)) return null;
        double e = CUT_RAMP_SEC / D, hb = beatSec > 0 ? 0.5 * beatSec / D : 0.02, C = CUT_DB;
        Curves c = new Curves();
        c.durationSec = r6(D);
        switch (String.valueOf(lanes[0])) {
            case "smooth": {
                double[][] a = new double[17][], b = new double[17][];
                for (int i = 0; i <= 16; i++) {
                    double x = i / 16.0;
                    a[i] = new double[]{x, Math.cos(x * Math.PI / 2)};
                    b[i] = new double[]{x, Math.sin(x * Math.PI / 2)};
                }
                c.out.put("gain", at(D, a));
                c.in.put("gain", at(D, b));
                break;
            }
            case "crossfade":
                c.out.put("gain", at(D, new double[][]{{0, 1}, {1, 0}}));
                c.in.put("gain", at(D, new double[][]{{0, 0}, {1, 1}}));
                break;
            case "overlap":
                c.out.put("gain", at(D, new double[][]{{0, 1}, {0.75, 1}, {1, 0}}));
                c.in.put("gain", at(D, new double[][]{{0, 0}, {0.25, 1}}));
                break;
            case "cutInFadeOut":
                c.out.put("gain", at(D, new double[][]{{0, 1}, {1, 0}}));
                c.in.put("gain", at(D, new double[][]{{0, 0}, {e, 1}}));
                break;
            case "fadeInCutOut":
                c.out.put("gain", at(D, new double[][]{{0, 1}, {1 - e, 1}, {1, 0}}));
                c.in.put("gain", at(D, new double[][]{{0, 0}, {1, 1}}));
                break;
            case "centerCut":
                c.out.put("gain", at(D, new double[][]{{0, 1}, {0.5, 1}, {0.5 + e, 0}}));
                c.in.put("gain", at(D, new double[][]{{0, 0}, {0.5, 0}, {0.5 + e, 1}}));
                break;
            default:
                return null;
        }
        switch (String.valueOf(lanes[1])) {
            case "centerBassSwap":
                swap(c, D, 0.5, hb);
                break;
            case "endBassSwap":
                swap(c, D, 0.875, hb);
                break;
            case "threeBandFade":
                swap(c, D, 0.5, hb);
                c.out.put("highDb", at(D, new double[][]{{0.5, 0}, {1, C}}));
                c.in.put("highDb", at(D, new double[][]{{0, C}, {0.5, 0}}));
                break;
            case "quickBassCut":
                c.out.put("bassDb", at(D, new double[][]{{0, 0}, {2 * hb, C}}));
                break;
            case "none":
                break;
            default:
                return null;
        }
        switch (String.valueOf(lanes[2])) {
            case "none":
                break;
            case "lpOut":
                c.out.put("lowpassHz", at(D, new double[][]{{0, 20000}, {1, LP_CLOSED}}));
                break;
            case "hpIn":
                c.in.put("highpassHz", at(D, new double[][]{{0, HP_CLOSED}, {0.5, 20}}));
                break;
            case "lpBoth":
                c.out.put("lowpassHz", at(D, new double[][]{{0.5, 20000}, {1, LP_CLOSED}}));
                c.in.put("lowpassHz", at(D, new double[][]{{0, LP_CLOSED}, {0.5, 20000}}));
                break;
            case "hpBoth":
                c.out.put("highpassHz", at(D, new double[][]{{0.5, 20}, {1, HP_CLOSED}}));
                c.in.put("highpassHz", at(D, new double[][]{{0, HP_CLOSED}, {0.5, 20}}));
                break;
            case "lpInHpOut":
                c.out.put("highpassHz", at(D, new double[][]{{0, 20}, {1, HP_CLOSED}}));
                c.in.put("lowpassHz", at(D, new double[][]{{0, LP_CLOSED}, {1, 20000}}));
                break;
            default:
                return null;
        }
        return c;
    }

    private static void swap(Curves c, double D, double centre, double hb) {
        c.out.put("bassDb", at(D, new double[][]{{centre - hb, 0}, {centre + hb, CUT_DB}}));
        c.in.put("bassDb", at(D, new double[][]{{0, CUT_DB}, {centre - hb, CUT_DB}, {centre + hb, 0}}));
    }

    /** Points at fractions of the window, in seconds (rounded as the extension rounds them). */
    private static double[][] at(double D, double[][] pts) {
        double[][] r = new double[pts.length][];
        for (int i = 0; i < pts.length; i++) r[i] = new double[]{r6(pts[i][0] * D), r6(pts[i][1])};
        return r;
    }

    private static double r6(double v) {
        return Math.round(v * 1e6) / 1e6;
    }

    // ---------------------------------------------------------------------------------------------
    // the window (editor.md §6)

    /**
     * A saved position back to song seconds on a grid (downbeatSec, beatSec of that song; NaN beat:
     * no grid, the saved seconds as they are). More than a beat from the saved seconds: the grid
     * changed under it, so the saved seconds snap to the new grid's nearest downbeat (moved).
     */
    public static Resolved resolveWindow(Position pos, double downbeatSec, double beatSec) {
        if (pos == null) return null;
        double nudge = pos.nudgeMs / 1000.0;
        if (!(beatSec > 0) || Double.isNaN(downbeatSec) || Double.isInfinite(downbeatSec)) return new Resolved(pos.sec, false, false, pos.bar);
        double bar = 4 * beatSec;
        double t = downbeatSec + pos.bar * bar + pos.beat * beatSec + nudge;
        if (Double.isNaN(pos.sec) || Math.abs(t - pos.sec) <= beatSec) return new Resolved(r6(t), false, true, pos.bar);
        int k = (int) Math.round((pos.sec - downbeatSec) / bar);
        return new Resolved(r6(downbeatSec + k * bar), true, true, k);
    }

    static Resolved resolveWindow(Position pos, Planner.Grid g) {
        return g == null ? resolveWindow(pos, Double.NaN, Double.NaN) : resolveWindow(pos, g.downbeatSec, g.beatSec);
    }

    /** A position at song seconds [sec] snapped to a grid's nearest downbeat (or beat): snap() of positionOf. */
    public static Position snap(double sec, double downbeatSec, double beatSec, boolean toBeats) {
        return positionOf(sec, downbeatSec, beatSec, true, toBeats);
    }

    /**
     * Song seconds as a saved position (automix-overview.js positionOf): snapped, the nearest
     * downbeat (or beat); else the beat at or before it plus the rest as a nudge in 10 ms steps.
     * No grid (NaN beat): bar 0 and the seconds as they are.
     */
    public static Position positionOf(double sec, double downbeatSec, double beatSec, boolean snap, boolean toBeats) {
        if (!(beatSec > 0) || Double.isNaN(downbeatSec)) return new Position(0, 0, 0, Math.round(sec * 1e3) / 1e3);
        long j;
        if (snap) {
            j = toBeats ? Math.round((sec - downbeatSec) / beatSec) : 4 * Math.round((sec - downbeatSec) / (4 * beatSec));
            int bar = (int) Math.floorDiv(j, 4L);
            return new Position(bar, (int) (j - 4L * bar), 0, Math.round((downbeatSec + j * beatSec) * 1e3) / 1e3);
        }
        j = (long) Math.floor((sec - downbeatSec) / beatSec + 1e-9);
        int bar = (int) Math.floorDiv(j, 4L);
        int nudge = (int) (Math.round((sec - downbeatSec - j * beatSec) * 100) * 10);
        return new Position(bar, (int) (j - 4L * bar), nudge, Math.round(sec * 1e3) / 1e3);
    }

    // ---------------------------------------------------------------------------------------------

    private static String str(Object v) {
        return v instanceof String ? (String) v : null;
    }

    private static int intOf(Object v, int dflt) {
        return v instanceof Number ? (int) Math.round(((Number) v).doubleValue()) : dflt;
    }

    /** The lane names in the order the editor's dropdowns show them. */
    public static List<String> options(String lane) {
        return Arrays.asList(lane.equals("volume") ? VOLUMES : lane.equals("eq") ? EQS : EFFECTS);
    }
}
