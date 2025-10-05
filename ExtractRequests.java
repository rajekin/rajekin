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

        // 1) Unescape entities to get the inner fragment
        String inner = unescapeXml(dataEscaped);

        // 2) Repair the fragment and wrap so it becomes well-formed XML
        String repaired = repairFragment(inner);
        String wrapped = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Payload>\n" + repaired + "\n</Payload>";

        // 3) Parse the wrapped doc
        Document doc = parseXml(wrapped);

        // 4) Extract meta using STRICT paths first, then robust fallbacks
        InnerMeta meta = extractMetaStrictThenFallback(doc);

        if (isBlank(meta.dmFunction) || isBlank(meta.applicationNumber)) {
            System.err.println("Row " + rowIndex + ": missing DMFunction or ApplicationNumber; skipping.");
            // Uncomment for a quick peek at the content that failed
            // System.err.println("--- DEBUG FRAGMENT START ---\n" + repaired.substring(0, Math.min(repaired.length(), 1200)) + "\n--- DEBUG FRAGMENT END ---");
            return;
        }

        // 5) Write a *proper* XML file (wrapped, repaired)
        String safeApp = sanitize(meta.applicationNumber);
        String safeDM  = sanitize(meta.dmFunction);
        File out = new File(outDir, safeApp + "_" + safeDM + ".xml");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8)) {
            w.write(wrapped);
        }
        System.out.println("Wrote: " + out.getAbsolutePath());
    }

    // ------------------------------------------------------------
    //              META EXTRACTION (strict + fallback)
    // ------------------------------------------------------------

    private static InnerMeta extractMetaStrictThenFallback(Document wrappedDoc) throws Exception {
        XPath xpath = XPathFactory.newInstance().newXPath();

        // STRICT: only get ApplicationNumber under CreditApplication
        String dmFuncStrict = (String) xpath.evaluate(
            "/Payload/*[local-name()='Application']/*[local-name()='CreditRequest']/*[local-name()='DMFunction']/text()",
            wrappedDoc, XPathConstants.STRING);

        String appNumStrict = (String) xpath.evaluate(
            "/Payload/*[local-name()='Application']/*[local-name()='CreditApplication']//*[local-name()='ApplicationNumber']/text()",
            wrappedDoc, XPathConstants.STRING);

        String dmFunc = trimOrNull(dmFuncStrict);
        String appNum = trimOrNull(appNumStrict);

        if (!isBlank(dmFunc) && !isBlank(appNum)) {
            InnerMeta m = new InnerMeta();
            m.dmFunction = dmFunc;
            m.applicationNumber = appNum;
            return m;
        }

        // FALLBACKS: previous robust method (attribute/element, namespace-agnostic, anywhere)
        String[] dmPaths = new String[] {
            // Element DMFunction under CreditRequest (ns-agnostic)
            "/Payload/*[local-name()='Application']/*[local-name()='CreditRequest']/*[local-name()='DMFunction']/text()",
            "/*[local-name()='Payload']//*[local-name()='CreditRequest']/*[local-name()='DMFunction']/text()",
            // Attribute variants in case some rows store it as attribute
            "/Payload/*[local-name()='Application']/*[local-name()='CreditRequest']/@DMfunction",
            "/Payload/*[local-name()='Application']/*[local-name()='CreditRequest']/@DMFunction",
            "/*[local-name()='Payload']//*[local-name()='CreditRequest']/@DMfunction",
            "/*[local-name()='Payload']//*[local-name()='CreditRequest']/@DMFunction",
            // Last resort: any DMFunction element
            "/*[local-name()='Payload']//*[local-name()='DMFunction']/text()"
        };

        String[] appNumPaths = new String[] {
            // CreditApplication only (ns-agnostic)
            "/Payload/*[local-name()='Application']/*[local-name()='CreditApplication']//*[local-name()='ApplicationNumber']/text()",
            // More permissive, anywhere
            "/*[local-name()='Payload']//*[local-name()='CreditApplication']//*[local-name()='ApplicationNumber']/text()",
            "/*[local-name()='Payload']//*[local-name()='ApplicationNumber']/text()"
        };

        String dmFuncFB = firstNonEmpty(xpath, wrappedDoc, dmPaths);
        String appNumFB = firstNonEmpty(xpath, wrappedDoc, appNumPaths);

        InnerMeta m = new InnerMeta();
        m.dmFunction = trimOrNull(dmFuncFB);
        m.applicationNumber = trimOrNull(appNumFB);
        return m;
    }

    private static String firstNonEmpty(XPath xp, Document doc, String[] paths) throws Exception {
        for (String p : paths) {
            String v = (String) xp.evaluate(p, doc, XPathConstants.STRING);
            if (!isBlank(v)) return v;
        }
        return null;
    }

    // ------------------------------------------------------------
    //                    XML PARSE / REPAIR
    // ------------------------------------------------------------

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

    private static String repairFragment(String s) {
        if (s == null) return "";

        String t = s.replace("\uFEFF", "")     // BOM
                    .replace("\u200B", "");    // zero-width space

        // Keep only the XML-ish part
        int start = t.indexOf('<');
        int end   = t.lastIndexOf('>');
        if (start >= 0 && end >= start) t = t.substring(start, end + 1);

        // Remove inner XML declarations
        t = t.replaceAll("<\\?xml[^>]*\\?>", "");

        // Fix bare '&' (but keep valid entities)
        t = t.replaceAll("&(?!amp;|lt;|gt;|quot;|apos;|#[0-9]+;|#x[0-9A-Fa-f]+;)", "&amp;");

        // Ensure every '<' has a following '>' — if not, add it at the end of the segment
        t = ensureAngles(t);

        // If a start tag is immediately followed by another tag (no content), make it self-closing
        // e.g., "<Tag ...><Next"  -> "<Tag .../><Next"
        t = t.replaceAll("(<[A-Za-z_][\\w\\-.:]*[^<>]*?)>(\\s*)(?=<)", "$1/>$2");

        return t.trim();
    }

    // Adds a missing '>' if a '<...>' chunk is missing it.
    private static String ensureAngles(String xml) {
        StringBuilder out = new StringBuilder(xml.length() + 32);
        for (int i = 0; i < xml.length(); ) {
            int lt = xml.indexOf('<', i);
            if (lt < 0) {
                out.append(xml, i, xml.length());
                break;
            }
            // copy plain text before the tag
            out.append(xml, i, lt);
            int gt = xml.indexOf('>', lt + 1);
            if (gt < 0) {
                // No closing '>' — add one at the end of this run or line
                int nl = xml.indexOf('\n', lt + 1);
                int cut = (nl > 0 ? nl : xml.length());
                out.append(xml, lt, cut).append('>');
                i = cut;
            } else {
                out.append(xml, lt, gt + 1);
                i = gt + 1;
            }
        }
        return out.toString();
    }

    // ------------------------------------------------------------
    //                  SMALL UTILS / DATA CLASS
    // ------------------------------------------------------------

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
