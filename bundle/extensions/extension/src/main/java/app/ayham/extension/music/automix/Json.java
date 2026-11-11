package app.ayham.extension.music.automix;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A small JSON reader and writer (objects are maps, arrays lists, numbers doubles), so the
 * analysis cache and the planner need nothing from Android and run the same on a desktop JVM.
 */
public final class Json {
    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    public static Object parse(String text) {
        Json j = new Json(text);
        j.ws();
        Object v = j.value();
        j.ws();
        if (j.i != j.s.length()) throw new IllegalArgumentException("trailing text at " + j.i);
        return v;
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private Object value() {
        char c = s.charAt(i);
        if (c == '{') return object();
        if (c == '[') return array();
        if (c == '"') return string();
        if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        if (s.startsWith("null", i)) { i += 4; return null; }
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (start == i) throw new IllegalArgumentException("unexpected '" + c + "' at " + i);
        return Double.parseDouble(s.substring(start, i));
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (s.charAt(i) == '}') { i++; return m; }
        while (true) {
            ws();
            String k = string();
            ws();
            i++; // ':'
            ws();
            m.put(k, value());
            ws();
            char c = s.charAt(i++);
            if (c == '}') return m;
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        i++;
        ws();
        if (s.charAt(i) == ']') { i++; return l; }
        while (true) {
            ws();
            l.add(value());
            ws();
            char c = s.charAt(i++);
            if (c == ']') return l;
        }
    }

    private String string() {
        StringBuilder b = new StringBuilder();
        i++;
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c == '\\') {
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': b.append('\n'); break;
                    case 't': b.append('\t'); break;
                    case 'r': b.append('\r'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                    default: b.append(e);
                }
            } else {
                b.append(c);
            }
        }
    }

    // ---- writing ----

    public static String write(Object v) {
        StringBuilder b = new StringBuilder();
        write(b, v);
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder b, Object v) {
        if (v == null) {
            b.append("null");
        } else if (v instanceof Map) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                if (!first) b.append(',');
                first = false;
                quote(b, e.getKey());
                b.append(':');
                write(b, e.getValue());
            }
            b.append('}');
        } else if (v instanceof List) {
            b.append('[');
            boolean first = true;
            for (Object o : (List<Object>) v) {
                if (!first) b.append(',');
                first = false;
                write(b, o);
            }
            b.append(']');
        } else if (v instanceof double[]) {
            b.append('[');
            double[] a = (double[]) v;
            for (int k = 0; k < a.length; k++) {
                if (k > 0) b.append(',');
                number(b, a[k]);
            }
            b.append(']');
        } else if (v instanceof float[]) {
            b.append('[');
            float[] a = (float[]) v;
            for (int k = 0; k < a.length; k++) {
                if (k > 0) b.append(',');
                // the vocal curve: 4 decimals are plenty and keep the cache small. NaN / infinity
                // as null: "%.4f" would write NaN, which no JSON reader (this one included) reads
                if (Float.isNaN(a[k]) || Float.isInfinite(a[k])) b.append("null");
                else b.append(String.format(Locale.ROOT, "%.4f", a[k]));
            }
            b.append(']');
        } else if (v instanceof Number) {
            number(b, ((Number) v).doubleValue());
        } else if (v instanceof Boolean) {
            b.append(v.toString());
        } else {
            quote(b, v.toString());
        }
    }

    private static void number(StringBuilder b, double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) b.append("null");
        else if (d == Math.rint(d) && Math.abs(d) < 1e15) b.append((long) d);
        else b.append(d);
    }

    private static void quote(StringBuilder b, String str) {
        b.append('"');
        for (int k = 0; k < str.length(); k++) {
            char c = str.charAt(k);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c == '\n') b.append("\\n");
            else if (c < 0x20) b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            else b.append(c);
        }
        b.append('"');
    }

    // ---- reading helpers ----

    static double num(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof Number ? ((Number) v).doubleValue() : Double.NaN;
    }

    static double[] nums(Object list) {
        if (!(list instanceof List)) return null;
        List<?> l = (List<?>) list;
        double[] a = new double[l.size()];
        for (int k = 0; k < a.length; k++) a[k] = l.get(k) instanceof Number ? ((Number) l.get(k)).doubleValue() : Double.NaN;
        return a;
    }

    static float[] floats(Object list) {
        if (!(list instanceof List)) return new float[0];
        List<?> l = (List<?>) list;
        float[] a = new float[l.size()];
        for (int k = 0; k < a.length; k++) a[k] = l.get(k) instanceof Number ? ((Number) l.get(k)).floatValue() : 0f;
        return a;
    }
}
