package app.ayham.extension.music.automix;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import app.ayham.extension.music.addons.AddonPlayback;
import app.ayham.extension.music.addons.AddonPrep;
import app.ayham.extension.music.addons.AddonSettings;
import app.ayham.extension.music.features.Features;
import app.ayham.extension.music.features.PlayerTap;
import app.ayham.extension.music.features.SeekAccess;

/**
 * Direct mode (protocol.md §8, the phone): a song prepared from an addon plays on our own player
 * (YT's media3 classes, the local file), kept on YT's player's clock (addonTime = youtubeTime +
 * offsetSec) while YT's player runs on muted underneath as the clock and the fallback. Locked
 * silently first (seeks, then speed nudges while unheard), then a short crossfade from YT's audio
 * to the addon's; YT's own volume (its normalisation, Morphe's and automix's fades, the gate)
 * times the source gain sets ours. Any trouble hands the song back to YT's player at once.
 *
 * <p>No speed change while the addon is heard: a change re-plans ExoPlayer's audio and is heard as
 * a cut (phone, 2026-11-17). A drift is corrected by a silent re-lock (crossfade to YT and back).
 */
public final class AddonDirect {
    private static final String TAG = "AyhamDirect";
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final long TICK_MS = 10;
    /** A length within this of YouTube's: the player holds this song. */
    private static final double SAME_SONG_SEC = 2;
    private static final long FADE_IN_MS = 200, FADE_OUT_MS = 150;
    private static final Map<Object, Shadow> shadows = new IdentityHashMap<>();
    private static Context ctx;
    private static boolean started;
    private static long lastQueue;
    private static volatile boolean pausedByYt;
    /** When YT paused: its last reading or two still arrive just after, they aren't a resume. */
    private static volatile long pausedAt;
    /** Debug (spike prop directTrace=1): every clock reading logged. */
    private static boolean TRACE;

    private AddonDirect() {
    }

    /** From Automix.onActivityCreate. */
    static void start(Context c) {
        ctx = c.getApplicationContext();
        AddonSettings.init(ctx);
        if (started) return;
        started = true;
        AddonPrep.clearCache(ctx);
        TRACE = "1".equals(Features.prop("directTrace"));
        main.postDelayed(AddonDirect::tick, 2000);
    }

    private static Shadow get(Object host) {
        synchronized (shadows) {
            return shadows.get(host);
        }
    }

    /** How much of the host's sound the addon carries (0..1): YT's player is turned down by it. */
    static float share(Object host) {
        Shadow s = get(host);
        return s == null ? 0 : s.share;
    }

    /** The gate set YT's player's volume: ours follows (the same moment on the main thread). */
    static void onGate(Object host, float base) {
        final Shadow s = get(host);
        if (s == null) return;
        if (Looper.myLooper() == Looper.getMainLooper()) s.volume(base);
        else main.post(() -> s.volume(Engine.gateOut(host)));
    }

    /** The song's addon audio is heard now (the mix decks use it for this song's end). */
    static boolean audibleFor(String id) {
        synchronized (shadows) {
            for (Shadow s : shadows.values()) if (s.p.id.equals(id) && s.share >= 0.5f) return true;
        }
        return false;
    }

    /** Morphe's onPauseVideo: our players stop with YT's at once (the clock check is the backup). */
    static void onPause() {
        pausedAt = SystemClock.elapsedRealtime();
        pausedByYt = true;
        main.post(() -> {
            for (Shadow s : list()) s.play(false);
        });
    }

    private static List<Shadow> list() {
        synchronized (shadows) {
            return new ArrayList<>(shadows.values());
        }
    }

    private static void tick() {
        long next = TICK_MS;
        try {
            if (!AddonSettings.on()) {
                for (Shadow s : list()) s.end("addons off", false);
                AddonPrep.held = false;
                next = 500;
            } else {
                step();
            }
        } catch (Throwable t) {
            Log.e(TAG, "tick failed", t);
        }
        main.postDelayed(AddonDirect::tick, next);
    }

