package app.ayham.extension.music.automix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/** The window's limits a drag stops at (editor.md §6, automix.js windowLimits). */
public class WindowLimitsTest {
    private static Planner.Plan blend() {
        Planner.Plan p = new Planner.Plan();
        p.style = "blend";
        p.beatSec = 0.5;
        p.bars = 4;          // 8 s of A
        p.rate = 1.1;
        p.outRate = 1;
        p.rampSongSec = 2;
        p.durationSec = 8;
        return p;
    }

    @Test
    public void aBlendsLimits() {
        double[] l = Planner.windowLimits(blend(), 200, 190, 180, 50);
        assertEquals(52, l[0], 1e-9);              // earliest + the tempo ramp
        assertEquals(190 - 8, l[1], 1e-9);         // the window ends with A's music
        assertEquals(0, l[2], 1e-9);
        assertEquals(180 - 10 - 8 * 1.1, l[3], 1e-9); // B's span at its rate, 10 s before its end
    }

    @Test
    public void aGlidesSpanOnB() {
        Planner.Plan p = blend();
        p.glide = new Planner.Glide(0.9, 16, 0.5);
        assertEquals(180 - 10 - 8 / 0.9, Planner.windowLimits(p, 200, 200, 180, 0)[3], 1e-9);
    }

    @Test
    public void aCrossfadeEndsBeforeTheMusicLessItsLength() {
        Planner.Plan p = new Planner.Plan();
        p.style = "crossfade";
        p.durationSec = 12;
        double[] l = Planner.windowLimits(p, 200, 195, 150, 30);
        assertEquals(30, l[0], 1e-9);              // no ramp outside a blend
        assertEquals(195 - 0.3 - 12, l[1], 1e-9);
        assertEquals(150 - 10 - 12, l[3], 1e-9);
    }

    @Test
    public void pastTheEarliestTheSongStaysAtIt() {
        // the earliest past where the window can end: held at the earliest, not inverted
        double[] l = Planner.windowLimits(blend(), 200, 190, 180, 185);
        assertEquals(l[0], l[1], 1e-9);
    }

    @Test
    public void loopDropsASavedBeatMatch() {
        Map<String, Object> j = new HashMap<>();
        j.put("v", 1);
        j.put("preset", "loop");
        j.put("beatMatch", "sync");
        MixOverride o = MixOverride.normalize(MixOverride.fromJson(j));
        assertNull(o == null ? null : o.beatMatch);
    }
}
