package com.theages.server.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** 登入頻率限制的端對端行為（用很緊的限制方便測試）。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "theages.auth-limit.login-per-ip.burst=100",
    "theages.auth-limit.register-per-ip.burst=3",
    "theages.auth-limit.register-per-ip.per-minute=0.01",
    "theages.auth-limit.max-failures=3",
})
@ActiveProfiles("test")
class AuthRateLimitIntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    LoginThrottle throttle;

    private final HttpClient http = HttpClient.newHttpClient();

    /** 所有請求都來自 127.0.0.1，每個測試重新計算額度。 */
    @BeforeEach
    void resetThrottle() {
        throttle.reset();
    }

    @Test
    void accountLocksAfterRepeatedWrongPasswordsEvenForCorrectOne() throws Exception {
        String name = unique("victim");
        assertThat(post("/api/auth/register", name, "password123").statusCode()).isEqualTo(201);

        for (int i = 0; i < 3; i++) {
            assertThat(post("/api/auth/login", name, "wrong-password").statusCode()).isEqualTo(400);
        }
        HttpResponse<String> locked = post("/api/auth/login", name, "password123");
        assertThat(locked.statusCode()).isEqualTo(429);
        assertThat(locked.headers().firstValue("Retry-After")).hasValueSatisfying(v -> assertThat(Long.parseLong(v)).isPositive());
        assertThat(locked.body()).contains("嘗試次數過多");
    }

    @Test
    void unknownAccountsAreLockedTheSameWay() throws Exception {
        String ghost = unique("ghost");
        for (int i = 0; i < 3; i++) {
            HttpResponse<String> r = post("/api/auth/login", ghost, "whatever123");
            assertThat(r.statusCode()).isEqualTo(400);
            assertThat(r.body()).contains("帳號或密碼錯誤");
        }
        assertThat(post("/api/auth/login", ghost, "whatever123").statusCode())
            .as("和存在的帳號一樣被鎖，看不出帳號存不存在").isEqualTo(429);
    }

    @Test
    void registrationIsLimitedPerIp() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(post("/api/auth/register", unique("spam"), "password123").statusCode()).isEqualTo(201);
        }
        HttpResponse<String> limited = post("/api/auth/register", unique("spam"), "password123");
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.headers().firstValue("Retry-After")).isPresent();
    }

    private static String unique(String prefix) {
        return prefix + Long.toString(System.nanoTime() % 1_000_000);
    }

    private HttpResponse<String> post(String path, String username, String password) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(
                "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
            .build(), HttpResponse.BodyHandlers.ofString());
    }
}
