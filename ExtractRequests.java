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
            if (min == null && max == null) return var + " = NA";
            if (min != null && max != null && Objects.equals(min, max) && minInc && maxInc) return var + " = " + fmt(min);

            if (min == null) return var + " " + (maxInc ? "<=" : "<") + " " + fmt(max);
            if (max == null) return var + " " + (minInc ? ">=" : ">") + " " + fmt(min);

            String left = fmt(min) + " " + (minInc ? "<=" : "<") + " " + var;
            String right = var + " " + (maxInc ? "<=" : "<") + " " + fmt(max);
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
            for (var e : this.categorical.entrySet()) p.categorical.put(e.getKey(), new ArrayList<>(e.getValue()));
            p.action = this.action;
            p.id = this.id;
            p.invalid = this.invalid;
            return p;
        }
    }

    /* ======================= STATE ======================= */

    static final List<Path> PATHS = new ArrayList<>();
    static final LinkedHashSet<String> ALL_KEYS = new LinkedHashSet<>();
    static final Map<String, Bounds> NUM_BOUNDS = new HashMap<>();
    static final List<String> VARIABLE_ORDER = new ArrayList<>();

    static final List<String> GAP_LINES = new ArrayList<>();
    static final List<String> SIBLING_OVERLAP_LINES = new ArrayList<>();

    /* Shadow relationships (ignoring NA/NAN for shadow detection only) */
    static final Map<Integer, List<Integer>> SHADOW_TREE = new LinkedHashMap<>(); // A -> [B...]
    static final Map<Integer, Integer> SHADOW_PARENT = new HashMap<>();           // B -> A

    /* ======================= XML HELPERS ======================= */

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

    static boolean isMissingToken(String v) {
        if (v == null) return true;
        String t = v.trim();
        return t.isEmpty() || "NaN".equalsIgnoreCase(t) || "NAN".equalsIgnoreCase(t) || "NA".equalsIgnoreCase(t);
    }

    static String resolveValue(String key, String rawVal) {
        if (isMissingToken(rawVal)) return "NA";
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

        // Missing token: keep literal NA
        if ("NA".equalsIgnoreCase(val)) {
            addCategorical(p, key, "eq", "NA");
            return;
        }

        // numeric comparators if numeric value
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

        // categorical otherwise
        addCategorical(p, key, type, val);
    }

    /* ======================= TREE WALK (record ACTION even with children) ======================= */

    static void walkNode(Element node, Path incoming) {
        if (node == null) return;

        Path cur = incoming.copy();

        for (Element c : children(node, "CONDITION")) {
            extractConditionRecursive(c, cur);
            if (cur.invalid) return;
        }

        // record rule if ACTIONS exists (even if node has children)
        Element act = firstChild(node, "ACTIONS");
        if (act != null) {
            Path rule = cur.copy();
            rule.action = attr(act, "Label");
            rule.id = PATHS.size() + 1;
            PATHS.add(rule);
        }

        analyzeSiblingSplits(node, cur);

        for (Element k : children(node, "NODE")) walkNode(k, cur);
    }

    /* ======================= SIBLING SPLIT ANALYSIS ======================= */

    static class KidConstraint {
        Element kid;
        String key;
        Interval interval;
        KidConstraint(Element kid, String key, Interval interval) { this.kid = kid; this.key = key; this.interval = interval; }
    }

    static void analyzeSiblingSplits(Element parent, Path parentCtx) {
        List<Element> kids = children(parent, "NODE");
        if (kids.size() < 2) return;

        List<KidConstraint> numericKids = new ArrayList<>();
        for (Element kid : kids) {
            Path tmp = parentCtx.copy();
            for (Element c : children(kid, "CONDITION")) extractConditionRecursive(c, tmp);
            if (tmp.invalid) continue;

            for (var e : tmp.numeric.entrySet()) {
                String k = e.getKey();
                Interval childInt = e.getValue();

                Interval parentInt = parentCtx.numeric.get(k);
                Bounds b = NUM_BOUNDS.get(k);
                if (parentInt == null) parentInt = (b != null) ? Interval.universe(b) : new Interval(null, true, null, true);

                boolean different = !parentInt.covers(childInt) || !childInt.covers(parentInt);
                if (different) {
                    numericKids.add(new KidConstraint(kid, k, childInt));
                    break;
                }
            }
        }

        if (numericKids.size() < 2) return;

        Map<String, List<KidConstraint>> byKey = numericKids.stream().collect(Collectors.groupingBy(k -> k.key));

        for (var entry : byKey.entrySet()) {
            String key = entry.getKey();
            List<KidConstraint> list = entry.getValue();
            if (list.size() < 2) continue;

            Bounds b = NUM_BOUNDS.get(key);
            Interval parentRange = parentCtx.numeric.get(key);
            if (parentRange == null) parentRange = (b != null) ? Interval.universe(b) : new Interval(null, true, null, true);

            List<Interval> union = normalizeUnion(list.stream().map(k -> k.interval).collect(Collectors.toList()));
            List<Interval> gaps = gapsWithin(parentRange, union);

            if (!gaps.isEmpty()) {
                GAP_LINES.add("SIBLING GAP under parent NODE Label=\"" + attr(parent,"Label") + "\" key=" + key);
                GAP_LINES.add("  ParentRange: " + parentRange.asText(key));
                for (Interval g : gaps) GAP_LINES.add("  GAP: " + g.asText(key));
                GAP_LINES.add("");
            }

            List<String> overlaps = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                for (int j = i + 1; j < list.size(); j++) {
                    Interval inter = list.get(i).interval.intersect(list.get(j).interval);
                    if (inter != null) {
                        overlaps.add("  OVERLAP child \"" + attr(list.get(i).kid,"Label") + "\" vs \"" + attr(list.get(j).kid,"Label") + "\": "
                                + inter.asText(key));
                    }
                }
            }
            if (!overlaps.isEmpty()) {
                SIBLING_OVERLAP_LINES.add("SIBLING OVERLAPS under parent NODE Label=\"" + attr(parent,"Label") + "\" key=" + key);
                SIBLING_OVERLAP_LINES.add("  ParentRange: " + parentRange.asText(key));
                SIBLING_OVERLAP_LINES.addAll(overlaps);
                SIBLING_OVERLAP_LINES.add("");
            }
        }
    }

    /* ======================= OVERLAP + SHADOW ======================= */

    // Overlap (existence) keeps strict NA semantics (optional). Shadow ignores NA per your request.
    static boolean overlapExistsStrict(Path a, Path b, List<String> witnessOut) {
        Set<String> numericKeys = new LinkedHashSet<>();
        numericKeys.addAll(a.numeric.keySet());
        numericKeys.addAll(b.numeric.keySet());

        for (String k : numericKeys) {
            Interval ia = getIntervalOrUniverse(a, k);
            Interval ib = getIntervalOrUniverse(b, k);

            // Strict: NA treated as categorical equality -> only overlaps if both NA
            if (hasEqNA(a, k) || hasEqNA(b, k)) {
                if (!(hasEqNA(a, k) && hasEqNA(b, k))) return false;
                if (witnessOut != null) witnessOut.add(k + " = NA");
                continue;
            }

            Interval inter = ia.intersect(ib);
            if (inter == null) return false;
            if (witnessOut != null) witnessOut.add(inter.asText(k));
        }

        Set<String> catKeys = new LinkedHashSet<>();
        catKeys.addAll(a.categorical.keySet());
        catKeys.addAll(b.categorical.keySet());

        for (String k : catKeys) {
            List<String> pa = a.categorical.getOrDefault(k, List.of());
            List<String> pb = b.categorical.getOrDefault(k, List.of());

            String eqA = requiredEq(pa);
            String eqB = requiredEq(pb);

            if ("__CONFLICT__".equals(eqA) || "__CONFLICT__".equals(eqB)) return false;

            if (eqA != null && eqB != null && !eqA.equals(eqB)) return false;
            if (eqA != null && violatesNe(eqA, pb)) return false;
            if (eqB != null && violatesNe(eqB, pa)) return false;

            if (witnessOut != null) {
                if (eqA != null || eqB != null) {
                    String v = eqA != null ? eqA : eqB;
                    witnessOut.add(k + " = " + v);
                }
            }
        }
        return true;
    }

    /**
     * SHADOW COVERAGE ignoring NA:
     * - If either side has eq:NA for a variable, we ignore that variable for coverage.
     * - This prevents NA from blocking shadow detection.
     */
    static boolean coversIgnoringNA(Path a, Path b) {
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(a.numeric.keySet());
        keys.addAll(b.numeric.keySet());
        keys.addAll(a.categorical.keySet());
        keys.addAll(b.categorical.keySet());

        for (String k : keys) {
            // If either path has NA on this key, ignore it for shadow logic
            if (hasEqNA(a, k) || hasEqNA(b, k)) continue;

            // numeric coverage
            Interval ia = getIntervalOrUniverse(a, k);
            Interval ib = getIntervalOrUniverse(b, k);
            // if b doesn't constrain numeric and a also doesn't, ok
            // universe covers universe ok
            if (b.numeric.containsKey(k) || a.numeric.containsKey(k) || NUM_BOUNDS.containsKey(k)) {
                if (!ia.covers(ib)) return false;
            }

            // categorical coverage (conservative but useful):
            // if A requires eq, B must have same eq to be covered
            String eqA = requiredEq(a.categorical.getOrDefault(k, List.of()));
            String eqB = requiredEq(b.categorical.getOrDefault(k, List.of()));
            if ("__CONFLICT__".equals(eqA) || "__CONFLICT__".equals(eqB)) return false;

            if (eqA != null) {
                if (eqB == null) return false;
                if (!eqA.equals(eqB)) return false;
            }

            // If A has NE, don't claim coverage (conservative)
            if (hasNe(a.categorical.getOrDefault(k, List.of()))) return false;
        }
        return true;
    }

    static void computeShadowTreeIgnoringNA() {
        SHADOW_TREE.clear();
        SHADOW_PARENT.clear();

        // For each rule B, attach it to the FIRST earlier rule A that covers it (ignoring NA)
        for (int j = 0; j < PATHS.size(); j++) {
            Path b = PATHS.get(j);
            Integer parent = null;
            for (int i = 0; i < j; i++) {
                Path a = PATHS.get(i);
                if (coversIgnoringNA(a, b)) { parent = a.id; break; }
            }
            if (parent != null) {
                SHADOW_PARENT.put(b.id, parent);
                SHADOW_TREE.computeIfAbsent(parent, x -> new ArrayList<>()).add(b.id);
            }
        }

        // Ensure all shadowing parents exist as keys (even if empty) for tree rendering
        for (Integer a : new ArrayList<>(SHADOW_TREE.keySet())) {
            SHADOW_TREE.putIfAbsent(a, SHADOW_TREE.get(a));
        }
    }

    static void writeOverlapAndShadowReport(String file) throws Exception {
        computeShadowTreeIgnoringNA();

        List<String> shadowedLines = new ArrayList<>();
        for (var e : SHADOW_PARENT.entrySet()) {
            int bId = e.getKey();
            int aId = e.getValue();
            Path a = PATHS.get(aId - 1);
            Path b = PATHS.get(bId - 1);
            shadowedLines.add("Rule " + bId + " is SHADOWED by Rule " + aId + " (IGNORING NA). "
                    + "B.action=\"" + safe(b.action) + "\"  A.action=\"" + safe(a.action) + "\"");
        }

        // Optionally include overlaps (strict)
        int overlapCount = 0;
        for (int i = 0; i < PATHS.size(); i++) {
            for (int j = i + 1; j < PATHS.size(); j++) {
                if (overlapExistsStrict(PATHS.get(i), PATHS.get(j), null)) overlapCount++;
            }
        }

        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            out.println("TOTAL RULES: " + PATHS.size());
            out.println("OVERLAPPING RULE PAIRS (STRICT NA SEMANTICS): " + overlapCount);
            out.println("");
            out.println("SHADOWED RULES (IGNORING NA/NAN/empty): " + shadowedLines.size());
            for (String s : shadowedLines) out.println("  " + s);
            out.println("");
            out.println("NOTE:");
            out.println("- Shadow detection here ignores NA/NAN so missing checks do not block coverage/subsumption.");
            out.println("- If your engine treats NA as a real constraint, strict shadowing may differ.");
        }
    }

    /* ======================= RULE VIEW / CONDITIONS RENDER ======================= */

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

        for (var e : p.numeric.entrySet()) {
            String k = e.getKey();
            if (hasEqNA(p, k)) continue;
            Interval in = e.getValue();
            if (in == null) continue;

            Bounds b = NUM_BOUNDS.get(k);
            if (b != null) {
                Interval u = Interval.universe(b);
                if (u.covers(in) && in.covers(u)) continue;
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

    /* ======================= OUTPUT: CSV + HTML ======================= */

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
                    if (p.numeric.containsKey(v) && !hasEqNA(p, v)) {
                        cell = p.numeric.get(v).asText(v);
                    } else if (p.categorical.containsKey(v)) {
                        cell = p.categorical.get(v).stream().map(cl -> {
                            String[] parts = cl.split(":",2);
                            String op = parts[0];
                            String val = parts.length>1?parts[1]:"";
                            return opSymbol(op) + " " + val;
                        }).collect(Collectors.joining(" AND "));
                    } else {
                        cell = "NA";
                    }
                    out.print("," + csvEscape(excelSafe(cell)));
                }
                out.println("," + csvEscape(excelSafe(p.action == null ? "NA" : p.action)));
            }
        }
    }

    static void writeRuleHtml(String file) throws Exception {
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
        sb.append("<div class='pill'><b>Total rules:</b> ").append(PATHS.size()).append("</div>");
        sb.append("<div class='pill'><b>Vars with bounds:</b> ").append(NUM_BOUNDS.size()).append("</div>");
        sb.append("</div>");

        sb.append("<div class='grid'>");
        for (Path p : PATHS) {
            sb.append("<div class='card'>");
            sb.append("<div class='title'>Rule ").append(p.id).append("</div>");

            List<String> conds = renderConditions(p);
            for (String c : conds) sb.append("<div class='cond mono'>➜ ").append(esc(c)).append("</div>");

            sb.append("<div class='action'>ACTION → ").append(esc(p.action == null ? "NA" : p.action)).append("</div>");
            sb.append("</div>");
        }
        sb.append("</div></body></html>");

        try (Writer out = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            out.write(sb.toString());
        }
    }

    /* ======================= NEW: SHADOW TREE VIEW ======================= */

    static void writeShadowTreeHtml(String file) throws Exception {
        computeShadowTreeIgnoringNA();

        // roots = rules that shadow others but are not themselves shadowed (or simply all parents)
        LinkedHashSet<Integer> parents = new LinkedHashSet<>(SHADOW_TREE.keySet());
        List<Integer> roots = parents.stream().filter(a -> !SHADOW_PARENT.containsKey(a)).collect(Collectors.toList());
        if (roots.isEmpty()) roots = new ArrayList<>(parents);

        StringBuilder sb = new StringBuilder();
        sb.append("<html><head><meta charset='UTF-8'>");
        sb.append("<style>");
        sb.append("body{font-family:Arial;background:#f4f6f8;margin:20px;color:#2c3e50}");
        sb.append(".box{background:#fff;border-radius:14px;padding:16px;box-shadow:0 2px 10px rgba(0,0,0,.12)}");
        sb.append("details{margin:10px 0}");
        sb.append("summary{cursor:pointer;font-weight:900}");
        sb.append(".row{margin:8px 0;padding:10px;border-radius:12px;background:#fafbfc;border-left:5px solid #dfe6e9}");
        sb.append(".tag{display:inline-block;padding:4px 8px;border-radius:999px;background:#eef2ff;font-weight:800;margin-right:8px}");
        sb.append(".act{display:inline-block;margin-top:6px;font-weight:900;color:#c0392b;background:#fff3cd;border:1px solid #ffeeba;padding:6px 10px;border-radius:999px}");
        sb.append(".mono{font-family:ui-monospace,SFMono-Regular,Menlo,Monaco,Consolas,monospace}");
        sb.append("</style></head><body>");

        sb.append("<h1>Shadow Paths Tree (Ignoring NA)</h1>");
        sb.append("<div class='box'>");
        sb.append("<div class='row'><span class='tag'>Definition</span> Rule B is shadowed by Rule A if A fully covers B when we IGNORE NA/NAN constraints.</div>");
        sb.append("<div class='row'><span class='tag'>Total shadowed</span> ").append(SHADOW_PARENT.size()).append("</div>");

        for (Integer r : roots) {
            sb.append(renderShadowNodeHtml(r, 0));
        }

        sb.append("</div></body></html>");

        try (Writer out = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            out.write(sb.toString());
        }
    }

    static String renderShadowNodeHtml(int ruleId, int depth) {
        Path p = PATHS.get(ruleId - 1);
        List<Integer> kids = SHADOW_TREE.getOrDefault(ruleId, List.of());

        StringBuilder sb = new StringBuilder();
        sb.append("<details ").append(depth == 0 ? "open" : "").append(">");
        sb.append("<summary>Rule ").append(ruleId).append(" (shadows ").append(kids.size()).append(")</summary>");

        sb.append("<div class='row'>");
        sb.append("<div class='mono'><b>Conditions:</b><br>");
        for (String c : renderConditions(p)) sb.append("➜ ").append(esc(c)).append("<br>");
        sb.append("</div>");
        sb.append("<div class='act'>ACTION → ").append(esc(p.action == null ? "NA" : p.action)).append("</div>");
        sb.append("</div>");

        for (Integer k : kids) {
            sb.append(renderShadowNodeHtml(k, depth + 1));
        }

        sb.append("</details>");
        return sb.toString();
    }

    static void writeShadowTreeTxt(String file) throws Exception {
        computeShadowTreeIgnoringNA();

        LinkedHashSet<Integer> parents = new LinkedHashSet<>(SHADOW_TREE.keySet());
        List<Integer> roots = parents.stream().filter(a -> !SHADOW_PARENT.containsKey(a)).collect(Collectors.toList());
        if (roots.isEmpty()) roots = new ArrayList<>(parents);

        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            out.println("SHADOW TREE (Ignoring NA)");
            out.println("========================");
            out.println("Total shadowed: " + SHADOW_PARENT.size());
            out.println("");
            for (Integer r : roots) dumpShadowTxt(r, out, 0);
        }
    }

    static void dumpShadowTxt(int ruleId, PrintWriter out, int depth) {
        Path p = PATHS.get(ruleId - 1);
        String pad = "  ".repeat(Math.max(0, depth));
        out.println(pad + "- Rule " + ruleId + "  ACTION: " + safe(p.action));
        for (String c : renderConditions(p)) out.println(pad + "    * " + c);
        for (Integer k : SHADOW_TREE.getOrDefault(ruleId, List.of())) dumpShadowTxt(k, out, depth + 1);
    }

    static void writeShadowTreeDot(String file) throws Exception {
        computeShadowTreeIgnoringNA();

        StringBuilder sb = new StringBuilder();
        sb.append("digraph SHADOW {\n");
        sb.append("  rankdir=LR;\n");
        sb.append("  node [shape=box, style=\"rounded,filled\", fillcolor=\"#ffffff\", fontname=\"Arial\"];\n");
        sb.append("  edge [fontname=\"Arial\"];\n");

        // Nodes
        for (Path p : PATHS) {
            if (!SHADOW_TREE.containsKey(p.id) && !SHADOW_PARENT.containsKey(p.id)) continue; // only show shadow-related
            String label = "Rule " + p.id + "\\n" + (p.action == null ? "NA" : p.action);
            sb.append("  r").append(p.id).append(" [label=\"").append(dotEsc(label)).append("\"];\n");
        }

        // Edges (A -> B)
        for (var e : SHADOW_TREE.entrySet()) {
            int a = e.getKey();
            for (int b : e.getValue()) {
                sb.append("  r").append(a).append(" -> r").append(b).append(";\n");
            }
        }

        sb.append("}\n");

        try (Writer out = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            out.write(sb.toString());
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

    static List<Interval> gapsWithin(Interval universe, List<Interval> coveredUnion) {
        List<Interval> gaps = new ArrayList<>();
        Interval u = universe;

        Double cur = u.min;
        boolean curInc = u.minInc;

        for (Interval cov : coveredUnion) {
            Interval c = cov.intersect(u);
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
            Interval tail = new Interval(cur, curInc, u.max, u.maxInc);
            Interval ok = tail.intersect(u);
            if (ok != null) gaps.add(ok);
        }

        return gaps.stream().filter(g -> g.intersect(u) != null).collect(Collectors.toList());
    }

    static void writeGapAnalysis(String file) throws Exception {
        List<String> lines = new ArrayList<>();

        for (String var : orderedVarsForOutput()) {
            Bounds b = NUM_BOUNDS.get(var);
            if (b == null) continue;

            Interval universe = Interval.universe(b);

            List<Interval> projected = new ArrayList<>();
            for (Path p : PATHS) {
                if (hasEqNA(p, var)) continue;
                Interval in = p.numeric.get(var);
                if (in == null) in = universe;
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

        lines.add("=== SIBLING SPLIT GAPS (tree-accurate) ===");
        lines.addAll(GAP_LINES);
        lines.add("");
        lines.add("=== SIBLING SPLIT OVERLAPS (tree-accurate) ===");
        lines.addAll(SIBLING_OVERLAP_LINES);

        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            for (String s : lines) out.println(s);
        }
    }

    /* ======================= SMALL UTILITIES ======================= */

    static Interval getIntervalOrUniverse(Path p, String k) {
        Interval in = p.numeric.get(k);
        if (in != null) return in;
        Bounds b = NUM_BOUNDS.get(k);
        return (b != null) ? Interval.universe(b) : new Interval(null, true, null, true);
    }

    static boolean hasEqNA(Path p, String k) {
        List<String> preds = p.categorical.get(k);
        if (preds == null) return false;
        for (String cl : preds) {
            String[] parts = cl.split(":",2);
            if (parts.length == 2 && "eq".equals(parts[0])) {
                String v = parts[1];
                if (isMissingToken(v) || "NA".equalsIgnoreCase(v)) return true;
            }
        }
        return false;
    }

    static String requiredEq(List<String> preds) {
        String eq = null;
        for (String cl : preds) {
            String[] parts = cl.split(":",2);
            if (parts.length == 2 && "eq".equals(parts[0])) {
                String v = parts[1];
                // keep NA in eq; caller decides whether to ignore
                if (eq == null) eq = v;
                else if (!eq.equals(v)) return "__CONFLICT__";
            }
        }
        return eq;
    }

    static boolean violatesNe(String eqValue, List<String> preds) {
        for (String cl : preds) {
            String[] parts = cl.split(":",2);
            if (parts.length == 2 && "ne".equals(parts[0]) && eqValue.equals(parts[1])) return true;
        }
        return false;
    }

    static boolean hasNe(List<String> preds) {
        for (String cl : preds) if (cl.startsWith("ne:")) return true;
        return false;
    }

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

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");
    }

    static String dotEsc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    static String safe(String s) { return s == null ? "" : s; }

    static String fmt(double d) {
        String s = String.format(Locale.US, "%.10f", d);
        s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s;
    }

    /* ======================= MAIN ======================= */

    public static void main(String[] args) throws Exception {
        String fsml = (args.length > 0) ? args[0] : "PenFed_AR_Expert_09042025.fsml";

        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        Document doc = f.newDocumentBuilder().parse(new File(fsml));

        parseNumericBounds(doc);
        parseVariableOrder(doc);

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

        // Outputs
        writeDecisionCsv("decision-table.csv");
        writeRuleHtml("fsml-view.html");
        writeGapAnalysis("gap-analysis.txt");

        // Shadow analysis ignoring NA + tree views
        writeOverlapAndShadowReport("overlap-analysis.txt");
        writeShadowTreeHtml("shadow-tree.html");
        writeShadowTreeTxt("shadow-tree.txt");
        writeShadowTreeDot("shadow-tree.dot");

        System.out.println("FSML File: " + fsml);
        System.out.println("TOTAL RULES: " + PATHS.size());
        System.out.println("SHADOWED (ignoring NA): " + SHADOW_PARENT.size());
        System.out.println("Generated:");
        System.out.println("  decision-table.csv");
        System.out.println("  fsml-view.html");
        System.out.println("  gap-analysis.txt");
        System.out.println("  overlap-analysis.txt (includes SHADOW ignoring NA)");
        System.out.println("  shadow-tree.html (NEW)");
        System.out.println("  shadow-tree.txt  (NEW)");
        System.out.println("  shadow-tree.dot  (NEW graphviz)");
    }
}
