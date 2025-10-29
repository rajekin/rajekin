import org.dmg.pmml.PMML;
import org.jpmml.evaluator.Evaluator;
import org.jpmml.evaluator.EvaluatorUtil;
import org.jpmml.evaluator.InputField;
import org.jpmml.evaluator.LoadingModelEvaluatorBuilder;
import org.jpmml.model.PMMLUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.*;

public class PMMLEvalNoFieldName {

    public static void main(String[] args) throws Exception {
        String pmmlPath = (args.length > 0)
                ? args[0]
                : "C:\\opensource\\eclipse-workspace\\APRCalc\\plfraud.pmml";

        Evaluator evaluator = loadEvaluator(pmmlPath);
        evaluator.verify();

        // Show MiningSchema order for positional input
        List<InputField> schemaOrder = evaluator.getInputFields();
        System.out.println("Expected input order (MiningSchema ACTIVE/GROUP):");
        for (int i = 0; i < schemaOrder.size(); i++) {
            System.out.printf("%02d  %s%n", i, schemaOrder.get(i).getName().getValue());
        }
        System.out.println();

        // --- Example inputs (fill yours) ---
        Map<String, Object> raw = new HashMap<>();
        raw.put("ap_app_type_I", 1.0);
        raw.put("ap_credit_score_g", 793);
        raw.put("ap_term_12", 0.0);
        raw.put("ap_term_24", 0.0);
        raw.put("ap_term_36", 0.0);
        raw.put("ap_term_48", 0.0);
        raw.put("ap_loan_am", 5000);
        raw.put("ap_state_CA", 0.0);
        raw.put("ap_state_FL", 0.0);
        raw.put("ap_state_IL", 0.0);
        raw.put("ap_state_NY", 0.0);
        raw.put("ap_state_TX", 0.0);
        raw.put("ap_state_VA", 0.0);
        raw.put("ap_state_WA", 0.0);
        raw.put("home_email_alp_num", 9.0);
        raw.put("home_email_domain_AOL.COM", 0.0);
        raw.put("home_email_domain_YAHOO.COM", 0.0);
        raw.put("home_email_num_num", 0.0);
        raw.put("home_email_oth_num", 0.0);
        raw.put("member_length_days", 93.0);
        raw.put("ap_loan_purpose", 76);
        // add all required fields…

        // A) Evaluate using a String->Object map (order-agnostic)
        Map<String, ?> out1 = evaluateByName(evaluator, raw);
        System.out.println("=== Outputs (by name) ===");
        out1.forEach((k, v) -> System.out.println(k + " = " + v));

        // B) Evaluate using a positional vector (must match printed order)
        double[] vector = buildVectorFromMap(schemaOrder, raw);
        Map<String, ?> out2 = evaluateVector(evaluator, vector);
        System.out.println("\n=== Outputs (by vector) ===");
        out2.forEach((k, v) -> System.out.println(k + " = " + v));
    }

    // ---------- PUBLIC HELPERS (no FieldName/FieldValue types) ----------

    /** Evaluate with a name->value map. */
    public static Map<String, ?> evaluateByName(Evaluator evaluator, Map<String, Object> rawByName) {
        // Build args as raw Map<Object,Object>; keys/values are created by JPMML APIs.
        Map args = new LinkedHashMap(); // raw to avoid generics on FieldName/FieldValue
        for (InputField in : evaluator.getInputFields()) {
            String fname = in.getName().getValue();
            Object raw = rawByName.get(fname);
            Object prepared = in.prepare(raw);       // FieldValue under the hood
            Object fieldNameObj = in.getName();      // FieldName under the hood
            args.put(fieldNameObj, prepared);
        }
        Map results = evaluator.evaluate(args);
        // Decode to user-friendly Map<String,?>
        return (Map<String, ?>) EvaluatorUtil.decodeAll(results);
    }

    /** Evaluate using a positional vector following MiningSchema order. */
    public static Map<String, ?> evaluateVector(Evaluator evaluator, double[] vector) {
        List<InputField> order = evaluator.getInputFields();
        if (vector.length != order.size()) {
            throw new IllegalArgumentException("Expected " + order.size() + " values, got " + vector.length);
        }
        Map args = new LinkedHashMap(); // raw Map
        for (int i = 0; i < order.size(); i++) {
            InputField in = order.get(i);
            Object prepared = in.prepare(vector[i]); // FieldValue internally
            Object fieldNameObj = in.getName();      // FieldName internally
            args.put(fieldNameObj, prepared);
        }
        Map results = evaluator.evaluate(args);
        return (Map<String, ?>) EvaluatorUtil.decodeAll(results);
    }

    /** Utility to build a vector from your name->value map using MiningSchema order. */
    public static double[] buildVectorFromMap(List<InputField> order, Map<String, Object> rawByName) {
        double[] vector = new double[order.size()];
        for (int i = 0; i < order.size(); i++) {
            String fname = order.get(i).getName().getValue();
            Object v = rawByName.get(fname);
            vector[i] = toDoubleOrNaN(v);
        }
        return vector;
    }

    // ---------- INTERNAL ----------

    private static Evaluator loadEvaluator(String pmmlPath) throws Exception {
        try (InputStream is = new FileInputStream(new File(pmmlPath))) {
            PMML pmml = PMMLUtil.unmarshal(is);
            return new LoadingModelEvaluatorBuilder().load(pmml).build();
        }
    }

    private static double toDoubleOrNaN(Object v) {
        if (v == null) return Double.NaN;
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return Double.parseDouble(String.valueOf(v)); }
        catch (Exception e) { return Double.NaN; }
    }
}
