package gg.paceman.aatracker.fabricfail;

public class FabricFail {
    public void onInitialize() {
        throw new RuntimeException("PaceMan AA Tracker is not supposed to be ran as a mod!");
    }
}
