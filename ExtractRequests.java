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
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);

        try (InputStream in = new FileInputStream(input)) {
            XMLEventReader reader = factory.createXMLEventReader(in, StandardCharsets.UTF_8.name());
            process(reader, outDir);
        }
    }

    private static void process(XMLEventReader reader, File outDir) throws Exception {
        Map<String, String> row = null;
        String colName = null;
        StringBuilder valBuf = null;
        boolean inRow=false, inCol=false, inName=false, inValue=false;
        int rowIndex = 0;

        while (reader.hasNext()) {
            XMLEvent ev = reader.nextEvent();

            if (ev.isStartElement()) {
                String tag = ev.asStartElement().getName().getLocalPart();
                if ("row".equals(tag)) { inRow = true; row = new LinkedHashMap<>(); rowIndex++; }
                else if (inRow && "Column".equals(tag)) { inCol = true; colName=null; }
                else if (inCol && "Name".equals(tag)) { inName = true; }
                else if (inCol && "value".equals(tag)) { inValue = true; valBuf = new StringBuilder(); }
            } else if (ev.isCharacters()) {
                String t = ev.asCharacters().getData();
                if (t != null) {
                    if (inName) {
                        String n = t.trim();
                        if (!n.isEmpty()) colName = n;
                    } else if (inValue) {
                        valBuf.append(t);
                    }
                }
            } else if (ev.isEndElement()) {
                String tag = ev.asEndElement().getName().getLocalPart();
                if ("Name".equals(tag)) inName=false;
                else if ("value".equals(tag)) {
                    inValue=false;
                    if (inCol && colName != null) row.put(colName, valBuf==null?"":valBuf.toString().trim());
                } else if ("Column".equals(tag)) { inCol=false; valBuf=null; }
                else if ("row".equals(tag)) {
                    handleRow(row, outDir, rowIndex);
                    inRow=false; row=null;
                }
            }
        }
    }

    private static void handleRow(Map<String, String> row, File outDir, int rowIndex) throws Exception {
        if (row == null) return;

        String typeVal = row.get("TYPE");
        if (typeVal == null || !typeVal.equalsIgnoreCase("Request")) return;

        String dataEscaped = row.get("DATA");
        if (dataEscaped == null || dataEscaped.isEmpty()) return;

        // 1) Unescape entities
        String inner = unescapeXml(dataEscaped);

        // 2) Clean/wrap so it becomes well-formed XML
        String cleanedFragment = cleanFragment(inner);
        String wrapped = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Payload>\n" + cleanedFragment + "\n</Payload>";

        // 3) Parse the WRAPPED document
        Document doc = parseXml(wrapped);

        // 4) Extract values with strict + fallback XPaths
        InnerMeta meta = extractMeta(doc);

        if (isBlank(meta.dmFunction) || isBlank(meta.applicationNumber)) {
            System.err.println("Row " + rowIndex + ": missing DMFunction or ApplicationNumber; skipping.");
            return;
        }

        // 5) Write a proper XML file (the wrapped, cleaned version)
        String safeApp = sanitize(meta.applicationNumber);
        String safeDM  = sanitize(meta.dmFunction);
        File out = new File(outDir, safeApp + "_" + safeDM + ".xml");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8)) {
            w.write(wrapped);
        }
        System.out.println("Wrote: " + out.getAbsolutePath());
    }

    // ---- XML parsing / extraction helpers ----

    private static Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        dbf.setExpandEntityReferences(false);
        try {
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        } catch (Throwable ignore) {}
        DocumentBuilder db = dbf.newDocumentBuilder();
        return db.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static InnerMeta extractMeta(Document wrappedDoc) throws Exception {
        XPath xp = XPathFactory.newInstance().newXPath();

        // NOTE: we query under the wrapper root "Payload"
        // DMFunction as element under Application/CreditRequest
        String dmFunc = firstNonEmpty(xp, wrappedDoc, new String[] {
            "/Payload/*[local-name()='Application']/*[local-name()='CreditRequest']/*[local-name()='DMFunction']/text()",
            "//*/Application/CreditRequest/DMFunction/text()" // if no namespaces
        });

        // ApplicationNumber ONLY inside CreditApplication
        String appNum = firstNonEmpty(xp, wrappedDoc, new String[] {
            "/Payload/*[local-name()='Application']/*[local-name()='CreditApplication']//*[local-name()='ApplicationNumber']/text()",
            "//*/Application/CreditApplication//ApplicationNumber/text()"
        });

        InnerMeta m = new InnerMeta();
        m.dmFunction = trimOrNull(dmFunc);
        m.applicationNumber = trimOrNull(appNum);
        return m;
    }

    // ---- Cleaning helpers to make malformed fragments parseable ----

    private static String cleanFragment(String s) {
        if (s == null) return "";
        String t = s;

        // Remove BOM / zero-width / non-XML leading junk that triggers "content is not allowed in prolog"
        t = t.replace("\uFEFF", "").replace("\u200B", "");

        // Drop everything before the first '<' and after the last '>'
        int start = t.indexOf('<');
        int end   = t.lastIndexOf('>');
        if (start > 0 || end >= 0) {
            if (start >= 0 && end >= start) t = t.substring(start, end + 1);
        }

        // Remove any XML declaration inside the fragment
        t = t.replaceAll("<\\?xml[^>]*\\?>", "");

        // Fix bare ampersands that are not entities (common cause of “Element type …”)
        t = t.replaceAll("&(?!amp;|lt;|gt;|quot;|apos;|#[0-9]+;|#x[0-9A-Fa-f]+;)", "&amp;");

        t = t.trim();
        return t;
    }

    // ---- Small utils ----

    private static String firstNonEmpty(XPath xp, Document doc, String[] paths) throws Exception {
        for (String p : paths) {
            String v = (String) xp.evaluate(p, doc, XPathConstants.STRING);
            if (!isBlank(v)) return v;
        }
        return null;
    }

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
    private static String trimOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static class InnerMeta {
        String applicationNumber;
        String dmFunction;
    }
}
