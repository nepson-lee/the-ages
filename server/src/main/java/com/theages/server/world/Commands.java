package com.theages.server.world;

import com.theages.protocol.v1.TextChannel;
import com.theages.server.world.item.EquipSlot;
import com.theages.server.world.item.Inventory;
import com.theages.server.world.item.InventoryEntry;
import com.theages.server.world.party.PartyService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * MUD 風格文字指令。每個指令在區域的 tick 執行緒上執行，可直接讀寫區域狀態。
 * 新增指令：在建構子裡 register 即可。
 */
final class Commands {

    @FunctionalInterface
    interface Handler {
        void run(Zone zone, PlayerEntity actor, String args);
    }

    private record Entry(String help, Handler handler) {
    }

    private final Map<String, Entry> handlers = new LinkedHashMap<>();
    private final Map<String, String> aliases = Map.ofEntries(
        Map.entry("l", "look"),
        Map.entry("'", "say"),
        Map.entry("k", "kill"),
        Map.entry("sc", "score"),
        Map.entry("hp", "score"),
        Map.entry("i", "inventory"),
        Map.entry("eq", "equipment"),
        Map.entry("take", "get"),
        Map.entry("wield", "wear"),
        Map.entry("eat", "use"),
        Map.entry("drink", "use"),
        Map.entry("shop", "list"),
        Map.entry("enter", "go"),
        Map.entry("pt", "psay"),
        Map.entry("team", "party"),
        Map.entry("q", "quest"),
        Map.entry("quests", "quest"),
        Map.entry("ask", "talk"));

