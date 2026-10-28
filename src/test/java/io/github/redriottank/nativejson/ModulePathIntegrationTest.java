package io.github.redriottank.nativejson;

import org.junit.jupiter.api.Test;

import java.lang.module.Configuration;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@code module-info.class} produces a valid JPMS module descriptor,
 * exports the expected package, and can be resolved and invoked dynamically
 * through a custom {@link ModuleLayer} on the module path.
 */
class ModulePathIntegrationTest {

    private static final String MODULE_NAME = "io.github.redriottank.nativejson";
    private static final String EXPORTED_PACKAGE = "io.github.redriottank.nativejson";

    @Test
    void moduleDescriptorIsValidAndResolvable() throws Exception {
        Path classesDir = Path.of("target/classes");
        Path moduleInfo = classesDir.resolve("module-info.class");

        if (!Files.exists(moduleInfo)) {
            // If run prior to compilation in an IDE, locate target/classes
            return;
        }

        ModuleFinder finder = ModuleFinder.of(classesDir);
        ModuleReference reference = finder.find(MODULE_NAME)
                .orElseGet(() -> fail("Module " + MODULE_NAME + " not found in " + classesDir));

        ModuleDescriptor descriptor = reference.descriptor();
        assertEquals(MODULE_NAME, descriptor.name(), "Module name must match expected coordinate");
        assertFalse(descriptor.isOpen(), "Module should be strongly encapsulated (not open)");

        // Verify exported packages
        boolean exportsPackage = descriptor.exports().stream()
                .anyMatch(e -> e.source().equals(EXPORTED_PACKAGE) && !e.isQualified());
        assertTrue(exportsPackage, "Module must unconditionally export " + EXPORTED_PACKAGE);

        // Resolve module in a child JPMS Configuration against the Boot layer
        Configuration bootConfig = ModuleLayer.boot().configuration();
        Configuration childConfig = bootConfig.resolve(finder, ModuleFinder.of(), Set.of(MODULE_NAME));
        assertTrue(childConfig.findModule(MODULE_NAME).isPresent(), "Module must resolve cleanly in configuration");

        // Define a ModuleLayer from the resolved configuration
        ClassLoader parentLoader = ClassLoader.getSystemClassLoader();
        ModuleLayer layer = ModuleLayer.boot().defineModulesWithOneLoader(childConfig, parentLoader);

        ClassLoader layerLoader = layer.findLoader(MODULE_NAME);
        assertNotNull(layerLoader, "Layer class loader must be present");

        // Verify that JsonDocument is loadable from the module layer
        Class<?> docClass = layerLoader.loadClass("io.github.redriottank.nativejson.JsonDocument");
        assertSame(layer.findModule(MODULE_NAME).orElseThrow(), docClass.getModule());

        // Verify that public API methods exist on the loaded module class
        Method openMethod = docClass.getMethod("open", String.class);
        assertNotNull(openMethod);

        Class<?> valClass = layerLoader.loadClass("io.github.redriottank.nativejson.JsonValue");
        assertSame(layer.findModule(MODULE_NAME).orElseThrow(), valClass.getModule());
    }
}
