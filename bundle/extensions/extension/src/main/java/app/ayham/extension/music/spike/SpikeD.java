package app.ayham.extension.music.spike;

import android.app.Activity;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.Properties;

/**
 * Throwaway spike, item 1 (simple canvas, no Kawarp): a muted, looping motion clip drawn over
 * YouTube Music's own album art (player_thumbnail), the way BitChord does it, with Android's
 * MediaPlayer on a TextureView. Only one clip plays, only while the player is expanded and in
 * song mode; otherwise it is paused and hidden, and YT's art shows as usual.
 *
 * spike.properties: canvas=1 turns it on; canvasUrl=... plays that URL for every song (test);
 * canvasApple=1 looks the song up on Apple Music (AppleCanvas).
 */
final class SpikeD {
    private static final String TAG = "AyhamSpike";
    private static final Handler main = new Handler(Looper.getMainLooper());

    private static TextureView view;
    private static MediaPlayer player;
    private static Surface surface;
    /** Written on the main thread, read by the lookup thread to drop a skipped song. */
    private static volatile String key = "";
    private static String playingUrl;
    private static int videoW, videoH;
    private static long lookupStart;

    private SpikeD() {
    }

    private static java.io.File cacheDir;
    private static final long SETTLE_MS = 700;

    static void attach(Activity activity) {
        cacheDir = activity.getCacheDir();
        CanvasCache.init(activity.getFilesDir());
        final View decor = activity.getWindow().getDecorView();
        final String pkg = activity.getPackageName();
        final int pageId = id(activity, "player_page", pkg);
        final int thumbId = id(activity, "player_thumbnail", pkg);
        final int artistId = id(activity, "artist", pkg);
        final int titleId = id(activity, "title", pkg);
        Log.i(TAG, "canvas: ids thumb=" + thumbId + " artist=" + artistId);

        decor.getViewTreeObserver().addOnPreDrawListener(() -> {
            try {
                tick(activity, decor, pageId, thumbId, artistId, titleId);
            } catch (Throwable t) {
                SpikeA.failed("canvas", t);
            }
            try {
                SpikeCover.tick(activity, decor, pageId, thumbId);
            } catch (Throwable t) {
                SpikeA.failed("autoSong", t);
            }
            return true;
        });
    }

    private static int id(Activity a, String name, String pkg) {
        return a.getResources().getIdentifier(name, "id", pkg);
    }

    private static void tick(Activity activity, View decor, int pageId, int thumbId, int artistId, int titleId) {
        if (!"1".equals(SpikeA.props().getProperty("canvas"))) {
            // switched off in the settings: drop the clip and show YT's art
            if (!key.isEmpty() || player != null) {
                key = "";
                stop();
                if (view != null) view.setVisibility(View.GONE);
                Log.i(TAG, "canvas: switched off");
            }
            return;
        }
        View page = decor.findViewById(pageId);
        View thumb = page == null ? null : page.findViewById(thumbId);
        if (thumb == null || !(thumb.getParent() instanceof ViewGroup)) return;
        ViewGroup host = (ViewGroup) thumb.getParent();

        if (view == null || view.getParent() != host) {
            if (view != null && view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
            // the view of a destroyed activity is not moved into the new one (it would keep the old
            // activity alive): a new view, and its clip starts again (from the saved lookup) so it
            // gets a first frame
            if (view != null && view.getContext() != activity && view.getContext() instanceof Activity
                    && (((Activity) view.getContext()).isDestroyed() || ((Activity) view.getContext()).isFinishing())) {
                stop();
                key = "";
                view = null;
            }
            if (view == null) view = newView(activity);
            host.addView(view, new ViewGroup.LayoutParams(thumb.getWidth(), thumb.getHeight()));
            Log.i(TAG, "canvas: view added over player_thumbnail in " + host.getClass().getName());
        }
        // Follow the art's size and position (it animates between the expanded and mini player).
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp.width != thumb.getWidth() || lp.height != thumb.getHeight()) {
            lp.width = thumb.getWidth();
            lp.height = thumb.getHeight();
            view.setLayoutParams(lp);
            fit();
        }
        if (view.getX() != thumb.getX()) view.setX(thumb.getX());
        if (view.getY() != thumb.getY()) view.setY(thumb.getY());
        view.setZ(thumb.getZ() + 1);

        // Song mode and expanded only; in video mode the thumbnail is hidden.
        int[] at = new int[2];
        page.getLocationOnScreen(at);
        boolean wanted = thumb.isShown() && thumb.getAlpha() > 0.5f && at[1] < decor.getHeight() / 2;
        if (!wanted && at[1] < decor.getHeight() / 2) songMode(activity, decor);

        long now = System.currentTimeMillis();
        if (wanted != lastWanted) {
            lastWanted = wanted;
            View a = shown(page, artistId);
            Log.i(TAG, "canvas state: wanted=" + wanted + " thumbShown=" + thumb.isShown() + " alpha=" + thumb.getAlpha()
                    + " pageY=" + at[1] + " artist=" + (a == null ? null : ((TextView) a).getText())
                    + " title=" + text(page, titleId, artistId, true));
        }
        if (!wanted || now - lastTextCheck < 500) {
            applyVisibility(wanted);
            return;
        }
        lastTextCheck = now;
        String title = text(page, titleId, artistId, true);
        String artist = text(page, titleId, artistId, false);
        if (title != null && artist != null && !(title + "\u0000" + artist).equals(key)) {
            key = title + "\u0000" + artist;
            onSong(title, artist);
        }
        applyVisibility(wanted);
    }

