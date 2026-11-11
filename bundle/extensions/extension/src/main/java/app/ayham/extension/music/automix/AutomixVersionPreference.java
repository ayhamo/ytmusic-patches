package app.ayham.extension.music.automix;

import android.content.Context;
import android.preference.ListPreference;
import android.preference.Preference;
import android.util.AttributeSet;

/**
 * The settings' Automix lists, as in the extension's popup. "Automix version" (Original AutoDJ /
 * Advanced AutoDJ): the key-clash toggle works only in Advanced AutoDJ, so it is greyed out (its
 * setting kept) under Original. "Blend length" (Auto / Up to N bars). The entries are set here, by key.
 */
@SuppressWarnings("deprecation")
public class AutomixVersionPreference extends ListPreference {
    public AutomixVersionPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        entries(attrs);
    }

    public AutomixVersionPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        entries(attrs);
    }

    public AutomixVersionPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        entries(attrs);
    }

    public AutomixVersionPreference(Context context) {
        super(context);
        entries(null);
    }

    private void entries(AttributeSet attrs) {
        String key = attrs == null ? null : attrs.getAttributeValue("http://schemas.android.com/apk/res/android", "key");
        if ("ayham_eq_preset".equals(key)) {
            setEntries(new CharSequence[]{"Bass Boost", "Clear Vocals", "Night Mode", "Room", "Flat"});
            setEntryValues(new CharSequence[]{"bass", "vocals", "night", "room", "flat"});
        } else if (Automix.KEY_BARS.equals(key)) {
            setEntries(new CharSequence[]{"Auto (picks per song, like BitChord phone)", "Up to 4 bars", "Up to 8 bars",
                    "Up to 16 bars (BitChord web default)", "Up to 32 bars"});
            setEntryValues(new CharSequence[]{"auto", "4", "8", "16", "32"});
        } else {
            setEntries(new CharSequence[]{"Original AutoDJ", "Advanced AutoDJ"});
            setEntryValues(new CharSequence[]{"1", "2"});
        }
    }

    @Override
    protected void onAttachedToActivity() {
        super.onAttachedToActivity();
        keyAware(getValue());
    }

    @Override
    public void setValue(String value) {
        super.setValue(value);
        keyAware(value);
    }

    private void keyAware(String value) {
        if (!Automix.KEY_VERSION.equals(getKey())) return;
        try {
            if (getPreferenceManager() == null) return;
            Preference p = getPreferenceManager().findPreference(Automix.KEY_KEY_AWARE);
            if (p != null) p.setEnabled("2".equals(value));
        } catch (Throwable ignored) {
        }
    }
}
