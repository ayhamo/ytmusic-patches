package app.ayham.extension.music.automix;

/**
 * A stereo biquad with the Web Audio API's BiquadFilterNode formulas (the extension's filters):
 * lowshelf / highshelf (shelf slope 1, gain in dB), lowpass / highpass (Q in dB). Direct form I in
 * doubles, so a 20 Hz corner at 48 kHz stays stable.
 */
final class Biquad {
    static final int LOWSHELF = 0, HIGHSHELF = 1, LOWPASS = 2, HIGHPASS = 3;
    /** The extension's high/low-pass Q (Web Audio: dB). */
    static final double Q_DB = 0.7;

    private final int type;
    private final double sr, freq;
    private double b0 = 1, b1, b2, a1, a2;
    private double x1l, x2l, y1l, y2l, x1r, x2r, y1r, y2r;
    private double value = Double.NaN;

    /** [freq]: fixed for the shelves (their value is the gain), unused for the passes (their value is the corner). */
    Biquad(int type, double sr, double freq) {
        this.type = type;
        this.sr = sr;
        this.freq = freq;
    }

    void setValue(double v) {
        if (v == value) return;
        value = v;
        double f = type == LOWSHELF || type == HIGHSHELF ? freq : Math.max(10, Math.min(sr / 2 * 0.999, v));
        double w0 = 2 * Math.PI * f / sr, cos = Math.cos(w0), sin = Math.sin(w0);
        double nb0, nb1, nb2, na0, na1, na2;
        if (type == LOWSHELF || type == HIGHSHELF) {
            double A = Math.pow(10, v / 40), alpha = sin / 2 * Math.sqrt(2), sq = 2 * Math.sqrt(A) * alpha;
            if (type == LOWSHELF) {
                nb0 = A * ((A + 1) - (A - 1) * cos + sq);
                nb1 = 2 * A * ((A - 1) - (A + 1) * cos);
                nb2 = A * ((A + 1) - (A - 1) * cos - sq);
                na0 = (A + 1) + (A - 1) * cos + sq;
                na1 = -2 * ((A - 1) + (A + 1) * cos);
                na2 = (A + 1) + (A - 1) * cos - sq;
            } else {
                nb0 = A * ((A + 1) + (A - 1) * cos + sq);
                nb1 = -2 * A * ((A - 1) + (A + 1) * cos);
                nb2 = A * ((A + 1) + (A - 1) * cos - sq);
                na0 = (A + 1) - (A - 1) * cos + sq;
                na1 = 2 * ((A - 1) - (A + 1) * cos);
                na2 = (A + 1) - (A - 1) * cos - sq;
            }
        } else {
            double alpha = sin / (2 * Math.pow(10, Q_DB / 20));
            if (type == LOWPASS) {
                nb0 = (1 - cos) / 2;
                nb1 = 1 - cos;
                nb2 = (1 - cos) / 2;
            } else {
                nb0 = (1 + cos) / 2;
                nb1 = -(1 + cos);
                nb2 = (1 + cos) / 2;
            }
            na0 = 1 + alpha;
            na1 = -2 * cos;
            na2 = 1 - alpha;
        }
        b0 = nb0 / na0;
        b1 = nb1 / na0;
        b2 = nb2 / na0;
        a1 = na1 / na0;
        a2 = na2 / na0;
    }

    /** Filters interleaved stereo [buf] frames [from, to) in place. */
    void process(float[] buf, int from, int to) {
        for (int i = from; i < to; i++) {
            double xl = buf[2 * i], xr = buf[2 * i + 1];
            double yl = b0 * xl + b1 * x1l + b2 * x2l - a1 * y1l - a2 * y2l;
            double yr = b0 * xr + b1 * x1r + b2 * x2r - a1 * y1r - a2 * y2r;
            x2l = x1l;
            x1l = xl;
            y2l = y1l;
            y1l = yl;
            x2r = x1r;
            x1r = xr;
            y2r = y1r;
            y1r = yr;
            buf[2 * i] = (float) yl;
            buf[2 * i + 1] = (float) yr;
        }
    }
}
