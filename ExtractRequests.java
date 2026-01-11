import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * FSML Analyzer - single file - avoids pattern matching, text blocks, switch expressions
 *
 * Outputs:
 *  - decision-table.csv
 *  - fsml-rules.html
 *  - gap-analysis.txt
 *  - shadowed-paths.txt
 *
 * Run:
 *  java FsmlAnalyzerMain PenFed_AR_Expert_09042025.fsml --ignoreNAForShadow=true
 */
public class FsmlAnalyzerMain {

    /* ============================== CONFIG ============================== */

    static boolean IGNORE_NA_FOR_SHADOW = true;

    static final Set<String> NA_LIKE = new HashSet<String>(Arrays.asList(
            "NA","N/A","NAN","NULL","NONE",""
    ));

    /* ============================== MODEL ============================== */

    static class VarDef {
        String name;
        boolean numeric;
        double min = -9999999;
        double max =  9999999;
    }

    static class Interval {
        double lo;
        double hi;
        boolean loInc;
        boolean hiInc;

        Interval(double lo, boolean loInc, double hi, boolean hiInc) {
            this.lo = lo;
            this.hi = hi;
            this.loInc = loInc;
            this.hiInc = hiInc;
        }

        Interval copy() { return new Interval(lo, loInc, hi, hiInc); }

        static Interval universe(VarDef v) {
            return new Interval(v.min, true, v.max, true);
        }

        Interval intersect(Interval o) {
            double nlo = this.lo;
            boolean nloInc = this.loInc;

            if (o.lo > nlo) {
                nlo = o.lo; nloInc = o.loInc;
            } else if (o.lo == nlo) {
                nloInc = this.loInc && o.loInc;
            }

            double nhi = this.hi;
            boolean nhiInc = this.hiInc;

            if (o.hi < nhi) {
                nhi = o.hi; nhiInc = o.hiInc;
            } else if (o.hi == nhi) {
                nhiInc = this.hiInc && o.hiInc;
            }

            if (nlo < nhi) return new Interval(nlo, nloInc, nhi, nhiInc);

            if (nlo == nhi) {
                if (nloInc && nhiInc) return new Interval(nlo, true, nhi, true);
            }
            return null;
        }

        boolean covers(Interval other) {
            // lower bound
            if (this.lo > other.lo) return false;
            if (this.lo == other.lo && !this.loInc && other.loInc) return false;

            // upper bound
            if (this.hi < other.hi) return false;
            if (this.hi == other.hi && !this.hiInc && other.hiInc) return false;

            return true;
        }

        String toLogic(String varName) {
            // "lo <= var < hi" style
            String left = trimNum(lo) + (loInc ? " <= " : " < ") + varName;
            String right = (hiInc ? " <= " : " < ") + trimNum(hi);
            return left + right;
        }
    }

    static class CatAtom {
        String op;
        String val;
        CatAtom(String op, String val) { this.op = op; this.val = val; }
        public String toString() { return op + " " + val; }
    }

    static class Constraint {
        Interval interval;            // for numeric restrictions (ge/lt/etc)
        List<CatAtom> cats = new ArrayList<CatAtom>(); // categorical + NA constraints

        Constraint copy() {
            Constraint c = new Constraint();
            c.interval = (this.interval == null ? null : this.interval.copy());
            c.cats = new ArrayList<CatAtom>(this.cats);
            return c;
        }
    }

    static class RulePath {
        int id;
        String action;
        LinkedHashMap<String, Constraint> byVar = new LinkedHashMap<String, Constraint>();
    }

    /* ============================== STATE ============================== */

