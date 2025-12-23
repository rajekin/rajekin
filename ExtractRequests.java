import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.io.*;
import java.util.*;

public class FsmlAnalyzerAllInOne {

    /* ===================== DATA ===================== */

    static class Variable {
        String name;
        boolean numeric;
        double min, max;
    }

    static class Interval {
        double min, max;
        Interval(double a, double b) { min = a; max = b; }
        Interval intersect(Interval o) {
            double lo = Math.max(min, o.min);
            double hi = Math.min(max, o.max);
            return lo >= hi ? null : new Interval(lo, hi);
        }
        public String toString() { return "["+min+","+max+")"; }
    }

    static class Path {
        Map<String, Interval> numeric = new LinkedHashMap<>();
        Map<String, String> categorical = new LinkedHashMap<>();
        String action;
        String leafTag;

        Path copy() {
            Path p = new Path();
            p.numeric.putAll(numeric);
            p.categorical.putAll(categorical);
            return p;
        }
    }

    static Map<String, Variable> variables = new HashMap<>();
    static List<Path> paths = new ArrayList<>();

    /* ===================== GENERIC HELPERS ===================== */

    static boolean isDecisionNode(Element e) {
        return e.getLocalName().equalsIgnoreCase("NODE");
    }

    static boolean isDecisionLeaf(Element e) {
        return getDirectChild(e, "ACTIONS") != null
            || getDirectChild(e, "ACTION") != null
            || getDirectChild(e, "DECISION") != null;
    }

    static Element getDirectChild(Element parent, String localName) {
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            if (kids.item(i) instanceof Element) {
                Element e = (Element) kids.item(i);
                if (localName.equalsIgnoreCase(e.getLocalName())) {
                    return e;
                }
            }
        }
        return null;
    }

    static List<Element> getDirectChildren(Element parent, String localName) {
        List<Element> list = new ArrayList<>();
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            if (kids.item(i) instanceof Element) {
                Element e = (Element) kids.item(i);
                if (localName.equalsIgnoreCase(e.getLocalName())) {
                    list.add(e);
                }
            }
        }
        return list;
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
                Element r = getDirectChild(e, "NumericRange");
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

    /* ===================== GENERIC WALK ===================== */

    static void walk(Element current, Path path) {

        Path next = path.copy();

        // Apply DIRECT conditions
        for (Element c : getDirectChildren(current, "CONDITION")) {
            String key = c.getAttribute("DecisionKey");
            String type = c.getAttribute("Type");
            String val  = c.getAttribute("Value");

            if ("true".equalsIgnoreCase(type)) continue;
            Variable v = variables.get(key);
            if (v == null) continue;

            if (v.numeric) {
                Interval base = next.numeric
                        .getOrDefault(key, new Interval(v.min, v.max));

                double num;
                if ("LOW".equalsIgnoreCase(val)) num = v.min;
                else if ("HIGH".equalsIgnoreCase(val)) num = v.max;
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

        // Leaf detection (DIRECT ONLY)
        if (isDecisionLeaf(current)
                && getDirectChildren(current, "NODE").isEmpty()) {

            Element a = getDirectChild(current, "ACTIONS");
            if (a == null) a = getDirectChild(current, "ACTION");
            if (a == null) a = getDirectChild(current, "DECISION");

            next.action = a != null ? a.getAttribute("Label") : "NO_ACTION";
            next.leafTag = current.getAttribute("Label");
            paths.add(next);
            return;
        }

        // Recurse into direct NODE children
        for (Element child : getDirectChildren(current, "NODE")) {
            walk(child, next);
        }
    }

    /* ===================== OUTPUT ===================== */

    static void writeCsv() throws Exception {
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
                    else out.print("-,");
                }
                out.println(p.action);
            }
        }
    }

    /* ===================== MAIN ===================== */

    public static void main(String[] args) throws Exception {

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        Document doc = dbf.newDocumentBuilder()
                .parse(new File("model.fsml"));
        doc.getDocumentElement().normalize();

        parseVariables(doc);

        // Find first NODE anywhere (generic root)
        Element root = null;
        NodeList all = doc.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            if (all.item(i) instanceof Element) {
                Element e = (Element) all.item(i);
                if ("NODE".equalsIgnoreCase(e.getLocalName())) {
                    root = e;
                    break;
                }
            }
        }

        walk(root, new Path());

        System.out.println("TOTAL PATHS = " + paths.size());
        writeCsv();
    }
}
