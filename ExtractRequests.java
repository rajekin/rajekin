package com.raj.utilities.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class XmlToJsonService {

    private static final String TEXT_KEY = "#text";
    private final ObjectMapper mapper = new ObjectMapper();

    public static final class ConversionResult {
        public final String prettyJson;
        public final int xmlAttributeCount;
        public final int jsonAttributeCount;
        public final boolean allAttributesConverted;
        public final List<String> missingAttributes;

        public ConversionResult(String prettyJson, int xmlAttributeCount, int jsonAttributeCount,
                                boolean allAttributesConverted, List<String> missingAttributes) {
            this.prettyJson = prettyJson;
            this.xmlAttributeCount = xmlAttributeCount;
            this.jsonAttributeCount = jsonAttributeCount;
            this.allAttributesConverted = allAttributesConverted;
            this.missingAttributes = missingAttributes;
        }
    }

    /** Main entry */
    public ConversionResult convert(String xml,
                                    boolean unwrapSoapBody,
                                    boolean parseCdataXml,
                                    boolean flattenAttributes,
                                    boolean coerceNumbers) throws Exception {
        if (xml == null || xml.isBlank()) throw new IllegalArgumentException("XML input is empty.");

        Document doc = buildSafeDocument(xml);
        Element start = doc.getDocumentElement();

        // Unwrap SOAP Body if asked
        if (unwrapSoapBody && localName(start).equalsIgnoreCase("Envelope")) {
            Optional<Element> body = firstChildElementByLocalName(start, "Body");
            if (body.isPresent()) {
                start = firstElementChild(body.get()).orElse(body.get());
            }
        }

        Set<String> xmlAttrPaths = new LinkedHashSet<>();
        Set<String> jsonAttrPaths = new LinkedHashSet<>();

        ObjectNode root = mapper.createObjectNode();
        root.set(localName(start),
                 elementToJson(start,
                               mapper.createObjectNode(),
                               "/" + localName(start),
                               xmlAttrPaths, jsonAttrPaths,
                               parseCdataXml, flattenAttributes, coerceNumbers));

        String pretty = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);

        // Verify 1:1 attribute accounting
        int xmlAttrCount = xmlAttrPaths.size();
        int jsonAttrCount = jsonAttrPaths.size();
        List<String> missing = new ArrayList<>();
        for (String p : xmlAttrPaths) if (!jsonAttrPaths.contains(p)) missing.add(p);
        boolean allConverted = missing.isEmpty() && xmlAttrCount == jsonAttrCount;

        return new ConversionResult(pretty, xmlAttrCount, jsonAttrCount, allConverted, missing);
    }

    /* ================= helpers ================= */

    private Document buildSafeDocument(String xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        dbf.setXIncludeAware(false);
        dbf.setExpandEntityReferences(false);
        DocumentBuilder db = dbf.newDocumentBuilder();
        return db.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private JsonNode elementToJson(Element elem, ObjectNode target, String path,
                                   Set<String> xmlAttrPaths, Set<String> jsonAttrPaths,
                                   boolean parseCdataXml, boolean flattenAttributes, boolean coerceNumbers) throws Exception {
        // 1) Attributes
        NamedNodeMap attrs = elem.getAttributes();
        if (attrs != null && attrs.getLength() > 0) {
            for (int i = 0; i < attrs.getLength(); i++) {
                Attr a = (Attr) attrs.item(i);
                String key = a.getName();
                String val = a.getValue();
                String slot = key;
                if (flattenAttributes) {
                    // avoid clobbering an existing child with same name
                    if (target.has(slot)) slot = "_" + slot;
                    putValue(target, slot, val, coerceNumbers);
                } else {
                    ObjectNode attrNode = (ObjectNode) target.get("@attributes");
                    if (attrNode == null) attrNode = target.putObject("@attributes");
                    putValue(attrNode, key, val, coerceNumbers);
                    slot = "@"+key;
                }
                String attrPath = path + "/@" + key;
                xmlAttrPaths.add(attrPath);
                jsonAttrPaths.add(attrPath);
            }
        }

        // 2) Children vs text
        Map<String, List<Element>> groups = new LinkedHashMap<>();
        NodeList children = elem.getChildNodes();
        StringBuilder textBuf = new StringBuilder();

        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n instanceof Element) {
                Element ce = (Element) n;
                String ln = localName(ce);
                groups.computeIfAbsent(ln, k -> new ArrayList<>()).add(ce);
            } else if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) {
                textBuf.append(n.getTextContent());
            }
        }

        String rawText = textBuf.toString();
        String trimmed = rawText.trim();

        // Special: parse XML inside CDATA/text if it looks like XML
        if (parseCdataXml && looksLikeXml(trimmed)) {
            try {
                Document inner = buildSafeDocument(trimmed);
                Element innerRoot = inner.getDocumentElement();
                ObjectNode innerObj = mapper.createObjectNode();
                JsonNode innerJson = elementToJson(innerRoot, innerObj,
                        path + "/" + localName(innerRoot), xmlAttrPaths, jsonAttrPaths,
                        true, flattenAttributes, coerceNumbers);
                // we insert the parsed payload as a single child with its own root name
                target.set(localName(innerRoot), innerJson);
                // Continue to also handle any element children of current node below
            } catch (Exception ignore) {
                // fall back to treating as text
            }
        }

        // If no element children, return text (or object if attributes already added)
        if (groups.isEmpty()) {
            if (target.size() == 0) {
                if (trimmed.isEmpty()) return NullNode.instance;
                // keep text as string; optional: coerce numbers for pure text-only nodes
                return new TextNode(trimmed);
            } else {
                if (!trimmed.isEmpty()) putValue(target, TEXT_KEY, trimmed, coerceNumbers);
                return target;
            }
        }

        // 3) Handle grouped children
        for (Map.Entry<String, List<Element>> e : groups.entrySet()) {
            String childName = e.getKey();
            List<Element> items = e.getValue();
            if (items.size() == 1) {
                Element only = items.get(0);
                JsonNode val = elementToJson(only, mapper.createObjectNode(),
                        path + "/" + childName, xmlAttrPaths, jsonAttrPaths,
                        parseCdataXml, flattenAttributes, coerceNumbers);
                target.set(childName, val);
            } else {
                ArrayNode arr = mapper.createArrayNode();
                for (Element ce : items) {
                    arr.add(elementToJson(ce, mapper.createObjectNode(),
                            path + "/" + childName + "[" + arr.size() + "]",
                            xmlAttrPaths, jsonAttrPaths,
                            parseCdataXml, flattenAttributes, coerceNumbers));
                }
                target.set(childName, arr);
            }
        }

        if (!trimmed.isEmpty()) putValue(target, TEXT_KEY, trimmed, coerceNumbers);
        return target;
    }

    private static boolean looksLikeXml(String s) {
        return s.startsWith("<") && s.endsWith(">") && s.contains("</");
    }

    private static void putValue(ObjectNode node, String key, String v, boolean coerceNumbers) {
        if (coerceNumbers && isNumeric(v)) {
            if (v.contains(".") || v.contains("e") || v.contains("E")) {
                node.put(key, new BigDecimal(v));
            } else {
                try {
                    long lv = Long.parseLong(v);
                    node.put(key, lv);
                } catch (NumberFormatException nfe) {
                    node.put(key, new BigDecimal(v));
                }
            }
        } else {
            node.put(key, v);
        }
    }

    private static boolean isNumeric(String s) {
        if (s == null || s.isEmpty()) return false;
        String t = s.trim();
        return t.matches("[-+]?\\d+(\\.\\d+)?([eE][-+]?\\d+)?");
    }

    private static String localName(Element e) {
        return (e.getLocalName() != null) ? e.getLocalName() : e.getNodeName();
    }

    private static Optional<Element> firstChildElementByLocalName(Element parent, String wantedLocalName) {
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n instanceof Element) {
                Element e = (Element) n;
                String ln = localName(e);
                if (wantedLocalName.equalsIgnoreCase(ln)) return Optional.of(e);
            }
        }
        return Optional.empty();
    }

    private static Optional<Element> firstElementChild(Element parent) {
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n instanceof Element) return Optional.of((Element) n);
        }
        return Optional.empty();
    }
}
