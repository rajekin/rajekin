

package xmlUI;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class XmlFolderToJsonMain_NoArgs {

    // >>>>>>>>> EDIT THESE TWO LINES <<<<<<<<<
    private static final String INPUT_DIR  = "C:\\work\\xmls";   // folder with .xml files
    private static final String OUTPUT_DIR = "C:\\work\\out";    // where .json files go

    // Optional XSLT use (leave false to use normal XML->JSON path)
    private static final boolean USE_XSLT = false;
    private static final String  XSLT_LOCATION = "classpath:xsl/xml-to-json.xsl"; // or "C:\\path\\to\\xml-to-json.xsl"

    public static void main(String[] args) throws Exception {
        Path inDir  = Paths.get(INPUT_DIR).toAbsolutePath().normalize();
        Path outDir = Paths.get(OUTPUT_DIR).toAbsolutePath().normalize();

        if (!Files.isDirectory(inDir)) {
            System.err.println("Input directory not found: " + inDir);
            return;
        }
        Files.createDirectories(outDir);

        // Set up your service + options (matches defaults from your class)
        XmlToJsonUnifiedService svc = new XmlToJsonUnifiedService();
        XmlToJsonUnifiedService.Options opt = new XmlToJsonUnifiedService.Options();
        opt.unwrapSoapBody    = true;
        opt.parseCdataXml     = true;
        opt.lowercaseRoot     = true;
        opt.flattenAttributes = true;
        opt.coerceNumbers     = true;
        opt.useXslt           = USE_XSLT;
        opt.xsltLocation      = USE_XSLT ? XSLT_LOCATION : null;
        opt.xsltOutputIsJson  = true;

        AtomicInteger ok = new AtomicInteger();
        AtomicInteger fail = new AtomicInteger();

        // Walk the folder (recurses into subfolders; remove ".walk" and use ".list" for top-level only)
        try (var paths = Files.walk(inDir)) {
            paths.filter(Files::isRegularFile)
                 .filter(XmlFolderToJsonMain_NoArgs::isXml)
                 .forEach(xmlPath -> {
                     try {
                         String xml = readString(xmlPath);
                         XmlToJsonUnifiedService.Result res = svc.convert(xml, opt);

                         String base = xmlPath.getFileName().toString().replaceFirst("(?i)\\.xml$", "");
                         Path outPath = outDir.resolve(base + ".json");

                         writeAtomic(outPath, res.prettyJson);
                         System.out.println("✓ " + inDir.relativize(xmlPath) + " -> " + outPath.getFileName());
                         ok.incrementAndGet();
                     } catch (Exception ex) {
                         System.err.println("✗ " + xmlPath.getFileName() + " :: " + ex.getMessage());
                         fail.incrementAndGet();
                     }
                 });
        }

        System.out.println("Done. Success: " + ok.get() + ", Failed: " + fail.get());
        System.out.println("Output folder: " + outDir);
    }

    private static boolean isXml(Path p) {
        String name = p.getFileName().toString().toLowerCase();
        return name.endsWith(".xml");
    }

    private static String readString(Path p) throws IOException {
        byte[] bytes = Files.readAllBytes(p);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeAtomic(Path out, String content) throws IOException {
        Path tmp = out.resolveSibling(out.getFileName().toString() + ".tmp");
        Files.write(tmp, content.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
