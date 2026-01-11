import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * FSML Analyzer (single-file, Java 21 compatible, no Swing)
 *
 * Outputs:
 *  - decision-table.csv
 *  - fsml-rules.html
 *  - fsml-tree.html
 *  - gap-analysis.txt
 *  - shadowed-paths.txt
 *  - shadow-tree.html
 *
 * Notes:
 *  - LOW/HIGH are mapped to variable min/max only when variable is numeric.
 *  - NA / NAN are treated as literal categorical values (never parsed as numbers).
 *  - Shadow detection can optionally ignore NA/NAN dimensions (config flag),
 *    but DOES NOT drop other constraints (prevents "Rule 1 shadows all" bug).
 */
public class FsmlAnalyzerMain {

    /* ============================== CONFIG ============================== */

    static boolean IGNORE_NA_FOR_SHADOW = true;

    // Strings treated as NA-like
    static final Set<String> NA_LIKE = new HashSet<>(Arrays.asList("NA", "N/A", "NAN", "NULL", "NONE"));

    /* ============================== MODEL ============================== */

    static class VarDef {
        String name;            // ShortName / DecisionKey
        boolean numeric;
        double min = -9999999;
        double max =  9999999;
        List<String> categories = new ArrayList<>(); // for categorical vars
    }

    static class Interval {
        double lo; // inclusive
        double hi; // exclusive-ish (we store as hi, and keep operator separately when rendering if needed)
        boolean loInclusive = true;
        boolean hiInclusive = false; // lt vs le

        Interval(double lo, boolean loInc, double hi, boolean hiInc) {
            this.lo = lo;
            this.hi = hi;
            this.loInclusive = loInc;
            this.hiInclusive = hiInc;
        }

        static Interval universe(VarDef v) {
            return new Interval(v.min, true, v.max, true);
        }

        Interval copy() { return new Interval(lo, loInclusive, hi, hiInclusive); }

        // Intersect with other; return null if empty
        Interval intersect(Interval o) {
            double nlo = this.lo;
            boolean nloInc = this.loInclusive;
            if (o.lo > nlo || (o.lo == nlo && o.loInclusive && !nloInc)) {
                nlo = o.lo;
                nloInc = o.loInclusive;
            } else if (o.lo == nlo) {
                nloInc = this.loInclusive && o.loInclusive;
            }

            double nhi = this.hi;
            boolean nhiInc = this.hiInclusive;
            if (o.hi < nhi || (o.hi == nhi && o.hiInclusive && !nhiInc)) {
                nhi = o.hi;
                nhiInc = o.hiInclusive;
            } else if (o.hi == nhi) {
                nhiInc = this.hiInclusive && o.hiInclusive;
            }

            // emptiness check
            if (nlo < nhi) return new Interval(nlo, nloInc, nhi, nhiInc);
            if (nlo == nhi) {
                // Single point only valid if both inclusive at boundary
                if (nloInc && nhiInc) return new Interval(nlo, true, nhi, true);
            }
            return null;
        }

        boolean covers(Interval other) {
            // this superset of other
            // lower
            if (this.lo > other.lo) return false;
            if (this.lo == other.lo && !this.loInclusive && other.loInclusive) return false;
            // upper
            if (this.hi < other.hi) return false;
            if (this.hi == other.hi && !this.hiInclusive && other.hiInclusive) return false;
            return true;
        }

        boolean overlaps(Interval o) {
            return this.intersect(o) != null;
        }

        String pretty(VarDef v) {
            String l = (loInclusive ? "[" : "(") + trimNum(lo);
            String r = trimNum(hi) + (hiInclusive ? "]" : ")");
            // For display, prefer math style: lo <= x < hi
            return (loInclusive ? trimNum(lo) + " <= " : trimNum(lo) + " < ")
                    + v.name + " "
                    + (hiInclusive ? "<= " : "< ")
                    + trimNum(hi);
        }
    }

    // Constraint per variable can be numeric interval AND/OR categorical equality list
    static class Constraint {
        // numeric constraint (interval). If null -> unconstrained numeric.
        Interval interval;
        // categorical constraints: list of (op, value). We keep multiple conditions.
        // Example: eq Active_Other; ne X; etc.
        List<CatAtom> cat = new ArrayList<>();

        Constraint copy() {
            Constraint c = new Constraint();
            c.interval = (this.interval == null ? null : this.interval.copy());
            c.cat = new ArrayList<>(this.cat);
            return c;
        }
    }

    static class CatAtom {
        String op;     // eq, ne, etc.
        String value;  // literal
        CatAtom(String op, String value) { this.op = op; this.value = value; }
        @Override public String toString() { return op + " " + value; }
    }

