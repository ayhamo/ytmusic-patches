package app.ayham.extension.music.spike;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * BitChord's phone now-playing screen, built from YT Music's own views. Property ui=bitchord.
 * <p>
 * Nothing of YT's is hidden or redrawn: its title row, seek bar + controls and chips row stay
 * YT's views and only get drawn in BitChord's order (translationY, given back whenever the player
 * collapses, a sheet opens or the switch goes off). Ours fills the gaps between them, as in
 * BitChord: the artwork mesh behind the page, the stats on the art's lower edge, the lyric line
 * under the title, the volume row under the controls, the automix band on YT's seek bar and the
 * output device under the chips. Every text and icon is white at a fixed alpha, as in BitChord.
 */
final class SpikeG {
    private static final String TAG = "AyhamSpikeG";
    private static final Handler main = new Handler(Looper.getMainLooper());

    private static FrameLayout overlay;
    private static MeshView mesh;
    private static TextView stats, lyric, device;
    private static LinearLayout lyricRow, volRow;
    private static ThinBar volume;
    private static MixZone zone;
    private static long durationMs;
    private static String meshFor = "", meshWanted = "";
    private static long meshTry;
    private static boolean layoutLogged, deviceRoom = true;
    /** Where the bottom sheet's handle rests while the sheet is closed. */
    private static int sheetRest, sheetRestFor;
    /** YT's views we draw elsewhere: {YT's own translationY, the one we set}. */
    private static final Map<View, float[]> moved = new WeakHashMap<>();

    private SpikeG() {
    }

    static boolean on() {
        return "bitchord".equals(SpikeA.props().getProperty("ui"));
    }

    private static int id(Activity a, String name) {
        return a.getResources().getIdentifier(name, "id", a.getPackageName());
    }

    private static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    private static int top(View v) {
        int[] at = new int[2];
        v.getLocationOnScreen(at);
        return at[1];
    }

