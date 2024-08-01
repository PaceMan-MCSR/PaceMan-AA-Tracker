package gg.paceman.aatracker.launching;

import com.google.common.io.Resources;
import gg.paceman.aatracker.AATracker;
import gg.paceman.aatracker.AATrackerOptions;
import gg.paceman.aatracker.gui.AATrackerGUI;
import gg.paceman.aatracker.util.LockUtil;
import org.apache.logging.log4j.Level;
import xyz.duncanruns.julti.Julti;
import xyz.duncanruns.julti.JultiAppLaunch;
import xyz.duncanruns.julti.gui.JultiGUI;
import xyz.duncanruns.julti.gui.PluginsGUI;
import xyz.duncanruns.julti.plugin.PluginEvents;
import xyz.duncanruns.julti.plugin.PluginInitializer;
import xyz.duncanruns.julti.plugin.PluginManager;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Launches PaceMan AATracker as a Julti Plugin
 */
public class AATrackerPluginInit implements PluginInitializer {
    private static LockUtil.LockStuff lockStuff;
    private static boolean shouldRun = true;

    public static void main(String[] args) throws IOException {
        // This is only used to test the plugin in the dev environment
        // AATrackerPluginInit.main itself is never used when users run Julti

        // Run this in dev to test as Julti plugin

        PluginManager.JultiPluginData pluginData = PluginManager.JultiPluginData.fromString(
                Resources.toString(Resources.getResource(AATrackerPluginInit.class, "/julti.plugin.json"), Charset.defaultCharset())
        );
        AATracker.VERSION = pluginData.version;
        JultiAppLaunch.launchWithDevPlugin(args, pluginData, new AATrackerPluginInit());
    }

    private static void checkLock() {
        Path lockPath = AATrackerOptions.getPaceManAADir().resolve("LOCK");
        if (LockUtil.isLocked(lockPath)) {
            AATracker.logError("PaceMan AA Tracker will not run as it is already open elsewhere!");
            shouldRun = false;
        } else {
            lockStuff = LockUtil.lock(lockPath);
            PluginEvents.RunnableEventType.PRE_UPDATE.register(() -> LockUtil.releaseLock(lockStuff));
            Runtime.getRuntime().addShutdownHook(new Thread(() -> LockUtil.releaseLock(lockStuff)));
        }
    }

    private static void setLoggers() {
        AATracker.logConsumer = m -> Julti.log(Level.INFO, "(PaceMan AA Tracker) " + m);
        AATracker.debugConsumer = m -> Julti.log(Level.DEBUG, "(PaceMan AA Tracker) " + m);
        AATracker.errorConsumer = m -> Julti.log(Level.ERROR, "(PaceMan AA Tracker) " + m);
        AATracker.warningConsumer = m -> Julti.log(Level.WARN, "(PaceMan AA Tracker) " + m);
    }

    @Override
    public void initialize() {
        AATrackerOptions.ensurePaceManAADir();
        AATrackerPluginInit.setLoggers();
        AATrackerPluginInit.checkLock();
        if (!shouldRun) {
            return;
        }
        try {
            AATrackerOptions.load().save();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        Optional<PluginManager.LoadedJultiPlugin> pluginData = PluginManager.getPluginManager().getLoadedPlugins().stream().filter(loadedJultiPlugin -> loadedJultiPlugin.pluginData.id.equals("paceman-aa-tracker")).findAny();
        if (pluginData.isPresent()) {
            String version = pluginData.get().pluginData.version;
            AATracker.VERSION = version.equals("${version}") ? "DEV" : version;
            AATracker.log("Loaded PaceMan AA Tracker v" + AATracker.VERSION);
        }
        AATracker.start(true);
        PluginEvents.RunnableEventType.STOP.register(AATracker::stop);
        PluginEvents.RunnableEventType.PRE_UPDATE.register(AATracker::stop);
    }

    @Override
    public void onMenuButtonPress() {
        if (!shouldRun) {
            JOptionPane.showMessageDialog(JultiGUI.getPluginsGUI(), "PaceMan AA Tracker is already opened elsewhere! Please make sure it is closed, then restart Julti to continue.", "PaceMan AA Tracker: Already Opened", JOptionPane.WARNING_MESSAGE);
            return;
        }
        PluginsGUI pluginsGUI = JultiGUI.getPluginsGUI();
        AATrackerGUI.open(true, new Point(pluginsGUI.getX() + pluginsGUI.getWidth(), pluginsGUI.getY()));
    }
}
