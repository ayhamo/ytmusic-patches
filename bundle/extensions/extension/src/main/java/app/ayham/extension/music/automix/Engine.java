package app.ayham.extension.music.automix;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTimestamp;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

import com.music.bitchord.playback.loopmix.LoopMixRenderer;
import com.music.bitchord.playback.smart.MelSpectrogram;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import app.ayham.extension.music.spike.SeekAccess;
import app.ayham.extension.music.spike.SpikeE;

/**
 * Plays a {@link Mix}: BitChord's loop-mix renderer (Signalsmith Stretch, beat knots) for each deck,
 * one renderer per deck so each gets its own filters (the extension's: gain, 250 Hz shelf, 4 kHz
 * shelf, high-pass, low-pass, in Java), summed into our own AudioTrack.
 *
 * <p>The deck runs on YT's clock (YT's position against our track's timestamps). At mix 0 YT's
 * player is silenced through a gate on ExoPlayer.setVolume and our deck carries the outgoing song;
 * YT is asked for the next song at [askAt]; its player, held silent, is seeked and nudged onto the
 * incoming deck's timeline and takes over (the deck fading in 25 ms at the same song position) once
 * it is in time, from [elementAt] on.
 */
final class Engine {
    private static final String TAG = Automix.TAG;
    static final int SR = 48000;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final int BLOCK = 512, CONTROL = 64;

    // ---- the volume gate (patched into ExoPlayer.setVolume) ----
    static volatile boolean holding;
    static volatile Object released;

    /** The volume YT (or Morphe) last asked each player for, not counting ours: its loudness level. */
    private static final java.util.Map<Object, Float> ytAsked = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static boolean selfCall;

    static float ytVolume(Object player) {
        Float v = player == null ? null : ytAsked.get(player);
        return v == null ? 1f : v;
    }

    static float gate(Object player, float volume) {
        if (!selfCall && player != null) ytAsked.put(player, volume);
        if (!holding) return volume;
        return player == released ? volume : 0f;
    }

    interface Listener {
        void onMixStarted(Engine e);

        void onFinished(Engine e, boolean ok, String why);
    }

    final Mix m;
    private final Listener listener;
    private long hOut, hIn;
    private long firstFrame;
    private double inRegionEnd; // song seconds the incoming deck has audio for
    private double deadline;    // the last mix second the incoming deck has audio for (less a margin)
    private volatile boolean renderDone;
    private final Biquad[] fOut = chain(), fIn = chain();

    Object outPlayer, inPlayer;
    private AudioTrack track;
    private volatile long offsetFrames = Long.MIN_VALUE;
    private volatile boolean stop;
    private volatile long written;
    private volatile double fadeFrom = Double.NaN, fadeLen; // master fade (abort / another song)
    private final List<Double> offsets = new ArrayList<>();
    private boolean started, tookOver, asked, toasted, handedOver, finished;
    private double handoverAt = Double.NaN;
    private long lead = 260;
    private int seeks, nudges;
    private boolean steady;
    private long lastErr = Long.MIN_VALUE;
    private boolean aligned;
    private final StringBuilder alignLog = new StringBuilder();
    private long settleUntil, nudgeUntil, lastP = -1, lastAt;
    private float nudge = 1, setSpeed = 1;
    private final List<Long> errs = new ArrayList<>();
    private long lastSeekCheck;
    private String inIdSeen;

    private static Biquad[] chain() {
        return new Biquad[]{new Biquad(Biquad.LOWSHELF, SR, 250), new Biquad(Biquad.HIGHSHELF, SR, 4000),
                new Biquad(Biquad.HIGHPASS, SR, 0), new Biquad(Biquad.LOWPASS, SR, 0)};
    }

    private Engine(Mix m, Listener listener) {
        this.m = m;
        this.listener = listener;
    }

    // ---------------------------------------------------------------------------------------------
    // Building (background thread)

    private static final class Region {
        float[] left, right;
        double start;

        double end() {
            return start + left.length / (double) SR;
        }
    }

    private static Region region(Analyzer.Pcm pcm, double from, double to) throws Exception {
        long a = Math.max(pcm.firstFrame, (long) Math.floor(from * pcm.rate));
        float[][] lr = pcm.read(from, to);
        Region r = new Region();
        r.start = a / (double) pcm.rate;
        if (pcm.rate != SR) {
            r.left = MelSpectrogram.nativeResample(lr[0], pcm.rate, SR);
            r.right = MelSpectrogram.nativeResample(lr[1], pcm.rate, SR);
        } else {
            r.left = lr[0];
            r.right = lr[1];
        }
        return r;
    }

