package app.ayham.extension.music.automix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * The transition editor (spec/automix/editor.md): the shared cases of editor-cases.json (each
 * preset and lane as curves, saved window positions read back on a grid), the old picks'
 * migration, and how the planner plays an override (editor.md §9).
 */
@SuppressWarnings("unchecked")
public class EditorCasesTest {
    private static final double TOLERANCE = 1e-6;

    private static Map<String, Object> cases() throws Exception {
        for (File d = new File("").getAbsoluteFile(); d != null; d = d.getParentFile()) {
            File f = new File(d, "spec/automix/editor-cases.json");
            if (f.isFile()) return (Map<String, Object>) Json.parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        }
        throw new AssertionError("spec/automix/editor-cases.json not found above " + new File("").getAbsolutePath());
    }

    @Test
    public void presetAndLaneCurvesMatchTheCases() throws Exception {
        List<String> diffs = new ArrayList<>();
        List<Object> cases = (List<Object>) cases().get("curves");
        assertEquals(27, cases.size());
        for (Object o : cases) {
            Map<String, Object> c = (Map<String, Object>) o;
            MixOverride ov = MixOverride.fromJson(c.get("override"));
            double D = ((Number) c.get("D")).doubleValue();
            double beat = c.get("beatSec") instanceof Number ? ((Number) c.get("beatSec")).doubleValue() : Double.NaN;
            Curves got = MixOverride.presetCurves(ov, D, beat);
            Object want = c.get("expect");
            if (want == null) {
                if (got != null) diffs.add(c.get("name") + ": expected refused, got " + Json.write(asJson(got)));
                continue;
            }
            if (got == null) {
                diffs.add(c.get("name") + ": refused");
                continue;
            }
            if (!same(want, asJson(got))) diffs.add(c.get("name") + ":\n  got  " + Json.write(asJson(got)) + "\n  want " + Json.write(want));
        }
        assertTrue(diffs.size() + " of " + cases.size() + " differ:\n" + String.join("\n", diffs), diffs.isEmpty());
    }

    @Test
    public void savedWindowPositionsReadBackAsTheCasesSay() throws Exception {
        List<String> diffs = new ArrayList<>();
        List<Object> cases = (List<Object>) cases().get("windows");
        assertEquals(8, cases.size());
        for (Object o : cases) {
            Map<String, Object> c = (Map<String, Object>) o;
            Map<String, Object> p = (Map<String, Object>) c.get("pos");
            MixOverride.Position pos = new MixOverride.Position(((Number) p.get("bar")).intValue(),
                    p.get("beat") instanceof Number ? ((Number) p.get("beat")).intValue() : 0,
                    p.get("nudgeMs") instanceof Number ? ((Number) p.get("nudgeMs")).intValue() : 0, ((Number) p.get("sec")).doubleValue());
            Map<String, Object> g = (Map<String, Object>) c.get("grid");
            MixOverride.Resolved r = g == null ? MixOverride.resolveWindow(pos, Double.NaN, Double.NaN)
                    : MixOverride.resolveWindow(pos, ((Number) g.get("downbeatSec")).doubleValue(), ((Number) g.get("beatSec")).doubleValue());
            Map<String, Object> got = new LinkedHashMap<>();
            got.put("sec", r.sec);
            got.put("moved", r.moved);
            got.put("snapped", r.snapped);
            Map<String, Object> want = (Map<String, Object>) c.get("expect");
            if (want.containsKey("bar")) got.put("bar", r.bar);
            if (!same(want, got)) diffs.add(c.get("name") + ": got " + Json.write(got) + ", want " + Json.write(want));
        }
        assertTrue(String.join("\n", diffs), diffs.isEmpty());
    }

    /** Curves as editor-cases.json writes them: durationSec, then out and in, each curve name in the case's order. */
    private static Map<String, Object> asJson(Curves c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("durationSec", c.durationSec);
        m.put("out", side(c.out));
        m.put("in", side(c.in));
        return m;
    }

