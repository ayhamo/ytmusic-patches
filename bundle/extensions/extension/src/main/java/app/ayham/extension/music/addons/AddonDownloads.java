package app.ayham.extension.music.addons;

import android.content.Context;
import android.os.Process;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Properties;

/**
 * Lossless downloads: alongside YT's own download of a song, its lossless file from the user's
 * addons (allowDownloads ones, ±2 s, the bytes checked), fitted to YouTube's audio like playback
 * and kept in files/ayham-downloads with what the fit found (id.json). A downloaded song then plays
 * from its file, offline too. The tier asked for steps down (Best, Hi-Res, CD) when no addon has it;
 * with none, YT's download is all the song has.
 *
 * <p>One worker; the queue is kept on disk, so downloads cut off by the app closing go on at the next start.
 */
public final class AddonDownloads {
    private static final String TAG = AddonPrep.TAG;
    /** The tiers of the popup, in the order they step down. */
    public static final String BEST = Pick.LOSSLESS, HIRES = Pick.HIRES, CD = Pick.CD;
    private static final Deque<String> queue = new ArrayDeque<>();
    private static Thread worker;
    private static Context ctx;
    private static volatile String working;

    private AddonDownloads() {
    }

    public static File dir(Context c) {
        return new File(c.getFilesDir(), "ayham-downloads");
    }

    private static File meta(Context c, String id) {
        return new File(dir(c), id + ".json");
    }

    private static File pendingFile(Context c) {
        return new File(dir(c), "pending.properties");
    }

