package app.ayham.extension.music.spike;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Throwaway spike (rough edge of item 8): finds the song version of a music video automatically,
 * and its length. Same matching as Morphe's audio/video switch (oEmbed title, YT Music search,
 * best ATV title match), but only in the video -> song direction.
 */
final class SongVersion {
    private static final String TAG = "AyhamSpike";
    private static final String OEMBED_URL = "https://www.youtube.com/oembed?format=json&url="
            + "https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3D";
    private static final String SEARCH_URL =
            "https://music.youtube.com/youtubei/v1/search?key=AIzaSyAOghZGza2MQSZkY_zfZ370N-PUdXEo8AI";
    private static final String NEXT_URL = "https://music.youtube.com/youtubei/v1/next?prettyPrint=false";
    private static final String SONG = "MUSIC_VIDEO_TYPE_ATV";
    private static final String VIDEO = "MUSIC_VIDEO_TYPE_OMV";

    /** videoId -> songId, or "" when the video has no song version. */
    private static final Map<String, String> songs = Collections.synchronizedMap(new HashMap<>());
    /** songId -> length in seconds. */
    /** Each looked-up video's type (MUSIC_VIDEO_TYPE_ATV song, _OMV music video, ...). */
    private static final Map<String, String> types = Collections.synchronizedMap(new HashMap<>());
    private static final Map<String, Long> lengths = Collections.synchronizedMap(new HashMap<>());

    private SongVersion() {
    }

    /** Lookups in flight, so the resolver waits for a look-ahead instead of asking twice. */
    private static final Map<String, java.util.concurrent.FutureTask<String>> pending = new java.util.concurrent.ConcurrentHashMap<>();

    /** Look the video up in the background (the queue's next song). */
    static void prefetch(String videoId) {
        if (songs.containsKey(videoId) || pending.containsKey(videoId)) return;
        Log.i(TAG, "song version: looking ahead at " + videoId);
        new Thread(() -> songFor(videoId), "ayham-songversion").start();
    }

    /** Off the main thread. @return the song id, or null to keep the video. */
    static String songFor(String videoId) {
        String cached = songs.get(videoId);
        if (cached != null) return cached.isEmpty() ? null : cached;
        java.util.concurrent.FutureTask<String> task = new java.util.concurrent.FutureTask<>(() -> find(videoId));
        java.util.concurrent.FutureTask<String> running = pending.putIfAbsent(videoId, task);
        if (running == null) {
            running = task;
            task.run();
            pending.remove(videoId);
        }
        try {
            return running.get();
        } catch (Exception e) {
            return null;
        }
    }

    private static String find(String videoId) {
        String song = null;
        try {
            long start = System.currentTimeMillis();
            song = lookup(videoId);
            if (song != null) lengths.put(song, lengthSeconds(song));
            Log.i(TAG, "song version of " + videoId + " -> " + song + " length=" + lengths.get(song)
                    + "s in " + (System.currentTimeMillis() - start) + " ms");
        } catch (Exception e) {
            Log.w(TAG, "song version lookup failed for " + videoId, e);
        }
        songs.put(videoId, song == null ? "" : song);
        return song;
    }

    /** The type learned elsewhere (a download's player data). */
    static void noteType(String videoId, String type) {
        if (type != null && !type.isEmpty()) types.put(videoId, type);
    }

    /** A music video that plays as itself (no song version swapped in): its audio has the video's intro/outro. */
    static boolean musicVideo(String videoId) {
        return VIDEO.equals(types.get(videoId)) && !swapped(videoId);
    }

    /** True when the video plays as its song version. */
    static boolean swapped(String videoId) {
        String song = songs.get(videoId);
        return song != null && !song.isEmpty();
    }

    /** @return the length of the song served for the video, or 0. */
    static long lengthFor(String videoId) {
        String song = songs.get(videoId);
        if (song == null || song.isEmpty()) return 0;
        Long length = lengths.get(song);
        return length == null ? 0 : length;
    }

    private static String lookup(String videoId) throws Exception {
        JSONObject meta = request(OEMBED_URL + videoId, null);
        String title = meta.optString("title", "");
        String author = meta.optString("author_name", "");
        if (title.trim().isEmpty()) return null;

        Map<String, String[]> items = new LinkedHashMap<>();
        collect(request(SEARCH_URL, searchBody(query(title, author))), "", items);

        String[] current = items.get(videoId);
        String type = current == null ? "" : current[0];
        // a search that doesn't list the video says nothing: it keeps a type learnt elsewhere (a
        // download's player data), which the music-video rule reads
        if (!type.isEmpty()) types.put(videoId, type);
        if (!VIDEO.equals(type)) {
            Log.i(TAG, "not swapping " + videoId + ": type " + (type.isEmpty() ? "unknown" : type));
            return null;
        }
        String best = null;
        double bestScore = 0.5; // Below half the words in common, keep the video.
        for (Map.Entry<String, String[]> e : items.entrySet()) {
            if (!SONG.equals(e.getValue()[0]) || e.getKey().equals(videoId)) continue;
            double s = score(title, e.getValue()[1]);
            if (s > bestScore) {
                bestScore = s;
                best = e.getKey();
            }
        }
        return best;
    }

