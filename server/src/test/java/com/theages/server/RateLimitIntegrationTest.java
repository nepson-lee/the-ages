package com.theages.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.theages.protocol.v1.ClientMessage;
import com.theages.protocol.v1.Command;
import com.theages.protocol.v1.ServerMessage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

/** 頻率限制的端對端行為：超量提示、持續洗頻斷線、過大訊息斷線（用很緊的限制方便測試）。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "theages.rate-limit.command.burst=3",
    "theages.rate-limit.command.per-second=0.01",
    "theages.rate-limit.abuse-rejections=10",
    "theages.rate-limit.max-message-bytes=512",
})
@ActiveProfiles("test")
class RateLimitIntegrationTest {

    private static final Pattern TOKEN = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"");

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void floodingCommandsIsThrottledThenDisconnected() throws Exception {
        Client c = connect("flooder");
        c.drain();

        for (int i = 0; i < 4; i++) {
            c.command("say " + i);
        }
        assertThat(c.textsWithin(2)).contains("你說：「0」", "你說：「1」", "你說：「2」", "你的動作太快了，請稍候再試。")
            .doesNotContain("你說：「3」");

        int sent = 0;
        while (sent < 20 && c.session.isOpen()) { // 伺服器在第 10 次被擋時就會斷線
            try {
                c.command("say spam");
                sent++;
            } catch (IllegalStateException closedMeanwhile) {
                break;
            }
        }
        CloseStatus status = c.closed.get(5, TimeUnit.SECONDS);
        assertThat(status.getCode()).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());
    }

    @Test
    void oversizedMessageClosesConnection() throws Exception {
        Client c = connect("bigmouth");
        c.drain();
        c.command("say " + "啊".repeat(400)); // 超過 512 bytes

        CloseStatus status = c.closed.get(5, TimeUnit.SECONDS);
        assertThat(status.getCode()).isIn(CloseStatus.TOO_BIG_TO_PROCESS.getCode(), 1009);
    }

    private Client connect(String name) throws Exception {
        String username = name + System.nanoTime() % 100000;
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/register"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"" + username + "\",\"password\":\"password123\"}"))
            .build(), HttpResponse.BodyHandlers.ofString());
        Matcher m = TOKEN.matcher(res.body());
        assertThat(m.find()).isTrue();

        Client client = new Client();
        client.session = new StandardWebSocketClient().execute(new BinaryWebSocketHandler() {
            @Override
            protected void handleBinaryMessage(WebSocketSession s, BinaryMessage message) throws Exception {
                client.received.add(ServerMessage.parseFrom(message.getPayload()));
            }

            @Override
            public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
                client.closed.complete(status);
            }
        }, "ws://localhost:" + port + "/ws?token=" + m.group(1)).get(5, TimeUnit.SECONDS);
        return client;
    }

    private static final class Client {
        final BlockingQueue<ServerMessage> received = new LinkedBlockingQueue<>();
        final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();
        WebSocketSession session;

        void command(String text) throws Exception {
            ClientMessage msg = ClientMessage.newBuilder().setCommand(Command.newBuilder().setText(text)).build();
            session.sendMessage(new BinaryMessage(msg.toByteArray()));
        }

        /** 丟掉進場時收到的訊息（歡迎、快照、look……）。 */
        void drain() throws InterruptedException {
            while (received.poll(500, TimeUnit.MILLISECONDS) != null) {
                // 等到 0.5 秒內沒有新訊息
            }
        }

        java.util.List<String> textsWithin(int seconds) throws InterruptedException {
            java.util.List<String> texts = new java.util.ArrayList<>();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            while (System.nanoTime() < deadline) {
                ServerMessage msg = received.poll(100, TimeUnit.MILLISECONDS);
                if (msg != null && msg.hasText()) {
                    texts.add(msg.getText().getText());
                }
            }
            return texts;
        }
    }
}
