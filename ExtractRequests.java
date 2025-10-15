<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8"/>
  <title>XML/SOAP → JSON Converter · Raj Utilities</title>
  <meta name="viewport" content="width=device-width, initial-scale=1"/>
  <meta name="color-scheme" content="light dark">
  <style>
    :root { --fg:#111827; --muted:#6b7280; --bg:#ffffff; --card:#f9fafb; --border:#e5e7eb; --brand:#111827; --ok:#0f766e; --bad:#b91c1c; --codebg:#0b1020; --codefg:#e6edf3; }
    @media (prefers-color-scheme: dark) {
      :root { --fg:#e5e7eb; --muted:#9ca3af; --bg:#0b0f1a; --card:#0f1524; --border:#1f2937; --brand:#e5e7eb; --codebg:#0f1524; --codefg:#e5e7eb; }
    }
    * { box-sizing: border-box; }
    body { margin:24px; font-family: ui-sans-serif, system-ui, -apple-system, Segoe UI, Roboto, Arial, "Apple Color Emoji", "Segoe UI Emoji"; color:var(--fg); background:var(--bg); }
    .topbar { display:flex; align-items:center; justify-content:space-between; gap:12px; margin-bottom:12px; }
    h1 { margin:0; font-size:1.35rem; }
    a.back { text-decoration:none; color:#2563eb; }
    .panel { border:1px solid var(--border); border-radius:14px; background:var(--card); padding:14px; }
    .controls { display:flex; flex-wrap:wrap; gap:12px; align-items:center; margin: 12px 0 16px; }
    .controls label { display:flex; gap:8px; align-items:center; font-size:.95rem; }
    .btn { padding:10px 14px; border-radius:10px; border:0; cursor:pointer; background:var(--brand); color:#fff; font-weight:600; }
    .btn[disabled] { opacity:.6; cursor:not-allowed; }
    .btn.secondary { background:transparent; color:var(--fg); border:1px solid var(--border); }
    .grid { display:grid; grid-template-columns: 1fr 1fr; gap:16px; }
    @media (max-width: 980px) { .grid { grid-template-columns: 1fr; } }

    textarea { width:100%; min-height: 360px; max-height: 70vh; resize: vertical;
      font-family: ui-monospace,SFMono-Regular,Menlo,Consolas,monospace; font-size:13px;
      padding:12px; border-radius:12px; border:1px solid var(--border); background:var(--bg); color:var(--fg);
    }
    pre.code { white-space: pre-wrap; word-wrap: break-word; background: var(--codebg); color: var(--codefg);
      padding: 12px; border-radius:12px; min-height: 360px; margin:0; }
    .stats { display:flex; flex-wrap:wrap; gap:10px; margin-top:10px; }
    .stat { display:inline-flex; align-items:center; gap:6px; background:var(--card); border:1px solid var(--border);
      padding:6px 10px; border-radius:9px; font-size:.90rem; }
    .ok { color: var(--ok); }
    .bad { color: var(--bad); }
    .row-title { margin: 8px 0; font-size:.95rem; color:var(--muted); }
    .footer { margin-top:14px; font-size:.9rem; color:var(--muted); }
    .right-actions { display:flex; gap:8px; flex-wrap:wrap; }
  </style>
</head>
<body>
  <div class="topbar">
    <h1>XML/SOAP → JSON Converter</h1>
    <div class="right-actions">
      <a class="back" href="/">← Back to utilities</a>
      <button id="loadSample" class="btn secondary" title="Load a small SOAP+CDATA example">Load sample</button>
    </div>
  </div>

  <div class="panel">
    <div class="controls">
      <label><input type="checkbox" id="unwrap" checked> Unwrap SOAP Body</label>
      <label><input type="checkbox" id="parseCdata" checked> Parse XML inside CDATA/Text</label>
      <label><input type="checkbox" id="flatten" checked> Flatten attributes</label>
      <label><input type="checkbox" id="coerce" checked> Coerce numbers</label>
      <button id="convertBtn" class="btn">Convert</button>
      <button id="clearBtn" class="btn secondary">Clear</button>
      <span id="status" style="margin-left:auto;"></span>
    </div>

    <div class="grid">
      <div>
        <div class="row-title">Input (XML or SOAP)</div>
        <textarea id="xml" placeholder="Paste your XML or SOAP request here…"></textarea>
      </div>
      <div>
        <div class="row-title">Output (JSON)</div>
        <pre id="json" class="code">/* JSON will appear here */</pre>
        <div class="stats" id="stats"></div>
        <div class="controls" style="margin-top:10px">
          <button id="copyBtn" class="btn secondary">Copy JSON</button>
          <button id="downloadBtn" class="btn secondary">Download JSON</button>
        </div>
      </div>
    </div>

    <div class="footer">
      Tips: keep “Unwrap SOAP Body” on for SOAP envelopes; “Parse XML inside CDATA” is needed when your payload is nested in <![CDATA[ … ]]> like in your example.
    </div>
  </div>

  <script>
    const $ = (id) => document.getElementById(id);
    const btn = $("convertBtn");
    const xmlEl = $("xml");
    const jsonEl = $("json");
    const statusEl = $("status");
    const statsEl = $("stats");
    const unwrapEl = $("unwrap");
    const parseCdataEl = $("parseCdata");
    const flattenEl = $("flatten");
    const coerceEl = $("coerce");

    $("clearBtn").addEventListener("click", () => { xmlEl.value = ""; jsonEl.textContent = "/* JSON will appear here */"; statsEl.innerHTML=""; });
    $("copyBtn").addEventListener("click", async () => {
      try { await navigator.clipboard.writeText(jsonEl.textContent || ""); statusEl.textContent = "Copied!"; setTimeout(() => statusEl.textContent="", 1000);} catch {}
    });
    $("downloadBtn").addEventListener("click", () => {
      const blob = new Blob([jsonEl.textContent || ""], { type: "application/json" });
      const a = document.createElement("a");
      a.href = URL.createObjectURL(blob);
      a.download = "converted.json";
      document.body.appendChild(a); a.click(); a.remove();
      setTimeout(() => URL.revokeObjectURL(a.href), 0);
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
          <PAYMENT_PER_AMOUNT>1000</PAYMENT_PER_AMOUNT>
        </CreditApplication>
      </Application>
    ]]></inv:inputXml>
  </soapenv:Body>
</soapenv:Envelope>`;
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
          coerceNumbers: coerceEl.checked
        }).toString();

        const resp = await fetch("/api/xml-to-json?" + qs, {
          method: "POST",
          headers: { "Content-Type": "text/plain" },
          body: xml
        });
        if (!resp.ok) {
          const t = await resp.text();
          throw new Error(t || "Server error");
        }
        const data = await resp.json();
        jsonEl.textContent = data.json;

        const ok = data.allAttributesConverted;
        const cls = ok ? "ok" : "bad";
        statsEl.innerHTML = `
          <div class="stat">XML attributes: <strong>${data.xmlAttributeCount}</strong></div>
          <div class="stat">JSON attributes: <strong>${data.jsonAttributeCount}</strong></div>
          <div class="stat ${cls}">${ok ? "All attributes converted ✔" : "Missing attributes ⚠"}</div>
          ${!ok && data.missingAttributes?.length ? `<details style="margin-top:8px;"><summary>See missing</summary><pre class="code" style="min-height:auto; margin-top:8px;">${data.missingAttributes.join('\n')}</pre></details>` : ""}
        `;
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
