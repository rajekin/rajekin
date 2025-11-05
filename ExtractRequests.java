private static void writeHtmlReport(Path htmlPath, String baseName,
                                    List<Diff> diffs, JsonNode leftJson, JsonNode rightJson) throws IOException {
    int missingLeft  = (int) diffs.stream().filter(d -> d.kind == DiffKind.MISSING_LEFT).count();
    int missingRight = (int) diffs.stream().filter(d -> d.kind == DiffKind.MISSING_RIGHT).count();
    int typeMismatch = (int) diffs.stream().filter(d -> d.kind == DiffKind.TYPE_MISMATCH).count();
    int valueMismatch= (int) diffs.stream().filter(d -> d.kind == DiffKind.VALUE_MISMATCH).count();

    String title = "Diff Report • " + baseName;
    String leftPretty  = prettyJsonOrRaw(leftJson);
    String rightPretty = prettyJsonOrRaw(rightJson);

    StringBuilder sb = new StringBuilder(64_000);
    sb.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"/>")
      .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"/>")
      .append("<title>").append(esc(title)).append("</title>")
      // --- Minimal, clean CSS ---
      .append("<style>")
      .append("body{font-family:Inter,system-ui,-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;margin:24px;background:#0b0f14;color:#e6edf3}")
      .append(".card{background:#0f1722;border:1px solid #1f2a3a;border-radius:16px;box-shadow:0 8px 24px rgba(0,0,0,.35);}")
      .append(".hdr{display:flex;gap:12px;align-items:center;flex-wrap:wrap;padding:20px 22px;border-bottom:1px solid #1f2a3a}")
      .append(".pill{padding:.25rem .6rem;border-radius:999px;font-size:.78rem;font-weight:600}")
      .append(".ok{background:#12291a;color:#75f0a3;border:1px solid #1f6b3a}")
      .append(".warn{background:#2b1a1a;color:#ffb4b4;border:1px solid #7a2e2e}")
      .append(".muted{color:#9fb3c8}")
      .append(".content{padding:18px 22px}")
      .append("table{width:100%;border-collapse:separate;border-spacing:0 8px}")
      .append("th{font-size:.85rem;color:#9fb3c8;text-align:left;padding:8px 10px}")
      .append("td{background:#0b1420;border:1px solid #1f2a3a;padding:10px;border-radius:10px}")
      .append(".row{transition:.15s transform}")
      .append(".row:hover{transform:translateY(-1px)}")
      .append(".k-MISSING_LEFT{border-left:4px solid #ff6363}")
      .append(".k-MISSING_RIGHT{border-left:4px solid #ffa14a}")
      .append(".k-TYPE_MISMATCH{border-left:4px solid #5ea0ff}")
      .append(".k-VALUE_MISMATCH{border-left:4px solid #ffd166}")
      .append(".btn{cursor:pointer;border:1px solid #294057;background:#0b1420;color:#cfe3ff;border-radius:10px;padding:8px 12px;font-size:.85rem}")
      .append(".btn:active{transform:translateY(1px)}")
      .append("details{background:#0b1420;border:1px solid #1f2a3a;border-radius:12px;padding:12px 14px}")
      .append("summary{cursor:pointer;color:#9fb3c8}")
      .append("pre{white-space:pre-wrap;word-wrap:break-word;background:#07111b;border:1px solid #1a2533;border-radius:12px;padding:12px;color:#d6e6ff;overflow:auto}")
      .append(".grid{display:grid;grid-template-columns:1fr 1fr;gap:16px}")
      .append("@media(max-width:900px){.grid{grid-template-columns:1fr}}")
      .append("</style>")
      // --- Tiny JS for filtering by kind/search ---
      .append("<script>")
      .append("function filterKind(k){document.querySelectorAll('tr.row').forEach(r=>{r.style.display=(k==='ALL'||r.dataset.kind===k)?'':'none';});}")
      .append("function searchPaths(){let q=document.getElementById('q').value.toLowerCase();")
      .append("document.querySelectorAll('tr.row').forEach(r=>{let jp=r.dataset.jp.toLowerCase();let xp=r.dataset.xp.toLowerCase();")
      .append("r.style.display=(jp.includes(q)||xp.includes(q))?'':'none';});}")
      .append("function resetFilters(){document.getElementById('q').value='';filterKind('ALL');}")
      .append("</script>")
      .append("</head><body>");

    // Header card
    sb.append("<div class='card'><div class='hdr'>")
      .append("<div style='font-weight:700;font-size:1.1rem'>").append(esc(title)).append("</div>")
      .append("<span class='pill ok'>Total: ").append(diffs.size()).append("</span>")
      .append("<span class='pill warn'>Missing L: ").append(missingLeft).append("</span>")
      .append("<span class='pill warn'>Missing R: ").append(missingRight).append("</span>")
      .append("<span class='pill' style='background:#11253a;color:#8fc1ff;border:1px solid #1e4c7a'>Type: ").append(typeMismatch).append("</span>")
      .append("<span class='pill' style='background:#3a2b11;color:#ffe08f;border:1px solid #7a5a1e'>Value: ").append(valueMismatch).append("</span>")
      .append("<span class='muted' style='margin-left:auto'>").append(esc(java.time.LocalDateTime.now().toString())).append("</span>")
      .append("</div><div class='content'>");

    // Controls
    sb.append("<div style='display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin-bottom:14px'>")
      .append("<button class='btn' onclick=\"filterKind('ALL')\">All</button>")
      .append("<button class='btn' onclick=\"filterKind('MISSING_LEFT')\">Missing Left</button>")
      .append("<button class='btn' onclick=\"filterKind('MISSING_RIGHT')\">Missing Right</button>")
      .append("<button class='btn' onclick=\"filterKind('TYPE_MISMATCH')\">Type</button>")
      .append("<button class='btn' onclick=\"filterKind('VALUE_MISMATCH')\">Value</button>")
      .append("<input id='q' class='btn' style='min-width:260px' placeholder='Search path…' oninput='searchPaths()'/>")
      .append("<button class='btn' onclick='resetFilters()'>Reset</button>")
      .append("</div>");

    // Table
    sb.append("<table><thead><tr>")
      .append("<th>Kind</th><th>JSON Pointer</th><th>XPath</th><th>Left</th><th>Right</th>")
      .append("</tr></thead><tbody>");

    for (Diff d : diffs) {
        sb.append("<tr class='row k-").append(d.kind).append("' ")
          .append("data-kind='").append(d.kind).append("' ")
          .append("data-jp='").append(escAttr(d.jsonPointer)).append("' ")
          .append("data-xp='").append(escAttr(d.xPath)).append("'>")
          .append("<td>").append(esc(d.kind.toString())).append("</td>")
          .append("<td>").append(esc(d.jsonPointer)).append("</td>")
          .append("<td>").append(esc(d.xPath)).append("</td>")
          .append("<td>").append(esc(prettyValue(d.left))).append("</td>")
          .append("<td>").append(esc(prettyValue(d.right))).append("</td>")
          .append("</tr>");
    }
    sb.append("</tbody></table>");

    // Raw snapshots (collapsible)
    sb.append("<div style='margin-top:18px' class='grid'>")
      .append("<details open><summary>Left (SOAP → XML → JSON)</summary><pre>")
      .append(esc(leftPretty)).append("</pre></details>")
      .append("<details open><summary>Right (JSON endpoint)</summary><pre>")
      .append(esc(rightPretty)).append("</pre></details>")
      .append("</div>");

    sb.append("</div></div></body></html>");

    writeAtomic(htmlPath, sb.toString());
}

private static String prettyJsonOrRaw(JsonNode n) {
    try { return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(n); }
    catch (Exception ignore) { return String.valueOf(n); }
}

private static String prettyValue(String s) {
    if (s == null) return "∅";
    String t = s.trim();
    if ((t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"))) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter()
                         .writeValueAsString(MAPPER.readTree(t));
        } catch (Exception ignore) { /* fall through */ }
    }
    return t;
}

private static String esc(String s) {
    if (s == null) return "";
    return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
            .replace("\"","&quot;").replace("'","&#39;");
}
private static String escAttr(String s) { return esc(s); }
