package app.ayham.extension.music.features;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;

/**
 * lyrics.md §3.3-4: the lyric strip's timing: this song's offset and the global one, each in steps of
 * 50 ms on a slider over ±3000, a field for any value and a reset to 0. Both apply as they change.
 */
final class LyricsOffsetDialog {
    private static final String TAG = "AyhamScreen";
    static final int STEP = 50, RANGE = 3000;

    private LyricsOffsetDialog() {
    }

    static void show(Activity a) {
        String song = LyricTiming.lyricsId();
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * a.getResources().getDisplayMetrics().density);
        col.setPadding(pad, pad / 2, pad, 0);
        if (song != null) row(a, col, "This song", song, LyricTiming.songOffset(song));
        row(a, col, "All songs", null, LyricTiming.globalOffset());
        TextView note = new TextView(a);
        note.setText("Positive: the lyrics come earlier.");
        note.setAlpha(0.7f);
        col.addView(note);
        new AlertDialog.Builder(a).setTitle("Lyrics timing").setView(col).setPositiveButton("Done", null).show();
    }

    /** A narrow borderless button: four of them and the field fit the dialog's width. */
    private static Button small(Activity a) {
        Button b = new Button(a, null, android.R.attr.borderlessButtonStyle);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        int p = Math.round(10 * a.getResources().getDisplayMetrics().density);
        b.setPadding(p, 0, p, 0);
        return b;
    }

    private static void row(Activity a, LinearLayout col, String title, String songId, long start) {
        TextView head = new TextView(a);
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        head.setPadding(0, col.getChildCount() > 0 ? 24 : 0, 0, 0);
        col.addView(head);
        SeekBar bar = new SeekBar(a);
        bar.setMax(2 * RANGE / STEP);
        col.addView(bar);
        LinearLayout r = new LinearLayout(a);
        r.setGravity(Gravity.CENTER_VERTICAL);
        EditText field = new EditText(a);
        field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        field.setEms(3);
        final long[] v = {start};
        final boolean[] quiet = {false};
        Runnable apply = () -> {
            LyricTiming.setOffset(songId, v[0]);
            head.setText(String.format(Locale.US, "%s: %+d ms", title, v[0]));
            quiet[0] = true;
            bar.setProgress((int) ((Math.max(-RANGE, Math.min(RANGE, v[0])) + RANGE) / STEP));
            String t = String.valueOf(v[0]);
            if (!field.getText().toString().equals(t)) field.setText(t);
            quiet[0] = false;
        };
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (!user || quiet[0]) return;
                v[0] = (long) p * STEP - RANGE;
                apply.run();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                Log.i(TAG, "lyrics offset: " + (songId == null ? "global" : songId) + " " + v[0] + " ms");
            }
        });
        field.setOnEditorActionListener((x, id, e) -> {
            try {
                v[0] = Long.parseLong(field.getText().toString().trim());
                apply.run();
                Log.i(TAG, "lyrics offset: " + (songId == null ? "global" : songId) + " " + v[0] + " ms (typed)");
            } catch (NumberFormatException ignored) {
            }
            return false;
        });
        for (String label : new String[]{"−" + STEP, "+" + STEP}) {
            Button b = small(a);
            b.setText(label);
            b.setAllCaps(false);
            b.setOnClickListener(x -> {
                v[0] += label.startsWith("+") ? STEP : -STEP;
                apply.run();
            });
            r.addView(b);
        }
        r.addView(field);
        Button reset = small(a);
        reset.setText("Reset");
        reset.setAllCaps(false);
        reset.setOnClickListener(x -> {
            v[0] = 0;
            apply.run();
        });
        r.addView(reset);
        col.addView(r);
        apply.run();
    }
}
