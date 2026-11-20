package app.ayham.extension.music.addons;

import android.content.Context;
import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An addon's audio, streamed (BitChord's AudioCache): read in 1 MB ranges as the player or the
 * fit asks for them, kept on disk across plays (a sparse file and which of its chunks are in), and
 * trimmed oldest first past MAX_BYTES. Nothing fetches the whole file: only what is read is fetched.
 */
public final class StreamCache {
    private static final String TAG = "AyhamAddons";
    public static final int CHUNK = 1 << 20;
    static final long MAX_BYTES = 512L << 20;
    private static final int MAGIC = 0x41594331;   // "AYC1"
    private static final Map<String, Entry> open = new HashMap<>();
    /** Network fetches, off the callers' threads (Entry.chunkUninterrupted). */
    private static final java.util.concurrent.ExecutorService FETCH = java.util.concurrent.Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "addon-stream-fetch");
        t.setDaemon(true);
        return t;
    });

    private StreamCache() {
    }

    static File dir(Context c) {
        File d = new File(c.getCacheDir(), "addons/stream");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** The cache key of a row: the addon's host and the row (the same row is the same file). */
    public static String key(String host, String rowId) {
        String k = (host == null ? "" : host) + "_" + (rowId == null ? "" : rowId);
        return k.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** One file being streamed: its bytes, the chunks in, and where to fetch the rest. */
    public static final class Entry {
        public final String key;
        final File data, meta;
        private long length = -1;
        private BitSet have = new BitSet();
        private volatile String url;
        private volatile Map<String, String> headers = new HashMap<>();
        private int users;
        /** Bytes fetched from the network for this entry since it was opened (for the log). */
        public volatile long fetched;

        Entry(File dir, String key) {
            this.key = key;
            data = new File(dir, key + ".bin");
            meta = new File(dir, key + ".meta");
            load();
        }

        /** Where the missing chunks come from (a fresh /stream answer). */
        private volatile long sourcedAt;
        /** Asks the addon for a new link when the one held stops working (null: none). */
        public volatile Refresh refresh;

        public void source(String url, Map<String, String> headers) {
            sourcedAt = android.os.SystemClock.elapsedRealtime();
            this.url = url;
            this.headers = headers == null ? new HashMap<>() : new HashMap<>(headers);
        }

        public long length() throws IOException {
            synchronized (this) {
                if (length >= 0) return length;
            }
            chunkUninterrupted(0);   // the first range's Content-Range says it
            synchronized (this) {
                return length;
            }
        }

        /** Whether the chunks covering [from, to) are all on disk. */
        public synchronized boolean has(long from, long to) {
            if (length >= 0) to = Math.min(to, length);
            for (long c = from / CHUNK; c * CHUNK < to; c++) if (!have.get((int) c)) return false;
            return true;
        }

        /** Fetches the chunks covering [from, to) that are missing. */
        public void warm(long from, long to) throws IOException {
            for (long c = from / CHUNK; c * CHUNK < to; c++) {
                chunk((int) c);
                if (length >= 0 && (c + 1) * CHUNK >= length) break;
            }
        }

        /** Up to len bytes at pos, fetching their chunk when it is missing; -1 at the end. */
        public int read(long pos, byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            int c = (int) (pos / CHUNK);
            chunkUninterrupted(c);
            long end;
            synchronized (this) {
                if (length >= 0 && pos >= length) return -1;
                end = Math.min((long) (c + 1) * CHUNK, length >= 0 ? length : Long.MAX_VALUE);
            }
            int n = (int) Math.min(len, end - pos);
            try (RandomAccessFile f = new RandomAccessFile(data, "r")) {
                f.seek(pos);
                int got = f.read(b, off, n);
                return got <= 0 ? -1 : got;
            }
        }

        /**
         * chunk(c) on the fetch thread, waited for whatever interrupts the caller: media3 interrupts a
         * load it cancels (a seek), and an interrupted fetch reached it as an undeclared exception
         * through the DataSource proxy, which stalled the player (the phone, 2026-11-20). A cancelled
         * load gets its chunk and is dropped by the player as it would drop any.
         */
        private void chunkUninterrupted(int c) throws IOException {
            synchronized (this) {
                if (have.get(c)) return;
            }
            java.util.concurrent.Future<?> f = FETCH.submit(() -> {
                chunk(c);
                return null;
            });
            boolean interrupted = false;
            try {
                while (true) {
                    try {
                        f.get();
                        return;
                    } catch (InterruptedException e) {
                        interrupted = true;
                    } catch (java.util.concurrent.ExecutionException e) {
                        Throwable t = e.getCause();
                        if (t instanceof IOException) throw (IOException) t;
                        throw new IOException(t);
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }

        private synchronized void chunk(int c) throws IOException {
            if (have.get(c)) return;
            if (length >= 0 && (long) c * CHUNK >= length) return;
            try {
                fetch(c);
            } catch (Expired x) {
                Refresh r = refresh;
                if (r == null) throw x;
                String[] again;
                try {
                    again = r.again(this);
                } catch (Exception e) {
                    throw new IOException(x.getMessage() + "; a new link failed: " + Protocol.scrub(String.valueOf(e)));
                }
                if (again == null || again[0] == null) throw x;
                Log.i(TAG, "stream " + key + ": " + x.getMessage() + ": a new link from the addon");
                Map<String, String> h = new HashMap<>();
                for (int i = 1; i + 1 < again.length; i += 2) h.put(again[i], again[i + 1]);
                source(again[0], h);
                fetch(c);
            }
        }

        /** The link answered 4xx: it expired (the addon gives a new one). */
        static final class Expired extends IOException {
            Expired(String m) {
                super(m);
            }
        }

        private void fetch(int c) throws IOException {
            String u = url;
            if (u == null) throw new IOException("no stream for the missing part");
            long from = (long) c * CHUNK, to = from + CHUNK - 1;
            HttpURLConnection h = HttpTransport.open(Protocol.audio(u, headers, "bytes=" + from + "-" + to));
            h.setConnectTimeout(15_000);
            h.setReadTimeout(20_000);
            try {
                int code = h.getResponseCode();
                if (code != 206 && !(code == 200 && from == 0)) {
                    String m = "audio: HTTP " + code + " for chunk " + c + " (bytes " + from + "-" + to + " of " + length + ", "
                            + have.cardinality() + " chunks in, the link " + (android.os.SystemClock.elapsedRealtime() - sourcedAt) / 1000 + " s old)";
                    // the phone, 2026-11-20: NullBeat's links answered 400 once about a minute old
                    throw code >= 400 && code < 500 ? new Expired(m) : new IOException(m);
                }
                if (code == 206) {
                    String cr = h.getHeaderField("Content-Range");   // bytes a-b/total
                    int slash = cr == null ? -1 : cr.lastIndexOf('/');
                    if (slash > 0 && !cr.endsWith("*")) length = Long.parseLong(cr.substring(slash + 1).trim());
                } else {
                    // a server that ignores ranges sends it all: read just this chunk of it
                    long l = h.getContentLengthLong();
                    if (l > 0) length = l;
                }
                byte[] buf = new byte[64 * 1024];
                long want = length >= 0 ? Math.min(CHUNK, length - from) : CHUNK;
                long got = 0;
                try (InputStream in = h.getInputStream(); RandomAccessFile f = new RandomAccessFile(data, "rw")) {
                    f.seek(from);
                    while (got < want) {
                        int n = in.read(buf, 0, (int) Math.min(buf.length, want - got));
                        if (n < 0) break;
                        f.write(buf, 0, n);
                        got += n;
                    }
                }
                if (got < want) {
                    if (length < 0) length = from + got;   // the end came first
                    else throw new IOException("audio: the range ended early");
                }
                fetched += got;
                have.set(c);
                save();
            } finally {
                h.disconnect();
            }
        }

        private void load() {
            if (!meta.exists() || !data.exists()) return;
            try (DataInputStream in = new DataInputStream(new FileInputStream(meta))) {
                if (in.readInt() != MAGIC) return;
                length = in.readLong();
                int n = in.readInt();
                byte[] bits = new byte[n];
                in.readFully(bits);
                have = BitSet.valueOf(bits);
            } catch (IOException e) {
                length = -1;
                have = new BitSet();
            }
        }

        private void save() throws IOException {
            File tmp = new File(meta.getPath() + ".part");
            try (DataOutputStream out = new DataOutputStream(new FileOutputStream(tmp))) {
                byte[] bits = have.toByteArray();
                out.writeInt(MAGIC);
                out.writeLong(length);
                out.writeInt(bits.length);
                out.write(bits);
            }
            if (!tmp.renameTo(meta)) throw new IOException("cache: could not keep the chunk list");
            //noinspection ResultOfMethodCallIgnored
            data.setLastModified(System.currentTimeMillis());
        }
    }

    /** A fresh link for an entry (AddonClient.streamAgain): its url and headers, or null. */
    public interface Refresh {
        String[] again(Entry e) throws Exception;
    }

    /** The entry for key, opened for use (release it when done). */
    public static synchronized Entry open(Context c, String key) {
        Entry e = open.get(key);
        if (e == null) {
            trim(c);
            e = new Entry(dir(c), key);
            open.put(key, e);
        }
        e.users++;
        return e;
    }

    public static synchronized void release(Entry e) {
        if (e == null || --e.users > 0) return;
        open.remove(e.key);
        Log.i(TAG, "stream " + e.key + " closed: " + e.fetched / 1024 + " KB from the network while open, " + e.have.cardinality() + " of "
                + (e.length < 0 ? "?" : String.valueOf((e.length + CHUNK - 1) / CHUNK)) + " chunks on disk");
    }

    /**
     * "Upgrade quality" (protocol.md 8.1.7): the kept files of these rows go, or the replay would
     * bring the same file back. An entry in use stays. Returns how many went.
     */
    public static synchronized int drop(Context c, java.util.Collection<String> keyPrefixes) {
        File[] fs = dir(c).listFiles((d, n) -> n.endsWith(".bin"));
        if (fs == null) return 0;
        int n = 0;
        for (File f : fs) {
            String key = f.getName().substring(0, f.getName().length() - 4);
            boolean ours = false;
            for (String k : keyPrefixes) if (key.startsWith(k)) ours = true;
            if (!ours || open.containsKey(key)) continue;
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            //noinspection ResultOfMethodCallIgnored
            new File(f.getParentFile(), key + ".meta").delete();
            n++;
        }
        return n;
    }

    /** Oldest first, past MAX_BYTES; entries in use stay. */
    static synchronized void trim(Context c) {
        File[] fs = dir(c).listFiles((d, n) -> n.endsWith(".bin"));
        if (fs == null) return;
        long total = 0;
        for (File f : fs) total += f.length();
        if (total <= MAX_BYTES) return;
        List<File> l = new ArrayList<>(java.util.Arrays.asList(fs));
        l.sort((a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : l) {
            if (total <= MAX_BYTES) break;
            String key = f.getName().substring(0, f.getName().length() - 4);
            if (open.containsKey(key)) continue;
            total -= f.length();
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            //noinspection ResultOfMethodCallIgnored
            new File(f.getParentFile(), key + ".meta").delete();
            Log.i(TAG, "stream cache: dropped " + key);
        }
    }

    /** MediaExtractor's view of an entry: it reads only what it seeks to. */
    public static final class Source extends android.media.MediaDataSource {
        private final Entry e;

        public Source(Entry e) {
            this.e = e;
        }

        @Override
        public int readAt(long position, byte[] buffer, int offset, int size) throws IOException {
            if (size == 0) return 0;
            int total = 0;
            while (total < size) {
                int n = e.read(position + total, buffer, offset + total, size - total);
                if (n < 0) return total == 0 ? -1 : total;
                total += n;
            }
            return total;
        }

        @Override
        public long getSize() throws IOException {
            return e.length();
        }

        @Override
        public void close() {
        }
    }
}
