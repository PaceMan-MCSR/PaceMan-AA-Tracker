package gg.paceman.aatracker;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Handles the save options for the tracker
 */
public class AATrackerOptions {
    public static final Path SAVE_PATH = getPaceManAADir().resolve("options.json").toAbsolutePath();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static AATrackerOptions instance;

    public String accessKey = "";
    public boolean enabledForPlugin = false;

    /**
     * Load and return the options file
     */
    public static AATrackerOptions load() throws IOException, JsonSyntaxException {
        if (Files.exists(SAVE_PATH)) {
            instance = GSON.fromJson(new String(Files.readAllBytes(SAVE_PATH)), AATrackerOptions.class);
        } else {
            instance = new AATrackerOptions();
            tryStealKey();
        }
        return instance;
    }

    private static void tryStealKey() {
        try {
            Path path = getPaceManAADir().resolveSibling("options.json");
            if (Files.exists(path)) {
                JsonObject json = GSON.fromJson(new String(Files.readAllBytes(path)), JsonObject.class);
                if (json.has("accessKey")) {
                    instance.accessKey = json.get("accessKey").getAsString();
                    AATracker.log("Access key yoinked from regular tracker options!");
                }
            }
        } catch (Exception ignored) {
        }
    }

    public static AATrackerOptions getInstance() {
        return instance;
    }

    public static void ensurePaceManAADir() {
        new File((System.getProperty("user.home") + "/.PaceMan/AA/").replace("\\", "/").replace("//", "/")).mkdirs();
    }

    public static Path getPaceManAADir() {
        return Paths.get(System.getProperty("user.home")).resolve(".PaceMan").resolve("AA").toAbsolutePath();
    }

    public void save() throws IOException {
        AATrackerOptions.ensurePaceManAADir();
        FileWriter writer = new FileWriter(SAVE_PATH.toFile());
        GSON.toJson(this, writer);
        writer.close();
    }
}
