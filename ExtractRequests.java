import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.io.*;
import java.util.*;

public class FsmlAnalyzerMain {

    /* ===================== MODELS ===================== */

    static class Variable {
        String name;
        boolean numeric;
        double min, max;
    }

    static class Interval {
        double min, max;
        Interval(double min, double max) { this.min = min; this.max = max; }

        boolean covers(Interval o) {
            return min <= o.min && max >= o.max;
        }

        Interval intersect(Interval o) {
            double lo = Math.max(min, o.min);
            double hi = Math.min(max, o.max);
            return lo >= hi ? null : new Interval(lo, hi);
        }

        public String toString() {
            return min + " <= x < " + max;
        }
    }

    static class Path {
        Map<String, Interval> numeric = new LinkedHashMap<>();
        Map<String, String> categorical = new LinkedHashMap<>();
        String action;
        int id;
    }

    static Map<String, Variable> variables = new LinkedHashMap<>();
    static List<Path> paths = new ArrayList<>();

    /* ===================== XML HELPERS ===================== */

    static String local(Node n) {
        if (n.getLocalName() != null) return n.getLocalName();
        String s = n.getNodeName();
        int i = s.indexOf(':');
        return i >= 0 ? s.substring(i + 1) : s;
    }

    static List<Element> children(Element p, String tag) {
        List<Element> out = new ArrayList<>();
        if (p == null) return out;
        NodeList nl = p.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n instanceof Element) {
                Element e = (Element) n;
                if (tag.equalsIgnoreCase(local(e))) out.add(e);
            }
        }
        return out;
    }

    static Element firstChild(Element p, String tag) {
        List<Element> c = children(p, tag);
        return c.isEmpty() ? null : c.get(0);
    }

    /* ===================== VARIABLE PARSING ===================== */

    static void parseVariables(Document doc) {
        NodeList all = doc.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            if (!(all.item(i) instanceof Element)) continue;
            Element e = (Element) all.item(i);

            String tag = local(e);
            if ("NumericKey".equalsIgnoreCase(tag)) {
                Variable v = new Variable();
                v.name = e.getAttribute("ShortName");
                v.numeric = true;
                Element r = firstChild(e, "NumericRange");
                v.min = Double.parseDouble(r.getAttribute("minValue"));
                v.max = Double.parseDouble(r.getAttribute("maxValue"));
                variables.put(v.name, v);
            }
            if ("CategoricalKey".equalsIgnoreCase(tag)) {
                Variable v = new Variable();
                v.name = e.getAttribute("ShortName");
                v.numeric = false;
                variables.put(v.name, v);
            }
        }
    }

    /* ===================== CONDITION EXTRACTION ===================== */

    static void extractConditions(Element cond, Path p) {
        List<Element> nested = children(cond, "CONDITION");
        if (!nested.isEmpty()) {
            for (Element c : nested) extractConditions(c, p);
            return;
        }

        String key = cond.getAttribute("DecisionKey");
        String type = cond.getAttribute("Type");
        String val = cond.getAttribute("Value");

        if (key.isEmpty() || type.isEmpty()) return;
        if ("true".equalsIgnoreCase(type) || "and".equalsIgnoreCase(type)) return;

        Variable v = variables.get(key);
        if (v == null) return;

        if (v.numeric) {
            if (val.isEmpty() || "NaN".equalsIgnoreCase(val)) return;

            Interval base = p.numeric.getOrDefault(
                    key, new Interval(v.min, v.max));

            double num =
                    "LOW".equalsIgnoreCase(val) ? v.min :
                    "HIGH".equalsIgnoreCase(val) ? v.max :
                    Double.parseDouble(val);

            Interval local = type.startsWith("g")
                    ? new Interval(num, v.max)
                    : new Interval(v.min, num);

            Interval merged = base.intersect(local);
            if (merged == null) return;

            p.numeric.put(key, merged);
        } else {
            p.categorical.put(key, val);
        }
    }

    /* ===================== WALK ===================== */

    static void walk(Element node, Path incoming) {
        Path cur = new Path();
        cur.numeric.putAll(incoming.numeric);
        cur.categorical.putAll(incoming.categorical);

        for (Element c : children(node, "CONDITION"))
            extractConditions(c, cur);

        Element act = firstChild(node, "ACTIONS");
        List<Element> kids = children(node, "NODE");

        if (act != null && kids.isEmpty()) {
            cur.action = act.getAttribute("Label");
            cur.id = paths.size() + 1;
            paths.add(cur);
            return;
        }

        for (Element k : kids) walk(k, cur);
    }

    /* ===================== GAP ANALYSIS ===================== */

    static void gapAnalysis() throws Exception {
        PrintWriter out = new PrintWriter("gap-analysis.txt");

        for (Variable v : variables.values()) {
            if (!v.numeric) continue;

            List<Interval> covered = new ArrayList<>();

            for (Path p : paths) {
                Interval i = p.numeric.get(v.name);
                covered.add(i == null ? new Interval(v.min, v.max) : i);
            }

            covered.sort(Comparator.comparingDouble(a -> a.min));

            double cursor = v.min;
            boolean gap = false;

            for (Interval c : covered) {
                if (c.min > cursor) {
                    out.println("GAP " + v.name + ": " + cursor + " → " + c.min);
                    gap = true;
                }
                cursor = Math.max(cursor, c.max);
            }

            if (cursor < v.max) {
                out.println("GAP " + v.name + ": " + cursor + " → " + v.max);
                gap = true;
            }

            if (!gap) out.println("NO GAPS " + v.name);
            out.println();
        }
        out.close();
    }

    /* ===================== SHADOWED PATHS ===================== */

    static void detectShadowedPaths() throws Exception {
        PrintWriter out = new PrintWriter("shadowed-paths.txt");

        for (Path a : paths) {
            for (Path b : paths) {
                if (a == b) continue;
                if (!a.action.equals(b.action)) continue;

                boolean shadowed = true;

                for (Map.Entry<String, Interval> e : a.numeric.entrySet()) {
                    Interval bi = b.numeric.get(e.getKey());
                    if (bi == null || !bi.covers(e.getValue())) {
                        shadowed = false;
                        break;
                    }
                }

                for (Map.Entry<String, String> e : a.categorical.entrySet()) {
                    if (!e.getValue().equals(b.categorical.get(e.getKey()))) {
                        shadowed = false;
                        break;
                    }
                }

                if (shadowed) {
                    out.println("Path " + b.id + " is shadowed by Path " + a.id);
                }
            }
        }
        out.close();
    }

    /* ===================== MAIN ===================== */

    public static void main(String[] args) throws Exception {

        Document doc = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(new File("PenFed_AR_Expert_09042025.fsml"));

        parseVariables(doc);

        Element strategy = null;
        NodeList all = doc.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            if (all.item(i) instanceof Element) {
                Element e = (Element) all.item(i);
                if ("STRATEGY".equalsIgnoreCase(local(e))) {
                    strategy = e;
                    break;
                }
            }
        }

        walk(firstChild(strategy, "NODE"), new Path());

        gapAnalysis();
        detectShadowedPaths();

        System.out.println("TOTAL PATHS = " + paths.size());
        System.out.println("Generated:");
        System.out.println(" gap-analysis.txt");
        System.out.println(" shadowed-paths.txt");
    }
}
