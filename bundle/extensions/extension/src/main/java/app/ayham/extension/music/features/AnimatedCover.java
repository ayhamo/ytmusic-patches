package app.ayham.extension.music.features;

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
 * Animated covers (BitChord's canvas, no Kawarp): a muted, looping motion clip drawn over
 * YouTube Music's own album art (player_thumbnail), the way BitChord does it, with Android's
 * MediaPlayer on a TextureView. Only one clip plays, only while the player is expanded and in
 * song mode; otherwise it is paused and hidden, and YT's art shows as usual.
 *
 * spike.properties: canvas=1 turns it on; canvasUrl=... plays that URL for every song (test);
 * canvasApple=1 looks the song up on Apple Music (AppleCanvas).
 */
final class AnimatedCover {
    private static final String TAG = "Ayham";
    private static final Handler main = new Handler(Looper.getMainLooper());

    private static TextureView view;
    private static MediaPlayer player;
    private static Surface surface;
    /** Written on the main thread, read by the lookup thread to drop a skipped song. */
    private static volatile String key = "";
    private static String playingUrl;
    private static int videoW, videoH;
    private static long lookupStart;

    private AnimatedCover() {
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
                Features.failed("canvas", t);
            }
            try {
                SongCover.tick(activity, decor, pageId, thumbId);
            } catch (Throwable t) {
                Features.failed("autoSong", t);
            }
            return true;
        });
    }

    private static int id(Activity a, String name, String pkg) {
        return a.getResources().getIdentifier(name, "id", pkg);
    }

    private static void tick(Activity activity, View decor, int pageId, int thumbId, int artistId, int titleId) {
        if (!"1".equals(Features.props().getProperty("canvas"))) {
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
        // BitChord's clip plays only while the music plays: paused, it holds its frame
        boolean music = PlayerScreen.playingFor(activity, 0) != Boolean.FALSE;
        if (!wanted || now - lastTextCheck < 500) {
            applyVisibility(wanted, music);
            return;
        }
        lastTextCheck = now;
        String title = text(page, titleId, artistId, true);
        String artist = text(page, titleId, artistId, false);
        if (title != null && artist != null && !(title + "\u0000" + artist).equals(key)) {
            key = title + "\u0000" + artist;
            onSong(title, artist);
        }
        applyVisibility(wanted, music);
    }

    private static long lastTextCheck;
    private static boolean lastWanted, prepared;

    /** The animated cover is on the screen (the fullscreen art stays up while it plays, paused or not). */
    static boolean clipShowing() {
        return view != null && view.isShown() && view.getAlpha() > 0.5f && prepared;
    }

    /** A frame of the shown clip at w x h (the background's blurred backdrop), or null. */
    static android.graphics.Bitmap frame(int w, int h) {
        if (!clipShowing() || !view.isAvailable()) return null;
        try {
            return view.getBitmap(w, h);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void applyVisibility(boolean wanted, boolean music) {
        if (player != null) {
            boolean ready = prepared; // not the first frame: a clip paused before it never shows one
            boolean run = wanted && music;
            if (run && ready && !player.isPlaying()) {
                player.start();
                Log.i(TAG, "canvas: resumed (expanded, music playing)");
            } else if (!run && player.isPlaying()) {
                player.pause();
                Log.i(TAG, "canvas: paused (" + (wanted ? "music paused" : "collapsed or video mode") + ")");
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
        if (id == null || id.equals(switchedFor) || !"1".equals(Features.props().getProperty("autoSong")) || !SongVersion.swapped(id)) return;
        // not during a mix: the toggle makes YT reload its player, so the incoming player our engine
        // aligned is replaced and the handover lands far off (heard as a cut and a jump). Clicked
        // once the mix is done, on a later tick.
        try {
            if (app.ayham.extension.music.automix.Automix.incomingId() != null) return;
        } catch (Throwable ignored) {
        }
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

    private static final java.util.concurrent.ExecutorService aheadWorker = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final java.util.Set<String> aheadAsked = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    /**
     * Prism's CanvasAhead: the next two songs' music-video loops, fetched one at a time on an
     * unmetered network, 15 s after they show up next in the queue (skipping through songs asks
     * for none), so their canvas shows from the cache as they start.
     */
    static void ahead(java.util.List<String[]> next) {
        if (cacheDir == null) return;
        for (String[] s : next) {
            if (s[0] == null || s[1] == null || !aheadAsked.add(s[0])) continue;
            main.postDelayed(() -> aheadWorker.execute(() -> {
                try {
                    android.net.ConnectivityManager cm = (android.net.ConnectivityManager) app.ayham.extension.music.automix.Automix.context()
                            .getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
                    if (cm == null || cm.isActiveNetworkMetered()) {
                        aheadAsked.remove(s[0]);
                        return;
                    }
                    String mvKey = "mv:" + s[0];
                    if (CanvasCache.lookup(mvKey) != null) return;
                    boolean[] none = {false};
                    java.io.File f = VideoCanvas.clip(s[0], s[1], s[2], cacheDir, 1080, none);
                    if (f != null || none[0]) CanvasCache.put(mvKey, f == null ? null : f.getAbsolutePath());
                    Log.i(TAG, "canvas ahead: '" + s[1] + "' " + (f != null ? "loop cached" : none[0] ? "has no video" : "not this time"));
                } catch (Throwable t) {
                    aheadAsked.remove(s[0]);
                    Log.w(TAG, "canvas ahead: '" + s[1] + "' failed: " + t);
                }
            }), 15_000);
        }
    }

    private static void onSong(String title, String artist) {
        final String forKey = key;
        // the old clip fades out over the next art (the backdrop holds its frame meanwhile), not cut
        if (view != null && player != null && view.getAlpha() > 0f) {
            MediaPlayer was = player;
            view.animate().cancel();
            view.animate().alpha(0f).setDuration(300).withEndAction(() -> {
                if (player == was) stop();
            }).start();
        } else stop();
        // the clip is sized for the art on screen (from 360p up to 1080p)
        final int artPx = view == null ? 720 : Math.max(view.getWidth(), view.getHeight());
        // full-screen art is about 3:4: Apple's tall clip fits it, the square one the normal art
        final boolean tall = FullArt.on();
        Properties p = Features.props();
        String fixed = p.getProperty("canvasUrl", "").trim();
        boolean apple = "1".equals(p.getProperty("canvasApple"));
        boolean video = !"0".equals(p.getProperty("canvasVideo"));
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
            String songKey = title + "|" + artist + (tall ? "|tall" : "");
            String saved = null, vid = null;
            if (fixed.isEmpty()) {
                // a downloaded song's clip by its video id first (works offline)
                try {
                    vid = app.ayham.extension.music.automix.Automix.currentId();
                } catch (Throwable ignored) {
                }
                if (vid != null) saved = CanvasCache.lookup(CanvasCache.idKey(vid));
            }
            if (saved != null && !saved.isEmpty()) {
                // saved lookup: a miss for 24 h, or the clip file (also offline)
                Log.i(TAG, "canvas: saved lookup: " + (saved.isEmpty() ? "no clip" : "clip " + new java.io.File(saved).getName()));
                final String clip = saved;
                if (!clip.isEmpty()) main.post(() -> {
                    if (forKey.equals(key)) play(clip);
                });
                return;
            }
            // Metrolist's source order: the song's music video first, then Apple's animated cover
            if (fixed.isEmpty() && video && vid != null) {
                String mvKey = "mv:" + vid, had = CanvasCache.lookup(mvKey);
                if (had != null && !had.isEmpty()) {
                    Log.i(TAG, "canvas: video loop from cache");
                    main.post(() -> {
                        if (forKey.equals(key)) play(had);
                    });
                    return;
                }
                if (had == null) {
                    try {
                        boolean[] none = {false};
                        java.io.File f = VideoCanvas.clip(vid, title, artist, cacheDir, artPx, none);
                        if (f != null || none[0]) CanvasCache.put(mvKey, f == null ? null : f.getAbsolutePath());
                        if (f != null) {
                            String path = f.getAbsolutePath();
                            Log.i(TAG, "canvas: video loop in " + (System.currentTimeMillis() - lookupStart) + " ms");
                            main.post(() -> {
                                if (forKey.equals(key)) play(path);
                            });
                            return;
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "canvas video: failed: " + t);
                    }
                    if (!forKey.equals(key)) return;
                }
            }
            // then Apple's, saved by title and artist (a miss for 24 h, or the clip file)
            if (fixed.isEmpty()) {
                String hadApple = CanvasCache.lookup(songKey);
                if (hadApple != null) {
                    Log.i(TAG, "canvas: saved lookup: " + (hadApple.isEmpty() ? "no clip" : "clip " + new java.io.File(hadApple).getName()));
                    if (!hadApple.isEmpty()) main.post(() -> {
                        if (forKey.equals(key)) play(hadApple);
                    });
                    return;
                }
            }
            String url = null;
            String alt = null;
            boolean failed = false;
            try {
                if (!fixed.isEmpty()) url = fixed;
                else if (apple) {
                    String[] found = AppleCanvas.find(title, artist, tall);
                    if (found != null) {
                        url = found[0];
                        alt = found[1];
                    }
                }
            } catch (Throwable t) {
                failed = true;
                Log.w(TAG, "canvas: lookup failed: " + t);
            }
            if (url != null && url.contains(".m3u8") && forKey.equals(key)) {
                // the clip for the art's shape, else the other shape's (Prism's tall alternate)
                java.io.File f = null;
                for (String u : new String[]{url, alt}) {
                    if (u == null || f != null) continue;
                    try {
                        f = HlsClip.fetch(u, cacheDir, artPx);
                    } catch (Throwable t) {
                        Log.w(TAG, "canvas: clip download failed: " + t);
                    }
                }
                url = f == null ? null : f.getAbsolutePath();
                // no file is a failed download, not "no clip": not saved as a miss for 24 h
                if (f == null) failed = true;
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

    /**
     * Crop the clip into the art: inside a music video's baked-in bars, at its judged place across
     * the picture (the .crop sidecar); centred for anything else.
     */
    private static void fit() {
        if (view == null || videoW == 0 || videoH == 0 || view.getWidth() == 0) return;
        float vw = view.getWidth(), vh = view.getHeight();
        String path = playingUrl == null ? null : playingUrl.startsWith("file://") ? playingUrl.substring(7) : playingUrl;
        float[] c = VideoCanvas.cropOf(path);
        float cx = c == null ? 0.5f : c[0];
        float ax = c == null ? 0 : c[1] * videoW, ay = c == null ? 0 : c[2] * videoH;
        float aw = c == null ? videoW : videoW * (1 - c[1] - c[3]), ah = c == null ? videoH : videoH * (1 - c[2] - c[4]);
        float sx, sy, sw, sh;
        if (aw / ah > vw / vh) {
            sh = ah;
            sw = ah * vw / vh;
            sx = ax + cx * (aw - sw);
            sy = ay;
        } else {
            sw = aw;
            sh = aw * vh / vw;
            sx = ax;
            sy = ay + (ah - sh) / 2;
        }
        // the view first stretches the whole frame over itself; map the chosen part onto the view
        android.graphics.RectF src = new android.graphics.RectF(sx * vw / videoW, sy * vh / videoH, (sx + sw) * vw / videoW, (sy + sh) * vh / videoH);
        Matrix m = new Matrix();
        m.setRectToRect(src, new android.graphics.RectF(0, 0, vw, vh), Matrix.ScaleToFit.FILL);
        view.setTransform(m);
    }
}
