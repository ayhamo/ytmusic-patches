package app.ayham.extension.music.features;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Canvas from the song's music video (Metrolist's pipeline, Prism's video and part picking): a video
 * search scored by Prism's rules gives the video, its "Most replayed" curve picks the part, and only the DASH
 * fragments of that part are fetched (visionOS player URLs need no signature solving), then remuxed
 * into a small self-contained MP4 that loops muted over the art.
 */
final class VideoCanvas {
    private static final String TAG = "Ayham";
    static final double CLIP_SEC = 8;
    private static final String WEB_NEXT = "https://www.youtube.com/youtubei/v1/next?prettyPrint=false";
    private static final String VISION = "https://www.youtube.com/youtubei/v1/";
    private static final String VISION_VERSION = "1.02";
    private static volatile String visitor;

    private VideoCanvas() {
    }

    /** The loop file for the song, or null (none, or not this time). [noVideo] is set when the song has no usable video. */
    static synchronized File clip(String songId, String songTitle, String artist, File dir, int artPx, boolean[] noVideo) throws Exception {
        long t0 = System.currentTimeMillis();
        String[] video = findVideo(songTitle, artist);
        if (video == null) {
            noVideo[0] = true;
            return null;
        }
        JSONObject player = visionPlayer(video[0]);
        String status = player.optJSONObject("playabilityStatus") == null ? "" : player.getJSONObject("playabilityStatus").optString("status");
        if (!"OK".equals(status)) {
            Log.i(TAG, "canvas video: " + video[0] + " not playable (" + status + ")");
            return null;
        }
        // the square crop shows the video's full height across the art (the screen's width): 360p up to 1080p
        int screen = android.content.res.Resources.getSystem().getDisplayMetrics().widthPixels;
        int want = Math.max(artPx, screen) < 480 ? 360 : Math.max(artPx, screen) < 900 ? 720 : 1080;
        JSONObject f = pickFormat(player.optJSONObject("streamingData"), want);
        if (f == null) {
            noVideo[0] = true;
            Log.i(TAG, "canvas video: " + video[0] + " has no H.264 DASH stream");
            return null;
        }
        double dur = player.optJSONObject("videoDetails") == null ? 0 : player.getJSONObject("videoDetails").optDouble("lengthSeconds", 0);
        double[] heat = heatmap(video[0]);
        double peak = heat == null ? -1 : partStart(heat, dur);
        File canvas = new File(dir, "canvas");
        if (!canvas.isDirectory() && !canvas.mkdirs()) return null;
        File out = new File(canvas, "mv_" + songId + ".mp4");

        // Prism's first look: the storyboard (bars, a still upload, the best window near the peak, the crop)
        float[] bars = new float[4];
        float crop = 0.5f;
        List<double[]> windows = null;
        long length = Math.round(dur * 1000);
        double start = peak >= 0 ? peak : Math.max(0, dur * 0.4);
        try {
            Board b = board(player, length);
            if (b != null) {
                bars = ClipJudge.bars(b.frames);
                if (ClipJudge.still(b.frames, bars)) {
                    noVideo[0] = true;
                    Log.i(TAG, "canvas video: " + video[0] + " is a still picture (" + b.frames.size() + " preview frames)");
                    return null;
                }
                windows = ClipJudge.windows(b.frames, b.interval, length, bars);
                double[] w = ClipJudge.choose(windows, length, peak < 0 ? -1 : Math.round(peak * 1000), null);
                if (w != null) {
                    start = w[0] / 1000;
                    int i0 = (int) (w[0] / b.interval), i1 = (int) Math.min(b.frames.size() - 1, (w[1] + b.interval - 1) / b.interval);
                    crop = ClipJudge.cropX(b.frames.subList(i0, i1 + 1), bars);
                }
                Log.i(TAG, String.format(Locale.ROOT, "canvas video: storyboard %d frames every %d ms, bars %.2f/%.2f/%.2f/%.2f, %d windows, crop %.2f",
                        b.frames.size(), b.interval, bars[0], bars[1], bars[2], bars[3], windows.size(), crop));
            }
        } catch (Throwable t) {
            Log.w(TAG, "canvas video: storyboard not read: " + t);
        }
        start = Math.max(0, Math.min(start, dur - CLIP_SEC));
        Log.i(TAG, "canvas video: " + video[0] + " '" + video[1] + "' " + f.optInt("height") + "p, part at "
                + String.format(Locale.ROOT, "%.1f", start) + " s of " + (int) dur + " s ("
                + (heat == null ? "no most-replayed curve" : String.format(Locale.ROOT, "most replayed at %.1f s", peak)) + "), found in " + (System.currentTimeMillis() - t0) + " ms");

        // Prism's second look, on real frames (a small copy of the same seconds): end on a cut, no flashing
        JSONObject low = pickFormat(player.optJSONObject("streamingData"), 240);
        double end = start + CLIP_SEC;
        if (low != null) {
            for (int attempt = 0; attempt < 2; attempt++) {
                double e = refine(low, start, canvas);
                if (e > 0) {
                    end = e;
                    break;
                }
                double[] next = windows == null ? null : ClipJudge.choose(windows, length, -1, java.util.Collections.singletonList(new double[]{start * 1000}));
                if (e == 0 || attempt == 1 || next == null) {
                    if (e < 0) {
                        noVideo[0] = true;
                        Log.i(TAG, "canvas video: " + video[0] + " flashes: not shown");
                        return null;
                    }
                    break;
                }
                Log.i(TAG, "canvas video: the part flashes, trying another");
                start = next[0] / 1000;
                end = start + CLIP_SEC; // the window moved: its end with it
            }
        }
        double from = cut(f, start, end, canvas, out); // the loop starts on the key frame at or before [start]
        try (java.io.FileWriter w = new java.io.FileWriter(new File(canvas, "mv_" + songId + ".crop"))) {
            w.write(String.format(Locale.ROOT, "%.3f %.3f %.3f %.3f %.3f", crop, bars[0], bars[1], bars[2], bars[3]));
        }
        HlsClip.trim(canvas, out);
        Log.i(TAG, String.format(Locale.ROOT, "canvas video: %d KB loop %.1f-%.1f s ready %d ms after the lookup",
                out.length() / 1024, from, end, System.currentTimeMillis() - t0));
        return out;
    }

