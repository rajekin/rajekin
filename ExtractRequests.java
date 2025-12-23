import java.util.*;

public class FsmlModel {

    public Map<String, Variable> variables = new HashMap<>();
    public List<RuleNode> nodes = new ArrayList<>();

    public static class Variable {
        public String name;
        public String type; // NUMERIC / CATEGORICAL
        public double min;
        public double max;
        public Set<String> categories = new HashSet<>();
    }

    public static class RuleNode {
        public String label;
        public Map<String, List<Condition>> conditions = new HashMap<>();
        public String action;
    }

    public static class Condition {
        public String variable;
        public String operator; // ge, gt, lt, le, eq
        public String value;
    }
}



public class NumericInterval {
    public double start;
    public double end;
    public String source;

    public NumericInterval(double start, double end, String source) {
        this.start = start;
        this.end = end;
        this.source = source;
    }

    public boolean contains(double v) {
        return v >= start && v < end;
    }
}





import java.util.*;

public class GapAnalysisResult {
    public Map<String, List<NumericInterval>> gaps = new HashMap<>();
    public Set<String> deadNodes = new HashSet<>();
    public boolean hasDefaultPath;
}




import java.util.*;

public class FsmlAnalyzer {

    /* ---------------- Numeric resolution ---------------- */

    static double resolveNumericValue(
            FsmlModel.Variable var,
            String value) {

        if (value == null || value.trim().isEmpty()) {
            return var.min;
        }
        if ("LOW".equals(value)) return var.min;
        if ("HIGH".equals(value)) return var.max;
        return Double.parseDouble(value);
    }

    /* ---------------- Interval construction ---------------- */

    static List<NumericInterval> buildIntervals(
            FsmlModel.Variable var,
            FsmlModel model) {

        List<NumericInterval> intervals = new ArrayList<>();

        for (FsmlModel.RuleNode node : model.nodes) {
            List<FsmlModel.Condition> conds = node.conditions.get(var.name);
            if (conds == null) continue;

            Double low = null;
            Double high = null;

            for (FsmlModel.Condition c : conds) {
                double v = resolveNumericValue(var, c.value);
                if ("ge".equals(c.operator) || "gt".equals(c.operator)) low = v;
                if ("lt".equals(c.operator) || "le".equals(c.operator)) high = v;
            }

            if (low != null || high != null) {
                intervals.add(new NumericInterval(
                        low != null ? low : var.min,
                        high != null ? high : var.max,
                        node.label
                ));
            }
        }
        return intervals;
    }

    /* ---------------- GAP + DEAD NODE ANALYSIS ---------------- */

    public static GapAnalysisResult analyze(FsmlModel model) {

        GapAnalysisResult result = new GapAnalysisResult();
        Set<String> reachableNodes = new HashSet<>();

        for (FsmlModel.Variable var : model.variables.values()) {
            if (!"NUMERIC".equals(var.type)) continue;

            List<NumericInterval> intervals = buildIntervals(var, model);
            intervals.sort(Comparator.comparingDouble(i -> i.start));

            double cursor = var.min;
            List<NumericInterval> gaps = new ArrayList<>();

            for (NumericInterval i : intervals) {
                reachableNodes.add(i.source);

                if (i.start > cursor) {
                    gaps.add(new NumericInterval(cursor, i.start, "GAP"));
                }
                cursor = Math.max(cursor, i.end);
            }

            if (cursor < var.max) {
                gaps.add(new NumericInterval(cursor, var.max, "GAP"));
            }

            if (!gaps.isEmpty()) {
                result.gaps.put(var.name, gaps);
            }
        }

        /* ---------------- Dead nodes ---------------- */

        for (FsmlModel.RuleNode node : model.nodes) {
            if (!reachableNodes.contains(node.label)) {
                result.deadNodes.add(node.label);
            }
        }

        /* ---------------- Default / no-hit path ---------------- */

        result.hasDefaultPath = result.gaps.values().stream()
                .flatMap(List::stream)
                .anyMatch(g -> (g.end - g.start) > 0);

        return result;
    }
}





import java.util.*;

public class CoverageCalculator {

