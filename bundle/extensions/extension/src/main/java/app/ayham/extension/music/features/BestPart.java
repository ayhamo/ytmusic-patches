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
}
