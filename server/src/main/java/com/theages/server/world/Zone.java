package com.theages.server.world;

import com.theages.protocol.v1.CombatEvent;
import com.theages.protocol.v1.EntityLeft;
import com.theages.protocol.v1.EntityState;
import com.theages.protocol.v1.InventoryItem;
import com.theages.protocol.v1.QuestDialog;
import com.theages.protocol.v1.QuestEntry;
import com.theages.protocol.v1.QuestMarker;
import com.theages.protocol.v1.QuestMarkerKind;
import com.theages.protocol.v1.QuestMarkers;
import com.theages.protocol.v1.QuestObjective;
import com.theages.protocol.v1.QuestOffer;
import com.theages.protocol.v1.QuestOfferStatus;
import com.theages.protocol.v1.SellQuote;
import com.theages.protocol.v1.ServerMessage;
import com.theages.protocol.v1.ShopClosed;
import com.theages.protocol.v1.ShopOffer;
import com.theages.protocol.v1.ShopView;
import com.theages.protocol.v1.TextChannel;
import com.theages.protocol.v1.TextOutput;
import com.theages.protocol.v1.Welcome;
import com.theages.protocol.v1.WorldSnapshot;
import com.theages.server.world.item.EquipSlot;
import com.theages.server.world.item.Inventory;
import com.theages.server.world.item.InventoryEntry;
import com.theages.server.world.item.ItemTemplate;
import com.theages.server.world.item.ItemType;
import com.theages.server.world.item.LootEntry;
import com.theages.server.world.item.ShopDefinition;
import com.theages.server.world.party.PartyService;
import com.theages.server.world.quest.QuestDefinition;
import com.theages.server.world.quest.QuestLog;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一個區域 = 一條執行緒跑固定頻率的 tick 迴圈。
 *
 * <p>區域狀態（實體、玩家等欄位）只在 tick 執行緒上讀寫，所以不需要鎖。
 * 其他執行緒只能透過 {@link #enqueue(ZoneEvent)} 把事件丟進來，下一個 tick 開始時處理。
 * tick 內禁止阻塞 I/O，存檔交給 {@link CharacterStore} 非同步處理。
 */
public final class Zone {

    private static final Logger log = LoggerFactory.getLogger(Zone.class);
    /** 脫離戰鬥多久後開始回血。 */
    private static final int REGEN_DELAY_SECONDS = 5;
    private static final int REGEN_INTERVAL_SECONDS = 3;
    private static final int AUTOSAVE_INTERVAL_SECONDS = 60;
    /** 撿東西的距離（公尺）。 */
    static final float PICKUP_RANGE = 2f;
    /** 戰利品只有擊殺者能撿的秒數。 */
    static final int LOOT_OWNER_SECONDS = 30;
    /** 地上物品消失的秒數。 */
    static final int GROUND_ITEM_SECONDS = 120;
    private static final int USE_COOLDOWN_SECONDS = 2;

    private final ZoneDefinition definition;
    private final int tickRate;
    private final IntSupplier entityIds;
    private final CharacterStore store;
    private final PartyService parties;
    private final WorldContent content;
    private final Map<String, ItemTemplate> itemTemplates;
    private final RandomGenerator random;
    private final Commands commands = new Commands();
    private final Combat combat;
    private final NpcBrain npcBrain;
    private final Queue<ZoneEvent> inbox = new ConcurrentLinkedQueue<>();

    /** 目前在場（活著）的所有實體，依加入順序。 */
    private final Map<Integer, Entity> entities = new LinkedHashMap<>();
    /** 所有 NPC，包含正在等待重生的。 */
    private final List<NpcEntity> npcs = new ArrayList<>();
    private final Map<PlayerConnection, PlayerEntity> byConnection = new IdentityHashMap<>();
    private final Map<Long, PlayerEntity> byCharacter = new HashMap<>();
    private final Map<Integer, GroundItem> groundItems = new LinkedHashMap<>();
    private final Map<Integer, Portal> portals = new LinkedHashMap<>();
    /** 依 id 找其他區域（換區用）。 */
    private final Function<String, Zone> zones;
    /** 分享經驗的距離（公尺）：離被殺的 NPC 這麼近的隊員才分得到。 */
    static final float PARTY_SHARE_RANGE = 30f;
    /** 等級比隊上最高者低超過這麼多，就分不到經驗（防止帶練）。 */
    static final int PARTY_LEVEL_GAP = 5;
    private long tick;

    private ScheduledExecutorService executor;

    public Zone(ZoneDefinition definition, WorldContent content, int tickRate, IntSupplier entityIds,
                RandomGenerator random, ZoneServices services) {
        this.definition = definition;
        this.zones = services.zones();
        this.parties = services.parties();
        this.tickRate = tickRate;
        this.entityIds = entityIds;
        this.store = services.store();
        this.content = content;
        this.itemTemplates = content.items();
        this.random = random;
        this.combat = new Combat(this, random, tickRate);
        this.npcBrain = new NpcBrain(this, random, tickRate);
        for (ZoneDefinition.NpcSpawn spawn : definition.npcs()) {
            NpcTemplate template = content.npcs().get(spawn.template());
            for (int i = 0; i < spawn.countOrOne(); i++) {
                // 同一組的 NPC 散開在半徑 2 公尺內
                double angle = random.nextDouble() * Math.PI * 2;
                double r = spawn.countOrOne() == 1 ? 0 : 2 * Math.sqrt(random.nextDouble());
                NpcEntity npc = new NpcEntity(entityIds.getAsInt(), template,
                    definition.clamp((float) (spawn.x() + Math.cos(angle) * r)),
                    definition.clamp((float) (spawn.z() + Math.sin(angle) * r)));
                npcs.add(npc);
                entities.put(npc.id(), npc);
            }
        }
        for (ZoneDefinition.Exit exit : definition.exits()) {
            Portal portal = new Portal(entityIds.getAsInt(), exit);
            portals.put(portal.id(), portal);
        }
    }

    public ZoneDefinition definition() {
        return definition;
    }

    public void enqueue(ZoneEvent event) {
        inbox.add(event);
    }

    public void start() {
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "zone-" + definition.id());
            t.setDaemon(true);
            return t;
        });
        long periodMicros = 1_000_000L / tickRate;
        executor.scheduleAtFixedRate(this::safeTick, 0, periodMicros, TimeUnit.MICROSECONDS);
        log.info("區域 {} 啟動，tick rate {}/s，NPC {} 隻", definition.id(), tickRate, npcs.size());
    }

    /** 停止 tick 並把區域內所有玩家存檔。 */
    public void stop() {
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // tick 執行緒已停止，這裡可以安全存取狀態
        byConnection.values().forEach(this::persist);
        log.info("區域 {} 已停止，存檔 {} 名玩家", definition.id(), byConnection.size());
    }

    private void safeTick() {
        try {
            tick();
        } catch (Throwable t) {
            // 例外若逸出，ScheduledExecutorService 會默默停止後續 tick
            log.error("區域 {} tick {} 發生錯誤", definition.id(), tick, t);
        }
    }

    /** 執行一個 tick。測試可直接呼叫以取得確定性的結果。 */
    void tick() {
        ZoneEvent event;
        while ((event = inbox.poll()) != null) {
            handle(event);
        }
        // 連線已斷但 Leave 沒送到這裡（例如換區途中斷線）的玩家
        for (PlayerEntity p : new ArrayList<>(byConnection.values())) {
            if (!p.connection().isOpen()) {
                onLeave(p);
            }
        }

        npcBrain.update(tick);
        combat.update(tick);
        updatePickups();
        updateShops();
        updateExits();

        float dt = 1f / tickRate;
        for (Entity e : entities.values()) {
            e.step(dt);
        }
        if (tick % (REGEN_INTERVAL_SECONDS * tickRate) == 0) {
            regenerate();
        }
        if (tick % tickRate == 0) {
            for (PlayerEntity p : byConnection.values()) {
                parties.publishStatus(p.characterId(),
                    new PartyService.MemberStatus(p.level(), p.hp(), p.maxHp(), definition.name()));
            }
        }
        if (tick > 0 && tick % (AUTOSAVE_INTERVAL_SECONDS * tickRate) == 0) {
            byConnection.values().forEach(this::persist);
        }

        despawnGroundItems();

        List<EntityState> changed = new ArrayList<>();
        for (Entity e : entities.values()) {
            if (e.consumeDirty()) {
                changed.add(e.toState());
            }
        }
        for (GroundItem item : groundItems.values()) {
            if (item.consumeUnannounced()) {
                changed.add(item.toState());
            }
        }
        if (!changed.isEmpty()) {
            broadcast(ServerMessage.newBuilder()
                .setSnapshot(WorldSnapshot.newBuilder().setTick(tick).setFull(false).addAllEntities(changed))
                .build());
        }
        for (PlayerEntity p : byConnection.values()) {
            if (p.consumeStatsDirty()) {
                p.connection().send(ServerMessage.newBuilder().setSelfStats(p.toSelfStats()).build());
            }
            boolean inventoryChanged = p.inventory().consumeDirty();
            if (inventoryChanged) {
                p.quests().markDirty(); // 收集目標的進度看背包
            }
            syncQuests(p, tick % tickRate == 0);
            if (inventoryChanged) {
                p.connection().send(inventoryMessage(p.inventory()));
                if (p.openShop != null) {
                    sendShop(p, p.openShop); // 收購清單跟著背包變
                }
            }
        }
        tick++;
    }

    private void handle(ZoneEvent event) {
        if (event instanceof ZoneEvent.Join join) {
            onJoin(join);
            return;
        }
        PlayerEntity player = byConnection.get(event.connection());
        if (player == null) {
            return; // 連線已被取代或已離開
        }
        if (event instanceof ZoneEvent.Leave) {
            onLeave(player);
        } else if (event instanceof ZoneEvent.Move move) {
            player.setCombatTarget(null); // 移動 = 停止攻擊（NPC 仍會追你）
            player.pendingPickup = null;
            player.pendingShop = null;
            player.pendingExit = null;
            player.moveToward(definition.clamp(move.targetX()), definition.clamp(move.targetZ()));
        } else if (event instanceof ZoneEvent.Attack attack) {
            player.pendingPickup = null;
            player.pendingShop = null;
            player.pendingExit = null;
            startAttack(player, entities.get(attack.targetId()));
        } else if (event instanceof ZoneEvent.CommandText cmd) {
            commands.execute(this, player, cmd.text());
        }
    }

    private void onJoin(ZoneEvent.Join join) {
        PlayerEntity player = byCharacter.get(join.characterId());
        if (player != null) {
            // 同一角色重複登入：沿用記憶體中的最新狀態，踢掉舊連線
            PlayerConnection old = player.connection();
            byConnection.remove(old);
            old.close("你的角色已從其他地方登入");
            player.replaceConnection(join.connection());
        } else {
            Inventory inventory = Inventory.fromRecords(join.items(), itemTemplates,
                id -> log.warn("角色 {} 身上的物品 {} 已不存在於內容檔，略過", join.characterId(), id));
            QuestLog quests = QuestLog.fromRecords(join.quests(), content.quests(),
                id -> log.warn("角色 {} 的任務 {} 已不存在於內容檔，略過", join.characterId(), id));
            player = new PlayerEntity(entityIds.getAsInt(), join.characterId(), join.name(), join.connection(),
                definition.clamp(join.x()), definition.clamp(join.z()), join.level(), join.exp(), join.hp(), join.gold(), inventory, quests);
            byCharacter.put(player.characterId(), player);
            entities.put(player.id(), player);
            broadcastText(TextChannel.TEXT_CHANNEL_ROOM, player.name() + " 走了過來。", player);
        }
        byConnection.put(join.connection(), player);
        parties.online(player.characterId(), player.name(), player.connection());

        player.connection().send(ServerMessage.newBuilder()
            .setWelcome(Welcome.newBuilder()
                .setSelfId(player.id())
                .setZoneId(definition.id())
                .setZoneName(definition.name())
                .setTickRate(tickRate)
                .setZoneSize(definition.size()))
            .build());
        WorldSnapshot.Builder full = WorldSnapshot.newBuilder().setTick(tick).setFull(true);
        entities.values().forEach(e -> full.addEntities(e.toState()));
        groundItems.values().forEach(g -> full.addEntities(g.toState()));
        portals.values().forEach(portal -> full.addEntities(portal.toState()));
        player.connection().send(ServerMessage.newBuilder().setSnapshot(full).build());
        commands.execute(this, player, "look");
    }

    private void onLeave(PlayerEntity player) {
        removePlayer(player);
        persist(player);
        parties.offline(player.characterId(), player.connection());
        broadcastText(TextChannel.TEXT_CHANNEL_ROOM, player.name() + " 離開了。", null);
    }

    private void removePlayer(PlayerEntity player) {
        byConnection.remove(player.connection());
        byCharacter.remove(player.characterId());
        entities.remove(player.id());
        clearTargetsOn(player);
        broadcast(ServerMessage.newBuilder().setEntityLeft(EntityLeft.newBuilder().setId(player.id())).build());
    }

    // ===== 換區 =====

    /** 站在出口多近才能通過（公尺）。 */
    static final float EXIT_RANGE = 2.5f;

    Collection<Portal> portals() {
        return Collections.unmodifiableCollection(portals.values());
    }

    Portal portal(int id) {
        return portals.get(id);
    }

    /** 前往出口；太遠就先走過去。 */
    void requestTravel(PlayerEntity p, Portal portal) {
        if (p.combatTarget() != null || isTargeted(p)) {
            sendText(p, TextChannel.TEXT_CHANNEL_SYSTEM, "你正被纏住，脫不了身！");
        } else if (p.distanceTo(portal.exit().x(), portal.exit().z()) <= EXIT_RANGE) {
            travel(p, portal.exit());
        } else {
            p.pendingPickup = null;
            p.pendingShop = null;
            p.pendingExit = portal;
            p.moveToward(portal.exit().x(), portal.exit().z());
        }
    }

    private boolean isTargeted(PlayerEntity p) {
        return entities.values().stream().anyMatch(e -> e.combatTarget() == p);
    }

    private void updateExits() {
        for (PlayerEntity p : new ArrayList<>(byConnection.values())) {
            Portal portal = p.pendingExit;
            if (portal == null) {
                continue;
            }
            if (p.combatTarget() != null || isTargeted(p)) {
                p.pendingExit = null;
                sendText(p, TextChannel.TEXT_CHANNEL_SYSTEM, "你正被纏住，脫不了身！");
            } else if (p.distanceTo(portal.exit().x(), portal.exit().z()) <= EXIT_RANGE) {
                travel(p, portal.exit());
            } else {
                p.moveToward(portal.exit().x(), portal.exit().z());
            }
        }
    }

    /**
     * 把玩家交給另一個區域。目標區域在自己的執行緒上處理 Join，
     * 所以這裡只傳記憶體中的狀態（背包以存檔格式傳遞），不直接碰對方的資料。
     */
    private void travel(PlayerEntity p, ZoneDefinition.Exit exit) {
        Zone target = zones.apply(exit.to());
        p.pendingExit = null;
        if (p.openShop != null) {
            p.openShop = null;
            p.connection().send(ServerMessage.newBuilder().setShopClosed(ShopClosed.getDefaultInstance()).build());
        }
        removePlayer(p);
        broadcastText(TextChannel.TEXT_CHANNEL_ROOM, p.name() + " 往" + exit.name() + "離開了。", null);
        sendText(p, TextChannel.TEXT_CHANNEL_SYSTEM, "你沿著" + exit.name() + "走去……");

        CharacterSnapshot s = p.snapshot(target.definition().id());
        CharacterSnapshot arrived = new CharacterSnapshot(s.characterId(), s.zoneId(), exit.toX(), exit.toZ(),
            s.level(), s.exp(), s.hp(), s.gold(), s.items(), s.quests());
        store.saveAsync(arrived); // 換區當下就存檔：伺服器若在途中當掉，玩家會出現在目的地
        // 先排入 Join 再切換路由：之後的訊息都會排在 Join 後面
        target.enqueue(new ZoneEvent.Join(p.connection(), s.characterId(), p.name(), exit.toX(), exit.toZ(),
            s.level(), s.exp(), s.hp(), s.gold(), s.items(), s.quests()));
        p.connection().attachZone(target);
    }

    private void persist(PlayerEntity p) {
        store.saveAsync(p.snapshot(definition.id()));
    }

    private void regenerate() {
        long quietSince = tick - (long) REGEN_DELAY_SECONDS * tickRate;
        for (Entity e : entities.values()) {
            if (e.hp() < e.maxHp() && e.combatTarget() == null && e.lastCombatTick < quietSince) {
                e.heal(Math.max(1, e.maxHp() / 20));
            }
        }
    }

    /** 誰在打 victim 就停手；NPC 停手後走回出生點。 */
    private void clearTargetsOn(Entity victim) {
        for (Entity e : entities.values()) {
            if (e.combatTarget() == victim) {
                if (e instanceof NpcEntity npc) {
                    npc.goHome();
                } else {
                    e.setCombatTarget(null);
                }
            }
        }
    }

    // ===== 給 Combat / NpcBrain / Commands 使用（都在 tick 執行緒上呼叫） =====

    long currentTick() {
        return tick;
    }

    Collection<Entity> entities() {
        return Collections.unmodifiableCollection(entities.values());
    }

    Collection<PlayerEntity> players() {
        return Collections.unmodifiableCollection(byConnection.values());
    }

    List<NpcEntity> npcs() {
        return Collections.unmodifiableList(npcs);
    }

    Entity entity(int id) {
        return entities.get(id);
    }

    boolean contains(Entity e) {
        return entities.get(e.id()) == e;
    }

    void startAttack(PlayerEntity player, Entity target) {
        if (!(target instanceof NpcEntity npc) || target.isDead()) {
            sendText(player, TextChannel.TEXT_CHANNEL_SYSTEM, "這裡沒有這個目標。");
            return;
        }
        if (npc.template().isMerchant()) {
            sendText(player, TextChannel.TEXT_CHANNEL_SAY, npc.name() + "笑著搖搖頭：「年輕人，別在店門口動手動腳的。」");
            return;
        }
        if (npc.template().isPeaceful()) {
            sendText(player, TextChannel.TEXT_CHANNEL_SAY, npc.name() + "皺起眉頭：「有話好好說，動什麼手？」");
            return;
        }
        if (player.combatTarget() == target) {
            return;
        }
        player.setCombatTarget(target);
        player.nextAttackTick = Math.max(player.nextAttackTick, tick);
        sendText(player, TextChannel.TEXT_CHANNEL_SYSTEM, "你開始攻擊" + target.name() + "！");
    }

    void stopAttack(PlayerEntity player) {
        if (player.combatTarget() == null) {
            sendText(player, TextChannel.TEXT_CHANNEL_SYSTEM, "你現在沒有在戰鬥。");
            return;
        }
        player.setCombatTarget(null);
        sendText(player, TextChannel.TEXT_CHANNEL_SYSTEM, "你停止了攻擊。（對方可能還會追著你）");
    }

    void onKilled(Entity victim, Entity killer) {
        clearTargetsOn(victim);
        victim.setCombatTarget(null);

        if (victim instanceof NpcEntity npc) {
            entities.remove(npc.id());
            npc.respawnAtTick = tick + (long) npc.template().respawnSeconds() * tickRate;
            broadcast(ServerMessage.newBuilder().setEntityLeft(EntityLeft.newBuilder().setId(npc.id())).build());
            if (killer instanceof PlayerEntity p) {
                rewardKill(p, npc);
                dropLoot(npc, p);
            }
        } else if (victim instanceof PlayerEntity p) {
            int lost = p.loseExpOnDeath();
            sendText(p, TextChannel.TEXT_CHANNEL_SYSTEM, "你被" + killer.name() + "殺死了……"
                + (lost > 0 ? "失去 " + lost + " 點經驗。" : ""));
            broadcastText(TextChannel.TEXT_CHANNEL_ROOM, p.name() + " 被" + killer.name() + "殺死了。", p);
            p.teleport(definition.spawn().x(), definition.spawn().z());
            p.setHp(p.maxHp());
            sendText(p, TextChannel.TEXT_CHANNEL_SYSTEM, "你在" + definition.name() + "的出生點醒了過來。");
        }
    }

    /** 經驗值依等級差調整：每高一級 +20%、每低一級 −20%，介於 10%～200%。 */
    static int killExp(NpcEntity npc, PlayerEntity p) {
        double multiplier = Math.max(0.1, Math.min(2.0, 1 + (npc.level() - p.level()) * 0.2));
        return Math.max(1, (int) Math.round(npc.template().exp() * multiplier));
    }

    private void rewardKill(PlayerEntity killer, NpcEntity npc) {
        List<PlayerEntity> group = sharingGroup(killer, npc);
        NpcTemplate t = npc.template();
        int gold = t.goldMax() <= 0 ? 0 : t.goldMin() + random.nextInt(t.goldMax() - t.goldMin() + 1);
        broadcastText(TextChannel.TEXT_CHANNEL_ROOM, killer.name() + " 殺死了" + npc.name() + "。", killer);
        creditKill(group, npc);

        if (group.size() == 1) {
            int exp = killExp(npc, killer);
            sendText(killer, TextChannel.TEXT_CHANNEL_SYSTEM, "你殺死了" + npc.name() + "！獲得 " + exp + " 點經驗。");
            announceLevelUp(killer, killer.gainExp(exp));
            if (gold > 0) {
                killer.addGold(gold);
                sendText(killer, TextChannel.TEXT_CHANNEL_SYSTEM, "你從" + npc.name() + "身上搜出了 " + gold + " 枚銅錢。");
            }
            return;
        }

        sendText(killer, TextChannel.TEXT_CHANNEL_SYSTEM, "你殺死了" + npc.name() + "！");
        int topLevel = group.stream().mapToInt(PlayerEntity::level).max().orElseThrow();
        for (PlayerEntity m : group) {
            int exp = partyExp(npc, m, group.size(), topLevel);
            if (exp == 0) {
                sendText(m, TextChannel.TEXT_CHANNEL_PARTY, "你和隊友的等級差距太大，沒有分到經驗。");
            } else {
                sendText(m, TextChannel.TEXT_CHANNEL_PARTY, "隊伍分配：你獲得 " + exp + " 點經驗。");
                announceLevelUp(m, m.gainExp(exp));
            }
        }
        if (gold > 0) {
            // 平分，除不盡的零頭歸擊殺者
            int share = gold / group.size();
            int remainder = gold - share * group.size();
            for (PlayerEntity m : group) {
                int amount = share + (m == killer ? remainder : 0);
                if (amount > 0) {
                    m.addGold(amount);
                    sendText(m, TextChannel.TEXT_CHANNEL_PARTY, "隊伍分配：你分到 " + amount + " 枚銅錢。");
                }
            }
        }
    }

    /**
     * 每人分到的經驗 = 自己單獨擊殺應得的經驗 × 組隊加成（每多一人 +10%）÷ 人數。
     * 等級比隊上最高者低超過 {@link #PARTY_LEVEL_GAP} 級的人分不到（回傳 0）。
     */
    static int partyExp(NpcEntity npc, PlayerEntity member, int groupSize, int topLevel) {
        if (topLevel - member.level() > PARTY_LEVEL_GAP) {
            return 0;
        }
        double bonus = 1 + 0.1 * (groupSize - 1);
        return Math.max(1, (int) Math.round(killExp(npc, member) * bonus / groupSize));
    }

    /** 擊殺者加上同區域、活著、離屍體夠近的隊友。 */
    private List<PlayerEntity> sharingGroup(PlayerEntity killer, NpcEntity npc) {
        List<PlayerEntity> group = new ArrayList<>();
        group.add(killer);
        parties.partyOf(killer.characterId()).ifPresent(party -> {
            for (long id : party.members()) {
                PlayerEntity m = byCharacter.get(id);
                if (m != null && m != killer && !m.isDead() && m.distanceTo(npc) <= PARTY_SHARE_RANGE) {
                    group.add(m);
                }
            }
        });
        return group;
    }

    private void announceLevelUp(PlayerEntity p, int levels) {
        if (levels > 0) {
            sendText(p, TextChannel.TEXT_CHANNEL_SYSTEM, "★ 恭喜！你的等級提升到 " + p.level() + " 級了！");
            broadcastText(TextChannel.TEXT_CHANNEL_ROOM, p.name() + " 的等級提升到 " + p.level() + " 級了！", p);
        }
    }

    PartyService parties() {
        return parties;
    }

    void respawn(NpcEntity npc) {
        npc.respawn();
        entities.put(npc.id(), npc);
    }

    // ===== 對話與任務 =====

    /** 找 NPC 說話；太遠就先走過去。商人會順便打開商店。 */
    void talk(PlayerEntity p, NpcEntity npc) {
        if (!npc.template().isPeaceful()) {
            sendText(p, TextChannel.TEXT_CHANNEL_SYSTEM, npc.name() + "只會對你齜牙咧嘴。");
        } else if (p.combatTarget() != null) {
            sendText(p, TextChannel.TEXT_CHANNEL_SYSTEM, "你正在戰鬥，沒空聊天！");
        } else if (p.distanceTo(npc) <= TRADE_RANGE) {
            p.pendingShop = null;
            List<QuestDefinition> quests = content.questsAt(npc.template());
            if (!quests.isEmpty() || !npc.template().isMerchant()) {
                p.connection().send(dialogMessage(p, npc));
                String greeting = npc.template().greeting();
                sendText(p, TextChannel.TEXT_CHANNEL_SAY, npc.name() + "說：「"
                    + (greeting == null ? "你好啊，年輕人。" : greeting) + "」");
            }
            if (npc.template().isMerchant()) {
                openShop(p, npc);
            }
        } else {
            p.pendingPickup = null;
            p.pendingShop = npc;
            p.pendingTalk = true;
            p.moveToward(npc.x(), npc.z());
        }
    }

    /** 交談距離內的和平 NPC。 */
    private List<NpcEntity> peacefulNear(PlayerEntity p) {
        return entities.values().stream()
            .filter(e -> e instanceof NpcEntity n && n.template().isPeaceful() && p.distanceTo(n) <= TRADE_RANGE)
            .map(e -> (NpcEntity) e)
            .toList();
    }

    void acceptQuest(PlayerEntity p, String query) {
        for (NpcEntity npc : peacefulNear(p)) {
            for (QuestDefinition q : content.questsAt(npc.template())) {
                if (!q.giver().equals(npc.template().id()) || !q.matches(query)) {
                    continue;
                }
                Optional<String> why = p.quests().whyCannotAccept(q, p.level());
                if (why.isPresent()) {
                    sendText(p, TextChannel.TEXT_CHANNEL_SYSTEM, why.get());
                    return;
                }
                p.quests().accept(q);
                sendText(p, TextChannel.TEXT_CHANNEL_SYSTEM, "【任務】你接下了「" + q.name() + "」。");
                if (q.acceptText() != null) {
                    sendText(p, TextChannel.TEXT_CHANNEL_SAY, npc.name() + "說：「" + q.acceptText() + "」");
                }
                p.connection().send(dialogMessage(p, npc));
                return;
            }
        }
        sendText(p, TextChannel.TEXT_CHANNEL_SYSTEM, "附近沒有人給「" + query + "」這個任務。");
    }

    void completeQuest(PlayerEntity p, String query) {
        Optional<QuestLog.ActiveQuest> found = findActiveQuest(p, query);
        if (found.isEmpty()) {
            sendText(p, TextChannel.TEXT_CHANNEL_SYSTEM, "你沒有進行中的任務「" + query + "」。");
            return;
        }
        QuestDefinition q = found.get().definition();
        Optional<NpcEntity> npc = peacefulNear(p).stream()
            .filter(n -> n.template().id().equals(q.turnIn()))
            .findFirst();
        if (npc.isEmpty()) {
            sendText(p, TextChannel.TEXT_CHANNEL_SYSTEM, "「" + q.name() + "」要交給" + npcName(q.turnIn()) + "。");
            return;
        }
        if (!p.quests().isReady(found.get(), p.inventory())) {
            sendText(p, TextChannel.TEXT_CHANNEL_SYSTEM, "「" + q.name() + "」還沒完成。");
            return;
        }
        if (!rewardsFit(p, q)) {
            sendText(p, TextChannel.TEXT_CHANNEL_SYSTEM, "你身上的東西太多了，拿不下任務獎勵。");
            return;
        }
        for (QuestDefinition.Objective o : q.objectives()) {
            if (!o.isKill()) {
                p.inventory().removeCount(o.target(), o.required());
            }
        }
        p.quests().complete(q.id());
        sendText(p, TextChannel.TEXT_CHANNEL_SYSTEM, "【任務】你完成了「" + q.name() + "」！獲得：" + rewardsText(q) + "。");
        if (q.completeText() != null) {
            sendText(p, TextChannel.TEXT_CHANNEL_SAY, npc.get().name() + "說：「" + q.completeText() + "」");
        }
        QuestDefinition.Rewards r = q.rewards();
        for (QuestDefinition.ItemReward item : r.items()) {
            p.inventory().add(itemTemplates.get(item.item()), item.countOrOne());
        }
        if (r.gold() > 0) {
            p.addGold(r.gold());
        }
        if (r.exp() > 0) {
            announceLevelUp(p, p.gainExp(r.exp()));
        }
        p.connection().send(dialogMessage(p, npc.get()));
    }

    void abandonQuest(PlayerEntity p, String query) {
        findActiveQuest(p, query).ifPresentOrElse(a -> {
            p.quests().abandon(a.definition().id());
            sendText(p, TextChannel.TEXT_CHANNEL_SYSTEM, "【任務】你放棄了「" + a.definition().name() + "」。");
        }, () -> sendText(p, TextChannel.TEXT_CHANNEL_SYSTEM, "你沒有進行中的任務「" + query + "」。"));
    }

    /** 給 quest 指令列出任務日誌。 */
    String describeQuests(PlayerEntity p) {
        if (p.quests().active().isEmpty()) {
            return "你目前沒有進行中的任務。（找頭上有「!」的 NPC 說話：talk <名字>）";
        }
        StringBuilder sb = new StringBuilder("任務日誌：");
        for (QuestLog.ActiveQuest a : p.quests().active()) {
            QuestDefinition q = a.definition();
            boolean ready = p.quests().isReady(a, p.inventory());
            sb.append("\n  【").append(q.name()).append("】").append(ready ? "（完成！回報給" + npcName(q.turnIn()) + "）" : "");
            for (int i = 0; i < q.objectives().size(); i++) {
                sb.append("\n    ").append(objectiveText(q.objectives().get(i)))
                    .append(" ").append(p.quests().progress(a, i, p.inventory())).append("/").append(q.objectives().get(i).required());
            }
            if (q.objectives().isEmpty()) {
                sb.append("\n    去找").append(npcName(q.turnIn()));
            }
        }
        return sb.toString();
    }

    private Optional<QuestLog.ActiveQuest> findActiveQuest(PlayerEntity p, String query) {
        return p.quests().active().stream().filter(a -> a.definition().matches(query)).findFirst();
    }

    /** 擊殺時替參與分配的每個人累計任務進度。 */
    private void creditKill(List<PlayerEntity> group, NpcEntity npc) {
        for (PlayerEntity m : group) {
            if (!m.quests().onKill(npc.template().id())) {
                continue;
            }
            for (QuestLog.ActiveQuest a : m.quests().active()) {
                List<QuestDefinition.Objective> objectives = a.definition().objectives();
                for (int i = 0; i < objectives.size(); i++) {
                    QuestDefinition.Objective o = objectives.get(i);
                    if (o.isKill() && o.target().equals(npc.template().id())) {
                        sendText(m, TextChannel.TEXT_CHANNEL_SYSTEM, "【任務】" + a.definition().name() + "："
                            + objectiveText(o) + " " + m.quests().progress(a, i, m.inventory()) + "/" + o.required());
                    }
                }
            }
        }
    }

    /** 模擬交任務後的背包，確認獎勵放得下。 */
    private boolean rewardsFit(PlayerEntity p, QuestDefinition q) {
        Inventory copy = Inventory.fromRecords(p.inventory().toRecords(), itemTemplates, id -> { });
        for (QuestDefinition.Objective o : q.objectives()) {
            if (!o.isKill()) {
                copy.removeCount(o.target(), o.required());
            }
        }
        for (QuestDefinition.ItemReward item : q.rewards().items()) {
            ItemTemplate t = itemTemplates.get(item.item());
            if (!copy.canAdd(t, item.countOrOne())) {
                return false;
            }
            copy.add(t, item.countOrOne());
        }
        return true;
    }

    private String npcName(String templateId) {
        NpcTemplate t = content.npcs().get(templateId);
        return t == null ? templateId : t.name();
    }

    private String objectiveText(QuestDefinition.Objective o) {
        return o.isKill() ? "擊殺" + npcName(o.target()) : "收集" + itemTemplates.get(o.target()).name();
    }

    private String rewardsText(QuestDefinition q) {
        QuestDefinition.Rewards r = q.rewards();
        List<String> parts = new ArrayList<>();
        if (r.exp() > 0) {
            parts.add("經驗 " + r.exp());
        }
        if (r.gold() > 0) {
            parts.add("銅錢 " + r.gold());
        }
        for (QuestDefinition.ItemReward item : r.items()) {
            parts.add(itemTemplates.get(item.item()).name() + (item.countOrOne() > 1 ? " ×" + item.countOrOne() : ""));
        }
        return parts.isEmpty() ? "（無）" : String.join("、", parts);
    }

    private ServerMessage questLogMessage(PlayerEntity p) {
        com.theages.protocol.v1.QuestLog.Builder msg = com.theages.protocol.v1.QuestLog.newBuilder();
        for (QuestLog.ActiveQuest a : p.quests().active()) {
            QuestDefinition q = a.definition();
            QuestEntry.Builder entry = QuestEntry.newBuilder()
                .setId(q.id())
                .setName(q.name())
                .setDescription(q.description() == null ? "" : q.description())
                .setReady(p.quests().isReady(a, p.inventory()))
                .setTurnInName(npcName(q.turnIn()))
                .setRewards(rewardsText(q));
            for (int i = 0; i < q.objectives().size(); i++) {
                QuestDefinition.Objective o = q.objectives().get(i);
                entry.addObjectives(QuestObjective.newBuilder()
                    .setText(objectiveText(o))
                    .setCurrent(p.quests().progress(a, i, p.inventory()))
                    .setRequired(o.required()));
            }
            msg.addQuests(entry);
        }
        return ServerMessage.newBuilder().setQuestLog(msg).build();
    }

    private ServerMessage dialogMessage(PlayerEntity p, NpcEntity npc) {
        QuestDialog.Builder dialog = QuestDialog.newBuilder()
            .setNpcId(npc.id())
            .setNpcName(npc.name())
            .setGreeting(npc.template().greeting() == null ? "" : npc.template().greeting());
        String npcId = npc.template().id();
        for (QuestDefinition q : content.questsAt(npc.template())) {
            Optional<QuestLog.ActiveQuest> active = p.quests().active(q.id());
            QuestOfferStatus status;
            if (active.isPresent()) {
                boolean here = q.turnIn().equals(npcId);
                status = here && p.quests().isReady(active.get(), p.inventory())
                    ? QuestOfferStatus.QUEST_OFFER_STATUS_READY : QuestOfferStatus.QUEST_OFFER_STATUS_IN_PROGRESS;
            } else if (q.giver().equals(npcId) && p.quests().isAvailable(q, p.level())) {
                status = QuestOfferStatus.QUEST_OFFER_STATUS_AVAILABLE;
            } else {
                continue; // 已完成、等級不夠、前置未完成：不顯示
            }
            dialog.addOffers(QuestOffer.newBuilder()
                .setId(q.id())
                .setName(q.name())
                .setDescription(q.description() == null ? "" : q.description())
                .setStatus(status)
                .setObjectives(q.objectives().isEmpty() ? "去找" + npcName(q.turnIn())
                    : q.objectives().stream().map(o -> objectiveText(o) + " ×" + o.required())
                        .collect(java.util.stream.Collectors.joining("、")))
                .setRewards(rewardsText(q)));
        }
        return ServerMessage.newBuilder().setQuestDialog(dialog).build();
    }

    /** 這位玩家看到的 NPC 頭頂標記：可以交（?）優先於可以接（!）。 */
    private List<QuestMarker> markersFor(PlayerEntity p) {
        List<QuestMarker> markers = new ArrayList<>();
        for (Entity e : entities.values()) {
            if (!(e instanceof NpcEntity npc) || !npc.template().isPeaceful()) {
                continue;
            }
            String npcId = npc.template().id();
            boolean ready = false;
            boolean available = false;
            for (QuestDefinition q : content.questsAt(npc.template())) {
                Optional<QuestLog.ActiveQuest> active = p.quests().active(q.id());
                if (active.isPresent()) {
                    ready |= q.turnIn().equals(npcId) && p.quests().isReady(active.get(), p.inventory());
                } else {
                    available |= q.giver().equals(npcId) && p.quests().isAvailable(q, p.level());
                }
            }
            if (ready || available) {
                markers.add(QuestMarker.newBuilder()
                    .setEntityId(npc.id())
                    .setKind(ready ? QuestMarkerKind.QUEST_MARKER_KIND_READY : QuestMarkerKind.QUEST_MARKER_KIND_AVAILABLE)
                    .build());
            }
        }
        return markers;
    }

    private void syncQuests(PlayerEntity p, boolean force) {
        if (p.quests().consumeDirty()) {
            p.connection().send(questLogMessage(p));
            force = true;
        }
        if (!force) {
            return;
        }
        List<QuestMarker> markers = markersFor(p);
        if (!markers.equals(p.lastQuestMarkers)) {
            p.lastQuestMarkers = markers;
            p.connection().send(ServerMessage.newBuilder()
                .setQuestMarkers(QuestMarkers.newBuilder().addAllMarkers(markers)).build());
        }
    }

    // ===== 商店 =====

    /** 與商人交易的距離（公尺）。 */
    static final float TRADE_RANGE = 4f;

    /** 最近的、在交易距離內的商人。 */
    Optional<NpcEntity> merchantNear(PlayerEntity p) {
        return entities.values().stream()
            .filter(e -> e instanceof NpcEntity n && n.template().isMerchant())
            .map(e -> (NpcEntity) e)
            .filter(n -> p.distanceTo(n) <= TRADE_RANGE)
            .min(Comparator.comparingDouble(p::distanceTo));
    }

    /** 不帶參數的 list 會走向這個距離內最近的商人。 */
    static final float SHOP_SEARCH_RANGE = 15f;

    /** 在 {@link #SHOP_SEARCH_RANGE} 內最近的商人（不限交易距離）。 */
    Optional<NpcEntity> merchantInSight(PlayerEntity p) {
        return entities.values().stream()
            .filter(e -> e instanceof NpcEntity n && n.template().isMerchant())
            .map(e -> (NpcEntity) e)
            .filter(n -> p.distanceTo(n) <= SHOP_SEARCH_RANGE)
            .min(Comparator.comparingDouble(p::distanceTo));
    }

    /** 打開商店；太遠就先走過去。 */
    void openShop(PlayerEntity p, NpcEntity merchant) {
        if (p.combatTarget() != null) {
            sendText(p, TextChannel.TEXT_CHANNEL_SYSTEM, "你正在戰鬥，沒空做生意！");
        } else if (p.distanceTo(merchant) <= TRADE_RANGE) {
            p.pendingShop = null;
            p.openShop = merchant;
            sendShop(p, merchant);
            sendText(p, TextChannel.TEXT_CHANNEL_ROOM, describeShop(p, merchant));
        } else {
            p.pendingPickup = null;
            p.pendingShop = merchant;
            p.pendingTalk = false;
            p.moveToward(merchant.x(), merchant.z());
        }
    }

    void buy(PlayerEntity p, String query, int quantity) {
        Optional<NpcEntity> near = merchantNear(p);
        if (near.isEmpty()) {
            sendText(p, TextChannel.TEXT_CHANNEL_SYSTEM, "這附近沒有商人。");
            return;
        }
        NpcEntity merchant = near.get();
        ShopDefinition shop = content.shopOf(merchant.template());
        Optional<ShopDefinition.ShopListing> listing = shop.sells().stream()
            .filter(l -> content.items().get(l.item()).matches(query))
            .findFirst();
        if (listing.isEmpty()) {
            sendText(p, TextChannel.TEXT_CHANNEL_SAY, merchant.name() + "說：「抱歉，小店沒有賣「" + query + "」。」");
            return;
        }
        ItemTemplate item = content.items().get(listing.get().item());
        long total = (long) listing.get().priceFor(item) * quantity;
        if (total > p.gold()) {
            sendText(p, TextChannel.TEXT_CHANNEL_SAY, merchant.name() + "說：「" + quantity + " 個" + item.name()
                + "要 " + total + " 枚銅錢，你的錢不夠喔。」");
            return;
        }
        if (!p.inventory().canAdd(item, quantity)) {
            sendText(p, TextChannel.TEXT_CHANNEL_SYSTEM, "你身上的東西太多了，拿不下" + item.name() + "。");
            return;
        }
        p.spendGold((int) total);
        p.inventory().add(item, quantity);
        p.openShop = merchant;
        sendText(p, TextChannel.TEXT_CHANNEL_SYSTEM, "你花了 " + total + " 枚銅錢，向" + merchant.name() + "買了"
            + item.name() + (quantity > 1 ? " ×" + quantity : "") + "。");
    }

    void sell(PlayerEntity p, InventoryEntry entry, int quantity) {
        Optional<NpcEntity> near = merchantNear(p);
        if (near.isEmpty()) {
            sendText(p, TextChannel.TEXT_CHANNEL_SYSTEM, "這附近沒有商人。");
            return;
        }
        NpcEntity merchant = near.get();
        ItemTemplate item = entry.template();
        if (entry.equipped()) {
            sendText(p, TextChannel.TEXT_CHANNEL_SYSTEM, "你得先卸下" + item.name() + "。");
            return;
        }
        int unit = content.shopOf(merchant.template()).buyPrice(item);
        if (unit == 0) {
            sendText(p, TextChannel.TEXT_CHANNEL_SAY, merchant.name() + "搖搖頭說：「" + item.name() + "我不收。」");
            return;
        }
        int qty = Math.min(quantity, entry.quantity());
        p.inventory().remove(entry, qty);
        p.addGold(unit * qty);
        p.openShop = merchant;
        sendText(p, TextChannel.TEXT_CHANNEL_SYSTEM, "你把" + item.name() + (qty > 1 ? " ×" + qty : "") + "賣給"
            + merchant.name() + "，得到 " + (unit * qty) + " 枚銅錢。");
    }

    /** 估價：附近商人收購這個東西的單價。 */
    void appraise(PlayerEntity p, InventoryEntry entry) {
        Optional<NpcEntity> near = merchantNear(p);
        if (near.isEmpty()) {
            sendText(p, TextChannel.TEXT_CHANNEL_SYSTEM, "這附近沒有商人可以估價。");
            return;
        }
        NpcEntity merchant = near.get();
        int unit = content.shopOf(merchant.template()).buyPrice(entry.template());
        sendText(p, TextChannel.TEXT_CHANNEL_SAY, unit == 0
            ? merchant.name() + "說：「" + entry.template().name() + "我不收。」"
            : merchant.name() + "說：「" + entry.template().name() + "嘛……一個 " + unit + " 枚銅錢。」");
    }

    /** 走過去的商人到了就打開商店；開著的商店離太遠就關掉。 */
    private void updateShops() {
        for (PlayerEntity p : byConnection.values()) {
            NpcEntity pending = p.pendingShop;
            if (pending != null) {
                if (!contains(pending) || p.combatTarget() != null) {
                    p.pendingShop = null;
                } else if (p.distanceTo(pending) <= TRADE_RANGE) {
                    p.stop();
                    if (p.pendingTalk) {
                        talk(p, pending);
                    } else {
                        openShop(p, pending);
                    }
                } else {
                    p.moveToward(pending.x(), pending.z());
                }
            }
            NpcEntity open = p.openShop;
            if (open != null && (!contains(open) || p.distanceTo(open) > TRADE_RANGE + 1)) {
                p.openShop = null;
                p.connection().send(ServerMessage.newBuilder().setShopClosed(ShopClosed.getDefaultInstance()).build());
            }
        }
    }

    private void sendShop(PlayerEntity p, NpcEntity merchant) {
        ShopDefinition shop = content.shopOf(merchant.template());
        ShopView.Builder view = ShopView.newBuilder()
            .setMerchantId(merchant.id())
            .setMerchantName(merchant.name())
            .setShopName(shop.name());
        for (ShopDefinition.ShopListing listing : shop.sells()) {
            ItemTemplate t = content.items().get(listing.item());
            view.addOffers(ShopOffer.newBuilder()
                .setTemplateId(t.id())
                .setName(t.name())
                .setDescription(t.description())
                .setPrice(listing.priceFor(t))
                .setType(toProto(t.type()))
                .setSlot(t.slot() == null ? com.theages.protocol.v1.EquipSlot.EQUIP_SLOT_UNSPECIFIED : toProto(t.slot()))
                .setAttack(t.attack())
                .setDefense(t.defense())
                .setMaxHp(t.maxHp())
                .setHeal(t.heal()));
        }
        for (InventoryEntry e : p.inventory().entries()) {
            int price = shop.buyPrice(e.template());
            if (price > 0 && !e.equipped()) {
                view.addQuotes(SellQuote.newBuilder().setUid(e.uid()).setPrice(price));
            }
        }
        p.connection().send(ServerMessage.newBuilder().setShop(view).build());
    }

    private String describeShop(PlayerEntity p, NpcEntity merchant) {
        ShopDefinition shop = content.shopOf(merchant.template());
        StringBuilder sb = new StringBuilder(merchant.name()).append("笑著說：「歡迎光臨").append(shop.name()).append("！」");
        for (ShopDefinition.ShopListing listing : shop.sells()) {
            ItemTemplate t = content.items().get(listing.item());
            sb.append(String.format("%n  %-8s %4d 枚銅錢　%s", t.name(), listing.priceFor(t), t.statSummary()));
        }
        sb.append("\n你有 ").append(p.gold()).append(" 枚銅錢。（buy <物品> [數量]、sell <物品> [數量|all]）");
        return sb.toString();
    }

    // ===== 物品 =====

    private void dropLoot(NpcEntity npc, PlayerEntity killer) {
        List<String> names = new ArrayList<>();
        for (LootEntry loot : npc.template().loot()) {
            if (random.nextDouble() >= loot.chance()) {
                continue;
            }
            int qty = loot.minOrOne() + random.nextInt(loot.maxOrMin() - loot.minOrOne() + 1);
            // 散落在屍體周圍半徑 0.8 公尺內
            double angle = random.nextDouble() * Math.PI * 2;
            GroundItem item = spawnGroundItem(itemTemplates.get(loot.item()), qty,
                npc.x() + (float) (Math.cos(angle) * 0.8), npc.z() + (float) (Math.sin(angle) * 0.8), killer);
            names.add(item.displayName());
        }
        if (!names.isEmpty()) {
            sendText(killer, TextChannel.TEXT_CHANNEL_ROOM, npc.name() + "掉下了：" + String.join("、", names) + "。");
        }
    }

    /** owner 為 null 表示任何人都能撿。 */
    private GroundItem spawnGroundItem(ItemTemplate template, int quantity, float x, float z, PlayerEntity owner) {
        GroundItem item = new GroundItem(entityIds.getAsInt(), template, quantity,
            definition.clamp(x), definition.clamp(z),
            owner == null ? 0 : owner.characterId(), owner == null ? "" : owner.name(),
            tick + (long) LOOT_OWNER_SECONDS * tickRate, tick + (long) GROUND_ITEM_SECONDS * tickRate);
        groundItems.put(item.id(), item);
        return item;
    }

    private void despawnGroundItems() {
        groundItems.values().removeIf(item -> {
            if (!item.expired(tick)) {
                return false;
            }
            broadcast(ServerMessage.newBuilder().setEntityLeft(EntityLeft.newBuilder().setId(item.id())).build());
            return true;
        });
    }

    /** 走過去撿東西：到了就撿，物品不見或開始戰鬥就取消。 */
    private void updatePickups() {
        for (PlayerEntity p : byConnection.values()) {
            GroundItem item = p.pendingPickup;
            if (item == null) {
                continue;
            }
            if (!groundItems.containsKey(item.id()) || p.combatTarget() != null) {
                p.pendingPickup = null;
            } else if (p.distanceTo(item.x(), item.z()) <= PICKUP_RANGE) {
                p.pendingPickup = null;
                p.stop();
                pickUp(p, item);
            } else {
                p.moveToward(item.x(), item.z());
            }
        }
    }

    Collection<GroundItem> groundItems() {
        return Collections.unmodifiableCollection(groundItems.values());
    }

    GroundItem groundItem(int id) {
        return groundItems.get(id);
    }

    /** 撿起物品；太遠就先走過去。 */
    void requestPickUp(PlayerEntity p, GroundItem item) {
        if (p.combatTarget() != null) {
            sendText(p, TextChannel.TEXT_CHANNEL_SYSTEM, "你正在戰鬥，沒空撿東西！");
        } else if (p.distanceTo(item.x(), item.z()) <= PICKUP_RANGE) {
            pickUp(p, item);
        } else {
            p.pendingPickup = item;
            p.moveToward(item.x(), item.z());
        }
    }

    /** 立即撿起（不檢查距離）；成功回傳 true。 */
    boolean pickUp(PlayerEntity p, GroundItem item) {
        if (!item.canBeTakenBy(p, tick) && !parties.sameParty(item.ownerCharacterId(), p.characterId())) {
            sendText(p, TextChannel.TEXT_CHANNEL_SYSTEM, "那是" + item.ownerName() + "的戰利品，再等一下吧。");
            return false;
        }
        if (!p.inventory().canAdd(item.template(), item.quantity())) {
            sendText(p, TextChannel.TEXT_CHANNEL_SYSTEM, "你身上的東西太多了，拿不下" + item.displayName() + "。");
            return false;
        }
        groundItems.remove(item.id());
        p.inventory().add(item.template(), item.quantity());
        broadcast(ServerMessage.newBuilder().setEntityLeft(EntityLeft.newBuilder().setId(item.id())).build());
        sendText(p, TextChannel.TEXT_CHANNEL_SYSTEM, "你撿起了" + item.displayName() + "。");
        broadcastText(TextChannel.TEXT_CHANNEL_ROOM, p.name() + " 撿起了" + item.displayName() + "。", p);
        return true;
    }

    void drop(PlayerEntity p, InventoryEntry entry) {
        if (entry.equipped()) {
            sendText(p, TextChannel.TEXT_CHANNEL_SYSTEM, "你得先卸下" + entry.template().name() + "。");
            return;
        }
        String name = entry.displayName();
        int quantity = entry.quantity();
        p.inventory().remove(entry, quantity);
        spawnGroundItem(entry.template(), quantity, p.x(), p.z(), null);
        sendText(p, TextChannel.TEXT_CHANNEL_SYSTEM, "你丟下了" + name + "。");
        broadcastText(TextChannel.TEXT_CHANNEL_ROOM, p.name() + " 丟下了" + name + "。", p);
    }

    void equip(PlayerEntity p, InventoryEntry entry) {
        ItemTemplate t = entry.template();
        if (!t.isEquipment()) {
            sendText(p, TextChannel.TEXT_CHANNEL_SYSTEM, t.name() + "不能裝備。");
            return;
        }
        if (entry.equipped()) {
            sendText(p, TextChannel.TEXT_CHANNEL_SYSTEM, "你已經裝備著" + t.name() + "了。");
            return;
        }
        p.inventory().equip(entry).ifPresent(old ->
            sendText(p, TextChannel.TEXT_CHANNEL_SYSTEM, "你卸下了" + old.template().name() + "。"));
        p.refreshStats();
        sendText(p, TextChannel.TEXT_CHANNEL_SYSTEM, "你裝備了" + t.name() + "。（" + t.statSummary() + "）");
    }

    void unequip(PlayerEntity p, InventoryEntry entry) {
        if (!entry.equipped()) {
            sendText(p, TextChannel.TEXT_CHANNEL_SYSTEM, "你並沒有裝備" + entry.template().name() + "。");
            return;
        }
        p.inventory().unequip(entry);
        p.refreshStats();
        sendText(p, TextChannel.TEXT_CHANNEL_SYSTEM, "你卸下了" + entry.template().name() + "。");
    }

    void use(PlayerEntity p, InventoryEntry entry) {
        ItemTemplate t = entry.template();
        if (t.type() != ItemType.CONSUMABLE) {
            sendText(p, TextChannel.TEXT_CHANNEL_SYSTEM, t.name() + "不能使用。");
        } else if (tick < p.nextUseTick) {
            sendText(p, TextChannel.TEXT_CHANNEL_SYSTEM, "你還在吞上一口，慢慢來。");
        } else if (p.hp() >= p.maxHp()) {
            sendText(p, TextChannel.TEXT_CHANNEL_SYSTEM, "你現在精神飽滿，不需要" + t.name() + "。");
        } else {
            int before = p.hp();
            p.heal(t.heal());
            p.inventory().remove(entry, 1);
            p.nextUseTick = tick + (long) USE_COOLDOWN_SECONDS * tickRate;
            sendText(p, TextChannel.TEXT_CHANNEL_SYSTEM, "你吃下了" + t.name() + "，回復了 " + (p.hp() - before) + " 點生命。");
        }
    }

    private static ServerMessage inventoryMessage(Inventory inventory) {
        com.theages.protocol.v1.Inventory.Builder msg = com.theages.protocol.v1.Inventory.newBuilder()
            .setCapacity(Inventory.CAPACITY);
        for (InventoryEntry e : inventory.entries()) {
            ItemTemplate t = e.template();
            msg.addItems(InventoryItem.newBuilder()
                .setUid(e.uid())
                .setTemplateId(t.id())
                .setName(t.name())
                .setDescription(t.description())
                .setQuantity(e.quantity())
                .setType(toProto(t.type()))
                .setSlot(t.slot() == null ? com.theages.protocol.v1.EquipSlot.EQUIP_SLOT_UNSPECIFIED : toProto(t.slot()))
                .setEquipped(e.equipped())
                .setAttack(t.attack())
                .setDefense(t.defense())
                .setMaxHp(t.maxHp())
                .setHeal(t.heal()));
        }
        return ServerMessage.newBuilder().setInventory(msg).build();
    }

    private static com.theages.protocol.v1.ItemType toProto(ItemType type) {
        return switch (type) {
            case EQUIPMENT -> com.theages.protocol.v1.ItemType.ITEM_TYPE_EQUIPMENT;
            case CONSUMABLE -> com.theages.protocol.v1.ItemType.ITEM_TYPE_CONSUMABLE;
            case MISC -> com.theages.protocol.v1.ItemType.ITEM_TYPE_MISC;
        };
    }

    private static com.theages.protocol.v1.EquipSlot toProto(EquipSlot slot) {
        return switch (slot) {
            case WEAPON -> com.theages.protocol.v1.EquipSlot.EQUIP_SLOT_WEAPON;
            case HEAD -> com.theages.protocol.v1.EquipSlot.EQUIP_SLOT_HEAD;
            case BODY -> com.theages.protocol.v1.EquipSlot.EQUIP_SLOT_BODY;
            case FEET -> com.theages.protocol.v1.EquipSlot.EQUIP_SLOT_FEET;
        };
    }

    void sendText(PlayerEntity to, TextChannel channel, String text) {
        to.connection().send(textMessage(channel, text));
    }

    /** 對區域內所有人廣播文字；except 可為 null。 */
    void broadcastText(TextChannel channel, String text, PlayerEntity except) {
        ServerMessage msg = textMessage(channel, text);
        for (PlayerEntity p : byConnection.values()) {
            if (p != except) {
                p.connection().send(msg);
            }
        }
    }

    void broadcastCombat(CombatEvent event) {
        broadcast(ServerMessage.newBuilder().setCombat(event).build());
    }

    private void broadcast(ServerMessage msg) {
        for (PlayerEntity p : byConnection.values()) {
            p.connection().send(msg);
        }
    }

    private static ServerMessage textMessage(TextChannel channel, String text) {
        return ServerMessage.newBuilder()
            .setText(TextOutput.newBuilder().setChannel(channel).setText(text))
            .build();
    }
}