    /** The decks loaded and the renderers ready; null (logged) when the decoded audio doesn't cover the mix. */
    static Engine build(Context ctx, Mix m, Listener listener) {
        try {
            Models.loadNative();
            Analyzer.Pcm outPcm = Analyzer.tail(ctx, m.outId), inPcm = Analyzer.head(ctx, m.inId);
            if (outPcm == null || inPcm == null) {
                Log.w(TAG, "mix not built: decoded audio missing");
                return null;
            }
            if (m.out.needFrom < outPcm.start() - 0.01) {
                Log.w(TAG, String.format(Locale.US, "mix not built: the playing song is decoded from %.1f s, the mix needs it from %.1f s", outPcm.start(), m.out.needFrom));
                return null;
            }
            Region ro = region(outPcm, m.out.needFrom, m.out.needTo);
            Region ri = region(inPcm, Math.max(0, m.in.needFrom), m.in.needTo);
            Engine e = new Engine(m, listener);
            e.inRegionEnd = ri.end();
            if (m.in.trackAt(m.elementAt) + 2 > e.inRegionEnd) {
                Log.w(TAG, String.format(Locale.US, "mix not built: the next song is decoded to %.1f s, its player takes over at %.1f s", e.inRegionEnd, m.in.trackAt(m.elementAt)));
                return null;
            }
            double t = m.elementAt;
            while (m.in.trackAt(t) < e.inRegionEnd - 2 && t < m.elementAt + 120) t += 0.25;
            e.deadline = t;
            e.hOut = deck(LoopMixRenderer.OUT, m.out, ro);
            e.hIn = deck(LoopMixRenderer.IN, m.in, ri);
            e.firstFrame = Math.min(LoopMixRenderer.nativeFirstFrame(e.hOut), LoopMixRenderer.nativeFirstFrame(e.hIn));
            return e;
        } catch (Throwable t) {
            Log.e(TAG, "mix not built", t);
            return null;
        }
    }

    private static long deck(int index, Mix.Deck d, Region r) {
        long h = LoopMixRenderer.nativeCreate(SR);
        int n = d.knotT.length;
        double[] kf = new double[n], kp = new double[n];
        for (int k = 0; k < n; k++) {
            kf[k] = d.knotT[k] * SR;
            kp[k] = (d.knotP[k] - r.start) * SR;
        }
        boolean loop = d.loopB > d.loopA;
        LoopMixRenderer.nativeSetDeck(h, index, r.left, r.right, r.start,
                loop ? Math.round((d.loopA - r.start) * SR) : 0, loop ? Math.round((d.loopB - r.start) * SR) : 0,
                loop && d.release < 1e17 ? (d.release - r.start) * SR : (loop ? 1e18 : 0), kf, kp, d.runOn, d.stopAt, 0.4, 1f);
        LoopMixRenderer.nativePrepare(h);
        return h;
    }

    // ---------------------------------------------------------------------------------------------
    // Playback (main thread)

    /** Waits for the takeover: starts the engine ~3 s before it, while YT's player plays the song. */
    void arm(Object player) {
        outPlayer = player;
        long at = Math.round(m.takeoverSong * 1000);
        main.post(new Runnable() {
            @Override
            public void run() {
                if (stop) return;
                long now = Automix.position(outPlayer);
                if (now >= at - 3000 && now < at - 600) {
                    start();
                    return;
                }
                if (now >= at - 600) {
                    finish(false, "the takeover point passed before the engine was up (" + now + " ms)");
                    return;
                }
                main.postDelayed(this, 50);
            }
        });
    }

