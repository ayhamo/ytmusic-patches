package app.ayham.extension.music.addons;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The addon protocol's pure readings and request shapes (spec/addons/protocol.md): base URLs,
 * the manifest, the query parameters, the /stream answer, and the headers every call sends.
 * A port of the pure parts of the extension's src/addons/client.js. No I/O here.
 */
public final class Protocol {
    private Protocol() {
    }

    /**
     * The one User-Agent for every addon call and the audio file (the user's call): a browser's,
     * never BitChord's (an addon developer blocks every agent that says "bitchord").
     */
    public static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:159.0) Gecko/20100101 Firefox/159.0";

    public static final int TIMEOUT_MS = 45000;
    public static final int PROBE_TIMEOUT_MS = 4000;
    public static final String NEEDS_OFFICIAL = "needs official BitChord";

    // ---- links ----

    /** A link taken apart the way the WHATWG URL parser does for http(s): enough to compare and redact. */
    static final class Url {
        String scheme;
        String host;   // lower case, with a non-default port
        String path;   // "/" when empty; no query or fragment
    }

    private static final Pattern SCHEME = Pattern.compile("[a-zA-Z][a-zA-Z0-9+.-]*");

    static Url parse(String u) {
        if (u == null) return null;
        String s = Js.trim(u);
        int c = s.indexOf("://");
        if (c <= 0 || !SCHEME.matcher(s.substring(0, c)).matches()) return null;
        Url out = new Url();
        out.scheme = s.substring(0, c).toLowerCase(Locale.ROOT);
        String rest = s.substring(c + 3);
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char ch = rest.charAt(i);
            if (ch == '/' || ch == '?' || ch == '#' || ch == '\\') {
                end = i;
                break;
            }
        }
        String authority = rest.substring(0, end);
        int at = authority.lastIndexOf('@');
        if (at >= 0) authority = authority.substring(at + 1);
        String host = authority, port = "";
        int colon = authority.lastIndexOf(':');
        if (colon >= 0 && authority.indexOf(']', colon) < 0) {
            host = authority.substring(0, colon);
            port = authority.substring(colon + 1);
            if (!port.matches("[0-9]*")) return null;
            if (!port.isEmpty()) port = String.valueOf(Integer.parseInt(port));
        }
        host = host.toLowerCase(Locale.ROOT);
        if (host.isEmpty()) return null;
        if ((out.scheme.equals("https") && port.equals("443")) || (out.scheme.equals("http") && port.equals("80"))) port = "";
        out.host = port.isEmpty() ? host : host + ":" + port;
        String path = rest.substring(end);
        int q = path.length();
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '?' || path.charAt(i) == '#') {
                q = i;
                break;
            }
        }
        path = path.substring(0, q).replace('\\', '/');
        out.path = path.isEmpty() ? "/" : path;
        return out;
    }

    private static final Pattern TRAILING_SLASHES = Pattern.compile("/+$");
    private static final Pattern JSON_SEGMENT = Pattern.compile("/[^/]*\\.json$", Pattern.CASE_INSENSITIVE);

    /** §2: trimmed, no trailing '/', no final *.json segment; token path segments kept. */
    public static String baseOf(String url) {
        String u = TRAILING_SLASHES.matcher(Js.trim(Js.text(url))).replaceAll("");
        u = JSON_SEGMENT.matcher(u).replaceAll("");
        return TRAILING_SLASHES.matcher(u).replaceAll("");
    }

    /** §2: the same addon when scheme and host match case-insensitively and the path exactly. */
    public static boolean sameAddon(String a, String b) {
        Url x = parse(baseOf(a)), y = parse(baseOf(b));
        return x != null && y != null && x.scheme.equals(y.scheme) && x.host.equals(y.host) && x.path.equals(y.path);
    }

    /** A link as its host only: links hold their owner's token. */
    public static String redact(String url) {
        Url u = parse(url);
        return u == null ? "(addon)" : u.host;
    }

    private static final Pattern ANY_URL = Pattern.compile("https?://[^" + Js.WS + "'\"<>)]+", Pattern.CASE_INSENSITIVE);

    /** Every link inside a text (an error message, a log line) as its host. */
    public static String scrub(String text) {
        return Js.replace(Js.text(text), ANY_URL, m -> redact(m.group()));
    }

    /**
     * Whether the phone may call this link: https only. Plain http to the device itself
     * (localhost, 127.0.0.1) is only for a debug mock running on the phone, and only when the
     * client is built with allowLocalHttp (the release and the tests never are): on the phone
     * "localhost" is the phone, not the PC, and Android blocks cleartext anyway.
     */
    public static boolean allowed(String url, boolean allowLocalHttp) {
        Url u = parse(url);
        if (u == null) return false;
        if (u.scheme.equals("https")) return true;
        if (!allowLocalHttp || !u.scheme.equals("http")) return false;
        String h = u.host.replaceFirst(":[0-9]+$", "");
        return h.equals("localhost") || h.equals("127.0.0.1");
    }

    // ---- manifest (§2) ----

    public static final class Option {
        public final Object value;
        public final String label;

        Option(Object value, String label) {
            this.value = value;
            this.label = label;
        }
    }

    public static final class Setting {
        public final String key;
        public final Object def;
        public final List<Option> options;

        Setting(String key, Object def, List<Option> options) {
            this.key = key;
            this.def = def;
            this.options = options;
        }
    }

    /** §4a: the /stream budget (BitChord fork's manifest field), defaults 5 a minute, 60 uncached an hour. */
    public static final class Limits {
        public final int streamsPerMinute;
        public final int uncachedPerHour;
        public final String cachedMarker;

        public Limits(int streamsPerMinute, int uncachedPerHour, String cachedMarker) {
            this.streamsPerMinute = streamsPerMinute;
            this.uncachedPerHour = uncachedPerHour;
            this.cachedMarker = cachedMarker;
        }
    }

    public static final Limits DEFAULT_LIMITS = new Limits(5, 60, "cached");

    public static final class Manifest {
        public String id;
        public String name;
        public String version;
        public List<String> resources = new ArrayList<>();
        /** Resources empty or holding "search". */
        public boolean usable;
        public List<Setting> settings = new ArrayList<>();
        /** false: never used for downloads. */
        public boolean allowDownloads;
        /** bitchordAuth: needs the official, signed BitChord app; unsupported. */
        public boolean needsOfficial;
        public Limits limits = DEFAULT_LIMITS;
    }

    /** A manifest read leniently, or null when it isn't one (no id). */
    @SuppressWarnings("unchecked")
    public static Manifest readManifest(Object json) {
        if (!(json instanceof Map)) return null;
        Map<String, Object> j = (Map<String, Object>) json;
        if (Js.blank(Js.text(j.get("id")))) return null;
        Manifest m = new Manifest();
        m.id = Js.text(j.get("id"));
        m.name = Js.text(j.get("name")).isEmpty() ? m.id : Js.text(j.get("name"));
        m.version = Js.text(j.get("version"));
        if (j.get("resources") instanceof List) {
            for (Object r : (List<Object>) j.get("resources")) m.resources.add(Js.text(r).toLowerCase(Locale.ROOT));
        }
        m.usable = m.resources.isEmpty() || m.resources.contains("search");
        m.settings = readSettings(j.get("settings"));
        m.allowDownloads = Js.flag(j.get("allowDownloads"), true);
        m.needsOfficial = Js.flag(j.get("bitchordAuth"), false);
        m.limits = readLimits(j.get("limits"));
        return m;
    }

    /** The settings array: entries with a key, options labelled by their value when unlabelled. */
    @SuppressWarnings("unchecked")
    public static List<Setting> readSettings(Object settings) {
        List<Setting> out = new ArrayList<>();
        if (!(settings instanceof List)) return out;
        for (Object o : (List<Object>) settings) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> s = (Map<String, Object>) o;
            if (Js.text(s.get("key")).isEmpty()) continue;
            List<Option> options = new ArrayList<>();
            if (s.get("options") instanceof List) {
                for (Object x : (List<Object>) s.get("options")) {
                    Object value = x instanceof Map ? ((Map<String, Object>) x).get("value") : null;
                    String label = x instanceof Map ? Js.text(((Map<String, Object>) x).get("label")) : "";
                    options.add(new Option(value, label.isEmpty() ? Js.text(value) : label));
                }
            }
            out.add(new Setting(Js.text(s.get("key")), s.get("default"), options));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public static Limits readLimits(Object limits) {
        Map<String, Object> l = limits instanceof Map ? (Map<String, Object>) limits : Collections.<String, Object>emptyMap();
        String marker = Js.trim(Js.text(l.get("cachedMarker")));
        return new Limits(positive(l.get("streamsPerMinute"), DEFAULT_LIMITS.streamsPerMinute),
                positive(l.get("uncachedPerHour"), DEFAULT_LIMITS.uncachedPerHour),
                marker.isEmpty() ? DEFAULT_LIMITS.cachedMarker : marker);
    }

    private static int positive(Object v, int def) {
        Double n = Js.num(v);
        return n != null && n >= 1 ? (int) Math.min(Integer.MAX_VALUE, Math.floor(n)) : def;
    }

    /** §2 discovery: a body with a resources array, or an id plus a name or version. */
    @SuppressWarnings("unchecked")
    public static boolean looksLikeManifest(Object json) {
        if (!(json instanceof Map)) return false;
        Map<String, Object> j = (Map<String, Object>) json;
        return j.get("resources") instanceof List
                || (!Js.text(j.get("id")).isEmpty() && (j.get("name") != null || j.get("version") != null));
    }

    // ---- query parameters (§1) ----

    private static final Pattern LOW_QUALITY = Pattern.compile("low|96|128|min", Pattern.CASE_INSENSITIVE);
    private static final Pattern HIGH_QUALITY = Pattern.compile("high|320|normal|standard", Pattern.CASE_INSENSITIVE);

    /**
     * §1: each declared setting's default in order, atmos=false in place of a declared atmos
     * setting (ours: stereo only), then quality: the tier's option (or the tier name).
     */
    public static List<String[]> paramsFor(List<Setting> settings, String tier, String picked) {
        if (tier == null) tier = Pick.LOSSLESS;
        List<String[]> out = new ArrayList<>();
        Setting q = null;
        for (Setting s : settings) {
            if (s.key.equals("quality")) {
                if (q == null) q = s;
                continue;
            }
            if (s.key.toLowerCase(Locale.ROOT).equals("atmos")) {
                out.add(new String[]{"atmos", "false"});
                continue;
            }
            if (s.def != null) out.add(new String[]{s.key, Js.text(s.def)});
        }
        String quality = tier;
        if (q != null && !q.options.isEmpty()) {
            List<String> vals = new ArrayList<>();
            for (Option o : q.options) vals.add(Js.text(o.value));
            if (tier.equals(Pick.LOSSLESS)) {
                quality = picked != null && vals.contains(picked) ? picked : vals.get(0);
            } else {
                Pattern re = tier.equals(Pick.LOW) ? LOW_QUALITY : HIGH_QUALITY;
                quality = null;
                for (String v : vals) {
                    if (v.toUpperCase(Locale.ROOT).equals(tier)) {
                        quality = v;
                        break;
                    }
                }
                for (int i = 0; quality == null && i < vals.size(); i++) {
                    if (re.matcher(vals.get(i)).find() || re.matcher(q.options.get(i).label).find()) quality = vals.get(i);
                }
                if (quality == null) quality = vals.get(0);
            }
        }
        out.add(new String[]{"quality", quality});
        return out;
    }

    public static List<String[]> paramsFor(Manifest m, String tier, String picked) {
        return paramsFor(m == null ? new ArrayList<Setting>() : m.settings, tier, picked);
    }

    /** k=v pairs percent-encoded as encodeURIComponent does, joined by '&'. */
    public static String qs(List<String[]> pairs) {
        StringBuilder b = new StringBuilder();
        for (String[] p : pairs) {
            if (b.length() > 0) b.append('&');
            b.append(Js.encode(p[0])).append('=').append(Js.encode(p[1]));
        }
        return b.toString();
    }

    public static String manifestUrl(String base) {
        return base + "/manifest.json";
    }

    /** §3: settings, then the song's raw title and artist (when known), then the trimmed query (when not blank). */
    public static String searchUrl(String base, List<String[]> params, TrackMatcher.Target target, String query) {
        List<String[]> all = new ArrayList<>(params);
        if (target != null && target.title != null && !target.title.isEmpty()) all.add(new String[]{"title", target.title});
        if (target != null && target.artist != null && !target.artist.isEmpty()) all.add(new String[]{"artist", target.artist});
        String q = Js.trim(Js.text(query));
        if (!q.isEmpty()) all.add(new String[]{"q", q});
        return base + "/search?" + qs(all);
    }

    /** §4: the track id percent-encoded as one path segment. */
    public static String streamUrl(String base, String trackId, List<String[]> params) {
        return base + "/stream/" + Js.encode(trackId) + "?" + qs(params);
    }

    // ---- requests (§1) ----

    /** One GET: its link, headers in the order sent, and the whole-call timeout. */
    public static final class Request {
        public final String url;
        public final Map<String, String> headers;
        public final int timeoutMs;
        /** false for the probe: only the status and headers are read. */
        public final boolean readBody;

        Request(String url, Map<String, String> headers, int timeoutMs, boolean readBody) {
            this.url = url;
            this.headers = Collections.unmodifiableMap(headers);
            this.timeoutMs = timeoutMs;
            this.readBody = readBody;
        }
    }

    /** A manifest, search or stream call: Accept: application/json and the browser User-Agent, nothing else. */
    public static Request api(String url) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Accept", "application/json");
        h.put("User-Agent", USER_AGENT);
        return new Request(url, h, TIMEOUT_MS, true);
    }

    /**
     * The audio file: the headers the /stream answer supplied, plus Range, Accept-Encoding:
     * identity (a compressed answer's sizes would be the compressed ones) and our User-Agent,
     * which replaces any the addon sent. range may be null (the whole file).
     */
    public static Request audio(String url, Map<String, String> streamHeaders, String range) {
        Map<String, String> h = new LinkedHashMap<>();
        if (streamHeaders != null) {
            for (Map.Entry<String, String> e : streamHeaders.entrySet()) {
                String k = e.getKey().toLowerCase(Locale.ROOT);
                if (k.equals("user-agent") || k.equals("range") || k.equals("accept-encoding")) continue;
                h.put(e.getKey(), e.getValue());
            }
        }
        if (range != null) h.put("Range", range);
        h.put("Accept-Encoding", "identity");
        h.put("User-Agent", USER_AGENT);
        return new Request(url, h, TIMEOUT_MS, true);
    }

    /** §7: the preview check, Range bytes=0-0 with a 4 s timeout; only the headers are read. */
    public static Request probe(String url, Map<String, String> streamHeaders) {
        Request a = audio(url, streamHeaders, "bytes=0-0");
        return new Request(url, new LinkedHashMap<>(a.headers), PROBE_TIMEOUT_MS, false);
    }

    // ---- the /stream answer (§4) ----

    public static final class Stream {
        /** Set when the answer is a miss (no url, an error, encrypted, Atmos); the rest is then empty. */
        public String miss;
        public String url;
        public String codec;
        /** "file", "hls" or "dash". */
        public String transport;
        public String tier;
        public Double sampleRate;
        public Double bitDepth;
        /** kbps. */
        public Double bitrate;
        public String mimeType;
        public Map<String, String> headers = new LinkedHashMap<>();
        /** The cached marker's flag: it came from the service's cache (no uncached cost). */
        public boolean cached;

        static Stream miss(String why) {
            Stream s = new Stream();
            s.miss = why;
            return s;
        }
    }

    private static final Pattern HLS = Pattern.compile("hls|m3u8|mpegurl", Pattern.CASE_INSENSITIVE);
    private static final Pattern DASH = Pattern.compile("dash|mpd|dash\\+xml", Pattern.CASE_INSENSITIVE);
    private static final Pattern KHZ = Pattern.compile("([0-9.]+)[" + Js.WS + "]*kHz", Pattern.CASE_INSENSITIVE);
    private static final Pattern BITS = Pattern.compile("([0-9]{1,2})[" + Js.WS + "]*-?bit", Pattern.CASE_INSENSITIVE);
    private static final Pattern KBPS = Pattern.compile("([0-9]{2,4})[" + Js.WS + "]*kbps", Pattern.CASE_INSENSITIVE);
    private static final Pattern AUDIO_EXT = Pattern.compile("\\.(flac|mp3|m4a|aac|ogg|oga|opus|wav|aiff?|alac)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTAINER = Pattern.compile("flac|wav|mp3|aiff");
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");

    private static Object first(Map<String, Object> j, String... keys) {
        for (String k : keys) if (j.get(k) != null) return j.get(k);
        return null;
    }

    /** §4: the stream answer, read leniently. marker: the manifest's cachedMarker. */
    @SuppressWarnings("unchecked")
    public static Stream readStream(Object json, String marker) {
        Map<String, Object> j = json instanceof Map ? (Map<String, Object>) json : Collections.<String, Object>emptyMap();
        if (marker == null) marker = DEFAULT_LIMITS.cachedMarker;
        String url = Js.trim(Js.text(j.get("url")));
        if (url.isEmpty() || Js.truthy(j.get("error"))) {
            String e = Js.text(j.get("error"));
            return Stream.miss(e.isEmpty() ? "no url" : e);
        }
        Object enc = j.get("encrypted");
        String encText = Js.text(enc);
        if (!(Boolean.FALSE.equals(enc) || enc == null || Js.trim(encText).isEmpty()
                || encText.toLowerCase(Locale.ROOT).equals("none") || encText.toLowerCase(Locale.ROOT).equals("false"))) {
            return Stream.miss("encrypted");
        }
        String quality = Js.text(first(j, "quality", "streamQuality", "audioQuality"));
        StringBuilder all = new StringBuilder(url);
        for (Object v : new Object[]{j.get("format"), quality, j.get("codec"), j.get("fileCodec"), j.get("container"),
                j.get("containerFormat"), j.get("mimeType"), j.get("manifest"), j.get("mediaType"), j.get("audioMode")}) {
            all.append(' ').append(Js.text(v));
        }
        String allText = all.toString();
        String atmosText = Js.text(j.get("codec")) + " " + Js.text(j.get("audioMode")) + " " + Js.text(j.get("format")) + " " + quality;
        if (Pick.ATMOS.matcher(atmosText).find()) return Stream.miss("atmos");

        Stream s = new Stream();
        s.url = url;
        s.transport = HLS.matcher(allText).find() ? "hls" : DASH.matcher(allText).find() ? "dash" : "file";
        Double sampleRate = Js.num(j.get("sampleRate"));
        if (sampleRate == null) sampleRate = Js.num(Js.group1(KHZ, allText));
        if (sampleRate != null && sampleRate < 1000) sampleRate *= 1000;
        s.sampleRate = sampleRate;
        Double bitDepth = Js.num(j.get("bitDepth"));
        s.bitDepth = bitDepth != null ? bitDepth : Js.num(Js.group1(BITS, allText));
        s.tier = Pick.tierOf(Js.text(j.get("format")) + " " + quality + " " + Js.text(j.get("codec")));
        Double bitrate = Js.num(j.get("bitrate"));
        if (bitrate == null) bitrate = Js.num(Js.group1(KBPS, allText));
        if (bitrate != null && bitrate > 3000) bitrate /= 1000;
        if (bitrate == null) bitrate = Pick.HIGH.equals(s.tier) ? Double.valueOf(320) : Pick.LOW.equals(s.tier) ? Double.valueOf(128) : null;
        s.bitrate = bitrate;

        // codec, first that applies: stated codec, container, MIME subtype, flac when lossless, an audio extension
        Object codecValue = Js.truthy(j.get("codec")) ? j.get("codec") : j.get("fileCodec");
        String codec = Js.text(codecValue).toLowerCase(Locale.ROOT);
        if (codec.isEmpty()) {
            Object container = Js.truthy(j.get("container")) ? j.get("container") : j.get("containerFormat");
            Matcher cm = CONTAINER.matcher(Js.text(container).toLowerCase(Locale.ROOT));
            codec = cm.find() ? cm.group() : "";
        }
        if (codec.isEmpty()) {
            String mime = Js.text(j.get("mimeType"));
            String[] parts = mime.split("/", -1);
            codec = parts.length > 1 ? parts[1].toLowerCase(Locale.ROOT) : "";
        }
        if (codec.isEmpty() && Pick.LOSSLESS.equals(s.tier)) codec = "flac";
        if (codec.isEmpty()) {
            Url u = parse(url);
            Matcher em = u == null ? null : AUDIO_EXT.matcher(u.path);
            codec = em != null && em.find() ? em.group(1).toLowerCase(Locale.ROOT) : "";
        }
        s.codec = codec.isEmpty() ? null : codec;
        String mime = Js.text(j.get("mimeType"));
        s.mimeType = mime.isEmpty() ? null : mime;

        // the headers the addon wants on the file request: valid names, string or number values
        if (j.get("headers") instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) j.get("headers")).entrySet()) {
                Object v = e.getValue();
                if (HEADER_NAME.matcher(e.getKey()).matches() && (v instanceof String || v instanceof Number)) {
                    s.headers.put(e.getKey(), Js.string(v));
                }
            }
        }
        s.cached = Js.flag(j.get(marker), false);
        return s;
    }

    /** §8: downloads take lossless, or a lossy file over 256 kbps. */
    public static boolean goodForDownload(Stream s) {
        return Pick.LOSSLESS.equals(s.tier) || (s.bitrate != null && s.bitrate > 256);
    }
}
