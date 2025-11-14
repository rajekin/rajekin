import com.fasterxml.jackson.databind.ObjectMapper;
import org.benf.cfr.reader.api.CfrDriver;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Usage:
 *
 *   mvn package
 *   java -cp target/jar-decompiler-json-tool-1.0.0-SNAPSHOT.jar \
 *        JarDecompiler path/to/your.jar path/to/input.json [fully.qualified.ClassName]
 *
 * If [fully.qualified.ClassName] is NOT provided, the tool will:
 *   - Try to find a class whose simple name is "Application"
 *   - If found, use that
 *   - Otherwise, fall back to the first class in the JAR
 */
public class JarDecompiler {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage: java JarDecompiler <jar-path> <json-path> [fully.qualified.ClassName]");
            System.out.println("  <jar-path>  : path to the JAR file whose classes will be decompiled");
            System.out.println("  <json-path> : path to the JSON file to deserialize");
            System.out.println("  [fqcn]      : optional fully qualified class name inside the JAR");
            return;
        }

        Path jarPath = Paths.get(args[0]);
        Path jsonPath = Paths.get(args[1]);

        if (!Files.exists(jarPath)) {
            System.err.println("JAR not found: " + jarPath.toAbsolutePath());
            return;
        }
        if (!Files.exists(jsonPath)) {
            System.err.println("JSON file not found: " + jsonPath.toAbsolutePath());
            return;
        }

        // 1) Output directory for decompiled sources
        Path outputDir = Paths.get("decompiled-src");
        Files.createDirectories(outputDir);

        // 2) Decompile ALL classes from the JAR
        System.out.println("Decompiling JAR: " + jarPath.toAbsolutePath());
        decompileJarWithCfr(jarPath, outputDir);
        System.out.println("Decompiled sources written under: " + outputDir.toAbsolutePath());
        System.out.println();

        // 3) List all class names inside the JAR
        List<String> classNames = listClassesInJar(jarPath.toFile());
        System.out.println("Classes found in JAR (" + classNames.size() + "):");
        for (String cn : classNames) {
            System.out.println("  " + cn);
        }
        System.out.println();

        if (classNames.isEmpty()) {
            System.err.println("No .class files found in the JAR.");
            return;
        }

        // 4) Decide which class to use for JSON binding
        String fqcn;
        if (args.length >= 3) {
            // If explicitly provided, just use that
            fqcn = args[2];
            if (!classNames.contains(fqcn)) {
                System.out.println("Warning: specified class not found in JAR: " + fqcn);
                System.out.println("         It may still load if it's an inner class or from another dependency.");
            }
        } else {
            // Try to find a class whose simple name is "Application"
            String targetSimpleName = "Application";
            Optional<String> applicationClass = classNames.stream()
                    .filter(cn -> cn.equals(targetSimpleName) || cn.endsWith("." + targetSimpleName))
                    .findFirst();

            if (applicationClass.isPresent()) {
                fqcn = applicationClass.get();
                System.out.println("No FQCN provided. Found Application class: " + fqcn);
            } else {
                // Fallback: first class in the JAR
                fqcn = classNames.get(0);
                System.out.println("No FQCN provided and no Application class found.");
                System.out.println("Using first class from JAR: " + fqcn);
            }
        }

        // 5) Load the class from the JAR
        Class<?> clazz = loadClassFromJar(jarPath, fqcn);
        System.out.println("Loaded class: " + clazz.getName());
        System.out.println();

        // 6) Read JSON from file
        String json = Files.readString(jsonPath, StandardCharsets.UTF_8);
        System.out.println("JSON read from file:");
        System.out.println(json);
        System.out.println();

        // 7) Deserialize JSON into that class
        Object obj = deserializeJsonToClass(json, clazz);
        System.out.println("Deserialized instance type: " + obj.getClass().getName());

        // 8) Serialize the object back to JSON
        String backToJson = serializeClassToJson(obj);
        System.out.println("Serialized back to JSON:");
        System.out.println(backToJson);
    }

    /**
     * Use CFR to decompile the entire JAR into the given output directory.
     */
    public static void decompileJarWithCfr(Path jarPath, Path outputDir) {
        Map<String, String> options = new HashMap<>();
        // CFR will create package folders and .java files under this folder
        options.put("outputdir", outputDir.toAbsolutePath().toString());

        CfrDriver driver = new CfrDriver.Builder()
                .withOptions(options)
                .build();

        driver.analyse(Collections.singletonList(jarPath.toAbsolutePath().toString()));
    }

    /**
     * Extract fully qualified class names from a JAR file.
     */
    public static List<String> listClassesInJar(File jarFile) throws IOException {
        List<String> classNames = new ArrayList<>();

        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();

                String name = entry.getName();
                if (name.endsWith(".class") && !entry.isDirectory()) {
                    // Convert "com/example/MyClass.class" -> "com.example.MyClass"
                    String className = name
                            .replace('/', '.')
                            .substring(0, name.length() - ".class".length());
                    classNames.add(className);
                }
            }
        }
        return classNames;
    }

    /**
     * Load a class from the given JAR using a URLClassLoader.
     */
    public static Class<?> loadClassFromJar(Path jarPath, String fqcn) throws Exception {
        URL jarUrl = jarPath.toUri().toURL();
        URL[] urls = { jarUrl };

        ClassLoader parent = Thread.currentThread().getContextClassLoader();
        URLClassLoader urlClassLoader = new URLClassLoader(urls, parent);

        return Class.forName(fqcn, true, urlClassLoader);
    }

    /**
     * Deserialize JSON into the given class type.
     */
    public static Object deserializeJsonToClass(String json, Class<?> clazz) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(json, clazz);
    }

    /**
     * Serialize an object back to JSON.
     */
    public static String serializeClassToJson(Object obj) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.writeValueAsString(obj);
    }
}
