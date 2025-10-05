import javax.xml.stream.*;
import javax.xml.stream.events.*;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.xpath.*;
import org.w3c.dom.Document;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

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

        String fragment = unescapeXml(dataEscaped);

        // Wrap fragment so DOM has a single root
        String wrapped = wrap(fragment);

        Document doc = null;
        try {
            doc = parseDom(wrapped);
        } catch (Exception domFail) {
            // If DOM parsing fails (e.g., malformed tags), repair with jsoup
            String repaired = tidyWithJsoup(fragment);
            wrapped = wrap(repaired);
            doc = parseDom(wrapped); // if this throws, we let it bubble
        }

        InnerMeta meta = extractMetaStrictThenFallback(doc);
        if (isBlank(meta.dmFunction) || isBlank(meta.applicationNumber)) {
            System.err.println("Row " + rowIndex + ": missing DMFunction or ApplicationNumber; skipping.");
            return;
        }

        // Write cleaned, wrapped payload
        String safeApp = sanitize(meta.applicationNumber);
        String safeDM  = sanitize(meta.dmFunction);
        File out = new File(outDir, safeApp + "_" + safeDM + ".xml");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8)) {
            w.write(wrapped);
        }
        System.out.println("Wrote: " + out.getAbsolutePath());
    }

    // ------------- META (strict + previous robust fallback) -------------

    private static InnerMeta extractMetaStrictThenFallback(Document wrappedDoc) throws Exception {
        XPath xp = XPathFactory.newInstance().newXPath();

        // STRICT (namespace-agnostic)
        String dmFunc = (String) xp.evaluate(
            "/Payload/*[local-name()='Application']/*[local-name()='CreditRequest']/*[local-name()='DMFunction']/text()",
            wrappedDoc, XPathConstants.STRING);
        String appNum = (String) xp.evaluate(
            "/Payload/*[local-name()='Application']/*[local-name()='CreditApplication']//*[local-name()='ApplicationNumber']/text()",
            wrappedDoc, XPathConstants.STRING);

        dmFunc = trimOrNull(dmFunc);
        appNum = trimOrNull(appNum);
        if (!isBlank(dmFunc) && !isBlank(appNum)) {
            InnerMeta m = new InnerMeta(); m.dmFunction=dmFunc; m.applicationNumber=appNum; return m;
        }

        // FALLBACKS (your previous method + extras)
        String[] dmPaths = new String[] {
            "/*[local-name()='Payload']//*[local-name()='CreditRequest']/*[local-name()='DMFunction']/text()",
            "/*[local-name()='Payload']//*[local-name()='CreditRequest']/@DMfunction",
            "/*[local-name()='Payload']//*[local-name()='CreditRequest']/@DMFunction",
            "/*[local-name()='Payload']//*[local-name()='DMFunction']/text()"
        };
        String[] appNumPaths = new String[] {
            "/Payload/*[local-name()='Application']/*[local-name()='CreditApplication']//*[local-name()='ApplicationNumber']/text()",
            "/*[local-name()='Payload']//*[local-name()='CreditApplication']//*[local-name()='ApplicationNumber']/text()",
            "/*[local-name()='Payload']//*[local-name()='ApplicationNumber']/text()"
        };

        String dmFB = firstNonEmpty(xp, wrappedDoc, dmPaths);
        String appFB = firstNonEmpty(xp, wrappedDoc, appNumPaths);

        InnerMeta m = new InnerMeta();
        m.dmFunction = trimOrNull(dmFB);
        m.applicationNumber = trimOrNull(appFB);
        return m;
    }

    private static String firstNonEmpty(XPath xp, Document doc, String[] paths) throws Exception {
        for (String p : paths) {
            String v = (String) xp.evaluate(p, doc, XPathConstants.STRING);
            if (!isBlank(v)) return v;
        }
        return null;
    }

    // --------------------- Parsing helpers ---------------------

    private static Document parseDom(String xml) throws Exception {
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

    private static String tidyWithJsoup(String fragment) {
        // Use lenient HTML parser to fix tag soup (missing '>', unclosed tags, etc.)
        org.jsoup.nodes.Document html = Jsoup.parse(fragment, "", org.jsoup.parser.Parser.htmlParser());
        // Move body children under a synthetic root to keep structure
        Element root = new Element("Payload");
        for (org.jsoup.nodes.Node n : html.body().childNodes()) {
            root.appendChild(n.clone());
        }
        org.jsoup.nodes.Document xmlDoc = org.jsoup.nodes.Document.createShell("");
        xmlDoc.removeClass(""); // no-op, just to keep compiler quiet about xmlDoc usage
        // Serialize as XML (self-closes empty tags, ensures angle brackets, etc.)
        org.jsoup.nodes.Document out = new org.jsoup.nodes.Document("");
        out.appendChild(root);
        out.outputSettings().syntax(org.jsoup.nodes.Document.OutputSettings.Syntax.xml);
        out.outputSettings().escapeMode(org.jsoup.nodes.Entities.EscapeMode.base);
        out.outputSettings().prettyPrint(false);
        return out.outerHtml()
                  .replaceFirst("^<Payload>", "")      // strip the extra wrapper we just added
                  .replaceFirst("</Payload>$", "");
    }

    private static String wrap(String fragment) {
        String cleaned = fragment == null ? "" : fragment.replace("\uFEFF", "").replace("\u200B","").trim();
        // drop anything before first '<' and after last '>'
        int s = cleaned.indexOf('<'); int e = cleaned.lastIndexOf('>');
        if (s >= 0 && e >= s) cleaned = cleaned.substring(s, e+1);
        // remove inner XML declarations if present
        cleaned = cleaned.replaceAll("<\\?xml[^>]*\\?>", "");
        // fix bare ampersands
        cleaned = cleaned.replaceAll("&(?!amp;|lt;|gt;|quot;|apos;|#[0-9]+;|#x[0-9A-Fa-f]+;)", "&amp;");
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Payload>\n" + cleaned + "\n</Payload>";
    }

    // ---------------------- Small utils -----------------------

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
