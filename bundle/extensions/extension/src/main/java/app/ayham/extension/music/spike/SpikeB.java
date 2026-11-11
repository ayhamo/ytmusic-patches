package app.ayham.extension.music.spike;

import android.content.ComponentName;
import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.music.bitchord.playback.smart.MelSpectrogram;
import com.music.bitchord.playback.smart.TrackFeatures;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * Throwaway spike, batch B (PLAN.md items 4 and 5). Runs once per process when spike.properties
 * has batchB=1:
 * <ol>
 * <li>next track id from YT's media session queue (through its MediaBrowserService)</li>
 * <li>download it with Morphe's stream resolver and range requests</li>
 * <li>decode to mono float PCM with MediaCodec</li>
 * <li>BitChord's native analyser on it</li>
 * <li>both ONNX models load; Beat This! runs on one chunk of the track</li>
 * </ol>
 */
final class SpikeB {
    private static final String TAG = "AyhamSpikeB";
    private static final String BROWSER_SERVICE =
            "com.google.android.apps.youtube.music.mediabrowser.MusicBrowserService";
    private static final String MORPHE_DOWNLOADS =
            "app.morphe.extension.music.patches.downloads.LocalDownloadManager";
    private static final long RANGE = 1 << 20;

    private static boolean started;
    private static MediaBrowser browser;

    private SpikeB() {
    }

    static void start(Context ctx, String forcedVideoId) {
        if (started) return;
        started = true;
        Log.i(TAG, "batch B start");
        new Thread(() -> loadNative(ctx), "SpikeB-native").start();
        if (forcedVideoId != null && !forcedVideoId.isEmpty()) {
            new Thread(() -> analyseTrack(ctx, forcedVideoId), "SpikeB-track").start();
        } else {
            // Give YT time to set up its session and queue.
            new Handler(Looper.getMainLooper()).postDelayed(() -> findNextTrack(ctx), 8000);
        }
    }

    // ---- Item 4: native libraries + models ----

    private static volatile boolean nativeOk;

