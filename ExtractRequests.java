import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class XmlToJsonXsltFolder {

    /**
     * Usage:
     *   java XmlToJsonXsltFolder <xsltDir> <inputXml> <outputJson> [--dump=<folder>] [--strictXmlPipeline=true|false]
     *
     * Notes:
     * - XSLT pipeline MUST stay XML until the final step. If any step outputs JSON/text and there are more XSLTs,
     *   you’ll get “Content is not allowed in prolog” (because JSON is not XML).
     * - Control order by naming: 01-*.xslt, 02-*.xslt, 03-*.xslt
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: java XmlToJsonXsltFolder <xsltDir> <inputXml> <outputJson> [--dump=<folder>] [--strictXmlPipeline=true|false]");
            System.exit(2);
        }

        Path xsltDir = Paths.get(args[0]);
        Path inputXmlPath = Paths.get(args[1]);
        Path outputJsonPath = Paths.get(args[2]);

        Path dumpDir = null;
        boolean strictXmlPipeline = true; // recommended

        for (int i = 3; i < args.length; i++) {
            String a = args[i].trim();
            if (a.startsWith("--dump=")) {
                dumpDir = Paths.get(a.substring("--dump=".length()));
            } else if (a.startsWith("--strictXmlPipeline=")) {
                strictXmlPipeline = Boolean.parseBoolean(a.substring("--strictXmlPipeline=".length()));
            }
        }

        List<Path> xsltFiles = listXsltFiles(xsltDir);
        if (xsltFiles.isEmpty()) {
            throw new IllegalArgumentException("No .xsl/.xslt files found in: " + xsltDir.toAbsolutePath());
        }

        if (dumpDir != null) Files.createDirectories(dumpDir);

        // Read and sanitize initial XML (BOM / junk before '<')
        String current = Files.readString(inputXmlPath, StandardCharsets.UTF_8);
        current = sanitizeXmlLikeInput(current);

        if (!looksLikeXml(current) || !isWellFormedXml(current)) {
            throw new IllegalStateException(
                    "Input file does not look like well-formed XML after sanitization.\n" +
                    "First 200 chars:\n" + preview(current, 200));
        }

        TransformerFactory tf = newSecureTransformerFactory();

        List<Templates> templates = new ArrayList<>();
        List<String> outputMethods = new ArrayList<>();

        for (Path xslt : xsltFiles) {
            StreamSource xsltSource = new StreamSource(xslt.toFile());
            // Important for xsl:include/import relative resolution
            xsltSource.setSystemId(xslt.toUri().toString());

            templates.add(tf.newTemplates(xsltSource));
            outputMethods.add(detectXsltOutputMethod(xslt)); // xml/html/text/unknown
        }

        String finalText = null;

        for (int i = 0; i < templates.size(); i++) {
            Path xsltFile = xsltFiles.get(i);
            String method = outputMethods.get(i);

            // Ensure we only feed XML into an XSLT transform
            current = sanitizeXmlLikeInput(current);
            boolean currentIsXml = looksLikeXml(current) && isWellFormedXml(current);

            if (!currentIsXml) {
                String msg =
                        "Step " + (i + 1) + " cannot run because the current pipeline content is NOT XML.\n" +
                        "This usually means an earlier XSLT already output JSON/text.\n" +
                        "Current content starts with: " + preview(firstNonWs(current), 60) + "\n" +
                        "Next XSLT file: " + xsltFile.getFileName() + "\n";
                if (strictXmlPipeline) throw new IllegalStateException(msg);
            }

            String transformed;
            try {
                transformed = transformToString(templates.get(i), current);
            } catch (TransformerException te) {
                // Add extra diagnostics for “Content is not allowed in prolog”
                String head = preview(firstNonWs(current), 120);
                throw new IllegalStateException(
                        "XSLT transform failed at step " + (i + 1) + " using " + xsltFile.getFileName() + "\n" +
                        "XSLT declared output method: " + method + "\n" +
                        "Input starts with: " + head + "\n" +
                        "If you see JSON/text here, ensure ONLY the FINAL XSLT outputs JSON/text.\n",
                        te
                );
            }

            if (dumpDir != null) {
                String dumpName = String.format("%02d_%s.out", (i + 1), stripExt(xsltFile.getFileName().toString()));
                Files.writeString(dumpDir.resolve(dumpName), transformed, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }

            String transformedTrim = firstNonWs(transformed);
            boolean producedJsonLike = transformedTrim.startsWith("{") || transformedTrim.startsWith("[");
            boolean producedText = "text".equalsIgnoreCase(method) || producedJsonLike;

            if (producedText) {
                finalText = transformed;
                if (i != templates.size() - 1) {
                    throw new IllegalStateException(
                            "Your pipeline produced JSON/text at step " + (i + 1) + " (" + xsltFile.getFileName() + "),\n" +
                            "but there are more XSLTs after it.\n\n" +
                            "Fix: ensure all earlier XSLTs output XML, and ONLY the last one outputs JSON/text.\n" +
                            "Output starts with: " + preview(transformedTrim, 120) + "\n"
                    );
                }
            } else {
                // Continue as XML
                current = transformed;
            }
        }

        if (finalText == null) {
            // Pipeline ended with XML; that means your last XSLT didn't output JSON.
            String endTrim = firstNonWs(current);
            throw new IllegalStateException(
                    "Pipeline completed but did not produce JSON/text output.\n" +
                    "Last output looks like XML (starts with): " + preview(endTrim, 120) + "\n" +
                    "Fix: make the final XSLT output JSON with <xsl:output method=\"text\" encoding=\"UTF-8\"/>"
            );
        }

        // Validate + pretty print JSON
        String jsonText = finalText.trim();
        ObjectMapper mapper = new ObjectMapper();

        JsonNode node;
        try {
            node = mapper.readTree(jsonText);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Final output is not valid JSON.\n" +
                    "First 300 chars:\n" + preview(jsonText, 300) + "\n" +
                    "If your last XSLT outputs XML, you need an XML->JSON step or change the last XSLT.\n",
                    e
            );
        }

        Files.createDirectories(outputJsonPath.toAbsolutePath().getParent());
        String pretty = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        Files.writeString(outputJsonPath, pretty, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        System.out.println("Done.");
        System.out.println("Applied XSLTs in order:");
        for (int i = 0; i < xsltFiles.size(); i++) {
            System.out.println("  " + (i + 1) + ". " + xsltFiles.get(i).getFileName() + " (method=" + outputMethods.get(i) + ")");
        }
        if (dumpDir != null) {
            System.out.println("Intermediate outputs dumped to: " + dumpDir.toAbsolutePath());
        }
        System.out.println("Output JSON: " + outputJsonPath.toAbsolutePath());
    }

    // -------------------- Core helpers --------------------

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

    /** Removes UTF-8 BOM and trims junk before first '<' (common cause of "Content is not allowed in prolog"). */
    private static String sanitizeXmlLikeInput(String s) {
        if (s == null) return null;
        s = s.replace("\uFEFF", ""); // BOM
        int idx = s.indexOf('<');
        if (idx > 0) {
            // Drop any junk before the first '<'
            s = s.substring(idx);
        }
        return s;
    }

    private static TransformerFactory newSecureTransformerFactory() {
        TransformerFactory tf = TransformerFactory.newInstance();
        try { tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true); } catch (Exception ignored) {}
        try { tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); } catch (Exception ignored) {}
        try { tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, ""); } catch (Exception ignored) {}
        return tf;
    }

    private static String transformToString(Templates templates, String inputXml) throws TransformerException {
        Transformer transformer = templates.newTransformer();
        StreamSource in = new StreamSource(new StringReader(inputXml));
        StringWriter out = new StringWriter();
        transformer.transform(in, new StreamResult(out));
        return out.toString();
    }

    /** Fast heuristic (doesn't guarantee validity). */
    private static boolean looksLikeXml(String s) {
        if (s == null) return false;
        String t = firstNonWs(s);
        return t.startsWith("<");
    }

    /** Validates XML well-formedness by parsing (securely). */
    private static boolean isWellFormedXml(String xml) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);

            // Secure XML parsing
            try { dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true); } catch (Exception ignored) {}
            try { dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (Exception ignored) {}
            try { dbf.setFeature("http://xml.org/sax/features/external-general-entities", false); } catch (Exception ignored) {}
            try { dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false); } catch (Exception ignored) {}
            try { dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); } catch (Exception ignored) {}
            try { dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, ""); } catch (Exception ignored) {}

            var db = dbf.newDocumentBuilder();
            db.parse(new InputSource(new StringReader(xml)));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Attempts to detect <xsl:output method="..."> from an XSLT file.
     * Returns: "xml", "html", "text", or "unknown"
     */
    private static String detectXsltOutputMethod(Path xsltFile) {
        try {
            String xslt = Files.readString(xsltFile, StandardCharsets.UTF_8);
            // simple regex is enough for detection (not full parsing)
            Pattern p = Pattern.compile("<\\s*xsl:output\\b[^>]*\\bmethod\\s*=\\s*['\"](xml|html|text)['\"][^>]*>",
                    Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(xslt);
            if (m.find()) return m.group(1).toLowerCase(Locale.ROOT);
        } catch (Exception ignored) {}
        return "unknown";
    }

    private static String firstNonWs(String s) {
        if (s == null) return "";
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return s.substring(i);
    }

    private static String preview(String s, int max) {
        if (s == null) return "";
        s = s.replace("\r", "");
        if (s.length() <= max) return s;
        return s.substring(0, max) + " ...";
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return (dot > 0) ? name.substring(0, dot) : name;
    }
}
