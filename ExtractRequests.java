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

    public ConversionResult convert(String xml,
                                    boolean unwrapSoapBody,
                                    boolean parseCdataXml,
                                    boolean flattenAttributes,
                                    boolean coerceNumbers,
                                    boolean lowercaseRoot) throws Exception {
        if (xml == null || xml.isBlank()) throw new IllegalArgumentException("XML input is empty.");

        Document doc = buildSafeDocument(xml);
        Element start = doc.getDocumentElement();

        // SOAP unwrap (Envelope → Body → first payload)
        if (unwrapSoapBody && localName(start).equalsIgnoreCase("Envelope")) {
            Optional<Element> body = firstChildElementByLocalName(start, "Body");
            if (body.isPresent()) {
                Optional<Element> firstPayload = firstElementChild(body.get());
                if (firstPayload.isPresent()) start = firstPayload.get();
            }
        }

        // If root is a wrapper that contains XML inside CDATA/Text, promote the inner XML
        if (parseCdataXml) {
            String inner = collectText(start).trim();
            if (looksLikeXml(inner)) {
                Document innerDoc = buildSafeDocument(inner);
                start = innerDoc.getDocumentElement();
            }
        }

        Set<String> xmlAttrPaths = new LinkedHashSet<>();
        Set<String> jsonAttrPaths = new LinkedHashSet<>();

        ObjectNode root = mapper.createObjectNode();
        String rootName = localName(start);
        if (lowercaseRoot && rootName != null) rootName = rootName.toLowerCase(Locale.ROOT);

        root.set(rootName,
                elementToJson(start, mapper.createObjectNode(), "/" + rootName,
                        xmlAttrPaths, jsonAttrPaths,
                        parseCdataXml, flattenAttributes, coerceNumbers));

        String pretty = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);

        int xmlAttrCount = xmlAttrPaths.size();
        int jsonAttrCount = jsonAttrPaths.size();
        List<String> missing = new ArrayList<>();
        for (String p : xmlAttrPaths) if (!jsonAttrPaths.contains(p)) missing.add(p);
        boolean allConverted = missing.isEmpty() && xmlAttrCount == jsonAttrCount;

        return new ConversionResult(pretty, xmlAttrCount, jsonAttrCount, allConverted, missing);
    }

    /* ================= core ================= */

    private JsonNode elementToJson(Element elem, ObjectNode target, String path,
                                   Set<String> xmlAttrPaths, Set<String> jsonAttrPaths,
                                   boolean parseCdataXml, boolean flattenAttributes, boolean coerceNumbers) throws Exception {

        // Attributes (flatten by default)
        NamedNodeMap attrs = elem.getAttributes();
        if (attrs != null && attrs.getLength() > 0) {
            for (int i = 0; i < attrs.getLength(); i++) {
                Attr a = (Attr) attrs.item(i);
                if (isXsiNil(a)) continue;
                String key = a.getName();
                String val = a.getValue();
                String slot = key;

                if (flattenAttributes) {
                    if (target.has(slot)) slot = "_" + slot; // avoid clobber
                    putValue(target, slot, val, coerceNumbers);
                } else {
                    ObjectNode attrNode = (ObjectNode) target.get("@attributes");
                    if (attrNode == null) attrNode = target.putObject("@attributes");
                    putValue(attrNode, key, val, coerceNumbers);
                    slot = "@" + key;
                }

                String attrPath = path + "/@" + key;
                xmlAttrPaths.add(attrPath);
                jsonAttrPaths.add(attrPath);
            }
        }

        // Children / text
        Map<String, List<Element>> groups = new LinkedHashMap<>();
        NodeList children = elem.getChildNodes();
        StringBuilder textBuf = new StringBuilder();

        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n instanceof Element) {
                String ln = localName((Element) n);
                groups.computeIfAbsent(ln, k -> new ArrayList<>()).add((Element) n);
            } else if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) {
                textBuf.append(n.getTextContent());
            }
        }

        String rawText = textBuf.toString();
        String trimmed = rawText.trim();

        // Parse inner XML fragments if requested
        if (parseCdataXml && looksLikeXml(trimmed)) {
            try {
                Document inner = buildSafeDocument(trimmed);
                Element innerRoot = inner.getDocumentElement();
                JsonNode innerJson = elementToJson(innerRoot, mapper.createObjectNode(),
                        path + "/" + localName(innerRoot), xmlAttrPaths, jsonAttrPaths,
                        true, flattenAttributes, coerceNumbers);
                target.set(localName(innerRoot), innerJson);
            } catch (Exception ignore) {
                // leave as text
            }
        }

        // Leaf case
        if (groups.isEmpty()) {
            if (target.size() == 0) {
                if (trimmed.isEmpty()) return NullNode.instance;
                return new TextNode(trimmed);
            } else {
                if (!trimmed.isEmpty()) putValue(target, TEXT_KEY, trimmed, coerceNumbers);
                return target;
            }
        }

        // Children groups
        for (Map.Entry<String, List<Element>> e : groups.entrySet()) {
            String childName = e.getKey();
            List<Element> items = e.getValue();
            if (items.size() == 1) {
                target.set(childName, elementToJson(items.get(0), mapper.createObjectNode(),
                        path + "/" + childName, xmlAttrPaths, jsonAttrPaths,
                        parseCdataXml, flattenAttributes, coerceNumbers));
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

    private static boolean isXsiNil(Attr a) {
        return "nil".equals(a.getLocalName())
                && "http://www.w3.org/2001/XMLSchema-instance".equals(a.getNamespaceURI());
    }

    private static String collectText(Element e) {
        StringBuilder sb = new StringBuilder();
        NodeList nl = e.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) {
                sb.append(n.getTextContent());
            }
        }
        return sb.toString();
    }

    private static boolean looksLikeXml(String s) {
        return s.startsWith("<") && s.endsWith(">") && s.contains("</");
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
            if (nl.item(i) instanceof Element) return Optional.of((Element) nl.item(i));
        }
        return Optional.empty();
    }

    /* ===== robust numeric coercion ===== */

    private static void putValue(ObjectNode node, String key, String v, boolean coerceNumbers) {
        if (!coerceNumbers) { node.put(key, v); return; }

        String t = (v == null) ? "" : v.trim();
        if (t.isEmpty()) { node.put(key, ""); return; }

        if (isCanonicalNumber(t)) {
            if (writeNumeric(node, key, t)) return;
        }

        String relaxed = t.replace(",", "");
        if (relaxed.startsWith("$")) relaxed = relaxed.substring(1);
        if (relaxed.endsWith("%"))  relaxed = relaxed.substring(0, relaxed.length() - 1).trim();

        if (isCanonicalNumber(relaxed)) {
            if (writeNumeric(node, key, relaxed)) return;
        }

        node.put(key, v);
    }

    private static boolean isCanonicalNumber(String s) {
        return s.matches("^[+-]?(?:\\d+\\.\\d+|\\d+|\\.\\d+)(?:[eE][+-]?\\d+)?$");
    }

    private static boolean writeNumeric(ObjectNode node, String key, String num) {
        try {
            if (num.indexOf('.') < 0 && num.indexOf('e') < 0 && num.indexOf('E') < 0) {
                long lv = Long.parseLong(num);
                node.put(key, lv);
            } else {
                node.put(key, new BigDecimal(num));
            }
            return true;
        } catch (Exception ignore) {
            try {
                node.put(key, new BigDecimal(num));
                return true;
            } catch (Exception ignore2) {
                return false;
            }
        }
    }
}
*********************************************

  package com.raj.utilities.service.xsd;

