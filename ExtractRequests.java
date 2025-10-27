import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class InputJsonLoader {

  private static final ObjectMapper MAPPER = new ObjectMapper()
      .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
      .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
      .configure(JsonParser.Feature.ALLOW_COMMENTS, true)
      .configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true)
      .configure(JsonParser.Feature.ALLOW_TRAILING_COMMA, true);

  /** Load from a file path like "output.json". If your JSON is flat, this just returns that map.
      If nested, it flattens into keys like "a.b[0].c". */
  public static Map<String,Object> load(Path jsonPath) throws IOException {
    try (BufferedReader r = Files.newBufferedReader(jsonPath, StandardCharsets.UTF_8)) {
      JsonNode root = MAPPER.readTree(r);
      Map<String,Object> out = new LinkedHashMap<>();
      flatten("", root, out);
      return out;
    }
  }

  private static void flatten(String prefix, JsonNode node, Map<String,Object> out) {
    if (node.isObject()) {
      node.fields().forEachRemaining(e -> {
        String k = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
        flatten(k, e.getValue(), out);
      });
    } else if (node.isArray()) {
      for (int i = 0; i < node.size(); i++) {
        flatten(prefix + "[" + i + "]", node.get(i), out);
      }
    } else if (node.isNull()) {
      out.put(prefix, null);
    } else if (node.isNumber()) {
      out.put(prefix, new BigDecimal(node.asText())); // keeps precision
    } else if (node.isBoolean()) {
      out.put(prefix, node.booleanValue());
    } else { // text
      String t = node.asText().trim();
      if (t.isEmpty() || "null".equalsIgnoreCase(t)) { out.put(prefix, null); return; }
      // If a numeric is encoded as text, try to parse it
      try { out.put(prefix, new BigDecimal(t)); }
      catch (NumberFormatException ex) { out.put(prefix, t); }
    }
  }
}
