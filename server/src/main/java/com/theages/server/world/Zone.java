package com.theages.server.world;

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
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一個區域 = 一條執行緒跑固定頻率的 tick 迴圈。
 *
 * <p>區域狀態（players 等欄位）只在 tick 執行緒上讀寫，所以不需要鎖。
 * 其他執行緒只能透過 {@link #enqueue(ZoneEvent)} 把事件丟進來，下一個 tick 開始時處理。
 * tick 內禁止阻塞 I/O，存檔交給 {@link CharacterStore} 非同步處理。
 */
public final class Zone {

    private static final Logger log = LoggerFactory.getLogger(Zone.class);

    private final ZoneDefinition definition;
    private final int tickRate;
    private final IntSupplier entityIds;
    private final CharacterStore store;
    private final Commands commands = new Commands();
    private final Queue<ZoneEvent> inbox = new ConcurrentLinkedQueue<>();

    private final Map<PlayerConnection, PlayerEntity> byConnection = new IdentityHashMap<>();
    private final Map<Long, PlayerEntity> byCharacter = new HashMap<>();
    private long tick;

    private ScheduledExecutorService executor;

    public Zone(ZoneDefinition definition, int tickRate, IntSupplier entityIds, CharacterStore store) {
        this.definition = definition;
        this.tickRate = tickRate;
        this.entityIds = entityIds;
        this.store = store;
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
        log.info("區域 {} 啟動，tick rate {}/s", definition.id(), tickRate);
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

        float dt = 1f / tickRate;
        List<EntityState> changed = new ArrayList<>();
        for (PlayerEntity p : byConnection.values()) {
            p.step(dt);
            if (p.consumeDirty()) {
                changed.add(p.toState());
            }
        }
        if (!changed.isEmpty()) {
            broadcast(ServerMessage.newBuilder()
                .setSnapshot(WorldSnapshot.newBuilder().setTick(tick).setFull(false).addAllEntities(changed))
                .build());
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
            player.setTarget(definition.clamp(move.targetX()), definition.clamp(move.targetZ()));
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
                definition.clamp(join.x()), definition.clamp(join.z()));
            byCharacter.put(player.characterId(), player);
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
        byConnection.values().forEach(p -> full.addEntities(p.toState()));
        player.connection().send(ServerMessage.newBuilder().setSnapshot(full).build());
        commands.execute(this, player, "look");
    }

    private void onLeave(PlayerEntity player) {
        byConnection.remove(player.connection());
        byCharacter.remove(player.characterId());
        persist(player);
        broadcast(ServerMessage.newBuilder().setEntityLeft(EntityLeft.newBuilder().setId(player.id())).build());
        broadcastText(TextChannel.TEXT_CHANNEL_ROOM, player.name() + " 離開了。", null);
    }

    private void persist(PlayerEntity p) {
        store.saveAsync(p.characterId(), definition.id(), p.x(), p.z());
    }

    // ===== 給 Commands 使用的操作（都在 tick 執行緒上呼叫） =====

    Collection<PlayerEntity> players() {
        return Collections.unmodifiableCollection(byConnection.values());
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
