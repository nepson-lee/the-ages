package com.theages.server.world;

import com.theages.protocol.v1.TextChannel;
import com.theages.server.world.item.EquipSlot;
import com.theages.server.world.item.Inventory;
import com.theages.server.world.item.InventoryEntry;
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
        Map.entry("drink", "use"));

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
            String.format("【%s】 等級 %d%n生命 %d/%d　攻擊 %d　防禦 %d%n經驗 %d/%d",
                actor.name(), actor.level(), actor.hp(), actor.maxHp(), actor.attack(), actor.defense(),
                actor.exp(), PlayerEntity.expToNext(actor.level()))));

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
            zone.sendText(actor, TextChannel.TEXT_CHANNEL_SYSTEM, "什麼？（輸入 help 查看指令）");
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
                return t.name() + keyword + (list.size() > 1 ? " ×" + list.size() : "");
            })
            .collect(Collectors.joining("、"));
        String loot = zone.groundItems().stream()
            .map(GroundItem::displayName)
            .collect(Collectors.joining("、"));
        String text = "【" + def.name() + "】\n" + def.description()
            + (others.isEmpty() ? "" : "\n這裡有：" + others)
            + (creatures.isEmpty() ? "" : "\n生物：" + creatures)
            + (loot.isEmpty() ? "" : "\n地上有：" + loot);
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

    /** 依名稱或代稱找最近的活著的 NPC。 */
    private static Optional<NpcEntity> findNpc(Zone zone, PlayerEntity actor, String query) {
        return zone.entities().stream()
            .filter(e -> e instanceof NpcEntity)
            .map(e -> (NpcEntity) e)
            .filter(n -> !n.isDead() && n.template().matches(query))
            .min(Comparator.comparingDouble(actor::distanceTo));
    }
}
