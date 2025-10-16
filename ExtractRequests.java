// src/main/java/com/example/convert/XmlToJsonController.java
package com.example.convert;

import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
public class XmlToJsonController {

  private final XmlToJsonXsltService service;

  public XmlToJsonController(XmlToJsonXsltService service) {
    this.service = service;
  }

  @PostMapping(
      path = "/convert/xml-to-json",
      consumes = { MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE, "application/soap+xml" },
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  public String convert(
      @RequestBody String xml,
      // EXPLICIT NAMES to avoid “name not specified” error:
      @RequestParam(name = "unwrap",            defaultValue = "false") String unwrap,
      @RequestParam(name = "parseCdata",        defaultValue = "false") String parseCdata,
      @RequestParam(name = "flattenAttributes", defaultValue = "false") String flattenAttributes,
      @RequestParam(name = "coerceNumbers",     defaultValue = "false") String coerceNumbers,
      @RequestParam(name = "pretty",            defaultValue = "true")  String pretty,
      // Catch ALL query params (including future ones) so nothing is lost:
      @RequestParam MultiValueMap<String, String> allParams
  ) throws Exception {

    // Build the param map we pass into XSLT + service.
    Map<String,String> p = new HashMap<>();

    // 1) Start with everything from the request (first value per key):
    for (Map.Entry<String, java.util.List<String>> e : allParams.entrySet()) {
      if (!e.getValue().isEmpty()) p.put(e.getKey(), e.getValue().get(0));
    }

    // 2) Ensure our known flags are definitely present (override if needed):
    p.put("unwrap", unwrap);
    p.put("parseCdata", parseCdata);
    p.put("flattenAttributes", flattenAttributes);
    p.put("coerceNumbers", coerceNumbers);

    // 3) pretty is a server-only concern, don’t forward to XSLT if you don’t want to:
    boolean prettyFlag = "true".equalsIgnoreCase(pretty);
    // If you prefer NOT to expose "pretty" to XSLT, uncomment next line:
    // p.remove("pretty");

    return service.transform(xml, p, prettyFlag);
  }
}



*********

