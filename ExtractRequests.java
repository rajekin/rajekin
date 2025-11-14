<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             http://maven.apache.org/xsd/maven-4.0.0.xsd">

    <modelVersion>4.0.0</modelVersion>

    <groupId>com.example</groupId>
    <artifactId>jar-decompiler-json-tool</artifactId>
    <version>1.0.0-SNAPSHOT</version>

    <properties>
        <!-- Set your Java version here -->
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <dependencies>
        <!-- CFR Java decompiler -->
        <dependency>
            <groupId>org.benf</groupId>
            <artifactId>cfr</artifactId>
            <version>0.152</version>
        </dependency>

        <!-- Jackson for JSON <-> POJO -->
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
            <version>2.17.0</version>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <!-- Compiler plugin -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.11.0</version>
                <configuration>
                    <source>${maven.compiler.source}</source>
                    <target>${maven.compiler.target}</target>
                </configuration>
            </plugin>

            <!-- Make this a runnable jar if you want -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-jar-plugin</artifactId>
                <version>3.3.0</version>
                <configuration>
                    <archive>
                        <manifest>
                            <mainClass>JarDecompiler</mainClass>
                        </manifest>
                    </archive>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>


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
 * - Decompiles ALL classes in the given JAR into ./decompiled-src
 * - Lists all class names
 * - Loads a class (either specified or the first one) from the JAR
 * - Deserializes JSON from the given file into that class
 * - Serializes the object back to JSON and prints it
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
            fqcn = args[2];
            if (!classNames.contains(fqcn)) {
                System.out.println("Warning: specified class not found in JAR: " + fqcn);
                System.out.println("         It may still load if it's an inner class or from another dependency.");
            }
        } else {
            // default: first class found
            fqcn = classNames.get(0);
            System.out.println("No FQCN provided, using first class from JAR: " + fqcn);
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

        // CFR takes a list of input "files" (jar/class)
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




    
