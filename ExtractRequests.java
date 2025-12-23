import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.io.*;
import java.util.*;

/**
 * FSML ANALYZER – SINGLE FILE, JAVA 21 SAFE
 * Works with real FICO FSML exports
 */
public class FsmlAnalyzerAllInOne {

    /* ===================== DATA MODEL ===================== */

    static class Variable {
        String name;
        String type; // NUMERIC / CATEGORICAL
        double min;
        double max;
        Set<String> categories = new LinkedHashSet<>();
    }

    static class Condition {
        String variable;
        String operator; // ge, gt, lt, le, eq
        String value;
    }

    static class FsmlNode {
        String label;
        List<Condition> conditions = new ArrayList<>();
        List<FsmlNode> children = new ArrayList<>();
        String action; // resolved decision outcome (if any)
    }

    static class Model {
        Map<String, Variable> variables = new LinkedHashMap<>();
        FsmlNode root;
    }

    static class Interval {
        double start; // inclusive
        double end;   // exclusive

        Interval(double s, double e) {
            this.start = s;
            this.end = e;
        }

        Interval intersect(Interval other) {
            double s = Math.max(this.start, other.start);
            double e = Math.min(this.end, other.end);
            if (s >= e) return null; // dead interval
            return new Interval(s, e);
        }

        public String toString() {
            return "[" + start + "," + end + ")";
        }
    }

    static class DecisionPath {
        Map<String, Interval> numeric = new LinkedHashMap<>();
        Map<String, String> categorical = new LinkedHashMap<>();
        String action;
        String label;

        DecisionPath copy() {
            DecisionPath p = new DecisionPath();
            p.numeric.putAll(this.numeric);
            p.categorical.putAll(this.categorical);
            return p;
        }
    }

    /* ===================== FSML PARSER ===================== */

    static Model parseFsml(File fsmlFile) throws Exception {

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setIgnoringComments(true);
        dbf.setNamespaceAware(false);

        Document doc = dbf.newDocumentBuilder().parse(fsmlFile);
        doc.getDocumentElement().normalize();

        Model model = new Model();

        /* ---- DecisionArea ---- */

        NodeList numericKeys = doc.getElementsByTagName("NumericKey");
        for (int i = 0; i < numericKeys.getLength(); i++) {
            Element e = (Element) numericKeys.item(i);
            Variable v = new Variable();
            v.name = e.getAttribute("ShortName");
            v.type = "NUMERIC";

            Element r = (Element) e.getElementsByTagName("NumericRange").item(0);
            v.min = Double.parseDouble(r.getAttribute("minValue"));
            v.max = Double.parseDouble(r.getAttribute("maxValue"));

            model.variables.put(v.name, v);
        }

        NodeList catKeys = doc.getElementsByTagName("CategoricalKey");
        for (int i = 0; i < catKeys.getLength(); i++) {
            Element e = (Element) catKeys.item(i);
            Variable v = new Variable();
            v.name = e.getAttribute("ShortName");
            v.type = "CATEGORICAL";

            NodeList cats = e.getElementsByTagName("CATEGORY");
            for (int j = 0; j < cats.getLength(); j++) {
                v.categories.add(((Element) cats.item(j)).getAttribute("Value"));
            }
            model.variables.put(v.name, v);
        }

        /* ---- STRATEGY ROOT ---- */

        Element strategy = (Element) doc.getElementsByTagName("STRATEGY").item(0);
        NodeList kids = strategy.getChildNodes();

        for (int i = 0; i < kids.getLength(); i++) {
            org.w3c.dom.Node n = kids.item(i);
            if (n instanceof Element && "NODE".equals(n.getNodeName())) {
                model.root = parseNode((Element) n);
                break;
            }
        }

        return model;
    }

