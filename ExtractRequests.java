package xmlUI;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public final class Utils {

    // Edit these two paths
    private static final String INPUT_DIR  = "C:\\work\\xmls";
    private static final String OUTPUT_DIR = "C:\\work\\out";

    public static void runFiles(XmlToJsonUnifiedService converter,
                                XmlToJsonUnifiedService.Options opt) {
        Path inDir  = Paths.get(INPUT_DIR).toAbsolutePath().normalize();
        Path outDir = Paths.get(OUTPUT_DIR).toAbsolutePath().normalize();

        try {
            Files.createDirectories(outDir);
            try (var walk = Files.walk(inDir)) {
                walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".xml"))
                    .forEach(xmlPath -> {
                        try {
                            String xml = new String(Files.readAllBytes(xmlPath), StandardCharsets.UTF_8);

                            // <-- This uses XSLT as long as opt.useXslt==true and xsltLocation is set
                            XmlToJsonUnifiedService.Result res = converter.convert(xml, opt);

                            String base = xmlPath.getFileName().toString().replaceFirst("(?i)\\.xml$", "");
                            Path out = outDir.resolve(base + ".json");

                            writeAtomic(out, res.prettyJson);
                            System.out.println("Saved (viaXSLT=" + opt.useXslt + "): " + out);
                        } catch (Exception e) {
                            System.err.println("Failed " + xmlPath + " :: " + e.getMessage());
                        }
                    });
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void writeAtomic(Path out, String text) throws IOException {
        Path tmp = out.resolveSibling(out.getFileName().toString() + ".tmp");
        Files.write(tmp, text.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
