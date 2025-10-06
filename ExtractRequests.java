
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamWriter;
import java.io.*;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class InsomniaToSoapUiXml {

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
    JsonNode resources = root.path("resources");
    if (!resources.isArray()) {
      throw new IllegalArgumentException("Expected Insomnia v5+ export with top-level 'resources' array.");
    }

    List<JsonNode> requests = new ArrayList<>();
    for (JsonNode r : resources) {
      if ("request".equalsIgnoreCase(r.path("_type").asText())) requests.add(r);
    }
    if (requests.isEmpty()) {
      System.out.println("No requests found.");
      return;
    }

    // Group requests by base host
    Map<String, List<JsonNode>> byHost = new LinkedHashMap<>();
    for (JsonNode r : requests) {
      URI uri = safeUri(r.path("url").asText(""));
      String hostKey = (uri.getScheme() == null ? "http" : uri.getScheme()) + "://" + (uri.getHost() == null ? "invalid" : uri.getHost());
      if (uri.getPort() > 0) hostKey += ":" + uri.getPort();
      byHost.computeIfAbsent(hostKey, k -> new ArrayList<>()).add(r);
    }

    // Write SoapUI project XML
    try (OutputStream fos = new FileOutputStream(output)) {
      XMLOutputFactory f = XMLOutputFactory.newInstance();
      XMLStreamWriter x = f.createXMLStreamWriter(new OutputStreamWriter(fos, StandardCharsets.UTF_8));

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

      // <con:settings/>
      elemEmpty(x, "settings");

      int serviceIndex = 1;
      for (Map.Entry<String, List<JsonNode>> entry : byHost.entrySet()) {
        String endpoint = entry.getKey();
        // <con:interface xsi:type="con:RestService" ...>
        x.writeStartElement(NS_CON, "interface");
        x.writeAttribute("xmlns:xsi", NS_XSI);
        x.writeAttribute("xsi:type", "con:RestService");
        attr(x, "id", uuid());
        attr(x, "wadlVersion", "http://wadl.dev.java.net/2009/02");
        attr(x, "name", endpoint);
        attr(x, "type", "rest");

        elemEmpty(x, "settings");

        // <con:definitionCache type="TEXT" rootPart=""/>
        x.writeStartElement(NS_CON, "definitionCache");
        attr(x, "type", "TEXT");
        attr(x, "rootPart", "");
        x.writeEndElement();

        // <con:endpoints><con:endpoint>...</con:endpoint></con:endpoints>
        x.writeStartElement(NS_CON, "endpoints");
        textElem(x, "endpoint", endpoint);
        x.writeEndElement();

        // Prepare resource map by path
        Map<String, List<JsonNode>> byPath = new LinkedHashMap<>();
        for (JsonNode r : entry.getValue()) {
          URI uri = safeUri(r.path("url").asText(""));
          String path = defaultStr(uri.getRawPath(), "/");
          byPath.computeIfAbsent(path, k -> new ArrayList<>()).add(r);
        }

        for (Map.Entry<String, List<JsonNode>> pathEntry : byPath.entrySet()) {
          String path = pathEntry.getKey();
          String resourceId = uuid();

          x.writeStartElement(NS_CON, "resource");
          attr(x, "name", displayName(path));
          attr(x, "path", path);
          attr(x, "id", resourceId);
          elemEmpty(x, "settings");
          elemEmpty(x, "parameters");

          // group by HTTP method
          Map<String, List<JsonNode>> byVerb = new LinkedHashMap<>();
          for (JsonNode r : pathEntry.getValue()) {
            String verb = defaultStr(r.path("method").asText(""), "GET").toUpperCase(Locale.ROOT);
            byVerb.computeIfAbsent(verb, k -> new ArrayList<>()).add(r);
          }

          for (Map.Entry<String, List<JsonNode>> verbEntry : byVerb.entrySet()) {
            String verb = verbEntry.getKey();
            x.writeStartElement(NS_CON, "method");
            attr(x, "name", verb);           // SoapUI will show e.g. "GET"
            attr(x, "id", uuid());
            attr(x, "method", verb);
            elemEmpty(x, "settings");
            elemEmpty(x, "parameters");

            // Optional representation block (kept minimal)
            x.writeStartElement(NS_CON, "representation");
            attr(x, "type", "RESPONSE");
            textElem(x, "mediaType", "application/json");
            textElem(x, "status", "200");
            elemEmpty(x, "params");
            // element tag is optional; leave out
            x.writeEndElement(); // representation

            // Requests under the method
            for (JsonNode req : verbEntry.getValue()) {
              String reqName = defaultStr(req.path("name").asText(""), verb + " " + path);
              URI uri = safeUri(req.path("url").asText(""));
              String mediaType = detectMediaType(req);
              String body = readBody(req);

              x.writeStartElement(NS_CON, "request");
              attr(x, "name", reqName);
              attr(x, "id", uuid());
              if (!mediaType.isBlank()) attr(x, "mediaType", mediaType);

              // <con:settings> with request headers fragment
              x.writeStartElement(NS_CON, "settings");
              String headersFragment = buildHeadersXmlFragment(req);
              x.writeStartElement(NS_CON, "setting");
              attr(x, "id", "com.eviware.soapui.impl.wsdl.WsdlRequest@request-headers");
              // value must be XML-escaped; SoapUI expects <xml-fragment...>…</xml-fragment>
              x.writeCharacters(headersFragment);
              x.writeEndElement(); // setting
              x.writeEndElement(); // settings

              textElem(x, "endpoint", endpoint);

              // body (may be empty)
              x.writeStartElement(NS_CON, "request");
              if (!body.isBlank()) x.writeCData(body);
              x.writeEndElement();

              // originalUri with query params intact so SoapUI shows them in Params
              textElem(x, "originalUri", uri.toString());

              // No authorization by default
              x.writeStartElement(NS_CON, "credentials");
              textElem(x, "authType", "No Authorization");
              x.writeEndElement();

              // JMS & params shells (optional)
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

      // minimal containers expected by many projects
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

  /* --------------- helpers --------------- */

  private static JsonNode readInsomnia(File f) throws IOException {
    try (InputStream in = new FileInputStream(f)) {
      return new ObjectMapper(new YAMLFactory()).readTree(in);
    } catch (Exception notYaml) {
      try (InputStream in = new FileInputStream(f)) {
        return new ObjectMapper().readTree(in);
      }
    }
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

  private static String detectMediaType(JsonNode req) {
    String mt = req.path("body").path("mimeType").asText("");
    if (!mt.isBlank()) return mt;
    String t = readBody(req).trim();
    if ((t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"))) return "application/json";
    if (t.startsWith("<") && t.endsWith(">")) return "application/xml";
    // header fallback
    for (JsonNode h : iterable(req.path("headers"))) {
      if ("content-type".equalsIgnoreCase(h.path("name").asText(""))) {
        String v = h.path("value").asText("");
        if (!v.isBlank()) return v;
      }
    }
    return "application/json";
  }

  private static String readBody(JsonNode req) {
    JsonNode b = req.path("body");
    if (b.isObject()) {
      return b.path("text").asText("");
    } else if (b.isTextual()) {
      return b.asText("");
    }
    return "";
  }

  private static Iterable<JsonNode> iterable(JsonNode node) {
    return node != null && node.isArray() ? node::elements : Collections::emptyIterator;
  }

  /**
   * Builds the inner value for the SoapUI setting
   *   id="com.eviware.soapui.impl.wsdl.WsdlRequest@request-headers"
   * SoapUI stores request headers as an XML fragment inside that setting.
   * We emit: &lt;xml-fragment xmlns:con="...">&lt;con:entry key="K" value="V"/>...&lt;/xml-fragment>
   * The whole fragment is inserted as text (SoapUI expects the XML to be entity-escaped).
   */
  private static String buildHeadersXmlFragment(JsonNode req) {
    StringBuilder sb = new StringBuilder();
    sb.append("<xml-fragment xmlns:con=\"").append(NS_CON).append("\">");
    for (JsonNode h : iterable(req.path("headers"))) {
      if (h.path("disabled").asBoolean(false)) continue;
      String name = h.path("name").asText("");
      String value = h.path("value").asText("");
      if (name.isBlank()) continue;
      sb.append("<con:entry key=\"")
        .append(escapeXmlAttr(name)).append("\" value=\"")
        .append(escapeXmlAttr(value)).append("\"/>");
    }
    sb.append("</xml-fragment>");
    // SoapUI expects this fragment to be XML-escaped in the project file.
    return sb.toString().replace("<", "&lt;").replace(">", "&gt;");
  }

  private static String escapeXmlAttr(String s) {
    if (s == null) return "";
    return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
  }

  @SuppressWarnings("unused")
  private static String urlDecode(String s) {
    try { return URLDecoder.decode(s, StandardCharsets.UTF_8); } catch (Exception e) { return s; }
  }
}
