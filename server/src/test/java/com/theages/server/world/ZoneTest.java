package com.theages.server.world;

import static org.assertj.core.api.Assertions.assertThat;

import com.theages.protocol.v1.EntityKind;
import com.theages.protocol.v1.EntityState;
import com.theages.protocol.v1.ServerMessage;
import com.theages.protocol.v1.TextOutput;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class ZoneTest {

    private static final int TICK_RATE = 10;

    /** 不會動、容易打死的練習假人。 */
    private static final NpcTemplate DUMMY = new NpcTemplate("dummy", "木人", List.of("dummy"), "一具木人。",
        1, 10, 3, 0, 50, 3f, "敲了", false, 2, 0f);
    /** 一擊必殺的主動攻擊型 NPC。 */
    private static final NpcTemplate KILLER = new NpcTemplate("killer", "惡狼", List.of("wolf"), "很兇。",
        1, 1000, 100, 0, 1, 3f, "撲咬了", true, 30, 0f);
    /** 很耐打、跑得比玩家慢的 NPC，用來測試脫戰。 */
    private static final NpcTemplate TANK = new NpcTemplate("tank", "老龜", List.of("turtle"), "很硬。",
        1, 1000, 1, 0, 1, 3f, "咬了", false, 30, 0f);

    private final List<CharacterSnapshot> saves = new ArrayList<>();

    /** 亂數固定回傳 0：攻擊必中，傷害取最小值（攻擊力 × 0.7）。 */
    private static final RandomGenerator ALWAYS_HIT = new RandomGenerator() {
        @Override
        public long nextLong() {
            return 0;
        }

        @Override
        public double nextDouble() {
            return 0;
        }
    };

    private Zone zone(ZoneDefinition.NpcSpawn... npcs) {
        ZoneDefinition def = new ZoneDefinition("test", "測試區", "空曠的平原。", 60,
            new ZoneDefinition.Point(0, 0), List.of(npcs));
        AtomicInteger ids = new AtomicInteger(1);
        return new Zone(def, Map.of("dummy", DUMMY, "killer", KILLER, "tank", TANK), TICK_RATE,
            ids::getAndIncrement, saves::add, ALWAYS_HIT);
    }

    private static ZoneEvent.Join join(PlayerConnection c, long characterId, String name, float x, float z) {
        return new ZoneEvent.Join(c, characterId, name, x, z, 1, 0, 0);
    }

    private static void run(Zone zone, int ticks) {
        for (int i = 0; i < ticks; i++) {
            zone.tick();
        }
    }

    private static <T extends Entity> T entity(Zone zone, Class<T> type) {
        return zone.entities().stream().filter(type::isInstance).map(type::cast).findFirst().orElseThrow();
    }

    // ===== 基本 =====

    @Test
    void joinSendsWelcomeAndFullSnapshotIncludingNpcs() {
        Zone zone = zone(new ZoneDefinition.NpcSpawn("dummy", 5, 5, 2));
        FakeConnection alice = new FakeConnection();
        zone.enqueue(join(alice, 1, "alice", 0, 0));
        zone.tick();

        assertThat(alice.sent.get(0).hasWelcome()).isTrue();
        WorldSnapshotAssert full = new WorldSnapshotAssert(alice.sent.get(1));
        assertThat(full.kinds()).containsExactlyInAnyOrder(EntityKind.ENTITY_KIND_NPC, EntityKind.ENTITY_KIND_NPC,
            EntityKind.ENTITY_KIND_PLAYER);
        assertThat(alice.texts()).anyMatch(t -> t.contains("木人(dummy) ×2"));
    }

    @Test
    void movesAtFixedSpeedAndStopsAtTarget() {
        Zone zone = zone();
        FakeConnection alice = new FakeConnection();
        zone.enqueue(join(alice, 1, "alice", 0, 0));
        zone.tick();

        zone.enqueue(new ZoneEvent.Move(alice, 1, 0));
        zone.tick();
        PlayerEntity p = entity(zone, PlayerEntity.class);
        assertThat(p.x()).isEqualTo(PlayerEntity.SPEED / TICK_RATE);

        run(zone, 2);
        assertThat(p.x()).isEqualTo(1f);
    }

    @Test
    void moveTargetIsClampedToZoneBounds() {
        Zone zone = zone();
        FakeConnection alice = new FakeConnection();
        zone.enqueue(join(alice, 1, "alice", 0, 0));
        zone.enqueue(new ZoneEvent.Move(alice, 1000, 0));
        run(zone, 100);
        assertThat(entity(zone, PlayerEntity.class).x()).isEqualTo(30f);
    }

    @Test
    void sayIsHeardByOthers() {
        Zone zone = zone();
        FakeConnection alice = new FakeConnection();
        FakeConnection bob = new FakeConnection();
        zone.enqueue(join(alice, 1, "alice", 0, 0));
        zone.enqueue(join(bob, 2, "bob", 0, 0));
        zone.tick();

        zone.enqueue(new ZoneEvent.CommandText(alice, "say 大家好"));
        zone.tick();

        assertThat(bob.texts()).contains("alice說：「大家好」");
        assertThat(alice.texts()).contains("你說：「大家好」");
    }

    @Test
    void reloginReplacesOldConnectionAndKeepsPosition() {
        Zone zone = zone();
        FakeConnection first = new FakeConnection();
        zone.enqueue(join(first, 1, "alice", 0, 0));
        zone.enqueue(new ZoneEvent.Move(first, 0.5f, 0));
        zone.tick();

        FakeConnection second = new FakeConnection();
        zone.enqueue(join(second, 1, "alice", 0, 0)); // 資料庫裡的舊位置
        zone.enqueue(new ZoneEvent.Leave(first));      // 舊連線關閉後才送達的離開事件
        zone.tick();

        assertThat(first.closedReason).isNotNull();
        assertThat(second.sent.get(1).getSnapshot().getEntities(0).getPosition().getX()).isEqualTo(0.5f);
        assertThat(saves).isEmpty();
    }

    @Test
    void leavePersistsCharacterProgress() {
        Zone zone = zone();
        FakeConnection alice = new FakeConnection();
        zone.enqueue(new ZoneEvent.Join(alice, 42, "alice", 0, 0, 3, 77, 0)); // hp 0 = 滿血
        zone.tick();
        zone.enqueue(new ZoneEvent.Leave(alice));
        zone.tick();

        assertThat(saves).containsExactly(new CharacterSnapshot(42, "test", 0, 0, 3, 77, PlayerEntity.maxHpFor(3)));
    }

    // ===== 戰鬥 =====

    @Test
    void killingNpcGrantsExpAndNpcRespawns() {
        Zone zone = zone(new ZoneDefinition.NpcSpawn("dummy", 1, 0, 1));
        FakeConnection alice = new FakeConnection();
        zone.enqueue(join(alice, 1, "alice", 0, 0));
        zone.tick();
        NpcEntity dummy = entity(zone, NpcEntity.class);
        PlayerEntity p = entity(zone, PlayerEntity.class);

        zone.enqueue(new ZoneEvent.Attack(alice, dummy.id()));
        // 玩家攻擊 8 × 0.7 ≈ 6，木人 10 HP → 第 2 回合（1 秒後）打死
        run(zone, TICK_RATE + 1);

        assertThat(zone.contains(dummy)).isFalse();
        assertThat(p.exp()).isEqualTo(50);
        assertThat(p.hp()).as("木人有反擊").isLessThan(p.maxHp());
        assertThat(p.combatTarget()).isNull();
        assertThat(alice.sent).anyMatch(m -> m.hasEntityLeft() && m.getEntityLeft().getId() == dummy.id());
        assertThat(alice.sent).anyMatch(m -> m.hasCombat() && m.getCombat().getKilled());
        assertThat(alice.texts()).anyMatch(t -> t.startsWith("你殺死了木人"));

        run(zone, DUMMY.respawnSeconds() * TICK_RATE);
        assertThat(zone.contains(dummy)).isTrue();
        assertThat(dummy.hp()).isEqualTo(dummy.maxHp());
    }

    @Test
    void killCommandFindsTargetByKeyword() {
        Zone zone = zone(new ZoneDefinition.NpcSpawn("dummy", 10, 0, 1));
        FakeConnection alice = new FakeConnection();
        zone.enqueue(join(alice, 1, "alice", 0, 0));
        zone.enqueue(new ZoneEvent.CommandText(alice, "kill dummy"));
        zone.tick();

        PlayerEntity p = entity(zone, PlayerEntity.class);
        assertThat(p.combatTarget()).isInstanceOf(NpcEntity.class);
        assertThat(alice.texts()).contains("你開始攻擊木人！");

        zone.tick();
        assertThat(p.x()).as("不在攻擊距離內會先走過去").isGreaterThan(0f);
    }

    @Test
    void aggressiveNpcKillsPlayerWhoRespawnsWithExpPenalty() {
        Zone zone = zone(new ZoneDefinition.NpcSpawn("killer", 22, 0, 1));
        FakeConnection alice = new FakeConnection();
        zone.enqueue(new ZoneEvent.Join(alice, 1, "alice", 20, 0, 1, 50, 0));
        run(zone, 2 * TICK_RATE);

        PlayerEntity p = entity(zone, PlayerEntity.class);
        assertThat(alice.texts()).anyMatch(t -> t.startsWith("你被惡狼殺死了"));
        assertThat(p.x()).isZero();
        assertThat(p.z()).isZero();
        assertThat(p.hp()).isEqualTo(p.maxHp());
        assertThat(p.exp()).isEqualTo(40);
        assertThat(entity(zone, NpcEntity.class).combatTarget()).as("殺死玩家後回家").isNull();
    }

    @Test
    void npcGivesUpChaseBeyondLeashAndHealsAtHome() {
        Zone zone = zone(new ZoneDefinition.NpcSpawn("tank", 1, 0, 1));
        FakeConnection alice = new FakeConnection();
        zone.enqueue(join(alice, 1, "alice", 0, 0));
        zone.tick();
        NpcEntity tank = entity(zone, NpcEntity.class);

        zone.enqueue(new ZoneEvent.Attack(alice, tank.id()));
        zone.tick();
        assertThat(tank.hp()).isLessThan(tank.maxHp());
        assertThat(tank.combatTarget()).as("反擊").isNotNull();

        zone.enqueue(new ZoneEvent.Move(alice, -29, 0)); // 逃跑
        run(zone, 30 * TICK_RATE);

        assertThat(tank.combatTarget()).isNull();
        assertThat(tank.distanceFromHome()).isLessThan(0.01f);
        assertThat(tank.hp()).isEqualTo(tank.maxHp());
    }

    @Test
    void cannotAttackOtherPlayers() {
        Zone zone = zone();
        FakeConnection alice = new FakeConnection();
        FakeConnection bob = new FakeConnection();
        zone.enqueue(join(alice, 1, "alice", 0, 0));
        zone.enqueue(join(bob, 2, "bob", 1, 0));
        zone.tick();
        int bobId = zone.players().stream().filter(p -> p.name().equals("bob")).findFirst().orElseThrow().id();

        zone.enqueue(new ZoneEvent.Attack(alice, bobId));
        zone.tick();

        assertThat(alice.texts()).contains("這裡沒有這個目標。");
    }

    @Test
    void gainingEnoughExpLevelsUpAndRestoresHp() {
        PlayerEntity p = new PlayerEntity(1, 1, "alice", new FakeConnection(), 0, 0, 1, 0, 10);
        int levels = p.gainExp(250); // 升 2 級需要 100，升 3 級還要 200

        assertThat(levels).isEqualTo(1);
        assertThat(p.level()).isEqualTo(2);
        assertThat(p.exp()).isEqualTo(150);
        assertThat(p.maxHp()).isEqualTo(PlayerEntity.maxHpFor(2));
        assertThat(p.hp()).isEqualTo(p.maxHp());
    }

    @Test
    void killExpScalesWithLevelDifference() {
        NpcEntity npc = new NpcEntity(1, DUMMY, 0, 0); // Lv1，50 exp
        assertThat(Zone.killExp(npc, new PlayerEntity(2, 1, "a", new FakeConnection(), 0, 0, 1, 0, 0))).isEqualTo(50);
        assertThat(Zone.killExp(npc, new PlayerEntity(3, 2, "b", new FakeConnection(), 0, 0, 3, 0, 0))).isEqualTo(30);
        assertThat(Zone.killExp(npc, new PlayerEntity(4, 3, "c", new FakeConnection(), 0, 0, 20, 0, 0))).isEqualTo(5);
    }

    // ===== 工具 =====

    private record WorldSnapshotAssert(ServerMessage message) {
        List<EntityKind> kinds() {
            return message.getSnapshot().getEntitiesList().stream().map(EntityState::getKind).toList();
        }
    }

    private static final class FakeConnection implements PlayerConnection {
        final List<ServerMessage> sent = new ArrayList<>();
        String closedReason;

        @Override
        public void send(ServerMessage message) {
            sent.add(message);
        }

        @Override
        public void close(String reason) {
            closedReason = reason;
        }

        List<String> texts() {
            return sent.stream().filter(ServerMessage::hasText).map(ServerMessage::getText)
                .map(TextOutput::getText).toList();
        }
    }
}
