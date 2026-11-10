package net.fabricmc.loader.api;

import java.nio.file.Path;

/** Compile-only API surface; never included in the produced mod. */
public interface FabricLoader {
    static FabricLoader getInstance() {
        // Also supplies an isolated config directory in standalone transport tests.
        return () -> Path.of(System.getProperty("economysim.test.config", "config"));
    }
    Path getConfigDir();
}
