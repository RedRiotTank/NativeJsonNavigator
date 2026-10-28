package testing;

import io.github.redriottank.nativejson.JsonDocument;
import io.github.redriottank.nativejson.JsonValue;

/**
 * Small manual smoke-test/demo. Run from the repository root:
 * {@code java -cp target/test-classes:target/classes testing.Main}.
 */
public class Main {

    public static void main(String[] args) {

        try (JsonDocument doc = JsonDocument.open("src/test/resources/proyecto.json")) {

            JsonValue tags = doc.find("tags");

            if (tags != null) {
                System.out.println("Found 'tags' key");

                JsonValue element = tags.getAt(2);

                if (element != null) {
                    System.out.println("Real val: " + element.asString());
                } else {
                    System.out.println("Index 2 doesn't exist.");
                }
            } else {
                System.out.println("Key 404.");
            }

        } catch (Exception e) {
            System.err.println("Error processing JSON: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