import org.apache.xerces.dom.DOMInputImpl;
import org.apache.xerces.xs.*;
import org.w3c.dom.DOMImplementation;
import org.w3c.dom.bootstrap.DOMImplementationRegistry;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

public final class XsdSchemaModel {
    private final XSModel model;

    private XsdSchemaModel(XSModel model) { this.model = model; }

    public static XsdSchemaModel fromBytes(byte[] xsdBytes) throws Exception {
        System.setProperty(DOMImplementationRegistry.PROPERTY,
                "org.apache.xerces.dom.DOMXSImplementationSourceImpl");
        DOMImplementationRegistry registry = DOMImplementationRegistry.newInstance();
        DOMImplementation impl = registry.getDOMImplementation("XS-Loader");
        XSImplementation xsImpl = (XSImplementation) impl;
        XSLoader loader = xsImpl.createXSLoader(null);

        DOMInputImpl in = new DOMInputImpl();
        in.setStringData(new String(xsdBytes, StandardCharsets.UTF_8));
        XSModel model = loader.load(in);
        if (model == null) throw new IllegalArgumentException("Failed to load XSD");
        return new XsdSchemaModel(model);
    }

    public XSElementDeclaration getGlobalElement(String localName, String ns) {
        return model.getElementDeclaration(localName, ns == null ? "" : ns);
    }

    public Optional<ParticleInfo> findChild(XSElementDeclaration parent, String childLocalName) {
        if (parent == null) return Optional.empty();
        XSTypeDefinition t = parent.getTypeDefinition();
        if (!(t instanceof XSComplexTypeDefinition)) return Optional.empty();
        XSComplexTypeDefinition c = (XSComplexTypeDefinition) t;
        XSParticle p = c.getParticle();
        if (p == null) return Optional.empty();
        return walkParticleForChild(p, childLocalName);
    }

    private Optional<ParticleInfo> walkParticleForChild(XSParticle particle, String wanted) {
        XSTerm term = particle.getTerm();
        if (term instanceof XSElementDeclaration) {
            XSElementDeclaration el = (XSElementDeclaration) term;
            if (wanted.equals(el.getName())) {
                return Optional.of(new ParticleInfo(el, particle.getMinOccurs(),
                        particle.getMaxOccursUnbounded() ? Integer.MAX_VALUE : particle.getMaxOccurs()));
            }
        } else if (term instanceof XSModelGroup) {
            XSObjectList parts = ((XSModelGroup) term).getParticles();
            for (int i = 0; i < parts.getLength(); i++) {
                Optional<ParticleInfo> got = walkParticleForChild((XSParticle) parts.item(i), wanted);
                if (got.isPresent()) return got;
            }
        }
        return Optional.empty();
    }

