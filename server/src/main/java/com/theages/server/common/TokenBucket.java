package com.theages.server.common;

import java.util.function.LongSupplier;

/**
 * 權杖桶：最多存 capacity 個權杖，每秒補 refillPerSecond 個；每個動作花一個。
 * 允許短時間的連續操作（用掉存量），但長時間的平均速度不能超過補充速度。
 */
public final class TokenBucket {

    private final double capacity;
    private final double refillPerNano;
    private final LongSupplier nanoClock;
    private double tokens;
    private long lastRefill;

    public TokenBucket(double capacity, double refillPerSecond, LongSupplier nanoClock) {
        this.capacity = capacity;
        this.refillPerNano = refillPerSecond / 1_000_000_000.0;
        this.nanoClock = nanoClock;
        this.tokens = capacity;
        this.lastRefill = nanoClock.getAsLong();
    }

    /** 有權杖就花一個並回傳 true。 */
    public synchronized boolean tryAcquire() {
        refill();
        if (tokens >= 1) {
            tokens -= 1;
            return true;
        }
        return false;
    }

    /** 還要多久（奈秒）才會有下一個權杖；0 = 現在就有。 */
    public synchronized long nanosUntilAvailable() {
        refill();
        return tokens >= 1 ? 0 : (long) Math.ceil((1 - tokens) / refillPerNano);
    }

    /** 已經補滿而且一段時間沒用：可以丟掉以節省記憶體。 */
    public synchronized boolean isFull() {
        refill();
        return tokens >= capacity;
    }

    private void refill() {
        long now = nanoClock.getAsLong();
        tokens = Math.min(capacity, tokens + (now - lastRefill) * refillPerNano);
        lastRefill = now;
    }
}
