package app.ayham.extension.music.features;

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

import app.ayham.extension.music.addons.AddonPlayback;
import app.ayham.extension.music.addons.Quality;

/**
 * YT's players, captured from their constructors, and the stats line: the audio format YT's ExoPlayer is
 * decoding. Players (`ctb`, the class Morphe gives ExoPlayerAccess) are captured from their
 * constructors; the audio Format (Media3 `Format`, obfuscated `cdr`, found by its "Format("
 * toString) is found by walking the player's fields once, then that field path is reused.
 */
public final class PlayerTap {
    private static final String TAG = "Ayham";
    private static final String FORMAT_CLASS = "cdr";
    private static final List<WeakReference<Object>> players = new ArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static List<Field> path;
    private static String last = "";
    private static boolean started;

    private PlayerTap() {
    }

    /**
     * Players we build ourselves (the addon player) are not YT's: drop them from the captured list so
     * Automix, the stats line and the volume gate never read or act on them.
     */
    private static final Map<Object, Boolean> ours = new IdentityHashMap<>();

    public static void markOurs(Object player) {
        synchronized (players) {
            ours.put(player, Boolean.TRUE);
            for (int i = players.size() - 1; i >= 0; i--) {
                if (players.get(i).get() == player) players.remove(i);
            }
        }
    }

    public static void forget(Object player) {
        synchronized (players) {
            ours.remove(player);
        }
    }

    public static boolean isOurs(Object player) {
        synchronized (players) {
            return ours.containsKey(player);
        }
    }

    /** Injection point: end of every constructor of YT's ExoPlayer class. */
    public static void capturePlayer(Object player) {
        synchronized (players) {
            players.add(new WeakReference<>(player));
        }
        Log.i(TAG, "stats: player captured " + player.getClass().getName() + "@" + System.identityHashCode(player));
        if (!started) {
            started = true;
            main.postDelayed(PlayerTap::poll, 2000);
        }
    }

    private static void poll() {
        try {
            String line = line();
            // addon audio ended: its line goes, even when YT's player has none to show yet
            if (line == null && addonLine) line = "";
            if (line != null) addonLine = fromAddon;
            if (line != null && !line.equals(last)) {
                last = line;
                Log.i(TAG, "stats: " + line);
                Features.setOverlayText(line);
            }
        } catch (Throwable t) {
            Log.w(TAG, "stats poll failed", t);
        }
        main.postDelayed(PlayerTap::poll, 1000);
    }

    private static String line() throws Exception {
        Object[] q = AddonPlayback.now();
        badge = (Quality.Badge) q[0];
        fromAddon = q[1] != null;
        if (fromAddon) return (String) q[1];
        Object player = current();
        if (player == null) return null;
        Object format;
        if (path == null) {
            format = search(player);
            if (format != null) path = lastFound;
        } else {
            format = follow(player, path);
        }
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

    /**
     * The object at the end of the path, or null when the path doesn't fit this player (another player,
     * such as the one for a downloaded song, holds other classes on the way): the path is then searched again.
     */
    static Object follow(Object from, List<Field> fields) throws Exception {
        Object o = from;
        for (Field f : fields) {
            if (o == null || !f.getDeclaringClass().isInstance(o)) return null;
            o = f.get(o);
        }
        return isAudioFormat(o) ? o : null;
    }

    /** Breadth-first over app (obfuscated) fields, depth 5, remembering the path to an audio Format. */
    /** The audio Format a player (YT's or ours) is decoding, or null. */
    public static Object audioFormat(Object player) throws Exception {
        List<Field> known = ourPath;
        Object f = known == null ? null : follow(player, known);
        if (f != null) return f;
        ourPath = null;
        f = search(player);
        if (f != null) ourPath = lastFound;
        return f;
    }

    private static volatile List<Field> ourPath;
    /** The stats line on screen is the addon's; the last line() came from the addon. */
    private static boolean addonLine, fromAddon;
    /** The field path of the last search's find. */
    private static List<Field> lastFound;
    private static volatile Quality.Badge badge = Quality.Badge.NONE;

    /** The quality badge for addon audio (NONE when YT's own audio plays). */
    public static Quality.Badge badge() {
        return badge;
    }

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
                            lastFound = np;
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

    /** The codec under its usual name rather than its MIME type (BitChord's codecLabel). */
    static String codecLabel(String mime) {
        if (mime.endsWith("opus")) return "Opus";
        if (mime.endsWith("mp4a-latm")) return "AAC";
        if (mime.endsWith("vorbis")) return "Vorbis";
        if (mime.endsWith("mpeg")) return "MP3";
        if (mime.endsWith("flac")) return "FLAC";
        int slash = mime.indexOf('/');
        return (slash >= 0 ? mime.substring(slash + 1) : mime).toUpperCase(java.util.Locale.ROOT);
    }

    /** Format(id, label, container, sampleMime, codecs, bitrate, lang, [w, h, fps, color], [channels, rate]) */
    static String describe(String s) {
        try {
            String head = s.substring(7, s.indexOf(", ["));
            String[] h = head.split(", ");
            String tail = s.substring(s.lastIndexOf('[') + 1, s.lastIndexOf(']'));
            String[] t = tail.split(", ");
            String codec = codecLabel(h[3]);
            int bitrate = Integer.parseInt(h[5]);
            int channels = Integer.parseInt(t[0]);
            int rate = Integer.parseInt(t[1]);
            // BitChord's stream line: codec · sample rate · bitrate · channels (no itag)
            StringBuilder b = new StringBuilder(codec);
            if (rate > 0) b.append(" · ").append(String.format(java.util.Locale.ROOT, "%.1f kHz", rate / 1000f));
            if (bitrate > 0) b.append(" · ").append(Math.round(bitrate / 1000.0)).append(" kbps");
            if (channels > 0) b.append(" · ").append(channels == 1 ? "Mono" : channels == 2 ? "Stereo" : channels + " channels");
            return b.toString();
        } catch (Exception e) {
            return s;
        }
    }
}
