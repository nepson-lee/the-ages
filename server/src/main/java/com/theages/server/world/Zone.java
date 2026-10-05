package com.theages.server.world;

import com.theages.protocol.v1.CombatEvent;
import com.theages.protocol.v1.EntityLeft;
import com.theages.protocol.v1.EntityState;
import com.theages.protocol.v1.ServerMessage;
import com.theages.protocol.v1.TextChannel;
import com.theages.protocol.v1.TextOutput;
import com.theages.protocol.v1.Welcome;
import com.theages.protocol.v1.WorldSnapshot;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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

    private final ZoneDefinition definition;
    private final int tickRate;
    private final IntSupplier entityIds;
    private final CharacterStore store;
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
    private long tick;

    private ScheduledExecutorService executor;

    public Zone(ZoneDefinition definition, Map<String, NpcTemplate> templates, int tickRate,
                IntSupplier entityIds, CharacterStore store, RandomGenerator random) {
        this.definition = definition;
        this.tickRate = tickRate;
        this.entityIds = entityIds;
        this.store = store;
        this.combat = new Combat(this, random, tickRate);
        this.npcBrain = new NpcBrain(this, random, tickRate);
        for (ZoneDefinition.NpcSpawn spawn : definition.npcs()) {
            NpcTemplate template = templates.get(spawn.template());
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

        npcBrain.update(tick);
        combat.update(tick);

        float dt = 1f / tickRate;
        for (Entity e : entities.values()) {
            e.step(dt);
        }
        if (tick % (REGEN_INTERVAL_SECONDS * tickRate) == 0) {
            regenerate();
        }
        if (tick > 0 && tick % (AUTOSAVE_INTERVAL_SECONDS * tickRate) == 0) {
            byConnection.values().forEach(this::persist);
        }

        List<EntityState> changed = new ArrayList<>();
        for (Entity e : entities.values()) {
            if (e.consumeDirty()) {
                changed.add(e.toState());
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
            player.moveToward(definition.clamp(move.targetX()), definition.clamp(move.targetZ()));
        } else if (event instanceof ZoneEvent.Attack attack) {
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
            player = new PlayerEntity(entityIds.getAsInt(), join.characterId(), join.name(), join.connection(),
                definition.clamp(join.x()), definition.clamp(join.z()), join.level(), join.exp(), join.hp());
            byCharacter.put(player.characterId(), player);
            entities.put(player.id(), player);
            broadcastText(TextChannel.TEXT_CHANNEL_ROOM, player.name() + " 走了過來。", player);
        }
        byConnection.put(join.connection(), player);

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
        player.connection().send(ServerMessage.newBuilder().setSnapshot(full).build());
        commands.execute(this, player, "look");
    }

    private void onLeave(PlayerEntity player) {
        byConnection.remove(player.connection());
        byCharacter.remove(player.characterId());
        entities.remove(player.id());
        clearTargetsOn(player);
        persist(player);
        broadcast(ServerMessage.newBuilder().setEntityLeft(EntityLeft.newBuilder().setId(player.id())).build());
        broadcastText(TextChannel.TEXT_CHANNEL_ROOM, player.name() + " 離開了。", null);
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

    boolean contains(Entity e) {
        return entities.get(e.id()) == e;
    }

    void startAttack(PlayerEntity player, Entity target) {
        if (!(target instanceof NpcEntity) || target.isDead()) {
            sendText(player, TextChannel.TEXT_CHANNEL_SYSTEM, "這裡沒有這個目標。");
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

    private void rewardKill(PlayerEntity p, NpcEntity npc) {
        int exp = killExp(npc, p);
        int levels = p.gainExp(exp);
        sendText(p, TextChannel.TEXT_CHANNEL_SYSTEM, "你殺死了" + npc.name() + "！獲得 " + exp + " 點經驗。");
        broadcastText(TextChannel.TEXT_CHANNEL_ROOM, p.name() + " 殺死了" + npc.name() + "。", p);
        if (levels > 0) {
            sendText(p, TextChannel.TEXT_CHANNEL_SYSTEM, "★ 恭喜！你的等級提升到 " + p.level() + " 級了！");
            broadcastText(TextChannel.TEXT_CHANNEL_ROOM, p.name() + " 的等級提升到 " + p.level() + " 級了！", p);
        }
    }

    void respawn(NpcEntity npc) {
        npc.respawn();
        entities.put(npc.id(), npc);
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
