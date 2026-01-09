import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

public class FsmlAnalyzerMain {

    /* ======================= MODELS ======================= */

    static class Bounds {
        final double min;
        final double max;
        Bounds(double min, double max) { this.min = min; this.max = max; }
    }

    static class Interval {
        Double min; boolean minInc;
        Double max; boolean maxInc;

        Interval(Double min, boolean minInc, Double max, boolean maxInc) {
            this.min = min; this.minInc = minInc;
            this.max = max; this.maxInc = maxInc;
        }

        static Interval universe(Bounds b) {
            if (b == null) return new Interval(null, true, null, true);
            return new Interval(b.min, true, b.max, true);
        }

        Interval intersect(Interval o) {
            if (o == null) return null;

            Double nMin = this.min;
            boolean nMinInc = this.minInc;

            if (o.min != null) {
                if (nMin == null || o.min > nMin) {
                    nMin = o.min;
                    nMinInc = o.minInc;
                } else if (Objects.equals(o.min, nMin)) {
                    nMinInc = this.minInc && o.minInc;
                }
            }

            Double nMax = this.max;
            boolean nMaxInc = this.maxInc;

            if (o.max != null) {
                if (nMax == null || o.max < nMax) {
                    nMax = o.max;
                    nMaxInc = o.maxInc;
                } else if (Objects.equals(o.max, nMax)) {
                    nMaxInc = this.maxInc && o.maxInc;
                }
            }

            // empty check
            if (nMin != null && nMax != null) {
                if (nMin > nMax) return null;
                if (Objects.equals(nMin, nMax) && !(nMinInc && nMaxInc)) return null;
            }

            return new Interval(nMin, nMinInc, nMax, nMaxInc);
        }

        boolean covers(Interval o) {
            if (o == null) return true;

            // min: this <= o
            if (this.min != null) {
                if (o.min == null) return false;
                int c = Double.compare(this.min, o.min);
                if (c > 0) return false;
                if (c == 0 && !this.minInc && o.minInc) return false;
            }

            // max: this >= o
            if (this.max != null) {
                if (o.max == null) return false;
                int c = Double.compare(this.max, o.max);
                if (c < 0) return false;
                if (c == 0 && !this.maxInc && o.maxInc) return false;
            }

            return true;
        }

        String asMath(String var) {
            if (min == null && max == null) return var + " = NAN";

            if (min != null && max != null && Objects.equals(min, max) && minInc && maxInc) {
                return var + " = " + fmt(min);
            }

            if (min == null) return var + " " + (maxInc ? "≤" : "<") + " " + fmt(max);
            if (max == null) return var + " " + (minInc ? "≥" : ">") + " " + fmt(min);

            String left = fmt(min) + " " + (minInc ? "≤" : "<") + " " + var;
            String right = var + " " + (maxInc ? "≤" : "<") + " " + fmt(max);
            return left + " AND " + right;
        }
    }

    static class Path {
        int id;
        String action;

        Map<String, Interval> numeric = new LinkedHashMap<>();
        Map<String, List<String>> categorical = new LinkedHashMap<>();

        boolean invalid;

        Path copy() {
            Path p = new Path();
            p.numeric.putAll(this.numeric);
            for (var e : this.categorical.entrySet()) {
                p.categorical.put(e.getKey(), new ArrayList<>(e.getValue()));
            }
            return p;
        }
    }

    /* ======================= STATE ======================= */

    static final List<Path> PATHS = new ArrayList<>();
    static final LinkedHashSet<String> ALL_KEYS = new LinkedHashSet<>();
    static final Map<String, Bounds> NUM_BOUNDS = new HashMap<>();
    static final List<String> VARIABLE_ORDER = new ArrayList<>();

    /* ======================= XML HELPERS (namespace safe) ======================= */

    static String localName(Node n) {
        String ln = n.getLocalName();
        if (ln != null) return ln;
        String nn = n.getNodeName();
        int i = nn.indexOf(':');
        return i >= 0 ? nn.substring(i + 1) : nn;
    }

    static boolean isTag(Node n, String name) {
        return (n instanceof Element) && name.equalsIgnoreCase(localName(n));
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
        return v == null ? "" : v;
    }

    /* ======================= PARSE VARIABLE ORDER + BOUNDS ======================= */

