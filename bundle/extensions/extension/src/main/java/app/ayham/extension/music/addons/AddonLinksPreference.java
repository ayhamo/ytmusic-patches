package app.ayham.extension.music.addons;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.TypedArray;
import android.preference.Preference;
import android.text.InputType;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * The settings' "Addon links": the user's addons in the order they are asked (the first one
 * first). A tap opens the list: add a link (pasted from the addon's site), or tap one to move it
 * to the top or remove it. Links hold the user's token, so only the host is ever shown.
 */
@SuppressWarnings("deprecation")
public class AddonLinksPreference extends Preference {
    public AddonLinksPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        refresh();
    }

    public AddonLinksPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        refresh();
    }

    public AddonLinksPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        refresh();
    }

    public AddonLinksPreference(Context context) {
        super(context);
        refresh();
    }

    static String shown(String link) {
        Protocol.Url u = Protocol.parse(link);
        return u == null ? "(not a link)" : u.host;
    }

    private void refresh() {
        List<String> l = AddonSettings.links(getContext());
        setTitle("Addon links");
        if (l.isEmpty()) {
            setSummary("None yet. Tap to add your addons' links; the first one is asked first.");
            return;
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < l.size(); i++) b.append(i == 0 ? "" : ", ").append(i + 1).append(". ").append(shown(l.get(i)));
        setSummary(b + ". Tap to add, reorder or remove.");
    }

    @Override
    protected void onClick() {
        open();
    }

    private void open() {
        final Context c = getContext();
        final List<String> links = AddonSettings.links(c);
        TypedArray ta = c.obtainStyledAttributes(new int[]{android.R.attr.textColorPrimary, android.R.attr.textColorSecondary});
        final int text = ta.getColor(0, 0xFFFFFFFF), sub = ta.getColor(1, 0xB3FFFFFF);
        ta.recycle();
        int pad = dp(c, 20);
        LinearLayout list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(pad, dp(c, 8), pad, 0);
        final AlertDialog[] dialog = new AlertDialog[1];
        if (links.isEmpty()) {
            TextView t = new TextView(c);
            t.setText("No addons yet.");
            t.setTextColor(sub);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            list.addView(t);
        }
        for (int i = 0; i < links.size(); i++) {
            final int at = i;
            TextView t = new TextView(c);
            t.setText((i + 1) + ".  " + shown(links.get(i)));
            t.setTextColor(text);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            t.setPadding(0, dp(c, 12), 0, dp(c, 12));
            t.setOnClickListener(v -> {
                String[] what = at == 0 ? new String[]{"Remove"} : new String[]{"Move to the top", "Remove"};
                new AlertDialog.Builder(c).setTitle(shown(links.get(at))).setItems(what, (d, which) -> {
                    String l = links.remove(at);
                    if (what[which].startsWith("Move")) links.add(0, l);
                    AddonSettings.setLinks(c, links);
                    refresh();
                    if (dialog[0] != null) dialog[0].dismiss();
                    open();
                }).show();
            });
            list.addView(t);
        }
        ScrollView sv = new ScrollView(c);
        sv.addView(list);
        dialog[0] = new AlertDialog.Builder(c)
                .setTitle("Addon links")
                .setView(sv)
                .setPositiveButton("Add", (d, w) -> add(links))
                .setNegativeButton("Done", null)
                .show();
    }

    private void add(final List<String> links) {
        final Context c = getContext();
        final EditText e = new EditText(c);
        e.setHint("https://…");
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setSingleLine(true);
        LinearLayout box = new LinearLayout(c);
        int pad = dp(c, 20);
        box.setPadding(pad, dp(c, 8), pad, 0);
        box.addView(e, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(c)
                .setTitle("Add an addon")
                .setMessage("Paste the addon's link (its manifest link from the addon's site). It holds your token and stays on this device.")
                .setView(box)
                .setPositiveButton("Add", (d, w) -> {
                    String l = e.getText().toString().trim();
                    String why = AddonSettings.problem(l);
                    if (why != null) {
                        Toast.makeText(c, why, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (links.contains(l)) {
                        Toast.makeText(c, "Already in the list", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    links.add(l);
                    AddonSettings.setLinks(c, links);
                    refresh();
                    open();
                })
                .setNegativeButton("Cancel", (d, w) -> open())
                .show();
        e.requestFocus();
    }

    private static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