    private static long lastTextCheck;
    private static boolean lastWanted, prepared;

    private static void applyVisibility(boolean wanted) {
        if (player != null) {
            boolean ready = prepared; // not the first frame: a clip paused before it never shows one
            if (wanted && ready && !player.isPlaying()) {
                player.start();
                Log.i(TAG, "canvas: resumed (expanded)");
            } else if (!wanted && player.isPlaying()) {
                player.pause();
                Log.i(TAG, "canvas: paused (collapsed or video mode)");
            }
        }
        int vis = wanted ? View.VISIBLE : View.INVISIBLE;
        if (view.getVisibility() != vis) view.setVisibility(vis);
    }

    /** The player page's title and artist, found next to each other (the queue has titles too). */
    private static String text(View page, int titleId, int artistId, boolean wantTitle) {
        View artist = shown(page, artistId);
        if (!(artist instanceof TextView) || !(artist.getParent() instanceof ViewGroup)) return null;
        if (!wantTitle) return ((TextView) artist).getText().toString();
        View parent = (View) artist.getParent();
        for (int up = 0; up < 4 && parent instanceof ViewGroup; up++, parent = (View) parent.getParent()) {
            View title = shown(parent, titleId);
            if (title != null) return ((TextView) title).getText().toString();
        }
        return null;
    }

