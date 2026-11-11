package app.ayham.extension.music.spike;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;

/**
 * The animated art's saved state, in the app's files (kept across restarts, used offline):
 * <ul>
 *   <li>lookups per song: the clip found (its file) or a miss, 24 h; errors are not saved, so
 *   they are tried again. A saved clip whose file is still there plays even after 24 h and with
 *   no network: that is the downloaded songs' animated art offline.</li>
 *   <li>the Apple token's backup copy, used after a restart until it expires or gets a 401.</li>
 * </ul>
 */
final class CanvasCache {
    private static final String TAG = "AyhamSpike";
    static final long DAY = 24 * 3600_000L;
    private static File file;
    private static Properties props;

    private CanvasCache() {
    }

    static synchronized void init(File filesDir) {
        if (file != null) return;
        file = new File(filesDir, "ayham-canvas.properties");
        props = new Properties();
        if (file.exists()) {
            try (FileInputStream in = new FileInputStream(file)) {
                props.load(in);
            } catch (Exception e) {
                Log.w(TAG, "canvas cache: read failed", e);
            }
        }
    }

    private static void save() {
        // written next to it and moved over it: an app killed mid-write leaves the old file whole
        File tmp = new File(file.getPath() + ".part");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            props.store(out, "Ayham animated art: lookups and token");
        } catch (Exception e) {
            Log.w(TAG, "canvas cache: write failed", e);
            return;
        }
        if (!tmp.renameTo(file)) Log.w(TAG, "canvas cache: could not replace " + file);
    }

    /**
     * @return null when not saved or stale (look it up), "" for a saved miss, else the clip's file path.
     */
    static synchronized String lookup(String songKey) {
        if (props == null) return null;
        String v = props.getProperty("song." + songKey);
        if (v == null) return null;
        int bar = v.indexOf('|');
        long at;
        try {
            at = Long.parseLong(v.substring(0, bar));
        } catch (RuntimeException e) {
            // a damaged entry (no '|' or no number): looked up again. Thrown here, it would end the
            // canvas thread uncaught and so the app.
            return null;
        }
        String path = v.substring(bar + 1);
        if (!path.isEmpty() && new File(path).exists()) return path; // the clip is here: no network needed
        return System.currentTimeMillis() - at < DAY && path.isEmpty() ? "" : null;
    }

    /** The key a downloaded song's clip is saved under (title and artist can differ offline). */
    static String idKey(String videoId) {
        return "id:" + videoId;
    }

    static synchronized void put(String songKey, String clipPath) {
        if (props == null) return;
        props.setProperty("song." + songKey, System.currentTimeMillis() + "|" + (clipPath == null ? "" : clipPath));
        save();
    }

    static synchronized String[] token() {
        if (props == null) return null;
        String t = props.getProperty("token"), exp = props.getProperty("tokenExpires");
        return t == null || exp == null ? null : new String[]{t, exp};
    }

    static synchronized void token(String token, long expires) {
        if (props == null) return;
        if (token == null) {
            props.remove("token");
            props.remove("tokenExpires");
        } else {
            props.setProperty("token", token);
            props.setProperty("tokenExpires", String.valueOf(expires));
        }
        save();
    }
}
