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

        Path copy() {
            Path p = new Path();
            p.numeric.putAll(numeric);
            p.categorical.putAll(categorical);
            return p;
        }
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

    /* ===================== CONDITION EXTRACTION (FIX) ===================== */

    static void extractConditions(Element cond, Path path) {

        // Leaf condition
        if (!children(cond, "CONDITION").isEmpty()) {
            for (Element c : children(cond, "CONDITION")) {
                extractConditions(c, path);
            }
            return;
        }

        String key  = cond.getAttribute("DecisionKey");
        String type = cond.getAttribute("Type");
        String val  = cond.getAttribute("Value");

        if (key.isEmpty() || type.isEmpty()) return;
        if ("true".equalsIgnoreCase(type) || "and".equalsIgnoreCase(type)) return;

        Variable v = variables.get(key);
        if (v == null) return;

        if (v.numeric) {
            if (val.isEmpty() || "NaN".equalsIgnoreCase(val)) return;

            Interval base = path.numeric.getOrDefault(
                    key, new Interval(v.min, v.max));

            double num =
                    "LOW".equalsIgnoreCase(val) ? v.min :
                    "HIGH".equalsIgnoreCase(val) ? v.max :
                    Double.parseDouble(val);

            Interval local = type.startsWith("g")
                    ? new Interval(num, v.max)
                    : new Interval(v.min, num);

            Interval merged = base.intersect(local);
            if (merged == null) throw new RuntimeException("Impossible path");

            path.numeric.put(key, merged);
        } else {
            path.categorical.put(key, val);
        }
    }

    /* ===================== TREE WALK ===================== */

    static void walk(Element node, Path incoming) {

        Path current = incoming.copy();

        for (Element c : children(node, "CONDITION")) {
            extractConditions(c, current);
        }

        Element action = firstChild(node, "ACTIONS");
        List<Element> kids = children(node, "NODE");

        if (action != null && kids.isEmpty()) {
            current.action = action.getAttribute("Label");
            paths.add(current);
            return;
        }

        for (Element k : kids) walk(k, current);
    }

    /* ===================== OUTPUT ===================== */

    static void writeDecisionTable() throws Exception {
        PrintWriter out = new PrintWriter("decision-table.csv");
        out.println("RULE,CONDITIONS,ACTION");

        int r = 1;
        for (Path p : paths) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Interval> e : p.numeric.entrySet())
                sb.append(e.getKey()).append(" ").append(e.getValue()).append(" ; ");
            for (Map.Entry<String, String> e : p.categorical.entrySet())
                sb.append(e.getKey()).append(" = ").append(e.getValue()).append(" ; ");

            out.println("R" + r++ + ",\"" + sb.toString().trim() + "\"," + p.action);
        }
        out.close();
    }

    static void writeHtml() throws Exception {
        PrintWriter out = new PrintWriter("fsml-view.html");
        out.println("<html><body><h2>FSML Decision Paths</h2>");

        int r = 1;
        for (Path p : paths) {
            out.println("<b>Rule " + (r++) + "</b><ul>");
            for (Map.Entry<String, Interval> e : p.numeric.entrySet())
                out.println("<li>" + e.getKey() + " " + e.getValue() + "</li>");
            for (Map.Entry<String, String> e : p.categorical.entrySet())
                out.println("<li>" + e.getKey() + " = " + e.getValue() + "</li>");
            out.println("<li><b>ACTION:</b> " + p.action + "</li></ul><br/>");
        }

        out.println("</body></html>");
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

        writeDecisionTable();
        writeHtml();

        System.out.println("TOTAL PATHS = " + paths.size());
    }
}
