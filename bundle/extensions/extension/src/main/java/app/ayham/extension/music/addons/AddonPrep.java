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
        /** A downloaded song (AddonDownloads): its file is kept when it leaves memory. */
        public boolean saved;
        /** Downloads: the row's ISRC and the tier asked for. */
        public String isrc, tier;
        /** The file's loudness (dBFS, mono RMS) every ENV_HOP seconds of its own time: where it is quiet. */
        public float[] env;
        public static final double ENV_HOP = Envelope.HOP;

        /**
         * The quietest ENV_HOP of the file between YouTube times [fromSec, toSec), as YouTube
         * seconds; NaN when the window is outside the file.
         */
        public double quietest(double fromSec, double toSec) {
            if (env == null) return Double.NaN;
            int a = (int) Math.max(0, Math.ceil((fromSec + offsetSec) / ENV_HOP)), b = (int) Math.min(env.length, Math.floor((toSec + offsetSec) / ENV_HOP));
            int best = -1;
            for (int i = a; i < b; i++) if (best < 0 || env[i] < env[best]) best = i;
            return best < 0 ? Double.NaN : best * ENV_HOP - offsetSec;
        }

        /** The file's level at YouTube time sec (dBFS), or 0 outside it. */
        public float levelAt(double sec) {
            int i = (int) Math.floor((sec + offsetSec) / ENV_HOP);
            return env == null || i < 0 || i >= env.length ? 0f : env[i];
        }

        /**
         * The gain our player and the mix decks apply: at most 0 dB, since the player's volume stops at
         * 1 and a boost the decks alone had would step the level at a takeover or hand-over.
         */
        public float gainLinear() {
            return (float) Math.pow(10, Math.min(0, gainDb) / 20);
        }

        @Override
        public String toString() {
            return String.format(Locale.US, "%s via %s: offset %+.4f s, gain %+.2f dB%s, %s %d Hz %d ch, score %.3f", id, addon, offsetSec, gainDb,
                    gain != null && gain.capped != null ? " (" + gain.capped + ")" : "", codec, rate, channels, score);
        }
    }

    /** Mono RMS in dBFS per HOP seconds, in order. */
    static final class Envelope {
        static final double HOP = 0.05;
        private int hop;
        private double acc;
        private int n;
        private float[] out = new float[4096];
        private int len;

        void format(int rate) {
            hop = Math.max(1, (int) Math.round(rate * HOP));
        }

        void add(float[][] planar, int frames) {
            int ch = planar.length;
            for (int f = 0; f < frames; f++) {
                float s = 0;
                for (int c = 0; c < ch; c++) s += planar[c][f];
                s /= ch;
                acc += s * (double) s;
                if (++n == hop) push();
            }
        }

        private void push() {
            if (len == out.length) out = java.util.Arrays.copyOf(out, len * 2);
            out[len++] = (float) (10 * Math.log10(acc / n + 1e-12));
            acc = 0;
            n = 0;
        }

        float[] done() {
            if (n > 0) push();
            return java.util.Arrays.copyOf(out, len);
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
        if (p != null && p.saved) {
            // a downloaded file stays (only this play falls back to YouTube)
            failedAt.put(id, SystemClock.elapsedRealtime());
            Log.w(TAG, "dropped downloaded " + id + " for now: " + why);
            return;
        }
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
                p = AddonDownloads.has(ctx, id) ? AddonDownloads.load(ctx, id) : prepare(ctx, id, info[0], info[1], "1".equals(info[2]));
                why = p == null ? "no addon has a file that fits" : null;
            } catch (Throwable t) {
                why = Protocol.scrub(String.valueOf(t));
                Log.w(TAG, "prepare " + id + " failed: " + why);
            }
            synchronized (AddonPrep.class) {
                working = null;
                if (p != null) {
                    ready.put(id, p);
                    // the eldest first, never one our player is on (its next seek reopens the file)
                    java.util.Iterator<Map.Entry<String, Prepared>> it = ready.entrySet().iterator();
                    while (ready.size() > KEEP && it.hasNext()) {
                        Map.Entry<String, Prepared> e = it.next();
                        if (app.ayham.extension.music.automix.AddonDirect.inUse(e.getKey())) continue;
                        it.remove();
                        deleteFiles(e.getValue());
                    }
                } else {
                    failedAt.put(id, SystemClock.elapsedRealtime());
                }
            }
            Log.i(TAG, (p != null ? "ready: " + p : "not from an addon: " + id + " (" + why + ")") + " in " + (SystemClock.elapsedRealtime() - t0) + " ms");
        }
    }

    /** The fetched file of a song not prepared (whatever its extension). */
    private static File fileFor(Context c, String id) {
        File[] fs = dir(c).listFiles((d, n) -> n.startsWith(id + ".") && !n.endsWith(".part"));
        return fs == null || fs.length == 0 ? null : fs[0];
    }

    private static void deleteQuietly(File f) {
        //noinspection ResultOfMethodCallIgnored
        if (f != null) f.delete();
    }

    private static void deleteFiles(Prepared p) {
        if (p == null) return;
        //noinspection ResultOfMethodCallIgnored
        if (!p.saved) p.file.delete();
        app.ayham.extension.music.automix.AddonPcm.delete(ctx, p.id);
    }

    /** At start: files prepared by an earlier run are no longer known (kept only in memory), so they go. */
    public static void clearCache(Context c) {
        new Thread(() -> {
            int n = 0;
            for (File d : new File[]{dir(c), new File(c.getCacheDir(), "addons/yt")}) {
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

    /** Forgets a song held in memory (its download was deleted): its next play asks again. */
    public static synchronized void forget(String id) {
        Prepared p = ready.remove(id);
        if (p != null) app.ayham.extension.music.automix.AddonPcm.delete(ctx, p.id);
    }

    /** What a download asks of the fetch (AddonDownloads); null for playback. */
    static final class Download {
        String tier;
        File dir;
        /** A file already downloaded for the same ISRC (another upload of the song): reused, not fetched. */
        java.util.function.Function<String, File> byIsrc;
    }

    /** The song from the addons, fitted and levelled; null when none fits. */
    static Prepared prepare(Context c, String id, String title, String artist, boolean isVideo) throws Exception {
        return prepare(c, id, title, artist, isVideo, null);
    }

    /** dl set: a download (allowDownloads addons, ±2 s, lossless at the tier asked, kept in dl.dir). */
    static Prepared prepare(Context c, String id, String title, String artist, boolean isVideo, Download dl) throws Exception {
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
            // not kept (addons switched off during the download): nobody else needs it
            if (yt.getParentFile() != null && "dl".equals(yt.getParentFile().getName())) //noinspection ResultOfMethodCallIgnored
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
        for (String l : AddonSettings.links(c)) addons.add(new AddonClient.Addon(l));
        AddonClient.Options opt = new AddonClient.Options();
        opt.slackSec = AddonSettings.slackSec();
        if (dl != null) {
            opt.forDownload = true;
            opt.tier = dl.tier;
        }
        final Prepared[] got = new Prepared[1];
        opt.check = o -> {
            try {
                String early = dl == null ? null : Fetch.statedRefusal(o.stream, dl.tier);
                if (early != null) return early;
                Prepared p = fetchAndFit(c, id, o, ytSide, ytDur, ytLufs, opt.slackSec, dl);
                if (p.score < 0) {
                    //noinspection ResultOfMethodCallIgnored
                    p.file.delete();
                    return p.codec;   // the refusal, carried in codec
                }
                got[0] = p;
                return null;
            } catch (Throwable e) {
                if (dl == null && !app.ayham.extension.music.automix.AddonDirect.inUse(id)) deleteQuietly(fileFor(c, id));
                return "fetch or decode failed: " + Protocol.scrub(String.valueOf(e));
            }
        };
        AddonClient.Opened o = client().resolve(t, addons, opt);
        if (o.stream == null || got[0] == null) {
            if (o.tried != null) for (AddonClient.Tried x : o.tried) Log.i(TAG, "  passed over: " + x.addon + " " + x.row + ": " + x.why);
            return null;
        }
        Prepared p = got[0];
        if (dl != null) return p; // its deck PCM is written when it is played (AddonDownloads.load)
        try {
            app.ayham.extension.music.automix.AddonPcm.write(c, p);
        } catch (Throwable e) {
            deleteFiles(p);
            throw e;
        }
        return p;
    }

    private static Prepared fetchAndFit(Context c, String id, AddonClient.Opened o, OffsetFit.Side ytSide, double ytDur, double ytLufs, double slack, Download dl) throws Exception {
        Protocol.Stream s = o.stream;
        String ext = s.codec == null ? "audio" : s.codec.toLowerCase(Locale.ROOT).contains("flac") ? "flac"
                : s.codec.toLowerCase(Locale.ROOT).contains("alac") || s.codec.toLowerCase(Locale.ROOT).contains("aac") ? "m4a"
                : s.codec.toLowerCase(Locale.ROOT).contains("mp3") ? "mp3" : "audio";
        if (dl != null) {
            File f = null;
            File reuse = o.row == null || o.row.isrc == null || dl.byIsrc == null ? null : dl.byIsrc.apply(o.row.isrc);
            if (reuse != null && reuse.length() > 0) {
                f = reuse;
                Log.i(TAG, id + ": the same recording (ISRC) is downloaded already: " + f.getName() + " reused");
            } else {
                f = Fetch.download(o, new File(dl.dir, id + "." + ext));
            }
            String bad = Fetch.formatRefusal(f, ext, dl.tier, s);
            if (bad != null) {
                if (f != reuse) deleteQuietly(f);
                Prepared p = new Prepared();
                p.score = -1;
                p.codec = bad;
                p.file = new File(dl.dir, id + ".none");
                return p;
            }
            Prepared p = fit(id, o, f, ytSide, ytDur, ytLufs, slack);
            if (p.score < 0 && f == reuse) p.file = new File(dl.dir, id + ".none"); // the shared file stays
            p.saved = true;
            p.tier = dl.tier;
            p.isrc = o.row == null ? null : o.row.isrc;
            return p;
        }
        File f = new File(dir(c), id + "." + ext), part = new File(dir(c), id + "." + ext + ".part");
        long t0 = SystemClock.elapsedRealtime();
        HttpURLConnection h = HttpTransport.open(Protocol.audio(s.url, s.headers, null));
        h.setReadTimeout(30_000);
        boolean done = false;
        try (InputStream in = h.getInputStream(); OutputStream out = new FileOutputStream(part)) {
            int code = h.getResponseCode();
            if (code != 200 && code != 206) throw new IOException("audio: HTTP " + code);
            byte[] b = new byte[1 << 16];
            long total = 0, until = t0 + 120_000;
            for (int n; (n = in.read(b)) > 0; ) {
                out.write(b, 0, n);
                total += n;
                if (total > 300L << 20) throw new IOException("over 300 MB");
                if (SystemClock.elapsedRealtime() > until) throw new IOException("download over 120 s");
            }
            done = true;
        } finally {
            h.disconnect();
            if (!done) //noinspection ResultOfMethodCallIgnored
                part.delete();
        }
        if (!part.renameTo(f)) throw new IOException("could not keep the file");
        Log.i(TAG, String.format(Locale.US, "%s: %s's file fetched (%d KB) in %d ms", id, o.addon.name, f.length() / 1024, SystemClock.elapsedRealtime() - t0));
        return fit(id, o, f, ytSide, ytDur, ytLufs, slack);
    }

    /** Decodes the file and fits it to YouTube's audio: a score under 0 (with why in codec) when it doesn't fit. */
    static Prepared fit(String id, AddonClient.Opened o, File f, OffsetFit.Side ytSide, double ytDur, double ytLufs, double slack) throws Exception {
        Protocol.Stream s = o.stream;
        long t1 = SystemClock.elapsedRealtime();
        final OffsetFit.Side side = new OffsetFit.Side();
        final AudioDecode.ToFit fit = new AudioDecode.ToFit(side);
        final OffsetFit.PeakMeter[] peaks = new OffsetFit.PeakMeter[1];
        final int[] fmt = new int[2];
        final Envelope env = new Envelope();
        AudioDecode.decode(f, new AudioDecode.Sink() {
            @Override
            public void format(int rate, int channels) {
                fmt[0] = rate;
                fmt[1] = channels;
                fit.format(rate);
                peaks[0] = new OffsetFit.PeakMeter(channels);
                env.format(rate);
            }

            @Override
            public void block(float[][] planar, int frames) throws IOException {
                AddonPrep.waitWhileHeld();
                fit.add(planar, frames);
                env.add(planar, frames);
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
        p.env = env.done();
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
