Path report = fileOutDir.resolve(base + "-diff-report.html");
writeHtmlReport(report, base, diffs, left, right);

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
      .append("td{background:#0b1420;bor
