package dev.chrc.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ConfigManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("CHRC/Config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final Path DIRECTORY = FabricLoader.getInstance().getConfigDir().resolve("chrc");
    private static final Path FILE = DIRECTORY.resolve("config.json");
    private static ChrcConfig config = new ChrcConfig();

    private ConfigManager() {}

    public static ChrcConfig get() {
        return config;
    }

    public static void load() {
        try {
            Files.createDirectories(DIRECTORY);
            if (Files.exists(FILE)) {
                try (Reader reader = Files.newBufferedReader(FILE)) {
                    ChrcConfig loaded = GSON.fromJson(reader, ChrcConfig.class);
                    if (loaded != null) config = loaded;
                }
            }
            config.normalize();
            save();
        } catch (Exception e) {
            LOGGER.error("Failed to load CHRC config", e);
            config = new ChrcConfig();
        }
    }

    public static void save() {
        try {
            Files.createDirectories(DIRECTORY);
            config.normalize();
            try (Writer writer = Files.newBufferedWriter(FILE)) {
                GSON.toJson(config, writer);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to save CHRC config", e);
        }
    }

    public static void reset() {
        config = new ChrcConfig();
        save();
    }
}
