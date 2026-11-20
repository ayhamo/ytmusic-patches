package app.ayham.extension.music.features;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.util.Locale;

/**
 * "Jump to the most replayed part" (Prism's BestPart): seeks the playing song to where YouTube's
 * "Most replayed" curve rises to its peak, the same pick the canvas makes for its loop.
 */
final class BestPart {
    private static final String TAG = "Ayham";
    private static final Handler main = new Handler(Looper.getMainLooper());

    private BestPart() {
    }

    static void jump(Activity a) {
        String id = app.ayham.extension.music.automix.Automix.currentId();
        if (id == null) {
            toast(a, "No song playing");
            return;
        }
        new Thread(() -> {
            double[] heat = VideoCanvas.heatmap(id);
            if (heat == null) {
                main.post(() -> toast(a, "YouTube has no most replayed part for this song"));
                return;
            }
            int n = heat.length / 3;
            double dur = heat[(n - 1) * 3] + heat[(n - 1) * 3 + 1];
            double at = VideoCanvas.partStart(heat, dur);
            if (at < 0) {
                // a flat curve, or a song under a minute: nothing stands out
                main.post(() -> toast(a, "No part of this song stands out in YouTube's most replayed"));
                return;
            }
            main.post(() -> {
                if (!id.equals(app.ayham.extension.music.automix.Automix.currentId())) return;
                Object p;
                try {
                    p = PlayerTap.current();
                } catch (Exception e) {
                    p = null;
                }
                if (!(p instanceof SeekAccess)) {
                    toast(a, "Could not seek the player");
                    return;
                }
                ((SeekAccess) p).ayham_seekTo(Math.round(at * 1000));
                Log.i(TAG, String.format(Locale.US, "best part: %s jumped to %.1f s of %.0f s", id, at, dur));
                toast(a, String.format(Locale.US, "Most replayed part, %d:%02d", (int) at / 60, (int) at % 60));
            });
        }, "ayham-bestpart").start();
    }

    private static void toast(Activity a, String s) {
        Toast.makeText(a, s, Toast.LENGTH_SHORT).show();
    }

    /**
     * The chorus from time-synced lyrics (canvas.md §6 rule 11, Prism's BestPart.chorusMs): the start
     * of the first line holding the most repeated line (keys: lower case, letters and digits, 4 or
     * more), from 5000 on and not in the last 30 000, pulled back over up to 4 lines before it that
     * repeat at least twice and at least (most - 1) times. Null for a song under 60 s, fewer than 4
     * timed lines, or nothing repeated.
     */
    static Long chorusMs(long durationMs, long[] startMs, String[] text) {
        if (durationMs < 60_000 || startMs == null || text == null) return null;
        java.util.List<Long> at = new java.util.ArrayList<>();
        java.util.List<String> keys = new java.util.ArrayList<>();
        for (int i = 0; i < startMs.length && i < text.length; i++) {
            if (text[i] == null || text[i].trim().isEmpty()) continue;   // gaps and blank lines
            at.add(startMs[i]);
            keys.add(key(text[i]));
        }
        if (at.size() < 4) return null;
        java.util.Map<String, Integer> count = new java.util.HashMap<>();
        for (String k : keys) if (k.length() >= 4) count.merge(k, 1, Integer::sum);
        int most = 0;
        for (int c : count.values()) most = Math.max(most, c);
        if (most < 2) return null;
        int first = -1;
        for (int i = 0; i < keys.size() && first < 0; i++) {
            Integer c = count.get(keys.get(i));
            if (c != null && c == most && at.get(i) >= 5000 && at.get(i) <= durationMs - 30_000) first = i;
        }
        if (first < 0) return null;
        int start = first;
        for (int j = first - 1; j >= Math.max(0, first - 4); j--) {
            Integer c = count.get(keys.get(j));
            if (c == null || c < 2 || c < most - 1 || at.get(j) < 5000) break;
            start = j;
        }
        return at.get(start);
    }

    private static String key(String text) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) b.appendCodePoint(Character.toLowerCase(cp));
            i += Character.charCount(cp);
        }
        return b.toString();
    }
}
