package gg.paceman.aatracker.launching;

import com.google.common.io.Resources;
import gg.paceman.aatracker.AATracker;
import gg.paceman.aatracker.AATrackerOptions;
import gg.paceman.aatracker.gui.AATrackerPanel;
import gg.paceman.aatracker.util.LockUtil;
import org.apache.logging.log4j.Level;
import xyz.duncanruns.jingle.Jingle;
import xyz.duncanruns.jingle.JingleAppLaunch;
import xyz.duncanruns.jingle.gui.JingleGUI;
import xyz.duncanruns.jingle.plugin.PluginEvents;
import xyz.duncanruns.jingle.plugin.PluginManager;

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
            PluginEvents.RunnableEventType.STOP.register(() -> LockUtil.releaseLock(lockStuff));
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
        Optional<PluginManager.LoadedJinglePlugin> pluginData = PluginManager.getLoadedPlugins().stream().filter(loadedJinglePlugin -> loadedJinglePlugin.pluginData.id.equals("paceman-tracker")).findAny();
        if (pluginData.isPresent()) {
            String version = pluginData.get().pluginData.version;
            AATracker.VERSION = version.equals("${version}") ? "DEV" : version;
            AATracker.log("Loaded PaceMan AA Tracker v" + AATracker.VERSION);
        }
        AATracker.start(true);
        PluginEvents.RunnableEventType.STOP.register(AATracker::stop);

        JingleGUI.addPluginTab("PaceMan AA Tracker", AATrackerPanel.getPanel());
    }
}
