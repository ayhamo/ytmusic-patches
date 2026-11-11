package app.ayham.extension.music.spike;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Throwaway spike, batch C again (PLAN.md item 9): YouTube Music's own offline engine for a free
 * account. The song is saved by the app's native download path, so it plays offline in YT's own
 * player. Ported from Morphe's YouTube in-app downloads, which does the same for YouTube.
 * <p>
 * Switched on with {@code native=1} in {@code spike.properties}.
 */
@SuppressWarnings("unused")
public final class SpikeC {
    private static final String TAG = "AyhamSpikeC";
    private static final Handler main = new Handler(Looper.getMainLooper());

    /** Seconds a saved song stays valid before the app asks the server again. */
    private static final int POLICY_LIFETIME_SECONDS = 365 * 24 * 3600;

    private static final String[] DOWNLOAD_CLIENTS = {
            "VISIONOS_1_03", "VISIONOS_1_02", "ANDROID_VR_DASH", "TV_DASH", "ANDROID_CREATOR", "TV_SIMPLY"
    };

    private static volatile WeakReference<Object> videoResolver = new WeakReference<>(null);
    private static volatile WeakReference<Object> offlineManager = new WeakReference<>(null);

    private SpikeC() {
    }

    // ---- switch ----

    /** The same configuration as the other features (settings, then the dev override file). */
    private static Properties props() {
        try {
            if (SpikeA.appContext == null) {
                SpikeA.appContext = (Context) Class.forName("android.app.ActivityThread")
                        .getMethod("currentApplication").invoke(null);
            }
        } catch (Throwable t) {
            Log.w(TAG, "app context not found", t);
        }
        return SpikeA.props();
    }

    private static boolean nativeMode() {
        return "1".equals(props().getProperty("native"));
    }

    // ---- captures ----

    /** Injection point: constructor of YT Music's OfflineVideoCommandResolver. */
    public static void captureVideoResolver(Object resolver) {
        videoResolver = new WeakReference<>(resolver);
        Log.i(TAG, "captured offline video command resolver " + resolver.getClass().getName());
    }

    /** Injection point: constructor of YT Music's offline manager (MusicTrackDownloadController front). */
    public static void captureOfflineManager(Object manager) {
        offlineManager = new WeakReference<>(manager);
        Log.i(TAG, "captured offline manager " + manager.getClass().getName());
    }

    // ---- 1: the Download button saves with the native engine ----

    /**
     * Injection point: start of Morphe's DownloadsPatch.onMenuDownload(videoId), which every
     * Download tap goes through (menu, player chip, upsell dialog).
     *
     * @return true if the song is saved natively instead of by Morphe's own downloader.
     */
    public static boolean onMenuDownload(String videoId) {
        try {
            if (!nativeMode()) return false;
            Log.i(TAG, "Download tapped for " + videoId + ", saving with YT's native engine");
            return nativeSave(videoId);
        } catch (Throwable t) {
            Log.e(TAG, "onMenuDownload failed", t);
            return false;
        }
    }

    /**
     * Injection point: start of Morphe's LocalDownloadManager.downloadPlaylistTrack(videoId), the
     * per-track save of its playlist/album downloads (it lists the tracks and shows the progress).
     *
     * @return true if saved natively instead (Morphe then counts it as saved).
     */
    public static boolean onPlaylistTrack(String videoId) {
        try {
            if (!nativeMode()) return false;
            final boolean[] saved = {false};
            final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            main.post(() -> {
                try {
                    saved[0] = nativeSave(videoId);
                } catch (Throwable t) {
                    Log.e(TAG, "native playlist track save failed for " + videoId, t);
                } finally {
                    done.countDown();
                }
            });
            done.await(10, java.util.concurrent.TimeUnit.SECONDS);
            Log.i(TAG, "playlist track " + videoId + " saved natively: " + saved[0]);
            return saved[0];
        } catch (Throwable t) {
            Log.e(TAG, "onPlaylistTrack failed", t);
            return false;
        }
    }

    /**
     * Injection point: start of Morphe's offlinePlaylistEndpointOnClick (the app's own Download of
     * a playlist it offers to save, e.g. Liked music).
     *
     * @return true to leave it to the app (its native offline playlist), false for Morphe's handling.
     */
    public static boolean letAppSavePlaylist() {
        boolean app = nativeMode() && "app".equals(props().getProperty("playlistRoute"));
        Log.i(TAG, "offline playlist endpoint tapped, route=" + (app ? "app" : "morphe/tracks"));
        return app;
    }

