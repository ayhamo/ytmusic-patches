package app.ayham.extension.music.addons;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Which of an addon's search rows to open for a YouTube Music song: our rules (protocol.md §3 and
 * §7) on top of BitChord's TrackMatcher. A port of the extension's src/addons/pick.js; the cases
 * are spec/addons/pick-cases.json.
 */
public final class Pick {
    private Pick() {
    }

    static final int MAX_ROWS = 15;          // rows kept for matching
    static final int MAX_OPEN = 3;           // rows opened (stream asked, probed, fitted)
    static final double PREFER_SEC = 3;      // rows this close in length go first
    static final double DOWNLOAD_SLACK_SEC = 2;
    static final double PREVIEW_MAX_SEC = 35, PREVIEW_SONG_SEC = 45;   // (Mornye) a clip this short of a longer song

    public static final String LOSSLESS = "LOSSLESS", HIGH = "HIGH", LOW = "LOW";
    /** Quality tiers asked of an addon (Protocol.paramsFor): Hi-Res or CD by name; LOSSLESS is its best. */
    public static final String HIRES = "HIRES", CD = "CD";

    /** An addon's search row, its fields read leniently. */
    public static final class Row extends TrackMatcher.Candidate {
        public String isrc;
        public String tier;
        public Double kbps;
        public boolean atmos;
        public String streamURL;
        public Map<String, Object> raw;
    }

    public static final class Refusal {
        public final String id, why;

        Refusal(String id, String why) {
            this.id = id;
            this.why = why;
        }
    }

    public static final class Choice {
        /** At most 3 rows, best first. */
        public final List<Row> open = new ArrayList<>();
        /** In the order the rules refused them. */
        public final List<Refusal> refused = new ArrayList<>();
    }

    public static final class Verdict {
        public final boolean ok;
        public final String why;
        public final Double maxSec;

        Verdict(boolean ok, String why, Double maxSec) {
            this.ok = ok;
            this.why = why;
            this.maxSec = maxSec;
        }
    }

    /** The probe's answer: its status and the two size headers (null when absent). */
    public static final class Head {
        public Integer status;
        public String contentRange;
        public String contentLength;
    }

    private static final Pattern LOSSLESS_TEXT = Pattern.compile("flac|alac|wav|hi[" + Js.WS + "_-]?res|(16|24)[" + Js.WS + "-]?bit|lossless");
    private static final Pattern LOW_TEXT = Pattern.compile(Js.B0 + "low" + Js.B1 + "|128|96|64");
    private static final Pattern HIGH_TEXT = Pattern.compile("320|mp3|aac|opus|high");
    static final Pattern ATMOS = Pattern.compile("atmos|dolby|eac3[_-]?joc|ec-?3", Pattern.CASE_INSENSITIVE);
    private static final Pattern KBPS = Pattern.compile("([0-9]{2,4})[" + Js.WS + "]*kbps", Pattern.CASE_INSENSITIVE);
    private static final Pattern ISRC = Pattern.compile("[A-Z]{2}[A-Z0-9]{3}[0-9]{7}");
    private static final Pattern TOTAL = Pattern.compile("/([0-9]+)[" + Js.WS + "]*\\z");

    /** §3: the tier a row's format text names, or null. */
    public static String tierOf(Object s) {
        String t = Js.text(s).toLowerCase(Locale.ROOT);
        if (t.isEmpty()) return null;
        if (LOSSLESS_TEXT.matcher(t).find()) return LOSSLESS;
        if (LOW_TEXT.matcher(t).find()) return LOW;
        if (HIGH_TEXT.matcher(t).find()) return HIGH;
        return null;
    }

    /** Atmos by NullBeat's flags or by the mode and format text. */
    public static boolean isAtmos(Map<String, Object> raw) {
        if (Boolean.TRUE.equals(Js.flag(raw.get("atmos"), null)) || Boolean.TRUE.equals(Js.flag(raw.get("isAtmos"), null))
                || Boolean.TRUE.equals(Js.flag(raw.get("isAtmosOnly"), null))) return true;
        List<Object> modes = new ArrayList<>();
        modes.add(raw.get("audioMode"));
        if (raw.get("audioModes") instanceof List) modes.addAll((List<?>) raw.get("audioModes"));
        modes.add(raw.get("format"));
        modes.add(raw.get("audioQuality"));
        for (Object m : modes) if (ATMOS.matcher(Js.text(m)).find()) return true;
        return false;
    }

    /** ISRC: 12 letters and digits, case and separators ignored; null when it isn't one. */
    public static String isrcOf(Object v) {
        String s = Js.text(v).toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        return ISRC.matcher(s).matches() ? s : null;
    }

