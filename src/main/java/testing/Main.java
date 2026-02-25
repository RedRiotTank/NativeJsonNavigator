package testing;

import htt.JsonPointer;
import htt.NativeJsonNavigator;

public class Main {
    public static void main(String[] args) {
        try (NativeJsonNavigator navigator = new NativeJsonNavigator("proyecto.json")) {

            long ptr = navigator.findValue("tags");

            if (ptr != -1) {
                System.out.println("Ptr found: " + ptr);
                System.out.println("Offset: " + JsonPointer.unpackOffset(ptr));
                System.out.println("Length: " + JsonPointer.unpackLength(ptr));

                long a = navigator.getElementAt(ptr, 2);
                String valor = navigator.extract(a);
                System.out.println("Real val: " + valor);
            } else {
                System.out.println("Key 404.");
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}