package app.ayham.extension.music.automix;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * spec/automix/playback.md e9697cc7, "The queue during a mix" rule 5: the listener's pause during a
 * mix stops it at once, in every phase; rule 3: our own Next's pause, and the new song's load, don't.
 */
public class PauseTest {
    /** Away from our Next, YT's pause is the listener's: the mix stops at once. */
    @Test
    public void aPauseAwayFromOurNextStopsAtOnce() {
        assertTrue(Automix.listenersPause(Automix.NEXT_GRACE_MS, false, 0));
        assertTrue(Automix.listenersPause(60_000, false, 0));
    }

    /**
     * Between our Next and the new song (the player loading, its session buffering), a tap on the
     * play button to pause, or YT's session reading paused for two checks in a row (the notification,
     * a headset), stops it at once, not after the grace.
     */
    @Test
    public void aPauseWhileTheNextSongLoadsStopsAtOnce() {
        assertTrue(Automix.listenersPause(400, true, 0));
        assertTrue(Automix.listenersPause(400, false, Automix.PAUSED_READS));
        // our own Next's pause: the session never reads paused over it (pair runs 2026-11-25)
        assertFalse(Automix.listenersPause(400, false, 0));
        assertFalse(Automix.listenersPause(400, false, Automix.PAUSED_READS - 1));
    }

    /** The checks run every PAUSE_CHECK_MS: a held pause is caught within about a quarter second. */
    @Test
    public void aHeldPauseIsCaughtWithinAQuarterSecond() {
        assertTrue(Automix.PAUSE_CHECK_MS * Automix.PAUSED_READS <= 300);
        assertEquals(2, Automix.PAUSED_READS);
    }

    /**
     * After our Next, YT's paused player is left at the incoming song's spot the mix had reached, not
     * where its held, silent player was (pair 2, 2026-11-25: paused at 2.0 s of Cold Water, the mix at
     * 3.6 s of it).
     */
    @Test
    public void theIncomingSongIsLeftAtTheSpotTheMixReached() {
        Planner.Settings s = new Planner.Settings();
        s.version = 3;
        Analysis a = PlannerTest.song("a", 102.3, 220, 0.9), b = PlannerTest.song("b", 93, 210, 0.9);
        Planner.Plan p = Planner.plan(s, "a", "b", a, b, a.durationSec, 60, 60 + PlannerTest.EARLIEST_LEAD, null, null, 0.97057, 1.03228);
        Mix m = Mix.blend(p, "a", "b", 220);
        double t = m.t0 + 3;
        assertEquals((p.mixInTrackTime + 3 * p.rate) * 1000, Engine.incomingSpotMs(m, t), 30);
        // before B starts: its first second played
        assertEquals(Math.round(p.mixInTrackTime * 1000), Engine.incomingSpotMs(m, m.t0 - 1), 30);
    }

    /** The decks fade out in about 0.1 s from the next sample written. */
    @Test
    public void theDecksFadeOutInATenthOfASecond() {
        assertEquals(0.1, Engine.PAUSE_FADE_SEC, 1e-9);
        // from the first block not rendered yet (the loop renders one ahead and waits in the write
        // before it), not 0.2 s ahead as another song's fade
        assertEquals(12 + 2 * 512.0 / Engine.SR, Engine.pauseFadeFrom(Engine.SR * 10L, Engine.SR * 2L), 1e-9);
    }

    /** A pause during a running fade: the one that ends sooner, never back up to full. */
    @Test
    public void aPauseDuringAFadeKeepsTheSoonerEnd() {
        double L = Engine.PAUSE_FADE_SEC;
        // none running: 0.1 s from the pause
        assertArrayEquals(new double[]{20, L}, Engine.pauseFade(Double.NaN, 0, 20), 1e-12);
        // an abort's 0.05 s ending first: kept as it is
        assertArrayEquals(new double[]{19.98, 0.05}, Engine.pauseFade(19.98, 0.05, 20), 1e-12);
        // another song's 1.5 s fade, half way down: on from 0.5 to 0 by the pause's end, no jump up
        double[] f = Engine.pauseFade(19.25, 1.5, 20);
        assertEquals(20 + L, f[0] + f[1], 1e-12);
        assertEquals(0.5, 1 - (20 - f[0]) / f[1], 1e-12);
        // that fade not begun yet (it starts 0.2 s ahead): the pause's own, from full
        assertArrayEquals(new double[]{20, L}, Engine.pauseFade(20.2, 1.5, 20), 1e-12);
    }
}