    public static double numericCoverage(
            FsmlModel.Variable var,
            List<NumericInterval> intervals,
            int buckets) {

        double step = (var.max - var.min) / buckets;
        int covered = 0;

        for (int i = 0; i < buckets; i++) {
            double probe = var.min + (i * step) + step / 2;

            boolean hit = intervals.stream()
                    .anyMatch(in -> in.contains(probe));

            if (hit) covered++;
        }
        return (covered * 100.0) / buckets;
    }

    public static double categoricalCoverage(
            FsmlModel.Variable var,
            FsmlModel model) {

        Set<String> used = new HashSet<>();

        for (FsmlModel.RuleNode n : model.nodes) {
            List<FsmlModel.Condition> c = n.conditions.get(var.name);
            if (c != null) {
                for (FsmlModel.Condition x : c) {
                    used.add(x.value);
                }
            }
        }
        return (used.size() * 100.0) / var.categories.size();
    }
}





import java.io.*;
import java.util.*;

public class JUnitTestGenerator {

    public static void generate(
            FsmlModel model,
            GapAnalysisResult gaps,
            File file) throws Exception {

        try (PrintWriter out = new PrintWriter(file)) {

            out.println("import org.junit.jupiter.api.Test;");
            out.println("import static org.junit.jupiter.api.Assertions.*;");
            out.println("import java.util.*;");
            out.println();
            out.println("class FsmlGeneratedTests {");

            int idx = 1;

            /* -------- Positive tests -------- */

            for (FsmlModel.RuleNode node : model.nodes) {
                if (node.action == null) continue;

                out.println("  @Test");
                out.println("  void positive_" + idx++ + "() {");
                out.println("    Map<String,Object> input = new HashMap<>();");

                for (var e : node.conditions.entrySet()) {
                    FsmlModel.Variable v = model.variables.get(e.getKey());
                    FsmlModel.Condition c = e.getValue().get(0);

                    if ("NUMERIC".equals(v.type)) {
                        double val = FsmlAnalyzer.resolveNumericValue(v, c.value);
                        out.println("    input.put(\"" + v.name + "\", " + val + ");");
                    } else {
                        out.println("    input.put(\"" + v.name + "\", \"" + c.value + "\");");
                    }
                }

                out.println("    String result = DecisionEngine.evaluate(input);");
                out.println("    assertEquals(\"" + node.action + "\", result);");
                out.println("  }");
            }

            /* -------- Negative GAP tests -------- */

            for (var entry : gaps.gaps.entrySet()) {
                String var = entry.getKey();

                for (NumericInterval g : entry.getValue()) {
                    double probe = (g.start + g.end) / 2;

                    out.println("  @Test");
                    out.println("  void negative_gap_" + idx++ + "() {");
                    out.println("    Map<String,Object> input = new HashMap<>();");
                    out.println("    input.put(\"" + var + "\", " + probe + ");");
                    out.println("    String result = DecisionEngine.evaluate(input);");
                    out.println("    assertNull(result);");
                    out.println("  }");
                }
            }

            out.println("}");
        }
    }
}





public class Main {

    public static void main(String[] args) throws Exception {

        FsmlModel model = FsmlParser.parse(
                new File("k.fsml"));

        GapAnalysisResult gaps = FsmlAnalyzer.analyze(model);

        System.out.println("=== GAP ANALYSIS ===");
        gaps.gaps.forEach((k, v) ->
                v.forEach(g ->
                        System.out.println(k + " GAP: " + g.start + " → " + g.end)));

        System.out.println("\n=== DEAD NODES ===");
        gaps.deadNodes.forEach(System.out::println);

        System.out.println("\nDefault / No-Hit Path Exists: " + gaps.hasDefaultPath);

        System.out.println("\n=== COVERAGE ===");
        for (FsmlModel.Variable v : model.variables.values()) {
            if ("NUMERIC".equals(v.type)) {
                double pct = CoverageCalculator.numericCoverage(
                        v,
                        FsmlAnalyzer.buildIntervals(v, model),
                        100);
                System.out.println(v.name + " coverage: " + pct + "%");
            } else {
                double pct = CoverageCalculator.categoricalCoverage(v, model);
                System.out.println(v.name + " coverage: " + pct + "%");
            }
        }

        JUnitTestGenerator.generate(
                model,
                gaps,
                new File("FsmlGeneratedTests.java"));
    }
}
