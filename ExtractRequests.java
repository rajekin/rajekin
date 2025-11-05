package xmlUI;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHeaders;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class JsonFolderRestCaller {

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

        try (CloseableHttpClient http = HttpClients.custom()
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectTimeout(30_000)
                        .setSocketTimeout(60_000)
                        .build())
                .build()) {

            String token = fetchBearerToken(http);
            if (token == null || token.isEmpty()) {
                System.err.println("Failed to acquire token; aborting.");
                return;
            }
            System.out.println("Bearer token acquired.");

            AtomicInteger ok = new AtomicInteger();
            AtomicInteger fail = new AtomicInteger();

            try (var stream = Files.walk(inDir)) {
                stream.filter(Files::isRegularFile)
                      .filter(JsonFolderRestCaller::isJson)
                      .forEach(jsonPath -> {
                          try {
                              String reqJson = Files.readString(jsonPath, StandardCharsets.UTF_8);
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

    // ---- HTTP helpers ----

    private static String fetchBearerToken(CloseableHttpClient http) throws IOException {
        HttpPost post = new HttpPost(TOKEN_URL);
        post.setHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.getMimeType());

        // body: {"client":"...","secret":"..."}
        String body = MAPPER.createObjectNode()
                .put("client", CLIENT_ID)
                .put("secret", CLIENT_SECRET)
                .toString();
        post.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));

        try (CloseableHttpResponse resp = http.execute(post)) {
            int status = resp.getStatusLine().getStatusCode();
            HttpEntity ent = resp.getEntity();
            String respText = ent == null ? "" : EntityUtils.toString(ent, StandardCharsets.UTF_8);

            if (status / 100 != 2) {
                throw new IOException("Token HTTP " + status + ": " + respText);
            }

            // Try common token field names
            JsonNode node = safeParse(respText);
            if (node != null) {
                if (node.hasNonNull("access_token")) return node.get("access_token").asText();
                if (node.hasNonNull("token"))        return node.get("token").asText();
                if (node.hasNonNull("bearer"))       return node.get("bearer").asText();
                if (node.hasNonNull("jwt"))          return node.get("jwt").asText();
                // first string value fallback
                for (JsonNode v : node) if (v.isTextual()) return v.asText();
            }
            // If token endpoint returns raw token string:
            String trimmed = respText == null ? "" : respText.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("{") && !trimmed.startsWith("[")) {
                return trimmed;
            }
            return null;
        }
    }

    private static String postJsonWithBearer(CloseableHttpClient http, String url, String json, String token) throws IOException {
        HttpPost post = new HttpPost(url);
        post.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        post.setHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.getMimeType());
        post.setEntity(new StringEntity(json, ContentType.APPLICATION_JSON));

        try (CloseableHttpResponse resp = http.execute(post)) {
            int status = resp.getStatusLine().getStatusCode();
            String text = resp.getEntity() == null ? "" : EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
            if (status / 100 != 2) {
                throw new IOException("REST HTTP " + status + ": " + text);
            }
            return text;
        }
    }

    // ---- file helpers ----

    private static boolean isJson(Path p) {
        String n = p.getFileName().toString().toLowerCase();
        return n.endsWith(".json");
    }

    private static String baseName(String fileName) {
        int i = fileName.lastIndexOf('.');
        return i >= 0 ? fileName.substring(0, i) : fileName;
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


*************************************


    <project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>xmlUI</groupId>
  <artifactId>json-folder-rest-caller</artifactId>
  <version>1.0.0</version>

  <properties>
    <maven.compiler.source>1.8</maven.compiler.source>
    <maven.compiler.target>1.8</maven.compiler.target>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <jackson.version>2.17.1</jackson.version>
    <httpclient.version>4.5.14</httpclient.version>
  </properties>

  <dependencies>
    <!-- Apache HttpClient (Java 8 compatible) -->
    <dependency>
      <groupId>org.apache.httpcomponents</groupId>
      <artifactId>httpclient</artifactId>
      <version>${httpclient.version}</version>
    </dependency>

    <!-- Jackson for JSON parsing -->
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-databind</artifactId>
      <version>${jackson.version}</version>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>3.11.0</version>
        <configuration>
          <source>${maven.compiler.source}</source>
          <target>${maven.compiler.target}</target>
          <encoding>${project.build.sourceEncoding}</encoding>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>
