<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>tools.insomnia2soapui</groupId>
  <artifactId>insomnia2soapui</artifactId>
  <version>1.0.0</version>

  <properties>
    <maven.compiler.source>11</maven.compiler.source>
    <maven.compiler.target>11</maven.compiler.target>
    <jackson.version>2.17.1</jackson.version>
    <soapui.version>5.7.0</soapui.version>
  </properties>

  <dependencies>
    <!-- Jackson (JSON + YAML) -->
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-databind</artifactId>
      <version>${jackson.version}</version>
    </dependency>
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-core</artifactId>
      <version>${jackson.version}</version>
    </dependency>
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-annotations</artifactId>
      <version>${jackson.version}</version>
    </dependency>
    <dependency>
      <groupId>com.fasterxml.jackson.dataformat</groupId>
      <artifactId>jackson-dataformat-yaml</artifactId>
      <version>${jackson.version}</version>
    </dependency>

    <!-- SoapUI OSS (project/model APIs) -->
    <dependency>
      <groupId>org.soapui</groupId>
      <artifactId>soapui</artifactId>
      <version>${soapui.version}</version>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <!-- Build an executable jar with dependencies for easy running -->
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-assembly-plugin</artifactId>
        <version>3.6.0</version>
        <configuration>
          <archive>
            <manifest>
              <mainClass>tools.insomnia2soapui.InsomniaToSoapUI</mainClass>
            </manifest>
          </archive>
          <descriptorRefs>
            <descriptorRef>jar-with-dependencies</descriptorRef>
          </descriptorRefs>
        </configuration>
        <executions>
          <execution>
            <id>make-assembly</id>
            <phase>package</phase>
            <goals><goal>single</goal></goals>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>

package tools.insomnia2soapui;

import com.eviware.soapui.impl.rest.RestMethod;
import com.eviware.soapui.impl.rest.RestRequest;
import com.eviware.soapui.impl.rest.RestRequestInterface;
import com.eviware.soapui.impl.rest.RestResource;
import com.eviware.soapui.impl.rest.RestService;
import com.eviware.soapui.impl.rest.RestServiceFactory;
import com.eviware.soapui.impl.wsdl.WsdlProject;
import com.eviware.soapui.support.types.StringToStringsMap;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Insomnia v5+ export (YAML or JSON) -> SoapUI REST project (.xml).
 *
 * Usage:
 *   java -jar insomnia2soapui-1.0.0-jar-with-dependencies.jar export.yaml SoapUI-Project.xml
 */
public class InsomniaToSoapUI {

  public static void main(String[] args) throws Exception {
    if (args.length < 2) {
      System.err.println("Usage: java -jar insomnia2soapui-<ver>-jar-with-dependencies.jar <insomnia-export.(yaml|yml|json)> <output-soapui-project.xml>");
      System.exit(1);
    }
    File input = new File(args[0]);
    File output = new File(args[1]);

    JsonNode root = readInsomniaTree(input);

    // Insomnia collections generally have top-level "resources" containing objects with _type == "request"
    JsonNode resources = root.path("resources");
    if (!resources.isArray()) {
      throw new IllegalArgumentException("Not an Insomnia v5+ export: missing top-level 'resources' array.");
    }

    // Collect requests
    List<JsonNode> requests = new ArrayList<>();
    for (JsonNode r : resources) {
      if ("request".equalsIgnoreCase(r.path("_type").asText())) {
        requests.add(r);
      }
    }
    if (requests.isEmpty()) {
      System.out.println("No requests found in the Insomnia export.");
      return;
    }

    // Group by base endpoint (scheme://host[:port]) -> one RestService per host
    Map<String, List<JsonNode>> byHost = new LinkedHashMap<>();
    for (JsonNode req : requests) {
      String u = req.path("url").asText("");
      URI uri = safeUri(u);
      String hostKey = (uri.getScheme() == null ? "http" : uri.getScheme()) + "://" + (uri.getHost() == null ? "invalid" : uri.getHost());
      if (uri.getPort() > 0) hostKey += ":" + uri.getPort();
      byHost.computeIfAbsent(hostKey, k -> new ArrayList<>()).add(req);
    }

    // Create SoapUI project
    WsdlProject project = new WsdlProject();
    String projectName = root.path("name").asText("");
    project.setName(projectName.isBlank() ? "Imported from Insomnia" : projectName);

    int serviceIndex = 1;
    for (Map.Entry<String, List<JsonNode>> entry : byHost.entrySet()) {
      String baseEndpoint = entry.getKey();
      String serviceName = "Service " + serviceIndex++ + " - " + baseEndpoint;

      RestService service = (RestService) project.addNewInterface(serviceName, RestServiceFactory.REST_TYPE);
      service.addNewEndpoint(baseEndpoint);

      // Resource cache per path
      Map<String, RestResource> resourceMap = new LinkedHashMap<>();

      // stable order
      List<JsonNode> hostRequests = new ArrayList<>(entry.getValue());
      hostRequests.sort(Comparator.comparing(n -> n.path("name").asText("")));

      int reqCounter = 1;
      for (JsonNode req : hostRequests) {
        String name = nonEmpty(req.path("name").asText(""), "Request " + reqCounter++);
        String methodStr = nonEmpty(req.path("method").asText(""), "GET");
        RestRequestInterface.HttpMethod httpMethod = httpMethodOf(methodStr);

        URI uri = safeUri(req.path("url").asText(""));
        String path = nonEmpty(uri.getRawPath(), "/");

        // Make/find resource by path
        RestResource resource = resourceMap.computeIfAbsent(path, p -> {
          String display = p.startsWith("/") ? p.substring(1) : p;
          return service.addNewResource(display.isBlank() ? "/" : display, p.isBlank() ? "/" : p);
        });

        // Reuse or create a RestMethod for this HTTP verb under the resource
        RestMethod restMethod = findOrCreateMethod(resource, httpMethod);

        // Create a request under the method
        RestRequest request = restMethod.addNewRequest(name);
        request.setMethod(httpMethod);

        // Body + media type detection
        String mediaType = detectMediaType(req);
        if (!mediaType.isBlank()) request.setMediaType(mediaType);

        String bodyContent = readBodyContent(req);
        if (!bodyContent.isBlank()) request.setRequestContent(bodyContent);

        // Query params from URL
        if (uri.getRawQuery() != null && !uri.getRawQuery().isEmpty()) {
          for (String kv : uri.getRawQuery().split("&")) {
            if (kv.isBlank()) continue;
            String[] parts = kv.split("=", 2);
            String k = urlDecode(parts[0]);
            String v = parts.length > 1 ? urlDecode(parts[1]) : "";
            if (!k.isBlank()) request.getParams().addProperty(k).setValue(v);
          }
        }

        // Query params from Insomnia "parameters"
        JsonNode params = req.path("parameters");
        if (params.isArray()) {
          params.forEach(p -> {
            if (p.path("disabled").asBoolean(false)) return;
            String k = p.path("name").asText("");
            String v = p.path("value").asText("");
            if (!k.isBlank()) {
              if (request.getParams().getProperty(k) == null) {
                request.getParams().addProperty(k).setValue(v);
              } else {
                request.getParams().getProperty(k).setValue(v);
              }
            }
          });
        }

        // Headers
        StringToStringsMap headersMap = new StringToStringsMap();
        JsonNode headers = req.path("headers");
        if (headers.isArray()) {
          headers.forEach(h -> {
            if (h.path("disabled").asBoolean(false)) {
              String hn = h.path("name").asText("");
              String hv = h.path("value").asText("");
              if (!hn.isBlank()) headersMap.add(hn, hv);
            }
          });
        }
        // Ensure Content-Type aligns with mediaType if we detected it
        if (!mediaType.isBlank()) {
          headersMap.put("Content-Type", Collections.singletonList(mediaType));
        }
        request.setRequestHeaders(headersMap);
      }
    }

    project.saveAs(output.getAbsolutePath());
    System.out.println("Wrote SoapUI project: " + output.getAbsolutePath());
  }

