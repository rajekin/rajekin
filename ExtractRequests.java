package tools.insomnia2soapuixml;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamWriter;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class InsomniaToSoapUiXml {

  /* ====== Namespaces ====== */
  private static final String NS_CON = "http://eviware.com/soapui/config";
  private static final String NS_XSI = "http://www.w3.org/2001/XMLSchema-instance";

  public static void main(String[] args) throws Exception {
    if (args.length < 2) {
      System.err.println("Usage: java -jar insomnia2soapui-xml-1.0.0-jar-with-dependencies.jar <insomnia.(yaml|yml|json)> <SoapUI-Project.xml>");
      System.exit(1);
    }
    File input = new File(args[0]);
    File output = new File(args[1]);

    JsonNode root = readInsomnia(input);
    List<JsonNode> requests = extractRequests(root);
    if (requests.isEmpty()) {
      throw new IllegalArgumentException("No request nodes found. Supported: top-level 'resources[]' OR a 'type/children' tree with request leaves.");
    }

    // Group by base endpoint scheme://host[:port]
    Map<String, List<JsonNode>> byHost = new LinkedHashMap<>();
    for (JsonNode r : requests) {
      URI uri = safeUri(r.path("url").asText(""));
      String hostKey = ((uri.getScheme() == null) ? "http" : uri.getScheme()) + "://" + ((uri.getHost() == null) ? "invalid" : uri.getHost());
      if (uri.getPort() > 0) hostKey += ":" + uri.getPort();
      byHost.computeIfAbsent(hostKey, k -> new ArrayList<>()).add(r);
    }

    // Write SoapUI project XML
    try (OutputStream fos = new FileOutputStream(output);
         OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
      XMLOutputFactory f = XMLOutputFactory.newInstance();
      XMLStreamWriter x = f.createXMLStreamWriter(osw);

      x.writeStartDocument("UTF-8", "1.0");
      x.setDefaultNamespace(NS_CON);
      x.writeStartElement("con", "soapui-project", NS_CON);
      x.writeNamespace("con", NS_CON);
      x.writeNamespace("xsi", NS_XSI);

      attr(x, "id", uuid());
      attr(x, "activeEnvironment", "Default");
      attr(x, "name", defaultStr(root.path("name").asText(""), "Imported from Insomnia"));
      attr(x, "resourceRoot", "");
      attr(x, "soapui-version", "5.7.0");
      attr(x, "abortOnError", "false");
      attr(x, "runType", "SEQUENTIAL");

      // project-level sections (minimal)
      elemEmpty(x, "settings");

      // Interfaces (one per host)
      for (Map.Entry<String, List<JsonNode>> entry : byHost.entrySet()) {
        String endpoint = entry.getKey();

        x.writeStartElement(NS_CON, "interface");
        x.writeAttribute("xmlns:xsi", NS_XSI);
        x.writeAttribute("xsi:type", "con:RestService");
        attr(x, "id", uuid());
        attr(x, "wadlVersion", "http://wadl.dev.java.net/2009/02");
        attr(x, "name", endpoint);
        attr(x, "type", "rest");

        elemEmpty(x, "settings");

        x.writeStartElement(NS_CON, "definitionCache");
        attr(x, "type", "TEXT");
        attr(x, "rootPart", "");
        x.writeEndElement();

        x.writeStartElement(NS_CON, "endpoints");
        textElem(x, "endpoint", endpoint);
        x.writeEndElement();

        // Group by path under this host
        Map<String, List<JsonNode>> byPath = new LinkedHashMap<>();
        for (JsonNode r : entry.getValue()) {
          URI uri = safeUri(r.path("url").asText(""));
          String path = defaultStr(uri.getRawPath(), "/");
          byPath.computeIfAbsent(path, k -> new ArrayList<>()).add(r);
        }

        for (Map.Entry<String, List<JsonNode>> pathEntry : byPath.entrySet()) {
          String path = pathEntry.getKey();

          x.writeStartElement(NS_CON, "resource");
          attr(x, "name", displayName(path));
          attr(x, "path", path);
          attr(x, "id", uuid());

          elemEmpty(x, "settings");
          elemEmpty(x, "parameters");

          // Group by HTTP verb
          Map<String, List<JsonNode>> byVerb = new LinkedHashMap<>();
          for (JsonNode r : pathEntry.getValue()) {
            String verb = defaultStr(r.path("method").asText(""), "GET").toUpperCase(Locale.ROOT);
            byVerb.computeIfAbsent(verb, k -> new ArrayList<>()).add(r);
          }

          for (Map.Entry<String, List<JsonNode>> verbEntry : byVerb.entrySet()) {
            String verb = verbEntry.getKey();

            x.writeStartElement(NS_CON, "method");
            attr(x, "name", verb);
            attr(x, "id", uuid());
            attr(x, "method", verb);

            elemEmpty(x, "settings");
            elemEmpty(x, "parameters");

            // Minimal representation (optional)
            x.writeStartElement(NS_CON, "representation");
            attr(x, "type", "RESPONSE");
            textElem(x, "mediaType", "application/json");
            textElem(x, "status", "200");
            elemEmpty(x, "params");
            x.writeEndElement(); // representation

            // Requests
            for (JsonNode req : verbEntry.getValue()) {
              String reqName = defaultStr(req.path("name").asText(""), verb + " " + path);
              URI uri = safeUri(req.path("url").asText(""));
              String mediaType = detectMediaType(req);
              String body = readBody(req);

              x.writeStartElement(NS_CON, "request");
              attr(x, "name", reqName);
              attr(x, "id", uuid());
              if (!mediaType.isBlank()) attr(x, "mediaType", mediaType);

              // headers in settings as SoapUI's request-headers xml-fragment
              x.writeStartElement(NS_CON, "settings");
              x.writeStartElement(NS_CON, "setting");
              attr(x, "id", "com.eviware.soapui.impl.wsdl.WsdlRequest@request-headers");
              x.writeCharacters(buildHeadersXmlFragment(req)); // already escaped
              x.writeEndElement(); // setting
              x.writeEndElement(); // settings

              textElem(x, "endpoint", endpoint);

              x.writeStartElement(NS_CON, "request");
              if (!body.isBlank()) x.writeCData(body);
              x.writeEndElement(); // request body

              // keep original URL (with query) so SoapUI shows Params tab populated
              textElem(x, "originalUri", uri.toString());

              // No auth by default
              x.writeStartElement(NS_CON, "credentials");
              textElem(x, "authType", "No Authorization");
              x.writeEndElement();

              // Stubs required by many project files
              x.writeEmptyElement(NS_CON, "jmsConfig");
              x.writeAttribute("JMSDeliveryMode", "PERSISTENT");
              elemEmpty(x, "jmsPropertyConfig");
              elemEmpty(x, "parameters");

              x.writeEndElement(); // request
            }

            x.writeEndElement(); // method
          }

          x.writeEndElement(); // resource
        }

        x.writeEndElement(); // interface
      }

      // minimal containers
      elemEmpty(x, "properties");
      elemEmpty(x, "wssContainer");
      elemEmpty(x, "oAuth2ProfileContainer");
      elemEmpty(x, "oAuth1ProfileContainer");

      x.writeEndElement(); // soapui-project
      x.writeEndDocument();
      x.flush();
    }

    System.out.println("Wrote SoapUI project: " + output.getAbsolutePath());
  }

  /* ================= Helpers ================= */

  /** Reads YAML first; if that fails, falls back to JSON. */
  private static JsonNode readInsomnia(File f) throws IOException {
    byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
    try {
      JsonNode n = new ObjectMapper(new YAMLFactory()).readTree(bytes);
      System.out.println("Parsed input as YAML.");
      return n;
    } catch (Throwable yamlErr) {
      System.err.println("YAML parse failed (" + yamlErr.getClass().getSimpleName() + "): " + yamlErr.getMessage());
      System.out.println("Falling back to JSON…");
      return new ObjectMapper().readTree(bytes);
    }
  }

  /** Supports both classic `resources[]` and tree-style `type/children` exports. */
  private static List<JsonNode> extractRequests(JsonNode root) {
    List<JsonNode> out = new ArrayList<>();

    // Case A: resources[]
    JsonNode resources = root.path("resources");
    if (resources.isArray()) {
      for (JsonNode r : resources) if (isRequestNode(r)) out.add(r);
      if (!out.isEmpty()) return out;
    }

    // Case B: recursive type/children
    Deque<JsonNode> stack = new ArrayDeque<>();
    stack.push(root);
    while (!stack.isEmpty()) {
      JsonNode n = stack.pop();
      if (isRequestNode(n)) out.add(n);
      JsonNode kids = n.path("children");
      if (kids.isArray()) kids.forEach(stack::push);
    }
    return out;
  }

  private static boolean isRequestNode(JsonNode n) {
    String t1 = n.path("_type").asText("");
    String t2 = n.path("type").asText("");
    if ("request".equalsIgnoreCase(t1) || "request".equalsIgnoreCase(t2)) return true;
    return n.has("url") && (n.has("method") || n.has("name")); // heuristic
  }

  private static String detectMediaType(JsonNode req) {
    String mt = req.path("body").path("mimeType").asText("");
    if (!mt.isBlank()) return mt;

    String t = readBody(req).trim();
    if ((t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"))) return "application/json";
    if (t.startsWith("<") && t.endsWith(">")) return "application/xml";

    // header fallback
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

  private static String readBody(JsonNode req) {
    JsonNode b = req.path("body");
    if (b.isObject()) return b.path("text").asText("");
    if (b.isTextual()) return b.asText("");
    return "";
  }

  private static String defaultStr(String value, String fallback) {
    return (value == null || value.isBlank()) ? fallback : value;
  }

  private static String uuid() { return java.util.UUID.randomUUID().toString(); }

  private static void attr(XMLStreamWriter x, String name, String value) throws Exception {
    x.writeAttribute(name, value);
  }

  private static void elemEmpty(XMLStreamWriter x, String local) throws Exception {
    x.writeEmptyElement(NS_CON, local);
  }

  private static void textElem(XMLStreamWriter x, String local, String text) throws Exception {
    x.writeStartElement(NS_CON, local);
    x.writeCharacters(text == null ? "" : text);
    x.writeEndElement();
  }

  private static String displayName(String path) {
    if (path == null || path.isBlank() || "/".equals(path)) return "/";
    return path.startsWith("/") ? path.substring(1) : path;
  }

  private static URI safeUri(String url) {
    try { return URI.create(url); }
    catch (Exception e) { return URI.create("http://invalid/"); }
  }

  /** SoapUI stores request headers in a `request-headers` XML fragment setting. */
  private static String buildHeadersXmlFragment(JsonNode req) {
    StringBuilder sb = new StringBuilder();
    sb.append("<xml-fragment xmlns:con=\"").append(NS_CON).append("\">");
    JsonNode headers = req.path("headers");
    if (headers.isArray()) {
      for (JsonNode h : headers) {
        if (h.path("disabled").asBoolean(false)) continue;
        String name = h.path("name").asText("");
        String value = h.path("value").asText("");
        if (name.isBlank()) continue;
        sb.append("<con:entry key=\"").append(escapeXmlAttr(name))
          .append("\" value=\"").append(escapeXmlAttr(value)).append("\"/>");
      }
    }
    sb.append("</xml-fragment>");
    // Must be entity-escaped when embedded as a setting value.
    return sb.toString().replace("<", "&lt;").replace(">", "&gt;");
  }

  private static String escapeXmlAttr(String s) {
    if (s == null) return "";
    return s.replace("&", "&amp;").replace("\"", "&quot;")
            .replace("<", "&lt;").replace(">", "&gt;");
  }
}
