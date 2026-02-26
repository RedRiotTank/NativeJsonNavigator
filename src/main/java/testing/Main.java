package testing;

// Fíjate en los imports: ya no necesitas (ni puedes) importar JsonPointer o NativeJsonNavigator

import htt.JsonValue;
import htt.internal.NativeJsonDocument;

public class Main {
    public static void main(String[] args) {

        try (NativeJsonDocument doc = new NativeJsonDocument("proyecto.json")) {

            JsonValue tags = doc.find("tags");

            if (tags != null) {
                System.out.println("Found 'tags' key");


                JsonValue elemento = tags.getAt(2);

                if (elemento != null) {
                    String valor = elemento.asString();
                    System.out.println("Real val: " + valor);
                } else {
                    System.out.println("Index 2 doesn't exists.");
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