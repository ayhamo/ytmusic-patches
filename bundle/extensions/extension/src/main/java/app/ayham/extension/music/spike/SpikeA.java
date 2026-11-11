package app.ayham.extension.music.spike;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Objects;
import java.util.Properties;

/**
 * Throwaway spike, batch A. Everything is driven by a properties file the test writes with adb:
 * {@code <external files dir>/spike.properties}, read fresh on each use.
 * <pre>
 * fadeMs=8000            item 2: override Morphe's crossfade length (and so its trigger point)
 * seekMs=30000           item 3: seek the incoming crossfade player here
 * speed=1.06             item 3: incoming player speed for 8 s, then back to 1.0
 * map.VIDEOID=SONGID     item 8: play SONGID's streams for VIDEOID
 * hide=1                 item 7: hide the player's action bar (chips row)
 * </pre>
 */
@SuppressWarnings("unused")
public final class SpikeA {
    private static final String TAG = "AyhamSpike";
    private static final String MORPHE_RESOLVER =
            "app.morphe.extension.shared.spoof.requests.StreamingDataRequest$VideoIdResolver";
    private static final String MORPHE_STREAMING =
            "app.morphe.extension.shared.spoof.requests.StreamingDataRequest";
    private static final String MORPHE_LYRICS =
            "app.morphe.extension.music.patches.lyrics.LyricsManager";

    private static final Handler main = new Handler(Looper.getMainLooper());
    static volatile Context appContext;
    private static boolean started;

    private SpikeA() {
    }

    private static Properties cachedProps = new Properties();
    private static long cachedAt;

    /** The settings changed: the next read is fresh. */
    static synchronized void invalidate() {
        cachedAt = 0;
    }

    static synchronized Properties props() {
        long now = System.currentTimeMillis();
        if (now - cachedAt < 2000) return cachedProps;
        cachedAt = now;
        cachedProps = readProps();
        return cachedProps;
    }

    /** The feature switches in Morphe's settings ("Ayham features"), all on by default. */
    static final String[] SWITCHES = {"ui", "artistArt", "canvas", "lyricLine", "stats", "autoSong", "native", "eq"};

    /**
     * The features' configuration: the settings switches (Morphe's settings screen, "Ayham
     * features", on unless switched off), then {@code spike.properties} in the external files dir
     * if present, a developer override for tests.
     */
    private static Properties readProps() {
        Properties p = new Properties();
        p.setProperty("offlineAudio", "251");
        p.setProperty("playlistRoute", "app");
        Context ctx = appContext;
        if (ctx == null) return p;
        try {
            android.content.SharedPreferences sp = ctx.getSharedPreferences("morphe_prefs", Context.MODE_PRIVATE);
            for (String key : SWITCHES) {
                boolean on = sp.getBoolean("ayham_" + key, !key.equals("eq"));
                switch (key) {
                    case "ui":
                        if (on) p.setProperty("ui", "bitchord");
                        break;
                    case "eq":
                        if (on) p.setProperty("eq", sp.getString("ayham_eq_preset", "bass"));
                        break;
                    case "canvas":
                        p.setProperty("canvas", on ? "1" : "0");
                        if (on) p.setProperty("canvasApple", "1");
                        break;
                    default:
                        p.setProperty(key, on ? "1" : "0");
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "settings read failed", e);
        }
        File f = new File(ctx.getExternalFilesDir(null), "spike.properties");
        if (f.exists()) {
            try (FileInputStream in = new FileInputStream(f)) {
                Properties dev = new Properties();
                dev.load(in);
                p.putAll(dev);
            } catch (Exception e) {
                Log.w(TAG, "props read failed", e);
            }
        }
        for (String key : broken) {
            if (key.equals("ui")) p.remove("ui");
            else if (key.equals("eq")) p.remove("eq");
            else p.setProperty(key, "0");
        }
        return p;
    }

    /** Features turned off for this run because their hook kept failing. */
    private static final java.util.Set<String> broken = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private static final java.util.Map<String, Integer> failures = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * A feature's hook threw. After 5 failures the feature is off until the app restarts (YT's own
     * screen back instead of a crash or a broken page), logged and told once.
     */
    public static void failed(String key, Throwable t) {
        int n = failures.merge(key, 1, Integer::sum);
        Log.e(TAG, "feature '" + key + "' failed (" + n + "/5)", t);
        if (n < 5 || !broken.add(key)) return;
        invalidate(); // under the props lock: hooks fail on other threads too
        Log.e(TAG, "feature '" + key + "' switched off for this run after 5 failures");
        Context ctx = appContext;
        if (ctx != null) new Handler(Looper.getMainLooper()).post(() -> android.widget.Toast.makeText(ctx,
                "Ayham: '" + key + "' kept failing and is off until restart", android.widget.Toast.LENGTH_LONG).show());
    }

