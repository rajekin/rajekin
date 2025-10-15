package com.raj.utilities.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import org.apache.xerces.dom.DOMInputImpl;
import org.apache.xerces.xs.*;
import org.w3c.dom.*;
import org.w3c.dom.bootstrap.DOMImplementationRegistry;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * ONE service to handle XML/SOAP → JSON with optional XSD guidance.
 * - In plain mode: arrays by occurrence, robust number coercion.
 * - In XSD mode: arrays/types by schema; ignores number coercion flag (schema decides).
 * - Unwrap SOAP Envelope/Body, parse XML embedded in CDATA (top-level + inline), lowercase root, emit empty arrays.
 */
public class XmlToJsonUnifiedService {

    /* ======================= Options & Result ======================= */

    public static final class Options {
        public boolean unwrapSoapBody = true;          // Envelope/Body → payload
        public boolean parseCdataXml = true;           // promote/parse XML from CDATA/Text
        public boolean flattenAttributes = true;       // attributes as sibling props (vs @attributes)
        public boolean coerceNumbers = true;           // plain mode only (schema mode uses XSD types)
        public boolean lowercaseRoot = false;          // Application → application
        public boolean useXsd = false;                 // turn on schema-aware conversion
        public String  xsdLocation = null;             // "classpath:/xsd/app.xsd" or "file:/path/app.xsd"
        public boolean emitEmptyArraysFromXsd = false; // materialize [] for schema arrays missing in XML
    }

    public static final class Result {
        public final String prettyJson;
        public Result(String prettyJson) { this.prettyJson = prettyJson; }
    }

    private final ObjectMapper mapper = new ObjectMapper();

    /* ======================= Public API ======================= */

    /**
     * Convert XML/SOAP into JSON, optionally using an XSD from classpath/file.
     * @param xml UTF-8 XML text
     * @param opt options
     */
    public Result convert(String xml, Options opt) throws Exception {
        if (xml == null || xml.isBlank()) throw new IllegalArgumentException("XML input is empty.");
        if (opt == null) opt = new Options();

        // Parse XML safely
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

        // Unwrap SOAP
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
            // -------------------- PLAIN MODE --------------------
            Set<String> xmlAttrPaths = new LinkedHashSet<>();
            Set<String> jsonAttrPaths = new LinkedHashSet<>();

            JsonNode payload = elementToJsonPlain(root, mapper.createObjectNode(), "/" + outRoot,
                    xmlAttrPaths, jsonAttrPaths, opt);

            out.set(outRoot, payload);
        } else {
            // -------------------- SCHEMA MODE --------------------
            XSModel xsdModel = loadXsModel(opt.xsdLocation);
            XSElementDeclaration rootDecl = lookupRootDecl(xsdModel, root);

            JsonNode payload = elementToJsonXsd(root, rootDecl, xsdModel, opt);
            out.set(outRoot, payload);
        }

