package com.theages.server.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.theages.server.common.TokenBucket;
import com.theages.server.gateway.ConnectionLimits.Kind;
import com.theages.server.gateway.ConnectionLimits.Verdict;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ConnectionLimitsTest {

    private static final long SECOND = 1_000_000_000L;

    private final AtomicLong now = new AtomicLong(0);

    private ConnectionLimits limits(int abuseRejections) {
        return new ConnectionLimits(new RateLimitProperties(
            new RateLimitProperties.Bucket(10, 5),
            new RateLimitProperties.Bucket(5, 2),
            new RateLimitProperties.Bucket(3, 1),
            Duration.ofSeconds(3), Duration.ofSeconds(10), abuseRejections, 4096), now::get);
    }

    @Test
    void bucketAllowsBurstThenRefillsOverTime() {
        TokenBucket bucket = new TokenBucket(3, 2, now::get);
        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).isFalse();

        now.addAndGet(SECOND / 2); // 每秒補 2 個 → 半秒補 1 個
        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).isFalse();

        now.addAndGet(100 * SECOND); // 補滿也不會超過容量
        int allowed = 0;
        while (bucket.tryAcquire()) {
            allowed++;
        }
        assertThat(allowed).isEqualTo(3);
    }

    @Test
    void kindsHaveSeparateBuckets() {
        ConnectionLimits limits = limits(50);
        for (int i = 0; i < 3; i++) {
            assertThat(limits.check(Kind.COMMAND)).isEqualTo(Verdict.ALLOW);
        }
        assertThat(limits.check(Kind.COMMAND)).isNotEqualTo(Verdict.ALLOW);
        assertThat(limits.check(Kind.MOVE)).as("指令用完不影響移動").isEqualTo(Verdict.ALLOW);
    }

    @Test
    void noticeIsThrottled() {
        ConnectionLimits limits = limits(50);
        for (int i = 0; i < 3; i++) {
            limits.check(Kind.COMMAND);
        }
        assertThat(limits.check(Kind.COMMAND)).isEqualTo(Verdict.REJECT_AND_NOTIFY);
        assertThat(limits.check(Kind.COMMAND)).isEqualTo(Verdict.REJECT);

        now.addAndGet(3 * SECOND); // 3 秒後補了 3 個指令，而且提示間隔已過
        for (int i = 0; i < 3; i++) {
            assertThat(limits.check(Kind.COMMAND)).isEqualTo(Verdict.ALLOW);
        }
        assertThat(limits.check(Kind.COMMAND)).isEqualTo(Verdict.REJECT_AND_NOTIFY);
    }

    @Test
    void sustainedFloodingDisconnects() {
        ConnectionLimits limits = limits(5);
        for (int i = 0; i < 3; i++) {
            limits.check(Kind.COMMAND);
        }
        Verdict last = null;
        for (int i = 0; i < 5; i++) {
            last = limits.check(Kind.COMMAND);
        }
        assertThat(last).isEqualTo(Verdict.DISCONNECT);
    }

    @Test
    void oldRejectionsFallOutOfTheAbuseWindow() {
        ConnectionLimits limits = limits(5);
        for (int i = 0; i < 3; i++) {
            limits.check(Kind.COMMAND);
        }
        for (int i = 0; i < 4; i++) {
            limits.check(Kind.COMMAND); // 被擋 4 次，還沒到 5 次
        }
        now.addAndGet(11 * SECOND); // 時間窗過了，之前的紀錄不算
        for (int i = 0; i < 3; i++) {
            limits.check(Kind.COMMAND); // 這 11 秒補回來的
        }
        assertThat(limits.check(Kind.COMMAND)).isNotEqualTo(Verdict.DISCONNECT);
    }
}
