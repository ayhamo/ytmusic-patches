package app.ayham.extension.music.automix;

import app.ayham.extension.music.features.Features;
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

import app.ayham.extension.music.features.PlayerTap;

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

    public static Context context() {
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
        // Original (1) is retired like on the PC: a saved 1 plays Classic (2); version 1's code stays for the audit
        String v = p.getString(KEY_VERSION, "2");
        s.version = "3".equals(v) ? 3 : 2;
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

    /** Injection point (through Features): MusicActivity.onCreate. */
    public static void onActivityCreate(Activity activity) {
        app = activity.getApplicationContext();
        if (started) return;
        started = true;
        main.postDelayed(Automix::tick, 2000);
        AddonDirect.start(app);
        Log.i(TAG, "automix ready (" + (enabled() ? "on" : "off") + ")");
    }

    /** Injection point: the start of YT's ExoPlayer.setVolume(float). */
    public static float gate(Object player, float volume) {
        return Engine.gate(player, volume);
    }
    /** Injection point (through Features): Morphe's crossfade has a new player. True when a mix takes it. */
    public static boolean onIncomingPlayerReady(Object player) {
        Engine e = engine;
        return e != null && e.running() && e.onIncoming(player);
    }

    /** Injection point: Morphe's CrossfadePatch.onPauseVideo (a pause, or the first step of a skip). */
    public static void onPauseVideo() {
        AddonDirect.onPause();
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

    /**
     * The current song's id, only when the queue's title for it is [title]: the queue is read on
     * Automix's tick, so with Automix off it can still name the song before.
     */
    public static String currentIdFor(String title) {
        String id = curId, t = curTitle;
        if (id == null || t == null || title == null) return null;
        return t.trim().equalsIgnoreCase(title.trim()) ? id : null;
    }

    static String nextIdNow() {
        return nextId;
    }

    /** The queue as last read: {current id, title, artist, next id, title, artist}. */
    static String[] queue() {
        return new String[]{curId, curTitle, curArtist, nextId, nextTitle, nextArtist};
    }

    /** Reads the queue (main thread) when automix's tick hasn't lately: the addons need it with automix off. */
    static void pollQueue() {
        if (SystemClock.elapsedRealtime() - queueAt > 1400) readQueue();
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
                    if (curItem != null) remember((String) videoId.invoke(access, curItem), curTitle, (String) artist.invoke(access, curItem));
                    if (nextItem != null) remember((String) videoId.invoke(access, nextItem), (String) title.invoke(access, nextItem), (String) artist.invoke(access, nextItem));
                    curArtist = curItem == null ? null : (String) artist.invoke(access, curItem);
                    nextTitle = nextItem == null ? null : (String) title.invoke(access, nextItem);
                    nextArtist = nextItem == null ? null : (String) artist.invoke(access, nextItem);
                    curId = cur;
                    if (next != null && !next.equals(nextId)) Features.lookAhead(next);
                    // the next two songs' canvas loops, fetched ahead (Prism's CanvasAhead)
                    java.util.List<String[]> ahead = new java.util.ArrayList<>();
                    Object[] auto = null;
                    for (int k = 1; ahead.size() < 2 && k <= 4; k++) {
                        Object it = items != null && current + k < items.length ? items[current + k] : null;
                        if (it == null) {
                            if (auto == null) auto = (Object[]) type.getMethod("patch_jamAutoplayItems").invoke(access);
                            int ai = k - (items == null ? 0 : Math.max(0, items.length - current - 1)) - 1;
                            it = auto != null && ai >= 0 && ai < auto.length ? auto[ai] : null;
                        }
                        if (it == null) break;
                        ahead.add(new String[]{(String) videoId.invoke(access, it), (String) title.invoke(access, it), (String) artist.invoke(access, it)});
                    }
                    Features.canvasAhead(ahead);
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
        /** Automix v2: why the loop mix or the blend plays (loopOrBlend), or null. */
        String choice;
        double takeover;
        /** The song's length when planned (a skip changes it before the queue is read again). */
        double dur;
        boolean loopPending;
        /** The loop mix chosen for this song, or null (a blend or crossfade). */
        Planner.LoopPlan loop;

        Song(String id) {
            this.id = id;
        }
    }

    private static Song amx = new Song("");
    private static volatile Engine engine;

    /** A mix is on (from its build to its end). */
    static boolean mixing() {
        Engine e = engine;
        return e != null && e.running();
    }
    private static Object lastPlayer;
    private static double lastDur, lastPos;

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
            player = PlayerTap.current();
        } catch (Throwable t) {
            player = null;
        }
        if (player == null) return;
        long now = SystemClock.elapsedRealtime();
        double pos0 = position(player) / 1000.0, dur0 = duration(player) / 1000.0;
        // a skip: the length changes or the position jumps back, so the queue is read now
        boolean jumped = dur0 > 0 && (Math.abs(dur0 - lastDur) > 0.5 || pos0 < lastPos - 3);
        if (dur0 > 0) {
            lastDur = dur0;
            lastPos = pos0;
        }
        if (player != lastPlayer || jumped || now - queueAt > 1500) {
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
        if (from != null) from.musicVideo = Features.isMusicVideo(id);
        if (to != null) to.musicVideo = Features.isMusicVideo(next);
        // where each song is sung, from its synced lyrics (the extension's first source; else the mask)
        SungLyrics.request(app, id, curTitle, curArtist, from != null ? from.durationSec : dur);
        if (to != null && next != null && !next.equals(id)) SungLyrics.request(app, next, nextTitle, nextArtist, to.durationSec);
        Planner.Sung sung = sung(id, next);
        boolean lyricsWait = (sung.out == null && SungLyrics.pending(id)) || (sung.in == null && SungLyrics.pending(next));
        Planner.Settings s = settings();
        // the editor's override for this pair: Loop asks for the loop mix (planned as Auto besides);
        // anything else means no loop mix (crossfade.js: loopPicked, noLoop)
        MixOverride ov = next == null ? null : override(id + ">" + next);
        boolean noLoop = ov != null && !ov.loop();
        Planner.Plan p = Planner.plan(s, id, next, from, to, dur, pos, pos + EARLIEST_LEAD, sung, noLoop ? ov : null);
        // a pair is looked at again when its lyrics arrive
        String pair = id + ">" + next + "|" + (sung.out != null ? "L" : "m") + (sung.in != null ? "L" : "m");
        if (!noLoop && next != null && from != null && to != null && !lyricsWait && (!pair.equals(loopKey)
                || (loopState.equals("missing") && SystemClock.elapsedRealtime() >= loopRetryAt))) prepareLoop(s.version, id, next, from, to, pos, sung, pair);
        boolean loopPending = !noLoop && (lyricsWait || pair.equals(loopKey) && (loopState.equals("loading") || loopState.equals("missing")));
        Planner.LoopPlan lp = !noLoop && pair.equals(loopKey) && loopState.equals("ready") ? loopPlan : null;
        amx.loopPending = loopPending;
        // Automix v2: the loop mix plays only when it scores over the blend (automix.js loopOrBlend)
        Planner.Choice choice = lp != null && ov != null && ov.loop() ? Planner.Choice.picked(lp.none == null) : Planner.loopOrBlend(s.version, p, lp);
        boolean loop = lp != null && choice.loop && lp.out.loopStart > pos + EARLIEST_LEAD;
        amx.choice = lp != null ? choice.why : null;
        double takeover = loop ? lp.out.loopStart : p.mixOutTrackTime - Mix.HAND_LEAD - (p.style.equals("blend") ? p.rampSongSec : 0);
        String shown = (loop ? lp.toString() : p.toString()) + (amx.choice != null ? " [" + amx.choice + "]" : "");
        if (!shown.equals(amx.shown)) {
            amx.shown = shown;
            String waiting = from == null && Analyzer.busy(id) ? " (analysing this song)" : next != null && to == null && Analyzer.busy(next) ? " (analysing the next song)" : "";
            Log.i(TAG, String.format(Locale.US, "plan at %.1f s of %s (v%d%s, bars %s): %s%s", pos, id, s.version, s.keyAware && s.version == 2 ? ", key-aware" : "",
                    s.bars == 0 ? "auto" : String.valueOf(s.bars), shown, waiting));
        }
        amx.plan = p;
        amx.loop = loop ? lp : null;
        amx.takeover = takeover;
        amx.dur = dur;
        if (!loop && (p.fallback || next == null)) return;   // Morphe's crossfade, as the extension's plain one
        if (!loop && !(p.durationSec >= 1)) return;
        if (pos < takeover - TAIL_LEAD || (loopPending && pos < takeover - 8)) return;

        amx.state = "building";
        Song me = amx;
        Object outPlayer = player;
        String inId = next;
        new Thread(() -> {
            Mix mix = loop ? Mix.loop(lp, id, inId, dur) : Mix.blend(s.version == 3 ? kickLocked(p, id, inId) : p, id, inId, dur);
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
            // a running mix keeps its band after YT has moved on to the next song (its midpoint)
            boolean running = e != null && e.running() && e.tookOverOnce();
            Object p = PlayerTap.current();
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

    /**
     * The seek bar during a mix: {how far the mix is (0-1), brightness on the beat (0.1-1)}, or null
     * when no mix is heard.
     */
    public static float[] mixUi() {
        Engine e = engine;
        if (e == null || !e.running() || !e.tookOverOnce()) return null;
        double t = e.mixNow();
        if (Double.isNaN(t)) return null;
        double end = e.m.pulseEnd();
        float cover = (float) Math.max(0, Math.min(1, end > 0 ? t / end : 1));
        return new float[]{cover, (float) Mix.pulse(e.m.beats, t)};
    }

    /**
     * The incoming song's second heard now, in ms, while YT already shows it but its player is still
     * held silent (YT moved on, before the handover): the song plays on our deck, and YT's position
     * (Morphe's lyric clock) is not where the singing is. -1 otherwise.
     */
    public static long incomingHeardMs() {
        Engine e = engine;
        if (e == null || !e.running() || !e.askedNext() || e.handedOver()) return -1;
        double t = e.mixNow();
        if (Double.isNaN(t)) return -1;
        double s = e.m.in.trackAt(t);
        return s >= 0 ? Math.round(s * 1000) : -1;
    }

    /** The video id of the song a running mix goes into, or null. */
    public static String incomingId() {
        Engine e = engine;
        return e == null || !e.running() ? null : e.m.inId;
    }

    /** True while a loop mix runs (takeover to handover): the lyric line holds, as the extension's. */
    public static boolean holdLyrics() {
        Engine e = engine;
        return e != null && e.running() && e.loopHolding();
    }

    // ---------------------------------------------------------------------------------------------
    // The Automix overview (the extension's automix-overview.js reads crossfade.js the same way)

    /** The editor's overrides (editor.md §1), the same key the overview's picks had: old picks migrate as read. */
    private static final String KEY_PICKS = "ayham_automix_pair_picks";
    /** "a>b" → the user's override for that pair; oldest saved first. */
    private static java.util.LinkedHashMap<String, MixOverride> overrides;
    private static final java.util.Map<String, String[]> names = new java.util.LinkedHashMap<String, String[]>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, String[]> e) {
            return size() > 20;
        }
    };

    private static void remember(String id, String title, String artist) {
        if (id == null || title == null) return;
        synchronized (names) {
            names.put(id, new String[]{title, artist});
        }
    }

    /** {title, artist} of a song seen in the queue, or null. */
    public static String[] names(String id) {
        if (id == null) return null;
        synchronized (names) {
            return names.get(id);
        }
    }

    private static synchronized java.util.LinkedHashMap<String, MixOverride> overrides() {
        if (overrides != null) return overrides;
        overrides = new java.util.LinkedHashMap<>();
        if (app == null) return overrides;
        try {
            Object o = Json.parse(prefs(app).getString(KEY_PICKS, "{}"));
            if (o instanceof java.util.Map) {
                for (java.util.Map.Entry<?, ?> e : ((java.util.Map<?, ?>) o).entrySet()) {
                    MixOverride ov = MixOverride.fromJson(e.getValue());
                    if (ov != null) overrides.put(String.valueOf(e.getKey()), ov);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "pair overrides unreadable", t);
        }
        return overrides;
    }

    /** The user's override for a pair ("a>b"), or null: Auto. */
    public static synchronized MixOverride override(String pairKey) {
        MixOverride o = overrides().get(pairKey);
        return o == null ? null : o.copy();
    }

    /**
     * Saves a pair's override (null, or nothing but Auto: removed), kept for the next time the pair
     * plays; returns why it was refused, or null. The pair playing now is planned again, unless its
     * mix has begun (editor.md §8). Unlocked overrides past MAX_UNLOCKED go oldest first; a lock
     * past MAX_LOCKED is refused.
     */
    public static String setOverride(String pairKey, MixOverride ov) {
        ov = MixOverride.normalize(ov);
        synchronized (Automix.class) {
            java.util.LinkedHashMap<String, MixOverride> m = overrides();
            MixOverride was = m.get(pairKey);
            if (ov != null && ov.lock && (was == null || !was.lock)) {
                int locked = 0;
                for (MixOverride o : m.values()) if (o.lock) locked++;
                if (locked >= MixOverride.MAX_LOCKED) return "Already " + MixOverride.MAX_LOCKED + " locked pairs: unlock one first";
            }
            m.remove(pairKey);
            if (ov != null) {
                ov.engine = settings().version == 3 ? 3 : 2;
                ov.savedAt = System.currentTimeMillis();
                m.put(pairKey, ov);
            }
            int unlocked = 0;
            for (MixOverride o : m.values()) if (!o.lock) unlocked++;
            for (java.util.Iterator<MixOverride> it = m.values().iterator(); unlocked > MixOverride.MAX_UNLOCKED && it.hasNext(); ) {
                if (!it.next().lock) {
                    it.remove();
                    unlocked--;
                }
            }
            try {
                java.util.Map<String, Object> o = new java.util.LinkedHashMap<>();
                for (java.util.Map.Entry<String, MixOverride> e : m.entrySet()) o.put(e.getKey(), e.getValue().toJson());
                if (app != null) prefs(app).edit().putString(KEY_PICKS, Json.write(o)).apply();
            } catch (Throwable t) {
                Log.w(TAG, "pair overrides not saved", t);
            }
        }
        Log.i(TAG, "override for " + pairKey + ": " + (ov == null ? "auto" : Json.write(ov.toJson())));
        // planned afresh (an armed mix, not started yet, is dropped for the new plan)
        if (pairKey.equals(curId + ">" + nextId)) {
            Engine e = engine;
            if (e != null && e.running() && !e.tookOverOnce()) e.abort("replanned", false);
            if (!amx.state.equals("mixing")) {
                amx.state = "idle";
                amx.shown = null;
            }
        }
        return null;
    }

    /** The playing song jumps to [sec] (the overview's preview: just before the mix). */
    public static boolean seekTo(double sec) {
        try {
            Object p = PlayerTap.current();
            if (!(p instanceof app.ayham.extension.music.features.SeekAccess)) return false;
            ((app.ayham.extension.music.features.SeekAccess) p).ayham_seekTo(Math.round(sec * 1000));
            Log.i(TAG, String.format(Locale.US, "overview preview: jump to %.1f s", sec));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static double curDur() {
        try {
            Object p = PlayerTap.current();
            return p == null ? -1 : duration(p) / 1000.0;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** A song's grid as the editor snaps to it (automix.js editorGrid): its end grid for the mix-out, its opening for the mix-in. */
    static double[] editorGrid(Analysis a, boolean out) {
        Planner.Grid g = a == null ? null : Planner.gridOf(out ? Planner.asOutgoing(a) : a);
        return g == null ? null : new double[]{g.downbeatSec, g.beatSec, g.bpm};
    }

    /** The coming mix, or the one playing, for the overview (crossfade.js overviewSnapshot). */
    public static Overview overview() {
        Overview o = new Overview();
        if (!enabled()) {
            o.why = "Automix is off";
            return o;
        }
        Engine e = engine;
        boolean live = e != null && e.running() && e.tookOverOnce();
        String id = curId, next = nextId;
        if (!live && id == null) {
            o.why = "Nothing is playing";
            return o;
        }
        Planner.Plan plan = null;
        Planner.LoopPlan loop = null;
        if (live) {
            plan = e.m.plan;
            loop = e.m.loopPlan;
            o.fromId = e.m.outId;
            o.toId = e.m.inId;
        } else {
            Song s = amx;
            if (next == null || next.equals(id)) {
                o.why = "Nothing queued after this song";
                return o;
            }
            if (id.equals(s.id) && s.shown != null && !"done".equals(s.state) && (!(s.dur > 0) || Math.abs(s.dur - curDur()) < 0.5)) {
                loop = s.loop;
                plan = loop == null ? s.plan : null;
                o.choice = s.choice;
            }
            o.fromId = id;
            o.toId = next;
        }
        o.pairKey = o.fromId + ">" + o.toId;
        o.override = override(o.pairKey);
        if (app != null) {
            Analysis fa = Analyzer.get(app, o.fromId), ta = Analyzer.get(app, o.toId);
            o.outGrid = editorGrid(fa, true);
            o.inGrid = editorGrid(ta, false);
            o.outDur = fa != null ? fa.durationSec : Double.NaN;
            o.inDur = ta != null ? ta.durationSec : Double.NaN;
        }
        if (!live) {
            Object[] pf = Analyzer.progress(o.fromId), pt = Analyzer.progress(o.toId);
            if (pf != null || pt != null) {
                Object[] pr = pf != null ? pf : pt;
                o.waiting = "Analysing " + (pf != null ? "this song" : "the next song") + ": " + pr[0];
                o.waitDone = (Float) pr[1];
                o.waitingFor = pf != null ? o.fromId : o.toId;
            } else if (o.fromId.equals(id) && amx.loopPending) {
                o.waiting = "Looking for a loop mix";
                o.waitDone = -1;
            }
        }
        if (plan == null && loop == null) {
            o.why = live ? "A plain crossfade is playing" : "Planning the mix…";
            return o;
        }
        o.live = live;
        o.plan = plan;
        o.loop = loop;
        if (loop != null) {
            o.x = Planner.loopSchedule(loop, 0);
            o.D = o.x.elementAt;
            o.curves = loop.curves;
        } else {
            o.D = plan.durationSec;
            o.curves = plan.curves;
        }
        String[] a = names(o.fromId), b = names(o.toId);
        if (a != null) {
            o.fromTitle = a[0];
            o.fromArtist = a[1];
        }
        if (b != null) {
            o.toTitle = b[0];
            o.toArtist = b[1];
        }
        double start = loop != null ? loop.out.loopStart : plan.mixOutTrackTime;
        double takeover = loop != null ? loop.out.loopStart : plan.mixOutTrackTime - Mix.HAND_LEAD - ("blend".equals(plan.style) ? plan.rampSongSec : 0);
        if (live) {
            double t = e.mixNow();
            o.at = Double.isNaN(t) ? Double.NaN : t - e.m.t0;
        } else {
            try {
                Object player = PlayerTap.current();
                double pos = player == null ? -1 : position(player) / 1000.0;
                if (pos >= 0) {
                    o.at = pos - start;
                    o.left = takeover - pos;
                    o.earliest = pos + EARLIEST_LEAD;
                }
            } catch (Throwable ignored) {
            }
        }
        o.editable = !live && o.fromId.equals(id);
        // a fade planned because the analysis isn't there yet, with time left for it: only for now
        o.provisional = !live && loop == null && plan.fallback && o.waiting != null && o.waitingFor != null
                && (Double.isNaN(o.left) || o.left > TAIL_LEAD);
        o.previewAt = loop != null ? start - Mix.HAND_LEAD - 8 : start - Mix.HAND_LEAD - (TAIL_LEAD + 3 + plan.rampSongSec);
        if (loopKey.startsWith(o.pairKey + "|")) {
            Planner.LoopPlan lp = loopPlan;
            if ("ready".equals(loopState) && lp != null && lp.none != null) o.loopNone = lp.none;
        }
        return o;
    }

    /** Why [status] last returned null (the final phone pass looks for the line vanishing, item 6). */
    public static volatile String noStatusWhy;

    /**
     * Automix's stats-for-nerds line, as BitChord's (CrossfadeController describeAutomix): the mix
     * running now, else the one planned for this song, else both songs' analysis.
     */
    public static String status() {
        if (!enabled()) {
            noStatusWhy = "automix switched off";
            return null;
        }
        String id = curId, next = nextId;
        if (id == null) {
            noStatusWhy = "no current song known";
            return null;
        }
        noStatusWhy = null;
        Engine e = engine;
        if (e != null && e.running() && e.tookOverOnce()) {
            Mix m = e.m;
            if (m.loopPlan != null) return "Automix · " + (e.handedOver() ? "letting go" : "looping") + " · " + describeLoop(m.loopPlan);
            if (m.plan != null) return "Automix · " + describePlan(m.plan, 0, null);
            return "Automix · mixing now";
        }
        Song s = amx;
        Object player;
        try {
            player = PlayerTap.current();
        } catch (Throwable t) {
            player = null;
        }
        double pos = player == null ? -1 : position(player) / 1000.0, dur = player == null ? -1 : duration(player) / 1000.0;
        if (id.equals(s.id) && next != null && pos >= 0 && Math.abs(dur - s.dur) < 0.5 && !"done".equals(s.state)) {
            double in = Math.max(0, s.takeover - pos);
            if (s.loop != null) return "Automix · loop mix in " + (int) Math.ceil(in) + " s · " + describeLoop(s.loop);
            Planner.Plan p = s.plan;
            if (p != null && !p.fallback && p.durationSec >= 1) {
                Planner.LoopPlan lp = loopPlan;
                String noLoop = lp != null && lp.none != null ? lp.none : "missing".equals(loopState) ? "audio not decoded yet" : null;
                return "Automix · " + describePlan(p, in, noLoop);
            }
        }
        return "Automix · this song: " + label(id) + " · next: " + (next == null || next.equals(id) ? "none" : label(next));
    }

    /** BitChord's describeLoop: "out 8-bar, clear by lyrics · in 4-bar, clear by mask · 94 → 100 BPM over 3 bars". */
    static String describeLoop(Planner.LoopPlan p) {
        long from = Math.round(p.out.bpm), to = Math.round(p.in.bpm / (p.fold > 0 ? p.fold : 1));
        String tempo = from == to ? from + " BPM" : from + " → " + to + " BPM over " + p.rampBars + " bars";
        String fold = p.fold > 1 ? " (next in double time)" : p.fold < 1 ? " (next in half time)" : "";
        return "out " + side(p.out) + " · in " + side(p.in) + " · " + tempo + fold;
    }

    private static String side(Planner.LoopSide s) {
        return s.bars + "-bar, clear by " + ("lyrics".equals(s.checkedBy) ? "lyrics" : "mask");
    }

    /**
     * BitChord's line for a planned mix: "crossfading in 12 s · 12 s", "filter mix in 8 s · 4 bars",
     * "mixing now · 8 bars · next at 1.034×", with its reason and why there is no loop mix.
     */
    static String describePlan(Planner.Plan p, double inSec, String noLoop) {
        String when = inSec > 0 ? "in " + (int) Math.ceil(inSec) + " s" : "now";
        String length = p.bars > 0 ? p.bars + " bars" : String.format(Locale.ROOT, "%.1f s", p.durationSec);
        String why = p.reason != null ? " · " + p.reason : "";
        String no = noLoop != null ? " · no loop: " + noLoop : "";
        if (p.handoff) return "hand-over " + when + " · " + String.format(Locale.ROOT, "%.1f s", p.durationSec) + why + no;
        if (p.bridge) return "beatless bridge " + when + " · " + String.format(Locale.ROOT, "%.1f s", p.durationSec) + why + no;
        if ("crossfade".equals(p.style)) return "crossfading " + when + " · " + String.format(Locale.ROOT, "%.0f s", p.durationSec) + why + no;
        if ("filter".equals(p.style)) return "filter mix " + when + " · " + length + why + no;
        String stretch = Math.abs(p.rate - 1) < 0.0005 ? "same tempo" : String.format(Locale.ROOT, "next at %.3f×", p.rate);
        return "mixing " + when + " · " + length + " · " + stretch + no;
    }

    /** BitChord's per-song label: analysing… / failed / analysed (94 BPM F# major, dsp grid 0.82). */
    private static String label(String id) {
        Analysis a = app == null ? null : Analyzer.get(app, id);
        if (a == null) return Analyzer.busy(id) ? "analysing…" : "waiting";
        if (!(a.bpm > 0)) return "failed";
        String key = a.keyName != null && !a.keyName.isEmpty() ? " " + a.keyName : "";
        return String.format(Locale.ROOT, "analysed (%d BPM%s, dsp grid %.2f)", Math.round(a.bpm), key, a.gridConfidence);
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

    /**
     * Automix v2's kick lock (crossfade.js kickLockFor): the next song's cue moved a few ms so both
     * songs' kicks land together, read from the decoded end and opening. The plan as it was when
     * there is nothing to move or the audio is missing.
     */
    private static Planner.Plan kickLocked(Planner.Plan p, String id, String next) {
        if (!"blend".equals(p.style) || !(p.beatSec > 0)) return p;
        try {
            long t = SystemClock.elapsedRealtime();
            double b = p.beatSec, span = p.durationSec * 1.2 + 10 * b + 1;
            Analyzer.Pcm tail = Analyzer.tail(app, id), head = Analyzer.head(app, next);
            if (tail == null || head == null) {
                Log.i(TAG, "kick lock: no decoded audio");
                return p;
            }
            double oFrom = Math.max(0, p.mixOutTrackTime - 10 * b - 1), iFrom = Math.max(0, p.mixInTrackTime - 10 * b - 1);
            float[][] o = tail.read(oFrom, p.mixOutTrackTime + span), i = head.read(iFrom, p.mixInTrackTime + span);
            if (o[0].length == 0 || i[0].length == 0) {
                Log.i(TAG, "kick lock: no decoded audio there");
                return p;
            }
            KickLock.Side os = KickLock.kickSource(o, tail.rate, tail.fromSec((long) Math.floor(oFrom * tail.rate)), 0, Double.POSITIVE_INFINITY);
            KickLock.Side is = KickLock.kickSource(i, head.rate, head.fromSec((long) Math.floor(iFrom * head.rate)), 0, Double.POSITIVE_INFINITY);
            KickLock.Result k = KickLock.kickLock(p, os, is);
            Log.i(TAG, (k == null ? "kick lock: not a blend" : k.toString()) + " (" + (SystemClock.elapsedRealtime() - t) + " ms)");
            if (k == null || k.none != null) return p;
            Planner.Plan q = p.copy();
            q.mixInTrackTime += k.shift;
            q.kick = k;
            return q;
        } catch (Throwable e) {
            Log.w(TAG, "kick lock failed", e);
            return p;
        }
    }

    private static void prepareLoop(int version, String id, String next, Analysis from, Analysis to, double pos, Planner.Sung sung, String key) {
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
                        boolean offline = Features.offline();
                        Log.i(TAG, "no loop mix for this pair yet: decoded audio missing, trying again in " + (offline ? "30 s (offline)" : "5 s"));
                        state = "missing";
                        retryAt = SystemClock.elapsedRealtime() + (offline ? 30_000 : 5000);
                    } else {
                        Log.i(TAG, "sung parts from " + (sung.out != null ? "synced lyrics" : "the vocal mask") + " (playing song) and "
                                + (sung.in != null ? "synced lyrics" : "the vocal mask") + " (next song)");
                        plan = Planner.planLoopMix(version, cur, outAudio, to, inAudio, pos + Planner.LOOP_SETUP_SEC, sung);
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

    /** The length of the song YT's player has now, or -1 (no player yet). */
    public static long durationMs() {
        try {
            Object p = PlayerTap.current();
            return p == null ? -1 : duration(p);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** YT's player position now, or -1 (no player yet). */
    public static long positionMs() {
        try {
            Object p = PlayerTap.current();
            return p == null ? -1 : position(p);
        } catch (Throwable t) {
            return -1;
        }
    }

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
