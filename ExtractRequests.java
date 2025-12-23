import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.io.*;
import java.util.*;

public class FsmlAnalyzerMain {

    /* ===================== DATA MODELS ===================== */

    static class Variable {
        String name;
        boolean numeric;
        double min;
        double max;
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
            return "[" + min + "," + max + ")";
        }
    }

    static class Path {
        Map<String, Interval> numeric = new LinkedHashMap<>();
        Map<String, String> categorical = new LinkedHashMap<>();
        String action;
        String leafLabel;

        Path copy() {
            Path p = new Path();
            p.numeric.putAll(numeric);
            p.categorical.putAll(categorical);
            return p;
        }
    }

    static Map<String, Variable> variables = new HashMap<>();
    static List<Path> paths = new ArrayList<>();

    /* ===================== XML HELPERS ===================== */

    static List<Element> directChildren(Element parent, String localName) {
        List<Element> out = new ArrayList<>();
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            if (kids.item(i) instanceof Element) {
                Element e = (Element) kids.item(i);
                if (localName.equalsIgnoreCase(e.getLocalName())) {
                    out.add(e);
                }
            }
        }
        return out;
    }

    static Element directChild(Element parent, String localName) {
        for (Element e : directChildren(parent, localName)) {
            return e;
        }
        return null;
    }

    /* ===================== VARIABLE PARSING ===================== */

    static void parseVariables(Document doc) {
        NodeList all = doc.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            if (!(all.item(i) instanceof Element)) continue;
            Element e = (Element) all.item(i);

            if ("NumericKey".equalsIgnoreCase(e.getLocalName())) {
                Variable v = new Variable();
                v.name = e.getAttribute("ShortName");
                v.numeric = true;

                Element r = directChild(e, "NumericRange");
                v.min = Double.parseDouble(r.getAttribute("minValue"));
                v.max = Double.parseDouble(r.getAttribute("maxValue"));

                variables.put(v.name, v);
            }

            if ("CategoricalKey".equalsIgnoreCase(e.getLocalName())) {
                Variable v = new Variable();
                v.name = e.getAttribute("ShortName");
                v.numeric = false;
                variables.put(v.name, v);
            }
        }
    }

    /* ===================== FSML WALK ===================== */

    static void walk(Element node, Path incoming) {

        Path current = incoming.copy();

        /* ---- Apply DIRECT CONDITIONS only ---- */
        for (Element c : directChildren(node, "CONDITION")) {

            String key = c.getAttribute("DecisionKey");
            String type = c.getAttribute("Type");
            String val  = c.getAttribute("Value");

            // Skip structural / grouping conditions
            if (key == null || key.isBlank()) continue;
            if (type == null || type.isBlank()) continue;
            if ("true".equalsIgnoreCase(type)) continue;
            if ("and".equalsIgnoreCase(type)) continue;

            Variable v = variables.get(key);
            if (v == null) continue;

            // ---- Numeric ----
            if (v.numeric) {

                // Skip missing / symbolic buckets
                if (val == null || val.isBlank()) continue;
                if ("NaN".equalsIgnoreCase(val)) continue;

                Interval base = current.numeric
                        .getOrDefault(key, new Interval(v.min, v.max));

                double num;
                if ("LOW".equalsIgnoreCase(val)) {
                    num = v.min;
                } else if ("HIGH".equalsIgnoreCase(val)) {
                    num = v.max;
                } else {
                    num = Double.parseDouble(val);
                }

                Interval local;
                if (type.startsWith("g")) {
                    local = new Interval(num, v.max);
                } else if (type.startsWith("l")) {
                    local = new Interval(v.min, num);
                } else {
                    continue; // unknown operator
                }

                Interval merged = base.intersect(local);
                if (merged == null) return; // dead path

                current.numeric.put(key, merged);

            } else {
                // ---- Categorical ----
                if (val != null && !val.isBlank()) {
                    current.categorical.put(key, val);
                }
            }
        }

        /* ---- Leaf detection: DIRECT ACTIONS only ---- */
        Element actions = directChild(node, "ACTIONS");
        List<Element> childNodes = directChildren(node, "NODE");

        if (actions != null && childNodes.isEmpty()) {
            current.action = actions.getAttribute("Label");
            current.leafLabel = node.getAttribute("Label");
            paths.add(current);
            return;
        }

        /* ---- Recurse into child NODEs ---- */
        for (Element child : childNodes) {
            walk(child, current);
        }
    }

    /* ===================== OUTPUT ===================== */

    static void writeDecisionTable() throws Exception {
        try (PrintWriter out = new PrintWriter("decision-table.csv")) {

            Set<String> cols = new LinkedHashSet<>();
            for (Path p : paths) {
                cols.addAll(p.numeric.keySet());
                cols.addAll(p.categorical.keySet());
            }

            for (String c : cols) out.print(c + ",");
            out.println("ACTION");

            for (Path p : paths) {
                for (String c : cols) {
                    if (p.numeric.containsKey(c))
                        out.print(p.numeric.get(c) + ",");
                    else if (p.categorical.containsKey(c))
                        out.print(p.categorical.get(c) + ",");
                    else
                        out.print("-,");
                }
                out.println(p.action);
            }
        }
    }

    static void writeHtml() throws Exception {
        try (PrintWriter out = new PrintWriter("fsml-view.html")) {
            out.println("<html><body><h2>FSML Decision Paths</h2><ul>");
            for (Path p : paths) {
                out.println("<li><b>" + p.leafLabel + "</b><ul>");
                p.numeric.forEach((k,v) ->
                        out.println("<li>"+k+" "+v+"</li>"));
                p.categorical.forEach((k,v) ->
                        out.println("<li>"+k+" = "+v+"</li>"));
                out.println("<li><b>ACTION:</b> "+p.action+"</li>");
                out.println("</ul></li>");
            }
            out.println("</ul></body></html>");
        }
    }

    /* ===================== MAIN ===================== */

    public static void main(String[] args) throws Exception {

        File fsml = new File("model.fsml"); // <-- your FSML file

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);

        Document doc = dbf.newDocumentBuilder().parse(fsml);
        doc.getDocumentElement().normalize();

        parseVariables(doc);

        // Locate STRATEGY
        Element strategy = null;
        NodeList all = doc.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            if (all.item(i) instanceof Element) {
                Element e = (Element) all.item(i);
                if ("STRATEGY".equalsIgnoreCase(e.getLocalName())) {
                    strategy = e;
                    break;
                }
            }
        }

        if (strategy == null) {
            throw new RuntimeException("STRATEGY element not found");
        }

        // Root NODE
        Element root = directChild(strategy, "NODE");
        if (root == null) {
            throw new RuntimeException("Root NODE not found");
        }

        walk(root, new Path());

        System.out.println("TOTAL DECISION PATHS = " + paths.size());

        writeDecisionTable();
        writeHtml();

        System.out.println("Generated:");
        System.out.println(" - decision-table.csv");
        System.out.println(" - fsml-view.html");
    }
}
