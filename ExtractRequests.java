import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.io.*;
import java.util.*;

public class FsmlAnalyzerAllInOne {

    /* ===================== MODELS ===================== */

    static class Variable {
        String name;
        String type; // NUMERIC / CATEGORICAL
        double min;
        double max;
        Set<String> categories = new HashSet<>();
    }

    static class Condition {
        String key;
        String op;
        String value;
    }

    static class Interval {
        double min, max;
        Interval(double min, double max) {
            this.min = min; this.max = max;
        }
        Interval intersect(Interval o) {
            double lo = Math.max(min, o.min);
            double hi = Math.min(max, o.max);
            return (lo >= hi) ? null : new Interval(lo, hi);
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

    /* ===================== PARSER ===================== */

    static void parseVariables(Document doc) {
        NodeList keys = doc.getElementsByTagName("*");
        for (int i = 0; i < keys.getLength(); i++) {
            Node n = keys.item(i);
            if (!(n instanceof Element)) continue;
            Element e = (Element) n;

            if ("NumericKey".equalsIgnoreCase(e.getLocalName())) {
                Variable v = new Variable();
                v.name = e.getAttribute("ShortName");
                v.type = "NUMERIC";
                Element r = (Element) e.getElementsByTagName("NumericRange").item(0);
                v.min = Double.parseDouble(r.getAttribute("minValue"));
                v.max = Double.parseDouble(r.getAttribute("maxValue"));
                variables.put(v.name, v);
            }

            if ("CategoricalKey".equalsIgnoreCase(e.getLocalName())) {
                Variable v = new Variable();
                v.name = e.getAttribute("ShortName");
                v.type = "CATEGORICAL";
                NodeList cats = e.getElementsByTagName("CATEGORY");
                for (int j = 0; j < cats.getLength(); j++) {
                    v.categories.add(((Element) cats.item(j)).getAttribute("Value"));
                }
                variables.put(v.name, v);
            }
        }
    }

    /* ===================== TREE WALK ===================== */

    static void walk(Element node, Path current) {

        Path next = current.copy();

        NodeList kids = node.getChildNodes();

        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (!(n instanceof Element)) continue;
            Element e = (Element) n;

            if ("CONDITION".equalsIgnoreCase(e.getLocalName())) {
                String key = e.getAttribute("DecisionKey");
                String type = e.getAttribute("Type");
                String val  = e.getAttribute("Value");

                if ("true".equalsIgnoreCase(type)) continue;

                Variable v = variables.get(key);
                if (v == null) continue;

                if ("NUMERIC".equals(v.type)) {
                    Interval base = next.numeric
                            .getOrDefault(key, new Interval(v.min, v.max));

                    double num;
                    if ("LOW".equals(val)) num = v.min;
                    else if ("HIGH".equals(val)) num = v.max;
                    else num = Double.parseDouble(val);

                    Interval local = type.startsWith("g")
                            ? new Interval(num, v.max)
                            : new Interval(v.min, num);

                    Interval merged = base.intersect(local);
                    if (merged == null) return;

                    next.numeric.put(key, merged);
                } else {
                    next.categorical.put(key, val);
                }
            }
        }

        // Leaf
        if (node.getElementsByTagName("ACTIONS").getLength() > 0) {
            Element a = (Element) node.getElementsByTagName("ACTIONS").item(0);
            next.action = a.getAttribute("Label");
            next.leafLabel = node.getAttribute("Label");
            paths.add(next);
            return;
        }

        // Recurse children
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n instanceof Element
                    && "NODE".equalsIgnoreCase(((Element) n).getLocalName())) {
                walk((Element) n, next);
            }
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
                p.numeric.forEach((k,v)->out.println("<li>"+k+" "+v+"</li>"));
                p.categorical.forEach((k,v)->out.println("<li>"+k+"="+v+"</li>"));
                out.println("<li><b>ACTION:</b> "+p.action+"</li>");
                out.println("</ul></li>");
            }
            out.println("</ul></body></html>");
        }
    }

    /* ===================== MAIN ===================== */

    public static void main(String[] args) throws Exception {

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        Document doc = dbf.newDocumentBuilder()
                .parse(new File("model.fsml")); // <-- your file
        doc.getDocumentElement().normalize();

        parseVariables(doc);

        Element strategy = null;
        NodeList all = doc.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Element e = (Element) all.item(i);
            if ("STRATEGY".equalsIgnoreCase(e.getLocalName())) {
                strategy = e;
                break;
            }
        }

        Element root = null;
        NodeList kids = strategy.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            if (kids.item(i) instanceof Element
                    && "NODE".equalsIgnoreCase(((Element) kids.item(i)).getLocalName())) {
                root = (Element) kids.item(i);
                break;
            }
        }

        walk(root, new Path());

        System.out.println("TOTAL DECISION PATHS = " + paths.size());

        writeDecisionTable();
        writeHtml();
    }
}
