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
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import app.ayham.extension.music.automix.Automix;
import app.ayham.extension.music.automix.Overview;
import app.ayham.extension.music.automix.Planner;
import app.ayham.extension.music.automix.Waves;

/**
 * The Automix overview (the YT Music Extension's automix-overview.js, BitChord's MixViewPopup with
 * Spotify Mix's per-transition choices): the coming mix, or the one playing, drawn on its own
 * clock. Each song gets a 3-band waveform (low, mid, high), dimmed outside the mix; the mix is
 * framed, with its beat grid (bars brighter) and each song's volume curve from the plan. A style
 * (Auto, Loop, Blend, Filter, Fade) and a length (Auto, 4 to 32 bars) can be picked for each pair
 * of songs, kept for the next time the pair plays, and Preview jumps to just before the mix.
 * Opened from the "..." menu of the title row, or by tapping the automix line on the art.
 */
final class MixOverview {
    private static final String TAG = "AyhamOverview";
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final int LOW = 0xFF2E45E6, MID = 0xFFE08B35, HIGH = 0xFFF1DDC4, FRAME = 0xFF3A9BDC, FADER = 0xFFF2C230;
    private static final float OUTSIDE_ALPHA = 0.32f;
    private static final String[][] STYLES = {{"", "Auto"}, {"loop", "Loop"}, {"blend", "Blend"}, {"filter", "Filter"}, {"crossfade", "Fade"}};
    private static final int[] BARS = {0, 4, 8, 16, 32};
    private static final String[] BAR_LABELS = {"Auto", "4", "8", "16", "32"};
    private static final int FILTER_MAX_BARS = 8;
    private static final long FRAME_MS = 50;

    private static Dialog dialog;
    private static TextView sub, msg, clock, note, preview;
    private static LinearLayout songsRow, work;
    private static android.widget.ProgressBar workBar;
    private static TextView workText;
    private static MixView view;
    private static final TextView[] styleChips = new TextView[STYLES.length], barChips = new TextView[BARS.length];
    private static String songsSig = "";
    private static Overview last;
    private static long tagsAt;
    private static String tagsSig = "";
    private static final Map<String, Bitmap> covers = new HashMap<>();

    private MixOverview() {
    }

    static void show(Activity a) {
        if (dialog != null && dialog.isShowing()) return;
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
        card.setBackground(bg);

        // head: title, what the mix is, close
        LinearLayout head = new LinearLayout(a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(a);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text(a, "Automix", 20, 0xFFFFFFFF, true));
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
        int[] keys = {LOW, MID, HIGH, FADER};
        String[] names = {"Low", "Mid", "High", "Volume"};
        for (int i = 0; i < keys.length; i++) {
            View sw = new View(a);
            GradientDrawable g = new GradientDrawable();
            g.setColor(keys[i]);
            g.setCornerRadius(dp(a, 2));
            sw.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(a, 10), dp(a, 10));
            lp.rightMargin = dp(a, 4);
            legend.addView(sw, lp);
            TextView t = text(a, names[i], 12, 0xB3FFFFFF, false);
            t.setPadding(0, 0, dp(a, 10), 0);
            legend.addView(t);
        }
        clock = text(a, "", 12, 0xE6FFFFFF, false);
        clock.setGravity(Gravity.END);
        legend.addView(clock, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(legend);

        // the pair's choices
        card.addView(chipRow(a, "Style", STYLES.length, i -> STYLES[i][1], styleChips, i -> pick(STYLES[i][0].isEmpty() ? null : STYLES[i][0], -1)));
        card.addView(chipRow(a, "Length", BARS.length, i -> BAR_LABELS[i], barChips, i -> pick(null, BARS[i]), true));
        LinearLayout actions = new LinearLayout(a);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(a, 10), 0, 0);
        preview = chip(a, "▶ Preview");
        preview.setOnClickListener(v -> {
            Overview s = Automix.overview();
            if (s.why == null && s.editable) Automix.seekTo(s.previewAt);
        });
        actions.addView(preview);
        note = text(a, "", 12, 0xB3FFFFFF, false);
        note.setPadding(dp(a, 12), 0, 0, 0);
        actions.addView(note, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(actions);

        d.setContentView(card);
        d.setCanceledOnTouchOutside(true);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(a.getResources().getDisplayMetrics().widthPixels - 2 * dp(a, 12), WindowManager.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.getAttributes().y = dp(a, 24);
        }
        d.setOnDismissListener(x -> {
            if (dialog == d) dialog = null;
            Log.i(TAG, "overview closed");
        });
        dialog = d;
        songsSig = "";
        d.show();
        main.post(MixOverview::frame);
    }

