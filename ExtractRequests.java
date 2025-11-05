HttpURLConnection conn = (HttpURLConnection) new URL(endpointUrl).openConnection();
    conn.setRequestMethod("POST");
    conn.setDoOutput(true);
    conn.setRequestProperty("Content-Type",
        soap12 ? "application/soap+xml; charset=utf-8" : "text/xml; charset=utf-8");
    if (bearerToken != null && !bearerToken.isEmpty()) {
        conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
    }

    try (OutputStream os = conn.getOutputStream()) {
        os.write(soapXml.getBytes(StandardCharsets.UTF_8));
    }
    InputStream is = (conn.getResponseCode() >= 200 && conn.getResponseCode() < 300)
        ? conn.getInputStream() : conn.getErrorStream();
    try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
        StringBuilder sb = new StringBuilder(); String line;
        while ((line = br.readLine()) != null) sb.append(line).append('\n');
        return sb.toString();
    }
}
