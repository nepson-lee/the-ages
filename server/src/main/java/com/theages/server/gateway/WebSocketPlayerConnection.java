package com.theages.server.gateway;

import com.theages.protocol.v1.ServerMessage;
import com.theages.server.world.PlayerConnection;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/**
 * 把 WebSocketSession 包成區域可用的連線。
 * Decorator 負責執行緒安全與緩衝；客戶端太慢、緩衝爆掉時會直接斷線，避免拖慢 tick。
 */
final class WebSocketPlayerConnection implements PlayerConnection {

    private static final Logger log = LoggerFactory.getLogger(WebSocketPlayerConnection.class);
    private static final int SEND_TIME_LIMIT_MS = 2_000;
    private static final int BUFFER_SIZE_LIMIT = 512 * 1024;

    private final WebSocketSession session;

    WebSocketPlayerConnection(WebSocketSession session) {
        this.session = new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT,
            ConcurrentWebSocketSessionDecorator.OverflowStrategy.TERMINATE);
    }

    @Override
    public void send(ServerMessage message) {
        if (!session.isOpen()) {
            return;
        }
        try {
            session.sendMessage(new BinaryMessage(message.toByteArray()));
        } catch (IOException | RuntimeException e) {
            log.debug("送出訊息失敗，session {}", session.getId(), e);
        }
    }

    @Override
    public void close(String reason) {
        try {
            session.close(CloseStatus.POLICY_VIOLATION.withReason(reason));
        } catch (IOException e) {
            log.debug("關閉 session {} 失敗", session.getId(), e);
        }
    }
}
