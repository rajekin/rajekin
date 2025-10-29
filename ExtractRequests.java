

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

public class PMMLEvaluatorSimple {

    public static void main(String[] args) throws Exception {

        // --- 1. Load the PMML ---
        String pmmlPath = "C:\\opensource\\eclipse-workspace\\APRCalc\\plfraud.pmml"; // change path
        Evaluator evaluator = loadEvaluator(pmmlPath);
        evaluator.verify();

        // --- 2. Print input order ---
        List<InputField> inputFields = evaluator.getInputFields();
        System.out.println("Expected input order:");
        for (int i = 0; i < inputFields.size(); i++) {
            System.out.println((i + 1) + ". " + inputFields.get(i).getName().getValue());
        }

        // --- 3. Create input map (you can load from JSON or DB) ---
        Map<String, Object> rawInputs = new HashMap<>();
        rawInputs.put("ap_app_type_I", 1.0);
        rawInputs.put("ap_credit_score_g", 793);
        rawInputs.put("ap_term_12", 0.0);
        rawInputs.put("ap_term_24", 0.0);
        rawInputs.put("ap_term_36", 0.0);
        rawInputs.put("ap_term_48", 0.0);
        rawInputs.put("ap_loan_am", 5000);
        rawInputs.put("member_length_days", 93.0);
        rawInputs.put("ap_loan_purpose", 76);
        // add remaining fields as per printed order

        // --- 4. Evaluate ---
        Map<String, ?> results = evaluateModel(evaluator, rawInputs);

        // --- 5. Print outputs ---
        System.out.println("\n=== Model Outputs ===");
        results.forEach((k, v) -> System.out.println(k + " = " + v));
    }

    // ---------- Core logic below ----------

    private static Evaluator loadEvaluator(String pmmlPath) throws Exception {
        try (InputStream is = new FileInputStream(new File(pmmlPath))) {
            PMML pmml = PMMLUtil.unmarshal(is);
            return new LoadingModelEvaluatorBuilder().load(pmml).build();
        }
    }

    /**
     * You pass a simple Map<String,Object> and this helper internally prepares
     * the JPMML FieldName/FieldValue map (you never see them).
     */
    private static Map<String, ?> evaluateModel(Evaluator evaluator, Map<String, Object> rawInputs) {
        // Build internal argument map that JPMML expects
        Map<Object, Object> args = new LinkedHashMap<>();

        for (InputField in : evaluator.getInputFields()) {
            Object fieldNameObj = in.getName();     // this is FieldName internally
            Object preparedVal = in.prepare(rawInputs.get(in.getName().getValue())); // FieldValue internally
            args.put(fieldNameObj, preparedVal);
        }

        // Evaluate and decode to plain Java types
        Map<?, ?> rawResults = evaluator.evaluate(args);
        return EvaluatorUtil.decodeAll(rawResults);
    }
}
