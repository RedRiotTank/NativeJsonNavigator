package testing;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;

public class JsonDataGenerator {

    public static void main(String[] args) {
        generateLargeJson("large_dataset.json", 1_000_000);
    }

    public static void generateLargeJson(String fileName, int numRecords) {
        Path path = Path.of(fileName);
        try (BufferedWriter writer = Files.newBufferedWriter(path)) {
            writer.write("{\n");
            writer.write("  \"metadata\": {\"created\": \"2026-02-26\", \"type\": \"benchmark\"},\n");
            writer.write("  \"users\": [\n");

            for (int i = 0; i < numRecords; i++) {
                writer.write("    {\n");
                writer.write("      \"id\": " + i + ",\n");
                writer.write("      \"name\": \"User_" + i + "\",\n");
                writer.write("      \"active\": " + (i % 2 == 0) + ",\n");
                writer.write("      \"balance\": " + (Math.random() * 1000) + "\n");
                writer.write("    }");
                if (i < numRecords - 1) {
                    writer.write(",");
                }
                writer.write("\n");
            }

            writer.write("  ],\n");
            writer.write("  \"target_data\": \"Found it!\"\n");
            writer.write("}");

            System.out.println("File " + fileName + " generated successfully. Approx size: " + Files.size(path) / (1024 * 1024) + " MB");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}