    @SuppressWarnings("unchecked")
    public static Row rowOf(Object rawValue) {
        Map<String, Object> raw = rawValue instanceof Map ? (Map<String, Object>) rawValue : Collections.<String, Object>emptyMap();
        String q = Js.text(raw.get("format")) + " " + Js.text(raw.get("audioQuality"));
        Row r = new Row();
        r.id = Js.text(raw.get("id"));
        r.title = Js.text(raw.get("title"));
        r.artist = Js.text(raw.get("artist"));
        r.album = raw.get("album") == null ? null : Js.text(raw.get("album"));
        r.durationSec = Js.num(raw.get("duration"));
        r.explicit = Js.flag(raw.get("explicit"), null);
        r.isrc = isrcOf(raw.get("isrc"));
        r.tier = tierOf(q);
        r.kbps = Js.num(Js.group1(KBPS, q));
        r.atmos = isAtmos(raw);
        r.streamURL = Js.truthy(raw.get("streamURL")) ? Js.text(raw.get("streamURL")) : null;
        r.raw = raw;
        return r;
    }

    /**
     * The rows to open, best first (at most 3), and why the others were refused.
     * slackSec: the user's length slack (default 5, clamped to 0-10); forDownload: ±2 s instead.
     */
    public static Choice rowsToOpen(List<?> raws, TrackMatcher.Target target, double slackSec, boolean forDownload) {
        Choice out = new Choice();
        double slack = forDownload ? DOWNLOAD_SLACK_SEC : Math.min(10, Math.max(0, slackSec));
        Double song = target.durationSec != null && target.durationSec > 0 ? target.durationSec : null;   // 0: unknown
        List<Row> rows = new ArrayList<>();
        if (raws != null) {
            for (int i = 0; i < raws.size() && i < MAX_ROWS; i++) {
                Row r = rowOf(raws.get(i));
                if (!r.id.isEmpty() && !r.title.isEmpty()) rows.add(r);
            }
        }
        // Atmos (another mix than YouTube's) and previews first
        List<Row> usable = new ArrayList<>();
        for (Row r : rows) {
            boolean preview = r.durationSec != null && song != null && r.durationSec <= PREVIEW_MAX_SEC && song > PREVIEW_SONG_SEC;
            String why = r.atmos ? "atmos" : preview ? "preview" : null;
            if (why != null) out.refused.add(new Refusal(r.id, why));
            else usable.add(r);
        }
        // ISRC first (ours, from Mornye): an equal ISRC is the same recording, whatever the titles say
        String want = isrcOf(target.isrc);
        List<Row> byIsrc = new ArrayList<>(), rest = new ArrayList<>();
        for (Row r : usable) {
            if (want != null && want.equals(r.isrc)) byIsrc.add(r);
            else rest.add(r);
        }
        TrackMatcher.Target t = target;
        if (song == null) {
            t = target.copy();
            t.durationSec = null;
        }
        List<Row> matched = TrackMatcher.ranked(rest, t);
        for (Row r : rest) if (!containsSame(matched, r)) out.refused.add(new Refusal(r.id, "not this song"));
        List<Row> ok = new ArrayList<>();
        List<Row> all = new ArrayList<>(byIsrc);
        all.addAll(matched);
        for (Row r : all) {
            if (r.durationSec != null && song != null && Math.abs(r.durationSec - song) > slack) {
                out.refused.add(new Refusal(r.id, "length"));
                continue;
            }
            ok.add(r);
        }
        // rows within 3 s first, otherwise in the order above
        List<Row> ordered = new ArrayList<>();
        for (Row r : ok) if (near(r, song)) ordered.add(r);
        for (Row r : ok) if (!near(r, song)) ordered.add(r);
        for (int i = 0; i < ordered.size() && i < MAX_OPEN; i++) out.open.add(ordered.get(i));
        return out;
    }

    public static Choice rowsToOpen(List<?> raws, TrackMatcher.Target target) {
        return rowsToOpen(raws, target, 5, false);
    }

    private static boolean near(Row r, Double song) {
        return r.durationSec != null && song != null && Math.abs(r.durationSec - song) <= PREFER_SEC;
    }

    private static boolean containsSame(List<Row> rows, Row r) {
        for (Row x : rows) if (x == r) return true;
        return false;
    }

    /**
     * §7: a file too small to hold the whole song at the row's lowest believable bitrate is a cut.
     * The size is Content-Range's total, or Content-Length of a full (200) answer (a 206's is the
     * 1 byte). Unmeasurable is not refused.
     */
    public static Verdict probeVerdict(Head head, String tier, Double kbps, Double songSec, double slackSec) {
        Double total = Js.num(Js.group1(TOTAL, head == null || head.contentRange == null ? "" : head.contentRange));
        if (total == null && head != null && head.status != null && head.status == 200) total = Js.num(head.contentLength);
        if (!(total != null && total > 0) || !(songSec != null && songSec > 0)) return new Verdict(true, null, null);
        double floor = LOSSLESS.equals(tier) ? 200 : Math.min(kbps != null ? kbps : 64, 64);
        double maxSec = (total * 8) / (floor * 1000);
        return maxSec < songSec - slackSec
                ? new Verdict(false, "file holds at most " + Math.round(maxSec) + " s", null)
                : new Verdict(true, null, maxSec);
    }
}
