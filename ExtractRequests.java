import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.io.*;
import java.util.*;

public class FsmlAnalyzerMain {

    /* ======================= DATA MODELS ======================= */

    static class Cond {
        String key;   // DecisionKey
        String type;  // ge/lt/eq/and/true/etc
        String value; // Value

        Cond(String k, String t, String v) { key=k; type=t; value=v; }

        @Override public String toString() {
            String v = (value == null || value.isBlank() || "NaN".equalsIgnoreCase(value)) ? "NAN" : value;
            return key + " " + type + " " + v;
        }
    }

    static class Path {
        List<Cond> conds = new ArrayList<>();
        String action;
        int id;

        Path copy() {
            Path p = new Path();
            p.conds.addAll(this.conds);
            return p;
        }
    }

    static final List<Path> PATHS = new ArrayList<>();
    static final LinkedHashSet<String> ALL_KEYS = new LinkedHashSet<>();

    /* ======================= XML HELPERS (namespace safe) ======================= */

    static String localName(Node n) {
        String ln = n.getLocalName();
        if (ln != null) return ln;
        String nn = n.getNodeName();
        int i = nn.indexOf(':');
        return i >= 0 ? nn.substring(i + 1) : nn;
    }

    static boolean isTag(Node n, String name) {
        return n instanceof Element && name.equalsIgnoreCase(localName(n));
    }

    static List<Element> children(Element p, String name) {
        List<Element> out = new ArrayList<>();
        if (p == null) return out;
        NodeList nl = p.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (isTag(n, name)) out.add((Element) n);
        }
        return out;
    }

    static Element firstChild(Element p, String name) {
        List<Element> c = children(p, name);
        return c.isEmpty() ? null : c.get(0);
    }

    static String attr(Element e, String a) {
        String v = e.getAttribute(a);
        return (v == null) ? "" : v;
    }

    /* ======================= CONDITION EXTRACTION ======================= */

    static void addCond(Path p, String key, String type, String value) {
        if (key == null || key.isBlank()) return;

        // normalize NAN handling
        String v = value;
        if (v == null || v.isBlank() || "NaN".equalsIgnoreCase(v)) v = "NAN";

        ALL_KEYS.add(key);
        p.conds.add(new Cond(key, type == null ? "" : type, v));
    }

    // FSML can have <CONDITION Type="and"> with nested <CONDITION.../>
    static void extractConditionRecursive(Element condEl, Path p) {
        String type = attr(condEl, "Type");

        // Container node: AND block
        if ("and".equalsIgnoreCase(type)) {
            for (Element sub : children(condEl, "CONDITION")) {
                extractConditionRecursive(sub, p);
            }
            return;
        }

        // Type="true" is the root placeholder, ignore
        if ("true".equalsIgnoreCase(type)) return;

        String key = attr(condEl, "DecisionKey");
        String val = attr(condEl, "Value");

        addCond(p, key, type, val);
    }

    /* ======================= TREE WALK (AND accumulation across NODEs) ======================= */

    static void walkNode(Element node, Path incoming) {
        if (node == null) return;

        Path cur = incoming.copy();

        // All conditions at this NODE are ANDed
        for (Element c : children(node, "CONDITION")) {
            extractConditionRecursive(c, cur);
        }

        // ACTIONS is the leaf action element in your FSML
        Element act = firstChild(node, "ACTIONS");
        List<Element> kids = children(node, "NODE");

        // If this node has an action, record a path (even if formatting includes whitespace)
        if (act != null) {
            Path leaf = cur.copy();
            leaf.action = attr(act, "Label");
            leaf.id = PATHS.size() + 1;
            PATHS.add(leaf);
            // Note: FSML often uses action as leaf; if it ALSO has kids, keep walking too
        }

        for (Element k : kids) {
            walkNode(k, cur);
        }
    }

    /* ======================= OUTPUT (simple + correct) ======================= */

    static void writeDecisionTableCsv(String file) throws Exception {
        try (PrintWriter out = new PrintWriter(file)) {
            out.print("PATH_ID");
            for (String k : ALL_KEYS) out.print("," + k);
            out.println(",ACTION");

            for (Path p : PATHS) {
                Map<String, List<String>> m = new LinkedHashMap<>();
                for (Cond c : p.conds) {
                    m.computeIfAbsent(c.key, kk -> new ArrayList<>()).add(c.type + ":" + c.value);
                }

                out.print(p.id);
                for (String k : ALL_KEYS) {
                    List<String> vals = m.get(k);
                    out.print(",");
                    if (vals == null || vals.isEmpty()) out.print("NAN");
                    else out.print(String.join("&", vals));
                }
                out.println("," + (p.action == null ? "NAN" : p.action));
            }
        }
    }

    static void writeHtml(String file) throws Exception {
        try (PrintWriter out = new PrintWriter(file)) {
            out.println("<html><head><meta charset='UTF-8'><style>");
            out.println("body{font-family:Arial;background:#f4f6f8;margin:20px}");
            out.println(".card{background:#fff;border-radius:10px;padding:12px;margin:12px 0;box-shadow:0 2px 8px rgba(0,0,0,.12);max-width:1000px}");
            out.println(".t{font-weight:700;color:#2c3e50;margin-bottom:8px}");
            out.println(".c{margin-left:18px;color:#34495e}");
            out.println(".a{margin-top:10px;font-weight:700;color:#c0392b;background:#fff3cd;padding:6px 10px;border-radius:8px;display:inline-block}");
            out.println("</style></head><body>");
            out.println("<h1>FSML Rules Extract</h1>");
            out.println("<div>Total Paths: " + PATHS.size() + "</div>");

            for (Path p : PATHS) {
                out.println("<div class='card'>");
                out.println("<div class='t'>Rule " + p.id + "</div>");
                for (Cond c : p.conds) out.println("<div class='c'>➜ " + esc(c.toString()) + "</div>");
                out.println("<div class='a'>ACTION → " + esc(p.action == null ? "NAN" : p.action) + "</div>");
                out.println("</div>");
            }

            out.println("</body></html>");
        }
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");
    }

    /* ======================= MAIN ======================= */

    public static void main(String[] args) throws Exception {
        String fsml = (args.length > 0) ? args[0] : "PenFed_AR_Expert_09042025.fsml";

        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true); // important for FSML variants
        Document doc = f.newDocumentBuilder().parse(new File(fsml));

        // Find STRATEGY (namespace-safe)
        Element strategy = null;
        NodeList all = doc.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Node n = all.item(i);
            if (isTag(n, "STRATEGY")) { strategy = (Element) n; break; }
        }
        if (strategy == null) throw new IllegalStateException("No <STRATEGY> found in FSML.");

        // Find root NODE under STRATEGY
        Element rootNode = firstChild(strategy, "NODE");
        if (rootNode == null) throw new IllegalStateException("No <NODE> found under <STRATEGY>.");

        walkNode(rootNode, new Path());

        // Write outputs
        writeDecisionTableCsv("decision-table.csv");
        writeHtml("fsml-view.html");

        System.out.println("FSML File: " + fsml);
        System.out.println("TOTAL PATHS: " + PATHS.size());
        System.out.println("Generated: decision-table.csv, fsml-view.html");
    }
}
