package app.ayham.extension.music.automix;

import android.content.Context;
import android.util.Log;

import com.music.bitchord.playback.smart.MelSpectrogram;
import com.music.bitchord.playback.smart.VocalSpectrogram;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Collections;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * The two models BitChord's analysis runs (the extension's too): Beat This! (beats and downbeats)
 * and open-unmix's vocal mask. They ship in the APK's assets (automix/) and are copied to the
 * app's files on first use, where ONNX Runtime opens them.
 */
final class Models {
    private static final String TAG = Automix.TAG;
    static final String BEAT = "beat_this_final0_q8.onnx";
    static final String VOCALS = "vocals_umxhq_int8.onnx";

    private static OrtSession beat, vocals;
    private static boolean nativeLoaded;

    private Models() {
    }

    static synchronized void loadNative() {
        if (nativeLoaded) return;
        System.loadLibrary("bitchord_analysis");
        System.loadLibrary("bitchord_loopmix");
        nativeLoaded = true;
    }

    /** The model file, copied out of the assets once. */
    private static File file(Context ctx, String name) throws Exception {
        File dir = new File(ctx.getFilesDir(), "automix/models");
        File f = new File(dir, name);
        if (f.length() > 0) return f;
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File tmp = new File(dir, name + ".part");
        try (InputStream in = ctx.getAssets().open("automix/" + name); OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        if (!tmp.renameTo(f)) throw new IllegalStateException("could not place " + f);
        Log.i(TAG, "model " + name + " copied from the APK (" + f.length() + " bytes)");
        return f;
    }

    private static OrtSession open(Context ctx, String name) throws Exception {
        return OrtEnvironment.getEnvironment().createSession(file(ctx, name).getPath(), new OrtSession.SessionOptions());
    }

    /** Beat This! over mono PCM (any rate); the grid in song seconds from [offsetSec], or null. */
    static synchronized Analysis.BeatSide beats(Context ctx, float[] pcm, int rate, double offsetSec) throws Exception {
        double melRate = MelSpectrogram.nativeSampleRate();
        int mels = MelSpectrogram.nativeMelCount();
        float[] spec = MelSpectrogram.nativeCompute(MelSpectrogram.nativeResample(pcm, rate, melRate), melRate);
        int frames = spec.length / mels;
        if (frames <= 0) return null;
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        if (beat == null) beat = open(ctx, BEAT);
        float[] beatLogits = new float[frames], downLogits = new float[frames];
        final int chunk = 1500, border = 6, stride = chunk - 2 * border;
        String input = beat.getInputNames().iterator().next();
        for (int start = 0; start < frames; start += stride) {
            int length = Math.min(chunk, frames - start);
            if (length <= 2 * border && start > 0) break;
            float[] values = new float[length * mels];
            System.arraycopy(spec, start * mels, values, 0, values.length);
            try (OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(values), new long[]{1, length, mels});
                 OrtSession.Result result = beat.run(Collections.singletonMap(input, tensor))) {
                float[] b = ((float[][]) result.get(0).getValue())[0];
                float[] d = ((float[][]) result.get(1).getValue())[0];
                int keepFrom = start == 0 ? 0 : border;
                int keepTo = start + length >= frames ? length : length - border;
                for (int i = keepFrom; i < keepTo && start + i < frames; i++) {
                    beatLogits[start + i] = b[i];
                    downLogits[start + i] = d[i];
                }
            }
            if (start + length >= frames) break;
        }
        return BeatGrid.trackBeats(beatLogits, downLogits, offsetSec);
    }

    /** The vocal mask (0-1 per STFT frame, 200 Hz-4 kHz) of a stereo window, or null. */
    static synchronized Analysis.VocalWindow vocals(Context ctx, float[] left, float[] right, int rate, double startSec) {
        try {
            double vr = VocalSpectrogram.nativeSampleRate();
            int bins = VocalSpectrogram.nativeBins(), hop = VocalSpectrogram.nativeHop(), fft = VocalSpectrogram.nativeFftSize();
            float[] l = rate == vr ? left : MelSpectrogram.nativeResample(left, rate, vr);
            float[] r = rate == vr ? right : MelSpectrogram.nativeResample(right, rate, vr);
            float[] spec = VocalSpectrogram.nativeCompute(l, r, vr);
            int frames = spec.length / (2 * bins);
            final int fixed = 960;
            if (frames <= 0 || frames > fixed) return null;
            OrtEnvironment env = OrtEnvironment.getEnvironment();
            if (vocals == null) vocals = open(ctx, VOCALS);
            ByteBuffer backing = ByteBuffer.allocateDirect(2 * bins * fixed * 4).order(ByteOrder.nativeOrder());
            FloatBuffer into = backing.asFloatBuffer();
            float[] pad = new float[fixed - frames];
            for (int c = 0; c < 2; c++) {
                for (int bin = 0; bin < bins; bin++) {
                    into.put(spec, (c * bins + bin) * frames, frames);
                    into.put(pad);
                }
            }
            FloatBuffer mix = backing.asFloatBuffer();
            try (OnnxTensor tensor = OnnxTensor.createTensor(env, backing.asFloatBuffer(), new long[]{1, 2, bins, fixed});
                 OrtSession.Result out = vocals.run(Collections.singletonMap(vocals.getInputNames().iterator().next(), tensor))) {
                FloatBuffer target = ((OnnxTensor) out.get(0)).getFloatBuffer();
                int lo = Math.max(0, (int) Math.floor(200.0 * fft / vr)), hi = Math.min(bins - 1, (int) Math.ceil(4000.0 * fft / vr));
                float[] curve = new float[frames];
                for (int f = 0; f < frames; f++) {
                    double sum = 0;
                    int count = 0;
                    for (int c = 0; c < 2; c++) {
                        for (int bin = lo; bin <= hi; bin++) {
                            int idx = (c * bins + bin) * fixed + f;
                            float m = mix.get(idx);
                            if (m <= 1e-6f) continue;
                            sum += Math.max(0f, Math.min(1f, target.get(idx) / m));
                            count++;
                        }
                    }
                    curve[f] = count > 0 ? (float) (sum / count) : 0f;
                }
                Analysis.VocalWindow w = new Analysis.VocalWindow();
                w.startSec = startSec;
                w.fps = vr / hop;
                w.curve = curve;
                return w;
            }
        } catch (Throwable t) {
            Log.w(TAG, "vocal mask failed", t);
            return null;
        }
    }
}
