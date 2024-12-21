package gg.paceman.aatracker.launching;

import com.google.common.io.Resources;
import gg.paceman.aatracker.AATracker;
import gg.paceman.aatracker.AATrackerOptions;
import gg.paceman.aatracker.gui.AATrackerGUI;
import gg.paceman.aatracker.gui.AATrackerPanel;
import gg.paceman.aatracker.util.LockUtil;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.Level;
import xyz.duncanruns.jingle.Jingle;
import xyz.duncanruns.jingle.JingleAppLaunch;
import xyz.duncanruns.jingle.gui.JingleGUI;
import xyz.duncanruns.jingle.plugin.PluginEvents;
import xyz.duncanruns.jingle.plugin.PluginManager;

import javax.swing.*;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Launches PaceMan Tracker as a Julti Plugin
 */
public class AATrackerJinglePluginInit {
    private static LockUtil.LockStuff lockStuff;
    private static boolean shouldRun = true;

    public static void main(String[] args) throws IOException {
        // This is only used to test the plugin in the dev environment
        // AATrackerJinglePluginInit.main itself is never used when users run Jingle

        // Run this in dev to test as Jingle plugin

        PluginManager.JinglePluginData pluginData = PluginManager.JinglePluginData.fromString(
                Resources.toString(Resources.getResource(AATrackerJinglePluginInit.class, "/jingle.plugin.json"), Charset.defaultCharset())
        );
        AATracker.VERSION = pluginData.version;
        JingleAppLaunch.launchWithDevPlugin(args, pluginData, AATrackerJinglePluginInit::initialize);
    }

    private static void checkLock() {
        Path lockPath = AATrackerOptions.getPaceManAADir().resolve("LOCK");
        if (LockUtil.isLocked(lockPath)) {
            AATracker.logError("PaceMan AA Tracker will not run as it is already open elsewhere!");
            shouldRun = false;
        } else {
            lockStuff = LockUtil.lock(lockPath);
            PluginEvents.STOP.register(() -> LockUtil.releaseLock(lockStuff));
            Runtime.getRuntime().addShutdownHook(new Thread(() -> LockUtil.releaseLock(lockStuff)));
        }
    }

    private static void setLoggers() {
        AATracker.logConsumer = m -> Jingle.log(Level.INFO, "(PaceMan AA Tracker) " + m);
        AATracker.debugConsumer = m -> Jingle.log(Level.DEBUG, "(PaceMan AA Tracker) " + m);
        AATracker.errorConsumer = m -> Jingle.log(Level.ERROR, "(PaceMan AA Tracker) " + m);
        AATracker.warningConsumer = m -> Jingle.log(Level.WARN, "(PaceMan AA Tracker) " + m);
    }

    public static void initialize() {
        AATrackerOptions.ensurePaceManAADir();
        AATrackerJinglePluginInit.setLoggers();
        AATrackerJinglePluginInit.checkLock();
        if (!shouldRun) {
            return;
        }
        try {
            AATrackerOptions.load().save();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        Optional<PluginManager.LoadedJinglePlugin> pluginData = PluginManager.getLoadedPlugins().stream().filter(loadedJinglePlugin -> loadedJinglePlugin.pluginData.id.equals("paceman-aa-tracker")).findAny();
        if (pluginData.isPresent()) {
            String version = pluginData.get().pluginData.version;
            AATracker.VERSION = version.equals("${version}") ? "DEV" : version;
            AATracker.log("Loaded PaceMan AA Tracker v" + AATracker.VERSION);
        }
        AATracker.start(true);
        PluginEvents.STOP.register(AATracker::stop);

        Pair<AATrackerGUI, JPanel> guiPair = AATrackerPanel.getNewGUIAsPanel();
        AATrackerGUI aaTrackerGUI = guiPair.getLeft();
        JPanel pmtPanel = guiPair.getRight();

        JingleGUI.addPluginTab("PaceMan AA Tracker", pmtPanel);


        JingleGUI.get().registerQuickActionButton(0, () -> {
            AATrackerOptions options = AATrackerOptions.getInstance();
            if (options == null) return null;
            if (options.accessKey.isEmpty()) return null;
            return JingleGUI.makeButton(
                    options.enabledForPlugin ? "Disable AA PaceMan" : "Enable AA PaceMan",
                    () -> {
                        options.enabledForPlugin = !options.enabledForPlugin;
                        aaTrackerGUI.enabledCheckBox.setSelected(options.enabledForPlugin);
                        JingleGUI.get().refreshQuickActions();
                        JingleGUI.get().refreshHack();
                        try {
                            options.save();
                        } catch (IOException ex) {
                            throw new RuntimeException(ex);
                        }
                    },
                    () -> JingleGUI.get().openTab(pmtPanel),
                    "Right Click to Configure",
                    true
            );
        });
        AATracker.jingleQABRefresh = () -> JingleGUI.get().refreshQuickActions();
    }
}
