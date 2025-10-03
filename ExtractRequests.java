import javax.xml.stream.*;
import javax.xml.stream.events.XMLEvent;
import javax.xml.stream.events.Characters;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.EndElement;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.DocumentBuilder;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import javax.xml.xpath.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Usage:
 *   java ExtractRequestData input.xml output_dir
 *
 * For each <row> where Column(Name=TYPE, value=Request), this writes the inner XML
 * from Column(Name=DATA) to: <ApplicationNumber>_<DMFunction>.xml
 */
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
        factory.setProperty(XMLInputFactory.IS_COALESCING, true); // merge adjacent text nodes
        try (InputStream in = new FileInputStream(input)) {
            XMLEventReader reader = factory.createXMLEventReader(in, StandardCharsets.UTF_8.name());
            process(reader, outDir);
        }
    }

    private static void process(XMLEventReader reader, File outDir) throws Exception {
        Map<String, String> currentRow = null;
        String currentColumnName = null;
        StringBuilder currentValueBuf = null;
        boolean insideRow = false;
        boolean insideColumn = false;
        boolean insideName = false;
        boolean insideValue = false;

        while (reader.hasNext()) {
            XMLEvent ev = reader.nextEvent();

            if (ev.isStartElement()) {
                StartElement se = ev.asStartElement();
                String local = se.getName().getLocalPart();

                if ("row".equals(local)) {
                    insideRow = true;
                    currentRow = new LinkedHashMap<>();
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
                if (insideName && ch.getData() != null) {
                    // name text
                    String n = ch.getData().trim();
                    if (!n.isEmpty()) currentColumnName = n;
                } else if (insideValue && ch.getData() != null) {
                    currentValueBuf.append(ch.getData());
                }

            } else if (ev.isEndElement()) {
                EndElement ee = ev.asEndElement();
                String local = ee.getName().getLocalPart();

                if ("Name".equals(local)) {
                    insideName = false;
                } else if ("value".equals(local)) {
                    insideValue = false;
                    // store accumulated value for this column name
                    if (insideColumn && currentColumnName != null) {
                        currentRow.put(currentColumnName, currentValueBuf == null ? "" : currentValueBuf.toString().trim());
                    }
                } else if ("Column".equals(local)) {
                    insideColumn = false;
                    currentValueBuf = null;
                } else if ("row".equals(local)) {
                    // finished a row: evaluate
                    handleRow(currentRow, outDir);
                    insideRow = false;
                    currentRow = null;
                }
            }
        }
    }

    private static void handleRow(Map<String, String> row, File outDir) throws Exception {
        if (row == null) return;

        // Condition: Column Name == TYPE has value == Request (case-insensitive)
        String typeVal = row.get("TYPE");
        if (typeVal == null || !typeVal.equalsIgnoreCase("Request")) return;

        // Need DATA column
        String dataEscaped = row.get("DATA");
        if (dataEscaped == null || dataEscaped.isEmpty()) return;

        // Unescape XML entities (&lt; &gt; &amp; etc.)
        String innerXml = unescapeXml(dataEscaped).trim();
        if (innerXml.isEmpty()) return;

        // Parse the inner XML to pick filename parts
        InnerMeta meta = extractInnerMeta(innerXml);
        if (meta == null || meta.applicationNumber == null || meta.dmFunction == null) {
            // Fallback: if meta missing, still write with a generic name
            meta = meta == null ? new InnerMeta() : meta;
            if (meta.applicationNumber == null) meta.applicationNumber = "UNKNOWN_APP";
            if (meta.dmFunction == null) meta.dmFunction = "UNKNOWN_DM";
        }

        // Build file name and write
        String safeApp = sanitize(meta.applicationNumber);
        String safeDM  = sanitize(meta.dmFunction);
        File out = new File(outDir, safeApp + "_" + safeDM + ".xml");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8)) {
            w.write(innerXml);
        }
        System.out.println("Wrote: " + out.getAbsolutePath());
    }

    // Extracts DMFunction (attribute of <CreditRequest>) and ApplicationNumber element text.
    private static InnerMeta extractInnerMeta(String xml) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
            dbf.setExpandEntityReferences(false);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

            XPathFactory xpf = XPathFactory.newInstance();
            XPath xp = xpf.newXPath();
            InnerMeta m = new InnerMeta();
            // DMFunction attribute on the root/first CreditRequest element
            m.dmFunction = xp.evaluate("/*[@DMFunction]/@DMFunction", doc);
            if (m.dmFunction == null || m.dmFunction.isEmpty()) {
                // sometimes attribute may be lower/upper-cased differently—try case-insensitive-ish search
                m.dmFunction = xp.evaluate("/*/@DMFUNCTION | /*/@dmfunction", doc);
            }
            // ApplicationNumber element anywhere
            m.applicationNumber = xp.evaluate("//*[local-name()='ApplicationNumber' or name()='ApplicationNumber']/text()", doc);
            if (m.applicationNumber != null) m.applicationNumber = m.applicationNumber.trim();
            if (m.dmFunction != null) m.dmFunction = m.dmFunction.trim();
            return m;
        } catch (Exception e) {
            System.err.println("Failed to parse inner XML: " + e.getMessage());
            return null;
        }
    }

    // Minimal XML unescape (avoids 3rd-party deps). If you prefer, swap for Apache Commons Text.
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

    private static class InnerMeta {
        String applicationNumber;
        String dmFunction;
    }
}
