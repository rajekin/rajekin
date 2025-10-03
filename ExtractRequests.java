// --- Replace the old extractWithYourXpath(...) with this ---

private static InnerMeta extractWithYourXpath(String xml) {
    try {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);                 // be safe if namespaces exist
        dbf.setExpandEntityReferences(false);
        DocumentBuilder db = dbf.newDocumentBuilder();
        Document innerDoc = db.parse(
            new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        XPath xpath = XPathFactory.newInstance().newXPath();

        // 1) Your EXACT paths (no namespaces). Works when the inner XML has no default ns.
        String dmFunc = (String) xpath.evaluate(
            "/Application/CreditRequest/DMFunction/text()",
            innerDoc, XPathConstants.STRING);

        String appNum = (String) xpath.evaluate(
            "/Application/CreditApplication/PersonalApplicant/OMLoanApplicationList/OMLoanApplication/ApplicationNumber/text()",
            innerDoc, XPathConstants.STRING);

        // 2) Fallback: namespace-agnostic using local-name() if the exact ones return empty
        if (isBlank(dmFunc)) {
            dmFunc = (String) xpath.evaluate(
                "/*[local-name()='Application']/*[local-name()='CreditRequest']/*[local-name()='DMFunction']/text()",
                innerDoc, XPathConstants.STRING);
        }
        if (isBlank(appNum)) {
            appNum = (String) xpath.evaluate(
                "/*[local-name()='Application']/*[local-name()='CreditApplication']" +
                "/*[local-name()='PersonalApplicant']/*[local-name()='OMLoanApplicationList']" +
                "/*[local-name()='OMLoanApplication']/*[local-name()='ApplicationNumber']/text()",
                innerDoc, XPathConstants.STRING);
        }

        InnerMeta m = new InnerMeta();
        m.dmFunction = dmFunc != null ? dmFunc.trim() : null;
        m.applicationNumber = appNum != null ? appNum.trim() : null;
        return m;

    } catch (Exception e) {
        System.err.println("Failed to parse inner XML: " + e.getMessage());
        return null;
    }
}
