package app.ayham.extension.music.automix;

/**
 * The song's key, found the way the extension (BitChord web) finds it: essentia's KeyExtractor
 * over the whole song, i.e. the key profile that best correlates with the song's pitch-class
 * profile, and that correlation as its strength (about 0.5-0.9 for most pop and EDM; the
 * key-clash rule needs 0.5 on both songs). BitChord's native analyser was off by a semitone on
 * "Levels" (C minor for C♯ minor) and gives only a best-to-second gap (0.00-0.1), so it is not
 * used for the key. Chroma over the whole song (log power weighted by the frame's loudness,
 * 55 Hz-5 kHz), Pearson correlation with the 24 Krumhansl profiles.
 */
final class KeyStrength {
    private static final double[] MAJOR = {6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88};
    private static final double[] MINOR = {6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17};
    /** The native analyser's spelling, which BeatGrid.camelot reads. */
    private static final String[] ROOTS = {"C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B"};
    private static final int N = 8192;

    private KeyStrength() {
    }

    static final class Key {
        final String name;
        final double strength;

        Key(String name, double strength) {
            this.name = name;
            this.strength = strength;
        }
    }

    /** The best of the 24 keys, or null when the audio gives no chroma. */
    static Key detect(short[] mono, int length, int rate) {
        if (mono == null || rate <= 0) return null;
        double[] c = chroma(mono, length, rate);
        if (c == null) return null;
        Key best = null;
        for (int root = 0; root < 12; root++) {
            for (int m = 0; m < 2; m++) {
                double[] prof = m == 0 ? MAJOR : MINOR, p = new double[12];
                for (int i = 0; i < 12; i++) p[i] = prof[(i + 12 - root) % 12];
                double r = pearson(c, p);
                if (!Double.isNaN(r) && (best == null || r > best.strength)) best = new Key(ROOTS[root] + (m == 0 ? " major" : " minor"), r);
            }
        }
        return best;
    }

    static double[] chroma(short[] mono, int length, int rate) {
        int[] pc = new int[N / 2];
        int lo = (int) Math.ceil(55.0 * N / rate), hi = Math.min(N / 2 - 1, (int) Math.floor(5000.0 * N / rate));
        for (int k = lo; k <= hi; k++) {
            double f = (double) k * rate / N;
            int midi = (int) Math.round(69 + 12 * Math.log(f / 440) / Math.log(2));
            pc[k] = ((midi % 12) + 12) % 12;
        }
        double[] win = new double[N];
        for (int i = 0; i < N; i++) win[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (N - 1));
        double[] re = new double[N], im = new double[N], chroma = new double[12];
        int frames = 0;
        for (int start = 0; start + N <= length; start += N) {
            double sq = 0;
            for (int i = 0; i < N; i++) {
                double v = mono[start + i] / 32768.0;
                sq += v * v;
                re[i] = v * win[i];
                im[i] = 0;
            }
            double rms = Math.sqrt(sq / N);
            if (rms < 1e-3) continue;
            fft(re, im);
            for (int k = lo; k <= hi; k++) chroma[pc[k]] += Math.log1p(re[k] * re[k] + im[k] * im[k]) * rms;
            frames++;
        }
        return frames < 8 ? null : chroma;
    }

    private static double pearson(double[] a, double[] b) {
        double ma = 0, mb = 0;
        for (int i = 0; i < 12; i++) {
            ma += a[i] / 12;
            mb += b[i] / 12;
        }
        double sab = 0, saa = 0, sbb = 0;
        for (int i = 0; i < 12; i++) {
            sab += (a[i] - ma) * (b[i] - mb);
            saa += (a[i] - ma) * (a[i] - ma);
            sbb += (b[i] - mb) * (b[i] - mb);
        }
        return saa <= 0 || sbb <= 0 ? Double.NaN : sab / Math.sqrt(saa * sbb);
    }

    /** In-place radix-2 FFT (length a power of two). */
    private static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len, wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = a + len / 2;
                    double xr = re[b] * cr - im[b] * ci, xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr;
                    im[b] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                    double t = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = t;
                }
            }
        }
    }
}