    private static volatile WeakReference<Object> activityComponent = new WeakReference<>(null);

    /** Injection point: constructor of the activity component that keeps the manager's provider. */
    public static void captureActivityComponent(Object component) {
        activityComponent = new WeakReference<>(component);
        Log.i(TAG, "captured activity component " + component.getClass().getName());
        // Test trigger: the same entry every Download tap uses, for layouts without a Download button.
        String now = props().getProperty("downloadNow", "").trim();
        if (!now.isEmpty() && !now.equals(lastTriggered)) {
            lastTriggered = now;
            main.postDelayed(() -> {
                for (String id : now.split(",")) Log.i(TAG, "test trigger " + id + " -> " + onMenuDownload(id.trim()));
            }, 6000);
        }
    }

    private static String lastTriggered;

    private static boolean nativeSave(String videoId) throws Exception {
        Object manager = offlineManager.get();
        Object logger = null; // The app skips logging when it is null.
        if (manager == null) {
            Object component = activityComponent.get();
            if (component == null) {
                Log.w(TAG, "no offline manager and no activity component captured");
                return false;
            }
            // Added by the patch: the scoped provider's get(), which builds the manager once.
            manager = component.getClass().getMethod("ayham_offlineManager", Object.class).invoke(null, component);
            Log.i(TAG, "offline manager from the component's provider: " + manager.getClass().getName());
        }

        Method save = null;
        for (Method m : manager.getClass().getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (m.getName().equals("h") && p.length == 4 && p[0] == String.class) save = m;
        }
        if (save == null) throw new IllegalStateException("save method not found on " + manager.getClass());

        Class<?> offlineabilityType = save.getParameterTypes()[1];
        Class<?> commandMetadataType = save.getParameterTypes()[3];
        Object offlineability = parse(offlineabilityType,
                new ProtoWire().setVarint(1, 1).toBytes()); // offlineable = true, no formats
        Object metadata = defaultInstance(commandMetadataType);
        Log.i(TAG, "calling native save " + manager.getClass().getName() + ".h(" + videoId + ")");
        save.invoke(manager, videoId, offlineability, logger, metadata);
        return true;
    }

    /**
     * Injection point: start of the native offline manager's save, with the offlineability of the song.
     * Unlocks a song the server marks as Premium-only.
     */
    public static Object unlockOfflineability(Object offlineability) {
        try {
            if (offlineability == null || !nativeMode()) return offlineability;
            ProtoWire renderer = new ProtoWire(toBytes(offlineability));
            boolean offlineable = renderer.varint(1, 0) == 1;
            StringBuilder formats = new StringBuilder();
            List<ProtoWire.Field> unlocked = new ArrayList<>();
            for (ProtoWire.Field f : renderer.all(3)) {
                ProtoWire format = new ProtoWire(f.data);
                formats.append(' ').append(format.message(1).string(4)).append('/')
                        .append(format.varint(3, 0)).append('/').append(format.varint(4, 0));
                if (format.varint(4, 0) == 2) format.setVarint(4, 1); // PREMIUM_LOCKED -> FREE
                f.data = format.toBytes();
            }
            Log.i(TAG, "offlineability: offlineable=" + offlineable + " dialog=" + renderer.has(2)
                    + " formats:" + formats);
            if (offlineable && formats.indexOf("/2") < 0) return offlineability;
            renderer.setVarint(1, 1).remove(2);
            Object result = parse(offlineability.getClass(), renderer.toBytes());
            Log.i(TAG, "offlineability unlocked");
            return result;
        } catch (Throwable t) {
            Log.e(TAG, "unlockOfflineability failed", t);
            return offlineability;
        }
    }

    // ---- 2: the server declines the offline player response ----

