package app.ayham.extension.music.features;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import app.ayham.extension.music.automix.Json;

/**
 * The shared lyrics cases (spec/lyrics/lyrics-cases.json, lyrics.md) run against the phone's strip.
 * Groups for the full lyrics view only are counted as skipped, with the reason.
 */
public class LyricsCasesTest {
    private static File dir() {
        for (File d = new File("").getAbsoluteFile(); d != null; d = d.getParentFile()) {
            File s = new File(d, "spec/lyrics");
            if (s.isDirectory()) return s;
        }
        throw new AssertionError("spec/lyrics not found above " + new File("").getAbsolutePath());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) {
        return (List<Object>) o;
    }

    private static long lng(Object o) {
        return ((Number) o).longValue();
    }

    @Test
    public void sharedCases() throws Exception {
        Map<String, Object> all = map(Json.parse(new String(Files.readAllBytes(new File(dir(), "lyrics-cases.json").toPath()), StandardCharsets.UTF_8)));
        StringBuilder report = new StringBuilder("lyrics cases:\n");
        List<String> fails = new ArrayList<>();
        int pass = 0, skip = 0;
        for (Map.Entry<String, Object> g : all.entrySet()) {
            if (g.getKey().equals("notes")) continue;
            int gp = 0, gs = 0;
            for (Object o : list(g.getValue())) {
                Map<String, Object> c = map(o), in = map(c.get("input"));
                Object want = c.get("expect");
                String got;
                boolean ok;
                switch (g.getKey()) {
                    case "currentLine": {
                        List<Object> s = list(in.get("starts"));
                        long[] st = new long[s.size()];
                        for (int i = 0; i < st.length; i++) st[i] = lng(s.get(i));
                        int r = LyricTiming.currentLine(st, lng(in.get("position")));
                        ok = r == lng(want);
                        got = String.valueOf(r);
                        break;
                    }
                    case "offset": {
                        long off = LyricTiming.offset(lng(in.get("song")), lng(in.get("global")));
                        long lyr = LyricTiming.lyricsPos(lng(in.get("player")), off);
                        long seek = LyricTiming.seekFor(in.containsKey("lyricTime") ? lng(in.get("lyricTime")) : lyr, off);
                        Map<String, Object> w = map(want);
                        ok = off == lng(w.get("offset")) && lyr == lng(w.get("lyrics")) && seek == lng(w.get("seekTo"));
                        got = off + "/" + lyr + "/" + seek;
                        break;
                    }
                    case "gaps": {
                        List<Object> ls = list(in.get("lines"));
                        long[] st = new long[ls.size()], en = new long[ls.size()];
                        for (int i = 0; i < st.length; i++) {
                            st[i] = lng(map(ls.get(i)).get("start"));
                            en[i] = lng(map(ls.get(i)).get("end"));
                        }
                        List<long[]> r = LyricTiming.gaps(st, en);
                        StringBuilder b = new StringBuilder("[");
                        for (long[] x : r) b.append(b.length() > 1 ? "," : "").append("[").append(x[0]).append(",").append(x[1]).append("]");
                        got = b.append("]").toString();
                        ok = got.equals(Json.write(want).replace(" ", ""));
                        break;
                    }
                    default:
                        gs++;
                        continue; // sides: the full view's (the strip has one line, lyrics.md §5.5)
                }
                if (ok) gp++;
                else fails.add(g.getKey() + " '" + c.get("name") + "': got " + got + ", expected " + Json.write(want));
            }
            pass += gp;
            skip += gs;
            report.append(String.format("  %-12s %d pass, %d skipped%n", g.getKey(), gp, gs));
        }
        report.append("  total ").append(pass).append(" pass, ").append(skip).append(" skipped, ").append(fails.size()).append(" failed\n");
        for (String f : fails) report.append("  FAIL ").append(f).append('\n');
        System.out.println(report);
        assertTrue(report.toString(), fails.isEmpty());
    }
}