    public ValueKind valueKind(XSElementDeclaration decl) {
        if (decl == null) return ValueKind.STRING;
        return valueKind(decl.getTypeDefinition());
    }

    public ValueKind valueKind(XSTypeDefinition t) {
        if (t instanceof XSSimpleTypeDefinition) {
            XSSimpleTypeDefinition s = (XSSimpleTypeDefinition) t;
            if (s.getVariety() == XSSimpleTypeDefinition.VARIETY_LIST) return ValueKind.LIST;
            switch (s.getPrimitiveKind()) {
                case XSSimpleTypeDefinition.PRIMITIVE_BOOLEAN: return ValueKind.BOOLEAN;
                case XSSimpleTypeDefinition.PRIMITIVE_DECIMAL: return ValueKind.DECIMAL;
                case XSSimpleTypeDefinition.PRIMITIVE_DOUBLE:
                case XSSimpleTypeDefinition.PRIMITIVE_FLOAT: return ValueKind.FLOATING;
                case XSSimpleTypeDefinition.PRIMITIVE_INTEGER:
                case XSSimpleTypeDefinition.PRIMITIVE_LONG:
                case XSSimpleTypeDefinition.PRIMITIVE_INT:
                case XSSimpleTypeDefinition.PRIMITIVE_SHORT:
                case XSSimpleTypeDefinition.PRIMITIVE_BYTE: return ValueKind.INTEGER;
                default: return ValueKind.STRING;
            }
        }
        if (t instanceof XSComplexTypeDefinition) {
            XSComplexTypeDefinition c = (XSComplexTypeDefinition) t;
            if (c.getContentType() == XSComplexTypeDefinition.CONTENTTYPE_SIMPLE) {
                return valueKind(c.getSimpleType());
            }
            return ValueKind.OBJECT;
        }
        return ValueKind.STRING;
    }

    public XSAttributeUseList attributesOf(XSElementDeclaration decl) {
        if (decl == null) return XSAttributeUseListImpl.EMPTY_LIST;
        XSTypeDefinition t = decl.getTypeDefinition();
        if (t instanceof XSComplexTypeDefinition) {
            return ((XSComplexTypeDefinition) t).getAttributeUses();
        }
        return XSAttributeUseListImpl.EMPTY_LIST;
    }

    public static boolean particleIsArray(ParticleInfo pi) {
        return pi != null && (pi.maxOccurs == Integer.MAX_VALUE || pi.maxOccurs > 1);
    }

    public static final class ParticleInfo {
        public final XSElementDeclaration decl;
        public final int minOccurs;
        public final int maxOccurs;
        public ParticleInfo(XSElementDeclaration decl, int minOccurs, int maxOccurs) {
            this.decl = decl; this.minOccurs = minOccurs; this.maxOccurs = maxOccurs;
        }
    }

    public enum ValueKind { STRING, INTEGER, DECIMAL, FLOATING, BOOLEAN, LIST, OBJECT }
}


*******************************************


  package com.raj.utilities.service.xsd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import org.apache.xerces.xs.*;
import org.w3c.dom.*;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static com.raj.utilities.service.xsd.XsdSchemaModel.particleIsArray;

public class XmlToJsonXsdService {

    private final ObjectMapper mapper = new ObjectMapper();

    public static final class Options {
        public boolean unwrapSoapBody = true;
        public boolean parseCdataXml  = true;
        public boolean flattenAttributes = true;
        public boolean lowercaseRoot = false;
        public boolean emitEmptyArraysFromXsd = false;
    }

    public static final class Result {
        public final String prettyJson;
        public Result(String prettyJson){ this.prettyJson = prettyJson; }
    }

    public Result convertUsingXsd(String xml, byte[] xsdBytes, Options opt) throws Exception {
        if (xml == null || xml.isBlank()) throw new IllegalArgumentException("XML input is empty.");
        if (opt == null) opt = new Options();

        XsdSchemaModel xsd = XsdSchemaModel.fromBytes(xsdBytes);

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        dbf.setXIncludeAware(false);
        dbf.setExpandEntityReferences(false);
        DocumentBuilder db = dbf.newDocumentBuilder();
        Document doc = db.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        doc.getDocumentElement().normalize();

        Element root = doc.getDocumentElement();

        if (opt.unwrapSoapBody && localName(root).equalsIgnoreCase("Envelope")) {
            Optional<Element> body = firstByLocalName(root, "Body");
            if (body.isPresent()) {
                Optional<Element> firstPayload = firstElementChild(body.get());
                if (firstPayload.isPresent()) root = firstPayload.get();
            }
        }
        if (opt.parseCdataXml) {
            String inner = collectText(root).trim();
            if (looksLikeXml(inner)) {
                Document innerDoc = db.parse(new ByteArrayInputStream(inner.getBytes(StandardCharsets.UTF_8)));
                root = innerDoc.getDocumentElement();
            }
        }

        String rootNs = root.getNamespaceURI();
        String rootName = localName(root);
        XSElementDeclaration rootDecl = xsd.getGlobalElement(rootName, rootNs);
        if (rootDecl == null) rootDecl = xsd.getGlobalElement(rootName, "");

        ObjectNode out = mapper.createObjectNode();
        String outRootName = opt.lowercaseRoot ? rootName.toLowerCase(Locale.ROOT) : rootName;
        out.set(outRootName, elementToJson(root, rootDecl, xsd, opt));

        String pretty = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(out);
        return new Result(pretty);
    }

