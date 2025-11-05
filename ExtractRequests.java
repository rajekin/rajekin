// Extract only the application XML from SOAP
String resp1BodyXml = extractSoapBodyXml(resp1Xml);                 // SOAP Body
String appXml       = extractEmbeddedApplicationXml(resp1BodyXml);  // <inputxmlreturn>...</inputxmlreturn>

// (Optional) save for debugging
writeAtomic(fileOutDir.resolve(base + "-soap-application.xml"), appXml);

// Now transform THAT XML to JSON
String jsonFromResp1 = transformXmlToJson(xsltToJson, appXml);
String jsonFromResp1Lower = lowercaseRootKey(jsonFromResp1);



// 2a) Pull the inner XML from <Envelope>/<Body>. If not SOAP, returns input as-is.
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

        if (body == null) return envelopeOrXml; // plain XML, not a SOAP envelope

        // Serialize Body’s children back to XML
        StringWriter out = new StringWriter();
        javax.xml.transform.Transformer t = javax.xml.transform.TransformerFactory.newInstance().newTransformer();
        for (int i = 0; i < body.getChildNodes().getLength(); i++) {
            org.w3c.dom.Node child = body.getChildNodes().item(i);
            if (child.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                t.transform(new javax.xml.transform.dom.DOMSource(child),
                            new javax.xml.transform.stream.StreamResult(out));
            }
        }
        return out.toString();
    } catch (Exception ignore) {
        return envelopeOrXml;
    }
}

// 2b) Find the element that carries the *stringified* application XML (e.g., <inputxmlreturn>),
//     unwrap CDATA / &lt;...&gt; / &amp; entities, or if it’s already real XML, serialize that subtree.
private static String extractEmbeddedApplicationXml(String bodyXml) {
    try {
        javax.xml.parsers.DocumentBuilderFactory dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        javax.xml.parsers.DocumentBuilder db = dbf.newDocumentBuilder();
        org.w3c.dom.Document doc = db.parse(new org.xml.sax.InputSource(new java.io.StringReader(bodyXml)));

        javax.xml.xpath.XPath xp = javax.xml.xpath.XPathFactory.newInstance().newXPath();

        // Candidates that commonly hold the XML string
        String expr =
            "//*[local-name()='inputxmlreturn' or " +
            "  local-name()='xmlreturn' or " +
            "  local-name()='return' or " +
            "  local-name()='result' or " +
            "  local-name()='responseXml' or " +
            "  local-name()='applicationXml' or " +
            "  local-name()='application']";

        org.w3c.dom.Node target = (org.w3c.dom.Node) xp.evaluate(expr, doc, javax.xml.xpath.XPathConstants.NODE);
        if (target == null) {
            // Fallback: first element whose text looks like embedded XML
            target = (org.w3c.dom.Node) xp.evaluate("//*[contains(normalize-space(text()), '<') and contains(text(), '>')]",
                                                    doc, javax.xml.xpath.XPathConstants.NODE);
        }
        if (target == null) {
            // Last resort: return the whole body as-is
            return bodyXml;
        }

        // If the target itself has element children, it’s already real XML → serialize that subtree
        for (int i = 0; i < target.getChildNodes().getLength(); i++) {
            if (target.getChildNodes().item(i).getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                StringWriter out = new StringWriter();
                javax.xml.transform.Transformer t = javax.xml.transform.TransformerFactory.newInstance().newTransformer();
                t.transform(new javax.xml.transform.dom.DOMSource(target),
                            new javax.xml.transform.stream.StreamResult(out));
                return out.toString();
            }
        }

        // Otherwise its textContent contains the XML as a STRING (possibly CDATA / &lt;...&gt;)
        String s = target.getTextContent();
        s = stripCdata(s.trim());
        s = unescapeBasicXmlEntities(s.trim());
        return s;
    } catch (Exception e) {
        return bodyXml; // fail open
    }
}

private static String stripCdata(String s) {
    if (s == null) return "";
    String t = s.trim();
    if (t.startsWith("<![CDATA[")) t = t.substring(9);
    if (t.endsWith("]]>")) t = t.substring(0, t.length() - 3);
    return t;
}

private static String unescapeBasicXmlEntities(String s) {
    if (s == null) return "";
    return s.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&apos;", "'");
}
