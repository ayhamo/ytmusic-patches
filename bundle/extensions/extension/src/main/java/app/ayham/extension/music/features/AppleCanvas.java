package app.ayham.extension.music.features;

import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Animated covers' lookup: BitChord's AppleMusicCanvas ported to plain
 * Java. Scrapes the web player's anonymous token, searches the catalog, scores the hits the same
 * way (artist gate, title, album, edition words, compilations dropped) and returns the square
 * motion clip (HLS) of the song's album, or null.
 */
final class AppleCanvas {
    private static final String TAG = "Ayham";
    private static final String AMP = "https://amp-api.music.apple.com/v1/catalog/";
    private static final String UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36";
    private static final int MIN_SCORE = 12;
    private static final String[] EDITION = {"deluxe", "expanded", "remastered", "remix", "version", "edit", "mix", "bonus"};
    private static final String[] COMPILATION = {"playlist", "set list", "essentials", "dj mix", "mixed", "apple music", "today's hits", "session"};

    private static String token;
    private static long tokenExpires;
    private static long retryAfter;

    private AppleCanvas() {
    }

    /**
     * Off the main thread. The clip to show and the other shape's ({preferred, alternate or null}):
     * the tall 3:4 one first when [tall] (full-screen art), else the square one; null when none.
     * Prism's rules: every credited artist must match, compilation albums are skipped for the next
     * hit, and without a token or a hit the public album page is read instead.
     */
    static String[] find(String title, String artist, boolean tall) throws Exception {
        String bearer = token();
        if (bearer == null) {
            String[] page = applePage(artist, title, tall);
            if (page != null) return page;
            throw new IllegalStateException("no Apple token"); // an error, not a miss: not saved
        }
        Log.i(TAG, "canvas: token ok (" + bearer.length() + " chars)");
        String store = Locale.getDefault().getCountry().length() == 2
                ? Locale.getDefault().getCountry().toLowerCase(Locale.ROOT) : "us";
        String term = (title.toLowerCase(Locale.ROOT).contains(artist.toLowerCase(Locale.ROOT)) ? "" : artist + " ") + title;
        JSONObject root = new JSONObject(get(AMP + store + "/search?types=songs&limit=10&extend=editorialVideo&include=albums&term="
                + URLEncoder.encode(term, "UTF-8"), bearer));
        JSONObject songs = root.optJSONObject("results") == null ? null : root.optJSONObject("results").optJSONObject("songs");
        JSONArray hits = songs == null ? null : songs.optJSONArray("data");
        if (hits == null) {
            Log.i(TAG, "canvas: no Apple songs for '" + term + "' in store " + store);
            return applePage(artist, title, tall);
        }
        Log.i(TAG, "canvas: " + hits.length() + " Apple hits for '" + term + "' in store " + store);

        // every hit that passes, best first
        java.util.List<JSONObject> passing = new java.util.ArrayList<>();
        java.util.List<Integer> scores = new java.util.ArrayList<>();
        for (int i = 0; i < hits.length(); i++) {
            int sc = score(hits.getJSONObject(i).getJSONObject("attributes"), title, artist);
            if (sc < MIN_SCORE) continue;
            int at = 0;
            while (at < scores.size() && scores.get(at) >= sc) at++;
            passing.add(at, hits.getJSONObject(i));
            scores.add(at, sc);
        }
        if (passing.isEmpty()) Log.i(TAG, "canvas: no Apple hit scored " + MIN_SCORE + "+ for '" + title + "'");
        int albumsRead = 0;
        for (int k = 0; k < passing.size() && albumsRead < 3; k++) {
            JSONObject best = passing.get(k);
            JSONObject attrs = best.getJSONObject("attributes");
            String[] urls = motion(attrs.optJSONObject("editorialVideo"), tall);
            if (urls != null) return urls;
            String albumId = null;
            JSONObject rel = best.optJSONObject("relationships");
            if (rel != null && rel.optJSONObject("albums") != null) {
                JSONArray d = rel.getJSONObject("albums").optJSONArray("data");
                if (d != null && d.length() > 0) albumId = d.getJSONObject(0).optString("id", null);
            }
            if (albumId == null) {
                Matcher m = Pattern.compile("/album/[^?]*/(\\d+)").matcher(attrs.optString("url"));
                if (m.find()) albumId = m.group(1);
            }
            Log.i(TAG, "canvas: hit '" + attrs.optString("name") + "' by " + attrs.optString("artistName")
                    + " (score " + scores.get(k) + "), album " + albumId + " / " + attrs.optString("albumName"));
            if (albumId == null || albumId.startsWith("pl.")) continue;
            albumsRead++;
            JSONObject album = new JSONObject(get(AMP + store + "/albums/" + albumId + "?extend=editorialVideo", bearer))
                    .getJSONArray("data").getJSONObject(0).getJSONObject("attributes");
            if (compilation(album.optString("name"))) {
                Log.i(TAG, "canvas: album '" + album.optString("name") + "' is a compilation, next hit");
                continue;
            }
            String[] clip = motion(album.optJSONObject("editorialVideo"), tall);
            Log.i(TAG, "canvas: album '" + album.optString("name") + "' " + (clip == null ? "has no motion art" : "has motion art"));
            if (clip != null) return clip;
        }
        return passing.isEmpty() ? applePage(artist, title, tall) : null;
    }