    Commands() {
        register("help", "列出所有指令", (zone, actor, args) -> zone.sendText(actor, TextChannel.TEXT_CHANNEL_SYSTEM,
            handlers.entrySet().stream()
                .map(e -> String.format("  %-6s %s", e.getKey(), e.getValue().help()))
                .collect(Collectors.joining("\n", "可用指令：\n", ""))));

        register("look", "觀察四周，或 look <目標> 觀察某個生物", Commands::look);

        register("say", "對附近的人說話：say <內容>", (zone, actor, args) -> {
            if (args.isEmpty()) {
                zone.sendText(actor, TextChannel.TEXT_CHANNEL_SYSTEM, "你想說什麼？");
                return;
            }
            zone.sendText(actor, TextChannel.TEXT_CHANNEL_SAY, "你說：「" + args + "」");
            zone.broadcastText(TextChannel.TEXT_CHANNEL_SAY, actor.name() + "說：「" + args + "」", actor);
        });

        register("who", "列出這個區域的玩家", (zone, actor, args) -> zone.sendText(actor, TextChannel.TEXT_CHANNEL_SYSTEM,
            "目前在" + zone.definition().name() + "的玩家（" + zone.players().size() + "）：" + zone.players().stream()
                .map(p -> p.name() + "(Lv" + p.level() + ")")
                .collect(Collectors.joining("、"))));

        register("kill", "攻擊生物：kill <名稱或代稱>，例如 kill rabbit", (zone, actor, args) -> {
            if (args.isEmpty()) {
                zone.sendText(actor, TextChannel.TEXT_CHANNEL_SYSTEM, "你想攻擊誰？");
                return;
            }
            findNpc(zone, actor, args).ifPresentOrElse(
                npc -> zone.startAttack(actor, npc),
                () -> zone.sendText(actor, TextChannel.TEXT_CHANNEL_SYSTEM, "這裡沒有「" + args + "」。"));
        });

        register("flee", "停止攻擊", (zone, actor, args) -> zone.stopAttack(actor));

        register("score", "查看自己的狀態", (zone, actor, args) -> zone.sendText(actor, TextChannel.TEXT_CHANNEL_SYSTEM,
            String.format("【%s】 等級 %d%n生命 %d/%d　攻擊 %d　防禦 %d%n經驗 %d/%d　銅錢 %d",
                actor.name(), actor.level(), actor.hp(), actor.maxHp(), actor.attack(), actor.defense(),
                actor.exp(), PlayerEntity.expToNext(actor.level()), actor.gold())));

        register("inventory", "查看背包（i）", (zone, actor, args) -> {
            List<InventoryEntry> entries = actor.inventory().entries();
            if (entries.isEmpty()) {
                zone.sendText(actor, TextChannel.TEXT_CHANNEL_SYSTEM, "你身上什麼都沒有。");
                return;
            }
            zone.sendText(actor, TextChannel.TEXT_CHANNEL_SYSTEM, entries.stream()
                .map(e -> "  #" + e.uid() + " " + e.displayName() + (e.equipped() ? "【裝備中】" : ""))
                .collect(Collectors.joining("\n", "背包（" + entries.size() + "/" + Inventory.CAPACITY + "）：\n", "")));
        });

        register("equipment", "查看身上的裝備（eq）", (zone, actor, args) -> {
            StringBuilder sb = new StringBuilder("你身上的裝備：");
            for (EquipSlot slot : EquipSlot.values()) {
                sb.append("\n  ").append(slot.label()).append("：").append(actor.inventory().equipped(slot)
                    .map(e -> e.template().name() + "（" + e.template().statSummary() + "）")
                    .orElse("（無）"));
            }
            zone.sendText(actor, TextChannel.TEXT_CHANNEL_SYSTEM, sb.toString());
        });

        register("get", "撿起地上的東西：get <物品>、get all", Commands::get);

        register("drop", "丟下物品：drop <物品>", (zone, actor, args) ->
            withItem(zone, actor, args, false, entry -> zone.drop(actor, entry)));

        register("wear", "穿戴裝備：wear <物品>", (zone, actor, args) ->
            withItem(zone, actor, args, false, entry -> zone.equip(actor, entry)));

        register("remove", "卸下裝備：remove <物品>", (zone, actor, args) ->
            withItem(zone, actor, args, true, entry -> zone.unequip(actor, entry)));

        register("use", "使用消耗品：use <物品>（eat、drink 也可以）", (zone, actor, args) ->
            withItem(zone, actor, args, false, entry -> zone.use(actor, entry)));

        register("list", "查看附近商人賣什麼", (zone, actor, args) -> {
            Optional<NpcEntity> merchant = args.isEmpty()
                ? zone.merchantInSight(actor)
                : findNpc(zone, actor, args).filter(n -> n.template().isMerchant());
            merchant.ifPresentOrElse(m -> zone.openShop(actor, m),
                () -> zone.sendText(actor, TextChannel.TEXT_CHANNEL_SYSTEM, "這附近沒有商人。"));
        });

        register("buy", "向商人買東西：buy <物品> [數量]", (zone, actor, args) -> {
            Quantity q = Quantity.parse(args, false);
            if (q == null) {
                zone.sendText(actor, TextChannel.TEXT_CHANNEL_SYSTEM, "你想買什麼？（buy <物品> [數量]，數量 1～" + Quantity.MAX + "）");
                return;
            }
            zone.buy(actor, q.target(), q.amount());
        });

        register("sell", "把東西賣給商人：sell <物品> [數量|all]", (zone, actor, args) -> {
            Quantity q = Quantity.parse(args, true);
            if (q == null) {
                zone.sendText(actor, TextChannel.TEXT_CHANNEL_SYSTEM, "你想賣什麼？（sell <物品> [數量|all]）");
                return;
            }
            withItem(zone, actor, q.target(), false, entry -> zone.sell(actor, entry, q.amount()));
        });

        register("value", "請附近商人估價：value <物品>", (zone, actor, args) ->
            withItem(zone, actor, args, false, entry -> zone.appraise(actor, entry)));

        register("party", "組隊：party（查看）、party leave、party kick <名字>", (zone, actor, args) -> {
            PartyService parties = zone.parties();
            String[] parts = args.split("\\s+", 2);
            switch (parts[0].toLowerCase()) {
                case "" , "list" -> zone.sendText(actor, TextChannel.TEXT_CHANNEL_PARTY, parties.describe(actor.characterId()));
                case "leave" -> parties.leave(actor.characterId());
                case "kick" -> {
                    if (parts.length < 2) {
                        zone.sendText(actor, TextChannel.TEXT_CHANNEL_SYSTEM, "你要把誰踢出隊伍？");
                    } else {
                        parties.kick(actor.characterId(), parts[1].strip());
                    }
                }
                case "invite" -> {
                    if (parts.length < 2) {
                        zone.sendText(actor, TextChannel.TEXT_CHANNEL_SYSTEM, "你要邀請誰？");
                    } else {
                        parties.invite(actor.characterId(), parts[1].strip());
                    }
                }
                case "accept" -> parties.accept(actor.characterId());
                default -> zone.sendText(actor, TextChannel.TEXT_CHANNEL_SYSTEM,
                    "用法：party、party invite <名字>、party accept、party leave、party kick <名字>");
            }
        });

        register("invite", "邀請別人加入隊伍：invite <名字>（對方在其他區域也可以）", (zone, actor, args) -> {
            if (args.isEmpty()) {
                zone.sendText(actor, TextChannel.TEXT_CHANNEL_SYSTEM, "你要邀請誰？");
            } else {
                zone.parties().invite(actor.characterId(), args);
            }
        });

        register("accept", "接受組隊邀請", (zone, actor, args) -> zone.parties().accept(actor.characterId()));

        register("psay", "隊伍頻道說話：pt <內容>", (zone, actor, args) -> {
            if (args.isEmpty()) {
                zone.sendText(actor, TextChannel.TEXT_CHANNEL_SYSTEM, "你想對隊友說什麼？");
            } else {
                zone.parties().chat(actor.characterId(), args);
            }
        });

        register("talk", "和 NPC 說話：talk <名字>（接任務、交任務）", (zone, actor, args) -> {
            if (args.isEmpty()) {
                zone.sendText(actor, TextChannel.TEXT_CHANNEL_SYSTEM, "你想和誰說話？");
                return;
            }
            findNpc(zone, actor, args).ifPresentOrElse(npc -> zone.talk(actor, npc),
                () -> zone.sendText(actor, TextChannel.TEXT_CHANNEL_SYSTEM, "這裡沒有「" + args + "」。"));
        });

        register("quest", "任務：quest（日誌）、quest accept/complete/abandon <任務>", (zone, actor, args) -> {
            String[] parts = args.split("\\s+", 2);
            String target = parts.length > 1 ? parts[1].strip() : "";
            switch (parts[0].toLowerCase()) {
                case "", "list", "log" -> zone.sendText(actor, TextChannel.TEXT_CHANNEL_SYSTEM, zone.describeQuests(actor));
                case "accept" -> withQuest(zone, actor, target, q -> zone.acceptQuest(actor, q));
                case "complete", "turnin" -> withQuest(zone, actor, target, q -> zone.completeQuest(actor, q));
                case "abandon" -> withQuest(zone, actor, target, q -> zone.abandonQuest(actor, q));
                default -> zone.sendText(actor, TextChannel.TEXT_CHANNEL_SYSTEM,
                    "用法：quest、quest accept <任務>、quest complete <任務>、quest abandon <任務>");
            }
        });

        register("go", "前往其他區域：go <出口>；也可以直接打方向，例如 north、n", (zone, actor, args) -> {
            if (args.isEmpty()) {
                zone.sendText(actor, TextChannel.TEXT_CHANNEL_SYSTEM, exitList(zone));
                return;
            }
            findPortal(zone, args).ifPresentOrElse(portal -> zone.requestTravel(actor, portal),
                () -> zone.sendText(actor, TextChannel.TEXT_CHANNEL_SYSTEM, "這裡沒有往「" + args + "」的路。"));
        });
    }

