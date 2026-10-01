package com.rlclones.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.rlclones.Config;
import com.rlclones.ai.brain.Brain;
import com.rlclones.ai.brain.EnemyKnowledge;
import com.rlclones.clone.CloneManager;
import com.rlclones.clone.ClonePlayer;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** /rlclone summon|list|remove|link|respawn|brain|knowledge|save */
public final class CloneCommand {
    private static final SuggestionProvider<CommandSourceStack> NAMES = (ctx, builder) -> {
        List<String> names = new ArrayList<>();
        for (ClonePlayer c : manager(ctx).clones()) {
            names.add(c.getGameProfile().getName());
        }
        return SharedSuggestionProvider.suggest(names, builder);
    };

    private static final SuggestionProvider<CommandSourceStack> TYPES = (ctx, builder) -> {
        ClonePlayer c = manager(ctx).byName(StringArgumentType.getString(ctx, "name"));
        List<String> types = new ArrayList<>();
        if (c != null) {
            types.addAll(c.getCloneBrain().allKnowledge().keySet());
        }
        return SharedSuggestionProvider.suggest(types, builder);
    };

    private CloneCommand() {
    }

    private static CloneManager manager(CommandContext<CommandSourceStack> ctx) {
        return CloneManager.get(ctx.getSource().getServer());
    }

