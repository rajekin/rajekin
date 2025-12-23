import java.util.*;

public class FsmlModel {

    public Map<String, Variable> variables = new HashMap<>();
    public List<RuleNode> nodes = new ArrayList<>();

    public static class Variable {
        public String name;
        public String type; // NUMERIC / CATEGORICAL
        public Double min;
        public Double max;
        public Set<String> categories = new HashSet<>();
    }

    public static class RuleNode {
        public String label;
        public Map<String, List<Condition>> conditions = new HashMap<>();
        public String action;
    }

    public static class Condition {
        public String variable;
        public String operator; // ge, lt, eq
        public String value;
    }
}





import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.io.File;

public class FsmlParser {

    public static FsmlModel parse(File fsmlFile) throws Exception {
        FsmlModel model = new FsmlModel();

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        Document doc = dbf.newDocumentBuilder().parse(fsmlFile);
        doc.getDocumentElement().normalize();

        // ---------- Numeric Keys ----------
        NodeList numericKeys = doc.getElementsByTagName("NumericKey");
        for (int i = 0; i < numericKeys.getLength(); i++) {
            Element e = (Element) numericKeys.item(i);
            FsmlModel.Variable v = new FsmlModel.Variable();
            v.name = e.getAttribute("ShortName");
            v.type = "NUMERIC";

            Element range = (Element) e.getElementsByTagName("NumericRange").item(0);
            v.min = Double.valueOf(range.getAttribute("minValue"));
            v.max = Double.valueOf(range.getAttribute("maxValue"));

            model.variables.put(v.name, v);
        }

        // ---------- Categorical Keys ----------
        NodeList catKeys = doc.getElementsByTagName("CategoricalKey");
        for (int i = 0; i < catKeys.getLength(); i++) {
            Element e = (Element) catKeys.item(i);
            FsmlModel.Variable v = new FsmlModel.Variable();
            v.name = e.getAttribute("ShortName");
            v.type = "CATEGORICAL";

            NodeList cats = e.getElementsByTagName("CATEGORY");
            for (int j = 0; j < cats.getLength(); j++) {
                v.categories.add(((Element) cats.item(j)).getAttribute("Value"));
            }

            model.variables.put(v.name, v);
        }

        // ---------- Nodes ----------
        NodeList nodes = doc.getElementsByTagName("NODE");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element n = (Element) nodes.item(i);
            FsmlModel.RuleNode rn = new FsmlModel.RuleNode();
            rn.label = n.getAttribute("Label");

            NodeList conditions = n.getElementsByTagName("CONDITION");
            for (int j = 0; j < conditions.getLength(); j++) {
                Element c = (Element) conditions.item(j);
                if (!c.hasAttribute("DecisionKey")) continue;

                FsmlModel.Condition cond = new FsmlModel.Condition();
                cond.variable = c.getAttribute("DecisionKey");
                cond.operator = c.getAttribute("Type");
                cond.value = c.getAttribute("Value");

                rn.conditions
                  .computeIfAbsent(cond.variable, k -> new java.util.ArrayList<>())
                  .add(cond);
            }

            NodeList actions = n.getElementsByTagName("ACTIONS");
            if (actions.getLength() > 0) {
                rn.action = ((Element) actions.item(0)).getAttribute("Label");
            }

            model.nodes.add(rn);
        }

        return model;
    }
}



import java.util.*;

public class GapAnalyzer {

    public static List<String> analyze(FsmlModel model) {
        List<String> gaps = new ArrayList<>();

        // Undefined variables
        for (FsmlModel.RuleNode node : model.nodes) {
            for (String var : node.conditions.keySet()) {
                if (!model.variables.containsKey(var)) {
                    gaps.add("Variable used but not defined: " + var);
                }
            }
        }

        // Missing actions
        for (FsmlModel.RuleNode node : model.nodes) {
            if (node.action == null || node.action.isEmpty()) {
                gaps.add("Node has no action: " + node.label);
            }
        }

        // Numeric boundary gaps
        model.variables.values().stream()
                .filter(v -> "NUMERIC".equals(v.type))
                .forEach(v -> {
                    boolean hasMin = false, hasMax = false;
                    for (FsmlModel.RuleNode node : model.nodes) {
                        List<FsmlModel.Condition> conds = node.conditions.get(v.name);
                        if (conds == null) continue;
                        for (FsmlModel.Condition c : conds) {
                            if ("ge".equals(c.operator)) hasMin = true;
                            if ("lt".equals(c.operator)) hasMax = true;
                        }
                    }
                    if (!hasMin || !hasMax) {
                        gaps.add("Numeric variable has incomplete coverage: " + v.name);
                    }
                });

        return gaps;
    }
}


import java.io.File;
import java.util.List;

public class FsmlAnalyzerMain {

    public static void main(String[] args) throws Exception {
        File fsml = new File("file.fsml");

        FsmlModel model = FsmlParser.parse(fsml);

        System.out.println("---- GAPS ----");
        GapAnalyzer.analyze(model).forEach(System.out::println);

        System.out.println("\n---- TEST CASES ----");
        List<TestCase> tests = TestCaseGenerator.generate(model);
        for (TestCase tc : tests) {
            System.out.println(tc.inputs + " -> " + tc.expectedAction);
        }
    }
}



