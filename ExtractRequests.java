import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.io.*;
import java.util.*;

/**
 * FSML Decision Analyzer
 * - Decision tables
 * - Gap analysis
 * - Negative tests
 * - Reachability %
 * - Default / no-hit detection
 *
 * Java 21 – single file
 */
public class FsmlDecisionAnalyzer {

    /* ===================== DATA MODELS ===================== */

    static class Variable {
        String name;
        boolean numeric;
        double min, max;
    }

    static class Interval {
        double min, max;
        Interval(double min, double max) {
            this.min = min; this.max = max;
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

    static List<Element> children(Element p, String name) {
        List<Element> list = new ArrayList<>();
        NodeList nl = p.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            if (nl.item(i) instanceof Element e &&
                name.equalsIgnoreCase(e.getLocalName())) {
                list.add(e);
            }
        }
        return list;
    }

    static Element firstChild(Element p, String name) {
        for (Element e : children(p, name)) return e;
        return null;
    }

    /* ===================== VARIABLE PARSING ===================== */

    static void parseVariables(Document doc) {
        NodeList all = doc.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            if (!(all.item(i) instanceof Element e)) continue;

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

        // Apply direct CONDITIONS
        for (Element c : children(node, "CONDITION")) {
            String key = c.getAttribute("DecisionKey");
            String type = c.getAttribute("Type");
            String val  = c.getAttribute("Value");

            if (key.isBlank() || type.isBlank()) continue;
            if ("true".equalsIgnoreCase(type)) continue;
            if ("and".equalsIgnoreCase(type)) continue;

            Variable v = variables.get(key);
            if (v == null) continue;

            if (v.numeric) {
                if (val == null || val.isBlank()) continue;
                if ("NaN".equalsIgnoreCase(val)) continue;

                Interval base = current.numeric
                        .getOrDefault(key, new Interval(v.min, v.max));

                double num;
                if ("LOW".equalsIgnoreCase(val)) num = v.min;
                else if ("HIGH".equalsIgnoreCase(val)) num = v.max;
                else num = Double.parseDouble(val);

                Interval local =
                        type.startsWith("g")
                                ? new Interval(num, v.max)
                                : new Interval(v.min, num);

                Interval merged = base.intersect(local);
                if (merged == null) return;

                current.numeric.put(key, merged);
            } else {
                current.categorical.put(key, val);
            }
        }

        // Leaf detection
        Element actions = firstChild(node, "ACTIONS");
        List<Element> childNodes = children(node, "NODE");

        if (actions != null && childNodes.isEmpty()) {
            current.action = actions.getAttribute("Label");
            current.label = node.getAttribute("Label");
            paths.add(current);
            return;
        }

        if (actions == null && childNodes.isEmpty()) {
            current.action = "DEFAULT / NO HIT";
            current.label = node.getAttribute("Label");
            paths.add(current);
            return;
        }

        for (Element child : childNodes) {
            walk(child, current);
        }
    }

    /* ===================== DECISION TABLE ===================== */

    static void writeDecisionTable() throws Exception {
        try (PrintWriter out = new PrintWriter("decision-table.csv")) {

            Set<String> cols = new LinkedHashSet<>(variables.keySet());

            out.print("RULE,");
            for (String c : cols) out.print(c + ",");
            out.println("ACTION");

            int i = 1;
            for (Path p : paths) {
                out.print("R" + i++ + ",");
                for (String c : cols) {
                    if (p.numeric.containsKey(c))
                        out.print(p.numeric.get(c) + ",");
                    else if (p.categorical.containsKey(c))
                        out.print(p.categorical.get(c) + ",");
                    else
                        out.print("ANY,");
                }
                out.println(p.action);
            }
        }
    }

    /* ===================== GAP ANALYSIS ===================== */

    static List<Interval> findGaps(List<Interval> covered, Interval universe) {

        covered.sort(Comparator.comparingDouble(i -> i.min));
        List<Interval> gaps = new ArrayList<>();

        double cursor = universe.min;

        for (Interval c : covered) {
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
        try (PrintWriter out = new PrintWriter("gap-analysis.txt")) {
            for (Variable v : variables.values()) {
                if (!v.numeric) continue;

                List<Interval> covered = new ArrayList<>();
                for (Path p : paths) {
                    Interval i = p.numeric.get(v.name);
                    if (i != null) covered.add(i);
                }

                List<Interval> gaps =
                        findGaps(covered, new Interval(v.min, v.max));

                out.println("Variable: " + v.name);
                if (gaps.isEmpty()) {
                    out.println("  NO GAPS");
                } else {
                    for (Interval g : gaps) {
                        out.println("  GAP: " + g);
                    }
                }
                out.println();
            }
        }
    }

    /* ===================== NEGATIVE TESTS ===================== */

    static void writeNegativeTests() throws Exception {
        try (PrintWriter out = new PrintWriter("negative-tests.json")) {
            out.println("[");
            boolean first = true;

            for (Variable v : variables.values()) {
                if (!v.numeric) continue;

                List<Interval> covered = new ArrayList<>();
                for (Path p : paths) {
                    Interval i = p.numeric.get(v.name);
                    if (i != null) covered.add(i);
                }

                List<Interval> gaps =
                        findGaps(covered, new Interval(v.min, v.max));

                for (Interval g : gaps) {
                    double val = (g.min + g.max) / 2;
                    if (!first) out.println(",");
                    first = false;
                    out.println("  { \"" + v.name + "\": " + val +
                                ", \"expected\": \"NO_DECISION\" }");
                }
            }
            out.println("\n]");
        }
    }

    /* ===================== HTML VIEW ===================== */

    static void writeHtml() throws Exception {
        try (PrintWriter out = new PrintWriter("fsml-view.html")) {
            out.println("<html><body><h2>FSML Decision Paths</h2><ul>");
            for (Path p : paths) {
                out.println("<li><b>" + p.label + "</b><ul>");
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

        File fsml = new File("PenFed_AR_Expert_09042025.fsml"); // <-- update path

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        Document doc = dbf.newDocumentBuilder().parse(fsml);
        doc.getDocumentElement().normalize();

        parseVariables(doc);

        Element strategy = null;
        NodeList all = doc.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            if (all.item(i) instanceof Element e &&
                "STRATEGY".equalsIgnoreCase(e.getLocalName())) {
                strategy = e;
                break;
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

        System.out.println("TOTAL PATHS        = " + paths.size());
        System.out.println("REACHABILITY %     = " + reachability);
        System.out.println("Generated:");
        System.out.println(" - decision-table.csv");
        System.out.println(" - gap-analysis.txt");
        System.out.println(" - negative-tests.json");
        System.out.println(" - fsml-view.html");
    }
}
