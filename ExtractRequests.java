package com.raj.utilities.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import org.w3c.dom.*;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class XmlToJsonService {

    private static final String ATTR_PREFIX = "@";
    private static final String TEXT_KEY = "#text";

    private final ObjectMapper mapper = new ObjectMapper();

    public static final class ConversionResult {
        public final String prettyJson;
        public final int xmlAttributeCount;
        public final int jsonAttributeCount;
        public final boolean allAttributesConverted;
        public final List<String> missingAttributes; // XPath-like of any missed attributes (should be empty)

        public ConversionResult(String prettyJson, int xmlAttributeCount, int jsonAttributeCount,
                                boolean allAttributesConverted, List<String> missingAttributes) {
            this.prettyJson = prettyJson;
            this.xmlAttributeCount = xmlAttributeCount;
            this.jsonAttributeCount = jsonAttributeCount;
            this.allAttributesConverted = allAttributesConverted;
            this.missingAttributes = missingAttributes;
        }
    }

    public ConversionResult convert(String xml, boolean unwrapSoapBody) throws Exception {
        if (xml == null || xml.isBlank()) {
            throw new IllegalArgumentException("XML input is empty.");
        }

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        // Safe XML parsing guards
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        dbf.setXIncludeAware(false);
        dbf.setExpandEntityReferences(false);

        DocumentBuilder db = dbf.newDocumentBuilder();
        Document doc = db.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        doc.getDocumentElement().normalize();

        Element start = doc.getDocumentElement();
        if (unwrapSoapBody) {
            Element maybeEnvelope = start;
            if (localName(maybeEnvelope).equalsIgnoreCase("Envelope")) {
                Optional<Element> body = firstChildElementByLocalName(maybeEnvelope, "Body");
                if (body.isPresent()) {
                    Optional<Element> firstPayload = firstElementChild(body.get());
                    if (firstPayload.isPresent()) {
                        start = firstPayload.get();
                    } else {
                        start = body.get();
                    }
                }
            }
        }

        Set<String> xmlAttrPaths = new LinkedHashSet<>();
        Set<String> jsonAttrPaths = new LinkedHashSet<>();

        ObjectNode root = mapper.createObjectNode();
        root.set(localName(start), elementToJson(start, mapper.createObjectNode(), "/" + localName(start), xmlAttrPaths, jsonAttrPaths));

        // Pretty JSON
        String pretty = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);

        // Verification
        int xmlAttrCount = xmlAttrPaths.size();
        int jsonAttrCount = jsonAttrPaths.size();

        // Any attribute in XML that didn't show under our JSON ATTR_PREFIX is considered missing
        List<String> missing = new ArrayList<>();
        for (String p : xmlAttrPaths) {
            if (!jsonAttrPaths.contains(p)) {
                missing.add(p);
            }
        }

        boolean allConverted = missing.isEmpty() && xmlAttrCount == jsonAttrCount;

        return new ConversionResult(pretty, xmlAttrCount, jsonAttrCount, allConverted, missing);
    }

    /* ============ Helpers ============ */

    private JsonNode elementToJson(Element elem, ObjectNode target, String path,
                                   Set<String> xmlAttrPaths, Set<String> jsonAttrPaths) {

        // Attributes
        NamedNodeMap attrs = elem.getAttributes();
        if (attrs != null && attrs.getLength() > 0) {
            ObjectNode attrsNode = target.putObject(ATTR_PREFIX + "attributes");
            for (int i = 0; i < attrs.getLength(); i++) {
                Attr a = (Attr) attrs.item(i);
                String key = a.getName();
                String val = a.getValue();
                attrsNode.put(key, val);

                String attrPath = path + "/@" + key;
                xmlAttrPaths.add(attrPath);
                jsonAttrPaths.add(attrPath); // tracked symmetrically since we’re emitting them
            }
        }

        // Children
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

        String text = textBuf.toString().trim();

        // If element only has text and no attributes/children -> return text node
        boolean hasAttrs = target.has(ATTR_PREFIX + "attributes");
        if (groups.isEmpty()) {
            if (!hasAttrs) {
                return text.isEmpty() ? NullNode.instance : new TextNode(text);
            } else {
                if (!text.isEmpty()) target.put(TEXT_KEY, text);
                return target;
            }
        }

        // Handle children groups
        for (Map.Entry<String, List<Element>> e : groups.entrySet()) {
            String childName = e.getKey();
            List<Element> items = e.getValue();
            if (items.size() == 1) {
                Element only = items.get(0);
                JsonNode val = elementToJson(only, mapper.createObjectNode(), path + "/" + childName, xmlAttrPaths, jsonAttrPaths);
                target.set(childName, val);
            } else {
                ArrayNode arr = mapper.createArrayNode();
                for (Element ce : items) {
                    arr.add(elementToJson(ce, mapper.createObjectNode(), path + "/" + childName + "[" + arr.size() + "]", xmlAttrPaths, jsonAttrPaths));
                }
                target.set(childName, arr);
            }
        }

        if (!text.isEmpty()) {
            target.put(TEXT_KEY, text);
        }

        return target;
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
                String ln = (e.getLocalName() != null) ? e.getLocalName() : e.getNodeName();
                if (wantedLocalName.equalsIgnoreCase(ln)) {
                    return Optional.of(e);
                }
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
_____________________



  package com.raj.utilities.web;

import com.raj.utilities.service.XmlToJsonService;
import com.raj.utilities.service.XmlToJsonService.ConversionResult;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Controller
public class XmlToJsonController {

    private final XmlToJsonService service = new XmlToJsonService();

    // Page
    @GetMapping("/xml-to-json")
    public String page() {
        // returns /templates/xml-to-json.html (Thymeleaf) OR static page if you serve it from /static
        return "xml-to-json";
    }

    // API
    @PostMapping(path = "/api/xml-to-json", consumes = MediaType.TEXT_PLAIN_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> convert(@RequestBody String xml,
                                       @RequestParam(name = "unwrapSoapBody", defaultValue = "true") boolean unwrapSoapBody) throws Exception {
        ConversionResult res = service.convert(xml, unwrapSoapBody);
        return Map.of(
                "json", res.prettyJson,
                "xmlAttributeCount", res.xmlAttributeCount,
                "jsonAttributeCount", res.jsonAttributeCount,
                "allAttributesConverted", res.allAttributesConverted,
                "missingAttributes", res.missingAttributes
        );
    }
}
___________________________________

  <!-- Add inside your utilities grid on index.html -->
<a class="card" href="/xml-to-json" style="text-decoration:none;">
  <div class="card-body">
    <h3>XML/SOAP → JSON Converter</h3>
    <p>Paste XML or SOAP; get clean JSON and a full attribute-conversion check.</p>
  </div>
</a>

<!-- Quick minimal styles if needed -->
<style>
  .card { display:block; border:1px solid #e5e7eb; border-radius:14px; padding:16px; transition:box-shadow .2s; }
  .card:hover { box-shadow:0 8px 24px rgba(0,0,0,.08);}
  .card-body h3 { margin:0 0 6px; font-size:1.1rem; }
  .card-body p { margin:0; color:#444; font-size:.95rem; }
</style>
__________________

<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8"/>
  <title>XML/SOAP → JSON Converter · Raj Utilities</title>
  <meta name="viewport" content="width=device-width, initial-scale=1"/>
  <style>
    body { font-family: system-ui, Arial, sans-serif; margin: 24px; }
    h1 { margin: 0 0 16px; }
    .row { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
    textarea { width: 100%; height: 360px; font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 13px; padding: 10px; border-radius: 10px; border:1px solid #d1d5db; }
    pre { white-space: pre-wrap; word-wrap: break-word; background:#0b1020; color:#e6edf3; padding: 12px; border-radius: 10px; min-height: 360px; margin:0; }
    .controls { display:flex; align-items:center; gap:12px; margin: 12px 0 16px; }
    button { padding: 10px 16px; border-radius: 10px; border:0; cursor:pointer; background:#111827; color:#fff; }
    button:disabled { opacity: .6; cursor: not-allowed; }
    .stat { display:inline-block; margin-right: 16px; background:#f3f4f6; padding:6px 10px; border-radius: 8px; font-size: 13px; }
    .ok { color: #0f766e; }
    .bad { color: #b91c1c; }
    .topbar { display:flex; align-items:center; justify-content:space-between; margin-bottom: 8px; }
    a.back { text-decoration:none; color:#2563eb; }
  </style>
</head>
<body>
  <div class="topbar">
    <h1>XML/SOAP → JSON Converter</h1>
    <a class="back" href="/">← Back to utilities</a>
  </div>

  <div class="controls">
    <label><input type="checkbox" id="unwrap" checked/> Unwrap SOAP Body</label>
    <button id="convertBtn">Convert</button>
    <span id="status"></span>
  </div>

  <div class="row">
    <div>
      <textarea id="xml" placeholder="Paste XML or SOAP request here..."></textarea>
    </div>
    <div>
      <pre id="json">/* JSON will appear here */</pre>
      <div id="stats"></div>
    </div>
  </div>

  <script>
    const btn = document.getElementById('convertBtn');
    const xmlEl = document.getElementById('xml');
    const jsonEl = document.getElementById('json');
    const statusEl = document.getElementById('status');
    const statsEl = document.getElementById('stats');
    const unwrapEl = document.getElementById('unwrap');

    async function convert() {
      statsEl.innerHTML = '';
      jsonEl.textContent = '';
      const xml = xmlEl.value.trim();
      if (!xml) {
        jsonEl.textContent = 'Please paste some XML.';
        return;
      }
      btn.disabled = true;
      statusEl.textContent = 'Converting...';
      try {
        const resp = await fetch('/api/xml-to-json?unwrapSoapBody=' + unwrapEl.checked, {
          method: 'POST',
          headers: { 'Content-Type': 'text/plain' },
          body: xml
        });
        if (!resp.ok) {
          const t = await resp.text();
          throw new Error(t || 'Server error');
        }
        const data = await resp.json();
        jsonEl.textContent = data.json;

        const ok = data.allAttributesConverted;
        const cls = ok ? 'ok' : 'bad';
        statsEl.innerHTML = `
          <div class="stat">XML attributes: <strong>${data.xmlAttributeCount}</strong></div>
          <div class="stat">JSON attributes: <strong>${data.jsonAttributeCount}</strong></div>
          <div class="stat ${cls}">${ok ? 'All attributes converted ✔' : 'Missing attributes ⚠'}</div>
          ${!ok && data.missingAttributes?.length ? `<details style="margin-top:8px;"><summary>See missing</summary><pre>${data.missingAttributes.join('\n')}</pre></details>` : ''}
        `;
      } catch (e) {
        jsonEl.textContent = 'Error: ' + (e.message || e);
      } finally {
        btn.disabled = false;
        statusEl.textContent = '';
      }
    }
    btn.addEventListener('click', convert);

    // Small sample for quick testing
    xmlEl.value = `<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:ex="http://example.com">
  <soapenv:Header/>
  <soapenv:Body>
    <ex:CreditRequest id="123" channel="web">
      <ex:Applicant age="42">
        <ex:Name first="Ada" last="Lovelace"/>
      </ex:Applicant>
      <ex:Amount currency="USD">5000</ex:Amount>
    </ex:CreditRequest>
  </soapenv:Body>
</soapenv:Envelope>`;
  </script>
</body>
</html>

  
