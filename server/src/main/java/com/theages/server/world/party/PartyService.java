package com.theages.server.world.party;

import com.theages.protocol.v1.PartyMember;
import com.theages.protocol.v1.PartyView;
import com.theages.protocol.v1.ServerMessage;
import com.theages.protocol.v1.TextChannel;
import com.theages.protocol.v1.TextOutput;
import com.theages.server.world.PlayerConnection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 組隊。隊員可能分散在不同區域（不同執行緒），所以這是全域、以 synchronized 保護的狀態。
 *
 * <p>規則：
 * <ul>
 *   <li>隊伍只存在記憶體中；離線就退出隊伍，換區不影響。</li>
 *   <li>沒有隊伍的人邀請別人，對方接受時才成立隊伍，邀請者是隊長；有隊伍時只有隊長能邀請。</li>
 *   <li>隊長離開時由下一位隊員接任；剩一個人時隊伍解散。</li>
 * </ul>
 *
 * <p>所有要送出的訊息都先收集起來，離開鎖之後才送，避免某條慢連線拖住其他區域。
 */
@Component
public class PartyService {

    public static final int MAX_SIZE = 5;
    static final long INVITE_TTL_MILLIS = 60_000;

    /** 隊伍的不可變快照，區域可以放心持有。 */
    public record Party(int id, long leader, List<Long> members) {

        public boolean contains(long characterId) {
            return members.contains(characterId);
        }
    }

    /** 隊員顯示在隊伍面板上的數值，由隊員所在的區域定期回報。 */
    public record MemberStatus(int level, int hp, int maxHp, String zoneName) {
    }

    private record Online(long characterId, String name, PlayerConnection connection) {
    }

    private record Invite(long from, long expiresAt) {
    }

    private record Outgoing(PlayerConnection to, ServerMessage message) {
    }

    private final LongSupplier clock;
    private final Map<Long, Online> online = new HashMap<>();
    private final Map<String, Long> byName = new HashMap<>();
    private final Map<Long, Party> partyOf = new HashMap<>();
    private final Map<Long, Invite> invites = new HashMap<>();
    private final Map<Long, MemberStatus> status = new HashMap<>();
    private int nextPartyId = 1;

    @Autowired
    public PartyService() {
        this(System::currentTimeMillis);
    }

    public PartyService(LongSupplier clock) {
        this.clock = clock;
    }

    // ===== 上線、離線 =====

    /** 進入任何區域時呼叫（包括換區、重複登入換連線）。 */
    public void online(long characterId, String name, PlayerConnection connection) {
        List<Outgoing> out = new ArrayList<>();
        synchronized (this) {
            online.put(characterId, new Online(characterId, name, connection));
            byName.put(name.toLowerCase(), characterId);
            Party party = partyOf.get(characterId);
            if (party != null) {
                out.add(new Outgoing(connection, view(party, characterId)));
            }
        }
        send(out);
    }

    /** 離線時呼叫；connection 不是目前的連線（已被重複登入取代）就忽略。 */
    public void offline(long characterId, PlayerConnection connection) {
        List<Outgoing> out = new ArrayList<>();
        synchronized (this) {
            Online o = online.get(characterId);
            if (o == null || o.connection() != connection) {
                return;
            }
            leaveLocked(characterId, out, o.name() + " 離線了，退出了隊伍。", null);
            online.remove(characterId);
            byName.remove(o.name().toLowerCase());
            invites.remove(characterId);
            status.remove(characterId);
        }
        send(out);
    }

    // ===== 查詢（區域在 tick 執行緒上呼叫） =====

    public synchronized Optional<Party> partyOf(long characterId) {
        return Optional.ofNullable(partyOf.get(characterId));
    }

    public synchronized boolean sameParty(long a, long b) {
        Party p = partyOf.get(a);
        return p != null && p.contains(b);
    }

    // ===== 指令 =====

