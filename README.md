# DAOT - The Rumbling  (Minecraft Java Edition, Fabric 1.21.1)

An addon for **Danny's AOT**. It spawns Danny's own Colossal Titan entity as the Rumbling.

## Commands (need operator / cheats on, permission level 2)
| Command | What it does |
|---|---|
| `/daot start rumbling` | Starts the Rumbling |
| `/daot start rumbling 100` | Starts it with 100 titans |
| `/daot stop rumbling` | Every rumbling titan instantly disappears |
| `/daot rumbling info` | Shows which titan was detected and how many wall points were found |

## What you need installed to PLAY
Minecraft Java 1.21.1, Fabric Loader, Fabric API, GeckoLib, AAA Particles, Danny's AOT, and this mod.

## How to BUILD it (beginner steps)
1. Install **JDK 21** (e.g. Temurin 21) and **IntelliJ IDEA Community**.
2. Get the Gradle wrapper (the one file type I cannot generate offline). Pick ONE:
   - A) If you have Gradle installed: open a terminal in this folder and run
        `gradle wrapper --gradle-version 8.10`
   - B) Download the template from https://fabricmc.net/develop/template/ (Minecraft 1.21.1)
        and copy its `gradlew`, `gradlew.bat` and `gradle/wrapper/gradle-wrapper.jar` into this folder.
        Keep THIS project's `gradle-wrapper.properties`.
3. Open this folder in IntelliJ (File > Open) and let Gradle sync.
4. Build: run `./gradlew build` (Windows: `gradlew build`).
5. Your mod is `build/libs/daot-rumbling-1.0.0.jar` (NOT the `-sources` one).
6. Put it in `.minecraft/mods` next to the other mods.

If the sync complains about `fabric_version`, look up the newest Fabric API for 1.21.1
on modrinth.com/mod/fabric-api and paste it into `gradle.properties`.

## Uploading to CurseForge
Create a project, upload the jar, choose Fabric + 1.21.1, and list
Fabric API and Danny's AOT as required dependencies.

## Settings
After the first launch edit `config/daot_rumbling.json` (titan count, speed, destruction, wall keywords...).

## If titans don't spawn at walls
Run `/daot rumbling info` near a wall. If it finds 0 wall points, look at a wall block with F3
open, read its block name, and add a word from that name to `wallBlockKeywords` in the config.
If it says the titan is NOT FOUND, copy the right id from the "colossal" list into `colossalEntityId`.