    /** The crop saved with a video loop: {cropX, bars left, top, right, bottom}, or null. */
    static float[] cropOf(String clipPath) {
        if (clipPath == null || !clipPath.endsWith(".mp4")) return null;
        File f = new File(clipPath.substring(0, clipPath.length() - 4) + ".crop");
        if (!f.exists()) return null;
        try {
            String[] p = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim().split(" ");
            float[] out = new float[5];
            for (int i = 0; i < 5; i++) out[i] = Float.parseFloat(p[i]);
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /** The storyboard's preview frames as grids (Prism's Storyboard, largest level, sheets up to 2 MB). */
    static final class Board {
        List<int[]> frames = new ArrayList<>();
        long interval;
    }

    private static Board board(JSONObject player, long lengthMs) throws Exception {
        JSONObject sb = player.optJSONObject("storyboards");
        JSONObject r = sb == null ? null : sb.optJSONObject("playerStoryboardSpecRenderer");
        String spec = r == null ? null : r.optString("spec", null);
        if (spec == null) return null;
        String[] parts = spec.split("\\|");
        if (parts.length < 2) return null;
        String[] f = parts[parts.length - 1].split("#");
        if (f.length < 8) return null;
        int w = Integer.parseInt(f[0]), h = Integer.parseInt(f[1]), count = Integer.parseInt(f[2]), cols = Integer.parseInt(f[3]), rows = Integer.parseInt(f[4]);
        long interval = Long.parseLong(f[5]);
        if (interval <= 0) interval = lengthMs > 0 ? lengthMs / count : 0;
        if (w <= 0 || h <= 0 || count <= 0 || interval <= 0) return null;
        int level = parts.length - 2, per = cols * rows, sheets = (count + per - 1) / per;
        Board b = new Board();
        b.interval = interval;
        long bytes = 0;
        for (int n = 0; n < sheets && bytes < 2_000_000; n++) {
            String url = parts[0].replace("$L", String.valueOf(level)).replace("$N", f[6].replace("$M", String.valueOf(n)))
                    + "&sigh=" + f[7].replace("$", "%24");
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10_000);
            c.setReadTimeout(15_000);
            if (c.getResponseCode() >= 400) break;
            byte[] img = read(c.getInputStream());
            bytes += img.length;
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray(img, 0, img.length);
            if (bmp == null) break;
            int[] px = new int[bmp.getWidth() * bmp.getHeight()];
            bmp.getPixels(px, 0, bmp.getWidth(), 0, 0, bmp.getWidth(), bmp.getHeight());
            for (int i = 0; i < px.length; i++) px[i] = (((px[i] >> 16) & 255) * 3 + ((px[i] >> 8) & 255) * 6 + (px[i] & 255)) / 10;
            for (int i = 0; i < per && n * per + i < count; i++) {
                int x0 = (i % cols) * w, y0 = (i / cols) * h;
                if (x0 + w > bmp.getWidth() || y0 + h > bmp.getHeight()) break;
                b.frames.add(ClipJudge.grid(px, bmp.getWidth(), x0, y0, w, h));
            }
            bmp.recycle();
        }
        return b.frames.size() < 3 ? null : b;
    }

    /**
     * The loop's end (video seconds; on a cut 5 to 9 s in, else 8 s), from a small copy of the
     * same seconds read frame by frame; -1 when it flashes, 0 when the frames could not be read.
     */
    private static double refine(JSONObject low, double start, File dir) {
        File tmp = new File(dir, "judge.mp4");
        try {
            double key = cut(low, start, start + CLIP_SEC + 2, dir, tmp);
            List<int[]> fr = new ArrayList<>();
            List<Long> tl = new ArrayList<>();
            ClipFrames.read(tmp, fr, tl);
            if (fr.size() < 10) {
                Log.i(TAG, "canvas video: real frames: only " + fr.size() + " read");
                return 0;
            }
            long[] ts = new long[tl.size()];
            for (int i = 0; i < ts.length; i++) ts[i] = tl.get(i);
            long step = (ts[ts.length - 1] - ts[0]) / Math.max(1, ts.length - 1);
            long end = ClipJudge.end(fr, ts, ts[ts.length - 1] + step);
            Log.i(TAG, "canvas video: real frames: " + fr.size() + " read from " + key + " s, cuts at " + ClipJudge.cuts(fr, ts) + " ms, loop " + (end < 0 ? "flashes" : end + " ms"));
            return end < 0 ? -1 : key + end / 1000.0;
        } catch (Throwable t) {
            Log.w(TAG, "canvas video: real frames not read: " + t);
            return 0;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    private static final String MUSIC_SEARCH = "https://music.youtube.com/youtubei/v1/search?prettyPrint=false";
    private static final String VIDEOS = "EgWKAQIQAWoMEA4QChADEAQQCRAF";
    /** Never the song's video, whoever posted it (Prism's MusicVideoMatch). */
    private static final String[] NEVER = {"lyric", "lyrics", "letra", "letras", "paroles", "karaoke", "instrumental", "reaction",
            "tutorial", "lesson", "slowed", "sped up", "nightcore", "8d", "fan made", "remix", "official audio", "audio oficial",
            "audio only", "bass boosted", "reverb", "1 hour", "10 hours", "loop", "mashup", "parody", "ai cover"};
    /** Another take on the song: fine when the song is that take itself. */
    private static final String[] OTHER_TAKE = {"cover", "acoustic", "unplugged", "live", "en vivo", "ao vivo", "concert", "session"};

    /**
     * {videoId, title} of the song's music video, or null. Signed out, YouTube no longer pairs songs with their
     * videos in `next`, so it is found as Prism does: a video search, and the first result whose title is the
     * song's and that the artist posted (their channel, its VEVO or "Official" one), not a lyric video or another take.
     */
    static String[] findVideo(String title, String artist) throws Exception {
        String[] artists = artists(artist);
        if (title == null || title.trim().isEmpty() || artists.length == 0) return null;
        for (String q : new String[]{title + " " + artists[0], title + " " + artists[0] + " official video"}) {
            JSONObject body = new JSONObject().put("query", q).put("params", VIDEOS).put("context", new JSONObject().put("client",
                    new JSONObject().put("clientName", "WEB_REMIX").put("clientVersion", "1.20251101.01.00").put("hl", "en").put("gl", "US")));
            List<String[]> found = new ArrayList<>();
            results(post(MUSIC_SEARCH, body, "Mozilla/5.0", "https://music.youtube.com"), found);
            for (String[] v : found) if (matches(title, artists, v[1], v[2])) return new String[]{v[0], v[1]};
            StringBuilder seen = new StringBuilder();
            for (int i = 0; i < Math.min(5, found.size()); i++) seen.append("; '").append(found.get(i)[1]).append("' by ").append(found.get(i)[2]);
            Log.i(TAG, "canvas video: none for '" + q + "' among" + seen);
        }
        return null;
    }

    /** {videoId, title, channel} of each search result. */
    private static void results(Object node, List<String[]> out) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            JSONObject r = o.optJSONObject("musicResponsiveListItemRenderer");
            if (r != null) {
                JSONObject pid = r.optJSONObject("playlistItemData");
                JSONArray fc = r.optJSONArray("flexColumns");
                String id = pid == null ? "" : pid.optString("videoId");
                if (!id.isEmpty() && fc != null && fc.length() > 1) {
                    String t = runs(column(fc, 0));
                    JSONObject second = column(fc, 1);
                    JSONArray rr = second == null ? null : second.optJSONArray("runs");
                    String ch = rr == null || rr.length() == 0 ? "" : rr.optJSONObject(0).optString("text");
                    out.add(new String[]{id, t, ch});
                }
                return;
            }
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) results(o.opt(it.next()), out);
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) results(a.opt(i), out);
        }
    }

    private static JSONObject column(JSONArray fc, int i) {
        JSONObject c = fc.optJSONObject(i);
        JSONObject r = c == null ? null : c.optJSONObject("musicResponsiveListItemFlexColumnRenderer");
        return r == null ? null : r.optJSONObject("text");
    }

    /** The artists named in YT's artist line ("A & B", "A, B", "A x B", "A feat. B"). */
    static String[] artists(String artist) {
        List<String> out = new ArrayList<>();
        if (artist != null)
            for (String a : artist.split("(?i)\\s*(,|&|\\band\\b|\\bx\\b|\\bfeat\\.?|\\bft\\.?|\\bwith\\b)\\s*"))
                if (!norm(a).isEmpty()) out.add(a.trim());
        return out.toArray(new String[0]);
    }

    /** The video is the song's music video (Prism's rules, the artist's own upload only). */
    static boolean matches(String songTitle, String[] artists, String videoTitle, String channel) {
        String song = songTitle.toLowerCase(Locale.ROOT), vt = videoTitle.toLowerCase(Locale.ROOT);
        if (has(NEVER, vt, song) || has(OTHER_TAKE, vt, song)) return false;
        List<String> names = new ArrayList<>();
        for (String a : artists) names.add(norm(a));
        String ch = officialChannel(norm(channel));
        if (ch.isEmpty() || !names.contains(ch)) return false;
        names.add(norm(channel));
        names.sort((x, y) -> y.length() - x.length());
        String want = core(songTitle, new ArrayList<>());
        return !want.isEmpty() && want.equals(core(videoTitle, names));
    }

    private static boolean has(String[] words, String text, String song) {
        for (String w : words) {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("(?<![\\p{L}\\p{N}])" + java.util.regex.Pattern.quote(w) + "(?![\\p{L}\\p{N}])");
            if (p.matcher(text).find() && !p.matcher(song).find()) return true;
        }
        return false;
    }

    /** The title without brackets, "| ..." tails, feat. credits, video words and the artists' names. */
    private static String core(String title, List<String> names) {
        String t = title.toLowerCase(Locale.ROOT).replaceAll("\\(.*?\\)|\\[.*?]|\u3010.*?\u3011", " ");
        int bar = t.indexOf(" | ");
        if (bar >= 0) t = t.substring(0, bar);
        t = t.replaceAll("\\s(ft\\.?|feat\\.?|featuring)\\s.*$", " ")
                .replaceAll("(?<![\\p{L}])(official|music|video|videoclip|clip|hd|4k|hq|remastered|remaster|visualizer|mv|m/v)(?![\\p{L}])", " ");
        t = norm(t);
        for (String n : names) if (!t.equals(n) && !n.isEmpty()) t = t.replace(n, "");
        return t;
    }

    static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("\\(.*?\\)|\\[.*?]", "").replaceAll("[^\\p{L}\\p{N}]", "");
    }

    /** "NirvanaVEVO", "Official Billie Eilish" -> the artist's own name, normalized. */
    static String officialChannel(String n) {
        String name = n;
        for (String s : new String[]{"vevo", "official", "oficial", "officiel", "offiziell", "music", "tv", "channel"})
            if (n.endsWith(s)) n = n.substring(0, n.length() - s.length());
        if (n.startsWith("official")) n = n.substring(8);
        return n.isEmpty() ? name : n;
    }

    private static String runs(JSONObject text) {
        if (text == null) return "";
        JSONArray r = text.optJSONArray("runs");
        if (r == null) return text.optString("simpleText", "");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < r.length(); i++) b.append(r.optJSONObject(i).optString("text"));
        return b.toString();
    }

    /** The video-only H.264 DASH stream nearest below [want] px tall, at most 30 fps. */
    static JSONObject pickFormat(JSONObject streaming, int want) {
        JSONArray a = streaming == null ? null : streaming.optJSONArray("adaptiveFormats");
        if (a == null) return null;
        JSONObject best = null;
        for (int i = 0; i < a.length(); i++) {
            JSONObject f = a.optJSONObject(i);
            if (f == null || !f.optString("mimeType").startsWith("video/mp4; codecs=\"avc1") || f.optString("url").isEmpty()
                    || f.optJSONObject("initRange") == null || f.optJSONObject("indexRange") == null || f.optInt("fps", 30) > 30) continue;
            int h = f.optInt("height");
            if (best == null) best = f;
            else {
                int bh = best.optInt("height");
                boolean better = h <= want ? (bh > want || h > bh) : (bh > want && h < bh);
                if (better) best = f;
            }
        }
        return best;
    }

    /** YouTube's "Most replayed" curve (100 points over the video, 0..1), or null when it has none. */
    private static final java.util.Map<String, double[]> heats = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<String, double[]>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, double[]> e) {
            return size() > 20;
        }
    });

    static double[] heatmap(String videoId) {
        if (heats.containsKey(videoId)) return heats.get(videoId);
        try {
            double[] h = fetchHeatmap(videoId);
            heats.put(videoId, h); // an answer without a curve is remembered; a failed fetch is not
            return h;
        } catch (Throwable t) {
            Log.w(TAG, "canvas video: no most-replayed curve: " + t);
            return null;
        }
    }

    private static double[] fetchHeatmap(String videoId) throws Exception {
        JSONObject body = new JSONObject().put("videoId", videoId).put("context", new JSONObject().put("client",
                new JSONObject().put("clientName", "WEB").put("clientVersion", "2.20251101.01.00").put("hl", "en").put("gl", "US")));
        return heatmapOf(post(WEB_NEXT, body, "Mozilla/5.0", "https://www.youtube.com"));
    }

    static double[] heatmapOf(JSONObject next) {
        JSONObject fw = next.optJSONObject("frameworkUpdates");
        JSONObject eb = fw == null ? null : fw.optJSONObject("entityBatchUpdate");
        JSONArray muts = eb == null ? null : eb.optJSONArray("mutations");
        if (muts == null) return null;
        for (int i = 0; i < muts.length(); i++) {
            JSONObject p = muts.optJSONObject(i).optJSONObject("payload");
            JSONObject m = p == null ? null : p.optJSONObject("macroMarkersListEntity");
            JSONObject list = m == null ? null : m.optJSONObject("markersList");
            if (list == null || !"MARKER_TYPE_HEATMAP".equals(list.optString("markerType"))) continue;
            JSONArray mk = list.optJSONArray("markers");
            if (mk == null || mk.length() < 10) continue;
            // {start s, duration s, intensity} per marker, flattened
            double[] out = new double[mk.length() * 3];
            for (int k = 0; k < mk.length(); k++) {
                JSONObject o = mk.optJSONObject(k);
                out[k * 3] = o.optDouble("startMillis", 0) / 1000;
                out[k * 3 + 1] = o.optDouble("durationMillis", 0) / 1000;
                out[k * 3 + 2] = o.optDouble("intensityScoreNormalized", 0);
            }
            return out;
        }
        return null;
    }

    /**
     * Where the loop starts: the "Most replayed" peak (the largest rise of a ~9 s smoothed curve over a
     * ±45 s running median, pulled back to where the rise starts; not in the first 12 s), as Prism's
     * BestPart; without a curve, 40% into the video.
     */
    static double partStart(double[] heat, double dur) {
        double fallback = Math.max(0, dur * 0.4);
        if (heat == null || heat.length < 30 || dur < CLIP_SEC + 2) return Math.min(fallback, Math.max(0, dur - CLIP_SEC));
        int n = heat.length / 3;
        double step = Math.max(0.5, heat[1]);
        double[] v = new double[n], s = new double[n];
        for (int i = 0; i < n; i++) v[i] = heat[i * 3 + 2];
        int half = Math.max(1, (int) Math.round(4.5 / step));
        for (int i = 0; i < n; i++) {
            double sum = 0;
            int c = 0;
            for (int j = Math.max(0, i - half); j <= Math.min(n - 1, i + half); j++, c++) sum += v[j];
            s[i] = sum / c;
        }
        int wide = Math.max(2, (int) Math.round(45 / step));
        int best = -1;
        double bestRise = 0;
        for (int i = 0; i < n; i++) {
            if (heat[i * 3] < 12 || heat[i * 3] > dur - CLIP_SEC) continue;
            double[] w = Arrays.copyOfRange(s, Math.max(0, i - wide), Math.min(n, i + wide + 1));
            Arrays.sort(w);
            double rise = s[i] - w[w.length / 2];
            if (rise > bestRise) {
                bestRise = rise;
                best = i;
            }
        }
        if (best < 0) return Math.min(fallback, Math.max(0, dur - CLIP_SEC));
        int i = best;
        while (i > 0 && s[i - 1] < s[i] && heat[(i - 1) * 3] >= 12) i--;
        return Math.min(heat[i * 3], Math.max(0, dur - CLIP_SEC));
    }

    /**
     * Fetches the fragments holding [start, end] (video seconds) and writes them as one MP4 starting
     * at 0; returns the video time (s) of its first frame, the key frame at or before [start].
     */
    private static double cut(JSONObject f, double start, double end, File dir, File out) throws Exception {
        double len = end - start;
        String url = f.getString("url");
        JSONObject ir = f.getJSONObject("initRange"), xr = f.getJSONObject("indexRange");
        long initEnd = Long.parseLong(xr.getString("end"));
        byte[] head = get(url, 0, initEnd);
        long indexStart = Long.parseLong(xr.getString("start"));
        Sidx sidx = Sidx.parse(head, (int) indexStart);
        if (sidx == null) throw new Exception("no sidx");
        long[] range = sidx.range(start, len, initEnd + 1);
        byte[] body = get(url, range[0], range[1]);
        File raw = new File(dir, out.getName() + ".part");
        try (FileOutputStream o = new FileOutputStream(raw)) {
            o.write(head, 0, (int) Long.parseLong(ir.getString("end")) + 1);
            o.write(body);
        }
        try {
            double base = range[2] / 1e6;
            return base + remux(raw, out, start - base, end - base) / 1e6;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            raw.delete();
        }
    }

    /** One segment index box: where each fragment's bytes and time are. */
    static final class Sidx {
        long timescale, earliest;
        long[] sizes, durations;

        static Sidx parse(byte[] b, int at) {
            ByteBuffer bb = ByteBuffer.wrap(b);
            if (at + 8 > b.length || bb.getInt(at + 4) != 0x73696478) return null; // 'sidx'
            int p = at + 8;
            int version = b[p] & 0xFF;
            p += 4 + 4; // version/flags, reference id
            Sidx s = new Sidx();
            s.timescale = bb.getInt(p) & 0xFFFFFFFFL;
            p += 4;
            long firstOffset;
            if (version == 0) {
                s.earliest = bb.getInt(p) & 0xFFFFFFFFL;
                firstOffset = bb.getInt(p + 4) & 0xFFFFFFFFL;
                p += 8;
            } else {
                s.earliest = bb.getLong(p);
                firstOffset = bb.getLong(p + 8);
                p += 16;
            }
            if (firstOffset != 0) return null;
            p += 2;
            int count = bb.getShort(p) & 0xFFFF;
            p += 2;
            s.sizes = new long[count];
            s.durations = new long[count];
            for (int i = 0; i < count; i++) {
                s.sizes[i] = bb.getInt(p) & 0x7FFFFFFFL;
                s.durations[i] = bb.getInt(p + 4) & 0xFFFFFFFFL;
                p += 12;
            }
            return s;
        }

        /**
         * Byte range {from, to} (inclusive) of the fragments covering [start, start + len], fragments
         * from [base], and the first one's video time in microseconds.
         */
        long[] range(double start, double len, long base) {
            long off = base, t = earliest;
            long from = -1, to = -1, fromTime = earliest;
            for (int i = 0; i < sizes.length; i++) {
                double a = t / (double) timescale, z = (t + durations[i]) / (double) timescale;
                if (z > start && a < start + len) {
                    if (from < 0) {
                        from = off;
                        fromTime = t;
                    }
                    to = off + sizes[i] - 1;
                }
                off += sizes[i];
                t += durations[i];
            }
            if (from < 0) {
                from = base;
                to = base + sizes[0] - 1;
            }
            return new long[]{from, to, Math.round(fromTime * 1e6 / timescale)};
        }
    }

    /**
     * A plain MP4 of the video track from the key frame at or before [start] to [end] (seconds in
     * [in]), times from 0; returns the first frame's time in [in] (microseconds).
     */
    private static long remux(File in, File out, double start, double end) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        MediaMuxer mux = null;
        try {
            ex.setDataSource(in.getAbsolutePath());
            int track = -1;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME).startsWith("video/")) {
                    track = i;
                    break;
                }
            }
            if (track < 0) throw new Exception("no video track");
            ex.selectTrack(track);
            MediaFormat fmt = ex.getTrackFormat(track);
            mux = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int t = mux.addTrack(fmt);
            mux.start();
            ByteBuffer buf = ByteBuffer.allocate(fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) ? Math.max(1 << 20, fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)) : 4 << 20);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            // start at the last key frame at or before the part (the fragments begin on one)
            long first = -1;
            ex.seekTo((long) (start * 1e6), MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            int written = 0;
            while (true) {
                int size = ex.readSampleData(buf, 0);
                if (size < 0) break;
                long ts = ex.getSampleTime();
                boolean key = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0;
                if (first < 0) {
                    if (!key) {
                        ex.advance();
                        continue;
                    }
                    first = ts;
                }
                if (ts >= end * 1e6) break;
                info.set(0, size, ts - first, key ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                mux.writeSampleData(t, buf, info);
                written++;
                ex.advance();
            }
            if (written == 0) throw new Exception("no frames in the part");
            return first;
        } finally {
            ex.release();
            if (mux != null) {
                try {
                    mux.stop();
                } catch (Exception ignored) {
                }
                mux.release();
            }
        }
    }

    private static byte[] get(String url, long from, long to) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url + "&range=" + from + "-" + to).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(20_000);
        c.setRequestProperty("User-Agent", userAgent());
        if (c.getResponseCode() >= 400) throw new Exception("HTTP " + c.getResponseCode() + " for bytes " + from + "-" + to);
        return read(c.getInputStream());
    }

    private static String userAgent() {
        return "com.google.visionos.youtube/" + VISION_VERSION + "(RealityDevice14,1; U; CPU visionOS 25_6_0 like Mac OS X; US)";
    }

    /** The video's player answer as YouTube's visionOS app (stream URLs need no signature solving), as Prism asks it. */
    private static JSONObject visionPlayer(String videoId) throws Exception {
        JSONObject body = new JSONObject().put("videoId", videoId).put("contentCheckOk", true).put("racyCheckOk", true);
        JSONObject r = visionPost("player", body, visitor());
        JSONObject ps = r.optJSONObject("playabilityStatus");
        if (ps != null && "LOGIN_REQUIRED".equals(ps.optString("status")) && ps.optString("reason").toLowerCase(Locale.ROOT).contains("bot")) {
            visitor = null;
            r = visionPost("player", body, visitor());
        }
        return r;
    }

    private static String visitor() throws Exception {
        if (visitor == null) {
            JSONObject r = visionPost("visitor_id", new JSONObject(), null);
            JSONObject rc = r.optJSONObject("responseContext");
            visitor = rc == null ? null : rc.optString("visitorData", null);
            if (visitor == null) throw new Exception("no visitor id");
        }
        return visitor;
    }

    private static JSONObject visionPost(String endpoint, JSONObject body, String visitor) throws Exception {
        JSONObject client = new JSONObject().put("clientName", "VISIONOS").put("clientVersion", VISION_VERSION)
                .put("clientScreen", "WATCH").put("platform", "MOBILE").put("deviceMake", "Apple").put("deviceModel", "RealityDevice14,1")
                .put("osName", "visionOS").put("osVersion", "25.6.0.23O471").put("hl", "en").put("gl", "US").put("utcOffsetMinutes", 0);
        if (visitor != null) client.put("visitorData", visitor);
        body.put("context", new JSONObject().put("client", client));
        HttpURLConnection c = open(VISION + endpoint + "?prettyPrint=false", userAgent());
        c.setRequestProperty("X-Goog-Api-Format-Version", "2");
        c.setRequestProperty("X-YouTube-Client-Name", "101");
        c.setRequestProperty("X-YouTube-Client-Version", VISION_VERSION);
        if (visitor != null) c.setRequestProperty("X-Goog-Visitor-Id", visitor);
        return send(c, body);
    }

    private static JSONObject post(String url, JSONObject body, String ua, String origin) throws Exception {
        HttpURLConnection c = open(url, ua);
        c.setRequestProperty("Origin", origin);
        return send(c, body);
    }

    private static HttpURLConnection open(String url, String ua) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(15_000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("User-Agent", ua);
        return c;
    }

    private static JSONObject send(HttpURLConnection c, JSONObject body) throws Exception {
        try (OutputStream out = c.getOutputStream()) {
            out.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        byte[] b = read(code < 400 ? c.getInputStream() : c.getErrorStream());
        if (code >= 400) throw new Exception("HTTP " + code);
        return new JSONObject(new String(b, StandardCharsets.UTF_8));
    }

    private static byte[] read(InputStream in) throws Exception {
        try (InputStream i = in) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[32768];
            int n;
            while ((n = i.read(b)) > 0) buf.write(b, 0, n);
            return buf.toByteArray();
        }
    }
}
