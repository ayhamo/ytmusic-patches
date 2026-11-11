package app.ayham.extension.music.spike;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Throwaway spike: turns an fMP4 HLS clip (what Apple's motion artwork is) into one local
 * fragmented .mp4 that Android's MediaPlayer can loop. MediaPlayer's own HLS client rejects
 * fMP4 (error -1007), and this avoids bundling ExoPlayer. Picks an H.264 variant around 1080p
 * or below, downloads init + segments (up to {@link #MAX_SECONDS}), and caches by URL.
 */
final class HlsClip {
    private static final String TAG = "AyhamSpike";
    private static final double MAX_SECONDS = 40;
    private static final int MAX_HEIGHT = 1080;
    private static final long CACHE_BYTES = 80L * 1024 * 1024;

    private HlsClip() {
    }

    /** Off the main thread. @return the cached file, or null. */
    static File fetch(String masterUrl, File cacheDir, int artPx) throws Exception {
        return fetch(masterUrl, cacheDir, artPx, true);
    }

    /** @param trim false for the downloaded songs' clips (kept in the app's files, no size cap). */
    static File fetch(String masterUrl, File cacheDir, int artPx, boolean trim) throws Exception {
        File dir = new File(cacheDir, "canvas");
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        // the art's size picks the variant: the smallest that still covers it, 360p to 1080p
        int want = artPx < 480 ? 360 : artPx < 900 ? 720 : MAX_HEIGHT;
        File out = new File(dir, Integer.toHexString(masterUrl.hashCode()) + "-" + want + ".mp4");
        if (out.length() > 0) {
            out.setLastModified(System.currentTimeMillis());
            Log.i(TAG, "canvas: clip from cache " + out.length() / 1024 + " KB");
            return out;
        }
        long start = System.currentTimeMillis();
        String master = text(masterUrl);
        String media = master.contains("#EXT-X-STREAM-INF") ? pickVariant(masterUrl, master, want) : masterUrl;
        if (media == null) return null;
        String playlist = media.equals(masterUrl) ? master : text(media);

        File tmp = new File(dir, out.getName() + ".part");
        long bytes = 0;
        double seconds = 0;
        int segments = 0;
        boolean whole = false;
        try (OutputStream o = new FileOutputStream(tmp)) {
            Matcher map = Pattern.compile("#EXT-X-MAP:URI=\"([^\"]+)\"(?:,BYTERANGE=\"(\\d+)@(\\d+)\")?").matcher(playlist);
            if (map.find()) bytes += copy(resolve(media, map.group(1)), map.group(2), map.group(3), o);
            String range = null;
            double duration = 0;
            long nextOffset = 0;
            for (String line : playlist.split("\n")) {
                line = line.trim();
                if (line.startsWith("#EXTINF:")) {
                    duration = Double.parseDouble(line.substring(8).split(",")[0]);
                } else if (line.startsWith("#EXT-X-BYTERANGE:")) {
                    range = line.substring(17);
                } else if (!line.isEmpty() && !line.startsWith("#")) {
                    String len = null, off = null;
                    if (range != null) {
                        String[] r = range.split("@");
                        len = r[0];
                        off = r.length > 1 ? r[1] : String.valueOf(nextOffset);
                        nextOffset = Long.parseLong(off) + Long.parseLong(len);
                    }
                    bytes += copy(resolve(media, line), len, off, o);
                    range = null;
                    segments++;
                    seconds += duration;
                    if (seconds >= MAX_SECONDS) break;
                }
            }
            whole = true;
        } finally {
            // a download that broke off leaves no part file behind (the offline folder is never trimmed)
            if (!whole) //noinspection ResultOfMethodCallIgnored
                tmp.delete();
        }
        if (!tmp.renameTo(out)) return null;
        if (trim) trim(dir, out);
        Log.i(TAG, "canvas: clip " + segments + " segments, " + Math.round(seconds) + " s, " + bytes / 1024
                + " KB in " + (System.currentTimeMillis() - start) + " ms");
        return out;
    }

    /** Keep the cache under CACHE_BYTES: the least recently shown clips go first. */
    private static void trim(File dir, File keep) {
        File[] files = dir.listFiles();
        if (files == null) return;
        java.util.Arrays.sort(files, (x, y) -> Long.compare(y.lastModified(), x.lastModified()));
        long total = 0;
        int removed = 0;
        for (File f : files) {
            total += f.length();
            if (total > CACHE_BYTES && !f.equals(keep) && f.delete()) removed++;
        }
        if (removed > 0) Log.i(TAG, "canvas: cache over " + CACHE_BYTES / 1048576 + " MB, removed " + removed + " old clips");
    }

    /** The H.264 variant with the fewest pixels at or above `want` lines (else the largest under it); any variant if none says avc1. */
    private static String pickVariant(String base, String master, int want) {
        String[] lines = master.split("\n");
        String best = null, fallback = null;
        long bestPixels = -1, fallbackBw = Long.MAX_VALUE;
        boolean bestCovers = false;
        for (int i = 0; i < lines.length - 1; i++) {
            if (!lines[i].startsWith("#EXT-X-STREAM-INF:")) continue;
            String attrs = lines[i];
            String uri = lines[i + 1].trim();
            long bw = longAttr(attrs, "BANDWIDTH");
            Matcher res = Pattern.compile("RESOLUTION=(\\d+)x(\\d+)").matcher(attrs);
            long w = 0, h = 0;
            if (res.find()) {
                w = Long.parseLong(res.group(1));
                h = Long.parseLong(res.group(2));
            }
            boolean avc = attrs.toLowerCase(Locale.ROOT).contains("avc1");
            if (avc && h <= MAX_HEIGHT) {
                boolean covers = Math.min(w, h) >= want;
                // prefer a variant that covers the art; among those the smallest, else the biggest
                boolean better = best == null
                        || (covers && (!bestCovers || w * h < bestPixels))
                        || (!covers && !bestCovers && w * h > bestPixels);
                if (better) {
                    bestPixels = w * h;
                    best = uri;
                    bestCovers = covers;
                }
            }
            if (bw < fallbackBw) {
                fallbackBw = bw;
                fallback = uri;
            }
        }
        String pick = best != null ? best : fallback;
        Log.i(TAG, "canvas: variant " + pick + (best == null ? " (no avc1, lowest bandwidth)" : ""));
        return pick == null ? null : resolve(base, pick);
    }

    private static long longAttr(String attrs, String name) {
        Matcher m = Pattern.compile(name + "=(\\d+)").matcher(attrs);
        return m.find() ? Long.parseLong(m.group(1)) : 0;
    }

    private static String resolve(String base, String ref) {
        try {
            return new URL(new URL(base), ref).toString();
        } catch (Exception e) {
            return ref;
        }
    }

    private static HttpURLConnection open(String url, String len, String off) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(15_000);
        if (len != null) {
            long from = Long.parseLong(off);
            c.setRequestProperty("Range", "bytes=" + from + "-" + (from + Long.parseLong(len) - 1));
        }
        if (c.getResponseCode() >= 400) throw new IllegalStateException("http " + c.getResponseCode());
        return c;
    }

    private static long copy(String url, String len, String off, OutputStream o) throws Exception {
        long n = 0;
        try (InputStream in = open(url, len, off).getInputStream()) {
            byte[] b = new byte[32768];
            int r;
            while ((r = in.read(b)) > 0) {
                o.write(b, 0, r);
                n += r;
            }
        }
        return n;
    }

    private static String text(String url) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        copy(url, null, null, buf);
        return buf.toString("UTF-8");
    }
}
