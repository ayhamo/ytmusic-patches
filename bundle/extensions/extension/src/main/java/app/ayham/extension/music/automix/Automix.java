package app.ayham.extension.music.automix;

import app.ayham.extension.music.spike.SpikeA;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.util.Locale;

import app.ayham.extension.music.spike.SpikeE;

/**
 * Automix, the YT Music Extension's (BitChord web's AutoDJ) on the phone: the playing song and the
 * one the queue plays next are analysed, the mix between them is planned afresh every tick until
 * it is near (the loop mix when both have instrumental bars to loop, else the blend or filter mix of
 * the version picked in the settings), and our own decks play it. A song too short, a repeat-one,
 * or a pair the planner can only crossfade is left to Morphe's crossfade.
 */
public final class Automix {
    static final String TAG = "AyhamAutomix";
    private static final String JAM = "app.morphe.extension.music.patches.jam.YouTubeMusicBridge";
    private static final String MORPHE_CROSSFADE = "app.morphe.extension.music.patches.CrossfadePatch";
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final long TICK_MS = 250;
    /** The mix is built this long before its takeover. */
    private static final double TAIL_LEAD = 14;
    /** The earliest a mix may start, from the playhead (the engine needs a few seconds to get onto YT's clock). */
    private static final double EARLIEST_LEAD = Mix.HAND_LEAD + 2.5;

    private static Context app;
    private static boolean started;

    private Automix() {
    }

    static Context context() {
        return app;
    }

    // ---------------------------------------------------------------------------------------------
    // Settings (Morphe's settings screen, its morphe_prefs file)

