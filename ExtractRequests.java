package com.raj.utilities.service.xsd;

import org.apache.xerces.dom.DOMInputImpl;
import org.apache.xerces.xs.*;
import org.w3c.dom.DOMImplementation;
import org.w3c.dom.bootstrap.DOMImplementationRegistry;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Loads an XSD into XSModel and provides helpers:
 *  - getGlobalElement
 *  - findChild (returns min/maxOccurs)
 *  - valueKind classification (portable; no getPrimitiveKind())
 *  - attributesOf (returns a SAFE empty XSAttributeUseList when none)
 */
public final class XsdSchemaModel {

    private final XSModel model;

    private XsdSchemaModel(XSModel model) { this.model = model; }

    /** Load an XSD from bytes using Xerces XS loader. */
    public static XsdSchemaModel fromBytes(byte[] xsdBytes) throws Exception {
        System.setProperty(DOMImplementationRegistry.PROPERTY,
                "org.apache.xerces.dom.DOMXSImplementationSourceImpl");

        DOMImplementationRegistry registry = DOMImplementationRegistry.newInstance();
        DOMImplementation impl = registry.getDOMImplementation("XS-Loader");
        if (!(impl instanceof XSImplementation)) {
            throw new IllegalStateException("XS-Loader not available; ensure xercesImpl is on the classpath.");
        }
        XSImplementation xsImpl = (XSImplementation) impl;
        XSLoader loader = xsImpl.createXSLoader(null);

        DOMInputImpl in = new DOMInputImpl();
        in.setStringData(new String(xsdBytes, StandardCharsets.UTF_8));

        XSModel model = loader.load(in);
        if (model == null) throw new IllegalArgumentException("Failed to load XSD.");
        return new XsdSchemaModel(model);
    }

    /** Lookup a global element by local name + namespace (use "" for no namespace). */
    public XSElementDeclaration getGlobalElement(String localName, String ns) {
        return model.getElementDeclaration(localName, ns == null ? "" : ns);
    }

    /** Find a (possibly nested) child element particle by local name under a parent element. */
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
                return Optional.of(new ParticleInfo(
                        el,
                        particle.getMinOccurs(),
                        particle.getMaxOccursUnbounded() ? Integer.MAX_VALUE : particle.getMaxOccurs()
                ));
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

    /** Coarse value kind for an element/type (STRING, INTEGER, DECIMAL, FLOATING, BOOLEAN, LIST, OBJECT). */
    public ValueKind valueKind(XSElementDeclaration decl) {
        if (decl == null) return ValueKind.STRING;
        return valueKind(decl.getTypeDefinition());
    }

    public ValueKind valueKind(XSTypeDefinition t) {
        if (t instanceof XSSimpleTypeDefinition) {
            XSSimpleTypeDefinition s = (XSSimpleTypeDefinition) t;

            if (s.getVariety() == XSSimpleTypeDefinition.VARIETY_LIST) return ValueKind.LIST;

            short kind = primitiveKind(s); // portable (no getPrimitiveKind())
            switch (kind) {
                case XSConstants.BOOLEAN_DT:  return ValueKind.BOOLEAN;
                case XSConstants.DECIMAL_DT:  return ValueKind.DECIMAL;
                case XSConstants.DOUBLE_DT:
                case XSConstants.FLOAT_DT:    return ValueKind.FLOATING;
                case XSConstants.INTEGER_DT:
                case XSConstants.LONG_DT:
                case XSConstants.INT_DT:
                case XSConstants.SHORT_DT:
                case XSConstants.BYTE_DT:     return ValueKind.INTEGER;
                default:                      return ValueKind.STRING;
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

    /** Attribute list for a complex element type (never null; returns a SAFE empty list). */
    public XSAttributeUseList attributesOf(XSElementDeclaration decl) {
        if (decl == null) return emptyAttrUseList();
        XSTypeDefinition t = decl.getTypeDefinition();
        if (t instanceof XSComplexTypeDefinition) {
            return ((XSComplexTypeDefinition) t).getAttributeUses();
        }
        return emptyAttrUseList();
    }

    /** Unbounded or >1 counts as an array. */
    public static boolean particleIsArray(ParticleInfo pi) {
        return pi != null && (pi.maxOccurs == Integer.MAX_VALUE || pi.maxOccurs > 1);
    }

    /** Portable primitive kind without using getPrimitiveKind(). */
    private static short primitiveKind(XSSimpleTypeDefinition s) {
        XSSimpleTypeDefinition p = s.getPrimitiveType();
        return (p != null) ? p.getBuiltInKind() : s.getBuiltInKind();
    }

    /** Safe empty XSAttributeUseList (avoids referencing XSAttributeUseListImpl). */
    private static XSAttributeUseList emptyAttrUseList() {
        return new XSAttributeUseList() {
            @Override public int getLength() { return 0; }
            @Override public XSAttributeUse item(int index) { return null; }
        };
    }

    /* === Types === */

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


*********************

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

        // xsi:nil="true" → null
        String xsiNil = elem.getAttributeNS("http://www.w3.org/2001/XMLSchema-instance", "nil");
        if ("true".equalsIgnoreCase(xsiNil)) return NullNode.instance;

        ObjectNode node = mapper.createObjectNode();

        // Attributes (typed where declared)
        XSAttributeUseList attrUses = (elemDecl != null) ? xsd.attributesOf(elemDecl) : emptyAttrUseList();
        Map<String, XSSimpleTypeDefinition> attrTypeByLocal = new HashMap<>();
        for (int i = 0; i < attrUses.getLength(); i++) {
            XSAttributeUse use = attrUses.item(i);
            if (use == null) continue;
            XSAttributeDeclaration ad = use.getAttrDeclaration();
            if (ad != null) attrTypeByLocal.put(ad.getName(), ad.getTypeDefinition());
        }

        NamedNodeMap attrs = elem.getAttributes();
        if (attrs != null) {
            for (int i = 0; i < attrs.getLength(); i++) {
                Attr a = (Attr) attrs.item(i);
                if ("nil".equals(a.getLocalName()) &&
                    "http://www.w3.org/2001/XMLSchema-instance".equals(a.getNamespaceURI())) continue;

                String key = a.getName();
                String ln = (a.getLocalName() != null) ? a.getLocalName() : a.getName();
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
                if (st != null) return coerceByXsd(text, st);
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
            boolean forceArray = XsdSchemaModel.particleIsArray(pi); // <— fully-qualified (no static import needed)

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
            for (int i = 0; i < parts.getLength(); i++) {
                addMissingArraysFromParticle(node, (XSParticle) parts.item(i));
            }
        }
    }

    /* ================= helpers ================= */

    private static XSAttributeUseList emptyAttrUseList() {
        return new XSAttributeUseList() {
            @Override public int getLength() { return 0; }
            @Override public XSAttributeUse item(int index) { return null; }
        };
    }

    private static String safeKey(ObjectNode node, String key) {
        return node.has(key) ? "_" + key : key;
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

        // Primitive via primitiveType/builtInKind (no getPrimitiveKind())
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
}
