// src/main/java/com/example/convert/XmlToJsonXsltService.java
package com.example.convert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import javax.xml.transform.*;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Service
public class XmlToJsonXsltService {

  private final Templates templates;
  private final ObjectMapper mapper = new ObjectMapper();

  public XmlToJsonXsltService() {
    try (InputStream xsl = new ClassPathResource("xslt/xml-to-json.xslt").getInputStream()) {
      TransformerFactory tf;
      try {
        // Prefer Saxon if present (XSLT 2.0/3.0)
        tf = (TransformerFactory) Class.forName("net.sf.saxon.TransformerFactoryImpl")
                .getDeclaredConstructor().newInstance();
      } catch (Throwable ignore) {
        // Fallback to JDK (XSLT 1.0)
        tf = TransformerFactory.newInstance();
      }
      this.templates = tf.newTemplates(new StreamSource(xsl));
    } catch (Exception e) {
      throw new IllegalStateException("Failed to load XSLT stylesheet", e);
    }
  }

  /**
   * Transform XML text to JSON text via XSLT.
   * @param xml XML string
   * @param params XSLT parameters: unwrap, parseCdata, flattenAttributes, coerceNumbers, etc.
   * @param pretty pretty-print and validate JSON if true
   */
  public String transform(String xml, Map<String, String> params, boolean pretty) throws Exception {
    StringWriter out = new StringWriter();
    Transformer t = templates.newTransformer();

    if (params != null) {
      for (Map.Entry<String, String> e : params.entrySet()) {
        t.setParameter(e.getKey(), e.getValue());
      }
    }

    try (Reader reader = new StringReader(xml)) {
      t.transform(new StreamSource(reader), new StreamResult(out));
    }
    String json = out.toString();

    if (!pretty) return json;

    // Validate & pretty-print
    ObjectWriter writer = mapper.writerWithDefaultPrettyPrinter();
    return writer.writeValueAsString(mapper.readTree(json));
  }
}


**********************

    // src/main/java/com/example/convert/XmlToJsonXsltService.java
package com.example.convert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import javax.xml.transform.*;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Service
public class XmlToJsonXsltService {

  private final Templates templates;
  private final ObjectMapper mapper = new ObjectMapper();

  public XmlToJsonXsltService() {
    try (InputStream xsl = new ClassPathResource("xslt/xml-to-json.xslt").getInputStream()) {
      TransformerFactory tf;
      try {
        // Prefer Saxon if present (XSLT 2.0/3.0)
        tf = (TransformerFactory) Class.forName("net.sf.saxon.TransformerFactoryImpl")
                .getDeclaredConstructor().newInstance();
      } catch (Throwable ignore) {
        // Fallback to JDK (XSLT 1.0)
        tf = TransformerFactory.newInstance();
      }
      this.templates = tf.newTemplates(new StreamSource(xsl));
    } catch (Exception e) {
      throw new IllegalStateException("Failed to load XSLT stylesheet", e);
    }
  }

  /**
   * Transform XML text to JSON text via XSLT.
   * @param xml XML string
   * @param params XSLT parameters: unwrap, parseCdata, flattenAttributes, coerceNumbers, etc.
   * @param pretty pretty-print and validate JSON if true
   */
  public String transform(String xml, Map<String, String> params, boolean pretty) throws Exception {
    StringWriter out = new StringWriter();
    Transformer t = templates.newTransformer();

    if (params != null) {
      for (Map.Entry<String, String> e : params.entrySet()) {
        t.setParameter(e.getKey(), e.getValue());
      }
    }

    try (Reader reader = new StringReader(xml)) {
      t.transform(new StreamSource(reader), new StreamResult(out));
    }
    String json = out.toString();

    if (!pretty) return json;

    // Validate & pretty-print
    ObjectWriter writer = mapper.writerWithDefaultPrettyPrinter();
    return writer.writeValueAsString(mapper.readTree(json));
  }
}
********************************************
    // src/main/java/com/example/convert/XmlToJsonController.java
package com.example.convert;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
public class XmlToJsonController {

  private final XmlToJsonXsltService service;

  public XmlToJsonController(XmlToJsonXsltService service) {
    this.service = service;
  }