    static final LinkedHashMap<String, VarDef> VARS = new LinkedHashMap<String, VarDef>();
    static final ArrayList<RulePath> PATHS = new ArrayList<RulePath>();

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
        ArrayList<Element> out = new ArrayList<Element>();
        if (parent == null) return out;
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                out.add((Element) n);
            }
        }
        return out;
    }

    static List<Element> children(Element parent, String name) {
        ArrayList<Element> out = new ArrayList<Element>();
        List<Element> els = childElements(parent);
        for (int i = 0; i < els.size(); i++) {
            Element e = els.get(i);
            String ln = localName(e);
            if (ln != null && name.equalsIgnoreCase(ln)) out.add(e);
        }
        return out;
    }

    static Element firstChild(Element parent, String name) {
        List<Element> list = children(parent, name);
        return list.isEmpty() ? null : list.get(0);
    }

    static List<Element> allElements(Document doc, String tagLocalName) {
        ArrayList<Element> res = new ArrayList<Element>();
        NodeList nl = doc.getElementsByTagName("*");
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                Element e = (Element) n;
                String ln = localName(e);
                if (ln != null && tagLocalName.equalsIgnoreCase(ln)) res.add(e);
            }
        }
        return res;
    }

    static String attr(Element e, String name) {
        if (e == null) return null;
        if (!e.hasAttribute(name)) return null;
        String v = e.getAttribute(name);
        return v == null ? null : v;
    }

    /* ============================== PARSE VARIABLES ============================== */

    static void parseVariables(Document doc) {
        // NumericKey
        List<Element> numericKeys = allElements(doc, "NumericKey");
        for (int i = 0; i < numericKeys.size(); i++) {
            Element nk = numericKeys.get(i);
            String shortName = attr(nk, "ShortName");
            if (shortName == null || shortName.trim().isEmpty()) continue;

            VarDef v = VARS.get(shortName);
            if (v == null) {
                v = new VarDef();
                v.name = shortName;
                VARS.put(shortName, v);
            }
            v.numeric = true;

            Element range = firstChild(nk, "NumericRange");
            if (range != null) {
                Double mn = safeDouble(attr(range, "minValue"));
                Double mx = safeDouble(attr(range, "maxValue"));
                if (mn != null) v.min = mn.doubleValue();
                if (mx != null) v.max = mx.doubleValue();
            }
        }

        // CategoricalKey (we just register it as categorical)
        List<Element> catKeys = allElements(doc, "CategoricalKey");
        for (int i = 0; i < catKeys.size(); i++) {
            Element ck = catKeys.get(i);
            String shortName = attr(ck, "ShortName");
            if (shortName == null || shortName.trim().isEmpty()) continue;

            VarDef v = VARS.get(shortName);
            if (v == null) {
                v = new VarDef();
                v.name = shortName;
                VARS.put(shortName, v);
            }
            v.numeric = false;
        }
    }

    /* ============================== PATH EXTRACTION ============================== */

    static void extractAllPaths(Document doc) {
        Element strategy = null;
        List<Element> strategies = allElements(doc, "STRATEGY");
        if (!strategies.isEmpty()) strategy = strategies.get(0);
        if (strategy == null) throw new IllegalStateException("No <STRATEGY> found.");

        Element root = firstChild(strategy, "NODE");
        if (root == null) throw new IllegalStateException("No <NODE> under <STRATEGY>.");

        RulePath start = new RulePath();
        walkNode(root, start);
    }

    static void walkNode(Element node, RulePath incoming) {
        if (node == null) return;

        RulePath cur = clonePath(incoming);

        // Apply ALL immediate CONDITION elements on this node
        List<Element> conds = children(node, "CONDITION");
        for (int i = 0; i < conds.size(); i++) {
            applyConditionElement(conds.get(i), cur);
        }

        // If node contains ACTIONS, emit a rule
        Element actions = firstChild(node, "ACTIONS");
        if (actions != null) {
            RulePath leaf = clonePath(cur);
            leaf.id = PATHS.size() + 1;
            leaf.action = safeStr(attr(actions, "Label")).trim();
            PATHS.add(leaf);
        }

        // Traverse child NODEs
        List<Element> kids = children(node, "NODE");
        for (int i = 0; i < kids.size(); i++) {
            walkNode(kids.get(i), cur);
        }
    }

    static RulePath clonePath(RulePath p) {
        RulePath np = new RulePath();
        np.id = p.id;
        np.action = p.action;
        np.byVar = new LinkedHashMap<String, Constraint>();
        for (Map.Entry<String, Constraint> e : p.byVar.entrySet()) {
            np.byVar.put(e.getKey(), e.getValue().copy());
        }
        return np;
    }

    /**
     * Handles:
     *  - <CONDITION Type="true"/>
     *  - <CONDITION DecisionKey="X" Type="and"> <CONDITION .../> <CONDITION .../> </CONDITION>
     *  - <CONDITION DecisionKey="X" Value="3" Type="lt"/>
     */
    static void applyConditionElement(Element cond, RulePath path) {
        String key = safeStr(attr(cond, "DecisionKey")).trim();
        String type = safeStr(attr(cond, "Type")).trim();
        String value = safeStr(attr(cond, "Value")).trim();

        // True condition -> ignore
        if (key.isEmpty() && "true".equalsIgnoreCase(type)) return;

        // If has nested CONDITION children, treat as conjunction group; apply each leaf
        List<Element> nested = children(cond, "CONDITION");
        if (!nested.isEmpty()) {
            for (int i = 0; i < nested.size(); i++) {
                applyConditionElement(nested.get(i), path);
            }
            return;
        }

        if (key.isEmpty() || type.isEmpty()) return;

        addAtom(path, key, type, value);
    }

    static void addAtom(RulePath path, String key, String op, String rawValue) {
        Constraint c = path.byVar.get(key);
        if (c == null) {
            c = new Constraint();
            path.byVar.put(key, c);
        }

        VarDef v = VARS.get(key);
        String val = rawValue == null ? "" : rawValue.trim();

        boolean isNA = isNaLike(val);
        boolean isRel = isRelational(op);

        // numeric interval if possible
        if (v != null && v.numeric && isRel && !isNA) {
            Double num = numericValueFor(v, val);
            if (num == null) {
                // store as categorical if not parseable
                c.cats.add(new CatAtom(op, val));
                return;
            }

            Interval local = intervalFromOp(v, op, num.doubleValue());
            if (local == null) {
                // eq/ne might fall here; keep categorical for safety
                c.cats.add(new CatAtom(op, val));
                return;
            }

            if (c.interval == null) {
                c.interval = local;
            } else {
                Interval merged = c.interval.intersect(local);
                if (merged == null) {
                    // contradiction: mark with IMPOSSIBLE atom and keep going
                    c.cats.add(new CatAtom("IMPOSSIBLE", key));
                } else {
                    c.interval = merged;
                }
            }
            return;
        }

        // categorical (including NA/NAN eq) or unknown var
        if (val.isEmpty()) val = "NA";
        c.cats.add(new CatAtom(op, val));
    }

    static boolean isRelational(String op) {
        String o = op.toLowerCase(Locale.ROOT);
        return o.equals("lt") || o.equals("le") || o.equals("gt") || o.equals("ge") || o.equals("eq") || o.equals("ne");
    }

    static boolean isNaLike(String s) {
        if (s == null) return true;
        String t = s.trim().toUpperCase(Locale.ROOT);
        return NA_LIKE.contains(t);
    }

    static Double numericValueFor(VarDef v, String val) {
        if (val == null) return null;
        String t = val.trim();
        if (t.isEmpty()) return null;
        if (isNaLike(t)) return null;

        if ("LOW".equalsIgnoreCase(t)) return v.min;
        if ("HIGH".equalsIgnoreCase(t)) return v.max;

        return safeDouble(t);
    }

    static Interval intervalFromOp(VarDef v, String op, double num) {
        String o = op.toLowerCase(Locale.ROOT);
        Interval u = Interval.universe(v);

        if (o.equals("ge")) return u.intersect(new Interval(num, true, v.max, true));
        if (o.equals("gt")) return u.intersect(new Interval(num, false, v.max, true));
        if (o.equals("lt")) return u.intersect(new Interval(v.min, true, num, false));
        if (o.equals("le")) return u.intersect(new Interval(v.min, true, num, true));
        if (o.equals("eq")) return u.intersect(new Interval(num, true, num, true));

        // ne not represented as interval safely
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

    static String safeStr(String s) { return s == null ? "" : s; }

    /* ============================== DECISION TABLE CSV ============================== */

    static void writeDecisionTableCsv(Path out) throws IOException {
        // Collect columns in stable order from VARS first, then any extras seen in paths
        LinkedHashSet<String> cols = new LinkedHashSet<String>();
        cols.addAll(VARS.keySet());
        for (int i = 0; i < PATHS.size(); i++) cols.addAll(PATHS.get(i).byVar.keySet());
        ArrayList<String> colList = new ArrayList<String>(cols);

        BufferedWriter bw = Files.newBufferedWriter(out, StandardCharsets.UTF_8);
        try {
            bw.write("RuleId");
            for (int i = 0; i < colList.size(); i++) bw.write("," + csv(colList.get(i)));
            bw.write(",Action\n");

            for (int r = 0; r < PATHS.size(); r++) {
                RulePath p = PATHS.get(r);
                bw.write(String.valueOf(p.id));
                for (int i = 0; i < colList.size(); i++) {
                    bw.write("," + csv(renderCell(p, colList.get(i))));
                }
                bw.write("," + csv(p.action));
                bw.write("\n");
            }
        } finally {
            bw.close();
        }
    }

    static String renderCell(RulePath p, String var) {
        Constraint c = p.byVar.get(var);
        if (c == null) return ""; // blank instead of ANY

        VarDef v = VARS.get(var);
        ArrayList<String> parts = new ArrayList<String>();

        if (c.interval != null && v != null && v.numeric) {
            parts.add(c.interval.toLogic(var));
        }

        if (!c.cats.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < c.cats.size(); i++) {
                if (i > 0) sb.append(" AND ");
                sb.append(c.cats.get(i).toString());
            }
            parts.add(sb.toString());
        }

        return join(parts, " AND ");
    }

    static String csv(String s) {
        if (s == null) return "";
        boolean need = s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
        if (!need) return s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    static String join(List<String> parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    /* ============================== GAP ANALYSIS (NUMERIC) ============================== */

    static void writeGapAnalysis(Path out) throws IOException {
        PrintWriter pw = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8));
        try {
            pw.println("FSML GAP ANALYSIS (numeric coverage vs universe)");
            pw.println("Total rules: " + PATHS.size());
            pw.println();

            for (VarDef v : VARS.values()) {
                if (!v.numeric) continue;

                Interval universe = Interval.universe(v);
                ArrayList<Interval> covered = new ArrayList<Interval>();

                for (int i = 0; i < PATHS.size(); i++) {
                    RulePath p = PATHS.get(i);
                    Constraint c = p.byVar.get(v.name);
                    if (c != null && c.interval != null) covered.add(c.interval);
                    else covered.add(universe.copy()); // unconstrained => covers universe
                }

                ArrayList<Interval> union = unionIntervals(covered);
                ArrayList<Interval> gaps = gapsFromUnion(union, universe);

                pw.println("Variable: " + v.name);
                pw.println("  Universe: " + universe.toLogic(v.name));
                pw.println("  Covered segments: " + union.size());
                for (int i = 0; i < union.size(); i++) pw.println("    - " + union.get(i).toLogic(v.name));
                pw.println("  Gaps: " + gaps.size());
                for (int i = 0; i < gaps.size(); i++) pw.println("    * " + gaps.get(i).toLogic(v.name));
                pw.println();
            }
        } finally {
            pw.close();
        }
    }

    static ArrayList<Interval> unionIntervals(List<Interval> in) {
        ArrayList<Interval> list = new ArrayList<Interval>();
        for (int i = 0; i < in.size(); i++) if (in.get(i) != null) list.add(in.get(i).copy());

        Collections.sort(list, new Comparator<Interval>() {
            public int compare(Interval a, Interval b) {
                int c = Double.compare(a.lo, b.lo);
                if (c != 0) return c;
                if (a.loInc == b.loInc) return 0;
                return a.loInc ? -1 : 1;
            }
        });

        ArrayList<Interval> out = new ArrayList<Interval>();
        for (int i = 0; i < list.size(); i++) {
            Interval cur = list.get(i);
            if (out.isEmpty()) { out.add(cur); continue; }

            Interval last = out.get(out.size() - 1);
            boolean overlap = last.intersect(cur) != null;
            boolean touch = (last.hi == cur.lo) && (last.hiInc || cur.loInc);

            if (overlap || touch) {
                // merge
                double nlo = last.lo;
                boolean nloInc = last.loInc;

                double nhi = last.hi;
                boolean nhiInc = last.hiInc;

                if (cur.hi > nhi) { nhi = cur.hi; nhiInc = cur.hiInc; }
                else if (cur.hi == nhi) nhiInc = last.hiInc || cur.hiInc;

                out.set(out.size() - 1, new Interval(nlo, nloInc, nhi, nhiInc));
            } else {
                out.add(cur);
            }
        }
        return out;
    }

    static ArrayList<Interval> gapsFromUnion(List<Interval> union, Interval universe) {
        ArrayList<Interval> gaps = new ArrayList<Interval>();
        if (union.isEmpty()) {
            gaps.add(universe.copy());
            return gaps;
        }

        Interval first = union.get(0);
        if (first.lo > universe.lo || (first.lo == universe.lo && universe.loInc && !first.loInc)) {
            gaps.add(new Interval(universe.lo, universe.loInc, first.lo, !first.loInc));
        }

        for (int i = 0; i < union.size() - 1; i++) {
            Interval a = union.get(i);
            Interval b = union.get(i + 1);

            if (a.hi < b.lo) {
                gaps.add(new Interval(a.hi, !a.hiInc, b.lo, !b.loInc));
            } else if (a.hi == b.lo && !(a.hiInc && b.loInc)) {
                gaps.add(new Interval(a.hi, true, b.lo, true));
            }
        }

        Interval last = union.get(union.size() - 1);
        if (last.hi < universe.hi || (last.hi == universe.hi && universe.hiInc && !last.hiInc)) {
            gaps.add(new Interval(last.hi, !last.hiInc, universe.hi, universe.hiInc));
        }

        return gaps;
    }

    /* ============================== SHADOW ANALYSIS ============================== */

    static void writeShadowAnalysis(Path out) throws IOException {
        PrintWriter pw = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8));
        try {
            pw.println("SHADOWED PATHS");
            pw.println("Ignore NA/NAN in shadow comparison: " + IGNORE_NA_FOR_SHADOW);
            pw.println("Total rules: " + PATHS.size());
            pw.println();

            int edges = 0;
            for (int i = 0; i < PATHS.size(); i++) {
                RulePath A = PATHS.get(i);
                int count = 0;
                for (int j = i + 1; j < PATHS.size(); j++) {
                    RulePath B = PATHS.get(j);
                    if (covers(A, B)) {
                        edges++;
                        count++;
                        pw.println("Rule " + A.id + " shadows Rule " + B.id);
                        pw.println("  A action: " + A.action);
                        pw.println("  B action: " + B.action);
                        pw.println("  B conditions:");
                        List<String> bc = prettyConditions(B);
                        for (int k = 0; k < bc.size(); k++) pw.println("    - " + bc.get(k));
                        pw.println();
                    }
                }
                // optional summary per rule
                if (count > 0) {
                    pw.println("SUMMARY: Rule " + A.id + " shadows " + count + " rule(s)");
                    pw.println();
                }
            }

            pw.println("Total shadow edges: " + edges);
        } finally {
            pw.close();
        }
    }

    static boolean covers(RulePath A, RulePath B) {
        // A covers B if for every variable, A's numeric interval covers B's interval,
        // and for categorical atoms: A must contain each non-NA atom required by B.
        LinkedHashSet<String> vars = new LinkedHashSet<String>();
        vars.addAll(A.byVar.keySet());
        vars.addAll(B.byVar.keySet());

        for (String vname : vars) {
            VarDef vdef = VARS.get(vname);
            Constraint ca = A.byVar.get(vname);
            Constraint cb = B.byVar.get(vname);

            if (vdef != null && vdef.numeric) {
                Interval ia = (ca != null && ca.interval != null) ? ca.interval : Interval.universe(vdef);
                Interval ib = (cb != null && cb.interval != null) ? cb.interval : Interval.universe(vdef);
                if (!ia.covers(ib)) return false;
            }

            List<CatAtom> aCats = (ca == null) ? Collections.<CatAtom>emptyList() : ca.cats;
            List<CatAtom> bCats = (cb == null) ? Collections.<CatAtom>emptyList() : cb.cats;

            for (int i = 0; i < bCats.size(); i++) {
                CatAtom req = bCats.get(i);

                // Optionally ignore eq NA/NAN for shadow comparison
                if (IGNORE_NA_FOR_SHADOW && "eq".equalsIgnoreCase(req.op) && isNaLike(req.val)) {
                    continue;
                }

                boolean found = false;
                for (int j = 0; j < aCats.size(); j++) {
                    CatAtom got = aCats.get(j);
                    if (got.op.equalsIgnoreCase(req.op) && safeStr(got.val).equals(safeStr(req.val))) {
                        found = true;
                        break;
                    }
                }
                if (!found) return false;
            }
        }
        return true;
    }

    static List<String> prettyConditions(RulePath p) {
        ArrayList<String> out = new ArrayList<String>();
        for (Map.Entry<String, Constraint> e : p.byVar.entrySet()) {
            String k = e.getKey();
            Constraint c = e.getValue();
            VarDef v = VARS.get(k);

            ArrayList<String> parts = new ArrayList<String>();
            if (c.interval != null && v != null && v.numeric) parts.add(c.interval.toLogic(k));

            if (!c.cats.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < c.cats.size(); i++) {
                    if (i > 0) sb.append(" AND ");
                    sb.append(c.cats.get(i).toString());
                }
                parts.add(sb.toString());
            }
            if (!parts.isEmpty()) out.add(k + ": " + join(parts, " AND "));
        }
        return out;
    }

    /* ============================== SIMPLE RULE HTML ============================== */

    static void writeRulesHtml(Path out) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html><head><meta charset='utf-8'>");
        sb.append("<title>FSML Rules Extract</title>");
        sb.append("<style>");
        sb.append("body{font-family:system-ui,Segoe UI,Arial;margin:22px;background:#0b1020;color:#e8ecff}");
        sb.append("h1{margin:0 0 10px 0}");
        sb.append(".top{display:flex;gap:12px;flex-wrap:wrap;align-items:center;margin-bottom:16px}");
        sb.append(".chip{background:#121a33;border:1px solid #23305a;border-radius:999px;padding:8px 12px}");
        sb.append("input{padding:10px 12px;border-radius:10px;border:1px solid #23305a;background:#0f1730;color:#e8ecff;min-width:280px}");
        sb.append(".grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(360px,1fr));gap:12px}");
        sb.append(".card{background:#121a33;border:1px solid #23305a;border-radius:16px;padding:14px}");
        sb.append(".rule{font-weight:800;font-size:18px}");
        sb.append(".cond{margin-top:10px;font-family:ui-monospace,Consolas,monospace;font-size:12px;white-space:pre-wrap}");
        sb.append(".action{margin-top:10px;background:#2b1f14;border:1px solid #60452f;color:#ffd7a8;padding:8px 10px;border-radius:12px;font-weight:800;display:inline-block}");
        sb.append("</style></head><body>");

        sb.append("<h1>FSML Rules Extract</h1>");
        sb.append("<div class='top'>");
        sb.append("<div class='chip'>Total rules: <b>").append(PATHS.size()).append("</b></div>");
        sb.append("<div class='chip'>LOW/HIGH resolved using NumericKey ranges (numeric vars)</div>");
        sb.append("<input id='q' placeholder='Search conditions or action…' oninput='filter()'>");
        sb.append("</div>");

        sb.append("<div class='grid' id='grid'>");
        for (int i = 0; i < PATHS.size(); i++) {
            RulePath p = PATHS.get(i);
            String text = ("rule " + p.id + " " + p.action + " " + join(prettyConditions(p), " ")).toLowerCase(Locale.ROOT);

            sb.append("<div class='card' data-text='").append(escapeAttr(text)).append("'>");
            sb.append("<div class='rule'>Rule ").append(p.id).append("</div>");
            sb.append("<div class='cond'>");
            List<String> conds = prettyConditions(p);
            for (int k = 0; k < conds.size(); k++) {
                sb.append("&#8594; ").append(escape(conds.get(k))).append("\n");
            }
            sb.append("</div>");
            sb.append("<div class='action'>ACTION &#8594; ").append(escape(p.action)).append("</div>");
            sb.append("</div>");
        }
        sb.append("</div>");

        sb.append("<script>");
        sb.append("function filter(){");
        sb.append("var q=document.getElementById('q').value.toLowerCase().trim();");
        sb.append("var cards=document.querySelectorAll('#grid .card');");
        sb.append("for(var i=0;i<cards.length;i++){");
        sb.append("var t=cards[i].getAttribute('data-text');");
        sb.append("cards[i].style.display=(!q||t.indexOf(q)>=0)?'':'none';");
        sb.append("}}");
        sb.append("</script>");

        sb.append("</body></html>");

        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
    }

    static String escape(String s) {
        if (s == null) return "";
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");
    }
    static String escapeAttr(String s) {
        return escape(s).replace("'","&#39;").replace("\"","&quot;");
    }

    static String trimNum(double d) {
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
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a != null && a.startsWith("--ignoreNAForShadow=")) {
                IGNORE_NA_FOR_SHADOW = Boolean.parseBoolean(a.substring("--ignoreNAForShadow=".length()));
            }
        }

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        try {
            dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        } catch (Exception ignore) {}

        Document doc;
        InputStream in = Files.newInputStream(Paths.get(file));
        try {
            doc = dbf.newDocumentBuilder().parse(in);
        } finally {
            in.close();
        }

        parseVariables(doc);
        extractAllPaths(doc);

        System.out.println("FSML file: " + file);
        System.out.println("Total paths(rules): " + PATHS.size());
        System.out.println("Ignore NA/NAN for shadow comparison: " + IGNORE_NA_FOR_SHADOW);

        writeDecisionTableCsv(Paths.get("decision-table.csv"));
        writeRulesHtml(Paths.get("fsml-rules.html"));
        writeGapAnalysis(Paths.get("gap-analysis.txt"));
        writeShadowAnalysis(Paths.get("shadowed-paths.txt"));

        System.out.println("Generated:");
        System.out.println("  decision-table.csv");
        System.out.println("  fsml-rules.html");
        System.out.println("  gap-analysis.txt");
        System.out.println("  shadowed-paths.txt");
    }
}
