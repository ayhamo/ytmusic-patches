package app.ayham.extension.music.spike;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Square song cover for a swapped music video. When the song version plays in place of a music
 * video, YT still shows the video's 16:9 thumbnail letterboxed in the square art. The song's own
 * thumbnail holds its square cover between dark bars: those are trimmed and the cover is drawn
 * over YT's art (YT's art stays underneath; the animated art still goes on top). Follows the
 * song-version switch.
 */
final class SpikeCover {
    private static final String TAG = "AyhamSpike";
    private static final Handler main = new Handler(Looper.getMainLooper());
    /** The last few covers only (main thread): each is a few MB, and a long session swaps many videos. */
    private static final Map<String, Bitmap> covers = new LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
            return size() > 6;
        }
    };
    private static ImageView view;
    private static String shownFor = "", wantedFor = "";

    private SpikeCover() {
    }

    static void tick(Activity a, View decor, int pageId, int thumbId) {
        boolean on = "1".equals(SpikeA.props().getProperty("autoSong"));
        View page = decor.findViewById(pageId);
        View thumb = page == null ? null : page.findViewById(thumbId);
        if (thumb == null || !(thumb.getParent() instanceof ViewGroup)) return;
        ViewGroup host = (ViewGroup) thumb.getParent();
        if (view == null || view.getParent() != host) {
            if (view != null && view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
            view = new ImageView(a);
            view.setScaleType(ImageView.ScaleType.CENTER_CROP);
            view.setVisibility(View.INVISIBLE);
            host.addView(view, new ViewGroup.LayoutParams(thumb.getWidth(), thumb.getHeight()));
            shownFor = "";
        }
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp.width != thumb.getWidth() || lp.height != thumb.getHeight()) {
            lp.width = thumb.getWidth();
            lp.height = thumb.getHeight();
            view.setLayoutParams(lp);
        }
        if (view.getX() != thumb.getX()) view.setX(thumb.getX());
        if (view.getY() != thumb.getY()) view.setY(thumb.getY());
        if (view.getZ() != thumb.getZ() + 0.5f) view.setZ(thumb.getZ() + 0.5f);

        String id = null;
        try {
            id = app.ayham.extension.music.automix.Automix.currentId();
        } catch (Throwable ignored) {
        }
        boolean swapped = on && id != null && SongVersion.swapped(id);
        if (swapped && !id.equals(wantedFor)) {
            wantedFor = id;
            String video = id;
            new Thread(() -> load(video), "ayham-cover").start();
        }
        Bitmap cover = swapped ? covers.get(id) : null;
        if (cover != null && !id.equals(shownFor)) {
            shownFor = id;
            view.setImageBitmap(cover);
        }
        int vis = cover != null && thumb.isShown() && thumb.getAlpha() > 0.5f ? View.VISIBLE : View.INVISIBLE;
        if (view.getVisibility() != vis) view.setVisibility(vis);
    }

    private static void load(String video) {
        try {
            String song = SongVersion.songFor(video);
            if (song == null) return;
            Bitmap b = null;
            for (String name : new String[]{"maxresdefault", "sddefault", "hqdefault"}) {
                b = fetch("https://i.ytimg.com/vi/" + song + "/" + name + ".jpg");
                if (b != null) break;
            }
            if (b == null) {
                Log.i(TAG, "cover: no thumbnail for song " + song);
                return;
            }
            Bitmap square = square(b);
            Log.i(TAG, "cover: song " + song + " for video " + video + ": " + b.getWidth() + "x" + b.getHeight()
                    + " -> " + square.getWidth() + "x" + square.getHeight());
            main.post(() -> covers.put(video, square));
        } catch (Throwable t) {
            Log.w(TAG, "cover failed for " + video, t);
        }
    }

    private static Bitmap fetch(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        try {
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                Bitmap b = BitmapFactory.decodeStream(in);
                // YT's "no thumbnail" image is a 120x90 grey card
                return b == null || b.getWidth() <= 120 ? null : b;
            }
        } finally {
            c.disconnect();
        }
    }

    /** Trim the dark bars around the cover, then take the centre square. */
    private static Bitmap square(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int left = 0, right = w - 1, top = 0, bottom = h - 1;
        while (left < right && dark(b, left, true)) left++;
        while (right > left && dark(b, right, true)) right--;
        while (top < bottom && dark(b, top, false)) top++;
        while (bottom > top && dark(b, bottom, false)) bottom--;
        int cw = right - left + 1, ch = bottom - top + 1;
        if (cw < w / 4 || ch < h / 4) { // all dark: keep the whole picture
            left = 0;
            top = 0;
            cw = w;
            ch = h;
        }
        int side = Math.min(cw, ch);
        return Bitmap.createBitmap(b, left + (cw - side) / 2, top + (ch - side) / 2, side, side);
    }

    /** A column (or row) of nearly black pixels. */
    private static boolean dark(Bitmap b, int at, boolean column) {
        int n = column ? b.getHeight() : b.getWidth();
        long sum = 0;
        int step = Math.max(1, n / 64), k = 0;
        for (int i = 0; i < n; i += step, k++) {
            int p = column ? b.getPixel(at, i) : b.getPixel(i, at);
            sum += Color.red(p) + Color.green(p) + Color.blue(p);
        }
        return sum / (3 * k) < 14;
    }
}