        String pretty = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(out);
        return new Result(pretty);
    }

    /* ======================= Plain mode ======================= */

    private JsonNode elementToJsonPlain(Element elem, ObjectNode target, String path,
                                        Set<String> xmlAttrPaths, Set<String> jsonAttrPaths,
                                        Options opt) throws Exception {

        // Attributes
        NamedNodeMap attrs = elem.getAttributes();
        if (attrs != null && attrs.getLength() > 0) {
            for (int i = 0; i < attrs.getLength(); i++) {
                Attr a = (Attr) attrs.item(i);
                if (isXsiNil(a)) continue;

                String key = a.getName();
                String val = a.getValue();
                if (opt.flattenAttributes) {
                    String slot = target.has(key) ? "_" + key : key;
                    putValue(target, slot, val, opt.coerceNumbers);
                } else {
                    ObjectNode at = (ObjectNode) target.get("@attributes");
                    if (at == null) at = target.putObject("@attributes");
                    putValue(at, key, val, opt.coerceNumbers);
                }
                String attrPath = path + "/@" + key;
                xmlAttrPaths.add(attrPath);
                jsonAttrPaths.add(attrPath);
            }
        }

        // Children & text
        Map<String, List<Element>> groups = groupChildren(elem);
        String rawText = collectText(elem);
        String trimmed = rawText.trim();

        // Inline fragment parsing (only in plain mode)
        if (opt.parseCdataXml && looksLikeXml(trimmed)) {
            try {
                Document inner = buildSafeDocument(trimmed);
                Element innerRoot = inner.getDocumentElement();
                JsonNode innerJson = elementToJsonPlain(innerRoot, mapper.createObjectNode(),
                        path + "/" + localName(innerRoot), xmlAttrPaths, jsonAttrPaths, opt);
                target.set(localName(innerRoot), innerJson);
            } catch (Exception ignore) { /* keep as text */ }
        }

        // Leaf
        if (groups.isEmpty()) {
            if (target.size() == 0) {
                if (trimmed.isEmpty()) return NullNode.instance;
                return new TextNode(trimmed);
            } else {
                if (!trimmed.isEmpty()) putValue(target, "#text", trimmed, opt.coerceNumbers);
                return target;
            }
        }

        // Children groups (arrays by occurrence)
        for (Map.Entry<String, List<Element>> e : groups.entrySet()) {
            String childName = e.getKey();
            List<Element> items = e.getValue();
            if (items.size() == 1) {
                target.set(childName, elementToJsonPlain(items.get(0), mapper.createObjectNode(),
                        path + "/" + childName, xmlAttrPaths, jsonAttrPaths, opt));
            } else {
                ArrayNode arr = mapper.createArrayNode();
                int idx = 0;
                for (Element ce : items) {
                    arr.add(elementToJsonPlain(ce, mapper.createObjectNode(),
                            path + "/" + childName + "[" + (idx++) + "]",
                            xmlAttrPaths, jsonAttrPaths, opt));
                }
                target.set(childName, arr);
            }
        }

        if (!trimmed.isEmpty()) putValue(target, "#text", trimmed, opt.coerceNumbers);
        return target;
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

        // Inline CDATA/XML parsing (schema-aware)
        if (opt.parseCdataXml && !text.isEmpty() && looksLikeXml(text)) {
            try {
                Document inner = buildSafeDocument(text);
                Element innerRoot = inner.getDocumentElement();
                // Try to bind inner root to a child declaration
                XSElementDeclaration innerDecl = null;
                if (elemDecl != null) {
                    ParticleInfo piInner = findChild(elemDecl, localName(innerRoot));
                    innerDecl = (piInner != null) ? piInner.decl : null;
                }
                node.set(localName(innerRoot), elementToJsonXsd(innerRoot, innerDecl, model, opt));
                text = "";
            } catch (Exception ignore) {
                // fallback to regular handling
            }
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

    private static boolean looksLikeXml(String s) { return s.startsWith("<") && s.endsWith(">") && s.contains("</"); }

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

    private static String localName(Element e) { return (e.getLocalName()!=null) ? e.getLocalName() : e.getNodeName(); }

    /* ======================= XSD loading & queries ======================= */

    private XSModel loadXsModel(String location) throws Exception {
        // Prepare DOM XS loader
        System.setProperty(DOMImplementationRegistry.PROPERTY,
                "org.apache.xerces.dom.DOMXSImplementationSourceImpl");
        DOMImplementationRegistry registry = DOMImplementationRegistry.newInstance();
        DOMImplementation impl = registry.getDOMImplementation("XS-Loader");
        if (!(impl instanceof XSImplementation)) {
            throw new IllegalStateException("XS-Loader not available; ensure xercesImpl is on the classpath (exclude xml-apis).");
        }
        XSImplementation xsImpl = (XSImplementation) impl;
        XSLoader loader = xsImpl.createXSLoader(null);

        // Load XSD bytes from classpath or file
        byte[] bytes = readAll(resolve(location));
        DOMInputImpl in = new DOMInputImpl();
        in.setStringData(new String(bytes, StandardCharsets.UTF_8));
        XSModel model = loader.load(in);
        if (model == null) throw new IllegalArgumentException("Failed to load XSD: " + location);
        return model;
    }

    private InputStream resolve(String location) throws Exception {
        if (location == null || location.isBlank())
            throw new IllegalArgumentException("xsdLocation must be provided when useXsd=true");
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
        // default to classpath:
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
            return ((XSComplexTypeDefinition) t).getAttributeUses(); // many Xerces builds expose XSObjectList here
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

    /* === tiny types === */
    private static final class ParticleInfo {
        final XSElementDeclaration decl;
        final int minOccurs;
        final int maxOccurs;
        ParticleInfo(XSElementDeclaration decl, int minOccurs, int maxOccurs) {
            this.decl = decl; this.minOccurs = minOccurs; this.maxOccurs = maxOccurs;
        }
    }
}
***************

    package com.raj.utilities.web;

import com.raj.utilities.service.XmlToJsonUnifiedService;
import com.raj.utilities.service.XmlToJsonUnifiedService.Options;
import com.raj.utilities.service.XmlToJsonUnifiedService.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api/xml-to-json")
public class XmlToJsonApi {

    private final XmlToJsonUnifiedService svc = new XmlToJsonUnifiedService();

    @Value("${raj.xml2json.xsd:classpath:/xsd/application.xsd}")
    private String defaultXsdLocation;

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
        @RequestParam(name="useXsd",            defaultValue="false") boolean useXsd,
        @RequestParam(name="xsdPath",           required = false)     String xsdPath,
        @RequestParam(name="emitEmptyArraysFromXsd", defaultValue="false") boolean emitEmptyArraysFromXsd
    ) throws Exception {

        String xml = new String(body, StandardCharsets.UTF_8);

        Options opt = new Options();
        opt.unwrapSoapBody = unwrapSoapBody;
        opt.parseCdataXml = parseCdataXml;
        opt.flattenAttributes = flattenAttributes;
        opt.coerceNumbers = coerceNumbers;     // ignored in XSD mode (schema decides)
        opt.lowercaseRoot = lowercaseRoot;
        opt.useXsd = useXsd;
        opt.xsdLocation = (xsdPath != null && !xsdPath.isBlank()) ? xsdPath : defaultXsdLocation;
        opt.emitEmptyArraysFromXsd = emitEmptyArraysFromXsd;

        Result res = svc.convert(xml, opt);

        // Plain mode had attribute stats in earlier iterations. This unified service focuses on output JSON.
        return Map.of(
            "json", res.prettyJson,
            "schemaMode", useXsd
        );
    }
}
***********************
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
      <!-- Plain/XSD-common options -->
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
      <label class="switch" title="Try to convert numeric-looking strings to numbers (plain mode)">
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
        <div style="margin-top:10px;display:flex;gap:8px">
          <button id="copyBtn" class="btn secondary" title="Copy JSON to clipboard">Copy JSON</button>
        </div>
      </div>
    </div>
  </div>
</div>

<script>
  const $ = (id) => document.getElementById(id);
  const xmlEl = $("xml"), jsonEl = $("json"), statusEl = $("status");
  const btn = $("convertBtn");

  const unwrapEl = $("unwrap"), parseCdataEl = $("parseCdata"), flattenEl = $("flatten"),
        coerceEl = $("coerce"), lowerRootEl = $("lowerRoot"),
        useXsdEl = $("useXsd"), xsdPathEl = $("xsdPath"), emitEmptyEl = $("emitEmpty");

  $("clearBtn").addEventListener("click", () => {
    xmlEl.value = ""; jsonEl.textContent = "/* JSON will appear here */";
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

  async function convert() {
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

    