    /** Called on every pre-draw of the activity while the player page exists. */
    static void tick(Activity a, View decor, ViewGroup page, boolean expanded) {
        if (!on()) {
            if (overlay != null && overlay.getVisibility() != View.GONE) {
                overlay.setVisibility(View.GONE);
                if (mesh != null) mesh.setVisibility(View.GONE);
                giveBack();
                Log.i(TAG, "BitChord screen switched off: YT's layout given back");
            }
            return;
        }
        ViewGroup layer = decor.findViewById(id(a, "player_page_bottom_layer"));
        if (layer == null) return;
        if (overlay != null && (overlay.getContext() != a || overlay.getParent() != layer || mesh.getParent() != page)) {
            // a new activity or player page (rotation, theme change): build ours again there
            for (View v : new View[]{overlay, mesh})
                if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
            overlay = null;
            moved.clear();
        }
        if (overlay == null) build(a, page, layer);
        overlay.setVisibility(View.VISIBLE);

        // The mesh fades with the page: full when expanded, gone by the time it reaches the mini player.
        int pageY = top(page);
        float shown = Math.max(0f, Math.min(1f, 1f - pageY / (decor.getHeight() * 0.5f)));
        mesh.setVisibility(shown > 0 ? View.VISIBLE : View.INVISIBLE);
        if (Math.abs(mesh.getAlpha() - shown) > 0.01f) mesh.setAlpha(shown);

        View art = decor.findViewById(id(a, "player_view_pager_container"));
        View song = decor.findViewById(id(a, "current_song_view"));
        View controls = decor.findViewById(id(a, "music_playback_controls"));
        View chips = decor.findViewById(id(a, "video_action_bar"));
        View sheet = decor.findViewById(id(a, "player_bottom_sheet"));
        // YT's Up next / Lyrics / Related sheet shrinks the art to a strip: the sheet owns the screen then
        boolean sheetOpen = art != null && art.getHeight() > 0 && art.getHeight() < decor.getWidth() / 2;
        // ...and the sheet starts rising before that: its handle leaves its resting place
        int sheetAt = sheet != null && sheet.isShown() ? top(sheet) : decor.getHeight() - dp(a, 64);
        if (decor.getHeight() != sheetRestFor) {
            // a new window height (rotation, split screen) moves the resting place: learnt again, as
            // an old, lower one would read as "sheet open" from then on and keep our layout off
            sheetRestFor = decor.getHeight();
            sheetRest = 0;
        }
        if (expanded && pageY <= dp(a, 2) && sheetAt > sheetRest) sheetRest = sheetAt;
        if (sheetAt < sheetRest - dp(a, 12)) sheetOpen = true;
        if (!expanded || pageY > dp(a, 2) || sheetOpen || art == null || song == null || controls == null || art.getHeight() == 0) {
            for (int i = 0; i < overlay.getChildCount(); i++) overlay.getChildAt(i).setVisibility(View.INVISIBLE);
            giveBack();
            return;
        }

        int artBottom = top(art) + art.getHeight();
        int sheetTop = sheetAt;
        mesh.seam = artBottom - top(mesh);
        catchArt(a, decor, song);

        // BitChord's column: art | title row | lyric | seek bar + controls | volume | chips | device.
        int hSong = song.getHeight(), hLyric = dp(a, 32), hCtl = controls.getHeight(), hVol = dp(a, 32);
        int hChips = chips == null ? 0 : chips.getHeight(), hDev = dp(a, 20);
        int gArt = dp(a, 14), gLyric = dp(a, 2), gCtl = dp(a, 4), gVol = dp(a, 4), gChips = dp(a, 10), gDev = dp(a, 4), gEnd = dp(a, 6);
        int need = gArt + hSong + gLyric + hLyric + gCtl + hCtl + gVol + hVol + gChips + hChips + gDev + hDev + gEnd;
        int avail = sheetTop - artBottom;
        boolean dev = need <= avail;
        if (!dev) need -= gDev + hDev;
        int extra = avail - need;
        if (extra < 0) {
            // a short screen: the gaps give way first, evenly
            float k = Math.max(0f, 1f + extra / (float) (gArt + gLyric + gCtl + gVol + gChips + gEnd));
            gArt *= k; gLyric *= k; gCtl *= k; gVol *= k; gChips *= k; gEnd *= k;
        } else {
            // tall-screen slack: up to 48dp split around the transport (BitChord's controlSpread),
            // the rest above and below the column
            int spread = Math.min(extra, dp(a, 48));
            gCtl += spread / 2;
            gVol += spread / 2;
            gArt += (extra - spread) / 2;
        }
        int y = artBottom + gArt;
        place(song, y);
        y += hSong + gLyric;
        int lyricY = y;
        y += hLyric + gCtl;
        place(controls, y);
        int ctlY = y;
        y += hCtl + gVol;
        int volY = y;
        y += hVol + gChips;
        if (chips != null && hChips > 0) place(chips, y);
        y += hChips + gDev;
        int devY = y;

        int oy = top(overlay);
        for (int i = 0; i < overlay.getChildCount(); i++) overlay.getChildAt(i).setVisibility(View.VISIBLE);
        if (deviceRoom != dev) {
            deviceRoom = dev;
            Log.i(TAG, "output device line " + (dev ? "shown" : "dropped: no room on this screen"));
        }
        device.setVisibility(dev ? View.VISIBLE : View.GONE);
        moveTo(lyricRow, lyricY - oy);
        moveTo(volRow, volY - oy);
        moveTo(device, devY - oy);
        moveTo(stats, artBottom - dp(a, 10) - stats.getHeight() - oy);
        View bar = decor.findViewById(id(a, "time_bar"));
        if (bar != null) {
            moveTo(zone, top(bar) - oy);
            ViewGroup.LayoutParams lp = zone.getLayoutParams();
            if (lp.height != bar.getHeight()) {
                lp.height = bar.getHeight();
                zone.setLayoutParams(lp);
            }
        }
        // YT's title and artist in BitChord's colours: white, the artist at 55%
        View t = song.findViewById(id(a, "title"));
        View ar = song.findViewById(id(a, "artist"));
        if (t instanceof TextView && ((TextView) t).getCurrentTextColor() != Color.WHITE) ((TextView) t).setTextColor(Color.WHITE);
        if (ar instanceof TextView && ((TextView) ar).getCurrentTextColor() != 0x8CFFFFFF) ((TextView) ar).setTextColor(0x8CFFFFFF);
        if (!layoutLogged) {
            layoutLogged = true;
            Log.i(TAG, "laid out: art ends " + artBottom + ", title " + top(song) + ", lyric " + lyricY + ", controls " + ctlY
                    + ", volume " + volY + ", chips " + (chips == null ? -1 : top(chips)) + ", device " + (dev ? devY : -1)
                    + ", sheet " + sheetTop + " (slack " + extra + "px)");
        }
    }

