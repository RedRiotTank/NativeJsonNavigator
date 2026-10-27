package io.github.redriottank.nativejson;

import java.nio.file.Path;

/**
 * Runs in a separate, heap-constrained JVM so that the large-document
 * validation test can prove that {@link JsonDocument#validate()} does not copy
 * the document onto the Java heap.
 */
public final class LargeDocumentValidationMain {

    private LargeDocumentValidationMain() {
    }

    public static void main(String[] args) throws Exception {
        try (JsonDocument document = JsonDocument.open(Path.of(args[0]))) {
            document.validate();
        }
    }
}
