package com.rumblingmod;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * All the Rumbling logic lives here.
 *
 * HOW THE REAL RUMBLING WORKS (Attack on Titan), and how we copy it:
 *  - Eren, using the Founding Titan, releases the countless Colossal-type wall titans
 *    that were hardened inside Paradis' walls.             -> titans spawn at the WALLS.
 *  - They emerge from the walls one after another, with steam and thunderous noise.
 *                                                          -> staggered spawning + particles + sound.
 *  - They walk shoulder-to-shoulder in a huge line in ONE direction, slowly and without stopping,
 *    never chasing individual people.                      -> AI off, constant straight march.
 *  - Everything under their feet is crushed.               -> blocks cleared and entities killed under them.
 *  - The Founding Titan's skeletal spine leads them. (Not included, it needs a new model.)
 *  - It ends when the Founding Titan's power ends: all the titans vanish. -> /daot stop rumbling.
 */
public class RumblingManager {

    /** Every rumbling titan gets this tag so we can always find (and remove) them. */
    public static final String TAG = "daot_rumbling_titan";

    private static boolean active = false;
    private static int ticks = 0;
    private static EntityType<?> cachedType = null;

    private static final Deque<SpawnTask> queue = new ArrayDeque<>();
    private static final List<TitanRef> titans = new ArrayList<>();

    private record SpawnTask(ServerWorld world, Vec3d pos, Vec3d dir) {}

    private record TitanRef(RegistryKey<World> dimension, UUID id, Vec3d dir) {}

    // ------------------------------------------------------------------
    // Setup
    // ------------------------------------------------------------------

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(RumblingManager::tick);

        // New world / server: forget everything from the last one.
        ServerLifecycleEvents.SERVER_STARTING.register(server -> reset());