    private static long longProp(String key) {
        try {
            return Long.parseLong(props().getProperty(key, "0").trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** Injection point: MusicActivity.onCreate. */
    public static void onActivityCreate(Activity activity) {
        try {
            appContext = activity.getApplicationContext();
            Log.i(TAG, "onActivityCreate " + activity.getClass().getName()
                    + " props=" + new File(appContext.getExternalFilesDir(null), "spike.properties"));
            if (!started) {
                started = true;
                checkMorpheReachable();
                rewrapCurrentResolver();
                startLyricsPoll();
                SpikeEq.register(appContext);
                if ("1".equals(readProps().getProperty("batchB"))) {
                    SpikeB.start(appContext, readProps().getProperty("batchBVideo"));
                }
            }
            app.ayham.extension.music.automix.Automix.onActivityCreate(activity);
            hookPlayerPage(activity);
            SpikeD.attach(activity); // follows the Animated art switch live
        } catch (Throwable t) {
            Log.e(TAG, "onActivityCreate failed", t);
        }
    }

    // ---- Item 2: Morphe crossfade hooks ----

    private static int lastFadeLogged = -1;

    /** Injection point: Morphe CrossfadePatch.getCrossfadeDurationMs(). */
    public static int overrideCrossfadeDurationMs(int morpheMs) {
        int result = morpheMs;
        try {
            long fade = longProp("fadeMs");
            if (fade > 0) result = (int) fade;
            long blend = app.ayham.extension.music.automix.Automix.crossfadeMs();
            if (blend > 0) result = (int) blend;
        } catch (Throwable t) {
            Log.e(TAG, "overrideCrossfadeDurationMs failed", t);
        }
        if (result != lastFadeLogged) {
            lastFadeLogged = result;
            Log.i(TAG, "crossfade duration read: morphe=" + morpheMs + " ours=" + result);
        }
        return result;
    }

    // ---- Items 2 + 3: incoming player ----

    /** Injection point: Morphe CrossfadePatch.onPendingPlayerReady(ExoPlayerAccess). */
    public static void onIncomingPlayerReady(Object player) {
        try {
            Log.i(TAG, "incoming player ready: " + player.getClass().getName()
                    + " seekAccess=" + (player instanceof SeekAccess)
                    + " pos=" + position(player));
            if (app.ayham.extension.music.automix.Automix.onIncomingPlayerReady(player)) return;
            Properties p = props();
            long seekMs = Long.parseLong(p.getProperty("seekMs", "0").trim());
            float speed = Float.parseFloat(p.getProperty("speed", "1").trim());

            if (seekMs > 0 && player instanceof SeekAccess) {
                ((SeekAccess) player).ayham_seekTo(seekMs);
                Log.i(TAG, "seeked incoming to " + seekMs + ", pos now=" + position(player));
                main.postDelayed(() -> Log.i(TAG, "incoming pos +1s=" + position(player)), 1000);
            }
            if (speed != 1f) {
                setSpeed(player, speed);
                Log.i(TAG, "incoming speed set to " + speed);
                main.postDelayed(() -> {
                    setSpeed(player, 1f);
                    Log.i(TAG, "incoming speed back to 1.0, pos=" + position(player));
                }, 8000);
            }
        } catch (Throwable t) {
            Log.e(TAG, "onIncomingPlayerReady failed", t);
        }
    }

    private static long position(Object player) {
        try {
            return (long) player.getClass().getMethod("patch_getCurrentPosition").invoke(player);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static void setSpeed(Object player, float speed) {
        try {
            // Added to the same class by Morphe's playback speed patch.
            player.getClass().getMethod("patch_setPlaybackParameters", float.class, float.class)
                    .invoke(player, speed, 1f);
        } catch (Throwable t) {
            Log.e(TAG, "setSpeed failed", t);
        }
    }

    // ---- Item 6: calling Morphe classes ----

    private static void checkMorpheReachable() {
        try {
            Class<?> sdr = Class.forName(MORPHE_STREAMING);
            boolean dl = false;
            for (Method m : sdr.getMethods()) {
                if (m.getName().equals("fetchRequestForDownload")) dl = true;
            }
            Log.i(TAG, "Morphe StreamingDataRequest reachable, fetchRequestForDownload=" + dl);
        } catch (Throwable t) {
            Log.e(TAG, "StreamingDataRequest not reachable", t);
        }
        try {
            Class.forName(MORPHE_LYRICS);
            Log.i(TAG, "Morphe LyricsManager reachable");
        } catch (Throwable t) {
            Log.e(TAG, "LyricsManager not reachable", t);
        }
    }

    private static String lastLine;

    private static void startLyricsPoll() {
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    Class<?> lm = Class.forName(MORPHE_LYRICS);
                    Object inst = lm.getMethod("getInstance").invoke(null);
                    String line = (String) lm.getMethod("getCurrentLineText").invoke(inst);
                    if (!Objects.equals(line, lastLine)) {
                        lastLine = line;
                        Log.i(TAG, "lyric line: " + line);
                        renderOverlay();
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "lyrics poll failed", t);
                    return;
                }
                main.postDelayed(this, 500);
            }
        }, 500);
    }

    // ---- Item 8: song version, wrapping Morphe's resolver ----

    /** The queue's next video: find its song version now, so the swap costs nothing when it starts. */
    /** For automix: the extension's music-video rule (no loop mix over a video's own intro/outro). */
    public static boolean isMusicVideo(String videoId) {
        return videoId != null && SongVersion.musicVideo(videoId);
    }

    public static void lookAhead(String videoId) {
        if (videoId == null || !"1".equals(props().getProperty("autoSong"))) return;
        SongVersion.prefetch(videoId);
    }

    private static final class OurResolver implements InvocationHandler {
        final Object inner;

        OurResolver(Object inner) {
            this.inner = inner;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "resolveVideoIdToFetch": {
                    String videoId = (String) args[0];
                    String result = videoId;
                    if (inner != null) {
                        result = (String) method.invoke(inner, videoId);
                    }
                    String morphe = result;
                    if (Objects.equals(result, videoId)) {
                        String mapped = props().getProperty("map." + videoId);
                        if (mapped != null && !mapped.trim().isEmpty()) result = mapped.trim();
                        else if ("1".equals(props().getProperty("autoSong"))) {
                            String song = SongVersion.songFor(videoId);
                            if (song != null) result = song;
                        }
                    }
                    if ("1".equals(props().getProperty("autoSong"))) {
                        try {
                            app.ayham.extension.music.automix.Automix.refreshQueue();
                        } catch (Throwable ignored) {
                        }
                    }
                    Log.i(TAG, "resolve " + videoId + " -> morphe=" + morphe + " final=" + result);
                    return result;
                }
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                case "toString":
                    return "AyhamResolver(" + inner + ")";
                default:
                    return null;
            }
        }
    }

