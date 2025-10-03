package com.example.xmlui;

import org.w3c.dom.*;
import javax.xml.parsers.*;
import javax.xml.xpath.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public class ExtractRequests {
  public static void main(String[] args) throws Exception {
    if (args.length < 2) {
      System.err.println("Usage: java ExtractRequests <input-xml> <output-dir>");
      System.exit(1);
    }
    Path input = Paths.get(args[0]);
    Path outDir = Paths.get(args[1]);
    Files.createDirectories(outDir);

    DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
    dbf.setNamespaceAware(true);
    DocumentBuilder db = dbf.newDocumentBuilder();
    Document outer = db.parse(input.toFile());

    XPath xpath = XPathFactory.newInstance().newXPath();

    // All rows where TYPE = Request
    NodeList matchingRows = (NodeList) xpath.evaluate(
        "//row[Column[Name='TYPE' and normalize-space(following-sibling::value)='Request']]",
        outer, XPathConstants.NODESET);

    int written = 0;
    for (int i = 0; i < matchingRows.getLength(); i++) {
      Element row = (Element) matchingRows.item(i);

      // Grab DATA column content
      String dataEscaped = (String) xpath.evaluate(
          "Column[Name='DATA']/value/text()", row, XPathConstants.STRING);
      if (dataEscaped == null || dataEscaped.isEmpty()) continue;

      // Unescape inner XML
      String innerXml = unescapeXml(dataEscaped);

      Document innerDoc = db.parse(
          new ByteArrayInputStream(innerXml.getBytes(StandardCharsets.UTF_8)));

      // Extract DMfunction
      String dmFunc = (String) xpath.evaluate(
          "//*[local-name()='CreditRequest']/@DMfunction", innerDoc, XPathConstants.STRING);

      // Extract ApplicationNumber
      String appNum = (String) xpath.evaluate(
          "//*[local-name()='ApplicationNumber']/text()", innerDoc, XPathConstants.STRING);

      if (dmFunc == null || dmFunc.isEmpty() || appNum == null || appNum.isEmpty()) {
        System.err.println("Row " + (i+1) + ": missing DMfunction or ApplicationNumber; skipping.");
        continue;
      }

      // Build filename: ApplicationNumber_DMfunction.xml
      String fileName = sanitize(appNum) + "_" + sanitize(dmFunc) + ".xml";
      Path outFile = outDir.resolve(fileName);

      // Write the inner XML
      writePretty(innerDoc, outFile);
      System.out.println("Wrote: " + outFile.getFileName());
      written++;
    }

    System.out.println("Done. Files written: " + written);
  }

  private static String unescapeXml(String s) {
    return s.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&apos;", "'")
            .replace("&quot;", "\"")
            .replace("&amp;", "&");
  }

  private static String sanitize(String s) {
    return s.replaceAll("[^A-Za-z0-9-_]", "_");
  }

  private static void writePretty(Document doc, Path path) throws Exception {
    javax.xml.transform.Transformer t =
        javax.xml.transform.TransformerFactory.newInstance().newTransformer();
    t.setOutputProperty(javax.xml.transform.OutputKeys.ENCODING, "UTF-8");
    t.setOutputProperty(javax.xml.transform.OutputKeys.INDENT, "yes");
    t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
    try (OutputStream os = Files.newOutputStream(path)) {
      t.transform(new javax.xml.transform.dom.DOMSource(doc),
                  new javax.xml.transform.stream.StreamResult(os));
    }
  }
}