    /* ================= core ================= */

    private JsonNode elementToJson(Element elem,
                                   XSElementDeclaration elemDecl,
                                   XsdSchemaModel xsd,
                                   Options opt) {

        // xsi:nil
        String xsiNil = elem.getAttributeNS("http://www.w3.org/2001/XMLSchema-instance", "nil");
        if ("true".equalsIgnoreCase(xsiNil)) return NullNode.instance;

        ObjectNode node = mapper.createObjectNode();

        // Attributes (typed if declared)
        XSAttributeUseList attrUses = (elemDecl != null) ? xsd.attributesOf(elemDecl) : XSAttributeUseListImpl.EMPTY_LIST;
        Map<String, XSSimpleTypeDefinition> attrTypeByLocal = new HashMap<>();
        for (int i=0;i<attrUses.getLength();i++) {
            XSAttributeUse use = (XSAttributeUse) attrUses.item(i);
            XSAttributeDeclaration ad = use.getAttrDeclaration();
            attrTypeByLocal.put(ad.getName(), ad.getTypeDefinition());
        }
        NamedNodeMap attrs = elem.getAttributes();
        if (attrs != null) {
            for (int i=0;i<attrs.getLength();i++) {
                Attr a = (Attr) attrs.item(i);
                if ("nil".equals(a.getLocalName()) &&
                    "http://www.w3.org/2001/XMLSchema-instance".equals(a.getNamespaceURI())) continue;

                String key = a.getName();
                String ln = a.getLocalName() != null ? a.getLocalName() : a.getName();
                String val = a.getValue();
                XSSimpleTypeDefinition t = attrTypeByLocal.get(ln);

                if (opt.flattenAttributes) {
                    node.set(safeKey(node, key), coerceByXsd(val, t));
                } else {
                    ObjectNode at = (ObjectNode) node.get("@attributes");
                    if (at == null) at = node.putObject("@attributes");
                    at.set(ln, coerceByXsd(val, t));
                }
            }
        }

        String text = collectText(elem).trim();
        Map<String, List<Element>> groups = groupChildren(elem);

        if (groups.isEmpty()) {
            if (elemDecl != null) {
                XSSimpleTypeDefinition st = simpleTypeOf(elemDecl);
                if (st != null) {
                    return coerceByXsd(text, st);
                }
            }
            if (node.size() == 0) {
                return text.isEmpty() ? NullNode.instance : new TextNode(text);
            } else {
                if (!text.isEmpty()) node.put("#text", text);
                return node;
            }
        }

        for (Map.Entry<String, List<Element>> e : groups.entrySet()) {
            String childLocal = e.getKey();
            List<Element> items = e.getValue();

            XsdSchemaModel.ParticleInfo pi = (elemDecl != null) ? xsd.findChild(elemDecl, childLocal).orElse(null) : null;
            XSElementDeclaration childDecl = (pi != null) ? pi.decl : null;
            boolean forceArray = particleIsArray(pi);

            if (forceArray) {
                ArrayNode arr = mapper.createArrayNode();
                for (Element ce : items) {
                    arr.add(elementToJson(ce, childDecl, xsd, opt));
                }
                node.set(childLocal, arr);
            } else {
                node.set(childLocal, elementToJson(items.get(0), childDecl, xsd, opt));
            }
        }

        if (opt.emitEmptyArraysFromXsd && elemDecl != null) {
            XSTypeDefinition t = elemDecl.getTypeDefinition();
            if (t instanceof XSComplexTypeDefinition) {
                XSComplexTypeDefinition c = (XSComplexTypeDefinition) t;
                XSParticle p = c.getParticle();
                if (p != null) addMissingArraysFromParticle(node, p);
            }
        }

        if (!text.isEmpty()) node.put("#text", text);
        return node;
    }

    private void addMissingArraysFromParticle(ObjectNode node, XSParticle particle) {
        XSTerm term = particle.getTerm();
        if (term instanceof XSElementDeclaration) {
            XSElementDeclaration el = (XSElementDeclaration) term;
            String ln = el.getName();
            boolean isArr = particle.getMaxOccursUnbounded() || particle.getMaxOccurs() > 1;
            if (isArr && !node.has(ln)) node.set(ln, mapper.createArrayNode());
        } else if (term instanceof XSModelGroup) {
            XSObjectList parts = ((XSModelGroup) term).getParticles();
            for (int i=0;i<parts.getLength();i++) addMissingArraysFromParticle(node, (XSParticle) parts.item(i));
        }
    }

