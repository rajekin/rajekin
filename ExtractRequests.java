import org.dmg.pmml.FieldName;
import org.dmg.pmml.PMML;
import org.jpmml.evaluator.Evaluator;
import org.jpmml.evaluator.EvaluatorUtil;
import org.jpmml.evaluator.FieldValue;
import org.jpmml.evaluator.InputField;
import org.jpmml.evaluator.LoadingModelEvaluatorBuilder;
import org.jpmml.model.PMMLUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.*;

public class PMMLRunner {

    public static void main(String[] args) throws Exception {
        String pmmlPath = (args.length > 0)
                ? args[0]
                : "C:\\opensource\\eclipse-workspace\\APRCalc\\plfraud.pmml"; // <-- change if needed

        Evaluator evaluator = loadEvaluator(pmmlPath);
        evaluator.verify();

        // 1) Show the exact order expected when using positional input
        List<InputField> schemaOrder = evaluator.getInputFields();
        System.out.println("Expected input order (MiningSchema ACTIVE/GROUP):");
        for (int i = 0; i < schemaOrder.size(); i++) {
            System.out.printf("%02d  %s%n", i, schemaOrder.get(i).getName().getValue());
        }
        System.out.println();

        // 2) Example A — evaluate using your String-keyed map (you never touch FieldName)
        Map<String, Object> rawByName = new HashMap<>();
        rawByName.put("ap_app_type_I", 1.0);
        rawByName.put("ap_credit_score_g", 793);
        rawByName.put("ap_term_12", 0.0);
        rawByName.put("ap_term_24", 0.0);
        rawByName.put("ap_term_36", 0.0);
        rawByName.put("ap_term_48", 0.0);
        rawByName.put("ap_loan_am", 5000);
        rawByName.put("ap_state_CA", 0.0);
        rawByName.put("ap_state_FL", 0.0);
        rawByName.put("ap_state_IL", 0.0);
        rawByName.put("ap_state_NY", 0.0);
        rawByName.put("ap_state_TX", 0.0);
        rawByName.put("ap_state_VA", 0.0);
        rawByName.put("ap_state_WA", 0.0);
        rawByName.put("home_email_alp_num", 9.0);
        rawByName.put("home_email_domain_AOL.COM", 0.0);
        rawByName.put("home_email_domain_YAHOO.COM", 0.0);
        rawByName.put("home_email_num_num", 0.0);
        rawByName.put("home_email_oth_num", 0.0);
        rawByName.put("member_length_days", 93.0);
        rawByName.put("ap_loan_purpose", 76);
        // ... add the rest as required by your model

        Map<FieldName, FieldValue> argsFromNames = toEvaluatorArgsFromNameMap(evaluator, rawByName);
        System.out.println("=== Evaluate (String→Object map) ===");
        evaluateAndPrint(evaluator, argsFromNames);
        System.out.println();

        // 3) Example B — evaluate from a positional vector that follows the printed order
        double[] vector = new double[schemaOrder.size()];
        for (int i = 0; i < schemaOrder.size(); i++) {
            String fname = schemaOrder.get(i).getName().getValue();
            Object v = rawByName.get(fname);                 // reuse values above
            vector[i] = toDoubleOrNaN(v);                    // only OK for numeric fields
        }
        Map<FieldName, FieldValue> argsFromVector = toEvaluatorArgsFromVector(evaluator, vector);
        System.out.println("=== Evaluate (positional double[] in MiningSchema order) ===");
        evaluateAndPrint(evaluator, argsFromVector);
    }

    // ----------------- Helpers -----------------

    private static Evaluator loadEvaluator(String pmmlPath) throws Exception {
        try (InputStream is = new FileInputStream(new File(pmmlPath))) {
            PMML pmml = PMMLUtil.unmarshal(is);
            return new LoadingModelEvaluatorBuilder()
                    .load(pmml)
                    .build();
        }
    }

    /** Keep your app using Map<String, Object>. We convert to the required Map<FieldName, FieldValue> just-in-time. */
    private static Map<FieldName, FieldValue> toEvaluatorArgsFromNameMap(Evaluator evaluator,
                                                                         Map<String, Object> rawByName) {
        Map<FieldName, FieldValue> args = new LinkedHashMap<>();
        for (InputField in : evaluator.getInputFields()) {
            String fname = in.getName().getValue(); // String for your map
            Object raw = rawByName.get(fname);
            FieldValue prepared = in.prepare(raw);  // JPMML handles type coercion & missing values
            args.put(FieldName.create(fname), prepared);
        }
        return args;
    }

    /** Build evaluator args from a positional vector that matches evaluator.getInputFields() order. */
    private static Map<FieldName, FieldValue> toEvaluatorArgsFromVector(Evaluator evaluator,
                                                                        double[] vector) {
        List<InputField> order = evaluator.getInputFields();
        if (vector.length != order.size()) {
            throw new IllegalArgumentException("Expected " + order.size() + " values, got " + vector.length);
        }
        Map<FieldName, FieldValue> args = new LinkedHashMap<>();
        for (int i = 0; i < order.size(); i++) {
            InputField in = order.get(i);
            FieldValue prepared = in.prepare(vector[i]);
            args.put(in.getName(), prepared); // safe: we only touch FieldName here
        }
        return args;
    }

    private static void evaluateAndPrint(Evaluator evaluator, Map<FieldName, FieldValue> args) {
        Map<FieldName, ?> results = evaluator.evaluate(args);
        Map<String, ?> decoded = EvaluatorUtil.decodeAll(results);
        System.out.println("Outputs:");
        decoded.forEach((k, v) -> System.out.println("  " + k + " = " + v));
    }

    /** Minimal numeric coercion; for categoricals use the name-map path instead. */
    private static double toDoubleOrNaN(Object v) {
        if (v == null) return Double.NaN;
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return Double.parseDouble(String.valueOf(v)); }
        catch (Exception e) { return Double.NaN; }
    }
}