  /* ====================== Helpers ====================== */

  private static JsonNode readInsomniaTree(File f) throws Exception {
    // Try YAML first; if it fails, fall back to JSON
    try (InputStream in = new FileInputStream(f)) {
      ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
      return yaml.readTree(in);
    } catch (Exception yamlFail) {
      try (InputStream in = new FileInputStream(f)) {
        ObjectMapper json = new ObjectMapper();
        return json.readTree(in);
      }
    }
  }

  private static URI safeUri(String u) {
    if (u == null || u.isBlank()) {
      try { return new URI("http://invalid/"); } catch (URISyntaxException e) { throw new RuntimeException(e); }
    }
    try {
      return new URI(u);
    } catch (URISyntaxException e) {
      // Try to fix missing scheme
      try { return new URI("http://" + u); } catch (URISyntaxException ex) {
        try { return new URI("http://invalid/"); } catch (URISyntaxException ignored) { throw new RuntimeException(ignored); }
      }
    }
  }

  private static String urlDecode(String s) {
    try { return URLDecoder.decode(s, StandardCharsets.UTF_8); }
    catch (Exception e) { return s; }
  }

  private static String nonEmpty(String v, String def) {
    return (v == null || v.isBlank()) ? def : v;
  }

  private static RestRequestInterface.HttpMethod httpMethodOf(String m) {
    try { return RestRequestInterface.HttpMethod.valueOf(m.toUpperCase(Locale.ROOT)); }
    catch (Exception e) { return RestRequestInterface.HttpMethod.GET; }
  }

  private static RestMethod findOrCreateMethod(RestResource res, RestRequestInterface.HttpMethod method) {
    for (RestMethod rm : res.getRestMethodList()) {
      if (rm.getMethod() == method) return rm;
    }
    RestMethod rm = res.addNewMethod(method.name());
    rm.setMethod(method);
    return rm;
  }

  private static String readBodyContent(JsonNode req) {
    JsonNode body = req.path("body");
    if (body.isObject()) {
      // Insomnia bodies often: { mimeType, text, fileName, params[] (for multipart) }
      String text = body.path("text").asText("");
      return text == null ? "" : text;
    } else if (body.isTextual()) {
      return body.asText("");
    }
    return "";
  }

  private static String detectMediaType(JsonNode req) {
    // prefer body.mimeType; fallback to header; else infer JSON/XML; default application/json
    String fromBody = req.path("body").path("mimeType").asText("");
    if (!fromBody.isBlank()) return fromBody;

    String text = readBodyContent(req);
    if (looksJson(text)) return "application/json";
    if (looksXml(text))  return "application/xml";

    // header fallback (content-type might exist)
    JsonNode headers = req.path("headers");
    if (headers.isArray()) {
      for (JsonNode h : headers) {
        if ("content-type".equalsIgnoreCase(h.path("name").asText(""))) {
          String v = h.path("value").asText("");
          if (!v.isBlank()) return v;
        }
      }
    }
    return "application/json";
  }

  private static boolean looksJson(String t) {
    if (t == null) return false;
    String s = t.trim();
    return (s.startsWith("{") && s.endsWith("}")) || (s.startsWith("[") && s.endsWith("]"));
  }

  private static boolean looksXml(String t) {
    if (t == null) return false;
    String s = t.trim();
    return s.startsWith("<") && s.endsWith(">");
  }
}



    