        // If a saved game still contains rumbling titans from before a restart, remove them.
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (!active && entity.getCommandTags().contains(TAG)) {
                world.getServer().execute(entity::discard);
            }
        });
    }

    private static void reset() {
        active = false;
        ticks = 0;
        cachedType = null;
        queue.clear();
        titans.clear();
    }

    // ------------------------------------------------------------------
    // /daot start rumbling
    // ------------------------------------------------------------------

    public static int start(ServerCommandSource source, int countOverride) {
        RumblingConfig cfg = RumblingMod.CONFIG;

        if (active) {
            source.sendError(Text.literal("The Rumbling is already happening! Use /daot stop rumbling first."));
            return 0;
        }

        EntityType<?> type = findColossalType();
        if (type == null) {
            source.sendError(Text.literal(
                    "Could not find Danny's AOT Colossal Titan. Is Danny's AOT installed? "
                            + "Run /daot rumbling info to see what was found."));
            return 0;
        }

        ServerWorld world = source.getWorld();
        Vec3d origin = source.getPosition();
        int count = countOverride > 0 ? countOverride : cfg.titanCount;

        // The direction you are looking (flat, ignoring up/down).
        Vec2f rotation = source.getRotation();
        float yawRad = rotation.y * MathHelper.RADIANS_PER_DEGREE;
        Vec3d lookDir = new Vec3d(-MathHelper.sin(yawRad), 0, MathHelper.cos(yawRad));

        List<SpawnTask> tasks = new ArrayList<>();
        String mode = "line";

        if (cfg.useWalls) {
            tasks = findWallSpawns(world, origin, lookDir, count);
            if (!tasks.isEmpty()) {
                mode = "walls";
            }
        }
        if (tasks.isEmpty()) {
            tasks = buildLineFormation(world, origin, lookDir, count);
        }
        if (tasks.isEmpty()) {
            source.sendError(Text.literal("No loaded ground to spawn on. Stand somewhere with loaded chunks around you."));
            return 0;
        }

        queue.clear();
        titans.clear();
        queue.addAll(tasks);
        ticks = 0;
        active = true;

        final String finalMode = mode;
        final int total = tasks.size();
        source.getServer().getPlayerManager().broadcast(
                Text.literal("§4§lThe Rumbling has begun..."), false);
        source.sendFeedback(() -> Text.literal(
                "Rumbling started: " + total + " Colossal Titans ("
                        + (finalMode.equals("walls") ? "coming out of the walls" : "no walls found, using a line formation")
                        + ")."), true);
        return total;
    }

    // ------------------------------------------------------------------
    // /daot stop rumbling
    // ------------------------------------------------------------------

    public static int stop(ServerCommandSource source) {
        int removed = 0;
        for (ServerWorld world : source.getServer().getWorlds()) {
            List<? extends Entity> found = world.getEntitiesByType(
                    TypeFilter.instanceOf(Entity.class),
                    e -> e.getCommandTags().contains(TAG));
            for (Entity e : new ArrayList<>(found)) {
                e.discard();   // gone, no drops, no death animation
                removed++;
            }
        }
        queue.clear();
        titans.clear();
        active = false;

        final int total = removed;
        source.sendFeedback(() -> Text.literal("The Rumbling has ended. " + total + " titans vanished."), true);
        return removed;
    }

    // ------------------------------------------------------------------
    // /daot rumbling info  (debug helper)
    // ------------------------------------------------------------------

    public static int info(ServerCommandSource source) {
        RumblingConfig cfg = RumblingMod.CONFIG;
        EntityType<?> type = findColossalType();

        source.sendFeedback(() -> Text.literal("§6Titan used: §f"
                + (type == null ? "NOT FOUND" : Registries.ENTITY_TYPE.getId(type).toString())), false);

        List<Identifier> candidates = colossalCandidates();
        source.sendFeedback(() -> Text.literal("§6Entities with 'colossal' in the name: §f"
                + (candidates.isEmpty() ? "none (is Danny's AOT installed?)" : candidates.toString())), false);

        List<SpawnTask> walls = findWallSpawns(source.getWorld(), source.getPosition(), new Vec3d(0, 0, 1), 9999);
        source.sendFeedback(() -> Text.literal("§6Wall spawn points found near you: §f" + walls.size()
                + (walls.isEmpty() ? " (stand closer to a wall, or edit wallBlockKeywords in the config)" : "")), false);

        source.sendFeedback(() -> Text.literal("§6Config file: §fconfig/daot_rumbling.json  (titanCount = "
                + cfg.titanCount + ")"), false);
        return 1;
    }

    // ------------------------------------------------------------------
    // Finding Danny's Colossal Titan
    // ------------------------------------------------------------------

    /**
     * We never copy Danny's model. We spawn HIS entity, so it automatically looks and animates
     * exactly like his Colossal Titan (and gets his future updates for free).
     */
    private static EntityType<?> findColossalType() {
        if (cachedType != null) {
            return cachedType;
        }
        String wanted = RumblingMod.CONFIG.colossalEntityId;

        if (wanted != null && !wanted.equalsIgnoreCase("auto")) {
            Identifier id = Identifier.tryParse(wanted);
            if (id != null && Registries.ENTITY_TYPE.containsId(id)) {
                cachedType = Registries.ENTITY_TYPE.get(id);
            }
            return cachedType;
        }

        List<Identifier> candidates = colossalCandidates();
        if (candidates.isEmpty()) {
            return null;
        }
        cachedType = Registries.ENTITY_TYPE.get(candidates.get(0));
        RumblingMod.LOGGER.info("Using {} as the Colossal Titan.", candidates.get(0));
        return cachedType;
    }

    /** All non-vanilla entities with "colossal" in their id, shortest name first (most likely the real titan). */
    private static List<Identifier> colossalCandidates() {
        List<Identifier> result = new ArrayList<>();
        for (Identifier id : Registries.ENTITY_TYPE.getIds()) {
            if (!id.getNamespace().equals("minecraft") && id.getPath().contains("colossal")) {
                result.add(id);
            }
        }
        result.sort(Comparator.comparingInt((Identifier id) -> id.getPath().length()));
        return result;
    }

    // ------------------------------------------------------------------
    // Where do the titans appear?
    // ------------------------------------------------------------------

    /** Searches around the player for wall blocks and spawns titans just outside them, walking outward. */
    private static List<SpawnTask> findWallSpawns(ServerWorld world, Vec3d origin, Vec3d fallbackDir, int want) {
        RumblingConfig cfg = RumblingMod.CONFIG;
        List<BlockPos> hits = new ArrayList<>();

        int radius = cfg.wallSearchRadius;
        int ox = MathHelper.floor(origin.x);
        int oz = MathHelper.floor(origin.z);

        for (int dx = -radius; dx <= radius; dx += 4) {
            for (int dz = -radius; dz <= radius; dz += 4) {
                if (dx * dx + dz * dz > radius * radius) continue;
                int x = ox + dx;
                int z = oz + dz;
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;

                int top = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z);
                BlockPos pos = new BlockPos(x, top - 1, z);
                Identifier id = Registries.BLOCK.getId(world.getBlockState(pos).getBlock());
                if (isWallBlock(id)) {
                    hits.add(pos);
                }
            }
        }

        Collections.shuffle(hits);
        List<SpawnTask> tasks = new ArrayList<>();
        List<Vec3d> used = new ArrayList<>();

        for (BlockPos hit : hits) {
            if (tasks.size() >= want) break;

            Vec3d wallPos = Vec3d.ofCenter(hit);
            boolean tooClose = false;
            for (Vec3d other : used) {
                if (other.squaredDistanceTo(wallPos) < cfg.wallSpawnSpacing * cfg.wallSpawnSpacing) {
                    tooClose = true;
                    break;
                }
            }
            if (tooClose) continue;

            // Walk away from the player's position (centre of the walled area).
            Vec3d outward = new Vec3d(wallPos.x - origin.x, 0, wallPos.z - origin.z);
            outward = outward.lengthSquared() < 1.0 ? fallbackDir : outward.normalize();

            double sx = wallPos.x + outward.x * 8;
            double sz = wallPos.z + outward.z * 8;
            if (!world.isChunkLoaded(MathHelper.floor(sx) >> 4, MathHelper.floor(sz) >> 4)) continue;
            double sy = world.getTopY(Heightmap.Type.WORLD_SURFACE, MathHelper.floor(sx), MathHelper.floor(sz));

            used.add(wallPos);
            tasks.add(new SpawnTask(world, new Vec3d(sx, sy, sz), outward));
        }
        return tasks;
    }

    private static boolean isWallBlock(Identifier id) {
        RumblingConfig cfg = RumblingMod.CONFIG;
        if (cfg.ignoreVanillaBlocksForWalls && id.getNamespace().equals("minecraft")) {
            return false;
        }
        String path = id.getPath();
        for (String keyword : cfg.wallBlockKeywords) {
            if (path.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /** Fallback: a wide line of titans behind you, all marching the way you are looking. */
    private static List<SpawnTask> buildLineFormation(ServerWorld world, Vec3d origin, Vec3d dir, int count) {
        RumblingConfig cfg = RumblingMod.CONFIG;
        List<SpawnTask> tasks = new ArrayList<>();
        Vec3d sideways = new Vec3d(dir.z, 0, -dir.x);
        int columns = Math.max(1, cfg.columns);

        for (int i = 0; i < count; i++) {
            int row = i / columns;
            int col = i % columns;
            int inThisRow = Math.min(columns, count - row * columns);
            double offset = (col - (inThisRow - 1) / 2.0) * cfg.lineSpacing;

            Vec3d pos = origin
                    .subtract(dir.multiply(cfg.lineSpawnDistance + row * cfg.rowSpacing))
                    .add(sideways.multiply(offset));

            int bx = MathHelper.floor(pos.x);
            int bz = MathHelper.floor(pos.z);
            if (!world.isChunkLoaded(bx >> 4, bz >> 4)) continue;
            double y = world.getTopY(Heightmap.Type.WORLD_SURFACE, bx, bz);

            tasks.add(new SpawnTask(world, new Vec3d(pos.x, y, pos.z), dir));
        }
        return tasks;
    }

    // ------------------------------------------------------------------
    // Every tick while the Rumbling is active
    // ------------------------------------------------------------------

    private static void tick(MinecraftServer server) {
        if (!active) return;
        RumblingConfig cfg = RumblingMod.CONFIG;
        ticks++;

        if (!queue.isEmpty() && ticks % Math.max(1, cfg.spawnIntervalTicks) == 0) {
            spawnNext();
        }

        int blockBudget = cfg.blockBudgetPerTick;
        int index = 0;

        for (TitanRef ref : titans) {
            index++;
            ServerWorld world = server.getWorld(ref.dimension());
            if (world == null) continue;

            Entity entity = world.getEntity(ref.id());
            if (!(entity instanceof MobEntity titan) || !titan.isAlive()) continue;

            march(titan, ref.dir());

            if (cfg.destroyBlocks && blockBudget > 0 && (ticks + index) % 4 == 0) {
                blockBudget -= clearBlocks(world, titan, ref.dir(), blockBudget);
            }
            if (cfg.trampleEntities && (ticks + index) % 10 == 0) {
                trample(world, titan);
            }
            if ((ticks + index) % 10 == 0) {
                Box box = titan.getBoundingBox();
                world.spawnParticles(ParticleTypes.LARGE_SMOKE,
                        titan.getX(), titan.getY() + 0.5, titan.getZ(),
                        8, box.getLengthX() / 3.0, 0.5, box.getLengthZ() / 3.0, 0.02);
            }
        }
    }

    /** Spawns the next titan in the queue, with steam and a roar-like sound. */
    private static void spawnNext() {
        SpawnTask task = queue.poll();
        EntityType<?> type = findColossalType();
        if (task == null || type == null) return;

        ServerWorld world = task.world();
        Entity entity = type.create(world);
        if (entity == null) return;

        float yaw = yawFromDirection(task.dir());
        entity.refreshPositionAndAngles(task.pos().x, task.pos().y, task.pos().z, yaw, 0f);

        if (entity instanceof MobEntity mob) {
            mob.initialize(world, world.getLocalDifficulty(BlockPos.ofFloored(task.pos())),
                    SpawnReason.COMMAND, null);
            mob.setPersistent();                       // never despawn
            mob.setTarget(null);
            if (RumblingMod.CONFIG.disableAi) {
                mob.setAiDisabled(true);               // we steer it ourselves
            }
            EntityAttributeInstance step = mob.getAttributeInstance(EntityAttributes.GENERIC_STEP_HEIGHT);
            if (step != null && step.getBaseValue() < RumblingMod.CONFIG.stepHeight) {
                step.setBaseValue(RumblingMod.CONFIG.stepHeight);
            }
        }
        if (RumblingMod.CONFIG.invulnerable) {
            entity.setInvulnerable(true);
        }
        entity.addCommandTag(TAG);
        world.spawnEntity(entity);

        titans.add(new TitanRef(world.getRegistryKey(), entity.getUuid(), task.dir()));

        // Steam + sound, like the titans bursting out of the wall.
        world.spawnParticles(ParticleTypes.CLOUD,
                task.pos().x, task.pos().y + 2, task.pos().z, 150, 4, 6, 4, 0.05);
        world.playSound(null, BlockPos.ofFloored(task.pos()),
                SoundEvents.ENTITY_WARDEN_EMERGE, SoundCategory.HOSTILE, 6.0f, 0.5f);
    }

    /** Keeps the titan walking in a straight line, facing the right way, never stopping. */
    private static void march(MobEntity titan, Vec3d dir) {
        double speed = RumblingMod.CONFIG.marchSpeed;
        Vec3d v = titan.getVelocity();
        titan.setVelocity(dir.x * speed, v.y, dir.z * speed);

        float yaw = yawFromDirection(dir);
        titan.setYaw(yaw);
        titan.setBodyYaw(yaw);
        titan.setHeadYaw(yaw);
        titan.setTarget(null);
    }

    /** Removes blocks in front of and under the titan's body. Returns how many were removed. */
    private static int clearBlocks(ServerWorld world, MobEntity titan, Vec3d dir, int budget) {
        RumblingConfig cfg = RumblingMod.CONFIG;
        Box box = titan.getBoundingBox().stretch(dir.x * 3, 0, dir.z * 3);

        int minX = MathHelper.floor(box.minX);
        int maxX = MathHelper.floor(box.maxX);
        int minZ = MathHelper.floor(box.minZ);
        int maxZ = MathHelper.floor(box.maxZ);
        int minY = MathHelper.floor(box.minY);
        int maxY = minY + cfg.clearHeight;

        int removed = 0;
        BlockPos.Mutable pos = new BlockPos.Mutable();

        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (removed >= budget) return removed;
                    pos.set(x, y, z);
                    if (!world.isChunkLoaded(pos)) continue;

                    BlockState state = world.getBlockState(pos);
                    if (state.isAir()) continue;
                    if (state.getHardness(world, pos) < 0) continue;   // bedrock etc.

                    world.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
                    removed++;
                }
            }
        }
        return removed;
    }

    /** Anything living under the titan's body gets crushed (creative players are immune automatically). */
    private static void trample(ServerWorld world, MobEntity titan) {
        Box b = titan.getBoundingBox();
        Box feet = new Box(b.minX, b.minY, b.minZ, b.maxX, b.minY + RumblingMod.CONFIG.clearHeight, b.maxZ);

        List<Entity> victims = world.getOtherEntities(titan, feet,
                e -> e instanceof LivingEntity && !e.getCommandTags().contains(TAG));
        for (Entity victim : victims) {
            victim.damage(world.getDamageSources().mobAttack(titan), 1000.0f);
        }
    }

    /** Minecraft yaw: 0 = facing +Z (south). */
    private static float yawFromDirection(Vec3d dir) {
        return (float) (MathHelper.atan2(-dir.x, dir.z) * (180.0 / Math.PI));
    }
}