    /**
     * Prism's applePage: the iTunes search for the album (named [album], often the single's title)
     * by every credited artist, and the motion clips in its public page's markup.
     */
    private static String[] applePage(String artist, String album, boolean tall) {
        try {
            JSONArray results = new JSONObject(get("https://itunes.apple.com/search?media=music&entity=album&limit=5&term="
                    + URLEncoder.encode(artist + " " + album, "UTF-8"), null)).optJSONArray("results");
            if (results == null) return null;
            String want = norm(album.replaceAll("\\s*[(\\[].*?[)\\]]", ""));
            if (want.isEmpty()) return null;
            for (int i = 0; i < results.length(); i++) {
                JSONObject r = results.getJSONObject(i);
                String name = norm(r.optString("collectionName").replaceAll("\\s*[(\\[].*?[)\\]]", ""));
                if (compilation(r.optString("collectionName")) || !(name.equals(want) || name.startsWith(want + " "))) continue;
                boolean all = true;
                for (String w : artists(artist)) {
                    boolean found = false;
                    for (String g : artists(r.optString("artistName"))) found |= g.equals(w);
                    all &= found;
                }
                if (!all) continue;
                String page = r.optString("collectionViewUrl").replaceAll("\\?.*", "");
                if (page.isEmpty()) return null;
                String html = get(page, null).replace("\\u002F", "/").replace("\\/", "/");
                String square = grab(html, "motionDetailSquare"), tallUrl = grab(html, "motionDetailTall");
                if (square == null) square = grab(html, "motionSquareVideo1x1");
                if (tallUrl == null) tallUrl = grab(html, "motionTallVideo3x4");
                Log.i(TAG, "canvas: album page '" + r.optString("collectionName") + "': " + (square != null || tallUrl != null ? "motion art" : "none"));
                return pair(square, tallUrl, tall);
            }
        } catch (Throwable t) {
            Log.w(TAG, "canvas: album page failed: " + t);
        }
        return null;
    }