    /** 「物品 [數量]」的解析結果；all 以 {@link Integer#MAX_VALUE} 表示。 */
    private record Quantity(String target, int amount) {

        static final int MAX = 99;

        static Quantity parse(String args, boolean allowAll) {
            if (args.isEmpty()) {
                return null;
            }
            int space = args.lastIndexOf(' ');
            if (space < 0) {
                return new Quantity(args, 1);
            }
            String last = args.substring(space + 1);
            String target = args.substring(0, space).strip();
            if (allowAll && last.equalsIgnoreCase("all")) {
                return new Quantity(target, Integer.MAX_VALUE);
            }
            try {
                int n = Integer.parseInt(last);
                return n >= 1 && n <= MAX ? new Quantity(target, n) : null;
            } catch (NumberFormatException e) {
                return new Quantity(args, 1); // 名稱本身含空白
            }
        }
    }

    private void register(String verb, String help, Handler handler) {
        handlers.put(verb, new Entry(help, handler));
    }

    void execute(Zone zone, PlayerEntity actor, String rawText) {
        String text = rawText.strip();
        if (text.isEmpty()) {
            return;
        }
        int space = text.indexOf(' ');
        String verb = (space < 0 ? text : text.substring(0, space)).toLowerCase();
        String args = space < 0 ? "" : text.substring(space + 1).strip();
        verb = aliases.getOrDefault(verb, verb);

        Entry entry = handlers.get(verb);
        if (entry == null) {
            // MUD 傳統：出口的方向詞本身就是指令（north、n……）
            Optional<Portal> exit = args.isEmpty() ? findPortal(zone, verb) : Optional.empty();
            if (exit.isPresent()) {
                zone.requestTravel(actor, exit.get());
            } else {
                zone.sendText(actor, TextChannel.TEXT_CHANNEL_SYSTEM, "什麼？（輸入 help 查看指令）");
            }
            return;
        }
        entry.handler().run(zone, actor, args);
    }