    private static void step() throws Exception {
        long now = SystemClock.elapsedRealtime();
        if (now - lastQueue > 1500) {
            lastQueue = now;
            Automix.pollQueue();
        }
        String[] q = Automix.queue();
        String cur = q[0], next = q[3];
        if (cur != null && q[1] != null && q[2] != null) AddonPrep.request(ctx, cur, q[1], q[2], Features.isMusicVideo(cur), true);
        if (next != null && !next.equals(cur) && q[4] != null && q[5] != null)
            AddonPrep.request(ctx, next, q[4], q[5], Features.isMusicVideo(next), false);

        Object host = PlayerTap.current();
        if (host != null) {
            // the song on the host: the prepared one (current first, then next: right after YT moves on
            // the queue can still name the song before) whose length is the host's
            double dur = Automix.duration(host) / 1000.0;
            AddonPrep.Prepared p = null;
            for (String id : new String[]{cur, next}) {
                AddonPrep.Prepared c = AddonPrep.get(id);
                if (c != null && dur > 0 && Math.abs(dur - c.ytDurationSec) <= SAME_SONG_SEC) {
                    p = c;
                    break;
                }
            }
            Shadow s = get(host);
            if (s != null && p != null && s.p != p) s.end("the next song", false);
            if (p != null && (s == null || s.done) && Automix.position(host) < (p.ytDurationSec - 20) * 1000) {
                s = new Shadow(host, p);
                synchronized (shadows) {
                    shadows.put(host, s);
                }
                s.open();
            }
        }
        boolean locking = false;
        for (Shadow s : list()) {
            if (!s.done) s.tick(now);
            if (s.done) {
                synchronized (shadows) {
                    if (shadows.get(s.host) == s) shadows.remove(s.host);
                }
            } else if (s.host == host && s.share < 1) {
                locking = true;
            }
        }
        AddonPlayback.upgrading(AddonPrep.busy(cur) || locking);
        AddonPrep.held = Automix.mixing();
    }

    /** Our player for one of YT's players. */
    private static final class Shadow {
        final Object host;
        final AddonPrep.Prepared p;
        final float gain;
        Object pl;
        Method state, pos, play, vol, speed;
        boolean done, on, playing;
        volatile float share;
        float fadeFrom, fadeTo;
        long fadeAt, fadeMs;
        long lastYt = -1, lastYtAt;
        boolean moving;
        final StepClock ytClock = new StepClock(), ourClock = new StepClock();
        int seeks, nudges, relocks;
        long seekLead, settleUntil, nudgeUntil, stallFrom, openedAt;
        float speedSet = 1f;
        float lastVol = -1;
        boolean justSeeked, cappedLogged;
        final StringBuilder log = new StringBuilder();

        Shadow(Object host, AddonPrep.Prepared p) {
            this.host = host;
            this.p = p;
            this.gain = p.gainLinear();
        }

        double target(long yt) {
            return yt + p.offsetSec * 1000;
        }

        void open() {
            try {
                openedAt = SystemClock.elapsedRealtime();
                ClassLoader cl = ctx.getClassLoader();
                Class<?> crg = cl.loadClass("crg"), ctb = cl.loadClass("ctb"), dvp = cl.loadClass("dvp"), dme = cl.loadClass("dme"),
                        cmj = cl.loadClass("cmj"), dvy = cl.loadClass("dvy"), cdv = cl.loadClass("cdv"), ceg = cl.loadClass("ceg"),
                        cmq = cl.loadClass("cmq");
                Object data = cmq.getConstructor(Context.class).newInstance(ctx);
                Object ext = dvp.getConstructor().newInstance();
                dvp.getMethod("d").invoke(ext); // constant-bitrate seeking for a header-less MP3
                Object factory = dme.getConstructor(cmj, dvy).newInstance(data, ext);
                Object pl = crg.getMethod("a").invoke(crg.getConstructor(Context.class).newInstance(ctx));
                PlayerTap.markOurs(pl);
                this.pl = pl;
                state = ctb.getMethod("n");
                pos = ctb.getMethod("r");
                play = ctb.getMethod("A", boolean.class);
                vol = ctb.getMethod("E", float.class);
                speed = ctb.getMethod("patch_setPlaybackParameters", float.class, float.class);
                Object mib = cdv.getConstructor().newInstance();
                cdv.getField("b").set(mib, Uri.fromFile(p.file));
                Object src = dme.getMethod("a", ceg).invoke(factory, cdv.getMethod("a").invoke(mib));
                long at = Math.max(0, Math.round(target(Automix.position(host))));
                ctb.getMethod("ae", List.class, long.class).invoke(pl, Collections.singletonList(src), at);
                ctb.getMethod("z").invoke(pl);
                vol.invoke(pl, 0f);
                lastVol = 0;
                justSeeked = true;
                AddonPlayback.playing(pl, p.addon, false);
                Log.i(TAG, "addon player for " + p);
            } catch (Throwable t) {
                Log.e(TAG, "addon player failed to open", t);
                end("could not open the file", true);
            }
        }

