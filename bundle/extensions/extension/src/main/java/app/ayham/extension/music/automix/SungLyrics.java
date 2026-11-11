package app.ayham.extension.music.automix;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static app.ayham.extension.music.automix.Automix.TAG;

/**
 * Where a song is sung, from its synced lyrics, as the extension finds it (BitChord web
 * {@code sungSpansFromLines}): each line from 0.15 s before its start to 0.35 s after its end; a
 * line with no end is sung until the next line, at most 10 s. The extension trusts these over
 * the vocal mask, and the loop mix never loops a sung word.
 * <p>
 * Lyrics come from Morphe's own providers, in the user's order (its "Third-party lyrics"
 * setting), asked by title / artist / length. Only synced lyrics that match the song well are
 * taken; providers keyed by video id are skipped, since they answer for the song on screen, not
 * the one asked about. Kept per song in {@code files/automix/lyrics}, "none" included.
 */
final class SungLyrics {
    private static final double LEAD = 0.15, TAIL = 0.35, NO_END_MAX = 10;
    private static final String LYRICS = "app.morphe.extension.music.patches.lyrics.";

    /** Known answers: spans, or an empty array for "no synced lyrics". */
    private static final Map<String, double[][]> known = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> asking = new ConcurrentHashMap<>();
    /** A lookup that failed (network) is asked again a minute later. */
    private static final Map<String, Long> failedAt = new ConcurrentHashMap<>();

    private SungLyrics() {
    }

    /** The spans if already known: null while unknown, empty when the song has no synced lyrics. */
    static double[][] cached(Context c, String id) {
        if (id == null) return null;
        double[][] s = known.get(id);
        if (s != null) return s;
        s = load(c, id);
        if (s != null) known.put(id, s);
        return s;
    }

    static boolean pending(String id) {
        return id != null && asking.containsKey(id);
    }

    /** Asks for [id]'s lyrics on a background thread, once. */
    static void request(Context c, String id, String title, String artist, double durationSec) {
        if (id == null || title == null || title.isEmpty() || cached(c, id) != null) return;
        Long failed = failedAt.get(id);
        if (failed != null && android.os.SystemClock.elapsedRealtime() - failed < 60_000) return;
        if (asking.putIfAbsent(id, true) != null) return;
        new Thread(() -> {
            try {
                double[][] s = fetch(title, artist == null ? "" : artist, durationSec);
                if (s != null) {
                    known.put(id, s);
                    save(c, id, s);
                    Log.i(TAG, String.format("%s: %s", id, s.length == 0 ? "no synced lyrics, the vocal mask decides where it is sung"
                            : s.length + " synced lyric lines decide where it is sung"));
                } else {
                    failedAt.put(id, android.os.SystemClock.elapsedRealtime());
                }
            } catch (Throwable t) {
                failedAt.put(id, android.os.SystemClock.elapsedRealtime());
                Log.w(TAG, id + ": lyrics lookup failed", t);
            } finally {
                asking.remove(id);
            }
        }, "Automix-lyrics").start();
    }

    /** Null on a lookup error (asked again later), empty when no provider has synced lines. */
    private static double[][] fetch(String title, String artist, double durationSec) throws Exception {
        Class<?> settings = Class.forName("app.morphe.extension.music.settings.Settings");
        Object source = settings.getField("LYRICS_SOURCE").get(null);
        String order = (String) source.getClass().getMethod("get").invoke(source);
        @SuppressWarnings("unchecked")
        List<Object> providers = (List<Object>) Class.forName(LYRICS + "LyricsProviders")
                .getMethod("providersInOrder", String.class).invoke(null, order);
        Class<?> trackType = Class.forName(LYRICS + "model.TrackInfo");
        Object track = trackType.getConstructor(String.class, String.class, String.class, int.class)
                .newInstance(title, artist, "", (int) Math.round(durationSec));
        Method fetch = Class.forName(LYRICS + "requests.LyricsProvider").getMethod("fetch", trackType);
        boolean errors = false;
        for (Object p : providers) {
            Object r;
            try {
                r = fetch.invoke(p, track);
            } catch (Throwable t) {
                errors = true;
                continue;
            }
            if (r == null) continue;
            Class<?> rt = r.getClass();
            if ((boolean) rt.getMethod("videoIdKeyed").invoke(r)) continue;
            if (!(boolean) rt.getMethod("isHighMatch", trackType).invoke(r, track)) continue;
            Object lyrics = rt.getMethod("lyrics").invoke(r);
            if (lyrics == null || !(boolean) lyrics.getClass().getMethod("synced").invoke(lyrics)) continue;
            List<?> lines = (List<?>) lyrics.getClass().getMethod("lines").invoke(lyrics);
            double[][] spans = spans(lines);
            if (spans.length > 0) return spans;
        }
        return errors ? null : new double[0][];
    }

    /** BitChord web {@code sungSpansFromLines}. */
    private static double[][] spans(List<?> lines) throws Exception {
        List<double[]> raw = new ArrayList<>();
        for (Object l : lines) {
            Class<?> t = l.getClass();
            long start = (long) t.getMethod("startTimeMs").invoke(l), end = (long) t.getMethod("endTimeMs").invoke(l);
            String text = (String) t.getMethod("text").invoke(l);
            if (start < 0) continue;
            raw.add(new double[]{start, end, text == null || text.trim().isEmpty() ? 1 : 0});
        }
        raw.sort((a, b) -> Double.compare(a[0], b[0]));
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            double[] l = raw.get(i);
            if (l[2] == 1) continue;
            double start = l[0] / 1000, next = Double.NaN;
            for (int j = i + 1; j < raw.size(); j++) {
                if (raw.get(j)[0] > l[0]) {
                    next = raw.get(j)[0];
                    break;
                }
            }
            double end = l[1] > l[0] ? l[1] / 1000 : Math.min(start + NO_END_MAX, (Double.isNaN(next) ? l[0] + NO_END_MAX * 1000 : next) / 1000);
            out.add(new double[]{start - LEAD, end + TAIL});
        }
        return out.toArray(new double[0][]);
    }

    private static File file(Context c, String id) {
        return new File(new File(c.getFilesDir(), "automix/lyrics"), id + ".json");
    }

    private static double[][] load(Context c, String id) {
        try {
            File f = file(c, id);
            if (!f.exists()) return null;
            JSONArray a = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)).getJSONArray("spans");
            double[][] s = new double[a.length()][];
            for (int i = 0; i < a.length(); i++) s[i] = new double[]{a.getJSONArray(i).getDouble(0), a.getJSONArray(i).getDouble(1)};
            return s;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void save(Context c, String id, double[][] s) {
        try {
            JSONArray a = new JSONArray();
            for (double[] x : s) a.put(new JSONArray().put(x[0]).put(x[1]));
            File f = file(c, id);
            f.getParentFile().mkdirs();
            Files.write(f.toPath(), new JSONObject().put("spans", a).toString().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            Log.w(TAG, id + ": lyrics not saved", t);
        }
    }

    static void clear(Context c) {
        known.clear();
        File[] fs = new File(c.getFilesDir(), "automix/lyrics").listFiles();
        if (fs != null) for (File f : fs) f.delete();
    }
}
