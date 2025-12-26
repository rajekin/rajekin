import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.xml.XMLConstants;
import javax.xml.transform.*;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

public class XmlToJsonWithXsltFolder {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: java XmlToJsonWithXsltFolder <xsltDir> <inputXml> <outputJson>");
            System.err.println("Example: java XmlToJsonWithXsltFolder ./xslts ./input.xml ./out.json");
            System.exit(2);
        }

        Path xsltDir = Paths.get(args[0]);
        Path inputXml = Paths.get(args[1]);
        Path outputJson = Paths.get(args[2]);

        List<Path> xsltFiles = listXsltFiles(xsltDir);
        if (xsltFiles.isEmpty()) {
            throw new IllegalArgumentException("No .xsl/.xslt files found in: " + xsltDir.toAbsolutePath());
        }

        // Start with the original XML as a string (so we can chain transforms in-memory)
        String current = Files.readString(inputXml, StandardCharsets.UTF_8);

        TransformerFactory tf = newSecureTransformerFactory();

        // Compile templates for each XSLT (faster and also validates early)
        List<Templates> pipeline = new ArrayList<>();
        for (Path xslt : xsltFiles) {
            StreamSource xsltSource = new StreamSource(xslt.toFile());
            // Important: set systemId so xsl:include/xsl:import relative paths resolve correctly
            xsltSource.setSystemId(xslt.toUri().toString());
            pipeline.add(tf.newTemplates(xsltSource));
        }

        // Apply each XSLT in order
        for (int i = 0; i < pipeline.size(); i++) {
            Templates t = pipeline.get(i);
            String transformed = transformToString(t, current);
            current = transformed;
            // Optional: uncomment to debug intermediate outputs
            // System.out.println("After XSLT " + (i+1) + ":\n" + current);
        }

        // Expect final output to be JSON text
        String jsonText = current.trim();

        // Validate + pretty print JSON
        ObjectMapper mapper = new ObjectMapper();
        JsonNode node;
        try {
            node = mapper.readTree(jsonText);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Final transform output is not valid JSON. " +
                    "If your last XSLT outputs XML, you’ll need an XML->JSON step.\n" +
                    "First 300 chars of output:\n" + preview(jsonText, 300), e);
        }

        Files.createDirectories(outputJson.toAbsolutePath().getParent());
        String pretty = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        Files.writeString(outputJson, pretty, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        System.out.println("Done. Applied " + pipeline.size() + " XSLT(s). Output: " + outputJson.toAbsolutePath());
        System.out.println("XSLT order:");
        for (Path p : xsltFiles) System.out.println(" - " + p.getFileName());
    }

    private static List<Path> listXsltFiles(Path xsltDir) throws IOException {
        if (!Files.isDirectory(xsltDir)) {
            throw new IllegalArgumentException("Not a directory: " + xsltDir.toAbsolutePath());
        }
        try (var stream = Files.list(xsltDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return n.endsWith(".xslt") || n.endsWith(".xsl");
                    })
                    .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .collect(Collectors.toList());
        }
    }

    private static TransformerFactory newSecureTransformerFactory() {
        TransformerFactory tf = TransformerFactory.newInstance();

        // Harden XML/XSLT processing (prevents XXE / external entity access)
        try { tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true); } catch (Exception ignored) {}

        // Block external DTDs and stylesheets where supported
        try { tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); } catch (Exception ignored) {}
        try { tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, ""); } catch (Exception ignored) {}

        // Optional: custom URIResolver if you want to control include/import loading.
        // Default works when systemId is set (we do that).
        // tf.setURIResolver((href, base) -> null);

        return tf;
    }

    private static String transformToString(Templates templates, String inputXmlOrText) throws TransformerException {
        Transformer transformer = templates.newTransformer();

        // If your XSLTs need params, set them here:
        // transformer.setParameter("someParam", "value");

        StreamSource in = new StreamSource(new StringReader(inputXmlOrText));
        StringWriter out = new StringWriter();
        transformer.transform(in, new StreamResult(out));
        return out.toString();
    }

    private static String preview(String s, int max) {
        if (s == null) return "";
        s = s.replace("\r", "");
        return s.length() <= max ? s : s.substring(0, max) + " ...";
    }
}
