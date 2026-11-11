package app.ayham.extension.music.spike;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.Equalizer;
import android.media.audiofx.PresetReverb;
import android.os.Build;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * Throwaway spike (extension parity: equalizer). YT Music announces each player's audio session
 * with the standard OPEN_AUDIO_EFFECT_CONTROL_SESSION broadcast (one per crossfade player); an
 * Equalizer (and a room reverb) is attached to every announced session, released on CLOSE.
 * Property eq = flat | bass | vocals | night | room.
 */
public final class SpikeEq {
    private static final String TAG = "AyhamSpikeEq";
    private static final Map<Integer, AudioEffect[]> effects = new HashMap<>();
    private static final Map<Integer, String> presets = new HashMap<>();
    /** Sessions in the order YT opened them; the last is the newest player. */
    private static final java.util.List<Integer> order = new java.util.ArrayList<>();
    private static boolean registered;

    private SpikeEq() {
    }

    static synchronized void register(Context context) {
        if (registered) return;
        registered = true;
        IntentFilter filter = new IntentFilter();
        filter.addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
        filter.addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                int session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1);
                String from = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME);
                boolean open = AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION.equals(intent.getAction());
                Log.i(TAG, (open ? "open" : "close") + " session " + session + " from " + from);
                if (session <= 0) return;
                synchronized (SpikeEq.class) {
                    if (open) live.add(session); else live.remove(session);
                }
                if (open) attach(session); else release(session);
            }
        };
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        else context.registerReceiver(receiver, filter);
        // a change in the settings applies at once to every open session
        prefs = context.getSharedPreferences("morphe_prefs", Context.MODE_PRIVATE);
        prefs.registerOnSharedPreferenceChangeListener(prefsListener);
        Log.i(TAG, "listening for audio sessions");
    }

    private static android.content.SharedPreferences prefs;
    private static final android.content.SharedPreferences.OnSharedPreferenceChangeListener prefsListener = (sp, key) -> {
        if (key != null && key.startsWith("ayham_")) SpikeA.invalidate();
        if (!"ayham_eq".equals(key) && !"ayham_eq_preset".equals(key)) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(SpikeEq::reapply, 100);
    };

    /** The settings changed: every open session (YT's players and automix's decks) takes the new preset. */
    private static synchronized void reapply() {
        SpikeA.invalidate();
        // every session still open, with or without effects now (the switch may have been off)
        java.util.List<Integer> sessions = new java.util.ArrayList<>(live);
        for (Integer s : decks) if (!sessions.contains(s)) sessions.add(s);
        for (Integer s : new java.util.ArrayList<>(effects.keySet())) if (!sessions.contains(s)) sessions.add(s);
        for (Integer s : sessions) {
            boolean deck = decks.contains(s);
            release(s);
            if (deck) decks.add(s);
            attach(s);
        }
        Log.i(TAG, "settings changed: " + sessions.size() + " sessions now on '" + SpikeA.props().getProperty("eq", "off") + "'");
    }

    /** Automix's own deck output: the same preset as YT's players, so a mix doesn't change the sound. */
    private static final java.util.Set<Integer> decks = new java.util.HashSet<>();
    /** YT's sessions announced open and not yet closed, in the order they opened. */
    private static final java.util.Set<Integer> live = new java.util.LinkedHashSet<>();

    public static synchronized void attachDeck(int session) {
        decks.add(session);
        attach(session);
        order.remove((Integer) session);
    }

    public static synchronized void releaseDeck(int session) {
        decks.remove(session);
        release(session);
    }

    private static synchronized void attach(int session) {
        if (effects.containsKey(session)) return;
        String preset = SpikeA.props().getProperty("eq", "").trim();
        if (preset.isEmpty()) {
            Log.i(TAG, "session " + session + ": equalizer switched off, not attached");
            return;
        }
        try {
            Equalizer eq = new Equalizer(0, session);
            short bands = eq.getNumberOfBands();
            short[] range = eq.getBandLevelRange();
            StringBuilder levels = new StringBuilder();
            for (short b = 0; b < bands; b++) {
                int hz = eq.getCenterFreq(b) / 1000;
                // The extension's 3 bands (low shelf 200 Hz, mid 1 kHz, high shelf 3.2 kHz), in dB.
                int db = hz < 400 ? low(preset) : hz < 2500 ? mid(preset) : high(preset);
                short mB = (short) Math.max(range[0], Math.min(range[1], db * 100));
                eq.setBandLevel(b, mB);
                levels.append(hz).append("Hz=").append(mB / 100).append("dB ");
            }
            eq.setEnabled(true);
            AudioEffect reverb = null;
            if (preset.equals("room")) {
                PresetReverb r = new PresetReverb(0, session);
                r.setPreset(PresetReverb.PRESET_MEDIUMROOM);
                r.setEnabled(true);
                reverb = r;
            }
            effects.put(session, new AudioEffect[]{eq, reverb});
            presets.put(session, preset);
            order.remove((Integer) session);
            order.add(session);
            Log.i(TAG, "session " + session + ": equalizer '" + preset + "' on (" + bands + " bands, enabled="
                    + eq.getEnabled() + ") " + levels + (reverb != null ? "+ room reverb" : ""));
        } catch (Throwable t) {
            Log.e(TAG, "equalizer on session " + session + " failed", t);
        }
    }

    private static synchronized void release(int session) {
        order.remove((Integer) session);
        presets.remove(session);
        AudioEffect[] e = effects.remove(session);
        if (e == null) return;
        for (AudioEffect a : e) if (a != null) a.release();
        Log.i(TAG, "session " + session + ": effects released");
    }

    /** Newest open session, or -1. */
    static synchronized int newest() {
        return order.isEmpty() ? -1 : order.get(order.size() - 1);
    }

    /** The session opened before the newest, or -1. */
    static synchronized int previous() {
        return order.size() < 2 ? -1 : order.get(order.size() - 2);
    }

    /**
     * Automix: the preset plus a bass offset (bands under 400 Hz) and a treble offset (bands from
     * 2.5 kHz), in dB, clamped to the equalizer's range. Used for the bass swap and filter sweeps.
     */
    static synchronized boolean shape(int session, int bassDb, int trebleDb) {
        AudioEffect[] e = effects.get(session);
        if (e == null || !(e[0] instanceof Equalizer)) return false;
        try {
            Equalizer eq = (Equalizer) e[0];
            String preset = presets.get(session);
            short[] range = eq.getBandLevelRange();
            for (short b = 0; b < eq.getNumberOfBands(); b++) {
                int hz = eq.getCenterFreq(b) / 1000;
                int db = hz < 400 ? low(preset) + bassDb : hz < 2500 ? mid(preset) : high(preset) + trebleDb;
                eq.setBandLevel(b, (short) Math.max(range[0], Math.min(range[1], db * 100)));
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "shape failed on session " + session, t);
            return false;
        }
    }

    // Presets from the extension: Bass Boost +8/-1/+1, Clear Vocals -3/+5/+3, Night Mode -5/+2/-2.
    private static int low(String p) {
        return p.equals("bass") ? 8 : p.equals("vocals") ? -3 : p.equals("night") ? -5 : 0;
    }

    private static int mid(String p) {
        return p.equals("bass") ? -1 : p.equals("vocals") ? 5 : p.equals("night") ? 2 : 0;
    }

    private static int high(String p) {
        return p.equals("bass") ? 1 : p.equals("vocals") ? 3 : p.equals("night") ? -2 : 0;
    }
}
