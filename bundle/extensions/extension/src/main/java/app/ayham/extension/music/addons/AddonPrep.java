package app.ayham.extension.music.addons;

import android.content.Context;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Gets a song's addon file ready before it is needed (protocol.md §8, the phone's Direct mode):
 * YouTube's own copy decoded, the addons asked in the user's order (find, open, probe), the
 * picked file fetched whole, decoded by the same decoder that plays it, fitted to YouTube's
 * audio and levelled (offset-map.md). A row that doesn't fit is refused and the next row, then
 * the next addon, is tried. The result: the local file, its offset and its gain; also its
 * opening and end as deck PCM on YouTube's timeline, for automix.
 *
 * <p>One worker, the playing song first, then the next one.
 */
public final class AddonPrep {
    static final String TAG = "AyhamAddons";
    /** Files kept: the playing song, the next one, and two before. */
    private static final int KEEP = 4;
    /** A failed song is not tried again for this long. */
    private static final long RETRY_MS = 10 * 60_000;

    /** A song ready to play from an addon. */
    public static final class Prepared {
        public String id, addon, host, rowId;
        public File file;
        /** addonTime = youtubeTime + offsetSec. */
        public double offsetSec, gainDb, ytDurationSec, addonDurationSec, score;
        public OffsetFit.Gain gain;
        public int rate, channels;
        public String codec;

        public float gainLinear() {
            return (float) Math.pow(10, gainDb / 20);
        }

        @Override
        public String toString() {
            return String.format(Locale.US, "%s via %s: offset %+.4f s, gain %+.2f dB%s, %s %d Hz %d ch, score %.3f", id, addon, offsetSec, gainDb,
                    gain != null && gain.capped != null ? " (" + gain.capped + ")" : "", codec, rate, channels, score);
        }
    }

    private static final Map<String, Prepared> ready = new LinkedHashMap<>();
    private static final Map<String, Long> failedAt = new HashMap<>();
    private static final Map<String, String[]> asked = new HashMap<>();
    private static final Deque<String> queue = new ArrayDeque<>();
    private static volatile String working;
    private static Thread worker;
    private static Context ctx;
    private static AddonClient client;

    private AddonPrep() {
    }

    /** Set while automix mixes: the decodes wait, so the mix's audio thread has the CPU. */
    public static volatile boolean held;

