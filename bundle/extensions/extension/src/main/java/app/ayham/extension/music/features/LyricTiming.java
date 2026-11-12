package app.ayham.extension.music.features;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;

/**
 * Morphe's lyrics for the lyric line on screen (its LyricsManager, by reflection like the line
 * itself): what state the lookup is in, whether the song is in its intro or an instrumental gap,
 * when the line on screen ends (BitChord fades it out before the next one), and its word timings.
 * Only lyrics that really carry word timings fill word by word; anything else reads -1 and the
 * line shows as plain text.
 */
final class LyricTiming {
    private static final String TAG = "AyhamScreen";
    private static final String MANAGER = "app.morphe.extension.music.patches.lyrics.LyricsManager";

    /** What Morphe has for the song. */
    static final int UNKNOWN = -1, NONE = 0, LOADING = 1, MISSING = 2, PLAIN = 3, TIMED = 4;
    /** An empty line shorter than this is a breath, not an instrumental: the line before stays up. */
    private static final long SHORT_GAP_MS = 2500;

    private static boolean ready, broken;
    private static Method getInstance, positionMs, isPlaying, lines, words, lineStart, lineText, hasWordTimings,
            wStart, wEnd, wText, wSpace, synced, isEmpty, provider;
    private static Field current, state, lyricsVideo;

    /** The lyrics' lines: start times and their letters ("" for an empty line, a gap). */
    private static Object linesFor;
    private static List<?> lineObjs;
    private static long[] starts;
    private static String[] texts;

    /** The line matched last: its lyrics, the text shown, its span and its words' timings. */
    private static Object forLyrics;
    private static String forShown;
    private static long[] start, end;
    private static int[] chars;
    private static int total;
    private static long spanStart, spanEnd = -1, lastPos;
    private static boolean logged;
    private static Object missFor;

    private LyricTiming() {
    }

    private static void init() throws Exception {
        if (ready) return;
        Class<?> m = Class.forName(MANAGER);
        getInstance = m.getMethod("getInstance");
        positionMs = m.getMethod("getPositionMs");
        isPlaying = m.getMethod("isPlaying");
        current = m.getDeclaredField("currentLyrics");
        current.setAccessible(true);
        try {
            state = m.getDeclaredField("state");
            state.setAccessible(true);
        } catch (Throwable t) {
            state = null; // another Morphe version: no loading or "not available" text, the rest works
            Log.w(TAG, "Morphe's lyrics state unreadable", t);
        }
        try {
            lyricsVideo = m.getDeclaredField("lyricsVideoId");
            lyricsVideo.setAccessible(true);
        } catch (Throwable t) {
            lyricsVideo = null; // another Morphe version: during a mix the line follows Morphe's clock
        }
        Class<?> lyrics = Class.forName("app.morphe.extension.music.patches.lyrics.model.Lyrics");
        Class<?> line = Class.forName("app.morphe.extension.music.patches.lyrics.model.LyricsLine");
        Class<?> word = Class.forName("app.morphe.extension.music.patches.lyrics.model.Word");
        lines = lyrics.getMethod("lines");
        provider = lyrics.getMethod("providerName");
        synced = lyrics.getMethod("synced");
        isEmpty = lyrics.getMethod("isEmpty");
        words = line.getMethod("words");
        lineStart = line.getMethod("startTimeMs");
        lineText = line.getMethod("text");
        hasWordTimings = line.getMethod("hasWordTimings");
        wStart = word.getMethod("startMs");
        wEnd = word.getMethod("endMs");
        wText = word.getMethod("text");
        wSpace = word.getMethod("endsWithSpace");
        ready = true;
    }

    /** Morphe's position when the play button was tapped to pause; -1 = none. */
    private static long frozenPos = -1;

    /** The pause was tapped: hold the lyric clock where it is until YT's session catches up. */
    static void freeze() {
        frozenPos = -1;
        try {
            init();
            frozenPos = (Long) positionMs.invoke(getInstance.invoke(null));
        } catch (Throwable ignored) {
        }
    }

    /**
     * Morphe's position, held at the tap for the whole pause: its own paused position is a little
     * behind its running estimate, and the fill would step back. A seek lets go of it.
     */
    private static long position(Object manager) throws Exception {
        long deck = deckPos(manager);
        if (deck >= 0) return deck;
        long pos = (Long) positionMs.invoke(manager);
        if (frozenPos >= 0) {
            boolean paused = PlayerScreen.tapPaused() || !(Boolean) isPlaying.invoke(manager);
            if (holds(pos, frozenPos, paused)) return frozenPos;
            frozenPos = -1;
        }
        return pos;
    }