    /** Draw one of YT's views with its top at screenY; its own translation is kept to give back. */
    private static void place(View v, int screenY) {
        float cur = v.getTranslationY();
        float[] s = moved.get(v);
        if (s == null || Math.abs(cur - s[1]) > 0.5f) {
            // first time, or YT moved it itself since: that is its own value now
            s = new float[]{cur, cur};
            moved.put(v, s);
        }
        float want = cur + (screenY - top(v));
        if (Math.abs(want - cur) > 0.5f) v.setTranslationY(want);
        s[1] = want;
    }

    /** YT's views back where YT put them (collapse, sheet, switch off). */
    private static void giveBack() {
        if (moved.isEmpty()) return;
        for (Map.Entry<View, float[]> e : moved.entrySet()) {
            View v = e.getKey();
            if (v != null && Math.abs(v.getTranslationY() - e.getValue()[1]) < 0.5f) v.setTranslationY(e.getValue()[0]);
        }
        moved.clear();
    }

    private static void moveTo(View v, float y) {
        if (Math.abs(v.getTranslationY() - y) > 0.5f) v.setTranslationY(y);
    }

    private static TextView text(Context c, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        return t;
    }

    private static FrameLayout.LayoutParams at(int w, int h, int sideMargin) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h, Gravity.TOP | Gravity.START);
        lp.leftMargin = sideMargin;
        lp.rightMargin = sideMargin;
        return lp;
    }

    private static void build(Activity a, ViewGroup page, ViewGroup layer) {
        // The mesh goes right above YT's own page background, under everything else.
        mesh = new MeshView(a);
        View bg = page.findViewById(id(a, "player_page_background"));
        int at = bg != null && bg.getParent() == page ? page.indexOfChild(bg) + 1 : 0;
        page.addView(mesh, at, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Ours over YT's bottom layer. The frame takes no touches; only its children do.
        overlay = new FrameLayout(a);
        int side = dp(a, 20); // YT's own side margin on the title row
        View song = layer.findViewById(id(a, "current_song_view"));
        View t = song == null ? null : song.findViewById(id(a, "title"));
        if (t != null && t.getLeft() > 0) side = t.getLeft();

        stats = text(a, 11, 0xCCFFFFFF, false);
        stats.setSingleLine(false);
        stats.setMaxLines(2);
        stats.setShadowLayer(dp(a, 4), 0, dp(a, 1), 0xB3000000);
        overlay.addView(stats, at(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, side + dp(a, 12)));

        // lyric line: 16sp semibold white at 78%, then a small chevron; opens YT's Lyrics tab
        lyricRow = new LinearLayout(a);
        lyricRow.setGravity(Gravity.CENTER_VERTICAL);
        lyric = text(a, 16, 0xC7FFFFFF, true);
        TextView chevron = text(a, 16, 0x80FFFFFF, false);
        chevron.setText(" ›");
        lyricRow.addView(lyric, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        lyricRow.addView(chevron);
        lyricRow.setOnClickListener(v -> openLyrics(a));
        overlay.addView(lyricRow, at(ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 32), side));
        ((LinearLayout.LayoutParams) lyric.getLayoutParams()).width = 0;

        // the automix band, drawn on YT's own seek bar
        zone = new MixZone(a);
        overlay.addView(zone, at(ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 24), 0));

        // volume: quiet icon | thin bar | loud icon
        AudioManager am = (AudioManager) a.getSystemService(Context.AUDIO_SERVICE);
        volRow = new LinearLayout(a);
        volRow.setGravity(Gravity.CENTER_VERTICAL);
        volume = new ThinBar(a, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), value -> {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0);
            Log.i(TAG, "volume -> " + value + " (stream now " + am.getStreamVolume(AudioManager.STREAM_MUSIC) + ")");
        });
        LinearLayout.LayoutParams icon = new LinearLayout.LayoutParams(dp(a, 20), dp(a, 20));
        volRow.addView(new Speaker(a, false), icon);
        LinearLayout.LayoutParams bar = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        bar.leftMargin = dp(a, 10);
        bar.rightMargin = dp(a, 10);
        volRow.addView(volume, bar);
        volRow.addView(new Speaker(a, true), new LinearLayout.LayoutParams(dp(a, 20), dp(a, 20)));
        overlay.addView(volRow, at(ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 32), side));

        // output device: 11sp white at 55%, centred; tapping it opens YT's cast picker
        device = text(a, 11, 0x8CFFFFFF, false);
        device.setGravity(Gravity.CENTER);
        device.setOnClickListener(v -> {
            View route = a.getWindow().getDecorView().findViewById(id(a, "media_route_button"));
            Log.i(TAG, "device line -> media_route_button" + (route == null ? " (missing)" : " performClick=" + route.performClick()));
        });
        FrameLayout.LayoutParams dl = at(Math.round(a.getResources().getDisplayMetrics().widthPixels * 0.65f), dp(a, 20), 0);
        dl.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        overlay.addView(device, dl);

        layer.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        layoutLogged = false;
        sheetRest = 0;
        meshFor = "";
        Log.i(TAG, "BitChord layout on YT's views: mesh in " + page.getClass().getSimpleName() + " at " + at
                + ", our pieces in player_page_bottom_layer");
        loop(a);
    }

    /** SpikeH's Apple palette. BitChord keeps every text and icon white, so nothing takes it now. */
    static void accent(int background, int accent) {
    }

    private static void loop(Activity a) {
        FrameLayout mine = overlay;
        main.postDelayed(() -> {
            if (overlay == null || overlay != mine) return;
            if (a.isDestroyed()) {
                // the activity is gone (closed with the music still playing): our views would keep it
                // alive in these fields until the next one opens; built again then
                if (mesh != null && mesh.getParent() instanceof ViewGroup) ((ViewGroup) mesh.getParent()).removeView(mesh);
                overlay = null;
                mesh = null;
                stats = lyric = device = null;
                lyricRow = volRow = null;
                volume = null;
                zone = null;
                moved.clear();
                return;
            }
            // only while it shows: not while collapsed, under a sheet or switched off
            if (lyricRow.isShown()) refresh(a);
            loop(a);
        }, 500);
    }

    /** Our pieces' contents. */
    private static void refresh(Activity a) {
        try {
            View decor = a.getWindow().getDecorView();
            View bar = decor.findViewById(id(a, "time_bar"));
            if (bar != null) {
                // YT's time bar is a custom view; its accessibility text has the length:
                // "1 minutes 5 seconds of 3 minutes 20 seconds"
                CharSequence d = bar.createAccessibilityNodeInfo().getContentDescription();
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("of (?:(\\d+) hours? )?(\\d+) minutes? (\\d+) seconds?").matcher(String.valueOf(d));
                if (m.find()) {
                    long h = m.group(1) == null ? 0 : Long.parseLong(m.group(1));
                    durationMs = ((h * 60 + Long.parseLong(m.group(2))) * 60 + Long.parseLong(m.group(3))) * 1000;
                }
                if (durationMs > 0) zone.set(app.ayham.extension.music.automix.Automix.zone(), durationMs);
            }
            AudioManager am = (AudioManager) a.getSystemService(Context.AUDIO_SERVICE);
            volume.setValue(am.getStreamVolume(AudioManager.STREAM_MUSIC));
            device.setText(outputName(am));
            String line = SpikeA.currentLyricLine();
            // No song on the page (YT's restored queue offline shows an empty title): no line either,
            // or Morphe's last line from an earlier song would stay up.
            View song = decor.findViewById(id(a, "current_song_view"));
            View title = song == null ? null : song.findViewById(id(a, "title"));
            if (title instanceof android.widget.TextView && ((android.widget.TextView) title).getText().toString().trim().isEmpty()) line = null;
            lyric.setText(line == null ? "" : line.trim());
            lyricRow.getChildAt(1).setVisibility(line == null || line.trim().isEmpty() ? View.INVISIBLE : View.VISIBLE);
            String s = SpikeA.currentStats(), mix = SpikeA.currentAutomixStats();
            StringBuilder b = new StringBuilder();
            if (s != null && !s.isEmpty()) b.append(s);
            if (mix != null && !mix.isEmpty()) b.append(b.length() > 0 ? "\n" : "").append(mix);
            stats.setText(b);
            stats.setVisibility(b.length() == 0 ? View.INVISIBLE : View.VISIBLE);
        } catch (Throwable e) {
            Log.w(TAG, "refresh failed", e);
        }
    }

    private static String outputName(AudioManager am) {
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int type = d.getType();
            if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                    || type == AudioDeviceInfo.TYPE_WIRED_HEADSET || type == AudioDeviceInfo.TYPE_USB_HEADSET)
                return String.valueOf(d.getProductName());
        }
        return "This phone";
    }

    // ---- the artwork mesh (BitChord ArtworkMeshBackdrop) ----

    /** On a new song, copy the art off the screen (a few tries: the art loads late) and build the mesh. */
    private static void catchArt(Activity a, View decor, View song) {
        View t = song.findViewById(id(a, "title"));
        View ar = song.findViewById(id(a, "artist"));
        String key = (t instanceof TextView ? ((TextView) t).getText() : "") + "\u0000" + (ar instanceof TextView ? ((TextView) ar).getText() : "");
        long now = SystemClock.uptimeMillis();
        if (!key.equals(meshWanted)) {
            meshWanted = key;
            meshTry = now + 700; // first copy once YT has drawn the new art
            return;
        }
        if (key.equals(meshFor) || now < meshTry) return;
        meshTry = now + 1500;
        // The art card, without its edges (our own mesh shows beside it).
        View card = decor.findViewById(id(a, "card_view_container"));
        if (card == null || !card.isShown()) card = decor.findViewById(id(a, "player_view_pager_container"));
        if (card == null || card.getWidth() == 0) return;
        int[] at = new int[2];
        card.getLocationInWindow(at);
        int inset = card.getWidth() / 12;
        Rect src = new Rect(at[0] + inset, at[1] + inset, at[0] + card.getWidth() - inset, at[1] + Math.min(card.getHeight(), card.getWidth()) - inset);
        if (src.width() <= 0 || src.height() <= 0) return;
        Bitmap shot = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888);
        try {
            PixelCopy.request(a.getWindow(), src, shot, result -> {
                if (result != PixelCopy.SUCCESS || !key.equals(meshWanted) || mesh == null) return;
                Bitmap m = buildMesh(shot);
                if (m == null) return; // nothing drawn there yet
                meshFor = key;
                mesh.show(m);
                Log.i(TAG, "mesh from the art of '" + key.replace('\u0000', '/') + "'");
            }, main);
        } catch (Throwable e) {
            Log.w(TAG, "art copy failed", e);
        }
    }

    /** 6x6 averaged grid, a little more saturated, flipped so row 0 is the colour the art ends on, blurred at 32x32. */
    private static Bitmap buildMesh(Bitmap shot) {
        final int n = 6, cell = 8;
        int[] grid = new int[n * n];
        float[] hsv = new float[3];
        long lum = 0;
        for (int gy = 0; gy < n; gy++)
            for (int gx = 0; gx < n; gx++) {
                long r = 0, g = 0, b = 0;
                for (int y = 0; y < cell; y++)
                    for (int x = 0; x < cell; x++) {
                        int p = shot.getPixel(gx * cell + x, gy * cell + y);
                        r += Color.red(p);
                        g += Color.green(p);
                        b += Color.blue(p);
                    }
                int c = Color.rgb((int) (r / 64), (int) (g / 64), (int) (b / 64));
                lum += r + g + b;
                Color.colorToHSV(c, hsv);
                hsv[1] = Math.min(1f, hsv[1] * 1.12f);
                // BitChord's floor keeps black art from a dead black page; the cap is ours: plain white
                // text (no blend modes here) has to stay readable on light art.
                hsv[2] = Math.max(0.06f, Math.min(0.42f, hsv[2]));
                grid[(n - 1 - gy) * n + gx] = Color.HSVToColor(hsv);
            }
        if (lum == 0) return null;
        Bitmap small = Bitmap.createBitmap(grid, n, n, Bitmap.Config.ARGB_8888);
        Bitmap big = Bitmap.createScaledBitmap(small, 32, 32, true);
        int[] px = new int[32 * 32];
        big.getPixels(px, 0, 32, 0, 0, 32, 32);
        for (int pass = 0; pass < 3; pass++) px = boxBlur(px, 32);
        return Bitmap.createBitmap(px, 32, 32, Bitmap.Config.ARGB_8888);
    }

    private static int[] boxBlur(int[] in, int s) {
        int[] out = new int[in.length];
        for (int y = 0; y < s; y++)
            for (int x = 0; x < s; x++) {
                int r = 0, g = 0, b = 0, k = 0;
                for (int dy = -1; dy <= 1; dy++)
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = Math.max(0, Math.min(s - 1, x + dx)), yy = Math.max(0, Math.min(s - 1, y + dy));
                        int p = in[yy * s + xx];
                        r += Color.red(p);
                        g += Color.green(p);
                        b += Color.blue(p);
                        k++;
                    }
                out[y * s + x] = Color.rgb(r / k, g / k, b / k);
            }
        return out;
    }

    /** Floor #121212, the mesh stretched from the art's lower edge down, row 0 stretched above it, a dark gradient on top. */
    private static final class MeshView extends View {
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final Paint shade = new Paint();
        private Bitmap now, before;
        private long fadeStart;
        int seam;
        private int shadeFor = -1;

        MeshView(Context c) {
            super(c);
        }

        void show(Bitmap m) {
            before = now;
            now = m;
            fadeStart = SystemClock.uptimeMillis();
            invalidate();
        }

        private void draw(Canvas c, Bitmap m, int alpha) {
            paint.setAlpha(alpha);
            int w = getWidth(), h = getHeight(), s = Math.max(0, Math.min(seam, h));
            if (s > 0) c.drawBitmap(m, new Rect(0, 0, m.getWidth(), 1), new RectF(0, 0, w, s), paint);
            c.drawBitmap(m, null, new RectF(0, s, w, h), paint);
        }

        @Override
        protected void onDraw(Canvas c) {
            c.drawColor(0xFF121212);
            if (now == null) return;
            float t = Math.min(1f, (SystemClock.uptimeMillis() - fadeStart) / 900f);
            if (before != null && t < 1f) draw(c, before, 255);
            draw(c, now, Math.round(255 * t));
            if (t < 1f) postInvalidateOnAnimation();
            if (shadeFor != getHeight()) {
                shadeFor = getHeight();
                shade.setShader(new LinearGradient(0, 0, 0, getHeight(), 0x0F000000, 0x4D000000, Shader.TileMode.CLAMP));
            }
            c.drawRect(0, 0, getWidth(), getHeight(), shade);
        }
    }

    // ---- small pieces ----

    /** BitChord's ThinSlider: a capsule, no thumb, 6dp (10dp while dragged), white 92% on white 26%. */
    private static final class ThinBar extends View {
        interface Listener {
            void changed(int value);
        }

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int max;
        private final Listener listener;
        private int value;
        private boolean active;

        ThinBar(Context c, int max, Listener l) {
            super(c);
            this.max = Math.max(1, max);
            this.listener = l;
        }

        void setValue(int v) {
            if (active || v == value) return;
            value = v;
            invalidate();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    active = true;
                    // fall through
                case MotionEvent.ACTION_MOVE:
                    int v = Math.round(Math.max(0f, Math.min(1f, e.getX() / getWidth())) * max);
                    if (v != value) {
                        value = v;
                        listener.changed(v);
                    }
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    active = false;
                    invalidate();
                    return true;
            }
            return super.onTouchEvent(e);
        }

        @Override
        protected void onDraw(Canvas c) {
            float d = getResources().getDisplayMetrics().density;
            float h = (active ? 10 : 6) * d, cy = getHeight() / 2f, w = getWidth();
            paint.setColor(0x42FFFFFF);
            c.drawRoundRect(0, cy - h / 2, w, cy + h / 2, h / 2, h / 2, paint);
            float fill = Math.max(h, w * value / max);
            paint.setColor(0xEBFFFFFF);
            c.drawRoundRect(0, cy - h / 2, fill, cy + h / 2, h / 2, h / 2, paint);
        }
    }

    /** Volume icons, white at 50%: a speaker with one wave (quiet) or three (loud). */
    private static final class Speaker extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final boolean loud;

        Speaker(Context c, boolean loud) {
            super(c);
            this.loud = loud;
            paint.setColor(0x80FFFFFF);
        }

        @Override
        protected void onDraw(Canvas c) {
            float s = Math.min(getWidth(), getHeight()) / 20f;
            paint.setStyle(Paint.Style.FILL);
            Path p = new Path();
            p.moveTo(2 * s, 7 * s);
            p.lineTo(6 * s, 7 * s);
            p.lineTo(11 * s, 3 * s);
            p.lineTo(11 * s, 17 * s);
            p.lineTo(6 * s, 13 * s);
            p.lineTo(2 * s, 13 * s);
            p.close();
            c.drawPath(p, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.6f * s);
            paint.setStrokeCap(Paint.Cap.ROUND);
            for (int i = 0; i < (loud ? 3 : 1); i++) {
                float r = (3 + i * 3) * s;
                c.drawArc(11 * s - r, 10 * s - r, 11 * s + r, 10 * s + r, -45, 90, false, paint);
            }
        }
    }

    /**
     * The part of the song automix mixes over, drawn on YT's seek bar as BitChord does: a white band
     * at 50% with soft ends from the takeover to the end; it pulses while the mix runs.
     */
    private static final class MixZone extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private long from = -1, dur;
        private boolean running;

        MixZone(Context c) {
            super(c);
        }

        void set(long[] z, long durationMs) {
            long f = z == null ? -1 : z[0];
            long d = z == null || z[1] <= 0 ? durationMs : z[1];
            boolean r = z != null && z[2] == 1;
            if (f == from && d == dur && r == running) return;
            from = f;
            dur = d;
            running = r;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (from < 0 || dur <= 0 || from >= dur) return;
            float d = getResources().getDisplayMetrics().density;
            // YT's seek track: inset like its title row, centred in the bar's height
            float left = 20 * d, right = getWidth() - 20 * d;
            float x = left + (right - left) * from / dur;
            float h = 3 * d, cy = getHeight() / 2f;
            int alpha = 0x80;
            if (running) {
                double t = (SystemClock.uptimeMillis() % 1200) / 1200.0;
                alpha = (int) (0x50 + 0x70 * (0.5 + 0.5 * Math.sin(t * 2 * Math.PI)));
                postInvalidateOnAnimation();
            }
            float feather = Math.max(6 * d, Math.min(40 * d, (right - x) * 0.4f));
            paint.setShader(new LinearGradient(x, 0, x + feather, 0, 0x00FFFFFF, (alpha << 24) | 0xFFFFFF, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(x, cy - h / 2, right, cy + h / 2, h / 2, h / 2, paint);
        }
    }

    /** The lyric line opens YT's own Lyrics tab. */
    private static void openLyrics(Activity a) {
        View tab = findLabel(a.getWindow().getDecorView(), "lyrics");
        if (tab == null) {
            Log.i(TAG, "lyrics tab: not found");
            return;
        }
        View c = tab;
        while (c != null && !c.isClickable()) c = c.getParent() instanceof View ? (View) c.getParent() : null;
        Log.i(TAG, "lyrics tab: " + tab.getClass().getSimpleName() + " click=" + (c != null && c.performClick()));
    }

    private static View findLabel(View v, String label) {
        if (v == overlay || v.getVisibility() != View.VISIBLE) return null;
        CharSequence d = v.getContentDescription();
        CharSequence t = v instanceof TextView ? ((TextView) v).getText() : null;
        if ((d != null && d.toString().trim().equalsIgnoreCase(label)) || (t != null && t.toString().trim().equalsIgnoreCase(label)))
            return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View f = findLabel(g.getChildAt(i), label);
                if (f != null) return f;
            }
        }
        return null;
    }
}
