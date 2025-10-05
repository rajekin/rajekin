private static InnerMeta extractWithYourXpath(String xml) {
    try {
        // Clean common hidden chars (BOM) that can break parsers or comparisons
        xml = xml.replace("\uFEFF", "");

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);                 // IMPORTANT for namespace'd XML
        dbf.setExpandEntityReferences(false);
        // Security hardening (optional)
        try {
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        } catch (Throwable ignore) {}

        DocumentBuilder db = dbf.newDocumentBuilder();
        org.w3c.dom.Document doc = db.parse(new java.io.ByteArrayInputStream(
                xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        XPath xpath = XPathFactory.newInstance().newXPath();

        // We’ll try several XPath candidates (attribute/element, with and without namespaces)
        // and pick the first non-empty result. We also log what matched for easier debugging.
        String[] dmPaths = new String[] {
            // Your original “element” location (no namespaces)
            "/Application/CreditRequest/DMFunction/text()",
            // Namespace-agnostic (element)
            "/*[local-name()='Application']/*[local-name()='CreditRequest']/*[local-name()='DMFunction']/text()",
            // In case some rows actually store it as an ATTRIBUTE on CreditRequest
            "/Application/CreditRequest/@DMfunction",
            "/*[local-name()='Application']/*[local-name()='CreditRequest']/@DMfunction",
            "/Application/CreditRequest/@DMFunction",
            "/*[local-name()='Application']/*[local-name()='CreditRequest']/@DMFunction"
        };

        String[] appNumPaths = new String[] {
            // Your exact path (no namespaces)
            "/Application/CreditApplication/PersonalApplicant/OMLoanApplicationList/OMLoanApplication/ApplicationNumber/text()",
            // Namespace-agnostic
            "/*[local-name()='Application']" +
                "/*[local-name()='CreditApplication']" +
                "/*[local-name()='PersonalApplicant']" +
                "/*[local-name()='OMLoanApplicationList']" +
                "/*[local-name()='OMLoanApplication']" +
                "/*[local-name()='ApplicationNumber']/text()",
            // Sometimes it appears elsewhere — last-resort scan
            "//*[local-name()='ApplicationNumber']/text()"
        };

        String dmFunc = firstNonEmpty(xpath, doc, dmPaths);
        String appNum = firstNonEmpty(xpath, doc, appNumPaths);

        // DEBUG (comment out when done)
        if (isBlank(dmFunc) || isBlank(appNum)) {
            System.err.println("DEBUG: Could not extract from inner XML, showing snippet:");
            System.err.println(xml.substring(0, Math.min(xml.length(), 800)));
            System.err.println("DEBUG dmFunc candidates tried:");
            for (String p : dmPaths) {
                String v = evalString(xpath, doc, p);
                System.err.println("  " + p + " => [" + v + "]");
            }
            System.err.println("DEBUG appNum candidates tried:");
            for (String p : appNumPaths) {
                String v = evalString(xpath, doc, p);
                System.err.println("  " + p + " => [" + v + "]");
            }
        }

        InnerMeta m = new InnerMeta();
        m.dmFunction = trimOrNull(dmFunc);
        m.applicationNumber = trimOrNull(appNum);
        return m;

    } catch (Exception e) {
        System.err.println("Failed to parse inner XML: " + e.getMessage());
        return null;
    }
}

private static String firstNonEmpty(XPath xpath, org.w3c.dom.Document doc, String[] paths) throws Exception {
    for (String p : paths) {
        String val = evalString(xpath, doc, p);
        if (!isBlank(val)) return val;
    }
    return null;
}

private static String evalString(XPath xpath, org.w3c.dom.Document doc, String path) throws Exception {
    String v = (String) xpath.evaluate(path, doc, javax.xml.xpath.XPathConstants.STRING);
    return v == null ? null : v;
}

private static String trimOrNull(String s) {
    if (s == null) return null;
    String t = s.trim();
    return t.isEmpty() ? null : t;
}

private static boolean isBlank(String s) {
    return s == null || s.trim().isEmpty();
}
