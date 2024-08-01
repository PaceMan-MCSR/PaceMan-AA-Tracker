package gg.paceman.aatracker;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import gg.paceman.aatracker.util.ExceptionUtil;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The actual logic and stuff for the PaceMan AA Tracker
 */
public class AATracker {
    public static String VERSION = "Unknown"; // To be set dependent on launch method
    private static final AATracker INSTANCE = new AATracker();

    public static Consumer<String> logConsumer = System.out::println;
    public static Consumer<String> debugConsumer = System.out::println;
    public static Consumer<String> errorConsumer = System.out::println;
    public static Consumer<String> warningConsumer = System.out::println;


    public static final String PACEMANGG_AA_ENDPOINT = "https://paceman.gg/api/sendaa";
    private static final String PACEMANGG_TEST_ENDPOINT = "https://paceman.gg/api/test";
    private static final int MIN_DENY_CODE = 400;

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private boolean asPlugin;

    public static AATracker getInstance() {
        return INSTANCE;
    }

    public static void log(String message) {
        logConsumer.accept(message);
    }

    public static void logDebug(String message) {
        debugConsumer.accept(message);
    }

    public static void logError(String error) {
        errorConsumer.accept(error);
    }

    public static void logWarning(String error) {
        warningConsumer.accept(error);
    }

    private static boolean areAtumSettingsGood(Path worldPath) {
        // .minecraft/saves/x -> .minecraft/saves -> .minecraft/config
        Path configPath = worldPath.getParent().resolveSibling("config");
        // .minecraft/config -> .minecraft/config/atum -> .minecraft/config/atum/atum.properties
        Path oldAtumPropPath = configPath.resolve("atum").resolve("atum.properties");
        // .minecraft/config -> .minecraft/config/mcsr -> .minecraft/config/mcsr/atum.json
        Path newAtumJsonPath = configPath.resolve("mcsr").resolve("atum.json");

        boolean oldExists = Files.exists(oldAtumPropPath);
        boolean newExists = Files.exists(newAtumJsonPath);
        if (!(oldExists || newExists)) {
            AATracker.logWarning("You must use the Atum mod " + oldAtumPropPath);
            return false; // no settings exist
        }

        if (oldExists) {
            try {
                if (!AATracker.areOldAtumSettingsGood(oldAtumPropPath)) {
                    AATracker.logWarning("Illegal Atum settings found in " + oldAtumPropPath);
                    AATracker.logWarning("Make sure your Atum settings are set to defaults with no set seed and above peaceful difficulty.");
                    AATracker.logWarning("If you are using the newer Atum with more world generation options, you should delete the old config file.");
                    return false; // old settings exist and are bad
                }
            } catch (Exception e) {
                AATracker.logWarning("Invalid/Corrupted Atum settings found in " + oldAtumPropPath);
                AATracker.logWarning("If you are using the newer Atum with more world generation options, you should delete the old config file.");
                return false;
            }
        }

        if (newExists) {
            try {
                if (!AATracker.areNewAtumSettingsGood(newAtumJsonPath)) {
                    AATracker.logWarning("Illegal Atum settings found in " + newAtumJsonPath);
                    AATracker.logWarning("Make sure your Atum settings are set to defaults with no set seed and above peaceful difficulty.");
                    AATracker.logWarning("If you are using the older Atum with less world generation options, you should delete the new config file.");
                    return false; // new settings exist and are bad
                }
            } catch (Exception e) {
                AATracker.logWarning("Invalid/Corrupted Atum settings found in " + newAtumJsonPath);
                AATracker.logWarning("If you are using the older Atum with less world generation options, you should delete the new config file.");
                return false; // new settings exist and are bad
            }
        }

        return true; // settings exists, no settings are bad
    }

    private static boolean areOldAtumSettingsGood(Path atumPropPath) throws IOException {
        String atumPropText = new String(Files.readAllBytes(atumPropPath));
        for (String line : atumPropText.split("\n")) {
            String[] args = line.trim().split("=");
            if (args.length < 2) {
                continue;
            }
            if (args[0].trim().equals("generatorType") && !args[1].trim().equals("0")) {
                return false;
            }
            if (args[0].trim().equals("bonusChest") && args[1].trim().equals("true")) {
                return false;
            }
        }
        return true;
    }


    private static boolean areNewAtumSettingsGood(Path atumJsonPath) throws IOException, JsonSyntaxException {
        String atumJsonText = new String(Files.readAllBytes(atumJsonPath));
        JsonObject json = new Gson().fromJson(atumJsonText, JsonObject.class);
        return json.has("hasLegalSettings")
                && json.get("hasLegalSettings").getAsBoolean()
                && json.has("seed")
                && json.get("seed").getAsString().isEmpty()
                && json.has("difficulty")
                && !json.get("difficulty").getAsString().equalsIgnoreCase("peaceful");
    }

    private static String sha256Hash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    public static PostResponse sendData(String endpointUrl, String jsonData) throws IOException {
        // Create URL object
        URL url = new URL(endpointUrl);
        HttpURLConnection connection = null;
        try {
            // Open connection
            connection = (HttpURLConnection) url.openConnection();

            // Set the necessary properties
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);

            // Write JSON data to the connection output stream
            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = jsonData.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }
            int responseCode = connection.getResponseCode();
            String message = responseCode >= 400 ? AATracker.readStream(connection.getErrorStream()) : connection.getResponseMessage();


            // Return the response code
            return new PostResponse(responseCode, message);
        } finally {
            // Close the connection
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    public static PostResponse testAccessKey(String accessKey) {
        JsonObject testModelInput = new JsonObject();
        testModelInput.addProperty("accessKey", accessKey);
        try {
            return AATracker.sendData(PACEMANGG_TEST_ENDPOINT, testModelInput.toString());
        } catch (IOException e) {
            return null;
        }
    }

    private static String readStream(InputStream inputStream) {
        return new BufferedReader(new InputStreamReader(inputStream)).lines().collect(Collectors.joining("\n"));
    }

    private boolean shouldRun() {
        AATrackerOptions options = AATrackerOptions.getInstance();
        if (options.accessKey.isEmpty()) {
            return false;
        }
        return !this.asPlugin || options.enabledForPlugin;
    }

    public void start(boolean asPlugin) {
        this.asPlugin = asPlugin;
        // Run tick every 1 second
        this.executor.scheduleAtFixedRate(this::tryTick, 0, 1, TimeUnit.SECONDS);
    }

    private void tryTick() {
        try {
            Thread.currentThread().setName("paceman-aa-tracker");
            this.tick();
        } catch (Throwable t) {
            if (!this.asPlugin) {
                ExceptionUtil.showExceptionAndExit(t, "PaceMan AA Tracker has crashed! Please report this bug to the developers.\n" + t);
            } else {
                String detailedString = ExceptionUtil.toDetailedString(t);
                AATracker.logError("PaceMan AA Tracker has crashed! Please report this bug to the developers. " + detailedString);
                AATracker.logError("PaceMan AA Tracker will now shutdown, Julti will need to be restarted to use PaceMan AA Tracker.");
                this.stop();
            }
        }
    }

    private void tick() {
    }

    public void stop() {
        try {
            // Wait for and shutdown executor
            this.executor.shutdownNow();
            this.executor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // Do cleanup
    }

    public static class PostResponse {
        private final int code;
        private final String message;

        private PostResponse(int code, String message) {
            this.code = code;
            this.message = message;
        }

        public int getCode() {
            return this.code;
        }

        public String getMessage() {
            return this.message;
        }
    }
}
