package com.raj.utilities.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api/fico")
public class FicoProxyController {

    private final WebClient web;
    private final String tokenUrl;
    private final String scoreUrl;
    private final String clientId;
    private final String clientSecret;

    // NEW: header names / behavior for the secret-bearer token flow
    private final String tokenAuthHeaderName;    // usually "Authorization"
    private final String tokenAuthPrefix;        // usually "Bearer "
    private final String tokenClientIdHeader;    // optional header for client id ("" to disable)

    public FicoProxyController(
            WebClient.Builder webBuilder,
            @Value("${fico.auth.tokenUrl}") String tokenUrl,
            @Value("${fico.api.scoreUrl}") String scoreUrl,
            @Value("${fico.clientId}") String clientId,
            @Value("${fico.clientSecret}") String clientSecret,
            @Value("${fico.auth.headerToken.headerName:Authorization}") String tokenAuthHeaderName,
            @Value("${fico.auth.headerToken.prefix:Bearer }") String tokenAuthPrefix,
            @Value("${fico.auth.headerToken.clientIdHeader:}") String tokenClientIdHeader
    ) {
        this.web = webBuilder.build();
        this.tokenUrl = tokenUrl;
        this.scoreUrl = scoreUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.tokenAuthHeaderName = tokenAuthHeaderName;
        this.tokenAuthPrefix = tokenAuthPrefix;
        this.tokenClientIdHeader = tokenClientIdHeader;
    }

    // ... keep your original /api/fico/score method here if you want both flows ...

    /**
     * New flow:
     *  1) Call tokenUrl with headers:
     *     - Authorization: Bearer <clientSecret>   (configurable name/prefix)
     *     - [optional] <tokenClientIdHeader>: <clientId>
     *     Body is empty JSON by default (change if your server needs something else).
     *  2) Extract token from JSON body or header.
     *  3) Call scoreUrl with Authorization: Bearer <token> and the given request JSON.
     */
    @PostMapping(
        path = "/score-secret",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<?> scoreWithSecretBearer(@RequestBody String requestJson) {
        try {
            // 1) Get token with SECRET in header
            WebClient.RequestBodySpec tokenReq = web.post()
                    .uri(tokenUrl)
                    .headers(h -> {
                        h.add(tokenAuthHeaderName, tokenAuthPrefix + clientSecret);
                        if (tokenClientIdHeader != null && !tokenClientIdHeader.isBlank()) {
                            h.add(tokenClientIdHeader, clientId);
                        }
                        h.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
                    })
                    .contentType(MediaType.APPLICATION_JSON);

            // Some services require an empty body, some require {}.
            var tokenResp = tokenReq
                    .bodyValue("{}")
                    .retrieve()
                    .toEntity(String.class)
                    .timeout(Duration.ofSeconds(30))
                    .block();

            if (tokenResp == null) throw new IllegalStateException("Empty token response");

            // Extract token from header first (Authorization or X-Auth-Token), then from JSON body
            String headerTok = tokenResp.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            if (headerTok == null) headerTok = tokenResp.getHeaders().getFirst("X-Auth-Token");
            String token = stripBearer(headerTok);
            if (token == null) {
                String body = tokenResp.getBody() == null ? "" : tokenResp.getBody().trim();
                token = extractTokenFromBody(body);
            }
            if (token == null || token.isBlank()) throw new IllegalStateException("Token not found");

            // 2) Call scoring API with token
            String ficoResponse = web.post()
                    .uri(scoreUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(h -> h.setBearerAuth(token))
                    .bodyValue(requestJson)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(30))
                    .block();

            return ResponseEntity.ok(Map.of(
                    "requestSent", requestJson,
                    "ficoResponse", ficoResponse
            ));
        } catch (Exception ex) {
            return ResponseEntity.status(502).body(Map.of(
                    "error", "FICO call failed",
                    "message", ex.getMessage()
            ));
        }
    }

    /* ------- tiny helpers ------- */
    private static String stripBearer(String headerVal) {
        if (headerVal == null) return null;
        String s = headerVal.trim();
        String lower = s.toLowerCase();
        if (lower.startsWith("bearer ")) return s.substring(7).trim();
        return s.isEmpty() ? null : s;
    }

    @SuppressWarnings("unchecked")
    private static String extractTokenFromBody(String body) {
        if (body == null || body.isBlank()) return null;
        // try JSON keys: token / access_token / accessToken
        try {
            var map = new com.fasterxml.jackson.databind.ObjectMapper().readValue(body, Map.class);
            for (String k : new String[]{"token","access_token","accessToken"}) {
                Object v = map.get(k);
                if (v != null) return String.valueOf(v);
            }
            // nested data.token
            Object data = map.get("data");
            if (data instanceof Map) {
                Map<?,?> dm = (Map<?,?>) data;
                for (String k : new String[]{"token","access_token","accessToken"}) {
                    Object v = dm.get(k);
                    if (v != null) return String.valueOf(v);
                }
            }
        } catch (Exception ignore) {
            // not JSON → treat whole body as token
            return body;
        }
        return null;
    }
}
************************************************



# NEW (configurable header names/prefix for the token call)
fico.auth.headerToken.headerName=Authorization
fico.auth.headerToken.prefix=Bearer 
fico.auth.headerToken.clientIdHeader=   # e.g., X-Client-Id (leave blank to skip)
*************************************

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
    .row-actions{display:flex;gap:8px;flex-wrap:wrap}
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