    private interface IntFn<T> {
        T at(int i);
    }

    private interface IntAct {
        void at(int i);
    }

    private static View chipRow(Activity a, String label, int n, IntFn<String> names, TextView[] into, IntAct onPick) {
        return chipRow(a, label, n, names, into, onPick, false);
    }

    private static View chipRow(Activity a, String label, int n, IntFn<String> names, TextView[] into, IntAct onPick, boolean unused) {
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(a, 6), 0, 0);
        TextView l = text(a, label, 13, 0xB3FFFFFF, false);
        row.addView(l, new LinearLayout.LayoutParams(dp(a, 52), ViewGroup.LayoutParams.WRAP_CONTENT));
        HorizontalScrollView scroll = new HorizontalScrollView(a);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(a);
        for (int i = 0; i < n; i++) {
            TextView c = chip(a, names.at(i));
            int k = i;
            c.setOnClickListener(v -> {
                if (v.isEnabled()) onPick.at(k);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(a, 5);
            chips.addView(c, lp);
            into[i] = c;
        }
        scroll.addView(chips);
        row.addView(scroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }

    private static TextView chip(Context a, String label) {
        TextView c = text(a, label, 13, 0xFFFFFFFF, true);
        c.setPadding(dp(a, 10), dp(a, 7), dp(a, 10), dp(a, 7));
        lit(c, false);
        return c;
    }

    private static void lit(TextView c, boolean on) {
        if (Boolean.valueOf(on).equals(c.getTag())) return;
        c.setTag(on);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(c.getContext(), 16));
        g.setColor(on ? FRAME : 0x1FFFFFFF);
        c.setBackground(g);
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

    /** A chip picked: the pair's pick changes as automix-overview.js pick() does. */
    private static void pick(String style, int bars) {
        Overview s = Automix.overview();
        if (s.pairKey == null || s.toId == null) return;
        Planner.Pick cur = s.pick != null ? s.pick : new Planner.Pick(null, 0);
        String st = bars >= 0 ? cur.style : style;
        int b = bars >= 0 ? bars : cur.bars;
        // a loop's length is its loops'; a fade's is fixed
        if ("loop".equals(st) || "crossfade".equals(st)) b = 0;
        if ("filter".equals(st) && b > FILTER_MAX_BARS) b = FILTER_MAX_BARS;
        Automix.setPairPick(s.pairKey, st, b);
        view.sig = "";
    }

    /** Each frame while open (20 a second): what the mix is now. */
    private static void frame() {
        Dialog d = dialog;
        if (d == null || !d.isShowing()) return;
        try {
            render(Automix.overview());
        } catch (Throwable t) {
            Log.w(TAG, "overview frame failed", t);
        }
        main.postDelayed(MixOverview::frame, FRAME_MS);
    }

    private static void work(Overview s) {
        boolean on = s.waiting != null;
        if (on != (work.getVisibility() == View.VISIBLE)) work.setVisibility(on ? View.VISIBLE : View.GONE);
        if (!on) return;
        boolean known = s.waitDone >= 0;
        set(workText, s.waiting + (known ? String.format(Locale.ROOT, " · %d%%", Math.round(s.waitDone * 100)) : "…"));
        if (workBar.isIndeterminate() == known) workBar.setIndeterminate(!known);
        if (known) workBar.setProgress(Math.round(s.waitDone * 1000));
    }

    private static void render(Overview s) {
        last = s;
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
        Planner.Pick pk = s.pick != null ? s.pick : new Planner.Pick(null, 0);
        String style = pk.style == null ? "" : pk.style;
        boolean loopNone = s.loopNone != null;
        for (int i = 0; i < STYLES.length; i++) {
            TextView b = styleChips[i];
            lit(b, STYLES[i][0].equals(style));
            boolean on = s.toId != null && !(STYLES[i][0].equals("loop") && loopNone);
            b.setEnabled(on);
            b.setAlpha(on ? 1f : 0.35f);
        }
        String actual = has ? s.style() : null;
        boolean fixedLen = style.equals("loop") || style.equals("crossfade")
                || (style.isEmpty() && pk.bars == 0 && ("loop".equals(actual) || "crossfade".equals(actual)));
        for (int i = 0; i < BARS.length; i++) {
            TextView b = barChips[i];
            int v = BARS[i];
            lit(b, v == pk.bars);
            boolean on = s.toId != null && !(fixedLen && v != 0) && !(style.equals("filter") && v > FILTER_MAX_BARS);
            b.setEnabled(on);
            b.setAlpha(on ? 1f : 0.35f);
        }
        boolean canPreview = has && s.editable && !s.provisional && s.previewAt > 0 && !Double.isNaN(s.left) && s.left >= 25;
        preview.setEnabled(canPreview);
        preview.setAlpha(canPreview ? 1f : 0.35f);
        String n = "";
        if (!has) n = s.pick != null ? "These two songs have a pick of their own: Auto clears it." : "";
        if (!has && s.waiting != null && n.isEmpty()) n = "The mix is planned once the analysis is done.";
        else if (!s.editable) n = "Mixing now: a new pick is for the next time these two play.";
        else if (s.provisional) n = "A stand-in fade while the analysis runs.";
        else if (!style.isEmpty() && !style.equals(actual)) n = style.equals("loop") ? (loopNone ? "No loop mix for these two: " + s.loopNone : "Preparing the loop mix…") : "Planning…";
        else if (pk.bars > 0 && s.bars() > 0 && pk.bars != s.bars() && !"loop".equals(actual)) n = pk.bars + " bars asked; " + s.bars() + " fit here.";
        else if (!style.isEmpty() || pk.bars > 0) n = "Kept for these two songs.";
        else if (s.plan != null && s.plan.reason != null) n = s.plan.reason;
        else if (s.choice != null) n = (s.loop != null ? "Loop mix: " : "Blend: ") + s.choice;
        else if (fixedLen) n = "loop".equals(actual) ? "A loop mix sets its own length: pick Blend or Filter to choose one." : "A fade has a fixed length.";
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
     * volume curves, the bars chip) once per plan, then each frame only the playhead.
     */
    private static final class MixView extends View {
        String sig = "";
        private Bitmap layer;
        private Overview s;
        private double t0, t1;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        MixView(Context c) {
            super(c);
        }

        void show(Overview o) {
            s = o;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            Overview o = s;
            int w = getWidth(), h = getHeight();
            if (o == null || o.why != null || w == 0 || h == 0) return;
            double pad = Math.max(4, o.D * 0.24);
            t0 = -pad;
            t1 = o.D + pad;
            Waves.Wave wa = Waves.get(o.fromId, () -> sig = ""), wb = Waves.get(o.toId, () -> sig = "");
            String want = o.pairKey + "|" + o.style() + "|" + (o.plan != null ? String.format(Locale.ROOT, "%.2f|%.2f", o.plan.mixOutTrackTime, o.plan.mixInTrackTime) : o.loop.out.loopStart)
                    + "|" + String.format(Locale.ROOT, "%.2f", o.D) + "|" + o.bars() + "|" + w + "x" + h + "|" + (wa != null) + (wb != null) + "|" + o.provisional;
            if (!want.equals(sig) || layer == null || layer.getWidth() != w || layer.getHeight() != h) {
                sig = want;
                layer = drawStatic(o, w, h, wa, wb);
            }
            c.drawBitmap(layer, 0, 0, null);
            // the playhead, on the mix's clock
            if (!Double.isNaN(o.at) && o.at >= t0 && o.at <= t1) {
                float x = X(o.at, w);
                paint.setColor(0xFFFFFFFF);
                paint.setStyle(Paint.Style.FILL);
                float d = getResources().getDisplayMetrics().density;
                c.drawRect(x - d, 4 * d, x + d, h - 4 * d, paint);
            }
        }

        private float X(double t, int w) {
            return (float) ((t - t0) / (t1 - t0) * w);
        }

        private double T(float x, int w) {
            return t0 + (x / (double) w) * (t1 - t0);
        }

        private Bitmap drawStatic(Overview o, int w, int h, Waves.Wave wa, Waves.Wave wb) {
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
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
            p.setColor(FADER);
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
            // the frame
            p.setColor(FRAME);
            p.setStrokeWidth(2 * d);
            if (o.provisional) p.setAlpha(150);
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
    }
}
