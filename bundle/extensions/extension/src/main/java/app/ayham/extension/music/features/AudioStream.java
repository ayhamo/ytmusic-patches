package app.ayham.extension.music.features;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Fetches a song's audio file for the automix analysis: the streams come from Morphe's
 * StreamingDataRequest (the same clients the downloads use), the best audio format is read from
 * the player response, and the file is fetched in 1 MB ranges.
 */
public final class AudioStream {
    private static final String TAG = "AudioStream";
    private static final long RANGE = 1 << 20;
    private static final String USER_AGENT = "com.google.android.youtube/19.47.53 (Linux; U; Android 14) gzip";
    static final String[] CLIENTS = {
            "VISIONOS_1_03", "VISIONOS_1_02", "ANDROID_VR_DASH", "TV_DASH", "ANDROID_CREATOR", "TV_SIMPLY"
    };

    private AudioStream() {
    }

    /** The audio format picked from a player response. */
    static final class Pick {
        final int itag;
        final String url, mime;
        final long length;

        Pick(int itag, String url, String mime, long length) {
            this.itag = itag;
            this.url = url;
            this.mime = mime;
            this.length = length;
        }
    }

    /** The player response of a song from Morphe's download clients, or null. */
    static byte[] playerResponse(String videoId) throws Exception {
        Class<?> clientType = Class.forName("app.morphe.extension.shared.spoof.ClientType");
        List<Object> clients = new ArrayList<>();
        for (String name : CLIENTS) {
            for (Object c : clientType.getEnumConstants()) {
                if (((Enum<?>) c).name().equals(name)) clients.add(c);
            }
        }
        if (clients.isEmpty()) throw new IOException("no download clients");
        Class<?> request = Class.forName("app.morphe.extension.shared.spoof.requests.StreamingDataRequest");
        Object req = request.getMethod("fetchRequestForDownload", String.class, List.class, clientType)
                .invoke(null, videoId, clients, clients.get(0));
        Object stream = request.getMethod("getStream").invoke(req);
        if (stream == null) return null;
        return (byte[]) stream.getClass().getMethod("streamingData").invoke(stream);
    }

    /** The audio format with the highest bitrate that has a plain url and a length, or null. */
    static Pick best(byte[] playerResponse) {
        Pick best = null;
        long bestRate = -1;
        for (ProtoWire.Field f : new ProtoWire(playerResponse).message(4).all(3)) {
            ProtoWire format = new ProtoWire(f.data);
            String mime = format.string(5);
            String url = format.string(2);
            long length = format.varint(12, 0);
            if (!mime.startsWith("audio/") || url.isEmpty() || length <= 0) continue;
            long rate = format.varint(6, 0);
            if (rate > bestRate) {
                bestRate = rate;
                best = new Pick((int) format.varint(1, 0), url, mime, length);
            }
        }
        return best;
    }

    /** Fetches the song's best audio into {@code dir}; throws when no stream is found or a range fails. */
    public static File download(File dir, String videoId) throws Exception {
        byte[] response = playerResponse(videoId);
        if (response == null) throw new IOException("no stream for " + videoId);
        Pick pick = best(response);
        if (pick == null) throw new IOException("no audio format for " + videoId);
        Log.i(TAG, videoId + ": itag=" + pick.itag + " " + pick.mime + " length=" + pick.length);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File out = new File(dir, videoId + (pick.mime.contains("mp4") ? ".m4a" : ".webm"));
        byte[] buffer = new byte[64 * 1024];
        try (FileOutputStream os = new FileOutputStream(out)) {
            String url = pick.url;
            for (long start = 0; start < pick.length; start += RANGE) {
                long end = Math.min(pick.length, start + RANGE) - 1;
                HttpURLConnection conn = null;
                int code = -1;
                // googlevideo sometimes answers 302 to another server; the connection in YT's process
                // doesn't follow it (phone, 2026-11-18: every analysis of a song failed on it). Followed
                // here, and the new address kept for the remaining ranges.
                for (int hop = 0; hop <= 5; hop++) {
                    conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setInstanceFollowRedirects(false);
                    conn.setConnectTimeout(15_000);
                    conn.setReadTimeout(30_000);
                    conn.setRequestProperty("User-Agent", USER_AGENT);
                    conn.setRequestProperty("Referer", "https://music.youtube.com/");
                    conn.setRequestProperty("Range", "bytes=" + start + "-" + end);
                    code = conn.getResponseCode();
                    String to = code / 100 == 3 ? conn.getHeaderField("Location") : null;
                    if (to == null || hop == 5) break;
                    conn.disconnect();
                    url = new URL(new URL(url), to).toString();
                    // the host only: the address carries the stream's signature
                    Log.i(TAG, videoId + ": HTTP " + code + " at byte " + start + ", followed to " + new URL(url).getHost());
                }
                try {
                    if (code != HttpURLConnection.HTTP_PARTIAL) {
                        throw new IOException("range " + start + "-" + end + ": HTTP " + code);
                    }
                    try (InputStream in = conn.getInputStream()) {
                        int n;
                        while ((n = in.read(buffer)) > 0) os.write(buffer, 0, n);
                    }
                } finally {
                    conn.disconnect();
                }
            }
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            throw e;
        }
        return out;
    }
}
