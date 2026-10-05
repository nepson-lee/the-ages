package com.theages.server.world;

import static org.assertj.core.api.Assertions.assertThat;

import com.theages.protocol.v1.EntityKind;
import com.theages.protocol.v1.EntityState;
import com.theages.protocol.v1.ServerMessage;
import com.theages.protocol.v1.TextOutput;
import com.theages.server.world.item.EquipSlot;
import com.theages.server.world.item.Inventory;
import com.theages.server.world.item.ItemRecord;
import com.theages.server.world.item.ItemTemplate;
import com.theages.server.world.item.ItemType;
import com.theages.server.world.item.LootEntry;
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
        1, 10, 3, 0, 50, 3f, "敲了", false, 2, 0f, List.of());
    /** 一擊必殺的主動攻擊型 NPC。 */
    private static final NpcTemplate KILLER = new NpcTemplate("killer", "惡狼", List.of("wolf"), "很兇。",
        1, 1000, 100, 0, 1, 3f, "撲咬了", true, 30, 0f, List.of());
    /** 很耐打、跑得比玩家慢的 NPC，用來測試脫戰。 */
    private static final NpcTemplate TANK = new NpcTemplate("tank", "老龜", List.of("turtle"), "很硬。",
        1, 1000, 1, 0, 1, 3f, "咬了", false, 30, 0f, List.of());
    /** 一定掉一把匕首和兩塊肉的木人。 */
    private static final NpcTemplate PINATA = new NpcTemplate("pinata", "寶箱怪", List.of("pinata"), "裝滿東西。",
        1, 1, 0, 0, 1, 3f, "撞了", false, 30, 0f,
        List.of(new LootEntry("dagger", 1.0, null, null), new LootEntry("meat", 1.0, 2, 2)));

    private static final ItemTemplate DAGGER = new ItemTemplate("dagger", "匕首", List.of("dagger"), "很利。",
        ItemType.EQUIPMENT, EquipSlot.WEAPON, 6, 0, 0, 0, null);
    private static final ItemTemplate VEST = new ItemTemplate("vest", "背心", List.of("vest"), "很暖。",
        ItemType.EQUIPMENT, EquipSlot.BODY, 0, 4, 10, 0, null);
    private static final ItemTemplate MEAT = new ItemTemplate("meat", "兔肉", List.of("meat"), "好吃。",
        ItemType.CONSUMABLE, null, 0, 0, 0, 15, null);

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
        return new Zone(def, Map.of("dummy", DUMMY, "killer", KILLER, "tank", TANK, "pinata", PINATA),
            Map.of("dagger", DAGGER, "vest", VEST, "meat", MEAT), TICK_RATE, ids::getAndIncrement, saves::add, ALWAYS_HIT);
    }

    private static ZoneEvent.Join join(PlayerConnection c, long characterId, String name, float x, float z) {
        return new ZoneEvent.Join(c, characterId, name, x, z, 1, 0, 0, List.of());
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
        zone.enqueue(new ZoneEvent.Join(alice, 42, "alice", 0, 0, 3, 77, 0, // hp 0 = 滿血
            List.of(new ItemRecord("dagger", 1, true), new ItemRecord("meat", 4, false))));
        zone.tick();
        zone.enqueue(new ZoneEvent.Leave(alice));
        zone.tick();

        assertThat(saves).containsExactly(new CharacterSnapshot(42, "test", 0, 0, 3, 77, PlayerEntity.maxHpFor(3),
            List.of(new ItemRecord("dagger", 1, true), new ItemRecord("meat", 4, false))));
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
        zone.enqueue(new ZoneEvent.Join(alice, 1, "alice", 20, 0, 1, 50, 0, List.of()));
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

    // ===== 物品 =====

    /** 讓 alice 打死身旁的寶箱怪，回傳 [zone, alice 連線]。 */
    private Object[] killPinata() {
        Zone zone = zone(new ZoneDefinition.NpcSpawn("pinata", 1, 0, 1));
        FakeConnection alice = new FakeConnection();
        zone.enqueue(join(alice, 1, "alice", 0, 0));
        zone.tick();
        zone.enqueue(new ZoneEvent.Attack(alice, entity(zone, NpcEntity.class).id()));
        zone.tick();
        return new Object[] {zone, alice};
    }

    @Test
    void killDropsLootOnGroundForKiller() {
        Object[] r = killPinata();
        Zone zone = (Zone) r[0];
        FakeConnection alice = (FakeConnection) r[1];

        assertThat(zone.groundItems()).extracting(GroundItem::displayName).containsExactly("匕首", "兔肉 ×2");
        assertThat(alice.texts()).contains("寶箱怪掉下了：匕首、兔肉 ×2。");
        assertThat(alice.sent).anyMatch(m -> m.hasSnapshot() && m.getSnapshot().getEntitiesList().stream()
            .anyMatch(e -> e.getKind() == EntityKind.ENTITY_KIND_ITEM && e.getModel().equals("dagger")));

        zone.enqueue(new ZoneEvent.CommandText(alice, "get all"));
        zone.tick();
        PlayerEntity p = entity(zone, PlayerEntity.class);
        assertThat(zone.groundItems()).isEmpty();
        assertThat(p.inventory().entries()).extracting(e -> e.displayName()).containsExactly("匕首", "兔肉 ×2");
        assertThat(alice.sent).anyMatch(m -> m.hasInventory() && m.getInventory().getItemsCount() == 2);
    }

    @Test
    void othersMustWaitForLootOwnership() {
        Object[] r = killPinata();
        Zone zone = (Zone) r[0];
        FakeConnection bob = new FakeConnection();
        zone.enqueue(join(bob, 2, "bob", 1, 0));
        zone.tick();

        zone.enqueue(new ZoneEvent.CommandText(bob, "get dagger"));
        zone.tick();
        assertThat(bob.texts()).contains("那是alice的戰利品，再等一下吧。");

        run(zone, Zone.LOOT_OWNER_SECONDS * TICK_RATE);
        zone.enqueue(new ZoneEvent.CommandText(bob, "get dagger"));
        zone.tick();
        assertThat(bob.texts()).contains("你撿起了匕首。");
    }

    @Test
    void getWalksToFarItemThenPicksItUp() {
        Zone zone = zone();
        FakeConnection alice = new FakeConnection();
        zone.enqueue(new ZoneEvent.Join(alice, 1, "alice", 0, 0, 1, 0, 0, List.of(new ItemRecord("meat", 1, false))));
        zone.enqueue(new ZoneEvent.CommandText(alice, "drop meat"));
        zone.tick();
        assertThat(zone.groundItems()).hasSize(1);
        int itemId = zone.groundItems().iterator().next().id();

        zone.enqueue(new ZoneEvent.Move(alice, 10, 0));
        run(zone, 2 * TICK_RATE);
        zone.enqueue(new ZoneEvent.CommandText(alice, "get #" + itemId));
        run(zone, 3 * TICK_RATE);

        assertThat(zone.groundItems()).isEmpty();
        assertThat(entity(zone, PlayerEntity.class).inventory().find("meat", false)).isPresent();
    }

    @Test
    void wearingEquipmentChangesStatsAndSwaps() {
        Zone zone = zone();
        FakeConnection alice = new FakeConnection();
        zone.enqueue(new ZoneEvent.Join(alice, 1, "alice", 0, 0, 1, 0, 0,
            List.of(new ItemRecord("dagger", 1, false), new ItemRecord("vest", 1, false))));
        zone.tick();
        PlayerEntity p = entity(zone, PlayerEntity.class);
        int baseAttack = p.attack();
        int baseMaxHp = p.maxHp();

        zone.enqueue(new ZoneEvent.CommandText(alice, "wield dagger"));
        zone.enqueue(new ZoneEvent.CommandText(alice, "wear 背心"));
        zone.tick();
        assertThat(p.attack()).isEqualTo(baseAttack + 6);
        assertThat(p.maxHp()).isEqualTo(baseMaxHp + 10);
        assertThat(alice.texts()).contains("你裝備了匕首。（攻擊 +6）");
        assertThat(alice.sent).anyMatch(m -> m.hasSelfStats() && m.getSelfStats().getAttack() == baseAttack + 6);

        zone.enqueue(new ZoneEvent.CommandText(alice, "remove vest"));
        zone.tick();
        assertThat(p.maxHp()).isEqualTo(baseMaxHp);
        assertThat(p.hp()).isLessThanOrEqualTo(p.maxHp());
    }

    @Test
    void cannotDropEquippedItem() {
        Zone zone = zone();
        FakeConnection alice = new FakeConnection();
        zone.enqueue(new ZoneEvent.Join(alice, 1, "alice", 0, 0, 1, 0, 0, List.of(new ItemRecord("dagger", 1, true))));
        zone.enqueue(new ZoneEvent.CommandText(alice, "drop dagger"));
        zone.tick();

        assertThat(alice.texts()).contains("你得先卸下匕首。");
        assertThat(zone.groundItems()).isEmpty();
    }

    @Test
    void eatingHealsWithCooldownAndConsumesItem() {
        Zone zone = zone();
        FakeConnection alice = new FakeConnection();
        zone.enqueue(new ZoneEvent.Join(alice, 1, "alice", 0, 0, 1, 0, 10, List.of(new ItemRecord("meat", 3, false))));
        zone.tick();
        PlayerEntity p = entity(zone, PlayerEntity.class);
        int before = p.hp();

        zone.enqueue(new ZoneEvent.CommandText(alice, "eat meat"));
        zone.enqueue(new ZoneEvent.CommandText(alice, "eat meat"));
        zone.tick();

        assertThat(p.hp()).isEqualTo(before + 15);
        assertThat(p.inventory().find("meat", false).orElseThrow().quantity()).isEqualTo(2);
        assertThat(alice.texts()).contains("你還在吞上一口，慢慢來。");
    }

    @Test
    void eatingAtFullHealthIsRefused() {
        Zone zone = zone();
        FakeConnection alice = new FakeConnection();
        zone.enqueue(new ZoneEvent.Join(alice, 1, "alice", 0, 0, 1, 0, 0, List.of(new ItemRecord("meat", 1, false))));
        zone.enqueue(new ZoneEvent.CommandText(alice, "eat meat"));
        zone.tick();

        assertThat(entity(zone, PlayerEntity.class).inventory().entries()).hasSize(1);
    }

    @Test
    void groundItemsDespawn() {
        Zone zone = zone();
        FakeConnection alice = new FakeConnection();
        zone.enqueue(new ZoneEvent.Join(alice, 1, "alice", 0, 0, 1, 0, 0, List.of(new ItemRecord("meat", 1, false))));
        zone.enqueue(new ZoneEvent.CommandText(alice, "drop meat"));
        zone.tick();
        int id = zone.groundItems().iterator().next().id();

        run(zone, Zone.GROUND_ITEM_SECONDS * TICK_RATE);
        assertThat(zone.groundItems()).isEmpty();
        assertThat(alice.sent).anyMatch(m -> m.hasEntityLeft() && m.getEntityLeft().getId() == id);
    }

    @Test
    void fullInventoryRefusesPickup() {
        Object[] r = killPinata();
        Zone zone = (Zone) r[0];
        FakeConnection alice = (FakeConnection) r[1];
        PlayerEntity p = entity(zone, PlayerEntity.class);
        for (int i = 0; i < Inventory.CAPACITY; i++) {
            p.inventory().add(VEST, 1);
        }

        zone.enqueue(new ZoneEvent.CommandText(alice, "get dagger"));
        zone.tick();
        assertThat(alice.texts()).contains("你身上的東西太多了，拿不下匕首。");
        assertThat(zone.groundItems()).hasSize(2);
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
