package app.ayham.extension.music.spike;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Full offline: when YT's native download saves a song, the rest of what its player shows is
 * saved with it, so a downloaded song looks the same with no network:
 * <ul>
 *   <li>its lyrics, from Morphe's providers in the user's order, into Morphe's own lyrics store
 *   (the same track key Morphe's player uses: YT Music's title and artist, cleaned by Morphe);</li>
 *   <li>its animated art clip, into the app's files (not the 80 MB cache), saved against the
 *   video id so the canvas finds it offline;</li>
 *   <li>its video type, for the song-version and automix rules.</li>
 * </ul>
 * YT saves the thumbnail and the player data itself. One background thread per song, once per run.
 */
final class OfflineExtras {
    private static final String TAG = "AyhamSpikeC";
    private static final String LYRICS = "app.morphe.extension.music.patches.lyrics.";
    private static final Set<String> done = ConcurrentHashMap.newKeySet();

    private OfflineExtras() {
    }

    /** Called when YT's offline engine saves [videoId]. */
    static void saved(String videoId) {
        Context c = SpikeA.appContext;
        if (c == null || videoId == null || videoId.isEmpty() || !done.add(videoId)) return;
        new Thread(() -> {
            try {
                JSONObject d = details(videoId);
                String title = d.optString("title", ""), artist = d.optString("author", "");
                int length = d.optInt("lengthSeconds", 0);
                SongVersion.noteType(videoId, d.optString("musicVideoType", ""));
                Log.i(TAG, "offline extras for " + videoId + ": '" + title + "' by '" + artist + "' (" + length + " s)");
                if (title.isEmpty()) return;
                Log.i(TAG, "offline extras " + videoId + ": lyrics " + lyrics(title, artist, length));
                if ("1".equals(SpikeA.props().getProperty("canvas"))) Log.i(TAG, "offline extras " + videoId + ": art clip " + clip(c, videoId, title, artist));
            } catch (Throwable t) {
                done.remove(videoId); // asked again on the next save
                Log.w(TAG, "offline extras failed for " + videoId, t);
            }
        }, "ayham-offline-extras").start();
    }

    /** YT Music's own title / artist / length / type for the video (the WEB_REMIX player data). */
    private static JSONObject details(String videoId) throws Exception {
        JSONObject body = new JSONObject().put("videoId", videoId).put("context", new JSONObject()
                .put("client", new JSONObject().put("clientName", "WEB_REMIX").put("clientVersion", "1.20251101.01.00")
                        .put("hl", "en").put("gl", "US")));
        HttpURLConnection h = (HttpURLConnection) new URL("https://music.youtube.com/youtubei/v1/player?prettyPrint=false").openConnection();
        h.setConnectTimeout(10_000);
        h.setReadTimeout(15_000);
        h.setDoOutput(true);
        h.setRequestProperty("Content-Type", "application/json");
        // a failed send lets the connection go too (the read below has its own finally)
        try (OutputStream out = h.getOutputStream()) {
            out.write(body.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            h.disconnect();
            throw e;
        }
        try (InputStream in = h.getInputStream()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            JSONObject d = new JSONObject(buf.toString("UTF-8")).optJSONObject("videoDetails");
            return d == null ? new JSONObject() : d;
        } finally {
            h.disconnect();
        }
    }

    /**
     * Morphe's own lyrics prefetch, for this song: its search providers in the user's order, the
     * first valid answer stored in its lyrics store under the track key its player looks up.
     */
    private static String lyrics(String title, String artist, int length) throws Exception {
        Class<?> settings = Class.forName("app.morphe.extension.music.settings.Settings");
        Object enabled = settings.getField("LYRICS_ENABLED").get(null);
        if (!(boolean) enabled.getClass().getMethod("get").invoke(enabled)) return "skipped (Morphe's lyrics are off)";
        Object source = settings.getField("LYRICS_SOURCE").get(null);
        String order = (String) source.getClass().getMethod("get").invoke(source);
        Class<?> providersType = Class.forName(LYRICS + "LyricsProviders");
        List<?> providers = (List<?>) providersType.getMethod("searchableProviders", List.class)
                .invoke(null, providersType.getMethod("providersInOrder", String.class).invoke(null, order));
        String[] clean = (String[]) Class.forName(LYRICS + "model.MetadataCleaner")
                .getMethod("parseCleanTitleAndArtist", String.class, String.class).invoke(null, title, artist);
        Class<?> trackType = Class.forName(LYRICS + "model.TrackInfo");
        Object track = trackType.getConstructor(String.class, String.class, String.class, int.class)
                .newInstance(clean[1], clean[0], "", length);
        Class<?> lyricsType = Class.forName(LYRICS + "model.Lyrics");
        Class<?> match = Class.forName(LYRICS + "LyricsMatch");
        Class<?> cache = Class.forName(LYRICS + "storage.LyricsCache");
        Method fetch = Class.forName(LYRICS + "requests.LyricsProvider").getMethod("fetch", trackType);
        Object notFound = lyricsType.getField("NOT_FOUND").get(null);
        for (Object p : providers) {
            Object r;
            try {
                r = fetch.invoke(p, track);
            } catch (Throwable t) {
                continue;
            }
            if (r == null) continue;
            Object l = r.getClass().getMethod("lyrics").invoke(r);
            if (l == null || l == notFound) continue;
            if (!(boolean) match.getMethod("isValidLyrics", lyricsType, trackType).invoke(null, l, track)) continue;
            String src = (String) match.getMethod("cacheSourceFor", lyricsType).invoke(null, l);
            cache.getMethod("put", trackType, String.class, lyricsType, String.class).invoke(null, track, src, l, null);
            return "saved from " + p.getClass().getSimpleName() + " for '" + clean[0] + " - " + clean[1] + "'";
        }
        return "none found";
    }

    /** The Apple motion clip, kept in files/ayham-offline (no cap) and saved against the video id. */
    private static String clip(Context c, String videoId, String title, String artist) throws Exception {
        String url = AppleCanvas.find(title, artist);
        if (url == null) {
            CanvasCache.put(CanvasCache.idKey(videoId), null);
            return "none (no motion art)";
        }
        File f = url.contains(".m3u8") ? HlsClip.fetch(url, new File(c.getFilesDir(), "ayham-offline"), 720, false) : null;
        if (f == null) return "download failed";
        CanvasCache.put(CanvasCache.idKey(videoId), f.getAbsolutePath());
        return f.getName() + " (" + f.length() / 1024 + " KB)";
    }
}
