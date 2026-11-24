package com.rumblingmod;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * Registers:
 *   /daot start rumbling [count]
 *   /daot stop rumbling
 *   /daot rumbling info        (shows which titan + walls were detected)
 *
 * Danny's AOT also uses "/daot ...". Brigadier (Minecraft's command system)
 * merges both, so /daot shifter set ... keeps working.
 * We put the permission check on our own sub-commands only (op level 2).
 */
public class RumblingCommand {

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(literal("daot")

                        .then(literal("start")
                                .requires(src -> src.hasPermissionLevel(2))
                                .then(literal("rumbling")
                                        .executes(ctx -> RumblingManager.start(ctx.getSource(), -1))
                                        .then(argument("count", IntegerArgumentType.integer(1, 500))
                                                .executes(ctx -> RumblingManager.start(
                                                        ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "count"))))))

                        .then(literal("stop")
                                .requires(src -> src.hasPermissionLevel(2))
                                .then(literal("rumbling")
                                        .executes(ctx -> RumblingManager.stop(ctx.getSource()))))

                        .then(literal("rumbling")
                                .requires(src -> src.hasPermissionLevel(2))
                                .then(literal("info")
                                        .executes(ctx -> RumblingManager.info(ctx.getSource()))))
                ));
    }
}
