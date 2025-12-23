import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.io.*;
import java.util.*;

/**
 * FULL FSML ANALYZER – SINGLE FILE – JAVA 21
 * Handles:
 *  - namespaces
 *  - nested NODEs
 *  - flat NODEs (ParentId / TrueNode / FalseNode)
 *  - multiple roots
 *  - multiple strategies
 *  - ACTION / ACTIONS / DECISION
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
        String label;
        String action;

        List<Condition> conditions = new ArrayList<>();
        List<String> childIds = new ArrayList<>(); // graph edges
    }

    static class Model {
        Map<String, Variable> variables = new LinkedHashMap<>();
        Map<String, FsmlNode> nodes = new LinkedHashMap<>();
        Set<String> rootIds = new LinkedHashSet<>();
    }

    static class Interval {
        double start, end;
        Interval(double s, double e) { start = s; end = e; }
        Interval intersect(Interval o) {
            double s = Math.max(start, o.start);
            double e = Math.min(end, o.end);
            return (s >= e) ? null : new Interval(s, e);
        }
        public String toString() { return "[" + start + "," + end + ")"; }
    }

    static class DecisionPath {
        Map<String, Interval> numeric = new LinkedHashMap<>();
        Map<String, String> categorical = new LinkedHashMap<>();
        String action;
        String leafLabel;

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

        NodeList all = doc.getElementsByTagName("*");

        /* ---------- VARIABLES ---------- */
        for (int i = 0; i < all.getLength(); i++) {
            Element e = asElement(all.item(i));
            if (e == null) continue;

            String name = e.getLocalName();

            if ("NumericKey".equalsIgnoreCase(name)) {
                Variable v = new Variable();
                v.name = e.getAttribute("ShortName");
                v.type = "NUMERIC";
                Element r = firstChild(e, "NumericRange");
                v.min = Double.parseDouble(r.getAttribute("minValue"));
                v.max = Double.parseDouble(r.getAttribute("maxValue"));
                model.variables.put(v.name, v);
            }

            if ("CategoricalKey".equalsIgnoreCase(name)) {
                Variable v = new Variable();
                v.name = e.getAttribute("ShortName");
                v.type = "CATEGORICAL";
                NodeList cats = e.getElementsByTagName("*");
                for (int j = 0; j < cats.getLength(); j++) {
                    Element c = asElement(cats.item(j));
                    if (c != null && c.hasAttribute("Value")) {
                        v.categories.add(c.getAttribute("Value"));
                    }
                }
                model.variables.put(v.name, v);
            }
        }

        /* ---------- NODES ---------- */
        for (int i = 0; i < all.getLength(); i++) {
            Element e = asElement(all.item(i));
            if (e == null) continue;
            if (!"NODE".equalsIgnoreCase(e.getLocalName())) continue;

            FsmlNode n = new FsmlNode();
            n.id = e.getAttribute("Id");
            n.label = e.getAttribute("Label");

            // conditions
            NodeList kids = e.getChildNodes();
            for (int j = 0; j < kids.getLength(); j++) {
                Element k = asElement(kids.item(j));
                if (k == null) continue;

                String ln = k.getLocalName();

                if ("CONDITION".equalsIgnoreCase(ln)) {
                    Condition c = new Condition();
                    c.variable = k.getAttribute("DecisionKey");
                    c.operator = k.getAttribute("Type");
                    c.value = k.getAttribute("Value");
                    n.conditions.add(c);
                }

                if ("ACTION".equalsIgnoreCase(ln)
                        || "DECISION".equalsIgnoreCase(ln)) {
                    n.action = k.getAttribute("Label");
                }

                if ("ACTIONS".equalsIgnoreCase(ln)) {
                    Element a = firstChild(k, "ACTION");
                    if (a != null) n.action = a.getAttribute("Label");
                }
            }

            // edges (flat FSML)
            addIfPresent(n.childIds, e.getAttribute("TrueNode"));
            addIfPresent(n.childIds, e.getAttribute("FalseNode"));
            addIfPresent(n.childIds, e.getAttribute("NextNode"));

            model.nodes.put(n.id, n);
        }

        /* ---------- ROOT DETECTION ---------- */
        Set<String> referenced = new HashSet<>();
        for (FsmlNode n : model.nodes.values()) {
            referenced.addAll(n.childIds);
        }

        for (String id : model.nodes.keySet()) {
            if (!referenced.contains(id)) {
                model.rootIds.add(id);
            }
        }

        return model;
    }

    /* ===================== PATH EXTRACTION ===================== */

    static double resolve(Variable v, String val) {
        if (val == null || val.isBlank()) return v.min;
        if ("LOW".equalsIgnoreCase(val)) return v.min;
        if ("HIGH".equalsIgnoreCase(val)) return v.max;
        return Double.parseDouble(val);
    }

    static List<DecisionPath> extractPaths(Model model) {

        List<DecisionPath> paths = new ArrayList<>();

        for (String rootId : model.rootIds) {
            dfs(model.nodes.get(rootId),
                new DecisionPath(),
                model,
                paths,
                new HashSet<>());
        }
        return paths;
    }

    static void dfs(FsmlNode node,
                    DecisionPath current,
                    Model model,
                    List<DecisionPath> out,
                    Set<String> visited) {

        if (node == null || visited.contains(node.id)) return;
        visited.add(node.id);

        DecisionPath next = current.copy();

        for (Condition c : node.conditions) {
            Variable v = model.variables.get(c.variable);
            if (v == null) continue;

            if ("NUMERIC".equals(v.type)) {
                Interval base =
                        next.numeric.getOrDefault(
                                v.name, new Interval(v.min, v.max));

                Interval local = c.operator.startsWith("g")
                        ? new Interval(resolve(v, c.value), v.max)
                        : new Interval(v.min, resolve(v, c.value));

                Interval merged = base.intersect(local);
                if (merged == null) return;
                next.numeric.put(v.name, merged);
            } else {
                next.categorical.put(v.name, c.value);
            }
        }

        // leaf = has action OR no outgoing edges
        if (node.action != null || node.childIds.isEmpty()) {
            next.action = (node.action != null)
                    ? node.action
                    : "DEFAULT / NO HIT";
            next.leafLabel = node.label;
            out.add(next);
            return;
        }

        for (String cid : node.childIds) {
            dfs(model.nodes.get(cid),
                next,
                model,
                out,
                new HashSet<>(visited));
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
                    else
                        out.print("-,");
                }
                out.println(p.action);
            }
        }
    }

    static void generateHtml(List<DecisionPath> paths) throws Exception {

        try (PrintWriter out = new PrintWriter("fsml-view.html")) {
            out.println("<html><body><h2>FSML Decision Paths</h2><ul>");
            for (DecisionPath p : paths) {
                out.println("<li><b>" + p.leafLabel + "</b><ul>");
                p.numeric.forEach((k,v)->out.println("<li>"+k+" "+v+"</li>"));
                p.categorical.forEach((k,v)->out.println("<li>"+k+"="+v+"</li>"));
                out.println("<li><b>ACTION:</b> "+p.action+"</li>");
                out.println("</ul></li>");
            }
            out.println("</ul></body></html>");
        }
    }

    /* ===================== HELPERS ===================== */

    static Element asElement(Node n) {
        return (n instanceof Element) ? (Element) n : null;
    }

    static Element firstChild(Element e, String localName) {
        NodeList nl = e.getElementsByTagName("*");
        for (int i = 0; i < nl.getLength(); i++) {
            Element c = asElement(nl.item(i));
            if (c != null && localName.equalsIgnoreCase(c.getLocalName())) {
                return c;
            }
        }
        return null;
    }

    static void addIfPresent(List<String> l, String v) {
        if (v != null && !v.isBlank()) l.add(v);
    }

    /* ===================== MAIN ===================== */

    public static void main(String[] args) throws Exception {

        File fsml = new File("model.fsml"); // <-- update path
        Model model = parseFsml(fsml);

        List<DecisionPath> paths = extractPaths(model);

        System.out.println("TOTAL DECISION PATHS: " + paths.size());

        generateDecisionTable(paths);
        generateHtml(paths);

        System.out.println("Generated:");
        System.out.println(" - decision-table.csv");
        System.out.println(" - fsml-view.html");
    }
}
