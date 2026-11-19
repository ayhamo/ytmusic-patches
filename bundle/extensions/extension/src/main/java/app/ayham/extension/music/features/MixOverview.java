package app.ayham.extension.music.features;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import app.ayham.extension.music.automix.Automix;
import app.ayham.extension.music.automix.MixOverride;
import app.ayham.extension.music.automix.Overview;
import app.ayham.extension.music.automix.Waves;

/**
 * The Automix overview and transition editor (the YT Music Extension's automix-overview.js,
 * spec/automix/editor.md): the coming mix, or the one playing, drawn on its own clock. Each song
 * gets a 3-band waveform (low, mid, high), dimmed outside the mix; the mix is framed, with its beat
 * grid (bars brighter) and each song's volume, EQ and filter curves from the plan. For each pair:
 * a preset (or custom lanes under "Customize"), beat match, a length, and the window (each song
 * dragged under the frame, snapped to its downbeats, beats after a long-press, nudged in 10 ms),
 * kept (or locked) for the next time the pair plays. Preview jumps to just before the mix.
 * Opened from the "..." menu of the title row, or by tapping the automix line on the art.
 */
final class MixOverview {
    private static final String TAG = "AyhamOverview";
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final int LOW = 0xFF2E45E6, MID = 0xFFE08B35, HIGH = 0xFFF1DDC4, FRAME = 0xFF3A9BDC;
    /** editor.md §2: the lanes' colours, the same in both apps. */
    private static final int VOLUME = 0xFF4C8DFF, EQ = 0xFFF5C542, EFFECT = 0xFFA779FF;
    private static final float OUTSIDE_ALPHA = 0.32f;
    /** editor.md §2: id, name, chip colour (Echo out is reserved, §5, and not shown). */
    private static final String[] PRESETS = {"auto", "smooth", "rise", "blend", "wave", "melt", "slam", "loop", "custom"};
    private static final String[] PRESET_NAMES = {"Auto", "Smooth", "Rise", "Blend", "Wave", "Melt", "Slam", "Loop", "Custom"};
    private static final int[] PRESET_COLORS = {FRAME, 0xFF4C8DFF, 0xFFA779FF, 0xFFF5C542, 0xFFA779FF, 0xFFA779FF, 0xFFE5534B, 0xFF3FB950, FRAME};
    private static final String[] MATCH = {"auto", "sync", "glide", "off"};
    private static final String[] MATCH_NAMES = {"Auto", "Sync", "Glide", "Off"};
    private static final int[] BARS = {0, 2, 4, 8, 16, 32};
    private static final String[] BAR_LABELS = {"Auto", "2", "4", "8", "16", "32"};
    private static final String[] LANES = {"volume", "eq", "effect"};
    private static final String[] LANE_TITLES = {"Volume", "EQ", "Effect"};
    private static final int NUDGE_MS = 10, NUDGE_MAX_MS = 2000;
    private static final long FRAME_MS = 50, LONG_PRESS_MS = 400;
    private static final double MAX_ZOOM = 8;

    private static Dialog dialog;
    private static Activity owner;

    /** Drops the dialog and its views, so they don't keep the activity alive. */
    private static void forget() {
        dialog = null;
        owner = null;
        sub = msg = clock = note = preview = workText = lock = reset = badge = winReset = customToggle = null;
        songsRow = work = customBox = null;
        workBar = null;
        view = null;
        drag = null;
        java.util.Arrays.fill(presetChips, null);
        java.util.Arrays.fill(matchChips, null);
        java.util.Arrays.fill(barChips, null);
        java.util.Arrays.fill(laneChips, null);
        java.util.Arrays.fill(nudgeChips, null);
    }

    private static TextView sub, msg, clock, note, preview, workText, lock, reset, badge, winReset, customToggle;
    private static LinearLayout songsRow, work, customBox;
    private static android.widget.ProgressBar workBar;
    private static MixView view;
    private static final TextView[] presetChips = new TextView[PRESETS.length], matchChips = new TextView[MATCH.length],
            barChips = new TextView[BARS.length], laneChips = new TextView[LANES.length], nudgeChips = new TextView[4];
    private static String songsSig = "";
    /** A refusal or a limit hit by the last edit, shown until the next one. */
    private static String refusedNote = "";
    private static boolean previewed;
    private static long tagsAt;
    private static String tagsSig = "";
    private static final Map<String, Bitmap> covers = new HashMap<>();

    private MixOverview() {
    }

    static void show(Activity a) {
        // a dialog of an activity that was destroyed while it showed never gets its dismiss
        if (dialog != null && owner != null && owner.isDestroyed()) forget();
        if (dialog != null && dialog.isShowing()) return;
        owner = a;
        Log.i(TAG, "overview opened");
        Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        int p = dp(a, 18);
        card.setPadding(p, p, p, p);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF161618);
        bg.setCornerRadius(dp(a, 24));

