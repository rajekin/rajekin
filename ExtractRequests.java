private static String postTextWithBearer(
        CloseableHttpClient http,
        String url,
        String soapXml,
        String token,
        boolean soap12) throws IOException {

    HttpPost post = new HttpPost(url);

    // Authorization header
    post.setHeader("Authorization", "Bearer " + token);

    // Content-Type decided entirely inside this method (no external constants)
    if (soap12) {
        // SOAP 1.2
        post.setHeader("Content-Type", "application/soap+xml; charset=utf-8");
    } else {
        // SOAP 1.1
        post.setHeader("Content-Type", "text/xml; charset=utf-8");
    }

    // Request body
    post.setEntity(new StringEntity(soapXml, java.nio.charset.StandardCharsets.UTF_8));

    return http.execute(post, response -> {
        int code = response.getCode();
        String body = response.getEntity() == null
                ? ""
                : org.apache.hc.core5.http.io.entity.EntityUtils.toString(
                        response.getEntity(), java.nio.charset.StandardCharsets.UTF_8);
        if (code / 100 != 2) {
            throw new IOException("SOAP HTTP " + code + ": " + body);
        }
        return body;
    });
}
