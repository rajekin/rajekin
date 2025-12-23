import java.util.*;

public class GapAnalyzer {

    public static List<String> analyze(FsmlModel model) {
        List<String> gaps = new ArrayList<>();

        // 1️⃣ Variable used but not defined
        for (FsmlModel.RuleNode node : model.nodes) {
            for (String var : node.conditions.keySet()) {
                if (!model.variables.containsKey(var)) {
                    gaps.add("Variable used but not defined: " + var);
                }
            }
        }

        // 2️⃣ Node without action (leaf gap)
        for (FsmlModel.RuleNode node : model.nodes) {
            if (node.action == null || node.action.isBlank()) {
                gaps.add("Node has no ACTION (leaf gap): " + node.label);
            }
        }

        // 3️⃣ Numeric boundary coverage
        for (FsmlModel.Variable var : model.variables.values()) {
            if (!"NUMERIC".equals(var.type)) continue;

            boolean hasLow = false;
            boolean hasHigh = false;

            for (FsmlModel.RuleNode node : model.nodes) {
                List<FsmlModel.Condition> conds = node.conditions.get(var.name);
                if (conds == null) continue;

                for (FsmlModel.Condition c : conds) {
                    if ("LOW".equals(c.value) || "ge".equals(c.operator)) {
                        hasLow = true;
                    }
                    if ("HIGH".equals(c.value) || "lt".equals(c.operator)) {
                        hasHigh = true;
                    }
                }
            }

            if (!hasLow || !hasHigh) {
                gaps.add("Incomplete numeric range coverage for: " + var.name);
            }
        }

        // 4️⃣ Categorical coverage gaps
        for (FsmlModel.Variable var : model.variables.values()) {
            if (!"CATEGORICAL".equals(var.type)) continue;

            Set<String> used = new HashSet<>();

            for (FsmlModel.RuleNode node : model.nodes) {
                List<FsmlModel.Condition> conds = node.conditions.get(var.name);
                if (conds == null) continue;
                for (FsmlModel.Condition c : conds) {
                    used.add(c.value);
                }
            }

            for (String category : var.categories) {
                if (!used.contains(category)) {
                    gaps.add("Category not handled: " +
                            var.name + " = " + category);
                }
            }
        }

        return gaps;
    }
}





private static double resolveNumericValue(
        FsmlModel.Variable var,
        String value,
        String operator) {

    if (value == null || value.trim().isEmpty()) {
        return var.min;
    }

    if ("LOW".equals(value)) {
        return var.min;
    }

    if ("HIGH".equals(value)) {
        return var.max;
    }

    return Double.parseDouble(value);
}

