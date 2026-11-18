package app.ayham.extension.music.addons;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Downloads: what a file really is (its bytes), the tier steps, and the stated-format check. */
public class DownloadFormatTest {
    /** A FLAC header: "fLaC", a STREAMINFO block with this rate, channels and bits. */
    private static byte[] flac(int rate, int channels, int bits) {
        byte[] b = new byte[64];
        b[0] = 'f';
        b[1] = 'L';
        b[2] = 'a';
        b[3] = 'C';
        b[4] = (byte) 0x80; // last block, STREAMINFO
        b[7] = 34;
        int d = 8;
        b[d + 10] = (byte) (rate >> 12);
        b[d + 11] = (byte) (rate >> 4);
        b[d + 12] = (byte) ((rate & 0xf) << 4 | (channels - 1) << 1 | ((bits - 1) >> 4));
        b[d + 13] = (byte) (((bits - 1) & 0xf) << 4);
        return b;
    }

    @Test
    public void flacStreamInfo() {
        Fetch.Info cd = Fetch.info(flac(44100, 2, 16), 64);
        assertNotNull(cd);
        assertEquals(44100, cd.rate);
        assertEquals(16, cd.bits);
        assertFalse(cd.hiRes());
        Fetch.Info hr = Fetch.info(flac(96000, 2, 24), 64);
        assertEquals(96000, hr.rate);
        assertEquals(24, hr.bits);
        assertTrue(hr.hiRes());
        assertTrue(Fetch.info(flac(192000, 2, 16), 64).hiRes());
    }

    @Test
    public void lossyIsNotLossless() {
        byte[] mp3 = new byte[64];
        mp3[0] = 'I';
        mp3[1] = 'D';
        mp3[2] = '3';
        assertNull(Fetch.info(mp3, 64));
        byte[] aac = new byte[64];
        aac[4] = 'f';
        aac[5] = 't';
        aac[6] = 'y';
        aac[7] = 'p';
        aac[8] = 'M';
        aac[9] = '4';
        aac[10] = 'A';
        assertNull(Fetch.info(aac, 64)); // an MP4 with no 'alac' box
    }

    @Test
    public void tiersStepDown() {
        assertArrayEquals(new String[]{Pick.LOSSLESS, Pick.HIRES, Pick.CD}, AddonDownloads.steps(AddonDownloads.BEST));
        assertArrayEquals(new String[]{Pick.HIRES, Pick.CD}, AddonDownloads.steps(AddonDownloads.HIRES));
        assertArrayEquals(new String[]{Pick.CD}, AddonDownloads.steps(AddonDownloads.CD));
    }

    @Test
    public void statedFormat() {
        Protocol.Stream s = new Protocol.Stream();
        s.codec = "mp3";
        assertNotNull(Fetch.statedRefusal(s, Pick.CD));
        s.codec = "FLAC";
        s.bitDepth = 16.0;
        s.sampleRate = 44100.0;
        assertNull(Fetch.statedRefusal(s, Pick.CD));
        assertNotNull(Fetch.statedRefusal(s, Pick.HIRES));
        s.bitDepth = 24.0;
        assertNull(Fetch.statedRefusal(s, Pick.HIRES));
        s.codec = null; // not stated: the bytes decide
        assertNull(Fetch.statedRefusal(s, Pick.HIRES));
    }
}
