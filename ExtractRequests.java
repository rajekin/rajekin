import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.io.*;
import java.util.*;

public class FsmlAnalyzerAllInOne {

    /* ======================= DATA MODELS ======================= */

    static class Interval {
        Double min, max;

        Interval(Double min, Double max) {
            this.min = min;
            this.max = max;
        }

        boolean covers(Interval o) {
            if (o == null) return true;
            if (min != null && o.min != null && min > o.min) return false;
            if (max != null && o.max != null && max < o.max) return false;
            return true;
        }

        public String toString() {
            if (min == null && max == null) return "NAN";
            if (min == null) return "<= " + max;
            if (max == null) return ">= " + min;
            return min + " - " + max;
        }
    }

    static class Path {
        Map<String, Interval> numeric = new LinkedHashMap<>();
        Map<String, String> categorical = new LinkedHashMap<>();
        String action;
        int id;
        boolean invalid = false;

        Path copy() {
            Path p = new Path();
            p.numeric.putAll(this.numeric);
            p.categorical.putAll(this.categorical);
            return p;
        }
    }

    static List<Path> paths = new ArrayList<>();
    static Set<String> allAttributes = new LinkedHashSet<>();

    /* ======================= XML HELPERS ======================= */

    static List<Element> children(Element e, String tag) {
        List<Element> out = new ArrayList<>();
        NodeList nl = e.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            if (nl.item(i) instanceof Element) {
                Element c = (Element) nl.item(i);
                if (c.getTagName().equals(tag)) out.add(c);
            }
        }
        return out;
    }

    static Element firstChild(Element e, String tag) {
        for (Element c : children(e, tag)) return c;
        return null;
    }

    /* ======================= CONDITION EXTRACTION ======================= */

    static void extractConditions(Element c, Path p) {

        String key = c.getAttribute("DecisionKey");
        String type = c.getAttribute("Type");
        String val = c.getAttribute("Value");

        allAttributes.add(key);

        if ("and".equalsIgnoreCase(type)) {
            for (Element sub : children(c, "CONDITION")) {
                extractConditions(sub, p);
            }
            return;
        }

        if (val == null || val.isEmpty()) {
            p.categorical.put(key, "NAN");
            return;
        }

        if ("ge".equals(type) || "gt".equals(type) ||
            "le".equals(type) || "lt".equals(type)) {

            Double num = Double.valueOf(val);
            Interval local;

            if ("ge".equals(type)) local = new Interval(num, null);
            else if ("gt".equals(type)) local = new Interval(num + 0.0001, null);
            else if ("le".equals(type)) local = new Interval(null, num);
            else local = new Interval(null, num - 0.0001);

            Interval base = p.numeric.get(key);
            if (base == null) {
                p.numeric.put(key, local);
            } else {
                Double min = base.min, max = base.max;
                if (local.min != null) min = (min == null) ? local.min : Math.max(min, local.min);
                if (local.max != null) max = (max == null) ? local.max : Math.min(max, local.max);
                if (min != null && max != null && min > max) {
                    p.invalid = true;
                    return;
                }
                p.numeric.put(key, new Interval(min, max));
            }
        } else {
            p.categorical.put(key, val);
        }
    }

    /* ======================= TREE WALK ======================= */

    static void walk(Element node, Path incoming) {

        Path cur = incoming.copy();

        // AND conditions at this node
        for (Element c : children(node, "CONDITION")) {
            extractConditions(c, cur);
            if (cur.invalid) return;
        }

        Element act = firstChild(node, "ACTIONS");
        List<Element> kids = children(node, "NODE");

        if (act != null && kids.isEmpty()) {
            cur.action = act.getAttribute("Label");
            cur.id = paths.size() + 1;
            paths.add(cur);
            return;
        }

        for (Element k : kids) {
            walk(k, cur);
        }
    }

    /* ======================= SHADOW DETECTION ======================= */

    static boolean shadows(Path a, Path b) {
        if (!Objects.equals(a.action, b.action)) return false;

        for (String k : allAttributes) {
            Interval ai = a.numeric.get(k);
            Interval bi = b.numeric.get(k);
            if (ai != null && !ai.covers(bi)) return false;

            String ac = a.categorical.get(k);
            String bc = b.categorical.get(k);
            if (ac != null && bc != null && !ac.equals(bc)) return false;
        }
        return true;
    }

    /* ======================= OUTPUT ======================= */

    static void writeCSV() throws Exception {
        PrintWriter out = new PrintWriter("decision_table.csv");
        out.print("ID");
        for (String a : allAttributes) out.print("," + a);
        out.println(",Action");

        for (Path p : paths) {
            out.print(p.id);
            for (String a : allAttributes) {
                if (p.numeric.containsKey(a)) out.print("," + p.numeric.get(a));
                else if (p.categorical.containsKey(a)) out.print("," + p.categorical.get(a));
                else out.print(",NAN");
            }
            out.println("," + p.action);
        }
        out.close();
    }

    static void writeHTML() throws Exception {
        PrintWriter out = new PrintWriter("decision_table.html");
        out.println("<html><head><style>");
        out.println("table{border-collapse:collapse;font-family:Arial}");
        out.println("th,td{border:1px solid #444;padding:6px}");
        out.println("th{background:#222;color:white}");
        out.println("</style></head><body>");
        out.println("<h2>FSML Decision Table</h2>");
        out.println("<table><tr><th>ID</th>");
        for (String a : allAttributes) out.println("<th>" + a + "</th>");
        out.println("<th>Action</th></tr>");

        for (Path p : paths) {
            out.println("<tr><td>" + p.id + "</td>");
            for (String a : allAttributes) {
                if (p.numeric.containsKey(a)) out.println("<td>" + p.numeric.get(a) + "</td>");
                else if (p.categorical.containsKey(a)) out.println("<td>" + p.categorical.get(a) + "</td>");
                else out.println("<td>NAN</td>");
            }
            out.println("<td>" + p.action + "</td></tr>");
        }
        out.println("</table></body></html>");
        out.close();
    }

    static void writeShadowAnalysis() throws Exception {
        PrintWriter out = new PrintWriter("shadowed_paths.txt");
        for (int i = 0; i < paths.size(); i++) {
            for (int j = 0; j < paths.size(); j++) {
                if (i != j && shadows(paths.get(i), paths.get(j))) {
                    out.println("Path " + paths.get(j).id +
                            " is shadowed by Path " + paths.get(i).id);
                }
            }
        }
        out.close();
    }

    /* ======================= MAIN ======================= */

    public static void main(String[] args) throws Exception {

        DocumentBuilder db = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        Document doc = db.parse(new File("fsml.xml"));

        Element root = doc.getDocumentElement();
        walk(root, new Path());

        writeCSV();
        writeHTML();
        writeShadowAnalysis();

        System.out.println("Paths generated: " + paths.size());
    }
}