    static void parseVariableOrder(Document doc) {
        NodeList all = doc.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Node n = all.item(i);
            if (isTag(n, "VARIABLE-ORDER")) {
                String txt = n.getTextContent();
                if (txt != null) {
                    String[] parts = txt.split("[,\\s]+");
                    for (String p : parts) {
                        String s = p.trim();
                        if (!s.isEmpty()) VARIABLE_ORDER.add(s);
                    }
                }
                break;
            }
        }
    }

    static void parseNumericBounds(Document doc) {
        NodeList all = doc.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Node n = all.item(i);
            if (!isTag(n, "NumericKey")) continue;
            Element nk = (Element) n;

            String shortName = attr(nk, "ShortName");
            if (shortName.isBlank()) shortName = attr(nk, "LongName");
            if (shortName.isBlank()) continue;

            Element nr = firstChild(nk, "NumericRange");
            if (nr == null) continue;

            String minV = attr(nr, "minValue");
            String maxV = attr(nr, "maxValue");

            Double min = safeParseDouble(minV);
            Double max = safeParseDouble(maxV);
            if (min != null && max != null) {
                NUM_BOUNDS.put(shortName, new Bounds(min, max));
            }
        }
    }

    /* ======================= VALUE HANDLING (LOW/HIGH/NAN) ======================= */

    static boolean isNanToken(String v) {
        return v == null || v.isBlank() || "NaN".equalsIgnoreCase(v) || "NAN".equalsIgnoreCase(v);
    }

    static String resolveValue(String key, String rawVal) {
        if (isNanToken(rawVal)) return "NAN";

        if ("LOW".equalsIgnoreCase(rawVal)) {
            Bounds b = NUM_BOUNDS.get(key);
            return b == null ? "LOW" : fmt(b.min);
        }
        if ("HIGH".equalsIgnoreCase(rawVal)) {
            Bounds b = NUM_BOUNDS.get(key);
            return b == null ? "HIGH" : fmt(b.max);
        }
        return rawVal;
    }

    static boolean looksNumeric(String s) {
        if (s == null) return false;
        return s.matches("[-+]?\\d+(\\.\\d+)?");
    }

    static Double safeParseDouble(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty() || "NaN".equalsIgnoreCase(t)) return null;
        try { return Double.parseDouble(t); } catch (Exception e) { return null; }
    }

    static String opSymbol(String op) {
        if (op == null) return "";
        switch (op) {
            case "lt": return "<";
            case "le": return "≤";
            case "gt": return ">";
            case "ge": return "≥";
            case "eq": return "=";
            case "ne": return "≠";
            default: return op;
        }
    }

    /* ======================= CONDITION EXTRACTION ======================= */

    static void addCategorical(Path p, String key, String op, String value) {
        ALL_KEYS.add(key);
        p.categorical.computeIfAbsent(key, k -> new ArrayList<>()).add(op + ":" + value);
    }

    static void addNumeric(Path p, String key, Interval local) {
        ALL_KEYS.add(key);
        Interval base = p.numeric.get(key);

        if (base == null) {
            // start from universe if we have bounds, else use local directly
            Bounds b = NUM_BOUNDS.get(key);
            if (b != null) base = Interval.universe(b);
            else base = new Interval(null, true, null, true);
        }

        Interval merged = base.intersect(local);
        if (merged == null) {
            p.invalid = true;
            return;
        }
        p.numeric.put(key, merged);
    }

    // Nested conditions: <CONDITION Type="and"> contains <CONDITION .../>
    static void extractConditionRecursive(Element condEl, Path p) {
        String type = attr(condEl, "Type");

        if ("and".equalsIgnoreCase(type)) {
            for (Element sub : children(condEl, "CONDITION")) extractConditionRecursive(sub, p);
            return;
        }
        if ("true".equalsIgnoreCase(type)) return;

        String key = attr(condEl, "DecisionKey");
        String rawVal = attr(condEl, "Value");
        if (key == null || key.isBlank()) return;

        String val = resolveValue(key, rawVal);

        // NAN never parsed numerically — just keep as categorical so analysis doesn't fail
        if ("NAN".equalsIgnoreCase(val)) {
            addCategorical(p, key, "eq", "NAN");
            return;
        }

        // Numeric constraint?
        if (looksNumeric(val) && (type.equals("ge") || type.equals("gt") || type.equals("le") || type.equals("lt") || type.equals("eq"))) {
            Double num = safeParseDouble(val);
            if (num == null) {
                // fallback categorical, never throw
                addCategorical(p, key, type, val);
                return;
            }

            Interval local;
            switch (type) {
                case "ge": local = new Interval(num, true, null, true); break;
                case "gt": local = new Interval(num, false, null, true); break;
                case "le": local = new Interval(null, true, num, true); break;
                case "lt": local = new Interval(null, true, num, false); break;
                case "eq": local = new Interval(num, true, num, true); break;
                default:   local = null;
            }
            if (local != null) addNumeric(p, key, local);
            return;
        }

        // Otherwise categorical
        addCategorical(p, key, type, val);
    }

    /* ======================= TREE WALK ======================= */

    static void walkNode(Element node, Path incoming) {
        if (node == null) return;

        Path cur = incoming.copy();

        for (Element c : children(node, "CONDITION")) {
            extractConditionRecursive(c, cur);
            if (cur.invalid) return;
        }

        Element act = firstChild(node, "ACTIONS");
        List<Element> kids = children(node, "NODE");

        // record a rule whenever ACTIONS exists
        if (act != null) {
            Path leaf = cur.copy();
            leaf.action = attr(act, "Label");
            leaf.id = PATHS.size() + 1;
            PATHS.add(leaf);
        }

        for (Element k : kids) walkNode(k, cur);
    }

    /* ======================= RENDER CONDITIONS (merged ranges) ======================= */

    static List<String> orderedVarsForOutput() {
        if (!VARIABLE_ORDER.isEmpty()) {
            List<String> out = new ArrayList<>();
            for (String v : VARIABLE_ORDER) if (ALL_KEYS.contains(v) || NUM_BOUNDS.containsKey(v)) out.add(v);
            for (String v : ALL_KEYS) if (!out.contains(v)) out.add(v);
            return out;
        }
        return new ArrayList<>(ALL_KEYS);
    }

    static List<String> renderConditions(Path p) {
        List<String> lines = new ArrayList<>();

        // numeric intervals
        for (var e : p.numeric.entrySet()) {
            String k = e.getKey();
            Interval in = e.getValue();
            if (in == null) continue;

            // suppress printing universe-only constraints
            Bounds b = NUM_BOUNDS.get(k);
            if (b != null) {
                Interval u = Interval.universe(b);
                if (u.covers(in) && in.covers(u)) continue;
            }
            lines.add(in.asMath(k));
        }

        // categorical
        for (var e : p.categorical.entrySet()) {
            String k = e.getKey();
            List<String> clauses = e.getValue();
            if (clauses == null || clauses.isEmpty()) continue;

            String joined = clauses.stream().map(cl -> {
                String[] parts = cl.split(":", 2);
                String op = parts[0];
                String v = parts.length > 1 ? parts[1] : "";
                return k + " " + opSymbol(op) + " " + v;
            }).collect(Collectors.joining(" AND "));
            lines.add(joined);
        }

        // stable sort by VARIABLE_ORDER
        List<String> order = orderedVarsForOutput();
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < order.size(); i++) idx.put(order.get(i), i);

        lines.sort(Comparator.comparingInt(s -> idx.getOrDefault(s.split("\\s+",2)[0], Integer.MAX_VALUE)));
        return lines;
    }

    /* ======================= DECISION TABLE CSV ======================= */

    static void writeDecisionCsv(String file) throws Exception {
        List<String> cols = orderedVarsForOutput();
        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            out.print("RULE_ID");
            for (String c : cols) out.print("," + c);
            out.println(",ACTION");

            for (Path p : PATHS) {
                out.print(p.id);
                for (String v : cols) {
                    String cell;
                    if (p.numeric.containsKey(v)) {
                        cell = p.numeric.get(v).asMath(v);
                    } else if (p.categorical.containsKey(v)) {
                        cell = p.categorical.get(v).stream().map(cl -> {
                            String[] parts = cl.split(":", 2);
                            String op = parts[0];
                            String val = parts.length > 1 ? parts[1] : "";
                            return opSymbol(op) + " " + val;
                        }).collect(Collectors.joining(" AND "));
                    } else {
                        cell = "NAN";
                    }
                    out.print("," + csvEscape(cell));
                }
                out.println("," + csvEscape(p.action == null ? "NAN" : p.action));
            }
        }
    }

    static String csvEscape(String s) {
        if (s == null) return "";
        String x = s.replace("\"", "\"\"");
        if (x.contains(",") || x.contains("\"") || x.contains("\n")) return "\"" + x + "\"";
        return x;
    }

    /* ======================= HTML OUTPUT ======================= */

    static void writeHtml(String file) throws Exception {
        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            out.println("""
                <html><head><meta charset="UTF-8">
                <style>
                  body{font-family:Arial;background:#f4f6f8;margin:20px;color:#2c3e50}
                  .hdr{display:flex;gap:16px;align-items:center;flex-wrap:wrap}
                  .pill{background:#fff;border-radius:999px;padding:8px 12px;box-shadow:0 2px 8px rgba(0,0,0,.10)}
                  .grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(520px,1fr));gap:14px;margin-top:14px}
                  .card{background:#fff;border-radius:12px;padding:14px;box-shadow:0 2px 10px rgba(0,0,0,.12)}
                  .title{font-weight:800;font-size:18px;margin-bottom:8px}
                  .cond{margin-left:8px;padding:6px 10px;border-left:4px solid #dfe6e9;margin:6px 0;background:#fafbfc;border-radius:8px}
                  .action{margin-top:10px;font-weight:800;color:#c0392b;background:#fff3cd;border:1px solid #ffeeba;padding:8px 10px;border-radius:10px;display:inline-block}
                  .mono{font-family:ui-monospace,SFMono-Regular,Menlo,Monaco,Consolas,monospace}
                </style>
                </head><body>
            """);

            out.println("<div class='hdr'>");
            out.println("<h1 style='margin:0'>FSML Rules Extract</h1>");
            out.println("<div class='pill'><b>Total paths:</b> " + PATHS.size() + "</div>");
            out.println("<div class='pill'><b>Vars with bounds:</b> " + NUM_BOUNDS.size() + "</div>");
            out.println("</div>");

            out.println("<div class='grid'>");
            for (Path p : PATHS) {
                out.println("<div class='card'>");
                out.println("<div class='title'>Rule " + p.id + "</div>");
                for (String s : renderConditions(p)) {
                    out.println("<div class='cond mono'>➜ " + esc(s) + "</div>");
                }
                out.println("<div class='action'>ACTION → " + esc(p.action == null ? "NAN" : p.action) + "</div>");
                out.println("</div>");
            }
            out.println("</div>");

            out.println("</body></html>");
        }
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");
    }

    /* ======================= GAP ANALYSIS (per-variable projection) ======================= */

    static List<Interval> normalizeUnion(List<Interval> intervals) {
        List<Interval> list = intervals.stream().filter(Objects::nonNull).collect(Collectors.toList());
        list.sort((a, b) -> {
            if (a.min == null && b.min == null) return 0;
            if (a.min == null) return -1;
            if (b.min == null) return 1;
            int c = Double.compare(a.min, b.min);
            if (c != 0) return c;
            return Boolean.compare(!a.minInc, !b.minInc);
        });

        List<Interval> merged = new ArrayList<>();
        for (Interval cur : list) {
            if (merged.isEmpty()) { merged.add(cur); continue; }
            Interval last = merged.get(merged.size() - 1);

            boolean overlaps;
            if (last.max == null || cur.min == null) overlaps = true;
            else {
                int cmp = Double.compare(cur.min, last.max);
                if (cmp < 0) overlaps = true;
                else if (cmp > 0) overlaps = false;
                else overlaps = last.maxInc || cur.minInc;
            }

            if (!overlaps) merged.add(cur);
            else {
                if (last.max == null || cur.max == null) { last.max = null; last.maxInc = true; }
                else {
                    int cmp = Double.compare(cur.max, last.max);
                    if (cmp > 0) { last.max = cur.max; last.maxInc = cur.maxInc; }
                    else if (cmp == 0) last.maxInc = last.maxInc || cur.maxInc;
                }
            }
        }
        return merged;
    }

    static void writeGaps(String file) throws Exception {
        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            for (String var : orderedVarsForOutput()) {
                Bounds b = NUM_BOUNDS.get(var);
                if (b == null) continue; // no bounds => cannot compute universe gaps

                Interval universe = Interval.universe(b);

                List<Interval> projected = new ArrayList<>();
                for (Path p : PATHS) {
                    Interval in = p.numeric.get(var);
                    if (in == null) in = universe; // unconstrained => covers universe
                    projected.add(in);
                }

                List<Interval> covered = normalizeUnion(projected);

                List<Interval> gaps = new ArrayList<>();
                Double cur = universe.min;
                boolean curInc = universe.minInc;

                for (Interval cov : covered) {
                    Interval c = cov.intersect(universe);
                    if (c == null) continue;

                    if (c.min != null && cur != null) {
                        int cmp = Double.compare(cur, c.min);
                        if (cmp < 0) gaps.add(new Interval(cur, curInc, c.min, !c.minInc));
                    }

                    if (c.max == null) { cur = null; break; }
                    cur = c.max;
                    curInc = c.maxInc;
                }

                if (cur != null) gaps.add(new Interval(cur, curInc, universe.max, universe.maxInc));
                gaps = gaps.stream().filter(g -> g.intersect(universe) != null).collect(Collectors.toList());

                out.println("Variable: " + var + "  Universe: " + universe.asMath(var));
                if (gaps.isEmpty()) out.println("  GAPS: none (per-variable projection)");
                else {
                    out.println("  GAPS:");
                    for (Interval g : gaps) out.println("    - " + g.asMath(var));
                }
                out.println();
            }
        }
    }

    /* ======================= SHADOWED PATHS ======================= */

    static boolean subsumes(Path broader, Path narrower) {
        if (!Objects.equals(broader.action, narrower.action)) return false;

        // numeric: broader covers narrower
        for (var e : broader.numeric.entrySet()) {
            String k = e.getKey();
            Interval b = e.getValue();
            Interval n = narrower.numeric.get(k);
            if (n == null) return false;
            if (!b.covers(n)) return false;
        }

        // categorical: broader clauses must be contained in narrower
        for (var e : broader.categorical.entrySet()) {
            List<String> bClauses = e.getValue();
            List<String> nClauses = narrower.categorical.get(e.getKey());
            if (bClauses == null || bClauses.isEmpty()) continue;
            if (nClauses == null) return false;
            for (String bc : bClauses) if (!nClauses.contains(bc)) return false;
        }

        return true;
    }

    static void writeShadowed(String file) throws Exception {
        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            int count = 0;
            for (int i = 0; i < PATHS.size(); i++) {
                Path a = PATHS.get(i);
                for (int j = i + 1; j < PATHS.size(); j++) {
                    Path b = PATHS.get(j);
                    if (subsumes(a, b)) {
                        count++;
                        out.println("Rule " + b.id + " is SHADOWED by Rule " + a.id + " (action: " + a.action + ")");
                        out.println("  Rule " + a.id + " conditions:");
                        for (String s : renderConditions(a)) out.println("    - " + s);
                        out.println("  Rule " + b.id + " conditions:");
                        for (String s : renderConditions(b)) out.println("    - " + s);
                        out.println();
                    }
                }
            }
            out.println("TOTAL SHADOWED RELATIONSHIPS: " + count);
        }
    }

    /* ======================= MAIN ======================= */

    public static void main(String[] args) throws Exception {
        String fsml = (args.length > 0) ? args[0] : "PenFed_AR_Expert_09042025.fsml";

        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        Document doc = f.newDocumentBuilder().parse(new File(fsml));

        parseNumericBounds(doc);
        parseVariableOrder(doc);

        // Find STRATEGY
        Element strategy = null;
        NodeList all = doc.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Node n = all.item(i);
            if (isTag(n, "STRATEGY")) { strategy = (Element) n; break; }
        }
        if (strategy == null) throw new IllegalStateException("No <STRATEGY> found.");

        // Start from first NODE under STRATEGY
        Element rootNode = firstChild(strategy, "NODE");
        if (rootNode == null) throw new IllegalStateException("No <NODE> under <STRATEGY>.");

        walkNode(rootNode, new Path());

        writeDecisionCsv("decision-table.csv");
        writeHtml("fsml-view.html");
        writeGaps("gap-analysis.txt");
        writeShadowed("shadowed-paths.txt");

        System.out.println("FSML File: " + fsml);
        System.out.println("TOTAL PATHS: " + PATHS.size());
        System.out.println("Generated:");
        System.out.println("  decision-table.csv");
        System.out.println("  fsml-view.html");
        System.out.println("  gap-analysis.txt");
        System.out.println("  shadowed-paths.txt");
    }

    /* ======================= FORMAT ======================= */

    static String fmt(double d) {
        String s = String.format(Locale.US, "%.10f", d);
        s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s;
    }
}