    /* =============== helpers =============== */

    private static String safeKey(ObjectNode node, String key) {
        return node.has(key) ? "_"+key : key;
    }

    private static Map<String, List<Element>> groupChildren(Element elem) {
        Map<String, List<Element>> g = new LinkedHashMap<>();
        NodeList nl = elem.getChildNodes();
        for (int i=0;i<nl.getLength();i++) {
            Node n = nl.item(i);
            if (n instanceof Element) {
                String ln = localName((Element) n);
                g.computeIfAbsent(ln, k->new ArrayList<>()).add((Element) n);
            }
        }
        return g;
    }

    private static XSSimpleTypeDefinition simpleTypeOf(XSElementDeclaration d) {
        if (d == null) return null;
        XSTypeDefinition t = d.getTypeDefinition();
        if (t instanceof XSSimpleTypeDefinition) return (XSSimpleTypeDefinition) t;
        if (t instanceof XSComplexTypeDefinition) {
            XSComplexTypeDefinition c = (XSComplexTypeDefinition) t;
            if (c.getContentType() == XSComplexTypeDefinition.CONTENTTYPE_SIMPLE) return c.getSimpleType();
        }
        return null;
    }

    private static JsonNode coerceByXsd(String value, XSSimpleTypeDefinition t) {
        if (t == null) return new TextNode(value);
        String v = value == null ? "" : value.trim();

        // list types → array of strings split by whitespace
        if (t.getVariety() == XSSimpleTypeDefinition.VARIETY_LIST) {
            ArrayNode arr = new ObjectMapper().createArrayNode();
            for (String s : v.split("\\s+")) if (!s.isEmpty()) arr.add(s);
            return arr;
        }

        short prim = t.getPrimitiveKind();
        try {
            switch (prim) {
                case XSSimpleTypeDefinition.PRIMITIVE_BOOLEAN:
                    if ("1".equals(v) || "true".equalsIgnoreCase(v)) return BooleanNode.TRUE;
                    if ("0".equals(v) || "false".equalsIgnoreCase(v)) return BooleanNode.FALSE;
                    return new TextNode(value);
                case XSSimpleTypeDefinition.PRIMITIVE_INTEGER:
                case XSSimpleTypeDefinition.PRIMITIVE_LONG:
                case XSSimpleTypeDefinition.PRIMITIVE_INT:
                case XSSimpleTypeDefinition.PRIMITIVE_SHORT:
                case XSSimpleTypeDefinition.PRIMITIVE_BYTE:
                    v = v.replace(",", "");
                    return LongNode.valueOf(Long.parseLong(v));
                case XSSimpleTypeDefinition.PRIMITIVE_DECIMAL:
                case XSSimpleTypeDefinition.PRIMITIVE_DOUBLE:
                case XSSimpleTypeDefinition.PRIMITIVE_FLOAT:
                    v = v.replace(",", "").replace("%", "");
                    return DecimalNode.valueOf(new java.math.BigDecimal(v));
                default:
                    return new TextNode(value);
            }
        } catch (Exception ignore) {
            return new TextNode(value);
        }
    }

    private static Optional<Element> firstByLocalName(Element p, String ln) {
        NodeList nl = p.getChildNodes();
        for (int i=0;i<nl.getLength();i++) {
            Node n = nl.item(i);
            if (n instanceof Element) {
                Element e = (Element) n;
                if (ln.equalsIgnoreCase(localName(e))) return Optional.of(e);
            }
        }
        return Optional.empty();
    }
    private static Optional<Element> firstElementChild(Element p) {
        NodeList nl = p.getChildNodes();
        for (int i=0;i<nl.getLength();i++) if (nl.item(i) instanceof Element) return Optional.of((Element) nl.item(i));
        return Optional.empty();
    }
    private static String collectText(Element e) {
        StringBuilder sb = new StringBuilder();
        NodeList nl = e.getChildNodes();
        for (int i=0;i<nl.getLength();i++) {
            Node n = nl.item(i);
            if (n.getNodeType()==Node.TEXT_NODE || n.getNodeType()==Node.CDATA_SECTION_NODE) sb.append(n.getTextContent());
        }
        return sb.toString();
    }
    private static boolean looksLikeXml(String s) { return s.startsWith("<") && s.endsWith(">") && s.contains("</"); }
    private static String localName(Element e){ return e.getLocalName()!=null? e.getLocalName(): e.getNodeName(); }
}
***********************


  package com.raj.utilities.service;

import com.raj.utilities.service.XmlToJsonService.ConversionResult;
import com.raj.utilities.service.xsd.XmlToJsonXsdService;
import com.raj.utilities.service.xsd.XmlToJsonXsdService.Options;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Service
public class XmlToJsonFacade {

    private final XmlToJsonService plain;
    private final XmlToJsonXsdService schema;
    private final ResourceLoader resources;

    @Value("${raj.xml2json.xsd:classpath:/xsd/application.xsd}")
    private String defaultXsdLocation;