    private static void loadNative(Context ctx) {
        long t0 = System.nanoTime();
        try {
            System.loadLibrary("bitchord_analysis");
            nativeOk = true;
            Log.i(TAG, "libbitchord_analysis loaded from APK in " + ms(t0) + " ms; analysis rate="
                    + TrackFeatures.nativeSampleRate() + " mel rate=" + MelSpectrogram.nativeSampleRate()
                    + " mels=" + MelSpectrogram.nativeMelCount() + " hop=" + MelSpectrogram.nativeHop());
        } catch (Throwable t) {
            Log.e(TAG, "libbitchord_analysis load failed", t);
        }
        try {
            long t1 = System.nanoTime();
            OrtEnvironment env = OrtEnvironment.getEnvironment();
            Log.i(TAG, "ONNX Runtime ready in " + ms(t1) + " ms, version=" + env.getVersion());
            File models = new File(ctx.getExternalFilesDir(null), "spike/models");
            for (String name : new String[]{"beat_this_final0_q8.onnx", "vocals_umxhq_int8.onnx"}) {
                File f = new File(models, name);
                if (!f.exists()) {
                    Log.w(TAG, "model missing: " + f);
                    continue;
                }
                long t2 = System.nanoTime();
                try (OrtSession session = env.createSession(f.getPath(), new OrtSession.SessionOptions())) {
                    StringBuilder sb = new StringBuilder();
                    for (Map.Entry<String, NodeInfo> e : session.getInputInfo().entrySet()) {
                        sb.append(" in ").append(e.getKey()).append('=').append(e.getValue().getInfo());
                    }
                    for (Map.Entry<String, NodeInfo> e : session.getOutputInfo().entrySet()) {
                        sb.append(" out ").append(e.getKey()).append('=').append(e.getValue().getInfo());
                    }
                    Log.i(TAG, "model " + name + " loaded in " + ms(t2) + " ms:" + sb);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "ONNX Runtime failed", t);
        }
    }

    // ---- Item 5a: next track from the media session queue ----

    /**
     * YT's own queue, through the accessor Morphe's audio/video switch patch adds to it.
     * The media session queue has titles only (no video ids), so it cannot be used.
     */
    private static int queueTries;

    private static final String MORPHE_JAM_BRIDGE =
            "app.morphe.extension.music.patches.jam.YouTubeMusicBridge";

    private static void findNextTrack(Context ctx) {
        try {
            // The audio/video switch capture is not in this build; the Jam bridge's is.
            Class<?> bridge = Class.forName(MORPHE_JAM_BRIDGE);
            java.lang.reflect.Field f = bridge.getDeclaredField("queue");
            f.setAccessible(true);
            Object access = ((java.lang.ref.WeakReference<?>) f.get(null)).get();
            if (access == null) {
                if (++queueTries <= 24) {
                    if (queueTries == 1) Log.w(TAG, "Morphe queue accessor not captured yet, retrying");
                    new Handler(Looper.getMainLooper()).postDelayed(() -> findNextTrack(ctx), 5000);
                } else {
                    Log.e(TAG, "Morphe queue accessor never captured");
                }
                return;
            }
            Log.i(TAG, "Morphe Jam queue accessor captured after " + queueTries + " retries: "
                    + access.getClass().getName());
            Class<?> type = Class.forName(MORPHE_JAM_BRIDGE + "$QueueAccess");
            java.util.concurrent.Executor exec =
                    (java.util.concurrent.Executor) type.getMethod("patch_jamExecutor").invoke(access);
            // Morphe reads YT's queue on YT's own queue executor; do the same.
            exec.execute(() -> {
                try {
                    int current = (int) type.getMethod("patch_jamCurrent").invoke(access);
                    Object[] items = (Object[]) type.getMethod("patch_jamItems").invoke(access);
                    Method videoId = type.getMethod("patch_jamVideoId", Object.class);
                    Log.i(TAG, "YT queue via Morphe Jam bridge: " + (items == null ? null : items.length)
                            + " items, current index " + current);
                    String next = null;
                    for (int i = 0; items != null && i < items.length && i < current + 4; i++) {
                        String id = (String) videoId.invoke(access, items[i]);
                        if (i >= current - 1) Log.i(TAG, "queue[" + i + "] = " + id + (i == current ? " (current)" : ""));
                        if (i == current + 1) next = id;
                    }
                    if (next == null) {
                        Object[] auto = (Object[]) type.getMethod("patch_jamAutoplayItems").invoke(access);
                        Log.i(TAG, "autoplay lane: " + (auto == null ? null : auto.length) + " items");
                        if (auto != null && auto.length > 0) next = (String) videoId.invoke(access, auto[0]);
                    }
                    Log.i(TAG, "next track id=" + next);
                    String nextId = next;
                    if (nextId != null) new Thread(() -> analyseTrack(ctx, nextId), "SpikeB-track").start();
                } catch (Throwable t) {
                    Log.e(TAG, "Morphe queue read failed", t);
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Morphe queue read failed", t);
        }
    }

    private static void findNextTrackViaSession(Context ctx) {
        try {
            ComponentName component = new ComponentName(ctx.getPackageName(), BROWSER_SERVICE);
            browser = new MediaBrowser(ctx, component, new MediaBrowser.ConnectionCallback() {
                @Override
                public void onConnected() {
                    try {
                        MediaController controller = new MediaController(ctx, browser.getSessionToken());
                        List<MediaSession.QueueItem> queue = controller.getQueue();
                        PlaybackState state = controller.getPlaybackState();
                        long active = state == null ? -1 : state.getActiveQueueItemId();
                        Log.i(TAG, "session connected: queue=" + (queue == null ? null : queue.size())
                                + " activeId=" + active + " title="
                                + (controller.getMetadata() == null ? null
                                : controller.getMetadata().getDescription().getTitle()));
                        if (queue == null) return;
                        String next = null;
                        for (int i = 0; i < queue.size(); i++) {
                            MediaSession.QueueItem item = queue.get(i);
                            if (i < 4) {
                                Log.i(TAG, "queue[" + i + "] id=" + item.getQueueId() + " mediaId="
                                        + item.getDescription().getMediaId() + " title="
                                        + item.getDescription().getTitle());
                            }
                            if (item.getQueueId() == active && i + 1 < queue.size()) {
                                next = queue.get(i + 1).getDescription().getMediaId();
                            }
                        }
                        Log.i(TAG, "session: next track mediaId=" + next);
                    } catch (Throwable t) {
                        Log.e(TAG, "queue read failed", t);
                    } finally {
                        browser.disconnect();
                    }
                }

                @Override
                public void onConnectionFailed() {
                    Log.e(TAG, "MediaBrowser connection failed");
                }
            }, null);
            browser.connect();
        } catch (Throwable t) {
            Log.e(TAG, "findNextTrack failed", t);
        }
    }

    // ---- Item 5b-d: download, decode, analyse ----

    private static void analyseTrack(Context ctx, String videoId) {
        try {
            long t0 = System.nanoTime();
            File audio = download(ctx, videoId);
            if (audio == null) return;
            Log.i(TAG, "downloaded " + videoId + ": " + audio.length() + " bytes in " + ms(t0) + " ms");

            long t1 = System.nanoTime();
            int[] rate = new int[1];
            float[] pcm = decodeMono(audio, rate);
            double duration = pcm.length / (double) rate[0];
            Log.i(TAG, "decoded " + pcm.length + " mono samples @" + rate[0] + " Hz ("
                    + String.format("%.1f", duration) + " s) in " + ms(t1) + " ms");

            for (int i = 0; i < 40 && !nativeOk; i++) Thread.sleep(250);
            if (!nativeOk) {
                Log.e(TAG, "native library not loaded, analysis skipped");
                return;
            }

            long t2 = System.nanoTime();
            double analysisRate = TrackFeatures.nativeSampleRate();
            float[] forAnalysis = TrackFeatures.nativeResample(pcm, rate[0], analysisRate);
            String json = TrackFeatures.nativeAnalyze(forAnalysis, analysisRate, duration);
            Log.i(TAG, "native analysis in " + ms(t2) + " ms: " + summary(json));

            runBeatModel(ctx, pcm, rate[0]);
            audio.delete();
        } catch (Throwable t) {
            Log.e(TAG, "analyseTrack failed", t);
        }
    }

    static File download(Context ctx, String videoId) throws Exception {
        Class<?> manager = Class.forName(MORPHE_DOWNLOADS);
        Method resolve = manager.getDeclaredMethod("resolveStream", String.class);
        resolve.setAccessible(true);
        Object resolved = resolve.invoke(null, videoId);
        if (resolved == null) {
            Log.e(TAG, "Morphe resolveStream returned null for " + videoId);
            return null;
        }
        Object format = resolved.getClass().getMethod("format").invoke(resolved);
        String url = (String) format.getClass().getMethod("getUrl").invoke(format);
        String mime = (String) format.getClass().getMethod("getMimeType").invoke(format);
        long length = (long) format.getClass().getMethod("getContentLength").invoke(format);
        int itag = (int) format.getClass().getMethod("getItag").invoke(format);
        Log.i(TAG, "Morphe resolved " + videoId + ": itag=" + itag + " " + mime + " length=" + length);

        Method open = manager.getDeclaredMethod("openAudioConnection", String.class, long.class, long.class);
        open.setAccessible(true);

        File dir = new File(ctx.getCacheDir(), "spike");
        dir.mkdirs();
        File out = new File(dir, videoId + (mime.contains("mp4") ? ".m4a" : ".webm"));
        byte[] buffer = new byte[64 * 1024];
        try (FileOutputStream os = new FileOutputStream(out)) {
            for (long start = 0; start < length; start += RANGE) {
                long end = Math.min(length, start + RANGE) - 1;
                HttpURLConnection c = (HttpURLConnection) open.invoke(null, url, start, end);
                int code = c.getResponseCode();
                if (code != HttpURLConnection.HTTP_PARTIAL) {
                    Log.e(TAG, "range " + start + "-" + end + " returned HTTP " + code);
                    c.disconnect();
                    return null;
                }
                try (InputStream in = c.getInputStream()) {
                    int n;
                    while ((n = in.read(buffer)) > 0) os.write(buffer, 0, n);
                } finally {
                    c.disconnect();
                }
            }
        }
        return out;
    }

    static float[] decodeMono(File file, int[] rateOut) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(file.getPath());
        int track = -1;
        MediaFormat format = null;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat f = extractor.getTrackFormat(i);
            if (f.getString(MediaFormat.KEY_MIME).startsWith("audio/")) {
                track = i;
                format = f;
                break;
            }
        }
        if (track < 0) throw new IllegalStateException("no audio track");
        extractor.selectTrack(track);
        String mime = format.getString(MediaFormat.KEY_MIME);
        Log.i(TAG, "decoding " + mime + " " + format);

        MediaCodec codec = MediaCodec.createDecoderByType(mime);
        codec.configure(format, null, null, 0);
        codec.start();

        int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        int rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        boolean floatPcm = false;
        float[] out = new float[1 << 20];
        int size = 0;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inputDone = false;
        while (true) {
            if (!inputDone) {
                int in = codec.dequeueInputBuffer(10_000);
                if (in >= 0) {
                    ByteBuffer buf = codec.getInputBuffer(in);
                    int n = extractor.readSampleData(buf, 0);
                    if (n < 0) {
                        codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        codec.queueInputBuffer(in, 0, n, extractor.getSampleTime(), 0);
                        extractor.advance();
                    }
                }
            }
            int outIndex = codec.dequeueOutputBuffer(info, 10_000);
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat of = codec.getOutputFormat();
                channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                floatPcm = of.containsKey(MediaFormat.KEY_PCM_ENCODING)
                        && of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT;
            } else if (outIndex >= 0) {
                ByteBuffer buf = codec.getOutputBuffer(outIndex).order(ByteOrder.nativeOrder());
                buf.position(info.offset).limit(info.offset + info.size);
                int frames = info.size / (channels * (floatPcm ? 4 : 2));
                if (size + frames > out.length) {
                    float[] bigger = new float[Math.max(out.length * 2, size + frames)];
                    System.arraycopy(out, 0, bigger, 0, size);
                    out = bigger;
                }
                for (int f = 0; f < frames; f++) {
                    float sum = 0;
                    for (int ch = 0; ch < channels; ch++) {
                        sum += floatPcm ? buf.getFloat() : buf.getShort() / 32768f;
                    }
                    out[size++] = sum / channels;
                }
                codec.releaseOutputBuffer(outIndex, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            }
        }
        codec.stop();
        codec.release();
        extractor.release();
        rateOut[0] = rate;
        float[] result = new float[size];
        System.arraycopy(out, 0, result, 0, size);
        return result;
    }

    /** A decoded stereo stretch of a song. */
    static final class Region {
        float[] left, right;
        int rate;
        /** Track seconds of sample 0, and the whole song's length. */
        double start, duration;

        double end() {
            return start + left.length / (double) rate;
        }
    }

    /**
     * Decodes track seconds [from, to) as planar stereo at the codec's rate. A negative [from]
     * counts from the song's end (-70 = its last 70 s); only the region is kept in memory.
     */
    static Region decodeRegion(File file, double from, double to) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(file.getPath());
        int track = -1;
        MediaFormat format = null;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat f = extractor.getTrackFormat(i);
            if (f.getString(MediaFormat.KEY_MIME).startsWith("audio/")) {
                track = i;
                format = f;
                break;
            }
        }
        if (track < 0) throw new IllegalStateException("no audio track");
        extractor.selectTrack(track);
        double duration = format.containsKey(MediaFormat.KEY_DURATION) ? format.getLong(MediaFormat.KEY_DURATION) / 1e6 : -1;
        if (from < 0) {
            if (duration <= 0) throw new IllegalStateException("song length unknown");
            from = Math.max(0, duration + from);
            to = duration + 1;
        }
        MediaCodec codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
        codec.configure(format, null, null, 0);
        codec.start();
        int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        int rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        boolean floatPcm = false;
        long first = Math.round(from * rate), last = Math.round(to * rate);
        float[] l = new float[1 << 18], r = new float[1 << 18];
        int size = 0;
        long frame = 0;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inputDone = false;
        while (true) {
            if (!inputDone) {
                int in = codec.dequeueInputBuffer(10_000);
                if (in >= 0) {
                    ByteBuffer buf = codec.getInputBuffer(in);
                    int n = extractor.readSampleData(buf, 0);
                    if (n < 0) {
                        codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        codec.queueInputBuffer(in, 0, n, extractor.getSampleTime(), 0);
                        extractor.advance();
                    }
                }
            }
            int outIndex = codec.dequeueOutputBuffer(info, 10_000);
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat of = codec.getOutputFormat();
                channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                first = Math.round(from * rate);
                last = Math.round(to * rate);
                floatPcm = of.containsKey(MediaFormat.KEY_PCM_ENCODING)
                        && of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT;
            } else if (outIndex >= 0) {
                ByteBuffer buf = codec.getOutputBuffer(outIndex).order(ByteOrder.nativeOrder());
                buf.position(info.offset).limit(info.offset + info.size);
                int frames = info.size / (channels * (floatPcm ? 4 : 2));
                for (int f = 0; f < frames; f++, frame++) {
                    float a = floatPcm ? buf.getFloat() : buf.getShort() / 32768f;
                    float b = channels > 1 ? (floatPcm ? buf.getFloat() : buf.getShort() / 32768f) : a;
                    for (int ch = 2; ch < channels; ch++) {
                        if (floatPcm) buf.getFloat(); else buf.getShort();
                    }
                    if (frame < first || frame >= last) continue;
                    if (size == l.length) {
                        l = java.util.Arrays.copyOf(l, size * 2);
                        r = java.util.Arrays.copyOf(r, size * 2);
                    }
                    l[size] = a;
                    r[size++] = b;
                }
                codec.releaseOutputBuffer(outIndex, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 || frame >= last) break;
            }
        }
        codec.stop();
        codec.release();
        extractor.release();
        Region region = new Region();
        region.left = java.util.Arrays.copyOf(l, size);
        region.right = java.util.Arrays.copyOf(r, size);
        region.rate = rate;
        region.start = from;
        region.duration = duration > 0 ? duration : frame / (double) rate;
        return region;
    }

    private static void runBeatModel(Context ctx, float[] pcm, int rate) {
        try {
            File model = new File(ctx.getExternalFilesDir(null), "spike/models/beat_this_final0_q8.onnx");
            long t0 = System.nanoTime();
            double melRate = MelSpectrogram.nativeSampleRate();
            int mels = MelSpectrogram.nativeMelCount();
            float[] resampled = MelSpectrogram.nativeResample(pcm, rate, melRate);
            float[] spectrogram = MelSpectrogram.nativeCompute(resampled, melRate);
            int frames = spectrogram.length / mels;
            Log.i(TAG, "mel spectrogram " + frames + "x" + mels + " in " + ms(t0) + " ms");

            int length = Math.min(1500, frames);
            float[] chunk = new float[length * mels];
            System.arraycopy(spectrogram, 0, chunk, 0, chunk.length);
            OrtEnvironment env = OrtEnvironment.getEnvironment();
            long t1 = System.nanoTime();
            try (OrtSession session = env.createSession(model.getPath(), new OrtSession.SessionOptions());
                 OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(chunk),
                         new long[]{1, length, mels})) {
                String input = session.getInputNames().iterator().next();
                long t2 = System.nanoTime();
                try (OrtSession.Result result = session.run(Collections.singletonMap(input, tensor))) {
                    StringBuilder sb = new StringBuilder();
                    for (Map.Entry<String, ai.onnxruntime.OnnxValue> e : result) {
                        float[][] logits = ((float[][]) e.getValue().getValue());
                        int positive = 0;
                        for (float v : logits[0]) if (v > 0) positive++;
                        sb.append(' ').append(e.getKey()).append(": ").append(logits[0].length)
                                .append(" frames, ").append(positive).append(" above 0");
                    }
                    Log.i(TAG, "Beat This! on first " + length + " frames (" + (length / 50) + " s): load "
                            + (t2 - t1) / 1_000_000 + " ms, run " + ms(t2) + " ms;" + sb);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "beat model failed", t);
        }
    }

    private static String summary(String json) {
        StringBuilder sb = new StringBuilder();
        for (String key : new String[]{"duration", "bpm", "beatConfidence", "key", "keyConfidence",
                "mixInTime", "mixOutTime", "introEndTime", "outroStartTime"}) {
            int i = json.indexOf("\"" + key + "\":");
            if (i < 0) continue;
            int start = i + key.length() + 3;
            int end = start;
            while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
            sb.append(key).append('=').append(json, start, end).append(' ');
        }
        return sb + "(" + json.length() + " chars)";
    }

    private static long ms(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}