    /**
     * During a mix, between YT moving on and the handover, the incoming song plays on our deck while
     * YT's player is held: its second heard, when Morphe's lyrics are that song's. -1 otherwise.
     */
    private static long deckPos(Object manager) {
        long ms = app.ayham.extension.music.automix.Automix.incomingHeardMs();
        if (ms < 0 || lyricsVideo == null) return -1;
        try {
            return deckFor(String.valueOf(lyricsVideo.get(manager)), app.ayham.extension.music.automix.Automix.incomingId(), ms);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** The deck's position [ms] counts only when Morphe's lyrics belong to the incoming song. */
    static long deckFor(String lyricsVideoId, String incomingId, long ms) {
        return ms >= 0 && incomingId != null && incomingId.equals(lyricsVideoId) ? ms : -1;
    }

    /**
     * The incoming song's line due on our deck during a mix ("" before its first line or in a gap),
     * or null when Morphe's own line stands: Morphe picks its line on YT's held player, not where the
     * singing is.
     */
    static String deckLine() {
        if (broken) return null;
        try {
            init();
            Object manager = getInstance.invoke(null);
            long pos = deckPos(manager);
            Object lyr = current.get(manager);
            if (pos < 0 || lyr == null) return null;
            lines(lyr);
            int i = at(pos);
            return i < 0 ? "" : String.valueOf(lineText.invoke(lineObjs.get(i))).trim();
        } catch (Throwable t) {
            return null;
        }
    }

    /** The held position still stands: paused, and Morphe's own is near it (further = a seek). */
    static boolean holds(long pos, long frozen, boolean paused) {
        return frozen >= 0 && paused && Math.abs(pos - frozen) < 1500;
    }

    /** Whether Morphe's lyrics clock runs (the song plays). */
    static boolean playing() {
        if (PlayerScreen.tapPaused()) return false;
        if (app.ayham.extension.music.automix.Automix.incomingHeardMs() >= 0) return true; // on our deck
        try {
            init();
            return (Boolean) isPlaying.invoke(getInstance.invoke(null));
        } catch (Throwable t) {
            return false;
        }
    }

    /** What Morphe has for the song: lyrics (timed or plain), a lookup running, none found. */
    static int status() {
        if (broken) return UNKNOWN;
        try {
            init();
            Object manager = getInstance.invoke(null);
            Object lyr = current.get(manager);
            if (lyr != null && !(Boolean) isEmpty.invoke(lyr)) return (Boolean) synced.invoke(lyr) ? TIMED : PLAIN;
            String s = state == null ? "" : String.valueOf(state.get(manager));
            switch (s) {
                case "LOADING":
                    return LOADING;
                case "NOT_FOUND":
                case "ERROR":
                    return MISSING;
                default:
                    return NONE;
            }
        } catch (Throwable t) {
            return UNKNOWN;
        }
    }

    /**
     * 1 = before the first sung line (the intro), 2 = an instrumental gap (an empty line, long enough),
     * 0 = singing, or not known. A line Morphe already shows a little early counts as singing.
     */
    static int phase(String shown) {
        if (broken) return 0;
        try {
            init();
            Object manager = getInstance.invoke(null);
            Object lyr = current.get(manager);
            if (lyr == null) return 0;
            lines(lyr);
            int first = nextVocal(0);
            if (first < 0) return 0;
            long pos = position(manager);
            int at = at(pos);
            int phase;
            if (pos < starts[first]) phase = 1;
            else if (at >= 0 && texts[at].isEmpty()) {
                int v = nextVocal(at);
                phase = v < 0 || starts[v] - starts[at] >= SHORT_GAP_MS ? 2 : 0;
            } else return 0;
            int v = nextVocal(Math.max(at, 0));
            String want = letters(shown == null ? "" : shown);
            if (phase != 0 && v >= 0 && !want.isEmpty() && texts[v].equals(want)) return 0;
            return phase;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** @return the characters of [shown] sung so far, or -1 when the line has no word timings. */
    static float fill(String shown) {
        if (broken || shown == null || shown.trim().isEmpty()) return -1;
        try {
            init();
            Object manager = getInstance.invoke(null);
            Object lyr = current.get(manager);
            if (lyr == null) {
                spanEnd = -1;
                return -1;
            }
            long pos = position(manager);
            lastPos = pos;
            if (rematch(lyr == forLyrics, shown.equals(forShown), pos, spanEnd)) match(lyr, shown, pos);
            if (start == null) return -1;
            return LyricWords.shown(LyricWords.sung(start, end, chars, pos), total, shown.length());
        } catch (Throwable t) {
            broken = true;
            spanEnd = -1;
            Log.w(TAG, "word timings unreadable: the lyric line stays plain", t);
            return -1;
        }
    }

    /**
     * Whether to find the line on screen again: new lyrics, new text, or the playhead past the end of
     * the line found. The last is a repeated line ("Bow (work out, work out)" twice): its text does
     * not change, but it is the next line, with its own words and end, or it fades out and leaves
     * only the chevron.
     */
    static boolean rematch(boolean sameLyrics, boolean sameText, long pos, long spanEnd) {
        return !sameLyrics || !sameText || (spanEnd >= 0 && pos >= spanEnd);
    }

    private static float fadeLen() {
        return Math.max(160f, Math.min(700f, (spanEnd - spanStart) * 0.28f));
    }

    /**
     * BitChord's line fade, for the line [fill] read last: 1 until the last 28% of its time (160 to
     * 700 ms), then down to 0 when the next line starts. 1 when its end is not known.
     */
    static float lineFade() {
        if (spanEnd < 0) return 1f;
        return Math.max(0f, Math.min(1f, (spanEnd - lastPos) / fadeLen()));
    }

    /** Milliseconds until the line [fill] read last starts to fade (-1: it does not). */
    static long msToFade() {
        return spanEnd < 0 ? -1 : (long) (spanEnd - fadeLen() - lastPos);
    }

    /** Milliseconds until the line [fill] read last ends (-1: not known). */
    static long msToEnd() {
        return spanEnd < 0 ? -1 : spanEnd - lastPos;
    }

    private static void lines(Object lyr) throws Exception {
        if (lyr == linesFor) return;
        List<?> ls = (List<?>) lines.invoke(lyr);
        long[] s = new long[ls.size()];
        String[] t = new String[ls.size()];
        for (int i = 0; i < ls.size(); i++) {
            s[i] = (Long) lineStart.invoke(ls.get(i));
            t[i] = letters(String.valueOf(lineText.invoke(ls.get(i))));
        }
        lineObjs = ls;
        starts = s;
        texts = t;
        linesFor = lyr;
    }

    /** How long past a line's end Morphe may still name it before the line at the playhead is shown. */
    static final long CATCH_UP_MS = 60;

    /** True when the playhead is past the line's end by [CATCH_UP_MS] (its end known). */
    static boolean overdue(long pos, long end) {
        return end >= 0 && pos >= end + CATCH_UP_MS;
    }

    /**
     * Morphe's line, or the line at the playhead once Morphe's has ended and it has not moved on (a
     * poll behind, or stuck): the faded line would leave the strip blank, where BitChord always shows
     * the line due, or its note ("" here: a gap).
     */
    static String catchUp(String shown) {
        if (broken || shown == null) return shown;
        if (stale != null && !stale.equals(shown)) stale = null; // Morphe has moved on
        try {
            init();
            Object manager = getInstance.invoke(null);
            Object lyr = current.get(manager);
            if (lyr == null || starts == null || lyr != linesFor) return shown;
            long pos = position(manager);
            if (stale == null && !(shown.equals(forShown) && overdue(pos, spanEnd))) return shown;
            stale = shown;
            int i = at(pos);
            return i < 0 ? shown : String.valueOf(lineText.invoke(lineObjs.get(i))).trim();
        } catch (Throwable t) {
            return shown;
        }
    }

    /** Morphe's line that ran past its end, while it still names it. */
    private static String stale;

    /** The line at [pos] (see {@link #lineAt}), or -1. */
    private static int at(long pos) {
        return lineAt(starts, pos);
    }

    /**
     * The last line started at [pos], or -1. Of lines sharing a start (a sung line and its ad-lib,
     * "Bounce on the stage..." and "back it up" both at 45.55 s), the first: the one Morphe shows.
     */
    static int lineAt(long[] starts, long pos) {
        int at = -1;
        for (int i = 0; i < starts.length; i++) {
            if (starts[i] <= pos) at = i;
            else break;
        }
        while (at > 0 && starts[at - 1] == starts[at]) at--;
        return at;
    }

    private static int nextVocal(int from) {
        for (int i = Math.max(0, from); i < texts.length; i++) if (!texts[i].isEmpty()) return i;
        return -1;
    }

    /** Where line [i] ends (see {@link #lineEnd}). */
    private static long endOf(int i) {
        return lineEnd(starts, texts, i);
    }

    /**
     * Where line [i] ends: the start of the next line that starts later (an ad-lib stamped at the
     * same moment is not its end, or the line would last 0 ms and show faded out), past a short
     * empty line to the sung one after it. -1 for the last line.
     */
    static long lineEnd(long[] starts, String[] texts, int i) {
        int j = i + 1;
        while (j < starts.length && starts[j] <= starts[i]) j++;
        if (j >= starts.length) return -1;
        if (!texts[j].isEmpty()) return starts[j];
        int v = j;
        while (v < texts.length && texts[v].isEmpty()) v++;
        return v < texts.length && starts[v] - starts[j] < SHORT_GAP_MS ? starts[v] : starts[j];
    }

    /**
     * Which of the lines near the playhead is the one on screen, among those reading the same ([same]):
     * the one running now, else the next to start (Morphe names a line a moment early), else the last
     * one past. A repeated line ("Bow (work out, work out)") would otherwise match its earlier copy,
     * already ended, and be drawn faded out: only the chevron showing. -1 when none reads the same.
     */
    static int pickLine(long[] starts, long[] ends, boolean[] same, long pos) {
        int next = -1, past = -1;
        for (int k = 0; k < starts.length; k++) {
            if (!same[k]) continue;
            boolean ended = ends[k] >= 0 && pos >= ends[k];
            if (starts[k] <= pos && !ended) return k;
            if (starts[k] > pos) {
                if (next < 0) next = k;
            } else past = k;
        }
        return next >= 0 ? next : past;
    }

    /** Finds the line on screen: the one at the position or a neighbour (the text can be a poll behind). */
    private static void match(Object lyr, String shown, long pos) throws Exception {
        forLyrics = lyr;
        forShown = shown;
        start = null;
        spanEnd = -1;
        lines(lyr);
        int at = at(pos);
        String want = letters(shown);
        String why = "no line near " + pos + " ms reads the same";
        int lo = Math.max(0, at - 2), hi = Math.min(starts.length - 1, at + 2);
        long[] ends = new long[Math.max(0, hi - lo + 1)];
        boolean[] same = new boolean[ends.length];
        for (int k = 0; k < ends.length; k++) {
            ends[k] = endOf(lo + k);
            same[k] = texts[lo + k].equals(want);
        }
        int pick = pickLine(java.util.Arrays.copyOfRange(starts, lo, lo + ends.length), ends, same, pos);
        for (int i : pick < 0 ? new int[0] : new int[]{lo + pick}) {
            spanStart = starts[i];
            spanEnd = endOf(i);
            Object line = lineObjs.get(i);
            if (!(Boolean) hasWordTimings.invoke(line)) {
                why = "line timing only";
                break;
            }
            List<?> ws = (List<?>) words.invoke(line);
            long[] s = new long[ws.size()], e = new long[ws.size()];
            int[] c = new int[ws.size()];
            StringBuilder joined = new StringBuilder();
            int n = 0;
            for (int k = 0; k < ws.size(); k++) {
                Object w = ws.get(k);
                String text = String.valueOf(wText.invoke(w));
                s[k] = (Long) wStart.invoke(w);
                e[k] = (Long) wEnd.invoke(w);
                c[k] = text.length() + ((Boolean) wSpace.invoke(w) ? 1 : 0);
                n += c[k];
                joined.append(text).append(' ');
            }
            if (ws.isEmpty() || !letters(joined.toString()).equals(want)) {
                why = "its words read '" + joined.toString().trim() + "'";
                break;
            }
            start = s;
            end = e;
            chars = c;
            total = n;
            if (!logged) {
                logged = true;
                Log.i(TAG, "lyric line fills word by word (" + ws.size() + " words in '" + shown + "')");
            }
            return;
        }
        if (lyr != missFor) {
            // once per song: why its line stays plain (to check a provider or a new Morphe version)
            missFor = lyr;
            Log.i(TAG, "lyric line plain for '" + shown + "': " + why + " (" + provider.invoke(lyr) + ")");
        }
    }

    static String letters(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isLetterOrDigit(ch)) b.append(Character.toLowerCase(ch));
        }
        return b.toString().toLowerCase(Locale.ROOT);
    }
}