    public XmlToJsonFacade(XmlToJsonService plain,
                           XmlToJsonXsdService schema,
                           ResourceLoader resources) {
        this.plain = plain;
        this.schema = schema;
        this.resources = resources;
    }

    public Map<String, Object> convert(
            byte[] body,
            boolean unwrapSoapBody,
            boolean parseCdataXml,
            boolean flattenAttributes,
            boolean coerceNumbers,
            boolean lowercaseRoot,
            boolean useXsd,
            String xsdPathOverride,
            boolean emitEmptyArraysFromXsd
    ) throws Exception {

        String xml = new String(body, StandardCharsets.UTF_8).trim();

        if (!useXsd) {
            ConversionResult res = plain.convert(xml, unwrapSoapBody, parseCdataXml, flattenAttributes, coerceNumbers, lowercaseRoot);
            return Map.of(
                    "json", res.prettyJson,
                    "xmlAttributeCount", res.xmlAttributeCount,
                    "jsonAttributeCount", res.jsonAttributeCount,
                    "allAttributesConverted", res.allAttributesConverted,
                    "missingAttributes", res.missingAttributes,
                    "schemaMode", false
            );
        }

        byte[] xsdBytes = loadXsdBytes(
                (xsdPathOverride != null && !xsdPathOverride.isBlank())
                        ? xsdPathOverride
                        : defaultXsdLocation
        );

        Options opt = new Options();
        opt.unwrapSoapBody = unwrapSoapBody;
        opt.parseCdataXml = parseCdataXml;
        opt.flattenAttributes = flattenAttributes;
        opt.lowercaseRoot = lowercaseRoot;
        opt.emitEmptyArraysFromXsd = emitEmptyArraysFromXsd;

        var res = schema.convertUsingXsd(xml, xsdBytes, opt);
        return Map.of("json", res.prettyJson, "schemaMode", true);
    }

    private byte[] loadXsdBytes(String location) throws Exception {
        String loc = (location.startsWith("classpath:") || location.startsWith("file:")) ? location : "classpath:" + location;
        Resource r = resources.getResource(loc);
        try (InputStream in = r.getInputStream()) {
            return in.readAllBytes();
        }
    }
}
**********************************************************************************

  package com.raj.utilities.web;

import com.raj.utilities.service.XmlToJsonFacade;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/xml-to-json")
public class XmlToJsonApi {

    private final XmlToJsonFacade facade;

    public XmlToJsonApi(XmlToJsonFacade facade) {
        this.facade = facade;
    }

    @PostMapping(
        consumes = { MediaType.TEXT_PLAIN_VALUE, MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE, MediaType.APPLICATION_OCTET_STREAM_VALUE },
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    public Map<String, Object> convert(
        @RequestBody byte[] body,
        @RequestParam(name="unwrapSoapBody",    defaultValue="true")  boolean unwrapSoapBody,
        @RequestParam(name="parseCdataXml",     defaultValue="true")  boolean parseCdataXml,
        @RequestParam(name="flattenAttributes", defaultValue="true")  boolean flattenAttributes,
        @RequestParam(name="coerceNumbers",     defaultValue="true")  boolean coerceNumbers,
        @RequestParam(name="lowercaseRoot",     defaultValue="false") boolean lowercaseRoot,

        // XSD mode toggles
        @RequestParam(name="useXsd",            defaultValue="false") boolean useXsd,
        @RequestParam(name="xsdPath",           required = false)     String xsdPath,
        @RequestParam(name="emitEmptyArraysFromXsd", defaultValue="false") boolean emitEmptyArraysFromXsd
    ) throws Exception {

        return facade.convert(body, unwrapSoapBody, parseCdataXml, flattenAttributes, coerceNumbers, lowercaseRoot,
                              useXsd, xsdPath, emitEmptyArraysFromXsd);
    }
}
*************************************************


  # Where your schema is, e.g., src/main/resources/xsd/application.xsd
raj.xml2json.xsd=classpath:/xsd/application.xsd

  ************************************************************

  <dependency>
  <groupId>xerces</groupId>
  <artifactId>xercesImpl</artifactId>
  <version>2.12.2</version>
</dependency>
******************************************************************

