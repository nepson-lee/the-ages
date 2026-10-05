package com.theages.server.world;

import static org.assertj.core.api.Assertions.assertThat;

import com.theages.protocol.v1.EntityState;
import com.theages.protocol.v1.ServerMessage;
import com.theages.protocol.v1.TextOutput;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ZoneTest {

    private static final int TICK_RATE = 10;

    private final List<long[]> saves = new ArrayList<>();
    private Zone zone;

    @BeforeEach
    void setUp() {
        ZoneDefinition def = new ZoneDefinition("test", "測試區", "空曠的平原。", 20, new ZoneDefinition.Point(0, 0));
        AtomicInteger ids = new AtomicInteger(1);
        zone = new Zone(def, TICK_RATE, ids::getAndIncrement, (id, zoneId, x, z) -> saves.add(new long[] {id}));
    }

    @Test
    void joinSendsWelcomeAndFullSnapshot() {
        FakeConnection alice = new FakeConnection();
        zone.enqueue(new ZoneEvent.Join(alice, 1, "alice", 0, 0));
        zone.tick();

        assertThat(alice.sent.get(0).hasWelcome()).isTrue();
        assertThat(alice.sent.get(1).getSnapshot().getFull()).isTrue();
        assertThat(alice.sent.get(1).getSnapshot().getEntitiesList()).extracting(EntityState::getName)
            .containsExactly("alice");
    }

    @Test
    void movesAtFixedSpeedAndStopsAtTarget() {
        FakeConnection alice = new FakeConnection();
        zone.enqueue(new ZoneEvent.Join(alice, 1, "alice", 0, 0));
        zone.tick();

        zone.enqueue(new ZoneEvent.Move(alice, 1, 0));
        zone.tick();
        assertThat(lastPosition(alice).getX()).isEqualTo(PlayerEntity.SPEED / TICK_RATE);

        zone.tick();
        zone.tick();
        assertThat(lastPosition(alice).getX()).isEqualTo(1f);
    }

    @Test
    void moveTargetIsClampedToZoneBounds() {
        FakeConnection alice = new FakeConnection();
        zone.enqueue(new ZoneEvent.Join(alice, 1, "alice", 0, 0));
        zone.enqueue(new ZoneEvent.Move(alice, 1000, 0));
        for (int i = 0; i < 100; i++) {
            zone.tick();
        }
        assertThat(lastPosition(alice).getX()).isEqualTo(10f);
    }

    @Test
    void sayIsHeardByOthers() {
        FakeConnection alice = new FakeConnection();
        FakeConnection bob = new FakeConnection();
        zone.enqueue(new ZoneEvent.Join(alice, 1, "alice", 0, 0));
        zone.enqueue(new ZoneEvent.Join(bob, 2, "bob", 0, 0));
        zone.tick();

        zone.enqueue(new ZoneEvent.CommandText(alice, "say 大家好"));
        zone.tick();

        assertThat(bob.texts()).contains("alice說：「大家好」");
        assertThat(alice.texts()).contains("你說：「大家好」");
    }

    @Test
    void reloginReplacesOldConnectionAndKeepsPosition() {
        FakeConnection first = new FakeConnection();
        zone.enqueue(new ZoneEvent.Join(first, 1, "alice", 0, 0));
        zone.enqueue(new ZoneEvent.Move(first, 0.5f, 0));
        zone.tick();

        FakeConnection second = new FakeConnection();
        zone.enqueue(new ZoneEvent.Join(second, 1, "alice", 0, 0)); // 資料庫裡的舊位置
        zone.enqueue(new ZoneEvent.Leave(first));                    // 舊連線關閉後才送達的離開事件
        zone.tick();

        assertThat(first.closedReason).isNotNull();
        assertThat(second.sent.get(1).getSnapshot().getEntities(0).getPosition().getX()).isEqualTo(0.5f);
        assertThat(saves).isEmpty();
    }

    @Test
    void leavePersistsCharacter() {
        FakeConnection alice = new FakeConnection();
        zone.enqueue(new ZoneEvent.Join(alice, 42, "alice", 0, 0));
        zone.tick();
        zone.enqueue(new ZoneEvent.Leave(alice));
        zone.tick();

        assertThat(saves).hasSize(1);
        assertThat(saves.get(0)[0]).isEqualTo(42);
    }

    private static com.theages.protocol.v1.Vec2 lastPosition(FakeConnection c) {
        for (int i = c.sent.size() - 1; i >= 0; i--) {
            ServerMessage m = c.sent.get(i);
            if (m.hasSnapshot() && m.getSnapshot().getEntitiesCount() > 0) {
                return m.getSnapshot().getEntities(0).getPosition();
            }
        }
        throw new AssertionError("沒有收到任何快照");
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
