# PaceMan AA Tracker

A standalone application or Julti plugin to track and upload All Advancement runs to PaceMan.gg.

## Usage

PaceMan AA Tracker can be downloaded from [the releases page](https://github.com/PaceMan-MCSR/PaceMan-AA-Tracker/releases) as a Jingle plugin, Julti plugin, or a standalone application.

The AA Tracker uses SpeedrunIGT record files, so the `Make Record` setting must be set to `Every Run` in order to ensure proper functioning of the tracker:

![image](https://github.com/user-attachments/assets/e38d8bff-8e60-4743-9dbe-727802cf15d0)



## Developing and Building

Both the plugin and standalone jars can be built using `./gradlew build`.

If you intend on changing GUI portions of the code, IntelliJ IDEA must be configured in a certain way to ensure the GUI form works properly:
- `Settings` -> `Build, Execution, Deployment` -> `Build Tools` -> `Gradle` -> `Build and run using: IntelliJ Idea`
- `Settings` -> `Editor` -> `GUI Designer` -> `Generate GUI into: Java source code`
