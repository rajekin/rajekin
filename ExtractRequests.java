import org.jpmml.evaluator.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

public class PMMLBatchRunner {

  private static final ObjectMapper OUT = new ObjectMapper().writerWithDefaultPrettyPrinter().withDefaultPrettyPrinter() == null
      ? new ObjectMapper() : new ObjectMapper(); // just to keep a mapper; we'll call writer later

  public static void runFolder(Evaluator evaluator, Path inputDir, Path outputDir) throws Exception {
    Files.createDirectories(outputDir);

    try (Stream<Path> files = Files.list(inputDir)) {
      files.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".json"))
           .forEach(p -> {
             try {
               // 1) Load JSON into map
               Map<String,Object> inputMap = InputJsonLoader.load(p);

               // 2) Build evaluator arguments
               Map<FieldName, FieldValue> args = new LinkedHashMap<>();
               for (InputField in : evaluator.getInputFields()) {
                 FieldName name = in.getName();
                 Object raw = getValue(inputMap, name.getValue()); // JSON key should match PMML input name
                 FieldValue prepared = in.prepare(raw);            // JPMML coerces types
                 args.put(name, prepared);
               }

               // 3) Evaluate
               Map<FieldName, ?> result = evaluator.evaluate(args);
               Map<String, ?> decoded = EvaluatorUtil.decodeAll(result);

               // 4) Write output JSON next to results folder
               String base = p.getFileName().toString().replaceFirst("\\.json$", "");
               Path outPath = outputDir.resolve(base + ".out.json");
               new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(outPath.toFile(), decoded);

               System.out.println("✓ " + p.getFileName() + " -> " + outPath.getFileName());
             } catch (Exception ex) {
               System.err.println("✗ " + p.getFileName() + " : " + ex.getMessage());
             }
           });
    }
  }

  // Exact match first; fall back to case-insensitive
  private static Object getValue(Map<String,Object> map, String key) {
    if (map.containsKey(key)) return map.get(key);
    for (String k : map.keySet()) if (k.equalsIgnoreCase(key)) return map.get(k);
    return null; // let JPMML treat as missing
  }
}
