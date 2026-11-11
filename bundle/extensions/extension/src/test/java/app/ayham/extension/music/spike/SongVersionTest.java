package app.ayham.extension.music.spike;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;

import org.junit.Test;

public class SongVersionTest {

    private static double score(String expected, String actual) throws Exception {
        Method m = SongVersion.class.getDeclaredMethod("score", String.class, String.class);
        m.setAccessible(true);
        return (double) m.invoke(null, expected, actual);
    }

    private static String normalize(String s) throws Exception {
        Method m = SongVersion.class.getDeclaredMethod("normalize", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, s);
    }

    @Test
    public void normalizeDropsBracketsAndPunctuation() throws Exception {
        assertEquals("artist song title", normalize("Artist - Song Title (Official Music Video) [4K]"));
        assertEquals("don t stop", normalize("Don't Stop!"));
        assertEquals("beyoncé halo", normalize("Beyoncé – Halo"));
    }

    @Test
    public void scoreMatchesTitlesInsideEachOther() throws Exception {
        assertEquals(1, score("Artist - Song Title (Official Video)", "Song Title"), 0);
        assertEquals(0, score("Completely different", "Nothing alike"), 0);
        assertEquals(0.5, score("one two three four", "one two five six"), 1e-12);
        assertEquals(0, score("", "x"), 0);
    }

    @Test
    public void nonLatinTitlesCanMatch() throws Exception {
        // Arabic and Japanese titles used to normalise to nothing, so they never found their song version.
        assertEquals(1, score("عمرو دياب - تملي معاك (فيديو كليب)", "تملي معاك"), 0);
        assertEquals(1, score("米津玄師 - Lemon", "Lemon"), 0);
        assertEquals(1, score("YOASOBI「夜に駆ける」Official Music Video", "夜に駆ける"), 0);
        assertTrue(score("उड़ता पंजाब गाना", "उड़ता पंजाब") >= 1);
        assertEquals(0, score("عمرو دياب", "أم كلثوم"), 0);
    }

    @Test
    public void typeFromADownloadMakesAMusicVideo() {
        assertFalse(SongVersion.musicVideo("vid00000001"));
        SongVersion.noteType("vid00000001", "MUSIC_VIDEO_TYPE_OMV");
        assertTrue(SongVersion.musicVideo("vid00000001"));
        assertFalse(SongVersion.swapped("vid00000001"));
        SongVersion.noteType("vid00000002", "MUSIC_VIDEO_TYPE_ATV");
        assertFalse(SongVersion.musicVideo("vid00000002"));
        SongVersion.noteType("vid00000003", "");
        assertFalse(SongVersion.musicVideo("vid00000003"));
        assertEquals(0, SongVersion.lengthFor("vid00000001"));
    }
}
