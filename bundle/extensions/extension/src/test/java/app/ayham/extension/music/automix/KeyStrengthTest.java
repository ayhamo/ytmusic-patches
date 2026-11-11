package app.ayham.extension.music.automix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class KeyStrengthTest {
    private static final int RATE = 22050;

    /** Sines at the given MIDI notes, quiet enough not to clip, [seconds] long. */
    private static short[] chord(double seconds, int... midi) {
        short[] pcm = new short[(int) (seconds * RATE)];
        for (int i = 0; i < pcm.length; i++) {
            double v = 0;
            for (int m : midi) v += Math.sin(2 * Math.PI * 440 * Math.pow(2, (m - 69) / 12.0) * i / RATE);
            pcm[i] = (short) (v / midi.length * 12000);
        }
        return pcm;
    }

    @Test
    public void cMajorTriadReadsAsCMajor() {
        short[] pcm = chord(6, 60, 64, 67, 72); // C4 E4 G4 C5
        KeyStrength.Key k = KeyStrength.detect(pcm, pcm.length, RATE);
        assertNotNull(k);
        assertEquals("C major", k.name);
        assertTrue(k.strength > 0.5 && k.strength <= 1);
    }

    @Test
    public void aMinorTriadReadsAsAMinor() {
        short[] pcm = chord(6, 57, 60, 64, 69); // A3 C4 E4 A4
        KeyStrength.Key k = KeyStrength.detect(pcm, pcm.length, RATE);
        assertNotNull(k);
        assertEquals("A minor", k.name);
    }

    @Test
    public void silenceOrTooLittleAudioGivesNoKey() {
        short[] silent = new short[RATE * 6];
        assertNull(KeyStrength.detect(silent, silent.length, RATE));
        short[] shortClip = chord(1, 60, 64, 67);
        assertNull(KeyStrength.detect(shortClip, shortClip.length, RATE)); // under 8 frames of 8192
        assertNull(KeyStrength.detect(null, 0, RATE));
    }

    @Test
    public void keyNamesAreOnesTheCamelotWheelKnows() {
        // every key KeyStrength can name must map onto the wheel, or the key is silently dropped
        String[] roots = {"C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B"};
        for (String r : roots) {
            for (String mode : new String[]{" major", " minor"}) {
                assertTrue(r + mode, BeatGrid.camelot(r + mode, new Analysis()));
            }
        }
    }
}