    private static View shown(View v, int id) {
        if (v.getId() == id && v.isShown() && v instanceof TextView && ((TextView) v).length() > 0) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = shown(g.getChildAt(i), id);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static TextureView newView(Activity activity) {
        TextureView v = new TextureView(activity);
        v.setAlpha(0f);
        v.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                surface = new Surface(st);
                if (player != null) player.setSurface(surface);
                fit();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                fit();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                if (player != null) player.setSurface(null);
                if (surface != null) surface.release();
                surface = null;
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) {
            }
        });
        return v;
    }

    private static String switchedFor = "";

    /**
     * The song version replaced a music video, but YT still shows its video page (black: the song
     * has no picture). Flip YT's song/video toggle to song once for that video, so the art and the
     * animated art show. Tapping back to video afterwards is left alone.
     */
    private static void songMode(Activity activity, View decor) {
        String id;
        try {
            id = app.ayham.extension.music.automix.Automix.currentId();
        } catch (Throwable t) {
            return;
        }
        if (id == null || id.equals(switchedFor) || !"1".equals(SpikeA.props().getProperty("autoSong")) || !SongVersion.swapped(id)) return;
        // the page has two copies of the toggle (one hidden): the one on screen
        View toggle = shownWithId(decor, id(activity, "audio_video_switch_toggle", activity.getPackageName()));
        if (toggle == null) return;
        switchedFor = id;
        Log.i(TAG, "song version playing in video mode: switching YT to song mode, click=" + toggle.performClick());
    }

    private static View shownWithId(View v, int id) {
        if (v.getId() == id && v.isShown()) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View f = shownWithId(g.getChildAt(i), id);
                if (f != null) return f;
            }
        }
        return null;
    }

    private static void onSong(String title, String artist) {
        stop();
        final String forKey = key;
        // the clip is sized for the art on screen (from 360p up to 1080p)
        final int artPx = view == null ? 720 : Math.max(view.getWidth(), view.getHeight());
        Properties p = SpikeA.props();
        String fixed = p.getProperty("canvasUrl", "").trim();
        boolean apple = "1".equals(p.getProperty("canvasApple"));
        lookupStart = System.currentTimeMillis();
        Log.i(TAG, "canvas: song '" + title + "' by '" + artist + "'");
        new Thread(() -> {
            // skipping through songs: only look up the one that stays for a moment
            try {
                Thread.sleep(SETTLE_MS);
            } catch (InterruptedException ignored) {
            }
            if (!forKey.equals(key)) {
                Log.i(TAG, "canvas: '" + title + "' skipped before lookup");
                return;
            }
            String songKey = title + "|" + artist;
            String saved = null;
            if (fixed.isEmpty()) {
                // a downloaded song's clip by its video id first (works offline), then by title and artist
                String vid = null;
                try {
                    vid = app.ayham.extension.music.automix.Automix.currentId();
                } catch (Throwable ignored) {
                }
                if (vid != null) saved = CanvasCache.lookup(CanvasCache.idKey(vid));
                if (saved == null) saved = CanvasCache.lookup(songKey);
            }
            if (saved != null) {
                // saved lookup: a miss for 24 h, or the clip file (also offline)
                Log.i(TAG, "canvas: saved lookup: " + (saved.isEmpty() ? "no clip" : "clip " + new java.io.File(saved).getName()));
                final String clip = saved;
                if (!clip.isEmpty()) main.post(() -> {
                    if (forKey.equals(key)) play(clip);
                });
                return;
            }
            String url = null;
            boolean failed = false;
            try {
                if (!fixed.isEmpty()) url = fixed;
                else if (apple) url = AppleCanvas.find(title, artist);
            } catch (Throwable t) {
                failed = true;
                Log.w(TAG, "canvas: lookup failed: " + t);
            }
            if (url != null && url.contains(".m3u8") && forKey.equals(key)) {
                try {
                    java.io.File f = HlsClip.fetch(url, cacheDir, artPx);
                    url = f == null ? null : f.getAbsolutePath();
                    // no file is a failed download, not "no clip": not saved as a miss for 24 h
                    if (f == null) failed = true;
                } catch (Throwable t) {
                    Log.w(TAG, "canvas: clip download failed: " + t);
                    url = null;
                    failed = true;
                }
            }
            if (!failed && fixed.isEmpty() && apple && (url == null || !url.contains(".m3u8"))) CanvasCache.put(songKey, url);
            final String found = url;
            Log.i(TAG, "canvas: lookup " + (found == null ? "none" : "hit") + " in "
                    + (System.currentTimeMillis() - lookupStart) + " ms");
            if (found != null) main.post(() -> {
                if (forKey.equals(key)) play(found);
            });
        }, "ayham-canvas").start();
    }

    private static void play(String url) {
        stop();
        playingUrl = url;
        final long start = System.currentTimeMillis();
        MediaPlayer mp = new MediaPlayer();
        player = mp;
        try {
            mp.setDataSource(url);
            mp.setVolume(0f, 0f);
            mp.setLooping(true);
            if (surface != null) mp.setSurface(surface);
            mp.setOnVideoSizeChangedListener((m, w, h) -> {
                videoW = w;
                videoH = h;
                Log.i(TAG, "canvas: video " + w + "x" + h);
                fit();
            });
            mp.setOnInfoListener((m, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && m == player) {
                    Log.i(TAG, "canvas: first frame " + (System.currentTimeMillis() - start) + " ms after play, "
                            + (System.currentTimeMillis() - lookupStart) + " ms after song change");
                    view.animate().alpha(1f).setDuration(400).start();
                }
                return false;
            });
            mp.setOnErrorListener((m, what, extra) -> {
                Log.w(TAG, "canvas: player error " + what + "/" + extra + " for " + playingUrl);
                if (m == player) stop();
                return true;
            });
            mp.setOnPreparedListener(m -> {
                if (m != player) return;
                Log.i(TAG, "canvas: prepared in " + (System.currentTimeMillis() - start) + " ms");
                prepared = true;
                m.start();
            });
            mp.prepareAsync();
        } catch (Exception e) {
            Log.w(TAG, "canvas: play failed", e);
            stop();
        }
    }

    private static void stop() {
        if (view != null) {
            view.animate().cancel();
            view.setAlpha(0f);
        }
        if (player != null) {
            MediaPlayer mp = player;
            player = null;
            try {
                mp.release();
            } catch (Exception ignored) {
            }
        }
        videoW = videoH = 0;
        prepared = false;
    }

    /** Center-crop the clip into the square art. */
    private static void fit() {
        if (view == null || videoW == 0 || videoH == 0 || view.getWidth() == 0) return;
        float vw = view.getWidth(), vh = view.getHeight();
        float scale = Math.max(vw / videoW, vh / videoH);
        Matrix m = new Matrix();
        m.setScale(videoW * scale / vw, videoH * scale / vh, vw / 2, vh / 2);
        view.setTransform(m);
    }
}
