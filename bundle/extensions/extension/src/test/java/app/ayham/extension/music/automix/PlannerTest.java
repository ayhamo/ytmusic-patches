package app.ayham.extension.music.automix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PlannerTest {
    static final double EARLIEST_LEAD = Mix.HAND_LEAD + 2.5; // as Automix plans

    /** A song with a steady grid from 0.5 s (the same at its end), no energy curve. */
    static Analysis song(String id, double bpm, double dur, double conf) {
        Analysis a = new Analysis();
        a.fileKey = id;
        a.durationSec = dur;
        a.bpm = bpm;
        a.beatIntervalSec = 60 / bpm;
        a.firstDownbeatSec = 0.5;
        a.gridConfidence = conf;
        a.tailGrid = new Analysis.LocalGrid();
        a.tailGrid.bpm = bpm;
        a.tailGrid.beatIntervalSec = 60 / bpm;
        a.tailGrid.firstDownbeatSec = 0.5;
        a.tailGrid.gridConfidence = conf;
        a.energy = 0.5;
        a.introEndSec = 8;
        return a;
    }

    static Planner.Plan plan(Planner.Settings s, Analysis from, Analysis to, double pos) {
        return Planner.plan(s, "a", "b", from, to, from == null ? 200 : from.durationSec, pos, pos + EARLIEST_LEAD, null);
    }

    @Test
    public void sameTempoBlendEndsOnTheBarGridBeforeTheEnd() {
        Planner.Plan p = plan(new Planner.Settings(), song("a", 120, 200, 0.9), song("b", 120, 180, 0.9), 60);
        assertEquals("blend", p.style);
        assertFalse(p.fallback);
        assertEquals(1, p.rate, 0);
        assertEquals(1, p.outRate, 0);
        assertEquals(0, p.rampBeats);
        // auto length: 16 beats capped at 12 s -> 4 bars of 2 s
        assertEquals(4, p.bars);
        assertEquals(8, p.durationSec, 1e-9);
        // the last downbeat that leaves a bar after the blend: 200 - 2 - 8 = 190 -> 188.5
        assertEquals(188.5, p.mixOutTrackTime, 1e-9);
        assertEquals(0, ((p.mixOutTrackTime - 0.5) / 2) % 1, 1e-9);
        assertTrue(p.mixOutTrackTime + p.durationSec <= 200 - 0.3);
        // the next song arrives so its first full-energy phrase (16.5 s) lands at the blend's end
        assertEquals(8.5, p.mixInTrackTime, 1e-9);
        assertEquals(Planner.SHORT_BLEND_KILL_DB, p.swapDb, 0);
    }

    @Test
    public void fixedBarsAreHonouredAndConfidenceLimitsThem() {
        Planner.Settings s = new Planner.Settings();
        s.bars = 16;
        Planner.Plan p = plan(s, song("a", 120, 200, 0.9), song("b", 120, 180, 0.9), 60);
        assertEquals(16, p.bars);
        assertEquals(Planner.KILL_DB, p.swapDb, 0);
        p = plan(s, song("a", 120, 200, 0.5), song("b", 120, 180, 0.9), 60);
        assertEquals(8, p.bars); // confidence 0.45-0.6: at most 8
        p = plan(s, song("a", 120, 200, 0.3), song("b", 120, 180, 0.9), 60);
        assertEquals(4, p.bars); // under 0.45: at most 4
    }

    @Test
    public void slowerNextSongSpeedsThePlayingSongUpOverARamp() {
        // 120 into 126: the playing song goes up 5% in 0.75% steps per beat
        Planner.Plan p = plan(new Planner.Settings(), song("a", 120, 200, 0.9), song("b", 126, 180, 0.9), 60);
        assertEquals("blend", p.style);
        assertEquals(1, p.rate, 0);
        assertEquals(1.05, p.outRate, 1e-9);
        assertEquals(7, p.rampBeats);
        assertEquals(7 * 0.5, p.rampSongSec, 1e-9);
        assertEquals(126, p.bpm, 1e-9);
    }

    @Test
    public void temposTooFarApartGiveAFilterMix() {
        Planner.Plan p = plan(new Planner.Settings(), song("a", 120, 200, 0.9), song("b", 90, 180, 0.9), 60);
        assertEquals("filter", p.style);
        assertTrue(p.reason, p.reason.contains("apart"));
        assertEquals(1, p.rate, 0);
        assertEquals(0.5, p.mixInTrackTime, 1e-9); // on its own first downbeat
    }

    @Test
    public void halfTimeFoldsBeforeStretching() {
        assertEquals(1.0, Planner.foldRate(2.0), 1e-12);
        assertEquals(1.0, Planner.foldRate(0.5), 1e-12);
        assertEquals(1.1, Planner.foldRate(1.1), 1e-12);
        assertTrue(Planner.stretchAllowed(1.25));
        assertFalse(Planner.stretchAllowed(1.26));
        assertTrue(Planner.stretchAllowed(0.9));
        assertFalse(Planner.stretchAllowed(0.89));
    }

    @Test
    public void unanalysedSongsFallBackToACrossfade() {
        Planner.Plan p = Planner.plan(new Planner.Settings(), "a", "b", null, null, 200, 60, 60 + EARLIEST_LEAD, null);
        assertEquals("crossfade", p.style);
        assertTrue(p.fallback);
        assertEquals(200 - 0.3 - Planner.FALLBACK_SEC, p.mixOutTrackTime, 1e-9);
        assertEquals(Planner.FALLBACK_SEC, p.durationSec, 1e-9);
    }

    @Test
    public void noGridAtTheEndFallsBack() {
        Analysis a = song("a", 120, 200, 0.9);
        a.tailGrid = null; // known: no beat at its end
        Planner.Plan p = plan(new Planner.Settings(), a, song("b", 120, 180, 0.9), 60);
        assertTrue(p.fallback);
        assertTrue(p.reason, p.reason.contains("end"));
    }

    @Test
    public void tooLateForABlendGivesShorterOrNoMix() {
        Planner.Plan p = plan(new Planner.Settings(), song("a", 120, 200, 0.9), song("b", 120, 180, 0.9), 195);
        assertTrue(p.style.equals("crossfade") || p.mixOutTrackTime + p.durationSec <= 200);
        assertTrue(p.durationSec >= 0);
    }

    @Test
    public void advancedAutoDjPlanIsMarkedAndBuildsAMix() {
        Planner.Settings s = new Planner.Settings();
        s.version = 2;
        Planner.Sung sung = new Planner.Sung();
        sung.out = new double[0][];
        sung.in = new double[0][];
        Planner.Plan p = Planner.plan(s, "a", "b", song("a", 120, 200, 0.9), song("b", 120, 180, 0.9), 200, 60, 60 + EARLIEST_LEAD, sung);
        assertEquals("blend", p.style);
        assertTrue(p.v2);
        assertEquals(0, p.clash, 0);
        Mix m = Mix.blend(p, "a", "b", 200);
        assertTrue(m.v2);
        assertEquals(1, m.out.gain.at(m.t0), 1e-3);
        assertEquals(1, m.in.gain.at(m.t0 + m.D + 0.01), 1e-9);
    }

    @Test
    public void keyClashRule() {
        Analysis a = new Analysis(), b = new Analysis();
        a.camelotNum = 8;
        a.camelotLetter = 'A';
        a.keyConfidence = 0.8;
        b.camelotNum = 9;
        b.camelotLetter = 'A';
        b.keyConfidence = 0.8;
        assertNull(Planner.keysClash(a, b)); // neighbours
        b.camelotNum = 7;
        assertNull(Planner.keysClash(a, b));
        b.camelotNum = 8;
        b.camelotLetter = 'B';
        assertNull(Planner.keysClash(a, b)); // relative major
        a.camelotNum = 12;
        a.camelotLetter = 'B';
        b.camelotNum = 1;
        assertNull(Planner.keysClash(a, b)); // 12 and 1 are neighbours on the wheel
        b.camelotNum = 3;
        assertNotNull(Planner.keysClash(a, b));
        b.keyConfidence = 0.4;
        assertNull(Planner.keysClash(a, b)); // a weak key never clashes
    }

    @Test
    public void vocalOverlapFromLyricSpans() {
        Analysis a = song("a", 120, 200, 0.9), b = song("b", 120, 180, 0.9);
        Planner.Sung sung = new Planner.Sung();
        sung.out = new double[][]{{0, 1000}};
        sung.in = new double[][]{{0, 1000}};
        assertEquals(1, Planner.vocalOverlap(a, b, 100, 8, 0, 1, sung), 1e-9);
        sung.in = new double[0][];
        assertEquals(0, Planner.vocalOverlap(a, b, 100, 8, 0, 1, sung), 1e-9);
        // neither lyrics nor a mask: unknown
        assertTrue(Double.isNaN(Planner.vocalOverlap(a, b, 100, 8, 0, 1, new Planner.Sung())));
    }

    @Test
    public void maskStatsNeedsCoverage() {
        Analysis.VocalWindow w = new Analysis.VocalWindow();
        w.startSec = 10;
        w.fps = 10;
        w.curve = new float[100]; // 10 s to 20 s
        for (int i = 50; i < 60; i++) w.curve[i] = 1; // sung 15-16 s
        double[] m = Planner.maskStats(java.util.Collections.singletonList(w), 12, 18);
        assertNotNull(m);
        assertEquals(10 / 60.0, m[0], 1e-9);
        assertEquals(1.0, m[1], 1e-9);
        assertNull(Planner.maskStats(java.util.Collections.singletonList(w), 0, 12)); // covers 2 of 12 s
    }

    static Planner.Audio flat(double duration) {
        return new Planner.Audio() {
            @Override
            public double duration() {
                return duration;
            }

            @Override
            public double rms(double from, double to) {
                return 0.5;
            }
        };
    }

    static Planner.LoopPlan loopPlan() {
        Planner.Sung sung = new Planner.Sung();
        sung.out = new double[0][];
        sung.in = new double[0][];
        return Planner.planLoopMix(song("a", 120, 200, 0.9), flat(200), song("b", 120, 180, 0.9), flat(180), 100, sung);
    }

    @Test
    public void loopMixPicksTheLastCleanLoopOutAndTheFirstIn() {
        Planner.LoopPlan lp = loopPlan();
        assertNotNull(lp);
        assertNull(lp.none);
        assertEquals(190.5, lp.out.loopStart, 1e-9);
        assertEquals(198.5, lp.out.loopEnd, 1e-9);
        assertEquals(0.5, lp.in.loopStart, 1e-9);
        assertEquals(8.5, lp.in.loopEnd, 1e-9);
        assertEquals(1, lp.fold, 0);
        assertEquals(Planner.MIN_RAMP_BARS, lp.rampBars);
        assertEquals(8, lp.totalBars);
        assertEquals("lyrics", lp.out.checkedBy);
    }

    @Test
    public void loopMixSkipsSungBarsAndMusicVideos() {
        Planner.Sung sung = new Planner.Sung();
        sung.out = new double[][]{{186, 199}}; // the last loops are sung
        sung.in = new double[0][];
        Planner.LoopPlan lp = Planner.planLoopMix(song("a", 120, 200, 0.9), flat(200), song("b", 120, 180, 0.9), flat(180), 100, sung);
        assertNotNull(lp);
        assertTrue(lp.out.loopEnd <= 186);

        Analysis video = song("a", 120, 200, 0.9);
        video.musicVideo = true;
        assertNull(Planner.planLoopMix(video, flat(200), song("b", 120, 180, 0.9), flat(180), 100, sung));
    }

    @Test
    public void loopScheduleKeepsBeatsInOrder() {
        Planner.LoopPlan lp = loopPlan();
        Planner.LoopSchedule x = Planner.loopSchedule(lp, 0);
        assertEquals(32, x.beats);
        assertEquals(x.beats + x.settleBeats + 1, x.beatTimes.length);
        for (int k = 1; k < x.beatTimes.length; k++) assertTrue(x.beatTimes[k] > x.beatTimes[k - 1]);
        assertEquals(16, x.releaseAt, 1e-9); // 32 beats at 120 BPM
        assertEquals(20, x.elementAt, 1e-9);
        assertTrue(x.enterEnd < x.rampEnd && x.rampEnd <= x.releaseAt);
    }

    @Test
    public void tempoRampIsMonotonic() {
        double last = Planner.tempo(0, 16, 24, 120, 128);
        assertEquals(120, last, 0);
        for (int k = 1; k < 40; k++) {
            double t = Planner.tempo(k, 16, 24, 120, 128);
            assertTrue(t >= last);
            last = t;
        }
        assertEquals(128, last, 0);
    }
}
