package com.example.convert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.*;
import java.util.Map;

@Service
public class XmlToJsonXsltService {

  private final Templates templates;
  private final ObjectMapper mapper = new ObjectMapper();

  public XmlToJsonXsltService() {
    try (InputStream xsl = new ClassPathResource("xslt/xml-to-json.xslt").getInputStream()) {
      TransformerFactory tf;
      try {
        // Prefer Saxon (XSLT 3.0 -> enables parse-xml(), regex, etc.)
        tf = (TransformerFactory) Class.forName("net.sf.saxon.TransformerFactoryImpl")
            .getDeclaredConstructor().newInstance();
      } catch (Throwable ignore) {
        tf = TransformerFactory.newInstance(); // XSLT 1.0 fallback
      }
      this.templates = tf.newTemplates(new StreamSource(xsl));
    } catch (Exception e) {
      throw new IllegalStateException("Failed to load XSLT stylesheet", e);
    }
  }

  public String transform(String xml, Map<String, String> params, boolean pretty) throws Exception {
    // 1) Optional SOAP unwrap in Java (robust & fast)
    if ("true".equalsIgnoreCase(params.getOrDefault("unwrap", "false"))) {
      xml = extractSoapPayload(xml);  // returns the inner payload XML as a string
    }

    // 2) Run the XSLT
    StringWriter out = new StringWriter();
    Transformer t = templates.newTransformer();
    if (params != null) {
      for (Map.Entry<String, String> e : params.entrySet()) t.setParameter(e.getKey(), e.getValue());
    }
    try (Reader reader = new StringReader(xml)) {
      t.transform(new StreamSource(reader), new StreamResult(out));
    }
    String json = out.toString();

    // 3) Optional: validate & pretty-print
    if (!pretty) return json;
    ObjectWriter writer = mapper.writerWithDefaultPrettyPrinter();
    return writer.writeValueAsString(mapper.readTree(json));
  }

  /** Extracts the first child element of SOAP Body; returns original XML if not SOAP. */
  private String extractSoapPayload(String soapXml) throws Exception {
    DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
    dbf.setNamespaceAware(true);
    Document doc = dbf.newDocumentBuilder().parse(new ByteArrayInputStream(soapXml.getBytes()));
    XPath xp = XPathFactory.newInstance().newXPath();
    // match Body regardless of prefix
    Node body = (Node) xp.evaluate("/*[local-name()='Envelope']/*[local-name()='Body']",
                                   doc, XPathConstants.NODE);
    if (body == null || !body.hasChildNodes()) return soapXml;

    // Find first element child in Body
    Node payload = null;
    for (int i = 0; i < body.getChildNodes().getLength(); i++) {
      Node n = body.getChildNodes().item(i);
      if (n.getNodeType() == Node.ELEMENT_NODE) { payload = n; break; }
    }
    if (payload == null) return soapXml;

    // Serialize payload node back to a standalone XML string
    Transformer tr = TransformerFactory.newInstance().newTransformer();
    StringWriter sw = new StringWriter();
    tr.transform(new DOMSource(payload), new StreamResult(sw));
    return sw.toString();
  }
}
****************************


  package com.example.convert;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import java.util.HashMap;
import java.util.Map;

@RestController
public class XmlToJsonController {

  private final XmlToJsonXsltService service;
  public XmlToJsonController(XmlToJsonXsltService service){ this.service = service; }

  @PostMapping(
      path = "/convert/xml-to-json",
      consumes = MediaType.APPLICATION_XML_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public String convert(@RequestBody String xml,
                        @RequestParam(defaultValue="false") String unwrap,
                        @RequestParam(defaultValue="false") String parseCdata,
                        @RequestParam(defaultValue="false") String flattenAttributes,
                        @RequestParam(defaultValue="false") String coerceNumbers,
                        @RequestParam(defaultValue="true")  boolean pretty) throws Exception {

    Map<String,String> p = new HashMap<>();
    p.put("unwrap", unwrap);
    p.put("parseCdata", parseCdata);
    p.put("flattenAttributes", flattenAttributes);
    p.put("coerceNumbers", coerceNumbers);
    return service.transform(xml, p, pretty);
  }
}

**********************