    private static String grab(String html, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\\{[^}]*?\"video\"\\s*:\\s*\"(https://[^\"]+?\\.m3u8)\"").matcher(html);
        return m.find() ? m.group(1) : null;
    }

    private static String[] pair(String square, String tallUrl, boolean tall) {
        String first = tall ? (tallUrl != null ? tallUrl : square) : (square != null ? square : tallUrl);
        if (first == null) return null;
        String other = first.equals(square) ? tallUrl : square;
        return new String[]{first, other};
    }

    private static int score(JSONObject a, String title, String artist) {
        String name = a.optString("name");
        String hitArtist = a.optString("artistName");
        String album = a.optString("albumName");
        if (compilation(name) || compilation(album)) return -100;
        for (String want : artists(artist)) {
            boolean found = false;
            for (String got : artists(hitArtist)) found |= got.equals(want);
            if (!found) return -100;
        }
        int s = 10;
        String w = norm(title.replaceAll("\\s*[(\\[].*?[)\\]]", ""));
        String h = norm(name.replaceAll("\\s*[(\\[].*?[)\\]]", ""));
        if (w.isEmpty()) return -100;
        s += h.equals(w) ? 15 : (h.contains(w) || w.contains(h)) ? 7 : -10;
        for (String word : EDITION) {
            boolean inW = title.toLowerCase(Locale.ROOT).contains(word);
            boolean inH = name.toLowerCase(Locale.ROOT).contains(word);
            if (inW && inH) s += 5;
            else if (inH) s -= 3;
        }
        return s;
    }

    private static String[] artists(String s) {
        return norm(s.replaceAll("(?i)\\s*(,|&| and | x | feat\\.?| ft\\.?| with )\\s*", "|")).split("\\|");
    }

    private static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}| ]+", " ").replaceAll("\\s+", " ").trim();
    }

    private static boolean compilation(String name) {
        String l = name.toLowerCase(Locale.ROOT);
        for (String c : COMPILATION) if (l.contains(c)) return true;
        return false;
    }

    private static String[] motion(JSONObject video, boolean tall) {
        if (video == null) return null;
        String square = link(video, "motionDetailSquare", "motionSquareVideo1x1", "motionDetailRaw");
        String tallUrl = link(video, "motionDetailTall", "motionTallVideo3x4");
        return pair(square, tallUrl, tall);
    }

    private static String link(JSONObject video, String... keys) {
        for (String key : keys) {
            JSONObject asset = video.optJSONObject(key);
            if (asset == null) continue;
            for (String f : new String[]{"video", "videoUrl", "hlsUrl", "url"}) {
                String u = asset.optString(f, "");
                if (!u.isEmpty()) return u;
            }
        }
        return null;
    }

    private static synchronized String token() throws Exception {
        long now = System.currentTimeMillis();
        if (token != null && now < tokenExpires - 60_000) return token;
        if (token == null) {
            // the backup copy from an earlier run, until it expires or gets a 401
            String[] saved = CanvasCache.token();
            long savedExpires;
            try {
                savedExpires = saved == null ? 0 : Long.parseLong(saved[1]);
            } catch (NumberFormatException e) {
                savedExpires = 0; // a damaged copy: scraped afresh instead of failing every lookup
            }
            if (saved != null && now < savedExpires - 60_000) {
                token = saved[0];
                tokenExpires = savedExpires;
                Log.i(TAG, "canvas: token from the backup copy");
                return token;
            }
        }
        if (now < retryAfter) return null;
        retryAfter = now + 2 * 60_000L; // the network failed: tried again soon
        String html = get("https://music.apple.com/us/browse", null);
        retryAfter = now + 30 * 60_000L; // Apple answered: a page with no token is not asked again for a while
        Matcher js = Pattern.compile("/assets/index(?:-legacy)?[~-][A-Za-z0-9_-]+\\.js").matcher(html);
        while (js.find()) {
            String script = get("https://music.apple.com" + js.group(), null);
            Matcher jwt = Pattern.compile("ey[A-Za-z0-9_-]+\\.ey[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+").matcher(script);
            // The web player's own token names itself (kid WebPlayKid / iss AMPWebPlay); any other
            // unexpired one is kept as a fallback, so a relabel on Apple's side is not fatal.
            String fallback = null;
            long fallbackExpires = 0;
            while (jwt.find()) {
                try {
                    String[] parts = jwt.group().split("\\.");
                    String header = new String(Base64.decode(parts[0], Base64.URL_SAFE), StandardCharsets.UTF_8);
                    String payload = new String(Base64.decode(parts[1], Base64.URL_SAFE), StandardCharsets.UTF_8);
                    Matcher exp = Pattern.compile("\"exp\"\\s*:\\s*(\\d+)").matcher(payload);
                    if (!exp.find()) continue;
                    long expires = Long.parseLong(exp.group(1)) * 1000;
                    if (expires <= now) continue;
                    if (header.contains("WebPlayKid") || payload.contains("AMPWebPlay")) {
                        fallback = jwt.group();
                        fallbackExpires = expires;
                        break;
                    }
                    if (fallback == null) {
                        fallback = jwt.group();
                        fallbackExpires = expires;
                    }
                } catch (IllegalArgumentException ignored) {
                    // Not a JWT, just something shaped like one.
                }
            }
            if (fallback != null) {
                token = fallback;
                tokenExpires = fallbackExpires;
                retryAfter = 0;
                CanvasCache.token(token, tokenExpires);
                return token;
            }
        }
        Log.w(TAG, "canvas: no web player token found");
        return null;
    }

    private static String get(String url, String bearer) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(15_000);
        c.setRequestProperty("User-Agent", UA);
        if (bearer != null) {
            c.setRequestProperty("Authorization", "Bearer " + bearer);
            c.setRequestProperty("Origin", "https://music.apple.com");
            c.setRequestProperty("Referer", "https://music.apple.com/");
        }
        int code = c.getResponseCode();
        if (code == 401 && bearer != null) {
            synchronized (AppleCanvas.class) {
                token = null;
                retryAfter = 0;
                CanvasCache.token(null, 0);
                Log.i(TAG, "canvas: token refused (401), scraping again next time");
            }
        }
        if (code >= 400) throw new IllegalStateException("http " + code + " for " + url.replaceAll("\\?.*", ""));
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            return buf.toString("UTF-8");
        }
    }
}