    /** Called between decoded blocks. */
    public static void waitWhileHeld() {
        while (held) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    public static synchronized Prepared get(String id) {
        return id == null ? null : ready.get(id);
    }

    /** Being prepared now (the badge's "Upgrading Quality" for the playing song). */
    public static boolean busy(String id) {
        return id != null && id.equals(working);
    }

    public static synchronized boolean failed(String id) {
        Long at = failedAt.get(id);
        return at != null && SystemClock.elapsedRealtime() - at < RETRY_MS;
    }

    /** Playback gave up on it (the file stalled or errored): not played from the addon again soon. */
    public static synchronized void dropped(String id, String why) {
        Prepared p = ready.remove(id);
        failedAt.put(id, SystemClock.elapsedRealtime());
        Log.w(TAG, "dropped " + id + ": " + why);
        if (p != null) deleteFiles(p);
    }

    /**
     * Asks for a song. now: the playing song (goes first). Title and artist from YT's queue;
     * isVideo when it is a music video (looser length match).
     */
    public static synchronized void request(Context c, String id, String title, String artist, boolean isVideo, boolean now) {
        if (c == null || id == null || title == null || artist == null || !AddonSettings.on()) return;
        ctx = c.getApplicationContext();
        if (ready.containsKey(id) || id.equals(working) || failed(id)) return;
        asked.put(id, new String[]{title, artist, isVideo ? "1" : "0"});
        if (queue.contains(id)) {
            if (now) {
                queue.remove(id);
                queue.addFirst(id);
            }
        } else if (now) {
            queue.addFirst(id);
        } else {
            queue.addLast(id);
        }
        while (queue.size() > 3) queue.pollLast();
        if (worker == null) {
            worker = new Thread(AddonPrep::work, "addon-prep");
            worker.start();
        }
        AddonPrep.class.notifyAll();
    }

    private static synchronized AddonClient client() {
        if (client == null) client = AddonClient.create(line -> Log.i(TAG, Protocol.scrub(line)));
        return client;
    }

    private static void work() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        while (true) {
            String id;
            String[] info;
            synchronized (AddonPrep.class) {
                while (queue.isEmpty()) {
                    try {
                        AddonPrep.class.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                id = queue.pollFirst();
                info = asked.get(id);
                if (info == null || ready.containsKey(id) || !AddonSettings.on()) continue;
                working = id;
            }
            long t0 = SystemClock.elapsedRealtime();
            Prepared p = null;
            String why;
            try {
                p = prepare(ctx, id, info[0], info[1], "1".equals(info[2]));
                why = p == null ? "no addon has a file that fits" : null;
            } catch (Throwable t) {
                why = Protocol.scrub(String.valueOf(t));
                Log.w(TAG, "prepare " + id + " failed: " + why);
            }
            synchronized (AddonPrep.class) {
                working = null;
                if (p != null) {
                    ready.put(id, p);
                    while (ready.size() > KEEP) {
                        String eldest = ready.keySet().iterator().next();
                        deleteFiles(ready.remove(eldest));
                    }
                } else {
                    failedAt.put(id, SystemClock.elapsedRealtime());
                }
            }
            Log.i(TAG, (p != null ? "ready: " + p : "not from an addon: " + id + " (" + why + ")") + " in " + (SystemClock.elapsedRealtime() - t0) + " ms");
        }
    }

    private static void deleteFiles(Prepared p) {
        if (p == null) return;
        //noinspection ResultOfMethodCallIgnored
        p.file.delete();
        app.ayham.extension.music.automix.AddonPcm.delete(ctx, p.id);
    }

    /** At start: files prepared by an earlier run are no longer known (kept only in memory), so they go. */
    public static void clearCache(Context c) {
        new Thread(() -> {
            int n = 0;
            for (File d : new File[]{dir(c), app.ayham.extension.music.automix.AddonPcm.tempDir(c)}) {
                File[] fs = d.listFiles();
                if (fs != null) for (File f : fs) if (f.isFile() && f.delete()) n++;
            }
            File[] pcm = new File(c.getCacheDir(), "automix/pcm").listFiles();
            if (pcm != null) for (File f : pcm) {
                String name = f.getName();
                if ((name.endsWith(".ahead") || name.endsWith(".atail")) && f.delete()) n++;
            }
            if (n > 0) Log.i(TAG, "cleared " + n + " addon files from an earlier run");
        }, "addon-clear").start();
    }

    static File dir(Context c) {
        File d = new File(c.getCacheDir(), "addons");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** The song from the addons, fitted and levelled; null when none fits. */
    static Prepared prepare(Context c, String id, String title, String artist, boolean isVideo) throws Exception {
        // YouTube's side first: everything is fitted to it
        long t0 = SystemClock.elapsedRealtime();
        File yt = app.ayham.extension.music.automix.AddonPcm.youtubeFile(c, id);
        final OffsetFit.Side ytSide = new OffsetFit.Side();
        final AudioDecode.ToFit ytFit = new AudioDecode.ToFit(ytSide);
        try {
            AudioDecode.decode(yt, new AudioDecode.Sink() {
                @Override
                public void format(int rate, int channels) {
                    ytFit.format(rate);
                }

                @Override
                public void block(float[][] planar, int frames) throws IOException {
                    AddonPrep.waitWhileHeld();
                    ytFit.add(planar, frames);
                }
            });
            ytFit.finish();
        } finally {
            // fetched only for this fit (the stream deck keeps its own copies)
            if (yt.getParentFile() != null && yt.getParentFile().equals(app.ayham.extension.music.automix.AddonPcm.tempDir(c))) //noinspection ResultOfMethodCallIgnored
                yt.delete();
        }
        final double ytDur = ytSide.durationSec(), ytLufs = ytSide.lufs();
        Log.i(TAG, String.format(Locale.US, "%s: YouTube's copy decoded (%.1f s, %.2f LUFS) in %d ms", id, ytDur, ytLufs, SystemClock.elapsedRealtime() - t0));

        TrackMatcher.Target t = new TrackMatcher.Target();
        t.title = title;
        t.artist = artist;
        t.durationSec = ytDur;
        t.isVideo = isVideo;
        List<AddonClient.Addon> addons = new ArrayList<>();
        for (String l : AddonSettings.links()) addons.add(new AddonClient.Addon(l));
        AddonClient.Options opt = new AddonClient.Options();
        opt.slackSec = AddonSettings.slackSec();
        final Prepared[] got = new Prepared[1];
        opt.check = o -> {
            try {
                Prepared p = fetchAndFit(c, id, o, ytSide, ytDur, ytLufs, opt.slackSec);
                if (p.score < 0) {
                    //noinspection ResultOfMethodCallIgnored
                    p.file.delete();
                    return p.codec;   // the refusal, carried in codec
                }
                got[0] = p;
                return null;
            } catch (Throwable e) {
                return "fetch or decode failed: " + Protocol.scrub(String.valueOf(e));
            }
        };
        AddonClient.Opened o = client().resolve(t, addons, opt);
        if (o.stream == null || got[0] == null) {
            if (o.tried != null) for (AddonClient.Tried x : o.tried) Log.i(TAG, "  passed over: " + x.addon + " " + x.row + ": " + x.why);
            return null;
        }
        Prepared p = got[0];
        app.ayham.extension.music.automix.AddonPcm.write(c, p);
        return p;
    }

    private static Prepared fetchAndFit(Context c, String id, AddonClient.Opened o, OffsetFit.Side ytSide, double ytDur, double ytLufs, double slack) throws Exception {
        Protocol.Stream s = o.stream;
        String ext = s.codec == null ? "audio" : s.codec.toLowerCase(Locale.ROOT).contains("flac") ? "flac"
                : s.codec.toLowerCase(Locale.ROOT).contains("alac") || s.codec.toLowerCase(Locale.ROOT).contains("aac") ? "m4a"
                : s.codec.toLowerCase(Locale.ROOT).contains("mp3") ? "mp3" : "audio";
        File f = new File(dir(c), id + "." + ext), part = new File(dir(c), id + "." + ext + ".part");
        long t0 = SystemClock.elapsedRealtime();
        HttpURLConnection h = HttpTransport.open(Protocol.audio(s.url, s.headers, null));
        h.setReadTimeout(30_000);
        boolean done = false;
        try (InputStream in = h.getInputStream(); OutputStream out = new FileOutputStream(part)) {
            int code = h.getResponseCode();
            if (code != 200 && code != 206) throw new IOException("audio: HTTP " + code);
            byte[] b = new byte[1 << 16];
            long total = 0;
            for (int n; (n = in.read(b)) > 0; ) {
                out.write(b, 0, n);
                total += n;
                if (total > 400L << 20) throw new IOException("over 400 MB");
            }
            done = true;
        } finally {
            h.disconnect();
            if (!done) //noinspection ResultOfMethodCallIgnored
                part.delete();
        }
        if (!part.renameTo(f)) throw new IOException("could not keep the file");
        Log.i(TAG, String.format(Locale.US, "%s: %s's file fetched (%d KB) in %d ms", id, o.addon.name, f.length() / 1024, SystemClock.elapsedRealtime() - t0));

        long t1 = SystemClock.elapsedRealtime();
        final OffsetFit.Side side = new OffsetFit.Side();
        final AudioDecode.ToFit fit = new AudioDecode.ToFit(side);
        final OffsetFit.PeakMeter[] peaks = new OffsetFit.PeakMeter[1];
        final int[] fmt = new int[2];
        AudioDecode.decode(f, new AudioDecode.Sink() {
            @Override
            public void format(int rate, int channels) {
                fmt[0] = rate;
                fmt[1] = channels;
                fit.format(rate);
                peaks[0] = new OffsetFit.PeakMeter(channels);
            }

            @Override
            public void block(float[][] planar, int frames) throws IOException {
                AddonPrep.waitWhileHeld();
                fit.add(planar, frames);
                for (int ch = 0; ch < planar.length; ch++) peaks[0].add(ch, planar[ch], 0, frames);
            }
        });
        fit.finish();
        OffsetFit.Result r = OffsetFit.fit(ytSide, side, slack);
        Prepared p = new Prepared();
        p.id = id;
        p.addon = o.addon.name;
        p.host = o.addon.host;
        p.rowId = o.row == null ? null : o.row.id;
        p.file = f;
        p.rate = fmt[0];
        p.channels = fmt[1];
        p.ytDurationSec = ytDur;
        p.addonDurationSec = side.durationSec();
        Log.i(TAG, String.format(Locale.US, "%s: %s's file decoded and fitted in %d ms: %s", id, o.addon.name, SystemClock.elapsedRealtime() - t1, r));
        if (r.none != null) {
            p.score = -1;
            p.codec = "does not fit YouTube's audio (" + r.none + ")";
            return p;
        }
        p.offsetSec = r.offsetSec;
        p.score = r.score;
        p.gain = OffsetFit.sourceGainDb(ytLufs, side.lufs(), peaks[0]);
        p.gainDb = p.gain.gainDb;
        p.codec = s.codec;
        Log.i(TAG, String.format(Locale.US, "%s: level: YouTube %.2f LUFS, file %.2f LUFS: %s", id, ytLufs, side.lufs(), p.gain));
        return p;
    }
}
