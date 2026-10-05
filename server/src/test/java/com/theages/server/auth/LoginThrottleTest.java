package com.theages.server.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class LoginThrottleTest {

    private static final long SECOND = 1_000_000_000L;

    private final AtomicLong now = new AtomicLong(0);
    private final LoginThrottle throttle = new LoginThrottle(new AuthLimitProperties(
        new AuthLimitProperties.Bucket(3, 6),   // 登入：連續 3 次，之後每 10 秒 1 次
        new AuthLimitProperties.Bucket(1, 1),   // 註冊：1 次，之後每分鐘 1 次
        3, Duration.ofMinutes(15), Duration.ofMinutes(15)), now::get);

    @Test
    void ipBucketLimitsAttemptsAndReportsRetryAfter() {
        for (int i = 0; i < 3; i++) {
            assertThat(throttle.acquire("1.2.3.4", LoginThrottle.Action.LOGIN)).isEmpty();
        }
        assertThat(throttle.acquire("1.2.3.4", LoginThrottle.Action.LOGIN)).hasValue(Duration.ofSeconds(10));
        assertThat(throttle.acquire("5.6.7.8", LoginThrottle.Action.LOGIN)).as("別的 IP 不受影響").isEmpty();
        assertThat(throttle.acquire("1.2.3.4", LoginThrottle.Action.REGISTER)).as("註冊分開計算").isEmpty();

        now.addAndGet(10 * SECOND);
        assertThat(throttle.acquire("1.2.3.4", LoginThrottle.Action.LOGIN)).isEmpty();
    }

    @Test
    void accountLocksAfterRepeatedFailures() {
        throttle.recordFailure("Alice");
        throttle.recordFailure("alice");
        assertThat(throttle.lockedFor("alice")).isEmpty();

        throttle.recordFailure("ALICE"); // 大小寫算同一個帳號
        assertThat(throttle.lockedFor("alice")).hasValue(Duration.ofMinutes(15));
        assertThat(throttle.lockedFor("bob")).isEmpty();

        now.addAndGet(Duration.ofMinutes(15).toNanos());
        assertThat(throttle.lockedFor("alice")).as("時間到自動解鎖").isEmpty();
    }

    @Test
    void successResetsFailures() {
        throttle.recordFailure("alice");
        throttle.recordFailure("alice");
        throttle.recordSuccess("alice");
        throttle.recordFailure("alice");
        throttle.recordFailure("alice");

        assertThat(throttle.lockedFor("alice")).isEmpty();
    }

    @Test
    void failuresOutsideTheWindowDoNotAccumulate() {
        throttle.recordFailure("alice");
        throttle.recordFailure("alice");
        now.addAndGet(Duration.ofMinutes(16).toNanos());
        throttle.recordFailure("alice");

        assertThat(throttle.lockedFor("alice")).isEmpty();
    }

    @Test
    void idleEntriesAreCleanedUp() {
        throttle.acquire("1.2.3.4", LoginThrottle.Action.LOGIN);
        throttle.recordFailure("alice");
        assertThat(throttle.trackedEntries()).isEqualTo(2);

        now.addAndGet(Duration.ofMinutes(20).toNanos()); // 桶補滿、失敗紀錄過期
        throttle.acquire("9.9.9.9", LoginThrottle.Action.LOGIN); // 觸發清理

        assertThat(throttle.trackedEntries()).as("只剩剛剛這個 IP").isEqualTo(1);
    }
}
