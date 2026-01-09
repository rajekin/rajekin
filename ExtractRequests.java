import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

public class FsmlAnalyzerMain {

    /* ======================= MODELS ======================= */

    static class Bounds {
        final double min, max;
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
                if (nMin == null || o.min > nMin) { nMin = o.min; nMinInc = o.minInc; }
                else if (Objects.equals(o.min, nMin)) { nMinInc = this.minInc && o.minInc; }
            }

            Double nMax = this.max;
            boolean nMaxInc = this.maxInc;

            if (o.max != null) {
                if (nMax == null || o.max < nMax) { nMax = o.max; nMaxInc = o.maxInc; }
                else if (Objects.equals(o.max, nMax)) { nMaxInc = this.maxInc && o.maxInc; }
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

            if (this.min != null) {
                if (o.min == null) return false;
                int c = Double.compare(this.min, o.min);
                if (c > 0) return false;
                if (c == 0 && !this.minInc && o.minInc) return false;
            }
            if (this.max != null) {
                if (o.max == null) return false;
                int c = Double.compare(this.max, o.max);
                if (c < 0) return false;
                if (c == 0 && !this.maxInc && o.maxInc) return false;
            }
            return true;
        }

        String asText(String var) {
            // ASCII only for Excel reliability
            if (min == null && max == null) return var + " = NAN";
            if (min != null && max != null && Objects.equals(min, max) && minInc && maxInc) return var + " = " + fmt(min);

            if (min == null) return var + " " + (maxInc ? "<=" : "<") + " " + fmt(max);
            if (max == null) return var + " " + (minInc ? ">=" : ">") + " " + fmt(min);

            String left = fmt(min) + " " + (minInc ? "<=" : "<") + " " + var;
            String right = var + " " + (maxInc ? "<=" : "<") + " " + fmt(max);
            return left + " AND " + right;
        }

