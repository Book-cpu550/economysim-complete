package com.example.economyadvisor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import net.fabricmc.loader.api.FabricLoader;

/** Shared NIM settings. Keeps the released mod's command/config interface. */
public final class AdvisorConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir()
            .resolve("economysim-advisor.json");
    private static volatile AdvisorConfig instance;
    public volatile String apiKey = "";
    public volatile String model = "";
    public int maxTokens = 900;
    public int historyTurns = 4;
    public int timeoutSeconds = 120;
    /** Disable only for models without NVIDIA JSON-object output support. */
    public boolean jsonMode = true;

    public static AdvisorConfig get() {
        AdvisorConfig current = instance;
        if (current == null) {
            synchronized (AdvisorConfig.class) {
                if (instance == null) instance = load();
                current = instance;
            }
        }
        return current;
    }

    private static AdvisorConfig load() {
        AdvisorConfig config = new AdvisorConfig();
        try {
            if (Files.exists(FILE)) {
                AdvisorConfig read = GSON.fromJson(Files.readString(FILE), AdvisorConfig.class);
                if (read != null) config = read;
            }
        } catch (Exception e) {
            System.getLogger("economysim-nim").log(System.Logger.Level.WARNING,
                    "Could not read advisor config; using defaults");
        }
        if (config.model == null || config.model.startsWith("gemma")
                || config.model.startsWith("claude-")) config.model = "";
        if (config.apiKey == null || config.apiKey.startsWith("sk-ant-")
                || config.apiKey.equals("ollama-local-placeholder")) config.apiKey = "";
        return config;
    }

    public synchronized void save() {
        try {
            Files.createDirectories(FILE.getParent());
            // Restrict the file before writing a credential, where POSIX is supported.
            if (!Files.exists(FILE)) {
                try {
                    Files.createFile(FILE, PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rw-------")));
                } catch (UnsupportedOperationException e) {
                    Files.createFile(FILE);
                }
            }
            try {
                Files.setPosixFilePermissions(FILE, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) { }
            Files.writeString(FILE, GSON.toJson(this));
        } catch (Exception e) {
            System.getLogger("economysim-nim").log(System.Logger.Level.WARNING,
                    "Could not save advisor config");
        }
    }

    public void setApiKey(String key) {
        apiKey = key == null ? "" : key.trim();
        save();
    }

    public String keySource() {
        if (apiKey != null && !apiKey.isBlank()) return "config file";
        String env = System.getenv("NVIDIA_API_KEY");
        return env != null && !env.isBlank() ? "NVIDIA_API_KEY environment variable" : "none";
    }

    public String resolveKey() {
        if (apiKey != null && !apiKey.isBlank()) return apiKey.trim();
        String env = System.getenv("NVIDIA_API_KEY");
        return env == null ? "" : env.trim();
    }
}
