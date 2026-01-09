import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * FSML Analyzer (single file, Java 21)
 *
 * Outputs:
 *  - decision-table.csv  (Excel-ready)
 *  - fsml-view.html      (beautiful readable rules)
 *  - gap-analysis.txt    (numeric gaps per variable)
 *  - shadowed-paths.txt  (shadow/subsumption detection)
 */
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
                if (nMin == null || o.min > nMin || (Objects.equals(o.min, nMin) && !o.minInc && nMinInc)) {
                    nMin = o.min;
                    nMinInc = o.minInc;
                } else if (Objects.equals(o.min, nMin)) {
                    nMinInc = this.minInc && o.minInc;
                }
            }

            Double nMax = this.max;
            boolean nMaxInc = this.maxInc;
            if (o.max != null) {
                if (nMax == null || o.max < nMax || (Objects.equals(o.max, nMax) && !o.maxInc && nMaxInc)) {
                    nMax = o.max;
                    nMaxInc = o.maxInc;
                } else if (Objects.equals(o.max, nMax)) {
                    nMaxInc = this.maxInc && o.maxInc;
                }
            }

            // Check emptiness
            if (nMin != null && nMax != null) {
                if (nMin > nMax) return null;
                if (Objects.equals(nMin, nMax) && !(nMinInc && nMaxInc)) return null;
            }

            return new Interval(nMin, nMinInc, nMax, nMaxInc);
        }

        boolean covers(Interval o) {
            if (o == null) return true;
            // min check: this.min <= o.min
            if (this.min != null && o.min != null) {
                if (this.min > o.min) return false;
                if (Objects.equals(this.min, o.min) && this.minInc == false && o.minInc == true) return false;
            } else if (this.min != null && o.min == null) {
                return false;
            }

            // max check: this.max >= o.max
            if (this.max != null && o.max != null) {
                if (this.max < o.max) return false;
                if (Objects.equals(this.max, o.max) && this.maxInc == false && o.maxInc == true) return false;
            } else if (this.max != null && o.max == null) {
                return false;
            }

            return true;
        }

        String asMath(String var) {
            // Render as range, best-effort
            if (min == null && max == null) return "NAN";
            if (min != null && max != null && Objects.equals(min, max) && minInc && maxInc) {
                return var + " = " + fmt(min);
            }
            if (min == null) return var + " " + (maxInc ? "≤" : "<") + " " + fmt(max);
            if (max == null) return var + " " + (minInc ? "≥" : ">") + " " + fmt(min);

            return fmt(min) + " " + (minInc ? "≤" : "<") + " " + var + " " + (maxInc ? "≤" : "<") + " " + fmt(max);
        }

        @Override public String toString() {
            return asMath("x");
        }
    }

    static class Condition {
        String key;
        String op;     // ge/lt/eq/etc
        String value;  // resolved LOW/HIGH->numbers, NaN->NAN
        Condition(String k, String o, String v) { key=k; op=o; value=v; }
    }

    static class Path {
        int id;
        String action;

        // For decision-table and analysis:
        // numeric constraints expressed as final interval per variable
        Map<String, Interval> numeric = new LinkedHashMap<>();
        // categorical constraints as list of "op:value" clauses (kept verbatim)
        Map<String, List<String>> categorical = new LinkedHashMap<>();

        // keep raw conditions for debugging/HTML
        List<Condition> raw = new ArrayList<>();

        boolean invalid;

        Path copy() {
            Path p = new Path();
            p.numeric.putAll(this.numeric);
            for (var e : this.categorical.entrySet()) p.categorical.put(e.getKey(), new ArrayList<>(e.getValue()));
            p.raw.addAll(this.raw);
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

    /* ======================= PARSE VARIABLES (bounds + order) ======================= */

    static void parseVariableOrder(Document doc) {
        // VARIABLE-ORDER is inside STRATEGY in your FSML
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
        // Find all NumericKey nodes and their NumericRange minValue/maxValue
        NodeList all = doc.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Node n = all.item(i);
            if (!isTag(n, "NumericKey")) continue;
            Element nk = (Element) n;

            String shortName = attr(nk, "ShortName");
            if (shortName.isBlank()) shortName = attr(nk, "LongName");
            if (shortName.isBlank()) continue;

            Element nr = null;
            for (Element c : children(nk, "NumericRange")) { nr = c; break; }
            if (nr == null) continue;

            String minV = attr(nr, "minValue");
            String maxV = attr(nr, "maxValue");
            try {
                double min = Double.parseDouble(minV);
                double max = Double.parseDouble(maxV);
                NUM_BOUNDS.put(shortName, new Bounds(min, max));
            } catch (Exception ignored) {
                // leave unbounded if cannot parse
            }
        }
    }

    /* ======================= VALUE RESOLUTION ======================= */

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

    static String opSymbol(String op) {
        return switch (op) {
            case "lt" -> "<";
            case "le" -> "≤";
            case "gt" -> ">";
            case "ge" -> "≥";
            case "eq" -> "=";
            case "ne" -> "≠";
            default -> op;
        };
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
            p.numeric.put(key, local);
        } else {
            Interval merged = base.intersect(local);
            if (merged == null) {
                p.invalid = true;
            } else {
                p.numeric.put(key, merged);
            }
        }
    }

    // FSML supports nested <CONDITION Type="and"> containing sub CONDITIONS
    static void extractConditionRecursive(Element condEl, Path p) {
        String type = attr(condEl, "Type");

        // container AND
        if ("and".equalsIgnoreCase(type)) {
            for (Element sub : children(condEl, "CONDITION")) extractConditionRecursive(sub, p);
            return;
        }

        // ignore root true placeholder
        if ("true".equalsIgnoreCase(type)) return;

        String key = attr(condEl, "DecisionKey");
        String rawVal = attr(condEl, "Value");
        if (key == null || key.isBlank()) return;

        String val = resolveValue(key, rawVal);

        p.raw.add(new Condition(key, type, val));

        // NaN/Missing: keep literal NAN as categorical equality so it doesn’t break parsing
        if ("NAN".equalsIgnoreCase(val)) {
            addCategorical(p, key, "eq", "NAN");
            return;
        }

        // numeric op?
        boolean numericVal = looksNumeric(val);
        if (numericVal && (type.equals("ge") || type.equals("gt") || type.equals("le") || type.equals("lt") || type.equals("eq"))) {
            double num = Double.parseDouble(val);
            Interval local;

            switch (type) {
                case "ge" -> local = new Interval(num, true, null, true);
                case "gt" -> local = new Interval(num, false, null, true);
                case "le" -> local = new Interval(null, true, num, true);
                case "lt" -> local = new Interval(null, true, num, false);
                case "eq" -> local = new Interval(num, true, num, true);
                default -> { addCategorical(p, key, type, val); return; }
            }

            // If we know bounds, start from universe to allow proper intersection semantics later (optional)
            if (!p.numeric.containsKey(key)) {
                Bounds b = NUM_BOUNDS.get(key);
                if (b != null) p.numeric.put(key, Interval.universe(b));
            }

            addNumeric(p, key, local);
            return;
        }

        // categorical
        addCategorical(p, key, type, val);
    }

    /* ======================= TREE WALK (AND accumulation across NODE levels) ======================= */

    static void walkNode(Element node, Path incoming) {
        if (node == null) return;

        Path cur = incoming.copy();

        // All CONDITIONS directly under this NODE are ANDed
        for (Element c : children(node, "CONDITION")) {
            extractConditionRecursive(c, cur);
            if (cur.invalid) return;
        }

        Element act = firstChild(node, "ACTIONS");
        List<Element> kids = children(node, "NODE");

        if (act != null) {
            Path leaf = cur.copy();
            leaf.action = attr(act, "Label");
            leaf.id = PATHS.size() + 1;
            PATHS.add(leaf);
        }

        for (Element k : kids) {
            walkNode(k, cur);
        }
    }

    /* ======================= PRETTY CONDITION RENDERING (merged ranges) ======================= */

    static List<String> renderConditions(Path p) {
        List<String> lines = new ArrayList<>();

        // numeric as range
        for (var e : p.numeric.entrySet()) {
            String k = e.getKey();
            Interval in = e.getValue();
            if (in == null) continue;

            // If numeric constraint remained as pure universe and there is no other restriction, skip printing it
            Bounds b = NUM_BOUNDS.get(k);
            if (b != null) {
                Interval u = Interval.universe(b);
                if (u.covers(in) && in.covers(u)) {
                    // exactly universe
                    continue;
                }
            }
            lines.add(in.asMath(k));
        }

        // categorical as explicit clauses
        for (var e : p.categorical.entrySet()) {
            String k = e.getKey();
            List<String> clauses = e.getValue();
            if (clauses == null || clauses.isEmpty()) continue;
            // Join multiple clauses with AND
            String joined = clauses.stream().map(c -> {
                String[] parts = c.split(":", 2);
                String op = parts[0];
                String v = parts.length > 1 ? parts[1] : "";
                return k + " " + opSymbol(op) + " " + v;
            }).collect(Collectors.joining(" AND "));
            lines.add(joined);
        }

        // keep stable ordering based on VARIABLE-ORDER when possible
        if (!VARIABLE_ORDER.isEmpty()) {
            Map<String, Integer> idx = new HashMap<>();
            for (int i = 0; i < VARIABLE_ORDER.size(); i++) idx.put(VARIABLE_ORDER.get(i), i);

            lines.sort(Comparator.comparingInt(s -> {
                // try to match variable at start
                String var = s.split("\\s+", 2)[0];
                return idx.getOrDefault(var, Integer.MAX_VALUE);
            }));
        } else {
            Collections.sort(lines);
        }

        return lines;
    }

    /* ======================= SHADOWED PATHS ======================= */

    static boolean subsumes(Path broader, Path narrower) {
        if (!Objects.equals(broader.action, narrower.action)) return false;

        // numeric: broader must cover narrower for every var it constrains
        for (var e : broader.numeric.entrySet()) {
            Interval b = e.getValue();
            Interval n = narrower.numeric.get(e.getKey());
            if (n == null) {
                // narrower didn't constrain but broader did -> broader cannot cover all of narrower's space unless it's universe
                Bounds bb = NUM_BOUNDS.get(e.getKey());
                if (bb == null) return false;
                Interval u = Interval.universe(bb);
                if (!u.covers(b) || !b.covers(u)) return false; // not universe
            } else {
                if (!b.covers(n)) return false;
            }
        }

        // categorical: broader clauses must be subset-compatible with narrower
        // We’ll use simple containment: all broader clauses must exist in narrower
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
            // Use extraction order as priority: earlier rules shadow later rules
            int count = 0;
            for (int i = 0; i < PATHS.size(); i++) {
                Path a = PATHS.get(i);
                for (int j = i + 1; j < PATHS.size(); j++) {
                    Path b = PATHS.get(j);
                    if (subsumes(a, b)) {
                        count++;
                        out.println("Rule " + b.id + " is SHADOWED by Rule " + a.id + " (same action: " + a.action + ")");
                        out.println("  Shadowing Rule " + a.id + " conditions:");
                        for (String s : renderConditions(a)) out.println("    - " + s);
                        out.println("  Shadowed Rule " + b.id + " conditions:");
                        for (String s : renderConditions(b)) out.println("    - " + s);
                        out.println();
                    }
                }
            }
            out.println("TOTAL SHADOWED RELATIONSHIPS: " + count);
        }
    }

    /* ======================= GAP ANALYSIS (per variable projection) ======================= */

    static List<Interval> normalizeUnion(List<Interval> intervals) {
        List<Interval> list = intervals.stream().filter(Objects::nonNull).collect(Collectors.toList());
        list.sort((a, b) -> {
            if (a.min == null && b.min == null) return 0;
            if (a.min == null) return -1;
            if (b.min == null) return 1;
            int c = Double.compare(a.min, b.min);
            if (c != 0) return c;
            // inclusive comes first
            return Boolean.compare(!a.minInc, !b.minInc);
        });

        List<Interval> merged = new ArrayList<>();
        for (Interval cur : list) {
            if (merged.isEmpty()) { merged.add(cur); continue; }
            Interval last = merged.get(merged.size() - 1);

            // Check overlap/touch
            boolean overlaps;
            if (last.max == null || cur.min == null) overlaps = true;
            else {
                int cmp = Double.compare(cur.min, last.max);
                if (cmp < 0) overlaps = true;
                else if (cmp > 0) overlaps = false;
                else {
                    // equal boundary: overlaps if either is inclusive at the join
                    overlaps = last.maxInc || cur.minInc;
                }
            }

            if (!overlaps) {
                merged.add(cur);
            } else {
                // extend last.max if needed
                if (last.max == null || cur.max == null) {
                    last.max = null; last.maxInc = true;
                } else {
                    int cmp = Double.compare(cur.max, last.max);
                    if (cmp > 0) { last.max = cur.max; last.maxInc = cur.maxInc; }
                    else if (cmp == 0) { last.maxInc = last.maxInc || cur.maxInc; }
                }
            }
        }
        return merged;
    }

    static void writeGaps(String file) throws Exception {
        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            for (String var : orderedVarsForOutput()) {
                Bounds b = NUM_BOUNDS.get(var);
                if (b == null) continue; // no universe => skip

                Interval universe = Interval.universe(b);

                // Project all paths onto this variable:
                // If a path doesn’t constrain var, treat it as universe (contributes full coverage).
                List<Interval> projected = new ArrayList<>();
                for (Path p : PATHS) {
                    Interval in = p.numeric.get(var);
                    if (in == null) in = universe;
                    projected.add(in);
                }

                List<Interval> covered = normalizeUnion(projected);

                // Now compute gaps inside universe
                List<Interval> gaps = new ArrayList<>();
                Interval cursor = new Interval(universe.min, universe.minInc, universe.min, universe.minInc); // point cursor at start
                Double curStart = universe.min;
                boolean curStartInc = universe.minInc;

                for (Interval cov : covered) {
                    // clip cov to universe
                    Interval c = cov.intersect(universe);
                    if (c == null) continue;

                    // gap from curStart to c.min ?
                    if (c.min != null && curStart != null) {
                        int cmp = Double.compare(curStart, c.min);
                        boolean hasGap;
                        if (cmp < 0) hasGap = true;
                        else if (cmp > 0) hasGap = false;
                        else {
                            // same point: gap exists if current start is exclusive and cov starts exclusive? (no interval)
                            hasGap = false;
                        }

                        if (hasGap) {
                            gaps.add(new Interval(curStart, curStartInc, c.min, !c.minInc));
                        }
                    }

                    // advance curStart to end of c
                    if (c.max == null) {
                        curStart = null;
                        curStartInc = true;
                        break;
                    } else {
                        curStart = c.max;
                        curStartInc = !c.maxInc ? false : true;
                    }
                }

                // tail gap to universe end
                if (curStart != null) {
                    gaps.add(new Interval(curStart, curStartInc, universe.max, universe.maxInc));
                }

                // remove empty gaps
                gaps = gaps.stream().filter(g -> g.intersect(universe) != null).collect(Collectors.toList());

                out.println("Variable: " + var + "  Universe: " + universe.asMath(var));
                if (gaps.isEmpty()) {
                    out.println("  GAPS: none (based on per-variable projection across all paths)");
                } else {
                    out.println("  GAPS:");
                    for (Interval g : gaps) out.println("    - " + g.asMath(var));
                }
                out.println();
            }
        }
    }

    /* ======================= DECISION TABLE OUTPUT ======================= */

    static List<String> orderedVarsForOutput() {
        if (!VARIABLE_ORDER.isEmpty()) {
            // include only keys we saw, but keep order stable
            List<String> out = new ArrayList<>();
            for (String v : VARIABLE_ORDER) if (ALL_KEYS.contains(v) || NUM_BOUNDS.containsKey(v)) out.add(v);
            // add any extras at end
            for (String v : ALL_KEYS) if (!out.contains(v)) out.add(v);
            return out;
        }
        return new ArrayList<>(ALL_KEYS);
    }

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
                        // join categorical clauses
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

    /* ======================= BEAUTIFUL HTML ======================= */

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

    /* ======================= MAIN ======================= */

    public static void main(String[] args) throws Exception {
        String fsml = (args.length > 0) ? args[0] : "PenFed_AR_Expert_09042025.fsml";

        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        Document doc = f.newDocumentBuilder().parse(new File(fsml));

        // Parse variable metadata first (bounds + order)
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

        // Start from root NODE inside STRATEGY
        Element rootNode = firstChild(strategy, "NODE");
        if (rootNode == null) throw new IllegalStateException("No <NODE> under <STRATEGY>.");

        // Walk
        walkNode(rootNode, new Path());

        // Outputs
        writeDecisionCsv("decision-table.csv");
        writeHtml("fsml-view.html");
        writeGaps("gap-analysis.txt");
        writeShadowed("shadowed-paths.txt");

        System.out.println("FSML: " + fsml);
        System.out.println("TOTAL PATHS: " + PATHS.size());
        System.out.println("Generated:");
        System.out.println("  decision-table.csv");
        System.out.println("  fsml-view.html");
        System.out.println("  gap-analysis.txt");
        System.out.println("  shadowed-paths.txt");
    }

    /* ======================= FORMAT ======================= */

    static String fmt(double d) {
        // Avoid scientific notation, trim .0
        String s = String.format(Locale.US, "%.10f", d);
        s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s;
    }
}
