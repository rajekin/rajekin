import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.io.*;
import java.util.*;

public class FsmlAnalyzerMain {

    /* ===================== MODELS ===================== */

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
    static Set<Element> visitedNodes = new HashSet<>();
    static int totalNodeCount = 0;

    /* ===================== XML HELPERS ===================== */

    static List<Element> children(Element parent, String localName) {
        List<Element> list = new ArrayList<>();
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n instanceof Element) {
                Element e = (Element) n;
                if (localName.equalsIgnoreCase(e.getLocalName())) {
                    list.add(e);
                }
            }
        }
        return list;
    }

    static Element firstChild(Element parent, String localName) {
        List<Element> list = children(parent, localName);
        return list.isEmpty() ? null : list.get(0);
    }

    /* ===================== VARIABLE PARSING ===================== */

    static void parseVariables(Document doc) {
        NodeList all = doc.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Node n = all.item(i);
            if (!(n instanceof Element)) continue;

            Element e = (Element) n;

            if ("NumericKey".equalsIgnoreCase(e.getLocalName())) {
                Variable v = new Variable();
                v.name = e.getAttribute("ShortName");
                v.numeric = true;

                Element r = firstChild(e, "NumericRange");
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

        totalNodeCount++;
        visitedNodes.add(node);

        Path current = incoming.copy();

        // Apply DIRECT conditions only
        List<Element> conds = children(node, "CONDITION");
        for (int i = 0; i < conds.size(); i++) {
            Element c = conds.get(i);

            String key = c.getAttribute("DecisionKey");
            String type = c.getAttribute("Type");
            String val  = c.getAttribute("Value");

            if (key == null || key.isEmpty()) continue;
            if (type == null || type.isEmpty()) continue;
            if ("true".equalsIgnoreCase(type)) continue;
            if ("and".equalsIgnoreCase(type)) continue;

            Variable v = variables.get(key);
            if (v == null) continue;

            if (v.numeric) {
                if (val == null || val.isEmpty()) continue;
                if ("NaN".equalsIgnoreCase(val)) continue;

                Interval base = current.numeric.containsKey(key)
                        ? current.numeric.get(key)
                        : new Interval(v.min, v.max);

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
                    continue;
                }

                Interval merged = base.intersect(local);
                if (merged == null) return;

                current.numeric.put(key, merged);

            } else {
                current.categorical.put(key, val);
            }
        }

        Element actions = firstChild(node, "ACTIONS");
        List<Element> childNodes = children(node, "NODE");

        // Leaf
        if (actions != null && childNodes.isEmpty()) {
            current.action = actions.getAttribute("Label");
            current.label = node.getAttribute("Label");
            paths.add(current);
            return;
        }

        // Default / no-hit
        if (actions == null && childNodes.isEmpty()) {
            current.action = "DEFAULT / NO HIT";
            current.label = node.getAttribute("Label");
            paths.add(current);
            return;
        }

        for (int i = 0; i < childNodes.size(); i++) {
            walk(childNodes.get(i), current);
        }
    }

    /* ===================== DECISION TABLE ===================== */

    static void writeDecisionTable() throws Exception {
        PrintWriter out = new PrintWriter("decision-table.csv");

        out.print("RULE,");
        for (String v : variables.keySet()) {
            out.print(v + ",");
        }
        out.println("ACTION");

        int r = 1;
        for (int i = 0; i < paths.size(); i++) {
            Path p = paths.get(i);
            out.print("R" + r++ + ",");
            for (String v : variables.keySet()) {
                if (p.numeric.containsKey(v)) {
                    out.print(p.numeric.get(v) + ",");
                } else if (p.categorical.containsKey(v)) {
                    out.print(p.categorical.get(v) + ",");
                } else {
                    out.print("ANY,");
                }
            }
            out.println(p.action);
        }
        out.close();
    }

    /* ===================== GAP ANALYSIS ===================== */

    static List<Interval> gaps(List<Interval> covered, Interval universe) {
        Collections.sort(covered, new Comparator<Interval>() {
            public int compare(Interval a, Interval b) {
                return Double.compare(a.min, b.min);
            }
        });

        List<Interval> gaps = new ArrayList<>();
        double cursor = universe.min;

        for (int i = 0; i < covered.size(); i++) {
            Interval c = covered.get(i);
            if (c.min > cursor) {
                gaps.add(new Interval(cursor, c.min));
            }
            cursor = Math.max(cursor, c.max);
        }

        if (cursor < universe.max) {
            gaps.add(new Interval(cursor, universe.max));
        }
        return gaps;
    }

    static void writeGapAnalysis() throws Exception {
        PrintWriter out = new PrintWriter("gap-analysis.txt");

        for (Variable v : variables.values()) {
            if (!v.numeric) continue;

            List<Interval> covered = new ArrayList<>();
            for (int i = 0; i < paths.size(); i++) {
                Path p = paths.get(i);
                Interval iv = p.numeric.get(v.name);
                if (iv != null) covered.add(iv);
            }

            out.println("Variable: " + v.name);
            List<Interval> gaps = gaps(covered, new Interval(v.min, v.max));

            if (gaps.isEmpty()) {
                out.println("  NO GAPS");
            } else {
                for (int i = 0; i < gaps.size(); i++) {
                    out.println("  GAP: " + gaps.get(i));
                }
            }
            out.println();
        }
        out.close();
    }

    /* ===================== NEGATIVE TESTS ===================== */

    static void writeNegativeTests() throws Exception {
        PrintWriter out = new PrintWriter("negative-tests.json");
        out.println("[");

        boolean first = true;
        for (Variable v : variables.values()) {
            if (!v.numeric) continue;

            List<Interval> covered = new ArrayList<>();
            for (int i = 0; i < paths.size(); i++) {
                Interval iv = paths.get(i).numeric.get(v.name);
                if (iv != null) covered.add(iv);
            }

            List<Interval> gaps = gaps(covered, new Interval(v.min, v.max));
            for (int i = 0; i < gaps.size(); i++) {
                Interval g = gaps.get(i);
                double val = (g.min + g.max) / 2;

                if (!first) out.println(",");
                first = false;

                out.print("  { \"" + v.name + "\": " + val +
                        ", \"expected\": \"NO_DECISION\" }");
            }
        }
        out.println("\n]");
        out.close();
    }

    /* ===================== HTML ===================== */

    static void writeHtml() throws Exception {
        PrintWriter out = new PrintWriter("fsml-view.html");
        out.println("<html><body><h2>FSML Decision Paths</h2><ul>");

        for (int i = 0; i < paths.size(); i++) {
            Path p = paths.get(i);
            out.println("<li><b>" + p.label + "</b><ul>");
            for (String k : p.numeric.keySet()) {
                out.println("<li>" + k + " " + p.numeric.get(k) + "</li>");
            }
            for (String k : p.categorical.keySet()) {
                out.println("<li>" + k + " = " + p.categorical.get(k) + "</li>");
            }
            out.println("<li><b>ACTION:</b> " + p.action + "</li>");
            out.println("</ul></li>");
        }
        out.println("</ul></body></html>");
        out.close();
    }

    /* ===================== MAIN ===================== */

    public static void main(String[] args) throws Exception {

        File fsml = new File("PenFed_AR_Expert_09042025.fsml"); // <-- update

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);

        Document doc = dbf.newDocumentBuilder().parse(fsml);
        doc.getDocumentElement().normalize();

        parseVariables(doc);

        Element strategy = null;
        NodeList all = doc.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Node n = all.item(i);
            if (n instanceof Element) {
                Element e = (Element) n;
                if ("STRATEGY".equalsIgnoreCase(e.getLocalName())) {
                    strategy = e;
                    break;
                }
            }
        }

        Element root = firstChild(strategy, "NODE");
        walk(root, new Path());

        writeDecisionTable();
        writeGapAnalysis();
        writeNegativeTests();
        writeHtml();

        double reachability =
                (visitedNodes.size() * 100.0) / totalNodeCount;

        System.out.println("TOTAL PATHS    = " + paths.size());
        System.out.println("REACHABILITY % = " + reachability);
        System.out.println("Generated:");
        System.out.println(" decision-table.csv");
        System.out.println(" gap-analysis.txt");
        System.out.println(" negative-tests.json");
        System.out.println(" fsml-view.html");
    }
}