    static FsmlNode parseNode(Element el) {

        FsmlNode node = new FsmlNode();
        node.label = el.getAttribute("Label");

        NodeList children = el.getChildNodes();

        for (int i = 0; i < children.getLength(); i++) {
            org.w3c.dom.Node dom = children.item(i);
            if (!(dom instanceof Element)) continue;

            Element e = (Element) dom;

            // Only DIRECT child conditions
            if ("CONDITION".equals(e.getTagName())
                    && e.getParentNode() == el) {

                Condition c = new Condition();
                c.variable = e.getAttribute("DecisionKey");
                c.operator = e.getAttribute("Type");
                c.value = e.getAttribute("Value");
                node.conditions.add(c);
            }

            // ACTION
            if ("ACTION".equals(e.getTagName())
                    && e.getParentNode() == el) {
                node.action = e.getAttribute("Label");
            }

            // ACTIONS/ACTION
            if ("ACTIONS".equals(e.getTagName())
                    && e.getParentNode() == el) {
                NodeList acts = e.getElementsByTagName("ACTION");
                if (acts.getLength() > 0) {
                    node.action =
                            ((Element) acts.item(0)).getAttribute("Label");
                }
            }

            // DECISION (variant)
            if ("DECISION".equals(e.getTagName())
                    && e.getParentNode() == el) {
                node.action = e.getAttribute("Label");
            }

            // Child NODEs
            if ("NODE".equals(e.getTagName())) {
                node.children.add(parseNode(e));
            }
        }
        return node;
    }

    /* ===================== PATH EXTRACTION ===================== */

    static double resolve(Variable v, String value) {
        if (value == null || value.isBlank()) return v.min;
        if ("LOW".equals(value)) return v.min;
        if ("HIGH".equals(value)) return v.max;
        return Double.parseDouble(value);
    }

    static List<DecisionPath> extractPaths(Model model) {
        List<DecisionPath> paths = new ArrayList<>();
        walk(model.root, new DecisionPath(), model, paths);
        return paths;
    }

    static void walk(FsmlNode node,
                     DecisionPath current,
                     Model model,
                     List<DecisionPath> out) {

        DecisionPath next = current.copy();

        for (Condition c : node.conditions) {
            Variable v = model.variables.get(c.variable);
            if (v == null) continue;

            if ("NUMERIC".equals(v.type)) {
                Interval base =
                        next.numeric.getOrDefault(
                                v.name, new Interval(v.min, v.max));

                double val = resolve(v, c.value);
                Interval local;

                if ("ge".equals(c.operator) || "gt".equals(c.operator)) {
                    local = new Interval(val, v.max);
                } else {
                    local = new Interval(v.min, val);
                }

                Interval merged = base.intersect(local);
                if (merged == null) return; // dead path
                next.numeric.put(v.name, merged);

            } else {
                next.categorical.put(v.name, c.value);
            }
        }

        boolean isLeaf = node.children.isEmpty();

        if (isLeaf) {
            next.action =
                    (node.action != null ? node.action : "NO_ACTION_DEFINED");
            next.label = node.label;
            out.add(next);
            return;
        }

        for (FsmlNode child : node.children) {
            walk(child, next, model, out);
        }
    }

    /* ===================== OUTPUT ===================== */

    static void generateDecisionTable(List<DecisionPath> paths)
            throws Exception {

        try (PrintWriter out =
                     new PrintWriter("decision-table.csv")) {

            Set<String> cols = new LinkedHashSet<>();
            for (DecisionPath p : paths) {
                cols.addAll(p.numeric.keySet());
                cols.addAll(p.categorical.keySet());
            }

            for (String c : cols) out.print(c + ",");
            out.println("ACTION");

            for (DecisionPath p : paths) {
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

    static void generateHtml(List<DecisionPath> paths)
            throws Exception {

        try (PrintWriter out =
                     new PrintWriter("fsml-view.html")) {

            out.println("<html><body>");
            out.println("<h2>FSML Decision Paths</h2><ul>");

            for (DecisionPath p : paths) {
                out.println("<li><b>" + p.label + "</b><ul>");
                p.numeric.forEach(
                        (k, v) -> out.println("<li>" + k + " " + v + "</li>"));
                p.categorical.forEach(
                        (k, v) -> out.println("<li>" + k + " = " + v + "</li>"));
                out.println("<li><b>ACTION:</b> " + p.action + "</li>");
                out.println("</ul></li>");
            }

            out.println("</ul></body></html>");
        }
    }

    /* ===================== MAIN ===================== */

    public static void main(String[] args) throws Exception {

        File fsml = new File("model.fsml"); // <-- your FSML file
        Model model = parseFsml(fsml);

        List<DecisionPath> paths = extractPaths(model);

        System.out.println("Total decision paths: " + paths.size());

        generateDecisionTable(paths);
        generateHtml(paths);

        System.out.println("Generated:");
        System.out.println(" - decision-table.csv");
        System.out.println(" - fsml-view.html");
    }
}
