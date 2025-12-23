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

    // Handles namespaces safely
    static String local(Node n) {
        if (n.getLocalName() != null) return n.getLocalName();
        String name = n.getNodeName();
        int idx = name.indexOf(':');
        return idx >= 0 ? name.substring(idx + 1) : name;
    }

    static List<Element> children(Element parent, String tag) {
        List<Element> list = new ArrayList<>();
        if (parent == null) return list;

        NodeList nl = parent.getChildNodes();
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

    static Element firstChild(Element parent, String tag) {
        List<Element> list = children(parent, tag);
        return list.isEmpty() ? null : list.get(0);
    }

    /* ===================== VARIABLE PARSING ===================== */

    static void parseVariables(Document doc) {
        NodeList all = doc.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Node n = all.item(i);
            if (!(n instanceof Element)) continue;
            Element e = (Element) n;

            String name = local(e);

            if ("NumericKey".equalsIgnoreCase(name)) {
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

            if ("CategoricalKey".equalsIgnoreCase(name)) {
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

    /* ===================== OUTPUT ===================== */

    static void writeDecisionTable() throws Exception {
        PrintWriter out = new PrintWriter("decision-table.csv");
        out.print("RULE,");
        for (String v : variables.keySet()) out.print(v + ",");
        out.println("ACTION");

        int r = 1;
        for (Path p : paths) {
            out.print("R" + r++ + ",");
            for (Variable v : variables.values()) {
                if (v.numeric) {
                    Interval i = p.numeric.get(v.name);
                    out.print((i == null ? "ANY" : i) + ",");
                } else {
                    String c = p.categorical.get(v.name);
                    out.print((c == null ? "ANY" : c) + ",");
                }
            }
            out.println(p.action);
        }
        out.close();
    }

    static void writeHtml() throws Exception {
        PrintWriter out = new PrintWriter("fsml-view.html");
        out.println("<html><body><h2>Decision Table</h2><table border='1'>");
        out.print("<tr>");
        for (String v : variables.keySet()) out.print("<th>" + v + "</th>");
        out.println("<th>ACTION</th></tr>");

        for (Path p : paths) {
            out.print("<tr>");
            for (Variable v : variables.values()) {
                if (v.numeric) {
                    Interval i = p.numeric.get(v.name);
                    out.print("<td>" + (i == null ? "ANY" : i) + "</td>");
                } else {
                    String c = p.categorical.get(v.name);
                    out.print("<td>" + (c == null ? "ANY" : c) + "</td>");
                }
            }
            out.print("<td><b>" + p.action + "</b></td>");
            out.println("</tr>");
        }
        out.println("</table></body></html>");
        out.close();
    }

    /* ===================== MAIN ===================== */

    public static void main(String[] args) throws Exception {

        Document doc = DocumentBuilderFactory
                .newInstance()
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

        if (strategy == null) {
            throw new RuntimeException("No STRATEGY element found in FSML");
        }

        Element root = firstChild(strategy, "NODE");
        if (root == null) {
            throw new RuntimeException("No root NODE found under STRATEGY");
        }

        walk(root, new Path());

        writeDecisionTable();
        writeHtml();

        System.out.println("TOTAL PATHS = " + paths.size());
    }
}
