import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.io.*;
import java.util.*;

/**
 * FSML ANALYZER – SINGLE FILE – JAVA 21
 * Robust against namespaces, flat NODEs, real FICO FSML exports
 */
public class FsmlAnalyzerAllInOne {

    /* ===================== MODEL ===================== */

    static class Variable {
        String name;
        String type; // NUMERIC / CATEGORICAL
        double min;
        double max;
        Set<String> categories = new LinkedHashSet<>();
    }

    static class Condition {
        String variable;
        String operator;
        String value;
    }

    static class FsmlNode {
        String id;
        String parentId;
        String label;
        List<Condition> conditions = new ArrayList<>();
        List<FsmlNode> children = new ArrayList<>();
        String action;
    }

    static class Model {
        Map<String, Variable> variables = new LinkedHashMap<>();
        Map<String, FsmlNode> nodesById = new LinkedHashMap<>();
        FsmlNode root;
    }

    static class Interval {
        double start;
        double end;

        Interval(double s, double e) {
            this.start = s;
            this.end = e;
        }

        Interval intersect(Interval o) {
            double s = Math.max(start, o.start);
            double e = Math.min(end, o.end);
            if (s >= e) return null;
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
            p.numeric.putAll(numeric);
            p.categorical.putAll(categorical);
            return p;
        }
    }

    /* ===================== PARSER ===================== */

    static Model parseFsml(File fsml) throws Exception {

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        Document doc = dbf.newDocumentBuilder().parse(fsml);
        doc.getDocumentElement().normalize();

        Model model = new Model();

        // ---------- Variables ----------
        NodeList all = doc.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Node n = all.item(i);
            if (!(n instanceof Element)) continue;
            Element e = (Element) n;

            if (e.getLocalName().equalsIgnoreCase("NumericKey")) {
                Variable v = new Variable();
                v.name = e.getAttribute("ShortName");
                v.type = "NUMERIC";
                Element r = (Element) e.getElementsByTagName("*").item(0);
                v.min = Double.parseDouble(r.getAttribute("minValue"));
                v.max = Double.parseDouble(r.getAttribute("maxValue"));
                model.variables.put(v.name, v);
            }

            if (e.getLocalName().equalsIgnoreCase("CategoricalKey")) {
                Variable v = new Variable();
                v.name = e.getAttribute("ShortName");
                v.type = "CATEGORICAL";
                NodeList cats = e.getElementsByTagName("*");
                for (int j = 0; j < cats.getLength(); j++) {
                    Element c = (Element) cats.item(j);
                    if (c.hasAttribute("Value")) {
                        v.categories.add(c.getAttribute("Value"));
                    }
                }
                model.variables.put(v.name, v);
            }
        }

        // ---------- Collect ALL NODEs (flat) ----------
        for (int i = 0; i < all.getLength(); i++) {
            Node n = all.item(i);
            if (!(n instanceof Element)) continue;
            Element e = (Element) n;

            if (!e.getLocalName().equalsIgnoreCase("NODE")) continue;

            FsmlNode fn = new FsmlNode();
            fn.id = e.getAttribute("Id");
            fn.parentId = e.getAttribute("ParentId");
            fn.label = e.getAttribute("Label");

            NodeList kids = e.getChildNodes();
            for (int j = 0; j < kids.getLength(); j++) {
                Node k = kids.item(j);
                if (!(k instanceof Element)) continue;
                Element ke = (Element) k;

                if (ke.getLocalName().equalsIgnoreCase("CONDITION")) {
                    Condition c = new Condition();
                    c.variable = ke.getAttribute("DecisionKey");
                    c.operator = ke.getAttribute("Type");
                    c.value = ke.getAttribute("Value");
                    fn.conditions.add(c);
                }

                if (ke.getLocalName().equalsIgnoreCase("ACTION")
                        || ke.getLocalName().equalsIgnoreCase("DECISION")) {
                    fn.action = ke.getAttribute("Label");
                }
            }
            model.nodesById.put(fn.id, fn);
        }

        // ---------- Build Tree ----------
        for (FsmlNode n : model.nodesById.values()) {
            if (n.parentId == null || n.parentId.isBlank()) {
                model.root = n;
            } else {
                FsmlNode parent = model.nodesById.get(n.parentId);
                if (parent != null) parent.children.add(n);
            }
        }

        if (model.root == null) {
            throw new RuntimeException("FSML root NODE not found");
        }

        return model;
    }

    /* ===================== PATH LOGIC ===================== */

    static double resolve(Variable v, String val) {
        if (val == null || val.isBlank()) return v.min;
        if ("LOW".equalsIgnoreCase(val)) return v.min;
        if ("HIGH".equalsIgnoreCase(val)) return v.max;
        return Double.parseDouble(val);
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
                Interval base = next.numeric
                        .getOrDefault(v.name, new Interval(v.min, v.max));

                double val = resolve(v, c.value);
                Interval local = c.operator.startsWith("g")
                        ? new Interval(val, v.max)
                        : new Interval(v.min, val);

                Interval merged = base.intersect(local);
                if (merged == null) return;
                next.numeric.put(v.name, merged);
            } else {
                next.categorical.put(v.name, c.value);
            }
        }

        if (node.children.isEmpty()) {
            next.action = node.action != null ? node.action : "NO_DECISION";
            next.label = node.label;
            out.add(next);
            return;
        }

        for (FsmlNode child : node.children) {
            walk(child, next, model, out);
        }
    }

    /* ===================== OUTPUT ===================== */

    static void generateDecisionTable(List<DecisionPath> paths) throws Exception {
        try (PrintWriter out = new PrintWriter("decision-table.csv")) {
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
                    else out.print("-,");
                }
                out.println(p.action);
            }
        }
    }

    static void generateHtml(List<DecisionPath> paths) throws Exception {
        try (PrintWriter out = new PrintWriter("fsml-view.html")) {
            out.println("<html><body><h2>FSML Decision Paths</h2><ul>");
            for (DecisionPath p : paths) {
                out.println("<li><b>" + p.label + "</b><ul>");
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
