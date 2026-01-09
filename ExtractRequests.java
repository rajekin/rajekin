import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

public class FsmlAnalyzerAllInOne {

    /* ===================== DATA MODELS ===================== */

    static class Condition {
        String key;
        String op;
        String value;

        public String toString() {
            return key + " " + op + " " + value;
        }
    }

    static class Path {
        List<Condition> conditions = new ArrayList<>();
        String action;

        String signature() {
            return conditions.stream()
                    .map(c -> c.key + c.op + c.value)
                    .sorted()
                    .collect(Collectors.joining("|")) + "->" + action;
        }
    }

    static class NumericRange {
        double min, max;
        NumericRange(double min, double max) { this.min = min; this.max = max; }
    }

    /* ===================== STATE ===================== */

    static List<Path> paths = new ArrayList<>();

    /* ===================== XML HELPERS ===================== */

    static List<Element> children(Element e, String tag) {
        List<Element> out = new ArrayList<>();
        NodeList nl = e.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n instanceof Element el && el.getNodeName().equalsIgnoreCase(tag)) {
                out.add(el);
            }
        }
        return out;
    }

    /* ===================== FSML WALK ===================== */

    static void walk(Element node, List<Condition> inherited) {
        if (node == null) return;

        List<Condition> local = new ArrayList<>(inherited);

        for (Element c : children(node, "CONDITION")) {
            Condition cond = new Condition();
            cond.key = c.getAttribute("DecisionKey");
            cond.op  = c.getAttribute("Type");
            cond.value = c.getAttribute("Value");
            local.add(cond);
        }

        for (Element a : children(node, "ACTION")) {
            Path p = new Path();
            p.conditions.addAll(local);
            p.action = a.getAttribute("Label");
            paths.add(p);
        }

        for (Element child : children(node, "NODE")) {
            walk(child, local);
        }
    }

    /* ===================== DECISION TABLE ===================== */

    static void writeDecisionTable() throws Exception {
        try (PrintWriter pw = new PrintWriter("decision-table.csv")) {
            Set<String> vars = paths.stream()
                    .flatMap(p -> p.conditions.stream().map(c -> c.key))
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            pw.println(String.join(",", vars) + ",ACTION");

            for (Path p : paths) {
                Map<String, List<String>> row = new HashMap<>();
                for (Condition c : p.conditions) {
                    row.computeIfAbsent(c.key, k -> new ArrayList<>())
                            .add(c.op + ":" + c.value);
                }

                for (String v : vars) {
                    pw.print(row.containsKey(v)
                            ? String.join("&", row.get(v))
                            : "");
                    pw.print(",");
                }
                pw.println(p.action);
            }
        }
    }

    /* ===================== SHADOWED PATHS ===================== */

    static void writeShadowed() throws Exception {
        try (PrintWriter pw = new PrintWriter("shadowed-paths.txt")) {
            for (int i = 0; i < paths.size(); i++) {
                for (int j = 0; j < paths.size(); j++) {
                    if (i == j) continue;
                    if (subsumes(paths.get(i), paths.get(j))) {
                        pw.println("PATH " + j + " shadowed by PATH " + i);
                    }
                }
            }
        }
    }

    static boolean subsumes(Path a, Path b) {
        if (!a.action.equals(b.action)) return false;
        return b.conditions.containsAll(a.conditions);
    }

    /* ===================== GAP ANALYSIS ===================== */

    static void writeGaps() throws Exception {
        try (PrintWriter pw = new PrintWriter("gap-analysis.txt")) {
            Map<String, List<NumericRange>> ranges = new HashMap<>();

            for (Path p : paths) {
                for (Condition c : p.conditions) {
                    if (!c.value.matches("-?\\d+(\\.\\d+)?")) continue;
                    double v = Double.parseDouble(c.value);
                    if (c.op.equals("ge")) {
                        ranges.computeIfAbsent(c.key, k -> new ArrayList<>())
                                .add(new NumericRange(v, Double.POSITIVE_INFINITY));
                    }
                    if (c.op.equals("lt")) {
                        ranges.computeIfAbsent(c.key, k -> new ArrayList<>())
                                .add(new NumericRange(Double.NEGATIVE_INFINITY, v));
                    }
                }
            }

            for (var e : ranges.entrySet()) {
                pw.println("Variable: " + e.getKey());
                List<NumericRange> rs = e.getValue();
                rs.sort(Comparator.comparingDouble(r -> r.min));
                for (int i = 1; i < rs.size(); i++) {
                    if (rs.get(i - 1).max < rs.get(i).min) {
                        pw.println("  GAP: " + rs.get(i - 1).max + " to " + rs.get(i).min);
                    }
                }
            }
        }
    }

    /* ===================== VISUAL HTML ===================== */

    static void writeHtml() throws Exception {
        try (PrintWriter pw = new PrintWriter("fsml-visual.html")) {
            pw.println("""
                <html><head>
                <style>
                body{font-family:Arial}
                .rule{border:1px solid #ccc;margin:10px;padding:10px;border-radius:8px}
                .cond{color:#333}
                .action{background:#ffe0a3;padding:5px;border-radius:6px;display:inline-block}
                </style></head><body>
                <h1>FSML Decision Paths</h1>
                """);

            int i = 1;
            for (Path p : paths) {
                pw.println("<div class='rule'><b>Rule " + (i++) + "</b><br/>");
                for (Condition c : p.conditions) {
                    pw.println("<div class='cond'>" + c + "</div>");
                }
                pw.println("<div class='action'>" + p.action + "</div></div>");
            }

            pw.println("</body></html>");
        }
    }

    /* ===================== MAIN ===================== */

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("Usage: java FsmlAnalyzerAllInOne <fsml-file>");
            return;
        }

        Document doc = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(new File(args[0]));

        Element strategy = (Element) doc.getElementsByTagName("STRATEGY").item(0);
        Element rootNode = (Element) strategy.getElementsByTagName("NODE").item(0);

        walk(rootNode, new ArrayList<>());

        writeDecisionTable();
        writeShadowed();
        writeGaps();
        writeHtml();

        System.out.println("TOTAL PATHS: " + paths.size());
    }
}
