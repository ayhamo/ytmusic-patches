package app.ayham.extension.music.addons;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;

import org.junit.Test;

public class AtmosWavTest {
    private static short[] samples(File f) throws Exception {
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(f.toPath())).order(ByteOrder.LITTLE_ENDIAN);
        b.position(44);
        short[] s = new short[b.remaining() / 2];
        for (int i = 0; i < s.length; i++) s[i] = b.getShort();
        return s;
    }

    @Test
    public void writesAWavOfTheDecodeAndBakesTheGain() throws Exception {
        File f = File.createTempFile("atmos", ".wav");
        try {
            AtmosWav.Writer w = new AtmosWav.Writer(f);
            w.format(48000, 2);
            w.add(new float[][]{{0.25f, -0.5f, 0.75f}, {0.125f, 1f, -1f}}, 3);
            w.finish();
            ByteBuffer h = ByteBuffer.wrap(Files.readAllBytes(f.toPath())).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals("RIFF", new String(h.array(), 0, 4, "US-ASCII"));
            assertEquals(36 + 12, h.getInt(4));
            assertEquals(2, h.getShort(22));
            assertEquals(48000, h.getInt(24));
            assertEquals(12, h.getInt(40));
            short[] s = samples(f);
            assertEquals(8192, s[0]);
            assertEquals(4096, s[1]);
            assertEquals(-16384, s[2]);
            assertEquals(32767, s[3]);   // 1.0 held at full scale
            AtmosWav.scale(f, 20 * Math.log10(2));
            s = samples(f);
            assertEquals(16384, s[0]);
            assertEquals(8192, s[1]);
            assertEquals(-32768, s[2]);
            assertEquals(32767, s[3]);
            assertEquals(32767, s[4]);   // 0.75 doubled: held
            assertEquals(-32768, s[5]);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }
}