    public void invite(long from, String targetName) {
        List<Outgoing> out = new ArrayList<>();
        synchronized (this) {
            Online inviter = online.get(from);
            Long targetId = byName.get(targetName.toLowerCase());
            Party party = partyOf.get(from);
            if (inviter == null) {
                return;
            }
            if (targetId == null) {
                tell(out, inviter, "線上沒有叫「" + targetName + "」的人。");
            } else if (targetId == from) {
                tell(out, inviter, "你不能邀請自己。");
            } else if (party != null && party.leader() != from) {
                tell(out, inviter, "只有隊長可以邀請別人。");
            } else if (party != null && party.members().size() >= MAX_SIZE) {
                tell(out, inviter, "隊伍已經滿了（最多 " + MAX_SIZE + " 人）。");
            } else if (partyOf.containsKey(targetId)) {
                tell(out, inviter, online.get(targetId).name() + " 已經有隊伍了。");
            } else {
                Online target = online.get(targetId);
                invites.put(targetId, new Invite(from, clock.getAsLong() + INVITE_TTL_MILLIS));
                tell(out, inviter, "你邀請 " + target.name() + " 加入隊伍。");
                tell(out, target, inviter.name() + " 邀請你加入隊伍。（輸入 accept 接受，60 秒內有效）");
            }
        }
        send(out);
    }

    public void accept(long characterId) {
        List<Outgoing> out = new ArrayList<>();
        synchronized (this) {
            Online me = online.get(characterId);
            Invite invite = invites.remove(characterId);
            if (me == null) {
                return;
            }
            Online inviter = invite == null ? null : online.get(invite.from());
            if (invite == null || invite.expiresAt() < clock.getAsLong() || inviter == null) {
                tell(out, me, "沒有人邀請你，或邀請已經過期了。");
            } else if (partyOf.containsKey(characterId)) {
                tell(out, me, "你已經有隊伍了。");
            } else {
                Party party = partyOf.get(inviter.characterId());
                if (party != null && (party.leader() != inviter.characterId() || party.members().size() >= MAX_SIZE)) {
                    tell(out, me, "那支隊伍已經不能再加人了。");
                } else {
                    List<Long> members = new ArrayList<>(party == null ? List.of(inviter.characterId()) : party.members());
                    members.add(characterId);
                    Party updated = new Party(party == null ? nextPartyId++ : party.id(), inviter.characterId(), List.copyOf(members));
                    install(updated);
                    announce(out, updated, me.name() + " 加入了隊伍。");
                    broadcastView(out, updated);
                }
            }
        }
        send(out);
    }

    public void leave(long characterId) {
        List<Outgoing> out = new ArrayList<>();
        synchronized (this) {
            Online me = online.get(characterId);
            if (me == null) {
                return;
            }
            if (!partyOf.containsKey(characterId)) {
                tell(out, me, "你不在任何隊伍中。");
            } else {
                leaveLocked(characterId, out, me.name() + " 離開了隊伍。", "你離開了隊伍。");
            }
        }
        send(out);
    }

    public void kick(long leader, String targetName) {
        List<Outgoing> out = new ArrayList<>();
        synchronized (this) {
            Online me = online.get(leader);
            Party party = partyOf.get(leader);
            Long targetId = byName.get(targetName.toLowerCase());
            if (me == null) {
                return;
            }
            if (party == null || party.leader() != leader) {
                tell(out, me, "只有隊長可以把人踢出隊伍。");
            } else if (targetId == null || !party.contains(targetId) || targetId == leader) {
                tell(out, me, "隊伍裡沒有「" + targetName + "」。");
            } else {
                leaveLocked(targetId, out, online.get(targetId).name() + " 被踢出了隊伍。", "你被踢出了隊伍。");
            }
        }
        send(out);
    }

    public void chat(long from, String text) {
        List<Outgoing> out = new ArrayList<>();
        synchronized (this) {
            Online me = online.get(from);
            Party party = partyOf.get(from);
            if (me == null) {
                return;
            }
            if (party == null) {
                tell(out, me, "你不在任何隊伍中。");
            } else {
                announce(out, party, "【隊伍】" + me.name() + "：" + text);
            }
        }
        send(out);
    }

