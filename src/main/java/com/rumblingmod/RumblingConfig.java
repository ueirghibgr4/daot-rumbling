package com.rumblingmod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Settings you can edit WITHOUT touching code.
 * The file is created automatically the first time the game runs:
 *   <minecraft folder>/config/daot_rumbling.json
 * Change a value, save the file, restart the game.
 */
public class RumblingConfig {

    // ---------- Which titan to use ----------
    /** "auto" = search Danny's AOT for an entity with "colossal" in its name.
     *  Or type an exact id such as "daot:colossal_titan" (use /daot rumbling info to see the real one). */
    public String colossalEntityId = "auto";

    // ---------- How many titans, and how they march ----------
    public int titanCount = 40;
    /** Blocks per tick (20 ticks = 1 second). 0.35 = about 7 blocks per second. */
    public double marchSpeed = 0.35;
    /** How many ticks between each titan appearing (5 = four titans per second). */
    public int spawnIntervalTicks = 5;
    /** Lets the titans climb over hills. */
    public double stepHeight = 6.0;

    // ---------- Coming out of the walls ----------
    public boolean useWalls = true;
    /** How far (in blocks) around you to search for walls. */
    public int wallSearchRadius = 160;
    /** A block counts as "wall" if its name contains one of these words. */
    public List<String> wallBlockKeywords = new ArrayList<>(List.of("wall"));
    /** Ignore vanilla blocks like cobblestone_wall so only Danny's AOT walls count. */
    public boolean ignoreVanillaBlocksForWalls = true;
    /** Minimum distance between two titans that come out of the walls. */
    public double wallSpawnSpacing = 30.0;

    // ---------- Fallback formation (used when no walls are found) ----------
    public double lineSpawnDistance = 60.0;
    public int columns = 12;
    public double lineSpacing = 30.0;
    public double rowSpacing = 45.0;

    // ---------- Destruction ----------
    public boolean destroyBlocks = true;
    /** Only blocks from the titan's feet up to this height are removed. */
    public int clearHeight = 14;
    /** Safety limit so the server does not lag. Raise if you have a strong PC. */
    public int blockBudgetPerTick = 4000;
    public boolean trampleEntities = true;

    // ---------- Titan behaviour ----------
    /** Turns off the titan's own AI so it marches instead of chasing players. */
    public boolean disableAi = true;
    public boolean invulnerable = true;

    // ------------------------------------------------------------------

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static RumblingConfig load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("daot_rumbling.json");
        try {
            if (Files.exists(path)) {
                RumblingConfig loaded = GSON.fromJson(Files.readString(path), RumblingConfig.class);
                if (loaded != null) {
                    return loaded;
                }
            }
            RumblingConfig fresh = new RumblingConfig();
            Files.writeString(path, GSON.toJson(fresh));
            return fresh;
        } catch (Exception e) {
            RumblingMod.LOGGER.error("Could not read config, using defaults.", e);
            return new RumblingConfig();
        }
    }
}
