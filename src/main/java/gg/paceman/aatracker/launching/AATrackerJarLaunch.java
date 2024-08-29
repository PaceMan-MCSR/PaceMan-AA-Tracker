package gg.paceman.aatracker.launching;

import com.formdev.flatlaf.FlatDarkLaf;
import gg.paceman.aatracker.AATracker;
import gg.paceman.aatracker.AATrackerOptions;
import gg.paceman.aatracker.gui.AATrackerGUI;
import gg.paceman.aatracker.util.LockUtil;

import javax.swing.*;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Launches PaceMan as a standalone program.
 */
public class AATrackerJarLaunch {
    private static LockUtil.LockStuff lockStuff;
    private static List<String> args;

    public static void main(String[] args) throws IOException {
        AATrackerJarLaunch.args = Arrays.asList(args);
        FlatDarkLaf.setup();

        if (!AATrackerJarLaunch.args.contains("--skiplocks")) {
            AATrackerJarLaunch.checkLock();
        }

        AATracker.VERSION = Optional.ofNullable(AATrackerJarLaunch.class.getPackage().getImplementationVersion()).orElse("DEV");
        AATrackerOptions.load().save();
        if (!AATrackerJarLaunch.args.contains("--nogui")) {
            AATrackerGUI gui = AATrackerGUI.open(false, null);
            gui.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        }
        AATracker.log("Running PaceMan AA Tracker v" + AATracker.VERSION);
        AATracker.start(false);
    }

    private static void checkLock() {
        AATrackerOptions.ensurePaceManAADir();
        Path lockPath = AATrackerOptions.getPaceManAADir().resolve("LOCK");
        if (LockUtil.isLocked(lockPath)) {
            if (AATrackerJarLaunch.args.contains("--nogui")) {
                System.out.println("PaceMan AA Tracker is already opened, you cannot run another instance. (Not recommended: use --skiplocks to bypass)");
                System.exit(0);
            } else {
                AATrackerJarLaunch.showMultiTrackerWarning();
            }
        } else {
            lockStuff = LockUtil.lock(lockPath);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> LockUtil.releaseLock(lockStuff)));
        }
    }

    private static void showMultiTrackerWarning() {
        boolean isJulti = LockUtil.isLocked(Paths.get(System.getProperty("user.home")).resolve(".Julti").resolve("LOCK").toAbsolutePath());
        boolean isJingle = LockUtil.isLocked(Paths.get(System.getProperty("user.home")).resolve(".config").resolve("Jingle").resolve("LOCK").toAbsolutePath());
        int ans = JOptionPane.showConfirmDialog(null, "PaceMan AA Tracker is already opened" + (isJulti ? " in Julti" : (isJingle ? " in Jingle" : "")) + "! Are you sure you want to open the tracker again?", "PaceMan AA Tracker: Already Opened", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ans != 0) {
            System.exit(0);
        }
    }
}