    static final String KEY_ENABLED = "ayham_automix_enabled", KEY_VERSION = "ayham_automix_version",
            KEY_BARS = "ayham_automix_bars", KEY_KEY_AWARE = "ayham_automix_key_aware";

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("morphe_prefs", Context.MODE_PRIVATE);
    }

    static boolean enabled() {
        return app != null && prefs(app).getBoolean(KEY_ENABLED, true);
    }

    static Planner.Settings settings() {
        SharedPreferences p = prefs(app);
        Planner.Settings s = new Planner.Settings();
        s.version = "2".equals(p.getString(KEY_VERSION, "1")) ? 2 : 1;
        s.keyAware = p.getBoolean(KEY_KEY_AWARE, false);
        String bars = p.getString(KEY_BARS, "auto");
        try {
            s.bars = "auto".equals(bars) ? 0 : Integer.parseInt(bars);
        } catch (NumberFormatException e) {
            s.bars = 8;
        }
        return s;
    }

    // ---------------------------------------------------------------------------------------------
    // Hooks

    /** Injection point (through SpikeA): MusicActivity.onCreate. */
    public static void onActivityCreate(Activity activity) {
        app = activity.getApplicationContext();
        if (started) return;
        started = true;
        main.postDelayed(Automix::tick, 2000);
        Log.i(TAG, "automix ready (" + (enabled() ? "on" : "off") + ")");
    }

    /** Injection point: the start of YT's ExoPlayer.setVolume(float). */
    public static float gate(Object player, float volume) {
        return Engine.gate(player, volume);
    }
    /** Injection point (through SpikeA): Morphe's crossfade has a new player. True when a mix takes it. */
    public static boolean onIncomingPlayerReady(Object player) {
        Engine e = engine;
        return e != null && e.running() && e.onIncoming(player);
    }

    /** Injection point: Morphe's CrossfadePatch.onPauseVideo (a pause, or the first step of a skip). */
    public static void onPauseVideo() {
        Engine e = engine;
        if (e == null || !e.running()) return;
        // our own "next" pauses too, 10-50 ms before the stop: the next song's start isn't a pause
        if (SystemClock.elapsedRealtime() - askedAt < 3000) return;
        main.post(() -> e.stopNow("paused"));
    }

    /** Morphe's crossfade length while a mix plays: short, out of the way (our gate holds both players). */
    public static long crossfadeMs() {
        Engine e = engine;
        return e != null && e.running() ? 300 : 0;
    }

    static volatile long askedAt;

    // ---------------------------------------------------------------------------------------------
    // The queue (Morphe's Jam bridge to YT's queue)

    private static volatile String curId, nextId, curTitle, curArtist, nextTitle, nextArtist;
    private static volatile long queueAt;
    private static volatile boolean queueBusy;

    public static String currentId() {
        return curId;
    }

    /** The song version lookup reads the queue too (for the next song), with or without automix. */
    public static void refreshQueue() {
        main.postDelayed(Automix::readQueue, 1500);
    }

    private static void readQueue() {
        if (queueBusy) return;
        try {
            Class<?> bridge = Class.forName(JAM);
            java.lang.reflect.Field f = bridge.getDeclaredField("queue");
            f.setAccessible(true);
            Object access = ((java.lang.ref.WeakReference<?>) f.get(null)).get();
            if (access == null) return;
            Class<?> type = Class.forName(JAM + "$QueueAccess");
            java.util.concurrent.Executor exec = (java.util.concurrent.Executor) type.getMethod("patch_jamExecutor").invoke(access);
            queueBusy = true;
            exec.execute(() -> {
                try {
                    int current = (int) type.getMethod("patch_jamCurrent").invoke(access);
                    Object[] items = (Object[]) type.getMethod("patch_jamItems").invoke(access);
                    Method videoId = type.getMethod("patch_jamVideoId", Object.class);
                    Method title = type.getMethod("patch_jamTitle", Object.class), artist = type.getMethod("patch_jamArtist", Object.class);
                    Object curItem = items != null && current >= 0 && current < items.length ? items[current] : null;
                    Object nextItem = items != null && current + 1 < items.length ? items[current + 1] : null;
                    if (nextItem == null) {
                        Object[] auto = (Object[]) type.getMethod("patch_jamAutoplayItems").invoke(access);
                        if (auto != null && auto.length > 0) nextItem = auto[0];
                    }
                    String cur = curItem == null ? null : (String) videoId.invoke(access, curItem);
                    String next = nextItem == null ? null : (String) videoId.invoke(access, nextItem);
                    curTitle = curItem == null ? null : (String) title.invoke(access, curItem);
                    curArtist = curItem == null ? null : (String) artist.invoke(access, curItem);
                    nextTitle = nextItem == null ? null : (String) title.invoke(access, nextItem);
                    nextArtist = nextItem == null ? null : (String) artist.invoke(access, nextItem);
                    curId = cur;
                    if (next != null && !next.equals(nextId)) SpikeA.lookAhead(next);
                    nextId = next;
                    queueAt = SystemClock.elapsedRealtime();
                } catch (Throwable t) {
                    Log.w(TAG, "queue read failed", t);
                } finally {
                    queueBusy = false;
                }
            });
        } catch (Throwable t) {
            queueBusy = false;
            Log.w(TAG, "queue read failed", t);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The tick (crossfade.js's automix tick)

    private static final class Song {
        final String id;
        String state = "idle"; // idle | building | armed | mixing | done | off
        Planner.Plan plan;
        String shown;
        double takeover;

        Song(String id) {
            this.id = id;
        }
    }

    private static Song amx = new Song("");
    private static volatile Engine engine;
    private static Object lastPlayer;

    // the loop mix, prepared once per pair in the background
    private static volatile String loopKey = "", loopState = "idle";
    /** A pair whose decoded audio was not there yet (still being decoded again) is tried again from then. */
    private static volatile long loopRetryAt;
    private static volatile Planner.LoopPlan loopPlan;

    private static void tick() {
        try {
            step();
        } catch (Throwable t) {
            Log.e(TAG, "tick failed", t);
        }
        main.postDelayed(Automix::tick, TICK_MS);
    }

    private static void step() {
        if (!enabled()) return;
        Object player;
        try {
            player = SpikeE.current();
        } catch (Throwable t) {
            player = null;
        }
        if (player == null) return;
        long now = SystemClock.elapsedRealtime();
        if (player != lastPlayer || now - queueAt > 1500) {
            lastPlayer = player;
            readQueue();
        }
        String id = curId, next = nextId;
        if (id == null) return;
        Engine e = engine;
        if (e != null && e.running()) return;
        double pos = position(player) / 1000.0, dur = duration(player) / 1000.0;
        if (pos < 0 || !(dur > 0)) return;
        if (!id.equals(amx.id)) amx = new Song(id);
        if (dur < 30 || repeatOne()) return;

        Analyzer.request(app, id, true);
        if (next != null && !next.equals(id)) Analyzer.request(app, next, false);
        if (!amx.state.equals("idle")) {
            if (amx.state.equals("armed") && pos > amx.takeover + 1) amx.state = "idle";
            return;
        }

        Analysis from = Analyzer.get(app, id), to = next != null ? Analyzer.get(app, next) : null;
        // music videos (known from the song-version lookup): no loop mix, as in the extension
        if (from != null) from.musicVideo = SpikeA.isMusicVideo(id);
        if (to != null) to.musicVideo = SpikeA.isMusicVideo(next);
        // where each song is sung, from its synced lyrics (the extension's first source; else the mask)
        SungLyrics.request(app, id, curTitle, curArtist, from != null ? from.durationSec : dur);
        if (to != null && next != null && !next.equals(id)) SungLyrics.request(app, next, nextTitle, nextArtist, to.durationSec);
        Planner.Sung sung = sung(id, next);
        boolean lyricsWait = (sung.out == null && SungLyrics.pending(id)) || (sung.in == null && SungLyrics.pending(next));
        Planner.Settings s = settings();
        Planner.Plan p = Planner.plan(s, id, next, from, to, dur, pos, pos + EARLIEST_LEAD, sung);
        // a pair is looked at again when its lyrics arrive
        String pair = id + ">" + next + "|" + (sung.out != null ? "L" : "m") + (sung.in != null ? "L" : "m");
        if (next != null && from != null && to != null && !lyricsWait && (!pair.equals(loopKey)
                || (loopState.equals("missing") && SystemClock.elapsedRealtime() >= loopRetryAt))) prepareLoop(id, next, from, to, pos, sung, pair);
        boolean loopPending = lyricsWait || (pair.equals(loopKey) && (loopState.equals("loading") || loopState.equals("missing")));
        Planner.LoopPlan lp = pair.equals(loopKey) && loopState.equals("ready") ? loopPlan : null;
        boolean loop = lp != null && lp.out.loopStart > pos + EARLIEST_LEAD;
        double takeover = loop ? lp.out.loopStart : p.mixOutTrackTime - Mix.HAND_LEAD - (p.style.equals("blend") ? p.rampSongSec : 0);
        String shown = loop ? lp.toString() : p.toString();
        if (!shown.equals(amx.shown)) {
            amx.shown = shown;
            String waiting = from == null && Analyzer.busy(id) ? " (analysing this song)" : next != null && to == null && Analyzer.busy(next) ? " (analysing the next song)" : "";
            Log.i(TAG, String.format(Locale.US, "plan at %.1f s of %s (v%d%s, bars %s): %s%s", pos, id, s.version, s.keyAware && s.version == 2 ? ", key-aware" : "",
                    s.bars == 0 ? "auto" : String.valueOf(s.bars), shown, waiting));
        }
        amx.plan = p;
        amx.takeover = takeover;
        if (!loop && (p.fallback || next == null)) return;   // Morphe's crossfade, as the extension's plain one
        if (!loop && !(p.durationSec >= 1)) return;
        if (pos < takeover - TAIL_LEAD || (loopPending && pos < takeover - 8)) return;

        amx.state = "building";
        Song me = amx;
        Object outPlayer = player;
        String inId = next;
        new Thread(() -> {
            Mix mix = loop ? Mix.loop(lp, id, inId, dur) : Mix.blend(p, id, inId, dur);
            Engine built = Engine.build(app, mix, listener);
            main.post(() -> {
                if (amx != me || !me.state.equals("building")) {
                    if (built != null) built.abort("stale", false);
                    return;
                }
                long at = position(outPlayer);
                if (built == null) {
                    me.state = "done";
                    Log.w(TAG, "no mix for this pair; Morphe's crossfade instead");
                } else if (at >= 0 && at < Math.round((mix.takeoverSong - 0.8) * 1000)) {
                    me.state = "armed";
                    engine = built;
                    built.arm(outPlayer);
                    Log.i(TAG, String.format(Locale.US, "mix armed: %s; takeover at %.2f s, YT moves on at mix %.2f s, its player takes over from mix %.2f s",
                            mix.note, mix.takeoverSong, mix.askAt, mix.elementAt));
                } else {
                    me.state = "idle";
                    built.abort("built too late", false);
                }
            });
        }, "Automix-build").start();
    }

    // ---------------------------------------------------------------------------------------------
    // For the player screen

    /**
     * The playing song's mix region in song ms {start, end, running (1/0)}, or null when no mix is
     * planned for it. Start is the takeover (where the decks take over from YT), end the song's end.
     */
    public static long[] zone() {
        try {
            Engine e = engine;
            String id = curId;
            if (id == null || !enabled()) return null;
            boolean running = e != null && e.running() && e.tookOverOnce() && id.equals(e.m.outId);
            Object p = SpikeE.current();
            long dur = p == null ? -1 : duration(p);
            if (dur <= 0) return null;
            if (running) return new long[]{Math.round(e.m.takeoverSong * 1000), dur, 1};
            Song s = amx;
            if (!id.equals(s.id) || s.shown == null || !(s.takeover > 0)) return null;
            if (s.plan != null && (s.plan.fallback || nextId == null) && !s.shown.startsWith("loop")) return null;
            return new long[]{Math.round(s.takeover * 1000), dur, 0};
        } catch (Throwable t) {
            return null;
        }
    }

    /** True while a loop mix runs (takeover to handover): the lyric line holds, as the extension's. */
    public static boolean holdLyrics() {
        Engine e = engine;
        return e != null && e.running() && e.loopHolding();
    }

    /** Automix's status for the stats line: this song and the next (analysed / analysing / failed). */
    public static String status() {
        if (!enabled()) return null;
        String id = curId, next = nextId;
        if (id == null) return null;
        StringBuilder b = new StringBuilder("Automix · ").append(describe(id));
        if (next != null && !next.equals(id)) b.append("  →  next ").append(describe(next));
        Engine e = engine;
        if (e != null && e.running() && e.tookOverOnce()) b.append("  ·  mixing (").append(e.m.style).append(')');
        return b.toString();
    }

    private static String describe(String id) {
        Analysis a = app == null ? null : Analyzer.get(app, id);
        if (a == null) return Analyzer.busy(id) ? "analysing…" : "not analysed";
        StringBuilder b = new StringBuilder();
        if (a.bpm > 0) b.append(String.format(Locale.US, "%.0f BPM", a.bpm));
        if (a.keyName != null) b.append(b.length() > 0 ? " · " : "").append(a.keyName);
        if (a.gridConfidence > 0) b.append(String.format(Locale.US, " · grid %.2f", a.gridConfidence));
        return b.length() == 0 ? "analysed" : b.toString();
    }

    private static final Engine.Listener listener = new Engine.Listener() {
        @Override
        public void onMixStarted(Engine e) {
            if (amx.id.equals(e.m.outId)) amx.state = "mixing";
        }

        @Override
        public void onFinished(Engine e, boolean ok, String why) {
            if (engine == e) engine = null;
            if (why != null && !why.equals("stale") && !why.equals("built too late")) Log.i(TAG, "mix ended" + (ok ? "" : ": " + why));
            if (amx.id.equals(e.m.outId)) {
                // not started (a seek past its takeover): planned again from here; else this song is done
                amx.state = e.tookOverOnce() ? "done" : "idle";
            }
        }
    };

    /** Each song's sung spans from synced lyrics, or null (the vocal mask) when it has none. */
    private static Planner.Sung sung(String id, String next) {
        Planner.Sung s = new Planner.Sung();
        double[][] a = SungLyrics.cached(app, id), b = next != null ? SungLyrics.cached(app, next) : null;
        s.out = a != null && a.length > 0 ? a : null;
        s.in = b != null && b.length > 0 ? b : null;
        return s;
    }

    private static void prepareLoop(String id, String next, Analysis from, Analysis to, double pos, Planner.Sung sung, String key) {
        loopKey = key;
        loopState = "loading";
        loopPlan = null;
        new Thread(() -> {
            String state = "none";
            Planner.LoopPlan plan = null;
            long retryAt = 0;
            try {
                Analysis cur = Planner.asOutgoing(from);
                if (!Planner.gridOk(cur) || !Planner.gridOk(to)) {
                    Log.i(TAG, "no loop mix for this pair: beat grid too weak");
                } else {
                    Planner.Audio outAudio = audio(Analyzer.tail(app, id), cur.durationSec - Planner.OUT_REGION_SEC, cur.durationSec, cur.durationSec);
                    Planner.Audio inAudio = audio(Analyzer.head(app, next), 0, Planner.IN_REGION_SEC, to.durationSec);
                    if (outAudio == null || inAudio == null) {
                        // offline the audio can't be fetched: ask again every 30 s instead (crossfade meanwhile)
                        boolean offline = SpikeA.offline();
                        Log.i(TAG, "no loop mix for this pair yet: decoded audio missing, trying again in " + (offline ? "30 s (offline)" : "5 s"));
                        state = "missing";
                        retryAt = SystemClock.elapsedRealtime() + (offline ? 30_000 : 5000);
                    } else {
                        Log.i(TAG, "sung parts from " + (sung.out != null ? "synced lyrics" : "the vocal mask") + " (playing song) and "
                                + (sung.in != null ? "synced lyrics" : "the vocal mask") + " (next song)");
                        plan = Planner.planLoopMix(cur, outAudio, to, inAudio, pos + Planner.LOOP_SETUP_SEC, sung);
                        if (plan == null || plan.none != null) {
                            Log.i(TAG, "no loop mix for this pair: " + (plan == null ? "no beat grid to loop on" : plan.none));
                            plan = null;
                        } else {
                            state = "ready";
                            Log.i(TAG, "loop mix ready: " + plan);
                        }
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "loop mix preparation failed", t);
            }
            if (key.equals(loopKey)) {
                // Set only for the pair still wanted: a late thread of an earlier pair would move
                // the current pair's retry time.
                loopRetryAt = retryAt;
                loopPlan = plan;
                loopState = state;
            }
        }, "Automix-loop").start();
    }

    /** A song's decoded stretch for the loop search: its left channel's loudness (automix.js rms). */
    private static Planner.Audio audio(Analyzer.Pcm pcm, double from, double to, double duration) throws Exception {
        if (pcm == null) return null;
        float[] left = pcm.read(from, to)[0];
        double sr = pcm.rate;
        long first = Math.max(pcm.firstFrame, (long) Math.floor(from * sr));
        return new Planner.Audio() {
            @Override
            public double duration() {
                return duration;
            }

            @Override
            public double rms(double a, double b) {
                long i0 = Math.max(0, (long) Math.floor(a * sr)), i1 = (long) Math.floor(b * sr);
                double ss = 0;
                int n = 0;
                for (long i = i0; i < i1; i += 16) {
                    long k = i - first;
                    if (k < 0 || k >= left.length) continue;
                    double x = left[(int) k];
                    ss += x * x;
                    n++;
                }
                return n > 0 ? Math.sqrt(ss / n) : 0;
            }
        };
    }

    // ---------------------------------------------------------------------------------------------
    // YT's player and the screen

    private static Method posMethod, durMethod;

    static long position(Object player) {
        try {
            if (posMethod == null || posMethod.getDeclaringClass() != player.getClass()) posMethod = player.getClass().getMethod("patch_getCurrentPosition");
            return (long) posMethod.invoke(player);
        } catch (Throwable t) {
            return -1;
        }
    }

    static long duration(Object player) {
        try {
            if (durMethod == null || durMethod.getDeclaringClass() != player.getClass()) durMethod = player.getClass().getMethod("patch_getDuration");
            return (long) durMethod.invoke(player);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static boolean repeatOne() {
        try {
            java.lang.reflect.Field f = Class.forName(MORPHE_CROSSFADE).getDeclaredField("repeatSingleActive");
            f.setAccessible(true);
            return f.getBoolean(null);
        } catch (Throwable t) {
            return false;
        }
    }

    static void toast(String text) {
        main.post(() -> {
            try {
                Toast.makeText(app, text, Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {
            }
        });
    }

    /** YT's player from silent to full over [sec] (through the gate, which lets it pass). */
    static void rampVolume(Object player, double sec) {
        long t0 = SystemClock.elapsedRealtime();
        main.post(new Runnable() {
            @Override
            public void run() {
                double f = Math.min(1, (SystemClock.elapsedRealtime() - t0) / (sec * 1000));
                Engine.setVolume(player, (float) Math.sin(f * Math.PI / 2) * Engine.ytVolume(player));
                if (f < 1) main.postDelayed(this, 30);
            }
        });
    }
}
