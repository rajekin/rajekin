import java.util.*;

public class FsmlModel {

    public static class Variable {
        public String name;
        public String type; // NUMERIC / CATEGORICAL
        public double min;
        public double max;
        public Set<String> categories = new HashSet<>();
    }

    public static class Condition {
        public String variable;
        public String operator; // ge, gt, lt, le, eq
        public String value;
    }

    public static class Node {
        public String label;
        public List<Condition> conditions = new ArrayList<>();
        public List<Node> children = new ArrayList<>();
        public String action;
    }

    public Map<String, Variable> variables = new HashMap<>();
    public Node root;
}


public class Interval {
    public double start;
    public double end;

    public Interval(double s, double e) {
        this.start = s;
        this.end = e;
    }

    public Interval intersect(Interval other) {
        double s = Math.max(start, other.start);
        double e = Math.min(end, other.end);
        return (s < e) ? new Interval(s, e) : null;
    }

    public boolean contains(double v) {
        return v >= start && v < end;
    }
}




import java.util.*;

public class DecisionPath {
    public Map<String, Interval> numeric = new HashMap<>();
    public Map<String, String> categorical = new HashMap<>();
    public String action;
    public String label;

    public DecisionPath copy() {
        DecisionPath p = new DecisionPath();
        p.numeric.putAll(this.numeric);
        p.categorical.putAll(this.categorical);
        return p;
    }
}




import java.util.*;

public class PathAnalyzer {

    static double resolve(FsmlModel.Variable v, String val) {
        if (val == null || val.isBlank()) return v.min;
        if ("LOW".equals(val)) return v.min;
        if ("HIGH".equals(val)) return v.max;
        return Double.parseDouble(val);
    }

    public static List<DecisionPath> extractPaths(
            FsmlModel model) {

        List<DecisionPath> paths = new ArrayList<>();
        walk(model.root, new DecisionPath(), model, paths);
        return paths;
    }

    private static void walk(
            FsmlModel.Node node,
            DecisionPath current,
            FsmlModel model,
            List<DecisionPath> out) {

        DecisionPath next = current.copy();

        for (FsmlModel.Condition c : node.conditions) {
            FsmlModel.Variable v = model.variables.get(c.variable);

            if ("NUMERIC".equals(v.type)) {
                Interval i = next.numeric.getOrDefault(
                        v.name,
                        new Interval(v.min, v.max)
                );

                double val = resolve(v, c.value);
                Interval local =
                        ("ge".equals(c.operator) || "gt".equals(c.operator))
                                ? new Interval(val, v.max)
                                : new Interval(v.min, val);

                Interval merged = i.intersect(local);
                if (merged == null) return; // DEAD PATH
                next.numeric.put(v.name, merged);

            } else {
                next.categorical.put(v.name, c.value);
            }
        }

        if (node.action != null) {
            next.action = node.action;
            next.label = node.label;
            out.add(next);
            return;
        }

        for (FsmlModel.Node child : node.children) {
            walk(child, next, model, out);
        }
    }
}



static List<Interval> gaps(
        Interval covered,
        Interval universe) {

    List<Interval> g = new ArrayList<>();
    if (covered.start > universe.start)
        g.add(new Interval(universe.start, covered.start));
    if (covered.end < universe.end)
        g.add(new Interval(covered.end, universe.end));
    return g;
}




import java.io.*;
import java.util.*;

public class JUnitTestGenerator {

    public static void generate(
            List<DecisionPath> paths,
            File outFile) throws Exception {

        try (PrintWriter out = new PrintWriter(outFile)) {

            out.println("import org.junit.jupiter.api.*;");
            out.println("import static org.junit.jupiter.api.Assertions.*;");
            out.println("import java.util.*;");
            out.println("class FsmlGeneratedTests {");

            int i = 1;

            // Positive tests
            for (DecisionPath p : paths) {
                out.println("@Test void positive_" + i++ + "() {");
                out.println(" Map<String,Object> in = new HashMap<>();");

                p.numeric.forEach((k,v) ->
                        out.println(" in.put(\""+k+"\", "+(v.start+1)+");"));
                p.categorical.forEach((k,v) ->
                        out.println(" in.put(\""+k+"\", \""+v+"\");"));

                out.println(" assertEquals(\""+p.action+"\", DecisionEngine.evaluate(in));");
                out.println("}");
            }

            // Negative GAP tests
            for (DecisionPath p : paths) {
                for (var e : p.numeric.entrySet()) {
                    Interval iv = e.getValue();
                    double probe = iv.start - 1;

                    out.println("@Test void negative_gap_" + i++ + "() {");
                    out.println(" Map<String,Object> in = new HashMap<>();");
                    out.println(" in.put(\""+e.getKey()+"\", "+probe+");");
                    out.println(" assertNull(DecisionEngine.evaluate(in));");
                    out.println("}");
                }
            }
            out.println("}");
        }
    }
}



import java.io.*;
import java.util.*;

public class HtmlReportGenerator {

    public static void generate(
            List<DecisionPath> paths,
            File file) throws Exception {

        try (PrintWriter out = new PrintWriter(file)) {

            out.println("<html><body>");
            out.println("<h2>FSML Decision Paths</h2>");
            out.println("<ul>");

            for (DecisionPath p : paths) {
                out.println("<li><b>" + p.label + "</b>");
                out.println("<ul>");

                p.numeric.forEach((k,v) ->
                        out.println("<li>"+k+": "+v.start+" → "+v.end+"</li>"));
                p.categorical.forEach((k,v) ->
                        out.println("<li>"+k+" = "+v+"</li>"));

                out.println("<li><b>ACTION:</b> "+p.action+"</li>");
                out.println("</ul></li>");
            }

            out.println("</ul>");
            out.println("</body></html>");
        }
    }
}



import java.io.File;
import java.util.*;

public class Main {

    public static void main(String[] args) throws Exception {

        FsmlModel model =
                FsmlParser.parse(new File("s.fsml"));

        List<DecisionPath> paths =
                PathAnalyzer.extractPaths(model);

        System.out.println("Total decision paths: " + paths.size());

        JUnitTestGenerator.generate(
                paths, new File("FsmlGeneratedTests.java"));

        HtmlReportGenerator.generate(
                paths, new File("fsml-view.html"));
    }
}

public class Interval {

    public final double start;   // inclusive
    public final double end;     // exclusive

    // ✅ REQUIRED constructor
    public Interval(double start, double end) {
        if (start >= end) {
            throw new IllegalArgumentException(
                "Invalid interval: [" + start + ", " + end + ")"
            );
        }
        this.start = start;
        this.end = end;
    }

    // ✅ Intersection (path-aware logic)
    public Interval intersect(Interval other) {
        double s = Math.max(this.start, other.start);
        double e = Math.min(this.end, other.end);
        return (s < e) ? new Interval(s, e) : null;
    }

    // ✅ Containment check (coverage, reachability)
    public boolean contains(double value) {
        return value >= start && value < end;
    }

    @Override
    public String toString() {
        return "[" + start + ", " + end + ")";
    }
}
