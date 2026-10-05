package com.theages.server.auth;

import com.theages.server.common.TokenBucket;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 註冊、登入的頻率限制，資料只放在記憶體（重啟就清空；多台伺服器時各自計算）。
 *
 * <ul>
 *   <li>每個 IP 一個權杖桶：擋同一來源大量嘗試（撞庫、大量註冊）。</li>
 *   <li>每個帳號的失敗次數：時間窗內失敗太多次就鎖住一段時間，擋針對單一帳號的暴力破解。
 *       不存在的帳號也照樣計算，回應和存在的帳號一樣，避免被拿來試探帳號是否存在。</li>
 * </ul>
 *
 * <p>帳號鎖定的代價：別人可以故意打錯密碼讓你暫時無法登入。鎖定時間刻意設得短（預設 15 分鐘）。
 */
@Component
public class LoginThrottle {

    public enum Action { LOGIN, REGISTER }

    /** 某個帳號的失敗紀錄。 */
    private static final class Failures {
        int count;
        long windowStart;
        long lockedUntil;
    }

    /** 每隔多久順手清掉不再需要的紀錄（避免記憶體被大量不同 IP、帳號塞爆）。 */
    private static final long CLEANUP_INTERVAL_NANOS = Duration.ofMinutes(1).toNanos();

    private final AuthLimitProperties properties;
    private final LongSupplier nanoClock;
    private final Map<String, TokenBucket> loginBuckets = new ConcurrentHashMap<>();
    private final Map<String, TokenBucket> registerBuckets = new ConcurrentHashMap<>();
    private final Map<String, Failures> failures = new ConcurrentHashMap<>();
    private volatile long lastCleanup;

    @Autowired
    public LoginThrottle(AuthLimitProperties properties) {
        this(properties, System::nanoTime);
    }

    LoginThrottle(AuthLimitProperties properties, LongSupplier nanoClock) {
        this.properties = properties;
        this.nanoClock = nanoClock;
        this.lastCleanup = nanoClock.getAsLong();
    }

    /** 花掉這個 IP 的一次額度；用完時回傳還要等多久。 */
    public Optional<Duration> acquire(String ip, Action action) {
        cleanupIfDue();
        Map<String, TokenBucket> buckets = action == Action.LOGIN ? loginBuckets : registerBuckets;
        AuthLimitProperties.Bucket limit = action == Action.LOGIN ? properties.loginPerIp() : properties.registerPerIp();
        TokenBucket bucket = buckets.computeIfAbsent(ip, k -> new TokenBucket(limit.burst(), limit.perSecond(), nanoClock));
        if (bucket.tryAcquire()) {
            return Optional.empty();
        }
        return Optional.of(Duration.ofNanos(bucket.nanosUntilAvailable()));
    }

    /** 帳號被鎖住時回傳還要等多久。 */
    public Optional<Duration> lockedFor(String username) {
        Failures f = failures.get(key(username));
        if (f == null) {
            return Optional.empty();
        }
        synchronized (f) {
            long left = f.lockedUntil - nanoClock.getAsLong();
            return left > 0 ? Optional.of(Duration.ofNanos(left)) : Optional.empty();
        }
    }

    public void recordFailure(String username) {
        long now = nanoClock.getAsLong();
        Failures f = failures.computeIfAbsent(key(username), k -> new Failures());
        synchronized (f) {
            if (f.count == 0 || now - f.windowStart > properties.failureWindow().toNanos()) {
                f.count = 0;
                f.windowStart = now;
            }
            f.count++;
            if (f.count >= properties.maxFailures()) {
                f.lockedUntil = now + properties.lockout().toNanos();
                f.count = 0; // 解鎖後重新計算
            }
        }
    }

    /** 登入成功：清掉失敗紀錄。 */
    public void recordSuccess(String username) {
        failures.remove(key(username));
    }

    /** 帳號名稱不分大小寫，避免用大小寫變化繞過鎖定。 */
    private static String key(String username) {
        return username.toLowerCase(Locale.ROOT);
    }

    private void cleanupIfDue() {
        long now = nanoClock.getAsLong();
        if (now - lastCleanup < CLEANUP_INTERVAL_NANOS) {
            return;
        }
        lastCleanup = now;
        loginBuckets.values().removeIf(TokenBucket::isFull);
        registerBuckets.values().removeIf(TokenBucket::isFull);
        long window = properties.failureWindow().toNanos();
        failures.values().removeIf(f -> {
            synchronized (f) {
                return f.lockedUntil <= now && now - f.windowStart > window;
            }
        });
    }

    /** 給測試用：清掉所有紀錄。 */
    void reset() {
        loginBuckets.clear();
        registerBuckets.clear();
        failures.clear();
    }

    /** 給測試確認清理有效。 */
    int trackedEntries() {
        return loginBuckets.size() + registerBuckets.size() + failures.size();
    }
}
