import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHeaders;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.client.LaxRedirectStrategy;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class JiraAttachmentDownloader {

    // ========= CONFIGURE =========
    private static final String JIRA_BASE_URL = "https://YOUR_DOMAIN.atlassian.net";
    private static final String EMAIL         = "you@example.com";
    private static final String API_TOKEN     = "YOUR_API_TOKEN";

    // Option A: single issue
    private static final boolean USE_JQL = false;
    private static final String ISSUE_KEY = "PROJ-123";

    // Option B: JQL batch
    private static final String JQL = "project=PROJ AND attachments is not EMPTY ORDER BY created ASC";

    private static final Path DOWNLOAD_DIR = Paths.get("./JiraDownloads");
    // =============================

    public static void main(String[] args) throws Exception {
        Files.createDirectories(DOWNLOAD_DIR);

        RequestConfig cfg = RequestConfig.custom()
                .setConnectTimeout(30_000)
                .setConnectionRequestTimeout(30_000)
                .setSocketTimeout(90_000)
                .build();

        try (JiraClient jira = new JiraClient(JIRA_BASE_URL, EMAIL, API_TOKEN, cfg)) {
            if (USE_JQL) {
                downloadByJql(jira, JQL, DOWNLOAD_DIR);
            } else {
                downloadIssueAttachments(jira, ISSUE_KEY, DOWNLOAD_DIR);
            }
        }
    }

    /** Download all attachments for a single issue key into the given directory. */
    public static void downloadIssueAttachments(JiraClient jira, String issueKey, Path outDir) throws Exception {
        String path = "/rest/api/3/issue/" + issueKey + "?fields=summary,attachment";
        JsonNode issue = jira.getJson(path);
        String summary = issue.path("fields").path("summary").asText("");
        System.out.println("\n=== " + issueKey + " — " + summary + " ===");

        ArrayNode attachments = (ArrayNode) issue.path("fields").path("attachment");
        if (attachments == null || attachments.size() == 0) {
            System.out.println("No attachments.");
            return;
        }

        for (JsonNode att : attachments) {
            String name = sanitize(att.path("filename").asText("attachment"));
            String url  = att.path("content").asText(null);
            if (url == null || url.isEmpty()) {
                System.err.println("Skipping attachment with no content URL.");
                continue;
            }
            Path target = uniquePath(outDir, name);
            System.out.println("Downloading: " + target.getFileName());
            jira.downloadToFile(url, target);
        }
    }

    /** Search issues by JQL and download attachments for each. */
    public static void downloadByJql(JiraClient jira, String jql, Path outDir) throws Exception {
        int startAt = 0;
        int maxResults = 50;
        while (true) {
            String search = String.format(
                    "/rest/api/3/search?jql=%s&fields=summary,attachment&startAt=%d&maxResults=%d",
                    urlEncode(jql), startAt, maxResults);
            JsonNode page = jira.getJson(search);
            ArrayNode issues = (ArrayNode) page.path("issues");
            if (issues == null || issues.size() == 0) break;

            for (JsonNode iss : issues) {
                String key = iss.path("key").asText();
                downloadIssueAttachments(jira, key, outDir);
            }
            startAt += issues.size();
        }
    }

    private static String urlEncode(String s) { return s.replace(" ", "%20"); }

    private static final Pattern ILLEGAL = Pattern.compile("[\\\\/:*?\"<>|]");
    private static String sanitize(String name) {
        String cleaned = ILLEGAL.matcher(name).replaceAll("_");
        return cleaned.isEmpty() ? "attachment" : cleaned;
    }

    /** Ensure we don't overwrite if multiple attachments share a name. */
    private static Path uniquePath(Path dir, String baseName) throws IOException {
        Path p = dir.resolve(baseName);
        if (!Files.exists(p)) return p;
        String name = baseName;
        String ext = "";
        int dot = baseName.lastIndexOf('.');
        if (dot > 0) {
            name = baseName.substring(0, dot);
            ext = baseName.substring(dot); // includes dot
        }
        int i = 2;
        while (true) {
            Path candidate = dir.resolve(name + " (" + i + ")" + ext);
            if (!Files.exists(candidate)) return candidate;
            i++;
        }
    }

    /** Minimal Jira client (GET JSON + download) with redirects, timeouts, and 429 retry. */
    static class JiraClient implements AutoCloseable {
        private final String base;
        private final CloseableHttpClient http;
        private final ObjectMapper mapper = new ObjectMapper();

        JiraClient(String baseUrl, String email, String token, RequestConfig cfg) {
            this.base = baseUrl;
            CredentialsProvider provider = new BasicCredentialsProvider();
            provider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(email, token));
            this.http = HttpClients.custom()
                    .setDefaultRequestConfig(cfg)
                    .setDefaultCredentialsProvider(provider)
                    .setRedirectStrategy(new LaxRedirectStrategy()) // follow 30x (e.g., S3 redirect)
                    .build();
        }

        JsonNode getJson(String path) throws Exception {
            HttpGet get = new HttpGet(base + path);
            get.addHeader(HttpHeaders.ACCEPT, "application/json");
            return executeWithRetry(get, true);
        }

        void downloadToFile(String absoluteUrl, Path out) throws Exception {
            HttpGet get = new HttpGet(absoluteUrl);
            get.addHeader(HttpHeaders.ACCEPT, "application/octet-stream");
            try (CloseableHttpResponse resp = http.execute(get)) {
                int code = resp.getStatusLine().getStatusCode();
                if (code != 200) {
                    String body = safeBody(resp);
                    throw new IOException("Download failed (" + code + "): " + body);
                }
                try (InputStream in = resp.getEntity().getContent()) {
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
                EntityUtils.consumeQuietly(resp.getEntity());
            }
        }

        private JsonNode executeWithRetry(HttpUriRequest req, boolean expectJson) throws Exception {
            int attempts = 0;
            while (true) {
                attempts++;
                try (CloseableHttpResponse resp = http.execute(req)) {
                    int code = resp.getStatusLine().getStatusCode();
                    if (code == 429 && attempts <= 5) {
                        long backoff = parseRetryAfterMs(resp, 2000);
                        System.err.println("429 rate-limited. Sleeping " + backoff + " ms (attempt " + attempts + ")");
                        EntityUtils.consumeQuietly(resp.getEntity());
                        Thread.sleep(backoff);
                        continue;
                    }
                    if (code >= 200 && code < 300) {
                        if (!expectJson) {
                            EntityUtils.consumeQuietly(resp.getEntity());
                            return null;
                        }
                        String body = safeBody(resp);
                        return mapper.readTree(body);
                    }
                    String body = safeBody(resp);
                    throw new IOException("HTTP " + code + " - " + body);
                }
            }
        }

        private static String safeBody(CloseableHttpResponse resp) throws IOException {
            HttpEntity e = resp.getEntity();
            String s = (e == null) ? "" : EntityUtils.toString(e);
            EntityUtils.consumeQuietly(e);
            return s;
        }

        private static long parseRetryAfterMs(CloseableHttpResponse resp, long defaultMs) {
            Header h = resp.getFirstHeader("Retry-After");
            if (h == null) return defaultMs;
            String v = h.getValue().trim();
            try {
                return TimeUnit.SECONDS.toMillis(Long.parseLong(v));
            } catch (NumberFormatException ignore) {
                return defaultMs;
            }
        }

        @Override public void close() throws Exception { http.close(); }
    }
}