    private void start() {
        started = true;
        int min = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT);
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(SR)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(Math.max(min, 8192 * 8))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        // the user's equalizer preset on the decks too, so the takeover doesn't change the sound
        try {
            app.ayham.extension.music.spike.SpikeEq.attachDeck(track.getAudioSessionId());
        } catch (Throwable t) {
            Log.w(TAG, "equalizer not on the decks", t);
        }
        track.play();
        Thread render = new Thread(this::renderLoop, "Automix-render");
        render.setPriority(Thread.MAX_PRIORITY);
        render.start();
        main.post(new Runnable() {
            @Override
            public void run() {
                if (stop) return;
                try {
                    control();
                } catch (Throwable t) {
                    Log.e(TAG, "mix control failed", t);
                    finish(false, "control failed: " + t);
                }
                if (!stop) main.postDelayed(this, 5);
            }
        });
    }

    /**
     * Each deck plays at the volume YT asks of its player (YT's loudness normalisation goes through
     * ExoPlayer.setVolume, so the gate sees it), the extension's level match: the takeover and the
     * handover splice at YT's own level. The incoming deck plays at 1 until YT's next player
     * shows its volume, then glides there (~0.3 s).
     */
    private volatile float levelOutTarget = 1, levelInTarget = 1;
    private float levelOut = Float.NaN, levelIn = Float.NaN;
    private static final float LEVEL_GLIDE = (float) (1 - Math.exp(-1 / (0.3 * SR)));

    private void renderLoop() {
        float[] buf = new float[BLOCK * 2], bo = new float[BLOCK * 2], bi = new float[BLOCK * 2];
        long w = 0;
        try {
            while (!stop) {
                long off = offsetFrames;
                if (off == Long.MIN_VALUE || w + off < firstFrame) {
                    int n = BLOCK;
                    if (off != Long.MIN_VALUE) n = (int) Math.min(BLOCK, firstFrame - (w + off));
                    Arrays.fill(buf, 0f);
                    track.write(buf, 0, n * 2, AudioTrack.WRITE_BLOCKING);
                    w += n;
                    written = w;
                    continue;
                }
                long f = w + off;
                LoopMixRenderer.nativeRender(hOut, f, bo, 0, BLOCK);
                LoopMixRenderer.nativeRender(hIn, f, bi, 0, BLOCK);
                shape(m.out, fOut, bo, f);
                shape(m.in, fIn, bi, f);
                double fs = fadeFrom;
                float lo = levelOutTarget, li = levelInTarget;
                if (Float.isNaN(levelOut)) {
                    levelOut = lo;
                    levelIn = li;
                }
                for (int i = 0; i < BLOCK; i++) {
                    float g = 1;
                    if (!Double.isNaN(fs)) g = (float) Math.max(0, Math.min(1, 1 - ((f + i) / (double) SR - fs) / fadeLen));
                    levelOut += (lo - levelOut) * LEVEL_GLIDE;
                    levelIn += (li - levelIn) * LEVEL_GLIDE;
                    buf[2 * i] = (bo[2 * i] * levelOut + bi[2 * i] * levelIn) * g;
                    buf[2 * i + 1] = (bo[2 * i + 1] * levelOut + bi[2 * i + 1] * levelIn) * g;
                }
                track.write(buf, 0, BLOCK * 2, AudioTrack.WRITE_BLOCKING);
                w += BLOCK;
                written = w;
                if (!Double.isNaN(fs) && f / (double) SR > fs + fadeLen + 0.05) break;
            }
        } catch (Throwable t) {
            Log.e(TAG, "render failed", t);
        } finally {
            try {
                track.stop();
                app.ayham.extension.music.spike.SpikeEq.releaseDeck(track.getAudioSessionId());
                track.release();
            } catch (Throwable ignored) {
            }
            LoopMixRenderer.nativeDestroy(hOut);
            LoopMixRenderer.nativeDestroy(hIn);
            renderDone = true;
        }
    }

    /** A deck's gain and filters over one block (mix frame [frame] on). */
    private static void shape(Mix.Deck d, Biquad[] f, float[] buf, long frame) {
        for (int at = 0; at < BLOCK; at += CONTROL) {
            double t0 = (frame + at) / (double) SR, t1 = (frame + at + CONTROL) / (double) SR, tm = (t0 + t1) / 2;
            f[0].setValue(d.lowDb.at(tm));
            f[1].setValue(d.highDb.at(tm));
            f[2].setValue(d.hpHz.at(tm));
            f[3].setValue(d.lpHz.at(tm));
            for (Biquad b : f) b.process(buf, at, at + CONTROL);
            double g0 = d.gain.at(t0), g1 = d.gain.at(t1);
            for (int i = 0; i < CONTROL; i++) {
                float g = (float) (g0 + (g1 - g0) * i / CONTROL);
                buf[2 * (at + i)] *= g;
                buf[2 * (at + i) + 1] *= g;
            }
        }
    }

    private long lastClockP = Long.MIN_VALUE;
    private int clockReads;
    /** The last fresh reading of each player, as (its position - the decks' clock) in seconds. */
    private double freshOutAt = Double.NaN, freshInAt = Double.NaN;

    /**
     * One reading of YT's position against the decks' clock. Some streams' players move their
     * position only every ~250 ms and hold it in between, so only a reading where it has just
     * moved is fresh; the held ones would pull the median up to ~120 ms late. True once there are
     * enough readings (40 tries, of which 5 or more fresh) to lock on.
     */
    private boolean clockRead(long p, double heard) {
        boolean fresh = lastClockP != Long.MIN_VALUE && p != lastClockP;
        lastClockP = p;
        if (fresh) {
            offsets.add(p / 1000.0 - heard / SR);
            freshOutAt = p / 1000.0 - heard / SR;
        }
        clockReads++;
        return clockReads >= 40 && offsets.size() >= 5;
    }

    private final AudioTimestamp ts = new AudioTimestamp();

    private double heardFrames() {
        if (!track.getTimestamp(ts)) return -1;
        return ts.framePosition + (System.nanoTime() - ts.nanoTime) * SR / 1e9;
    }

    /** Main thread, every 5 ms. */
    private void control() {
        if (renderDone) {
            finish(false, null);
            return;
        }
        if (outPlayer != null) levelOutTarget = ytVolume(outPlayer);
        if (inPlayer != null) {
            float v = ytVolume(inPlayer);
            if (Math.abs(v - levelInTarget) > 0.001f)
                Log.i(TAG, String.format(Locale.US, "next song at YT's volume %.3f (%.1f dB): the incoming deck follows", v, 20 * Math.log10(Math.max(v, 1e-6))));
            levelInTarget = v;
        }
        double heard = heardFrames();
        if (heard < 0 || heard < SR * 0.1) return;
        if (offsetFrames == Long.MIN_VALUE) {
            long p = Automix.position(outPlayer);
            if (p < 0 || !clockRead(p, heard)) return;
            List<Double> s = new ArrayList<>(offsets);
            Collections.sort(s);
            double c = s.get(s.size() / 2);
            long off = Math.round((c - m.takeoverSong) * SR);
            double iqr = (s.get(s.size() * 3 / 4) - s.get(s.size() / 4)) * 1000;
            // YT's readings are loose just after a seek or a stall: wait for steady ones while time allows
            if (iqr > 40) {
                StringBuilder b = new StringBuilder();
                for (Double v : offsets) b.append(String.format(Locale.US, " %.0f", (v - c) * 1000));
                Log.i(TAG, "loose clock readings (ms from the median, in order):" + b);
            }
            if (iqr > 40 && -(heard + off) > SR * 1.5 && written + off + SR / 2 < firstFrame) {
                offsets.clear();
                clockReads = 0;
                return;
            }
            if (written + off >= firstFrame) {
                finish(false, "too late to start the decks before their pre-roll");
                return;
            }
            offsetFrames = off;
            offsets.clear();
            Log.i(TAG, String.format(Locale.US, "decks on YT's clock (%d fresh readings of %d, offset spread %.1f ms, IQR %.1f ms), takeover in %.2f s",
                    s.size(), clockReads, (s.get(s.size() - 1) - s.get(0)) * 1000, iqr, (-(heard + off)) / SR));
            clockReads = 0;
            return;
        }
        double mix = (heard + offsetFrames) / SR;
        if (!Double.isNaN(fadeFrom)) {
            if (mix > fadeFrom + fadeLen) finish(false, null);
            return;
        }

        // YT's clock can jump after the lock (a stall, a dropped second): keep checking it during the
        // silent pre-roll and move the decks onto it again, so the takeover splices where YT is
        if (!tookOver && mix < -0.4) {
            long p = Automix.position(outPlayer);
            if (p >= 0 && clockRead(p, heard)) {
                List<Double> s = new ArrayList<>(offsets);
                offsets.clear();
                clockReads = 0;
                Collections.sort(s);
                long off = Math.round((s.get(s.size() / 2) - m.takeoverSong) * SR);
                double spread = (s.get(s.size() * 3 / 4) - s.get(s.size() / 4)) * 1000;
                if (Math.abs(off - offsetFrames) > SR * 0.03 && spread < 40) {
                    Log.i(TAG, String.format(Locale.US, "YT's clock moved %+.0f ms: decks moved onto it", (off - offsetFrames) * 1000.0 / SR));
                    offsetFrames = off;
                    return;
                }
            }
        }
        if (!tookOver && mix >= -0.012) {
            tookOver = true;
            holding = true;
            released = null;
            setVolume(outPlayer, 0f);
            // YT's position from its last fresh reading, run on by the decks' clock (a single reading
            // now can be up to ~250 ms stale on some streams)
            double yt = (freshOutAt + heard / SR) * 1000;
            Log.i(TAG, String.format(Locale.US, "TAKEOVER at %.0f ms (deck at %.3f s, splice error %+.0f ms, YT's volume %.3f): %s", yt,
                    m.takeoverSong + mix, yt - (m.takeoverSong + mix) * 1000, ytVolume(outPlayer), m.note));
            listener.onMixStarted(this);
        }
        if (tookOver && !toasted && mix >= m.t0 - 0.05) {
            toasted = true;
            Automix.toast(m.title + " · " + m.sub);
        }
        // a seek on the old song before YT moves on: the mix is off its clock
        if (tookOver && !asked) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastSeekCheck > 100) {
                lastSeekCheck = now;
                long p = Automix.position(outPlayer);
                if (p >= 0 && Math.abs(p - (m.takeoverSong + mix) * 1000) > 1500) {
                    abort("the playing song was seeked", true);
                    return;
                }
            }
        }
        if (tookOver && !asked && mix >= m.askAt) {
            asked = true;
            if (inPlayer == null) {
                Log.i(TAG, String.format(Locale.US, "mix %.2f s: YT moves to the next song", mix));
                mediaNext();
            }
        }
        if (asked && inPlayer == null) pollIncoming();
        if (inPlayer != null && !handedOver) {
            checkSong();
            if (!Double.isNaN(fadeFrom)) return;
            if (Double.isNaN(handoverAt)) align(mix);
        }
        // hand over once the player is in time (or as late as the decoded audio allows)
        if (!handedOver && Double.isNaN(handoverAt) && inPlayer != null
                && ((aligned && mix >= m.elementAt - 0.3) || mix >= Math.min(m.elementAt + 8, deadline))) {
            handoverAt = mix + 0.3;
            LoopMixRenderer.nativeSetFadeOut(hIn, LoopMixRenderer.IN, m.in.trackAt(handoverAt));
        }
        if (!handedOver && !Double.isNaN(handoverAt) && mix >= handoverAt - 0.0125) {
            handedOver = true;
            if (setSpeed != 1) speed(inPlayer, 1f);
            released = inPlayer;
            restoreVolume(inPlayer);
            double yt = (freshInAt + mix) * 1000;
            Log.i(TAG, String.format(Locale.US, "HANDOVER at mix %.3f s: YT at %.0f ms, deck at %.3f s (splice error %+.0f ms, last median %s ms, YT's volume %.3f); alignment:%s",
                    mix, yt, m.in.trackAt(mix), yt - m.in.trackAt(mix) * 1000, lastErr == Long.MIN_VALUE ? "-" : String.valueOf(lastErr), ytVolume(inPlayer), alignLog));
        }
        if (!handedOver && inPlayer == null && mix >= deadline) {
            abort("YT's player for the next song never came", false);
            return;
        }
        if (handedOver && mix >= handoverAt + 0.6) finish(true, null);
    }

    /** No incoming player from Morphe's crossfade: YT moved on in the same player, or another one is playing. */
    private void pollIncoming() {
        try {
            Object p = SpikeE.current();
            if (p == null) return;
            long pos = Automix.position(p);
            if (p != outPlayer && pos >= 0) onIncoming(p);
            else if (p == outPlayer && pos >= 0 && pos < 10_000 && m.takeoverSong > 20) onIncoming(p);
        } catch (Throwable ignored) {
        }
    }

    /** YT's next player: held silent for the takeover. True when this mix takes it. */
    boolean onIncoming(Object player) {
        if (!tookOver || inPlayer != null || stop) return false;
        if (!asked) {
            main.post(() -> abort("the song was skipped during the mix", true));
            return false;
        }
        inPlayer = player;
        setVolume(player, 0f);
        settleUntil = 0;
        nudgeUntil = 0;
        errs.clear();
        alignReads = 0;
        Log.i(TAG, "incoming player held silent at " + Automix.position(player) + " ms");
        return true;
    }

    /** The queue played another song than the mix is for: the deck fades out, the player fades in. */
    private void checkSong() {
        String id = Automix.currentId();
        if (id == null || id.equals(inIdSeen)) return;
        inIdSeen = id;
        if (id.equals(m.inId)) return;
        Log.i(TAG, "the queue played " + id + " instead of " + m.inId + ": crossfading into it as it is");
        if (setSpeed != 1) speed(inPlayer, 1f);
        released = inPlayer;
        Automix.rampVolume(inPlayer, 1.5);
        fadeOut(1.5);
        handedOver = true;
    }

    private void fadeOut(double sec) {
        double heard = heardFrames();
        double mix = heard < 0 || offsetFrames == Long.MIN_VALUE ? 0 : (heard + offsetFrames) / SR;
        fadeLen = sec;
        fadeFrom = mix + 0.2;
    }

    /** Ends the mix early: the deck fades out quickly and YT's players are let go. */
    void abort(String why, boolean restoreOut) {
        if (stop || finished) return;
        Log.w(TAG, "mix aborted: " + why);
        holding = false;
        released = null;
        Object p = inPlayer != null ? inPlayer : outPlayer;
        if (p != null && restoreOut) restoreVolume(p);
        if (inPlayer != null) {
            if (setSpeed != 1) speed(inPlayer, 1f);
            restoreVolume(inPlayer);
        }
        if (track == null || offsetFrames == Long.MIN_VALUE) {
            finish(false, why);
            return;
        }
        fadeOut(0.05);
        finishReason = why;
    }

    private String finishReason;

    /** Pause: everything stops at once. */
    void stopNow(String why) {
        if (stop || finished) return;
        Log.i(TAG, "mix stopped: " + why);
        holding = false;
        released = null;
        if (outPlayer != null) restoreVolume(outPlayer);
        if (inPlayer != null) {
            if (setSpeed != 1) speed(inPlayer, 1f);
            restoreVolume(inPlayer);
        }
        finish(false, why);
    }

    private void finish(boolean ok, String why) {
        if (finished) return;
        finished = true;
        stop = true;
        if (why == null) why = finishReason;
        holding = false;
        released = null;
        if (inPlayer != null && setSpeed != 1) speed(inPlayer, 1f);
        if (!started) {
            LoopMixRenderer.nativeDestroy(hOut);
            LoopMixRenderer.nativeDestroy(hIn);
        }
        if (ok) Log.i(TAG, "MIX DONE (" + m.style + ")");
        listener.onFinished(this, ok, why);
    }

    boolean running() {
        return !finished;
    }

    boolean tookOverOnce() {
        return tookOver;
    }

    /** A loop mix between its takeover and handover: what is heard is no longer YT's position. */
    boolean loopHolding() {
        return tookOver && !handedOver && "loop".equals(m.style);
    }

    // ---------------------------------------------------------------------------------------------
    // The incoming player onto the deck's timeline

    /**
     * Seeks the silent incoming player to where the deck is, then nudges its speed (around the deck's
     * own rate) until it is within a few ms. YT's position readings jump now and then, so each check
     * is the median of 40 readings (200 ms) taken once it plays steadily.
     */
    private boolean measuredSinceSeek;
    private long lastAlignP = -1;
    private int alignReads;

    private void align(double mix) {
        long now = SystemClock.elapsedRealtime();
        double want = m.in.trackAt(mix);
        float base = (float) m.in.rateAt(mix);
        // while the incoming deck still loops, the player (silent) runs on to where the deck will be
        // once the loop lets go, not round the loop with it (the extension's trackAt(tk - 1.5))
        if (m.in.loopB > m.in.loopA && mix < m.elementAt) {
            base = (float) m.in.rateAt(m.elementAt);
            want = m.in.trackAt(m.elementAt) - (m.elementAt - mix) * base;
        }
        if (Math.abs(base * nudge - setSpeed) > 0.0005 && nudgeUntil == 0) {
            setSpeed = base * nudge;
            speed(inPlayer, setSpeed);
        }
        if (seeks == 0) {
            seek(want, base, now);
            return;
        }
        if (nudgeUntil > 0) {
            if (now < nudgeUntil) return;
            nudge = 1;
            setSpeed = base;
            speed(inPlayer, base);
            nudgeUntil = 0;
            settleUntil = now + 300;
            errs.clear();
            alignReads = 0;
            return;
        }
        if (now < settleUntil) return;
        long p = Automix.position(inPlayer);
        if (p < 0) return;
        if (!steady) {
            if (lastP >= 0 && now - lastAt >= 250) {
                double rate = (p - lastP) / (double) (now - lastAt) / base;
                if (rate > 0.8 && rate < 1.2) steady = true;
                lastP = p;
                lastAt = now;
            } else if (lastP < 0) {
                lastP = p;
                lastAt = now;
            }
            if (!steady) return;
        }
        // only a reading where YT's position has just moved is fresh (some streams move it every ~250 ms)
        boolean fresh = lastAlignP >= 0 && p != lastAlignP;
        lastAlignP = p;
        alignReads++;
        if (fresh) {
            errs.add(Math.round(p - want * 1000));
            freshInAt = p / 1000.0 - mix;
        }
        if (alignReads < 40 || errs.size() < 5) return;
        alignReads = 0;
        List<Long> sorted = new ArrayList<>(errs);
        Collections.sort(sorted);
        long err = sorted.get(sorted.size() / 2);
        errs.clear();
        alignReads = 0;
        lastErr = err;
        boolean firstAfterSeek = !measuredSinceSeek;
        measuredSinceSeek = true;
        aligned = Math.abs(err) <= 15;
        if (Math.abs(err) <= 8) {
            alignLog.append(" err ").append(err).append(" ms (ok);");
            settleUntil = now + 400;
            return;
        }
        if (Math.abs(err) > 600 && seeks < 3) {
            alignLog.append(" err ").append(err).append(" ms;");
            // right after a seek the error is the seek's latency; later on it is a jump of YT's own
            if (firstAfterSeek) lead -= err;
            seek(want, base, now);
            return;
        }
        // a speed nudge (no re-buffer): ~70% of the error over up to 1 s, at most 8% off the deck's
        // rate; the player is silent until the handover, so its speed can move this much unheard
        double T = 1;
        double take = 0.7 * err;
        float sp = (float) Math.max(0.92, Math.min(1.08, 1 - take / (T * 1000)));
        long dur = Math.round(take / (1 - sp));
        dur = Math.max(100, Math.min(Math.round(T * 1000), Math.abs(dur)));
        nudge = sp;
        setSpeed = base * sp;
        speed(inPlayer, setSpeed);
        nudgeUntil = now + dur;
        nudges++;
        alignLog.append(String.format(Locale.US, " err %d ms -> x%.3f for %d ms;", err, sp, dur));
    }

    private void seek(double want, float base, long now) {
        long target = Math.max(0, Math.round(want * 1000) + Math.round(lead * base));
        if (inPlayer instanceof SeekAccess) ((SeekAccess) inPlayer).ayham_seekTo(target);
        seeks++;
        measuredSinceSeek = false;
        steady = false;
        aligned = false;
        lastP = -1;
        alignLog.append(" seek ").append(target).append(" (lead ").append(lead).append(')');
        errs.clear();
        alignReads = 0;
        settleUntil = now + 300;
    }

    // ---- YT's player ----

    static void setVolume(Object player, float v) {
        selfCall = true;
        try {
            player.getClass().getMethod("patch_setVolume", float.class).invoke(player, v);
        } catch (Throwable t) {
            Log.w(TAG, "setVolume failed", t);
        } finally {
            selfCall = false;
        }
    }

    /** Gives a player back the volume YT asked for. */
    static void restoreVolume(Object player) {
        setVolume(player, ytVolume(player));
    }

    static void speed(Object player, float s) {
        try {
            player.getClass().getMethod("patch_setPlaybackParameters", float.class, float.class).invoke(player, s, 1f);
        } catch (Throwable t) {
            Log.w(TAG, "speed failed", t);
        }
    }

    private static void mediaNext() {
        Automix.askedAt = SystemClock.elapsedRealtime();
        AudioManager am = (AudioManager) Automix.context().getSystemService(Context.AUDIO_SERVICE);
        long now = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT, 0));
    }
}