  <!-- src/main/resources/static/index.html -->
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8"/>
  <title>Raj Tools — XML → JSON (XSLT)</title>
  <meta name="viewport" content="width=device-width, initial-scale=1"/>
  <style>
    :root{
      --bg1:#0b1023; --bg2:#0f172a; --panel:#0c1426; --ink:#e7edf9; --muted:#9fb0c9;
      --border:rgba(255,255,255,.08); --acc1:#22d3ee; --acc2:#8b5cf6; --ok:#7ee787; --err:#ffb4ab;
    }
    *{box-sizing:border-box}
    html,body{height:100%}
    body{
      margin:0; color:var(--ink);
      background:
        radial-gradient(1100px 550px at 10% -10%, #192a68 10%, transparent 70%),
        radial-gradient(1200px 600px at 100% 0%, #1b0f35 6%, transparent 60%),
        linear-gradient(135deg,var(--bg1),var(--bg2));
      font-family: Inter, ui-sans-serif, -apple-system, Segoe UI, Roboto, Helvetica, Arial;
      letter-spacing:.2px;
    }
    /* Top bar */
    .nav{
      position:sticky; top:0; z-index:20;
      display:flex; align-items:center; justify-content:space-between; gap:16px;
      padding:16px 22px; backdrop-filter:saturate(140%) blur(8px);
      background:linear-gradient(180deg, rgba(9,11,31,.75), rgba(9,11,31,.35));
      border-bottom:1px solid var(--border);
    }
    .brand{display:flex; align-items:center; gap:12px; font-weight:800; font-size:18px}
    .logo{
      width:28px; height:28px; border-radius:8px;
      background:conic-gradient(from 210deg,var(--acc1),var(--acc2) 45%, #36d399 75%, var(--acc1));
      box-shadow:0 0 0 2px rgba(255,255,255,.06), 0 8px 20px rgba(98,87,255,.25) inset;
    }
    .badge{font-size:12px; color:var(--muted)}
    .kbd{font:600 11px ui-monospace, Menlo, monospace; padding:2px 6px; border-radius:6px;
      border:1px solid var(--border); color:var(--muted); background:#0a0f20}

    .container{max-width:1120px; margin:32px auto 56px; padding:0 20px}
    .hero{display:flex; align-items:flex-end; justify-content:space-between; gap:16px; margin-bottom:16px}
    .hero h1{margin:0; font-size:28px; font-weight:800}
    .hero p{margin:6px 0 0; color:var(--muted); font-size:13px}

    .grid{display:grid; grid-template-columns:1fr 1fr; gap:18px}
    @media (max-width: 980px){ .grid{grid-template-columns:1fr} }

    .card{
      background:linear-gradient(180deg, rgba(18,24,42,.82), rgba(12,16,30,.67));
      border:1px solid var(--border); border-radius:18px; padding:16px;
      box-shadow:0 12px 30px rgba(0,0,0,.35), inset 0 1px 0 rgba(255,255,255,.04);
    }
    .bar{display:flex; align-items:center; justify-content:space-between; margin-bottom:10px}
    .head{font-size:12px; color:var(--muted)}
    .status{font-size:12px; color:var(--muted)}
    .status.ok{color:var(--ok)} .status.err{color:var(--err)}

    textarea, pre{
      width:100%; min-height:440px; resize:vertical; border-radius:14px; border:1px solid var(--border);
      background:#0a0f20; color:var(--ink); padding:12px 14px;
      font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, "Liberation Mono", monospace;
      font-size:13px; line-height:1.5; outline:none; transition: box-shadow .2s, border-color .2s;
    }
    textarea:focus{border-color:rgba(98,87,255,.7); box-shadow:0 0 0 3px rgba(98,87,255,.25)}
    pre{margin:0; white-space:pre-wrap; word-break:break-word}

    .row{display:flex; gap:12px; flex-wrap:wrap; align-items:center; margin-top:12px}
    .chip{
      display:flex; align-items:center; gap:8px; padding:8px 12px; border-radius:999px;
      background:#0a0f20; border:1px solid var(--border); user-select:none;
    }
    .chip input{transform:scale(1.1)}

    .btn{
      border:none; border-radius:999px; padding:11px 18px; font-weight:700;
      background:linear-gradient(135deg,var(--acc1),var(--acc2));
      color:#0b1023; cursor:pointer; box-shadow:0 10px 28px rgba(104,89,255,.35);
      transition:transform .04s ease-in-out, box-shadow .2s;
    }
    .btn:active{transform:translateY(1px)}
    .btn[disabled]{opacity:.55; cursor:not-allowed}
    .ghost{
      border:1px solid var(--border); background:#0a0f20; color:var(--ink);
      padding:8px 12px; border-radius:999px; cursor:pointer; font-weight:600;
    }

    .actions{display:flex; gap:10px; justify-content:flex-end; margin-top:10px}
    footer{text-align:center; color:var(--muted); font-size:12px; margin-top:10px}
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
    <div>
      <span class="kbd">⌘/Ctrl</span> <span class="badge">+ Enter to Convert</span>
    </div>
  </nav>

  <div class="container">
    <section class="hero">
      <div>
        <h1>Convert XML to JSON using your XSLT</h1>
        <p>Paste XML, toggle options, and convert. Output is validated and pretty-printed server-side.</p>
      </div>
    </section>

    <div class="grid">
      <!-- Left: XML input -->
      <section class="card">
        <div class="bar">
          <span class="head">XML input</span>
          <span class="badge">No uploads • paste only</span>
        </div>
        <textarea id="xml" placeholder="&lt;CreditApplication&gt;…&lt;/CreditApplication&gt;"></textarea>

        <div class="row" role="group" aria-label="Options">
          <label class="chip" title="Remove SOAP Envelope/Body and pass only the payload to XSLT">
            <input type="checkbox" id="unwrap"> <span>Unwrap SOAP</span>
          </label>
          <label class="chip" title="If a field contains embedded XML in CDATA/text, parse it">
            <input type="checkbox" id="parseCdata"> <span>Parse CDATA</span>
          </label>
          <label class="chip" title="Expose element attributes as JSON fields">
            <input type="checkbox" id="flattenAttributes"> <span>Flatten attributes</span>
          </label>
          <label class="chip" title="Emit numbers/booleans/null without quotes">
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
          <span class="head">JSON output</span>
          <span id="status" class="status">Ready</span>
        </div>
        <pre id="out" aria-live="polite"></pre>
        <div class="actions">
          <button id="copyBtn" class="ghost" title="Copy JSON">Copy</button>
          <button id="clearBtn" class="ghost" title="Clear">Clear</button>
        </div>
        <footer>Tip: Press <span class="kbd">⌘/Ctrl</span> + <span class="kbd">Enter</span> to convert</footer>
      </section>
    </div>
  </div>

  <script>
    const $ = id => document.getElementById(id);
    const xmlEl = $('xml'), outEl = $('out'), statusEl = $('status');
    const convertBtn = $('convertBtn'), copyBtn = $('copyBtn'), clearBtn = $('clearBtn');

    function setStatus(text, kind){
      statusEl.textContent = text;
      statusEl.className = 'status' + (kind ? ' ' + kind : '');
    }

    async function doConvert(){
      const xml = xmlEl.value.trim();
      if (!xml){ outEl.textContent = 'Please paste XML first.'; return; }

      // Build query string with all options
      const params = new URLSearchParams({
        unwrap: $('unwrap').checked,
        parseCdata: $('parseCdata').checked,
        flattenAttributes: $('flattenAttributes').checked,
        coerceNumbers: $('coerceNumbers').checked,
        pretty: $('pretty').checked
      });

      outEl.textContent = '';
      setStatus('Converting…');
      convertBtn.disabled = true;

      try{
        const res = await fetch('/convert/xml-to-json?' + params.toString(), {
          method: 'POST',
          headers: { 'Content-Type': 'application/xml' }, // SOAP works too
          body: xml
        });
        const text = await res.text();
        if (!res.ok) throw new Error(text || ('HTTP ' + res.status));
        outEl.textContent = text;
        setStatus('Done','ok');
      }catch(err){
        outEl.textContent = (err && err.message) ? err.message : String(err);
        setStatus('Error','err');
      }finally{
        convertBtn.disabled = false;
      }
    }

    convertBtn.addEventListener('click', doConvert);

    // Keyboard shortcut: Cmd/Ctrl + Enter
    window.addEventListener('keydown', (e) => {
      if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
        e.preventDefault();
        doConvert();
      }
    });

    copyBtn.addEventListener('click', async () => {
      const txt = outEl.textContent;
      if (!txt) return;
      try{
        await navigator.clipboard.writeText(txt);
        setStatus('Copied','ok');
        setTimeout(()=>setStatus('Ready'), 1200);
      }catch{
        setStatus('Copy failed','err');
      }
    });

    clearBtn.addEventListener('click', () => {
      xmlEl.value = '';
      outEl.textContent = '';
      $('unwrap').checked = false;
      $('parseCdata').checked = false;
      $('flattenAttributes').checked = false;
      $('coerceNumbers').checked = false;
      $('pretty').checked = true;
      setStatus('Ready');
      xmlEl.focus();
    });
  </script>
</body>
</html>


