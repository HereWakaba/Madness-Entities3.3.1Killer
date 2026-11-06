package com.ryjs.madnesskiller.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.ryjs.madnesskiller.util.MadnessKiller;
import com.ryjs.madnesskiller.util.MadnessReflect;
import com.ryjs.madnesskiller.util.ThreadStopper;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.Event;

import java.util.List;
import java.util.Locale;

public final class KillerCommand {

    private static final SuggestionProvider<CommandSourceStack> TARGETS =
            (context, builder) -> SharedSuggestionProvider.suggest(
                    List.of("popbob", "notch", "him", "herobrine", "null", "nether_avator", "远古下界神明",
                            "Ancient Nether Avatar", "the sun", "the moon", "prototype"),
                    builder);

    private static final int DEFAULT_PASSES = 12;
    private static final int PASS_INTERVAL = 5;

    private static MinecraftServer pendingServer;
    private static MadnessKiller.Scope pendingScope;
    private static String pendingQuery;
    private static int pendingPasses;
    private static int cooldown;
    private static int emptyStreak;

    private KillerCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("madnesskiller")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("killall")
                        .executes(context -> killAll(context, DEFAULT_PASSES))
                        .then(Commands.argument("passes", IntegerArgumentType.integer(0, 200))
                                .executes(context -> killAll(context, IntegerArgumentType.getInteger(context, "passes")))))
                .then(Commands.literal("killer")
                        .then(Commands.argument("target", StringArgumentType.greedyString())
                                .suggests(TARGETS)
                                .executes(context -> killer(context, StringArgumentType.getString(context, "target"))))));
    }

    private static int killAll(CommandContext<CommandSourceStack> context, int passes) {
        reset();
        MadnessKiller.resetTracking();
        MinecraftServer server = context.getSource().getServer();
        if (server == null) {
            return 0;
        }
        int scanned = MadnessKiller.run(server, MadnessKiller.Scope.MADNESS).scanned;

        ThreadStopper.stopOnce();
        schedulePasses(server, passes - 1, MadnessKiller.Scope.MADNESS, null);
        return scanned;
    }

    private static int killer(CommandContext<CommandSourceStack> context, String query) {
        reset();
        MadnessKiller.resetTracking();
        MinecraftServer server = context.getSource().getServer();
        if (server == null) {
            return 0;
        }
        int scanned = MadnessKiller.kill(server, query).scanned;
        schedulePasses(server, DEFAULT_PASSES - 1, null, query);
        return scanned;
    }

    private static void schedulePasses(MinecraftServer server, int passes, MadnessKiller.Scope scope, String query) {
        if (passes <= 0) {
            return;
        }
        pendingServer = server;
        pendingScope = scope;
        pendingQuery = query;
        pendingPasses = passes;
        cooldown = PASS_INTERVAL;
    }

    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || pendingPasses <= 0 || pendingServer == null) {
            return;
        }
        if (--cooldown > 0) {
            return;
        }
        cooldown = PASS_INTERVAL;
        MadnessKiller.Report report = pendingQuery == null
                ? MadnessKiller.run(pendingServer, pendingScope == null ? MadnessKiller.Scope.MADNESS : pendingScope)
                : MadnessKiller.kill(pendingServer, pendingQuery);
        pendingPasses--;
        if (report.scanned == 0 && report.hosts == 0) {
            emptyStreak++;
        } else {
            emptyStreak = 0;
        }
        if (pendingPasses <= 0 || emptyStreak >= 2) {
            reset();
        }
    }

    private static void reset() {
        pendingServer = null;
        pendingScope = null;
        pendingQuery = null;
        pendingPasses = 0;
        cooldown = 0;
        emptyStreak = 0;
    }

    public static void onEntityJoin(EntityJoinLevelEvent event) {
        MadnessReflect.observe(event.getEntity());
    }

    public static void onCommand(CommandEvent event) {
        try {
            String input = event.getParseResults().getReader().getString();
            if (input == null || !input.toLowerCase(Locale.ROOT).startsWith("/madnesskiller")) {
                return;
            }
            if (event.isCancelable() && event.isCanceled()) {
                event.setCanceled(false);
                try {
                    event.setResult(Event.Result.ALLOW);
                } catch (Throwable ignored) {

                }
            }
        } catch (Throwable ignored) {

        }
    }
}
