package app.ayham.extension.music.automix;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** A mix's held addon player and automix's speed nudges of YT's player (AddonDirect.relockHeld). */
public class HeldLockTest {
    @Test
    public void aNudgesLagIsNotADrift() {
        // S21 FE, 2026-11-19: -25 ms read 300 ms into automix's x0.953 nudge: a relock, 4 s lost
        assertFalse(AddonDirect.relockHeld(-25, 300));
        assertFalse(AddonDirect.relockHeld(-25, AddonDirect.FOLLOW_SETTLE_MS - 1));
        // the same error once both players run at the speed: a real drift, locked again
        assertTrue(AddonDirect.relockHeld(-25, AddonDirect.FOLLOW_SETTLE_MS));
    }

    @Test
    public void withinTheLooseLockNothingMoves() {
        assertFalse(AddonDirect.relockHeld(20, 5000));
        assertFalse(AddonDirect.relockHeld(-12, 5000));
        assertTrue(AddonDirect.relockHeld(21, 5000));
    }

    @Test
    public void inStepOnlyOnAReadingPastTheLag() {
        assertFalse(AddonDirect.settled(0));
        assertFalse(AddonDirect.settled(AddonDirect.FOLLOW_SETTLE_MS - 1));
        assertTrue(AddonDirect.settled(AddonDirect.FOLLOW_SETTLE_MS));
        // never followed a speed: settled
        assertTrue(AddonDirect.settled(System.currentTimeMillis() - Long.MIN_VALUE / 2));
    }
}
