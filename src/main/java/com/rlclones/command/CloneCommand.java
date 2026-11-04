package com.rlclones.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.rlclones.Config;
import com.rlclones.RLClones;
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
                        .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                .executes(ctx -> summon(ctx, IntegerArgumentType.getInteger(ctx, "count"), ClonePlayer.DEFAULT_TEAM))
                                .then(Commands.literal("team").then(Commands.argument("team", StringArgumentType.word())
                                        .executes(ctx -> summon(ctx, IntegerArgumentType.getInteger(ctx, "count"),
                                                StringArgumentType.getString(ctx, "team")))))))
                .then(Commands.literal("summonmob").requires(CloneCommand::mayControl)
                        .then(Commands.argument("id", net.minecraft.commands.arguments.ResourceLocationArgument.id())
                                .suggests(net.minecraft.commands.synchronization.SuggestionProviders.SUMMONABLE_ENTITIES)
                                .executes(ctx -> summonMobs(ctx, 1))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                        .executes(ctx -> summonMobs(ctx, IntegerArgumentType.getInteger(ctx, "count"))))))
                .then(Commands.literal("mobfaction").requires(CloneCommand::mayControl)
                        .then(Commands.literal("hostile").executes(ctx -> setMobFaction(ctx, false)))
                        .then(Commands.literal("peace").executes(ctx -> setMobFaction(ctx, true))))
                .then(Commands.literal("plainstands").requires(CloneCommand::mayControl)
                        .executes(ctx -> setPlainStands(ctx, !CloneManager.plainStands()))
                        .then(Commands.literal("on").executes(ctx -> setPlainStands(ctx, true)))
                        .then(Commands.literal("off").executes(ctx -> setPlainStands(ctx, false))))
                .then(Commands.literal("equipfaction").requires(CloneCommand::mayControl)
                        .then(Commands.literal("hostile").executes(ctx -> setEquipFaction(ctx, false)))
                        .then(Commands.literal("peace").executes(ctx -> setEquipFaction(ctx, true))))
                .then(Commands.literal("summonedmobs").requires(CloneCommand::mayControl)
                        .executes(ctx -> setSummonedMobs(ctx, !manager(ctx).summonedAsClone()))
                        .then(Commands.literal("on").executes(ctx -> setSummonedMobs(ctx, true)))
                        .then(Commands.literal("off").executes(ctx -> setSummonedMobs(ctx, false))))
                .then(Commands.literal("battleroyale").requires(CloneCommand::mayControl)
                        .executes(ctx -> setBattleRoyale(ctx, !manager(ctx).isBattleRoyale()))
                        .then(Commands.literal("on").executes(ctx -> setBattleRoyale(ctx, true)))
                        .then(Commands.literal("off").executes(ctx -> setBattleRoyale(ctx, false))))
                .then(Commands.literal("breeding").requires(CloneCommand::mayControl)
                        .executes(ctx -> setBreeding(ctx, !manager(ctx).isBreeding()))
                        .then(Commands.literal("on").executes(ctx -> setBreeding(ctx, true)))
                        .then(Commands.literal("off").executes(ctx -> setBreeding(ctx, false))))
                .then(Commands.literal("chat").requires(CloneCommand::mayControl)
                        .executes(ctx -> setChat(ctx, !CloneManager.chatShown()))
                        .then(Commands.literal("on").executes(ctx -> setChat(ctx, true)))
                        .then(Commands.literal("off").executes(ctx -> setChat(ctx, false))))
                .then(Commands.literal("coward").requires(CloneCommand::mayControl)
                        .executes(ctx -> setCoward(ctx, !CloneManager.coward()))
                        .then(Commands.literal("on").executes(ctx -> setCoward(ctx, true)))
                        .then(Commands.literal("off").executes(ctx -> setCoward(ctx, false))))
                .then(Commands.literal("flee").requires(CloneCommand::mayControl)
                        .executes(ctx -> setFlee(ctx, !CloneManager.fleeAllowed()))
                        .then(Commands.literal("on").executes(ctx -> setFlee(ctx, true)))
                        .then(Commands.literal("off").executes(ctx -> setFlee(ctx, false))))
                .then(Commands.literal("giveitems").requires(CloneCommand::mayControl).executes(CloneCommand::giveItems))
                .then(Commands.literal("list").executes(CloneCommand::list))
                .then(Commands.literal("debug")
                        .executes(ctx -> debug(ctx, null))
                        .then(Commands.argument("name", StringArgumentType.word()).suggests(NAMES).executes(ctx -> debug(ctx, StringArgumentType.getString(ctx, "name")))))
                .then(achievements("achievement"))
                .then(achievements("achivement"))
                .then(Commands.literal("item")
                        .executes(ctx -> showItems(ctx, 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1)).executes(ctx -> showItems(ctx, IntegerArgumentType.getInteger(ctx, "page")))))
                .then(Commands.literal("deaths").executes(CloneCommand::deaths))
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
                .then(Commands.literal("inventory")
                        .then(Commands.argument("name", StringArgumentType.word()).suggests(NAMES).executes(CloneCommand::inventory)))
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
                .then(Commands.literal("mobtype").requires(CloneCommand::mayControl)
                        .then(Commands.literal("list").executes(ctx -> {
                            ctx.getSource().sendSuccess(() -> Component.literal("Clone mob types: " + manager(ctx).cloneMobTypes()), false);
                            return 1;
                        }))
                        .then(Commands.literal("add").then(Commands.argument("id", net.minecraft.commands.arguments.ResourceLocationArgument.id())
                                .suggests(net.minecraft.commands.synchronization.SuggestionProviders.SUMMONABLE_ENTITIES)
                                .executes(ctx -> setMobType(ctx, true))))
                        .then(Commands.literal("remove").then(Commands.argument("id", net.minecraft.commands.arguments.ResourceLocationArgument.id())
                                .suggests(net.minecraft.commands.synchronization.SuggestionProviders.SUMMONABLE_ENTITIES)
                                .executes(ctx -> setMobType(ctx, false)))))
                .then(Commands.literal("save").requires(CloneCommand::mayControl).executes(ctx -> {
                    manager(ctx).saveAll();
                    ctx.getSource().sendSuccess(() -> Component.translatable("rlclones.cmd.saved"), false);
                    return 1;
                })));
    }

    /** /rlclone mobtype add|remove: natural spawns of this entity type become (or stop being) clone mobs. */
    private static int setMobType(CommandContext<CommandSourceStack> ctx, boolean on) {
        var id = net.minecraft.commands.arguments.ResourceLocationArgument.getId(ctx, "id");
        if (!net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.containsKey(id)) {
            ctx.getSource().sendFailure(Component.literal("Unknown entity type " + id));
            return 0;
        }
        manager(ctx).setCloneMobType(id.toString(), on);
        ctx.getSource().sendSuccess(() -> Component.literal((on ? "Natural " : "No longer natural ") + id + (on ? " spawns become clone mobs" : " spawns as clone mobs")), true);
        return 1;
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

    private static int setBreeding(CommandContext<CommandSourceStack> ctx, boolean on) {
        manager(ctx).setBreeding(on);
        ctx.getSource().sendSuccess(() -> on ? Component.translatable("rlclones.msg.breeding_on")
                : Component.translatable("rlclones.msg.breeding_off"), true);
        return 1;
    }

    private static int setChat(CommandContext<CommandSourceStack> ctx, boolean on) {
        manager(ctx).setChatShown(on);
        ctx.getSource().sendSuccess(() -> Component.translatable(on ? "rlclones.msg.chat_on" : "rlclones.msg.chat_off"), true);
        return 1;
    }

    private static int setFlee(CommandContext<CommandSourceStack> ctx, boolean on) {
        manager(ctx).setFleeAllowed(on);
        ctx.getSource().sendSuccess(() -> Component.translatable(on ? "rlclones.msg.flee_on" : "rlclones.msg.flee_off"), true);
        return 1;
    }

    private static int setPlainStands(CommandContext<CommandSourceStack> ctx, boolean on) {
        manager(ctx).setPlainStands(on);
        ctx.getSource().sendSuccess(() -> Component.translatable(on ? "rlclones.msg.plainstands_on" : "rlclones.msg.plainstands_off"), true);
        return 1;
    }

    private static int setEquipFaction(CommandContext<CommandSourceStack> ctx, boolean peace) {
        manager(ctx).setEquipPeace(peace);
        ctx.getSource().sendSuccess(() -> Component.translatable(peace ? "rlclones.msg.equippeace_on" : "rlclones.msg.equippeace_off"), true);
        return 1;
    }

    /** /rlclone debug [name]: the state of that clone (default: the nearest one) as chat lines, to paste into a bug report. */
    private static int debug(CommandContext<CommandSourceStack> ctx, String name) {
        CommandSourceStack src = ctx.getSource();
        ClonePlayer c = name != null ? manager(ctx).byName(name) : null;
        if (name == null) {
            double best = Double.MAX_VALUE;
            for (ClonePlayer k : manager(ctx).clones()) {
                double d = k.distanceToSqr(src.getPosition());
                if (d < best && k.getCommandSenderWorld() == src.getLevel()) {
                    best = d;
                    c = k;
                }
            }
        }
        if (c == null || c.controller() == null) {
            src.sendFailure(Component.translatable("rlclones.cmd.unknown", name == null ? "(nearest)" : name));
            return 0;
        }
        for (String line : c.controller().debugReport()) {
            src.sendSuccess(() -> Component.literal(line), false);
            RLClones.LOGGER.info("CLONE-DEBUG {}", line);
        }
        return 1;
    }

    private static final int PER_PAGE = 15;

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> achievements(String word) {
        return Commands.literal(word)
                .executes(ctx -> showAchievements(ctx, 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1)).executes(ctx -> showAchievements(ctx, IntegerArgumentType.getInteger(ctx, "page"))));
    }

    /** /rlclone achievement [page]: every advancement the clones (mobs and equipment too) have earned, with who earned it first. */
    private static int showAchievements(CommandContext<CommandSourceStack> ctx, int page) {
        var server = ctx.getSource().getServer();
        com.rlclones.clone.CloneRecords.catchUp(server);
        var book = new java.util.ArrayList<>(manager(ctx).advancementBook().entrySet());
        return showBook(ctx, "Achievements of the clones", book, page, e -> {
            var adv = server.getAdvancements().getAdvancement(new net.minecraft.resources.ResourceLocation(e.getKey()));
            return adv != null && adv.getDisplay() != null ? adv.getDisplay().getTitle() : Component.literal(e.getKey());
        }, "achievement");
    }

    /** /rlclone item [page]: every kind of item a clone has ever held, with who first had it. */
    private static int showItems(CommandContext<CommandSourceStack> ctx, int page) {
        var book = new java.util.ArrayList<>(manager(ctx).itemBook().entrySet());
        return showBook(ctx, "Items the clones have got", book, page, e -> {
            var item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(new net.minecraft.resources.ResourceLocation(e.getKey()));
            return item == null ? Component.literal(e.getKey()) : new net.minecraft.world.item.ItemStack(item).getHoverName();
        }, "item");
    }

    private static int showBook(CommandContext<CommandSourceStack> ctx, String title, java.util.List<java.util.Map.Entry<String, String>> book, int page,
                                java.util.function.Function<java.util.Map.Entry<String, String>, Component> name, String word) {
        int pages = Math.max(1, (book.size() + PER_PAGE - 1) / PER_PAGE);
        int p = Math.min(page, pages);
        ctx.getSource().sendSuccess(() -> Component.literal("== " + title + ": " + book.size() + " (page " + p + "/" + pages + ", /rlclone " + word + " <page>) ==").withStyle(ChatFormatting.AQUA), false);
        for (int i = (p - 1) * PER_PAGE; i < Math.min(book.size(), p * PER_PAGE); i++) {
            var e = book.get(i);
            ctx.getSource().sendSuccess(() -> Component.literal(" - ").append(name.apply(e)).append(Component.literal("  [" + e.getKey() + "] by " + e.getValue()).withStyle(ChatFormatting.GRAY)), false);
        }
        return book.size();
    }

    private static int setMobFaction(CommandContext<CommandSourceStack> ctx, boolean peace) {
        manager(ctx).setMobPeace(peace);
        ctx.getSource().sendSuccess(() -> Component.translatable(peace ? "rlclones.msg.mobpeace_on" : "rlclones.msg.mobpeace_off"), true);
        return 1;
    }

    private static int setSummonedMobs(CommandContext<CommandSourceStack> ctx, boolean on) {
        manager(ctx).setSummonedAsClone(on);
        ctx.getSource().sendSuccess(() -> Component.translatable(on ? "rlclones.msg.summoned_on" : "rlclones.msg.summoned_off"), true);
        return 1;
    }

    private static int setCoward(CommandContext<CommandSourceStack> ctx, boolean on) {
        manager(ctx).setCoward(on);
        ctx.getSource().sendSuccess(() -> Component.translatable(on ? "rlclones.msg.coward_on" : "rlclones.msg.coward_off"), true);
        return 1;
    }

    /** Every clone gets one random usable item (weapon, armour, pearls, mod items...), no two clones the same. */
    private static int giveItems(CommandContext<CommandSourceStack> ctx) {
        CloneManager m = manager(ctx);
        List<ClonePlayer> clones = new ArrayList<>(m.clones());
        List<net.minecraft.world.item.ItemStack> given = com.rlclones.clone.UsableItems.giveEach(clones, ctx.getSource().getLevel().getRandom());
        for (int i = 0; i < given.size(); i++) {
            ClonePlayer c = clones.get(i);
            net.minecraft.world.item.ItemStack s = given.get(i);
            ctx.getSource().sendSuccess(() -> Component.translatable("rlclones.cmd.gave_item", c.getGameProfile().getName(), s.getHoverName(), s.getCount()), false);
        }
        int n = given.size();
        ctx.getSource().sendSuccess(() -> Component.translatable("rlclones.cmd.gave_items", n), true);
        return n;
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

    /** /rlclone summonmob <entity> [count]: clone mobs (a body of that type with a clone soul), in a ring round the caller; no count cap. */
    private static int summonMobs(CommandContext<CommandSourceStack> ctx, int count) {
        CommandSourceStack src = ctx.getSource();
        var id = net.minecraft.commands.arguments.ResourceLocationArgument.getId(ctx, "id");
        var type = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getValue(id);
        if (type == null || !net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.containsKey(id)) {
            src.sendFailure(Component.literal("Unknown entity type " + id));
            return 0;
        }
        Vec3 base = src.getPosition();
        double radius = Math.max(2, count / (Math.PI * 2)); // (about one block of ring per mob)
        int made = 0;
        for (int i = 0; i < count; i++) {
            double angle = i * (Math.PI * 2 / count);
            Vec3 pos = count == 1 ? base : base.add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
            if (com.rlclones.clone.CloneMobs.summon(src.getPlayer(), src.getLevel(), type, pos) == null) {
                break;
            }
            made++;
        }
        int total = made;
        if (total == 0) {
            src.sendFailure(Component.translatable("rlclones.msg.limit", Config.cloneLimitLabel()));
        } else {
            src.sendSuccess(() -> Component.literal("Summoned " + total + " clone mob(s) of " + id), true);
        }
        return total;
    }

    private static int deaths(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        java.util.List<java.util.Map.Entry<String, Integer>> rows = new java.util.ArrayList<>(com.rlclones.ai.CloneController.DEATHS.entrySet());
        rows.sort((a, b) -> b.getValue() - a.getValue());
        int total = rows.stream().mapToInt(java.util.Map.Entry::getValue).sum();
        src.sendSuccess(() -> Component.literal("Deaths: " + total + " (cause, by whom, during which action)").withStyle(ChatFormatting.AQUA), false);
        for (var e : rows) {
            src.sendSuccess(() -> Component.literal(" " + e.getValue() + " x " + e.getKey()), false);
        }
        return rows.size();
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

    /** R-29: what a clone carries, as chat lines (hotbar / main / armor / offhand), identical items summed. */
    private static int inventory(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        ClonePlayer c = manager(ctx).byName(name);
        if (c == null) {
            ctx.getSource().sendFailure(Component.translatable("rlclones.cmd.unknown", name));
            return 0;
        }
        java.util.Map<String, Integer> sum = new java.util.LinkedHashMap<>();
        java.util.List<net.minecraft.world.item.ItemStack> all = new java.util.ArrayList<>(c.getInventory().items);
        all.addAll(c.getInventory().armor);
        all.addAll(c.getInventory().offhand);
        for (net.minecraft.world.item.ItemStack st : all) {
            if (!st.isEmpty()) {
                sum.merge(st.getHoverName().getString(), st.getCount(), Integer::sum);
            }
        }
        ctx.getSource().sendSuccess(() -> Component.literal(name + " (" + sum.size() + "): "
                + (sum.isEmpty() ? "-" : sum.entrySet().stream().map(e -> e.getKey() + " x" + e.getValue()).collect(java.util.stream.Collectors.joining(", ")))), false);
        return sum.size();
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
