package com.theages.server.world.party;

import static org.assertj.core.api.Assertions.assertThat;

import com.theages.protocol.v1.PartyMember;
import com.theages.protocol.v1.ServerMessage;
import com.theages.server.world.PlayerConnection;
import com.theages.server.world.Zone;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PartyServiceTest {

    private final AtomicLong now = new AtomicLong(1_000);
    private final PartyService parties = new PartyService(now::get);
    private Conn alice;
    private Conn bob;
    private Conn carol;

    @BeforeEach
    void setUp() {
        alice = online(1, "Alice");
        bob = online(2, "bob");
        carol = online(3, "carol");
    }

    private Conn online(long id, String name) {
        Conn c = new Conn();
        parties.online(id, name, c);
        return c;
    }

    private void form(long leader, long... members) {
        for (long m : members) {
            parties.invite(leader, m == 2 ? "bob" : m == 3 ? "carol" : "?");
            parties.accept(m);
        }
    }

    @Test
    void inviteAndAcceptFormsPartyWithInviterAsLeader() {
        parties.invite(1, "BOB"); // 名字不分大小寫
        assertThat(bob.texts()).contains("Alice 邀請你加入隊伍。（輸入 accept 接受，60 秒內有效）");

        parties.accept(2);
        PartyService.Party party = parties.partyOf(1).orElseThrow();
        assertThat(party.leader()).isEqualTo(1);
        assertThat(party.members()).containsExactly(1L, 2L);
        assertThat(parties.sameParty(2, 1)).isTrue();
        assertThat(alice.texts()).contains("bob 加入了隊伍。");
        assertThat(alice.lastView()).extracting(PartyMember::getName).containsExactly("Alice", "bob");
        assertThat(bob.lastView()).filteredOn(PartyMember::getSelf).extracting(PartyMember::getName).containsExactly("bob");
    }

    @Test
    void inviteExpires() {
        parties.invite(1, "bob");
        now.addAndGet(PartyService.INVITE_TTL_MILLIS + 1);
        parties.accept(2);

        assertThat(parties.partyOf(2)).isEmpty();
        assertThat(bob.texts()).contains("沒有人邀請你，或邀請已經過期了。");
    }

    @Test
    void onlyLeaderCanInvite() {
        form(1, 2);
        parties.invite(2, "carol");

        assertThat(bob.texts()).contains("只有隊長可以邀請別人。");
        assertThat(carol.texts()).isEmpty();
    }

    @Test
    void cannotInviteSomeoneAlreadyInAParty() {
        form(1, 2);
        Conn dave = online(4, "dave");
        parties.invite(4, "bob");

        assertThat(dave.texts()).contains("bob 已經有隊伍了。");
    }

    @Test
    void partyIsCappedAtMaxSize() {
        for (int i = 2; i <= PartyService.MAX_SIZE; i++) {
            online(10 + i, "m" + i);
            parties.invite(1, "m" + i);
            parties.accept(10 + i);
        }
        parties.invite(1, "carol");

        assertThat(alice.texts()).contains("隊伍已經滿了（最多 " + PartyService.MAX_SIZE + " 人）。");
    }

    @Test
    void leaderLeavingPassesLeadership() {
        form(1, 2, 3);
        parties.leave(1);

        PartyService.Party party = parties.partyOf(2).orElseThrow();
        assertThat(party.leader()).isEqualTo(2);
        assertThat(party.members()).containsExactly(2L, 3L);
        assertThat(alice.texts()).contains("你離開了隊伍。");
        assertThat(alice.lastView()).isEmpty();
        assertThat(carol.texts()).contains("Alice 離開了隊伍。bob 成為新的隊長。");
    }

    @Test
    void partyOfOneDisbands() {
        form(1, 2);
        parties.leave(2);

        assertThat(parties.partyOf(1)).isEmpty();
        assertThat(alice.texts()).contains("bob 離開了隊伍。隊伍解散了。");
    }

    @Test
    void leaderCanKick() {
        form(1, 2, 3);
        parties.kick(2, "carol");
        assertThat(bob.texts()).contains("只有隊長可以把人踢出隊伍。");

        parties.kick(1, "carol");
        assertThat(parties.partyOf(3)).isEmpty();
        assertThat(carol.texts()).contains("你被踢出了隊伍。");
    }

    @Test
    void goingOfflineLeavesParty() {
        form(1, 2, 3);
        parties.offline(2, bob);

        assertThat(parties.partyOf(1).orElseThrow().members()).containsExactly(1L, 3L);
        assertThat(alice.texts()).contains("bob 離線了，退出了隊伍。");
    }

    @Test
    void offlineFromReplacedConnectionIsIgnored() {
        form(1, 2);
        Conn bobAgain = online(2, "bob"); // 重複登入換了連線
        parties.offline(2, bob);           // 舊連線關閉

        assertThat(parties.sameParty(1, 2)).isTrue();
        assertThat(bobAgain.lastView()).hasSize(2);
    }

    @Test
    void chatReachesAllMembers() {
        form(1, 2);
        parties.chat(2, "熊在這裡！");

        assertThat(alice.texts()).contains("【隊伍】bob：熊在這裡！");
        assertThat(carol.texts()).isEmpty();
    }

    @Test
    void statusChangesPushViewsOnlyWhenChanged() {
        form(1, 2);
        parties.publishStatus(2, new PartyService.MemberStatus(3, 40, 74, "黑松林"));
        int views = alice.views();
        parties.publishStatus(2, new PartyService.MemberStatus(3, 40, 74, "黑松林"));

        assertThat(alice.views()).isEqualTo(views);
        assertThat(alice.lastView()).filteredOn(m -> m.getName().equals("bob"))
            .singleElement()
            .satisfies(m -> {
                assertThat(m.getHp()).isEqualTo(40);
                assertThat(m.getZoneName()).isEqualTo("黑松林");
            });
    }

    private static final class Conn implements PlayerConnection {
        final List<ServerMessage> sent = new ArrayList<>();

        @Override
        public void send(ServerMessage message) {
            sent.add(message);
        }

        @Override
        public void close(String reason) {
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public void attachZone(Zone zone) {
        }

        List<String> texts() {
            return sent.stream().filter(ServerMessage::hasText).map(m -> m.getText().getText()).toList();
        }

        int views() {
            return (int) sent.stream().filter(ServerMessage::hasParty).count();
        }

        List<PartyMember> lastView() {
            return sent.stream().filter(ServerMessage::hasParty).reduce((a, b) -> b).orElseThrow()
                .getParty().getMembersList();
        }
    }
}