    /** 列出隊員，回傳給指令顯示。 */
    public synchronized String describe(long characterId) {
        Party party = partyOf.get(characterId);
        if (party == null) {
            return "你不在任何隊伍中。（invite <名字> 邀請別人）";
        }
        StringBuilder sb = new StringBuilder("隊伍成員（" + party.members().size() + "/" + MAX_SIZE + "）：");
        for (long id : party.members()) {
            Online o = online.get(id);
            MemberStatus s = status.get(id);
            sb.append("\n  ").append(id == party.leader() ? "★" : "　").append(o == null ? "?" : o.name());
            if (s != null) {
                sb.append(String.format("  Lv%d  生命 %d/%d  %s", s.level(), s.hp(), s.maxHp(), s.zoneName()));
            }
        }
        return sb.toString();
    }

    /** 區域定期回報隊員數值；有變動才通知隊伍。 */
    public void publishStatus(long characterId, MemberStatus newStatus) {
        List<Outgoing> out = new ArrayList<>();
        synchronized (this) {
            Party party = partyOf.get(characterId);
            if (newStatus.equals(status.put(characterId, newStatus)) || party == null) {
                return;
            }
            broadcastView(out, party);
        }
        send(out);
    }

    // ===== 內部（呼叫時必須持有鎖） =====

    /** selfMessage 是給離開者本人的訊息；null = 不通知（例如已經離線）。 */
    private void leaveLocked(long characterId, List<Outgoing> out, String message, String selfMessage) {
        Party party = partyOf.remove(characterId);
        if (party == null) {
            return;
        }
        Online leaving = online.get(characterId);
        if (leaving != null && selfMessage != null) {
            tell(out, leaving, selfMessage);
            out.add(new Outgoing(leaving.connection(), emptyView()));
        }
        List<Long> rest = new ArrayList<>(party.members());
        rest.remove(characterId);
        if (rest.size() <= 1) {
            for (long id : rest) {
                partyOf.remove(id);
                Online o = online.get(id);
                if (o != null) {
                    tell(out, o, message + "隊伍解散了。");
                    out.add(new Outgoing(o.connection(), emptyView()));
                }
            }
            return;
        }
        long leader = party.leader() == characterId ? rest.get(0) : party.leader();
        Party updated = new Party(party.id(), leader, List.copyOf(rest));
        install(updated);
        announce(out, updated, message + (leader != party.leader() ? online.get(leader).name() + " 成為新的隊長。" : ""));
        broadcastView(out, updated);
    }

    private void install(Party party) {
        for (long id : party.members()) {
            partyOf.put(id, party);
        }
    }

    private void announce(List<Outgoing> out, Party party, String text) {
        for (long id : party.members()) {
            Online o = online.get(id);
            if (o != null) {
                tell(out, o, text);
            }
        }
    }

    private void broadcastView(List<Outgoing> out, Party party) {
        for (long id : party.members()) {
            Online o = online.get(id);
            if (o != null) {
                out.add(new Outgoing(o.connection(), view(party, id)));
            }
        }
    }

    private ServerMessage view(Party party, long viewer) {
        PartyView.Builder view = PartyView.newBuilder();
        Map<Long, Online> ordered = new LinkedHashMap<>();
        party.members().forEach(id -> ordered.put(id, online.get(id)));
        ordered.forEach((id, o) -> {
            MemberStatus s = status.getOrDefault(id, new MemberStatus(0, 0, 0, ""));
            view.addMembers(PartyMember.newBuilder()
                .setName(o == null ? "?" : o.name())
                .setLevel(s.level())
                .setHp(s.hp())
                .setMaxHp(s.maxHp())
                .setZoneName(s.zoneName())
                .setLeader(id == party.leader())
                .setSelf(id == viewer));
        });
        return ServerMessage.newBuilder().setParty(view).build();
    }

    private static ServerMessage emptyView() {
        return ServerMessage.newBuilder().setParty(PartyView.getDefaultInstance()).build();
    }

    private static void tell(List<Outgoing> out, Online to, String text) {
        out.add(new Outgoing(to.connection(), ServerMessage.newBuilder()
            .setText(TextOutput.newBuilder().setChannel(TextChannel.TEXT_CHANNEL_PARTY).setText(text))
            .build()));
    }

    private static void send(List<Outgoing> out) {
        for (Outgoing o : out) {
            o.to().send(o.message());
        }
    }
}