    private static long lengthSeconds(String songId) throws Exception {
        JSONObject body = new JSONObject()
                .put("videoId", songId)
                .put("context", new JSONObject().put("client", new JSONObject()
                        .put("clientName", "WEB_REMIX").put("clientVersion", "1.20251101.01.00")
                        .put("hl", "en").put("gl", "US")));
        String json = request(NEXT_URL, body).toString();
        int at = json.indexOf("\"lengthText\"");
        if (at < 0) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"(\\d+):(\\d\\d)\"")
                .matcher(json.substring(at, Math.min(json.length(), at + 400)));
        return m.find() ? Long.parseLong(m.group(1)) * 60 + Long.parseLong(m.group(2)) : 0;
    }

    private static String query(String title, String author) {
        String q = strip(title).trim();
        String channel = strip(author).replaceFirst("(?i)\\s*-\\s*Topic\\s*$", "")
                .replaceFirst("(?i)\\s*VEVO\\s*$", "").trim();
        if (!channel.isEmpty() && !channel.equalsIgnoreCase(q)) q = q + " " + channel;
        return q.replaceAll("\\s+", " ").trim();
    }

    private static JSONObject searchBody(String query) throws Exception {
        return new JSONObject().put("query", query).put("context", new JSONObject()
                .put("client", new JSONObject().put("clientName", "ANDROID_MUSIC")
                        .put("clientVersion", "7.27.52").put("androidSdkVersion", 34)
                        .put("hl", "en").put("gl", "US")));
    }

    private static String strip(String s) {
        return s == null ? "" : s.replaceAll("\\s*[(\\[].*?[)\\]]", " ");
    }

    private static String normalize(String s) {
        // letters of any script (Morphe's a-z0-9 leaves an Arabic or Japanese title empty, so its
        // song version could never be found)
        return strip(s).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{M}\\p{N} ]+", " ")
                .replaceAll("\\s+", " ").trim();
    }

    private static double score(String expected, String actual) {
        String a = normalize(expected);
        String b = normalize(actual);
        if (a.isEmpty() || b.isEmpty()) return 0;
        if (a.contains(b) || b.contains(a)) return 1;
        Set<String> x = new LinkedHashSet<>(Arrays.asList(a.split(" ")));
        Set<String> y = new LinkedHashSet<>(Arrays.asList(b.split(" ")));
        int common = 0;
        for (String t : x) if (y.contains(t)) common++;
        return (double) common / Math.min(x.size(), y.size());
    }

    private static void collect(Object node, String title, Map<String, String[]> out) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            for (String k : new String[]{"title", "flexColumns", "primaryText", "secondaryText"}) {
                if (!o.has(k)) continue;
                String t = flatten(o.opt(k), 0);
                if (!t.isEmpty()) {
                    title = t;
                    break;
                }
            }
            Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                Object v = o.opt(k);
                if ("watchEndpoint".equals(k) && v instanceof JSONObject) {
                    JSONObject ep = (JSONObject) v;
                    String id = ep.optString("videoId", "");
                    JSONObject cfg = ep.optJSONObject("watchEndpointMusicSupportedConfigs");
                    JSONObject music = cfg == null ? null : cfg.optJSONObject("watchEndpointMusicConfig");
                    String type = music == null ? "" : music.optString("musicVideoType", "");
                    if (id.length() != 11) continue;
                    String[] item = out.get(id);
                    if (item == null) out.put(id, new String[]{type, title});
                    else if (item[0].isEmpty()) item[0] = type;
                } else {
                    collect(v, title, out);
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) collect(a.opt(i), title, out);
        }
    }

    private static String flatten(Object v, int depth) {
        if (depth > 8) return "";
        if (v instanceof String) return (String) v;
        if (v instanceof JSONObject) {
            JSONObject o = (JSONObject) v;
            if (o.has("simpleText")) return o.optString("simpleText", "");
            for (String k : new String[]{"runs", "flexColumns", "text"}) {
                if (!o.has(k)) continue;
                String t = flatten(o.opt(k), depth + 1);
                if (!t.isEmpty()) return t;
            }
            return "";
        }
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < a.length(); i++) {
                String t = flatten(a.opt(i), depth + 1);
                if (t.isEmpty()) continue;
                if (b.length() > 0) b.append(' ');
                b.append(t);
            }
            return b.toString();
        }
        return "";
    }

    private static JSONObject request(String url, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(15_000);
        if (body == null) {
            c.setRequestProperty("User-Agent", "Mozilla/5.0");
        } else {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("User-Agent", url.startsWith(SEARCH_URL)
                    ? "com.google.android.apps.youtube.music/7.27.52 (Linux; U; Android 14)" : "Mozilla/5.0");
            if (url.startsWith(NEXT_URL)) c.setRequestProperty("Origin", "https://music.youtube.com");
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
        try (InputStream in = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            return new JSONObject(buf.toString("UTF-8"));
        }
    }
}