    private static void look(Zone zone, PlayerEntity actor, String args) {
        if (!args.isEmpty()) {
            Optional<NpcEntity> npc = findNpc(zone, actor, args);
            if (npc.isPresent()) {
                NpcEntity n = npc.get();
                zone.sendText(actor, TextChannel.TEXT_CHANNEL_ROOM, String.format("%s（Lv%d）%n%s%n生命 %d/%d",
                    n.name(), n.level(), n.template().description(), n.hp(), n.maxHp()));
                return;
            }
            Optional<com.theages.server.world.item.ItemTemplate> item = actor.inventory().find(args, false)
                .map(InventoryEntry::template)
                .or(() -> findGroundItem(zone, actor, args).map(GroundItem::template));
            item.ifPresentOrElse(
                t -> zone.sendText(actor, TextChannel.TEXT_CHANNEL_ROOM, t.name()
                    + (t.isEquipment() ? "（" + t.slot().label() + "）" : "") + "\n" + t.description()
                    + (t.statSummary().isEmpty() ? "" : "\n" + t.statSummary())),
                () -> zone.sendText(actor, TextChannel.TEXT_CHANNEL_SYSTEM, "這裡沒有「" + args + "」。"));
            return;
        }
        ZoneDefinition def = zone.definition();
        String others = zone.players().stream()
            .filter(p -> p != actor)
            .map(PlayerEntity::name)
            .collect(Collectors.joining("、"));
        // 同名 NPC 合併顯示，例如「野兔(rabbit) ×3」
        String creatures = zone.entities().stream()
            .filter(e -> e instanceof NpcEntity)
            .map(e -> (NpcEntity) e)
            .collect(Collectors.groupingBy(n -> n.template().id(), LinkedHashMap::new, Collectors.toList()))
            .values().stream()
            .map(list -> {
                NpcTemplate t = list.get(0).template();
                String keyword = t.keywords().isEmpty() ? "" : "(" + t.keywords().get(0) + ")";
                return t.name() + keyword + (t.isMerchant() ? "［商人］" : "") + (list.size() > 1 ? " ×" + list.size() : "");
            })
            .collect(Collectors.joining("、"));
        String loot = zone.groundItems().stream()
            .map(GroundItem::displayName)
            .collect(Collectors.joining("、"));
        String text = "【" + def.name() + "】\n" + def.description()
            + (others.isEmpty() ? "" : "\n這裡有：" + others)
            + (creatures.isEmpty() ? "" : "\n生物：" + creatures)
            + (loot.isEmpty() ? "" : "\n地上有：" + loot)
            + "\n" + exitList(zone);
        zone.sendText(actor, TextChannel.TEXT_CHANNEL_ROOM, text);
    }