        // head: title, the Custom badge, what the mix is, close
        LinearLayout head = new LinearLayout(a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(a);
        titles.setOrientation(LinearLayout.VERTICAL);
        LinearLayout titleRow = new LinearLayout(a);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(text(a, "Automix", 20, 0xFFFFFFFF, true));
        badge = text(a, "Custom", 11, 0xFFFFFFFF, true);
        badge.setPadding(dp(a, 8), dp(a, 2), dp(a, 8), dp(a, 2));
        GradientDrawable bb = new GradientDrawable();
        bb.setCornerRadius(dp(a, 10));
        bb.setColor(0x33FFFFFF);
        badge.setBackground(bb);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.leftMargin = dp(a, 8);
        titleRow.addView(badge, bl);
        badge.setVisibility(View.GONE);
        titles.addView(titleRow);
        sub = text(a, "", 13, 0xB3FFFFFF, false);
        titles.addView(sub);
        head.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView close = text(a, "×", 26, 0xCCFFFFFF, false);
        close.setPadding(dp(a, 12), 0, dp(a, 4), 0);
        close.setOnClickListener(v -> d.dismiss());
        close.setContentDescription("Close");
        head.addView(close);
        card.addView(head);

        songsRow = new LinearLayout(a);
        songsRow.setGravity(Gravity.CENTER_VERTICAL);
        songsRow.setPadding(0, dp(a, 14), 0, dp(a, 12));
        card.addView(songsRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // the well: the drawing, or why there is none
        android.widget.FrameLayout well = new android.widget.FrameLayout(a);
        view = new MixView(a);
        well.addView(view, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 200)));
        msg = text(a, "", 14, 0xB3FFFFFF, false);
        msg.setGravity(Gravity.CENTER);
        well.addView(msg, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 200)));
        card.addView(well);

        // work still running for the pair: which song is analysed, and how far
        work = new LinearLayout(a);
        work.setOrientation(LinearLayout.VERTICAL);
        work.setPadding(0, dp(a, 8), 0, 0);
        workText = text(a, "", 12, 0xE6FFFFFF, false);
        work.addView(workText);
        workBar = new android.widget.ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        workBar.setMax(1000);
        workBar.setProgressTintList(android.content.res.ColorStateList.valueOf(FRAME));
        workBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x33FFFFFF));
        workBar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(FRAME));
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 6));
        wl.topMargin = dp(a, 4);
        work.addView(workBar, wl);
        work.setVisibility(View.GONE);
        card.addView(work, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // legend and the clock
        LinearLayout legend = new LinearLayout(a);
        legend.setGravity(Gravity.CENTER_VERTICAL);
        legend.setPadding(0, dp(a, 8), 0, dp(a, 6));
        int[] keys = {LOW, MID, HIGH, VOLUME, EQ, EFFECT};
        String[] names = {"Low", "Mid", "High", "Volume", "EQ", "Effect"};
        for (int i = 0; i < keys.length; i++) {
            View sw = new View(a);
            GradientDrawable g = new GradientDrawable();
            g.setColor(keys[i]);
            g.setCornerRadius(dp(a, 2));
            sw.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(a, 9), dp(a, 9));
            lp.rightMargin = dp(a, 3);
            legend.addView(sw, lp);
            TextView t = text(a, names[i], 11, 0xB3FFFFFF, false);
            t.setPadding(0, 0, dp(a, 7), 0);
            legend.addView(t);
        }
        clock = text(a, "", 12, 0xE6FFFFFF, false);
        clock.setGravity(Gravity.END);
        legend.addView(clock, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(legend);

        // the pair's edit (editor.md): preset, its lanes, beat match, length, window
        card.addView(chipRow(a, "Preset", PRESETS.length, i -> PRESET_NAMES[i], presetChips, MixOverview::pickPreset));
        // the first chip reads "Auto · <what the planner chose>": as wide as its widest name, so the
        // chips after it stay where they are when the name changes
        TextView first = presetChips[0];
        String widest = SteadyLayout.widest(Overview.AUTO_NAMES, x -> first.getPaint().measureText(x));
        first.setGravity(Gravity.CENTER);
        first.setMinWidth((int) Math.ceil(first.getPaint().measureText(widest)) + first.getPaddingLeft() + first.getPaddingRight());
        customToggle = text(a, "Customize ▸", 13, 0xB3FFFFFF, true);
        customToggle.setPadding(0, dp(a, 8), 0, dp(a, 2));
        customToggle.setOnClickListener(v -> {
            boolean open = customBox.getVisibility() != View.VISIBLE;
            if (!open) card.setMinimumHeight(0); // the user folded it away: the sheet may shrink
            customBox.setVisibility(open ? View.VISIBLE : View.GONE);
            customToggle.setText(open ? "Customize ▾" : "Customize ▸");
        });
        card.addView(customToggle);
        customBox = new LinearLayout(a);
        customBox.setOrientation(LinearLayout.VERTICAL);
        customBox.setVisibility(View.GONE);
        int[] laneColors = {VOLUME, EQ, EFFECT};
        for (int k = 0; k < LANES.length; k++) {
            LinearLayout row = new LinearLayout(a);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(a, 4), 0, 0);
            TextView l = text(a, LANE_TITLES[k], 13, laneColors[k], true);
            row.addView(l, new LinearLayout.LayoutParams(dp(a, 64), ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView c = chip(a, "…");
            int lane = k;
            c.setOnClickListener(v -> {
                if (!v.isEnabled()) return;
                PopupMenu menu = new PopupMenu(v.getContext(), v);
                List<String> opts = MixOverride.options(LANES[lane]);
                for (int i = 0; i < opts.size(); i++) menu.getMenu().add(0, i, i, shapeName(opts.get(i)));
                menu.setOnMenuItemClickListener(item -> {
                    pickLane(lane, opts.get(item.getItemId()));
                    return true;
                });
                menu.show();
            });
            laneChips[k] = c;
            row.addView(c);
            customBox.addView(row);
        }
        card.addView(customBox);
        card.addView(chipRow(a, "Beat match", MATCH.length, i -> MATCH_NAMES[i], matchChips, i -> {
            MixOverride o = current();
            if (o == null) return;
            o.beatMatch = i == 0 ? null : MATCH[i];
            save(o);
        }));
        card.addView(chipRow(a, "Length", BARS.length, i -> BAR_LABELS[i], barChips, i -> {
            MixOverride o = current();
            if (o == null) return;
            o.bars = BARS[i];
            save(o);
        }));
        // the window: nudges of each song, and back to where the planner puts it
        LinearLayout win = new LinearLayout(a);
        win.setGravity(Gravity.CENTER_VERTICAL);
        win.setPadding(0, dp(a, 6), 0, 0);
        win.addView(text(a, "Window", 13, 0xB3FFFFFF, false), new LinearLayout.LayoutParams(dp(a, 64), ViewGroup.LayoutParams.WRAP_CONTENT));
        HorizontalScrollView ws = new HorizontalScrollView(a);
        ws.setHorizontalScrollBarEnabled(false);
        LinearLayout wc = new LinearLayout(a);
        String[] nudgeNames = {"A −10 ms", "A +10 ms", "B −10 ms", "B +10 ms"};
        for (int i = 0; i < 4; i++) {
            TextView c = chip(a, nudgeNames[i]);
            int k = i;
            c.setOnClickListener(v -> {
                if (v.isEnabled()) nudge(k < 2, k % 2 == 0 ? -1 : 1);
            });
            nudgeChips[i] = c;
            wc.addView(c, chipLp(a));
        }
        winReset = chip(a, "Auto");
        winReset.setOnClickListener(v -> {
            MixOverride o = current();
            if (o == null || !v.isEnabled()) return;
            o.out = null;
            o.in = null;
            save(o);
        });
        wc.addView(winReset, chipLp(a));
        ws.addView(wc);
        win.addView(ws, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(win);

        LinearLayout actions = new LinearLayout(a);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(a, 10), 0, 0);
        preview = chip(a, "▶ Preview");
        preview.setOnClickListener(v -> {
            Overview s = Automix.overview();
            if (s.why == null && s.editable && Automix.seekTo(s.previewAt)) {
                previewed = true;
                preview.setText("↻ Play again");
            }
        });
        actions.addView(preview, chipLp(a));
        lock = chip(a, "Lock");
        lock.setOnClickListener(v -> {
            MixOverride o = current();
            if (o == null || !v.isEnabled()) return;
            o.lock = !o.lock;
            save(o);
        });
        actions.addView(lock, chipLp(a));
        reset = chip(a, "Reset");
        reset.setContentDescription("Reset to Auto");
        reset.setOnClickListener(v -> {
            Overview s = Automix.overview();
            if (s.pairKey != null && v.isEnabled()) {
                refusedNote = "";
                Automix.setOverride(s.pairKey, null);
                if (view != null) view.sig = "";
            }
        });
        actions.addView(reset, chipLp(a));
        card.addView(actions);
        note = text(a, "", 12, 0xB3FFFFFF, false);
        note.setPadding(0, dp(a, 8), 0, 0);
        card.addView(note);

        // the sheet sits on the bottom: it only grows while open, so nothing in it moves down under a
        // finger when a shorter note or warning comes (SteadyLayout)
        card.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int keep = SteadyLayout.hold(v.getMinimumHeight(), b - t);
            if (keep != v.getMinimumHeight()) v.post(() -> v.setMinimumHeight(keep));
        });
        sub.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int keep = SteadyLayout.hold(v.getMinimumHeight(), b - t);
            if (keep != v.getMinimumHeight()) v.post(() -> v.setMinimumHeight(keep));
        });

        // a tall editor on a short screen scrolls; the drawing keeps its drags (MixView asks the parent)
        ScrollView scroll = new ScrollView(a);
        scroll.setBackground(bg);
        scroll.setClipToOutline(true);
        scroll.addView(card);
        d.setContentView(scroll);
        d.setCanceledOnTouchOutside(true);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(a.getResources().getDisplayMetrics().widthPixels - 2 * dp(a, 12), WindowManager.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.getAttributes().y = dp(a, 24);
        }
        d.setOnDismissListener(x -> {
            if (dialog == d) forget();
            Log.i(TAG, "overview closed");
        });
        dialog = d;
        songsSig = "";
        refusedNote = "";
        previewed = false;
        d.show();
        main.post(MixOverview::frame);
    }

    private static LinearLayout.LayoutParams chipLp(Context a) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(a, 5);
        return lp;
    }

    /** "Center bass swap" from centerBassSwap. */
    static String shapeName(String id) {
        switch (id) {
            case "lpOut": return "Low-pass out";
            case "hpIn": return "High-pass in";
            case "lpBoth": return "Low-pass both";
            case "hpBoth": return "High-pass both";
            case "lpInHpOut": return "Low-pass in, high-pass out";
            case "threeBandFade": return "3-band fade";
            default:
                StringBuilder b = new StringBuilder();
                for (char ch : id.toCharArray()) {
                    if (Character.isUpperCase(ch)) b.append(' ').append(Character.toLowerCase(ch));
                    else b.append(b.length() == 0 ? Character.toUpperCase(ch) : ch);
                }
                return b.toString();
        }
    }

    private interface IntFn<T> {
        T at(int i);
    }

    private interface IntAct {
        void at(int i);
    }

    private static View chipRow(Activity a, String label, int n, IntFn<String> names, TextView[] into, IntAct onPick) {
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(a, 6), 0, 0);
        TextView l = text(a, label, 13, 0xB3FFFFFF, false);
        row.addView(l, new LinearLayout.LayoutParams(dp(a, 64), ViewGroup.LayoutParams.WRAP_CONTENT));
        HorizontalScrollView scroll = new HorizontalScrollView(a);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(a);
        for (int i = 0; i < n; i++) {
            TextView c = chip(a, names.at(i));
            int k = i;
            c.setOnClickListener(v -> {
                if (v.isEnabled()) onPick.at(k);
            });
            chips.addView(c, chipLp(a));
            into[i] = c;
        }
        scroll.addView(chips);
        row.addView(scroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }

    private static TextView chip(Context a, String label) {
        TextView c = text(a, label, 13, 0xFFFFFFFF, true);
        c.setPadding(dp(a, 10), dp(a, 7), dp(a, 10), dp(a, 7));
        lit(c, false, FRAME);
        return c;
    }

    private static void lit(TextView c, boolean on, int color) {
        Integer want = on ? color : 0x1FFFFFFF;
        if (want.equals(c.getTag())) return;
        c.setTag(want);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(c.getContext(), 16));
        g.setColor(want);
        c.setBackground(g);
        // a light chip (yellow) takes dark text
        c.setTextColor(on && color == 0xFFF5C542 ? 0xFF1A1A1A : 0xFFFFFFFF);
    }

    private static void enable(TextView c, boolean on) {
        c.setEnabled(on);
        c.setAlpha(on ? 1f : 0.35f);
    }

    private static TextView text(Context a, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static int dp(Context a, float v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    // ---------------------------------------------------------------------------------------------
    // the edit (editor.md §1, §2): merged into what is saved; the planner gets it on its next tick

    /** The pair's override to edit (a fresh one when Auto), or null when there is no pair. */
    private static MixOverride current() {
        Overview s = Automix.overview();
        if (s.pairKey == null || s.toId == null) return null;
        return s.override != null ? s.override : new MixOverride();
    }

    private static void save(MixOverride o) {
        save(Automix.overview().pairKey, o);
    }

    /** [o] saved for [pairKey] (a drag saves for the pair it started on). */
    private static void save(String pairKey, MixOverride o) {
        if (pairKey == null) return;
        if (o.loop()) {
            o.bars = 0;
            o.out = null;
            o.in = null;
        }
        String r = Automix.setOverride(pairKey, o);
        refusedNote = r != null ? r : "";
        if (view != null) view.sig = "";
    }

    /** A preset picked: keeps the window and the length (Spotify does the same); drops a migrated family. */
    private static void pickPreset(int i) {
        MixOverride o = current();
        if (o == null) return;
        String p = PRESETS[i];
        o.family = null;
        if (p.equals("custom")) {
            String[] l = lanesNow(o);
            o.volume = l[0];
            o.eq = l[1];
            o.effect = l[2];
        }
        o.preset = p.equals("auto") ? null : p;
        save(o);
    }

    /** A lane edited: the preset becomes custom, with the lanes it had. */
    private static void pickLane(int lane, String shape) {
        MixOverride o = current();
        if (o == null) return;
        String[] l = lanesNow(o);
        l[lane] = shape;
        o.family = null;
        o.preset = "custom";
        o.volume = l[0];
        o.eq = l[1];
        o.effect = l[2];
        save(o);
    }

    /** The lanes on screen: the custom ones, else the preset's, else Smooth's (automix-overview.js lanesNow). */
    private static String[] lanesNow(MixOverride o) {
        String[] l = o != null ? o.lanes() : null;
        if (l == null) {
            MixOverride smooth = new MixOverride();
            smooth.preset = "smooth";
            l = smooth.lanes();
        }
        return l.clone();
    }

    // ---------------------------------------------------------------------------------------------
    // the window (editor.md §6): each song dragged under the frame, snapped to its own grid

    /** A drag in progress: which song, where it started, where it is now. */
    private static final class Drag {
        boolean out, beats;
        float x0, w;
        double base, span;
        MixOverride.Position pos;
        Overview s;
    }

    private static Drag drag;

    private static boolean windowEditable(Overview s) {
        return s != null && s.why == null && s.editable && s.toId != null && s.plan != null && s.loop == null
                && (s.override == null || !s.override.loop());
    }

    private static double[] grid(Overview s, boolean out) {
        return out ? s.outGrid : s.inGrid;
    }

    private static MixOverride.Position positionOf(Overview s, boolean out, double sec, boolean snap, boolean beats) {
        double[] g = grid(s, out);
        return g == null ? MixOverride.positionOf(sec, Double.NaN, Double.NaN, false, false)
                : MixOverride.positionOf(sec, g[0], g[1], snap, beats);
    }

    private static boolean snaps(Overview s) {
        return s.override == null || !"off".equals(s.override.beatMatch);
    }

    /** The song moves with the finger: what was left of the frame comes under it. */
    private static void dragTo(Drag d, float x) {
        Overview s = d.s;
        double sec = d.base - ((x - d.x0) / d.w) * d.span;
        double dur = d.out ? s.outDur : s.inDur;
        double lo = d.out && !Double.isNaN(s.earliest) ? s.earliest : 0;
        double hi = Double.isNaN(dur) ? sec : Math.max(lo, dur - s.D - (d.out ? 0.3 : 10));
        double c = Math.max(lo, Math.min(sec, hi));
        refusedNote = Math.abs(c - sec) > 0.05 ? (d.out ? (c > sec ? "The mix-out can't come before what has played" : "The window has to end before this song does")
                : (c > sec ? "The mix-in can't start before the song" : "The window has to end 10 s before the next song does")) : "";
        d.pos = positionOf(s, d.out, c, snaps(s), d.beats);
    }

    private static void dragEnd(Drag d) {
        if (d.pos == null || Math.abs(d.pos.sec - d.base) < 0.005) return;
        // the song changed under the finger: the drag was on another pair's songs, nothing saved
        if (d.s.pairKey == null || !d.s.pairKey.equals(Automix.overview().pairKey)) return;
        MixOverride o = d.s.override != null ? d.s.override.copy() : new MixOverride();
        windowWith(d.s, o, d.out, d.pos);
        save(d.s.pairKey, o);
    }

    /**
     * A window edit of one side (editor.md §6, automix-overview.js windowWith): the other side is
     * saved too, where the plan on screen has it, so the planner's own moves of an unsaved side
     * (capIncomingSkip, the vocal re-route) don't move it once the window is the user's.
     */
    private static void windowWith(Overview s, MixOverride o, boolean out, MixOverride.Position pos) {
        if (out) o.out = pos;
        else o.in = pos;
        if (out && o.in == null) o.in = positionOf(s, false, s.plan.mixInTrackTime, false, false);
        if (!out && o.out == null) o.out = positionOf(s, true, s.plan.mixOutTrackTime, false, false);
    }

    /** A 10 ms step of one song's position (from where the plan has it, when nothing is saved yet). */
    private static void nudge(boolean out, int dir) {
        Overview s = Automix.overview();
        if (!windowEditable(s)) return;
        MixOverride o = s.override != null ? s.override.copy() : new MixOverride();
        MixOverride.Position now = out ? o.out : o.in;
        if (now == null) now = positionOf(s, out, out ? s.plan.mixOutTrackTime : s.plan.mixInTrackTime, false, false);
        int n = now.nudgeMs + dir * NUDGE_MS;
        if (Math.abs(n) > NUDGE_MAX_MS) {
            refusedNote = "Nudges reach " + NUDGE_MAX_MS / 1000 + " s at most: drag the song instead";
            return;
        }
        MixOverride.Position p = new MixOverride.Position(now.bar, now.beat, n, Math.round((now.sec + dir * NUDGE_MS / 1000.0) * 1000) / 1000.0);
        windowWith(s, o, out, p);
        save(o);
    }

    private static String fmtSec(double t) {
        t = Math.max(0, t);
        int m = (int) Math.floor(t / 60);
        return m + ":" + String.format(Locale.ROOT, "%04.1f", t - m * 60);
    }

    /** "A mixes out at 3:12.4 (bar 81) · B enters at 0:32.4 (bar 17)"; bars count from 1 on screen. */
    private static String readout(MixOverride.Position out, MixOverride.Position in) {
        return "A mixes out at " + fmtSec(out.sec) + " (bar " + (out.bar + 1) + ") · B enters at " + fmtSec(in.sec) + " (bar " + (in.bar + 1) + ")";
    }

    // ---------------------------------------------------------------------------------------------

    /** Each frame while open (20 a second): what the mix is now. */
    private static void frame() {
        Dialog d = dialog;
        if (d == null || !d.isShowing()) return;
        if (owner != null && owner.isDestroyed()) {
            forget();
            return;
        }
        try {
            render(Automix.overview());
        } catch (Throwable t) {
            Log.w(TAG, "overview frame failed", t);
        }
        main.postDelayed(MixOverview::frame, FRAME_MS);
    }

    private static void work(Overview s) {
        boolean on = s.waiting != null;
        // once shown it keeps its room (invisible, not gone): the chips below don't jump up when it ends
        if (on != (work.getVisibility() == View.VISIBLE)) work.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
        if (!on) return;
        boolean known = s.waitDone >= 0;
        set(workText, s.waiting + (known ? String.format(Locale.ROOT, " · %d%%", Math.round(s.waitDone * 100)) : "…"));
        if (workBar.isIndeterminate() == known) workBar.setIndeterminate(!known);
        if (known) workBar.setProgress(Math.round(s.waitDone * 1000));
    }

    private static void render(Overview s) {
        work(s);
        if (s.why != null) {
            sub.setText(s.why);
            msg.setText(s.why);
            msg.setVisibility(View.VISIBLE);
            view.setVisibility(View.INVISIBLE);
            if (!songsSig.isEmpty()) {
                songsRow.removeAllViews();
                songsSig = "";
            }
            clock.setText("");
            controls(s);
            return;
        }
        msg.setVisibility(View.GONE);
        view.setVisibility(View.VISIBLE);
        set(sub, s.describe());
        // the cards again once an analysis lands (their BPM and key): looked at once a second
        long now = android.os.SystemClock.uptimeMillis();
        if (now - tagsAt > 1000) {
            tagsAt = now;
            tagsSig = Overview.tags(s.fromId).size() + "" + Overview.tags(s.toId).size();
        }
        String ss = s.pairKey + "|" + s.fromTitle + "|" + s.toTitle + "|" + tagsSig;
        if (!ss.equals(songsSig)) {
            songsSig = ss;
            songs(s);
        }
        controls(s);
        view.show(s);
        String c = "";
        if (!Double.isNaN(s.at)) {
            if (s.at < 0) c = "Mix in " + Overview.clock(-s.at);
            else if (s.at <= s.D) c = "Mixing · " + Overview.clock(s.at) + " / " + Overview.clock(s.D);
        }
        set(clock, c);
    }

    private static void set(TextView t, String s) {
        if (!TextUtils.equals(t.getText(), s)) t.setText(s);
    }

    private static void controls(Overview s) {
        boolean has = s.why == null;
        boolean pair = s.toId != null && s.pairKey != null;
        MixOverride ov = s.override;
        String preset = ov != null && ov.preset != null ? ov.preset : "auto";
        String bm = ov != null ? ov.beatMatchOrAuto() : "auto";
        boolean loopNone = s.loopNone != null;
        for (int i = 0; i < PRESETS.length; i++) {
            TextView b = presetChips[i];
            lit(b, PRESETS[i].equals(preset), PRESET_COLORS[i]);
            enable(b, pair && !(PRESETS[i].equals("loop") && loopNone));
            if (i == 0) set(b, preset.equals("auto") && has ? s.autoName() : "Auto");
        }
        // the lanes a first change starts from: Auto shows Smooth's (lanesNow)
        String[] lanes = lanesNow(ov);
        for (int k = 0; k < LANES.length; k++) {
            TextView c = laneChips[k];
            set(c, shapeName(lanes[k]) + " ▾");
            lit(c, preset.equals("custom"), new int[]{VOLUME, EQ, EFFECT}[k]);
            enable(c, pair && !preset.equals("loop"));
        }
        enable(customToggle, !preset.equals("loop"));
        for (int i = 0; i < MATCH.length; i++) {
            lit(matchChips[i], MATCH[i].equals(bm), FRAME);
            enable(matchChips[i], pair && !preset.equals("loop"));
        }
        boolean isLoop = preset.equals("loop") || (preset.equals("auto") && has && "loop".equals(s.style()));
        int bars = ov != null ? ov.bars : 0;
        for (int i = 0; i < BARS.length; i++) {
            lit(barChips[i], BARS[i] == bars, FRAME);
            enable(barChips[i], pair && !(isLoop && BARS[i] != 0));
        }
        boolean winOn = windowEditable(s);
        boolean hasWin = ov != null && (ov.out != null || ov.in != null);
        for (TextView c : nudgeChips) enable(c, winOn);
        lit(winReset, !hasWin, FRAME);
        enable(winReset, winOn && hasWin);
        lit(lock, ov != null && ov.lock, FRAME);
        set(lock, ov != null && ov.lock ? "Locked" : "Lock");
        enable(lock, ov != null);
        enable(reset, ov != null);
        boolean custom = ov != null;
        if (custom != (badge.getVisibility() == View.VISIBLE)) badge.setVisibility(custom ? View.VISIBLE : View.GONE);
        boolean canPreview = has && s.editable && !s.provisional && s.previewAt > 0 && !Double.isNaN(s.left) && s.left >= 25;
        enable(preview, canPreview);
        if (!canPreview && previewed) {
            previewed = false;
            preview.setText("▶ Preview");
        }

        String n = "";
        Drag d = drag;
        if (!has) {
            n = s.waiting != null ? "The mix is planned once the analysis is done." : ov != null ? "These two songs have an edit of their own: Reset clears it." : "";
        } else if (d != null && d.pos != null && s.plan != null) {
            MixOverride.Position other = positionOf(s, !d.out, d.out ? s.plan.mixInTrackTime : s.plan.mixOutTrackTime, false, false);
            n = d.out ? readout(d.pos, other) : readout(other, d.pos);
            if (!refusedNote.isEmpty()) n += "\n" + refusedNote;
        } else if (!refusedNote.isEmpty()) {
            n = refusedNote;
        } else if (!s.editable) {
            n = "Mixing now: an edit is for the next time these two play.";
        } else if (s.provisional) {
            n = "A stand-in fade while the analysis runs.";
        } else if (preset.equals("loop") && s.loop == null) {
            n = loopNone ? "No loop mix for these two: " + s.loopNone : "Preparing the loop mix…";
        } else if (s.plan != null && s.plan.warnings != null && !s.plan.warnings.isEmpty()) {
            n = "⚠ " + TextUtils.join("; ", s.plan.warnings);
        } else if (bars > 0 && s.plan != null && s.plan.bars > 0 && bars != s.plan.bars && s.loop == null && !"crossfade".equals(s.plan.style)) {
            n = bars + " bars asked; " + s.plan.bars + " fit here.";
        } else if (hasWin && s.plan != null && s.loop == null) {
            n = readout(ov.out != null ? ov.out : positionOf(s, true, s.plan.mixOutTrackTime, false, false),
                    ov.in != null ? ov.in : positionOf(s, false, s.plan.mixInTrackTime, false, false));
        } else if (ov != null) {
            n = ov.lock ? "Locked for these two songs." : "Kept for these two songs.";
        } else if (s.plan != null && s.plan.reason != null) {
            n = s.plan.reason;
        } else if (s.choice != null) {
            n = (s.loop != null ? "Loop mix: " : "Blend: ") + s.choice;
        } else if (winOn) {
            n = "Drag a song under the mix to move the window (long-press: beats).";
        }
        set(note, n);
    }

    /** The two songs: cover, name, artist and their analysis (BPM, key, length). */
    private static void songs(Overview s) {
        Context a = songsRow.getContext();
        songsRow.removeAllViews();
        songsRow.addView(song(a, s.fromId, s.fromTitle, s.fromArtist), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView arrow = text(a, "→", 18, 0x99FFFFFF, false);
        arrow.setPadding(dp(a, 8), 0, dp(a, 8), 0);
        songsRow.addView(arrow);
        songsRow.addView(song(a, s.toId, s.toTitle, s.toArtist), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
    }

    private static View song(Context a, String id, String title, String artist) {
        LinearLayout c = new LinearLayout(a);
        c.setGravity(Gravity.CENTER_VERTICAL);
        ImageView img = new ImageView(a);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable ph = new GradientDrawable();
        ph.setColor(0x26FFFFFF);
        ph.setCornerRadius(dp(a, 6));
        img.setBackground(ph);
        img.setClipToOutline(true);
        img.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(a, 6));
            }
        });
        cover(id, img);
        c.addView(img, new LinearLayout.LayoutParams(dp(a, 44), dp(a, 44)));
        LinearLayout t = new LinearLayout(a);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(dp(a, 8), 0, 0, 0);
        TextView name = text(a, title != null ? title : id == null ? "Nothing queued" : "…", 14, 0xFFFFFFFF, true);
        name.setSingleLine();
        name.setEllipsize(TextUtils.TruncateAt.END);
        t.addView(name);
        TextView ar = text(a, artist != null ? artist : "", 12, 0xB3FFFFFF, false);
        ar.setSingleLine();
        ar.setEllipsize(TextUtils.TruncateAt.END);
        t.addView(ar);
        List<String> tags = Overview.tags(id);
        if (!tags.isEmpty()) {
            TextView tg = text(a, TextUtils.join(" · ", tags), 11, 0x99FFFFFF, false);
            tg.setSingleLine();
            tg.setEllipsize(TextUtils.TruncateAt.END);
            t.addView(tg);
        }
        c.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return c;
    }

    private static void cover(String id, ImageView into) {
        if (id == null) return;
        Bitmap b = covers.get(id);
        if (b != null) {
            into.setImageBitmap(b);
            return;
        }
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("https://i.ytimg.com/vi/" + id + "/mqdefault.jpg").openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(6000);
                try (InputStream in = c.getInputStream()) {
                    Bitmap got = BitmapFactory.decodeStream(in);
                    if (got != null) main.post(() -> {
                        if (covers.size() > 8) covers.clear();
                        covers.put(id, got);
                        into.setImageBitmap(got);
                    });
                } finally {
                    c.disconnect();
                }
            } catch (Throwable ignored) {
                // offline: the placeholder stays
            }
        }, "Overview-cover").start();
    }

    /**
     * The drawing (automix-overview.js draw/drawStatic): the static picture (waveforms, grid, frame,
     * the lanes' curves, the bars chip) once per plan, then each frame only the playhead. A song's
     * lane follows the finger while it is dragged under the frame. Two fingers zoom the picture (up
     * to 8x) around where they are and move it as they move; a new pair starts zoomed out.
     */
    private static final class MixView extends View {
        String sig = "";
        private Bitmap layer;
        private Overview s;
        private double t0, t1;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Runnable longPress = () -> {
            Drag d = drag;
            if (d != null) {
                d.beats = true;
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            }
        };
        private float downX;
        private boolean moved;
        /** The part of the picture in view, as fractions of the whole (pad, mix, pad). */
        private double f0 = 0, f1 = 1, anchor;
        private String zoomPair;
        private boolean pinching;
        private final ScaleGestureDetector pinch;

        MixView(Context c) {
            super(c);
            pinch = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScaleBegin(ScaleGestureDetector g) {
                    int w = getWidth();
                    if (w == 0) return false;
                    anchor = f0 + (g.getFocusX() / w) * (f1 - f0);
                    return true;
                }

                @Override
                public boolean onScale(ScaleGestureDetector g) {
                    int w = getWidth();
                    if (w == 0) return false;
                    // the point under the fingers stays under them
                    double span = Math.max(1 / MAX_ZOOM, Math.min(1, (f1 - f0) / g.getScaleFactor()));
                    double a = Math.max(0, Math.min(1 - span, anchor - (g.getFocusX() / w) * span));
                    f0 = a;
                    f1 = a + span;
                    invalidate();
                    return true;
                }
            });
        }

        void show(Overview o) {
            s = o;
            invalidate();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            Overview o = s;
            int w = getWidth();
            if (o == null || o.why != null || w == 0) return false;
            pinch.onTouchEvent(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_POINTER_DOWN: {
                    // a second finger: a zoom, not a drag (the drag is dropped, nothing saved)
                    pinching = true;
                    removeCallbacks(longPress);
                    drag = null;
                    refusedNote = "";
                    getParent().requestDisallowInterceptTouchEvent(true);
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_DOWN: {
                    pinching = false;
                    if (!windowEditable(o)) return true;
                    Drag d = new Drag();
                    d.out = e.getY() < getHeight() / 2f;
                    d.x0 = e.getX();
                    d.w = w;
                    d.span = t1 - t0;
                    d.base = d.out ? o.plan.mixOutTrackTime : o.plan.mixInTrackTime;
                    d.s = o;
                    drag = d;
                    downX = e.getX();
                    moved = false;
                    refusedNote = "";
                    getParent().requestDisallowInterceptTouchEvent(true);
                    postDelayed(longPress, LONG_PRESS_MS);
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    Drag d = drag;
                    if (d == null || pinching) return true;
                    if (!moved && Math.abs(e.getX() - downX) > 8 * getResources().getDisplayMetrics().density) {
                        moved = true;
                        // a long-press is a hold before the drag; moving first keeps downbeats
                        if (!d.beats) removeCallbacks(longPress);
                    }
                    if (moved) dragTo(d, e.getX());
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    removeCallbacks(longPress);
                    Drag d = drag;
                    drag = null;
                    if (d != null && moved && !pinching && e.getActionMasked() == MotionEvent.ACTION_UP) dragEnd(d);
                    pinching = false;
                    invalidate();
                    return true;
                }
                default:
                    return true;
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            Overview o = s;
            int w = getWidth(), h = getHeight();
            if (o == null || o.why != null || w == 0 || h == 0) return;
            double pad = Math.max(4, o.D * 0.24);
            if (!java.util.Objects.equals(zoomPair, o.pairKey)) {
                zoomPair = o.pairKey;
                f0 = 0;
                f1 = 1;
            }
            double all = o.D + 2 * pad;
            t0 = -pad + f0 * all;
            t1 = -pad + f1 * all;
            Waves.Wave wa = Waves.get(o.fromId, () -> sig = ""), wb = Waves.get(o.toId, () -> sig = "");
            String want = o.pairKey + "|" + o.style() + "|" + (o.plan != null ? String.format(Locale.ROOT, "%.2f|%.2f|%s", o.plan.mixOutTrackTime, o.plan.mixInTrackTime, o.plan.preset) : o.loop.out.loopStart)
                    + "|" + String.format(Locale.ROOT, "%.2f", o.D) + "|" + o.bars() + "|" + w + "x" + h + "|" + (wa != null) + (wb != null) + "|" + o.provisional
                    + "|" + System.identityHashCode(o.curves) + String.format(Locale.ROOT, "|%.4f|%.4f", f0, f1);
            if (!want.equals(sig) || layer == null || layer.getWidth() != w || layer.getHeight() != h) {
                sig = want;
                layer = drawStatic(o, w, h, wa, wb);
            }
            Drag d = drag;
            if (d != null && d.pos != null) {
                // the dragged song's lane slides by what the drag moved it (snapped), the other stays
                float gap = 10 * getResources().getDisplayMetrics().density, laneH = (h - gap * 3) / 2;
                float top = d.out ? gap : gap * 2 + laneH;
                float dx = (float) (-(d.pos.sec - d.base) / d.span * w);
                c.save();
                c.clipRect(0, 0, w, h);
                c.drawBitmap(layer, 0, 0, null);
                c.restore();
                c.save();
                c.clipRect(0, top - gap / 2, w, top + laneH + gap / 2);
                paint.setColor(0xFF161618);
                c.drawRect(0, top - gap / 2, w, top + laneH + gap / 2, paint);
                c.translate(dx, 0);
                c.drawBitmap(layer, 0, 0, null);
                c.restore();
                // the frame stays put over both
                float d1 = getResources().getDisplayMetrics().density;
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2 * d1);
                paint.setColor(FRAME);
                c.drawRoundRect(new RectF(X(0, w), 2 * d1, X(o.D, w), h - 2 * d1), 6 * d1, 6 * d1, paint);
                paint.setStyle(Paint.Style.FILL);
            } else {
                c.drawBitmap(layer, 0, 0, null);
            }
            // the playhead, on the mix's clock
            if (!Double.isNaN(o.at) && o.at >= t0 && o.at <= t1) {
                float x = X(o.at, w);
                paint.setColor(0xFFFFFFFF);
                paint.setStyle(Paint.Style.FILL);
                float dd = getResources().getDisplayMetrics().density;
                c.drawRect(x - dd, 4 * dd, x + dd, h - 4 * dd, paint);
            }
        }

        private float X(double t, int w) {
            return (float) ((t - t0) / (t1 - t0) * w);
        }

        private double T(float x, int w) {
            return t0 + (x / (double) w) * (t1 - t0);
        }

        private Bitmap drawStatic(Overview o, int w, int h, Waves.Wave wa, Waves.Wave wb) {
            // one bitmap reused while the size holds (a pinch redraws it on every step)
            Bitmap b = layer;
            if (b != null && b.getWidth() == w && b.getHeight() == h) b.eraseColor(0);
            else b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas g = new Canvas(b);
            float d = getResources().getDisplayMetrics().density;
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            float gap = 10 * d, laneH = (h - gap * 3) / 2;
            float[] top = {gap, gap * 2 + laneH};
            // the grey well, the mix's frame
            p.setColor(0x0AFFFFFF);
            g.drawRoundRect(new RectF(0, 0, w, h), 10 * d, 10 * d, p);
            float fx0 = X(0, w), fx1 = X(o.D, w);
            p.setColor(0x143A9BDC);
            g.drawRect(fx0, 2 * d, fx1, h - 2 * d, p);
            // the beat grid
            p.setStrokeWidth(Math.max(1, d / 2));
            for (double[] beat : o.beats(t0, t1)) {
                float x = X(beat[0], w);
                p.setColor(beat[1] > 0 ? 0x24FFFFFF : 0x0FFFFFFF);
                g.drawLine(x, 2 * d, x, h - 2 * d, p);
            }
            // the waveforms, loudest band first so the quieter ones show in front of it
            Waves.Wave[] waves = {wa, wb};
            float[] v = new float[3];
            int[] colors = {LOW, MID, HIGH};
            float step = 2 * d;
            for (int lane = 0; lane < 2; lane++) {
                float yMid = top[lane] + laneH / 2, half = laneH / 2 - 2 * d;
                p.setColor(0x14FFFFFF);
                g.drawLine(0, yMid, w, yMid, p);
                Waves.Wave wave = waves[lane];
                if (wave == null) continue;
                for (float x = 0; x < w; x += step) {
                    double t = T(x + step / 2, w);
                    double tt = lane == 0 ? o.outTrack(t) : o.inTrack(t);
                    if (Double.isNaN(tt) || tt < 0 || !wave.at(tt, v)) continue;
                    int alpha = t < 0 || t > o.D ? Math.round(255 * OUTSIDE_ALPHA) : 255;
                    Integer[] order = {0, 1, 2};
                    java.util.Arrays.sort(order, (i, j) -> Float.compare(v[j], v[i]));
                    for (int k : order) {
                        float amp = v[k] * half;
                        if (amp < 0.5f) continue;
                        p.setColor(colors[k]);
                        p.setAlpha(alpha);
                        g.drawRect(x, yMid - amp, x + step - d / 2, yMid + amp, p);
                    }
                }
            }
            // each song's volume, from the plan's curves
            p.setAlpha(255);
            p.setColor(VOLUME);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.5f * d);
            android.graphics.PathEffect dash = o.provisional ? new android.graphics.DashPathEffect(new float[]{6 * d, 5 * d}, 0) : null;
            p.setPathEffect(dash);
            if (o.provisional) p.setAlpha(170);
            for (int lane = 0; lane < 2; lane++) {
                float y0 = top[lane] + laneH - d, span = laneH - 4 * d;
                Path path = new Path();
                for (float x = 0; x <= w; x += step) {
                    double t = T(x, w);
                    float y = (float) (y0 - (lane == 0 ? o.gainOut(t) : o.gainIn(t)) * span);
                    if (x == 0) path.moveTo(x, y);
                    else path.lineTo(x, y);
                }
                g.drawPath(path, p);
            }
            p.setPathEffect(null);
            p.setAlpha(255);
            // the EQ (bass, then highs; 0 dB at the top of the lane, the cut at its bottom) and the
            // filters (log Hz: 20 Hz at the bottom, 20 kHz at the top, as each song's filter opens)
            if (o.curves != null && o.loop == null) {
                p.setStrokeWidth(Math.max(1, d));
                for (int lane = 0; lane < 2; lane++) {
                    Map<String, double[][]> side = lane == 0 ? o.curves.out : o.curves.in;
                    curve(g, p, side.get("bassDb"), top[lane], laneH, fx0, fx1, w, EQ, 0);
                    curve(g, p, side.get("highDb"), top[lane], laneH, fx0, fx1, w, (EQ & 0x00FFFFFF) | 0xAA000000, 0);
                    curve(g, p, side.get("lowpassHz"), top[lane], laneH, fx0, fx1, w, EFFECT, 1);
                    curve(g, p, side.get("highpassHz"), top[lane], laneH, fx0, fx1, w, EFFECT, 2);
                }
                p.setPathEffect(null);
            }
            // the frame
            p.setColor(FRAME);
            p.setStrokeWidth(2 * d);
            if (o.provisional) p.setAlpha(150);
            if (o.provisional) p.setPathEffect(dash);
            g.drawRoundRect(new RectF(fx0, 2 * d, fx1, h - 2 * d), 6 * d, 6 * d, p);
            p.setPathEffect(null);
            p.setAlpha(255);
            // the bars chip (a stand-in fade: says so)
            int bars = o.bars();
            if (bars > 0 || o.provisional) {
                String text = o.provisional ? "for now" : bars + " bars";
                p.setStyle(Paint.Style.FILL);
                p.setTextSize(11 * getResources().getDisplayMetrics().scaledDensity);
                p.setTypeface(Typeface.DEFAULT_BOLD);
                float tw = p.measureText(text) + 14 * d;
                float cx = Math.min(Math.max(fx0 + (fx1 - fx0) / 2 - tw / 2, 2 * d), w - tw - 2 * d);
                p.setColor(FRAME);
                g.drawRoundRect(new RectF(cx, h / 2f - 10 * d, cx + tw, h / 2f + 10 * d), 10 * d, 10 * d, p);
                p.setColor(0xFFFFFFFF);
                g.drawText(text, cx + 7 * d, h / 2f + 4 * d, p);
            }
            return b;
        }

        /** One lane's curve inside the frame. kind 0: dB (0 at the top, the cut at the bottom); 1: low-pass Hz; 2: high-pass Hz. */
        private void curve(Canvas g, Paint p, double[][] pts, float top, float laneH, float fx0, float fx1, int w, int color, int kind) {
            if (pts == null || pts.length == 0) return;
            float d = getResources().getDisplayMetrics().density;
            float y0 = top + 2 * d, span = laneH - 6 * d;
            p.setColor(color);
            p.setPathEffect(kind > 0 ? new android.graphics.DashPathEffect(new float[]{4 * d, 3 * d}, 0) : null);
            Path path = new Path();
            boolean first = true;
            for (float x = Math.max(0, fx0); x <= Math.min(w, fx1); x += 2 * d) {
                double val = valueAt(pts, T(x, w), kind > 0);
                double f = kind == 0 ? 1 - Math.min(1, Math.max(0, val / cutDb()))
                        : Math.log(Math.max(20, val) / 20) / Math.log(1000);
                if (kind == 2) f = 1 - f;
                float y = (float) (y0 + (1 - f) * span);
                if (first) path.moveTo(x, y);
                else path.lineTo(x, y);
                first = false;
            }
            g.drawPath(path, p);
        }

        private static double cutDb() {
            return app.ayham.extension.music.automix.Planner.BASS_CUT_DB;
        }

        /** A curve at t, held outside its points; Hz between points geometric (plan.schema.json). */
        private static double valueAt(double[][] pts, double t, boolean geo) {
            if (t <= pts[0][0]) return pts[0][1];
            for (int i = 1; i < pts.length; i++) {
                double a = pts[i - 1][0], b = pts[i][0];
                if (t > b) continue;
                if (b - a < 1e-9) return pts[i][1];
                double x = (t - a) / (b - a), va = pts[i - 1][1], vb = pts[i][1];
                return geo && va > 0 && vb > 0 ? va * Math.pow(vb / va, x) : va + (vb - va) * x;
            }
            return pts[pts.length - 1][1];
        }
    }
}
