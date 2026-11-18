package app.ayham.extension.music.features;

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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Spotify Canvas, Metrolist's DirectCanvasProvider (getSpotifyCanvas) without a login: an anonymous
 * web-player token (spotlib's Auth and Totp), Spotify's search (the pathfinder searchTopResultsList
 * query), Metrolist's match rules, and the canvas URL from the public mirror (publicSpotifyCanvas).
 * Only scdn.co media over https is taken (trustedMedia). The clip is saved in the canvas cache.
 * Off the main thread.
 */
final class SpotifyCanvas {
    private static final String TAG = "Ayham";
    private static final String TOKEN_URL = "https://open.spotify.com/api/token";
    private static final String CLIENT_TOKEN_URL = "https://clienttoken.spotify.com/v1/clienttoken";
    private static final String PATHFINDER_URL = "https://api-partner.spotify.com/pathfinder/v2/query";
    private static final String MIRROR_URL = "https://spotify-canva.vercel.app/api/canvas?trackId=";
    private static final String SEARCH_HASH = "12d940b3f7381506a82ff5382eb59b0ed65118656e6bcdfbe4cebc528f75d29d";
    private static final String USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64; rv:153.0) Gecko/20100101 Firefox/153.0";
    private static final String APP_VERSION = "1.2.96.390.gd46a85db";
    private static final String WEB_CLIENT_ID = "d8a5ed958d274c2e8ee717e6a4b0971d";
    /** spotlib's Totp: the web player's secret (obfuscated), version 61, 30 s steps. */
    private static final String TOTP_SECRET = ",7/*F(\"rLJ2oxaKL^f+E1xvP@N";
    private static final int TOTP_VERSION = 61;
    private static final int TIMEOUT_MS = 8_000, MAX_BYTES = 256 * 1024;

    private static String accessToken, clientToken;
    private static long accessExpires;
    private static final String deviceId = UUID.randomUUID().toString();

    private SpotifyCanvas() {
    }

    /** The song's Canvas clip as a file in cacheDir/canvas, or null (no canvas, or no match). */
    static File find(String title, String artist, File cacheDir) throws Exception {
        return find(title, artist, cacheDir, true);
    }

    /** @param trim false for the downloaded songs' canvases (kept in the app's files, no size cap). */
    static synchronized File find(String title, String artist, File cacheDir, boolean trim) throws Exception {
        if (title == null || artist == null || title.trim().isEmpty() || artist.trim().isEmpty()) return null;
        String url = canvasUrl(title, artist);
        if (url == null) return null;
        File dir = new File(cacheDir, "canvas");
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        File out = new File(dir, "sp-" + Integer.toHexString(url.hashCode()) + ".mp4");
        if (out.length() > 0) {
            out.setLastModified(System.currentTimeMillis());
            return out;
        }
        long start = System.currentTimeMillis();
        File part = new File(dir, out.getName() + ".part");
        HttpURLConnection h = open(url);
        boolean done = false;
        // a failed download is an error (not saved as "no canvas"), and leaves no .part behind
        try (InputStream in = h.getInputStream(); OutputStream o = new FileOutputStream(part)) {
            if (h.getResponseCode() != 200) throw new java.io.IOException("canvas download: HTTP " + h.getResponseCode());
            byte[] b = new byte[65536];
            int n;
            long total = 0;
            while ((n = in.read(b)) > 0) {
                total += n;
                if (total > 30L * 1024 * 1024) throw new java.io.IOException("canvas over 30 MB");
                o.write(b, 0, n);
            }
            done = true;
        } finally {
            h.disconnect();
            if (!done && part.exists() && !part.delete()) Log.w(TAG, "canvas: could not delete " + part.getName());
        }
        if (!part.renameTo(out)) return null;
        if (trim) HlsClip.trim(dir, out);
        Log.i(TAG, "canvas: spotify clip " + out.length() / 1024 + " KB in " + (System.currentTimeMillis() - start) + " ms");
        return out;
    }

    /** Metrolist's spotifyCanvas: the first matching track among the top 10 results that has a canvas. */
    static String canvasUrl(String title, String artist) throws Exception {
        String primary = artist.split("\\s*(,|&| x | X |\\bfeat\\.?|\\bft\\.?)\\s*")[0].trim();
        JSONObject vars = new JSONObject()
                .put("query", title + " " + artist).put("limit", 10).put("offset", 0).put("numberOfTopResults", 10)
                .put("includeArtistHasConcertsField", false).put("includeAudiobooks", true).put("includeAuthors", false)
                .put("includePreReleases", true).put("includeAlbumPreReleases", false).put("includeEpisodeContentRatingsV2", true)
                .put("sectionFilters", new JSONArray().put("GENERIC").put("VIDEO_CONTENT"));
        JSONObject search = query("searchTopResultsList", SEARCH_HASH, vars);
        JSONObject data = search.optJSONObject("data");
        JSONObject v2 = data == null ? null : data.optJSONObject("searchV2");
        JSONObject top = v2 == null ? null : v2.optJSONObject("topResultsV2");
        JSONArray items = top == null ? null : top.optJSONArray("itemsV2");
        if (items == null) {
            Log.i(TAG, "canvas spotify: no results for '" + title + "'");
            return null;
        }
        StringBuilder seen = new StringBuilder();
        for (int i = 0; i < Math.min(10, items.length()); i++) {
            JSONObject it = items.optJSONObject(i);
            JSONObject item = it == null ? null : it.optJSONObject("item");
            JSONObject track = item == null ? null : item.optJSONObject("data");
            if (track == null || !"Track".equals(track.optString("__typename"))) continue;
            String id = track.optString("id", "");
            if (id.isEmpty()) continue;
            JSONObject artistsObj = track.optJSONObject("artists");
            JSONArray as = artistsObj == null ? null : artistsObj.optJSONArray("items");
            StringBuilder names = new StringBuilder();
            String first = null;
            for (int k = 0; as != null && k < as.length(); k++) {
                JSONObject prof = as.optJSONObject(k) == null ? null : as.optJSONObject(k).optJSONObject("profile");
                String nm = prof == null ? null : prof.optString("name", null);
                if (nm == null) continue;
                if (first == null) first = nm;
                if (names.length() > 0) names.append(", ");
                names.append(nm);
            }
            String name = track.optString("name", "");
            seen.append("; '").append(name).append("' by ").append(names);
            if (!matches(title, artist, null, 0, name, names.toString(), null, 0, primary, first)) continue;
            String canvas = publicCanvas(id);
            if (trusted(canvas)) {
                Log.i(TAG, "canvas spotify: '" + name + "' by " + names + " (" + id + ") has a canvas");
                return canvas;
            }
            Log.i(TAG, "canvas spotify: '" + name + "' by " + names + " (" + id + ") matches, no canvas");
        }
        Log.i(TAG, "canvas spotify: none for '" + title + "' by '" + artist + "' among" + seen);
        return null;
    }

    /** Metrolist's publicSpotifyCanvas: the public mirror's canvas for the track. */
    private static String publicCanvas(String trackId) throws Exception {
        if (!trackId.matches("[A-Za-z0-9]{22}")) return null;
        HttpURLConnection h = open(MIRROR_URL + URLEncoder.encode(trackId, "UTF-8"));
        try {
            if (h.getResponseCode() != 200) return null;
            JSONArray list = new JSONObject(read(h)).optJSONArray("canvasesList");
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject c = list.optJSONObject(i);
                if (c != null && ("spotify:track:" + trackId).equals(c.optString("trackUri")) && trusted(c.optString("canvasUrl", null)))
                    return c.optString("canvasUrl");
            }
            return null;
        } finally {
            h.disconnect();
        }
    }

    /** A pathfinder persisted query with the anonymous tokens (fetched again once on a 401). */
    private static JSONObject query(String operation, String hash, JSONObject variables) throws Exception {
        String body = new JSONObject().put("variables", variables).put("operationName", operation)
                .put("extensions", new JSONObject().put("persistedQuery", new JSONObject().put("version", 1).put("sha256Hash", hash)))
                .toString();
        for (int attempt = 0; attempt < 2; attempt++) {
            auth();
            HttpURLConnection h = open(PATHFINDER_URL);
            try {
                h.setRequestMethod("POST");
                h.setDoOutput(true);
                h.setRequestProperty("Accept", "application/json");
                h.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
                h.setRequestProperty("Origin", "https://open.spotify.com");
                h.setRequestProperty("Referer", "https://open.spotify.com/");
                h.setRequestProperty("app-platform", "WebPlayer");
                h.setRequestProperty("spotify-app-version", APP_VERSION);
                h.setRequestProperty("Authorization", "Bearer " + accessToken);
                h.setRequestProperty("client-token", clientToken);
                try (OutputStream o = h.getOutputStream()) {
                    o.write(body.getBytes(StandardCharsets.UTF_8));
                }
                int code = h.getResponseCode();
                if (code == 401 && attempt == 0) {
                    accessToken = clientToken = null;
                    continue;
                }
                if (code >= 400) throw new java.io.IOException(operation + ": HTTP " + code);
                JSONObject data = new JSONObject(read(h));
                if (data.has("errors")) throw new java.io.IOException(operation + ": GraphQL errors");
                return data;
            } finally {
                h.disconnect();
            }
        }
        throw new java.io.IOException(operation + ": session expired");
    }

    /** spotlib's AuthHttp: the anonymous web-player token (TOTP), then the client token. */
    private static void auth() throws Exception {
        if (accessToken == null || System.currentTimeMillis() > accessExpires - 60_000) {
            String code = totp(System.currentTimeMillis());
            HttpURLConnection h = open(TOKEN_URL + "?reason=init&productType=web-player&totp=" + code + "&totpServer=" + code + "&totpVer=" + TOTP_VERSION);
            try {
                h.setRequestProperty("Accept", "*/*");
                h.setRequestProperty("Referer", "https://open.spotify.com/");
                int status = h.getResponseCode();
                if (status >= 400) throw new java.io.IOException("/api/token: HTTP " + status);
                JSONObject t = new JSONObject(read(h));
                String token = t.optString("accessToken", "");
                if (token.isEmpty()) throw new java.io.IOException("/api/token: no access token");
                accessToken = token;
                accessExpires = t.optLong("accessTokenExpirationTimestampMs", System.currentTimeMillis() + 30 * 60_000);
                clientToken = null;
            } finally {
                h.disconnect();
            }
        }
        if (clientToken == null) {
            JSONObject payload = new JSONObject().put("client_data", new JSONObject()
                    .put("client_version", APP_VERSION).put("client_id", WEB_CLIENT_ID)
                    .put("js_sdk_data", new JSONObject().put("device_brand", "unknown").put("device_model", "unknown")
                            .put("os", "android").put("os_version", "unknown").put("device_id", deviceId).put("device_type", "computer")));
            HttpURLConnection h = open(CLIENT_TOKEN_URL);
            try {
                h.setRequestMethod("POST");
                h.setDoOutput(true);
                h.setRequestProperty("Accept", "application/json");
                h.setRequestProperty("Content-Type", "application/json");
                h.setRequestProperty("Origin", "https://open.spotify.com");
                h.setRequestProperty("Referer", "https://open.spotify.com/");
                try (OutputStream o = h.getOutputStream()) {
                    o.write(payload.toString().getBytes(StandardCharsets.UTF_8));
                }
                int status = h.getResponseCode();
                if (status >= 400) throw new java.io.IOException("clienttoken: HTTP " + status);
                JSONObject granted = new JSONObject(read(h)).optJSONObject("granted_token");
                String token = granted == null ? "" : granted.optString("token", "");
                if (token.isEmpty()) throw new java.io.IOException("clienttoken: no token");
                clientToken = token;
            } finally {
                h.disconnect();
            }
        }
    }

    /** spotlib's Totp.code: HOTP (SHA-1, 6 digits) over 30 s steps, with the de-obfuscated secret. */
    static String totp(long nowMs) throws Exception {
        StringBuilder raw = new StringBuilder();
        for (int i = 0; i < TOTP_SECRET.length(); i++) raw.append(TOTP_SECRET.charAt(i) ^ (i % 33 + 9));
        byte[] key = raw.toString().getBytes(StandardCharsets.UTF_8);
        long counter = nowMs / 1000 / 30;
        byte[] data = new byte[8];
        for (int i = 0; i < 8; i++) data[7 - i] = (byte) ((counter >> (8 * i)) & 0xFF);
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(key, "HmacSHA1"));
        byte[] d = mac.doFinal(data);
        int off = d[d.length - 1] & 0x0F;
        int bin = ((d[off] & 0x7F) << 24) | ((d[off + 1] & 0xFF) << 16) | ((d[off + 2] & 0xFF) << 8) | (d[off + 3] & 0xFF);
        String s = Integer.toString(bin % 1_000_000);
        while (s.length() < 6) s = "0" + s;
        return s;
    }

    /** Metrolist's trustedMedia for Spotify: https on 443, no user info, a scdn.co host. */
    static boolean trusted(String url) {
        if (url == null) return false;
        try {
            URL u = new URL(url);
            String host = u.getHost().toLowerCase(Locale.ROOT);
            return "https".equals(u.getProtocol()) && u.getUserInfo() == null && (u.getPort() == -1 || u.getPort() == 443)
                    && (host.equals("scdn.co") || host.endsWith(".scdn.co"));
        } catch (Exception e) {
            return false;
        }
    }

    private static final Set<String> NOISE = new HashSet<>(java.util.Arrays.asList("feat", "ft", "featuring", "official", "video", "audio"));

    private static Set<String> words(String value) {
        String s = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
        Set<String> out = new HashSet<>();
        for (String w : s.split("[^\\p{L}\\p{N}]+")) if (!w.isEmpty() && !NOISE.contains(w)) out.add(w);
        return out;
    }

    private static double similarity(String a, String b) {
        Set<String> x = words(a), y = words(b);
        if (x.isEmpty() || y.isEmpty()) return 0;
        Set<String> both = new HashSet<>(x);
        both.retainAll(y);
        return both.size() / (double) Math.max(x.size(), y.size());
    }

    /** Metrolist's DirectCanvasProvider.matches: the artist (or the primary one) exactly, title words 75%, length within 10 s, album 50%. */
    static boolean matches(String title, String artist, String album, long duration, String foundTitle, String foundArtist,
                           String foundAlbum, long foundDuration, String primary, String foundPrimary) {
        Set<String> want = words(artist), got = words(foundArtist), wantP = words(primary), gotP = words(foundPrimary);
        boolean complete = want.equals(got), primaryMatch = !wantP.isEmpty() && wantP.equals(gotP);
        if (want.isEmpty() || !(complete || primaryMatch)) return false;
        return similarity(title, foundTitle) >= 0.75
                && (duration <= 0 || foundDuration <= 0 || Math.abs(duration - foundDuration) <= 10_000)
                && (album == null || album.trim().isEmpty() || foundAlbum == null || foundAlbum.trim().isEmpty() || similarity(album, foundAlbum) >= 0.5);
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setInstanceFollowRedirects(false);
        h.setConnectTimeout(TIMEOUT_MS);
        h.setReadTimeout(TIMEOUT_MS);
        h.setRequestProperty("User-Agent", USER_AGENT);
        return h;
    }

    private static String read(HttpURLConnection h) throws Exception {
        try (InputStream in = h.getInputStream()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) {
                buf.write(b, 0, n);
                if (buf.size() > MAX_BYTES) throw new java.io.IOException("response over " + MAX_BYTES / 1024 + " KB");
            }
            return buf.toString("UTF-8");
        }
    }
}