  <!-- src/main/resources/static/index.html -->
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8"/>
  <title>Raj Tools — XML → JSON (XSLT)</title>
  <meta name="viewport" content="width=device-width, initial-scale=1"/>
  <style>
    :root {
      --bg1:#0b1023; --bg2:#0f172a; --panel:#0c1426; --ink:#e7edf9; --muted:#9fb0c9;
      --border: rgba(255,255,255,.08); --ring:#6050ff; --acc1:#22d3ee; --acc2:#8b5cf6;
      --ok:#7ee787; --err:#ffb4ab; --warn:#ffd166;
    }
    *{box-sizing:border-box}
    html,body{height:100%}
    body{
      margin:0; color:var(--ink);
      background:
        radial-gradient(1100px 550px at 10% -10%, #172659 12%, transparent 70%),
        radial-gradient(1200px 600px at 100% 0%, #1b0f35 6%, transparent 60%),
        linear-gradient(135deg, var(--bg1), var(--bg2));
      font-family: ui-sans-serif, -apple-system, Segoe UI, Inter, Roboto, Helvetica, Arial;
      letter-spacing:.2px;
    }
    .nav {
      position: sticky; top:0; z-index:10;
      display:flex; align-items:center; justify-content:space-between; gap:16px;
      padding:16px 22px; backdrop-filter:saturate(140%) blur(8px);
      background:linear-gradient(180deg, rgba(9,11,31,.75), rgba(9,11,31,.35));
      border-bottom:1px solid var(--border);
    }
    .brand { display:flex; align-items:center; gap:12px; font-weight:800; letter-spacing:.5px; font-size:18px; }
    .logo {
      width:28px; height:28px; border-radius:8px;
      background: conic-gradient(from 210deg, var(--acc1), var(--acc2) 40%, #36d399 75%, var(--acc1));
      box-shadow: 0 0 0 2px rgba(255,255,255,.06), 0 8px 20px rgba(98, 87, 255, .25) inset;
    }
    .badge { font-size:12px; color:var(--muted) }
    .container{max-width:1200px; margin:32px auto 56px; padding:0 20px}
    .hero { display:flex; justify-content:space-between; align-items:center; gap:20px; margin-bottom:16px; }
    .hero h1 {margin:0; font-size:28px; font-weight:800; letter-spacing:.3px}
    .hero p {margin:6px 0 0; color:var(--muted); font-size:13px}
    .grid { display:grid; grid-template-columns:1fr 1fr; gap:18px }
    @media (max-width: 980px){ .grid{grid-template-columns:1fr} }

    .card {
      background: linear-gradient(180deg, rgba(18,24,42,.8), rgba(12,16,30,.65));
      border:1px solid var(--border); border-radius:18px; padding:16px;
      box-shadow: 0 12px 30px rgba(0,0,0,.35), inset 0 1px 0 rgba(255,255,255,.04);
    }
    .bar { display:flex; align-items:center; justify-content:space-between; margin-bottom:10px }
    label.head { font-size:12px; color:var(--muted); }
    .hint { color:var(--muted); font-size:12px }

    textarea, pre {
      width:100%; min-height:420px; resize:vertical; border-radius:14px; border:1px solid var(--border);
      background:#0a0f20; color:var(--ink); padding:12px 14px;
      font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, "Liberation Mono", monospace;
      font-size:13px; line-height:1.5;
      outline:none; transition: box-shadow .2s, border-color .2s;
    }
    textarea:focus { border-color: rgba(96,80,255,.7); box-shadow: 0 0 0 3px rgba(96,80,255,.25) }
    pre { margin:0; white-space:pre-wrap; word-break:break-word }

    .row { display:flex; gap:12px; flex-wrap:wrap; align-items:center; margin-top:12px }
    .chip {
      display:flex; align-items:center; gap:8px; padding:8px 12px; border-radius:999px;
      background:#0a0f20; border:1px solid var(--border); user-select:none;
    }
    .chip input{transform:scale(1.1)}
    .btn {
      border:none; border-radius:999px; padding:11px 18px; font-weight:700;
      background: linear-gradient(135deg, var(--acc1), var(--acc2));
      color:#0b1023; cursor:pointer; box-shadow: 0 10px 28px rgba(104, 89, 255, .35);
      transition: transform .04s ease-in-out, box-shadow .2s;
    }
    .btn:active { transform: translateY(1px) }
    .btn[disabled]{opacity:.55; cursor:not-allowed}
    .toolbar { display:flex; align-items:center; gap:10px }
    .kbd { font: 600 11px ui-monospace, Menlo, monospace; padding:2px 6px; border-radius:6px;
      border:1px solid var(--border); color:var(--muted); background:#0a0f20 }
    .status { font-size:12px }
    .status.ok{ color:var(--ok) }
    .status.err{ color:var(--err) }
    .right-actions { display:flex; gap:10px; align-items:center }
    .ghost {
      border:1px solid var(--border); background:#0a0f20; color:var(--ink);
      padding:8px 12px; border-radius:999px; cursor:pointer; font-weight:600;
    }
    .filepick { display:flex; align-items:center; gap:8px }
    input[type=file]::file-selector-button{
      border:none; border-radius:10px; padding:8px 12px; font-weight:600;
      background:#121a35; color:var(--ink); border:1px solid var(--border); cursor:pointer;
    }
    footer { text-align:center; color:var(--muted); font-size:12px; margin-top:10px }
  </style>
</head>
<body>
  <!-- Top bar -->
  <nav class="nav">
    <div class="brand">
      <div class="logo" aria-hidden="true"></div>
      <div>
        <div>Raj Tools</div>
        <div class="badge">XML → JSON (XSLT)</div>
      </div>
    </div>
    <div class="toolbar">
      <span class="kbd">⌘/Ctrl</span><span class="hint">+ Enter to Convert</span>
    </div>
  </nav>

  <div class="container">
    <section class="hero">
      <div>
        <h1>Convert XML to JSON using your XSLT</h1>
        <p>Paste XML or pick a file. Toggle options for SOAP unwrapping, CDATA parsing, attribute flattening, and numeric coercion.</p>
      </div>
      <div class="right-actions">
        <button id="loadSample" class="ghost" title="Load a sample SOAP message">Load Sample SOAP</button>
        <button id="clearAll" class="ghost" title="Clear inputs and output">Clear</button>
      </div>
    </section>

    <div class="grid">
      <!-- Left: XML input -->
      <section class="card">
        <div class="bar">
          <label class="head">XML input</label>
          <div class="filepick">
            <input type="file" id="file" accept=".xml,text/xml"/>
          </div>
        </div>
        <textarea id="xml" placeholder="&lt;CreditApplication&gt;…&lt;/CreditApplication&gt;"></textarea>

        <div class="row" role="group" aria-label="Options">
          <label class="chip" title="Remove SOAP Envelope/Body and pass the first payload element to XSLT">
            <input type="checkbox" id="unwrap"> <span>Unwrap SOAP</span>
          </label>
          <label class="chip" title="If a field contains embedded XML inside CDATA/text, parse it">
            <input type="checkbox" id="parseCdata"> <span>Parse CDATA</span>
          </label>
          <label class="chip" title="Expose element attributes as JSON fields">
            <input type="checkbox" id="flattenAttributes"> <span>Flatten attributes</span>
          </label>
          <label class="chip" title="Numbers/booleans/null emitted unquoted">
            <input type="checkbox" id="coerceNumbers"> <span>Coerce numbers</span>
          </label>
          <label class="chip" title="Validate and pretty-print JSON on the server">
            <input type="checkbox" id="pretty" checked> <span>Pretty JSON</span>
          </label>
          <button class="btn" id="convertBtn" title="Convert XML to JSON">Convert</button>
        </div>
      </section>

      <!-- Right: JSON output -->
      <section class="card">
        <div class="bar">
          <label class="head">JSON output</label>
          <span id="status" class="status">Ready</span>
        </div>
        <pre id="out" aria-live="polite"></pre>
        <div class="row" style="justify-content: flex-end;">
          <button id="copyBtn" class="ghost" title="Copy JSON to clipboard">Copy JSON</button>
        </div>
        <footer>Tip: Press <span class="kbd">⌘/Ctrl</span> + <span class="kbd">Enter</span> to convert</footer>
      </section>
    </div>
  </div>

  <script>
    const $ = id => document.getElementById(id);
    const xmlEl = $('xml'), outEl = $('out'), statusEl = $('status'), fileEl = $('file');
    const btn = $('convertBtn'), copyBtn = $('copyBtn'), sampleBtn = $('loadSample'), clearBtn = $('clearAll');

    fileEl.addEventListener('change', async (e) => {
      const f = e.target.files?.[0];
      if (!f) return;
      xmlEl.value = await f.text();
      xmlEl.focus();
    });

    function setStatus(text, kind){
      statusEl.textContent = text;
      statusEl.className = 'status ' + (kind || '');
    }

    async function doConvert(){
      const xml = xmlEl.value.trim();
      if (!xml) { outEl.textContent = 'Please paste XML first.'; return; }

      const params = new URLSearchParams({
        unwrap: $('unwrap').checked,
        parseCdata: $('parseCdata').checked,
        flattenAttributes: $('flattenAttributes').checked,
        coerceNumbers: $('coerceNumbers').checked,
        pretty: $('pretty').checked
      });

      outEl.textContent = '';
      setStatus('Converting…','');
      btn.disabled = true;
      try {
        const res = await fetch('/convert/xml-to-json?' + params.toString(), {
          method: 'POST',
          headers: { 'Content-Type': 'application/xml' },
          body: xml
        });
        const text = await res.text();
        if (!res.ok) throw new Error(text || ('HTTP ' + res.status));
        outEl.textContent = text;
        setStatus('Done','ok');
      } catch (err) {
        setStatus('Error','err');
        outEl.textContent = (err && err.message) ? err.message : String(err);
      } finally {
        btn.disabled = false;
      }
    }

    $('convertBtn').addEventListener('click', doConvert);

    // Keyboard shortcut: Cmd/Ctrl + Enter
    window.addEventListener('keydown', (e) => {
      if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
        e.preventDefault();
        doConvert();
      }
    });

    // Copy output
    copyBtn.addEventListener('click', async () => {
      const txt = outEl.textContent;
      if (!txt) return;
      try {
        await navigator.clipboard.writeText(txt);
        setStatus('Copied to clipboard','ok');
        setTimeout(()=>setStatus('Ready',''), 1200);
      } catch {
        setStatus('Copy failed','err');
      }
    });

    // Load a sample SOAP envelope
    sampleBtn.addEventListener('click', () => {
      xmlEl.value =
`<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
  <soapenv:Header/>
  <soapenv:Body>
    <CreditApplication>
      <ApplicationNumber>12345</ApplicationNumber>
      <Applicant>
        <Name first="Raj" last="Rama">Raj Rama</Name>
        <Age>36</Age>
        <HasCoApplicant>false</HasCoApplicant>
      </Applicant>
      <Notes><![CDATA[<Extra><Flag>true</Flag><Score>712</Score></Extra>]]></Notes>
      <Addresses>
        <Address type="home"><Line1>123 Maple St</Line1><City>Aldie</City><State>VA</State></Address>
        <Address type="work"><Line1>456 Oak Ave</Line1><City>Reston</City><State>VA</State></Address>
      </Addresses>
    </CreditApplication>
  </soapenv:Body>
</soapenv:Envelope>`;
      $('unwrap').checked = true;
      $('parseCdata').checked = true;
      $('flattenAttributes').checked = true;
      $('coerceNumbers').checked = true;
      xmlEl.focus();
      setStatus('Sample loaded','');
    });

    // Clear
    clearBtn.addEventListener('click', () => {
      xmlEl.value = '';
      outEl.textContent = '';
      $('unwrap').checked = false;
      $('parseCdata').checked = false;
      $('flattenAttributes').checked = false;
      $('coerceNumbers').checked = false;
      $('pretty').checked = true;
      fileEl.value = '';
      setStatus('Ready','');
      xmlEl.focus();
    });
  </script>
</body>
</html>

