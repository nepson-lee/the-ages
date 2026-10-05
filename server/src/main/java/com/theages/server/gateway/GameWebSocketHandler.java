package com.theages.server.gateway;

import com.google.protobuf.InvalidProtocolBufferException;
import com.theages.protocol.v1.ClientMessage;
import com.theages.server.character.CharacterItemRepository;
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
    private static final int MAX_COMMAND_LENGTH = 256;

    private final World world;
    private final PlayerCharacterRepository characters;
    private final CharacterItemRepository items;
    /** 所有區域的線上角色，用來處理同一角色重複登入。 */
    private final Map<Long, WebSocketPlayerConnection> online = new ConcurrentHashMap<>();

    public GameWebSocketHandler(World world, PlayerCharacterRepository characters, CharacterItemRepository items) {
        this.world = world;
        this.characters = characters;
        this.items = items;
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
        zone.enqueue(new ZoneEvent.Join(connection, c.getId(), c.getName(), c.getPosX(), c.getPosZ(),
            c.getLevel(), c.getExp(), c.getHp(), c.getGold(), items.loadRecords(c.getId())));
        log.info("{} 連線進入 {}", username, zone.definition().id());
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        WebSocketPlayerConnection connection = (WebSocketPlayerConnection) session.getAttributes().get(CONNECTION_ATTR);
        if (connection == null) {
            return;
        }
        ClientMessage msg;
        try {
            msg = ClientMessage.parseFrom(message.getPayload());
        } catch (InvalidProtocolBufferException e) {
            session.close(CloseStatus.BAD_DATA);
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