    static class RulePath {
        int id;
        String action;
        // maintain insertion order for consistent CSV/HTML
        LinkedHashMap<String, Constraint> byVar = new LinkedHashMap<>();
        // optional: original path labels if needed
    }

    /* ============================== STATE ============================== */

    static final Map<String, VarDef> VARS = new LinkedHashMap<>();
    static final List<RulePath> PATHS = new ArrayList<>();

    /* ============================== XML HELPERS ============================== */

    static String localName(Node n) {
        if (n == null) return null;
        String ln = n.getLocalName();
        if (ln != null) return ln;
        String nn = n.getNodeName();
        if (nn == null) return null;
        int idx = nn.indexOf(':');
        return idx >= 0 ? nn.substring(idx + 1) : nn;
    }

    static List<Element> childElements(Element parent) {
        List<Element> out = new ArrayList<>();
        if (parent == null) return out;
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n instanceof Element e) out.add(e);
        }
        return out;
    }

    static List<Element> children(Element parent, String name) {
        List<Element> out = new ArrayList<>();
        for (Element e : childElements(parent)) {
            if (name.equalsIgnoreCase(localName(e))) out.add(e);
        }
        return out;
    }

    static Element firstChild(Element parent, String name) {
        for (Element e : children(parent, name)) return e;
        return null;
    }

    static List<Element> allElements(Document doc, String tagName) {
        List<Element> res = new ArrayList<>();
        NodeList nl = doc.getElementsByTagName("*");
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n instanceof Element e) {
                if (tagName.equalsIgnoreCase(localName(e))) res.add(e);
            }
        }
        return res;
    }

    /* ============================== PARSING VARS ============================== */

    static void parseVariables(Document doc) {
        // NumericKey
        for (Element nk : allElements(doc, "NumericKey")) {
            String shortName = attr(nk, "ShortName");
            if (shortName == null || shortName.isBlank()) continue;

            VarDef v = VARS.computeIfAbsent(shortName, k -> {
                VarDef nv = new VarDef();
                nv.name = k;
                return nv;
            });
            v.numeric = true;

            Element range = firstChild(nk, "NumericRange");
            if (range != null) {
                String minV = attr(range, "minValue");
                String maxV = attr(range, "maxValue");
                Double mn = safeDouble(minV);
                Double mx = safeDouble(maxV);
                if (mn != null) v.min = mn;
                if (mx != null) v.max = mx;
            }
        }

        // CategoricalKey
        for (Element ck : allElements(doc, "CategoricalKey")) {
            String shortName = attr(ck, "ShortName");
            if (shortName == null || shortName.isBlank()) continue;

            VarDef v = VARS.computeIfAbsent(shortName, k -> {
                VarDef nv = new VarDef();
                nv.name = k;
                return nv;
            });
            v.numeric = false;

            for (Element cat : children(ck, "CATEGORY")) {
                String val = attr(cat, "Value");
                if (val != null && !val.isBlank()) v.categories.add(val);
            }
        }
    }

    /* ============================== PARSING TREE / PATHS ============================== */

    static void extractAllPaths(Document doc) {
        Element strategy = null;
        // Find STRATEGY element (first)
        for (Element e : allElements(doc, "STRATEGY")) { strategy = e; break; }
        if (strategy == null) throw new IllegalStateException("No <STRATEGY> found in FSML.");

        // Root NODE under strategy
        Element rootNode = firstChild(strategy, "NODE");
        if (rootNode == null) {
            // sometimes there may be wrapper nodes; find first NODE anywhere under STRATEGY
            List<Element> nodes = children(strategy, "NODE");
            if (!nodes.isEmpty()) rootNode = nodes.get(0);
        }
        if (rootNode == null) throw new IllegalStateException("No <NODE> found under <STRATEGY>.");

        RulePath start = new RulePath();
        start.byVar = new LinkedHashMap<>();
        walkNode(rootNode, start);
    }

    /**
     * Walk the FSML decision tree.
     * Important fix: Nodes can have:
     *  - a <CONDITION ...> block containing multiple <CONDITION DecisionKey=...> children (AND)
     *  - nested nodes, where action can appear even if there are child nodes (we treat as leaf action if present)
     */
    static void walkNode(Element node, RulePath incoming) {
        if (node == null) return;

        RulePath cur = clonePath(incoming);

        // A NODE may contain:
        //   <CONDITION Type="true"/> OR
        //   <CONDITION DecisionKey="X" Type="and"> <CONDITION .../> <CONDITION .../> </CONDITION>
        // and/or multiple top-level CONDITION elements.
        for (Element cond : children(node, "CONDITION")) {
            applyConditionElement(cond, cur);
        }

        // If node has ACTIONS, record a rule path (even if it has children)
        Element act = firstChild(node, "ACTIONS");
        if (act != null) {
            String label = attr(act, "Label");
            if (label == null) label = "";
            RulePath leaf = clonePath(cur);
            leaf.action = label.trim();
            leaf.id = PATHS.size() + 1;
            PATHS.add(leaf);
        }

        // Then traverse child nodes
        List<Element> kids = children(node, "NODE");
        for (Element k : kids) walkNode(k, cur);
    }

    static RulePath clonePath(RulePath p) {
        RulePath np = new RulePath();
        np.id = p.id;
        np.action = p.action;
        np.byVar = new LinkedHashMap<>();
        for (Map.Entry<String, Constraint> e : p.byVar.entrySet()) {
            np.byVar.put(e.getKey(), e.getValue().copy());
        }
        return np;
    }

    /**
     * Apply a CONDITION element.
     * Handles:
     *  - <CONDITION Type="true"/> -> no-op
     *  - <CONDITION DecisionKey="X" Type="and"> <CONDITION DecisionKey="X" Value="LOW" Type="ge"/> ... </CONDITION>
     *  - <CONDITION DecisionKey="X" Value="3" Type="lt"/>
     *
     * We treat nested conditions as conjunction and collect leaf atoms.
     */
    static void applyConditionElement(Element cond, RulePath path) {
        String type = attr(cond, "Type");
        String key = attr(cond, "DecisionKey");
        String value = attr(cond, "Value");

        // <CONDITION Type="true"/>
        if ((key == null || key.isBlank()) && "true".equalsIgnoreCase(type)) return;

        // If this condition has child CONDITION nodes, apply them (AND group)
        List<Element> nested = children(cond, "CONDITION");
        if (!nested.isEmpty()) {
            for (Element c : nested) applyConditionElement(c, path);
            return;
        }

        // Leaf atom requires DecisionKey + Type(op) + Value
        if (key == null || key.isBlank()) return;
        if (type == null || type.isBlank()) return;

        addAtom(path, key.trim(), type.trim(), value);
    }

    static void addAtom(RulePath path, String key, String op, String rawValue) {
        path.byVar.putIfAbsent(key, new Constraint());
        Constraint c = path.byVar.get(key);

        VarDef v = VARS.get(key);
        String val = rawValue == null ? "" : rawValue.trim();

        // Preserve NA/NAN literally (do not parse)
        boolean isNA = isNaLike(val);

        // If var is numeric AND op is a relational AND value looks numeric or LOW/HIGH -> treat as interval constraint
        boolean relational = isRelational(op);
        if (v != null && v.numeric && relational && !isNA) {
            Double num = numericValueFor(v, val);
            if (num == null) {
                // fall back to categorical atom if cannot parse
                c.cat.add(new CatAtom(op, val));
                return;
            }
            Interval local = intervalFromOp(v, op, num);
            if (local == null) return;

            if (c.interval == null) {
                c.interval = local;
            } else {
                Interval merged = c.interval.intersect(local);
                if (merged == null) {
                    // Contradiction in same path; keep as impossible interval
                    // We'll mark by setting interval to empty sentinel
                    c.interval = null; // null means unconstrained; but we want "impossible"
                    // Instead: store a categorical marker:
                    c.cat.add(new CatAtom("IMPOSSIBLE", key));
                } else {
                    c.interval = merged;
                }
            }
        } else {
            // categorical OR NA/NAN OR unknown var: store as categorical atom
            if (val.isEmpty()) val = "(empty)";
            c.cat.add(new CatAtom(op, val));
        }
    }

    static boolean isNaLike(String s) {
        if (s == null) return false;
        String t = s.trim().toUpperCase(Locale.ROOT);
        return NA_LIKE.contains(t);
    }

    static boolean isRelational(String op) {
        String o = op.toLowerCase(Locale.ROOT);
        return o.equals("lt") || o.equals("le") || o.equals("gt") || o.equals("ge") || o.equals("eq") || o.equals("ne");
    }

    static Double numericValueFor(VarDef v, String val) {
        if (val == null) return null;
        String t = val.trim();
        if (t.isEmpty()) return null;

        if ("LOW".equalsIgnoreCase(t)) return v.min;
        if ("HIGH".equalsIgnoreCase(t)) return v.max;

        // avoid NumberFormatException on empty / NA
        Double d = safeDouble(t);
        return d;
    }

    static Interval intervalFromOp(VarDef v, String op, double num) {
        String o = op.toLowerCase(Locale.ROOT);
        Interval universe = Interval.universe(v);
        if (o.equals("ge")) return universe.intersect(new Interval(num, true, v.max, true));
        if (o.equals("gt")) return universe.intersect(new Interval(num, false, v.max, true));
        if (o.equals("lt")) return universe.intersect(new Interval(v.min, true, num, false));
        if (o.equals("le")) return universe.intersect(new Interval(v.min, true, num, true));
        if (o.equals("eq")) return universe.intersect(new Interval(num, true, num, true));
        // ne is hard as interval; keep as categorical atom rather than numeric interval
        return null;
    }

    static Double safeDouble(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        if (isNaLike(t)) return null;
        try {
            return Double.parseDouble(t);
        } catch (Exception e) {
            return null;
        }
    }

    static String attr(Element e, String name) {
        if (e == null) return null;
        if (!e.hasAttribute(name)) return null;
        String v = e.getAttribute(name);
        return v == null ? null : v;
    }

    /* ============================== DECISION TABLE ============================== */

    static void writeDecisionTableCsv(Path out) throws IOException {
        // Determine columns: all vars encountered in paths, preserve strategy order if possible
        LinkedHashSet<String> cols = new LinkedHashSet<>();
        for (RulePath p : PATHS) cols.addAll(p.byVar.keySet());

        // If FSML has VARIABLE-ORDER, use it to order columns
        List<String> ordered = extractVariableOrderFromDocIfPossible();
        if (!ordered.isEmpty()) {
            LinkedHashSet<String> newCols = new LinkedHashSet<>();
            for (String k : ordered) if (cols.contains(k)) newCols.add(k);
            for (String k : cols) newCols.add(k);
            cols = newCols;
        }

        List<String> colList = new ArrayList<>(cols);

        try (BufferedWriter bw = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            // header
            bw.write("RuleId");
            for (String c : colList) bw.write("," + csv(c));
            bw.write(",Action\n");

            for (RulePath p : PATHS) {
                bw.write(String.valueOf(p.id));
                for (String c : colList) {
                    String cell = renderCell(p, c);
                    bw.write("," + csv(cell));
                }
                bw.write("," + csv(p.action));
                bw.write("\n");
            }
        }
    }

    static String renderCell(RulePath p, String var) {
        Constraint c = p.byVar.get(var);
        if (c == null) return ""; // blank instead of ANY
        VarDef v = VARS.get(var);

        List<String> parts = new ArrayList<>();
        if (c.interval != null && v != null && v.numeric) {
            parts.add(c.interval.pretty(v));
        }
        if (!c.cat.isEmpty()) {
            parts.add(c.cat.stream().map(CatAtom::toString).collect(Collectors.joining(" AND ")));
        }
        return String.join(" AND ", parts);
    }

    // best-effort extract VARIABLE-ORDER
    static List<String> extractVariableOrderFromDocIfPossible() {
        // We do not keep Document globally; in typical runs, this stays empty.
        // We'll order by VARS insertion order if variable-order isn't available.
        return new ArrayList<>(VARS.keySet());
    }

    static String csv(String s) {
        if (s == null) return "";
        boolean need = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r");
        if (!need) return s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    /* ============================== GAP ANALYSIS ============================== */

    static void writeGapAnalysis(Path out) throws IOException {
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
            pw.println("FSML GAP ANALYSIS (numeric coverage vs universe)");
            pw.println("Total rules(paths): " + PATHS.size());
            pw.println();

            for (VarDef v : VARS.values()) {
                if (!v.numeric) continue;

                Interval universe = Interval.universe(v);
                List<Interval> covered = new ArrayList<>();

                for (RulePath p : PATHS) {
                    Constraint c = p.byVar.get(v.name);
                    if (c != null && c.interval != null) {
                        covered.add(c.interval);
                    } else {
                        // unconstrained in this rule => covers full universe
                        covered.add(universe.copy());
                    }
                }

                List<Interval> union = unionIntervals(covered);
                List<Interval> gaps = gapsFromUnion(union, universe);

                pw.println("Variable: " + v.name);
                pw.println("  Universe: " + universe.pretty(v));
                pw.println("  Union covered segments: " + union.size());
                for (Interval u : union) pw.println("    - " + u.pretty(v));
                pw.println("  Gaps: " + gaps.size());
                for (Interval g : gaps) pw.println("    * " + g.pretty(v));
                pw.println();
            }
        }
    }

    static List<Interval> unionIntervals(List<Interval> in) {
        List<Interval> list = in.stream().filter(Objects::nonNull).sorted((a,b) -> {
            int c = Double.compare(a.lo, b.lo);
            if (c != 0) return c;
            // inclusive before exclusive
            if (a.loInclusive == b.loInclusive) return 0;
            return a.loInclusive ? -1 : 1;
        }).collect(Collectors.toList());

        List<Interval> out = new ArrayList<>();
        for (Interval cur : list) {
            if (out.isEmpty()) { out.add(cur.copy()); continue; }
            Interval last = out.get(out.size()-1);

            // check overlap or adjacency
            Interval inter = last.intersect(cur);
            boolean overlap = inter != null;
            boolean touch = (last.hi == cur.lo) && (last.hiInclusive || cur.loInclusive);

            if (overlap || touch) {
                // merge by taking min lo of last, max hi of both
                double nlo = last.lo;
                boolean nloInc = last.loInclusive;

                double nhi = last.hi;
                boolean nhiInc = last.hiInclusive;
                if (cur.hi > nhi || (cur.hi == nhi && cur.hiInclusive && !nhiInc)) {
                    nhi = cur.hi;
                    nhiInc = cur.hiInclusive;
                } else if (cur.hi == nhi) {
                    nhiInc = last.hiInclusive || cur.hiInclusive;
                }

                out.set(out.size()-1, new Interval(nlo, nloInc, nhi, nhiInc));
            } else {
                out.add(cur.copy());
            }
        }
        return out;
    }

    static List<Interval> gapsFromUnion(List<Interval> union, Interval universe) {
        List<Interval> gaps = new ArrayList<>();
        if (union.isEmpty()) {
            gaps.add(universe.copy());
            return gaps;
        }

        // assume union sorted
        Interval first = union.get(0);
        if (first.lo > universe.lo || (first.lo == universe.lo && universe.loInclusive && !first.loInclusive)) {
            gaps.add(new Interval(universe.lo, universe.loInclusive, first.lo, !first.loInclusive));
        }

        for (int i=0; i<union.size()-1; i++) {
            Interval a = union.get(i);
            Interval b = union.get(i+1);
            // gap between a.hi and b.lo
            if (a.hi < b.lo) {
                gaps.add(new Interval(a.hi, !a.hiInclusive, b.lo, !b.loInclusive));
            } else if (a.hi == b.lo && !(a.hiInclusive && b.loInclusive)) {
                // single-point gap if both exclusive at same point
                gaps.add(new Interval(a.hi, true, b.lo, true));
            }
        }

        Interval last = union.get(union.size()-1);
        if (last.hi < universe.hi || (last.hi == universe.hi && universe.hiInclusive && !last.hiInclusive)) {
            gaps.add(new Interval(last.hi, !last.hiInclusive, universe.hi, universe.hiInclusive));
        }
        return gaps;
    }

    /* ============================== SHADOW ANALYSIS ============================== */

    static class ShadowEdge {
        int a; // shadows
        int b; // shadowed
        ShadowEdge(int a, int b) { this.a=a; this.b=b; }
    }

    static void writeShadowAnalysis(Path txtOut, Path htmlTreeOut) throws IOException {
        // Shadow definition used here:
        // Rule A shadows Rule B if:
        //  1) A appears before B (lower id)
        //  2) For every variable, A covers B (superset)
        //  3) (optional) while comparing, if IGNORE_NA_FOR_SHADOW and B has eq NA/NAN, ignore that atom for B only.
        //
        // This prevents the bug where we accidentally widen A or drop bounds like "< 3".
        List<ShadowEdge> edges = new ArrayList<>();

        for (int i=0; i<PATHS.size(); i++) {
            RulePath A = PATHS.get(i);
            for (int j=i+1; j<PATHS.size(); j++) {
                RulePath B = PATHS.get(j);
                if (covers(A, B)) {
                    edges.add(new ShadowEdge(A.id, B.id));
                }
            }
        }

        // Write txt with full conditions/actions
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(txtOut, StandardCharsets.UTF_8))) {
            pw.println("SHADOWED PATHS");
            pw.println("Definition: A shadows B if A appears before B and A fully covers B in condition space.");
            pw.println("Ignore NA/NAN for shadow comparison: " + IGNORE_NA_FOR_SHADOW);
            pw.println("Total rules: " + PATHS.size());
            pw.println("Total shadow edges: " + edges.size());
            pw.println();

            // group by shadower
            Map<Integer, List<Integer>> byA = new LinkedHashMap<>();
            for (ShadowEdge e : edges) byA.computeIfAbsent(e.a, k -> new ArrayList<>()).add(e.b);

            for (Map.Entry<Integer, List<Integer>> en : byA.entrySet()) {
                int aId = en.getKey();
                RulePath A = PATHS.get(aId-1);

                pw.println("Rule " + aId + " shadows " + en.getValue().size() + " rule(s)");
                pw.println("  ACTION: " + A.action);
                pw.println("  CONDITIONS:");
                for (String line : prettyConditions(A)) pw.println("    - " + line);
                pw.println("  Shadowed:");
                for (int bId : en.getValue()) {
                    RulePath B = PATHS.get(bId-1);
                    pw.println("    * Rule " + bId + " | ACTION: " + B.action);
                    for (String line : prettyConditions(B)) pw.println("        - " + line);
                }
                pw.println();
            }
        }

        // Tree HTML
        writeShadowTreeHtml(htmlTreeOut, edges);
    }

    static List<String> prettyConditions(RulePath p) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Constraint> e : p.byVar.entrySet()) {
            String k = e.getKey();
            Constraint c = e.getValue();
            VarDef v = VARS.get(k);
            List<String> parts = new ArrayList<>();
            if (c.interval != null && v != null && v.numeric) parts.add(c.interval.pretty(v));
            if (!c.cat.isEmpty()) parts.add(c.cat.stream().map(CatAtom::toString).collect(Collectors.joining(" AND ")));
            if (!parts.isEmpty()) out.add(k + ": " + String.join(" AND ", parts));
        }
        return out;
    }

    static boolean covers(RulePath A, RulePath B) {
        // compare per variable present in either
        Set<String> vars = new LinkedHashSet<>();
        vars.addAll(A.byVar.keySet());
        vars.addAll(B.byVar.keySet());

        for (String vname : vars) {
            VarDef vdef = VARS.get(vname);
            Constraint ca = A.byVar.get(vname);
            Constraint cb = B.byVar.get(vname);

            // numeric
            if (vdef != null && vdef.numeric) {
                Interval ia = (ca != null && ca.interval != null) ? ca.interval : Interval.universe(vdef);
                Interval ib = (cb != null && cb.interval != null) ? cb.interval : Interval.universe(vdef);
                if (!ia.covers(ib)) return false;
            }

            // categorical atoms: we only support a conservative cover check:
            // A covers B if either:
            //  - B has no categorical constraints, OR
            //  - A has the same categorical constraints (or weaker), OR
            //  - When IGNORE_NA_FOR_SHADOW: if B has eq NA/NAN atoms, ignore those atoms in comparison.
            List<CatAtom> aCats = (ca == null) ? List.of() : ca.cat;
            List<CatAtom> bCats = (cb == null) ? List.of() : cb.cat;

            List<CatAtom> bFiltered = new ArrayList<>();
            for (CatAtom atom : bCats) {
                if (IGNORE_NA_FOR_SHADOW && "eq".equalsIgnoreCase(atom.op) && isNaLike(atom.value)) {
                    // ignore this NA restriction for shadow comparison only
                    continue;
                }
                bFiltered.add(atom);
            }

            // If B requires some categorical atoms, ensure A also has them (same op/value)
            for (CatAtom req : bFiltered) {
                boolean found = false;
                for (CatAtom got : aCats) {
                    if (got.op.equalsIgnoreCase(req.op) && Objects.equals(got.value, req.value)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    // A does not enforce B's categorical restriction, so A may NOT cover B safely.
                    return false;
                }
            }
        }
        return true;
    }

    static void writeShadowTreeHtml(Path out, List<ShadowEdge> edges) throws IOException {
        Map<Integer, List<Integer>> byA = new LinkedHashMap<>();
        Set<Integer> shadowed = new HashSet<>();
        for (ShadowEdge e : edges) {
            byA.computeIfAbsent(e.a, k -> new ArrayList<>()).add(e.b);
            shadowed.add(e.b);
        }

        // roots = rules that shadow others but are not shadowed by anyone
        List<Integer> roots = byA.keySet().stream().filter(id -> !shadowed.contains(id)).toList();

        StringBuilder sb = new StringBuilder();
        sb.append("""
                <!doctype html>
                <html><head><meta charset="utf-8">
                <title>FSML Shadow Paths Tree</title>
                <style>
                  body{font-family:system-ui,Segoe UI,Arial;margin:24px;background:#0b1020;color:#e8ecff}
                  h1{margin:0 0 10px 0}
                  .sub{opacity:.8;margin-bottom:18px}
                  .card{background:#121a33;border:1px solid #23305a;border-radius:14px;padding:14px;margin:10px 0}
                  details{background:#0f1730;border:1px solid #23305a;border-radius:12px;padding:10px;margin:8px 0}
                  summary{cursor:pointer;font-weight:700}
                  .pill{display:inline-block;padding:2px 10px;border-radius:999px;background:#1b2a55;border:1px solid #2f4283;margin-left:8px;font-size:12px}
                  .cond{font-family:ui-monospace,Consolas,monospace;font-size:12px;opacity:.95;white-space:pre-wrap}
                  .action{display:inline-block;margin-top:6px;background:#2b1f14;border:1px solid #60452f;color:#ffd7a8;
                          padding:6px 10px;border-radius:10px;font-weight:700}
                </style>
                </head><body>
                """);

        sb.append("<h1>Shadow Paths Tree</h1>");
        sb.append("<div class='sub'>Ignore NA/NAN for shadow comparison: <b>")
          .append(IGNORE_NA_FOR_SHADOW)
          .append("</b> | Total rules: <b>")
          .append(PATHS.size())
          .append("</b></div>");

        if (roots.isEmpty()) {
            sb.append("<div class='card'>No shadow relationships detected.</div>");
        } else {
            for (int r : roots) {
                sb.append(renderShadowNodeHtml(r, byA, new HashSet<>()));
            }
        }

        sb.append("</body></html>");
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
    }

    static String renderShadowNodeHtml(int id, Map<Integer, List<Integer>> byA, Set<Integer> seen) {
        if (!seen.add(id)) return ""; // cycle guard
        RulePath p = PATHS.get(id - 1);
        List<Integer> kids = byA.getOrDefault(id, List.of());

        StringBuilder sb = new StringBuilder();
        sb.append("<details open><summary>Rule ").append(id)
                .append("<span class='pill'>shadows ").append(kids.size()).append("</span>")
                .append("</summary>");
        sb.append("<div class='action'>ACTION → ").append(escape(p.action)).append("</div>");
        sb.append("<div class='cond'>");
        for (String line : prettyConditions(p)) sb.append("• ").append(escape(line)).append("\n");
        sb.append("</div>");

        for (int k : kids) sb.append(renderShadowNodeHtml(k, byA, seen));
        sb.append("</details>");
        return sb.toString();
    }

    /* ============================== HTML OUTPUTS ============================== */

    static void writeRulesHtml(Path out) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                <!doctype html>
                <html><head><meta charset="utf-8">
                <title>FSML Rules Extract</title>
                <style>
                  body{font-family:system-ui,Segoe UI,Arial;margin:22px;background:#0b1020;color:#e8ecff}
                  h1{margin:0 0 10px 0}
                  .top{display:flex;gap:12px;flex-wrap:wrap;align-items:center;margin-bottom:16px}
                  .chip{background:#121a33;border:1px solid #23305a;border-radius:999px;padding:8px 12px}
                  input{padding:10px 12px;border-radius:10px;border:1px solid #23305a;background:#0f1730;color:#e8ecff;min-width:280px}
                  .grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(360px,1fr));gap:12px}
                  .card{background:#121a33;border:1px solid #23305a;border-radius:16px;padding:14px}
                  .rule{font-weight:800;font-size:18px}
                  .cond{margin-top:10px;font-family:ui-monospace,Consolas,monospace;font-size:12px;white-space:pre-wrap}
                  .action{margin-top:10px;background:#2b1f14;border:1px solid #60452f;color:#ffd7a8;
                          padding:8px 10px;border-radius:12px;font-weight:800;display:inline-block}
                  .muted{opacity:.8}
                </style>
                </head><body>
                """);

        sb.append("<h1>FSML Rules Extract</h1>");
        sb.append("<div class='top'>")
          .append("<div class='chip'>Total rules: <b>").append(PATHS.size()).append("</b></div>")
          .append("<div class='chip muted'>LOW/HIGH resolved using NumericKey ranges (for numeric vars)</div>")
          .append("<input id='q' placeholder='Search conditions or action…' oninput='filter()'>")
          .append("</div>");

        sb.append("<div class='grid' id='grid'>");
        for (RulePath p : PATHS) {
            sb.append("<div class='card' data-text='")
              .append(escapeAttr(("Rule " + p.id + " " + p.action + " " + String.join(" ", prettyConditions(p))).toLowerCase(Locale.ROOT)))
              .append("'>");

            sb.append("<div class='rule'>Rule ").append(p.id).append("</div>");
            sb.append("<div class='cond'>");
            for (String line : prettyConditions(p)) sb.append("→ ").append(escape(line)).append("\n");
            sb.append("</div>");
            sb.append("<div class='action'>ACTION → ").append(escape(p.action)).append("</div>");
            sb.append("</div>");
        }
        sb.append("</div>");

        sb.append("""
                <script>
                  function filter(){
                    const q=document.getElementById('q').value.toLowerCase().trim();
                    for(const el of document.querySelectorAll('#grid .card')){
                      const t=el.getAttribute('data-text');
                      el.style.display = (!q || t.includes(q)) ? '' : 'none';
                    }
                  }
                </script>
                </body></html>
                """);

        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
    }

    static void writeFsmlTreeHtml(Path out) throws IOException {
        // Simple tree based on extracted paths: group by action, then show conditions list
        Map<String, List<RulePath>> byAction = new LinkedHashMap<>();
        for (RulePath p : PATHS) byAction.computeIfAbsent(p.action, k -> new ArrayList<>()).add(p);

        StringBuilder sb = new StringBuilder();
        sb.append("""
                <!doctype html>
                <html><head><meta charset="utf-8">
                <title>FSML Tree View</title>
                <style>
                  body{font-family:system-ui,Segoe UI,Arial;margin:22px;background:#0b1020;color:#e8ecff}
                  h1{margin:0 0 12px 0}
                  details{background:#121a33;border:1px solid #23305a;border-radius:14px;padding:12px;margin:10px 0}
                  summary{cursor:pointer;font-weight:800}
                  .cond{font-family:ui-monospace,Consolas,monospace;font-size:12px;white-space:pre-wrap;margin-top:8px}
                  .pill{display:inline-block;padding:2px 10px;border-radius:999px;background:#1b2a55;border:1px solid #2f4283;margin-left:8px;font-size:12px}
                </style>
                </head><body>
                """);

        sb.append("<h1>FSML Tree View (grouped by Action)</h1>");
        sb.append("<div>Total rules: <b>").append(PATHS.size()).append("</b></div>");

        for (Map.Entry<String, List<RulePath>> e : byAction.entrySet()) {
            sb.append("<details><summary>")
              .append(escape(e.getKey()))
              .append("<span class='pill'>").append(e.getValue().size()).append("</span>")
              .append("</summary>");

            for (RulePath p : e.getValue()) {
                sb.append("<details style='margin-left:10px'><summary>Rule ")
                  .append(p.id).append("</summary><div class='cond'>");
                for (String line : prettyConditions(p)) sb.append("• ").append(escape(line)).append("\n");
                sb.append("</div></details>");
            }
            sb.append("</details>");
        }

        sb.append("</body></html>");
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
    }

    static String escape(String s) {
        if (s == null) return "";
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");
    }
    static String escapeAttr(String s) {
        return escape(s).replace("\"","&quot;");
    }

    /* ============================== UTILS ============================== */

    static String trimNum(double d) {
        if (Double.isNaN(d)) return "NaN";
        if (Double.isInfinite(d)) return d > 0 ? "+Inf" : "-Inf";
        long l = (long) d;
        if (Math.abs(d - l) < 1e-9) return String.valueOf(l);
        return String.valueOf(d);
    }

    /* ============================== MAIN ============================== */

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Usage: java FsmlAnalyzerMain <file.fsml> [--ignoreNAForShadow=true|false]");
            return;
        }
        String file = args[0];
        for (String a : args) {
            if (a.startsWith("--ignoreNAForShadow=")) {
                IGNORE_NA_FOR_SHADOW = Boolean.parseBoolean(a.substring("--ignoreNAForShadow=".length()));
            }
        }

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

        Document doc;
        try (InputStream in = Files.newInputStream(Paths.get(file))) {
            doc = dbf.newDocumentBuilder().parse(in);
        }

        parseVariables(doc);
        extractAllPaths(doc);

        System.out.println("FSML file: " + file);
        System.out.println("Total paths(rules): " + PATHS.size());
        System.out.println("Ignore NA/NAN for shadow comparison: " + IGNORE_NA_FOR_SHADOW);

        // outputs
        writeDecisionTableCsv(Paths.get("decision-table.csv"));
        writeRulesHtml(Paths.get("fsml-rules.html"));
        writeFsmlTreeHtml(Paths.get("fsml-tree.html"));
        writeGapAnalysis(Paths.get("gap-analysis.txt"));
        writeShadowAnalysis(Paths.get("shadowed-paths.txt"), Paths.get("shadow-tree.html"));

        System.out.println("Generated:");
        System.out.println("  decision-table.csv");
        System.out.println("  fsml-rules.html");
        System.out.println("  fsml-tree.html");
        System.out.println("  gap-analysis.txt");
        System.out.println("  shadowed-paths.txt");
        System.out.println("  shadow-tree.html");
    }
}