      <!-- Fallback XML→JSON options (applies when stylesheet outputs XML or XSLT disabled) -->
      <label class="switch"><input type="checkbox" id="flatten" checked><span>Flatten attributes</span></label>
      <label class="switch"><input type="checkbox" id="coerce" checked><span>Coerce numbers</span></label>

      <div class="row-actions" style="margin-left:auto">
        <button id="convertBtn" class="btn">Convert</button>
        <button id="sendFicoBtn" class="btn">Send to FICO</button>
        <button id="sendFicoSecretBtn" class="btn">Send to FICO (Secret-Bearer)</button>
        <button id="clearBtn" class="btn secondary">Clear</button>
        <button id="copyBtn" class="btn secondary">Copy JSON</button>
      </div>
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
      </div>
    </div>

    <div style="margin-top:18px">
      <div class="col-head">FICO Response</div>
      <pre id="ficoOut" class="code">/* FICO response will appear here */</pre>
    </div>

    <div class="hint" id="status" style="margin-top:10px"></div>
  </div>
</div>

<script>
  const $ = (id) => document.getElementById(id);

  const xmlEl = $("xml");
  const jsonEl = $("json");
  const ficoOutEl = $("ficoOut");
  const statusEl = $("status");

  const unwrapEl = $("unwrap");
  const parseCdataEl = $("parseCdata");
  const lowerRootEl = $("lowerRoot");
  const useXsltEl = $("useXslt");
  const xsltPathEl = $("xsltPath");
  const xsltOutputIsJsonEl = $("xsltOutputIsJson");
  const flattenEl = $("flatten");
  const coerceEl = $("coerce");

  const convertBtn = $("convertBtn");
  const clearBtn = $("clearBtn");
  const copyBtn = $("copyBtn");
  const loadSampleBtn = $("loadSample");
  const sendFicoBtn = $("sendFicoBtn");
  const sendFicoSecretBtn = $("sendFicoSecretBtn");

  function setStatus(msg){ statusEl.textContent = msg || ""; }

  clearBtn.addEventListener("click", () => {
    xmlEl.value = "";
    jsonEl.textContent = "/* JSON will appear here */";
    ficoOutEl.textContent = "/* FICO response will appear here */";
    setStatus("");
  });

  copyBtn.addEventListener("click", async () => {
    try {
      await navigator.clipboard.writeText(jsonEl.textContent || "");
      setStatus("Copied JSON to clipboard.");
      setTimeout(() => setStatus(""), 1000);
    } catch {
      setStatus("Copy failed.");
    }
  });

  loadSampleBtn.addEventListener("click", () => {
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
    const enabled = useXsltEl.checked;
    xsltPathEl.disabled = !enabled;
    xsltOutputIsJsonEl.disabled = !enabled;
  });
  useXsltEl.dispatchEvent(new Event("change"));

  async function convert() {
    const xml = xmlEl.value.trim();
    ficoOutEl.textContent = "/* FICO response will appear here */";
    if (!xml) { jsonEl.textContent = "Please paste some XML."; return; }

    convertBtn.disabled = true;
    setStatus("Converting…");
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
      if (!resp.ok) {
        const t = await resp.text().catch(()=> "");
        throw new Error(t || ("HTTP " + resp.status));
      }
      const data = await resp.json();
      jsonEl.textContent = data.json || JSON.stringify(data, null, 2);
      setStatus("");
    } catch (e) {
      jsonEl.textContent = "Error: " + (e.message || e);
      setStatus("Conversion failed.");
    } finally {
      convertBtn.disabled = false;
    }
  }

  convertBtn.addEventListener("click", convert);

  function getJsonOrWarn() {
    const text = (jsonEl.textContent || "").trim();
    if (!text || text.startsWith("/*") || text.startsWith("Error")) {
      ficoOutEl.textContent = "No JSON to send. Click Convert first.";
      return null;
    }
    try {
      return JSON.parse(text);
    } catch {
      ficoOutEl.textContent = "The output panel does not contain valid JSON.";
      return null;
    }
  }

  async function sendToFico(endpoint) {
    const payload = getJsonOrWarn();
    if (!payload) return;

    setStatus("Sending to FICO…");
    ficoOutEl.textContent = "Sending…";
    try {
      const resp = await fetch(endpoint, {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify(payload)
      });
      const data = await resp.json();
      if (!resp.ok) throw new Error(data.message || JSON.stringify(data));
      const pretty = typeof data.ficoResponse === "string"
        ? tryPretty(data.ficoResponse)
        : JSON.stringify(data.ficoResponse, null, 2);
      ficoOutEl.textContent = pretty;
      setStatus("");
    } catch (e) {
      ficoOutEl.textContent = "Error: " + (e.message || e);
      setStatus("FICO call failed.");
    }
  }

  sendFicoBtn.addEventListener("click", () => sendToFico("/api/fico/score"));
  sendFicoSecretBtn.addEventListener("click", () => sendToFico("/api/fico/score-secret"));

  function tryPretty(x) {
    try { return JSON.stringify(JSON.parse(x), null, 2); } catch { return x; }
  }
</script>
</body>
</html>

    

    
