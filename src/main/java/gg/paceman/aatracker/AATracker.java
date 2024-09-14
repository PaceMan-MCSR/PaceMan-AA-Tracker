package gg.paceman.aatracker;

import com.google.gson.*;
import gg.paceman.aatracker.util.ExceptionUtil;
import gg.paceman.aatracker.util.PostUtil;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The actual logic and stuff for the PaceMan AA Tracker
 */
public class AATracker {
    public static final String PACEMANGG_AA_SEND_ENDPOINT = "https://paceman.gg/api/aa/sendevent";
    public static final String PACEMANGG_AA_KILL_ENDPOINT = "https://paceman.gg/api/aa/kill";
    private static final String PACEMANGG_TEST_ENDPOINT = "https://paceman.gg/api/test";
    private static final String EGA_ADVANCEMENT = "minecraft:recipes/misc/mojang_banner_pattern";
    public static final Pattern RANDOM_WORLD_PATTERN = Pattern.compile("^Random Speedrun #\\d+( \\(\\d+\\))?$");
    private static final Path GLOBAL_LATEST_WORLD_PATH = Paths.get(System.getProperty("user.home")).resolve("speedrunigt").resolve("latest_world.json").toAbsolutePath();
    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor();
    private static final Gson GSON = new Gson();

    private static String lastDebugLog = "";
    private static int debugLogRepeats = 0;

    private static final boolean ACTUALLY_SEND = true;

    public static String VERSION = "Unknown"; // To be set dependent on launch method
    public static Consumer<String> logConsumer = System.out::println;
    public static Consumer<String> debugConsumer = System.out::println;
    public static Consumer<String> errorConsumer = System.out::println;
    public static Consumer<String> warningConsumer = System.out::println;
    private static boolean asPlugin;


    // Stuff that changes over the course of tick()
    private static long lastLatestWorldMTime = 0;
    private static @Nullable JsonObject latestWorld = null;
    private static long lastRecordMTime = 0;
    private static long lastEventsMTime = 0;
    private static List<String> events = Collections.emptyList();
    private static String lastSend = "";

    private static boolean runOnPaceMan = false;
    private static boolean runKilledOrEnded = false;

    private AATracker() {
    }

    public static void log(String message) {
        logConsumer.accept(message);
    }

    public static void logDebug(String message) {
        if (message.equals(lastDebugLog)) {
            debugLogRepeats++;
            if (((debugLogRepeats - 1) & debugLogRepeats) != 0) return;
        } else {
            debugLogRepeats = 1;
        }
        debugConsumer.accept(message + (debugLogRepeats > 1 ? " (x" + debugLogRepeats + ")" : ""));
        lastDebugLog = message;
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
                && AATracker.jsonHasGoodDifficulty(json);
    }

