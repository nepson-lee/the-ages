package com.theages.server.world;

import com.theages.protocol.v1.TextChannel;
import java.util.LinkedHashMap;
import java.util.Map;
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
    private final Map<String, String> aliases = Map.of("l", "look", "'", "say");

    Commands() {
        register("help", "列出所有指令", (zone, actor, args) -> zone.sendText(actor, TextChannel.TEXT_CHANNEL_SYSTEM,
            handlers.entrySet().stream()
                .map(e -> String.format("  %-6s %s", e.getKey(), e.getValue().help()))
                .collect(Collectors.joining("\n", "可用指令：\n", ""))));

        register("look", "觀察四周", (zone, actor, args) -> {
            ZoneDefinition def = zone.definition();
            String others = zone.players().stream()
                .filter(p -> p != actor)
                .map(PlayerEntity::name)
                .collect(Collectors.joining("、"));
            String text = "【" + def.name() + "】\n" + def.description()
                + (others.isEmpty() ? "" : "\n這裡有：" + others);
            zone.sendText(actor, TextChannel.TEXT_CHANNEL_ROOM, text);
        });

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
                .map(PlayerEntity::name)
                .collect(Collectors.joining("、"))));
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
}