    /** A downloaded file is here for the song. */
    public static boolean has(Context c, String id) {
        if (c == null || id == null) return false;
        try {
            File m = meta(c, id);
            if (!m.exists()) return false;
            return new File(new JSONObject(read(m)).getString("file")).length() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** The file's format line for the downloads list ("FLAC 96 kHz/24 bit"), or null. */
    public static String describe(Context c, String id) {
        try {
            JSONObject j = new JSONObject(read(meta(c, id)));
            return j.optString("format", null);
        } catch (Exception e) {
            return null;
        }
    }

    /** Adds a song to download at tier (BEST, HIRES or CD). */
    public static synchronized void enqueue(Context c, String id, String title, String artist, boolean isVideo, String tier) {
        if (c == null || id == null || title == null || title.isEmpty()) return;
        ctx = c.getApplicationContext();
        if (has(c, id) || id.equals(working) || queue.contains(id)) return;
        if (AddonSettings.links(c).isEmpty()) {
            Log.i(TAG, "download " + id + ": no addon links, YouTube's download only");
            return;
        }
        Properties p = pending(c);
        p.setProperty(id, tier + "\n" + (isVideo ? "1" : "0") + "\n" + artist + "\n" + title);
        savePending(c, p);
        queue.addLast(id);
        start();
    }

    /** At start: downloads left over from the last run go on. */
    public static synchronized void resume(Context c) {
        ctx = c.getApplicationContext();
        Properties p = pending(c);
        for (String id : p.stringPropertyNames()) if (!queue.contains(id)) queue.addLast(id);
        if (!queue.isEmpty()) {
            Log.i(TAG, "downloads: " + queue.size() + " left over from the last run, going on");
            start();
        }
    }

    private static void start() {
        if (worker == null) {
            worker = new Thread(AddonDownloads::work, "addon-downloads");
            worker.start();
        }
        AddonDownloads.class.notifyAll();
    }

    /** The tiers tried for the one asked: each steps down to the next. */
    static String[] steps(String tier) {
        if (HIRES.equals(tier)) return new String[]{HIRES, CD};
        if (CD.equals(tier)) return new String[]{CD};
        return new String[]{BEST, HIRES, CD};
    }

    private static void work() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        while (true) {
            String id;
            String[] info;
            synchronized (AddonDownloads.class) {
                while (queue.isEmpty()) {
                    try {
                        AddonDownloads.class.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                id = queue.pollFirst();
                String v = pending(ctx).getProperty(id);
                info = v == null ? null : v.split("\n", 4);
                if (info == null || info.length < 4) continue;
                working = id;
            }
            long t0 = SystemClock.elapsedRealtime();
            AddonPrep.Prepared got = null;
            String why = null;
            try {
                for (String tier : steps(info[0])) {
                    AddonPrep.Download dl = new AddonPrep.Download();
                    dl.tier = tier;
                    dl.dir = dir(ctx);
                    dl.byIsrc = AddonDownloads::byIsrc;
                    got = AddonPrep.prepare(ctx, id, info[3], info[2], "1".equals(info[1]), dl);
                    if (got != null) break;
                    Log.i(TAG, "download " + id + ": no addon has it at " + tier + (tier.equals(info[0]) ? "" : " (stepped down)"));
                }
                if (got != null) save(ctx, got, info[3], info[2]);
                else why = "no addon has it lossless: YouTube's download only";
            } catch (Throwable t) {
                why = Protocol.scrub(String.valueOf(t));
            }
            synchronized (AddonDownloads.class) {
                working = null;
                // a failure is not retried at every start: the user downloads it again to retry
                Properties p = pending(ctx);
                p.remove(id);
                savePending(ctx, p);
            }
            Log.i(TAG, (got != null ? "downloaded: " + got : "download " + id + ": " + why) + " in " + (SystemClock.elapsedRealtime() - t0) + " ms");
        }
    }

    /** A file of this recording (ISRC) already downloaded, else null. */
    static File byIsrc(String isrc) {
        if (isrc == null || isrc.isEmpty() || ctx == null) return null;
        File[] fs = dir(ctx).listFiles((d, n) -> n.endsWith(".json"));
        if (fs != null) for (File m : fs) {
            try {
                JSONObject j = new JSONObject(read(m));
                if (isrc.equalsIgnoreCase(j.optString("isrc"))) {
                    File f = new File(j.getString("file"));
                    if (f.length() > 0) return f;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static void save(Context c, AddonPrep.Prepared p, String title, String artist) throws Exception {
        JSONObject j = new JSONObject();
        j.put("title", title).put("artist", artist);
        j.put("file", p.file.getAbsolutePath()).put("addon", p.addon).put("host", p.host).put("row", p.rowId)
                .put("offset", p.offsetSec).put("gain", p.gainDb).put("ytDur", p.ytDurationSec).put("addonDur", p.addonDurationSec)
                .put("score", p.score).put("rate", p.rate).put("channels", p.channels).put("codec", p.codec)
                .put("isrc", p.isrc).put("tier", p.tier).put("at", System.currentTimeMillis());
        byte[] head = new byte[1 << 16];
        int n;
        try (InputStream in = new FileInputStream(p.file)) {
            n = in.read(head);
        }
        Fetch.Info i = Fetch.info(head, Math.max(0, n));
        if (i != null) j.put("format", i.toString());
        if (p.env != null) {
            ByteBuffer b = ByteBuffer.allocate(p.env.length * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (float v : p.env) b.putFloat(v);
            j.put("env", Base64.encodeToString(b.array(), Base64.NO_WRAP));
        }
        write(meta(c, p.id), j.toString());
    }

    /** A downloaded song, ready to play: its deck PCM written again (the cache is cleared at start). */
    static AddonPrep.Prepared load(Context c, String id) throws Exception {
        JSONObject j = new JSONObject(read(meta(c, id)));
        AddonPrep.Prepared p = new AddonPrep.Prepared();
        p.id = id;
        p.saved = true;
        p.file = new File(j.getString("file"));
        if (p.file.length() == 0) throw new IOException("the downloaded file is gone");
        p.addon = j.optString("addon");
        p.host = j.optString("host");
        p.rowId = j.optString("row", null);
        p.offsetSec = j.getDouble("offset");
        p.gainDb = j.getDouble("gain");
        p.ytDurationSec = j.getDouble("ytDur");
        p.addonDurationSec = j.optDouble("addonDur", 0);
        p.score = j.optDouble("score", 0);
        p.rate = j.getInt("rate");
        p.channels = j.getInt("channels");
        p.codec = j.optString("codec");
        p.isrc = j.optString("isrc", null);
        p.tier = j.optString("tier", null);
        String env = j.optString("env", "");
        if (!env.isEmpty()) {
            ByteBuffer b = ByteBuffer.wrap(Base64.decode(env, Base64.NO_WRAP)).order(ByteOrder.LITTLE_ENDIAN);
            p.env = new float[b.remaining() / 4];
            for (int k = 0; k < p.env.length; k++) p.env[k] = b.getFloat();
        }
        app.ayham.extension.music.automix.AddonPcm.write(c, p);
        Log.i(TAG, "downloaded file of " + id + " loaded: " + p.file.getName());
        return p;
    }

    /** The song's download was deleted: its file goes (unless another song's ISRC shares it). */
    public static synchronized long delete(Context c, String id) {
        if (c == null || id == null) return 0;
        Properties pend = pending(c);
        if (pend.remove(id) != null) savePending(c, pend);
        queue.remove(id);
        File m = meta(c, id);
        long freed = 0;
        if (m.exists()) {
            try {
                File f = new File(new JSONObject(read(m)).getString("file"));
                //noinspection ResultOfMethodCallIgnored
                m.delete();
                if (!sharedBy(c, f) && f.exists()) {
                    freed = f.length();
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            } catch (Exception e) {
                //noinspection ResultOfMethodCallIgnored
                m.delete();
            }
        }
        // a file left half-fetched for it
        File[] parts = dir(c).listFiles((d, n) -> n.startsWith(id + ".") && n.endsWith(".part"));
        if (parts != null) for (File f : parts) //noinspection ResultOfMethodCallIgnored
            f.delete();
        AddonPrep.forget(id);
        return freed;
    }

    /** A downloaded file to export: the file, and "Artist - Title" for its name. */
    public static final class Saved {
        public String id, name, format;
        public File file;
    }

    /** The downloaded files, one per song. */
    public static java.util.List<Saved> saved(Context c) {
        java.util.List<Saved> out = new java.util.ArrayList<>();
        File[] fs = dir(c).listFiles((d, n) -> n.endsWith(".json"));
        if (fs != null) for (File m : fs) {
            try {
                JSONObject j = new JSONObject(read(m));
                Saved s = new Saved();
                s.id = m.getName().substring(0, m.getName().length() - 5);
                s.file = new File(j.getString("file"));
                if (s.file.length() == 0) continue;
                String t = j.optString("title", ""), a = j.optString("artist", "");
                s.name = t.isEmpty() ? s.id : a.isEmpty() ? t : a + " - " + t;
                s.format = j.optString("format", null);
                out.add(s);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** The ids with a download record. */
    public static java.util.List<String> ids(Context c) {
        java.util.List<String> out = new java.util.ArrayList<>();
        File[] fs = dir(c).listFiles((d, n) -> n.endsWith(".json"));
        if (fs != null) for (File f : fs) out.add(f.getName().substring(0, f.getName().length() - 5));
        out.addAll(pending(c).stringPropertyNames());
        return out;
    }

    private static boolean sharedBy(Context c, File f) {
        File[] fs = dir(c).listFiles((d, n) -> n.endsWith(".json"));
        if (fs != null) for (File m : fs) {
            try {
                if (f.getAbsolutePath().equals(new JSONObject(read(m)).getString("file"))) return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private static Properties pending(Context c) {
        Properties p = new Properties();
        File f = pendingFile(c);
        if (f.exists()) {
            try (InputStream in = new FileInputStream(f)) {
                p.load(in);
            } catch (Exception e) {
                Log.w(TAG, "downloads: pending list unreadable", e);
            }
        }
        return p;
    }

    private static void savePending(Context c, Properties p) {
        try {
            if (p.isEmpty()) {
                //noinspection ResultOfMethodCallIgnored
                pendingFile(c).delete();
                return;
            }
            ByteArrayOut out = new ByteArrayOut();
            p.store(out, "Ayham: lossless downloads not done yet");
            write(pendingFile(c), out.toString("ISO-8859-1"));
        } catch (Exception e) {
            Log.w(TAG, "downloads: pending list not saved", e);
        }
    }

    private static final class ByteArrayOut extends java.io.ByteArrayOutputStream {
    }

    static String read(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            return b.toString("UTF-8");
        }
    }

    /** Written next to it and moved over it, so a kill mid-write leaves the old one whole. */
    static void write(File f, String text) throws IOException {
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream o = new FileOutputStream(tmp)) {
            o.write(text.getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("could not write " + f.getName());
        }
    }
}