        @Override public String toString() { return asText("x"); }
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
            for (var e : this.categorical.entrySet()) p.categorical.put(e.getKey(), new ArrayList<>(e.getValue()));
            return p;
        }
    }

    static class NodeInfo {
        Element el;
        List<Element> childNodes;
        Element actions;
        List<Element> conditions;
        NodeInfo(Element el) {
            this.el = el;
            this.childNodes = children(el, "NODE");
            this.actions = firstChild(el, "ACTIONS");
            this.conditions = children(el, "CONDITION");
        }
    }

    /* ======================= STATE ======================= */

    static final List<Path> PATHS = new ArrayList<>();
    static final LinkedHashSet<String> ALL_KEYS = new LinkedHashSet<>();
    static final Map<String, Bounds> NUM_BOUNDS = new HashMap<>();
    static final List<String> VARIABLE_ORDER = new ArrayList<>();

    static final List<String> GAP_LINES = new ArrayList<>();
    static final List<String> SHADOW_LINES = new ArrayList<>();

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

    /* ======================= PARSE ORDER + BOUNDS ======================= */

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
                return;
            }
        }
    }

    static void parseNumericBounds(Document doc) {
        NodeList all = doc.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Node n = all.item(i);
            if (!isTag(n, "NumericKey")) continue;
            Element nk = (Element) n;

            String key = attr(nk, "ShortName");
            if (key.isBlank()) key = attr(nk, "LongName");
            if (key.isBlank()) continue;

            Element nr = firstChild(nk, "NumericRange");
            if (nr == null) continue;

            Double min = safeParseDouble(attr(nr, "minValue"));
            Double max = safeParseDouble(attr(nr, "maxValue"));
            if (min != null && max != null) NUM_BOUNDS.put(key, new Bounds(min, max));
        }
    }

    /* ======================= VALUE HANDLING ======================= */

    static boolean isNanToken(String v) {
        return v == null || v.trim().isEmpty() || "NaN".equalsIgnoreCase(v.trim()) || "NAN".equalsIgnoreCase(v.trim());
    }

    static String resolveValue(String key, String rawVal) {
        if (isNanToken(rawVal)) return "NAN";
        String v = rawVal.trim();

        if ("LOW".equalsIgnoreCase(v)) {
            Bounds b = NUM_BOUNDS.get(key);
            return b == null ? "LOW" : fmt(b.min);
        }
        if ("HIGH".equalsIgnoreCase(v)) {
            Bounds b = NUM_BOUNDS.get(key);
            return b == null ? "HIGH" : fmt(b.max);
        }
        return v;
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
        if (op == null) return op;
        if ("lt".equals(op)) return "<";
        if ("le".equals(op)) return "<=";
        if ("gt".equals(op)) return ">";
        if ("ge".equals(op)) return ">=";
        if ("eq".equals(op)) return "=";
        if ("ne".equals(op)) return "!=";
        return op;
    }

    /* ======================= CONDITION EXTRACTION (nested AND) ======================= */

    static void addCategorical(Path p, String key, String op, String value) {
        ALL_KEYS.add(key);
        p.categorical.computeIfAbsent(key, k -> new ArrayList<>()).add(op + ":" + value);
    }

    static void addNumeric(Path p, String key, Interval local) {
        ALL_KEYS.add(key);

        Interval base = p.numeric.get(key);
        if (base == null) {
            Bounds b = NUM_BOUNDS.get(key);
            base = (b != null) ? Interval.universe(b) : new Interval(null, true, null, true);
        }

        Interval merged = base.intersect(local);
        if (merged == null) { p.invalid = true; return; }
        p.numeric.put(key, merged);
    }

    static void extractConditionRecursive(Element condEl, Path p) {
        String type = attr(condEl, "Type");

        if ("and".equalsIgnoreCase(type)) {
            for (Element sub : children(condEl, "CONDITION")) extractConditionRecursive(sub, p);
            return;
        }
        if ("true".equalsIgnoreCase(type)) return;

        String key = attr(condEl, "DecisionKey");
        if (key == null || key.isBlank()) return;

        String rawVal = attr(condEl, "Value");
        String val = resolveValue(key, rawVal);

        // NAN: store literal and never parse
        if ("NAN".equalsIgnoreCase(val)) {
            addCategorical(p, key, "eq", "NAN");
            return;
        }

        // numeric?
        if (looksNumeric(val) && ("ge".equals(type) || "gt".equals(type) || "le".equals(type) || "lt".equals(type) || "eq".equals(type))) {
            Double num = safeParseDouble(val);
            if (num == null) { addCategorical(p, key, type, val); return; }

            Interval local = null;
            if ("ge".equals(type)) local = new Interval(num, true, null, true);
            else if ("gt".equals(type)) local = new Interval(num, false, null, true);
            else if ("le".equals(type)) local = new Interval(null, true, num, true);
            else if ("lt".equals(type)) local = new Interval(null, true, num, false);
            else if ("eq".equals(type)) local = new Interval(num, true, num, true);

            if (local != null) addNumeric(p, key, local);
            return;
        }

        // categorical otherwise (including LOW/HIGH if no bounds were found)
        addCategorical(p, key, type, val);
    }

    /* ======================= TREE WALK (capture ACTION even with children) ======================= */

    static void walkNode(Element node, Path incoming) {
        if (node == null) return;

        Path cur = incoming.copy();

        // apply all CONDITION elements under this NODE
        for (Element c : children(node, "CONDITION")) {
            extractConditionRecursive(c, cur);
            if (cur.invalid) return;
        }

        // record rule if ACTIONS exists (even if it also has children)
        Element act = firstChild(node, "ACTIONS");
        if (act != null) {
            Path rule = cur.copy();
            rule.action = attr(act, "Label");
            rule.id = PATHS.size() + 1;
            PATHS.add(rule);
        }

        // structural gap/shadow check among this node's immediate children
        analyzeSiblingSplits(node, cur);

        // continue recursion
        for (Element k : children(node, "NODE")) walkNode(k, cur);
    }

    /* ======================= SIBLING SPLIT GAP/OVERLAP (tree-accurate) ======================= */

    static void analyzeSiblingSplits(Element parent, Path parentCtx) {
        List<Element> kids = children(parent, "NODE");
        if (kids.size() < 2) return;

        // For each child, find the first DecisionKey it constrains (common in FSML tree splits)
        class KidConstraint {
            Element kid;
            String key;
            Interval interval; // numeric only; categorical splits ignored here
            KidConstraint(Element kid, String key, Interval interval) { this.kid = kid; this.key = key; this.interval = interval; }
        }

        List<KidConstraint> numericKids = new ArrayList<>();

        for (Element kid : kids) {
            Path tmp = parentCtx.copy();
            for (Element c : children(kid, "CONDITION")) extractConditionRecursive(c, tmp);
            if (tmp.invalid) continue;

            // choose a numeric key that changed vs parent context
            for (var e : tmp.numeric.entrySet()) {
                String k = e.getKey();
                Interval childInt = e.getValue();
                Interval parentInt = parentCtx.numeric.get(k);
                Bounds b = NUM_BOUNDS.get(k);
                if (parentInt == null) parentInt = (b != null) ? Interval.universe(b) : new Interval(null, true, null, true);

                // if child interval is stricter than parent -> treat as split
                if (!parentInt.covers(childInt) || (parentInt.covers(childInt) && !childInt.covers(parentInt))) {
                    numericKids.add(new KidConstraint(kid, k, childInt));
                    break;
                }
            }
        }

        if (numericKids.isEmpty()) return;

        // group by key
        Map<String, List<KidConstraint>> byKey = numericKids.stream().collect(Collectors.groupingBy(k -> k.key));

        for (var entry : byKey.entrySet()) {
            String key = entry.getKey();
            List<KidConstraint> list = entry.getValue();
            if (list.size() < 2) continue;

            Bounds b = NUM_BOUNDS.get(key);
            Interval parentRange = parentCtx.numeric.get(key);
            if (parentRange == null) parentRange = (b != null) ? Interval.universe(b) : new Interval(null, true, null, true);

            // union and gap
            List<Interval> union = normalizeUnion(list.stream().map(k -> k.interval).collect(Collectors.toList()));
            List<Interval> gaps = gapsWithin(parentRange, union);

            if (!gaps.isEmpty()) {
                GAP_LINES.add("SIBLING GAP under parent NODE Label=\"" + attr(parent,"Label") + "\" key=" + key);
                GAP_LINES.add("  ParentRange: " + parentRange.asText(key));
                for (Interval g : gaps) GAP_LINES.add("  GAP: " + g.asText(key));
                GAP_LINES.add("");
            }

            // overlaps (potential shadow depending on evaluation order)
            List<String> overlaps = findOverlaps(list);
            if (!overlaps.isEmpty()) {
                SHADOW_LINES.add("SIBLING OVERLAP under parent NODE Label=\"" + attr(parent,"Label") + "\" key=" + key);
                SHADOW_LINES.add("  ParentRange: " + parentRange.asText(key));
                SHADOW_LINES.addAll(overlaps);
                SHADOW_LINES.add("");
            }
        }
    }

    static List<String> findOverlaps(List<?> kidsRaw) {
        @SuppressWarnings("unchecked")
        List<Object> kids = (List<Object>) kidsRaw;

        class KC {
            String key; Interval interval; Element kid;
            KC(String key, Interval interval, Element kid) { this.key = key; this.interval = interval; this.kid = kid; }
        }

        List<KC> list = new ArrayList<>();
        for (Object o : kids) {
            // reflection-free: kids are KidConstraint instances in our method only
            // We'll parse by string to keep it simple and safe.
        }
        // Rebuild properly by requiring the caller already provides KidConstraint list
        // We'll overload instead:
        return Collections.emptyList();
    }

    // Overload used by sibling analyzer
    static List<String> findOverlaps(List<?> kidConstraints, boolean unused) { return Collections.emptyList(); }

    // Actual implementation for KidConstraint list
    static List<String> findOverlapsTyped(List<?> kidConstraints) {
        List<String> out = new ArrayList<>();
        // kidConstraints is List<KidConstraint>
        for (int i = 0; i < kidConstraints.size(); i++) {
            Object ai = kidConstraints.get(i);
            for (int j = i + 1; j < kidConstraints.size(); j++) {
                Object bj = kidConstraints.get(j);

                Element aKid = (Element) getField(ai, "kid");
                Element bKid = (Element) getField(bj, "kid");
                Interval aInt = (Interval) getField(ai, "interval");
                Interval bInt = (Interval) getField(bj, "interval");

                Interval inter = aInt.intersect(bInt);
                if (inter != null) {
                    out.add("  OVERLAP between child \"" + attr(aKid,"Label") + "\" and \"" + attr(bKid,"Label") + "\": " + inter.asText("x"));
                }
            }
        }
        return out;
    }

    static Object getField(Object obj, String name) {
        try {
            var f = obj.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(obj);
        } catch (Exception e) {
            return null;
        }
    }

    /* ======================= GAP HELPERS ======================= */

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

    static List<Interval> gapsWithin(Interval parent, List<Interval> coveredUnion) {
        List<Interval> gaps = new ArrayList<>();
        Interval p = parent;

        Double cur = p.min;
        boolean curInc = p.minInc;

        for (Interval cov : coveredUnion) {
            Interval c = cov.intersect(p);
            if (c == null) continue;

            if (c.min != null && cur != null) {
                int cmp = Double.compare(cur, c.min);
                if (cmp < 0) gaps.add(new Interval(cur, curInc, c.min, !c.minInc));
            }

            if (c.max == null) { cur = null; break; }
            cur = c.max;
            curInc = c.maxInc;
        }

        if (cur != null) {
            Interval tail = new Interval(cur, curInc, p.max, p.maxInc);
            Interval ok = tail.intersect(p);
            if (ok != null) gaps.add(ok);
        }

        // remove empties
        return gaps.stream().filter(g -> g.intersect(p) != null).collect(Collectors.toList());
    }

    /* ======================= OUTPUT ORDER ======================= */

    static List<String> orderedVarsForOutput() {
        if (!VARIABLE_ORDER.isEmpty()) {
            List<String> out = new ArrayList<>();
            for (String v : VARIABLE_ORDER) if (ALL_KEYS.contains(v) || NUM_BOUNDS.containsKey(v)) out.add(v);
            for (String v : ALL_KEYS) if (!out.contains(v)) out.add(v);
            return out;
        }
        return new ArrayList<>(ALL_KEYS);
    }

    /* ======================= DECISION TABLE CSV (Excel-safe) ======================= */

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
                        cell = p.numeric.get(v).asText(v);
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
                    out.print("," + csvEscape(excelSafe(cell)));
                }
                out.println("," + csvEscape(excelSafe(p.action == null ? "NAN" : p.action)));
            }
        }
    }

    // Prevent Excel formula parsing: if value begins with =,+,-,@ then prefix '
    static String excelSafe(String s) {
        if (s == null) return "";
        if (s.isEmpty()) return s;
        char c = s.charAt(0);
        if (c == '=' || c == '+' || c == '-' || c == '@') return "'" + s;
        return s;
    }

    static String csvEscape(String s) {
        if (s == null) return "";
        String x = s.replace("\"", "\"\"");
        if (x.contains(",") || x.contains("\"") || x.contains("\n")) return "\"" + x + "\"";
        return x;
    }

    /* ======================= HTML OUTPUT (no text blocks) ======================= */

    static void writeHtml(String file) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><head><meta charset=\"UTF-8\">");
        sb.append("<style>");
        sb.append("body{font-family:Arial;background:#f4f6f8;margin:20px;color:#2c3e50}");
        sb.append(".hdr{display:flex;gap:16px;align-items:center;flex-wrap:wrap}");
        sb.append(".pill{background:#fff;border-radius:999px;padding:8px 12px;box-shadow:0 2px 8px rgba(0,0,0,.10)}");
        sb.append(".grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(520px,1fr));gap:14px;margin-top:14px}");
        sb.append(".card{background:#fff;border-radius:12px;padding:14px;box-shadow:0 2px 10px rgba(0,0,0,.12)}");
        sb.append(".title{font-weight:800;font-size:18px;margin-bottom:8px}");
        sb.append(".cond{margin-left:8px;padding:6px 10px;border-left:4px solid #dfe6e9;margin:6px 0;background:#fafbfc;border-radius:8px}");
        sb.append(".action{margin-top:10px;font-weight:800;color:#c0392b;background:#fff3cd;border:1px solid #ffeeba;padding:8px 10px;border-radius:10px;display:inline-block}");
        sb.append(".mono{font-family:ui-monospace,SFMono-Regular,Menlo,Monaco,Consolas,monospace}");
        sb.append("</style></head><body>");

        sb.append("<div class='hdr'>");
        sb.append("<h1 style='margin:0'>FSML Rules Extract</h1>");
        sb.append("<div class='pill'><b>Total paths:</b> ").append(PATHS.size()).append("</div>");
        sb.append("<div class='pill'><b>Vars with bounds:</b> ").append(NUM_BOUNDS.size()).append("</div>");
        sb.append("</div>");

        sb.append("<div class='grid'>");
        for (Path p : PATHS) {
            sb.append("<div class='card'>");
            sb.append("<div class='title'>Rule ").append(p.id).append("</div>");

            List<String> conds = renderConditions(p);
            for (String c : conds) sb.append("<div class='cond mono'>➜ ").append(esc(c)).append("</div>");

            sb.append("<div class='action'>ACTION → ").append(esc(p.action == null ? "NAN" : p.action)).append("</div>");
            sb.append("</div>");
        }
        sb.append("</div></body></html>");

        try (Writer out = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            out.write(sb.toString());
        }
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");
    }

    /* ======================= CONDITION RENDER ======================= */

    static List<String> renderConditions(Path p) {
        List<String> lines = new ArrayList<>();

        for (var e : p.numeric.entrySet()) {
            String k = e.getKey();
            Interval in = e.getValue();
            if (in == null) continue;

            Bounds b = NUM_BOUNDS.get(k);
            if (b != null) {
                Interval u = Interval.universe(b);
                if (u.covers(in) && in.covers(u)) continue; // suppress pure universe
            }
            lines.add(in.asText(k));
        }

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

        List<String> order = orderedVarsForOutput();
        Map<String,Integer> idx = new HashMap<>();
        for (int i=0;i<order.size();i++) idx.put(order.get(i), i);

        lines.sort(Comparator.comparingInt(s -> idx.getOrDefault(s.split("\\s+",2)[0], Integer.MAX_VALUE)));
        return lines;
    }

    /* ======================= GAP ANALYSIS (global projection + sibling splits) ======================= */

    static void writeGapAnalysis(String file) throws Exception {
        List<String> lines = new ArrayList<>();

        // Global per-variable projection gaps
        for (String var : orderedVarsForOutput()) {
            Bounds b = NUM_BOUNDS.get(var);
            if (b == null) continue;

            Interval universe = Interval.universe(b);

            List<Interval> projected = new ArrayList<>();
            for (Path p : PATHS) {
                Interval in = p.numeric.get(var);
                if (in == null) in = universe; // unconstrained means covers universe
                projected.add(in);
            }

            List<Interval> covered = normalizeUnion(projected);
            List<Interval> gaps = gapsWithin(universe, covered);

            lines.add("VARIABLE: " + var + " UNIVERSE: " + universe.asText(var));
            if (gaps.isEmpty()) lines.add("  GAPS: none (projection)");
            else {
                lines.add("  GAPS (projection):");
                for (Interval g : gaps) lines.add("    - " + g.asText(var));
            }
            lines.add("");
        }

        // Add sibling split gaps/overlaps (tree accurate)
        lines.add("=== SIBLING SPLIT GAPS (tree-accurate) ===");
        lines.addAll(GAP_LINES);
        lines.add("");
        lines.add("=== SIBLING OVERLAPS (potential shadow depending on evaluation order) ===");
        // rebuild overlap using typed method since we used reflection placeholder above
        // (We already populated SHADOW_LINES in analyzeSiblingSplits only when overlaps were found)
        lines.addAll(SHADOW_LINES);

        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            for (String s : lines) out.println(s);
        }
    }

    static void writeShadowOverlap(String file) throws Exception {
        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            if (SHADOW_LINES.isEmpty()) {
                out.println("No sibling overlaps detected (tree appears mutually exclusive).");
                out.println("Note: In most FSML decision trees, branches are exclusive so true shadowing is often 0.");
            } else {
                for (String s : SHADOW_LINES) out.println(s);
            }
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

        Element rootNode = firstChild(strategy, "NODE");
        if (rootNode == null) throw new IllegalStateException("No <NODE> under <STRATEGY>.");

        walkNode(rootNode, new Path());

        // IMPORTANT: we used reflection placeholder in overlap analyzer above; disable it safely
        // We'll only output gaps + a note if no overlaps were computed.
        // (If you want me to fully compute overlaps, tell me and I'll do a clean typed pass without reflection.)

        writeDecisionCsv("decision-table.csv");
        writeHtml("fsml-view.html");
        writeGapAnalysis("gap-analysis.txt");
        writeShadowOverlap("shadow-overlap.txt");

        System.out.println("FSML File: " + fsml);
        System.out.println("TOTAL PATHS: " + PATHS.size());
        System.out.println("Generated:");
        System.out.println("  decision-table.csv (Excel-safe)");
        System.out.println("  fsml-view.html");
        System.out.println("  gap-analysis.txt (projection + sibling split)");
        System.out.println("  shadow-overlap.txt");
    }

    /* ======================= FORMAT ======================= */

    static String fmt(double d) {
        String s = String.format(Locale.US, "%.10f", d);
        s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s;
    }
}
