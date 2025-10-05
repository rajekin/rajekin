import javax.xml.stream.*;
import javax.xml.stream.events.*;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.xpath.*;
import org.w3c.dom.Document;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        String fragment = unescapeXml(dataEscaped);

        // 2) Repair tag soup
        String repaired = repairFragment(fragment);

        // 3) Try to parse as a normal wrapped XML
        String wrapped = wrapXml(repaired);
        Document doc = null;
        boolean domOk = false;
        try {
            doc = parseDom(wrapped);
            domOk = true;
        } catch (Exception ignore) {
            domOk = false;
        }

        String dmFunc = null;
        String appNum = null;

        if (domOk) {
            // STRICT first
            XPath xp = XPathFactory.newInstance().newXPath();
            dmFunc = (String) xp.evaluate(
                "/Payload/*[local-name()='Application']/*[local-name()='CreditRequest']/*[local-name()='DMFunction']/text()",
                doc, XPathConstants.STRING);
            appNum = (String) xp.evaluate(
                "/Payload/*[local-name()='Application']/*[local-name()='CreditApplication']//*[local-name()='ApplicationNumber']/text()",
                doc, XPathConstants.STRING);

            dmFunc = trimOrNull(dmFunc);
            appNum = trimOrNull(appNum);

            // FALLBACK (previous robust method)
            if (isBlank(dmFunc) || isBlank(appNum)) {
                String dmFB = firstNonEmpty(xp, doc, new String[] {
                    "/*[local-name()='Payload']//*[local-name()='CreditRequest']/*[local-name()='DMFunction']/text()",
                    "/*[local-name()='Payload']//*[local-name()='CreditRequest']/@DMfunction",
                    "/*[local-name()='Payload']//*[local-name()='CreditRequest']/@DMFunction",
                    "/*[local-name()='Payload']//*[local-name()='DMFunction']/text()"
                });
                String appFB = firstNonEmpty(xp, doc, new String[] {
                    "/Payload/*[local-name()='Application']/*[local-name()='CreditApplication']//*[local-name()='ApplicationNumber']/text()",
                    "/*[local-name()='Payload']//*[local-name()='CreditApplication']//*[local-name()='ApplicationNumber']/text()",
                    "/*[local-name()='Payload']//*[local-name()='ApplicationNumber']/text()"
                });
                if (isBlank(dmFunc)) dmFunc = trimOrNull(dmFB);
                if (isBlank(appNum)) appNum = trimOrNull(appFB);
            }
        }

        // 4) If DOM still failed or metadata still missing, do regex fallback extraction (no XML parse)
        if (!domOk || isBlank(dmFunc) || isBlank(appNum)) {
            // Extract DMFunction (element or attribute on CreditRequest)
            dmFunc = trimOrNull(regexDmFunction(repaired));
            // Extract ApplicationNumber ONLY inside CreditApplication (fallback to global if needed)
            appNum = trimOrNull(regexApplicationNumberInCreditApplication(repaired));
            if (isBlank(appNum)) appNum = trimOrNull(regexApplicationNumberAnywhere(repaired));
        }

        if (isBlank(dmFunc) || isBlank(appNum)) {
            System.err.println("Row " + rowIndex + ": missing DMFunction or ApplicationNumber; skipping.");
            return;
        }

        // 5) Write output:
        //    If DOM succeeded, we can keep the parsed-style wrapper; else, guarantee well-formed by CDATA.
        String output;
        if (domOk) {
            output = wrapped; // already well-formed <Payload>…</Payload>
        } else {
            output = wrapAsCdata(repaired); // guaranteed well-formed even if inner is broken
        }

        String safeApp = sanitize(appNum);
        String safeDM  = sanitize(dmFunc);
        File out = new File(outDir, safeApp + "_" + safeDM + ".xml");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8)) {
            w.write(output);
        }
        System.out.println("Wrote: " + out.getAbsolutePath());
    }

    // -------------------- Regex fallbacks --------------------

    private static String regexDmFunction(String s) {
        if (s == null) return null;

        // 1) <DMFunction>value</DMFunction>
        Matcher m = Pattern.compile("<\\s*DMFunction\\s*>(.*?)</\\s*DMFunction\\s*>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(s);
        if (m.find()) return m.group(1).trim();

        // 2) CreditRequest ... DMfunction="value"  (or DMFunction=)
        m = Pattern.compile("<\\s*CreditRequest\\b[^>]*\\bDM[Ff]unction\\s*=\\s*\"([^\"]+)\"",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(s);
        if (m.find()) return m.group(1).trim();

        m = Pattern.compile("<\\s*CreditRequest\\b[^>]*\\bDM[Ff]unction\\s*=\\s*'([^']+)'",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(s);
        if (m.find()) return m.group(1).trim();

        return null;
    }

    private static String regexApplicationNumberInCreditApplication(String s) {
        if (s == null) return null;

        // Find the <CreditApplication> ... </CreditApplication> region first (most tolerant)
        Matcher region = Pattern.compile("<\\s*CreditApplication\\b(?s).*?</\\s*CreditApplication\\s*>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(s);
        if (region.find()) {
            String scope = region.group(0);
            Matcher m = Pattern.compile("<\\s*ApplicationNumber\\s*>(.*?)</\\s*ApplicationNumber\\s*>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(scope);
            if (m.find()) return m.group(1).trim();
        }
        return null;
    }

    private static String regexApplicationNumberAnywhere(String s) {
        if (s == null) return null;
        Matcher m = Pattern.compile("<\\s*ApplicationNumber\\s*>(.*?)</\\s*ApplicationNumber\\s*>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(s);
        if (m.find()) return m.group(1).trim();
        return null;
    }

    // -------------------- XPath helpers ---------------------

    private static String firstNonEmpty(XPath xp, Document doc, String[] paths) throws Exception {
        for (String p : paths) {
            String v = (String) xp.evaluate(p, doc, XPathConstants.STRING);
            if (!isBlank(v)) return v;
        }
        return null;
    }

    // -------------------- Parse / Repair --------------------

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

        // Ensure tags have a closing '>' — aggressively
        t = forceAngles(t);

        // Auto self-close empty start tags that are immediately followed by another tag
        t = t.replaceAll("(<[A-Za-z_][\\w\\-.:]*[^<>]*?)>(\\s*)(?=<)", "$1/>$2");

        return t.trim();
    }

    // More aggressive than before: if a '<' opens and the next '>' is missing or appears after another '<',
    // insert a '>' right before that next '<' (or at end of string).
    private static String forceAngles(String xml) {
        StringBuilder out = new StringBuilder(xml.length() + 64);
        int i = 0;
        while (i < xml.length()) {
            int lt = xml.indexOf('<', i);
            if (lt < 0) { out.append(xml, i, xml.length()); break; }
            out.append(xml, i, lt);
            int gt = xml.indexOf('>', lt + 1);
            int nextLt = xml.indexOf('<', lt + 1);

            if (gt < 0 && nextLt < 0) {
                // No '>' and no next '<' — close at end
                out.append(xml, lt, xml.length()).append('>');
                i = xml.length();
            } else if (gt >= 0 && (nextLt < 0 || gt < nextLt)) {
                // Normal well-formed tag or '>' before next '<'
                out.append(xml, lt, gt + 1);
                i = gt + 1;
            } else {
                // There is a next '<' before any '>' — insert a '>' just before that next '<'
                out.append(xml, lt, nextLt).append('>');
                i = nextLt;
            }
        }
        return out.toString();
    }

    private static String wrapXml(String fragment) {
        String cleaned = fragment == null ? "" : fragment.trim();
        // Drop leading junk before first '<' and trailing after last '>'
        int s = cleaned.indexOf('<'); int e = cleaned.lastIndexOf('>');
        if (s >= 0 && e >= s) cleaned = cleaned.substring(s, e+1);
        // Remove any inner XML decl left
        cleaned = cleaned.replaceAll("<\\?xml[^>]*\\?>", "");
        // Fix stray ampersands once more (safety)
        cleaned = cleaned.replaceAll("&(?!amp;|lt;|gt;|quot;|apos;|#[0-9]+;|#x[0-9A-Fa-f]+;)", "&amp;");
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Payload>\n" + cleaned + "\n</Payload>";
        }

    private static String wrapAsCdata(String fragment) {
        String c = fragment == null ? "" : fragment.replace("]]>", "]]]]><![CDATA[>");
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Payload><![CDATA[\n" + c + "\n]]></Payload>";
    }

    // -------------------- Small utils --------------------

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
