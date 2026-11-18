package app.ayham.extension.music.addons;

import android.util.Log;

import java.lang.reflect.Field;

/**
 * What addon audio is doing now, for the quality badge and the stats line: the player we play it
 * on, the addon it came from, whether it is audible (not a silent pre-roll), and whether a lookup is
 * still running. Read from the renderer's own input Format (YT Music 9.30.53's media3 `cdr`:
 * L = sampleRate, M = pcmEncoding, found by dumping a 24-bit and a 16-bit FLAC on the emulator).
 */
public final class AddonPlayback {
    private static final String TAG = "Ayham";
    private static volatile Object player;
    private static volatile String addon;
    private static volatile boolean audible, upgrading, ownAtmos;
    private static Field rateField, encodingField;
    private static boolean fieldsFailed;

    private AddonPlayback() {
    }

    /** A lookup started (true) or ended (false) for the song now playing. */
    public static void upgrading(boolean on) {
        upgrading = on;
    }

    /** Our player now carries this addon's audio; audible once it is heard (after any pre-roll). */
    public static void playing(Object p, String addonName, boolean isAudible) {
        playing(p, addonName, isAudible, false);
    }

    /**
     * atmosDecode: the player plays our own decode of a Dolby Atmos file (AtmosWav), so its input
     * is PCM; the badge and the line still say what was decoded.
     */
    public static void playing(Object p, String addonName, boolean isAudible, boolean atmosDecode) {
        player = p;
        addon = addonName;
        audible = isAudible;
        ownAtmos = atmosDecode;
    }

    public static void audible(Object p, boolean isAudible) {
        if (player == p) audible = isAudible;
    }

    public static void stopped(Object p) {
        if (player != p) return;
        player = null;
        ownAtmos = false;
        addon = null;
        audible = false;
    }

    /** {badge, stats line or null}: the line only while addon audio is really heard. */
    public static Object[] now() {
        Object p = player;
        String mime = null, line = null;
        int rate = 0, enc = -1, bitrate = -1;
        if (p != null && audible) {
            try {
                if ((int) p.getClass().getMethod("n").invoke(p) == 3) {
                    Object f = app.ayham.extension.music.features.PlayerTap.audioFormat(p);
                    if (f != null) {
                        String[] h = head(f.toString());
                        mime = h[3];
                        bitrate = parse(h[5]);
                        rate = intField(f, "L");
                        enc = intField(f, "M");
                        if (ownAtmos && "audio/raw".equals(mime)) {
                            // the phone's Dolby decoder's render, played from our WAV of it
                            mime = Atmos.EAC3_JOC;
                            bitrate = -1;
                            enc = -1;
                        }
                        line = Quality.line(mime, rate, enc, bitrate, addon);
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "addon format read failed", t);
            }
        }
        return new Object[]{Quality.badge(upgrading, mime, rate, enc, bitrate), line};
    }

    /** Format(id, label, container, sampleMime, codecs, bitrate, ...) */
    public static String[] head(String s) {
        return s.substring(7, s.indexOf(", [")).split(", ");
    }

    public static int parse(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int intField(Object f, String name) throws Exception {
        if (fieldsFailed) return name.equals("M") ? -1 : 0;
        try {
            if (rateField == null) {
                rateField = f.getClass().getDeclaredField("L");
                encodingField = f.getClass().getDeclaredField("M");
                rateField.setAccessible(true);
                encodingField.setAccessible(true);
            }
            return (name.equals("L") ? rateField : encodingField).getInt(f);
        } catch (NoSuchFieldException e) {
            fieldsFailed = true;
            Log.w(TAG, "Format fields not found: badge without bit depth");
            return name.equals("M") ? -1 : 0;
        }
    }
}
