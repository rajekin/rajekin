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
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class JiraStoryInfoAndDownloader {

    // ========= CONFIGURE =========
    private static final String JIRA_BASE_URL = "https://YOUR_DOMAIN.atlassian.net";
    private static final String EMAIL         = "you@example.com";
    private static final String API_TOKEN     = "YOUR_API_TOKEN";

    // If your site uses Story Points (custom field), set its ID here (e.g., "customfield_10016"), else leave empty
    private static final String STORY_POINTS_FIELD_ID = ""; // e.g., "customfield_10016"

    // Option A: single issue
    private static final boolean USE_JQL = false;
    private static final String ISSUE_KEY = "PROJ-123";

    // Option B: JQL batch
    private static final String JQL = "project=PROJ AND issuetype=Story ORDER BY created ASC";

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
                runByJql(jira, JQL, DOWNLOAD_DIR);
            } else {
                runSingle(jira, ISSUE_KEY, DOWNLOAD_DIR);
            }
        }
    }

    private static void runByJql(JiraClient jira, String jql, Path outDir) throws Exception {
        int startAt = 0, maxResults = 50;
        while (true) {
            String search = String.format(
                    "/rest/api/3/search?jql=%s&fields=%s&startAt=%d&maxResults=%d",
                    urlEncode(jql), wantedFieldsCsv(), startAt, maxResults);
            JsonNode page = jira.getJson(search);
            ArrayNode issues = (ArrayNode) page.path("issues");
            if (issues == null || issues.size() == 0) break;

            for (JsonNode iss : issues) {
                String key = iss.path("key").asText();
                runSingle(jira, key, outDir);
            }
            startAt += issues.size();
        }
    }

    private static void runSingle(JiraClient jira, String issueKey, Path outDir) throws Exception {
        String path = "/rest/api/3/issue/" + issueKey + "?fields=" + wantedFieldsCsv();
        JsonNode issue = jira.getJson(path);
        printIssueInfo(issue);

        // Download attachments
        ArrayNode attachments = (ArrayNode) issue.path("fields").path("attachment");
        if (attachments != null && attachments.size() > 0) {
            for (JsonNode att : attachments) {
                String name = sanitize(att.path("filename").asText("attachment"));
                String url  = att.path("content").asText(null);
                if (url == null || url.isEmpty()) continue;
                Path target = uniquePath(outDir, name);
                System.out.println("Downloading: " + target.getFileName());
                jira.downloadToFile(url, target);
            }
        } else {
            System.out.println("No attachments.");
        }
        System.out.println();
    }

    // ====== Issue info helpers ======

    private static String wantedFieldsCsv() {
        String base = "summary,issuetype,status,priority,labels,reporter,assignee,created,updated,description,attachment";
        if (STORY_POINTS_FIELD_ID != null && !STORY_POINTS_FIELD_ID.isBlank()) {
            base += "," + STORY_POINTS_FIELD_ID;
        }
        return base;
    }

    private static void printIssueInfo(JsonNode issue) {
        String key = issue.path("key").asText();
        JsonNode f = issue.path("fields");

        String summary   = f.path("summary").asText("");
        String type      = f.path("issuetype").path("name").asText("");
        String status    = f.path("status").path("name").asText("");
        String priority  = f.path("priority").path("name").asText("");
        String labels    = joinLabels((ArrayNode) f.path("labels"));
        String reporter  = f.path("reporter").path("displayName").asText("");
        String assignee  = f.path("assignee").path("displayName").asText("");
        String created   = isoToNice(f.path("created").asText(""));
        String updated   = isoToNice(f.path("updated").asText(""));
        String storyPts  = (STORY_POINTS_FIELD_ID == null || STORY_POINTS_FIELD_ID.isBlank())
                ? ""
                : f.path(STORY_POINTS_FIELD_ID).isMissingNode() ? "" : f.path(STORY_POINTS_FIELD_ID).asText("");

        String descriptionPlain = adfToPlainText(f.path("description"));

        System.out.println("\n=== " + key + " — " + summary + " ===");
        System.out.println("Type: " + type + "    Status: " + status + "    Priority: " + priority);
        if (!storyPts.isBlank()) System.out.println("Story Points: " + storyPts);
        System.out.println("Labels: " + (labels.isBlank() ? "-" : labels));
        System.out.println("Reporter: " + (reporter.isBlank() ? "-" : reporter)
                + "    Assignee: " + (assignee.isBlank() ? "-" : assignee));
        System.out.println("Created: " + created + "    Updated: " + updated);
        if (!descriptionPlain.isBlank()) {
            System.out.println("\n--- Description (plain text) ---");
            System.out.println(descriptionPlain);
        }
    }

    private static String joinLabels(ArrayNode arr) {
        if (arr == null || arr.size() == 0) return "";
        StringJoiner j = new StringJoiner(", ");
        for (JsonNode n : arr) j.add(n.asText());
        return j.toString();
    }

    private static String isoToNice(String iso) {
        try {
            return OffsetDateTime.parse(iso).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        } catch (Exception e) {
            return iso;
        }
        }

    /**
     * Very small ADF→text flattener.
     * Handles paragraphs, headings, hardBreaks, bullet/ordered lists, and text nodes.
     */
    private static String adfToPlainText(JsonNode adf) {
        if (adf == null || adf.isMissingNode() || adf.isNull()) return "";
        StringBuilder sb = new StringBuilder();
        adfWalk(adf, sb, 0, false);
        return sb.toString().replaceAll("\\s+\n", "\n").trim();
    }

    private static void adfWalk(JsonNode node, StringBuilder out, int indent, boolean inListItem) {
        if (node == null || node.isMissingNode()) return;
        String type = node.path("type").asText("");
        switch (type) {
            case "doc":
            case "paragraph":
            case "heading":
            case "blockquote":
            case "panel":
            case "listItem":
            case "bulletList":
            case "orderedList":
                ArrayNode content = (ArrayNode) node.path("content");
                if (type.equals("listItem")) indent++;
                if (content != null) {
                    for (Iterator<JsonNode> it = content.elements(); it.hasNext();) {
                        JsonNode c = it.next();
                        boolean listCtx = type.equals("bulletList") || type.equals("orderedList") || type.equals("listItem");
                        adfWalk(c, out, indent, listCtx);
                    }
                }
                if (type.equals("paragraph") || type.equals("heading") || type.equals("blockquote") || type.equals("panel")) {
                    out.append("\n");
                }
                break;
            case "text":
                out.append(node.path("text").asText(""));
                break;
            case "hardBreak":
                out.append("\n");
                break;
            default:
                // recurse children if any
                if (node.has("content") && node.path("content").isArray()) {
                    for (JsonNode c : node.path("content")) adfWalk(c, out, indent, inListItem);
                }
        }
        // prefix bullet for top-level text in list items
        if ((type.equals("paragraph") || type.equals("text")) && inListItem && out.length() > 0) {
            // crude: add bullet at line starts
            int lastNewline = out.lastIndexOf("\n");
            int start = (lastNewline == -1) ? 0 : lastNewline + 1;
            if (start < out.length() && out.charAt(start) != '•') {
                out.insert(start, "• ".repeat(Math.max(1, indent)));
            }
        }
    }

    // ====== Download helpers ======

    private static String urlEncode(String s) { return s.replace(" ", "%20"); }

    private static final Pattern ILLEGAL = Pattern.compile("[\\\\/:*?\"<>|]");
    private static String sanitize(String name) {
        String cleaned = ILLEGAL.matcher(name).replaceAll("_");
        return cleaned.isEmpty() ? "attachment" : cleaned;
    }

    private static Path uniquePath(Path dir, String baseName) throws IOException {
        Path p = dir.resolve(baseName);
        if (!Files.exists(p)) return p;
        String name = baseName;
        String ext = "";
        int dot = baseName.lastIndexOf('.');
        if (dot > 0) { name = baseName.substring(0, dot); ext = baseName.substring(dot); }
        int i = 2;
        while (true) {
            Path candidate = dir.resolve(name + " (" + i + ")" + ext);
            if (!Files.exists(candidate)) return candidate;
            i++;
        }
    }

    // ====== Minimal Jira client with redirects + 429 retry ======

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
                    .setRedirectStrategy(new LaxRedirectStrategy())
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
