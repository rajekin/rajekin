import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dmg.pmml.FieldName;
import org.jpmml.evaluator.*;

import java.io.File;
import java.nio.file.Paths;
import java.util.*;

public class PMMLRunner {
  public static void main(String[] args) throws Exception {
    Evaluator evaluator = new LoadingModelEvaluatorBuilder()
        .load(Paths.get("C:\\opensource\\eclipse-workspace\\APRCalc\\auto_gen2_model_implementation.pmml").toFile())
        .build();
    evaluator.verify();

    ObjectMapper mapper = new ObjectMapper();

    File dir = new File("C:\\Users\\RA0R17850C\\Downloads\\penfed-PMML-scoring-effort_20251024\\penfed-PMML-scoring-effort_20251024\\model_AutoGen2\\session_2025\\json");
    File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".json"));
    if (files == null || files.length == 0) {
      System.out.println("No JSON files found.");
      return;
    }

    for (File f : files) {
      // read this file -> Map
      Map<String,Object> inputMap = mapper.readValue(f, new TypeReference<Map<String,Object>>(){});

      // build evaluator arguments for this file
      Map<FieldName, FieldValue> args = new LinkedHashMap<>();
      int matched = 0;
      for (InputField in : evaluator.getInputFields()) {
        FieldName fn = in.getName();
        String expectedKey = fn.getValue();         // PMML input name
        Object raw = inputMap.get(expectedKey);     // must match JSON key
        if (raw != null) matched++;
        args.put(fn, in.prepare(raw));
      }

      Map<FieldName, ?> result = evaluator.evaluate(args);
      System.out.printf("%s  (matched %d/%d inputs) -> %s%n",
          f.getName(), matched, evaluator.getInputFields().size(), result);
    }
  }
}
