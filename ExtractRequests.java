package xmlUI;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;

import javax.xml.transform.*;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class XmlSoapJsonRoundtripRunner {

    // ====================== EDIT THESE ======================
    private static final String XML_INPUT_DIR   = "C:\\work\\xml-in";     // input XMLs
    private static final String OUTPUT_DIR      = "C:\\work\\out";        // outputs/logs/reports

    // XSLT must convert XML -> JSON text
    private static final String XSLT_PATH       = "C:\\work\\xsl\\xml-to-json.xsl";
    // If the XSLT is on classpath, use: "classpath:/xsl/xml-to-json.xsl"

    // ----- Endpoint 1 (SOAP + CDATA) -----
    private static final String TOKEN1_URL      = "https://api.example.com/token1";
    private static final String TOKEN1_CLIENT   = "client1";
    private static final String TOKEN1_SECRET   = "secret1";
    private static final String ENDPOINT1_URL   = "https://api.example.com/soap/process";
    // SOAP Content-Type: change if service requires "application/soap+xml"
    private static final String SOAP_CONTENT_TYPE = "text/xml";

    // ----- Endpoint 2 (JSON) -----
    private static final String TOKEN2_URL      = "https://api.example.com/token2";
    private static final String TOKEN2_CLIENT   = "client2";
    private static final String TOKEN2_SECRET   = "secret2";
    private static final String ENDPOINT2_URL   = "https://api.example.com/json/process";
    // ========================================================

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        Path inDir  = Paths.get(XML_INPUT_DIR).toAbsolutePath().normalize();
        Path outDir = Paths.get(OUTPUT_DIR).toAbsolutePath().normalize();
        if (!Files.isDirectory(inDir)) {
            System.err.println("Input directory not found: " + inDir);
            return;
        }
        Files.createDirectories(outDir);

        RequestConfig rc = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(30))
                .setResponseTimeout(Timeout.ofSeconds(90))
                .build();

        try (CloseableHttpClient http = HttpClients.custom().setDefaultRequestConfig(rc).build()) {

            // Acquire both tokens first
            String token1 = fetchBearerToken(http, TOKEN1_URL, TOKEN1_CLIENT, TOKEN1_SECRET);
            String token2 = fetchBearerToken(http, TOKEN2_URL, TOKEN2_CLIENT, TOKEN2_SECRET);
            if (isBlank(token1) || isBlank(token2)) {
                System.err.println("Failed to acquire token(s); aborting.");
                return;
            }
            System.out.println("Tokens acquired at " + LocalDateTime.now());

            Transformer xsltToJson = buildXslt(XSLT_PATH);

            AtomicInteger ok = new AtomicInteger();
            AtomicInteger fail = new AtomicInteger();

            try (var walk = Files.walk(inDir)) {
                walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".xml"))
                    .forEach(xmlPath -> {
                        String base = baseName(xmlPath.getFileName().toString());
                        Path fileOutDir = outDir.resolve(base);
                        try {
                            Files.createDirectories(fileOutDir);

                            String originalXml = Files.readString(xmlPath, StandardCharsets.UTF_8);

                            // 1) SOAP (with CDATA)
                            String soapBody = cdataWrap(originalXml);
                            String soapEnvelope = buildSoapEnvelope(soapBody);
                            String resp1Xml = postTextWithBearer(http, ENDPOINT1_URL, soapEnvelope, token1, SOAP_CONTENT_TYPE);

                            // Save SOAP artifacts
                            writeAtomic(fileOutDir.resolve(base + "-soap-request.xml"), soapEnvelope);
                            writeAtomic(fileOutDir.resolve(base + "-soap-response.xml"), resp1Xml);

                            // 2) ORIGINAL XML -> JSON via XSLT; lowercase root key
                            String jsonFromOriginal = transformXmlToJson(xsltToJson, originalXml);
                            String jsonLowerRoot = lowercaseRootKey(jsonFromOriginal);
                            writeAtomic(fileOutDir.resolve(base + "-json-from-xml.json"), jsonLowerRoot);

                            // 3) Call Endpoint 2 with JSON
                            String resp2Json = postJsonWithBearer(http, ENDPOINT2_URL, jsonLowerRoot, token2);
                            writeAtomic(fileOutDir.resolve(base + "-endpoint2-response.json"), resp2Json);

                            // 4) Endpoint1 XML response -> JSON (same XSLT), lowercase root
                            String jsonFromResp1 = transformXmlToJson(xsltToJson, resp1Xml);
                            String jsonFromResp1Lower = lowercaseRootKey(jsonFromResp1);
                            writeAtomic(fileOutDir.resolve(base + "-json-from-endpoint1.xml.json"), jsonFromResp1Lower);

                            // 5) Order-sensitive diff (JSON vs JSON)
                            JsonNode left = parseJson(jsonFromResp1Lower);  // from Endpoint 1 (converted)
                            JsonNode right = parseJson(resp2Json);          // from Endpoint 2 (native)
                            List<Diff> diffs = new ArrayList<>();
                            diffJson(JsonPointer.empty(), left, right, diffs);

                            Path report = fileOutDir.resolve(base + "-diff-report.csv");
                            writeCsvReport(report, diffs);

                            System.out.println("✓ " + xmlPath.getFileName() + " -> " + report.getFileName());
                            ok.incrementAndGet();

                        } catch (Exception e) {
                            System.err.println("✗ " + xmlPath.getFileName() + " :: " + e.getMessage());
                            try {
                                Files.writeString(fileOutDir.resolve(base + "-ERROR.txt"),
                                        e.toString(), StandardCharsets.UTF_8,
                                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                            } catch (IOException ignore) {}
                            fail.incrementAndGet();
                        }
                    });
            }

            System.out.println("Done. Success: " + ok.get() + ", Failed: " + fail.get());
            System.out.println("Output root: " + outDir);
        }
    }

    // ====================== HTTP ======================

    private static String fetchBearerToken(CloseableHttpClient http, String tokenUrl, String client, String secret) throws IOException {
        HttpPost post = new HttpPost(tokenUrl);
        post.setHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.getMimeType());
        String payload = MAPPER.createObjectNode().put("client", client).put("secret", secret).toString();
        post.setEntity(new StringEntity(payload, ContentType.APPLICATION_JSON));

        return http.execute(post, response -> {
            int code = response.getCode();
            String text = response.getEntity() == null ? "" : EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            if (code / 100 != 2) throw new IOException("Token HTTP " + code + ": " + text);

            JsonNode n = safeJson(text);
            if (n != null) {
                if (n.hasNonNull("access_token")) return n.get("access_token").asText();
                if (n.hasNonNull("token"))        return n.get("token").asText();
                if (n.hasNonNull("bearer"))       return n.get("bearer").asText();
                if (n.hasNonNull("jwt"))          return n.get("jwt").asText();
                for (JsonNode v : n) if (v.isTextual()) return v.asText(); // fallback
            }
            String trimmed = text == null ? "" : text.trim();
            return (!trimmed.isEmpty() && !trimmed.startsWith("{") && !trimmed.startsWith("[")) ? trimmed : null;
        });
    }

    private static String postTextWithBearer(CloseableHttpClient http, String url, String textBody, String token, String contentType) throws IOException {
        HttpPost post = new HttpPost(url);
        post.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        post.setHeader(HttpHeaders.CONTENT_TYPE, contentType);
        post.setEntity(new StringEntity(textBody, ContentType.parse(contentType)));

        return http.execute(post, response -> {
            int code = response.getCode();
            String text = response.getEntity() == null ? "" : EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            if (code / 100 != 2) throw new IOException("Endpoint1 HTTP " + code + ": " + text);
            return text;
        });
    }

    private static String postJsonWithBearer(CloseableHttpClient http, String url, String json, String token) throws IOException {
        HttpPost post = new HttpPost(url);
        post.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        post.setHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.getMimeType());
        post.setEntity(new StringEntity(json, ContentType.APPLICATION_JSON));

        return http.execute(post, response -> {
            int code = response.getCode();
            String text = response.getEntity() == null ? "" : EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            if (code / 100 != 2) throw new IOException("Endpoint2 HTTP " + code + ": " + text);
            return text;
        });
    }

    // ====================== SOAP + CDATA ======================

    private static String cdataWrap(String xml) {
        return "<![CDATA[" + xml + "]]>";
    }

    // Minimal SOAP envelope; adjust namespaces and body wrapper to the service contract if needed.
    private static String buildSoapEnvelope(String cdataBody) {
        return ""
            + "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\">"
            + "  <soapenv:Header/>"
            + "  <soapenv:Body>" + cdataBody + "</soapenv:Body>"
            + "</soapenv:Envelope>";
    }

    // ====================== XSLT ======================

    private static Transformer buildXslt(String xsltPath) throws Exception {
        TransformerFactory tf = TransformerFactory.newInstance();
        Source xsltSrc;
        if (xsltPath.startsWith("classpath:")) {
            String cp = xsltPath.replaceFirst("^classpath:/*", "");
            InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(cp);
            if (in == null) throw new FileNotFoundException("XSLT not on classpath: " + cp);
            xsltSrc = new StreamSource(in);
        } else {
            xsltSrc = new StreamSource(new File(xsltPath));
        }
        Transformer t = tf.newTransformer(xsltSrc);
        // t.setParameter("paramName", "value");  // if your XSLT expects params
        return t;
    }

    private static String transformXmlToJson(Transformer t, String xml) throws Exception {
        StringWriter out = new StringWriter();
        t.transform(new StreamSource(new StringReader(xml)), new StreamResult(out));
        return out.toString();
    }

    // ====================== JSON Helpers ======================

    private static JsonNode parseJson(String s) throws IOException {
        return MAPPER.readTree(s);
    }

    // Lowercase the single root key if JSON is like {"Root": {...}}
    private static String lowercaseRootKey(String jsonText) throws IOException {
        JsonNode n = MAPPER.readTree(jsonText);
        if (n.isObject()) {
            ObjectNode obj = (ObjectNode) n;
            Iterator<String> it = obj.fieldNames();
            if (it.hasNext()) {
                String first = it.next();
                if (!it.hasNext()) {
                    JsonNode v = obj.remove(first);
                    obj.set(first.toLowerCase(Locale.ROOT), v);
                    return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
                }
            }
        }
        return jsonText;
    }

    private static JsonNode safeJson(String s) {
        try { return MAPPER.readTree(s); } catch (Exception ignore) { return null; }
    }

    // ====================== Diff (order-sensitive) ======================

    private enum DiffKind { MISSING_LEFT, MISSING_RIGHT, TYPE_MISMATCH, VALUE_MISMATCH }

    private static final class Diff {
        final String jsonPointer;  // RFC 6901 pointer
        final String xPath;        // XPath-like path (/root/items[3]/id)
        final DiffKind kind;
        final String left;
        final String right;
        Diff(String jsonPointer, String xPath, DiffKind kind, String left, String right) {
            this.jsonPointer = jsonPointer;
            this.xPath = xPath;
            this.kind = kind;
            this.left = left;
            this.right = right;
        }
    }

    private static void diffJson(JsonPointer path, JsonNode left, JsonNode right, List<Diff> out) {
        if (left == null && right == null) return;

        if (left == null) {
            out.add(new Diff(path.toString(), jsonPointerToXPath(path), DiffKind.MISSING_LEFT, "∅", str(right)));
            return;
        }
        if (right == null) {
            out.add(new Diff(path.toString(), jsonPointerToXPath(path), DiffKind.MISSING_RIGHT, str(left), "∅"));
            return;
        }

        if (!typeKey(left).equals(typeKey(right))) {
            out.add(new Diff(path.toString(), jsonPointerToXPath(path), DiffKind.TYPE_MISMATCH, str(left), str(right)));
            return;
        }

        if (left.isObject()) {
            Set<String> keys = new TreeSet<>();
            left.fieldNames().forEachRemaining(keys::add);
            right.fieldNames().forEachRemaining(keys::add);
            for (String k : keys) {
                JsonPointer next = path.append(JsonPointer.compile("/" + escapeJsonPointer(k)));
                diffJson(next, left.get(k), right.get(k), out);
            }
            return;
        }

        if (left.isArray()) {
            // ORDER-SENSITIVE: compare by index
            int max = Math.max(left.size(), right.size());
            for (int i = 0; i < max; i++) {
                JsonNode li = i < left.size() ? left.get(i) : null;
                JsonNode ri = i < right.size() ? right.get(i) : null;
                JsonPointer next = path.append(JsonPointer.compile("/" + i));
                diffJson(next, li, ri, out);
            }
            return;
        }

        // scalars: slightly lenient (e.g., "123" == 123, "true" == true)
        if (!looselyEqual(left, right)) {
            out.add(new Diff(path.toString(), jsonPointerToXPath(path), DiffKind.VALUE_MISMATCH, str(left), str(right)));
        }
    }

    private static String jsonPointerToXPath(JsonPointer ptr) {
        if (ptr == null || ptr.toString().isEmpty()) return "/";
        String[] tokens = ptr.toString().split("/");
        StringBuilder xp = new StringBuilder();
        String lastName = null;
        for (String t : tokens) {
            if (t.isEmpty()) continue;
            String unesc = t.replace("~1", "/").replace("~0", "~"); // RFC6901 unescape
            if (isNumeric(unesc)) {
                int idx = Integer.parseInt(unesc) + 1; // XPath is 1-based
                if (lastName != null) {
                    xp.append("[").append(idx).append("]");
                } else {
                    xp.append("/[").append(idx).append("]");
                }
            } else {
                xp.append("/").append(unesc);
                lastName = unesc;
            }
        }
        return xp.length() == 0 ? "/" : xp.toString();
    }

    private static boolean isNumeric(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    private static String escapeJsonPointer(String s) {
        return s.replace("~", "~0").replace("/", "~1");
    }

    private static String typeKey(JsonNode n) {
        if (n == null) return "null";
        if (n.isObject()) return "obj";
        if (n.isArray()) return "arr";
        if (n.isNumber()) return "num";
        if (n.isTextual()) return "str";
        if (n.isBoolean()) return "bool";
        if (n.isNull()) return "nullv";
        return "other";
    }

    private static String str(JsonNode n) {
        if (n == null || n.isNull()) return "∅";
        if (n.isContainerNode()) return n.toString();
        return n.asText();
    }

    private static boolean looselyEqual(JsonNode a, JsonNode b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;

        // null vs missing
        if (a.isNull() && b.isMissingNode()) return true;
        if (b.isNull() && a.isMissingNode()) return true;

        // numeric vs "123"
        if ((a.isNumber() && b.isTextual()) || (a.isTextual() && b.isNumber())) {
            try { return Double.compare(a.asDouble(), b.asDouble()) == 0; } catch (Exception ignore) {}
        }

        // boolean vs "true"/"false"
        if ((a.isBoolean() && b.isTextual()) || (a.isTextual() && b.isBoolean())) {
            String sa = a.asText().trim().toLowerCase();
            String sb = b.asText().trim().toLowerCase();
            if ((sa.equals("true") || sa.equals("false")) && (sb.equals("true") || sb.equals("false")))
                return sa.equals(sb);
        }
        // default textual compare
        return Objects.equals(a.asText(), b.asText());
    }

    // ====================== Files / Utils ======================

    private static void writeCsvReport(Path csv, List<Diff> diffs) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("kind,json_pointer,xpath,left,right\n");
        for (Diff d : diffs) {
            sb.append(d.kind).append(',')
              .append(csvEscape(d.jsonPointer)).append(',')
              .append(csvEscape(d.xPath)).append(',')
              .append(csvEscape(d.left)).append(',')
              .append(csvEscape(d.right)).append('\n');
        }
        writeAtomic(csv, sb.toString());
    }

    private static String csvEscape(String s) {
        if (s == null) return "";
        String q = s.replace("\"", "\"\"");
        return "\"" + q + "\"";
        }

    private static void writeAtomic(Path out, String content) throws IOException {
        Path tmp = out.resolveSibling(out.getFileName().toString() + ".tmp");
        Files.write(tmp, content.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String baseName(String fileName) {
        int i = fileName.lastIndexOf('.');
        return i >= 0 ? fileName.substring(0, i) : fileName;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