  /**
   * POST the raw XML body. Options are passed as query params:
   *   ?unwrap=true&parseCdata=true&flattenAttributes=false&coerceNumbers=true&pretty=true
   */
  @PostMapping(
      path = "/convert/xml-to-json",
      consumes = MediaType.APPLICATION_XML_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public String convert(@RequestBody String xml,
                        @RequestParam(defaultValue = "false") String unwrap,
                        @RequestParam(defaultValue = "false") String parseCdata,
                        @RequestParam(defaultValue = "false") String flattenAttributes,
                        @RequestParam(defaultValue = "false") String coerceNumbers,
                        @RequestParam(defaultValue = "true")  boolean pretty
  ) throws Exception {

    Map<String,String> xsltParams = new HashMap<>();
    xsltParams.put("unwrap", unwrap);
    xsltParams.put("parseCdata", parseCdata);
    xsltParams.put("flattenAttributes", flattenAttributes);
    xsltParams.put("coerceNumbers", coerceNumbers);

    return service.transform(xml, xsltParams, pretty);
  }
}
****************************

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
      --border: rgba(255,255,255,.08); --ring:#60f; --acc1:#22d3ee; --acc2:#8b5cf6;
    }
    *{box-sizing:border-box}
    html,body{height:100%}
    body{
      margin:0; color:var(--ink); background: radial-gradient(1200px 600px at 15% -10%, #142044 10%, transparent 70%),
                 radial-gradient(1400px 700px at 100% 0%, #1b0f35 5%, transparent 60%),
                 linear-gradient(135deg, var(--bg1), var(--bg2));
      font-family: ui-sans-serif, -apple-system, Segoe UI, Roboto, Inter, system-ui, Helvetica, Arial;
      letter-spacing:.2px;
    }
    .nav {
      position: sticky; top:0; z-index:10;
      display:flex; align-items:center; justify-content:space-between;
      gap:16px; padding:16px 22px; backdrop-filter:saturate(140%) blur(8px);
      background:linear-gradient(180deg, rgba(9, 11, 31,.75), rgba(9, 11, 31,.35));
      border-bottom:1px solid var(--border);
    }
    .brand {
      display:flex; align-items:center; gap:12px; font-weight:800; letter-spacing:.5px;
      font-size:18px;
    }
    .logo {
      width:28px; height:28px; border-radius:8px;
      background: conic-gradient(from 210deg, var(--acc1), var(--acc2) 40%, #36d399 75%, var(--acc1));
      box-shadow: 0 0 0 2px rgba(255,255,255,.06), 0 8px 20px rgba(98, 87, 255, .25) inset;
    }
    .badge { font-size:12px; color:var(--muted) }
    .container{max-width:1200px; margin:32px auto 56px; padding:0 20px}
    .hero {
      display:flex; justify-content:space-between; align-items:center; gap:20px; margin-bottom:16px;
    }
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
      background: #0a0f20; color:var(--ink); padding:12px 14px;
      font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, "Liberation Mono", monospace;
      font-size:13px; line-height:1.5;
      outline: none; transition: box-shadow .2s, border-color .2s;
    }
    textarea:focus { border-color: rgba(96,80,255,.7); box-shadow: 0 0 0 3px rgba(96,80,255,.25) }
    pre { margin:0; white-space:pre-wrap; word-break:break-word }

    .row { display:flex; gap:12px; flex-wrap:wrap; align-items:center; margin-top:12px }
    .chip {
      display:flex; align-items:center; gap:8px; padding:8px 12px; border-radius:999px;
      background:#0a0f20; border:1px solid var(--border);
      user-select:none;
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
    .status-ok { color:#7ee787 }
    .status-err{ color:#ffb4ab }
    .filepick { display:flex; align-items:center; gap:8px }
    input[type=file]::file-selector-button{
      border:none; border-radius:10px; padding:8px 12px; font-weight:600;
      background:#121a35; color:var(--ink); border:1px solid var(--border); cursor:pointer;
    }
    footer { text-align:center; color:var(--muted); font-size:12px; margin-top:28px }
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
        <p>Paste XML or pick a file, toggle options, then convert. Output is validated & pretty-printed server-side.</p>
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

        <div class="row">
          <label class="chip"><input type="checkbox" id="unwrap"> <span>Unwrap XML</span></label>
          <label class="chip"><input type="checkbox" id="parseCdata"> <span>Parse CDATA</span></label>
          <label class="chip"><input type="checkbox" id="flattenAttributes"> <span>Flatten attributes</span></label>
          <label class="chip"><input type="checkbox" id="coerceNumbers"> <span>Coerce numbers</span></label>
          <label class="chip"><input type="checkbox" id="pretty" checked> <span>Pretty JSON</span></label>
          <button class="btn" id="convertBtn">Convert</button>
        </div>
      </section>

      <!-- Right: JSON output -->
      <section class="card">
        <div class="bar">
          <label class="head">JSON output</label>
          <span id="status" class="hint">Ready</span>
        </div>
        <pre id="out" aria-live="polite"></pre>
        <footer>Tip: Press <span class="kbd">⌘/Ctrl</span> + <span class="kbd">Enter</span> to convert</footer>
      </section>
    </div>
  </div>

  <script>
    const $ = id => document.getElementById(id);
    const xmlEl = $('xml'), outEl = $('out'), statusEl = $('status'), fileEl = $('file'), btn = $('convertBtn');

    fileEl.addEventListener('change', async (e) => {
      const f = e.target.files?.[0];
      if (!f) return;
      xmlEl.value = await f.text();
      xmlEl.focus();
    });

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
      statusEl.textContent = 'Converting…';
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
        statusEl.textContent = 'Done';
        statusEl.className = 'status-ok';
      } catch (err) {
        statusEl.textContent = 'Error';
        statusEl.className = 'status-err';
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
  </script>
</body>
</html>
