package app.ayham.extension.music.addons;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.ShortBuffer;
import java.nio.channels.FileChannel;

/**
 * Dolby Atmos is played from our own decode of the file: the phone's Dolby decoder's 2-channel
 * render (AudioDecode), written as a 16-bit WAV with the gain in it.
 *
 * YT's player decoding the E-AC-3 itself didn't line up with that decode, which the offset and
 * the mix decks come from (S21 FE, Blinding Lights, recorded): it played 41 to 59 ms ahead, a
 * different amount after each seek, and 6 dB quieter. So a hand-over to the decks jumped and
 * stepped in level. Played from the WAV, the player, the fit and the decks read the same samples.
 * The gain is in the samples too, so a boost needs no more than the player's volume of 1.
 */
public final class AtmosWav {
    private AtmosWav() {
    }

    private static final int HEADER = 44;

    /** Written while the file decodes; finish() fills in the header. */
    public static final class Writer {
        private final File file;
        private OutputStream out;
        private int channels;
        private int rate;
        private long bytes;
        private byte[] buf = new byte[0];

        public Writer(File file) {
            this.file = file;
        }

        public File file() {
            return file;
        }

        public void format(int rate, int channels) throws IOException {
            if (out != null) return;   // a decoder's second format change: the samples carry on
            this.rate = rate;
            this.channels = Math.min(2, channels);
            out = new BufferedOutputStream(new FileOutputStream(file), 1 << 16);
            out.write(new byte[HEADER]);
        }

        public void add(float[][] planar, int frames) throws IOException {
            if (out == null) return;
            int n = frames * channels * 2;
            if (buf.length < n) buf = new byte[n];
            int k = 0;
            for (int f = 0; f < frames; f++) {
                for (int c = 0; c < channels; c++) {
                    float[] ch = planar[Math.min(c, planar.length - 1)];
                    int v = Math.round(ch[f] * 32768f);
                    if (v > 32767) v = 32767;
                    else if (v < -32768) v = -32768;
                    buf[k++] = (byte) v;
                    buf[k++] = (byte) (v >> 8);
                }
            }
            out.write(buf, 0, n);
            bytes += n;
        }

        /** The header, then the file closed. */
        public void finish() throws IOException {
            if (out == null) throw new IOException("no audio decoded");
            out.close();
            out = null;
            try (RandomAccessFile f = new RandomAccessFile(file, "rw")) {
                f.seek(0);
                f.write(header(rate, channels, bytes));
            }
        }

        /** On a failure: closed and deleted. */
        public void abort() {
            try {
                if (out != null) out.close();
            } catch (IOException ignored) {
            }
            out = null;
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    static byte[] header(int rate, int channels, long dataBytes) {
        ByteBuffer h = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN);
        h.put(new byte[]{'R', 'I', 'F', 'F'}).putInt((int) (36 + dataBytes)).put(new byte[]{'W', 'A', 'V', 'E'});
        h.put(new byte[]{'f', 'm', 't', ' '}).putInt(16).putShort((short) 1).putShort((short) channels)
                .putInt(rate).putInt(rate * channels * 2).putShort((short) (channels * 2)).putShort((short) 16);
        h.put(new byte[]{'d', 'a', 't', 'a'}).putInt((int) dataBytes);
        return h.array();
    }

    /**
     * The gain into the samples, in place. The fit's gain keeps a boost under -1 dBTP, so nothing
     * clips; a sample that would is held at full scale.
     */
    public static void scale(File wav, double gainDb) throws IOException {
        // a WAV that is gone or empty must not pass as the song's audio (RandomAccessFile would
        // create it)
        if (wav == null || wav.length() <= HEADER) throw new IOException("Atmos WAV missing or empty: " + wav);
        if (Math.abs(gainDb) < 0.005) return;
        float g = (float) Math.pow(10, gainDb / 20);
        try (RandomAccessFile f = new RandomAccessFile(wav, "rw"); FileChannel ch = f.getChannel()) {
            long len = ch.size() - HEADER;
            for (long at = 0; at < len; at += 1 << 24) {
                long n = Math.min(1L << 24, len - at) & ~1L;
                MappedByteBuffer m = ch.map(FileChannel.MapMode.READ_WRITE, HEADER + at, n);
                ShortBuffer s = m.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
                for (int i = 0; i < s.limit(); i++) {
                    int v = Math.round(s.get(i) * g);
                    s.put(i, (short) (v > 32767 ? 32767 : v < -32768 ? -32768 : v));
                }
                m.force();
            }
        }
    }

    /** A downloaded Atmos file decoded to dst with gainDb in it. */
    public static File decode(File src, File dst, double gainDb) throws Exception {
        final Writer w = new Writer(dst);
        try {
            AudioDecode.decode(src, new AudioDecode.Sink() {
                @Override
                public void format(int rate, int channels) throws IOException {
                    w.format(rate, channels);
                }

                @Override
                public void block(float[][] planar, int frames) throws IOException {
                    w.add(planar, frames);
                }
            });
            w.finish();
            scale(dst, gainDb);
            return dst;
        } catch (Throwable t) {
            w.abort();
            throw t;
        }
    }
}