    private static Map<String, Object> side(Map<String, double[][]> curves) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<String, double[][]> e : curves.entrySet()) {
            List<Object> pts = new ArrayList<>();
            for (double[] p : e.getValue()) {
                List<Object> pt = new ArrayList<>();
                pt.add(p[0]);
                pt.add(p[1]);
                pts.add(pt);
            }
            m.put(e.getKey(), pts);
        }
        return m;
    }

    /** Numbers within TOLERANCE; maps by key (order free); lists element by element. */
    private static boolean same(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) return Math.abs(((Number) a).doubleValue() - ((Number) b).doubleValue()) <= TOLERANCE;
        if (a instanceof List && b instanceof List) {
            List<?> x = (List<?>) a, y = (List<?>) b;
            if (x.size() != y.size()) return false;
            for (int i = 0; i < x.size(); i++) if (!same(x.get(i), y.get(i))) return false;
            return true;
        }
        if (a instanceof Map && b instanceof Map) {
            Map<?, ?> x = (Map<?, ?>) a, y = (Map<?, ?>) b;
            if (!x.keySet().equals(y.keySet())) return false;
            for (Object k : x.keySet()) if (!same(x.get(k), y.get(k))) return false;
            return true;
        }
        return a == null ? b == null : a.equals(b);
    }

    // ── the override itself (editor.md §1) ──

    private static Object json(String s) {
        return Json.parse(s);
    }

    @Test
    public void oldPicksMigrateAsTheyAreRead() {
        MixOverride loop = MixOverride.fromJson(json("{\"style\":\"loop\",\"bars\":0}"));
        assertEquals("loop", loop.preset);
        MixOverride blend = MixOverride.fromJson(json("{\"style\":\"blend\",\"bars\":8}"));
        assertNull(blend.preset);
        assertEquals("blend", blend.family);
        assertEquals(8, blend.bars);
        MixOverride filter = MixOverride.fromJson(json("{\"style\":\"filter\",\"bars\":0}"));
        assertEquals("filter", filter.family);
        MixOverride fade = MixOverride.fromJson(json("{\"style\":\"crossfade\",\"bars\":0}"));
        assertEquals("off", fade.beatMatch);
        assertNull(fade.family);
        // Auto and Auto: no override at all
        assertNull(MixOverride.fromJson(json("{\"style\":null,\"bars\":0}")));
        // a loop keeps its own length: the bars of an old loop pick are dropped
        assertEquals(0, MixOverride.fromJson(json("{\"style\":\"loop\",\"bars\":8}")).bars);
    }

    @Test
    public void nothingButAutoIsNoOverride() {
        assertNull(MixOverride.fromJson(json("{\"v\":1,\"preset\":\"auto\",\"beatMatch\":\"auto\"}")));
        // a lock alone keeps nothing: Auto is the reset
        assertNull(MixOverride.fromJson(json("{\"v\":1,\"lock\":true}")));
        MixOverride o = new MixOverride();
        o.preset = "smooth";
        o.family = "blend";
        o.bars = 3; // not a length the editor offers
        MixOverride n = MixOverride.normalize(o);
        assertEquals("smooth", n.preset);
        assertNull("picking a preset drops a migrated family", n.family);
        assertEquals(0, n.bars);
        // custom needs its lanes
        o = new MixOverride();
        o.preset = "custom";
        assertNull(MixOverride.normalize(o));
    }

    @Test
    public void anOverrideSurvivesItsJson() {
        MixOverride o = new MixOverride();
        o.preset = "custom";
        o.volume = "overlap";
        o.eq = "endBassSwap";
        o.effect = "hpIn";
        o.bars = 16;
        o.beatMatch = "glide";
        o.out = new MixOverride.Position(80, 2, 37, 192.4567);
        o.in = new MixOverride.Position(-1, 0, 0, -0.75);
        o.lock = true;
        o.engine = 3;
        o.savedAt = 1763400000000L;
        String text = Json.write(MixOverride.normalize(o).toJson());
        MixOverride back = MixOverride.fromJson(Json.parse(text));
        assertEquals(text, Json.write(back.toJson()));
        assertEquals(40, back.out.nudgeMs); // 10 ms steps
        assertEquals(192.457, back.out.sec, 1e-9);
        assertTrue(back.lock);
        assertTrue(text, text.startsWith("{\"v\":1,\"preset\":\"custom\",\"lanes\":{\"volume\":\"overlap\",\"eq\":\"endBassSwap\",\"effect\":\"hpIn\"},\"bars\":16"));
    }

    @Test
    public void snappingFindsTheNearestDownbeatOrBeat() {
        MixOverride.Position p = MixOverride.snap(33.4, 1.25, 0.5, false);
        assertEquals(16, p.bar);
        assertEquals(0, p.beat);
        assertEquals(33.25, p.sec, 1e-9);
        p = MixOverride.snap(33.8, 1.25, 0.5, true);
        assertEquals(16, p.bar);
        assertEquals(1, p.beat);
        assertEquals(33.75, p.sec, 1e-9);
        p = MixOverride.snap(0.2, 1.25, 0.5, true);
        assertEquals(-1, p.bar);
        assertEquals(2, p.beat);
        assertEquals(0.25, p.sec, 1e-9);
        assertEquals(p.sec, MixOverride.resolveWindow(p, 1.25, 0.5).sec, 1e-9);
    }

    // ── the planner with an override (editor.md §4, §6, §9) ──

    private static Planner.Plan plan(Planner.Settings s, Analysis a, Analysis b, MixOverride ov) {
        return Planner.plan(s, "a", "b", a, b, a.durationSec, 60, 60 + PlannerTest.EARLIEST_LEAD, null, ov);
    }

    private static MixOverride ov(String json) {
        return MixOverride.fromJson(Json.parse(json));
    }

    @Test
    public void aPresetReplacesTheCurvesAndKeepsTheTempo() {
        Planner.Settings s = new Planner.Settings();
        s.version = 3;
        Analysis a = PlannerTest.song("a", 120, 200, 0.9), b = PlannerTest.song("b", 123, 180, 0.9);
        Planner.Plan auto = plan(s, a, b, null);
        Planner.Plan wave = plan(s, a, b, ov("{\"preset\":\"wave\"}"));
        assertEquals("blend", wave.style);
        assertEquals("wave", wave.preset);
        assertFalse(wave.fallback);
        Curves want = MixOverride.presetCurves(ov("{\"preset\":\"wave\"}"), wave.durationSec, wave.beatSec);
        assertEquals(java.util.Arrays.deepToString(want.out.get("gain")), java.util.Arrays.deepToString(wave.curves.out.get("gain")));
        assertEquals(java.util.Arrays.deepToString(want.in.get("lowpassHz")), java.util.Arrays.deepToString(wave.curves.in.get("lowpassHz")));
        // the planner's tempo stays: the next song's speed-up (or glide) as Auto plays it
        assertEquals(java.util.Arrays.deepToString(auto.curves.in.get("rate")), java.util.Arrays.deepToString(wave.curves.in.get("rate")));
        assertEquals(auto.rate, wave.rate, 0);
        assertTrue(wave.toString().contains("[editor: wave"));
    }

    @Test
    public void slamDefaultsToTwoBarsWithItsCutOnTheCentre() {
        Planner.Settings s = new Planner.Settings();
        s.version = 3;
        Planner.Plan p = plan(s, PlannerTest.song("a", 120, 200, 0.9), PlannerTest.song("b", 120, 180, 0.9), ov("{\"preset\":\"slam\"}"));
        assertEquals(2, p.bars);
        double[][] g = p.curves.out.get("gain");
        assertEquals(p.durationSec / 2, g[1][0], 1e-6);
        assertEquals(p.durationSec / 2 + MixOverride.CUT_RAMP_SEC, g[2][0], 1e-6);
    }

    @Test
    public void syncIsOneTempoAndGlideMovesIt() {
        Planner.Settings s = new Planner.Settings();
        s.version = 3;
        // 6% apart: Auto glides (over 4%), Sync holds one tempo, Glide glides
        Analysis a = PlannerTest.song("a", 120, 200, 0.9), b = PlannerTest.song("b", 127.2, 180, 0.9);
        assertNotNull(plan(s, a, b, null).glide);
        Planner.Plan sync = plan(s, a, b, ov("{\"beatMatch\":\"sync\"}"));
        assertNull(sync.glide);
        assertEquals("blend", sync.style);
        // tempo only moves up: the next song faster, so the playing one is brought up to it
        assertEquals(1, sync.rate, 1e-9);
        assertTrue(sync.outRate > 1.05);
        Planner.Plan glide = plan(s, a, PlannerTest.song("b", 121.2, 180, 0.9), ov("{\"beatMatch\":\"glide\"}"));
        assertNotNull("Glide asks for the glide even under 4%", glide.glide);
    }

    @Test
    public void syncOutOfRangePlaysWithoutBeatMatchAndSaysWhy() {
        Planner.Settings s = new Planner.Settings();
        s.version = 3;
        Analysis a = PlannerTest.song("a", 120, 200, 0.9), b = PlannerTest.song("b", 133.2, 180, 0.9);
        Planner.Plan p = plan(s, a, b, ov("{\"beatMatch\":\"sync\",\"bars\":8}"));
        assertEquals("crossfade", p.style);
        assertFalse(p.fallback);
        assertEquals(16, p.durationSec, 1e-9); // 8 bars of the playing song
        assertNotNull(p.warnings);
        assertTrue(p.warnings.get(0), p.warnings.get(0).startsWith("tempos 11% apart: Sync stretches at most 8%"));
    }

    @Test
    public void beatMatchOffWithAPresetStillPlaysOnOurDecks() {
        Planner.Settings s = new Planner.Settings();
        s.version = 3;
        Planner.Plan p = plan(s, PlannerTest.song("a", 120, 200, 0.9), PlannerTest.song("b", 120, 180, 0.9), ov("{\"preset\":\"melt\",\"beatMatch\":\"off\"}"));
        assertEquals("crossfade", p.style);
        assertEquals("melt", p.preset);
        assertFalse("a preset can't play on Morphe's crossfade", p.fallback);
        // with beat match off the swap is 2% of the window either side of the centre
        double D = p.durationSec;
        assertEquals(0.48 * D, p.curves.out.get("bassDb")[0][0], 1e-5);
    }

    @Test
    public void aWindowIsClampedWithTheReasonShown() {
        Planner.Settings s = new Planner.Settings();
        s.version = 3;
        Analysis a = PlannerTest.song("a", 120, 200, 0.9), b = PlannerTest.song("b", 120, 180, 0.9);
        // A's mix-out asked at bar 97 (194.5 s): an 8 s window has to start by 191.7 s to end by 199.7 s
        MixOverride o = ov("{\"window\":{\"out\":{\"bar\":97,\"sec\":194.5},\"in\":{\"bar\":4,\"sec\":8.5}}}");
        Planner.Plan p = plan(s, a, b, o);
        assertEquals("blend", p.style);
        assertEquals(191.7, p.mixOutTrackTime, 1e-9);
        assertNotNull(p.warnings);
        assertTrue(String.join("; ", p.warnings), p.warnings.get(0).startsWith("the mix-out moved to"));
        assertEquals(8.5, p.mixInTrackTime, 1e-9);
        // a window the grid allows plays where it was put
        Planner.Plan q = plan(s, a, b, ov("{\"window\":{\"out\":{\"bar\":80,\"sec\":160.5}}}"));
        assertEquals(160.5, q.mixOutTrackTime, 1e-9);
        assertNull(q.warnings);
    }

    @Test
    public void theEnginePlaysThePresetsPoints() {
        Planner.Settings s = new Planner.Settings();
        s.version = 3;
        Analysis a = PlannerTest.song("a", 120, 200, 0.9), b = PlannerTest.song("b", 120, 180, 0.9);
        Planner.Plan slam = plan(s, a, b, ov("{\"preset\":\"slam\"}"));
        Mix m = Mix.blend(slam, "a", "b", 200);
        double c = m.t0 + m.D / 2;
        // the cut: full until the centre, gone 8 ms later; the next song the other way round
        assertEquals(1, m.out.gain.at(c - 0.01), 1e-9);
        assertEquals(0, m.out.gain.at(c + 0.01), 1e-9);
        assertEquals(0, m.in.gain.at(c - 0.01), 1e-9);
        assertEquals(1, m.in.gain.at(c + 0.01), 1e-9);
        assertEquals(0.5, m.out.gain.at(c + 0.004), 1e-6);
        Planner.Plan melt = plan(s, a, b, ov("{\"preset\":\"melt\"}"));
        m = Mix.blend(melt, "a", "b", 200);
        // the next song's high-pass opens from 1 kHz to 20 Hz over the first half, exponentially
        assertEquals(1000, m.in.hpHz.at(m.t0), 1e-6);
        assertEquals(Math.sqrt(1000 * 20), m.in.hpHz.at(m.t0 + m.D / 4), 1e-3);
        assertEquals(20, m.in.hpHz.at(m.t0 + m.D / 2 + 0.1), 1e-6);
        // the playing song's bass goes at the centre, the next song's comes in
        assertEquals(0, m.out.lowDb.at(m.t0 + 0.1), 1e-9);
        assertEquals(MixOverride.CUT_DB, m.out.lowDb.at(m.t0 + m.D - 0.1), 1e-9);
        assertEquals(MixOverride.CUT_DB, m.in.lowDb.at(m.t0 + 0.1), 1e-9);
        assertEquals(0, m.in.lowDb.at(m.t0 + m.D - 0.1), 1e-9);
    }
}