        void play(boolean on) {
            if (pl == null || playing == on) return;
            playing = on;
            // readings from before a pause don't line up with those after it
            ytClock.reset();
            ourClock.reset();
            // both players' first readings after a start jump about: not read for a moment
            if (on) settleUntil = SystemClock.elapsedRealtime() + 600;
            Log.i(TAG, p.id + (on ? ": plays on with YT" : pausedByYt ? ": paused with YT (its pause)" : ": paused with YT (its clock stopped)"));
            try {
                play.invoke(pl, on);
            } catch (Throwable ignored) {
            }
        }

        /** Our volume from YT's: what the gate gives YT's player without us, times our share and the gain. */
        void volume(float base) {
            if (pl == null || done) return;
            float want = base * share * gain;
            if (want > 1f && !cappedLogged) {
                cappedLogged = true;
                Log.w(TAG, String.format(Locale.US, "%s: the gain wants %.3f, the player stops at 1 (%.2f dB short)", p.id, want, 20 * Math.log10(want)));
            }
            float v = Math.min(1f, want);
            if (Math.abs(v - lastVol) < 1e-4f) return;
            lastVol = v;
            try {
                vol.invoke(pl, v);
            } catch (Throwable ignored) {
            }
        }

        void fade(float to, long ms) {
            fadeFrom = share;
            fadeTo = to;
            fadeAt = SystemClock.elapsedRealtime();
            fadeMs = ms;
        }

        private void setShare(float s) {
            if (s == share) return;
            share = s;
            // YT's player turned down by our share (through the gate), ours set from it
            Engine.reapply(host);
            volume(Engine.gateOut(host));
            AddonPlayback.audible(pl, s >= 0.5f);
        }

        void tick(long now) throws Exception {
            // the crossfade
            if (fadeMs > 0) {
                float k = Math.min(1f, (now - fadeAt) / (float) fadeMs);
                float x = k * k * (3 - 2 * k);
                setShare(fadeFrom + (fadeTo - fadeFrom) * x);
                if (k >= 1) fadeMs = 0;
            }
            int hs = (int) host.getClass().getMethod("patch_getPlaybackState").invoke(host);
            if (hs == 1 || hs == 4) {
                end(hs == 1 ? "YT's player stopped" : "the song ended", false);
                return;
            }
            double hd = Automix.duration(host) / 1000.0;
            if (hd > 0 && Math.abs(hd - p.ytDurationSec) > SAME_SONG_SEC) {
                end("another song on YT's player", false);
                return;
            }
            long yt = Automix.position(host);
            boolean fresh = yt != lastYt;
            if (fresh && pausedByYt && now - pausedAt < 400) {
                lastYt = yt;   // the pause's last reading
                moving = false;
            } else if (fresh) {
                lastYt = yt;
                lastYtAt = now;
                moving = true;
                pausedByYt = false;
            } else if (now - lastYtAt > 300 || pausedByYt) {
                moving = false;
            }
            int st = (int) state.invoke(pl);
            if (st == 1) {
                end("the addon player failed", true);
                return;
            }
            if (st == 4) {
                // the file ended before YouTube's song (its end is cut a little short)
                end("the file ended", false);
                return;
            }
            play(moving && target(yt) >= 0);
            if (!moving) return;
            if (st != 3) {
                if (stallFrom == 0) stallFrom = now;
                if (on && now - stallFrom > 1000) end("the addon player stalled", true);
                else if (!on && now - stallFrom > 15_000) end("the addon player never got ready", true);
                return;
            }
            stallFrom = 0;
            // follow YT's speed (automix's silent nudges on a held player); never while heard
            float ys = Engine.speedOf(host);
            if (nudgeUntil == 0 && Math.abs(ys - speedSet) > 0.0005f && share == 0) setSpeed(ys, now);
            if (nudgeUntil > 0) {
                if (now < nudgeUntil) return;
                nudgeUntil = 0;
                setSpeed(ys, now);
                return;
            }
            if (now < settleUntil) return;
            long ours = (long) pos.invoke(pl);
            // both players' readings move in steps (~250 ms on the emulator): each clock's line is
            // found from the moments its reading changes, and the two lines are compared at one time
            boolean ytNew = ytClock.see(yt, now, ys), ourNew = ourClock.see(ours, now, speedSet);
            if (TRACE && (ytNew || ourNew)) Log.i(TAG, "trace " + now + " yt " + yt + " ours " + ours + (ytNew ? " Y" : "") + (ourNew ? " O" : "") + (on ? " on" : ""));
            if (!ytNew || ytClock.n() < 6 || ourClock.n() < 6) return;
            long m = Math.round(ourClock.at(now, speedSet) - target(Math.round(ytClock.at(now, ys))));
            if (!on) {
                ytClock.reset();
                ourClock.reset();
            }
            if (!on) lock(m, yt, ys, now);
            else if (Math.abs(m) > 30) {
                // off YT's clock (a seek, a stall, a drift): back to YT's audio, re-lock silently
                relocks++;
                log.append(" off ").append(m).append(" ms;");
                Log.i(TAG, p.id + ": " + m + " ms off YT's clock: YT's audio while it locks again");
                on = false;
                fade(0f, Math.abs(m) > 250 ? 30 : FADE_OUT_MS);
                if (Math.abs(m) > 250) seek(yt, now);
            }
        }

