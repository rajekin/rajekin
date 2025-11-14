import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

// ...

private static String transformXmlToJson(Transformer t, String xml) throws Exception {
    StringWriter out = new StringWriter();
    t.transform(new StreamSource(new StringReader(xml)), new StreamResult(out));
    String json = out.toString();

    // Validate JSON here and print failing object if it’s bad
    try {
        // just validate – you still use parseJson(...) later as before
        MAPPER.readTree(json);
    } catch (JsonProcessingException e) {
        JsonLocation loc = e.getLocation();
        int offset = (int) loc.getCharOffset();   // -1 if not known

        System.err.println("==== JSON PARSE ERROR AFTER XSLT ====");
        System.err.println("Message : " + e.getOriginalMessage());
        System.err.println("Line    : " + loc.getLineNr());
        System.err.println("Column  : " + loc.getColumnNr());
        System.err.println("Offset  : " + offset);

        if (offset >= 0) {
            // Try to isolate the JSON object that contains the error
            int objStart = json.lastIndexOf('{', offset);
            int objEnd   = json.indexOf('}', offset);
            if (objStart >= 0 && objEnd > objStart) {
                String failingObject = json.substring(objStart, objEnd + 1);
                System.err.println("---- Failing JSON object ----");
                System.err.println(failingObject);
                System.err.println("---- end failing object ----");
            } else {
                // Fallback: just print a window around the error
                int start = Math.max(0, offset - 200);
                int end   = Math.min(json.length(), offset + 200);
                System.err.println("---- Snippet around error ----");
                System.err.println(json.substring(start, end));
                System.err.println("---- end snippet ----");
            }
        }

        // Re-throw so your caller still sees the failure
        throw e;
    }

    return json;
}
