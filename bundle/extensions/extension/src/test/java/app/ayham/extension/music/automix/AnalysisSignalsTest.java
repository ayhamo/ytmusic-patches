package app.ayham.extension.music.automix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * The Java port of the extension's scripts/analysis_signals.mjs renders the same samples. The sums are
 * the JS renderer's (node 26, 2026-11-19): SHA-256 of each song's Float32Array (little-endian bytes)
 * at 44100 and 22050 Hz. A change to analysis_signals.mjs or to a song of analysis-cases.json
 * shows up here; take the new sums from the JS side, never from this port.
 */
public class AnalysisSignalsTest {
    private static final Map<String, String> JS = new HashMap<>();

    static {
        JS.put("click-120@44100", "1310158703e515fd0895a22c487a7fd2617803f1389e1c385c2ad92ca727a2c9");
        JS.put("click-120@22050", "185acc517bd09cb4ecefae70f647392a2173fad1fa131854a10b7002b01deb53");
        JS.put("drums-128-late-start@44100", "d3751cde758e4e6c5cd407cbc33a19553d8a7367e179ec8237e6a557345d93bf");
        JS.put("drums-128-late-start@22050", "e1832d4318af8e8670d5392db70a70677fbed57ede9c120bf96976cc7a6245d1");
        JS.put("drums-174@44100", "02a631d12f86edfd9736df5317e9b376fe2448a7041ec280f8162b67dc7cbb35");
        JS.put("drums-174@22050", "e5172b10720613cd87ec095fa4f02085901b35bf9730a7328feb6a32f49932a3");
        JS.put("drums-85@44100", "81b9c91ca1351009aa40f002602ee435c219396180506df0e25cabe59c78e24f");
        JS.put("drums-85@22050", "47b0c951a301156f76d1fed4387c5d61d166de636cc884a3091b8c1f5f810a13");
        JS.put("tempo-change-100-110@44100", "184ff981542b00ce02ef88ac7aacffe7c01d7ead748ccd3dd717535af012e2ec");
        JS.put("tempo-change-100-110@22050", "8ae835420a202d75544d6f4dd8ecd987abc17f0d8b25f87453f8770ff718882f");
        JS.put("voice-section@44100", "6d0d07d83ee69aeccd9a396571a941e1996107cf569959f7039e949c622b1f09");
        JS.put("voice-section@22050", "9583279e20c1825b801b6a1ec79716cc0480cac419ceffd33ec26d91e12e26bc");
        JS.put("no-voice@44100", "d3751cde758e4e6c5cd407cbc33a19553d8a7367e179ec8237e6a557345d93bf");
        JS.put("no-voice@22050", "e1832d4318af8e8670d5392db70a70677fbed57ede9c120bf96976cc7a6245d1");
        JS.put("voice-over-chord@44100", "1345a064f34c88a917f9fae38f184a0b6b38ef1088ee8f06d3b578da8a7e1f98");
        JS.put("voice-over-chord@22050", "be10153597703bca35406727361183c1c92cae8e3648664b363a5ecfa2cca942");
    }

    static String sha256(float[] x) throws Exception {
        ByteBuffer b = ByteBuffer.allocate(x.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : x) b.putFloat(v);
        StringBuilder sb = new StringBuilder();
        for (byte y : MessageDigest.getInstance("SHA-256").digest(b.array())) sb.append(String.format("%02x", y));
        return sb.toString();
    }

    @Test
    public void mulberry32MatchesTheJsGenerator() {
        AnalysisSignals.Mulberry32 r = new AnalysisSignals.Mulberry32(1);
        assertEquals(0.6270739405881613, r.next(), 0);
        assertEquals(0.002735721180215478, r.next(), 0);
        assertEquals(0.5274470399599522, r.next(), 0);
    }

    @Test
    public void everySongRendersTheJsRenderersSamples() throws Exception {
        List<String> diffs = new ArrayList<>();
        int checked = 0;
        for (AnalysisCases.Case c : AnalysisCases.cases()) {
            for (int sr : new int[]{44100, 22050}) {
                String key = c.name + "@" + sr, want = JS.get(key);
                assertTrue("no JS checksum for " + key + ": a new case? take its sum from analysis_signals.mjs", want != null);
                float[] x = AnalysisSignals.render(c.song, sr);
                assertEquals(key + " length", Math.round(c.song.sec * sr), x.length);
                String got = sha256(x);
                if (!got.equals(want)) diffs.add(key + ": " + got);
                checked++;
            }
        }
        assertEquals("songs rendered", JS.size(), checked);
        assertTrue("renders that differ from the JS renderer: " + diffs, diffs.isEmpty());
    }

    @Test
    public void beatTimesFollowEachPart() {
        AnalysisSignals.Song s = new AnalysisSignals.Song();
        s.sec = 10;
        s.kit = "drums";
        s.parts.add(new double[]{0.5, 120});
        s.parts.add(new double[]{4.5, 60});
        List<AnalysisSignals.Beat> b = AnalysisSignals.beatTimes(s);
        // 0.5 .. 4.0 every 0.5 s (8 beats), then 4.5 .. 9.5 every second (6 beats), bar count restarting
        assertEquals(14, b.size());
        assertEquals(4.0, b.get(7).t, 1e-12);
        assertEquals(3, b.get(7).beatInBar);
        assertEquals(4.5, b.get(8).t, 1e-12);
        assertEquals(0, b.get(8).beatInBar);
        assertEquals(60, b.get(8).bpm, 0);
        assertEquals(9.5, b.get(13).t, 1e-12);
    }
}
