static void writeVisualHtml() throws Exception {
    PrintWriter out = new PrintWriter("fsml-visual.html");

    out.println("<html>");
    out.println("<head>");
    out.println("<style>");
    out.println("body { font-family: Arial; background:#f4f6f8; margin:20px; }");
    out.println(".card { background:white; border-radius:8px; padding:12px; margin-bottom:14px;");
    out.println("        box-shadow:0 2px 6px rgba(0,0,0,0.15); }");
    out.println(".num { color:#2980b9; margin-left:12px; }");
    out.println(".cat { color:#27ae60; margin-left:12px; }");
    out.println(".action { color:#c0392b; font-weight:bold; margin-top:6px; }");
    out.println("svg { background:white; border:1px solid #ccc; margin-bottom:30px; }");
    out.println("</style>");
    out.println("</head>");
    out.println("<body>");

    out.println("<h1>FSML Decision Paths – Visual</h1>");

    /* ===== SVG FLOW ===== */

    int svgHeight = paths.size() * 80 + 100;
    out.println("<svg width='1400' height='" + svgHeight + "'>");

    int y = 40;
    for (Path p : paths) {
        int x = 40;

        out.println("<circle cx='" + x + "' cy='" + y + "' r='5' fill='#34495e'/>");

        for (Map.Entry<String, Interval> e : p.numeric.entrySet()) {
            int nx = x + 180;

            out.println("<line x1='" + x + "' y1='" + y +
                        "' x2='" + nx + "' y2='" + y +
                        "' stroke='#555'/>");

            out.println("<rect x='" + (nx - 70) + "' y='" + (y - 18) +
                        "' width='140' height='36' rx='6' fill='#ecf0f1'/>");

            out.println("<text x='" + nx + "' y='" + (y + 5) +
                        "' text-anchor='middle' font-size='11'>" +
                        esc(e.getKey() + " " + e.getValue()) +
                        "</text>");

            x = nx;
        }

        int ax = x + 200;
        out.println("<line x1='" + x + "' y1='" + y +
                    "' x2='" + ax + "' y2='" + y +
                    "' stroke='#555'/>");

        out.println("<rect x='" + (ax - 80) + "' y='" + (y - 20) +
                    "' width='160' height='40' rx='8' fill='#f9e79f' stroke='#d35400'/>");

        out.println("<text x='" + ax + "' y='" + (y + 6) +
                    "' text-anchor='middle' font-size='12'>" +
                    esc(p.action) +
                    "</text>");

        y += 80;
    }

    out.println("</svg>");

    /* ===== PATH CARDS ===== */

    int r = 1;
    for (Path p : paths) {
        out.println("<div class='card'>");
        out.println("<b>Rule " + (r++) + "</b>");

        for (Map.Entry<String, Interval> e : p.numeric.entrySet()) {
            out.println("<div class='num'>" +
                        esc(e.getKey() + " " + e.getValue()) +
                        "</div>");
        }

        for (Map.Entry<String, String> e : p.categorical.entrySet()) {
            out.println("<div class='cat'>" +
                        esc(e.getKey() + " = " + e.getValue()) +
                        "</div>");
        }

        out.println("<div class='action'>ACTION → " +
                    esc(p.action) +
                    "</div>");
        out.println("</div>");
    }

    out.println("</body>");
    out.println("</html>");
    out.close();
}
