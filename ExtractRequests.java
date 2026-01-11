import java.util.*;

/**
 * COMPLETE Shadow Path Analyzer
 * Java 21 Compatible
 */
public class ShadowPathAnalyzer {

    /* =========================
       ENTRY POINT
       ========================= */
    public static void main(String[] args) {

        List<RulePath> paths = new ArrayList<>();

        // -------- Path 75 (Shadowing) --------
        paths.add(new RulePath(
                75,
                Map.of(
                        "MONTHS_ON_BOOKS", new Interval(3.0, 36.0),
                        "LOAN_PMT_DLNQ_DAYS_CNT", new Interval(-9999999, 30),
                        "ACCOUNT_BAL", new Interval(-9999999, 16000),
                        "ACCOUNT_STATUS_2", Interval.equalsValue("Active_Other")
                ),
                "Very Very High Risk_Low Value"
        ));

        // -------- Path 3 (Shadowed) --------
        paths.add(new RulePath(
                3,
                Map.of(
                        "MONTHS_ON_BOOKS", new Interval(3.0, 36.0),
                        "LOAN_PMT_DLNQ_DAYS_CNT", new Interval(-9999999, 30),
                        "FICO_09_SCORE", new Interval(-9999999, 500),
                        "ACCOUNT_BAL", new Interval(-9999999, 16000),
                        "ACCOUNT_STATUS_2", Interval.equalsValue("Active_Other")
                ),
                "Very Very High Risk_Low Value"
        ));

        // -------- Run Analysis --------
        ShadowAnalyzer.analyze(paths);

        // -------- Build Tree --------
        List<ShadowTreeNode> tree = ShadowTreeBuilder.build(paths);

        // -------- Print Visualization --------
        ShadowTreePrinter.print(tree);
    }
}

/* =========================================================
   RULE PATH
   ========================================================= */
class RulePath {

    final int pathId;
    final Map<String, Interval> conditions;
    final String action;

    boolean isShadowing = false;
    boolean isShadowed = false;
    Integer shadowedBy = null;

    RulePath(int pathId, Map<String, Interval> conditions, String action) {
        this.pathId = pathId;
        this.conditions = conditions;
        this.action = action;
    }
}

/* =========================================================
   INTERVAL (NaN & SENTINEL SAFE)
   ========================================================= */
class Interval {

    final Double min;
    final Double max;
    final String equalsValue; // for categorical

    Interval(double min, double max) {
        this.min = normalizeMin(min);
        this.max = normalizeMax(max);
        this.equalsValue = null;
    }

    private Interval(String equalsValue) {
        this.min = null;
        this.max = null;
        this.equalsValue = equalsValue;
    }

    static Interval equalsValue(String value) {
        return new Interval(value);
    }

    private double normalizeMin(double v) {
        return (Double.isNaN(v) || v <= -9999999)
                ? Double.NEGATIVE_INFINITY
                : v;
    }

    private double normalizeMax(double v) {
        return (Double.isNaN(v) || v >= 9999999)
                ? Double.POSITIVE_INFINITY
                : v;
    }

    boolean contains(Interval other) {

        // Categorical
        if (equalsValue != null) {
            return Objects.equals(this.equalsValue, other.equalsValue);
        }

        // Numeric
        return this.min <= other.min && this.max >= other.max;
    }

    @Override
    public String toString() {
        if (equalsValue != null) {
            return "= " + equalsValue;
        }
        return "[" + min + ", " + max + ")";
    }
}

/* =========================================================
   SHADOW ANALYZER
   ========================================================= */
class ShadowAnalyzer {

    static void analyze(List<RulePath> paths) {

        for (RulePath a : paths) {
            for (RulePath b : paths) {

                if (a == b) continue;
                if (!a.action.equals(b.action)) continue;

                if (shadows(a, b)) {
                    a.isShadowing = true;
                    b.isShadowed = true;
                    b.shadowedBy = a.pathId;
                }
            }
        }
    }

    private static boolean shadows(RulePath broader, RulePath narrower) {

        // Broader path must be equal or broader on ALL its conditions
        for (var entry : broader.conditions.entrySet()) {

            Interval broadInterval = entry.getValue();
            Interval narrowInterval = narrower.conditions.get(entry.getKey());

            if (narrowInterval == null) {
                return false;
            }

            if (!broadInterval.contains(narrowInterval)) {
                return false;
            }
        }

        // Broader must not have more conditions
        return broader.conditions.size() <= narrower.conditions.size();
    }
}

/* =========================================================
   SHADOW TREE NODE
   ========================================================= */
class ShadowTreeNode {

    final RulePath path;
    final List<ShadowTreeNode> children = new ArrayList<>();

    ShadowTreeNode(RulePath path) {
        this.path = path;
    }
}

/* =========================================================
   TREE BUILDER
   ========================================================= */
class ShadowTreeBuilder {

    static List<ShadowTreeNode> build(List<RulePath> paths) {

        Map<Integer, ShadowTreeNode> nodeMap = new HashMap<>();
        for (RulePath p : paths) {
            nodeMap.put(p.pathId, new ShadowTreeNode(p));
        }

        List<ShadowTreeNode> roots = new ArrayList<>();

        for (RulePath p : paths) {
            if (p.isShadowed && p.shadowedBy != null) {
                nodeMap.get(p.shadowedBy).children.add(nodeMap.get(p.pathId));
            } else {
                roots.add(nodeMap.get(p.pathId));
            }
        }
        return roots;
    }
}

/* =========================================================
   TREE PRINTER (VISUALIZATION)
   ========================================================= */
class ShadowTreePrinter {

    static void print(List<ShadowTreeNode> roots) {
        for (ShadowTreeNode root : roots) {
            printNode(root, "", true);
        }
    }

    private static void printNode(ShadowTreeNode node, String prefix, boolean last) {

        System.out.println(prefix
                + (last ? "└── " : "├── ")
                + "Path " + node.path.pathId
                + (node.path.isShadowing ? " [SHADOWING]" : "")
                + (node.path.isShadowed ? " [SHADOWED]" : "")
        );

        for (int i = 0; i < node.children.size(); i++) {
            printNode(
                    node.children.get(i),
                    prefix + (last ? "    " : "│   "),
                    i == node.children.size() - 1
            );
        }
    }
}