  <!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8"/>
  <title>XML/SOAP → JSON · Raj Utilities</title>
  <meta name="viewport" content="width=device-width, initial-scale=1"/>
  <meta name="color-scheme" content="light dark">
  <style>
    :root{
      --bg:#0b0f1a; --fg:#e7e9ee; --muted:#a7b0bf; --card:#0f1524; --border:#233049; --accent:#7c9cff;
      --chip:#141c30; --ok:#34d399; --bad:#f87171; --btn:#1c2336; --btn-fg:#e7e9ee; --link:#9bb3ff;
      --codebg:#0c1222; --codefg:#e7e9ee;
    }
    @media (prefers-color-scheme: light){
      :root{
        --bg:#f7f8fb; --fg:#0d1320; --muted:#5b6577; --card:#ffffff; --border:#e5e9f2; --accent:#3b6cff;
        --chip:#f1f4fb; --ok:#0f766e; --bad:#b91c1c; --btn:#0d1320; --btn-fg:#ffffff; --link:#305dff;
        --codebg:#0b1020; --codefg:#e7e9ee;
      }
    }
    *{box-sizing:border-box}
    body{margin:28px;font:14px/1.45 ui-sans-serif,system-ui,-apple-system,Segoe UI,Roboto,Arial;color:var(--fg);background:var(--bg)}
    .container{max-width:1200px;margin:0 auto}
    .topbar{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:18px}
    h1{margin:0;font-size:1.5rem}
    a.back{color:var(--link);text-decoration:none}
    .panel{background:var(--card);border:1px solid var(--border);border-radius:16px;padding:18px}
    .controls{display:flex;flex-wrap:wrap;gap:12px;align-items:center;margin-bottom:14px}
    .switch{display:flex;align-items:center;gap:8px;background:var(--chip);border:1px solid var(--border);padding:8px 10px;border-radius:12px}
    .switch input[type="checkbox"]{width:16px;height:16px}
    .text{display:flex;align-items:center;gap:8px;background:var(--chip);border:1px solid var(--border);padding:8px 10px;border-radius:12px}
    .text input{border:none;outline:none;background:transparent;color:var(--fg);min-width:260px}
    .btn{padding:10px 14px;border-radius:12px;border:1px solid var(--border);background:var(--btn);color:var(--btn-fg);cursor:pointer}
    .btn.secondary{background:transparent;color:var(--fg)}
    .btn[disabled]{opacity:.6;cursor:not-allowed}
    .grid{display:grid;grid-template-columns:1fr 1fr;gap:18px}
    @media (max-width: 980px){.grid{grid-template-columns:1fr}}
    .col-head{margin:8px 0 8px;color:var(--muted);font-weight:600}
    textarea{width:100%;min-height:420px;resize:vertical;border-radius:14px;border:1px solid var(--border);
      background:var(--bg);color:var(--fg);padding:12px;font:13px ui-monospace,SFMono-Regular,Menlo,Consolas,monospace}
    pre.code{white-space:pre-wrap;word-wrap:break-word;background:var(--codebg);color:var(--codefg);border-radius:14px;
      border:1px solid var(--border);padding:12px;min-height:420px;margin:0}
    .stats{display:flex;flex-wrap:wrap;gap:10px;margin-top:10px}
    .chip{display:inline-flex;align-items:center;gap:6px;background:var(--chip);border:1px solid var(--border);
      padding:6px 10px;border-radius:999px}
    .ok{color:var(--ok)} .bad{color:var(--bad)}
    .hint{margin-top:10px;color:var(--muted)}
  </style>
</head>
<body>
<div class="container">
  <div class="topbar">
    <h1>XML/SOAP → JSON Converter</h1>
    <div style="display:flex;gap:8px;align-items:center">
      <a class="back" href="/">← Back to utilities</a>
      <button id="loadSample" class="btn secondary" title="Load a SOAP+CDATA sample">Load sample</button>
    </div>
  </div>

  <div class="panel">
    <div class="controls" id="controls">
      <!-- Existing features -->
      <label class="switch" title="Remove SOAP envelope and target Body/payload">
        <input type="checkbox" id="unwrap" checked>
        <span>Unwrap SOAP Body</span>
      </label>
      <label class="switch" title="Parse XML inside CDATA/Text nodes">
        <input type="checkbox" id="parseCdata" checked>
        <span>Parse XML in CDATA</span>
      </label>
      <label class="switch" title="Place XML attributes as sibling JSON properties">
        <input type="checkbox" id="flatten" checked>
        <span>Flatten attributes</span>
      </label>
      <label class="switch" title="Try to convert numeric-looking strings to numbers">
        <input type="checkbox" id="coerce" checked>
        <span>Coerce numbers</span>
      </label>
      <label class="switch" title="Make the top JSON key lowercase (Application → application)">
        <input type="checkbox" id="lowerRoot">
        <span>Lowercase root</span>
      </label>

      <!-- XSD mode -->
      <label class="switch" title="Use XSD to force arrays/types">
        <input type="checkbox" id="useXsd">
        <span>Use XSD (arrays/types)</span>
      </label>
      <label class="text" title="Override the classpath XSD (optional)">
        <span>XSD path</span>
        <input type="text" id="xsdPath" placeholder="classpath:/xsd/application.xsd">
      </label>
      <label class="switch" title="Emit [] for array elements defined in XSD but missing in XML">
        <input type="checkbox" id="emitEmpty">
        <span>Emit empty arrays</span>
      </label>

      <button id="convertBtn" class="btn">Convert</button>
      <button id="clearBtn" class="btn secondary">Clear</button>
      <span id="status" style="margin-left:auto;color:var(--muted)"></span>
    </div>