    private static boolean jsonHasGoodDifficulty(JsonObject json) {
        if (json.has("difficulty")) {
            return !json.get("difficulty").getAsString().equalsIgnoreCase("peaceful");
        } else if (json.has("worldDifficulty")) {
            return !json.get("worldDifficulty").getAsString().equalsIgnoreCase("peaceful");
        }
        return false;
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

    public static PostUtil.PostResponse testAccessKey(String accessKey) {
        JsonObject testModelInput = new JsonObject();
        testModelInput.addProperty("accessKey", accessKey);
        try {
            return PostUtil.sendData(PACEMANGG_TEST_ENDPOINT, testModelInput.toString());
        } catch (IOException e) {
            return null;
        }
    }

    public static void start(boolean asPlugin) {
        AATracker.asPlugin = asPlugin;
        // Run tick every 1 second
        EXECUTOR.scheduleAtFixedRate(AATracker::tryTick, 0, 5, TimeUnit.SECONDS);
    }

    private static void tryTick() {
        try {
            Thread.currentThread().setName("paceman-aa-tracker");
            AATracker.tick();
        } catch (Throwable t) {
            if (!AATracker.asPlugin) {
                ExceptionUtil.showExceptionAndExit(t, "PaceMan AA Tracker has crashed! Please report this bug to the developers.\n" + t);
            } else {
                String detailedString = ExceptionUtil.toDetailedString(t);
                AATracker.logError("PaceMan AA Tracker has crashed! Please report this bug to the developers. " + detailedString);
                AATracker.logError("PaceMan AA Tracker will now shutdown, Julti will need to be restarted to use PaceMan AA Tracker.");
                AATracker.stop();
            }
        }
    }

    public static void stop() {
        try {
            // Wait for and shutdown executor
            AATracker.EXECUTOR.shutdownNow();
            AATracker.EXECUTOR.awaitTermination(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // Do cleanup
    }

    private static void tick() throws IOException {
        if (!shouldRun()) return;

        checkLatestWorld();

        if (latestWorld == null) { // only present if a Random Speedrun, AA category, valid atum settings, latest_world.json exists
            endRun("Latest World was null.", true);
            return;
        }

        if (runKilledOrEnded) return;

        Path speedrunigtPath = getWorldPath().get().resolve("speedrunigt");
        Path recordPath = speedrunigtPath.resolve("record.json");
        Path eventsPath = speedrunigtPath.resolve("events.log");

        if (!Files.exists(recordPath) || !Files.exists(eventsPath)) return;

        long newRecordMTime = Files.getLastModifiedTime(recordPath).toMillis();
        long newEventsMTime = Files.getLastModifiedTime(eventsPath).toMillis();

        boolean recordFileModified = newRecordMTime != lastRecordMTime;
        boolean eventsFileModified = newEventsMTime != lastEventsMTime;

        if (!recordFileModified && !eventsFileModified) return;

        lastEventsMTime = newEventsMTime;
        lastRecordMTime = newRecordMTime;

        if (eventsFileModified) {
            updateEvents();
        }

        if (events.isEmpty()) {
            logDebug("Cancelling because no events yet...");
            return;
        }
        if (hasEvilEvents()) {
            logDebug("Ending run because cheaty events are detected!");
            endRun("Run has cheaty events (such as open to lan)", false);
            return;
        }
        if (!hasNetherEnter()) {
            logDebug("Not sending yet because the nether has not been entered...");
            return;
        }

        JsonObject record;
        try {
            record = GSON.fromJson(new String(Files.readAllBytes(recordPath)), JsonObject.class);
        } catch (Throwable t) {
            logError("Error reading record file: " + ExceptionUtil.toDetailedString(t));
            return;
        }

        if (!record.has("category") || !record.get("category").getAsString().equals("ALL_ADVANCEMENTS")) {
            log("Run category is not yet ALL_ADVANCEMENTS, won't be sending this run until the category is set or auto switch occurs.");
            return;
        }

        if (!(record.has("timelines") && record.get("timelines").isJsonArray() && record.has("advancements") && record.get("advancements").isJsonObject())) {
            log("record.json is missing stuff, can't send this run yet (you should never see this message lol).");
            return;
        }

        JsonArray completed = new JsonArray();

        JsonObject advancements = record.getAsJsonObject("advancements");
        for (String advancementName : advancements.keySet().stream().sorted().collect(Collectors.toList())) {
            JsonObject advancement = advancements.getAsJsonObject(advancementName);
            if (advancement.has("complete") && advancement.get("complete").getAsBoolean() && advancement.has("is_advancement") && advancement.get("is_advancement").getAsBoolean()) {
                String simpleAdvancementName = advancementName.startsWith("minecraft:") ? advancementName.substring(10) : advancementName;
                completed.add(String.format("%s %d %d", simpleAdvancementName, advancement.get("rta").getAsLong(), advancement.get("igt").getAsLong()));
            }
        }

        JsonObject criterias = new JsonObject();
        JsonArray biomes = new JsonArray();
        JsonArray monstersKilled = new JsonArray();
        JsonArray animalsBred = new JsonArray();
        JsonArray catsTamed = new JsonArray();
        JsonArray foodEaten = new JsonArray();
        if (advancements.has("minecraft:adventure/adventuring_time")) {
            advancements.getAsJsonObject("minecraft:adventure/adventuring_time").getAsJsonObject("criteria").keySet().stream().sorted().forEach(s -> biomes.add(s.startsWith("minecraft:") ? s.substring(10) : s));
        }
        if (advancements.has("minecraft:adventure/kill_all_mobs")) {
            advancements.getAsJsonObject("minecraft:adventure/kill_all_mobs").getAsJsonObject("criteria").keySet().stream().sorted().forEach(s -> monstersKilled.add(s.startsWith("minecraft:") ? s.substring(10) : s));
        }
        if (advancements.has("minecraft:husbandry/bred_all_animals")) {
            advancements.getAsJsonObject("minecraft:husbandry/bred_all_animals").getAsJsonObject("criteria").keySet().stream().sorted().forEach(s -> animalsBred.add(s.startsWith("minecraft:") ? s.substring(10) : s));
        }
        if (advancements.has("minecraft:husbandry/complete_catalogue")) {
            advancements.getAsJsonObject("minecraft:husbandry/complete_catalogue").getAsJsonObject("criteria").keySet().stream().sorted().forEach(s -> catsTamed.add(cleanseCatName(s)));
        }
        if (advancements.has("minecraft:husbandry/balanced_diet")) {
            advancements.getAsJsonObject("minecraft:husbandry/balanced_diet").getAsJsonObject("criteria").keySet().stream().sorted().forEach(s -> foodEaten.add(s.startsWith("minecraft:") ? s.substring(10) : s));
        }
        criterias.add("biomes", biomes);
        criterias.add("monstersKilled", monstersKilled);
        criterias.add("animalsBred", animalsBred);
        criterias.add("catsTamed", catsTamed);
        criterias.add("foodEaten", foodEaten);

        JsonObject aaItems = new JsonObject();

        boolean hasEnchantedGoldenApple = advancements.has(EGA_ADVANCEMENT) && advancements.getAsJsonObject(EGA_ADVANCEMENT).has("complete") && advancements.getAsJsonObject(EGA_ADVANCEMENT).get("complete").getAsBoolean();
        aaItems.addProperty("has_enchanted_golden_apple", hasEnchantedGoldenApple);

        aaItems.addProperty("skulls", 0);
        getAnyPlayerStats(record).ifPresent(playerStats -> aaItems.addProperty("skulls",
                getItemStat(playerStats, "minecraft:picked_up", "minecraft:wither_skeleton_skull") -
                        getItemStat(playerStats, "minecraft:dropped", "minecraft:wither_skeleton_skull") -
                        getItemStat(playerStats, "minecraft:used", "minecraft:wither_skeleton_skull")
        ));

        JsonObject toSend = new JsonObject();

        toSend.addProperty("lastRecordModified", lastRecordMTime);
        toSend.addProperty("gameVersion", latestWorld.get("version").getAsString());
        toSend.addProperty("modVersion", latestWorld.get("mod_version").getAsString().split("\\+")[0]);
        toSend.addProperty("aaTrackerVersion", VERSION.startsWith("v") ? VERSION.substring(1) : VERSION);
        JsonArray modList = new JsonArray();
        latestWorld.getAsJsonArray("mods").asList().stream().map(JsonElement::getAsString).sorted().forEach(modList::add);
        toSend.addProperty("worldId", getWorldId());
        toSend.add("modList", modList);
        toSend.add("completed", completed);
        toSend.add("timelines", record.getAsJsonArray("timelines"));
        JsonArray eventList = new JsonArray(events.size());
        events.forEach(eventList::add);
        toSend.add("eventList", eventList);
        toSend.add("criterias", criterias);
        toSend.add("items", aaItems);

        String toSendStringNoAK = toSend.toString();
        if (Objects.equals(lastSend, toSendStringNoAK)) {
            logDebug("Something updated but no changes found!");
            return;
        }
        lastSend = toSendStringNoAK;

        logDebug("Sending Exactly (access key hidden):\n" + toSendStringNoAK);

        toSend.addProperty("accessKey", AATrackerOptions.getInstance().accessKey);

        if (ACTUALLY_SEND) {
            try {
                PostUtil.PostResponse response = PostUtil.sendData(PACEMANGG_AA_SEND_ENDPOINT, toSend.toString());
                if (response.code < 400) {
                    runOnPaceMan = true;
                    log("Run updated on PaceMan.gg!");
                } else {
                    logError("Failed to send to PaceMan.gg: " + response.message);
                    endRun("Failed to send to PaceMan.gg", false);
                }
            } catch (Throwable t) {
                logError("Error during paceman.gg sending:\n" + ExceptionUtil.toDetailedString(t));
                endRun("Error during sending to paceman.gg", false);
            }
        }
    }

    private static String cleanseCatName(String catName) {
        if (catName.startsWith("textures/entity/cat/")) {
            catName = catName.substring("textures/entity/cat/".length());
        }
        if (catName.endsWith(".png")) {
            catName = catName.substring(0, catName.length() - 4);
        }
        return catName;
    }

    private static boolean hasNetherEnter() {
        return events.stream().anyMatch(s -> s.startsWith("rsg.enter_nether"));
    }

    private static boolean hasEvilEvents() {
        return events.stream().anyMatch(s -> s.startsWith("common.multiplayer") || s.startsWith("common.view_seed") || s.startsWith("common.enable_cheats") || s.startsWith("common.old_world"));
    }

    private static void endRun(String reason, boolean onlyLogIfWasOnPaceman) {
        if (runOnPaceMan || !onlyLogIfWasOnPaceman) logDebug("Ending run for reason: " + reason);
        if (runOnPaceMan) {
            logDebug("Killing run since it ended and was on paceman...");
            try {
                PostUtil.sendData(PACEMANGG_AA_KILL_ENDPOINT, String.format("{\"accessKey\":\"%s\"}", AATrackerOptions.getInstance().accessKey));
            } catch (IOException e) {
                logError("Failed to kill run: " + ExceptionUtil.toDetailedString(e));
            }
            runOnPaceMan = false;
        }
        runKilledOrEnded = true;
    }

    private static void updateEvents() {
        assert latestWorld != null;

        events = Collections.emptyList();
        try {
            Path eventsLogPath = getWorldPath().get().resolve("speedrunigt").resolve("events.log");
            if (Files.exists(eventsLogPath)) {
                events = Files.readAllLines(eventsLogPath).stream().map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
            }
        } catch (Exception e) {
            logError("Error while reading events.log: " + ExceptionUtil.toDetailedString(e));
        }
    }

    private static String getWorldId() {
        assert latestWorld != null;
        assert !events.isEmpty();

        String firstEvent = events.get(0);
        String[] parts = firstEvent.split(" ");
        String worldUniquifier;
        switch (parts.length) {
            case 3: // should always be this
                worldUniquifier = ";" + parts[0] + ";" + parts[1] + ";" + parts[2];
                break;
            case 2:
                logWarning("Event log contained only 2 parts for an event line! \"" + firstEvent + "\"");
                worldUniquifier = ";" + parts[0] + ";" + parts[1];
                break;
            default:
                logWarning("Event log contained a strange number of parts for an event line! \"" + firstEvent + "\"");
                worldUniquifier = ";" + parts[0];
                break;
        }
        return sha256Hash(getWorldPath().get() + worldUniquifier);
    }

    private static Optional<Path> getWorldPath() {
        return Optional.ofNullable(latestWorld).map(json -> Paths.get(json.get("world_path").getAsString()).toAbsolutePath());
    }

    private static void checkLatestWorld() throws IOException {
        if (!Files.exists(GLOBAL_LATEST_WORLD_PATH)) {
            latestWorld = null;
            return;
        }
        long newMTime = Files.getLastModifiedTime(GLOBAL_LATEST_WORLD_PATH).toMillis();
        if (newMTime != lastLatestWorldMTime) {
            @Nullable JsonObject lastLatestWorld = latestWorld;
            // Clear stuff
            lastLatestWorldMTime = newMTime;
            latestWorld = null;

            // Read and parse
            JsonObject json;
            try {
                json = GSON.fromJson(new String(Files.readAllBytes(GLOBAL_LATEST_WORLD_PATH)), JsonObject.class);
            } catch (Throwable t) {
                logError("Failed to read latest_world.json: " + ExceptionUtil.toDetailedString(t));
                return;
            }

            // Check everything is there
            if (!Stream.of("version", "mod_version", "category", "mods", "world_path").allMatch(json::has)) {
                logDebug("latest_world.json is missing data! Required data: \"version\", \"mod_version\", \"category\", \"mods\", \"world_path\"");
                return;
            }

            // Check for random speedrun #x, AA cat, and atum settings
            Path worldPath = Paths.get(json.get("world_path").getAsString());
            if (!RANDOM_WORLD_PATTERN.matcher(worldPath.getFileName().toString()).matches()) {
                logDebug("World path from latest_world.json does not match random world pattern.");
                return;
            }
            String category = json.get("category").getAsString();
            if (!(category.equals("ALL_ADVANCEMENTS") || category.equals("ANY"))) {
                logDebug("Invalid category in latest_world.json.");
                return;
            }
            if (!areAtumSettingsGood(worldPath)) return;

            Path recordPath = worldPath.resolve("speedrunigt").resolve("record.json");
            Path eventsPath = worldPath.resolve("speedrunigt").resolve("events.log");
            if (!Files.exists(recordPath)) return;
            if (!Files.exists(eventsPath)) return;

            // If world path changes
            if (lastLatestWorld == null || (!Objects.equals(lastLatestWorld.get("world_path"), json.get("world_path")))) {
                endRun("World path changed.", true);
                lastRecordMTime = Files.getLastModifiedTime(recordPath).toMillis();
                lastEventsMTime = Files.getLastModifiedTime(eventsPath).toMillis();
                runKilledOrEnded = false;
            }

            latestWorld = json; // This latest world is pointing to valid stuff
        }
    }

    private static boolean shouldRun() {
        AATrackerOptions options = AATrackerOptions.getInstance();
        if (options.accessKey.isEmpty()) {
            return false;
        }
        return !AATracker.asPlugin || options.enabledForPlugin;
    }

    private static int getItemStat(JsonObject playerStats, String type, String itemName) {
        return Optional.ofNullable(playerStats)
                .map(j -> j.get(type))
                .map(e -> e.isJsonObject() ? e.getAsJsonObject() : null)
                .map(j -> j.get(itemName))
                .map(e -> e.isJsonPrimitive() ? e.getAsJsonPrimitive() : null)
                .map(p -> p.isNumber() ? p.getAsInt() : null).orElse(0);
    }

    private static Optional<JsonObject> getAnyPlayerStats(JsonObject record) {
        return Optional.ofNullable(record)
                .map(j -> j.get("stats"))
                .map(e -> e.isJsonObject() ? e.getAsJsonObject() : null)
                .map(j -> j.keySet().isEmpty() ? null : j.get(j.keySet().stream().findAny().get()))
                .map(e -> e.isJsonObject() ? e.getAsJsonObject() : null)
                .map(j -> j.get("stats"))
                .map(e -> e.isJsonObject() ? e.getAsJsonObject() : null);
    }
}
