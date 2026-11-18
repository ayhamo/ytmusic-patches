package app.ayham.extension.music.addons;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A whole song decoded by MediaExtractor + MediaCodec, the decoders ExoPlayer itself plays through
 * on this device (offset-map.md: fit on the same decoder's output that plays), handed out as
 * planar float blocks at the file's own rate. Also the mono 22 050 Hz stream the fit reads.
 */
public final class AudioDecode {
    private AudioDecode() {
    }

    public interface Sink {
        /** The format, before the first block. */
        void format(int rate, int channels) throws IOException;

        /** frames samples per channel. */
        void block(float[][] planar, int frames) throws IOException;
    }

    /** Longer files (sets, albums in one) are not fitted. */
    public static final double MAX_SEC = 15 * 60;

    public static void decode(File file, Sink sink) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        try {
            ex.setDataSource(file.getPath());
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    ex.selectTrack(i);
                    fmt = f;
                    break;
                }
            }
            if (fmt == null) throw new IOException("no audio track");
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            codec.configure(fmt, null, null, 0);
            codec.start();
            int rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE), channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int enc = AudioFormat.ENCODING_PCM_16BIT;
            boolean told = false, inputDone = false;
            long frames = 0;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            float[][] planar = null;
            while (true) {
                if (!inputDone) {
                    int in = codec.dequeueInputBuffer(10_000);
                    if (in >= 0) {
                        ByteBuffer b = codec.getInputBuffer(in);
                        int n = b == null ? -1 : ex.readSampleData(b, 0);
                        if (n < 0) {
                            codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(in, 0, n, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int out = codec.dequeueOutputBuffer(info, 10_000);
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    enc = of.containsKey(MediaFormat.KEY_PCM_ENCODING) ? of.getInteger(MediaFormat.KEY_PCM_ENCODING) : AudioFormat.ENCODING_PCM_16BIT;
                    continue;
                }
                if (out < 0) continue;
                if (!told) {
                    told = true;
                    sink.format(rate, channels);
                }
                ByteBuffer buf = codec.getOutputBuffer(out);
                if (buf != null && info.size > 0) {
                    buf = buf.order(ByteOrder.nativeOrder());
                    buf.position(info.offset).limit(info.offset + info.size);
                    int bytes = enc == AudioFormat.ENCODING_PCM_FLOAT ? 4 : enc == AudioFormat.ENCODING_PCM_24BIT_PACKED ? 3
                            : enc == AudioFormat.ENCODING_PCM_32BIT ? 4 : enc == AudioFormat.ENCODING_PCM_8BIT ? 1 : 2;
                    int n = info.size / (channels * bytes);
                    if (planar == null || planar.length != channels || planar[0].length < n) planar = new float[channels][Math.max(n, 4096)];
                    for (int f = 0; f < n; f++) {
                        for (int c = 0; c < channels; c++) planar[c][f] = sample(buf, enc);
                    }
                    sink.block(planar, n);
                    frames += n;
                    if (frames > MAX_SEC * rate) throw new IOException("over " + (int) MAX_SEC + " s");
                }
                codec.releaseOutputBuffer(out, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            }
            if (!told) throw new IOException("nothing decoded");
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Throwable ignored) {
                }
                codec.release();
            }
            ex.release();
        }
    }

    private static float sample(ByteBuffer b, int enc) {
        switch (enc) {
            case AudioFormat.ENCODING_PCM_FLOAT:
                return b.getFloat();
            case AudioFormat.ENCODING_PCM_24BIT_PACKED: {
                int lo = b.get() & 0xFF, mid = b.get() & 0xFF, hi = b.get();
                return ((hi << 16) | (mid << 8) | lo) / 8388608f;
            }
            case AudioFormat.ENCODING_PCM_32BIT:
                return b.getInt() / 2147483648f;
            case AudioFormat.ENCODING_PCM_8BIT:
                return ((b.get() & 0xFF) - 128) / 128f;
            default:
                return b.getShort() / 32768f;
        }
    }

    /**
     * Planar audio streamed to another rate, in order: the native resampler run on 1 s blocks
     * with 0.1 s of context each side (zeros beyond the ends), the middle kept, so the stream is
     * continuous and the memory flat whatever the song's length and rate.
     */
    public static final class Resampler {
        public interface Out {
            void put(float[][] planar, int off, int frames) throws IOException;
        }

        private final int from, to, pad;
        private final float[][] buf;   // source rate: [pad context][1 s][pad context]
        private final Out out;
        private int have;              // frames in buf

        public Resampler(int from, int to, int channels, Out out) {
            this.from = from;
            this.to = to;
            this.out = out;
            pad = Math.max(1, from / 10);
            buf = new float[channels][from + 2 * pad];
            have = pad;                // zeros before the start
        }

        public void add(float[][] planar, int off, int frames) throws IOException {
            if (from == to) {
                out.put(planar, off, frames);
                return;
            }
            for (int f = 0; f < frames; f++) {
                for (int c = 0; c < buf.length; c++) buf[c][have] = planar[c][off + f];
                if (++have == buf[0].length) block(buf[0].length - 2 * pad);
            }
        }

        /** Resamples the buffer and hands out its middle: real frames after the leading context. */
        private void block(int real) throws IOException {
            float[][] o = new float[buf.length][];
            for (int c = 0; c < buf.length; c++) o[c] = app.ayham.extension.music.automix.Models.resample(buf[c], from, to);
            int outPad = (int) Math.round(pad * (double) to / from);
            int keep = (int) Math.round(real * (double) to / from);
            keep = Math.max(0, Math.min(keep, o[0].length - outPad));
            out.put(o, outPad, keep);
            // the last 2 pads become the next block's leading context
            for (float[] b : buf) System.arraycopy(b, b.length - 2 * pad, b, 0, 2 * pad);
            have = 2 * pad;
        }

        public void finish() throws IOException {
            if (from == to || have <= pad) return;
            // the last, short second: its samples after the leading context, zeros after them
            int real = have - pad;
            for (float[] b : buf) java.util.Arrays.fill(b, have, b.length, 0f);
            block(real);
        }
    }

    /** Mono (the mean of the channels) at FIT_RATE, for the fit. */
    public static final class ToFit {
        private final OffsetFit.Side side;
        private Resampler rs;
        private float[][] mono = new float[1][4096];

        public ToFit(OffsetFit.Side side) {
            this.side = side;
        }

        public void format(int rate) {
            rs = new Resampler(rate, OffsetFit.FIT_RATE, 1, (p, off, n) -> side.add(p[0], off, n));
        }

        public void add(float[][] planar, int frames) throws IOException {
            if (mono[0].length < frames) mono = new float[1][frames];
            int ch = planar.length;
            for (int f = 0; f < frames; f++) {
                float s = 0;
                for (int c = 0; c < ch; c++) s += planar[c][f];
                mono[0][f] = s / ch;
            }
            rs.add(mono, 0, frames);
        }

        public void finish() throws IOException {
            if (rs != null) rs.finish();
            side.finish();
        }
    }
}
