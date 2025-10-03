import javax.xml.stream.*;
import javax.xml.stream.events.*;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.xpath.*;
import org.w3c.dom.Document;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class ExtractRequestData {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: java ExtractRequestData <input-xml> <output-dir>");
            System.exit(1);
        }

        File input = new File(args[0]);
        File outDir = new File(args[1]);
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new IOException("Cannot create output dir: " + outDir.getAbsolutePath());
        }

        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.IS_COALESCING, true); // merge adjacent text

        try (InputStream in = new FileInputStream(input)) {
            XMLEventReader reader = factory.createXMLEventReader(in, StandardCharsets.UTF_8.name());
            process(reader, outDir);
        }
    }

    private static void process(XMLEventReader reader, File outDir) throws Exception {
        Map<String, String> currentRow = null; // ColumnName -> value
        String currentColumnName = null;
        StringBuilder currentValueBuf = null;

        boolean insideRow = false;
        boolean insideColumn = false;
        boolean insideName = false;
        boolean insideValue = false;

        int rowIndex = 0;

        while (reader.hasNext()) {
            XMLEvent ev = reader.nextEvent();

            if (ev.isStartElement()) {
                StartElement se = ev.asStartElement();
                String local = se.getName().getLocalPart();

                if ("row".equals(local)) {
                    insideRow = true;
                    currentRow = new LinkedHashMap<>();
                    rowIndex++;
                } else if (insideRow && "Column".equals(local)) {
                    insideColumn = true;
                    currentColumnName = null;
                } else if (insideColumn && "Name".equals(local)) {
                    insideName = true;
                } else if (insideColumn && "value".equals(local)) {
                    insideValue = true;
                    currentValueBuf = new StringBuilder();
                }

            } else if (ev.isCharacters()) {
                Characters ch = ev.asCharacters();
                String text = ch.getData();
                if (text != null) {
                    if (insideName) {
                        String n = text.trim();
                        if (!n.isEmpty()) currentColumnName = n;
                    } else if (insideValue) {
                        currentValueBuf.append(text);
                    }
                }

            } else if (ev.isEndElement()) {
                EndElement ee = ev.asEndElement();
                String local = ee.getName().getLocalPart();

                if ("Name".equals(local)) {
                    insideName = false;
                } else if ("value".equals(local)) {
                    insideValue = false;
                    if (insideColumn && currentColumnName != null) {
                        currentRow.put(currentColumnName, currentValueBuf == null ? "" : currentValueBuf.toString().trim());
                    }
                } else if ("Column".equals(local)) {
                    insideColumn = false;
                    currentValueBuf = null;
                } else if ("row".equals(local)) {
                    handleRow(currentRow, outDir, rowIndex);
                    insideRow = false;
                    currentRow = null;
                }
            }
        }
    }

    private static void handleRow(Map<String, String> row, File outDir, int rowIndex) throws Exception {
        if (row == null) return;

        // Must have TYPE=Request
        String typeVal = row.get("TYPE");
        if (typeVal == null || !typeVal.equalsIgnoreCase("Request")) return;

        // Must have DATA column
        String dataEscaped = row.get("DATA");
        if (dataEscaped == null || dataEscaped.isEmpty()) return;

        // Unescape &lt; &gt; &amp; etc. to real XML
        String innerXml = unescapeXml(dataEscaped).trim();
        if (innerXml.isEmpty()) return;

        // --- Use YOUR EXACT XPaths ---
        InnerMeta meta = extractWithYourXpath(innerXml);

        if (meta == null || isBlank(meta.dmFunction) || isBlank(meta.applicationNumber)) {
            System.err.println("Row " + rowIndex + ": missing DMfunction or ApplicationNumber; skipping.");
            return;
        }

        // Write file as <ApplicationNumber>_<DMfunction>.xml
        String safeApp = sanitize(meta.applicationNumber);
        String safeDM  = sanitize(meta.dmFunction);
        File out = new File(outDir, safeApp + "_" + safeDM + ".xml");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8)) {
            w.write(innerXml);
        }
        System.out.println("Wrote: " + out.getAbsolutePath());
    }

    // Parse inner XML and extract with the exact XPaths you provided.
    private static InnerMeta extractWithYourXpath(String xml) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
            dbf.setExpandEntityReferences(false);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document innerDoc = db.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

            XPath xpath = XPathFactory.newInstance().newXPath();

            // Extract DMfunction
            String dmFunc = (String) xpath.evaluate(
                "//*[local-name()='CreditRequest']/@DMfunction",
                innerDoc, XPathConstants.STRING);

            // Extract ApplicationNumber
            String appNum = (String) xpath.evaluate(
                "//*[local-name()='ApplicationNumber']/text()",
                innerDoc, XPathConstants.STRING);

            InnerMeta m = new InnerMeta();
            m.dmFunction = dmFunc != null ? dmFunc.trim() : null;
            m.applicationNumber = appNum != null ? appNum.trim() : null;
            return m;

        } catch (Exception e) {
            System.err.println("Failed to parse inner XML: " + e.getMessage());
            return null;
        }
    }

    // Minimal XML unescape (no external deps)
    private static String unescapeXml(String s) {
        return s.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replace("&apos;", "'")
                .replace("&quot;", "\"");
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static class InnerMeta {
        String applicationNumber;
        String dmFunction;
    }
}
