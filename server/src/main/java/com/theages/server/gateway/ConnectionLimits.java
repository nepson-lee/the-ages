package com.theages.server.gateway;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongSupplier;

/** 一條連線的頻率限制狀態：每種訊息一個權杖桶，加上濫用判定。 */
final class ConnectionLimits {

    enum Kind { MOVE, ATTACK, COMMAND }

    /** 訊息被擋時該怎麼處理。 */
    enum Verdict {
        /** 放行。 */
        ALLOW,
        /** 丟掉，並提示玩家「動作太快」。 */
        REJECT_AND_NOTIFY,
        /** 丟掉，不提示（剛提示過）。 */
        REJECT,
        /** 濫用：斷線。 */
        DISCONNECT
    }

    private final TokenBucket move;
    private final TokenBucket attack;
    private final TokenBucket command;
    private final LongSupplier nanoClock;
    private final long noticeIntervalNanos;
    private final long abuseWindowNanos;
    private final int abuseRejections;
    private final Deque<Long> rejections = new ArrayDeque<>();
    private long lastNotice = Long.MIN_VALUE / 2;

    ConnectionLimits(RateLimitProperties p, LongSupplier nanoClock) {
        this.move = bucket(p.move(), nanoClock);
        this.attack = bucket(p.attack(), nanoClock);
        this.command = bucket(p.command(), nanoClock);
        this.nanoClock = nanoClock;
        this.noticeIntervalNanos = p.noticeInterval().toNanos();
        this.abuseWindowNanos = p.abuseWindow().toNanos();
        this.abuseRejections = p.abuseRejections();
    }

    private static TokenBucket bucket(RateLimitProperties.Bucket b, LongSupplier clock) {
        return new TokenBucket(b.burst(), b.perSecond(), clock);
    }

    synchronized Verdict check(Kind kind) {
        TokenBucket bucket = switch (kind) {
            case MOVE -> move;
            case ATTACK -> attack;
            case COMMAND -> command;
        };
        if (bucket.tryAcquire()) {
            return Verdict.ALLOW;
        }
        long now = nanoClock.getAsLong();
        rejections.addLast(now);
        while (!rejections.isEmpty() && now - rejections.peekFirst() > abuseWindowNanos) {
            rejections.removeFirst();
        }
        if (rejections.size() >= abuseRejections) {
            return Verdict.DISCONNECT;
        }
        if (now - lastNotice >= noticeIntervalNanos) {
            lastNotice = now;
            return Verdict.REJECT_AND_NOTIFY;
        }
        return Verdict.REJECT;
    }
}