    /**
     * Injection point: the player response the app saves a song with, before it is cached.
     *
     * @return A response that can be saved, or null to keep the original.
     */
    public static byte[] replaceDeclinedOfflinePlayerResponse(Object playerResponse) {
        try {
            if (playerResponse == null || !nativeMode()) return null;
            ProtoWire original = new ProtoWire(toBytes(playerResponse));
            ProtoWire state = original.message(14);
            String videoId = original.message(11).string(1);
            int formats = original.message(4).all(3).size();
            long action = state.varint(5, -1);
            Log.i(TAG, "offline player response " + videoId + ": hasState=" + original.has(14)
                    + " action=" + action + " reason=" + state.string(6) + " adaptiveFormats=" + formats
                    + " hasPlayerConfig=" + original.has(15));
            if (!original.has(14)) return null;
            OfflineExtras.saved(videoId); // lyrics and the animated art saved along with the song
            if (action == 1 && formats > 0) return null;
            if (videoId.isEmpty()) return null;

            // Up to 3 tries: a failed fetch leaves YT's "Premium required" answer in place, and YT
            // then stores the song as "requires payment" and its Retry doesn't ask again.
            byte[] donorBytes = null;
            for (int attempt = 1; attempt <= 3 && donorBytes == null; attempt++) {
                try {
                    donorBytes = fetchStreams(videoId);
                } catch (Throwable t) {
                    Log.w(TAG, "streams for " + videoId + ", try " + attempt + " failed: " + t);
                }
                if (donorBytes == null && attempt < 3) {
                    try {
                        Thread.sleep(1500L * attempt);
                    } catch (InterruptedException e) {
                        // YT cancelled the download (its worker thread is interrupted): no more
                        // tries, and the interrupt is kept for YT's own code to see.
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            if (donorBytes == null) {
                Log.w(TAG, "no streams to save " + videoId + " after 3 tries");
                return null;
            }
            ProtoWire donor = new ProtoWire(donorBytes);
            ProtoWire streaming = donor.message(4);
            StringBuilder list = new StringBuilder();
            String only = props().getProperty("offlineAudio", "").trim();
            if (!only.isEmpty()) {
                // Keep only the chosen audio itag (and drop video), to see which formats the engine accepts.
                // Opus 251 by default; a song without it is saved as AAC 140 instead
                for (String itag : new String[]{only, "140"}) {
                    ProtoWire kept = new ProtoWire(streaming.toBytes()).remove(3);
                    int n = 0;
                    for (ProtoWire.Field f : streaming.all(3)) {
                        if (String.valueOf(new ProtoWire(f.data).varint(1, 0)).equals(itag)) {
                            kept.addMessage(3, new ProtoWire(f.data));
                            n++;
                        }
                    }
                    if (n > 0) {
                        streaming = kept;
                        break;
                    }
                }
            }
            for (ProtoWire.Field f : streaming.all(3)) {
                ProtoWire format = new ProtoWire(f.data);
                String mime = format.string(5);
                format.setVarint(58, 1); // offlineEligible
                f.data = format.toBytes();
                list.append(' ').append(format.varint(1, 0)).append(':')
                        .append(mime.isEmpty() ? "?" : mime.substring(0, Math.min(mime.length(), 16)))
                        .append(format.has(2) ? "+url" : "");
            }
            donor.setMessage(4, streaming);
            donor.setMessage(14, new ProtoWire()
                    .setString(1, state.string(1))
                    .setVarint(3, POLICY_LIFETIME_SECONDS)
                    .setVarint(5, 1));
            ProtoWire playerConfig = original.has(15) ? original.message(15) : donor.message(15);
            ProtoWire exo = playerConfig.message(57825011).setVarint(1, 1).setVarint(53, 1);
            playerConfig.setMessage(57825011, exo);
            donor.setMessage(15, playerConfig);
            Log.i(TAG, "replaced declined offline response of " + videoId + " formats:" + list);
            return donor.toBytes();
        } catch (Throwable t) {
            Log.e(TAG, "replaceDeclinedOfflinePlayerResponse failed", t);
            return null;
        }
    }

    /** Injection point: after the model of the replaced response is built. */
    public static void onOfflinePlayerResponseReplaced(Object model) {
        setKeepStreams(false);
        Log.i(TAG, "replaced offline player response model: " + (model == null ? "NULL (parse failed)" : model.getClass().getName()));
    }

    private static byte[] fetchStreams(String videoId) throws Exception {
        Class<?> clientType = Class.forName("app.morphe.extension.shared.spoof.ClientType");
        List<Object> clients = new ArrayList<>();
        for (String name : DOWNLOAD_CLIENTS) {
            for (Object c : clientType.getEnumConstants()) {
                if (((Enum<?>) c).name().equals(name)) clients.add(c);
            }
        }
        Class<?> request = Class.forName("app.morphe.extension.shared.spoof.requests.StreamingDataRequest");
        Object req = request.getMethod("fetchRequestForDownload", String.class, List.class, clientType)
                .invoke(null, videoId, clients, clients.get(0));
        Object stream = request.getMethod("getStream").invoke(req);
        if (stream == null) return null;
        Object client = stream.getClass().getMethod("client").invoke(stream);
        Log.i(TAG, "streams for " + videoId + " from client " + client);
        return (byte[]) stream.getClass().getMethod("streamingData").invoke(stream);
    }

    // ---- 3: the server disables a saved song when it is refreshed ----

    /** Injection point: the offline state of a saved song from a 'player/refresh' response. */
    public static Object replaceDisabledOfflineState(Object offlineState) {
        try {
            if (offlineState == null || !nativeMode()) return offlineState;
            ProtoWire state = new ProtoWire(toBytes(offlineState));
            Log.i(TAG, "refresh offline state action=" + state.varint(5, -1) + " reason=" + state.string(6));
            if (state.varint(5, -1) != 3) return offlineState;
            return parse(offlineState.getClass(), new ProtoWire()
                    .setString(1, state.string(1))
                    .setVarint(3, POLICY_LIFETIME_SECONDS)
                    .setVarint(5, 1).toBytes());
        } catch (Throwable t) {
            Log.e(TAG, "replaceDisabledOfflineState failed", t);
            return offlineState;
        }
    }

    private static volatile String currentPage;

    /** onCommand results: let Morphe look at the command, consume it, or skip Morphe (the app resolves it). */
    public static final int MORPHE = 0, CONSUMED = 1, APP = 2;

    /**
     * Injection point: start of Morphe's commandResolverOnClick (every command the app resolves).
     * Remembers the album/playlist page opened last; a Premium upsell on it starts a native
     * playlist download (Morphe's listing + dialog, each track saved natively). In native mode the
     * Downloads page (FEmusic_offline) is the app's own, not Morphe's local screen.
     */
    public static int onCommand(Object command) {
        try {
            if (command == null) return MORPHE;
            byte[] b = toBytes(command);
            String text = new String(b, java.nio.charset.StandardCharsets.ISO_8859_1);
            if (text.contains("FEmusic_offline") && nativeMode()) {
                Log.i(TAG, "Downloads page: the app's own, Morphe's local screen skipped");
                return APP;
            }
            ProtoWire c = new ProtoWire(b);
            if (c.has(48687626)) { // browseEndpoint
                String id = c.message(48687626).string(2); // BrowseEndpoint.browseId
                if (id.matches("(VL|MPRE)[A-Za-z0-9_-]{8,}")) {
                    currentPage = id;
                    Log.i(TAG, "page opened: " + id);
                } else if (!id.isEmpty()) {
                    currentPage = null;
                }
                return MORPHE;
            }
            if (!text.contains("premium_upsell") || !nativeMode()) return MORPHE;
            String page = currentPage;
            if (page == null) return MORPHE;
            if ("app".equals(props().getProperty("playlistRoute"))) {
                if (page.startsWith("MPRE")) {
                    // An album page: YT saves albums by their playlist id (OLAK5uy_...), read off the page.
                    new Thread(() -> saveAlbum(page), "ayham-album").start();
                    return CONSUMED;
                }
                String id = page.startsWith("VL") ? page.substring(2) : page;
                Log.i(TAG, "Premium upsell on page " + page + ": YT's own offline playlist save of " + id);
                if (saveOfflinePlaylist(id)) return CONSUMED;
            }
            Log.i(TAG, "Premium upsell on page " + page + ": native playlist download instead");
            Class.forName("app.morphe.extension.music.patches.downloads.PlaylistDownloader")
                    .getMethod("start", String.class).invoke(null, page);
            return CONSUMED;
        } catch (Throwable t) {
            Log.e(TAG, "onCommand failed", t);
            return MORPHE;
        }
    }

    // ---- YT's own offline playlists (albums and playlists saved as one entry) ----

    private static final int OFFLINE_PLAYLIST_ENDPOINT = 73082583;
    private static final int OFFLINEABILITY_RENDERER = 60572968;
    private static volatile Object router;
    private static volatile Class<?> commandClass;

    /** The command router's dispatch method name; replaced by the patch. */
    static String routerMethod() {
        return null;
    }

    /** Injection point: start of the command router's dispatch(command, Map). */
    public static void captureRouter(Object self, Object command) {
        if (self != null) router = self;
        if (command != null && commandClass == null) commandClass = command.getClass();
    }

    /**
     * Injection point: start of YT's offline playlist endpoint handler. The endpoint carries an
     * offlineability renderer that, for free accounts, holds the Premium dialog; it is removed
     * (and the playlist marked offlineable) so YT saves the playlist itself.
     */
    public static Object unlockPlaylistCommand(Object command) {
        try {
            if (command == null || !nativeMode()) return command;
            ProtoWire c = new ProtoWire(toBytes(command));
            if (!c.has(OFFLINE_PLAYLIST_ENDPOINT)) return command;
            ProtoWire endpoint = c.message(OFFLINE_PLAYLIST_ENDPOINT);
            ProtoWire wrapper = endpoint.message(4);
            ProtoWire renderer = wrapper.message(OFFLINEABILITY_RENDERER);
            Log.i(TAG, "offline playlist " + endpoint.string(1) + " action=" + endpoint.varint(2, -1)
                    + " offlineable=" + renderer.varint(1, -1) + " dialog=" + renderer.has(2));
            if (!renderer.has(2) && renderer.varint(1, 0) == 1) return command;
            renderer.setVarint(1, 1).remove(2);
            wrapper.setMessage(OFFLINEABILITY_RENDERER, renderer);
            endpoint.setMessage(4, wrapper);
            c.setMessage(OFFLINE_PLAYLIST_ENDPOINT, endpoint);
            Log.i(TAG, "offline playlist unlocked");
            return parse(command.getClass(), c.toBytes());
        } catch (Throwable t) {
            Log.e(TAG, "unlockPlaylistCommand failed", t);
            return command;
        }
    }

    /** Asks YT to save a playlist offline itself, as its Premium Download button would. */
    /** Off the main thread: finds the album's playlist id with Morphe's browse request, then saves it. */
    private static void saveAlbum(String albumId) {
        String playlistId = null;
        try {
            Class<?> request = Class.forName("app.morphe.extension.music.patches.downloads.PlaylistTracksRequest");
            Method body = request.getDeclaredMethod("newRequestBody", boolean.class);
            Method send = request.getDeclaredMethod("send", org.json.JSONObject.class, boolean.class);
            body.setAccessible(true);
            send.setAccessible(true);
            Object page = send.invoke(null, ((org.json.JSONObject) body.invoke(null, false)).put("browseId", albumId), false);
            if (page != null) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("OLAK5uy_[A-Za-z0-9_-]+").matcher(page.toString());
                if (m.find()) playlistId = m.group();
            }
        } catch (Throwable t) {
            Log.e(TAG, "album playlist id lookup failed", t);
        }
        final String id = playlistId;
        Log.i(TAG, "album " + albumId + " -> playlist " + id);
        main.post(() -> {
            if (id != null && saveOfflinePlaylist(id)) return;
            try {
                Log.i(TAG, "album " + albumId + ": native playlist download instead");
                Class.forName("app.morphe.extension.music.patches.downloads.PlaylistDownloader")
                        .getMethod("start", String.class).invoke(null, albumId);
            } catch (Throwable t) {
                Log.e(TAG, "album fallback failed", t);
            }
        });
    }

    static boolean saveOfflinePlaylist(String playlistId) {
        try {
            String name = routerMethod();
            Object target = router;
            if (name == null || target == null || commandClass == null) {
                Log.w(TAG, "offline playlist save: command router not captured yet");
                return false;
            }
            ProtoWire endpoint = new ProtoWire()
                    .setString(1, playlistId)
                    .setVarint(2, 1) // ACTION_ADD
                    .setMessage(4, new ProtoWire().setMessage(OFFLINEABILITY_RENDERER, new ProtoWire().setVarint(1, 1)));
            Object command = parse(commandClass, new ProtoWire().setMessage(OFFLINE_PLAYLIST_ENDPOINT, endpoint).toBytes());
            for (Method m : target.getClass().getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals(name) && p.length == 2 && p[0].isAssignableFrom(commandClass) && p[1] == java.util.Map.class) {
                    m.invoke(target, command, new java.util.HashMap<>());
                    Log.i(TAG, "offline playlist save of " + playlistId + " dispatched by YT's command router");
                    return true;
                }
            }
            Log.w(TAG, "offline playlist save: router method " + name + " not found");
            return false;
        } catch (Throwable t) {
            Log.e(TAG, "saveOfflinePlaylist failed", t);
            return false;
        }
    }

    /** Diagnostic: the command as a proto tree (field numbers, varints, strings, nested messages). */
    public static void dumpCommand(Object command) {
        try {
            if (command == null) return;
            StringBuilder out = new StringBuilder();
            tree(new ProtoWire(toBytes(command)), "", out, 0);
            for (String line : out.toString().split("\n")) Log.i(TAG, "cmd " + line);
        } catch (Throwable t) {
            Log.w(TAG, "dumpCommand failed", t);
        }
    }

    private static void tree(ProtoWire m, String indent, StringBuilder out, int depth) {
        for (ProtoWire.Field f : m.fields) {
            String text = f.data == null ? null : new String(f.data, java.nio.charset.StandardCharsets.UTF_8);
            boolean printable = text != null && text.matches("[ -~]{1,200}");
            ProtoWire child = null;
            if (f.data != null && !printable && depth < 6) {
                try { child = new ProtoWire(f.data); } catch (Throwable ignored) { }
            }
            if (f.data == null) out.append(indent).append(f.number).append(" = ").append(f.varint).append("\n");
            else if (printable) out.append(indent).append(f.number).append(" = '").append(text).append("'\n");
            else if (child != null && !child.fields.isEmpty()) {
                out.append(indent).append(f.number).append(" {\n");
                tree(child, indent + "  ", out, depth + 1);
                out.append(indent).append("}\n");
            } else out.append(indent).append(f.number).append(" = <").append(f.data.length).append(" bytes>\n");
        }
    }

    /** Diagnostic: the commands Morphe's download hooks see, as the printable ids they carry. */
    public static void logCommand(String where, Object command) {
        try {
            if (command == null) return;
            byte[] b = toBytes(command);
            String text = new String(b, java.nio.charset.StandardCharsets.ISO_8859_1);
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(VL|PL|OLAK5uy_|MPRE|RD|LM)[A-Za-z0-9_-]{0,60}|[A-Za-z0-9_-]{11}(?![A-Za-z0-9_-])").matcher(text);
            StringBuilder ids = new StringBuilder();
            while (m.find() && ids.length() < 400) ids.append(' ').append(m.group());
            StringBuilder fields = new StringBuilder();
            try {
                for (ProtoWire.Field f : new ProtoWire(b).fields) fields.append(' ').append(f.number);
            } catch (Throwable ignored) {
            }
            Log.i(TAG, where + ": " + b.length + " bytes, fields" + fields + ", ids" + ids);
        } catch (Throwable t) {
            Log.w(TAG, "logCommand failed", t);
        }
    }

    // ---- helpers ----

    private static void setKeepStreams(boolean keep) {
        try {
            Class.forName("app.morphe.extension.shared.spoof.SpoofVideoStreamsPatch")
                    .getMethod("setKeepStreamsOnThread", boolean.class).invoke(null, keep);
        } catch (Throwable t) {
            Log.e(TAG, "setKeepStreamsOnThread failed", t);
        }
    }

    private static Object field(Object owner, String name) throws Exception {
        Field f = owner.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(owner);
    }

    private static byte[] toBytes(Object message) throws Exception {
        return (byte[]) message.getClass().getMethod("toByteArray").invoke(message);
    }

    private static Object defaultInstance(Class<?> type) throws Exception {
        for (Field f : type.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == type) {
                f.setAccessible(true);
                return f.get(null);
            }
        }
        throw new IllegalStateException("no default instance in " + type);
    }

    /** Parses bytes into the app's own message class, with its (obfuscated) GeneratedMessageLite.parseFrom. */
    private static Object parse(Class<?> type, byte[] bytes) throws Exception {
        Object def = defaultInstance(type);
        // The generated registry knows the command extensions (browseEndpoint, offlinePlaylistEndpoint...);
        // without it they land in the unknown fields and no handler sees them.
        Class<?> registryType = Class.forName("com.google.protobuf.ExtensionRegistryLite");
        Object registry = registryType.getMethod("getGeneratedRegistry").invoke(null);
        for (Class<?> k = type.getSuperclass(); k != null; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (Modifier.isStatic(m.getModifiers()) && m.getName().equals("parseFrom")
                        && p.length == 3 && p[0] == k && p[1] == byte[].class && p[2] == registryType) {
                    m.setAccessible(true);
                    return m.invoke(null, def, bytes, registry);
                }
            }
        }
        throw new IllegalStateException("parseFrom not found for " + type);
    }
}
