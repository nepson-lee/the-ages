package com.theages.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.theages.protocol.v1.ServerMessage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

/** 從註冊、登入到 WebSocket 進入遊戲的端對端流程（使用 H2）。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GameFlowIntegrationTest {

    private static final Pattern TOKEN = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"");

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void registerLoginAndEnterWorld() throws Exception {
        HttpResponse<String> register = post("/api/auth/register", "{\"username\":\"hero\",\"password\":\"password123\"}");
        assertThat(register.statusCode()).isEqualTo(201);

        assertThat(post("/api/auth/register", "{\"username\":\"hero\",\"password\":\"password123\"}").statusCode())
            .isEqualTo(400);
        assertThat(post("/api/auth/login", "{\"username\":\"hero\",\"password\":\"wrong-password\"}").statusCode())
            .isEqualTo(400);
        HttpResponse<String> invalid = post("/api/auth/register", "{\"username\":\"x\",\"password\":\"password123\"}");
        assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(invalid.body()).contains("3-16 個英數字");

        HttpResponse<String> login = post("/api/auth/login", "{\"username\":\"hero\",\"password\":\"password123\"}");
        assertThat(login.statusCode()).isEqualTo(200);
        Matcher m = TOKEN.matcher(login.body());
        assertThat(m.find()).isTrue();

        BlockingQueue<ServerMessage> received = new LinkedBlockingQueue<>();
        WebSocketSession session = new StandardWebSocketClient()
            .execute(new BinaryWebSocketHandler() {
                @Override
                protected void handleBinaryMessage(WebSocketSession s, BinaryMessage message) throws Exception {
                    received.add(ServerMessage.parseFrom(message.getPayload()));
                }
            }, "ws://localhost:" + port + "/ws?token=" + m.group(1))
            .get(5, TimeUnit.SECONDS);

        ServerMessage welcome = received.poll(5, TimeUnit.SECONDS);
        assertThat(welcome).isNotNull();
        assertThat(welcome.getWelcome().getZoneId()).isEqualTo("newbie-village");
        ServerMessage full = received.poll(5, TimeUnit.SECONDS);
        assertThat(full.getSnapshot().getFull()).isTrue();
        assertThat(full.getSnapshot().getEntitiesList())
            .as("content.yml 的 NPC 已生成")
            .anyMatch(e -> e.getKind() == com.theages.protocol.v1.EntityKind.ENTITY_KIND_NPC && e.getModel().equals("wolf"))
            .anyMatch(e -> e.getKind() == com.theages.protocol.v1.EntityKind.ENTITY_KIND_FRIENDLY && e.getModel().equals("village-elder"));

        ServerMessage inventory = null;
        ServerMessage stats = null;
        for (ServerMessage msg; (inventory == null || stats == null) && (msg = received.poll(5, TimeUnit.SECONDS)) != null; ) {
            if (msg.hasInventory()) {
                inventory = msg;
            } else if (msg.hasSelfStats()) {
                stats = msg;
            }
        }
        assertThat(stats).isNotNull();
        assertThat(stats.getSelfStats().getGold()).as("出生銅錢").isEqualTo(30);
        assertThat(inventory).as("進場後會收到背包").isNotNull();
        assertThat(inventory.getInventory().getItemsList())
            .as("出生裝備已穿上")
            .anyMatch(i -> i.getTemplateId().equals("wooden-sword") && i.getEquipped())
            .anyMatch(i -> i.getTemplateId().equals("cloth-shirt") && i.getEquipped());
        session.close();
    }

    @Test
    void websocketRejectsMissingToken() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new StandardWebSocketClient()
                .execute(new BinaryWebSocketHandler(), "ws://localhost:" + port + "/ws")
                .get(5, TimeUnit.SECONDS))
            .isInstanceOf(java.util.concurrent.ExecutionException.class);
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build(), HttpResponse.BodyHandlers.ofString());
    }
}
