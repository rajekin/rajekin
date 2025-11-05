

// If endpoint 2 sent XML/SOAP by mistake (e.g., error page), convert it to JSON
String resp2Normalized = resp2Json;
if (resp2Normalized != null && resp2Normalized.trim().startsWith("<")) {
    String body2 = extractSoapBodyXml(resp2Normalized);     // handles plain XML or SOAP
    resp2Normalized = lowercaseRootKey(transformXmlToJson(xsltToJson, body2));
}

JsonNode left  = parseJsonStrict(jsonFromResp1Lower, fileOutDir, base, "left");   // SOAP→XML→JSON
JsonNode right = parseJsonStrict(resp2Normalized,    fileOutDir, base, "right");  // JSON (or XML converted)


// Pulls the inner XML from <Envelope>/<Body> … if present; otherwise returns the input as-is.
private static String extractSoapBodyXml(String envelopeOrXml) {
    try {
        javax.xml.parsers.DocumentBuilderFactory dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        javax.xml.parsers.DocumentBuilder db = dbf.newDocumentBuilder();
        org.w3c.dom.Document doc = db.parse(new org.xml.sax.InputSource(new java.io.StringReader(envelopeOrXml)));

        javax.xml.xpath.XPath xp = javax.xml.xpath.XPathFactory.newInstance().newXPath();
        org.w3c.dom.Node body = (org.w3c.dom.Node) xp.evaluate(
                "/*[local-name()='Envelope']/*[local-name()='Body']",
                doc, javax.xml.xpath.XPathConstants.NODE);

        if (body == null) return envelopeOrXml; // plain XML, no SOAP

        // Serialize all Body children
        StringWriter out = new StringWriter();
        javax.xml.transform.Transformer t = javax.xml.transform.TransformerFactory.newInstance().newTransformer();
        for (int i = 0; i < body.getChildNodes().getLength(); i++) {
            org.w3c.dom.Node child = body.getChildNodes().item(i);
            t.transform(new javax.xml.transform.dom.DOMSource(child),
                        new javax.xml.transform.stream.StreamResult(out));
        }
        return out.toString();
    } catch (Exception ignore) {
        return envelopeOrXml; // fall back
    }
}

// Normalizes and strictly parses JSON; writes a *_INVALID.json.txt file if it can’t parse.
private static JsonNode parseJsonStrict(String raw, java.nio.file.Path fileOutDir, String base, String label) throws java.io.IOException {
    String normalized = normalizeJsonText(raw);
    try {
        return MAPPER.readTree(normalized);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
        java.nio.file.Path bad = fileOutDir.resolve(base + "-" + label + "-INVALID.json.txt");
        String msg = "Parse error at line " + e.getLocation().getLineNr() + ", col " + e.getLocation().getColumnNr()
                   + " :: " + e.getOriginalMessage() + "\n\n=== RAW ===\n" + raw;
        java.nio.file.Files.writeString(bad, msg, java.nio.charset.StandardCharsets.UTF_8);
        throw e;
    }
}

private static String normalizeJsonText(String s) {
    if (s == null) return "";
    String t = stripUtf8Bom(s).trim();
    // If server wrapped JSON as a quoted string: "\"{...}\""
    if (t.startsWith("\"") && t.endsWith("\"")) {
        try { t = MAPPER.readValue(t, String.class).trim(); } catch (Exception ignore) {}
    }
    // If there’s leading/trailing noise, keep only the outermost {} or []
    int obj = t.indexOf('{'), arr = t.indexOf('[');
    int start = (obj == -1) ? arr : (arr == -1 ? obj : Math.min(obj, arr));
    int end = Math.max(t.lastIndexOf('}'), t.lastIndexOf(']'));
    if (start >= 0 && end > start) t = t.substring(start, end + 1).trim();
    return t;
}

private static String stripUtf8Bom(String s) {
    return (s != null && !s.isEmpty() && s.charAt(0) == '\uFEFF') ? s.substring(1) : s;
}
