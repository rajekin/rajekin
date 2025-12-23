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
        Interval(double min, double max) {
            this.min = min;
            this.max = max;
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
        String label;

        Path copy() {
            Path p = new Path();
            p.numeric.putAll(numeric);
            p.categorical.putAll(categorical);
            return p;
        }
    }

    /* ===================== STATE ===================== */

    static Map<String, Variable> variables = new LinkedHashMap<>();
    static List<Path> paths = new ArrayList<>();

    /* ===================== XML HELPERS ===================== */

    static String local(Node n) {
        if (n.getLocalName() != null) return n.getLocalName();
        String name = n.getNodeName();
        int i = name.indexOf(':');
        return i >= 0 ? name.substring(i + 1) : name;
    }

    static List<Element> children(Element p, String tag) {
        List<Element> list = new ArrayList<>();
        if (p == null) return list;

        NodeList nl = p.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n instanceof Element) {
                Element e = (Element) n;
                if (tag.equalsIgnoreCase(local(e))) {
                    list.add(e);
                }
            }
        }
        return list;
    }

    static Element firstChild(Element p, String tag) {
        List<Element> list = children(p, tag);
        return list.isEmpty() ? null : list.get(0);
    }

    /* ===================== VARIABLE PARSING ===================== */

    static void parseVariables(Document doc) {
        NodeList all = doc.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Node n = all.item(i);
            if (!(n instanceof Element)) continue;

            Element e = (Element) n;
            String tag = local(e);

            if ("NumericKey".equalsIgnoreCase(tag)) {
                Variable v = new Variable();
                v.name = e.getAttribute("ShortName");
                v.numeric = true;
                Element r = firstChild(e, "NumericRange");
                if (r != null) {
                    v.min = Double.parseDouble(r.getAttribute("minValue"));
                    v.max = Double.parseDouble(r.getAttribute("maxValue"));
                }
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

    /* ===================== FSML WALK ===================== */

    static void walk(Element node, Path incoming) {
        if (node == null) return;

        Path current = incoming.copy();

        for (Element c : children(node, "CONDITION")) {
            String key = c.getAttribute("DecisionKey");
            String type = c.getAttribute("Type");
            String val  = c.getAttribute("Value");

            if (key.isEmpty() || type.isEmpty()) continue;
            if ("true".equalsIgnoreCase(type) || "and".equalsIgnoreCase(type)) continue;

            Variable v = variables.get(key);
            if (v == null) continue;

            if (v.numeric) {
                if (val.isEmpty() || "NaN".equalsIgnoreCase(val)) continue;

                Interval base = current.numeric.getOrDefault(
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

                current.numeric.put(key, merged);
            } else {
                current.categorical.put(key, val);
            }
        }

        Element actions = firstChild(node, "ACTIONS");
        List<Element> kids = children(node, "NODE");

        if (actions != null && kids.isEmpty()) {
            current.action = actions.getAttribute("Label");
            current.label = node.getAttribute("Label");
            paths.add(current);
            return;
        }

        for (Element k : kids) {
            walk(k, current);
        }
    }

    /* ===================== PATH-BASED DECISION TABLE ===================== */

    static void writeDecisionTable() throws Exception {
        PrintWriter out = new PrintWriter("decision-table.csv");
        out.println("RULE,CONDITIONS,ACTION");

        int r = 1;
        for (Path p : paths) {
            StringBuilder cond = new StringBuilder();

            for (Map.Entry<String, Interval> e : p.numeric.entrySet()) {
                cond.append(e.getKey())
                    .append(" ")
                    .append(e.getValue())
                    .append(" ; ");
            }

            for (Map.Entry<String, String> e : p.categorical.entrySet()) {
                cond.append(e.getKey())
                    .append(" = ")
                    .append(e.getValue())
                    .append(" ; ");
            }

            out.println("R" + r++ + ",\"" +
                    cond.toString().trim() + "\"," +
                    p.action);
        }
        out.close();
    }

    /* ===================== HTML (MATCHES YOUR SCREENSHOT) ===================== */

    static void writeHtml() throws Exception {
        PrintWriter out = new PrintWriter("fsml-view.html");
        out.println("<html><body>");
        out.println("<h2>FSML Decision Paths</h2>");

        int r = 1;
        for (Path p : paths) {
            out.println("<div style='margin-bottom:16px'>");
            out.println("<b>Rule " + (r++) + "</b>");
            out.println("<ul>");

            for (Map.Entry<String, Interval> e : p.numeric.entrySet()) {
                out.println("<li>" + e.getKey() + " " + e.getValue() + "</li>");
            }
            for (Map.Entry<String, String> e : p.categorical.entrySet()) {
                out.println("<li>" + e.getKey() + " = " + e.getValue() + "</li>");
            }

            out.println("<li><b>ACTION:</b> " + p.action + "</li>");
            out.println("</ul></div>");
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
            Node n = all.item(i);
            if (n instanceof Element) {
                Element e = (Element) n;
                if ("STRATEGY".equalsIgnoreCase(local(e))) {
                    strategy = e;
                    break;
                }
            }
        }

        if (strategy == null)
            throw new RuntimeException("No STRATEGY element found");

        Element root = firstChild(strategy, "NODE");
        if (root == null)
            throw new RuntimeException("No root NODE under STRATEGY");

        walk(root, new Path());

        writeDecisionTable();
        writeHtml();

        System.out.println("TOTAL PATHS = " + paths.size());
        System.out.println("Generated:");
        System.out.println(" decision-table.csv");
        System.out.println(" fsml-view.html");
    }
}
