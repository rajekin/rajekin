package com.raj.utilities.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import org.apache.xerces.dom.DOMInputImpl;
import org.apache.xerces.xs.*;
import org.w3c.dom.*;
import org.w3c.dom.bootstrap.DOMImplementationRegistry;
import org.springframework.stereotype.Service;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * ONE class that handles XML/SOAP → JSON conversion.
 *
 * Features:
 *  - Optional SOAP Envelope/Body unwrap
 *  - Parse XML embedded in CDATA/Text (top-level + inline)
 *  - Flatten attributes (or group under @attributes)
 *  - Number coercion in plain mode (integers/decimals/booleans)
 *  - XSD-aware mode: arrays decided by maxOccurs, value types by schema, xsi:nil → null
 *  - Lowercase root option
 *  - Optionally emit [] for XSD-defined arrays missing in XML
 */
@Service
public class XmlToJsonUnifiedService {

    /* ======================= Options & Result ======================= */

    public static final class Options {
        public boolean unwrapSoapBody = true;          // Envelope/Body → payload
        public boolean parseCdataXml = true;           // promote/parse XML from CDATA/Text
        public boolean flattenAttributes = true;       // attributes as sibling props (vs @attributes)
        public boolean coerceNumbers = true;           // plain mode only (schema mode uses XSD types)
        public boolean lowercaseRoot = false;          // Application → application
        public boolean useXsd = false;                 // turn on schema-aware conversion
        public String  xsdLocation = null;             // e.g., "classpath:/xsd/application.xsd" or "file:/path/app.xsd"
        public boolean emitEmptyArraysFromXsd = false; // materialize [] for schema arrays missing in XML
    }

    public static final class Result {
        public final String prettyJson;
        public Result(String prettyJson) { this.prettyJson = prettyJson; }
    }

    private final ObjectMapper mapper = new ObjectMapper();

    /* ======================= Public API ======================= */

    /**
     * Convert XML/SOAP into JSON, optionally using an XSD.
     * Controller can pass bare "xsd/app.xsd"; this service normalizes to classpath:/xsd/app.xsd.
     */
    public Result convert(String xml, Options opt) throws Exception {
        if (xml == null || xml.isBlank()) throw new IllegalArgumentException("XML input is empty.");
        if (opt == null) opt = new Options();

        // Secure XML parse
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

        // Unwrap SOAP Envelope/Body
        if (opt.unwrapSoapBody && localName(root).equalsIgnoreCase("Envelope")) {
            Optional<Element> body = firstByLocalName(root, "Body");
            if (body.isPresent()) {
                Optional<Element> payload = firstElementChild(body.get());
                if (payload.isPresent()) root = payload.get();
            }
        }

        // Promote inner XML from CDATA/Text (top level)
        if (opt.parseCdataXml) {
            String inner = collectText(root).trim();
            if (looksLikeXml(inner)) {
                Document innerDoc = db.parse(new ByteArrayInputStream(inner.getBytes(StandardCharsets.UTF_8)));
                root = innerDoc.getDocumentElement();
            }
        }

        ObjectNode out = mapper.createObjectNode();
        String rootName = localName(root);
        String outRoot = opt.lowercaseRoot ? rootName.toLowerCase(Locale.ROOT) : rootName;

        if (!opt.useXsd) {
            // PLAIN MODE
            JsonNode payload = elementToJsonPlain(root, mapper.createObjectNode(), "/" + outRoot, opt);
            out.set(outRoot, payload);
        } else {
            // XSD MODE
            String normalized = normalizeLocation(opt.xsdLocation);
            XSModel xsdModel = loadXsModel(normalized);
            XSElementDeclaration rootDecl = lookupRootDecl(xsdModel, root);
            JsonNode payload = elementToJsonXsd(root, rootDecl, xsdModel, opt);
            out.set(outRoot, payload);
        }

        String pretty = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(out);
        return new Result(pretty);
    }

