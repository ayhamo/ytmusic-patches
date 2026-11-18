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
    /** Metrolist's letterbox zoom (CanvasFrameSampler.detectLetterboxZoom), from the live frame. */
    private static float zoom = 1f;
    /** The live frame, 48 px on its longest side (Metrolist's CanvasFrameSampler), reused. */
    private static android.graphics.Bitmap sample;
    /** The last transform set, so an unchanged layout is not set again each frame. */
    private static final float[] fitWas = new float[6];

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
        fit(); // the banner moves and grows on its own: the video stays where Metrolist draws it

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

    /**
     * The clip is Apple's tall (3:4) cover: Metrolist's canvasTall layout. Only that one: a Spotify
     * canvas (9:16) or a music video fills the whole player, as in Metrolist.
     */
    static boolean tallClip() {
        if (videoW == 0) return false;
        float r = videoH / (float) videoW;
        return r > 1.2f && r < 1.45f;
    }

    /**
     * Where Metrolist draws the clip, px from the player page's top-left on a W x H screen: a tall
     * cover fills fillMaxWidth().aspectRatio(3/4) at the top; anything else fills the whole player
     * (RESIZE_MODE_ZOOM, centre-cropped). Either is scaled by the letterbox zoom about its centre.
     */
    static android.graphics.RectF videoRect(int W, int H) {
        if (videoW == 0 || videoH == 0 || W == 0 || H == 0) return null;
        float bw = W, bh = tallClip() ? W * 4f / 3f : H;
        float sc = Math.max(bw / videoW, bh / videoH) * zoom;
        float rw = videoW * sc, rh = videoH * sc, cx = bw / 2, cy = bh / 2;
        return new android.graphics.RectF(cx - rw / 2, cy - rh / 2, cx + rw / 2, cy + rh / 2);
    }

    /** Metrolist's CanvasFrameSampler.detectLetterboxZoom: the zoom that takes black bars above and below off, 1..1.6. */
    static float letterboxZoom(int[] px, int w, int h) {
        if (w < 4 || h < 4 || isBlackRow(px, w, h / 2)) return 1f;
        int top = 0;
        while (top < h / 3 && isBlackRow(px, w, top)) top++;
        int bottom = 0;
        while (h - 1 - bottom > 2 * h / 3 && isBlackRow(px, w, h - 1 - bottom)) bottom++;
        int min = Math.max(1, h / 16);
        if (top < min || bottom < min) return 1f;
        int active = h - top - bottom;
        if (active < h / 2 || active >= h) return 1f;
        return Math.max(1f, Math.min(1.6f, h / (float) active));
    }

    private static boolean isBlackRow(int[] px, int w, int y) {
        int step = Math.max(1, w / 16), sum = 0, n = 0;
        for (int x = 0; x < w; x += step, n++) {
            int c = px[y * w + x];
            sum += (((c >> 16) & 0xFF) * 77 + ((c >> 8) & 0xFF) * 150 + (c & 0xFF) * 29) >> 8;
        }
        return n > 0 && sum / n < 24;
    }

    /** A w x h size with the clip's shape, its longest side [side] px. */
    static int[] sampleSize(int side) {
        if (videoW == 0 || videoH == 0) return new int[]{side, side};
        float k = side / (float) Math.max(videoW, videoH);
        return new int[]{Math.max(1, Math.round(videoW * k)), Math.max(1, Math.round(videoH * k))};
    }

    /** Each new clip frame (Metrolist samples every one): its letterbox zoom, and the live blurred backdrop. */
    private static void sampled() {
        if (!clipShowing() || !view.isAvailable() || !FullArt.clipMode() || videoW == 0) return;
        int[] sz = sampleSize(48);
        try {
            if (sample == null || sample.getWidth() != sz[0] || sample.getHeight() != sz[1])
                sample = android.graphics.Bitmap.createBitmap(sz[0], sz[1], android.graphics.Bitmap.Config.ARGB_8888);
            view.getBitmap(sample);
        } catch (Throwable t) {
            return;
        }
        int[] px = new int[sz[0] * sz[1]];
        sample.getPixels(px, 0, sz[0], 0, 0, sz[0], sz[1]);
        float z = letterboxZoom(px, sz[0], sz[1]);
        if (Math.abs(z - zoom) > 0.03f) {
            Log.i(TAG, "canvas: letterbox zoom " + zoom + " -> " + z);
            zoom = z;
            fit();
        }
        ArtBackdrop.live(px, sz[0], sz[1], tallClip());
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
                try {
                    sampled();
                } catch (Throwable t) {
                    Features.failed("canvas frame", t);
                }
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
        boolean spotify = !"0".equals(p.getProperty("canvasSpotify"));
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
                    // only when the queue already names this song (it can lag a song behind)
                    vid = app.ayham.extension.music.automix.Automix.currentIdFor(title);
                    if (vid == null) app.ayham.extension.music.automix.Automix.refreshQueue();
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
                String mvKey = "mv:" + vid, saidMv = CanvasCache.lookup(mvKey);
                // a saved clip the cache trim has since deleted is cut again
                final String had = saidMv != null && !saidMv.isEmpty() && new java.io.File(saidMv).length() == 0 ? null : saidMv;
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
                        // still this song: a clip cut while the queue moved on is not saved under its id
                        if (!vid.equals(app.ayham.extension.music.automix.Automix.currentIdFor(title))) {
                            Log.i(TAG, "canvas: the queue moved on while cutting: not saved");
                            if (f != null && !f.delete()) Log.w(TAG, "canvas: could not delete " + f.getName());
                            return;
                        }
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
            // then Spotify's Canvas (Metrolist's order: video, Spotify, Apple), saved by title and artist
            if (fixed.isEmpty() && spotify) {
                String spKey = "sp:" + title + "|" + artist, had = CanvasCache.lookup(spKey);
                if (had != null && !had.isEmpty() && new java.io.File(had).length() > 0) {
                    Log.i(TAG, "canvas: spotify canvas from cache");
                    main.post(() -> {
                        if (forKey.equals(key)) play(had);
                    });
                    return;
                }
                if (had == null || !had.isEmpty()) {
                    try {
                        java.io.File f = SpotifyCanvas.find(title, artist, cacheDir);
                        CanvasCache.put(spKey, f == null ? null : f.getAbsolutePath());
                        if (f != null) {
                            String path = f.getAbsolutePath();
                            Log.i(TAG, "canvas: spotify canvas in " + (System.currentTimeMillis() - lookupStart) + " ms");
                            main.post(() -> {
                                if (forKey.equals(key)) play(path);
                            });
                            return;
                        }
                    } catch (Throwable t) {
                        // a failed lookup is not saved as a miss: asked again next time
                        Log.w(TAG, "canvas spotify: failed: " + t);
                    }
                    if (!forKey.equals(key)) return;
                }
            }
            // then Apple's, saved by title and artist (a miss for 24 h, or the clip file)
            if (fixed.isEmpty()) {
                String saidApple = CanvasCache.lookup(songKey);
                final String hadApple = saidApple != null && !saidApple.isEmpty() && !saidApple.contains("://")
                        && new java.io.File(saidApple).length() == 0 ? null : saidApple;
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
        zoom = 1f;
        java.util.Arrays.fill(fitWas, 0);
        prepared = false;
    }

    /**
     * Crop the clip into the art: inside a music video's baked-in bars, at its judged place across
     * the picture (the .crop sidecar); centred for anything else.
     */
    private static String cropFor, cardFit;
    private static float[] crop;

    private static void fit() {
        if (view == null || videoW == 0 || videoH == 0 || view.getWidth() == 0) return;
        float vw = view.getWidth(), vh = view.getHeight();
        if (FullArt.on() && FullArt.clipMode() && FullArt.bannerSide() > 0 && metrolistFit(vw, vh)) return;
        if (fitWas[4] != 0) cardFit = null; // coming back from the full-screen fit: set the card's again
        fitWas[4] = 0; // the card's crop: the full-screen one is set again when it comes back
        String path = playingUrl == null ? null : playingUrl.startsWith("file://") ? playingUrl.substring(7) : playingUrl;
        // runs on every pre-draw: the crop file is read once per clip, and an unchanged fit is not set
        // again (setTransform redraws, which would call this again)
        if (!java.util.Objects.equals(path, cropFor)) {
            cropFor = path;
            crop = VideoCanvas.cropOf(path);
            cardFit = null;
        }
        String fitKey = path + "|" + vw + "x" + vh + "|" + videoW + "x" + videoH;
        if (fitKey.equals(cardFit)) return;
        cardFit = fitKey;
        float[] c = crop;
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

    /**
     * Full-screen art: the clip where Metrolist draws it (videoRect), seen through the banner (our
     * view sits in YT's card, scaled up as the banner): the screen rect taken into the view's own px.
     */
    private static boolean metrolistFit(float vw, float vh) {
        View root = view.getRootView();
        android.graphics.RectF r = videoRect(root.getWidth(), root.getHeight());
        if (r == null) return false;
        float s = 1;
        for (View v = view; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) s *= v.getScaleX();
        if (s <= 0) return false;
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        float top = FullArt.pageTop();
        float l = (r.left - at[0]) / s, t = (r.top + top - at[1]) / s, w = r.width() / s, h = r.height() / s;
        if (Math.abs(l - fitWas[0]) < 0.5f && Math.abs(t - fitWas[1]) < 0.5f && Math.abs(w - fitWas[2]) < 0.5f
                && Math.abs(h - fitWas[3]) < 0.5f && fitWas[4] == vw && fitWas[5] == vh) return true;
        fitWas[0] = l;
        fitWas[1] = t;
        fitWas[2] = w;
        fitWas[3] = h;
        fitWas[4] = vw;
        fitWas[5] = vh;
        Matrix m = new Matrix();
        m.setRectToRect(new android.graphics.RectF(0, 0, vw, vh), new android.graphics.RectF(l, t, l + w, t + h), Matrix.ScaleToFit.FILL);
        view.setTransform(m);
        view.invalidate();
        return true;
    }
}