    <div class="grid">
      <div>
        <div class="col-head">Input (XML or SOAP)</div>
        <textarea id="xml" placeholder="Paste your XML or SOAP request here…"></textarea>
        <div class="hint">Tip: Keep “Parse XML in CDATA” on if your payload is wrapped inside &lt;![CDATA[ … ]]&gt;.</div>
      </div>
      <div>
        <div class="col-head">Output (JSON)</div>
        <pre id="json" class="code">/* JSON will appear here */</pre>
        <div id="stats" class="stats"></div>
        <div style="margin-top:10px;display:flex;gap:8px">
          <button id="copyBtn" class="btn secondary" title="Copy JSON to clipboard">Copy JSON</button>
        </div>
      </div>
    </div>
  </div>
</div>

<script>
  const $ = (id) => document.getElementById(id);
  const xmlEl = $("xml"), jsonEl = $("json"), statsEl = $("stats"), statusEl = $("status");
  const btn = $("convertBtn");

  const unwrapEl = $("unwrap"), parseCdataEl = $("parseCdata"), flattenEl = $("flatten"),
        coerceEl = $("coerce"), lowerRootEl = $("lowerRoot"),
        useXsdEl = $("useXsd"), xsdPathEl = $("xsdPath"), emitEmptyEl = $("emitEmpty");

  $("clearBtn").addEventListener("click", () => {
    xmlEl.value = ""; jsonEl.textContent = "/* JSON will appear here */"; statsEl.innerHTML = "";
  });

  $("copyBtn").addEventListener("click", async () => {
    try {
      await navigator.clipboard.writeText(jsonEl.textContent || "");
      statusEl.textContent = "Copied!";
      setTimeout(() => statusEl.textContent = "", 900);
    } catch { /* ignore */ }
  });

  $("loadSample").addEventListener("click", () => {
    xmlEl.value =
`<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:inv="http://invoker.ps.example">
  <soapenv:Header/>
  <soapenv:Body>
    <inv:inputXml><![CDATA[
      <Application DeliveryOptionCode="PenFed-52538" ProcessingRequestType="DA">
        <CreditRequest DMfunction="GBL" DMProduct="NVH" ProductCategory="AutoLoans"/>
        <CreditApplication>
          <type xType="OM_CREDIT_APPLICATION_TYPE_INDIVIDUAL">OM_CREDIT_APPLICATION_TYPE_INDIVIDUAL</type>
          <enteredTimestamp>2024-01-04T14:05:14</enteredTimestamp>
          <applicationCreatedTimestamp>2024-01-04T14:05:08</applicationCreatedTimestamp>
          <RequestType>ReqOffer</RequestType>
          <LoanSourceId>Branch</LoanSourceId>
          <DEBT_RATIO>0.5</DEBT_RATIO>
          <PAYMENT_PER_AMOUNT>1,000</PAYMENT_PER_AMOUNT>
        </CreditApplication>
      </Application>
    ]]></inv:inputXml>
  </soapenv:Body>
</soapenv:Envelope>`;
  });

  useXsdEl.addEventListener("change", () => {
    // When using XSD, numeric coercion is typically handled via schema types;
    // keep both options available, but you can disable if you prefer:
    // coerceEl.disabled = useXsdEl.checked;
  });

  async function convert() {
    statsEl.innerHTML = "";
    jsonEl.textContent = "";
    const xml = xmlEl.value.trim();
    if (!xml) { jsonEl.textContent = "Please paste some XML."; return; }

    btn.disabled = true;
    statusEl.textContent = "Converting…";
    try {
      const qs = new URLSearchParams({
        unwrapSoapBody: unwrapEl.checked,
        parseCdataXml: parseCdataEl.checked,
        flattenAttributes: flattenEl.checked,
        coerceNumbers: coerceEl.checked,
        lowercaseRoot: lowerRootEl.checked,
        useXsd: useXsdEl.checked,
        xsdPath: xsdPathEl.value.trim(),
        emitEmptyArraysFromXsd: emitEmptyEl.checked
      }).toString();

      const resp = await fetch("/api/xml-to-json?" + qs, {
        method: "POST",
        headers: { "Content-Type": "text/plain" },
        body: xml
      });
      if (!resp.ok) throw new Error(await resp.text());
      const data = await resp.json();

      jsonEl.textContent = data.json;

      // Show attribute stats only in non-schema mode
      if (!data.schemaMode && typeof data.xmlAttributeCount === "number") {
        const ok = !!data.allAttributesConverted;
        statsEl.innerHTML = `
          <span class="chip">XML attrs: <strong>${data.xmlAttributeCount}</strong></span>
          <span class="chip">JSON attrs: <strong>${data.jsonAttributeCount}</strong></span>
          <span class="chip ${ok ? 'ok' : 'bad'}">${ok ? 'All attributes converted' : 'Attributes missing'}</span>
          ${!ok && data.missingAttributes?.length ? `<details style="margin-top:6px"><summary class="chip">Missing list</summary><pre class="code" style="background:var(--chip);border:none;margin:8px 0 0;min-height:auto">${data.missingAttributes.join('\n')}</pre></details>` : ""}
        `;
      } else {
        statsEl.innerHTML = "";
      }
    } catch (e) {
      jsonEl.textContent = "Error: " + (e.message || e);
    } finally {
      btn.disabled = false;
      statusEl.textContent = "";
    }
  }

  btn.addEventListener("click", convert);
</script>
</body>
</html>


  
