package app.ayham.extension.music.addons;

import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A download's file fetch: staged in a .part file and moved in place only when whole, resumed with
 * a Range request when the connection drops, paced per addon, and checked for what it really is
 * (the bytes, not the addon's label) before it is kept.
 */
final class Fetch {
    private static final String TAG = AddonPrep.TAG;
    /** Downloads may be Hi-Res: 1 GB and 15 minutes per file (playback's fetch stops at 300 MB / 120 s). */
    static final long MAX_BYTES = 1L << 30, MAX_MS = 15 * 60_000L;
    /** Gap between two file fetches from the same addon. */
    static final long PACE_MS = 5_000;
    private static final int RESUMES = 3;
    private static final Map<String, Long> lastAt = new HashMap<>();

    private Fetch() {
    }

    /** Waits for the addon's turn (one file every PACE_MS per addon). */
    static void pace(String host) throws InterruptedException {
        long wait;
        synchronized (lastAt) {
            Long at = lastAt.get(host);
            long now = SystemClock.elapsedRealtime();
            wait = at == null ? 0 : at + PACE_MS - now;
            lastAt.put(host, now + Math.max(0, wait));
        }
        if (wait > 0) Thread.sleep(wait);
    }

    /** Fetches the stream into out (via out.part), resuming a dropped connection. */
    static File download(AddonClient.Opened o, File out) throws Exception {
        Protocol.Stream s = o.stream;
        pace(o.addon.host);
        //noinspection ResultOfMethodCallIgnored
        out.getParentFile().mkdirs();
        File part = new File(out.getPath() + ".part");
        //noinspection ResultOfMethodCallIgnored
        part.delete();
        long t0 = SystemClock.elapsedRealtime(), until = t0 + MAX_MS;
        long total = 0, expected = -1;
        boolean done = false;
        IOException last = null;
        try {
            for (int attempt = 0; attempt <= RESUMES && !done; attempt++) {
                HttpURLConnection h = HttpTransport.open(Protocol.audio(s.url, s.headers, total > 0 ? "bytes=" + total + "-" : null));
                h.setReadTimeout(30_000);
                try (InputStream in = h.getInputStream(); OutputStream w = new FileOutputStream(part, total > 0)) {
                    int code = h.getResponseCode();
                    if (total > 0 && code != 206) throw new IOException("resume refused: HTTP " + code);
                    if (code != 200 && code != 206) throw new IOException("audio: HTTP " + code);
                    if (expected < 0) expected = h.getContentLengthLong();
                    byte[] b = new byte[1 << 16];
                    for (int n; (n = in.read(b)) > 0; ) {
                        w.write(b, 0, n);
                        total += n;
                        if (total > MAX_BYTES) throw new IllegalStateException("over 1 GB");
                        if (SystemClock.elapsedRealtime() > until) throw new IllegalStateException("download over 15 minutes");
                    }
                    if (code == 200 && expected > 0 && total < expected) throw new IOException("ended at " + total + " of " + expected);
                    done = true;
                } catch (IOException e) {
                    last = e;
                    if (e.getMessage() != null && e.getMessage().startsWith("resume refused")) break;
                    Log.w(TAG, String.format(Locale.US, "%s: fetch dropped at %d KB, resuming: %s", out.getName(), total / 1024, Protocol.scrub(String.valueOf(e))));
                } finally {
                    h.disconnect();
                }
            }
            if (!done) throw last != null ? last : new IOException("fetch failed");
        } finally {
            if (!done) //noinspection ResultOfMethodCallIgnored
                part.delete();
        }
        if (out.exists() && !out.delete()) throw new IOException("could not replace " + out.getName());
        if (!part.renameTo(out)) throw new IOException("could not keep the file");
        Log.i(TAG, String.format(Locale.US, "%s: %s's file downloaded (%d KB) in %d ms", out.getName(), o.addon.name, out.length() / 1024, SystemClock.elapsedRealtime() - t0));
        return out;
    }

    /** Before the fetch: a stream whose stated format can't be the tier asked for (null: fetch it). */
    static String statedRefusal(Protocol.Stream s, String tier) {
        if (s == null) return null;
        String codec = s.codec == null ? "" : s.codec.toLowerCase(Locale.ROOT);
        if (!codec.isEmpty() && !codec.contains("flac") && !codec.contains("alac") && !codec.contains("wav")) return "not lossless (" + s.codec + ")";
        if (Pick.HIRES.equals(tier) && s.bitDepth != null && s.sampleRate != null && s.bitDepth > 0 && s.sampleRate > 0
                && s.bitDepth <= 16 && s.sampleRate <= 48_000) return "not Hi-Res (" + Js.number(s.bitDepth) + " bit)";
        return null;
    }

    /** What the file really is, from its bytes: lossless, and Hi-Res when asked; null when fine. */
    static String formatRefusal(File f, String ext, String tier, Protocol.Stream s) throws IOException {
        byte[] head = new byte[1 << 16];
        int n;
        try (InputStream in = new FileInputStream(f)) {
            n = in.read(head);
        }
        if (n < 42) return "not an audio file";
        Info i = info(head, n);
        if (i == null) return "not lossless (the file is " + sniff(head, n) + ")";
        if (Pick.HIRES.equals(tier) && !i.hiRes()) return "not Hi-Res (" + i + ")";
        Log.i(TAG, f.getName() + ": " + i + (s != null && s.codec != null ? " (stated " + s.codec + ")" : ""));
        return null;
    }

    /** A lossless file's format. */
    static final class Info {
        final String codec;
        final int rate, bits;

        Info(String codec, int rate, int bits) {
            this.codec = codec;
            this.rate = rate;
            this.bits = bits;
        }

        boolean hiRes() {
            return bits > 16 || rate > 48_000;
        }

        @Override
        public String toString() {
            return codec + " " + (rate > 0 ? Js.number(rate / 1000.0) + " kHz" : "?") + (bits > 0 ? "/" + bits + " bit" : "");
        }
    }

    /** FLAC from its STREAMINFO, ALAC from its 'alac' box; null for anything else. */
    static Info info(byte[] b, int n) {
        int at = 0;
        // an ID3 tag before the FLAC marker
        if (n > 10 && b[0] == 'I' && b[1] == 'D' && b[2] == '3') at = 10 + ((b[6] & 0x7f) << 21 | (b[7] & 0x7f) << 14 | (b[8] & 0x7f) << 7 | (b[9] & 0x7f));
        if (at + 26 <= n && b[at] == 'f' && b[at + 1] == 'L' && b[at + 2] == 'a' && b[at + 3] == 'C') {
            int d = at + 8; // STREAMINFO data
            int rate = (b[d + 10] & 0xff) << 12 | (b[d + 11] & 0xff) << 4 | (b[d + 12] & 0xff) >> 4;
            int bits = ((b[d + 12] & 1) << 4 | (b[d + 13] & 0xff) >> 4) + 1;
            return new Info("FLAC", rate, bits);
        }
        if (n > 8 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') {
            // the 'alac' sample entry: its config box (also 'alac') holds bit depth and rate
            for (int i = 4; i + 36 < n; i++) {
                if (b[i] == 'a' && b[i + 1] == 'l' && b[i + 2] == 'a' && b[i + 3] == 'c') {
                    int j = i + 4;
                    // the second 'alac' (the magic cookie box): version(4) frameLength(4) compat(1) bitDepth(1)... sampleRate at +20
                    for (int k = j; k + 28 < n; k++) {
                        if (b[k] == 'a' && b[k + 1] == 'l' && b[k + 2] == 'a' && b[k + 3] == 'c') {
                            int c = k + 4 + 4;
                            int bits = b[c + 5] & 0xff;
                            int rate = (b[c + 20] & 0xff) << 24 | (b[c + 21] & 0xff) << 16 | (b[c + 22] & 0xff) << 8 | (b[c + 23] & 0xff);
                            return new Info("ALAC", rate, bits);
                        }
                    }
                    return new Info("ALAC", 0, 0);
                }
            }
            return null;
        }
        if (n > 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'A' && b[10] == 'V' && b[11] == 'E') {
            for (int i = 12; i + 24 < n; i++) {
                if (b[i] == 'f' && b[i + 1] == 'm' && b[i + 2] == 't' && b[i + 3] == ' ') {
                    int rate = (b[i + 12] & 0xff) | (b[i + 13] & 0xff) << 8 | (b[i + 14] & 0xff) << 16 | (b[i + 15] & 0xff) << 24;
                    int bits = (b[i + 22] & 0xff) | (b[i + 23] & 0xff) << 8;
                    return new Info("WAV", rate, bits);
                }
            }
            return new Info("WAV", 0, 0);
        }
        return null;
    }

    private static String sniff(byte[] b, int n) {
        if (n > 3 && b[0] == 'I' && b[1] == 'D' && b[2] == '3') return "MP3";
        if (n > 2 && (b[0] & 0xff) == 0xff && (b[1] & 0xe0) == 0xe0) return "MP3 or AAC";
        if (n > 8 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') return "MP4 (AAC)";
        if (n > 4 && b[0] == 'O' && b[1] == 'g' && b[2] == 'g') return "Ogg";
        if (n > 1 && b[0] == '<') return "a web page";
        return "unknown";
    }
}