    /** Injection point: Morphe StreamingDataRequest.setVideoIdResolver(resolver). */
    public static Object wrapResolver(Object morpheResolver) {
        try {
            if (morpheResolver != null && Proxy.isProxyClass(morpheResolver.getClass())
                    && Proxy.getInvocationHandler(morpheResolver) instanceof OurResolver) {
                return morpheResolver;
            }
            Class<?> type = Class.forName(MORPHE_RESOLVER);
            Object proxy = Proxy.newProxyInstance(SpikeA.class.getClassLoader(),
                    new Class<?>[]{type}, new OurResolver(morpheResolver));
            Log.i(TAG, "wrapped resolver, inner=" + morpheResolver);
            return proxy;
        } catch (Throwable t) {
            Log.e(TAG, "wrapResolver failed", t);
            return morpheResolver;
        }
    }

    /** Puts our wrapper in place even if Morphe set its resolver before we were loaded, or never. */
    private static void rewrapCurrentResolver() {
        try {
            Class<?> sdr = Class.forName(MORPHE_STREAMING);
            Field f = sdr.getDeclaredField("videoIdResolver");
            f.setAccessible(true);
            Object current = f.get(null);
            sdr.getMethod("setVideoIdResolver", Class.forName(MORPHE_RESOLVER)).invoke(null, current);
        } catch (Throwable t) {
            Log.e(TAG, "rewrapCurrentResolver failed", t);
        }
        try {
            Class<?> spoof = Class.forName(MORPHE_SPOOF);
            Field f = spoof.getDeclaredField("videoLengthResolver");
            f.setAccessible(true);
            Object current = f.get(null);
            spoof.getMethod("setVideoLengthResolver", Class.forName(MORPHE_LENGTH_RESOLVER)).invoke(null, current);
        } catch (Throwable t) {
            Log.e(TAG, "rewrap length resolver failed", t);
        }
    }

