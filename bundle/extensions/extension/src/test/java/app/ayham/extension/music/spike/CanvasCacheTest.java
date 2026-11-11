package app.ayham.extension.music.spike;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.BeforeClass;
import org.junit.Test;

/** CanvasCache is a process-wide singleton, initialised once: every test shares one temp folder. */
public class CanvasCacheTest {
    private static File dir;

    @BeforeClass
    public static void setUp() throws Exception {
        dir = Files.createTempDirectory("ayham-canvas-test").toFile();
        dir.deleteOnExit();
        File clip = new File(dir, "clip.mp4");
        Files.write(clip.toPath(), new byte[]{1});
        clip.deleteOnExit();
        // a file as an app killed mid-write could leave it, next to good entries
        String text = "song.noBar=1700000000000\n"
                + "song.notANumber=abc|\n"
                + "song.empty=\n"
                + "song.oldMiss=1|\n"
                + "song.oldClip=1|" + clip.getAbsolutePath().replace("\\", "\\\\") + "\n"
                + "song.goneClip=" + System.currentTimeMillis() + "|/no/such/file.mp4\n"
                + "token=abc.def.ghi\n"
                + "tokenExpires=12345\n";
        try (FileOutputStream out = new FileOutputStream(new File(dir, "ayham-canvas.properties"))) {
            out.write(text.getBytes(StandardCharsets.ISO_8859_1));
        }
        CanvasCache.init(dir);
    }

    @Test
    public void damagedEntriesReadAsUnknownInsteadOfThrowing() {
        assertNull(CanvasCache.lookup("noBar"));
        assertNull(CanvasCache.lookup("notANumber"));
        assertNull(CanvasCache.lookup("empty"));
    }

    @Test
    public void savedClipPlaysAtAnyAgeAndAMissExpires() {
        assertTrue(CanvasCache.lookup("oldClip").endsWith("clip.mp4"));
        assertNull(CanvasCache.lookup("oldMiss"));   // a miss older than a day: looked up again
        assertNull(CanvasCache.lookup("goneClip"));  // the clip file was deleted: looked up again
        assertNull(CanvasCache.lookup("never"));
    }

    @Test
    public void putThenLookupAndNoPartFileLeft() {
        CanvasCache.put("fresh miss", null);
        assertEquals("", CanvasCache.lookup("fresh miss"));
        CanvasCache.put(CanvasCache.idKey("abcdefghijk"), new File(dir, "clip.mp4").getAbsolutePath());
        assertTrue(CanvasCache.lookup("id:abcdefghijk").endsWith("clip.mp4"));
        assertFalse(new File(dir, "ayham-canvas.properties.part").exists());
        assertTrue(new File(dir, "ayham-canvas.properties").length() > 0);
    }

    @Test
    public void tokenBackup() {
        assertArrayEquals(new String[]{"abc.def.ghi", "12345"}, CanvasCache.token());
        CanvasCache.token("x.y.z", 99);
        assertArrayEquals(new String[]{"x.y.z", "99"}, CanvasCache.token());
        CanvasCache.token(null, 0);
        assertNull(CanvasCache.token());
    }
}
