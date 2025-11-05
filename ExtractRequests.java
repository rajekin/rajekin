import org.apache.hc.client5.http.classic.CloseableHttpClient;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClientBuilder;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.io.ResponseHandler;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

public class SoapWithHttpClient5 {

    public static void main(String[] args) throws Exception {
        // ---- Your values here ----
        String tokenUrl     = "https://your-token-endpoint.example.com/oauth/token";
        String clientId     = "YOUR_CLIENT_ID";
        String clientSecret = "YOUR_CLIENT_SECRET";

        // SOAP endpoint (FICO ProcessServer example)
        String soapEndpoint = "https://omdm-i80qkigqx3.dms.uswt2.ficoanalyticcloud.com/services/ProcessServer";

        // You said you already have this:
        String soap = buildSoapBodyForInvoker("<your-raw-xml-here>"); // keep your existing helper

        // If the service requires SOAP 1.2, flip this to true
        boolean useSoap12 = false;

        try (CloseableHttpClient http = HttpClients.custom().build()) {
            String token = fetchBearerToken(http, tokenUrl, clientId, clientSecret);
            String response = postSoap(http, soapEndpoint, soap, token, useSoap12);
            System.out.println(response);
        }
    }

    // -------- TOKEN (JSON-in, token-out) --------
    private static String fetchBearerToken(CloseableHttpClient http, String tokenUrl,
                                           String clientId, String clientSecret) throws Exception {
        HttpPost post = new HttpPost(tokenUrl);
        post.setHeader("Content-Type", "application/json; charset=utf-8");

        // Adjust to your token service contract if needed
        String json = "{\"clientId\":\"" + escapeJson(clientId) + "\",\"clientSecret\":\"" + escapeJson(clientSecret) + "\"}";
        post.setEntity(new StringEntity(json, ContentType.APPLICATION_JSON.withCharset(StandardCharsets.UTF_8)));

        ResponseHandler<String> handler = (ClassicHttpResponse resp) -> {
            int code = resp.getCode();
            String body = entityToString(resp.getEntity());
            if (code / 100 != 2) {
                throw new RuntimeException("Token call failed: HTTP " + code + " - " + body);
            }
            ObjectMapper om = new ObjectMapper();
            JsonNode root = om.readTree(body);
            // common fields: "access_token" or "token"
            String token = root.path("access_token").asText(null);
            if (token == null || token.isEmpty()) token = root.path("token").asText(null);
            if (token == null || token.isEmpty()) {
                throw new RuntimeException("Token not found in response: " + body);
            }
            return token;
        };

        return http.execute(post, handler);
    }

    // -------- SOAP POST --------
    private static String postSoap(CloseableHttpClient http, String endpointUrl, String soapXml,
                                   String bearerToken, boolean soap12) throws Exception {
        HttpPost post = new HttpPost(endpointUrl);
        post.setHeader("Authorization", "Bearer " + bearerToken);
        post.setHeader("Content-Type",
                (soap12 ? "application/soap+xml" : "text/xml") + "; charset=utf-8");
        // Per your requirement: no SOAPAction header
        post.setEntity(new StringEntity(soapXml, ContentType.TEXT_XML.withCharset(StandardCharsets.UTF_8)));

        ResponseHandler<String> handler = (ClassicHttpResponse resp) -> {
            int code = resp.getCode();
            String body = entityToString(resp.getEntity());
            if (code / 100 != 2) {
                throw new RuntimeException("SOAP call failed: HTTP " + code + " - " + body);
            }
            return body;
        };

        return http.execute(post, handler);
    }

    private static String entityToString(HttpEntity entity) throws Exception {
        return entity == null ? "" : EntityUtils.toString(entity, StandardCharsets.UTF_8);
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ---- Your existing helper (unchanged) ----
    public static String buildSoapBodyForInvoker(String rawXml) {
        String safe = rawXml.replace("]]>", "]]]]><![CDATA[>");
        return ""
            + "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\""
            + " xmlns:inv=\"http://invoker.ps.eos.fairisaac.com\">"
            + "<soapenv:Header/>"
            + "<soapenv:Body>"
            + "<inv:inputXml><![CDATA[" + safe + "]]></inv:inputXml>"
            + "</soapenv:Body>"
            + "</soapenv:Envelope>";
    }
}