    // ---- Item 8 rough edge: the length of the song served under the video ----

    private static final String MORPHE_SPOOF = "app.morphe.extension.shared.spoof.SpoofVideoStreamsPatch";
    private static final String MORPHE_LENGTH_RESOLVER = MORPHE_SPOOF + "$VideoLengthResolver";

    private static final class OurLengthResolver implements InvocationHandler {
        final Object inner;

        OurLengthResolver(Object inner) {
            this.inner = inner;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "getVideoLengthSeconds": {
                    String videoId = (String) args[0];
                    long morphe = inner == null ? 0 : (long) method.invoke(inner, videoId);
                    long ours = morphe > 0 ? morphe : SongVersion.lengthFor(videoId);
                    if (ours > 0) Log.i(TAG, "length " + videoId + " -> morphe=" + morphe + " final=" + ours);
                    return ours;
                }
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                case "toString":
                    return "AyhamLengthResolver(" + inner + ")";
                default:
                    return null;
            }
        }
    }

    /** Injection point: Morphe SpoofVideoStreamsPatch.setVideoLengthResolver(resolver). */
    public static Object wrapLengthResolver(Object morpheResolver) {
        try {
            if (morpheResolver != null && Proxy.isProxyClass(morpheResolver.getClass())
                    && Proxy.getInvocationHandler(morpheResolver) instanceof OurLengthResolver) {
                return morpheResolver;
            }
            Object proxy = Proxy.newProxyInstance(SpikeA.class.getClassLoader(),
                    new Class<?>[]{Class.forName(MORPHE_LENGTH_RESOLVER)}, new OurLengthResolver(morpheResolver));
            Log.i(TAG, "wrapped length resolver, inner=" + morpheResolver);
            return proxy;
        } catch (Throwable t) {
            Log.e(TAG, "wrapLengthResolver failed", t);
            return morpheResolver;
        }
    }

    // ---- Item 7: player page hook ----

    private static int rehides;
    private static volatile TextView overlayView;

    private static volatile String statsText;

    /** Spike E: shows the stats line in the test overlay. */
    static void setOverlayText(String text) {
        statsText = text;
        renderOverlay();
    }

    /** POC overlay: stats line, then the current lyric line from Morphe's lyrics. */
    private static void renderOverlay() {
        TextView tv = overlayView;
        if (tv == null) return;
        Properties p = props();
        String stats = currentStats(), mix = currentAutomixStats();
        String line = "0".equals(p.getProperty("lyricLine")) ? null : lastLine;
        StringBuilder text = new StringBuilder(stats == null ? "" : stats);
        if (mix != null && !mix.isEmpty()) text.append(text.length() > 0 ? "\n" : "").append(mix);
        if (line != null && !line.trim().isEmpty()) text.append(text.length() > 0 ? "\n" : "").append(line.trim());
        main.post(() -> tv.setText(text.toString()));
    }

    private static boolean held;
    private static String heldLine;

    /**
     * The lyric line, or null when switched off. During a loop mix it holds the line from the
     * takeover (as the extension does): YT's position runs on while the loop repeats, so Morphe's
     * current line would be ahead of what is heard.
     */
    static String currentLyricLine() {
        if ("0".equals(props().getProperty("lyricLine"))) return null;
        boolean hold;
        try {
            hold = app.ayham.extension.music.automix.Automix.holdLyrics();
        } catch (Throwable t) {
            hold = false;
        }
        if (hold && !held) heldLine = lastLine;
        held = hold;
        return hold ? heldLine : lastLine;
    }

    static String currentStats() {
        String s = "0".equals(props().getProperty("stats")) ? null : statsText;
        // the "Offline" badge shows with or without the stats switch
        if (offline()) return s == null || s.isEmpty() ? "Offline" : "Offline · " + s;
        return s;
    }

    /** No network that reaches the internet (what YT's own chips need). */
    public static boolean offline() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.NetworkCapabilities c = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return c == null || !c.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Throwable t) {
            return false;
        }
    }

    /** The second stats line: automix's status for this and the next song. */
    static String currentAutomixStats() {
        if ("0".equals(props().getProperty("stats"))) return null;
        try {
            return app.ayham.extension.music.automix.Automix.status();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void hookPlayerPage(Activity activity) {
        final View decor = activity.getWindow().getDecorView();
        final String pkg = activity.getPackageName();
        final int pageId = activity.getResources().getIdentifier("player_page", "id", pkg);
        final int barId = activity.getResources().getIdentifier("video_action_bar", "id", pkg);
        Log.i(TAG, "ids: player_page=" + pageId + " video_action_bar=" + barId);
        final TextView[] overlay = new TextView[1];

        final Runnable tick = () -> {
            try {
                View page = decor.findViewById(pageId);
                if (page == null) return;
                if (overlay[0] == null && page instanceof ViewGroup) {
                    TextView tv = new TextView(activity);
                    // YT's own screen (BitChord layout off): our lines on the art's lower edge,
                    // small white text with a shadow, as on BitChord's art
                    tv.setTextColor(0xD9FFFFFF);
                    tv.setTextSize(12);
                    tv.setShadowLayer(8, 0, 2, 0xB3000000);
                    ViewGroup group = (ViewGroup) page;
                    // Our container: a label, and later YT's moved chips row, placed under the art.
                    android.widget.LinearLayout box = new android.widget.LinearLayout(activity);
                    box.setOrientation(android.widget.LinearLayout.VERTICAL);
                    box.addView(tv);
                    if (group instanceof FrameLayout) {
                        group.addView(box, new FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                                Gravity.CENTER));
                    } else {
                        group.addView(box);
                    }
                    box.setTranslationX(64);
                    overlay[0] = tv;
                    overlayView = tv;
                    Log.i(TAG, "overlay added to player_page (" + group.getClass().getName()
                            + ", FrameLayout=" + (group instanceof FrameLayout) + ")");
                }
                // Rough edge: our views are children of player_page, so they also show over the
                // miniplayer when the player is collapsed. Follow where the page sits on screen.
                if (overlay[0] != null) {
                    int[] at = new int[2];
                    page.getLocationOnScreen(at);
                    boolean expanded = page.isShown() && at[1] < decor.getHeight() / 2;
                    View box = (View) overlay[0].getParent();
                    try {
                        SpikeG.tick(activity, decor, (ViewGroup) page, expanded);
                    } catch (Throwable t) {
                        failed("ui", t);
                    }
                    try {
                        SpikeH.tick(activity, decor, (ViewGroup) page, expanded);
                    } catch (Throwable t) {
                        failed("artistArt", t);
                    }
                    int want = expanded && !SpikeG.on() ? View.VISIBLE : View.INVISIBLE;
                    View art = decor.findViewById(activity.getResources().getIdentifier("player_view_pager_container", "id", pkg));
                    if (want == View.VISIBLE && art != null && art.getHeight() > 0) {
                        int[] a = new int[2], b = new int[2];
                        art.getLocationOnScreen(a);
                        box.getLocationOnScreen(b);
                        float y = box.getTranslationY() + (a[1] + art.getHeight() - 24 - box.getHeight()) - b[1];
                        if (Math.abs(box.getTranslationY() - y) > 1) box.setTranslationY(y);
                    }
                    if (box.getVisibility() != want) {
                        box.setVisibility(want);
                        Log.i(TAG, "player " + (expanded ? "expanded" : "collapsed") + ": pageY=" + at[1]
                                + " shown=" + page.isShown() + " -> overlay " + (expanded ? "shown" : "hidden"));
                    }
                }
                if ("2".equals(props().getProperty("move"))) {
                    // Keep YT's chips row in its own parent; only draw it lower, under the controls.
                    View bar = decor.findViewById(barId);
                    // Target: just above the seek bar, where no other view overlaps it.
                    View controls = decor.findViewById(activity.getResources()
                            .getIdentifier("time_bar", "id", pkg));
                    if (bar != null && controls != null && bar.getHeight() > 0) {
                        int[] b = new int[2];
                        int[] c = new int[2];
                        bar.getLocationOnScreen(b);
                        controls.getLocationOnScreen(c);
                        float shift = (c[1] - bar.getHeight() - 16) - (b[1] - bar.getTranslationY());
                        if (Math.abs(bar.getTranslationY() - shift) > 1) {
                            bar.setTranslationY(shift);
                            rehides++;
                            Log.i(TAG, "shifted video_action_bar by " + shift + "px (time #" + rehides
                                    + ", children=" + (bar instanceof ViewGroup ? ((ViewGroup) bar).getChildCount() : -1) + ")");
                        }
                    }
                }
                if ("1".equals(props().getProperty("move")) && overlay[0] != null) {
                    // Move YT's real chips row into our container (under our label), not a copy.
                    View bar = decor.findViewById(barId);
                    ViewGroup container = (ViewGroup) overlay[0].getParent();
                    if (bar != null && bar.getParent() != container && bar.getParent() instanceof ViewGroup) {
                        ViewGroup from = (ViewGroup) bar.getParent();
                        from.removeView(bar);
                        container.addView(bar, new android.widget.LinearLayout.LayoutParams(
                                1200, ViewGroup.LayoutParams.WRAP_CONTENT));
                        rehides++;
                        Log.i(TAG, "moved video_action_bar from " + from.getClass().getName()
                                + " into our container (time #" + rehides + ")");
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "player page hook failed", t);
            }
        };
        decor.getViewTreeObserver().addOnGlobalLayoutListener(tick::run);
        decor.getViewTreeObserver().addOnPreDrawListener(() -> {
            tick.run();
            return true;
        });
    }
}
