package xmlUI;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class JsonFolderRestCaller_HC5 {

    // ====== EDIT THESE ======
    private static final String JSON_INPUT_DIR = "C:\\work\\json-in";
    private static final String OUTPUT_DIR     = "C:\\work\\json-out";

    private static final String TOKEN_URL      = "https://api.example.com/token";
    private static final String CLIENT_ID      = "yourClient";
    private static final String CLIENT_SECRET  = "yourSecret";

    private static final String REST_URL       = "https://api.example.com/process";
    // =========================

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        Path inDir  = Paths.get(JSON_INPUT_DIR).toAbsolutePath().normalize();
        Path outDir = Paths.get(OUTPUT_DIR).toAbsolutePath().normalize();
        if (!Files.isDirectory(inDir)) {
            System.err.println("Input directory not found: " + inDir);
            return;
        }
        Files.createDirectories(outDir);

        RequestConfig rc = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(30))
                .setResponseTimeout(Timeout.ofSeconds(60))
                .build();

        try (CloseableHttpClient http = HttpClients.custom()
                .setDefaultRequestConfig(rc)
                .build()) {

            String token = fetchBearerToken(http);
            if (token == null || token.isBlank()) {
                System.err.println("Failed to acquire token; aborting.");
                return;
            }
            System.out.println("Bearer token acquired.");

            AtomicInteger ok = new AtomicInteger();
            AtomicInteger fail = new AtomicInteger();

            try (var walk = Files.walk(inDir)) {
                walk.filter(Files::isRegularFile)
                    .filter(JsonFolderRestCaller_HC5::isJson)
                    .forEach(jsonPath -> {
                        try {
                            String reqJson = readString(jsonPath);
                            String respBody = postJsonWithBearer(http, REST_URL, reqJson, token);

                            String base = baseName(jsonPath.getFileName().toString());
                            boolean jsony = looksLikeJson(respBody);
                            Path out = outDir.resolve(base + "-resp" + (jsony ? ".json" : ".txt"));
                            writeAtomic(out, respBody == null ? "" : respBody);

                            System.out.println("✓ " + jsonPath.getFileName() + " -> " + out.getFileName());
                            ok.incrementAndGet();
                        } catch (Exception e) {
                            System.err.println("✗ " + jsonPath.getFileName() + " :: " + e.getMessage());
                            fail.incrementAndGet();
                        }
                    });
            }

            System.out.println("Done. Success: " + ok.get() + ", Failed: " + fail.get());
            System.out.println("Output folder: " + outDir);
        }
    }

    // ---- Token: POST {"client":"..","secret":".."} ----
    private static String fetchBearerToken(CloseableHttpClient http) throws IOException {
        HttpPost post = new HttpPost(TOKEN_URL);
        post.setHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.getMimeType());

        String body = MAPPER.createObjectNode()
                .put("client", CLIENT_ID)
                .put("secret", CLIENT_SECRET)
                .toString();
        post.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));

        return http.execute(post, response -> {
            int status = response.getCode();
            String text = response.getEntity() == null ? "" : EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);

            if (status / 100 != 2) {
                throw new IOException("Token HTTP " + status + ": " + text);
            }

            JsonNode node = safeParse(text);
            if (node != null) {
                if (node.hasNonNull("access_token")) return node.get("access_token").asText();
                if (node.hasNonNull("token"))        return node.get("token").asText();
                if (node.hasNonNull("bearer"))       return node.get("bearer").asText();
                if (node.hasNonNull("jwt"))          return node.get("jwt").asText();
                for (JsonNode v : node) if (v.isTextual()) return v.asText(); // fallback
            }
            String trimmed = text == null ? "" : text.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("{") && !trimmed.startsWith("[")) {
                return trimmed; // raw token string
            }
            return null;
        });
    }

    // ---- Business POST with Bearer ----
    private static String postJsonWithBearer(CloseableHttpClient http, String url, String json, String token) throws IOException {
        HttpPost post = new HttpPost(url);
        post.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        post.setHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.getMimeType());
        post.setEntity(new StringEntity(json, ContentType.APPLICATION_JSON));

        return http.execute(post, response -> {
            int status = response.getCode();
            String text = response.getEntity() == null ? "" : EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            if (status / 100 != 2) {
                throw new IOException("REST HTTP " + status + ": " + text);
            }
            return text;
        });
    }

    // ---- File helpers ----
    private static boolean isJson(Path p) {
        String n = p.getFileName().toString().toLowerCase();
        return n.endsWith(".json");
    }

    private static String baseName(String fileName) {
        int i = fileName.lastIndexOf('.');
        return i >= 0 ? fileName.substring(0, i) : fileName;
    }

    private static String readString(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeAtomic(Path out, String content) throws IOException {
        Path tmp = out.resolveSibling(out.getFileName().toString() + ".tmp");
        Files.write(tmp, content.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static boolean looksLikeJson(String s) {
        if (s == null) return false;
        String t = s.trim();
        return !t.isEmpty() && ((t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]")));
    }

    private static JsonNode safeParse(String s) {
        try { return MAPPER.readTree(s); } catch (Exception ignore) { return null; }
    }
}