    /* ======================= Plain mode ======================= */

    private JsonNode elementToJsonPlain(Element elem, ObjectNode target, String path, Options opt) throws Exception {
        // Attributes
        NamedNodeMap attrs = elem.getAttributes();
        if (attrs != null && attrs.getLength() > 0) {
            for (int i = 0; i < attrs.getLength(); i++) {
                Attr a = (Attr) attrs.item(i);
                if (isXsiNil(a)) continue;
                String key = a.getName(), val = a.getValue();
                if (opt.flattenAttributes) {
                    String slot = target.has(key) ? "_" + key : key;
                    putValue(target, slot, val, opt.coerceNumbers);
                } else {
                    ObjectNode at = (ObjectNode) target.get("@attributes");
                    if (at == null) at = target.putObject("@attributes");
                    putValue(at, key, val, opt.coerceNumbers);
                }
            }
        }

        // Children + text
        Map<String, List<Element>> groups = groupChildren(elem);
        String rawText = collectText(elem).trim();

        // Inline fragment parse (if element's text contains an XML doc)
        if (opt.parseCdataXml && looksLikeXml(rawText)) {
            try {
                Document inner = buildSafeDocument(rawText);
                Element innerRoot = inner.getDocumentElement();
                JsonNode innerJson = elementToJsonPlain(innerRoot, mapper.createObjectNode(),
                        path + "/" + localName(innerRoot), opt);
                target.set(localName(innerRoot), innerJson);
                rawText = ""; // consumed
            } catch (Exception ignore) { /* keep as text */ }
        }

        // Leaf
        if (groups.isEmpty()) {
            if (target.size() == 0) {
                return rawText.isEmpty() ? NullNode.instance : new TextNode(rawText);
            } else {
                if (!rawText.isEmpty()) putValue(target, "#text", rawText, opt.coerceNumbers);
                return target;
            }
        }

        // Children: arrays by occurrence
        for (Map.Entry<String, List<Element>> e : groups.entrySet()) {
            String childName = e.getKey();
            List<Element> items = e.getValue();
            if (items.size() == 1) {
                target.set(childName, elementToJsonPlain(items.get(0), mapper.createObjectNode(),
                        path + "/" + childName, opt));
            } else {
                ArrayNode arr = mapper.createArrayNode();
                for (Element ce : items) {
                    arr.add(elementToJsonPlain(ce, mapper.createObjectNode(),
                            path + "/" + childName, opt));
                }
                target.set(childName, arr);
            }
        }

        if (!rawText.isEmpty()) putValue(target, "#text", rawText, opt.coerceNumbers);
        return target;
    }

    private static void putValue(ObjectNode node, String key, String raw, boolean coerceNumbers) {
        if (!coerceNumbers || raw == null) { node.put(key, raw); return; }
        String v = raw.trim();
        if (v.isEmpty()) { node.put(key, v); return; }

        // Integer
        try {
            String n = v.replace(",", "").replace("_", "");
            if (n.matches("^[+-]?\\d+$")) { node.put(key, Long.parseLong(n)); return; }
        } catch (Exception ignore) {}

        // Decimal
        try {
            String n = v.replace(",", "").replace("%","").replace("$","");
            if (n.matches("^[+-]?\\d*(\\.\\d+)?$") && n.matches(".*\\d.*")) {
                node.put(key, new BigDecimal(n));
                return;
            }
        } catch (Exception ignore) {}

        // Boolean
        if ("true".equalsIgnoreCase(v) || "false".equalsIgnoreCase(v)) {
            node.put(key, Boolean.parseBoolean(v));
            return;
        }

        node.put(key, raw); // fallback
    }

    /* ======================= XSD mode ======================= */