    private static void get(Zone zone, PlayerEntity actor, String args) {
        if (args.isEmpty()) {
            zone.sendText(actor, TextChannel.TEXT_CHANNEL_SYSTEM, "你想撿什麼？");
            return;
        }
        if (args.equalsIgnoreCase("all")) {
            List<GroundItem> nearby = new ArrayList<>(zone.groundItems().stream()
                .filter(g -> actor.distanceTo(g.x(), g.z()) <= Zone.PICKUP_RANGE)
                .toList());
            if (nearby.isEmpty()) {
                zone.sendText(actor, TextChannel.TEXT_CHANNEL_SYSTEM, "附近沒有東西可以撿。");
            }
            for (GroundItem g : nearby) {
                zone.pickUp(actor, g);
            }
            return;
        }
        findGroundItem(zone, actor, args).ifPresentOrElse(
            g -> zone.requestPickUp(actor, g),
            () -> zone.sendText(actor, TextChannel.TEXT_CHANNEL_SYSTEM, "地上沒有「" + args + "」。"));
    }

    private static String exitList(Zone zone) {
        if (zone.portals().isEmpty()) {
            return "這裡沒有明顯的出口。";
        }
        return zone.portals().stream()
            .map(p -> p.exit().name() + (p.exit().keywords().isEmpty() ? "" : "(" + p.exit().keywords().get(0) + ")"))
            .collect(Collectors.joining("、", "出口：", ""));
    }

    /** 出口：{@code #<id>}、名稱或代稱。 */
    private static Optional<Portal> findPortal(Zone zone, String query) {
        if (query.startsWith("#")) {
            try {
                return Optional.ofNullable(zone.portal(Integer.parseInt(query.substring(1))));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
        return zone.portals().stream().filter(p -> p.exit().matches(query)).findFirst();
    }

    private static void withQuest(Zone zone, PlayerEntity actor, String query,
                                  java.util.function.Consumer<String> action) {
        if (query.isEmpty()) {
            zone.sendText(actor, TextChannel.TEXT_CHANNEL_SYSTEM, "哪一個任務？");
        } else {
            action.accept(query);
        }
    }

    /** 找背包裡的物品後執行；找不到就提示。 */
    private static void withItem(Zone zone, PlayerEntity actor, String args, boolean preferEquipped,
                                 java.util.function.Consumer<InventoryEntry> action) {
        if (args.isEmpty()) {
            zone.sendText(actor, TextChannel.TEXT_CHANNEL_SYSTEM, "你要對什麼東西這麼做？");
            return;
        }
        actor.inventory().find(args, preferEquipped).ifPresentOrElse(action,
            () -> zone.sendText(actor, TextChannel.TEXT_CHANNEL_SYSTEM, "你身上沒有「" + args + "」。"));
    }

    /** 地上的物品：{@code #<id>}，或依名稱、代稱找最近的。 */
    private static Optional<GroundItem> findGroundItem(Zone zone, PlayerEntity actor, String query) {
        if (query.startsWith("#")) {
            try {
                return Optional.ofNullable(zone.groundItem(Integer.parseInt(query.substring(1))));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
        return zone.groundItems().stream()
            .filter(g -> g.template().matches(query))
            .min(Comparator.comparingDouble(g -> actor.distanceTo(g.x(), g.z())));
    }

    /** 依 {@code #<id>}、名稱或代稱找最近的活著的 NPC。 */
    private static Optional<NpcEntity> findNpc(Zone zone, PlayerEntity actor, String query) {
        if (query.startsWith("#")) {
            try {
                return Optional.ofNullable(zone.entity(Integer.parseInt(query.substring(1))))
                    .filter(e -> e instanceof NpcEntity && !e.isDead())
                    .map(e -> (NpcEntity) e);
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
        return zone.entities().stream()
            .filter(e -> e instanceof NpcEntity)
            .map(e -> (NpcEntity) e)
            .filter(n -> !n.isDead() && n.template().matches(query))
            .min(Comparator.comparingDouble(actor::distanceTo));
    }
}
