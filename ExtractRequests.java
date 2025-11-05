package xmlUI;

import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class JsonFolderRestCaller {

    // ====== EDIT THESE ======
    private static final String JSON_INPUT_DIR   = "C:\\work\\json-in";   // folder with request JSONs
    private static final String OUTPUT_DIR       = "C:\\work\\json-out";  // where responses are saved

    private static final String TOKEN_URL        = "https://api.example.com/token";
    private static final String CLIENT_ID        = "yourClient";
    private static final String CLIENT_SECRET    = "yourSecret";

    private static final String REST_URL         = "https://api.example.com/process";

    // If your token service wants JSON body {"client":"..","secret":".."}, leave true.
    // If it instead expects headers client/secret, set this to false.
    private static final boolean TOKEN_AS_JSON_BODY = true;

    // Optional: add static headers to business call
    private static final Map<String, String> EXTRA_REQUEST_HEADERS = Map.of(); // e.g., Map.of("x-env","dev")
    // =========================

    public static void main(String[] args) throws Exception {
        Path inDir  = Paths.get(JSON_INPUT_DIR).toAbsolutePath().normalize();
        Path outDir = Paths.get(OUTPUT_DIR).toAbsolutePath().normalize();
        if (!Files.isDirectory(inDir)) {
            System.err.println("Input directory not found: " + inDir);
            return;
        }
        Files.createDirectories(outDir);

        RestTemplate rt = restTemplateWithTimeouts(60_000, 60_000);

        String token = fetchBearerToken(rt);
        if (token == null || token.isBlank()) {
            System.err.println("Failed to acquire token; aborting.");
            return;
        }
        System.out.println("Token acquired at " + LocalDateTime.now());

        AtomicInteger ok = new AtomicInteger();
        AtomicInteger fail = new AtomicInteger();

        try (var walk = Files.walk(inDir)) {
            walk.filter(Files::isRegularFile)
                .filter(JsonFolderRestCaller::isJson)
                .forEach(jsonPath -> {
                    try {
                        String requestJson = Files.readString(jsonPath, StandardCharsets.UTF_8);

                        HttpHeaders headers = new HttpHeaders();
                        headers.setContentType(MediaType.APPLICATION_JSON);
                        headers.setBearerAuth(token);
                        // any extra headers:
                        EXTRA_REQUEST_HEADERS.forEach(headers::add);

                        HttpEntity<String> entity = new HttpEntity<>(requestJson, headers);
                        ResponseEntity<String> resp = rt.exchange(REST_URL, HttpMethod.POST, entity, String.class);

                        int status = resp.getStatusCodeValue();
                        String body = resp.getBody() == null ? "" : resp.getBody();

                        // Save response
                        String base = baseName(jsonPath.getFileName().toString());
                        Path out = outDir.resolve(base + "-resp" + (looksLikeJson(body) ? ".json" : ".txt"));
                        writeAtomic(out, body);

                        System.out.println("✓ " + jsonPath.getFileName() + " -> " + out.getFileName() + " (HTTP " + status + ")");
                        ok.incrementAndGet();
                    } catch (Exception ex) {
                        System.err.println("✗ " + jsonPath.getFileName() + " :: " + ex.getMessage());
                        fail.incrementAndGet();
                    }
                });
        }

        System.out.println("Done. Success: " + ok.get() + ", Failed: " + fail.get());
        System.out.println("Output folder: " + outDir);
    }

    // -------- token --------
    private static String fetchBearerToken(RestTemplate rt) {
        try {
            if (TOKEN_AS_JSON_BODY) {
                Map<String, String> body = new HashMap<>();
                body.put("client", CLIENT_ID);
                body.put("secret", CLIENT_SECRET);

                HttpHeaders h = new HttpHeaders();
                h.setContentType(MediaType.APPLICATION_JSON);

                ResponseEntity<Map> resp = rt.postForEntity(TOKEN_URL, new HttpEntity<>(body, h), Map.class);
                if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) return null;

                // Try common field names
                Object v;
                Map<?,?> m = resp.getBody();
                if      ((v = m.get("access_token")) instanceof String) return (String) v;
                else if ((v = m.get("token"))        instanceof String) return (String) v;
                else if ((v = m.get("bearer"))       instanceof String) return (String) v;
                else if ((v = m.get("jwt"))          instanceof String) return (String) v;
                // last resort: first string value
                return m.values().stream().filter(String.class::isInstance).map(String.class::cast).findFirst().orElse(null);
            } else {
                HttpHeaders h = new HttpHeaders();
                h.add("client", CLIENT_ID);
                h.add("secret", CLIENT_SECRET);
                h.setContentType(MediaType.APPLICATION_JSON);
                ResponseEntity<Map> resp = rt.exchange(TOKEN_URL, HttpMethod.POST, new HttpEntity<>("", h), Map.class);
                if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) return null;
                Map<?,?> m = resp.getBody();
                Object v;
                if      ((v = m.get("access_token")) instanceof String) return (String) v;
                else if ((v = m.get("token"))        instanceof String) return (String) v;
                else if ((v = m.get("bearer"))       instanceof String) return (String) v;
                else if ((v = m.get("jwt"))          instanceof String) return (String) v;
                return m.values().stream().filter(String.class::isInstance).map(String.class::cast).findFirst().orElse(null);
            }
        } catch (Exception e) {
            System.err.println("Token request failed: " + e.getMessage());
            return null;
        }
    }

    // -------- helpers --------
    private static RestTemplate restTemplateWithTimeouts(int connectMs, int readMs) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(connectMs);
        f.setReadTimeout(readMs);
        return new RestTemplate(f);
    }

    private static String baseName(String fileName) {
        int i = fileName.lastIndexOf('.');
        return i >= 0 ? fileName.substring(0, i) : fileName;
    }

    private static boolean isJson(Path p) {
        String n = p.getFileName().toString().toLowerCase();
        return n.endsWith(".json");
    }

    private static boolean looksLikeJson(String s) {
        String t = s == null ? "" : s.trim();
        return (!t.isEmpty()) && ((t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]")));
    }

    private static void writeAtomic(Path out, String content) throws IOException {
        Path tmp = out.resolveSibling(out.getFileName().toString() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