    private JsonNode elementToJsonXsd(Element elem,
                                      XSElementDeclaration elemDecl,
                                      XSModel model,
                                      Options opt) {

        // xsi:nil → null
        String xsiNil = elem.getAttributeNS("http://www.w3.org/2001/XMLSchema-instance", "nil");
        if ("true".equalsIgnoreCase(xsiNil)) return NullNode.instance;

        ObjectNode node = mapper.createObjectNode();

        // Attributes typed where declared (XSObjectList)
        XSObjectList attrUses = attributesOf(elemDecl);
        Map<String, XSSimpleTypeDefinition> attrTypeByLocal = new HashMap<>();
        for (int i = 0; i < attrUses.getLength(); i++) {
            XSObject xo = attrUses.item(i);
            if (xo instanceof XSAttributeUse) {
                XSAttributeUse use = (XSAttributeUse) xo;
                XSAttributeDeclaration ad = use.getAttrDeclaration();
                if (ad != null) attrTypeByLocal.put(ad.getName(), ad.getTypeDefinition());
            }
        }
        NamedNodeMap attrs = elem.getAttributes();
        if (attrs != null) {
            for (int i = 0; i < attrs.getLength(); i++) {
                Attr a = (Attr) attrs.item(i);
                if ("nil".equals(a.getLocalName()) &&
                    "http://www.w3.org/2001/XMLSchema-instance".equals(a.getNamespaceURI())) continue;

                String key = a.getName();
                String ln  = (a.getLocalName() != null) ? a.getLocalName() : a.getName();
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

        // Text & children
        String text = collectText(elem).trim();
        Map<String, List<Element>> groups = groupChildren(elem);

        // Inline CDATA/XML (schema-aware) — parse if element's text is an XML doc
        if (opt.parseCdataXml && !text.isEmpty() && looksLikeXml(text)) {
            try {
                Document inner = buildSafeDocument(text);
                Element innerRoot = inner.getDocumentElement();
                XSElementDeclaration innerDecl = null;
                if (elemDecl != null) {
                    ParticleInfo piInner = findChild(elemDecl, localName(innerRoot));
                    innerDecl = (piInner != null) ? piInner.decl : null;
                }
                node.set(localName(innerRoot), elementToJsonXsd(innerRoot, innerDecl, model, opt));
                text = "";
            } catch (Exception ignore) {}
        }

        // Leaf / simple content by schema
        if (groups.isEmpty()) {
            XSSimpleTypeDefinition st = simpleTypeOf(elemDecl);
            if (st != null) return coerceByXsd(text, st);

            if (node.size() == 0) {
                return text.isEmpty() ? NullNode.instance : new TextNode(text);
            } else {
                if (!text.isEmpty()) node.put("#text", text);
                return node;
            }
        }

        // Children → array decided by maxOccurs (not by count)
        for (Map.Entry<String, List<Element>> e : groups.entrySet()) {
            String childLocal = e.getKey();
            List<Element> items = e.getValue();

            ParticleInfo pi = findChild(elemDecl, childLocal);
            XSElementDeclaration childDecl = (pi != null) ? pi.decl : null;
            boolean forceArray = isArray(pi);

            if (forceArray) {
                ArrayNode arr = mapper.createArrayNode();
                for (Element ce : items) {
                    arr.add(elementToJsonXsd(ce, childDecl, model, opt));
                }
                node.set(childLocal, arr);
            } else {
                node.set(childLocal, elementToJsonXsd(items.get(0), childDecl, model, opt));
            }
        }

        // Optionally emit [] for schema arrays missing in XML
        if (opt.emitEmptyArraysFromXsd && elemDecl != null) {
            XSTypeDefinition t = elemDecl.getTypeDefinition();
            if (t instanceof XSComplexTypeDefinition) {
                XSParticle p = ((XSComplexTypeDefinition) t).getParticle();
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
            for (int i = 0; i < parts.getLength(); i++) {
                addMissingArraysFromParticle(node, (XSParticle) parts.item(i));
            }
        }
    }

    /* ======================= Shared helpers ======================= */

    private static boolean isXsiNil(Attr a) {
        return "nil".equals(a.getLocalName())
                && "http://www.w3.org/2001/XMLSchema-instance".equals(a.getNamespaceURI());
    }

    private static Map<String, List<Element>> groupChildren(Element elem) {
        Map<String, List<Element>> g = new LinkedHashMap<>();
        NodeList nl = elem.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n instanceof Element) {
                String ln = localName((Element) n);
                g.computeIfAbsent(ln, k -> new ArrayList<>()).add((Element) n);
            }
        }
        return g;
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

    private static Document buildSafeDocument(String xml) throws Exception {
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

    private static String localName(Element e) {
        return (e.getLocalName()!=null) ? e.getLocalName() : e.getNodeName();
    }

    private static String safeKey(ObjectNode node, String key) {
        return node.has(key) ? "_" + key : key;
    }

    /* ======================= XSD loading & queries ======================= */

    /** Accepts bare "xsd/app.xsd", "classpath:/xsd/app.xsd", or "file:/abs/path/app.xsd". */
    private static String normalizeLocation(String loc) {
        if (loc == null || loc.isBlank()) return loc;
        if (loc.startsWith("classpath:") || loc.startsWith("file:")) return loc;
        return "classpath:" + (loc.startsWith("/") ? loc : "/" + loc);
    }

    private XSModel loadXsModel(String location) throws Exception {
        if (location == null || location.isBlank())
            throw new IllegalArgumentException("xsdLocation must be provided when useXsd=true");

        // Tell DOM registry to use Xerces XS impl
        System.setProperty(DOMImplementationRegistry.PROPERTY,
                "org.apache.xerces.dom.DOMXSImplementationSourceImpl");

        DOMImplementationRegistry registry = DOMImplementationRegistry.newInstance();
        DOMImplementation impl = registry.getDOMImplementation("XS-Loader");
        if (!(impl instanceof XSImplementation)) {
            throw new IllegalStateException("XS-Loader not available; ensure xercesImpl on classpath (exclude xml-apis).");
        }

        XSImplementation xsImpl = (XSImplementation) impl;
        XSLoader loader = xsImpl.createXSLoader(null);

        byte[] bytes = readAll(resolve(location));
        DOMInputImpl in = new DOMInputImpl();
        in.setStringData(new String(bytes, StandardCharsets.UTF_8));
        XSModel model = loader.load(in);
        if (model == null) throw new IllegalArgumentException("Failed to load XSD: " + location);
        return model;
    }

    private InputStream resolve(String location) throws Exception {
        if (location.startsWith("classpath:")) {
            String p = location.substring("classpath:".length());
            InputStream in = XmlToJsonUnifiedService.class.getResourceAsStream(p.startsWith("/") ? p : "/" + p);
            if (in == null) throw new IllegalArgumentException("Classpath resource not found: " + location);
            return in;
        }
        if (location.startsWith("file:")) {
            java.net.URI uri = java.net.URI.create(location);
            return java.nio.file.Files.newInputStream(java.nio.file.Paths.get(uri));
        }
        // default → classpath
        InputStream in = XmlToJsonUnifiedService.class.getResourceAsStream(location.startsWith("/") ? location : "/" + location);
        if (in == null) throw new IllegalArgumentException("Classpath resource not found: " + location);
        return in;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try (in) { return in.readAllBytes(); }
    }

    private XSElementDeclaration lookupRootDecl(XSModel model, Element root) {
        String ln = localName(root);
        String ns = root.getNamespaceURI();
        XSElementDeclaration decl = model.getElementDeclaration(ln, ns == null ? "" : ns);
        if (decl == null) decl = model.getElementDeclaration(ln, "");
        return decl;
    }

    private XSObjectList attributesOf(XSElementDeclaration decl) {
        if (decl == null) return emptyObjectList();
        XSTypeDefinition t = decl.getTypeDefinition();
        if (t instanceof XSComplexTypeDefinition) {
            return ((XSComplexTypeDefinition) t).getAttributeUses(); // XSObjectList in many Xerces builds
        }
        return emptyObjectList();
    }

    private ParticleInfo findChild(XSElementDeclaration parent, String childLocal) {
        if (parent == null) return null;
        XSTypeDefinition t = parent.getTypeDefinition();
        if (!(t instanceof XSComplexTypeDefinition)) return null;
        XSParticle p = ((XSComplexTypeDefinition) t).getParticle();
        if (p == null) return null;
        return walkParticleForChild(p, childLocal);
    }

    private ParticleInfo walkParticleForChild(XSParticle particle, String wanted) {
        XSTerm term = particle.getTerm();
        if (term instanceof XSElementDeclaration) {
            XSElementDeclaration el = (XSElementDeclaration) term;
            if (wanted.equals(el.getName())) {
                return new ParticleInfo(el, particle.getMinOccurs(),
                        particle.getMaxOccursUnbounded() ? Integer.MAX_VALUE : particle.getMaxOccurs());
            }
        } else if (term instanceof XSModelGroup) {
            XSObjectList parts = ((XSModelGroup) term).getParticles();
            for (int i = 0; i < parts.getLength(); i++) {
                ParticleInfo got = walkParticleForChild((XSParticle) parts.item(i), wanted);
                if (got != null) return got;
            }
        }
        return null;
    }

    private static boolean isArray(ParticleInfo pi) {
        return pi != null && (pi.maxOccurs == Integer.MAX_VALUE || pi.maxOccurs > 1);
    }

    private static XSObjectList emptyObjectList() {
        return new XSObjectList() {
            @Override public int getLength() { return 0; }
            @Override public XSObject item(int index) { return null; }
        };
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
        String v = (value == null) ? "" : value.trim();

        if (t.getVariety() == XSSimpleTypeDefinition.VARIETY_LIST) {
            ArrayNode arr = new ObjectMapper().createArrayNode();
            for (String s : v.split("\\s+")) if (!s.isEmpty()) arr.add(s);
            return arr;
        }

        short kind;
        XSSimpleTypeDefinition prim = t.getPrimitiveType();
        kind = (prim != null) ? prim.getBuiltInKind() : t.getBuiltInKind();

        try {
            switch (kind) {
                case XSConstants.BOOLEAN_DT:
                    if ("1".equals(v) || "true".equalsIgnoreCase(v))  return BooleanNode.TRUE;
                    if ("0".equals(v) || "false".equalsIgnoreCase(v)) return BooleanNode.FALSE;
                    return new TextNode(value);

                case XSConstants.INTEGER_DT:
                case XSConstants.LONG_DT:
                case XSConstants.INT_DT:
                case XSConstants.SHORT_DT:
                case XSConstants.BYTE_DT:
                    v = v.replace(",", "");
                    return LongNode.valueOf(Long.parseLong(v));

                case XSConstants.DECIMAL_DT:
                case XSConstants.DOUBLE_DT:
                case XSConstants.FLOAT_DT:
                    v = v.replace(",", "").replace("%", "");
                    return DecimalNode.valueOf(new BigDecimal(v));

                default:
                    return new TextNode(value);
            }
        } catch (Exception ignore) {
            return new TextNode(value);
        }
    }

    /* tiny type */
    private static final class ParticleInfo {
        final XSElementDeclaration decl;
        final int minOccurs;
        final int maxOccurs;
        ParticleInfo(XSElementDeclaration decl, int minOccurs, int maxOccurs) {
            this.decl = decl; this.minOccurs = minOccurs; this.maxOccurs = maxOccurs;
        }
    }

    /* ===== Small DOM helpers ===== */

    private static Optional<Element> firstByLocalName(Element p, String ln) {
        NodeList nl = p.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
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
        for (int i = 0; i < nl.getLength(); i++) {
            if (nl.item(i) instanceof Element) return Optional.of((Element) nl.item(i));
        }
        return Optional.empty();
    }
}
