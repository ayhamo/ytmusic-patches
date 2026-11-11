package app.ayham.extension.music.spike;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SpikeHTest {

    @Test
    public void cleanKeepsTheArtistOfYtsSubtitle() {
        assertEquals("Sabrina Carpenter", SpikeH.clean("Sabrina Carpenter • Short n' Sweet • 2024"));
        assertEquals("Sabrina Carpenter", SpikeH.clean("Sabrina Carpenter"));
    }

    @Test
    public void cleanDropsTopic() {
        assertEquals("Adele", SpikeH.clean("Adele - Topic"));
        assertEquals("Adele", SpikeH.clean("Adele - topic • 25 • 2015"));
    }

    @Test
    public void cleanTakesTheFirstOfSeveralArtists() {
        assertEquals("The Weeknd", SpikeH.clean("The Weeknd, Lily Rose Depp & Ramsey"));
        assertEquals("Dua Lipa", SpikeH.clean("Dua Lipa & Elton John"));
        assertEquals("Calvin Harris", SpikeH.clean("Calvin Harris feat. Rihanna"));
        assertEquals("Calvin Harris", SpikeH.clean("Calvin Harris ft Rihanna"));
        assertEquals("Fred again..", SpikeH.clean("Fred again.. x Skrillex"));
    }

    @Test
    public void cleanOfNothingIsEmpty() {
        assertEquals("", SpikeH.clean(""));
        assertEquals("", SpikeH.clean("  "));
    }
}
