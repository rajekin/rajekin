import org.dmg.pmml.FieldName;
import org.jpmml.evaluator.*;
import org.jpmml.model.PMMLUtil;

import javax.xml.bind.JAXBException;
import javax.xml.parsers.ParserConfigurationException;
import org.xml.sax.SAXException;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.IntStream;

public class PMMLRunner {

    public static void main(String[] args)
            throws IOException, ParserConfigurationException, SAXException, JAXBException {

        // --- Load PMML ---
        ModelEvaluator<?> evaluator = (ModelEvaluator<?>) new LoadingModelEvaluatorBuilder()
                .load(new File("C:\\opensource\\eclipse-workspace\\APRCalc\\plfraud.pmml"))
                .build();
        evaluator.verify();

        // --- Step 1: Get input order from MiningSchema ---
        List<InputField> schemaOrder = evaluator.getInputFields();
        System.out.println("Expected Input Order:");
        IntStream.range(0, schemaOrder.size()).forEach(i ->
                System.out.println(i + " : " + schemaOrder.get(i).getName().getValue())
        );

        // --- Step 2: Build raw input map by name (order-agnostic) ---
        Map<String, Object> rawInputs = new HashMap<>();
        rawInputs.put("ap_app_type_I", 1.0);
        rawInputs.put("ap_credit_score_g", 793);
        rawInputs.put("ap_term_12", 0.0);
        rawInputs.put("ap_term_24", 0.0);
        rawInputs.put("ap_term_36", 0.0);
        rawInputs.put("ap_term_48", 0.0);
        rawInputs.put("ap_term_60", 0.0);
        rawInputs.put("ap_loan_amt", 5000.0);
        rawInputs.put("ap_state_FL", 0.0);
        rawInputs.put("ap_state_GA", 0.0);
        rawInputs.put("ap_state_NY", 0.0);
        rawInputs.put("ap_state_VA", 0.0);
        rawInputs.put("ap_email_alp_num", 9.0);
        rawInputs.put("home_email_domain_AOL.COM", 0.0);
        rawInputs.put("home_email_domain_YAHOO.COM", 0.0);
        rawInputs.put("home_email_num_num", 0.0);
        rawInputs.put("home_email_oth_num", 0.0);
        rawInputs.put("member_length_days", 93.0);
        rawInputs.put("ap_loan_purpose", 76);

        // --- Step 3: Prepare arguments in correct order ---
        Map<FieldName, FieldValue> arguments = new LinkedHashMap<>();

        for (InputField inputField : schemaOrder) {
            FieldName fn = inputField.getName();
            Object raw = rawInputs.get(fn.getValue()); // name lookup
            FieldValue prepared = inputField.prepare(raw);
            arguments.put(fn, prepared);
        }

        // --- Step 4: Evaluate ---
        Map<FieldName, ?> results = evaluator.evaluate(arguments);

        // --- Step 5: Print results ---
        System.out.println("\n=== Evaluation Results ===");
        for (Map.Entry<FieldName, ?> e : results.entrySet()) {
            System.out.println(e.getKey().getValue() + " = " + e.getValue());
        }
    }
}
