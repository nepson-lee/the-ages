package com.theages.server.gateway;

import com.google.protobuf.InvalidProtocolBufferException;
import com.theages.protocol.v1.ClientMessage;
import com.theages.protocol.v1.ServerMessage;
import com.theages.protocol.v1.TextChannel;
import com.theages.protocol.v1.TextOutput;
import com.theages.server.character.CharacterItemRepository;
import com.theages.server.character.CharacterQuestRepository;
import com.theages.server.character.CharacterSkillRepository;
import com.theages.server.character.PlayerCharacter;
import com.theages.server.character.PlayerCharacterRepository;
import com.theages.server.world.World;
import com.theages.server.world.Zone;
import com.theages.server.world.ZoneEvent;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

/**
 * WebSocket 進出口：只負責解碼、驗證格式，再轉成 {@link ZoneEvent} 丟進玩家目前所在的區域。
 * 這裡跑在 Web 容器的執行緒上，不可直接碰區域狀態。
 */
@Component
public class GameWebSocketHandler extends BinaryWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(GameWebSocketHandler.class);
    private static final String CONNECTION_ATTR = "connection";
    private static final String LIMITS_ATTR = "limits";
    private static final ServerMessage TOO_FAST = ServerMessage.newBuilder()
        .setText(TextOutput.newBuilder().setChannel(TextChannel.TEXT_CHANNEL_SYSTEM).setText("你的動作太快了，請稍候再試。"))
        .build();
    private static final int MAX_COMMAND_LENGTH = 256;

    private final World world;
    private final PlayerCharacterRepository characters;
    private final CharacterItemRepository items;
    private final CharacterQuestRepository quests;
    private final CharacterSkillRepository skills;
    private final RateLimitProperties rateLimits;
    /** 所有區域的線上角色，用來處理同一角色重複登入。 */
    private final Map<Long, WebSocketPlayerConnection> online = new ConcurrentHashMap<>();

    public GameWebSocketHandler(World world, PlayerCharacterRepository characters, CharacterItemRepository items,
                                CharacterQuestRepository quests, CharacterSkillRepository skills,
                                RateLimitProperties rateLimits) {
        this.world = world;
        this.characters = characters;
        this.items = items;
        this.quests = quests;
        this.skills = skills;
        this.rateLimits = rateLimits;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String username = (String) session.getAttributes().get(JwtHandshakeInterceptor.USERNAME_ATTR);
        Optional<PlayerCharacter> character = characters.findByName(username);
        if (character.isEmpty()) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("找不到角色"));
            return;
        }
        PlayerCharacter c = character.get();
        // 已經在線上：送進他目前所在的區域，由區域沿用記憶體中的狀態並踢掉舊連線。
        // （資料庫裡的區域與位置可能還沒寫入，不能拿來決定區域）
        WebSocketPlayerConnection previous = online.get(c.getId());
        Zone zone = previous != null && previous.isOpen() ? previous.zone() : world.zoneOrStart(c.getZoneId());
        WebSocketPlayerConnection connection = new WebSocketPlayerConnection(session, c.getId(), zone);
        online.put(c.getId(), connection);
        session.getAttributes().put(CONNECTION_ATTR, connection);
        session.getAttributes().put(LIMITS_ATTR, new ConnectionLimits(rateLimits, System::nanoTime));
        zone.enqueue(new ZoneEvent.Join(connection, c.getId(), c.getName(), c.getPosX(), c.getPosZ(),
            c.getLevel(), c.getExp(), c.getHp(), c.getGold(), c.getMp(), items.loadRecords(c.getId()),
            quests.loadRecords(c.getId()), skills.loadIds(c.getId())));
        log.info("{} 連線進入 {}", username, zone.definition().id());
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        WebSocketPlayerConnection connection = (WebSocketPlayerConnection) session.getAttributes().get(CONNECTION_ATTR);
        ConnectionLimits limits = (ConnectionLimits) session.getAttributes().get(LIMITS_ATTR);
        if (connection == null || limits == null) {
            return;
        }
        if (message.getPayloadLength() > rateLimits.maxMessageBytes()) {
            session.close(CloseStatus.TOO_BIG_TO_PROCESS);
            return;
        }
        ClientMessage msg;
        try {
            msg = ClientMessage.parseFrom(message.getPayload());
        } catch (InvalidProtocolBufferException e) {
            session.close(CloseStatus.BAD_DATA);
            return;
        }
        ConnectionLimits.Kind kind = switch (msg.getPayloadCase()) {
            case MOVE_TO -> ConnectionLimits.Kind.MOVE;
            case ATTACK -> ConnectionLimits.Kind.ATTACK;
            case COMMAND -> ConnectionLimits.Kind.COMMAND;
            case PAYLOAD_NOT_SET -> null;
        };
        if (kind == null || !admit(session, connection, limits.check(kind))) {
            return;
        }
        Zone zone = connection.zone(); // 換區後會指向新的區域
        switch (msg.getPayloadCase()) {
            case MOVE_TO -> zone.enqueue(new ZoneEvent.Move(connection,
                msg.getMoveTo().getTarget().getX(), msg.getMoveTo().getTarget().getZ()));
            case ATTACK -> zone.enqueue(new ZoneEvent.Attack(connection, msg.getAttack().getTargetId()));
            case COMMAND -> {
                String text = msg.getCommand().getText();
                if (text.length() <= MAX_COMMAND_LENGTH) {
                    zone.enqueue(new ZoneEvent.CommandText(connection, text));
                }
            }
            case PAYLOAD_NOT_SET -> {
            }
        }
    }

    /** 依頻率限制的判定放行、提示或斷線；回傳 true 表示放行。 */
    private boolean admit(WebSocketSession session, WebSocketPlayerConnection connection,
                          ConnectionLimits.Verdict verdict) throws java.io.IOException {
        switch (verdict) {
            case ALLOW -> {
                return true;
            }
            case REJECT_AND_NOTIFY -> connection.send(TOO_FAST);
            case REJECT -> {
            }
            case DISCONNECT -> {
                log.warn("角色 {} 送出訊息過於頻繁，中斷連線", connection.characterId());
                session.close(CloseStatus.POLICY_VIOLATION.withReason("指令太頻繁，已中斷連線"));
            }
        }
        return false;
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        WebSocketPlayerConnection connection = (WebSocketPlayerConnection) session.getAttributes().get(CONNECTION_ATTR);
        if (connection == null) {
            return;
        }
        online.remove(connection.characterId(), connection);
        // 若剛好在換區途中，這個 Leave 可能送錯區域；目標區域會在下個 tick 發現連線已關閉並自行移除
        connection.zone().enqueue(new ZoneEvent.Leave(connection));
    }
}
