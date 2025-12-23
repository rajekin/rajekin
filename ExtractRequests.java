static void writeVisualHtml() throws Exception {
    PrintWriter out = new PrintWriter("fsml-visual.html");

    out.println("<html><head><style>");
    out.println("body { font-family: Arial; background:#f4f6f8; margin:20px; }");
    out.println(".rule { background:white; border-radius:8px; padding:14px; margin-bottom:16px;");
    out.println("        box-shadow:0 2px 6px rgba(0,0,0,0.15); max-width:900px; }");
    out.println(".title { font-weight:bold; margin-bottom:8px; color:#2c3e50; }");
    out.println(".cond { margin-left:20px; margin-bottom:4px; }");
    out.println(".num { color:#2980b9; }");
    out.println(".cat { color:#27ae60; }");
    out.println(".action { margin-top:10px; font-weight:bold; color:#c0392b; }");
    out.println(".arrow { color:#7f8c8d; margin-right:6px; }");
    out.println("</style></head><body>");

    out.println("<h1>FSML Decision Paths</h1>");
    out.println("<p>Vertical, path-based view (conditions shown in evaluation order)</p>");

    int r = 1;
    for (Path p : paths) {
        out.println("<div class='rule'>");
        out.println("<div class='title'>Rule " + (r++) + "</div>");

        // Numeric conditions
        for (Map.Entry<String, Interval> e : p.numeric.entrySet()) {
            Interval i = e.getValue();

            // Skip full-domain noise
            Variable v = variables.get(e.getKey());
            if (v != null && i.min == v.min && i.max == v.max) continue;

            out.println("<div class='cond num'>");
            out.println("<span class='arrow'>➜</span>");
            out.println(esc(e.getKey()) + " " + esc(i.toString()));
            out.println("</div>");
        }

        // Categorical conditions
        for (Map.Entry<String, String> e : p.categorical.entrySet()) {
            out.println("<div class='cond cat'>");
            out.println("<span class='arrow'>➜</span>");
            out.println(esc(e.getKey()) + " = " + esc(e.getValue()));
            out.println("</div>");
        }

        out.println("<div class='action'>");
        out.println("✔ ACTION → " + esc(p.action));
        out.println("</div>");

        out.println("</div>");
    }

    out.println("</body></html>");
    out.close();
}
