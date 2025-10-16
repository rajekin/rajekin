package com.raj.utilities.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import net.sf.saxon.jaxp.SaxonTransformerFactory;
import org.springframework.stereotype.Service;
import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class XmlToJsonUnifiedService {

    public static final class Options {
        // pre-processing
        public boolean unwrapSoapBody = true;
        public boolean parseCdataXml = true;
        public boolean lowercaseRoot = false;

        // attribute & text handling (for fallback XML->JSON path)
        public boolean flattenAttributes = true;
        public boolean coerceNumbers = true;

        // XSLT mode
        public boolean useXslt = false;
        public String  xsltLocation = null;           // classpath:/xslt/xml-to-json.xsl or file:/...
        public boolean xsltOutputIsJson = true;       // true: stylesheet emits JSON text; false: emits XML we will convert
        public Map<String,String> xsltParams = Map.of(); // optional named params
    }

    public static final class Result {
        public final String prettyJson;
        public Result(String prettyJson) { this.prettyJson = prettyJson; }
    }

    private final ObjectMapper mapper = new ObjectMapper();

    public Result convert(String xml, Options opt) throws Exception {
        if (xml == null || xml.isBlank()) throw new IllegalArgumentException("XML input is empty.");
        if (opt == null) opt = new Options();

        // Parse original XML once (securely), we may reuse it
        Document doc = buildSafeDocument(xml);
        Element root = doc.getDocumentElement();

        // Unwrap SOAP Envelope/Body if requested
        if (opt.unwrapSoapBody && localName(root).equalsIgnoreCase("Envelope")) {
            Optional<Element> body = firstByLocalName(root, "Body");
            if (body.isPresent()) {
                Optional<Element> payload = firstElementChild(body.get());
                if (payload.isPresent()) root = payload.get();
            }
        }

        // Promote top-level CDATA containing XML
        if (opt.parseCdataXml) {
            String inner = collectText(root).trim();
            if (looksLikeXml(inner)) {
                Document innerDoc = buildSafeDocument(inner);
                root = innerDoc.getDocumentElement();
            }
        }

        if (opt.useXslt) {
            // Run XSLT against the (possibly unwrapped) fragment
            String sourceXml = serializeElement(root);
            String xsltOut = applyXslt(sourceXml, normalizeLocation(opt.xsltLocation), opt.xsltParams);

            if (opt.xsltOutputIsJson) {
                // Pretty-print JSON text returned by the stylesheet
                JsonNode n = mapper.readTree(xsltOut);
                return new Result(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(n));
            } else {
                // Stylesheet produced XML → convert to JSON using plain path
                Document outDoc = buildSafeDocument(xsltOut);
                Element outRoot = outDoc.getDocumentElement();
                String finalRoot = opt.lowercaseRoot ? localName(outRoot).toLowerCase(Locale.ROOT) : localName(outRoot);
                ObjectNode wrapper = mapper.createObjectNode();
                JsonNode payload = elementToJsonPlain(outRoot, mapper.createObjectNode(), "/" + finalRoot, opt);
                wrapper.set(finalRoot, payload);
                return new Result(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(wrapper));
            }
        }

        // No XSLT: do regular XML->JSON (plain) on the (possibly unwrapped) root
        String outRootName = opt.lowercaseRoot ? localName(root).toLowerCase(Locale.ROOT) : localName(root);
        ObjectNode out = mapper.createObjectNode();
        out.set(outRootName, elementToJsonPlain(root, mapper.createObjectNode(), "/" + outRootName, opt));
        return new Result(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(out));
    }

    /* ===================== XSLT ===================== */

    private String applyXslt(String xml, String xsltLocation, Map<String,String> params) throws Exception {
        if (xsltLocation == null || xsltLocation.isBlank())
            throw new IllegalArgumentException("xsltLocation must be provided when useXslt=true");

        // Saxon TransformerFactory
        SaxonTransformerFactory tf = new SaxonTransformerFactory();
        Source xslt = new StreamSource(resolve(xsltLocation));
        Transformer t = tf.newTransformer(xslt);

        if (params != null) {
            for (Map.Entry<String,String> e : params.entrySet()) {
                t.setParameter(e.getKey(), e.getValue());
            }
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        t.transform(new StreamSource(new StringReader(xml)), new StreamResult(baos));
        return baos.toString(StandardCharsets.UTF_8);
    }

    private static String normalizeLocation(String loc) {
        if (loc == null || loc.isBlank()) return loc;
        if (loc.startsWith("classpath:") || loc.startsWith("file:")) return loc;
        return "classpath:" + (loc.startsWith("/") ? loc : "/" + loc);
    }

    private InputStream resolve(String location) throws Exception {
        if (location.startsWith("classpath:")) {
            String p = location.substring("classpath:".length());
            InputStream in = XmlToJsonUnifiedService.class.getResourceAsStream(p.startsWith("/") ? p : "/" + p);
            if (in == null) throw new IllegalArgumentException("Classpath resource not found: " + location);
            return in;
        }
        if (location.startsWith("file:")) {
            var uri = java.net.URI.create(location);
            return java.nio.file.Files.newInputStream(java.nio.file.Paths.get(uri));
        }
        InputStream in = XmlToJsonUnifiedService.class.getResourceAsStream(location.startsWith("/") ? location : "/" + location);
        if (in == null) throw new IllegalArgumentException("Classpath resource not found: " + location);
        return in;
    }

    /* ===================== Plain XML → JSON (fallback or no-XSLT path) ===================== */

    private JsonNode elementToJsonPlain(Element elem, ObjectNode target, String path, Options opt) throws Exception {
        // attributes
        NamedNodeMap attrs = elem.getAttributes();
        if (attrs != null && attrs.getLength() > 0) {
            for (int i = 0; i < attrs.getLength(); i++) {
                Attr a = (Attr) attrs.item(i);
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

        Map<String, List<Element>> groups = groupChildren(elem);
        String rawText = collectText(elem).trim();

        // inline CDATA that is XML
        if (opt.parseCdataXml && looksLikeXml(rawText)) {
            try {
                Document inner = buildSafeDocument(rawText);
                Element innerRoot = inner.getDocumentElement();
                JsonNode innerJson = elementToJsonPlain(innerRoot, mapper.createObjectNode(),
                        path + "/" + localName(innerRoot), opt);
                target.set(localName(innerRoot), innerJson);
                rawText = "";
            } catch (Exception ignore) {}
        }

        if (groups.isEmpty()) {
            if (target.size() == 0) {
                return rawText.isEmpty() ? NullNode.instance : new TextNode(rawText);
            } else {
                if (!rawText.isEmpty()) putValue(target, "#text", rawText, opt.coerceNumbers);
                return target;
            }
        }

        // arrays by occurrence (plain policy)
        for (Map.Entry<String, List<Element>> e : groups.entrySet()) {
            String child = e.getKey();
            List<Element> items = e.getValue();
            if (items.size() == 1) {
                target.set(child, elementToJsonPlain(items.get(0), mapper.createObjectNode(),
                        path + "/" + child, opt));
            } else {
                ArrayNode arr = mapper.createArrayNode();
                for (Element ce : items) {
                    arr.add(elementToJsonPlain(ce, mapper.createObjectNode(),
                            path + "/" + child, opt));
                }
                target.set(child, arr);
            }
        }

        if (!rawText.isEmpty()) putValue(target, "#text", rawText, opt.coerceNumbers);
        return target;
    }

    private static void putValue(ObjectNode node, String key, String raw, boolean coerceNumbers) {
        if (!coerceNumbers || raw == null) { node.put(key, raw); return; }
        String v = raw.trim();
        if (v.isEmpty()) { node.put(key, v); return; }

        try { // integer
            String n = v.replace(",", "").replace("_", "");
            if (n.matches("^[+-]?\\d+$")) { node.put(key, Long.parseLong(n)); return; }
        } catch (Exception ignore) {}

        try { // decimal
            String n = v.replace(",", "").replace("%","").replace("$","");
            if (n.matches("^[+-]?\\d*(\\.\\d+)?$") && n.matches(".*\\d.*")) {
                node.put(key, new BigDecimal(n));
                return;
            }
        } catch (Exception ignore) {}

        if ("true".equalsIgnoreCase(v) || "false".equalsIgnoreCase(v)) { // boolean
            node.put(key, Boolean.parseBoolean(v));
            return;
        }
        node.put(key, raw);
    }

    /* ===================== DOM helpers ===================== */

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

    private static String serializeElement(Element elem) throws Exception {
        // simple DOM -> string (UTF-8), without external libs
        StringWriter sw = new StringWriter();
        javax.xml.transform.Transformer id = javax.xml.transform.TransformerFactory.newInstance().newTransformer();
        id.setOutputProperty("omit-xml-declaration", "yes");
        id.transform(new javax.xml.transform.dom.DOMSource(elem), new javax.xml.transform.stream.StreamResult(sw));
        return sw.toString();
    }

    private static String localName(Element e) {
        return (e.getLocalName()!=null) ? e.getLocalName() : e.getNodeName();
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
**********************************************************************************


  package com.raj.utilities.web;

import com.raj.utilities.service.XmlToJsonUnifiedService;
import com.raj.utilities.service.XmlToJsonUnifiedService.Options;
import com.raj.utilities.service.XmlToJsonUnifiedService.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/xml-to-json")
public class XmlToJsonController {

    private final XmlToJsonUnifiedService converter;

    public XmlToJsonController(XmlToJsonUnifiedService converter) {
        this.converter = converter;
    }

    @Value("${raj.xml2json.xslt:classpath:/xslt/xml-to-json.xsl}")
    private String defaultXslt;

    @PostMapping(
        consumes = {
            MediaType.TEXT_PLAIN_VALUE,
            MediaType.APPLICATION_XML_VALUE,
            MediaType.TEXT_XML_VALUE,
            MediaType.APPLICATION_OCTET_STREAM_VALUE
        },
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<Map<String, Object>> convert(
        @RequestBody byte[] body,

        // pre-processing
        @RequestParam(name = "unwrapSoapBody",    defaultValue = "true")  boolean unwrapSoapBody,
        @RequestParam(name = "parseCdataXml",     defaultValue = "true")  boolean parseCdataXml,
        @RequestParam(name = "lowercaseRoot",     defaultValue = "false") boolean lowercaseRoot,

        // attributes / numbers for fallback XML->JSON
        @RequestParam(name = "flattenAttributes", defaultValue = "true")  boolean flattenAttributes,
        @RequestParam(name = "coerceNumbers",     defaultValue = "true")  boolean coerceNumbers,

        // XSLT
        @RequestParam(name = "useXslt",           defaultValue = "true")  boolean useXslt,
        @RequestParam(name = "xsltPath",          required = false)       String xsltPath,
        @RequestParam(name = "xsltOutputIsJson",  defaultValue = "true")  boolean xsltOutputIsJson
    ) throws Exception {

        String xml = new String(body, StandardCharsets.UTF_8);

        Options opt = new Options();
        opt.unwrapSoapBody = unwrapSoapBody;
        opt.parseCdataXml = parseCdataXml;
        opt.lowercaseRoot = lowercaseRoot;

        opt.flattenAttributes = flattenAttributes;
        opt.coerceNumbers = coerceNumbers;

        opt.useXslt = useXslt;
        opt.xsltLocation = (xsltPath != null && !xsltPath.isBlank()) ? xsltPath : defaultXslt;
        opt.xsltOutputIsJson = xsltOutputIsJson;

        // OPTIONAL: capture any request params prefixed with "p." as XSLT params
        Map<String,String> xsltParams = new HashMap<>();
        // Example: /api/xml-to-json?...&p_env=dev&p_flag=1  -> params env=dev, flag=1
        // If you want this, you can parse from the native request (ServletRequest). Keeping it simple here.

        opt.xsltParams = xsltParams;

        Result res = converter.convert(xml, opt);
        return ResponseEntity.ok(Map.of(
            "json", res.prettyJson,
            "viaXslt", useXslt
        ));
    }

    @GetMapping(path = "/health", produces = MimeTypeUtils.TEXT_PLAIN_VALUE)
    public String health() { return "OK"; }
}
*****************************************************************************
raj.xml2json.xslt=classpath:/xslt/xml-to-json.xsl
****************************************************************************

<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8"/>
  <title>XML/SOAP → JSON (XSLT) · Raj Utilities</title>
  <meta name="viewport" content="width=device-width, initial-scale=1"/>
  <meta name="color-scheme" content="light dark">
  <style>
    :root{
      --bg:#0b0f1a; --fg:#e7e9ee; --muted:#a7b0bf; --card:#0f1524; --border:#233049; --accent:#7c9cff;
      --chip:#141c30; --btn:#1c2336; --btn-fg:#e7e9ee; --link:#9bb3ff; --codebg:#0c1222; --codefg:#e7e9ee;
    }
    @media (prefers-color-scheme: light){
      :root{
        --bg:#f7f8fb; --fg:#0d1320; --muted:#5b6577; --card:#ffffff; --border:#e5e9f2; --accent:#3b6cff;
        --chip:#f1f4fb; --btn:#0d1320; --btn-fg:#ffffff; --link:#305dff; --codebg:#0b1020; --codefg:#e7e9ee;
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
    .select{display:flex;align-items:center;gap:8px;background:var(--chip);border:1px solid var(--border);padding:8px 10px;border-radius:12px}
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
    .hint{color:var(--muted);font-size:.9em}
  </style>
</head>
<body>
<div class="container">
  <div class="topbar">
    <h1>XML/SOAP → JSON (via XSLT)</h1>
    <div style="display:flex;gap:8px;align-items:center">
      <a class="back" href="/">← Back to utilities</a>
      <button id="loadSample" class="btn secondary" title="Load a SOAP+CDATA sample">Load sample</button>
    </div>
  </div>

  <div class="panel">
    <div class="controls" id="controls">

      <!-- Pre-processing -->
      <label class="switch"><input type="checkbox" id="unwrap" checked><span>Unwrap SOAP Body</span></label>
      <label class="switch"><input type="checkbox" id="parseCdata" checked><span>Parse XML in CDATA</span></label>
      <label class="switch"><input type="checkbox" id="lowerRoot"><span>Lowercase root</span></label>

      <!-- XSLT -->
      <label class="switch"><input type="checkbox" id="useXslt" checked><span>Use XSLT</span></label>
      <label class="text"><span>XSLT path</span>
        <input type="text" id="xsltPath" placeholder="classpath:/xslt/xml-to-json.xsl">
      </label>
      <label class="switch"><input type="checkbox" id="xsltOutputIsJson" checked><span>Stylesheet outputs JSON</span></label>

      <!-- Fallback XML→JSON options (only used when stylesheet outputs XML or XSLT disabled) -->
      <label class="switch"><input type="checkbox" id="flatten" checked><span>Flatten attributes</span></label>
      <label class="switch"><input type="checkbox" id="coerce" checked><span>Coerce numbers</span></label>

      <button id="convertBtn" class="btn">Convert</button>
      <button id="clearBtn" class="btn secondary">Clear</button>
      <span id="status" style="margin-left:auto;color:var(--muted)"></span>
    </div>

    <div class="grid">
      <div>
        <div class="col-head">Input (XML or SOAP)</div>
        <textarea id="xml" placeholder="Paste your XML or SOAP here…"></textarea>
        <div class="hint" style="margin-top:6px">
          Tip: if your stylesheet produces XML, uncheck “Stylesheet outputs JSON” and we’ll convert its XML output to JSON.
        </div>
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

  const unwrapEl = $("unwrap"),
        parseCdataEl = $("parseCdata"),
        lowerRootEl = $("lowerRoot"),
        useXsltEl = $("useXslt"),
        xsltPathEl = $("xsltPath"),
        xsltOutputIsJsonEl = $("xsltOutputIsJson"),
        flattenEl = $("flatten"),
        coerceEl = $("coerce");

  $("clearBtn").addEventListener("click", () => {
    xmlEl.value = ""; jsonEl.textContent = "/* JSON will appear here */";
  });

  $("copyBtn").addEventListener("click", async () => {
    try {
      await navigator.clipboard.writeText(jsonEl.textContent || "");
      statusEl.textContent = "Copied!";
      setTimeout(() => statusEl.textContent = "", 900);
    } catch {}
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
          <type>INDIVIDUAL</type>
          <enteredTimestamp>2024-01-04T14:05:14</enteredTimestamp>
          <DEBT_RATIO>0.5</DEBT_RATIO>
          <PAYMENT_PER_AMOUNT>1,000</PAYMENT_PER_AMOUNT>
        </CreditApplication>
      </Application>
    ]]></inv:inputXml>
  </soapenv:Body>
</soapenv:Envelope>`;
  });

  useXsltEl.addEventListener("change", () => {
    const enableXslt = useXsltEl.checked;
    xsltPathEl.disabled = !enableXslt;
    xsltOutputIsJsonEl.disabled = !enableXslt;
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
        lowercaseRoot: lowerRootEl.checked,
        useXslt: useXsltEl.checked,
        xsltPath: xsltPathEl.value.trim(),
        xsltOutputIsJson: xsltOutputIsJsonEl.checked,
        flattenAttributes: flattenEl.checked,
        coerceNumbers: coerceEl.checked
      }).toString();

      const resp = await fetch("/api/xml-to-json?" + qs, {
        method: "POST",
        headers: { "Content-Type": "text/plain" },
        body: xml
      });
      if (!resp.ok) throw new Error(await resp.text());
      const data = await resp.json();
      jsonEl.textContent = data.json || JSON.stringify(data, null, 2);
    } catch (e) {
      jsonEl.textContent = "Error: " + (e.message || e);
    } finally {
      btn.disabled = false;
      statusEl.textContent = "";
    }
  }

  btn.addEventListener("click", convert);

  // Initialize control states
  useXsltEl.dispatchEvent(new Event("change"));
</script>
</body>
</html>

  


  