    private static boolean mayControl(CommandSourceStack src) {
        return !Config.get(Config.OPS_ONLY, false) || src.hasPermission(2);
    }

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("rlclone")
                .then(Commands.literal("summon").requires(CloneCommand::mayControl)
                        .executes(ctx -> summon(ctx, 1, ClonePlayer.DEFAULT_TEAM))
                        .then(Commands.literal("team").then(Commands.argument("team", StringArgumentType.word())
                                .executes(ctx -> summon(ctx, 1, StringArgumentType.getString(ctx, "team")))))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                .executes(ctx -> summon(ctx, IntegerArgumentType.getInteger(ctx, "count"), ClonePlayer.DEFAULT_TEAM))
                                .then(Commands.literal("team").then(Commands.argument("team", StringArgumentType.word())
                                        .executes(ctx -> summon(ctx, IntegerArgumentType.getInteger(ctx, "count"),
                                                StringArgumentType.getString(ctx, "team")))))))
                .then(Commands.literal("battleroyale").requires(CloneCommand::mayControl)
                        .executes(ctx -> setBattleRoyale(ctx, !manager(ctx).isBattleRoyale()))
                        .then(Commands.literal("on").executes(ctx -> setBattleRoyale(ctx, true)))
                        .then(Commands.literal("off").executes(ctx -> setBattleRoyale(ctx, false))))
                .then(Commands.literal("list").executes(CloneCommand::list))
                .then(Commands.literal("remove").requires(CloneCommand::mayControl)
                        .then(Commands.literal("all").executes(ctx -> {
                            int n = manager(ctx).removeAll();
                            ctx.getSource().sendSuccess(() -> Component.translatable("rlclones.cmd.removed_all", n), true);
                            return n;
                        }))
                        .then(Commands.argument("name", StringArgumentType.word()).suggests(NAMES).executes(CloneCommand::remove)))
                .then(Commands.literal("link").requires(CloneCommand::mayControl)
                        .executes(ctx -> setLink(ctx, !manager(ctx).isLinked()))
                        .then(Commands.literal("on").executes(ctx -> setLink(ctx, true)))
                        .then(Commands.literal("off").executes(ctx -> setLink(ctx, false))))
                .then(Commands.literal("respawn").requires(CloneCommand::mayControl)
                        .executes(ctx -> setRespawn(ctx, !manager(ctx).isRespawnEnabled()))
                        .then(Commands.literal("on").executes(ctx -> setRespawn(ctx, true)))
                        .then(Commands.literal("off").executes(ctx -> setRespawn(ctx, false))))
                .then(Commands.literal("ai").requires(CloneCommand::mayControl)
                        .then(Commands.argument("name", StringArgumentType.word()).suggests(NAMES)
                                .then(Commands.literal("on").executes(ctx -> setAi(ctx, true)))
                                .then(Commands.literal("off").executes(ctx -> setAi(ctx, false)))))
                .then(Commands.literal("brain")
                        .then(Commands.argument("name", StringArgumentType.word()).suggests(NAMES).executes(CloneCommand::brain)))
                .then(Commands.literal("knowledge")
                        .then(Commands.argument("name", StringArgumentType.word()).suggests(NAMES)
                                .executes(CloneCommand::knowledgeList)
                                .then(Commands.argument("type", StringArgumentType.greedyString()).suggests(TYPES)
                                        .executes(CloneCommand::knowledgeDetail))))
                .then(Commands.literal("limit")
                        .executes(ctx -> {
                            ctx.getSource().sendSuccess(() -> Component.translatable("rlclones.cmd.limit", Config.cloneLimitLabel()), false);
                            return 1;
                        })
                        .then(Commands.literal("unlimited").requires(s -> s.hasPermission(2)).executes(ctx -> setLimit(ctx, 0)))
                        .then(Commands.argument("max", IntegerArgumentType.integer(0, 1_000_000)).requires(s -> s.hasPermission(2))
                                .executes(ctx -> setLimit(ctx, IntegerArgumentType.getInteger(ctx, "max")))))
                .then(Commands.literal("save").requires(CloneCommand::mayControl).executes(ctx -> {
                    manager(ctx).saveAll();
                    ctx.getSource().sendSuccess(() -> Component.translatable("rlclones.cmd.saved"), false);
                    return 1;
                })));
    }

    private static int setLimit(CommandContext<CommandSourceStack> ctx, int max) {
        Config.setCloneLimit(max);
        CloneManager m = CloneManager.peek();
        if (m != null) {
            m.updatePlayerSlots();
        }
        ctx.getSource().sendSuccess(() -> Component.translatable("rlclones.cmd.limit_set", Config.cloneLimitLabel()), true);
        return 1;
    }

    private static int setBattleRoyale(CommandContext<CommandSourceStack> ctx, boolean on) {
        manager(ctx).setBattleRoyale(on);
        ctx.getSource().sendSuccess(() -> on ? Component.translatable("rlclones.msg.br_on", manager(ctx).clones().size())
                : Component.translatable("rlclones.msg.br_off"), true);
        return 1;
    }

    private static int summon(CommandContext<CommandSourceStack> ctx, int count, String team) {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayer();
        CloneManager m = manager(ctx);
        Vec3 base = src.getPosition();
        int made = 0;
        for (int i = 0; i < count; i++) {
            double angle = i * (Math.PI * 2 / Math.max(1, count));
            Vec3 pos = count == 1 ? base : base.add(Math.cos(angle) * 2, 0, Math.sin(angle) * 2);
            ClonePlayer c = m.summon(ClonePlayer.DEFAULT_TEAM.equals(team) ? player : null, src.getLevel(), pos, src.getRotation().y + 180f, team);
            if (c == null) {
                break;
            }
            made++;
        }
        int total = made;
        if (total == 0) {
            src.sendFailure(Component.translatable("rlclones.msg.limit", Config.cloneLimitLabel()));
        } else {
            src.sendSuccess(() -> ClonePlayer.DEFAULT_TEAM.equals(team) ? Component.translatable("rlclones.cmd.summoned", total)
                    : Component.translatable("rlclones.cmd.summoned_team", total, team), true);
        }
        return total;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        CloneManager m = manager(ctx);
        CommandSourceStack src = ctx.getSource();
        src.sendSuccess(() -> Component.translatable("rlclones.cmd.status", m.clones().size(),
                Component.translatable(m.isLinked() ? "rlclones.on" : "rlclones.off"),
                Component.translatable(m.isRespawnEnabled() ? "rlclones.on" : "rlclones.off")).withStyle(ChatFormatting.AQUA), false);
        for (ClonePlayer c : m.clones()) {
            String line = m.describe(c);
            src.sendSuccess(() -> Component.literal(" - " + line), false);
        }
        return m.clones().size();
    }

    private static int remove(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        CloneManager m = manager(ctx);
        ClonePlayer c = m.byName(name);
        if (c == null) {
            ctx.getSource().sendFailure(Component.translatable("rlclones.cmd.unknown", name));
            return 0;
        }
        m.remove(c, true, Component.translatable("rlclones.cmd.removed", name));
        ctx.getSource().sendSuccess(() -> Component.translatable("rlclones.cmd.removed", name), true);
        return 1;
    }

    private static int setLink(CommandContext<CommandSourceStack> ctx, boolean on) {
        CloneManager m = manager(ctx);
        m.setLinked(on);
        ctx.getSource().sendSuccess(() -> on
                ? Component.translatable("rlclones.msg.link_on", m.clones().size())
                : Component.translatable("rlclones.msg.link_off"), true);
        return 1;
    }

    private static int setRespawn(CommandContext<CommandSourceStack> ctx, boolean on) {
        manager(ctx).setRespawn(on);
        ctx.getSource().sendSuccess(() -> Component.translatable(on ? "rlclones.msg.respawn_on" : "rlclones.msg.respawn_off"), true);
        return 1;
    }

    private static int setAi(CommandContext<CommandSourceStack> ctx, boolean on) {
        String name = StringArgumentType.getString(ctx, "name");
        ClonePlayer c = manager(ctx).byName(name);
        if (c == null) {
            ctx.getSource().sendFailure(Component.translatable("rlclones.cmd.unknown", name));
            return 0;
        }
        c.setAiEnabled(on);
        ctx.getSource().sendSuccess(() -> Component.translatable(on ? "rlclones.cmd.ai_on" : "rlclones.cmd.ai_off", name), true);
        return 1;
    }

    private static int brain(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        CloneManager m = manager(ctx);
        ClonePlayer c = m.byName(name);
        if (c == null) {
            ctx.getSource().sendFailure(Component.translatable("rlclones.cmd.unknown", name));
            return 0;
        }
        Brain b = c.getCloneBrain();
        CommandSourceStack src = ctx.getSource();
        String state = m.describe(c);
        src.sendSuccess(() -> Component.literal(state).withStyle(ChatFormatting.AQUA), false);
        src.sendSuccess(() -> Component.literal((m.isLinked() ? "[linked] " : "") + String.join(" ", b.summary())), false);
        String vision = String.format(Locale.ROOT, "sees %d entities, remembers %d, knows %d resource blocks, watching %d agents (%d imitated transitions)",
                c.controller().perception().visible().size(), c.controller().perception().remembered().size(),
                c.controller().perception().blocks().size(), c.controller().watcher().watching(), c.controller().watcher().observedTransitions);
        src.sendSuccess(() -> Component.literal(vision).withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int knowledgeList(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        ClonePlayer c = manager(ctx).byName(name);
        if (c == null) {
            ctx.getSource().sendFailure(Component.translatable("rlclones.cmd.unknown", name));
            return 0;
        }
        Map<String, EnemyKnowledge> all = c.getCloneBrain().allKnowledge();
        CommandSourceStack src = ctx.getSource();
        src.sendSuccess(() -> Component.translatable("rlclones.cmd.knowledge_header", name, all.size()).withStyle(ChatFormatting.AQUA), false);
        all.forEach((type, k) -> {
            String line = String.format(Locale.ROOT, " - %s: seen %d, fights %d, observed attacks %d, combat states %d",
                    type, k.sightings, k.encounters, k.observations(), c.getCloneBrain().combatTypes().contains(type) ? c.getCloneBrain().combatTable(type).size() : 0);
            src.sendSuccess(() -> Component.literal(line), false);
        });
        return all.size();
    }

    private static int knowledgeDetail(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        String type = StringArgumentType.getString(ctx, "type").trim();
        ClonePlayer c = manager(ctx).byName(name);
        if (c == null) {
            ctx.getSource().sendFailure(Component.translatable("rlclones.cmd.unknown", name));
            return 0;
        }
        EnemyKnowledge k = c.getCloneBrain().knowledgeIfPresent(type);
        if (k == null && !type.contains(":")) {
            type = "minecraft:" + type;
            k = c.getCloneBrain().knowledgeIfPresent(type);
        }
        if (k == null) {
            ctx.getSource().sendFailure(Component.translatable("rlclones.cmd.no_knowledge", type));
            return 0;
        }
        CommandSourceStack src = ctx.getSource();
        String t = type;
        src.sendSuccess(() -> Component.literal("== " + t + " ==").withStyle(ChatFormatting.AQUA), false);
        for (String line : k.describe()) {
            src.sendSuccess(() -> Component.literal(line), false);
        }
        src.sendSuccess(() -> Component.translatable("rlclones.cmd.policy").withStyle(ChatFormatting.YELLOW), false);
        for (String line : c.getCloneBrain().describePolicy(type)) {
            src.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }
}