        private void lock(long m, long yt, float ys, long now) {
            if (Math.abs(m) > 150 && seeks < 6) {
                // right after a seek the error is its latency: the next seek leads by it
                if (justSeeked && seeks > 0) seekLead -= m;
                log.append(" err ").append(m).append(';');
                seek(yt, now);
                return;
            }
            justSeeked = false;
            if (Math.abs(m) > 6) {
                // unheard: up to 8% off YT's speed for up to 1 s takes ~70% of the error
                double take = 0.7 * m;
                float sp = (float) Math.max(0.92, Math.min(1.08, 1 - take / 1000));
                long dur = Math.max(80, Math.min(1000, Math.abs(Math.round(take / (1 - sp)))));
                setSpeed(ys * sp, now);
                nudgeUntil = now + dur;
                nudges++;
                log.append(String.format(Locale.US, " err %d -> x%.3f %d ms;", m, sp, dur));
                return;
            }
            // in step; with the host held silent by a mix, wait for its handover
            if (Engine.gateOut(host) <= 0f && Engine.holding) {
                settleUntil = now + 100;
                return;
            }
            on = true;
            fade(1f, FADE_IN_MS);
            Log.i(TAG, String.format(Locale.US, "%s: in step (%d ms, %d seeks, lead %d ms, %d nudges, %d ms after opening): the addon's audio now;%s",
                    p.id, m, seeks, seekLead, nudges, SystemClock.elapsedRealtime() - openedAt, log));
            log.setLength(0);
        }

        private void seek(long yt, long now) {
            long at = Math.max(0, Math.round(target(yt)) + seekLead);
            if (pl instanceof SeekAccess) ((SeekAccess) pl).ayham_seekTo(at);
            seeks++;
            justSeeked = true;
            ytClock.reset();
            ourClock.reset();
            settleUntil = now + 250;
            log.append(" seek ").append(at).append(" (lead ").append(seekLead).append(");");
        }

        private void setSpeed(float s, long now) {
            speedSet = s;
            try {
                speed.invoke(pl, s, 1f);
            } catch (Throwable ignored) {
            }
            ytClock.reset();
            ourClock.reset();
            settleUntil = now + 300;
        }

        void end(String why, boolean failed) {
            if (done) return;
            done = true;
            share = 0;
            fadeMs = 0;
            try {
                Engine.reapply(host);
            } catch (Throwable ignored) {
            }
            Object x = pl;
            pl = null;
            if (x != null) {
                try {
                    x.getClass().getMethod("A", boolean.class).invoke(x, false);
                    x.getClass().getMethod("L").invoke(x);
                } catch (Throwable t) {
                    Log.w(TAG, "release failed", t);
                }
                PlayerTap.forget(x);
                AddonPlayback.stopped(x);
            }
            Log.i(TAG, p.id + ": YT's audio again (" + why + (relocks > 0 ? ", " + relocks + " re-locks" : "") + ")");
            if (failed) AddonPrep.dropped(p.id, why);
        }
    }
}
