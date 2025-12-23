import java.util.Map;

public class TestCase {
    public Map<String, Object> inputs;
    public String expectedAction;

    public TestCase(Map<String, Object> inputs, String expectedAction) {
        this.inputs = inputs;
        this.expectedAction = expectedAction;
    }
}



import java.util.*;

public class TestCaseGenerator {

    public static List<TestCase> generate(FsmlModel model) {
        List<TestCase> tests = new ArrayList<>();

        for (FsmlModel.RuleNode node : model.nodes) {
            Map<String, Object> input = new HashMap<>();

            for (Map.Entry<String, List<FsmlModel.Condition>> e : node.conditions.entrySet()) {
                FsmlModel.Variable v = model.variables.get(e.getKey());
                FsmlModel.Condition c = e.getValue().get(0);

                if ("NUMERIC".equals(v.type)) {
                    double val = Double.parseDouble(c.value.equals("LOW") ? "1" : c.value.equals("HIGH") ? "9999" : c.value);
                    input.put(v.name, val);
                } else {
                    input.put(v.name, c.value);
                }
            }

            tests.add(new TestCase(input, node.action));
        }

        return tests;
    }
}




import java.io.File;
import java.util.List;

public class FsmlAnalyzerMain {

    public static void main(String[] args) throws Exception {
        File fsml = new File("PenFed_AR_Expert_09042025.fsml");

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


import java.io.File;
import java.util.List;

public class FsmlAnalyzerMain {

    public static void main(String[] args) throws Exception {
        File fsml = new File("PenFed_AR_Expert_09042025.fsml");

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

