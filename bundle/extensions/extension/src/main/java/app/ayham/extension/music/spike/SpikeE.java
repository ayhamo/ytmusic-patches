package app.ayham.extension.music.spike;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Throwaway spike, item 8 (stats for nerds, line 1): the audio format YT's ExoPlayer is
 * decoding. Players (`ctb`, the class Morphe gives ExoPlayerAccess) are captured from their
 * constructors; the audio Format (Media3 `Format`, obfuscated `cdr`, found by its "Format("
 * toString) is found by walking the player's fields once, then that field path is reused.
 */
public final class SpikeE {
    private static final String TAG = "AyhamSpike";
    private static final String FORMAT_CLASS = "cdr";
    private static final List<WeakReference<Object>> players = new ArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static List<Field> path;
    private static String last = "";
    private static boolean started;

    private SpikeE() {
    }

    /** Injection point: end of every constructor of YT's ExoPlayer class. */
    public static void capturePlayer(Object player) {
        synchronized (players) {
            players.add(new WeakReference<>(player));
        }
        Log.i(TAG, "stats: player captured " + player.getClass().getName() + "@" + System.identityHashCode(player));
        if (!started) {
            started = true;
            main.postDelayed(SpikeE::poll, 2000);
        }
    }

    private static void poll() {
        try {
            String line = line();
            if (line != null && !line.equals(last)) {
                last = line;
                Log.i(TAG, "stats: " + line);
                SpikeA.setOverlayText(line);
            }
        } catch (Throwable t) {
            Log.w(TAG, "stats poll failed", t);
        }
        main.postDelayed(SpikeE::poll, 1000);
    }

    private static String line() throws Exception {
        Object player = current();
        if (player == null) return null;
        Object format = path == null ? search(player) : follow(player, path);
        if (format == null) {
            path = null;
            return null;
        }
        return describe(format.toString());
    }

    /** The newest captured player that is READY (3). */
    public static Object current() throws Exception {
        synchronized (players) {
            for (int i = players.size() - 1; i >= 0; i--) {
                Object p = players.get(i).get();
                if (p == null) {
                    players.remove(i);
                    continue;
                }
                Method state = p.getClass().getMethod("patch_getPlaybackState");
                if ((int) state.invoke(p) == 3) return p;
            }
        }
        return null;
    }

    private static Object follow(Object from, List<Field> fields) throws Exception {
        Object o = from;
        for (Field f : fields) {
            if (o == null) return null;
            o = f.get(o);
        }
        return isAudioFormat(o) ? o : null;
    }

    /** Breadth-first over app (obfuscated) fields, depth 5, remembering the path to an audio Format. */
    private static Object search(Object root) throws Exception {
        long start = System.currentTimeMillis();
        Map<Object, Boolean> seen = new IdentityHashMap<>();
        List<Object[]> level = new ArrayList<>();
        level.add(new Object[]{root, new ArrayList<Field>()});
        int visited = 0;
        for (int depth = 0; depth < 5 && !level.isEmpty(); depth++) {
            List<Object[]> next = new ArrayList<>();
            for (Object[] node : level) {
                Object o = node[0];
                @SuppressWarnings("unchecked") List<Field> p = (List<Field>) node[1];
                for (Class<?> c = o.getClass(); c != null && !c.getName().startsWith("java."); c = c.getSuperclass()) {
                    for (Field f : c.getDeclaredFields()) {
                        if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                        String type = f.getType().getName();
                        if (type.startsWith("java.") || type.startsWith("android.") || type.startsWith("kotlin.")) continue;
                        f.setAccessible(true);
                        Object v = f.get(o);
                        if (v == null || seen.put(v, true) != null) continue;
                        visited++;
                        List<Field> np = new ArrayList<>(p);
                        np.add(f);
                        if (isAudioFormat(v)) {
                            path = np;
                            Log.i(TAG, "stats: audio format at " + describePath(np) + " (" + visited + " objects, "
                                    + (System.currentTimeMillis() - start) + " ms)");
                            return v;
                        }
                        next.add(new Object[]{v, np});
                    }
                }
            }
            level = next;
        }
        Log.i(TAG, "stats: no audio format within depth 5 (" + visited + " objects)");
        return null;
    }

    private static boolean isAudioFormat(Object o) {
        if (o == null || !o.getClass().getName().equals(FORMAT_CLASS)) return false;
        return o.toString().contains("audio/");
    }

    private static String describePath(List<Field> p) {
        StringBuilder b = new StringBuilder();
        for (Field f : p) b.append('.').append(f.getDeclaringClass().getName()).append('#').append(f.getName());
        return b.toString();
    }

    /** Format(id, label, container, sampleMime, codecs, bitrate, lang, [w, h, fps, color], [channels, rate]) */
    static String describe(String s) {
        try {
            String head = s.substring(7, s.indexOf(", ["));
            String[] h = head.split(", ");
            String tail = s.substring(s.lastIndexOf('[') + 1, s.lastIndexOf(']'));
            String[] t = tail.split(", ");
            String mime = h[3];
            String codec = mime.startsWith("audio/") ? mime.substring(6) : mime;
            if ("mp4a-latm".equals(codec)) codec = "AAC";
            else if ("opus".equals(codec)) codec = "Opus";
            int bitrate = Integer.parseInt(h[5]);
            int channels = Integer.parseInt(t[0]);
            int rate = Integer.parseInt(t[1]);
            StringBuilder b = new StringBuilder(codec);
            if (rate > 0) b.append(" · ").append(rate / 1000.0).append(" kHz");
            if (bitrate > 0) b.append(" · ").append(Math.round(bitrate / 1000.0)).append(" kbps");
            if (channels > 0) b.append(" · ").append(channels).append(" ch");
            b.append(" · itag ").append(h[0]);
            return b.toString();
        } catch (Exception e) {
            return s;
        }
    }
}
